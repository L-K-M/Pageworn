package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.sleep.SleepWindow
import ch.lkmc.pageworn.data.SleepSettings
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class SleepSettingsUiTest {
    private val defaults = listOf(15, 30, 45, 60)

    @Test
    fun aLengthIsOneMinuteOrMinutes() {
        assertThat(presetLabel(1)).isEqualTo("1 minute")
        assertThat(presetLabel(15)).isEqualTo("15 minutes")
        assertThat(presetLabel(90)).isEqualTo("90 minutes")
    }

    @Test
    fun aLengthOutsideOneTo720IsRefused() {
        assertThat(addPresetResult(defaults, 0)).isEqualTo(AddResult.OUT_OF_RANGE)
        assertThat(addPresetResult(defaults, 721)).isEqualTo(AddResult.OUT_OF_RANGE)
        assertThat(addPresetResult(defaults, 1)).isEqualTo(AddResult.OK)
        assertThat(addPresetResult(defaults, 720)).isEqualTo(AddResult.OK)
    }

    @Test
    fun aLengthAlreadyOfferedIsRefused() {
        assertThat(addPresetResult(defaults, 30)).isEqualTo(AddResult.DUPLICATE)
    }

    @Test
    fun aNinthLengthIsRefused() {
        val eight = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertThat(addPresetResult(eight, 90)).isEqualTo(AddResult.FULL)
    }

    @Test
    fun theSummaryNamesTheLengthsAndAnAutomaticWindow() {
        val settings = SleepSettings(presets = defaults, automatic = true, window = SleepWindow(22 * 60, 7 * 60))
        assertThat(sleepSummary(settings, Locale.UK)).isEqualTo("15, 30, 45 and 60 minutes · Automatic 22:00 to 07:00")
    }

    @Test
    fun theSummarySaysWhenAutomaticIsOff() {
        val settings = SleepSettings(presets = defaults, automatic = false)
        assertThat(sleepSummary(settings, Locale.UK)).isEqualTo("15, 30, 45 and 60 minutes · Automatic off")
    }
}
