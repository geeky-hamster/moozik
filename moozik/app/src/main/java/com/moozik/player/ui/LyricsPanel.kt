package com.moozik.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moozik.player.audio.LrcParser
import com.moozik.player.audio.LyricsFetcher
import com.moozik.player.audio.MoozikPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Synced lyrics (LRCLib): active line highlighted + auto-scrolled,
 * tap any line to seek there. Falls back to plain lyrics, then a hint.
 */
@Composable
fun LyricsPanel(player: MoozikPlayer, positionMs: Long, modifier: Modifier = Modifier) {
    val state by player.state.collectAsState()

    var lyrics by remember(state.currentUri) {
        mutableStateOf<LyricsFetcher.Lyrics?>(null)
    }
    var loading by remember(state.currentUri) { mutableStateOf(false) }

    LaunchedEffect(state.currentUri, state.title, state.artist) {
        if (state.title.isBlank()) return@LaunchedEffect
        loading = true
        lyrics = withContext(Dispatchers.IO) {
            LyricsFetcher.cached("${state.artist}|${state.title}")
                ?: LyricsFetcher.fetch(state.artist, state.title, state.durationMs)
        }
        loading = false
    }

    val current = lyrics
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Lyrics",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                )
            }
            current == null || current.isEmpty -> {
                Text(
                    "No lyrics found for this track",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            current.synced.isNotEmpty() -> {
                val lines = current.synced
                val active = LrcParser.activeIndex(lines, positionMs)
                val listState = rememberLazyListState()

                LaunchedEffect(active) {
                    if (active >= 0) {
                        listState.animateScrollToItem(
                            index = (active - 2).coerceAtLeast(0),
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                ) {
                    items(lines.size, key = { it }) { i ->
                        val line = lines[i]
                        val isActive = i == active
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            maxLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = true) { player.seekTo(line.timeMs) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
            else -> {
                Text(
                    text = current.plain ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
        }
    }
}
