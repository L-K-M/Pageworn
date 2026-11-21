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
import ch.lkmc.pageworn.playback.BookMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Opens a local track with the same extractors and decoders as the player, without playing it,
 * to learn its actual container, codec and duration before an edition is published. A track the
 * player cannot open is reported as unplayable, whatever its file name says.
 */
class MediaProbe(private val context: Context) {

    data class Result(
        val playable: Boolean,
        val durationMs: Long?,
        val sampleMimeType: String?,
        val codecs: String?,
        val error: String?,
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
            val format = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            if (format == null) return@withContext Result(false, duration, null, null, "no audio track")
            Result(duration != null, duration, format.sampleMimeType, format.codecs, if (duration == null) "unknown duration" else null)
        } finally {
            player.release()
        }
    }

    private fun describe(e: PlaybackException) = "${e.errorCodeName}: ${e.cause?.message ?: e.message}"
}
