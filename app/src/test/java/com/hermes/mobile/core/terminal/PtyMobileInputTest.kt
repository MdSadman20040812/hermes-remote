package com.hermes.mobile.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors pty-mobile-input.test.ts behavior on the Kotlin port. */
class PtyMobileInputTest {

    @Test
    fun `plain typing appends to the tracked line`() {
        assertEquals("git st", updatePtyInputLine("git ", "st"))
        assertEquals("", updatePtyInputLine("abc", "\r"))
        assertEquals("ab", updatePtyInputLine("abc", "\u007F"))
        assertEquals("", updatePtyInputLine("abc", "\u0015"))
    }

    @Test
    fun `escape sequences reset the tracker`() {
        assertEquals("", updatePtyInputLine("abc", "\u001b[D"))
    }

    @Test
    fun `ime full-line replacement is rewritten as delete plus retype`() {
        // User typed "git stat", IME replaced with "git status" as one event.
        val r = normalizePtyMobileInput("git status", "git stat", replacementActive = true)
        assertTrue(r.normalized)
        assertEquals("git status", r.nextLine)
        assertTrue(r.data.startsWith("\u007F".repeat("git stat".length)))
        assertTrue(r.data.endsWith("git status"))
    }

    @Test
    fun `gboard duplicated final word collapses`() {
        val r = normalizePtyMobileInput("hello world world", "hello world", replacementActive = true)
        assertTrue(r.normalized)
        assertEquals("hello world", r.nextLine)
    }

    @Test
    fun `unrelated incoming text is not rewritten`() {
        val r = normalizePtyMobileInput("totally different", "git st", replacementActive = true)
        assertFalse(r.normalized)
    }

    @Test
    fun `no normalization outside the replacement window`() {
        val r = normalizePtyMobileInput("git status", "git stat", replacementActive = false)
        assertFalse(r.normalized)
        assertEquals("git statgit status", r.nextLine)
    }
}
