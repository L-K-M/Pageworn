package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransferNoticeTest {
    @Test fun progressWithinOnePercentDrawsTheSameNotice() {
        assertThat(notice(bytesTotal = 1_000, bytesDone = 10)).isEqualTo(notice(bytesTotal = 1_000, bytesDone = 19))
    }

    @Test fun crossingAPercentDrawsANewNotice() {
        assertThat(notice(bytesTotal = 1_000, bytesDone = 19)).isNotEqualTo(notice(bytesTotal = 1_000, bytesDone = 20))
        assertThat(notice(bytesTotal = 1_000, bytesDone = 20)?.percent).isEqualTo(2)
    }

    @Test fun anotherBookDrawsANewNotice() {
        assertThat(notice(bytesTotal = 1_000, bytesDone = 10)).isNotEqualTo(notice(bytesTotal = 1_000, bytesDone = 10, title = "The next book"))
    }

    @Test fun anUnknownOrEmptySizeHasNoPercent() {
        assertThat(notice(bytesTotal = null, bytesDone = 500)).isEqualTo(TransferNotice("The book", null))
        assertThat(notice(bytesTotal = 0, bytesDone = 500)?.percent).isNull()
    }

    @Test fun noJobIsPreparing() {
        assertThat(TransferNotice.of(null)).isNull()
    }

    private fun notice(bytesTotal: Long?, bytesDone: Long, title: String = "The book") = TransferNotice.of(
        TransferEntity(
            jobId = "job-1", profileId = "profile", itemId = "item-1", stagingGenerationId = "staged-1",
            manifestFingerprint = null, state = TransferState.TRANSFERRING, activeMarker = 1,
            createdAt = 1, updatedAt = 1, bytesTotal = bytesTotal, bytesDone = bytesDone,
            title = title, authors = null,
        ),
    )
}
