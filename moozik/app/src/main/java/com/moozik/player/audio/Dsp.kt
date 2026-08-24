package com.moozik.player.audio

object Dsp {
    init {
        System.loadLibrary("moozik_dsp")
    }

    const val FILTER_PEAKING = 0
    const val FILTER_LOWSHELF = 1
    const val FILTER_HIGHSHELF = 2

    /** Mirrors native DspEngine::kMaxBands. */
    const val BAND_COUNT = 48

    external fun version(): String

    /** Returns [b0, b1, b2, a1, a2] of an RBJ peaking filter. */
    external fun peakingCoefficients(
        sampleRate: Double,
        freq: Double,
        q: Double,
        gainDb: Double,
    ): FloatArray

    /**
     * Verifies the designed peaking filter hits unity gain at DC/Nyquist
     * and target gain (+gainDb) at the center frequency.
     */
    external fun selfCheck(
        sampleRate: Double,
        freq: Double,
        q: Double,
        gainDb: Double,
    ): Boolean

    external fun createEngine(sampleRate: Int): Long
    external fun destroyEngine(handle: Long)
    external fun setPreamp(handle: Long, gainDb: Float)
    external fun setBand(
        handle: Long,
        index: Int,
        type: Int,
        freq: Double,
        q: Double,
        gainDb: Double,
        enabled: Boolean,
    )

    external fun reset(handle: Long)
    external fun process(handle: Long, interleaved: FloatArray, frames: Int)

    // Output backend (single instance)
    external fun openOutput(handle: Long, sampleRate: Int): Boolean
    external fun closeOutput()
    external fun setOutputPaused(paused: Boolean)
    external fun drainOutput()
    external fun writeOutput(interleaved: FloatArray, frames: Int)

    /** "exclusive · 48000 Hz" style summary of the live output; "" when closed. */
    external fun outputInfo(): String

    /** Opt-in switch for exclusive (mixer-bypassing) output attempts. */
    external fun setExclusiveEnabled(enabled: Boolean)
}
