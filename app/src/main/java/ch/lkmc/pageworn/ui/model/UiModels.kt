package ch.lkmc.pageworn.ui.model

import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.catalog.CatalogSort
import ch.lkmc.pageworn.core.BookKey
import java.io.File

/*
 * Immutable UI state for every screen. ViewModels produce these from Room and the playback hub;
 * composables render them and report intents. All copy shown to the listener comes from [Copy].
 */

/** Where a cover image comes from. Downloaded and the player always use the edition's own copy. */
sealed interface CoverImage {
    /** The original stored with a published edition. */
    data class Local(val file: File) : CoverImage

    /** A catalog thumbnail fetched through the profile's route and cached; may be evicted. */
    data class Remote(val profileId: String, val itemId: String, val updatedAt: Long) : CoverImage

    /** No art: draw the cloth cover with the live title and author. */
    data object Cloth : CoverImage
}

/** What identifies a book on screen. */
data class BookFace(
    val key: BookKey,
    val title: String,
    val author: String?,
    val cover: CoverImage,
)

enum class Destination { LIBRARY, DOWNLOADED }

// --- Status lines and empty states (docs/02-interface.md "Status lines and empty states") ---------

enum class UiAction {
    RETRY,
    ALLOW_LOCAL_NETWORK,
    OPEN_ADDRESSES,
    SIGN_IN,
    NOTIFICATION_SETTINGS,
    BROWSE_LIBRARY,
    CHECK_AGAIN,
    CLEAR_SEARCH,
    REFRESH,
}

/** A recoverable condition shown as live type between the heading block and the first row. */
data class StatusLine(
    val text: String,
    val action: UiAction? = null,
    val actionLabel: String? = null,
    /** Library still loading: a small native indeterminate progress mark. */
    val showsProgress: Boolean = false,
)

/** Shown in place of the rows, starting at the first row position. */
data class EmptyState(
    val text: String,
    val action: UiAction? = null,
    val actionLabel: String? = null,
)

// --- Transfers ------------------------------------------------------------------------------------

enum class TransferPhase { QUEUED, TRANSFERRING, WAITING, PAUSED, FAILED, VERIFYING }

data class TransferUi(
    val jobId: String,
    val phase: TransferPhase,
    val bytesDone: Long,
    /** Null when the total is unknown: show bytes received instead of a percentage. */
    val bytesTotal: Long?,
    /** State text paired with the icon, e.g. "Waiting for connection" or "Paused". */
    val stateText: String,
    /** Failure copy from the product brief, e.g. the storage-full message with the shortfall. */
    val message: String? = null,
    val canPause: Boolean,
    val canResume: Boolean,
    val canRetry: Boolean,
    val canCancel: Boolean,
) {
    val fraction: Float? get() = bytesTotal?.takeIf { it > 0 }?.let { (bytesDone.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

// --- Library -----------------------------------------------------------------------------------

sealed interface CatalogAction {
    /** A remote book the account may download. */
    data object Download : CatalogAction

    /** The account can browse but not download; the row shows the download-disabled message. */
    data object DownloadDisabled : CatalogAction

    /** A format the phone cannot play; a Download tap starts nothing and shows the message. */
    data object Unsupported : CatalogAction

    data class Transferring(val transfer: TransferUi) : CatalogAction

    /** A locally ready book: Play, Resume or Replay key and an accessible "Downloaded" state. */
    data class Ready(val showsPause: Boolean, val starting: Boolean, val start: StartAction) : CatalogAction

    data object RepairNeeded : CatalogAction
}

data class CatalogRow(
    val face: BookFace,
    val action: CatalogAction,
    /** Row message on the metadata line (download disabled, unsupported format, ...). */
    val message: String? = null,
)

data class LibraryChoice(val id: String, val name: String)

data class LibraryUiState(
    val heading: String = "Library",
    val libraries: List<LibraryChoice> = emptyList(),
    val selectedLibraryId: String? = null,
    val query: String = "",
    val sort: CatalogSort = CatalogSort.DEFAULT,
    val rows: List<CatalogRow> = emptyList(),
    val status: StatusLine? = null,
    /** When set, replaces the rows (and, for no audiobook libraries, the selector and search). */
    val empty: EmptyState? = null,
    val showSelectorAndSearch: Boolean = true,
    /** A catalog refresh is running: the pull-to-refresh indicator spins. */
    val refreshing: Boolean = false,
)

// --- Downloaded --------------------------------------------------------------------------------

sealed interface ReadyBadge {
    /** Never played. */
    data object New : ReadyBadge

    /** E.g. "Chapter 4 · 2 h 12 min left". */
    data class InProgress(val text: String) : ReadyBadge

    data object Finished : ReadyBadge

    /** With the damaged-copy or removed-audio message. */
    data class RepairNeeded(val message: String) : ReadyBadge
}

data class ReadyBookRow(
    val face: BookFace,
    val badge: ReadyBadge,
    /** A saved position exists: draw the place ribbon. */
    val hasPlace: Boolean,
    /** The key shows Pause, and a tap on it pauses. */
    val showsPause: Boolean,
    /** Pause is shown but no audio is heard yet. */
    val starting: Boolean,
    val playable: Boolean,
) {
    val start: StartAction get() = StartAction.of(hasPlace, badge == ReadyBadge.Finished)
}

/** What a Play/Pause key showed when it was tapped, which is what the tap does. */
enum class KeyAction { PLAY, PAUSE }

/** What a play key starts: a book from its beginning, its saved place, or a finished book again. */
enum class StartAction {
    PLAY, RESUME, REPLAY;

    companion object {
        fun of(hasPlace: Boolean, finished: Boolean): StartAction = when {
            finished -> REPLAY
            hasPlace -> RESUME
            else -> PLAY
        }
    }
}

data class TransferRow(val face: BookFace, val transfer: TransferUi)

data class DownloadedUiState(
    val heading: String = "Downloaded",
    val transfers: List<TransferRow> = emptyList(),
    val books: List<ReadyBookRow> = emptyList(),
    val status: StatusLine? = null,
    val empty: EmptyState? = null,
)

// --- Mini-player and player --------------------------------------------------------------------

data class MiniPlayerUi(
    val face: BookFace,
    /** Chapter or track label, e.g. "Chapter 4 · The Gate". */
    val subtitle: String?,
    val showsPause: Boolean,
    val starting: Boolean,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val start: StartAction = StartAction.PLAY,
)

/** One row of the Sleep sheet: Off when [length] is null, a preset, or End of chapter. */
data class SleepOption(val length: SleepLength?, val label: String)

data class SleepUi(
    /** Off, the listener's presets, then "End of chapter", "End of track" or "End of part". */
    val options: List<SleepOption>,
    /** The mode's length, or null while sleep mode is off. */
    val selected: SleepLength?,
    /** Live remaining time of a running timer, e.g. "12 min 40 sec remaining". */
    val remainingText: String?,
    /** Accessible value of the Sleep utility, e.g. "Sleep, 14 minutes remaining". */
    val utilityDescription: String,
    /** Under the Sleep utility: a running countdown ("12:40"), else the mode's length ("30 min"); null when off. */
    val shortTarget: String? = null,
)

data class SegmentRow(
    val index: Int,
    val title: String,
    val durationText: String,
    val generated: Boolean,
    val current: Boolean,
    val startMs: Long,
)

data class ContinuityChoice(
    /** E.g. "1 h 05 min". */
    val previousPositionText: String,
    val newDurationText: String,
)

data class PlayerUiState(
    val face: BookFace,
    val narrator: String? = null,
    /** Title of the chapter, track or part that holds the position. */
    val segmentLine: String,
    /** What such a segment is called: "Chapter", "Track" or "Part". */
    val segmentName: String,
    val segmentStartMs: Long,
    val segmentEndMs: Long,
    val positionMs: Long,
    /** "8:20" and "−12:40". */
    val elapsedText: String,
    val remainingText: String,
    /** "Book · 2 h 08 min of 4 h 20 min". */
    val bookLine: String,
    val bookDurationMs: Long,
    val showsPause: Boolean,
    val starting: Boolean = false,
    val transportEnabled: Boolean,
    val transportDisabledReason: String? = null,
    /** Why the last tap on Play started nothing, below the title block. */
    val playNotice: String? = null,
    /** Audio stopped during playback: message and Repair download below the title block. */
    val failureMessage: String? = null,
    val speed: Float,
    val sleep: SleepUi,
    /** "Chapters" or "Tracks". */
    val segmentsLabel: String,
    val segments: List<SegmentRow>,
    val hasPlace: Boolean,
    val finished: Boolean = false,
    val continuityChoice: ContinuityChoice? = null,
)

// --- Book detail -------------------------------------------------------------------------------

sealed interface DetailAction {
    data object Download : DetailAction
    data object DownloadDisabled : DetailAction
    data object Unsupported : DetailAction
    data class Transferring(val transfer: TransferUi) : DetailAction
    data class Play(val start: StartAction, val showsPause: Boolean, val starting: Boolean) : DetailAction
    data object Repair : DetailAction
}

data class BookDetailUiState(
    val face: BookFace,
    val narrator: String?,
    val durationText: String?,
    val sizeText: String?,
    val action: DetailAction,
    /** Message under the primary action. */
    val message: String? = null,
    /** Why the last tap on Play started nothing; shown instead of [message], with Dismiss. */
    val playNotice: String? = null,
    val description: String?,
    /** "Chapters", "Tracks" or "Parts"; null until the book is downloaded. */
    val segmentsTitle: String?,
    val segments: List<SegmentRow>,
    /** Chapter rows start local playback only for a downloaded book. */
    val segmentsPlayable: Boolean,
    val canRemove: Boolean,
)

// --- Settings, addresses and connection setup -------------------------------------------------

data class SettingsUiState(
    val accountName: String?,
    val signedIn: Boolean,
    val versionText: String,
    val confirmSignOut: Boolean = false,
    /** The Sleep timer row's summary, e.g. "15, 30, 45 and 60 minutes · Automatic off". */
    val sleepSummary: String = "15, 30, 45 and 60 minutes · Automatic off",
)

/** One timer length offered on the Sleep timer page. */
data class SleepPresetRow(val minutes: Int, val label: String)

/** The Sleep timer page: presets, the automatic window and what the picker shows. */
data class SleepSettingsUiState(
    val presets: List<SleepPresetRow> = emptyList(),
    /** At least two lengths stay, so the last one keeps no Remove. */
    val canRemove: Boolean = false,
    /** Eight is the most the player offers. */
    val canAdd: Boolean = true,
    val automatic: Boolean = false,
    /** The window in the device's own short clock format, e.g. "22:00". */
    val fromText: String = "",
    val untilText: String = "",
    /** The same times as minutes after midnight, for a TimePicker. */
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
)

data class AddressRow(
    val addressId: String,
    val label: String,
    val url: String,
    val enabled: Boolean,
    val isActive: Boolean,
    val cleartext: Boolean,
    val lastSuccessText: String?,
    /** Local-network, certificate, mismatch or session message for this address. */
    val message: String?,
    /** SHA-256 fingerprint shown with an unrecognized or changed certificate. */
    val fingerprint: String?,
    val canTrustCertificate: Boolean,
    val canAllowLocalNetwork: Boolean,
    val needsSignIn: Boolean,
    val testResult: String? = null,
)

data class AddressesUiState(
    val rows: List<AddressRow>,
    val automatic: Boolean,
    val pinnedAddressId: String?,
)

enum class ConnectStep { ADDRESS, CREDENTIALS, CONFIRM_PAIRING }

data class ConnectUiState(
    val step: ConnectStep = ConnectStep.ADDRESS,
    val heading: String = "Connect to server",
    val url: String = "",
    val label: String = "",
    val username: String = "",
    val password: String = "",
    /** Explicit choice required before using http. */
    val allowCleartext: Boolean = false,
    val cleartextExplanation: String? = null,
    val addressError: String? = null,
    val usernameError: String? = null,
    val credentialsError: String? = null,
    /** Unrecognized or changed certificate: fingerprint with its Trust action. */
    val fingerprint: String? = null,
    val canRetry: Boolean = false,
    /** The local-network action's label, or null: Continue before the system asks, Allow access once denied. */
    val localNetworkAction: String? = null,
    /** Pairing a second address: what matched, for the one-time confirmation. */
    val pairingSummary: String? = null,
    val busy: Boolean = false,
)
