package ch.lkmc.pageworn.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The one media library session. The UI, the notification, the lock screen, headsets, the car's
 * buttons and Android Auto all act through it. It owns the single active book.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var controller: PlaybackController? = null
    private var callback: BookSessionCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as PagewornApp).container
        val exo = ExoPlayer.Builder(this, container.playbackRenderersFactory(this))
            .setMediaSourceFactory(
                BookMediaSourceFactory(container.playbackDataSourceFactory)
                    // A transient read error is retried once from the same position.
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)),
            )
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
        val items = BookItems(this)
        val playback = PlaybackController(
            exo = exo,
            editions = container.editionStore,
            progress = container.progressStore,
            settings = container.settings,
            clock = container.liveClock,
            hub = container.playbackHub,
            // Reads the container's writer at call time, so a writer a test installs later applies.
            writer = ProgressWriter { key, token, position, duration, fingerprint, kind, finished, listened ->
                container.progressWriter.write(key, token, position, duration, fingerprint, kind, finished, listened)
            },
            items = items,
            scope = scope,
        )
        controller = playback
        val player = BookPlayer(exo, playback::beforePlay)
        val cb = BookSessionCallback(packageName, container, { playback }, items)
        callback = cb
        session = MediaLibrarySession.Builder(this, player, cb)
            .setId(SESSION_ID)
            .setSessionActivity(container.sessionActivity(this))
            .setMediaButtonPreferences(cb.mediaButtonPreferences(forCar = false))
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
        controller?.release()
        callback?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        controller = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val SESSION_ID = "pageworn"
    }
}
