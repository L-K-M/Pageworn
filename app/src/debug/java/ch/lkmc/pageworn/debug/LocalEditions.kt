package ch.lkmc.pageworn.debug

import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.data.EditionAssembler
import ch.lkmc.pageworn.data.EditionFiles
import ch.lkmc.pageworn.data.EditionStore
import java.io.File
import java.util.UUID

/**
 * Debug and test only: publishes local audio files as a ready edition without a transfer, through
 * the same staging, finalize and commit path a download uses.
 */
object LocalEditions {
    data class Track(val file: File, val durationMs: Long, val title: String? = null, val sourceFileId: String = file.name, val mtimeMs: Long = 0)

    suspend fun publish(
        container: AppContainer,
        key: BookKey,
        title: String,
        authors: String?,
        tracks: List<Track>,
        serverChapters: List<RawChapter> = emptyList(),
        embeddedChapters: List<List<RawChapter>> = emptyList(),
        cover: File? = null,
        narrators: String? = null,
        description: String? = null,
        generationId: String = UUID.randomUUID().toString(),
    ): EditionStore.PublishOutcome {
        val files = container.editionFiles
        files.ensureRoots()
        val staging = files.staging(generationId)
        staging.mkdirs()
        val staged = tracks.mapIndexed { i, t ->
            val name = EditionFiles.trackFileName(i, t.file.extension)
            t.file.copyTo(File(staging, name), overwrite = true)
            EditionAssembler.StagedTrack(name, t.sourceFileId, t.mtimeMs, t.file.length(), t.durationMs, t.title, t.file.extension, null)
        }
        val coverName = cover?.let { c -> EditionFiles.COVER + "." + EditionFiles.sanitizeExtension(c.extension).also { c.copyTo(File(staging, EditionFiles.COVER + "." + it), overwrite = true) } }
        val manifest = EditionAssembler.assemble(
            generationId, jobId = null, key, EditionAssembler.Metadata(title, authors, narrators, description),
            staged, serverChapters, embeddedChapters, coverName,
        )
        return container.editionStore.publish(manifest)
    }
}
