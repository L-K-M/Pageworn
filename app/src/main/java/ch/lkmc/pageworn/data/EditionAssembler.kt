package ch.lkmc.pageworn.data

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.edition.EditionFingerprint
import ch.lkmc.pageworn.core.edition.SourceFile
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.core.timeline.ChapterMapBuilder
import ch.lkmc.pageworn.core.timeline.RawChapter

/**
 * Builds the manifest of a staged generation: the tracks as one contiguous book timeline from
 * their probed durations, and the chapter map from the server's chapters, falling back to the
 * embedded ones (playback rules). A download and the debug importer use the same path, so an
 * imported edition behaves exactly like a downloaded one.
 */
object EditionAssembler {
    /** One audio file already in the staging directory under [localFilename]. */
    data class StagedTrack(
        val localFilename: String,
        val sourceFileId: String,
        val sourceMtimeMs: Long,
        val sizeBytes: Long,
        /** Duration probed from the local file, not the server's figure. */
        val durationMs: Long,
        val title: String?,
        val extension: String,
        val codec: String?,
    )

    data class Metadata(val title: String, val authors: String?, val narrators: String? = null, val description: String? = null)

    fun assemble(
        generationId: String,
        jobId: String?,
        key: BookKey,
        metadata: Metadata,
        tracks: List<StagedTrack>,
        serverChapters: List<RawChapter>,
        embeddedChapters: List<List<RawChapter>>,
        coverFile: String?,
    ): EditionManifest {
        require(tracks.isNotEmpty()) { "An edition needs at least one track" }
        val spans = BookTimeline.contiguousTracks(tracks.map { it.durationMs }, tracks.map { it.title })
        val duration = spans.last().endMs
        val chapters = ChapterMapBuilder.build(duration, serverChapters, embeddedChapters, spans.map { it.startMs })
        val trackTitles = chapters.trackTitles
        return EditionManifest(
            generationId = generationId,
            jobId = jobId,
            profileId = key.profileId,
            itemId = key.itemId,
            fingerprint = EditionFingerprint.of(tracks.map { SourceFile(it.sourceFileId, it.sizeBytes, it.sourceMtimeMs, it.durationMs) }),
            title = metadata.title,
            authors = metadata.authors,
            narrators = metadata.narrators,
            description = metadata.description,
            coverFile = coverFile,
            chapterSource = chapters.source,
            tracks = tracks.mapIndexed { i, t ->
                ManifestTrack(i, t.sourceFileId, t.sourceMtimeMs, t.sizeBytes, t.localFilename, spans[i].startMs, t.durationMs, trackTitles?.get(i) ?: t.title, EditionFiles.sanitizeExtension(t.extension), t.codec)
            },
            chapters = chapters.chapters.mapIndexed { i, c -> ManifestChapter(i, c.title, c.origin, c.startMs, c.endMs) },
        )
    }
}
