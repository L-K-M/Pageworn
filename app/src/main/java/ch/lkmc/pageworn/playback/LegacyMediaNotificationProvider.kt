package ch.lkmc.pageworn.playback

import android.app.NotificationManager
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Android 12 reads controls from the notification, whose updates it can drop in a burst. */
internal class LegacyMediaNotificationProvider(
    private val notifications: NotificationManager,
    private val scope: CoroutineScope,
    private val provider: MediaNotification.Provider,
) : MediaNotification.Provider by provider {
    private var latestUpdate = 0L
    private var refresh: Job? = null

    override fun createNotification(
        session: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val update = ++latestUpdate
        fun refreshAfterBurst(notification: MediaNotification) {
            if (update != latestUpdate) return
            refresh?.cancel()
            refresh = scope.launch {
                delay(1_000)
                if (session.player.mediaItemCount == 0) return@launch
                // A dismissed notification stays dismissed. Retry only if Android kept older controls.
                val posted = notifications.activeNotifications.firstOrNull { it.id == notification.notificationId }
                    ?: return@launch
                val actual = posted.notification.actions.orEmpty().map { it.title.toString() }
                val expected = notification.notification.actions.orEmpty().map { it.title.toString() }
                if (actual != expected) callback.onNotificationChanged(notification)
            }
        }
        val notification = provider.createNotification(session, mediaButtonPreferences, actionFactory) { updated ->
            callback.onNotificationChanged(updated)
            refreshAfterBurst(updated)
        }
        refreshAfterBurst(notification)
        return notification
    }
}
