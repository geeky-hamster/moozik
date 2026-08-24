package com.moozik.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.moozik.player.audio.AutoEqParser
import com.moozik.player.audio.Dsp
import com.moozik.player.audio.EqController
import com.moozik.player.audio.MoozikPlayer
import com.moozik.player.audio.PlayerTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0B0B0D)
private val Ink = Color(0xFFE8E8EC)
private val Sub = Color(0xFF9A9AA2)
private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)
private val Gray = Color(0xFF3A3A42)
private val Faint = Color(0xFF161619)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    MoozikRoot()
                }
            }
        }
    }
}

@Composable
private fun MoozikRoot() {
    val context = LocalContext.current
    val (player, eq) = remember { PlayerBox.ensure(context) }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    val askNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // Media notification visibility on 13+
    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!granted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("MOOZIK", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = 14.sp)
            Spacer(Modifier.height(24.dp))
            Text("access to your music library is needed", color = Sub, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { askPermission.launch(permission) }) {
                Text("GRANT", color = Ink, letterSpacing = 3.sp)
            }
        }
        return
    }

    LibraryScreen(player = player, eq = eq)
}

@Composable
private fun LibraryScreen(player: MoozikPlayer, eq: EqController) {
    val context = LocalContext.current
    var tracks by remember { mutableStateOf<List<PlayerTrack>>(emptyList()) }

    LaunchedEffect(Unit) {
        tracks = loadLibrary(context)
    }

    val state by player.state.collectAsState()
    var showEq by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }

    if (showEq) {
        EqScreen(eq = eq, onClose = { showEq = false })
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("MOOZIK", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 10.sp)
            TextButton(onClick = { showEq = true }) {
                Text("EQ", color = if (eq.enabled) Ink else Sub, letterSpacing = 3.sp, fontSize = 13.sp)
            }
        }

        Text(
            "${tracks.size} tracks",
            color = Sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(tracks, key = { _, t -> t.uri }) { index, track ->
                TrackRow(
                    track = track,
                    playingThis = state.title == track.title && state.status != MoozikPlayer.Status.IDLE &&
                        state.queueIndex == index,
                    onClick = {
                        ContextCompat.startForegroundService(
                            context, Intent(context, PlaybackService::class.java),
                        )
                        player.playQueue(tracks, index)
                    },
                )
            }
        }

        if (state.status != MoozikPlayer.Status.IDLE) {
            MiniBar(state = state, onExpand = { showNowPlaying = true }, onToggle = { player.togglePause() })
        }
    }

    if (showNowPlaying) {
        NowPlayingOverlay(player = player, onClose = { showNowPlaying = false })
    }
}

@Composable
private fun TrackRow(track: PlayerTrack, playingThis: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.artUri,
            contentDescription = null,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Faint),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (playingThis) Green else Ink,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifEmpty { "unknown artist" },
                color = Sub, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            fmt(track.durationMs),
            color = Sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MiniBar(state: MoozikPlayer.PlayerState, onExpand: () -> Unit, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Faint)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(state.title, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${state.queueIndex + 1} / ${state.queueSize}",
                color = Sub, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(onClick = onToggle) {
            Text(if (state.status == MoozikPlayer.Status.PLAYING) "❚❚" else "▶", color = Ink, fontSize = 14.sp)
        }
        TextButton(onClick = onExpand) {
            Text("OPEN", color = Sub, letterSpacing = 2.sp, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NowPlayingOverlay(player: MoozikPlayer, onClose: () -> Unit) {
    val state by player.state.collectAsState()

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.status) {
        while (true) {
            positionMs = player.positionMs()
            delay(if (state.status == MoozikPlayer.Status.PLAYING) 200 else 500)
        }
    }

    var dragPos by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("NOW PLAYING", color = Sub, fontSize = 11.sp, letterSpacing = 4.sp)
            TextButton(onClick = onClose) { Text("CLOSE", color = Sub, letterSpacing = 2.sp, fontSize = 12.sp) }
        }

        Spacer(Modifier.height(12.dp))

        AsyncImage(
            model = state.artUri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Faint),
        )

        Spacer(Modifier.height(24.dp))

        Text(state.title, color = Ink, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = listOf(state.artist, state.album).filter { it.isNotEmpty() }.joinToString(" · ")
                .ifEmpty { "unknown" },
            color = Sub, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(20.dp))

        val duration = state.durationMs.coerceAtLeast(0)
        val fraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = dragPos ?: fraction,
            onValueChange = { dragPos = it },
            onValueChangeFinished = {
                dragPos?.let { player.seekTo((it * duration).toLong()) }
                dragPos = null
            },
            enabled = duration > 0,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(dragPos?.let { (it * duration).toLong() } ?: positionMs),
                color = Sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(fmt(duration), color = Sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { player.previous() }, enabled = state.queueIndex > 0) {
                Text("⏮", color = Ink, fontSize = 18.sp)
            }
            TextButton(onClick = { player.togglePause() }) {
                Text(
                    if (state.status == MoozikPlayer.Status.PLAYING) "❚❚" else "▶",
                    color = Ink, fontSize = 22.sp,
                )
            }
            TextButton(onClick = { player.next() }, enabled = state.queueIndex < state.queueSize - 1) {
                Text("⏭", color = Ink, fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = buildString {
                append("${state.queueIndex + 1} / ${state.queueSize} · ")
                if (state.sampleRate > 0) append("${state.sampleRate / 1000} kHz · ")
                append(Dsp.outputInfo().ifEmpty { "idle output" })
            },
            color = Sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text("⚠ $it", color = Red, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun EqScreen(eq: EqController, onClose: () -> Unit) {
    val context = LocalContext.current
    var tick by mutableIntStateOf(0)
    val scroll = rememberScrollState()

    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = readText(context, uri) ?: return@rememberLauncherForActivityResult
        val name = queryTitle(context, uri).removeSuffix(".txt")
        eq.importPreset(AutoEqParser.parse(text, name))
        tick++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("EQ", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 10.sp)
            TextButton(onClick = onClose) {
                Text("DONE", color = Sub, letterSpacing = 3.sp, fontSize = 13.sp)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(vertical = 8.dp),
        ) {
            val response = eq.magnitudeResponse(sampleRate = 48000, points = 192)
            val midY = size.height / 2f
            val dbRange = 15f
            val pxPerDb = midY / dbRange

            drawLine(Faint, Offset(0f, midY), Offset(size.width, midY), 1.dp.toPx())
            listOf(-6f, 6f).forEach { db ->
                drawLine(
                    Faint.copy(alpha = 0.5f),
                    Offset(0f, midY - db * pxPerDb),
                    Offset(size.width, midY - db * pxPerDb),
                    1f,
                )
            }

            if (response.any { it != 0f }) {
                val path = Path()
                response.forEachIndexed { i, db ->
                    val x = i.toFloat() / (response.size - 1) * size.width
                    val y = midY - db.coerceIn(-dbRange, dbRange) * pxPerDb
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Green, style = Stroke(width = 2.dp.toPx()))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("enabled", color = Sub, fontSize = 14.sp)
            Switch(checked = eq.enabled, onCheckedChange = { eq.enabled = it; tick++ })
        }

        var preamp by remember(eq.preampDb) { mutableFloatStateOf(eq.preampDb.toFloat()) }
        Text(
            "preamp  %+.1f dB".format(preamp),
            color = Sub, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
        )
        Slider(
            value = preamp,
            onValueChange = {
                preamp = it
                eq.setPreamp(it.toDouble())
                tick++
            },
            valueRange = -12f..6f,
        )

        Text("graphic", color = Sub, fontSize = 14.sp, letterSpacing = 4.sp)
        EqController.GRAPHIC_FREQS.forEachIndexed { i, freq ->
            var gain by remember(i, tick) { mutableFloatStateOf(eq.graphicGains[i].toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    graphicLabel(freq), color = Sub, fontSize = 11.sp,
                    modifier = Modifier.size(width = 40.dp, height = 16.dp),
                )
                Slider(
                    value = gain,
                    onValueChange = {
                        gain = it
                        eq.setGraphicGainSilent(i, it.toDouble())
                    },
                    onValueChangeFinished = {
                        eq.commitGraphic(i)
                        tick++
                    },
                    valueRange = -12f..12f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%+d".format(gain.toInt()),
                    color = Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.size(width = 34.dp, height = 16.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("autoeq", color = Sub, fontSize = 14.sp, letterSpacing = 4.sp)
        Text(
            text = eq.preset?.let { p ->
                "${p.name} · ${p.bands.size} filters · preamp ${"%+.1f".format(p.preampDb)} dB"
            } ?: "no preset loaded",
            color = Ink,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { presetPicker.launch(arrayOf("text/*", "application/octet-stream")) }) {
                Text("IMPORT", color = Ink, letterSpacing = 3.sp, fontSize = 13.sp)
            }
            if (eq.preset != null) {
                TextButton(onClick = { eq.clearPreset(); tick++ }) {
                    Text("CLEAR", color = Red, letterSpacing = 3.sp, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ---- helpers ----

@Composable
private fun darkScheme() = MaterialTheme.colorScheme.copy(
    background = Bg, surface = Bg, primary = Ink, onPrimary = Bg,
)

private suspend fun loadLibrary(context: Context): List<PlayerTrack> = withContext(Dispatchers.IO) {
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
                        uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()).toString(),
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

private fun graphicLabel(freq: Double): String =
    if (freq >= 1000) "${(freq / 1000).toInt()}k" else freq.toInt().toString()

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

private fun queryTitle(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment ?: "untitled"
