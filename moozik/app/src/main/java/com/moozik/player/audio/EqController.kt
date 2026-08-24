package com.moozik.player.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns all EQ state and pushes it into a native DspEngine whenever it changes.
 *
 * Native slot layout (kMaxBands = 48):
 *   [0..9]   graphic EQ (10 peaking filters, octave-spaced)
 *   [10..47] parametric / AutoEq preset slots
 */
class EqController(context: Context) {

    companion object {
        val GRAPHIC_FREQS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        const val AUTOEQ_START = 10
        private const val PREFS = "moozik_eq"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Invoked on any EQ mutation; the player hooks this to push state into the live engine. */
    var onEqChanged: (() -> Unit)? = null

    var enabled: Boolean = true
        set(value) { field = value; save(); onEqChanged?.invoke() }

    var preampDb: Double = 0.0
        set(value) { field = value; save(); onEqChanged?.invoke() }

    /** Graphic band gains in dB, -12..+12. */
    val graphicGains = DoubleArray(GRAPHIC_FREQS.size)

    var preset: EqPreset? = null
        set(value) { field = value; save(); onEqChanged?.invoke() }

    init { loadFromPrefs() }

    fun setGraphicGain(index: Int, gainDb: Double) {
        graphicGains[index] = gainDb.coerceIn(-12.0, 12.0)
        save()
        notifyChange()
    }

    /** Silent write while dragging: coerces only, no persistence/engine churn. */
    fun setGraphicGainSilent(index: Int, gainDb: Double) {
        graphicGains[index] = gainDb.coerceIn(-12.0, 12.0)
    }

    /** Persists and applies the band after a drag completes. */
    fun commitGraphic(index: Int) {
        save()
        notifyChange()
    }

    fun setPreamp(db: Double) {
        preampDb = db.coerceIn(-12.0, 6.0)
    }

    fun importPreset(preset: EqPreset) {
        this.preset = preset
        this.enabled = true
    }

    fun clearPreset() {
        preset = null
        save()
        notifyChange()
    }

    private fun notifyChange() {
        onEqChanged?.invoke()
    }

    /** Pushes full state into the engine at [handle]. Safe to call repeatedly. */
    fun applyTo(handle: Long, sampleRate: Int) {
        if (handle == 0L) return

        // Graphic section: identity when disabled or zeroed.
        for (i in GRAPHIC_FREQS.indices) {
            Dsp.setBand(
                handle, i, Dsp.FILTER_PEAKING,
                freqHz(sampleRate, GRAPHIC_FREQS[i]), 1.1,
                if (enabled) graphicGains[i] else 0.0,
                enabled && graphicGains[i] != 0.0,
            )
        }

        // Parametric slots: identity unless active preset present.
        for (slot in AUTOEQ_START until Dsp.BAND_COUNT) {
            Dsp.setBand(handle, slot, Dsp.FILTER_PEAKING, 1000.0, 1.0, 0.0, false)
        }
        preset?.let { p ->
            p.bands.take(Dsp.BAND_COUNT - AUTOEQ_START).forEachIndexed { i, band ->
                Dsp.setBand(
                    handle, AUTOEQ_START + i, kindToNative(band.kind),
                    freqHz(sampleRate, band.freqHz), band.q,
                    if (enabled) band.gainDb else 0.0,
                    enabled,
                )
            }
        }

        Dsp.setPreamp(handle, if (enabled) preampOf() else 0f)
    }

    private fun preampOf(): Float {
        // The AutoEq preset's own Preamp line already carries its headroom;
        // we add protection only for what the user adds on top (graphic gains + trim).
        val presetPre = preset?.preampDb ?: 0.0
        val graphicBoost = (graphicGains.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
        return (presetPre + preampDb - graphicBoost).coerceIn(-18.0, 6.0).toFloat()
    }

    private fun freqHz(sampleRate: Int, f: Double): Double =
        f.coerceIn(10.0, sampleRate / 2.0 - 100.0)

    private fun kindToNative(kind: FilterKind) = when (kind) {
        FilterKind.PEAKING -> Dsp.FILTER_PEAKING
        FilterKind.LOW_SHELF -> Dsp.FILTER_LOWSHELF
        FilterKind.HIGH_SHELF -> Dsp.FILTER_HIGHSHELF
    }

    /**
     * Combined magnitude response in dB at log-spaced points, mirroring the
     * native RBJ designs — used purely for drawing the curve.
     */
    fun magnitudeResponse(sampleRate: Int, points: Int = 256): FloatArray {
        val out = FloatArray(points)
        if (!enabled) return out

        val activeBands = buildList {
            GRAPHIC_FREQS.forEachIndexed { i, f ->
                if (graphicGains[i] != 0.0) add(EqBand(FilterKind.PEAKING, f, graphicGains[i], 1.1))
            }
            preset?.bands?.let { addAll(it) }
        }
        if (activeBands.isEmpty()) return out

        for (p in 0 until points) {
            val freq = 20.0 * Math.pow(1000.0, p.toDouble() / (points - 1))
            var db = 0.0
            for (band in activeBands) {
                val coeffs = design(band, sampleRate) ?: continue
                db += 20.0 * Math.log10(magnitude(coeffs, sampleRate, freq))
            }
            out[p] = db.toFloat()
        }
        return out
    }

    // ---- persistence ----

    @Synchronized
    private fun save() {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putFloat("preamp", preampDb.toFloat())
            .putString("graphic", JSONArray().apply { graphicGains.forEach { put(it) } }.toString())
            .putString("presetName", preset?.name)
            .apply()
        // Preset body is intentionally not persisted across restarts; import is cheap.
    }

    @Synchronized
    private fun loadFromPrefs() {
        enabled = prefs.getBoolean("enabled", true)
        preampDb = prefs.getFloat("preamp", 0f).toDouble()
        prefs.getString("graphic", null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until minOf(arr.length(), graphicGains.size)) {
                    graphicGains[i] = arr.getDouble(i)
                }
            }
        }
    }

    // ---- pure DSP math (mirror of native RBJ cookbook) ----

    data class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private fun design(band: EqBand, fs: Int): Coeffs? {
        val A = Math.pow(10.0, band.gainDb / 40.0)
        val w0 = 2.0 * Math.PI * band.freqHz / fs
        val cw = kotlin.math.cos(w0)
        val sw = kotlin.math.sin(w0)

        return when (band.kind) {
            FilterKind.PEAKING -> {
                val alpha = sw / (2.0 * band.q)
                norm(
                    1.0 + alpha * A, -2.0 * cw, 1.0 - alpha * A,
                    1.0 + alpha / A, -2.0 * cw, 1.0 - alpha / A,
                )
            }
            FilterKind.LOW_SHELF -> {
                val alpha = sw / 2.0 * kotlin.math.sqrt(2.0)
                val sqA = 2.0 * kotlin.math.sqrt(A) * alpha
                norm(
                    A * ((A + 1) - (A - 1) * cw + sqA),
                    2.0 * A * ((A - 1) - (A + 1) * cw),
                    A * ((A + 1) - (A - 1) * cw - sqA),
                    (A + 1) + (A - 1) * cw + sqA,
                    -2.0 * ((A - 1) + (A + 1) * cw),
                    (A + 1) + (A - 1) * cw - sqA,
                )
            }
            FilterKind.HIGH_SHELF -> {
                val alpha = sw / 2.0 * kotlin.math.sqrt(2.0)
                val sqA = 2.0 * kotlin.math.sqrt(A) * alpha
                norm(
                    A * ((A + 1) + (A - 1) * cw + sqA),
                    -2.0 * A * ((A - 1) + (A + 1) * cw),
                    A * ((A + 1) + (A - 1) * cw - sqA),
                    (A + 1) - (A - 1) * cw + sqA,
                    2.0 * ((A - 1) - (A + 1) * cw),
                    (A + 1) - (A - 1) * cw - sqA,
                )
            }
        }
    }

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private fun magnitude(c: Coeffs, fs: Int, freq: Double): Double {
        val w0 = 2.0 * Math.PI * freq / fs
        val c1 = kotlin.math.cos(w0)
        val c2 = kotlin.math.cos(2 * w0)
        val s1 = kotlin.math.sin(w0)
        val s2 = kotlin.math.sin(2 * w0)

        val nr = c.b0 + c.b1 * c1 + c.b2 * c2
        val ni = -(c.b1 * s1 + c.b2 * s2)
        val dr = 1.0 + c.a1 * c1 + c.a2 * c2
        val di = -(c.a1 * s1 + c.a2 * s2)
        return kotlin.math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }
}
