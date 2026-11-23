package ch.lkmc.pageworn.ui.detail

import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.StartAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetailRibbonTest {
    @Test
    fun onlyAResumeKeyMarksASavedPlace() {
        assertThat(showsPlaceRibbon(DetailAction.Play(StartAction.RESUME, showsPause = false, starting = false))).isTrue()
        assertThat(showsPlaceRibbon(DetailAction.Play(StartAction.PLAY, showsPause = true, starting = true))).isFalse()
        assertThat(showsPlaceRibbon(DetailAction.Play(StartAction.REPLAY, showsPause = false, starting = false))).isFalse()
    }

    @Test
    fun booksWithoutAPlayKeyHaveNoRibbon() {
        assertThat(showsPlaceRibbon(DetailAction.Download)).isFalse()
        assertThat(showsPlaceRibbon(DetailAction.Repair)).isFalse()
        assertThat(showsPlaceRibbon(DetailAction.DownloadDisabled)).isFalse()
        assertThat(showsPlaceRibbon(DetailAction.Unsupported)).isFalse()
    }
}
