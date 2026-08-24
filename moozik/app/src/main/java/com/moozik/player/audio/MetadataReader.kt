package com.moozik.player.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Authoritative metadata straight from the audio file, overriding whatever
 * MediaStore has cached: tags, exact duration and embedded cover art.
 */
object MetadataReader {

    data class Meta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val art: Bitmap?,
    )

    fun read(context: Context, uri: Uri): Meta {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            Meta(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                art = mmr.embeddedPicture?.let { bytes ->
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                },
            )
        } catch (_: Throwable) {
            Meta(null, null, null, 0L, null)
        } finally {
            runCatching { mmr.release() }
        }
    }
}

/** Small in-memory cache for decoded cover art, keyed by track uri. */
object ArtCache {
    private const val MAX = 24
    private val map = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX
    }

    @Synchronized
    fun put(uri: String, bitmap: Bitmap) {
        map[uri] = bitmap
    }

    @Synchronized
    fun get(uri: String): Bitmap? = map[uri]
}
