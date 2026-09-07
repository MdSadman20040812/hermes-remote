package com.hermes.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hermes.mobile.core.notify.HermesNotifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry point for Hermes Remote.
 * The Activity stays thin; all routing lives in [HermesApp].
 * Handles hermes://session/<id> deep links (doorbell, notifications) and the
 * "Send to Hermes" share target.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deepLinkBus: DeepLinkBus
    @Inject lateinit var shareBus: ShareBus
    @Inject lateinit var notifier: HermesNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            HermesApp()
        }
    }

    // Turn-complete notifications are suppressed while the app is on screen —
    // announcing something the user is already watching is how a channel that
    // matters gets muted.
    override fun onStart() {
        super.onStart()
        notifier.appInForeground = true
    }

    override fun onStop() {
        notifier.appInForeground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "hermes" && data.host == "session") {
                    val id = data.pathSegments.firstOrNull() ?: return
                    deepLinkBus.offer(id)
                }
            }
            Intent.ACTION_SEND -> {
                // A share carries EITHER a stream or text. Check the stream
                // first: many apps attach a courtesy text/subject alongside a
                // file, and treating that as a prompt would drop the file.
                val stream = intent.streamExtra()
                if (stream != null) {
                    shareBus.offerFiles(stageForUpload(listOf(stream)))
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    if (!text.isNullOrBlank()) shareBus.offer(text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                shareBus.offerFiles(stageForUpload(intent.streamExtras()))
            }
        }
    }

    /**
     * EXTRA_STREAM, without the deprecated untyped getter on API 33+.
     * The typed overload is required on Tiramisu; the old one throws there.
     */
    private fun Intent.streamExtra(): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun Intent.streamExtras(): List<Uri> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    /**
     * Copy shared content into app-private cache and hand back file:// URIs.
     *
     * A share grants read access to the SENDING app's content URI only for the
     * life of this activity's grant. The upload is deliberately deferred until
     * the socket is connected, and by then the grant may be gone - the failure
     * is a SecurityException at read time, long after the user was told the
     * share was accepted. Reading the bytes NOW, while the grant is
     * unambiguously valid, removes the race entirely and costs one copy.
     *
     * Anything unreadable is dropped here with a log rather than surfacing
     * later as a mysterious upload failure.
     */
    private fun stageForUpload(uris: List<Uri>): List<Uri> {
        if (uris.isEmpty()) return emptyList()
        val stagingDir = java.io.File(cacheDir, "shared").apply { mkdirs() }
        // Clear anything older than a day so the cache cannot grow forever.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        stagingDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }

        return uris.mapNotNull { uri ->
            runCatching {
                val name = queryDisplayName(uri) ?: "shared-${System.currentTimeMillis()}"
                val out = java.io.File(stagingDir, "${System.nanoTime()}-$name")
                contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching null
                Uri.fromFile(out)
            }.onFailure {
            }.getOrNull()
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
}
