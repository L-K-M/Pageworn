package ch.lkmc.pageworn.data

import ch.lkmc.pageworn.core.timeline.ChapterOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Layout of audio in persistent app-private storage, outside cache and backup. Paths come from
 * internal IDs only: a generation's staging directory and its published directory share a volume,
 * so publication is one atomic directory rename.
 *
 *     <root>/staging/<generationId>/   audio being written, manifest, optional cover
 *     <root>/editions/<generationId>/  a finalized generation (published or awaiting the Room commit)
 */
class EditionFiles(val root: File) {
    val stagingRoot = File(root, "staging")
    val editionsRoot = File(root, "editions")

    fun staging(generationId: String) = File(stagingRoot, checkId(generationId))
    fun published(generationId: String) = File(editionsRoot, checkId(generationId))

    fun ensureRoots() {
        stagingRoot.mkdirs()
        editionsRoot.mkdirs()
    }

    /** Moves a complete staging directory to its final place. Throws if the rename fails. */
    fun finalize(generationId: String): File {
        val from = staging(generationId)
        val to = published(generationId)
        if (to.isDirectory && !from.exists()) return to
        editionsRoot.mkdirs()
        if (!from.renameTo(to)) throw IOException("Could not finalize generation $generationId")
        return to
    }

    fun readManifest(dir: File): EditionManifest? = runCatching {
        json.decodeFromString(EditionManifest.serializer(), File(dir, MANIFEST).readText())
    }.getOrNull()

    fun writeManifest(dir: File, manifest: EditionManifest) {
        val tmp = File(dir, "$MANIFEST.tmp")
        tmp.writeText(json.encodeToString(EditionManifest.serializer(), manifest))
        if (!tmp.renameTo(File(dir, MANIFEST))) throw IOException("Could not write the edition manifest")
    }

    fun deleteGeneration(generationId: String) {
        staging(generationId).deleteRecursively()
        published(generationId).deleteRecursively()
    }

    /** Free bytes on the volume that holds the editions. */
    fun usableSpace(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    companion object {
        const val MANIFEST = "edition.json"
        const val COVER = "cover"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val idPattern = Regex("^[A-Za-z0-9-]{1,64}$")

        private fun checkId(id: String): String {
            require(idPattern.matches(id)) { "Not an internal generation ID" }
            return id
        }

        /** Local track file name from its ordinal and a sanitized extension. */
        fun trackFileName(ordinal: Int, extension: String): String = "%03d.%s".format(ordinal, sanitizeExtension(extension))

        fun sanitizeExtension(extension: String): String {
            val e = extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8)
            return e.ifEmpty { "bin" }
        }
    }
}

/** Compact manifest finalized with a generation; recovery reads it to finish an interrupted publish. */
@Serializable
data class EditionManifest(
    val version: Int = 1,
    val generationId: String,
    /** The job that staged this generation, or null for an edition imported without a transfer. */
    val jobId: String?,
    val profileId: String,
    val itemId: String,
    val fingerprint: String,
    val title: String,
    val authors: String? = null,
    val narrators: String? = null,
    val description: String? = null,
    val coverFile: String? = null,
    val chapterSource: ChapterOrigin? = null,
    val tracks: List<ManifestTrack>,
    val chapters: List<ManifestChapter>,
)

@Serializable
data class ManifestTrack(
    val ordinal: Int,
    val sourceFileId: String,
    val sourceRevision: Long,
    val sizeBytes: Long,
    val localFilename: String,
    val startMs: Long,
    val durationMs: Long,
    val title: String? = null,
    val extension: String,
    val codec: String? = null,
)

@Serializable
data class ManifestChapter(
    val ordinal: Int,
    val title: String,
    val origin: ChapterOrigin,
    val startMs: Long,
    val endMs: Long,
)
