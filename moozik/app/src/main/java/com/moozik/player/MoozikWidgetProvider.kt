package com.moozik.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.moozik.player.audio.MoozikPlayer

/**
 * 4x1 home-screen widget: artwork, track info, transport controls.
 * Updates are pushed by [PlaybackService] on every player-state change;
 * buttons reuse the service's existing action intents.
 */
class MoozikWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Nothing cached yet — render the idle shell; the service will push
        // real state as soon as playback starts.
        push(context, MoozikPlayer.PlayerState())
    }

    companion object {
        private fun action(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(),
            Intent(context, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun push(context: Context, s: MoozikPlayer.PlayerState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, MoozikWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return

            val playing = s.status == MoozikPlayer.Status.PLAYING
            val views = RemoteViews(context.packageName, R.layout.widget_moozik).apply {
                setTextViewText(R.id.w_title, s.title.ifEmpty { "Moozik" })
                setTextViewText(
                    R.id.w_artist,
                    when {
                        s.artist.isNotEmpty() -> s.artist
                        s.status == MoozikPlayer.Status.PREPARING -> "loading…"
                        else -> "ready"
                    },
                )
                if (s.artBitmap != null) {
                    setImageViewBitmap(R.id.w_art, s.artBitmap)
                } else {
                    setImageViewResource(R.id.w_art, R.drawable.ic_notif)
                }
                setImageViewResource(
                    R.id.w_toggle,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                )
                setOnClickPendingIntent(R.id.w_prev, action(context, PlaybackService.ACTION_PREV))
                setOnClickPendingIntent(R.id.w_toggle, action(context, PlaybackService.ACTION_TOGGLE))
                setOnClickPendingIntent(R.id.w_next, action(context, PlaybackService.ACTION_NEXT))
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
