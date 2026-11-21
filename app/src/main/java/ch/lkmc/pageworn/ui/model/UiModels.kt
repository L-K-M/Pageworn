package ch.lkmc.pageworn.ui.model

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
    data class Ready(val isPlaying: Boolean, val start: StartAction) : CatalogAction

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
    val rows: List<CatalogRow> = emptyList(),
    val status: StatusLine? = null,
    /** When set, replaces the rows (and, for no audiobook libraries, the selector and search). */
    val empty: EmptyState? = null,
    val showSelectorAndSearch: Boolean = true,
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
    val isPlaying: Boolean,
    val playable: Boolean,
) {
    val start: StartAction get() = StartAction.of(hasPlace, badge == ReadyBadge.Finished)
}

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
    val isPlaying: Boolean,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val start: StartAction = StartAction.PLAY,
)

enum class SleepChoice { OFF, MINUTES_15, MINUTES_30, MINUTES_45, MINUTES_60, END_OF_SEGMENT }

data class SleepUi(
    val selected: SleepChoice,
    /** "End of chapter", "End of track" or "End of book" for this edition. */
    val endLabel: String,
    /** Live remaining time for the active choice, e.g. "12 min 40 sec remaining". */
    val remainingText: String?,
    /** Accessible value of the Sleep utility, e.g. "Sleep, 14 minutes remaining". */
    val utilityDescription: String,
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
    /** Chapter or track line; null for a single undivided file. */
    val segmentLine: String?,
    val segmentStartMs: Long,
    val segmentEndMs: Long,
    val positionMs: Long,
    /** "8:20" and "−12:40". */
    val elapsedText: String,
    val remainingText: String,
    /** "Book · 2 h 08 min of 4 h 20 min"; null when the slider already spans the book. */
    val bookLine: String?,
    val isPlaying: Boolean,
    val transportEnabled: Boolean,
    /** Audio stopped during playback: message and Repair download below the title block. */
    val failureMessage: String? = null,
    val speed: Float,
    val sleep: SleepUi,
    /** "Chapters" or "Tracks". */
    val segmentsLabel: String,
    val segmentsEnabled: Boolean,
    val segmentsDisabledReason: String? = null,
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
    data class Play(val start: StartAction, val isPlaying: Boolean) : DetailAction
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
    val description: String?,
    /** "Chapters", "Tracks", or null for a single undivided file. */
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
