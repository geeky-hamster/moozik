package com.moozik.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moozik.player.audio.MoozikPlayer

/** Compact bar above the bottom navigation; tap to expand Now Playing. */
@Composable
fun MiniPlayer(player: MoozikPlayer, onExpand: () -> Unit) {
    val state by player.state.collectAsState()
    val playing = state.status == MoozikPlayer.Status.PLAYING
    val position by rememberPosition(player, playing)

    Surface(
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onExpand),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.artUri, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title.ifEmpty { "Moozik" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            state.status == MoozikPlayer.Status.PREPARING -> "loading…"
                            state.artist.isNotEmpty() -> state.artist
                            else -> "${state.queueIndex + 1} / ${state.queueSize}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = {
                        if (state.status != MoozikPlayer.Status.IDLE) player.togglePause()
                    },
                ) {
                    if (state.status == MoozikPlayer.Status.PREPARING) {
                        LinearProgressIndicator(Modifier.size(20.dp))
                    } else {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                        )
                    }
                }
                IconButton(onClick = { player.next() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
            }

            // Progress hairline
            if (state.durationMs > 0 && state.status != MoozikPlayer.Status.PREPARING) {
                LinearProgressIndicator(
                    progress = {
                        (position.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}
