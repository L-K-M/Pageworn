package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.formats.TrackProbe
import ch.lkmc.pageworn.formats.MediaProbe
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The matrix gate must see the manifest codec, not just the file extension. */
class ProbeTrackVerifierTest {
    @Test
    fun cafWithMp3FramesIsProbedInsteadOfRefused() = runTest {
        var probed = false
        val verifier = ProbeTrackVerifier(TrackProbe {
            probed = true
            playable()
        })

        val result = verifier.verify(File("book.caf"), "caf", "mp3")

        assertThat(result).isInstanceOf(TrackCheck.Playable::class.java)
        assertThat(probed).isTrue()
    }

    @Test
    fun recordedUnsupportedFormatsStopBeforeTheProbe() = runTest {
        var probed = false
        val verifier = ProbeTrackVerifier(TrackProbe {
            probed = true
            playable()
        })

        assertThat(verifier.verify(File("book.wma"), "wma", "wmav2"))
            .isInstanceOf(TrackCheck.Unsupported::class.java)
        assertThat(verifier.verify(File("book.m4a"), "m4a", "alac"))
            .isInstanceOf(TrackCheck.Unsupported::class.java)
        assertThat(verifier.verify(File("book.caf"), "caf", "alac"))
            .isInstanceOf(TrackCheck.Unsupported::class.java)
        assertThat(probed).isFalse()
    }

    @Test
    fun aProbeThatCannotOpenTheFileStopsTheJob() = runTest {
        val verifier = ProbeTrackVerifier(TrackProbe {
            MediaProbe.Result(false, null, null, null, "no audio track")
        })

        val result = verifier.verify(File("book.mp3"), "mp3", "mp3")

        assertThat(result).isInstanceOf(TrackCheck.Unsupported::class.java)
    }

    @Test
    fun aManifestClaimingMp3CannotBypassTheActualCodec() = runTest {
        val verifier = ProbeTrackVerifier(TrackProbe {
            MediaProbe.Result(playable = true, durationMs = 90_000, sampleMimeType = "audio/mp4", codecs = "alac", error = null)
        })

        val result = verifier.verify(File("book.caf"), "caf", "mp3")

        assertThat(result).isInstanceOf(TrackCheck.Unsupported::class.java)
    }

    private fun playable() = MediaProbe.Result(true, 90_000, "audio/mpeg", "mp3", null)
}
