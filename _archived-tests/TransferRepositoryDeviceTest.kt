package com.hermes.mobile.data.repo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermes.mobile.core.connection.ConnState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real device, real network, real server.
 *
 * The UI path for picking a file goes through the Android system document
 * picker, which cannot be driven reliably from adb (its Recents view does not
 * index a shell-pushed file, and adb cannot forge a URI grant). That makes the
 * picker a poor place to prove the TRANSFER works. This exercises the same
 * repository the UI calls, on the phone, against the live dashboard - so a pass
 * means bytes genuinely moved between these two machines.
 *
 * Run:
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class TransferRepositoryDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Built directly rather than pulled out of Hilt.
     *
     * An @EntryPoint needs the Hilt processor to run over the ANDROID TEST
     * source set, which this module does not configure; without it the access
     * fails as an unhelpful ClassCastException. TransferRepository's only
     * dependencies are a Context and the app's live ConnectionManager, and the
     * latter is a @Singleton reachable from the running application - so the
     * object under test is wired to the same real, connected socket the UI uses.
     */
    /**
     * Wait for the socket before touching the network.
     *
     * The instrumentation process starts the app fresh: autoConnect races
     * profiles, mints a ticket and opens the WS, which takes a second or two.
     * Without this the first test asserts against "Not connected" and the
     * failure says nothing about whether transfers work.
     */
    @Before
    fun waitForConnection() = runBlocking {
        val app = context.applicationContext as com.hermes.mobile.HermesApplication
        // autoConnect normally runs from ShellViewModel's init, and no UI is
        // started here - so the instrumentation process would wait forever for
        // a connection nothing ever initiated.
        app.connectionManager.autoConnect()
        withTimeout(60_000) {
            app.connectionManager.state.first { it is ConnState.Connected }
        }
        Unit
    }

    private fun repo(): TransferRepository {
        val app = context.applicationContext as com.hermes.mobile.HermesApplication
        return TransferRepository(app, app.connectionManager)
    }

    @Test
    fun uploads_a_file_from_this_phone_to_the_pc() = runBlocking {
        val payload = "uploaded from the phone at ${System.currentTimeMillis()}\n"
        val local = File(context.cacheDir, "device-upload-test.txt")
        local.writeText(payload)

        val result = repo().uploadToPc(Uri.fromFile(local), overwrite = true)

        assertEquals("device-upload-test.txt", result.name)
        assertEquals(payload.toByteArray().size.toLong(), result.size)
        assertTrue(
            "expected the inbox path, got ${result.remotePath}",
            result.remotePath.contains("HermesInbox"),
        )
    }

    @Test
    fun saves_a_pc_file_into_phone_downloads() = runBlocking {
        // Round trip: push a known file up, then pull that same file down.
        val payload = "round trip ${System.currentTimeMillis()}\n"
        val local = File(context.cacheDir, "device-roundtrip.txt")
        local.writeText(payload)
        val up = repo().uploadToPc(Uri.fromFile(local), overwrite = true)

        val saved = repo().saveToPhone(up.remotePath)

        val landed = File(saved.localPath)
        assertTrue("file not on phone: ${saved.localPath}", landed.exists())
        assertEquals(payload, landed.readText())
    }

    @Test
    fun refuses_a_file_larger_than_the_ceiling() = runBlocking {
        val big = File(context.cacheDir, "too-big.bin")
        big.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(50) { out.write(chunk) } // 50 MB > 48 MB ceiling
        }
        val error = runCatching { repo().uploadToPc(Uri.fromFile(big)) }.exceptionOrNull()
        big.delete()
        assertTrue("expected a refusal, got $error", error != null)
        assertTrue(
            "the message should say why: ${error?.message}",
            error?.message?.contains("limit") == true,
        )
    }
}

