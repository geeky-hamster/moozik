package com.moozik.player.ui

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moozik.player.PlaybackService
import com.moozik.player.audio.MoozikPlayer
import com.moozik.player.audio.PlayerTrack

enum class SortMode(val label: String) {
    ARTIST("Artist"), TITLE("Title"), ALBUM("Album"), DURATION("Duration"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    player: MoozikPlayer,
    tracks: List<PlayerTrack>,
    onOpenSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by player.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.ARTIST) }
    var sortMenu by remember { mutableStateOf(false) }

    val filtered = remember(tracks, query, sort) {
        val q = query.trim().lowercase()
        val base = if (q.isEmpty()) tracks else tracks.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
        when (sort) {
            SortMode.TITLE -> base.sortedBy { it.title.lowercase() }
            SortMode.ALBUM -> base.sortedBy { it.album.lowercase() }
            SortMode.DURATION -> base.sortedByDescending { it.durationMs }
            SortMode.ARTIST -> base // already artist-sorted from MediaStore
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            LargeTopAppBar(
                title = { Text("Moozik") },
                actions = {
                    IconButton(onClick = onOpenSound) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = "Sound settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search music") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sort.label) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                onClick = { sort = m; sortMenu = false },
                            )
                        }
                    }
                }
            }

            if (tracks.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(filtered, key = { _, t -> t.uri }) { index, track ->
                        val isCurrent = state.currentUri == track.uri &&
                            state.status != MoozikPlayer.Status.IDLE
                        ListItem(
                            headlineContent = {
                                Text(
                                    track.title,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    listOf(track.artist, track.album)
                                        .filter { it.isNotEmpty() }
                                        .joinToString(" · ")
                                        .ifEmpty { "unknown" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { Artwork(track.artUri, 48) },
                            trailingContent = {
                                Text(
                                    formatDuration(track.durationMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable {
                                // Ensure the foreground service (notification) is alive.
                                ContextCompat.startForegroundService(
                                    context,
                                    Intent(context, PlaybackService::class.java),
                                )
                                player.playQueue(filtered, index)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Copy some music to your device and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
