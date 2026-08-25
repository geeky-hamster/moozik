package com.moozik.player.audio

/**
 * Minimal LRC (synced lyrics) parser: supports multiple timestamps per line,
 * fractional seconds, and metadata tags like [ar:], [ti:], [offset:]. Pure
 * Kotlin so it runs in JVM unit tests.
 */
object LrcParser {

    data class Line(val timeMs: Long, val text: String)

    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<Line> {
        var offsetMs = 0L
        val lines = mutableListOf<Line>()

        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // [offset:+/-ms] global shift
            if (line.startsWith("[offset:", ignoreCase = true)) {
                offsetMs = line.substringAfter(':').trimEnd(']').toLongOrNull() ?: 0L
                continue
            }

            // Skip pure metadata tags like [ar:..] [ti:..] [al:..] [by:..]
            if (timeTagRegex.findAll(line).none()) continue

            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) continue

            val text = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].toLong()
                val fraction = when (m.groupValues[3].length) {
                    0 -> 0L
                    1 -> m.groupValues[3].toLong() * 100
                    2 -> m.groupValues[3].toLong() * 10
                    else -> m.groupValues[3].take(3).toLong()
                }
                val timeMs = minutes * 60_000 + seconds * 1000 + fraction
                lines += Line(timeMs = timeMs, text = if (text.isEmpty()) "♪" else text)
            }
        }

        return lines.sortedBy { it.timeMs }.map {
            it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0))
        }
    }

    /** Index of the line active at [positionMs]; -1 before the first line. */
    fun activeIndex(lines: List<Line>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
