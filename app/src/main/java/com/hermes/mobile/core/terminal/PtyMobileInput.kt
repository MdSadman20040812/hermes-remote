package com.hermes.mobile.core.terminal

/**
 * Kotlin port of web/src/lib/pty-mobile-input.ts (spec §6 Phase 4 — port,
 * don't reinvent). Mobile IMEs (Gboard et al.) deliver autocorrect and
 * composition as *line replacements*, not appends; sent raw to a PTY they
 * double words and corrupt the tracked line. These pure functions track the
 * current input line and rewrite replacement bursts into delete+retype.
 */

private const val DELETE = '\u007F'
const val MOBILE_REPLACEMENT_WINDOW_MS = 350L

private fun isPlainText(data: String): Boolean =
    data.none { it < ' ' || it == '\u007F' }

private fun lastWordMatch(line: String): Triple<String, String, String>? {
    val m = Regex("^(.*?)(\\S+)(\\s*)$").find(line) ?: return null
    return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}

private fun collapseDuplicatedFinalWord(text: String, previousLine: String): String {
    val m = Regex("^(.*?)(\\S+)(\\s+)(\\S+)(\\s*)$").find(text) ?: return text
    val (prefix, first, sep, second, trailing) = m.groupValues.drop(1)
    if (!first.equals(second, ignoreCase = true)) return text
    // Only collapse a duplication the tracked line already ended with — i.e.
    // the IME re-emitted the final word. >=2 chars avoids eating legitimate
    // single-letter reduplication ("a a", "i i").
    if (first.length < 2) return text
    if (!previousLine.trimEnd().endsWith(first, ignoreCase = true)) return text
    return "$prefix$first$trailing"
}

private fun replacementLineForMobileInput(currentLine: String, incoming: String): String? {
    if (currentLine.length < 2 || incoming.isEmpty()) return null

    if (incoming.startsWith(currentLine, ignoreCase = true)) {
        return collapseDuplicatedFinalWord(incoming, currentLine)
    }

    val (prefix, last, trailing) = lastWordMatch(currentLine) ?: return null
    if (trailing.isNotEmpty()) return null

    val incomingFirst = incoming.trimStart().split(Regex("\\s+")).firstOrNull() ?: ""
    if (incomingFirst.isNotEmpty() && incomingFirst.equals(last, ignoreCase = true)) {
        return prefix + collapseDuplicatedFinalWord(incoming, currentLine)
    }
    return null
}

/**
 * Track the input line through raw outgoing data. Escape sequences reset the
 * tracker (unknown cursor position must disarm replacement normalization).
 */
fun updatePtyInputLine(currentLine: String, data: String): String {
    if (data.contains('\u001B')) return ""
    var next = currentLine
    for (ch in data) {
        when {
            ch == '\r' || ch == '\n' -> next = ""
            ch == DELETE || ch == '\b' -> next = next.dropLast(1)
            ch == '\u0015' -> next = "" // Ctrl-U kill line
            isPlainText(ch.toString()) -> next += ch
        }
    }
    return next
}

data class NormalizedInput(val data: String, val nextLine: String, val normalized: Boolean)

/**
 * If [replacementActive] (an IME composition/replacement just happened) and
 * [data] is plain text, rewrite it as delete-to-start + retyped line.
 */
fun normalizePtyMobileInput(
    data: String,
    currentLine: String,
    replacementActive: Boolean,
): NormalizedInput {
    if (replacementActive && isPlainText(data)) {
        val replacementLine = replacementLineForMobileInput(currentLine, data)
        if (replacementLine != null) {
            return NormalizedInput(
                data = DELETE.toString().repeat(currentLine.length) + replacementLine,
                nextLine = replacementLine,
                normalized = true,
            )
        }
    }
    return NormalizedInput(
        data = data,
        nextLine = updatePtyInputLine(currentLine, data),
        normalized = false,
    )
}
