package com.hermes.mobile.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors pty-resume-sanitizer.test.ts behavior on the Kotlin port. */
class PtySanitizerTest {

    @Test
    fun `collapses blank-line bursts to one blank row`() {
        val s = PtySanitizer()
        val burst = "\r\n".repeat(120)
        val out = s.next("hello" + burst + "world\r\n") + s.flush()
        assertTrue(out.contains("hello"))
        assertTrue(out.contains("world"))
        assertFalse(out.contains("\r\n\r\n\r\n"))
    }

    @Test
    fun `strips erase codes during resume window, keeps them after`() {
        val s = PtySanitizer()
        val during = s.next("abc\u001b[Kdef")
        s.endEraseSuppression()
        val after = s.next("abc\u001b[Kdef")
        assertFalse(during.contains("[K"))
        assertTrue(after.contains("[K"))
    }

    @Test
    fun `holds back partial escape split across frames`() {
        val s = PtySanitizer()
        val first = s.next("text\u001b")
        assertEquals("text", first)
        val second = s.next("[2Kmore")
        assertFalse(second.contains("\u001b"))
        assertTrue(second.contains("more"))
    }

    @Test
    fun `flush drops a dangling partial escape`() {
        val s = PtySanitizer()
        s.next("data\u001b")
        assertEquals("", s.flush())
    }
}
