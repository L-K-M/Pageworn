package ch.lkmc.pageworn.formats

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.playback.BookMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Opens a local track with the same extractors and decoders as the player, without playing it,
 * to learn its actual container, codec, duration and embedded chapter markers before an edition is
 * published. A track the player cannot open is reported as unplayable, whatever its file name says.
 */
class MediaProbe(private val context: Context) {

    data class Result(
        val playable: Boolean,
        val durationMs: Long?,
        val sampleMimeType: String?,
        val codecs: String?,
        val error: String?,
        /** Markers embedded in the file, relative to its start ([EmbeddedChapters]). */
        val chapters: List<RawChapter> = emptyList(),
    )

    suspend fun probe(file: File, timeoutMs: Long = 20_000): Result = withContext(Dispatchers.Main) {
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(FileDataSource.Factory(), BookMediaSourceFactory.extractorsFactory()))
            .build()
        try {
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            val settled = withTimeoutOrNull(timeoutMs) {
                while (player.playbackState != Player.STATE_READY && player.playerError == null) delay(10)
                true
            }
            val error = player.playerError
            if (error != null || settled == null) {
                return@withContext Result(false, null, null, null, error?.let(::describe) ?: "not ready after $timeoutMs ms")
            }
            val audio = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            if (audio.isEmpty()) return@withContext Result(false, duration, null, null, "no audio track")
            // The player becomes ready with an audio track that no decoder on this phone handles
            // (ALAC, MPEG layer II on the emulators); such a file opens but plays nothing.
            val supported = audio.firstOrNull { it.isTrackSupported(0) }
            if (supported == null) {
                val unsupported = audio.first().getTrackFormat(0)
                return@withContext Result(false, duration, unsupported.sampleMimeType, unsupported.codecs, "no decoder for ${unsupported.sampleMimeType}")
            }
            val format = supported.getTrackFormat(0)
            Result(
                duration != null, duration, format.sampleMimeType, format.codecs,
                if (duration == null) "unknown duration" else null,
                EmbeddedChapters.from(format.metadata),
            )
        } finally {
            player.release()
        }
    }

    private fun describe(e: PlaybackException) = "${e.errorCodeName}: ${e.cause?.message ?: e.message}"
}
