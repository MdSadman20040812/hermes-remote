package com.hermes.mobile.data.repo

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.domain.model.AttachmentKind
import com.hermes.mobile.domain.model.ChatAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What staging one attachment produced.
 *
 * [promptSuffix] is text that must ride along with the user's message for the
 * agent to be able to open the file — images and PDFs are queued into the
 * turn's own image list by the server and need nothing, but a generic file is
 * staged in the workspace and is only reachable through its `@file:` ref.
 */
data class StagedAttachment(
    val attachment: ChatAttachment,
    val promptSuffix: String = "",
)

/**
 * Sends the files the user picked, as part of the message they typed.
 *
 * This is the half that was missing. The composer collected URIs into
 * `pendingAttachments` and `prompt.submit` was called with text only, so an
 * image picked next to a sentence was silently dropped — the send looked like
 * it worked, the agent simply never saw the file.
 *
 * The gateway's contract is two-step and order-sensitive:
 *
 *   1. `image.attach_bytes` / `pdf.attach` / `file.attach` stage the payload
 *      against the LIVE session id, appending to `session["attached_images"]`.
 *   2. The next `prompt.submit` drains that list into the turn it starts.
 *
 * So every attachment must land BEFORE the submit, or it arrives one turn late
 * — attached to the *next* thing the user says, which is worse than losing it.
 * [stageAll] is therefore sequential and the caller awaits it before sending.
 *
 * Routing is by kind, not by extension, and each route exists for a reason:
 *  - images go as bytes, because the phone has no gateway-visible path;
 *  - PDFs go to `pdf.attach`, which rasterises pages the model can actually
 *    look at (a PDF queued as an opaque file is a path the model can only
 *    guess about);
 *  - everything else is staged in the workspace and referenced, because
 *    inlining a 40 MB archive into the conversation would be pointless.
 */
@Singleton
class AttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Stage every pending attachment, reporting progress per item.
     *
     * Failures are returned on the attachment itself rather than thrown: one
     * unreadable file must not cancel a message that also carries three good
     * ones, and the user needs to see WHICH file failed.
     */
    suspend fun stageAll(
        client: HermesClient,
        sessionId: String,
        attachments: List<ChatAttachment>,
        onProgress: (ChatAttachment) -> Unit = {},
    ): List<StagedAttachment> {
        val out = mutableListOf<StagedAttachment>()
        for (att in attachments) {
            onProgress(att.copy(uploading = true, progress = 0.05f))
            out += runCatching { stageOne(client, sessionId, att, onProgress) }
                .getOrElse { e ->
                    val failed = att.copy(
                        uploading = false,
                        error = e.message?.take(160) ?: "couldn't send this file",
                    )
                    onProgress(failed)
                    StagedAttachment(failed)
                }
        }
        return out
    }

    private suspend fun stageOne(
        client: HermesClient,
        sessionId: String,
        att: ChatAttachment,
        onProgress: (ChatAttachment) -> Unit,
    ): StagedAttachment = withContext(Dispatchers.IO) {
        val uri = att.localUri?.let(Uri::parse)
            ?: error("${att.name} has no local copy to send")

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("couldn't read ${att.name} from that app")

        // Refuse up front with the real limit rather than streaming 30 MB to
        // collect a 4018. The caps are the server's own, not invented here.
        val cap = when (att.kind) {
            AttachmentKind.IMAGE -> IMAGE_MAX_BYTES
            AttachmentKind.PDF -> PDF_MAX_BYTES
            else -> FILE_MAX_BYTES
        }
        if (bytes.size > cap) {
            error("${att.name} is ${human(bytes.size.toLong())}; the limit is ${human(cap)}")
        }

        onProgress(att.copy(uploading = true, progress = 0.55f))
        // NO_WRAP: base64 with newlines is invalid inside a JSON string value.
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val staged = when (att.kind) {
            AttachmentKind.IMAGE -> {
                val res = client.imageAttachBytes(sessionId, b64, att.name).obj()
                StagedAttachment(
                    att.copy(
                        uploading = false,
                        progress = 1f,
                        sizeBytes = bytes.size.toLong(),
                        remotePath = res.str("path"),
                    ),
                )
            }

            AttachmentKind.PDF -> {
                val res = client.pdfAttach(sessionId, b64, att.name).obj()
                val pages = res.int("pages_attached") ?: 0
                StagedAttachment(
                    att.copy(
                        uploading = false,
                        progress = 1f,
                        sizeBytes = bytes.size.toLong(),
                        remotePath = res.objects("pages").firstOrNull().str("path"),
                    ),
                    // The server already rasterised the pages into the turn, so
                    // the suffix is context, not a pointer: it tells the model
                    // how many page images belong to this document.
                    promptSuffix = if (pages > 0) {
                        "[Attached PDF: ${att.name} — $pages page(s) queued as images]"
                    } else {
                        ""
                    },
                )
            }

            else -> {
                val dataUrl = "data:${att.mimeType};base64,$b64"
                val res = client.fileAttach(sessionId, dataUrl, att.name).obj()
                val ref = res.str("ref_text")
                    ?: res.str("ref_path")?.let { "@file:$it" }
                    ?: ""
                StagedAttachment(
                    att.copy(
                        uploading = false,
                        progress = 1f,
                        sizeBytes = bytes.size.toLong(),
                        remotePath = res.str("path"),
                    ),
                    promptSuffix = ref,
                )
            }
        }
        onProgress(staged.attachment)
        staged
    }

    companion object {
        /** Mirrors `_ATTACH_BYTES_MAX_BYTES` in tui_gateway/prompt_attachments.py. */
        const val IMAGE_MAX_BYTES = 25L * 1024 * 1024

        /** Mirrors `_PDF_ATTACH_MAX_BYTES`. */
        const val PDF_MAX_BYTES = 50L * 1024 * 1024

        /**
         * `file.attach` has no explicit cap, so this is the phone's own: the
         * payload is base64'd in RAM, and a ceiling that fails fast beats an
         * OOM kill halfway through a send.
         */
        const val FILE_MAX_BYTES = 64L * 1024 * 1024

        fun human(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
