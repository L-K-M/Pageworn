package ch.lkmc.pageworn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sound-effects preference: on by default and kept across a process restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {
    private lateinit var context: Context
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pageworn-settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = SettingsStore(context)
    }

    @Test
    fun uiSoundsAreOnByDefault() {
        assertThat(store.uiSoundsEnabled.value).isTrue()
    }

    @Test
    fun turningUiSoundsOffPersistsAcrossAProcessRestart() {
        store.setUiSoundsEnabled(false)

        assertThat(store.uiSoundsEnabled.value).isFalse()

        val reopened = SettingsStore(context)
        assertThat(reopened.uiSoundsEnabled.value).isFalse()
    }

    @Test
    fun turningUiSoundsBackOnPersists() {
        store.setUiSoundsEnabled(false)
        store.setUiSoundsEnabled(true)

        assertThat(SettingsStore(context).uiSoundsEnabled.value).isTrue()
    }
}
