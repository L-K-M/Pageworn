package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.data.db.TransferFileEntity
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ManifestSourceTrack
import java.security.MessageDigest

/** Ordinals whose staged source identity is still present in the refreshed play order. */
fun sameSource(staged: List<TransferFileEntity>, fresh: ItemManifest): Set<Int> = staged.mapNotNull { file ->
    val track = fresh.tracks.getOrNull(file.ordinal)
    if (track != null && file.sizeBytes != null && file.mtimeMs != null &&
        file.sourceFileId == track.fileId && file.sizeBytes == track.sizeBytes && file.mtimeMs == track.mtimeMs
    ) file.ordinal else null
}.toSet()

/** The transfer source fingerprint omits durations; the edition fingerprint includes them. */
fun manifestFingerprint(tracks: List<ManifestSourceTrack>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    tracks.forEach { track ->
        digest.update("${track.fileId.length}:${track.fileId}|${track.sizeBytes ?: -1}|${track.mtimeMs ?: -1};"
            .toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
