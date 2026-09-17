package com.hermes.mobile.core.terminal

/**
 * Kotlin port of web/src/lib/pty-resume-sanitizer.ts (ported, not reinvented —
 * spec §6 Phase 4). Strips pathological ANSI sequences Ink's two-pass virtual
 * scroll emits during session resume:
 *  - blank-line bursts collapse to one blank row (threshold 50)
 *  - ESC[K / ESC[X stripped only during the resume window ([endEraseSuppression])
 *  - trailing partial escapes / newline runs are buffered across frames,
 *    because PTY reads are 64 KiB chunks, not message-framed
 */
class PtySanitizer {

    private var pending = ""
    private var stripErase = true

    fun endEraseSuppression() {
        stripErase = false
    }

    val isSuppressingErase: Boolean get() = stripErase

    /** Feed one decoded WS frame payload; returns sanitized output. */
    fun next(chunk: String): String {
        val combined = pending + chunk
        if (combined.isEmpty()) {
            pending = ""
            return ""
        }

        // Hold back a trailing partial escape ("ESC", "ESC[", "ESC[<digits>").
        val lastEsc = combined.lastIndexOf('\u001b')
        if (lastEsc != -1 && PARTIAL_ESC.matches(combined.substring(lastEsc))) {
            pending = combined.substring(lastEsc)
            return applyFilters(combined.substring(0, lastEsc))
        }

        // Hold back a trailing newline run so a burst spanning frames
        // accumulates to the collapse threshold.
        val trailing = TRAILING_NEWLINES.find(combined)!!
        if (trailing.range.first == 0) {
            pending = combined
            return ""
        }
        pending = trailing.value
        return applyFilters(combined.substring(0, trailing.range.first))
    }

    /** Drain at end of stream; buffered partial escapes are dropped. */
    fun flush(): String {
        val last = pending
        pending = ""
        if (last.isEmpty() || last.contains('\u001b')) return ""
        return applyFilters(last)
    }

    private fun applyFilters(input: String): String {
        val collapsed = input.replace(BLANK_LINE_BURST, COLLAPSED_BURST)
        if (!stripErase) return collapsed
        return collapsed.replace(ERASE_LINE, "").replace(ERASE_CHAR, "")
    }

    companion object {
        /** 50+ consecutive CRLF/LF = pathological Ink resume replay. */
        private val BLANK_LINE_BURST = Regex("(?:\\r?\\n){50,}")
        private val ERASE_LINE = Regex("\u001b\\[\\d*K")
        private val ERASE_CHAR = Regex("\u001b\\[\\d*X")
        private val PARTIAL_ESC = Regex("^\u001b(?:\\[\\d*)?$")
        private val TRAILING_NEWLINES = Regex("(?:\\r?\\n)*\\r?$")
        private const val COLLAPSED_BURST = "\r\n\r\n"

        /** Mirrors PTY_RESUME_SANITIZE_WINDOW_MS in pty-reconnect.ts. */
        const val RESUME_SANITIZE_WINDOW_MS = 30_000L
    }
}
