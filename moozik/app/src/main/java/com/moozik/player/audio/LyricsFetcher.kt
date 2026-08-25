package com.moozik.player.audio

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Synced-lyrics lookup via LRCLib.net (same source as the reference player).
 * Plain HttpURLConnection + org.json: no new dependencies.
 */
object LyricsFetcher {

    data class Lyrics(
        val synced: List<LrcParser.Line>,
        val plain: String?,
    ) {
        val isEmpty: Boolean get() = synced.isEmpty() && plain.isNullOrBlank()
    }

    private const val ENDPOINT = "https://lrclib.net/api/search"

    private val cache = object : LinkedHashMap<String, Lyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>) = size > 32
    }

    fun cached(key: String): Lyrics? = cache[key]

    /**
     * Blocking network call — invoke from a background dispatcher.
     * Returns empty Lyrics on any failure (offline, no match, bad JSON).
     */
    fun fetch(artist: String, title: String, durationMs: Long): Lyrics {
        val key = "$artist|$title"
        cache[key]?.let { return it }

        val lyrics = runCatching {
            val query = buildString {
                append(ENDPOINT)
                append("?track_name=").append(URLEncoder.encode(title, "UTF-8"))
                if (artist.isNotBlank()) {
                    append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
                }
            }

            val conn = (URL(query).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Moozik/1.4 (https://github.com/geeky-hamster/moozik)")
                setRequestProperty("Accept", "application/json")
            }

            val body = try {
                if (conn.responseCode != 200) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            } ?: return@runCatching Lyrics(emptyList(), null)

            val arr = JSONArray(body)
            var bestSynced: List<LrcParser.Line> = emptyList()
            var bestPlain: String? = null

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val syncedText = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
                if (syncedText != null && bestSynced.isEmpty()) {
                    bestSynced = LrcParser.parse(syncedText)
                }
                if (bestPlain == null) {
                    bestPlain = o.optString("plainLyrics", "").takeIf { it.isNotBlank() }
                }
                if (bestSynced.isNotEmpty()) break
            }

            Lyrics(bestSynced, bestPlain)
        }.getOrDefault(Lyrics(emptyList(), null))

        if (!lyrics.isEmpty) cache[key] = lyrics
        return lyrics
    }
}
