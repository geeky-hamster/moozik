package com.moozik.player.audio

enum class FilterKind { PEAKING, LOW_SHELF, HIGH_SHELF }

data class EqBand(
    val kind: FilterKind,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double,
)

data class EqPreset(
    val name: String,
    val preampDb: Double,
    val bands: List<EqBand>,
)

/**
 * Parses EqualizerAPO-format parametric EQ files (what AutoEq exports as
 * ParametricEQ.txt). Recognizes PK/LSC/HSC filter types; unsupported types
 * are skipped. Example line:
 *   Filter 3: ON PK Fc 2200 Hz Gain -3.2 dB Q 1.100
 */
object AutoEqParser {

    private val preampRegex =
        Regex("""^Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)

    private val filterRegex =
        Regex(
            """^Filter\s*\d*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+(\d+(?:\.\d+)?)\s*k?Hz\s+""" +
                """Gain\s+(-?\d+(?:\.\d+)?)\s*dB(?:\s*/\s*octave)?\s+Q\s+(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )

    fun parse(text: String, name: String): EqPreset {
        var preamp = 0.0
        val bands = mutableListOf<EqBand>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            preampRegex.find(line)?.let {
                preamp = it.groupValues[1].toDouble()
                continue
            }

            val m = filterRegex.find(line) ?: continue
            val enabled = m.groupValues[1].equals("ON", ignoreCase = true)
            if (!enabled) continue

            val kind = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ", "PEAKING" -> FilterKind.PEAKING
                "LSC", "LS", "LSQ" -> FilterKind.LOW_SHELF
                "HSC", "HS", "HSQ" -> FilterKind.HIGH_SHELF
                else -> null // NO / LP / HP etc. not used by AutoEq presets
            } ?: continue

            var freq = m.groupValues[3].toDouble()
            if (line.substringAfter("Fc").trimStart().contains("kHz", ignoreCase = true)) {
                freq *= 1000.0
            }

            bands += EqBand(
                kind = kind,
                freqHz = freq,
                gainDb = m.groupValues[4].toDouble(),
                q = m.groupValues[5].toDouble(),
            )
        }

        return EqPreset(name = name, preampDb = preamp, bands = bands.toList())
    }
}
