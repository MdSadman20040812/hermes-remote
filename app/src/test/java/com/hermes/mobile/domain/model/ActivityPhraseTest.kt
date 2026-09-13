package com.hermes.mobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat must never show a raw tool name.
 *
 * Moving tool calls out of the transcript is only an improvement if what
 * replaces them is readable. A status line that says `mcp__execute_code` is
 * the same failure as the wall of commands it replaced, just shorter.
 */
class ActivityPhraseTest {

    @Test
    fun `common tools get verb-first phrases`() {
        assertEquals("Running a command", activityPhraseFor("terminal"))
        assertEquals("Reading files", activityPhraseFor("mcp__read_file"))
        assertEquals("Editing code", activityPhraseFor("patch"))
        assertEquals("Searching the web", activityPhraseFor("web_search"))
        assertEquals("Delegating to subagents", activityPhraseFor("mcp__delegate_task"))
    }

    @Test
    fun `an unknown tool is humanised, never shown raw`() {
        val phrase = activityPhraseFor("mcp__some_new_tool")
        assertFalse("underscores leaked into the UI", phrase.contains("_"))
        assertFalse("mcp prefix leaked into the UI", phrase.startsWith("mcp"))
        assertEquals("Some new tool", phrase)
    }

    @Test
    fun `every phrase reads as a sentence start`() {
        listOf("terminal", "write_file", "web_extract", "totally_unknown_thing").forEach {
            val p = activityPhraseFor(it)
            assertTrue("'$p' should be capitalised", p.first().isUpperCase())
            assertTrue("'$p' should not be empty", p.isNotBlank())
        }
    }
}

/** Routing decides which server RPC a file takes; a wrong guess loses the file. */
class AttachmentKindTest {

    private fun att(name: String, mime: String) =
        ChatAttachment(id = "x", name = name, mimeType = mime, sizeBytes = 1)

    @Test
    fun `mime decides the kind`() {
        assertEquals(AttachmentKind.IMAGE, att("a.bin", "image/png").kind)
        assertEquals(AttachmentKind.PDF, att("a.bin", "application/pdf").kind)
        assertEquals(AttachmentKind.VIDEO, att("a.bin", "video/mp4").kind)
    }

    @Test
    fun `extension fallback catches providers that report no mime`() {
        // A resolver answering application/octet-stream for a PNG would route
        // it down the generic file path and the model would never see it.
        assertEquals("image/png", mimeForName("screenshot.PNG"))
        assertEquals("application/pdf", mimeForName("report.pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            mimeForName("contract.docx"),
        )
        assertEquals("application/octet-stream", mimeForName("mystery"))
    }

    @Test
    fun `displayUri prefers a local cache over nothing`() {
        val remoteOnly = att("chart.png", "image/png").copy(remotePath = "D:/out/chart.png")
        assertEquals(null, remoteOnly.displayUri())
        assertEquals(
            "file:///data/cache/chart.png",
            remoteOnly.copy(cachedUri = "file:///data/cache/chart.png").displayUri(),
        )
    }
}
