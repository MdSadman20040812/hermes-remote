package com.hermes.mobile.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hermes.mobile.domain.model.AttachmentKind
import com.hermes.mobile.domain.model.ChatAttachment

/**
 * Files inside the conversation.
 *
 * The Files tab already moved bytes in both directions, but everything had to
 * leave the conversation to be seen — which is backwards, because the file is
 * usually the subject of the sentence next to it. These render in place:
 * images inline, video with real playback, everything else as a typed chip that
 * still opens and still saves.
 *
 * Rendering is driven by MIME with an extension fallback, never by extension
 * alone: files arriving from another app's share sheet routinely have a correct
 * MIME and a meaningless name.
 */

private fun ChatAttachment.displaySize(): String = when {
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
    sizeBytes < 1024L * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} MB"
    else -> String.format("%.1f GB", sizeBytes / (1024.0 * 1024 * 1024))
}

/**
 * Row of attachments under a message. Images get tiles; others stack as chips.
 *
 * [onNeedsFetch] is how a file the PC produced becomes visible. A remote path
 * like `D:/out/chart.png` is not something Coil or ExoPlayer can open, so a
 * visual attachment that has no local copy asks for one exactly once, on first
 * composition. Documents are NOT auto-fetched: pulling a 40 MB PDF over a
 * phone connection because it scrolled into view is rude, and a chip with a
 * name and a download button is already a complete answer.
 */
@Composable
fun AttachmentGroupView(
    attachments: List<ChatAttachment>,
    modifier: Modifier = Modifier,
    onOpen: (ChatAttachment) -> Unit = {},
    onSave: (ChatAttachment) -> Unit = {},
    onNeedsFetch: (ChatAttachment) -> Unit = {},
) {
    if (attachments.isEmpty()) return
    val visual = attachments.filter {
        it.kind == AttachmentKind.IMAGE || it.kind == AttachmentKind.VIDEO
    }
    val rest = attachments - visual.toSet()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visual.forEach { att ->
            // Fire once per attachment identity, not per recomposition: keying
            // on the id means a scroll or a theme change does not re-download.
            LaunchedEffect(att.id, att.cachedUri, att.localUri) {
                if (att.displayUri() == null && att.remotePath != null &&
                    !att.downloading && att.error == null
                ) {
                    onNeedsFetch(att)
                }
            }
            MediaTile(att, onOpen = onOpen, onSave = onSave)
        }
        rest.forEach { FileChip(it, onOpen = onOpen, onSave = onSave) }
    }
}

/** An image or a video, rendered at a sane height with a tap-to-expand target. */
@Composable
private fun MediaTile(
    att: ChatAttachment,
    onOpen: (ChatAttachment) -> Unit,
    onSave: (ChatAttachment) -> Unit,
) {
    var fullscreen by remember(att.id) { mutableStateOf(false) }
    val uri = att.displayUri()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
    ) {
        Box(Modifier.clickable(enabled = uri != null) { fullscreen = true }) {
            when {
                // Three distinct waits, named honestly: bytes going up, bytes
                // coming down, and "the file exists but we have not asked for
                // it yet". A single "Loading…" for all three is how a failed
                // download looks identical to a slow one.
                att.uploading || att.downloading || uri == null -> Column(Modifier.padding(14.dp)) {
                    WaitingState(
                        phase = when {
                            att.uploading -> "Sending ${att.name}"
                            att.downloading -> "Getting ${att.name} from your PC"
                            else -> "Preparing ${att.name}"
                        },
                        compact = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (att.uploading) GradientProgress(att.progress)
                }

                att.kind == AttachmentKind.IMAGE -> AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(uri).crossfade(true).build(),
                    contentDescription = att.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                )

                else -> Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow, "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(10.dp).size(26.dp),
                        )
                    }
                }
            }

            att.error?.let {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp),
                ) {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }

    if (fullscreen && uri != null) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    if (att.kind == AttachmentKind.VIDEO) {
                        VideoPlayer(uri, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = uri,
                            contentDescription = att.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Row(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    ) {
                        IconButton({ onSave(att) }) {
                            Icon(Icons.Default.Download, "Save", tint = Color.White)
                        }
                        IconButton({ fullscreen = false }) {
                            Icon(Icons.Default.Close, "Close", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * ExoPlayer in a Compose slot.
 *
 * The player is released in onDispose — a leaked ExoPlayer keeps a wake lock
 * and an audio focus grant, which is audible to the user as another app's
 * music refusing to resume.
 */
@Composable
private fun VideoPlayer(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(uri) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
    )
}

/** Any non-visual file: typed icon, name, size, and the two actions that matter. */
@Composable
private fun FileChip(
    att: ChatAttachment,
    onOpen: (ChatAttachment) -> Unit,
    onSave: (ChatAttachment) -> Unit,
) {
    val icon = when (att.kind) {
        AttachmentKind.PDF -> Icons.Default.PictureAsPdf
        AttachmentKind.AUDIO -> Icons.Default.AudioFile
        AttachmentKind.TEXT -> Icons.Default.Article
        AttachmentKind.ARCHIVE -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(att) },
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    att.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (att.uploading) {
                    Spacer(Modifier.height(4.dp))
                    GradientProgress(att.progress, height = 4.dp)
                } else if (att.downloading) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Getting it from your PC…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        att.error
                            // A size of zero means "on the PC, not measured
                            // yet" — printing "0 B" next to a real file is a
                            // lie the user will read as corruption.
                            ?: if (att.sizeBytes > 0) att.displaySize()
                            else if (att.remotePath != null) "on your PC · tap to open"
                            else "ready to send",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (att.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton({ onSave(att) }) {
                Icon(
                    Icons.Default.Download, "Save",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Pending attachments, above the composer, before the message is sent.
 *
 * Horizontally scrollable: three photos and a PDF used to overflow the row and
 * silently clip the last chip, which made a file look un-attached right up
 * until it failed to arrive. Each chip carries its own progress and its own
 * error, because "one of these four failed" is only actionable if you can see
 * WHICH one.
 */
@Composable
fun ComposerAttachmentStrip(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { att ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (att.error != null) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (att.error != null) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
            ) {
                Column(Modifier.widthIn(max = 190.dp)) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A thumbnail answers "did I pick the right photo?"
                        // instantly; a filename from the gallery ("IMG_0421")
                        // does not.
                        if (att.kind == AttachmentKind.IMAGE && att.displayUri() != null) {
                            AsyncImage(
                                model = att.displayUri(),
                                contentDescription = att.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(
                                att.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 130.dp),
                            )
                            att.error?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton({ onRemove(att) }, Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close, "Remove ${att.name}",
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    if (att.uploading) {
                        GradientProgress(
                            att.progress,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            height = 3.dp,
                        )
                    }
                }
            }
        }
    }
}
