package com.moozik.player.ui

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.moozik.player.audio.MoozikPlayer
import com.moozik.player.audio.PlayerTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

suspend fun loadLibrary(context: Context): List<PlayerTrack> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
    )
    val cursor = context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        null,
        "artist, album, title",
    ) ?: return@withContext emptyList()

    cursor.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

        buildList {
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                add(
                    PlayerTrack(
                        uri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString(),
                        ).toString(),
                        title = c.getString(titleCol) ?: "untitled",
                        artist = c.getString(artistCol) ?: "",
                        album = c.getString(albumCol) ?: "",
                        artUri = Uri.withAppendedPath(
                            Uri.parse("content://media/external/audio/albumart"),
                            c.getLong(albumIdCol).toString(),
                        ).toString(),
                        durationMs = c.getLong(durationCol),
                    ),
                )
            }
        }
    }
}

/** Ticks playback position while a track is playing. */
@Composable
fun rememberPosition(player: MoozikPlayer, playing: Boolean): State<Long> =
    produceState(0L, playing) {
        while (true) {
            value = player.positionMs()
            delay(if (playing) 250L else 700L)
        }
    }

/** Album art thumbnail with a tasteful fallback glyph. */
@Composable
fun Artwork(artUri: String?, size: Int, corner: Int = 8) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size * 0.5).dp),
        )
        if (artUri != null) {
            AsyncImage(
                model = artUri,
                contentDescription = null,
                modifier = Modifier.size(size.dp),
            )
        }
    }
}

/** Full-bleed artwork for the Now Playing sheet. */
@Composable
fun ArtworkLarge(artUri: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        if (artUri != null) {
            AsyncImage(
                model = artUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Keeps a LaunchedEffect-style ticker alive; used by seek bars. */
@Composable
fun PositionSync(player: MoozikPlayer, playing: Boolean, onTick: (Long) -> Unit) {
    LaunchedEffect(playing) {
        while (true) {
            onTick(player.positionMs())
            delay(if (playing) 250L else 700L)
        }
    }
}
