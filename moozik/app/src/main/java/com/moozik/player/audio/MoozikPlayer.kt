package com.moozik.player.audio

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session-based playback orchestrator: decode loop on IO dispatcher,
 * lock-free handoff to the native AAudio output. Generation counters make
 * stale sessions harmless when tracks are switched quickly.
 */
class MoozikPlayer(context: Context) {

    enum class Status { IDLE, PREPARING, PLAYING, PAUSED }

    data class PlayerState(
        val status: Status = Status.IDLE,
        val title: String = "",
        val sampleRate: Int = 0,
        val durationMs: Long = 0L,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var job: Job? = null
    private val generation = AtomicLong(0)
    private val pendingSeekUs = AtomicLong(-1L)

    @Volatile private var paused = false
    @Volatile private var positionMs = 0L
    @Volatile private var engineHandle = 0L

    fun positionMs(): Long = positionMs

    fun play(uri: Uri, title: String) {
        val myGen = generation.incrementAndGet()
        job?.cancel()

        paused = false
        pendingSeekUs.set(-1)
        positionMs = 0

        job = scope.launch {
            var decoder: MediaDecoder? = null
            try {
                _state.value = PlayerState(status = Status.PREPARING, title = title)

                val d = MediaDecoder(appContext).also { decoder = it }
                val info = d.open(uri)

                engineHandle = Dsp.createEngine(info.sampleRate)
                check(Dsp.openOutput(engineHandle, info.sampleRate)) {
                    "could not open audio output"
                }

                _state.value = PlayerState(
                    status = Status.PLAYING,
                    title = title,
                    sampleRate = info.sampleRate,
                    durationMs = info.durationUs / 1000,
                )

                val eos = d.decodeLoop(
                    isActive = { isActive && generation.get() == myGen },
                    isPaused = { paused },
                    pendingSeekUs = pendingSeekUs,
                    sink = { _, _, ptsUs -> positionMs = (ptsUs / 1000).coerceAtLeast(0) },
                    onDrained = { Dsp.drainOutput() },
                )

                if (eos && isActive && generation.get() == myGen) {
                    _state.value.durationMs.takeIf { it > 0 }?.let { positionMs = it }
                    stopAudio()
                    _state.update { it.copy(status = Status.IDLE) }
                }
            } catch (t: Throwable) {
                if (generation.get() == myGen) {
                    stopAudio()
                    _state.value = PlayerState(error = t.message ?: "playback failed")
                }
            } finally {
                decoder?.release()
                if (generation.get() == myGen) stopAudio()
            }
        }
    }

    fun togglePause() {
        when (_state.value.status) {
            Status.PLAYING -> {
                paused = true
                Dsp.setOutputPaused(true)
                _state.update { it.copy(status = Status.PAUSED) }
            }
            Status.PAUSED -> {
                paused = false
                Dsp.setOutputPaused(false)
                _state.update { it.copy(status = Status.PLAYING) }
            }
            else -> Unit
        }
    }

    fun seekTo(ms: Long) {
        val status = _state.value.status
        if (status != Status.PLAYING && status != Status.PAUSED) return
        positionMs = ms.coerceIn(0, _state.value.durationMs)
        pendingSeekUs.set(ms * 1000)
    }

    fun stop() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        paused = false
        positionMs = 0
        stopAudio()
        _state.update { it.copy(status = Status.IDLE) }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun stopAudio() {
        Dsp.closeOutput()
        if (engineHandle != 0L) {
            Dsp.destroyEngine(engineHandle)
            engineHandle = 0L
        }
    }
}
