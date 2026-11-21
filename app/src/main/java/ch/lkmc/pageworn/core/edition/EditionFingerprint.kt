package ch.lkmc.pageworn.core.edition

import java.security.MessageDigest

/** Revision evidence of one source audio file in play order. */
data class SourceFile(val fileId: String, val sizeBytes: Long?, val mtimeMs: Long?, val durationMs: Long)

/**
 * The edition fingerprint: a digest of the ordered source file IDs, sizes and modification times and
 * the ordered track durations. Cover, title, author, description and chapter edits leave it intact;
 * reordering, replacing or re-encoding audio changes it. See docs/03-android-and-data.md.
 */
object EditionFingerprint {
    fun of(files: List<SourceFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { f ->
            digest.update("${f.fileId.length}:${f.fileId}|${f.sizeBytes ?: -1}|${f.mtimeMs ?: -1}|${f.durationMs};".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
