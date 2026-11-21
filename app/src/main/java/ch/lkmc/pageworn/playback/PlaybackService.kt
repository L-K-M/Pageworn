package ch.lkmc.pageworn.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.R

/**
 * The one media library session. The UI, the notification, the lock screen, headsets, the car's
 * buttons and Android Auto all act through it. It owns the single active book.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as PagewornApp
        val container = app.container
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(BookMediaSourceFactory(container.playbackDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(BookPlayer.SEEK_BACK_MS)
            .setSeekForwardIncrementMs(BookPlayer.SEEK_FORWARD_MS)
            .build()
        exoPlayer = exo
        val player = BookPlayer(exo)
        val callback = BookSessionCallback(this, container)
        session = MediaLibrarySession.Builder(this, player, callback)
            .setId(SESSION_ID)
            .setSessionActivity(container.sessionActivity(this))
            .setMediaButtonPreferences(callback.mediaButtonPreferences(forCar = false))
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_pageworn)
            },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val SESSION_ID = "pageworn"
    }
}
