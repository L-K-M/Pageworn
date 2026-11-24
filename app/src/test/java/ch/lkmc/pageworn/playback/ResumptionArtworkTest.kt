package ch.lkmc.pageworn.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class ResumptionArtworkTest {
    @Test fun returnsArtBelowTheLimit() {
        val bytes = byteArrayOf(1, 2, 3)
        assertThat(readResumptionArtwork(bytes.inputStream())).isEqualTo(bytes)
    }

    @Test fun rejectsOversizedArtWithoutReadingPastTheLimit() {
        val limit = BookSessionCallback.MAX_COVER_BYTES
        val input = ByteArrayInputStream(ByteArray(limit + 1))

        assertThat(readResumptionArtwork(input)).isNull()
        assertThat(input.available()).isEqualTo(1)
    }
}
