package com.moozik.player.audio

data class PlayerTrack(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val artUri: String? = null,
    val durationMs: Long = 0L,
)
