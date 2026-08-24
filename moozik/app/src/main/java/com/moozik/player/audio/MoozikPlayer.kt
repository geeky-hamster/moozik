package com.moozik.player.audio

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
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
 * Queue-based playback orchestrator running entirely on an IO dispatcher:
 * MediaCodec decode -> lock-free ring -> native DSP -> AAudio output.
 * Generation counters make superseded sessions harmless.
 */
class MoozikPlayer(context: Context, private val eq: EqController? = null) {

    enum class Status { IDLE, PREPARING, PLAYING, PAUSED }

    data class PlayerState(
        val status: Status = Status.IDLE,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val artUri: String? = null,
        val sampleRate: Int = 0,
        val durationMs: Long = 0L,
        val queueIndex: Int = -1,
        val queueSize: Int = 0,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var queue: List<PlayerTrack> = emptyList()
    private var job: Job? = null
    private val generation = AtomicLong(0)
    private val pendingSeekUs = AtomicLong(-1L)

    @Volatile private var paused = false
    @Volatile private var positionMs = 0L
    @Volatile private var engineHandle = 0L

    fun positionMs(): Long = positionMs

    fun playQueue(tracks: List<PlayerTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val myGen = generation.incrementAndGet()
        job?.cancel()

        queue = tracks
        paused = false
        pendingSeekUs.set(-1)
        positionMs = 0

        job = scope.launch {
            var idx = startIndex.coerceIn(0, tracks.lastIndex)
            var failure: String? = null

            while (isActive && generation.get() == myGen && idx in tracks.indices) {
                val track = tracks[idx]
                var decoder: MediaDecoder? = null
                try {
                    _state.update {
                        it.copy(
                            status = Status.PREPARING, title = track.title,
                            artist = track.artist, album = track.album, artUri = track.artUri,
                            queueIndex = idx, queueSize = tracks.size, error = null,
                        )
                    }

                    val d = MediaDecoder(appContext).also { decoder = it }
                    val info = d.open(Uri.parse(track.uri))

                    engineHandle = Dsp.createEngine(info.sampleRate)
                    check(Dsp.openOutput(engineHandle, info.sampleRate)) { "could not open audio output" }
                    eq?.applyTo(engineHandle, info.sampleRate)

                    _state.update {
                        it.copy(
                            status = Status.PLAYING,
                            sampleRate = info.sampleRate,
                            durationMs = (info.durationUs / 1000).takeIf { us -> us > 0 }
                                ?: track.durationMs,
                        )
                    }

                    val eos = d.decodeLoop(
                        isActive = { isActive && generation.get() == myGen },
                        isPaused = { paused },
                        pendingSeekUs = pendingSeekUs,
                        sink = { _, _, ptsUs -> positionMs = (ptsUs / 1000).coerceAtLeast(0) },
                        onDrained = { Dsp.drainOutput() },
                    )

                    if (!isActive || generation.get() != myGen) break
                    if (!eos) break

                    // Natural end -> advance to the next track.
                    stopAudio()
                    positionMs = 0
                    idx++
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (generation.get() == myGen) failure = t.message ?: "playback failed"
                    break
                } finally {
                    decoder?.release()
                    if (generation.get() == myGen) stopAudio()
                }
            }

            if (generation.get() == myGen) {
                stopAudio()
                _state.update { it.copy(status = Status.IDLE, error = failure) }
            }
        }
    }

    fun playSingle(track: PlayerTrack) = playQueue(listOf(track), 0)

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

    fun next() {
        val n = _state.value.queueIndex + 1
        if (n in queue.indices) playQueue(queue, n)
    }

    fun previous() {
        if (positionMs > 3000) {
            seekTo(0)
        } else {
            val p = _state.value.queueIndex - 1
            if (p in queue.indices) playQueue(queue, p) else seekTo(0)
        }
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
