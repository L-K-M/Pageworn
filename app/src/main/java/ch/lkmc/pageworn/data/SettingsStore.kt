package ch.lkmc.pageworn.data

import android.content.Context
import ch.lkmc.pageworn.core.BookKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lightweight preferences: last active book, chosen speed, last destination, one-time notices. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pageworn-settings", Context.MODE_PRIVATE)

    private val _lastActiveBook = MutableStateFlow(readLastActive())
    val lastActiveBook: StateFlow<BookKey?> = _lastActiveBook.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(SPEED, 1f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    var lastDestination: String?
        get() = prefs.getString(DESTINATION, null)
        set(value) = prefs.edit().putString(DESTINATION, value).apply()

    /** The notifications-disabled notice has been shown once and must not return. */
    var notificationsNoticeConsumed: Boolean
        get() = prefs.getBoolean(NOTIFICATIONS_NOTICE, false)
        set(value) = prefs.edit().putBoolean(NOTIFICATIONS_NOTICE, value).apply()

    /** The local-network permission was requested at least once (so a denial can open settings). */
    var localNetworkAsked: Boolean
        get() = prefs.getBoolean(LOCAL_NETWORK_ASKED, false)
        set(value) = prefs.edit().putBoolean(LOCAL_NETWORK_ASKED, value).apply()

    fun setLastActiveBook(key: BookKey?) {
        prefs.edit().putString(LAST_BOOK, key?.mediaId).apply()
        _lastActiveBook.value = key
    }

    fun setSpeed(speed: Float) {
        prefs.edit().putFloat(SPEED, speed).apply()
        _speed.value = speed
    }

    private fun readLastActive(): BookKey? = BookKey.fromMediaId(prefs.getString(LAST_BOOK, null))

    companion object {
        private const val LAST_BOOK = "last_active_book"
        private const val SPEED = "speed"
        private const val DESTINATION = "last_destination"
        private const val NOTIFICATIONS_NOTICE = "notifications_notice_consumed"
        private const val LOCAL_NETWORK_ASKED = "local_network_asked"
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}
