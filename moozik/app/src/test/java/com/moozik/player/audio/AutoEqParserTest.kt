package com.moozik.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqParserTest {

    private val sample = """
        Notes: AutoEq generated preset for Sennheiser HD 650
        Preamp: -6.6 dB
        Filter 1: ON LSC Fc 90 Hz Gain -5.4 dB Q 0.700
        Filter 2: ON PK Fc 120 Hz Gain 3.2 dB Q 1.000
        Filter 3: OFF PK Fc 300 Hz Gain 2.0 dB Q 0.900
        Filter 4: ON HSC Fc 8000 Hz Gain -1.5 dB Q 0.707
        Filter 5: ON NO Fc 10000 Hz Gain -4.0 dB Q 2.000
    """.trimIndent()

    @Test
    fun parsesPreampAndSupportedFilters() {
        val p = AutoEqParser.parse(sample, "HD 650")

        assertEquals("HD 650", p.name)
        assertEquals(-6.6, p.preampDb, 1e-9)
        assertEquals(3, p.bands.size)

        assertEquals(FilterKind.LOW_SHELF, p.bands[0].kind)
        assertEquals(90.0, p.bands[0].freqHz, 1e-9)
        assertEquals(-5.4, p.bands[0].gainDb, 1e-9)
        assertEquals(0.7, p.bands[0].q, 1e-9)

        assertEquals(FilterKind.PEAKING, p.bands[1].kind)
        assertEquals(120.0, p.bands[1].freqHz, 1e-9)

        assertEquals(FilterKind.HIGH_SHELF, p.bands[2].kind)
        assertEquals(8000.0, p.bands[2].freqHz, 1e-9)
    }

    @Test
    fun skipsDisabledAndUnsupportedTypes() {
        val p = AutoEqParser.parse(sample, "x")
        // OFF filter and NO (notch) must be excluded.
        assertTrue(p.bands.none { it.freqHz == 300.0 })
        assertTrue(p.bands.none { it.freqHz == 10000.0 })
    }

    @Test
    fun handlesKilohertzUnits() {
        val p = AutoEqParser.parse(
            "Preamp: 0 dB\nFilter 1: ON PK Fc 2.5 kHz Gain -3 dB Q 1.000",
            "k",
        )
        assertEquals(2500.0, p.bands.single().freqHz, 1e-9)
    }

    @Test
    fun emptyInputYieldsFlatPreset() {
        val p = AutoEqParser.parse("", "empty")
        assertEquals(0.0, p.preampDb, 1e-9)
        assertTrue(p.bands.isEmpty())
    }
}
