package com.hermes.mobile.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * The one thing that talks to Hermes. Repositories see only this facade —
 * nothing above the transport layer knows the wire format (spec §C.2).
 *
 * Owns one [RpcChannel] (the `/api/ws` spine) and one [RestClient].
 * Credential handling lives in [com.hermes.mobile.core.connection.CredentialStrategy];
 * reconnect orchestration in ConnectionManager.
 */
class HermesClient(
    httpClient: OkHttpClient,
    scope: CoroutineScope,
) {
    val rpc = RpcChannel(httpClient, scope)
    val rest = RestClient(httpClient)

    // ---- typed convenience wrappers over the probed method surface ----

    suspend fun serverStatus(baseUrl: String): ServerStatus = rest.getStatus(baseUrl)

    suspend fun sessionList(): JsonElement? = rpc.call("session.list")

    suspend fun sessionCreate(title: String = ""): JsonElement? =
        rpc.call("session.create", rpcParamsOf("title" to title))

    suspend fun promptSubmit(sessionId: String, text: String): JsonElement? =
        rpc.call("prompt.submit", rpcParamsOf("session_id" to sessionId, "text" to text))

    // ---- attachments -------------------------------------------------------
    // These stage a file against the session BEFORE prompt.submit; the server
    // drains `attached_images` into the turn it starts next. Order matters:
    // attach first, submit second, or the file arrives one turn late.

    /**
     * Stage an image from raw bytes. `image.attach_bytes` is the remote-client
     * path (`image.attach` needs a gateway-visible filesystem path, which a
     * phone never has). Caps at 25 MB server-side.
     */
    suspend fun imageAttachBytes(
        sessionId: String,
        base64: String,
        filename: String,
    ): JsonElement? = rpc.call(
        "image.attach_bytes",
        rpcParamsOf(
            "session_id" to sessionId,
            "content_base64" to base64,
            "filename" to filename,
        ),
        timeout = 120.seconds,
    )

    /**
     * Stage a PDF; the server renders its pages to PNGs and queues those, so
     * the model actually sees the document instead of a path it cannot open.
     * Requires `pdftoppm` on the PC — a 5028 means poppler is missing, which
     * the caller reports rather than silently dropping the file.
     */
    suspend fun pdfAttach(
        sessionId: String,
        base64: String,
        filename: String,
    ): JsonElement? = rpc.call(
        "pdf.attach",
        rpcParamsOf(
            "session_id" to sessionId,
            "content_base64" to base64,
            "filename" to filename,
        ),
        timeout = 180.seconds,
    )

    /**
     * Stage any other file into the session workspace. Answers a `ref_text`
     * (`@file:…`) that belongs in the prompt so the agent can open it.
     */
    suspend fun fileAttach(
        sessionId: String,
        dataUrl: String,
        name: String,
    ): JsonElement? = rpc.call(
        "file.attach",
        rpcParamsOf("session_id" to sessionId, "data_url" to dataUrl, "name" to name),
        timeout = 180.seconds,
    )

    // ---- autonomy ----------------------------------------------------------

    /** Current effective approvals mode: manual | smart | off. */
    suspend fun approvalModeGet(): JsonElement? =
        rpc.call("config.get", rpcParamsOf("key" to "approvals.mode"))

    /** Persist the approvals mode for every session on the PC. */
    suspend fun approvalModeSet(mode: String): JsonElement? =
        rpc.call("config.set", rpcParamsOf("key" to "approvals.mode", "value" to mode))

    /**
     * Session-scoped autonomy toggle (the desktop's Shift+Tab). Scoped to the
     * session on purpose: flipping the global mode from a phone would silently
     * disarm approvals for work running on the desktop too.
     */
    suspend fun sessionYolo(sessionId: String, enabled: Boolean): JsonElement? =
        rpc.call(
            "config.set",
            rpcParamsOf(
                "key" to "yolo",
                "value" to if (enabled) "on" else "off",
                "scope" to "session",
                "session_id" to sessionId,
            ),
        )

    /**
     * Fire a prompt that keeps running when the app is backgrounded. The turn
     * still streams to any attached socket; the difference is the server does
     * not treat a dropped client as a reason to stop.
     */
    suspend fun promptBackground(sessionId: String, text: String): JsonElement? =
        rpc.call("prompt.background", rpcParamsOf("session_id" to sessionId, "text" to text))

    suspend fun sessionInterrupt(sessionId: String): JsonElement? =
        rpc.call("session.interrupt", rpcParamsOf("session_id" to sessionId))

    suspend fun sessionSteer(sessionId: String, text: String): JsonElement? =
        rpc.call("session.steer", rpcParamsOf("session_id" to sessionId, "text" to text))

    suspend fun approvalRespond(sessionId: String, choice: String, all: Boolean = false): JsonElement? =
        rpc.call(
            "approval.respond",
            rpcParamsOf("session_id" to sessionId, "choice" to choice, "all" to all),
        )

    /** Approvals raised while the app was away — replayed on attach. */
    suspend fun approvalPending(sessionId: String): JsonElement? =
        rpc.call("approval.pending", rpcParamsOf("session_id" to sessionId))

    suspend fun sessionHistory(sessionId: String): JsonElement? =
        rpc.call("session.history", rpcParamsOf("session_id" to sessionId), timeout = 60.seconds)

    suspend fun sessionResume(storedSessionId: String): JsonElement? =
        rpc.call("session.resume", rpcParamsOf("session_id" to storedSessionId))

    suspend fun sessionActivate(sessionId: String): JsonElement? =
        rpc.call("session.activate", rpcParamsOf("session_id" to sessionId))

    /** Sessions currently live in the gateway — the desktop's open tabs. */
    suspend fun sessionActiveList(): JsonElement? = rpc.call("session.active_list")

    /** Point the session at a different working directory (repo switching). */
    suspend fun sessionSetCwd(sessionId: String, cwd: String): JsonElement? =
        rpc.call("session.cwd.set", rpcParamsOf("session_id" to sessionId, "cwd" to cwd))

    /** Liveness ping used to distinguish a wedged socket from a quiet one. */
    suspend fun ping(): JsonElement? = rpc.call("ping", timeout = 10.seconds)
}
