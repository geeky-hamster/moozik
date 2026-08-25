package com.moozik.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    private val sample = """
        [ar:Artist]
        [ti:Song]
        [offset:+500]
        [00:12.00]First line
        [00:15.50]Second line
        [01:02.300]Third line
        [00:12.00][01:30.00]Repeated line
    """.trimIndent()

    @Test
    fun parsesTimestampsAndText() {
        val lines = LrcParser.parse(sample)
        // 5 entries: 4 lyric lines, the last carrying two timestamps.
        assertEquals(5, lines.size)
        // offset:+500 shifts everything 500ms earlier; ties keep source order.
        assertEquals(11_500L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(11_500L, lines[1].timeMs)
        assertEquals("Repeated line", lines[1].text)
        assertEquals(15_000L, lines[2].timeMs)
        assertEquals("Second line", lines[2].text)
        assertEquals(61_800L, lines[3].timeMs)
        assertEquals("Third line", lines[3].text)
    }

    @Test
    fun appliesOffset() {
        val lines = LrcParser.parse(sample)
        // offset:+500 shifts times 500ms EARLIER
        assertEquals(11_500L, lines[0].timeMs)
        assertTrue(lines.all { it.timeMs >= 0 })
    }

    @Test
    fun ignoresMetadataTags() {
        val lines = LrcParser.parse(sample)
        assertTrue(lines.none { it.text.startsWith("Artist") })
    }

    @Test
    fun activeIndexFindsCurrentLine() {
        val lines = LrcParser.parse(sample)
        // offset:+500 shifts 00:12.00 -> 11_500; the repeated-tag line ties there too.
        assertEquals(-1, LrcParser.activeIndex(lines, 11_499))
        assertEquals(1, LrcParser.activeIndex(lines, 11_500))
        assertEquals(2, LrcParser.activeIndex(lines, 16_000))
        assertEquals(lines.lastIndex, LrcParser.activeIndex(lines, 10 * 60_000))
    }

    @Test
    fun emptyInputYieldsEmptyList() {
        assertTrue(LrcParser.parse("").isEmpty())
    }
}
