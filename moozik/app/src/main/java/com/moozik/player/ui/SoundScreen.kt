package com.moozik.player.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moozik.player.audio.AutoEqParser
import com.moozik.player.audio.EqController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundScreen(eq: EqController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var tick by remember { mutableIntStateOf(0) }

    val presetPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = readTextFile(context, uri) ?: return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri).removeSuffix(".txt")
        eq.importPreset(AutoEqParser.parse(text, name))
        tick++
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            LargeTopAppBar(
                title = { Text("Sound") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Response curve ----
            Card(modifier = Modifier.fillMaxWidth()) {
                val gridColor = MaterialTheme.colorScheme.outlineVariant
                val curveColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(16.dp),
                ) {
                    val response = eq.magnitudeResponse(sampleRate = 48000, points = 192)
                    val midY = size.height / 2f
                    val dbRange = 15f
                    val pxPerDb = midY / dbRange

                    drawLine(gridColor, Offset(0f, midY), Offset(size.width, midY), 1f)
                    listOf(-6f, 6f).forEach { db ->
                        drawLine(
                            gridColor.copy(alpha = 0.5f),
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
                        drawPath(path, curveColor, style = Stroke(2.dp.toPx()))
                    }
                }
            }

            // ---- Master ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Equalizer", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (eq.enabled) "shaping your sound" else "bypassed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = eq.enabled, onCheckedChange = { eq.enabled = it; tick++ })
                }
            }

            // ---- Preamp ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    var preamp by remember(eq.preampDb) { mutableFloatStateOf(eq.preampDb.toFloat()) }
                    Text("Preamp", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = preamp,
                            onValueChange = {
                                preamp = it
                                eq.setPreamp(it.toDouble())
                            },
                            valueRange = -12f..6f,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "%+.1f dB".format(preamp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(64.dp),
                        )
                    }
                }
            }

            // ---- Graphic EQ ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Graphic", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    EqController.GRAPHIC_FREQS.forEachIndexed { i, freq ->
                        var gain by remember(i, tick) { mutableFloatStateOf(eq.graphicGains[i].toFloat()) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                graphicLabel(freq),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(40.dp),
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
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "%+d".format(gain.toInt()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(36.dp),
                            )
                        }
                    }
                }
            }

            // ---- AutoEq ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("AutoEq", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = eq.preset?.let { p ->
                            "${p.name} — ${p.bands.size} filters, preamp ${"%+.1f".format(p.preampDb)} dB"
                        } ?: "Import a ParametricEQ.txt preset for your headphones from autoeq.app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            presetPicker.launch(arrayOf("text/*", "application/octet-stream"))
                        }) {
                            Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import")
                        }
                        if (eq.preset != null) {
                            FilledTonalButton(onClick = { eq.clearPreset(); tick++ }) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun graphicLabel(freq: Double): String =
    if (freq >= 1000) "${(freq / 1000).toInt()}k" else freq.toInt().toString()

private fun readTextFile(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

private fun queryDisplayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment ?: "preset"
