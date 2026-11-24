package ch.lkmc.pageworn.ui.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every way the notifications notice's action can resolve: ask, or send the listener to settings. */
class NotificationsRouteTest {
    @Test
    fun belowAndroid13HasNoRuntimeDialogSoItOpensSettings() {
        assertThat(notificationsRoute(sdk = 31, granted = false, askedBefore = false, rationale = false))
            .isEqualTo(NotificationsRoute.OPEN_SETTINGS)
        assertThat(notificationsRoute(sdk = 32, granted = true, askedBefore = true, rationale = true))
            .isEqualTo(NotificationsRoute.OPEN_SETTINGS)
    }

    @Test
    fun grantedPermissionOpensSettingsBecauseOnlySystemSettingsCanTurnNotificationsOff() {
        assertThat(notificationsRoute(sdk = 34, granted = true, askedBefore = false, rationale = false))
            .isEqualTo(NotificationsRoute.OPEN_SETTINGS)
        assertThat(notificationsRoute(sdk = 37, granted = true, askedBefore = true, rationale = true))
            .isEqualTo(NotificationsRoute.OPEN_SETTINGS)
    }

    @Test
    fun neverAskedOnAndroid13OrLaterAsks() {
        assertThat(notificationsRoute(sdk = 33, granted = false, askedBefore = false, rationale = false))
            .isEqualTo(NotificationsRoute.ASK)
        assertThat(notificationsRoute(sdk = 37, granted = false, askedBefore = false, rationale = true))
            .isEqualTo(NotificationsRoute.ASK)
    }

    @Test
    fun askedOnceWithARationaleAsksAgain() {
        assertThat(notificationsRoute(sdk = 34, granted = false, askedBefore = true, rationale = true))
            .isEqualTo(NotificationsRoute.ASK)
    }

    @Test
    fun askedOnceWithoutARationaleIsPermanentlyDeniedSoItOpensSettings() {
        assertThat(notificationsRoute(sdk = 33, granted = false, askedBefore = true, rationale = false))
            .isEqualTo(NotificationsRoute.OPEN_SETTINGS)
        assertThat(notificationsRoute(sdk = 37, granted = false, askedBefore = true, rationale = false))
            .isEqualTo(NotificationsRoute.OPEN_SETTINGS)
    }
}
