package com.moozik.player.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong
import androidx.core.content.ContextCompat
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

enum class RepeatMode { OFF, ALL, ONE }

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
        val artBitmap: android.graphics.Bitmap? = null,
        val currentUri: String? = null,
        val sampleRate: Int = 0,
        val durationMs: Long = 0L,
        val queueIndex: Int = -1,
        val queueSize: Int = 0,
        val repeat: RepeatMode = RepeatMode.OFF,
        val shuffled: Boolean = false,
        val sleepEndAt: Long = 0L,
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

    // Frame-counted position: codec PTS is unreliable (WAV reports 0 forever).
    @Volatile private var posSampleRate = 0
    @Volatile private var framesSinceSeek = 0L
    @Volatile private var seekBaseMs = 0L
    // Hardware anchor: framesRead at the last (re)start/seek. The live UI
    // position derives from what the stream has actually consumed.
    @Volatile private var readBase = 0L

    fun positionMs(): Long {
        val sr = posSampleRate
        if (sr <= 0) return positionMs
        val read = Dsp.outputFramesRead()
        if (read > 0) {
            val delta = read - readBase
            // Negative delta = stream was internally reopened (recovery) or
            // switched; the hardware counter restarted — fall back to the
            // decode-counted position until the next anchor.
            if (delta >= 0) {
                return seekBaseMs + delta * 1000 / sr
            }
        }
        return positionMs
    }

    private fun reanchorRead() {
        readBase = Dsp.outputFramesRead().coerceAtLeast(0)
    }

    private val audioPrefs by lazy {
        appContext.getSharedPreferences("moozik_audio", Context.MODE_PRIVATE)
    }

    // ---- audio focus & device-change handling ----
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocusLoss = false
                pauseForFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                resumeAfterFocusLoss = _state.value.status == Status.PLAYING
                pauseForFocus()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterFocusLoss) {
                    resumeAfterFocusLoss = false
                    resumeFromFocus()
                }
            }
        }
    }

    @Volatile private var resumeAfterFocusLoss = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(focusAttributes)
        .setOnAudioFocusChangeListener(focusListener)
        .setWillPauseWhenDucked(true)
        .build()

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pauseForFocus()
            resumeAfterFocusLoss = false
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun pauseForFocus() {
        if (_state.value.status == Status.PLAYING) togglePause()
    }

    private fun resumeFromFocus() {
        if (_state.value.status == Status.PAUSED) togglePause()
    }

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        resumeAfterFocusLoss = false
    }

    // ---- queue control ----

    fun playQueue(tracks: List<PlayerTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val myGen = generation.incrementAndGet()
        job?.cancel()

        abandonFocus()
        if (!requestFocus()) {
            _state.value = _state.value.copy(error = "audio focus unavailable")
            return
        }

        queue = tracks
        paused = false
        pendingSeekUs.set(-1)
        positionMs = 0
        framesSinceSeek = 0
        seekBaseMs = 0

        job = scope.launch {
            var idx = startIndex.coerceIn(0, tracks.lastIndex)
            var failure: String? = null
            // Gapless technique (ref: Intense Audio Player): keep the output
            // stream alive across consecutive tracks of the same rate — only
            // the decoder is swapped, eliminating the reopen gap entirely.
            var streamRate = -1

            while (isActive && generation.get() == myGen && idx in tracks.indices) {
                val track = tracks[idx]
                var decoder: MediaDecoder? = null
                try {
                    _state.update {
                        it.copy(
                            status = Status.PREPARING, title = track.title,
                            artist = track.artist, album = track.album,
                            artUri = track.artUri, artBitmap = ArtCache.get(track.uri),
                            currentUri = track.uri,
                            queueIndex = idx, queueSize = tracks.size, error = null,
                        )
                    }

                    val d = MediaDecoder(appContext).also { decoder = it }
                    val info = d.open(Uri.parse(track.uri))
                    android.util.Log.i("MoozikPlayer", "opened: mime=${info.mime} rate=${info.sampleRate} ch=${info.channels}")
                    posSampleRate = info.sampleRate

                    // Authoritative metadata straight from the file.
                    enrichMetadata(track)

                    if (engineHandle == 0L || streamRate != info.sampleRate) {
                        stopAudio()
                        engineHandle = Dsp.createEngine(info.sampleRate)
                        Dsp.setExclusiveEnabled(audioPrefs.getBoolean("exclusive", false))
                        val outOk = Dsp.openOutput(engineHandle, info.sampleRate)
                        android.util.Log.i(
                            "MoozikPlayer",
                            "openOutput(${info.sampleRate}Hz)=$outOk info=${Dsp.outputInfo()}",
                        )
                        check(outOk) { "could not open audio output" }
                        eq?.applyTo(engineHandle, info.sampleRate)
                        streamRate = info.sampleRate
                        reanchorRead()
                    }

                    _state.update {
                        it.copy(
                            status = Status.PLAYING,
                            sampleRate = info.sampleRate,
                            durationMs = it.durationMs.takeIf { ms -> ms > 0 }
                                ?: (info.durationUs / 1000).takeIf { us -> us > 0 }
                                ?: track.durationMs,
                        )
                    }

                    val eos = d.decodeLoop(
                        isActive = { isActive && generation.get() == myGen },
                        isPaused = { paused },
                        pendingSeekUs = pendingSeekUs,
                        sink = { buf, frames, _ ->
                            // THE critical handoff: decoded samples into the native ring.
                            Dsp.writeOutput(buf, frames)
                            // Frame-counted position — PTS-free, always truthful.
                            framesSinceSeek += frames
                            if (posSampleRate > 0) {
                                positionMs = seekBaseMs + framesSinceSeek * 1000 / posSampleRate
                            }
                        },
                        onDrained = { Dsp.drainOutput() },
                    )

                    if (!isActive || generation.get() != myGen) break
                    if (!eos) break

                    // Natural end -> apply repeat policy, then advance.
                    // Stream stays open for the next track (gapless when same rate);
                    // its buffered tail keeps playing while the next decoder spins up.
                    positionMs = 0
                    framesSinceSeek = 0
                    seekBaseMs = 0
                    idx = when (_state.value.repeat) {
                        RepeatMode.ONE -> idx
                        RepeatMode.ALL -> (idx + 1) % tracks.size
                        RepeatMode.OFF -> idx + 1
                    }
                    if (_state.value.repeat == RepeatMode.OFF && idx >= tracks.size) break
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (generation.get() == myGen) failure = t.message ?: "playback failed"
                    break
                } finally {
                    decoder?.release()
                }
            }

            if (generation.get() == myGen) {
                stopAudio()
                abandonFocus()
                _state.update { it.copy(status = Status.IDLE, error = failure) }
            }
        }
    }

    /** Pulls file-level tags + embedded art, overriding MediaStore guesses. */
    private fun enrichMetadata(track: PlayerTrack) {
        val meta = MetadataReader.read(appContext, Uri.parse(track.uri))
        meta.art?.let { ArtCache.put(track.uri, it) }
        _state.update {
            it.copy(
                title = meta.title?.takeIf { t -> t.isNotBlank() } ?: track.title,
                artist = meta.artist?.takeIf { a -> a.isNotBlank() } ?: track.artist,
                album = meta.album?.takeIf { a -> a.isNotBlank() } ?: track.album,
                artBitmap = ArtCache.get(track.uri),
                durationMs = if (meta.durationMs > 0) meta.durationMs / 1000
                else it.durationMs,
            )
        }
    }

    fun playSingle(track: PlayerTrack) = playQueue(listOf(track), 0)

    fun playAt(index: Int) {
        if (index in queue.indices) playQueue(queue, index)
    }

    fun queueSnapshot(): List<PlayerTrack> = queue

    fun setRepeat(mode: RepeatMode) {
        _state.update { it.copy(repeat = mode) }
    }

    /** Shuffles everything after the current track; playback continues. */
    fun shuffle() {
        val cur = _state.value.queueIndex
        if (cur !in queue.indices) return
        val current = queue[cur]
        queue = listOf(current) + queue.filterIndexed { i, _ -> i != cur }.shuffled()
        _state.update { it.copy(queueIndex = 0, queueSize = queue.size, shuffled = true) }
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
        seekBaseMs = positionMs
        framesSinceSeek = 0
        pendingSeekUs.set(positionMs * 1000)
        // Re-anchor the hardware counter AFTER the flush lands, so the delta
        // maps to post-seek audio. The drain in decodeLoop gives us the gap.
        Thread {
            Thread.sleep(120) // > ring drain + codec flush window
            reanchorRead()
        }.start()
    }

    fun next() {
        if (queue.isEmpty()) return
        val n = _state.value.queueIndex + 1
        when {
            n < queue.size -> playQueue(queue, n)
            _state.value.repeat == RepeatMode.ALL -> playQueue(queue, 0)
        }
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (positionMs > 3000) {
            seekTo(0)
        } else {
            val p = _state.value.queueIndex - 1
            when {
                p >= 0 -> playQueue(queue, p)
                _state.value.repeat == RepeatMode.ALL -> playQueue(queue, queue.lastIndex)
                else -> seekTo(0)
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        paused = false
        positionMs = 0
        stopAudio()
        abandonFocus()
        _state.update { it.copy(status = Status.IDLE) }
    }

    fun destroy() {
        stop()
        scope.cancel()
        runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
    }

    // ---- sleep timer ----

    private var sleepJob: Job? = null

    /** Minutes <= 0 cancels the timer. Pauses playback when it elapses. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepEndAt = 0L) }
            return
        }
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepEndAt = endAt) }
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            if (isActive && generation.get() >= 0) {
                pauseForFocus()
                _state.update { it.copy(sleepEndAt = 0L) }
            }
        }
    }

    private fun stopAudio() {
        // Drained close: let the buffered tail finish rendering (≤600ms)
        // so song endings are not clipped on transitions.
        Dsp.closeOutputDrained()
        if (engineHandle != 0L) {
            Dsp.destroyEngine(engineHandle)
            engineHandle = 0L
        }
    }
}
