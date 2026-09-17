package com.hermes.mobile.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.repo.TransferRepository
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes

/**
 * The live transfer card.
 *
 * A file crossing the network is the one moment in this panel where the user
 * is waiting on something they cannot see, so it gets a real progress bar, the
 * actual filename, and the direction stated in words. An indeterminate spinner
 * would say "something is happening"; this says what, which way, and how far.
 */
@Composable
fun TransferCard(state: TransferState?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val current = state ?: return@AnimatedVisibility
        val progress by animateFloatAsState(
            targetValue = current.fraction,
            animationSpec = tween(durationMillis = 220),
            label = "transferProgress",
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (current.toPc) Icons.Outlined.Upload else Icons.Outlined.Download,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (current.toPc) "Sending to your PC" else "Saving to this phone",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (current.totalBytes > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            TransferRepository.humanSize(current.totalBytes),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = HermesMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Determinate whenever the size is known; a known-length transfer
                // shown as an endless spinner is a small lie about progress.
                if (current.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
    }
}

/**
 * What just happened, and where it went.
 *
 * A snackbar disappears in four seconds and takes the destination path with
 * it. On a phone, "where did my file actually go?" is the question people ask
 * right after the transfer, so the answer stays on screen until the next one
 * replaces it.
 */
@Composable
fun TransferReceipt(
    receipt: TransferReceipt?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = receipt != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val current = receipt ?: return@AnimatedVisibility
        val semantics = MaterialTheme.hermes
        val failed = current.error != null
        Surface(
            color = if (failed) MaterialTheme.colorScheme.errorContainer
            else semantics.successContainer,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer
            else semantics.onSuccessContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = current.error ?: "${current.name} ${current.where}"
                },
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (failed) "Couldn't transfer ${current.name}" else current.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.error ?: current.where,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = if (failed) null else HermesMono,
                    )
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

/** Result of the last transfer, kept on screen so the destination is readable. */
data class TransferReceipt(
    val name: String,
    /** Human phrasing of the destination, e.g. "Saved to Downloads". */
    val where: String,
    val error: String? = null,
)
