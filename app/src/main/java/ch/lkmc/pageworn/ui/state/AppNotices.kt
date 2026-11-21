package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.data.SettingsStore
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Destination
import ch.lkmc.pageworn.ui.model.StatusLine
import ch.lkmc.pageworn.ui.model.UiAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The notifications-disabled notice (docs/03-android-and-data.md): shown in Downloaded the first
 * time a download is enqueued while the permission is denied; it stays until the listener uses its
 * action or goes from Downloaded to another screen, and never returns.
 */
class AppNotices(private val settings: SettingsStore) {
    private val _downloadedStatus = MutableStateFlow<StatusLine?>(null)
    val downloadedStatus: StateFlow<StatusLine?> = _downloadedStatus.asStateFlow()

    fun onDownloadEnqueued(notificationsDenied: Boolean) {
        if (!notificationsDenied || settings.notificationsNoticeConsumed) return
        settings.notificationsNoticeConsumed = true
        _downloadedStatus.value = StatusLine(Copy.NOTIFICATIONS_DISABLED, UiAction.NOTIFICATION_SETTINGS, Copy.ENABLE_NOTIFICATIONS)
    }

    fun leftDownloaded(from: Destination, to: Destination) {
        if (from == Destination.DOWNLOADED && to != Destination.DOWNLOADED) _downloadedStatus.value = null
    }

    /** The listener went from Downloaded to a screen outside the two sections, or used the action. */
    fun dismiss() {
        _downloadedStatus.value = null
    }
}
