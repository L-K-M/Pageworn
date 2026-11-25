package ch.lkmc.pageworn.data

import android.content.Context
import ch.lkmc.pageworn.catalog.CatalogSort
import ch.lkmc.pageworn.core.BookKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lightweight preferences: last active book, chosen speed, Library order, UI sounds, last destination, one-time notices. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pageworn-settings", Context.MODE_PRIVATE)

    private val _lastActiveBook = MutableStateFlow(readLastActive())
    val lastActiveBook: StateFlow<BookKey?> = _lastActiveBook.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(SPEED, 1f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _librarySort = MutableStateFlow(
        CatalogSort.entries.firstOrNull { it.name == prefs.getString(LIBRARY_SORT, null) } ?: CatalogSort.DEFAULT,
    )
    val librarySort: StateFlow<CatalogSort> = _librarySort.asStateFlow()

    private val _uiSoundsEnabled = MutableStateFlow(prefs.getBoolean(UI_SOUNDS_ENABLED, true))
    val uiSoundsEnabled: StateFlow<Boolean> = _uiSoundsEnabled.asStateFlow()

    var lastDestination: String?
        get() = prefs.getString(DESTINATION, null)
        set(value) = prefs.edit().putString(DESTINATION, value).apply()

    val notificationsNoticeConsumed: Boolean
        get() = prefs.getBoolean(NOTIFICATIONS_NOTICE, false)

    val notificationsNoticePending: Boolean
        get() = prefs.getBoolean(NOTIFICATIONS_NOTICE_PENDING, false)

    fun showNotificationsNotice() {
        prefs.edit().putBoolean(NOTIFICATIONS_NOTICE, true).putBoolean(NOTIFICATIONS_NOTICE_PENDING, true).apply()
    }

    fun dismissNotificationsNotice() {
        prefs.edit().remove(NOTIFICATIONS_NOTICE_PENDING).apply()
    }

    /** The local-network permission was requested at least once (so a denial can open settings). */
    var localNetworkAsked: Boolean
        get() = prefs.getBoolean(LOCAL_NETWORK_ASKED, false)
        set(value) = prefs.edit().putBoolean(LOCAL_NETWORK_ASKED, value).apply()

    /** POST_NOTIFICATIONS was requested at least once (so a denial can open settings). */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit().putBoolean(NOTIFICATIONS_ASKED, value).apply()

    /** The address the listener pinned in manual mode, or null for automatic mode. */
    var pinnedAddressId: String?
        get() = prefs.getString(PINNED_ADDRESS, null)
        set(value) = prefs.edit().putString(PINNED_ADDRESS, value).apply()

    fun setLastActiveBook(key: BookKey?) {
        prefs.edit().putString(LAST_BOOK, key?.mediaId).apply()
        _lastActiveBook.value = key
    }

    fun setSpeed(speed: Float) {
        prefs.edit().putFloat(SPEED, speed).apply()
        _speed.value = speed
    }

    fun setLibrarySort(sort: CatalogSort) {
        prefs.edit().putString(LIBRARY_SORT, sort.name).apply()
        _librarySort.value = sort
    }

    fun setUiSoundsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(UI_SOUNDS_ENABLED, enabled).apply()
        _uiSoundsEnabled.value = enabled
    }

    private fun readLastActive(): BookKey? = BookKey.fromMediaId(prefs.getString(LAST_BOOK, null))

    companion object {
        private const val LAST_BOOK = "last_active_book"
        private const val SPEED = "speed"
        private const val LIBRARY_SORT = "library_sort"
        private const val UI_SOUNDS_ENABLED = "ui_sounds_enabled"
        private const val DESTINATION = "last_destination"
        private const val NOTIFICATIONS_NOTICE = "notifications_notice_consumed"
        private const val NOTIFICATIONS_NOTICE_PENDING = "notifications_notice_pending"
        private const val LOCAL_NETWORK_ASKED = "local_network_asked"
        private const val NOTIFICATIONS_ASKED = "notifications_asked"
        private const val PINNED_ADDRESS = "pinned_address"
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}
