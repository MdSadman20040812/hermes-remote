package com.hermes.mobile.ui.components

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.mobile.domain.model.ArtifactState

/**
 * Renders a compiled TSX/JSX artifact.
 *
 * The agent answers with a component; the PC compiles it (esbuild, ~300 ms) and
 * hands back a URL. This shows the result inline in the transcript, expandable
 * to full screen.
 *
 * WHY A WEBVIEW AND NOT NATIVE COMPOSE
 *   The artifact is React. Reimplementing a React runtime in Compose is not a
 *   rendering problem, it is a language problem. A WebView runs the real thing.
 *
 * SANDBOX
 *   The artifact is agent-authored code, so it is treated as untrusted:
 *   file access off, no navigation away from the artifact origin, no window
 *   opening, JS enabled only because a React component cannot run without it.
 *   Nothing about the dashboard session is reachable from inside — the page is
 *   served by the artifact server, which holds no cookies.
 */

private const val TAG_MAX_LOG = 4

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactWebView(
    url: String,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    onReady: () -> Unit = {},
) {
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(bg)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // Untrusted content: never let it reach the filesystem.
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    // Artifacts are authored for the phone; do not let a stray
                    // viewport meta shrink everything to desktop scale.
                    loadWithOverviewMode = false
                    useWideViewPort = false
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            onError(m.message())
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        req: WebResourceRequest,
                    ): Boolean = true   // artifacts never navigate

                    override fun onPageFinished(view: WebView, u: String) = onReady()
                }
                addJavascriptInterface(object {
                    @JavascriptInterface fun onError(msg: String) = onError(msg)
                    @JavascriptInterface fun onReady() = onReady()
                }, "HermesBridge")
                loadUrl(url)
            }
        },
        update = { it.loadUrl(url) },
    )
}

/**
 * Inline artifact card for the transcript.
 *
 * Collapsed by default when the artifact is tall: a component that eats the
 * whole scrollback makes the conversation unreadable. Expanding is one tap,
 * and full-screen is one more.
 */
@Composable
fun ArtifactCard(
    state: ArtifactState,
    modifier: Modifier = Modifier,
    onRecompile: () -> Unit = {},
) {
    var expanded by remember(state.id) { mutableStateOf(true) }
    var fullscreen by remember(state.id) { mutableStateOf(false) }
    var runtimeError by remember(state.id) { mutableStateOf<String?>(null) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chev")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
            ),
        ),
    ) {
        Column {
            // ---- header -----------------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(4.dp),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title.ifBlank { "Component" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            state.compiling -> "compiling…"
                            state.error != null -> "failed to compile"
                            state.compileMs != null -> "${state.lang.uppercase()} · ${state.compileMs} ms"
                            else -> state.lang.uppercase()
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.url != null) {
                    IconButton({ fullscreen = true }) {
                        Icon(
                            Icons.Default.OpenInFull, "Full screen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Icon(
                    Icons.Default.ExpandMore,
                    if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevron),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 420.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                ) {
                    when {
                        state.compiling -> Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            WaitingState(
                                phase = "Compiling on your PC",
                                detail = "esbuild is transforming ${state.lang.uppercase()} → JS",
                            )
                            ShimmerParagraph(lines = 3)
                        }

                        state.error != null -> ArtifactError(state.error, onRecompile)

                        state.url != null -> {
                            ArtifactWebView(
                                url = state.url,
                                modifier = Modifier.fillMaxSize(),
                                onError = { runtimeError = it },
                            )
                            runtimeError?.let {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        it.take(300),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (fullscreen && state.url != null) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.title.ifBlank { "Component" },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        IconButton(onRecompile) {
                            Icon(
                                Icons.Default.Refresh, "Recompile",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton({ fullscreen = false }) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                    ArtifactWebView(state.url, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ArtifactError(error: String, onRetry: () -> Unit) {
    Column(Modifier.padding(14.dp)) {
        Text(
            "This component did not compile",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                error.lines().take(TAG_MAX_LOG).joinToString("\n"),
                style = com.hermes.mobile.ui.theme.HermesCodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onRetry) { Text("Try again") }
    }
}
