package com.hermes.mobile.core.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Literal strings CAPTURED from the live gateway (0.21.1), not invented.
 *
 * `pc/mobile_contract_probe.py` staged a real image and read the persisted
 * turn back; this is byte-for-byte what `session.history` returned. Pinning
 * the real shape is the difference between "the parser works on my examples"
 * and "the parser works on what the server actually sends" — the whole class
 * of bug being fixed here was the second kind.
 *
 * Note the BACKSLASHES: on Windows the gateway persists native paths, so a
 * parser tested only against forward slashes would silently render nothing.
 */
class LiveGatewayRefsTest {

    /** Exactly the user turn the probe read back from session.history. */
    private val LIVE_USER_TURN =
        "Reply with exactly: CONTRACT_OK\n@image:D:\\.hermes\\images\\upload_20260913_134837_1.png"

    @Test
    fun `parses the windows backslash path the gateway persists`() {
        val refs = AttachmentRefs.scan(LIVE_USER_TURN)
        assertEquals(1, refs.size)
        assertEquals("D:\\.hermes\\images\\upload_20260913_134837_1.png", refs.first().path)
    }

    @Test
    fun `renders the attachment with a clean filename`() {
        val att = AttachmentRefs.attachmentsIn(LIVE_USER_TURN, "h-user-0").single()
        assertEquals("upload_20260913_134837_1.png", att.name)
        assertEquals("image/png", att.mimeType)
        assertEquals("D:\\.hermes\\images\\upload_20260913_134837_1.png", att.remotePath)
    }

    @Test
    fun `the prose survives without the machine marker`() {
        // What the user should read is their own sentence — not a path that
        // wraps across three lines of a phone screen.
        assertEquals("Reply with exactly: CONTRACT_OK", AttachmentRefs.strip(LIVE_USER_TURN))
    }

    @Test
    fun `parses the file_attach ref_text the gateway returns`() {
        // Captured from file.attach: ref=@file:D:\.hermes\attachments\probe.txt
        val ref = """@file:D:\.hermes\attachments\probe.txt"""
        val att = AttachmentRefs.attachmentsIn(ref, "k").single()
        assertEquals("probe.txt", att.name)
        assertEquals("text/plain", att.mimeType)
    }

    @Test
    fun `a turn with several attached images yields one card each`() {
        val turn = "here they are\n" +
            "@image:D:\\.hermes\\images\\upload_1.png\n" +
            "@image:D:\\.hermes\\images\\upload_2.png"
        val atts = AttachmentRefs.attachmentsIn(turn, "k")
        assertEquals(2, atts.size)
        assertTrue(atts.all { it.mimeType == "image/png" })
        assertEquals("here they are", AttachmentRefs.strip(turn))
    }
}
