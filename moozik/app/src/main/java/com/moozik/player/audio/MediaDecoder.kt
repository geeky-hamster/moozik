package com.moozik.player.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MoozikDecode"

data class TrackInfo(
    val sampleRate: Int,
    val channels: Int,
    val durationUs: Long,
    val mime: String,
)

/**
 * MediaCodec-backed decoder producing interleaved stereo float PCM.
 * Both 16-bit and float codec outputs are normalized to float.
 * Channels beyond the first two are currently dropped (documented v0.2 limit).
 */
class MediaDecoder(context: Context) {

    private val appContext = context.applicationContext
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var conv = FloatArray(16384)

    // Gapless (ref: Symphonia-based players): MP3 streams carry encoder
    // delay/padding — decoder-added silence at both ends that must be
    // trimmed sample-exactly for gapless albums and honest durations.
    private var gaplessDelayFrames = 0L
    private var gaplessPaddingFrames = 0L
    private var expectedFrames = 0L
    private var trackSampleRate = 0

    fun open(uri: Uri): TrackInfo {
        extractor.setDataSource(appContext, uri, null)
        return openSelected()
    }

    fun openRaw(dataSource: String): TrackInfo {
        extractor.setDataSource(dataSource)
        return openSelected()
    }

    private fun openSelected(): TrackInfo {
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        checkNotNull(format) { "no audio track found" }
        extractor.selectTrack(trackIndex)

        val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }

        // LAME/Xing gapless metadata, surfaced by the platform extractor
        // when present (MP3). Values are in sample frames.
        gaplessDelayFrames = if (format.containsKey("encoder-delay")) {
            runCatching { format.getInteger("encoder-delay").toLong() }.getOrDefault(0L)
        } else 0L
        gaplessPaddingFrames = if (format.containsKey("encoder-padding")) {
            runCatching { format.getInteger("encoder-padding").toLong() }.getOrDefault(0L)
        } else 0L

        val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        trackSampleRate = sr
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        expectedFrames = if (durationUs > 0) durationUs * sr / 1_000_000L else 0L
        if (gaplessDelayFrames > 0 || gaplessPaddingFrames > 0) {
            Log.i(TAG, "gapless: delay=$gaplessDelayFrames pad=$gaplessPaddingFrames expected=$expectedFrames")
        }

        Log.i(
            TAG,
            "decoder ready: mime=$mime rate=${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} " +
                "ch=${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}",
        )

        return TrackInfo(
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L,
            mime = mime,
        )
    }

    /**
     * Produces samples into [sink](stereo interleaved floats, frames, ptsUs) until
     * end-of-stream or [isActive] turns false. Returns true when EOS was reached.
     * A non-negative value observed in [pendingSeekUs] triggers seek+flush+drain.
     */
    fun decodeLoop(
        isActive: () -> Boolean,
        isPaused: () -> Boolean,
        pendingSeekUs: AtomicLong,
        sink: (FloatArray, Int, Long) -> Unit,
        onDrained: () -> Unit,
    ): Boolean {
        val c = checkNotNull(codec)
        val info = MediaCodec.BufferInfo()
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var outChannels = 2
        var sawEos = false
        var buffersOut = 0L
        var framesOut = 0L
        var skipHead = gaplessDelayFrames
        var emitted = 0L
        Log.i(TAG, "decode loop enter")

        while (!sawEos && isActive()) {
            val seekUs = pendingSeekUs.getAndSet(-1L)
            if (seekUs >= 0L) {
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                c.flush()
                onDrained()
                continue
            }
            if (isPaused()) {
                Thread.sleep(20)
                continue
            }

            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib = checkNotNull(c.getInputBuffer(inIdx))
                val n = extractor.readSampleData(ib, 0)
                if (n < 0) {
                    c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    c.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                }
            }

            val outIdx = c.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    Log.i(TAG, "format changed: enc=$pcmEncoding ch=$outChannels")
                }
                outIdx >= 0 -> {
                    val ob = checkNotNull(c.getOutputBuffer(outIdx))
                    ob.order(ByteOrder.nativeOrder())
                    var frames: Int
                    when (pcmEncoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            frames = info.size / 4 / outChannels
                            stereoFromFloat(ob, frames, outChannels)
                        }
                        else -> {
                            frames = info.size / 2 / outChannels
                            stereoFromShort(ob, frames, outChannels)
                        }
                    }

                    // --- gapless trimming ---
                    if (skipHead > 0 && frames > 0) {
                        val cut = minOf(skipHead, frames.toLong()).toInt()
                        skipHead -= cut
                        frames -= cut
                        if (frames > 0) {
                            System.arraycopy(conv, cut * 2, conv, 0, frames * 2)
                        }
                    }
                    if (expectedFrames > 0 && emitted + frames > expectedFrames) {
                        frames = (expectedFrames - emitted).toInt().coerceAtLeast(0)
                    }

                    buffersOut++
                    if (frames > 0) {
                        emitted += frames
                        framesOut += frames
                        if (buffersOut == 1L || buffersOut % 200L == 0L) {
                            Log.i(TAG, "out #$buffersOut frames=$frames total=$framesOut pts=${info.presentationTimeUs} enc=$pcmEncoding")
                        }
                        sink(conv, frames, info.presentationTimeUs)
                    }
                    c.releaseOutputBuffer(outIdx, false)

                    if (expectedFrames > 0 && emitted >= expectedFrames) {
                        // Real audio exhausted; remaining codec output is padding.
                        sawEos = true
                        Log.i(TAG, "gapless EOS after $emitted frames (padding skipped)")
                    } else if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEos = true
                        Log.i(TAG, "EOS after $buffersOut buffers, $framesOut frames")
                    }
                }
                else -> Unit // TRY_AGAIN_LATER: go feed more input
            }
        }
        Log.i(TAG, "decode loop exit: eos=$sawEos buffers=$buffersOut frames=$framesOut")
        return sawEos
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor.release() }
    }

    private fun stereoFromFloat(ob: ByteBuffer, frames: Int, channels: Int) {
        ensureCapacity(frames)
        val fb = ob.asFloatBuffer()
        for (f in 0 until frames) {
            val base = f * channels
            val l = fb.get(base)
            val r = if (channels == 1) l else fb.get(base + 1)
            conv[f * 2] = l
            conv[f * 2 + 1] = r
        }
    }

    private fun stereoFromShort(ob: ByteBuffer, frames: Int, channels: Int) {
        ensureCapacity(frames)
        val sb = ob.asShortBuffer()
        val scale = 1f / 32767f
        for (f in 0 until frames) {
            val base = f * channels
            val l = sb.get(base) * scale
            val r = if (channels == 1) l else sb.get(base + 1) * scale
            conv[f * 2] = l
            conv[f * 2 + 1] = r
        }
    }

    private fun ensureCapacity(frames: Int) {
        if (conv.size < frames * 2) conv = FloatArray(frames * 2)
    }
}
