package ch.lkmc.pageworn.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor

/**
 * Resolves one book [MediaItem] to a [ConcatenatingMediaSource2] over the edition's ordered local
 * tracks, so the player position is book time and a seek across a file boundary stays in one item.
 * Each child gets the verified track duration as its placeholder duration.
 *
 * [dataSourceFactory] reads local files only. Tests substitute a fault-injecting factory.
 */
class BookMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory {
    private var progressive = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory())

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        progressive = progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        progressive = progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val plan = mediaItem.localConfiguration?.tag as? EditionPlan
            ?: throw IllegalArgumentException("Media item ${mediaItem.mediaId} has no resolved edition")
        val builder = ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem)
        plan.trackFiles.forEachIndexed { i, file ->
            val child = progressive.createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
            builder.add(child, plan.timeline.tracks[i].durationMs)
        }
        return builder.build()
    }

    companion object {
        /**
         * Extractor settings for local audiobook files. MP3 uses index seeking, which reads the file
         * up to the target instead of trusting a coarse Xing table, so late seeks in long VBR files
         * land on the audio that was asked for. Streams without an index (ADTS AAC, MP3 without a
         * header) fall back to constant-bitrate seeking. The format matrix measures the result.
         */
        fun extractorsFactory(): DefaultExtractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
    }
}
