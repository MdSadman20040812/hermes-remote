package com.hermes.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry point for Hermes Remote.
 * The Activity stays thin; all routing lives in [HermesApp].
 * Handles hermes://session/<id> deep links (Telegram doorbell, notifications).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deepLinkBus: DeepLinkBus
    @Inject lateinit var shareBus: ShareBus

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            HermesApp()
        }
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
                // "Send to Hermes" share target — shared text/URL becomes a prompt.
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (!text.isNullOrBlank()) shareBus.offer(text)
            }
        }
    }
}
