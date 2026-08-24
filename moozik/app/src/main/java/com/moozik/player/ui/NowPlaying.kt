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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moozik.player.audio.Dsp
import com.moozik.player.audio.MoozikPlayer
import com.moozik.player.audio.RepeatMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(player: MoozikPlayer, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by player.state.collectAsState()
    val playing = state.status == MoozikPlayer.Status.PLAYING

    var dragPos by remember { mutableStateOf<Float?>(null) }
    val position by rememberPosition(player, playing)
    val upcoming = remember(state.queueIndex, state.queueSize) {
        player.queueSnapshot()
            .drop(state.queueIndex + 1)
            .take(6)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtworkRich(
                artUri = state.artUri,
                artBitmap = state.artBitmap,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            Text(
                state.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(state.artist, state.album).filter { it.isNotEmpty() }
                    .joinToString(" · ").ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (state.status == MoozikPlayer.Status.PREPARING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(16.dp))

            val duration = state.durationMs.coerceAtLeast(0)
            val fraction =
                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = dragPos ?: fraction,
                onValueChange = { dragPos = it },
                onValueChangeFinished = {
                    dragPos?.let { player.seekTo((it * duration).toLong()) }
                    dragPos = null
                },
                enabled = duration > 0 && state.status != MoozikPlayer.Status.PREPARING,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatDuration(dragPos?.let { (it * duration).toLong() } ?: position),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDuration(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Shuffle | prev | play | next | repeat
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { player.shuffle() }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { player.previous() }) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                FilledIconButton(
                    onClick = { player.togglePause() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(
                    onClick = { player.next() },
                    enabled = state.queueIndex < state.queueSize - 1 ||
                        state.repeat == RepeatMode.ALL,
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
                IconButton(onClick = {
                    player.setRepeat(
                        when (state.repeat) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        },
                    )
                }) {
                    Icon(
                        if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne
                        else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.repeat == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = buildString {
                    append("${state.queueIndex + 1} / ${state.queueSize}")
                    if (state.sampleRate > 0) append("  ·  ${state.sampleRate / 1000} kHz")
                    Dsp.outputInfo().takeIf { it.isNotEmpty() }?.let { append("  ·  $it") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- Up Next ----
            if (upcoming.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Up next",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                upcoming.forEachIndexed { i, t ->
                    val absoluteIndex = state.queueIndex + 1 + i
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { player.playAt(absoluteIndex) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(t.artUri, 40, corner = 6)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                t.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            formatDuration(t.durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
