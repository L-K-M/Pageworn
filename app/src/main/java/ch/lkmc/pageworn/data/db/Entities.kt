package ch.lkmc.pageworn.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.timeline.ChapterOrigin

/**
 * Durable state. Times are 64-bit milliseconds, byte counts 64-bit. Server IDs are opaque strings.
 * Token material never enters Room; an address stores only a reference to its credential.
 * See docs/03-android-and-data.md, "Identity and schema".
 */

@Entity(tableName = "profile", primaryKeys = ["profileId"])
data class ProfileEntity(
    val profileId: String,
    /** The server's user ID, which pairing compares. */
    val accountId: String,
    val accountName: String,
    /** A library ID the account could read at first sign-in: pairing evidence besides the account ID. */
    val pairingLibraryId: String?,
    val chosenLibraryId: String?,
    val serverVersion: String?,
    val createdAt: Long,
    /** Whether new downloads are allowed, from the user's `permissions.download`. */
    val canDownload: Boolean = true,
)

enum class PairingState { PAIRED, MISMATCH }

enum class CertificateState {
    /** Normal validation passes, or the confirmed fingerprint matches. */
    OK,

    /** First contact presented a certificate outside the trust anchors; nothing was sent. */
    UNRECOGNIZED,

    /** A confirmed certificate was replaced, or a normally valid address now fails validation. */
    CHANGED,
}

enum class SessionState { SIGNED_IN, EXPIRED, SIGNED_OUT }

@Entity(
    tableName = "address",
    primaryKeys = ["addressId"],
    indices = [Index(value = ["profileId", "baseUrl"], unique = true)],
)
data class AddressEntity(
    val addressId: String,
    val profileId: String,
    /** Normalized origin and path prefix with a single trailing slash. */
    val baseUrl: String,
    val label: String,
    val priority: Int,
    val enabled: Boolean = true,
    val lastSuccessAt: Long? = null,
    /** Reference into the encrypted token store, or null when signed out on this address. */
    val credentialRef: String? = null,
    val cleartextOptIn: Boolean = false,
    /** SHA-256 of the whole confirmed certificate, colon-separated upper-case hex. */
    val confirmedCertSha256: String? = null,
    val certificateState: CertificateState = CertificateState.OK,
    /** Fingerprint presented by the server when [certificateState] is not OK. */
    val presentedCertSha256: String? = null,
    /** True once a connection passed normal validation, so a later failure reads as "changed". */
    val passedNormalValidation: Boolean = false,
    val pairingState: PairingState = PairingState.PAIRED,
    val sessionState: SessionState = SessionState.SIGNED_IN,
)

@Entity(tableName = "library", primaryKeys = ["profileId", "libraryId"])
data class LibraryEntity(
    val profileId: String,
    val libraryId: String,
    val name: String,
    val displayOrder: Int,
    /** Time the last complete refresh of this library was published, or null if never. */
    val lastRefreshAt: Long? = null,
    val publishedRefreshId: String? = null,
)

@Entity(
    tableName = "catalog_book",
    primaryKeys = ["profileId", "libraryId", "itemId"],
    indices = [Index(value = ["profileId", "itemId"])],
)
data class CatalogBookEntity(
    val profileId: String,
    val libraryId: String,
    val itemId: String,
    val title: String,
    val authors: String?,
    val narrators: String?,
    /** Position in the server's title order within the published refresh. */
    val sortIndex: Int,
    /** Item `updatedAt`, used as the cover cache key (`ts`). */
    val updatedAt: Long,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val refreshId: String,
    /** Item `addedAt`; null in rows cached before schema 2, until the next refresh. */
    val addedAt: Long? = null,
    /** Item description; null in rows cached before schema 3, until the next refresh. */
    val description: String? = null,
)

enum class RefreshState { RUNNING, FAILED, PUBLISHED, SUPERSEDED }

@Entity(tableName = "refresh_attempt", primaryKeys = ["refreshId"], indices = [Index(value = ["profileId", "libraryId"])])
data class RefreshAttemptEntity(
    val refreshId: String,
    val profileId: String,
    val libraryId: String,
    /** Monotonic per library: a higher sequence is a newer attempt. */
    val sequence: Long,
    val startedAt: Long,
    val state: RefreshState,
    /** No refresh of this library had been published when the attempt started. */
    val firstLoad: Boolean,
    val serverTotal: Int? = null,
)

/** One staged catalog entry of a refresh attempt; published as a whole or discarded. */
@Entity(tableName = "catalog_staging", primaryKeys = ["refreshId", "itemId"], indices = [Index(value = ["refreshId", "sortIndex"])])
data class CatalogStagingEntity(
    val refreshId: String,
    val itemId: String,
    val title: String,
    val authors: String?,
    val narrators: String?,
    val sortIndex: Int,
    val updatedAt: Long,
    val durationMs: Long?,
    val sizeBytes: Long?,
    /** Entries with no audio tracks count toward completeness but are never published. */
    val playable: Boolean,
    val addedAt: Long? = null,
    val description: String? = null,
)

enum class EditionState { READY, REPAIR_NEEDED }

@Entity(tableName = "local_edition", primaryKeys = ["profileId", "itemId"], indices = [Index(value = ["generationId"], unique = true)])
data class LocalEditionEntity(
    val profileId: String,
    val itemId: String,
    val generationId: String,
    /** Digest of ordered source file IDs, sizes, modification times and track durations. */
    val fingerprint: String,
    val state: EditionState,
    val durationMs: Long,
    /** Directory relative to the edition root, generated from internal IDs. */
    val relativeDir: String,
    /** Cover file name inside [relativeDir], or null for the cloth fallback. */
    val coverFile: String?,
    val title: String,
    val authors: String?,
    val narrators: String?,
    val description: String?,
    val chapterSource: ChapterOrigin?,
    /** Download completion time: the Downloaded sort for never-played books. */
    val publishedAt: Long,
    val sizeBytes: Long,
) {
    val key: BookKey get() = BookKey(profileId, itemId)
}

@Entity(tableName = "track", primaryKeys = ["generationId", "ordinal"])
data class TrackEntity(
    val generationId: String,
    val ordinal: Int,
    val sourceFileId: String,
    /** Source modification time (`mtimeMs`) at download. */
    val sourceRevision: Long,
    val sizeBytes: Long,
    val localFilename: String,
    val startMs: Long,
    val durationMs: Long,
    val title: String?,
    val extension: String,
    val codec: String?,
)

@Entity(tableName = "chapter", primaryKeys = ["generationId", "ordinal"])
data class ChapterEntity(
    val generationId: String,
    val ordinal: Int,
    val title: String,
    val origin: ChapterOrigin,
    val startMs: Long,
    val endMs: Long,
)

/** Removal is recorded before any audio is deleted, and resumed after process death. */
@Entity(tableName = "pending_removal", primaryKeys = ["profileId", "itemId"])
data class PendingRemovalEntity(
    val profileId: String,
    val itemId: String,
    val generationId: String?,
    val requestedAt: Long,
)

enum class TransferState {
    QUEUED, TRANSFERRING, WAITING_FOR_CONNECTION, PAUSED, FAILED, VERIFYING, COMPLETE, CANCELLED;

    val active: Boolean get() = this != COMPLETE && this != CANCELLED
}

enum class FailureCategory {
    NETWORK, SERVER, STORAGE_FULL, DOWNLOAD_DENIED, UNSUPPORTED_FORMAT, NO_AUDIO, SOURCE_MISMATCH,
    SESSION_EXPIRED, NO_ROUTE, LOCAL_NETWORK_DENIED, CERTIFICATE_CHANGED, VERIFICATION,
}

/**
 * One download job. [activeMarker] is 1 while the job is active and null once it is complete or
 * cancelled; the unique index on (profileId, itemId, activeMarker) therefore allows one active job
 * per book while keeping finished rows until cleanup.
 */
@Entity(
    tableName = "transfer",
    primaryKeys = ["jobId"],
    indices = [Index(value = ["profileId", "itemId", "activeMarker"], unique = true)],
)
data class TransferEntity(
    val jobId: String,
    val profileId: String,
    val itemId: String,
    val stagingGenerationId: String,
    val manifestFingerprint: String?,
    val state: TransferState,
    val activeMarker: Int?,
    val userPaused: Boolean = false,
    val failureCategory: FailureCategory? = null,
    val attempt: Int = 0,
    val nextRetryAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val bytesTotal: Long? = null,
    val bytesDone: Long = 0,
    val shortfallBytes: Long? = null,
    /** The address the job last fetched from. */
    val servingAddressId: String? = null,
    /** A repair or replacement of a published edition. */
    val replacesGenerationId: String? = null,
    val title: String,
    val authors: String?,
    /** Cover cache key for the transfer row while no edition exists. */
    val coverUpdatedAt: Long? = null,
) {
    val key: BookKey get() = BookKey(profileId, itemId)
}

enum class FileTransferState { PENDING, PARTIAL, COMPLETE }

@Entity(tableName = "transfer_file", primaryKeys = ["jobId", "ordinal"])
data class TransferFileEntity(
    val jobId: String,
    val ordinal: Int,
    val sourceFileId: String,
    val sizeBytes: Long?,
    val mtimeMs: Long?,
    val extension: String,
    val codec: String?,
    val durationMs: Long,
    val title: String?,
    val localFilename: String,
    /** Journaled length for display; resume decisions use the bytes on disk. */
    val bytesJournal: Long = 0,
    val state: FileTransferState = FileTransferState.PENDING,
    /** Fingerprint of the effective download URL the partial bytes came from. */
    val urlFingerprint: String? = null,
    /** Strong entity tag, or an HTTP date usable as a strong validator. */
    val validator: String? = null,
    val validatorIsDate: Boolean = false,
)

@Entity(tableName = "book_progress", primaryKeys = ["profileId", "itemId"])
data class BookProgressEntity(
    val profileId: String,
    val itemId: String,
    val positionMs: Long,
    val durationAtSaveMs: Long,
    val editionFingerprint: String?,
    val finished: Boolean,
    /** Wall time of the last write: the rest for the resume rewind is measured from here. */
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "ORDINARY") val stopKind: StopKind,
    /** Wall time audio last played: the Downloaded sort. Null for a book never played. */
    val lastListenedAt: Long?,
    val playbackGeneration: Long,
    val writeSequence: Long,
    /** Set when re-downloaded audio changed materially: the listener chooses before first play. */
    val pendingContinuityChoice: Boolean = false,
) {
    val key: BookKey get() = BookKey(profileId, itemId)
}
