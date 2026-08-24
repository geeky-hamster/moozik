package com.moozik.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.moozik.player.audio.Dsp
import com.moozik.player.audio.MoozikPlayer
import com.moozik.player.audio.PlayerTrack
import com.moozik.player.ui.LibraryScreen
import com.moozik.player.ui.MiniPlayer
import com.moozik.player.ui.MoozikTheme
import com.moozik.player.ui.NowPlayingSheet
import com.moozik.player.ui.SettingsScreen
import com.moozik.player.ui.SoundScreen
import com.moozik.player.ui.ThemeMode
import com.moozik.player.ui.loadLibrary

private const val UI_PREFS = "moozik_ui"
private const val KEY_THEME = "theme_mode"

private fun loadThemeMode(context: Context): ThemeMode = runCatching {
    ThemeMode.valueOf(
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name,
    )
}.getOrDefault(ThemeMode.SYSTEM)

private fun saveThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_THEME, mode.name).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(loadThemeMode(context)) }

            MoozikTheme(themeMode) {
                MoozikApp(
                    themeMode = themeMode,
                    onThemeMode = { themeMode = it; saveThemeMode(context, it) },
                )
            }
        }
    }
}

@Composable
private fun MoozikApp(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    val context = LocalContext.current
    val (player, eq) = remember { PlayerBox.ensure(context) }
    val state by player.state.collectAsState()

    // ---- permissions ----
    val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { audioGranted = it }

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(audioGranted) {
        if (audioGranted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!audioGranted) {
        PermissionGate(onGrant = { askAudio.launch(audioPermission) })
        return
    }

    // ---- content ----
    var tracks by remember { mutableStateOf<List<PlayerTrack>>(emptyList()) }
    LaunchedEffect(Unit) { tracks = loadLibrary(context) }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showNowPlaying by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            Column {
                AnimatedVisibility(state.status != MoozikPlayer.Status.IDLE) {
                    MiniPlayer(player = player, onExpand = { showNowPlaying = true })
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                        label = { Text("Sound") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> LibraryScreen(
                player = player,
                tracks = tracks,
                onOpenSound = { tab = 1 },
                modifier = Modifier.padding(padding),
            )
            1 -> SoundScreen(eq = eq, modifier = Modifier.padding(padding))
            2 -> SettingsScreen(
                themeMode = themeMode,
                onThemeMode = onThemeMode,
                outputSummary = Dsp.outputInfo(),
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showNowPlaying) {
        NowPlayingSheet(player = player, onDismiss = { showNowPlaying = false })
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Moozik", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Allow access to your music library to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant) { Text("Grant access") }
        }
    }
}
