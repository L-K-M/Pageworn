package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.server.NoRoute
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.transfer.DownloadStart
import ch.lkmc.pageworn.transfer.Refusal
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.DetailAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadRefusalTest {
    private val key = BookKey("profile", "item")

    @Test fun aProblemOfTheBookShowsOnItsRow() {
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.UnsupportedFormat)))
            .isEqualTo(DownloadRefusal(key, Copy.UNSUPPORTED_FORMAT, onRow = true, disabledAction = DetailAction.Unsupported))
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.DownloadsDisabled)))
            .isEqualTo(DownloadRefusal(key, Copy.DOWNLOAD_DISABLED, onRow = true, disabledAction = DetailAction.DownloadDisabled))
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.Server(ServerError.Forbidden))))
            .isEqualTo(DownloadRefusal(key, Copy.DOWNLOAD_DISABLED, onRow = true, disabledAction = DetailAction.DownloadDisabled))
    }

    @Test fun retryableProblemsLeaveTheActionEnabled() {
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.NoAudio))?.disabledAction).isNull()
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.NoRoute(NoRoute.SignedOut)))?.disabledAction).isNull()
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.NoRoute(NoRoute.Unreachable(null))))?.disabledAction).isNull()
    }

    @Test fun aProblemOfTheServerRepeatsTheStatus() {
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.NoRoute(NoRoute.Unreachable(null)))))
            .isEqualTo(DownloadRefusal(key, Copy.UNREACHABLE, onRow = false))
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.NoRoute(NoRoute.SignedOut)))?.text).isEqualTo(Copy.SESSION_EXPIRED)
    }

    @Test fun aStartedOrAttachedDownloadSaysNothing() {
        assertThat(refusalOf(key, DownloadStart.Enqueued("job"))).isNull()
        assertThat(refusalOf(key, DownloadStart.Attached("job"))).isNull()
        assertThat(refusalOf(key, DownloadStart.Refused(Refusal.AlreadyDownloaded))).isNull()
    }
}
