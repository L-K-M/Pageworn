package ch.lkmc.pageworn.formats

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.testing.Fixtures
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A14 and A28, the chapter source of every playable format fixture: the probe that verifies a
 * downloaded track reads the markers the file carries with the player's own extractors, so a book
 * whose server list is unusable falls back to them. Each fixture's manifest entry names the markers
 * ffmpeg wrote (`embedded_chapters`); a fixture without that entry must yield none.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedChaptersProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPlayableFixtureYieldsExactlyTheMarkersItCarries() = runBlocking<Unit> {
        val probe = MediaProbe(context)
        val fixtures = Fixtures.manifest.filter { (it.id.startsWith("fmt-") || it.id == SINGLE_M4B) && it.generated && it.files.size == 1 }
        val seen = mutableListOf<String>()
        for (fixture in fixtures) {
            val file = Fixtures.install(fixture.id).single()
            val result = probe.probe(file)
            if (!result.playable) continue
            val expected = (fixture.raw["embedded_chapters"] as? JsonArray).orEmpty().map { c ->
                val o = c.jsonObject
                o.getValue("start").jsonPrimitive.long * 1_000 to o.getValue("title").jsonPrimitive.content
            }
            val actual = result.chapters.map { it.startMs to it.title }
            assertWithMessage("markers of ${fixture.id}").that(actual).containsExactlyElementsIn(expected).inOrder()
            // A marker that carries an end must not run past the file.
            result.chapters.mapNotNull { it.endMs }.forEach { end ->
                assertWithMessage("marker end in ${fixture.id}").that(end).isAtMost(result.durationMs!! + ROUNDING_MS)
            }
            seen += "${fixture.id}=${if (expected.isEmpty()) "none" else "${expected.size} markers"}"
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", seen.joinToString(", ")) })
        assertWithMessage("fixtures with markers").that(seen.count { !it.endsWith("none") }).isAtLeast(MARKED_FIXTURES)
    }

    private companion object {
        const val SINGLE_M4B = "single-m4b"
        const val ROUNDING_MS = 10L

        /** single-m4b, the M4B, MP3, Opus, Ogg Vorbis and FLAC samples with markers. */
        const val MARKED_FIXTURES = 6
    }
}
