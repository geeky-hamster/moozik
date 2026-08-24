package com.moozik.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.moozik.player.audio.AutoEqParser
import com.moozik.player.audio.Dsp
import com.moozik.player.audio.EqController
import com.moozik.player.audio.MoozikPlayer
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
private val Faint = Color(0xFF222228)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    PlayerScreen()
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen() {
    val context = LocalContext.current
    val player = remember { MoozikPlayer(context.applicationContext) }
    val eq = remember { EqController(context.applicationContext) }
    LaunchedEffect(player, eq) { player.attachEq(eq) }

    val state by player.state.collectAsState()

    val selfTest by produceState("…") {
        value = withContext(Dispatchers.Default) { runSelfTest() }
    }
    var positionMs by mutableLongStateOf(0L)
    LaunchedEffect(state.status) {
        while (state.status == MoozikPlayer.Status.PLAYING) {
            positionMs = player.positionMs()
            delay(200)
        }
        positionMs = player.positionMs()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { player.play(it, queryTitle(context, it)) }
    }

    var dragPos by remember { mutableStateOf<Float?>(null) }
    var showEq by remember { mutableStateOf(false) }

    if (showEq) {
        EqScreen(eq = eq, onClose = { showEq = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "MOOZIK",
            color = Ink,
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 14.sp,
        )

        Spacer(Modifier.height(40.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        when (state.status) {
                            MoozikPlayer.Status.PLAYING -> Green
                            MoozikPlayer.Status.PAUSED -> Amber
                            MoozikPlayer.Status.PREPARING -> Gray
                            MoozikPlayer.Status.IDLE ->
                                if (selfTest.startsWith("PASS")) Green else Red
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = when (state.status) {
                    MoozikPlayer.Status.IDLE ->
                        if (selfTest.startsWith("PASS")) "ready" else selfTest.lineSequence().firstOrNull() ?: ""
                    MoozikPlayer.Status.PREPARING -> "loading"
                    MoozikPlayer.Status.PLAYING -> "${state.sampleRate / 1000} kHz · aaudio · float"
                    MoozikPlayer.Status.PAUSED -> "paused"
                },
                color = Sub,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = state.title.ifEmpty { "open something" },
            color = Ink,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        state.error?.let {
            Text(text = "⚠ $it", color = Red, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(28.dp))

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
            Text(
                fmt(dragPos?.let { (it * duration).toLong() } ?: positionMs),
                color = Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            Text(fmt(duration), color = Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                Text("OPEN", color = Ink, letterSpacing = 3.sp, fontSize = 13.sp)
            }
            TextButton(onClick = { showEq = true }) {
                Text("EQ", color = if (eq.enabled) Ink else Sub, letterSpacing = 3.sp, fontSize = 13.sp)
            }
            TextButton(
                onClick = { player.togglePause() },
                enabled = state.status == MoozikPlayer.Status.PLAYING ||
                    state.status == MoozikPlayer.Status.PAUSED,
            ) {
                Text(
                    if (state.status == MoozikPlayer.Status.PLAYING) "❚❚" else "▶",
                    color = Ink, fontSize = 15.sp,
                )
            }
            TextButton(
                onClick = { player.stop() },
                enabled = state.status == MoozikPlayer.Status.PLAYING ||
                    state.status == MoozikPlayer.Status.PAUSED,
            ) {
                Text("STOP", color = Ink, letterSpacing = 3.sp, fontSize = 13.sp)
            }
        }
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

        // Response curve
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
                drawLine(Faint.copy(alpha = 0.5f), Offset(0f, midY - db * pxPerDb), Offset(size.width, midY - db * pxPerDb), 1f)
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
            Switch(
                checked = eq.enabled,
                onCheckedChange = { eq.enabled = it; tick++ },
            )
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
                Text(graphicLabel(freq), color = Sub, fontSize = 11.sp, modifier = Modifier.size(width = 40.dp, height = 16.dp))
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
                    color = Ink, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
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

private fun graphicLabel(freq: Double): String =
    if (freq >= 1000) "${(freq / 1000).toInt()}k" else freq.toInt().toString()

private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

@Composable
private fun darkScheme() = MaterialTheme.colorScheme.copy(
    background = Bg, surface = Bg, primary = Ink, onPrimary = Bg,
)

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun queryTitle(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment ?: "untitled"

private fun runSelfTest(): String {
    return try {
        val ok = Dsp.selfCheck(48000.0, 1000.0, 1.0, 6.0)
        if (ok) "PASS dsp ${Dsp.version()}" else "FAIL dsp ${Dsp.version()}"
    } catch (e: Throwable) {
        "FAIL ${e.message}"
    }
}
