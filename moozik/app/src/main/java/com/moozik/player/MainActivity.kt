package com.moozik.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moozik.player.audio.Dsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StatusScreen()
                }
            }
        }
    }
}

private fun darkScheme() = androidx.compose.material3.darkColorScheme(
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF121215),
    primary = Color(0xFFE8E8EC),
    onBackground = Color(0xFFE8E8EC),
    onSurface = Color(0xFFB9B9C0),
    outline = Color(0xFF2A2A30),
)

@androidx.compose.runtime.Composable
private fun StatusScreen() {
    val state by produceState("booting") {
        value = withContext(Dispatchers.Default) { runSelfTest() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "MOOZIK",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 14.sp,
        )

        Box(modifier = Modifier.size(width = 0.dp, height = 36.dp))

        val pass = state.startsWith("PASS")
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (pass) Color(0xFF4ADE80) else Color(0xFFF87171), CircleShape),
        )

        Box(modifier = Modifier.size(width = 0.dp, height = 16.dp))

        Text(
            text = state,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp,
        )
    }
}

private fun runSelfTest(): String {
    return try {
        val v = Dsp.version()
        val ok = Dsp.selfCheck(48000.0, 1000.0, 1.0, 6.0)
        val c = Dsp.peakingCoefficients(48000.0, 1000.0, 1.0, 6.0)
        buildString {
            appendLine(if (ok) "PASS  dsp $v" else "FAIL  dsp $v")
            appendLine("b  %.6f %.6f %.6f".format(c[0], c[1], c[2]))
            appendLine("a  %.6f %.6f".format(c[3], c[4]))
        }
    } catch (e: Throwable) {
        "FAIL ${e.message}"
    }
}
