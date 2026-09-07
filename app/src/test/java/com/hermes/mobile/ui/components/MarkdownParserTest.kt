package com.hermes.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript renderer's parser.
 *
 * These cases are the shapes the agent actually emits, and the one rule that
 * matters most is the last test: nothing is ever dropped. A parser that
 * silently swallows a line it doesn't recognise loses the agent's output,
 * which is worse than rendering it plainly.
 */
class MarkdownParserTest {

    @Test
    fun `plain prose is one paragraph`() {
        val blocks = parseMarkdown("Just a sentence.")
        assertEquals(1, blocks.size)
        assertEquals("Just a sentence.", (blocks[0] as Block.Paragraph).text)
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val blocks = parseMarkdown("First.\n\nSecond.")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is Block.Paragraph })
    }

    @Test
    fun `fenced code keeps its language and body verbatim`() {
        val blocks = parseMarkdown("Run this:\n```bash\ncd /tmp && ls -la\n```\nDone.")
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertEquals("bash", code.language)
        assertEquals("cd /tmp && ls -la", code.body)
        assertEquals(2, blocks.filterIsInstance<Block.Paragraph>().size)
    }

    @Test
    fun `code fence content is not parsed as markdown`() {
        // A shell comment starts with '#', which is also an ATX heading.
        val blocks = parseMarkdown("```sh\n# not a heading\n- not a bullet\n```")
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertEquals("# not a heading\n- not a bullet", code.body)
        assertTrue(blocks.none { it is Block.Heading || it is Block.Bullet })
    }

    @Test
    fun `an unterminated fence still yields its content`() {
        // Streaming means a half-arrived code block is the normal case.
        val blocks = parseMarkdown("```python\nprint(1)")
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertEquals("print(1)", code.body)
    }

    @Test
    fun `headings carry their level`() {
        val blocks = parseMarkdown("# One\n## Two\n### Three")
        val levels = blocks.filterIsInstance<Block.Heading>().map { it.level }
        assertEquals(listOf(1, 2, 3), levels)
    }

    @Test
    fun `bullets and ordered items both become bullets`() {
        val blocks = parseMarkdown("- alpha\n* beta\n1. gamma\n2) delta")
        val bullets = blocks.filterIsInstance<Block.Bullet>()
        assertEquals(4, bullets.size)
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), bullets.map { it.text })
        assertEquals("1.", bullets[2].marker)
    }

    @Test
    fun `nested bullets record their depth`() {
        val blocks = parseMarkdown("- top\n  - nested")
        val bullets = blocks.filterIsInstance<Block.Bullet>()
        assertEquals(0, bullets[0].depth)
        assertEquals(1, bullets[1].depth)
    }

    @Test
    fun `quotes and rules are recognised`() {
        val blocks = parseMarkdown("> quoted\n\n---")
        assertEquals("quoted", blocks.filterIsInstance<Block.Quote>().single().text)
        assertEquals(1, blocks.count { it is Block.Rule })
    }

    @Test
    fun `a bare hash without a space is not a heading`() {
        val blocks = parseMarkdown("#hashtag")
        assertEquals("#hashtag", (blocks.single() as Block.Paragraph).text)
    }

    @Test
    fun `windows line endings are handled`() {
        val blocks = parseMarkdown("one\r\n\r\ntwo")
        assertEquals(2, blocks.size)
        assertEquals("one", (blocks[0] as Block.Paragraph).text)
        assertEquals("two", (blocks[1] as Block.Paragraph).text)
    }

    @Test
    fun `nothing is dropped`() {
        val source = """
            Intro line
            # Heading
            - item one
            - item two
            > a quote
            ```kt
            val x = 1
            ```
            Trailing line
        """.trimIndent()
        val blocks = parseMarkdown(source)
        val rendered = blocks.joinToString("\n") {
            when (it) {
                is Block.Paragraph -> it.text
                is Block.Heading -> it.text
                is Block.Bullet -> it.text
                is Block.Quote -> it.text
                is Block.Code -> it.body
                Block.Rule -> "---"
            }
        }
        listOf("Intro line", "Heading", "item one", "item two", "a quote", "val x = 1", "Trailing line")
            .forEach { assertTrue("lost: $it", rendered.contains(it)) }
    }

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(emptyList<Block>(), parseMarkdown(""))
        assertEquals(emptyList<Block>(), parseMarkdown("   \n\n  "))
    }
}
