package ch.lkmc.pageworn.debug

import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.edition.EditionFingerprint
import ch.lkmc.pageworn.core.edition.SourceFile
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.core.timeline.ChapterMapBuilder
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.data.EditionFiles
import ch.lkmc.pageworn.data.EditionManifest
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ManifestChapter
import ch.lkmc.pageworn.data.ManifestTrack
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
        val spans = BookTimeline.contiguousTracks(tracks.map { it.durationMs }, tracks.map { it.title })
        val duration = spans.last().endMs
        val chapters = ChapterMapBuilder.build(duration, serverChapters, embeddedChapters, spans.map { it.startMs })
        val trackTitles = chapters.trackTitles
        val manifestTracks = tracks.mapIndexed { i, t ->
            val name = EditionFiles.trackFileName(i, t.file.extension)
            t.file.copyTo(File(staging, name), overwrite = true)
            ManifestTrack(i, t.sourceFileId, t.mtimeMs, t.file.length(), name, spans[i].startMs, t.durationMs, trackTitles?.get(i) ?: t.title, EditionFiles.sanitizeExtension(t.file.extension), null)
        }
        val coverName = cover?.let { c -> EditionFiles.COVER + "." + EditionFiles.sanitizeExtension(c.extension).also { c.copyTo(File(staging, EditionFiles.COVER + "." + it), overwrite = true) } }
        val manifest = EditionManifest(
            generationId = generationId,
            jobId = null,
            profileId = key.profileId,
            itemId = key.itemId,
            fingerprint = EditionFingerprint.of(tracks.map { SourceFile(it.sourceFileId, it.file.length(), it.mtimeMs, it.durationMs) }),
            title = title,
            authors = authors,
            narrators = narrators,
            description = description,
            coverFile = coverName,
            chapterSource = chapters.source,
            tracks = manifestTracks,
            chapters = chapters.chapters.mapIndexed { i, c -> ManifestChapter(i, c.title, c.origin, c.startMs, c.endMs) },
        )
        return container.editionStore.publish(manifest)
    }
}
