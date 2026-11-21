package ch.lkmc.pageworn.server

import ch.lkmc.pageworn.core.timeline.RawChapter

/** The playable source list returned by an expanded item request. */
data class ItemManifest(
    val itemId: String,
    val updatedAt: Long,
    val title: String,
    val authors: String?,
    val narrators: String?,
    val description: String?,
    val mediaType: String,
    val tracks: List<ManifestSourceTrack>,
    val chapters: List<RawChapter>,
    val coverPresent: Boolean,
)

data class ManifestSourceTrack(
    val index: Int,
    val fileId: String,
    val startOffsetMs: Long,
    val durationMs: Long,
    val sizeBytes: Long?,
    val mtimeMs: Long?,
    val extension: String,
    val codec: String?,
    val mimeType: String?,
)

data class ResumeRequest(val from: Long, val validator: String) {
    init {
        require(from >= 0)
        require(validator.isNotBlank() && !validator.startsWith("W/", ignoreCase = true))
    }
}
