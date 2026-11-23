package ch.lkmc.pageworn.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pageworn has one palette and keeps it when the phone is in dark mode (docs/02-interface.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
class DayThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theScreensKeepTheDayPaperWhenThePhoneIsDark() {
        assertThat(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK).isEqualTo(Configuration.UI_MODE_NIGHT_YES)
        lateinit var colors: PagewornColors
        compose.setContent { PagewornTheme { colors = PagewornTheme.colors } }
        compose.waitForIdle()

        assertThat(colors.paper).isEqualTo(Color(DAY_PAPER))
        assertThat(colors.primaryInk).isEqualTo(Color(DAY_INK))
    }

    @Test
    fun theWindowAndTheWidgetsKeepTheDayPaperWhenThePhoneIsDark() {
        assertThat(context.getColor(R.color.paper)).isEqualTo(DAY_PAPER.toInt())
        assertThat(context.getColor(R.color.widget_paper)).isEqualTo(DAY_PAPER.toInt())
        assertThat(context.getColor(R.color.widget_ink)).isEqualTo(DAY_INK.toInt())
    }

    @Test
    fun androidNeitherDarkensNorInvertsTheWindowAndItsBarsKeepDarkIcons() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val attributes = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme, android.R.attr.forceDarkAllowed))
        try {
            // A theme that claims to be light is inverted by "Make more apps dark" (themes.xml).
            assertThat(attributes.getBoolean(0, true)).isFalse()
            assertThat(attributes.getBoolean(1, true)).isFalse()
        } finally {
            attributes.recycle()
        }

        val bars = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        assertThat(bars.isAppearanceLightStatusBars).isTrue()
        assertThat(bars.isAppearanceLightNavigationBars).isTrue()
    }

    private companion object {
        const val DAY_PAPER = 0xFFF3EBD9
        const val DAY_INK = 0xFF30251E
    }
}
