// SPDX-License-Identifier: GPL-3.0-only
package io.github.currencortex.music.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.currencortex.music.MainActivity
import io.github.currencortex.music.R
import io.github.currencortex.music.core.media.MusicService
import io.github.currencortex.music.data.song.Song

class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views(context)) }
    }

    companion object {
        private const val PREFS = "player_widget"
        private const val TITLE = "title"
        private const val ARTIST = "artist"
        private const val PLAYING = "playing"

        fun update(context: Context, song: Song?, playing: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(TITLE, song?.name.orEmpty())
                .putString(ARTIST, song?.artists.orEmpty())
                .putBoolean(PLAYING, playing)
                .apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString(TITLE, null).orEmpty().ifBlank { "PromX" }
            val artist = prefs.getString(ARTIST, null).orEmpty().ifBlank { "点击打开播放器" }
            val playing = prefs.getBoolean(PLAYING, false)
            return RemoteViews(context.packageName, R.layout.widget_player).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_artist, artist)
                setTextViewText(R.id.widget_play, if (playing) "Ⅱ" else "▶")
                setOnClickPendingIntent(R.id.widget_root, activityIntent(context))
                setOnClickPendingIntent(R.id.widget_previous, commandIntent(context, WidgetCommandReceiver.PREVIOUS, 1))
                setOnClickPendingIntent(R.id.widget_play, commandIntent(context, WidgetCommandReceiver.TOGGLE, 2))
                setOnClickPendingIntent(R.id.widget_next, commandIntent(context, WidgetCommandReceiver.NEXT, 3))
            }
        }

        private fun activityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun commandIntent(context: Context, action: String, request: Int): PendingIntent =
            PendingIntent.getBroadcast(context, request, Intent(context, WidgetCommandReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class WidgetCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching {
                val controller = future.get()
                when (intent.action) {
                    TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                    NEXT -> controller.seekToNext()
                    PREVIOUS -> controller.seekToPrevious()
                }
                controller.release()
            }
            pending.finish()
        }, context.mainExecutor)
    }

    companion object {
        const val TOGGLE = "io.github.currencortex.music.widget.TOGGLE"
        const val NEXT = "io.github.currencortex.music.widget.NEXT"
        const val PREVIOUS = "io.github.currencortex.music.widget.PREVIOUS"
    }
}
