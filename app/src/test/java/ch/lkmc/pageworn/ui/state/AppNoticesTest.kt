package ch.lkmc.pageworn.ui.state

import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.data.SettingsStore
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Destination
import ch.lkmc.pageworn.ui.model.UiAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNoticesTest {
    @Test
    fun a16bTheDeniedNoticeSurvivesRelaunchUntilLeavingDownloaded() {
        val settings = SettingsStore(ApplicationProvider.getApplicationContext())
        val firstLaunch = AppNotices(settings)
        firstLaunch.onDownloadEnqueued(notificationsDenied = false)
        assertThat(firstLaunch.downloadedStatus.value).isNull()

        firstLaunch.onDownloadEnqueued(notificationsDenied = true)
        assertThat(firstLaunch.downloadedStatus.value?.text).isEqualTo(Copy.NOTIFICATIONS_DISABLED)

        val relaunched = AppNotices(settings)
        assertThat(relaunched.downloadedStatus.value?.action).isEqualTo(UiAction.NOTIFICATION_SETTINGS)
        relaunched.leftDownloaded(Destination.DOWNLOADED, Destination.LIBRARY)
        assertThat(relaunched.downloadedStatus.value).isNull()

        val laterLaunch = AppNotices(settings)
        laterLaunch.onDownloadEnqueued(notificationsDenied = true)
        assertThat(laterLaunch.downloadedStatus.value).isNull()
    }
}
