package com.hermes.mobile.core.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file-reference formats are a CONTRACT with the desktop, not a convention
 * this app owns: `@image:` is how the gateway persists an attached image and
 * `MEDIA:` is how the desktop is told to deliver a file. Breaking the parse
 * turns "here is your deck" into a wall of Windows paths, silently.
 */
class AttachmentRefsTest {

    @Test
    fun `finds a media marker with an absolute windows path`() {
        val refs = AttachmentRefs.scan("Here is the deck.\n\nMEDIA:D:/Outputs/deck.pptx")
        assertEquals(1, refs.size)
        assertEquals("D:/Outputs/deck.pptx", refs.first().path)
    }

    @Test
    fun `handles a quoted path containing spaces`() {
        // format_reference_value quotes any value with whitespace; an unquoted
        // parse would stop at the first space and point at half a directory.
        val refs = AttachmentRefs.scan("""@image:`D:/My Reports/q3 chart.png`""")
        assertEquals(listOf("D:/My Reports/q3 chart.png"), refs.map { it.path })
    }

    @Test
    fun `does not swallow the sentence after a bare path`() {
        val refs = AttachmentRefs.scan("MEDIA:/tmp/a.pdf and that's the summary.")
        assertEquals("/tmp/a.pdf", refs.single().path)
    }

    @Test
    fun `strips trailing sentence punctuation from a bare path`() {
        assertEquals("/tmp/report.pdf", AttachmentRefs.scan("see MEDIA:/tmp/report.pdf.").single().path)
    }

    @Test
    fun `finds every reference in a multi-file reply`() {
        val text = """
            Done — three files:
            @file:out/summary.md
            @image:out/chart.png
            MEDIA:D:/Outputs/deck.pptx
        """.trimIndent()
        assertEquals(3, AttachmentRefs.scan(text).size)
    }

    @Test
    fun `strip leaves readable prose`() {
        val cleaned = AttachmentRefs.strip("Here's the chart.\n@image:D:/out/chart.png")
        assertEquals("Here's the chart.", cleaned)
    }

    @Test
    fun `ordinary code and prose are not mistaken for references`() {
        // A false positive is worse than a miss: it would delete real text from
        // the reply and attach a card pointing nowhere.
        val text = "Use `dict[image]` and the ratio file:line format in logs."
        assertTrue(AttachmentRefs.scan(text).isEmpty())
    }

    @Test
    fun `attachments carry a usable name and a mime guess`() {
        val att = AttachmentRefs.attachmentsIn("MEDIA:D:/Outputs/deck.pptx", "k").single()
        assertEquals("deck.pptx", att.name)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            att.mimeType,
        )
        assertEquals("D:/Outputs/deck.pptx", att.remotePath)
    }

    @Test
    fun `duplicate references collapse to one card`() {
        val text = "@image:out/a.png and again @image:out/a.png"
        assertEquals(1, AttachmentRefs.attachmentsIn(text, "k").size)
    }
}
