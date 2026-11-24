package ch.lkmc.pageworn.formats

import java.io.File

/**
 * Opens one local track and reports what it found. [MediaProbe] implements this with the
 * player's own extractors; tests substitute a result without a device decoder.
 */
fun interface TrackProbe {
    suspend fun probe(file: File): MediaProbe.Result
}
