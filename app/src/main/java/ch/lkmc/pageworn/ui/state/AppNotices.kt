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
    private val denied = StatusLine(Copy.NOTIFICATIONS_DISABLED, UiAction.NOTIFICATION_SETTINGS, Copy.ENABLE_NOTIFICATIONS)
    private val _downloadedStatus = MutableStateFlow(denied.takeIf { settings.notificationsNoticePending })
    val downloadedStatus: StateFlow<StatusLine?> = _downloadedStatus.asStateFlow()

    @Synchronized
    fun onDownloadEnqueued(notificationsDenied: Boolean) {
        if (!notificationsDenied || settings.notificationsNoticeConsumed) return
        settings.showNotificationsNotice()
        _downloadedStatus.value = denied
    }

    fun leftDownloaded(from: Destination, to: Destination) {
        if (from == Destination.DOWNLOADED && to != Destination.DOWNLOADED) dismiss()
    }

    /** A screen outside the sections opened over [section], so the notice only applies to Downloaded. */
    fun openedOver(section: Destination) {
        if (section == Destination.DOWNLOADED) dismiss()
    }

    /** The listener went from Downloaded to a screen outside the two sections, or used the action. */
    @Synchronized
    fun dismiss() {
        if (_downloadedStatus.value == null) return
        settings.dismissNotificationsNotice()
        _downloadedStatus.value = null
    }
}
