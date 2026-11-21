package ch.lkmc.pageworn.formats

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.testing.CueListener
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.ListeningRenderersFactory
import ch.lkmc.pageworn.testing.onMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: audible late-seek error of the long VBR MP3 under different MP3 extractor settings. */
@RunWith(AndroidJUnit4::class)
class Mp3SeekVariantsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun compareSeekers() {
        val file = Fixtures.install("fmt-mp3-vbr-long").single()
        val cues = Fixtures.fixture("fmt-mp3-vbr-long").cues
        val variants: Map<String, androidx.media3.extractor.ExtractorsFactory> = mapOf(
            "app" to ch.lkmc.pageworn.playback.BookMediaSourceFactory.extractorsFactory(),
            "default" to DefaultExtractorsFactory(),
            "index" to DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
            "index+cbr" to DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).setConstantBitrateSeekingAlwaysEnabled(true)
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
        )
        for ((name, factory) in variants) {
            val errors = onMain {
                val listener = CueListener()
                val player = ExoPlayer.Builder(context, ListeningRenderersFactory(context, listener))
                    .setMediaSourceFactory(ProgressiveMediaSource.Factory(FileDataSource.Factory(), factory)).build()
                player.volume = 0f
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                withTimeoutOrNull(20_000) { while (player.playbackState != Player.STATE_READY) delay(10) }
                val result = cues.map { cue ->
                    val target = cue.atMs - 1_000
                    player.seekTo(target)
                    withTimeoutOrNull(20_000) { while (player.playbackState != Player.STATE_READY) delay(10) }
                    player.play()
                    withTimeoutOrNull(8_000) { while (listener.capturedMs < 2_200) delay(20) }
                    player.pause()
                    val heard = listener.heard(listOf(700, 900, 1100, 1300, 1500))
                    "cue@${cue.atMs}: pos=${player.currentPosition} captured=${listener.capturedMs} heard=$heard"
                }
                player.release()
                result
            }
            Log.i("PagewornFormat", "mp3-vbr-long $name errors=$errors")
        }
    }
}
