package ch.lkmc.pageworn.formats

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.playback.BookMediaSourceFactory
import ch.lkmc.pageworn.testing.CueListener
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.ListeningRenderersFactory
import ch.lkmc.pageworn.testing.onMain
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * A14 and the format inventory: decode every format fixture with the app's extractor settings,
 * seek near its start and near 90 % of its duration, and measure where the tone cue is actually
 * heard. Writes `format-matrix-api<N>.json` to the app's external files directory for the record.
 *
 * The listener's families (M4B, M4A, MP3, FLAC, Opus, Ogg) must pass with an audible seek error of
 * at most one second. Every other family is recorded as playable or unsupported, with the evidence.
 */
@RunWith(AndroidJUnit4::class)
class FormatMatrixTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    data class Probe(val targetMs: Long, val cueMs: Long, val heardAtMs: Long?) {
        val errorMs: Long? get() = heardAtMs?.let { it - cueMs }
    }

    data class Outcome(
        val id: String,
        val extension: String,
        val container: String,
        val codec: String,
        val sampleMime: String?,
        val decoder: String?,
        val error: String?,
        val reportedDurationMs: Long?,
        val nominalDurationMs: Long,
        val startLatencyMs: Long?,
        val probes: List<Probe>,
    ) {
        val durationOk: Boolean
            get() = reportedDurationMs != null && abs(reportedDurationMs - nominalDurationMs) <= maxOf(1_000L, nominalDurationMs / 100)
        val seeksOk: Boolean get() = probes.isNotEmpty() && probes.all { p -> p.errorMs?.let { abs(it) <= 1_000 } == true }
        val playable: Boolean get() = error == null && durationOk && seeksOk
    }

    @Test
    fun formatMatrix() {
        val fixtures = Fixtures.manifest.filter { it.id.startsWith("fmt-") }
        assertWithMessage("format fixtures in the manifest").that(fixtures).isNotEmpty()
        val outcomes = fixtures.map { f ->
            if (!f.generated) {
                Outcome(f.id, f.files.firstOrNull()?.extension ?: "", "", "", null, null, "fixture not generated: ${f.raw["reason"]}", null, f.nominalDurationMs, null, emptyList())
            } else {
                probe(f)
            }
        }
        val report = buildJsonObject {
            put("api", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("fingerprint", Build.FINGERPRINT)
            put("engine", "Media3 ExoPlayer ${androidx.media3.common.MediaLibraryInfo.VERSION}")
            put(
                "outcomes",
                buildJsonArray {
                    outcomes.forEach { o ->
                        add(
                            buildJsonObject {
                                put("id", o.id)
                                put("extension", o.extension)
                                put("container", o.container)
                                put("codec", o.codec)
                                put("sample_mime", o.sampleMime)
                                put("decoder", o.decoder)
                                put("error", o.error)
                                put("reported_duration_ms", o.reportedDurationMs)
                                put("nominal_duration_ms", o.nominalDurationMs)
                                put("start_latency_ms", o.startLatencyMs)
                                put(
                                    "probes",
                                    buildJsonArray {
                                        o.probes.forEach { p ->
                                            add(
                                                buildJsonObject {
                                                    put("seek_target_ms", p.targetMs)
                                                    put("cue_ms", p.cueMs)
                                                    put("heard_at_ms", p.heardAtMs)
                                                    put("audible_error_ms", p.errorMs)
                                                },
                                            )
                                        }
                                    },
                                )
                                put("outcome", if (o.playable) "playable" else "unsupported")
                            },
                        )
                    }
                },
            )
        }
        val out = File(context.getExternalFilesDir(null), "format-matrix-api${Build.VERSION.SDK_INT}.json")
        out.writeText(report.toString())
        outcomes.forEach { Log.i(TAG, "${it.id}: ${if (it.playable) "playable" else "unsupported"} error=${it.error} duration=${it.reportedDurationMs} probes=${it.probes}") }

        val required = setOf(
            "fmt-m4b-aac", "fmt-m4a-aac", "fmt-mp4-aac", "fmt-mp3-cbr", "fmt-mp3-vbr", "fmt-mp3-vbr-long",
            "fmt-flac-flac", "fmt-opus-opus", "fmt-ogg-opus", "fmt-ogg-vorbis", "fmt-oga-vorbis",
        )
        outcomes.filter { it.id in required }.forEach { o ->
            assertWithMessage("${o.id} must be playable on API ${Build.VERSION.SDK_INT}: $o").that(o.playable).isTrue()
        }
    }

    private fun probe(f: Fixtures.Fixture): Outcome = onMain {
        val file = Fixtures.install(f.id).first()
        val meta = f.files.first()
        val listener = CueListener()
        val player = ExoPlayer.Builder(context, ListeningRenderersFactory(context, listener))
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(FileDataSource.Factory(), BookMediaSourceFactory.extractorsFactory()))
            .build()
        player.volume = 0f
        try {
            val started = SystemClock.elapsedRealtime()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            val ready = withTimeoutOrNull(20_000) {
                while (player.playbackState != Player.STATE_READY && player.playerError == null) delay(10)
                true
            }
            val latency = SystemClock.elapsedRealtime() - started
            val format = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)
            val mime = format?.sampleMimeType
            val decoder = mime?.let { runCatching { MediaCodecUtil.getDecoderInfos(it, false, false).firstOrNull()?.name }.getOrNull() }
            val error = player.playerError?.let { describe(it) } ?: if (ready == null) "not ready after 20 s" else null
            if (error != null) {
                return@onMain Outcome(f.id, meta.extension, meta.container, meta.codec, mime, decoder, error, null, f.nominalDurationMs, null, emptyList())
            }
            val duration = player.duration.takeIf { it != C.TIME_UNSET }
            val cues = listOfNotNull(f.cues.firstOrNull(), f.cues.lastOrNull()).distinct()
            val probes = cues.map { cue -> seekAndListen(player, listener, cue) }
            Outcome(f.id, meta.extension, meta.container, meta.codec, mime, decoder, player.playerError?.let { describe(it) }, duration, f.nominalDurationMs, latency, probes)
        } finally {
            player.release()
        }
    }

    private suspend fun seekAndListen(player: ExoPlayer, listener: CueListener, cue: Fixtures.Cue): Probe {
        val target = (cue.atMs - 1_000).coerceAtLeast(0)
        player.seekTo(target)
        withTimeoutOrNull(15_000) { while (player.playbackState != Player.STATE_READY && player.playerError == null) delay(10) }
        // The sink flushed at the seek, so the capture starts with the first sample rendered from the target.
        player.play()
        withTimeoutOrNull(8_000) {
            while (listener.capturedMs < 2_200 && player.playerError == null && player.playbackState != Player.STATE_ENDED) delay(20)
        }
        player.pause()
        val onset = listener.onsetMs(cue.freqHz)
        return Probe(target, cue.atMs, onset?.let { target + it })
    }

    private fun describe(e: PlaybackException) = "${e.errorCodeName}: ${e.cause?.javaClass?.simpleName ?: ""} ${e.cause?.message ?: e.message}".trim()

    companion object {
        const val TAG = "PagewornFormat"
    }
}
