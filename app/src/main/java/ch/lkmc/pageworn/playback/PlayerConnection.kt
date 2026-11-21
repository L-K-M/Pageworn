package ch.lkmc.pageworn.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.model.SleepChoice
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The UI's command path to the playback service: one media controller, connected on demand and
 * released when the activity stops. Live state is observed through [PlaybackHub], which the service
 * publishes from the same player the system surfaces see.
 */
class PlayerConnection(private val context: Context, private val hub: PlaybackHub) {
    private var controller: MediaController? = null
    private val mutex = Mutex()

    private suspend fun connected(): MediaController = mutex.withLock {
        controller?.takeIf { it.isConnected } ?: MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync().await().also { controller = it }
    }

    fun release() {
        controller?.release()
        controller = null
    }

    /** Play or pause [key]: resumes it from its saved place (with the rewind) if another book is loaded. */
    suspend fun playPause(key: BookKey) {
        val c = connected()
        val live = hub.state.value
        if (live.key == key && c.currentMediaItem?.mediaId == key.mediaId && !live.failed) {
            if (c.playWhenReady) c.pause() else c.play()
        } else {
            c.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build())
            c.prepare()
            c.play()
        }
    }

    suspend fun togglePlayPause() {
        val c = connected()
        if (c.mediaItemCount == 0) {
            hub.state.value.key?.let { playPause(it) }
            return
        }
        if (c.playWhenReady) c.pause() else c.play()
    }

    suspend fun pause() {
        connected().pause()
    }

    /** Loads [key] if needed and starts at [positionMs] exactly (a chapter choice moves the place). */
    suspend fun playFrom(key: BookKey, positionMs: Long) {
        val c = connected()
        if (c.currentMediaItem?.mediaId == key.mediaId && !hub.state.value.failed) {
            c.seekTo(positionMs)
            c.play()
        } else {
            c.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build(), positionMs)
            c.prepare()
            c.play()
        }
    }

    suspend fun seekTo(positionMs: Long) {
        connected().seekTo(positionMs)
    }

    suspend fun skipBack() = connected().seekBack()

    suspend fun skipForward() = connected().seekForward()

    suspend fun setSpeed(speed: Float) {
        connected().setPlaybackSpeed(speed)
    }

    suspend fun setSleep(choice: SleepChoice) {
        val args = Bundle()
        when (choice) {
            SleepChoice.OFF -> args.putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_OFF)
            SleepChoice.END_OF_SEGMENT -> args.putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_END_OF_SEGMENT)
            else -> {
                args.putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_TIMED)
                args.putInt(
                    PagewornCommands.ARG_SLEEP_MINUTES,
                    when (choice) {
                        SleepChoice.MINUTES_15 -> 15
                        SleepChoice.MINUTES_30 -> 30
                        SleepChoice.MINUTES_45 -> 45
                        else -> 60
                    },
                )
            }
        }
        connected().sendCustomCommand(SessionCommand(PagewornCommands.SET_SLEEP, Bundle.EMPTY), args).await()
    }

    /** Stops the book and releases its edition so its audio can be removed. */
    suspend fun releaseBook(key: BookKey) {
        if (hub.state.value.key != key) return
        val c = connected()
        c.pause()
        c.stop()
        c.clearMediaItems()
    }
}
