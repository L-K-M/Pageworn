package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ResumeRequest
import ch.lkmc.pageworn.server.ServerResult
import java.io.File
import okhttp3.Response

/**
 * What the transfer coordinator needs from address resolution (docs/03-android-and-data.md,
 * runtime ownership): a fresh manifest through a usable route, and the download channel of the
 * address that served it. Tests inject faults here.
 */
interface TransferRoutes {
    /** The expanded item through the first usable address. */
    suspend fun manifest(itemId: String): Routed<ItemManifest>

    /** The channel of [addressId], or null when that address is gone, disabled or signed out. */
    suspend fun channel(addressId: String): DownloadChannel?
}

/** Downloads through one address, with that address's session. */
interface DownloadChannel {
    /** The effective download URL of a file, which a partial's URL fingerprint binds to. */
    fun url(itemId: String, fileId: String): String

    /** Opens one file; the caller closes a successful response. */
    suspend fun open(itemId: String, fileId: String, resume: ResumeRequest?): ServerResult<Response>

    /** The original cover, or null when the item has none or it cannot be read. */
    suspend fun cover(itemId: String): ByteArray?
}

/** Opens a staged track the way the player will, before publication. */
fun interface TrackVerifier {
    suspend fun verify(file: File, extension: String): TrackCheck
}

sealed interface TrackCheck {
    /** [chapters] are the file's embedded markers, relative to its start. */
    data class Playable(val durationMs: Long, val codec: String?, val chapters: List<RawChapter> = emptyList()) : TrackCheck

    /** The player cannot open the file, or the matrix records its format as unsupported. */
    data class Unsupported(val detail: String) : TrackCheck
}

/** Starts the platform work that runs [TransferCoordinator.runQueue]. */
fun interface TransferScheduler {
    /** Returns false when the platform refused, for example because the app is not visible. */
    fun schedule(): Boolean
}
