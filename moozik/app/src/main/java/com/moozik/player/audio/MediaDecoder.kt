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
                    val frames: Int
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
                    buffersOut++
                    framesOut += frames
                    if (buffersOut == 1L || buffersOut % 200L == 0L) {
                        Log.i(TAG, "out #$buffersOut frames=$frames total=$framesOut pts=${info.presentationTimeUs} enc=$pcmEncoding")
                    }
                    if (frames > 0) sink(conv, frames, info.presentationTimeUs)
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
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
