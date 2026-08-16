package com.hermes.mobile.ui.files

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.data.repo.RemoteEntry
import com.hermes.mobile.data.repo.RemoteFileContent

/** PC file browser (Phase 4) — your whole PC, from the couch. */
@Composable
fun FilesScreen(
    vm: FilesViewModel = hiltViewModel(),
) {
    val listing by vm.listing.collectAsState()
    val loading by vm.loading.collectAsState()
    val preview by vm.preview.collectAsState()

    LaunchedEffect(Unit) { if (listing == null) vm.browse(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PC Files", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(20.dp))
        }
        listing?.let { l ->
            Text(
                l.path.ifBlank { "managed roots" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (l.parent != null) {
                TextButton(
                    onClick = { vm.up() },
                    modifier = Modifier.semantics { contentDescription = "Go up one directory" },
                ) { Text("↑ up") }
            }
        }
        Spacer(Modifier.size(8.dp))

        val entries = listing?.entries ?: emptyList()
        if (entries.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { it.path }) { entry ->
                    EntryRow(entry, onClick = { vm.open(entry) })
                }
            }
        }
    }

    preview?.let { FilePreviewDialog(it, onClose = { vm.closePreview() }) }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${if (entry.isDirectory) "Folder" else "File"} ${entry.name}" },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (entry.isDirectory) "📁" else "📄")
            Spacer(Modifier.size(10.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            entry.size?.let {
                Text(
                    formatSize(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FilePreviewDialog(file: RemoteFileContent, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    "${file.mimeType} · ${formatSize(file.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))

                val bytes = remember(file.dataUrl) {
                    runCatching {
                        Base64.decode(file.dataUrl.substringAfter("base64,"), Base64.DEFAULT)
                    }.getOrNull()
                }
                when {
                    file.mimeType.startsWith("image/") && bytes != null -> {
                        val bmp = remember(bytes) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        bmp?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Preview of ${file.name}",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    file.mimeType.startsWith("text/") || file.mimeType.contains("json") -> {
                        Text(
                            bytes?.toString(Charsets.UTF_8)?.take(8000) ?: "(unreadable)",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    else -> Text(
                        "No inline preview for ${file.mimeType}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.End)
                        .semantics { contentDescription = "Close preview" },
                ) { Text("Close") }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
