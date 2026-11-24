package ch.lkmc.pageworn.ui.state

import android.os.Build

/** What tapping the notifications notice's action should do about POST_NOTIFICATIONS. */
internal enum class NotificationsRoute {
    /** Android can still show its runtime dialog, so ask. */
    ASK,

    /** No dialog left to show, or notifications are off in settings: open the app's notification settings. */
    OPEN_SETTINGS,
}

/**
 * Picks what the notifications notice's action does. Android 13 introduced the POST_NOTIFICATIONS
 * runtime permission; below it there is no dialog to show. Once asked, Android shows the dialog
 * again only while it reports a rationale, so a first denial followed by no rationale means the
 * listener has to change the setting by hand. A granted permission with notifications turned off
 * in settings also needs the settings screen.
 */
internal fun notificationsRoute(sdk: Int, granted: Boolean, askedBefore: Boolean, rationale: Boolean): NotificationsRoute = when {
    sdk < Build.VERSION_CODES.TIRAMISU -> NotificationsRoute.OPEN_SETTINGS
    granted -> NotificationsRoute.OPEN_SETTINGS
    askedBefore && !rationale -> NotificationsRoute.OPEN_SETTINGS
    else -> NotificationsRoute.ASK
}
