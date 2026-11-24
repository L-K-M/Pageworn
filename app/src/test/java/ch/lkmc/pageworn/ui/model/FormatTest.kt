package ch.lkmc.pageworn.ui.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormatTest {
    @Test
    fun aTotalLengthAboveZeroAndBelowAMinuteReadsUnderOneMinute() {
        assertThat(Format.bookLength(1)).isEqualTo("Under 1 min")
        assertThat(Format.bookLength(59_999)).isEqualTo("Under 1 min")
    }

    @Test
    fun aTotalLengthOfAMinuteOrMoreKeepsWholeMinutes() {
        assertThat(Format.bookLength(0)).isEqualTo("0 min")
        assertThat(Format.bookLength(60_000)).isEqualTo("1 min")
        assertThat(Format.bookLength(4 * 3_600_000L + 20 * 60_000L)).isEqualTo("4 h 20 min")
    }
}
