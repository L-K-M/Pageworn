package ch.lkmc.pageworn.formats

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.testing.Fixtures
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The probe that verifies every downloaded track (step 5 of the transfer contract) agrees with the
 * format matrix on this phone: it opens each playable family with its duration, and refuses each
 * family the matrix records as unsupported, including those whose container the player can parse
 * but whose codec no decoder here handles. A refused track stops the download with the
 * unsupported-format copy instead of publishing a book that plays nothing.
 */
@RunWith(AndroidJUnit4::class)
class MediaProbeTest {
    private val probe = MediaProbe(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun theProbeAgreesWithTheFormatMatrix() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        for (id in PLAYABLE) {
            val fixture = Fixtures.fixture(id)
            val result = probe.probe(Fixtures.install(id).single())
            assertWithMessage("$id opens: $result").that(result.playable).isTrue()
            assertWithMessage("$id duration").that(abs(result.durationMs!! - fixture.nominalDurationMs)).isAtMost(maxOf(1_000L, fixture.nominalDurationMs / 100))
        }
        for (id in UNSUPPORTED) {
            val result = probe.probe(Fixtures.install(id).single())
            assertWithMessage("$id is refused: $result").that(result.playable).isFalse()
            seen += "$id: ${result.error}"
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", seen.joinToString("; ")) })
    }

    private companion object {
        val PLAYABLE = listOf(
            "fmt-m4b-aac", "fmt-m4a-aac", "fmt-mp3-cbr", "fmt-mp3-vbr", "fmt-flac-flac",
            "fmt-opus-opus", "fmt-ogg-opus", "fmt-ogg-vorbis", "fmt-mp3-chapters",
        )

        /** Every unsupported row of docs/format-matrix.md that has a fixture. */
        val UNSUPPORTED = listOf(
            "fmt-m4a-alac", "fmt-aiff-pcm", "fmt-aif-pcm", "fmt-wma-wmav2", "fmt-caf-alac", "fmt-mpg-mp2", "fmt-mpeg-mp2",
        )
    }
}
