package ch.lkmc.pageworn.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
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

    @OptIn(ExperimentalApi::class)
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val book = mediaItem.localConfiguration?.tag as? BookSource
            ?: throw IllegalArgumentException("Media item ${mediaItem.mediaId} has no resolved edition")
        val plan = book.edition
        val builder = ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem)
        plan.trackFiles.forEachIndexed { i, file ->
            val track = plan.timeline.tracks[i]
            val audibleMs = (book.audioEndMs?.minus(track.startMs) ?: track.durationMs).coerceIn(0, track.durationMs)
            if (audibleMs > 0) {
                val child = progressive.createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
                val source = if (audibleMs == track.durationMs) child else ClippingMediaSource.Builder(child)
                    .setEndPositionMs(audibleMs)
                    .setAllowUnseekableMedia(true)
                    .setEnableClippingInMediaPeriod(true)
                    .build()
                builder.add(source, audibleMs)
            }
            val silentMs = track.durationMs - audibleMs
            if (silentMs > 0) {
                // Keep the duration and absolute positions of the rest of the book.
                val silence = SilenceMediaSource.Factory().setDurationUs(C.msToUs(silentMs)).createMediaSource()
                builder.add(silence, silentMs)
            }
        }
        return builder.build()
    }

    companion object {
        /**
         * Extractor settings for local audiobook files. MP3 files seek through an exact frame index
         * ([ExactMp3ExtractorsFactory]) instead of the coarse Xing table, so late seeks in long VBR
         * files land on the audio that was asked for. Streams without any index (ADTS AAC) fall back
         * to constant-bitrate seeking. The format matrix measures the result on each target.
         */
        fun extractorsFactory(): ExtractorsFactory = ExactMp3ExtractorsFactory(
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
        )
    }
}
