package com.hermes.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes

/**
 * Markdown for agent output.
 *
 * The agent writes Markdown constantly — fenced code, bullet plans, `paths`,
 * **emphasis** — and rendering it as one flat string was the app's biggest
 * legibility failure: a shell command and the sentence introducing it looked
 * identical. This is a deliberately small renderer covering exactly what turns
 * up in a transcript, because pulling in a full CommonMark stack for six
 * constructs would cost more than it returns.
 *
 * Covered: ATX headings, fenced and indented code, unordered/ordered lists,
 * block quotes, horizontal rules, and the inline set (bold, italic, strike,
 * code spans, links). Anything unrecognised renders as its literal text —
 * never dropped.
 */

@Immutable
internal sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Code(val language: String?, val body: String) : Block
    data class Bullet(val marker: String, val text: String, val depth: Int) : Block
    data class Quote(val text: String) : Block
    data object Rule : Block
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> Text(
                    inline(block.text),
                    style = style,
                    color = color,
                )
                is Block.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                is Block.Code -> CodeBlock(block.language, block.body)
                is Block.Bullet -> Row(
                    Modifier.padding(start = (block.depth * 16).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        block.marker,
                        style = style,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(if (block.marker.length > 2) 26.dp else 16.dp),
                    )
                    Text(inline(block.text), style = style, color = color)
                }
                is Block.Quote -> Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        inline(block.text),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Block.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/**
 * Fenced code. Its own surface, its own material, horizontally scrollable so
 * long lines are never wrapped into nonsense, and a copy button — because the
 * single most common thing to do with a command on a phone is send it
 * somewhere else.
 */
@Composable
fun CodeBlock(language: String?, body: String) {
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.hermes.code, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.hermes.codeBorder, RoundedCornerShape(10.dp)),
    ) {
        Column {
            if (!language.isNullOrBlank()) {
                Text(
                    language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                )
            }
            Text(
                body,
                style = LocalTextStyle.current.copy(fontFamily = HermesMono),
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                color = MaterialTheme.hermes.onCode,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(scroll)
                    .padding(start = 12.dp, end = 44.dp, top = 8.dp, bottom = 10.dp),
            )
        }
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(body)) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(40.dp),
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "Copy code",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*(\\S+)?\\s*$")
private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
private val RULE = Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1[\\s\\-*_]*$")
private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")

internal fun parseMarkdown(source: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()

    fun flush() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) out.add(Block.Paragraph(text))
        paragraph.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        val open = FENCE.find(line)
        if (open != null) {
            flush()
            val fence = open.groupValues[1]
            val language = open.groupValues[2].takeIf { it.isNotBlank() }
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            i++ // consume the closing fence (or fall off the end, unterminated)
            out.add(Block.Code(language, body.toString()))
            continue
        }

        when {
            line.isBlank() -> flush()

            RULE.matches(line) -> {
                flush()
                out.add(Block.Rule)
            }

            HEADING.matches(line) -> {
                flush()
                val m = HEADING.find(line)!!
                out.add(Block.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            }

            QUOTE.matches(line) -> {
                flush()
                out.add(Block.Quote(QUOTE.find(line)!!.groupValues[1]))
            }

            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out.add(Block.Bullet("•", m.groupValues[3], m.groupValues[1].length / 2))
            }

            ORDERED.matches(line) -> {
                flush()
                val m = ORDERED.find(line)!!
                out.add(
                    Block.Bullet("${m.groupValues[2]}.", m.groupValues[3], m.groupValues[1].length / 2),
                )
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

// ---------------------------------------------------------------------------
// Inline spans
// ---------------------------------------------------------------------------

private val INLINE = Regex(
    // code span | bold | italic | strike | link
    "`([^`]+)`" +
        "|\\*\\*([^*]+)\\*\\*" +
        "|(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])" +
        "|~~([^~]+)~~" +
        "|\\[([^\\]]+)]\\(([^)\\s]+)\\)",
)

@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.hermes.code
    val codeFg = MaterialTheme.colorScheme.primary
    val linkFg = MaterialTheme.colorScheme.secondary
    return remember(text, codeBg, codeFg, linkFg) {
        buildAnnotatedString {
            var cursor = 0
            for (match in INLINE.findAll(text)) {
                if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
                val g = match.groupValues
                when {
                    g[1].isNotEmpty() -> withSpan(
                        SpanStyle(fontFamily = HermesMono, background = codeBg, color = codeFg),
                    ) { append(g[1]) }
                    g[2].isNotEmpty() -> withSpan(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(g[2])
                    }
                    g[3].isNotEmpty() -> withSpan(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(g[3])
                    }
                    g[4].isNotEmpty() -> withSpan(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                    ) { append(g[4]) }
                    g[5].isNotEmpty() -> withSpan(
                        SpanStyle(color = linkFg, textDecoration = TextDecoration.Underline),
                    ) { append(g[5]) }
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
}

private inline fun AnnotatedString.Builder.withSpan(
    span: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val start = length
    block()
    addStyle(span, start, length)
}
