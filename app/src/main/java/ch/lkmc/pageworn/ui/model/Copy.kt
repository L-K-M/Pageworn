package ch.lkmc.pageworn.ui.model

import ch.lkmc.pageworn.catalog.CatalogSort
import java.util.Locale

/** The listener-facing sentences of docs/01-product.md ("Copy and recovery"), verbatim. */
object Copy {
    /** An order as the sheet lists it and as TalkBack reads the Sort control. */
    fun sortName(sort: CatalogSort) = when (sort) {
        CatalogSort.DATE_ADDED_NEWEST -> "Date added, newest first"
        CatalogSort.DATE_ADDED_OLDEST -> "Date added, oldest first"
        CatalogSort.TITLE -> "Title"
        CatalogSort.AUTHOR -> "Author"
    }

    /** An order as the Sort control shows it. */
    fun sortShortName(sort: CatalogSort) = when (sort) {
        CatalogSort.DATE_ADDED_NEWEST -> "Newest first"
        CatalogSort.DATE_ADDED_OLDEST -> "Oldest first"
        CatalogSort.TITLE -> "Title"
        CatalogSort.AUTHOR -> "Author"
    }

    const val SORT_LIBRARY = "Sort Library"
    const val BACK_15 = "Back 15 seconds"
    const val FORWARD_30 = "Forward 30 seconds"
    const val NO_DOWNLOADS = "Your downloaded books will appear here."
    const val LIBRARY_LOADING = "Loading your library…"
    const val LIBRARY_INCOMPLETE = "Your library didn’t finish loading."
    const val NO_AUDIOBOOK_LIBRARIES = "This account has no audiobook libraries."
    const val EMPTY_LIBRARY = "This library has no audiobooks yet."
    const val NO_SEARCH_RESULTS = "No books match your search."
    const val UNREACHABLE = "Can’t reach your server."
    fun localNetworkDenied(label: String) = "Allow local network access to reach $label."
    const val SERVER_TOO_OLD = "This server needs Audiobookshelf 2.26 or later."
    const val NO_PASSWORD_SIGN_IN = "This server doesn’t offer password sign-in."
    const val NO_AUDIO = "The server sent no audio for this download."
    const val UNSUPPORTED_FORMAT = "Pageworn can’t play this book’s audio format."
    const val SESSION_EXPIRED = "Sign in to refresh your library."
    const val SIGN_OUT_CONFIRM = "Sign out? Your downloaded books and saved places stay on this phone."
    fun differentAccount(accountName: String) = "This phone holds books for $accountName. Sign in with that account."
    const val PAIRING_MISMATCH = "This address leads to a different server."
    const val INVALID_ADDRESS = "Enter a web address, such as https://books.example.com/audiobookshelf."
    const val WRONG_CREDENTIALS = "The user name or password is incorrect."
    const val TOO_MANY_SIGN_INS = "Too many sign-in attempts. Try again in a few minutes."
    const val CLEARTEXT_TRADEOFF = "Pageworn will send your password and books to this address without encryption. Anyone on the same network can read them."
    const val ALLOW_CLEARTEXT = "Allow unencrypted access"
    const val CLEARTEXT_ALLOWED = "Unencrypted access allowed"
    fun lastReached(dateText: String) = "Last reached $dateText"
    fun localNetworkNeeded(address: String) = "Pageworn needs local network access to reach $address."
    fun confirmPairing(username: String, libraryName: String?) =
        if (libraryName == null) "Signed in as $username." else "Signed in as $username. This address reaches the server with your library $libraryName."
    const val ADD_ADDRESS = "Add address"
    const val CONTINUE = "Continue"
    const val UNRECOGNIZED_CERTIFICATE = "Your phone doesn’t recognize this address’s certificate."
    fun certificateChanged(label: String) = "The certificate for $label has changed."
    const val DOWNLOAD_DISABLED = "This account can browse this book. Downloads are disabled."
    const val STORAGE_FULL = "More space is needed to finish this download."
    fun storageFull(shortfallBytes: Long?) =
        if (shortfallBytes == null || shortfallBytes <= 0) STORAGE_FULL else "$STORAGE_FULL ${Format.bytes(shortfallBytes)} more is needed."
    const val AUDIO_REMOVED = "Download removed. Your place is saved."
    const val DAMAGED_COPY = "This copy couldn’t be opened."
    const val PLAY_DID_NOT_START = "Couldn’t start playback. Another app may be using audio."
    const val CHOOSE_START = "Choose where to start."
    const val STARTING = "Starting"
    const val DISMISS = "Dismiss"
    const val PLAYBACK_STOPPED = "Playback paused at your last position."
    const val NOTIFICATIONS_DISABLED = "Transfers and playback controls stay available in the app."
    const val NOTHING_IN_CAR = "Download books on your phone to listen here."

    // Actions.
    const val BROWSE_LIBRARY = "Browse library"
    const val RETRY = "Retry"
    const val CHECK_AGAIN = "Check again"
    const val CLEAR_SEARCH = "Clear the search"
    const val ALLOW_ACCESS = "Allow access"
    const val SERVER_ADDRESSES = "Server addresses"
    const val SIGN_IN = "Sign in"
    const val SIGN_OUT = "Sign out"
    const val CANCEL = "Cancel"
    const val PAUSE = "Pause"
    const val TRUST_CERTIFICATE = "Trust this certificate"
    const val TRUST_NEW_CERTIFICATE = "Trust the new certificate"
    const val ENABLE_NOTIFICATIONS = "Enable notifications"
    const val REPAIR_DOWNLOAD = "Repair download"
    const val DOWNLOAD = "Download"
    const val USE_PREVIOUS_POSITION = "Use previous position"
    const val START_OVER = "Start over"
    const val REPLAY = "Replay"

    fun lastRefresh(atText: String) = "Last refreshed $atText."
}

object Format {
    /** Clock-style readout for a moving time: "8:20", "1:02:03". */
    fun clock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Remaining time with a true minus sign: "−12:40". */
    fun remaining(ms: Long): String = "−" + clock(ms)

    /** Whole-book scale: "2 h 08 min", "45 min", "0 min". */
    fun hoursMinutes(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0) / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) String.format(Locale.US, "%d h %02d min", h, m) else "$m min"
    }

    /** "Book · 2 h 08 min of 4 h 20 min". */
    fun bookLine(positionMs: Long, durationMs: Long) = "Book · ${hoursMinutes(positionMs)} of ${hoursMinutes(durationMs)}"

    /** "12 min 40 sec remaining", "45 sec remaining", "1 h 02 min remaining". */
    fun sleepRemaining(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 999) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d h %02d min remaining", h, m)
            m > 0 -> String.format(Locale.US, "%d min %d sec remaining", m, s)
            else -> "$s sec remaining"
        }
    }

    /** A countdown in whole minutes, rounded up: "13 min". */
    fun minutesLeft(ms: Long): String = "${(ms.coerceAtLeast(0) + 59_999) / 60_000} min"

    /** Spoken duration for accessibility: "12 minutes 40 seconds". */
    fun spokenDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = buildList {
            if (h > 0) add(if (h == 1L) "1 hour" else "$h hours")
            if (m > 0) add(if (m == 1L) "1 minute" else "$m minutes")
            if (s > 0 && h == 0L) add(if (s == 1L) "1 second" else "$s seconds")
        }
        return parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    /** "1.25×" and its spoken form "1.25 times". */
    fun speed(speed: Float): String = trimSpeed(speed) + "×"
    fun spokenSpeed(speed: Float): String = trimSpeed(speed) + " times"
    private fun trimSpeed(speed: Float): String {
        val s = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        return s
    }

    /** Byte counts for transfer rows and storage messages. */
    fun bytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0).toDouble()
        return when {
            b >= 1e9 -> String.format(Locale.US, "%.1f GB", b / 1e9)
            b >= 1e6 -> String.format(Locale.US, "%.0f MB", b / 1e6)
            b >= 1e3 -> String.format(Locale.US, "%.0f kB", b / 1e3)
            else -> "${bytes.coerceAtLeast(0)} bytes"
        }
    }

    fun percent(fraction: Float): String = "${(fraction * 100).toInt()}%"
}
