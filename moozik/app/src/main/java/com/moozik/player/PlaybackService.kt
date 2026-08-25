package com.moozik.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.moozik.player.audio.MoozikPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service keeping playback alive in the background, with a
 * MediaSession-backed media notification (lockscreen / headset controls).
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.moozik.action.TOGGLE"
        const val ACTION_STOP = "com.moozik.action.STOP"
        const val ACTION_NEXT = "com.moozik.action.NEXT"
        const val ACTION_PREV = "com.moozik.action.PREV"

        private const val CHANNEL_ID = "moozik_playback"
        private const val NOTIFICATION_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        val (player, _) = PlayerBox.ensure(this)

        createChannel()
        session = MediaSessionCompat(this, "MoozikSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { PlayerBox.player?.togglePause() }
                override fun onPause() { PlayerBox.player?.togglePause() }
                override fun onSkipToNext() { PlayerBox.player?.next() }
                override fun onSkipToPrevious() { PlayerBox.player?.previous() }
                override fun onStop() { PlayerBox.player?.stop() }
            })
            isActive = true
        }

        scope.launch {
            player.state.collect { s ->
                updateSession(s)
                MoozikWidgetProvider.push(this@PlaybackService, s)
                if (s.status == MoozikPlayer.Status.IDLE && s.queueSize == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(s))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            // Launched for playback (from the library): promote immediately
            // to satisfy foreground-service start requirements.
            startAsForeground(
                buildNotification(PlayerBox.player?.state?.value ?: MoozikPlayer.PlayerState()),
            )
        } else {
            when (action) {
                ACTION_TOGGLE -> PlayerBox.player?.togglePause()
                ACTION_STOP -> PlayerBox.player?.stop()
                ACTION_NEXT -> PlayerBox.player?.next()
                ACTION_PREV -> PlayerBox.player?.previous()
            }
            val s = PlayerBox.player?.state?.value ?: MoozikPlayer.PlayerState()
            if (s.status == MoozikPlayer.Status.IDLE && s.queueSize == 0) {
                stopSelf()
            } else {
                startAsForeground(buildNotification(s))
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun updateSession(s: MoozikPlayer.PlayerState) {
        val ms = session ?: return
        ms.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.durationMs * 1000)
                .also { b -> s.artBitmap?.let { b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        val playing = s.status == MoozikPlayer.Status.PLAYING
        ms.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    PlayerBox.player?.positionMs()?.times(1000) ?: 0L,
                    if (playing) 1f else 0f,
                )
                .build()
        )
    }

    private fun buildNotification(s: MoozikPlayer.PlayerState): Notification {
        val playing = s.status == MoozikPlayer.Status.PLAYING

        fun action(a: String): PendingIntent = PendingIntent.getService(
            this, a.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(a),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(s.title.ifEmpty { "Moozik" })
            .setContentText(if (s.artist.isEmpty()) "ready" else "${s.artist} · ${s.album}")
            .setLargeIcon(s.artBitmap)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Prev", action(ACTION_PREV))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                action(ACTION_TOGGLE),
            )
            .addAction(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT))
            .setStyle(MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
