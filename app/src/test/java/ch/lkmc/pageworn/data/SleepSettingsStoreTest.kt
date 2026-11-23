package ch.lkmc.pageworn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.sleep.SleepWindow
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persisted sleep settings and the schedule edges: R1 defaults, R4 choices, R10 presets and
 * R11 automatic windows and recent stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepSettingsStoreTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = FakeClock()
    private lateinit var context: Context
    private lateinit var store: SleepSettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pageworn-sleep", Context.MODE_PRIVATE).edit().clear().commit()
        store = SleepSettingsStore(context, clock) { zone }
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        clock.wall = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    }

    @Test
    fun defaultsAreOffWithThirtyMinutesAndTheDefaultWindow() {
        val settings = store.settings.value

        assertThat(settings.enabled).isFalse()
        assertThat(settings.length).isEqualTo(SleepLength.Minutes(30))
        assertThat(settings.presets).containsExactly(15, 30, 45, 60).inOrder()
        assertThat(settings.automatic).isFalse()
        assertThat(settings.window).isEqualTo(SleepWindow(22 * 60, 7 * 60))
    }

    @Test
    fun choosingALengthTurnsTheModeOnAndOffKeepsTheLength() {
        store.choose(SleepLength.Minutes(45))
        assertThat(store.settings.value.enabled).isTrue()
        assertThat(store.settings.value.length).isEqualTo(SleepLength.Minutes(45))

        store.choose(null)
        assertThat(store.settings.value.enabled).isFalse()
        assertThat(store.settings.value.length).isEqualTo(SleepLength.Minutes(45))

        store.choose(SleepLength.EndOfSegment)
        assertThat(store.settings.value.enabled).isTrue()
        assertThat(store.settings.value.length).isEqualTo(SleepLength.EndOfSegment)
    }

    @Test
    fun setPresetsDropsInvalidValuesAndKeepsTheNearestChosenLength() {
        store.choose(SleepLength.Minutes(30))

        assertThat(store.setPresets(listOf(60, 10, 10, 0, 721))).isTrue()

        val settings = store.settings.value
        assertThat(settings.presets).containsExactly(10, 60).inOrder()
        assertThat(settings.length).isEqualTo(SleepLength.Minutes(10))
    }

    @Test
    fun setPresetsRefusesEmptyAndTooManyValues() {
        assertThat(store.setPresets(emptyList())).isFalse()
        assertThat(store.settings.value.presets).containsExactly(15, 30, 45, 60).inOrder()

        assertThat(store.setPresets((1..9).toList())).isFalse()
        assertThat(store.settings.value.presets).containsExactly(15, 30, 45, 60).inOrder()
    }

    @Test
    fun setPresetsKeepsEndOfSegment() {
        store.choose(SleepLength.EndOfSegment)

        assertThat(store.setPresets(listOf(20, 45))).isTrue()

        val settings = store.settings.value
        assertThat(settings.presets).containsExactly(20, 45).inOrder()
        assertThat(settings.length).isEqualTo(SleepLength.EndOfSegment)
    }

    @Test
    fun theAutomaticWindowTurnsTheModeOnAtItsStartAndOffAtItsEnd() {
        at(2026, 11, 23, 21, 0)
        store.setAutomatic(true, SleepWindow(1320, 420))
        assertThat(store.settings.value.enabled).isFalse()

        at(2026, 11, 23, 21, 30)
        assertThat(store.refresh()).isNull()
        assertThat(store.settings.value.enabled).isFalse()

        at(2026, 11, 23, 22, 0)
        assertThat(store.refresh()).isTrue()
        assertThat(store.settings.value.enabled).isTrue()

        at(2026, 11, 23, 22, 1)
        assertThat(store.refresh()).isNull()

        at(2026, 11, 23, 23, 0)
        store.choose(null)
        assertThat(store.settings.value.enabled).isFalse()

        at(2026, 11, 23, 23, 30)
        assertThat(store.refresh()).isNull()
        assertThat(store.settings.value.enabled).isFalse()

        at(2026, 11, 24, 7, 0)
        assertThat(store.refresh()).isNull()
        assertThat(store.settings.value.enabled).isFalse()

        at(2026, 11, 24, 22, 0)
        assertThat(store.refresh()).isTrue()
    }

    @Test
    fun switchingAutomaticOnInsideTheWindowTurnsTheModeOnAtOnce() {
        at(2026, 11, 23, 23, 0)

        store.setAutomatic(true, SleepWindow(1320, 420))

        assertThat(store.settings.value.enabled).isTrue()
    }

    @Test
    fun anEdgePassedWhileThePhoneWasOffAppliesAtTheNextCheck() {
        at(2026, 11, 23, 12, 0)
        store.setAutomatic(true, SleepWindow(1320, 420))
        assertThat(store.settings.value.enabled).isFalse()

        at(2026, 11, 23, 23, 30)

        assertThat(store.refresh()).isTrue()
        assertThat(store.settings.value.enabled).isTrue()
    }

    @Test
    fun aManualChoiceSurvivesUntilTheWindowEnds() {
        at(2026, 11, 23, 12, 0)
        store.setAutomatic(true, SleepWindow(1320, 420))
        store.choose(SleepLength.Minutes(15))
        assertThat(store.settings.value.enabled).isTrue()

        at(2026, 11, 23, 23, 0)
        assertThat(store.refresh()).isNull()
        assertThat(store.settings.value.enabled).isTrue()

        at(2026, 11, 24, 8, 0)
        assertThat(store.refresh()).isFalse()
        assertThat(store.settings.value.enabled).isFalse()
    }

    @Test
    fun theLatestEdgeWinsWhenTwoPassWhileAway() {
        at(2026, 11, 23, 12, 0)
        store.setAutomatic(true, SleepWindow(1320, 420))
        store.choose(SleepLength.Minutes(15))

        at(2026, 11, 24, 8, 0)

        assertThat(store.refresh()).isFalse()
        assertThat(store.settings.value.enabled).isFalse()
    }

    @Test
    fun withoutAutomaticRefreshNeverChangesTheMode() {
        at(2026, 11, 23, 21, 0)
        assertThat(store.refresh()).isNull()

        at(2026, 11, 23, 22, 30)
        assertThat(store.refresh()).isNull()

        at(2026, 11, 24, 8, 0)
        assertThat(store.refresh()).isNull()
        assertThat(store.settings.value.enabled).isFalse()
    }

    @Test
    fun aRecentStopLastsFiveMinutesAndForgetClearsIt() {
        clock.elapsed = 100_000
        store.recordStop(SleepLength.EndOfSegment)

        clock.elapsed = 100_000 + 4 * 60_000
        assertThat(store.recentStop(5 * 60_000)).isEqualTo(SleepLength.EndOfSegment)

        clock.elapsed = 100_000 + 5 * 60_000
        assertThat(store.recentStop(5 * 60_000)).isEqualTo(SleepLength.EndOfSegment)

        clock.elapsed = 100_000 + 5 * 60_000 + 1
        assertThat(store.recentStop(5 * 60_000)).isNull()

        store.recordStop(SleepLength.EndOfSegment)
        store.forgetStop()
        assertThat(store.recentStop(5 * 60_000)).isNull()
    }

    @Test
    fun aStopBeforeAClockRestartIsNotRecent() {
        clock.elapsed = 100_000
        store.recordStop(SleepLength.Minutes(30))

        clock.elapsed = 50_000

        assertThat(store.recentStop(5 * 60_000)).isNull()
    }

    @Test
    fun settingsSurviveANewStoreOnTheSameContext() {
        at(2026, 11, 23, 23, 0)
        store.choose(SleepLength.Minutes(45))
        store.setPresets(listOf(20, 45))
        store.setAutomatic(true, SleepWindow(1380, 360))

        val reopened = SleepSettingsStore(context, clock) { zone }
        val settings = reopened.settings.value

        assertThat(settings.enabled).isTrue()
        assertThat(settings.length).isEqualTo(SleepLength.Minutes(45))
        assertThat(settings.presets).containsExactly(20, 45).inOrder()
        assertThat(settings.automatic).isTrue()
        assertThat(settings.window).isEqualTo(SleepWindow(1380, 360))
    }
}
