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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moozik.player.audio.Dsp
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
            Text(fmt(dragPos?.let { (it * duration).toLong() } ?: positionMs),
                color = Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(fmt(duration), color = Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                Text("OPEN", color = Ink, letterSpacing = 3.sp, fontSize = 13.sp)
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
