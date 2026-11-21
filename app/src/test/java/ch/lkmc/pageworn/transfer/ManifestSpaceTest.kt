package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.data.db.TransferFileEntity
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ManifestSourceTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManifestSpaceTest {
    private fun track(fileId: String, size: Long, mtime: Long) = ManifestSourceTrack(
        0, fileId, 0, 10_000, size, mtime, "m4a", "aac", "audio/mp4")

    private fun staged(size: Long = 10, mtime: Long = 20) = TransferFileEntity(
        "job", 0, "file", size, mtime, "m4a", "aac", 10_000, null, "000.m4a")

    private fun manifest(track: ManifestSourceTrack) = ItemManifest(
        "item", 1, "Title", null, null, null, "book", listOf(track), emptyList(), false)

    @Test fun a05dChangedSizeRestartsSource() {
        assertThat(sameSource(listOf(staged()), manifest(track("file", 11, 20)))).isEmpty()
    }

    @Test fun a05dChangedMtimeRestartsSource() {
        assertThat(sameSource(listOf(staged()), manifest(track("file", 10, 21)))).isEmpty()
    }

    @Test fun a04UnchangedSourceCanReuseVerifiedFile() {
        assertThat(sameSource(listOf(staged()), manifest(track("file", 10, 20)))).containsExactly(0)
    }

    @Test fun fingerprintChangesWithSourceRevision() {
        assertThat(manifestFingerprint(listOf(track("file", 10, 20))))
            .isNotEqualTo(manifestFingerprint(listOf(track("file", 10, 21))))
    }

    @Test fun a21bFiveHundredMegabytesAboveBookIsShortByFiveHundredMegabytes() {
        assertThat(SpacePolicy.shortfall(2_000_000_000, 2_500_000_000)).isEqualTo(500_000_000)
    }

    @Test fun a21bExactlyOneGigabyteAboveBookIsEnough() {
        assertThat(SpacePolicy.shortfall(2_000_000_000, 3_000_000_000)).isNull()
    }

    @Test fun a21bUnknownRemainingNeedsOneGigabyte() {
        assertThat(SpacePolicy.shortfall(null, 999_999_999)).isEqualTo(1)
        assertThat(SpacePolicy.shortfall(null, 1_000_000_000)).isNull()
    }
}
