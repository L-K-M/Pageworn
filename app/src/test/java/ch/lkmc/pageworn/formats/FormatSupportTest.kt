package ch.lkmc.pageworn.formats

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormatSupportTest {
    private val everythingDecodes = FormatSupport { true }

    @Test
    fun matrixRefusesRecordedUnsupportedCombinations() {
        assertThat(everythingDecodes.trackUnsupported(FormatSupport.Track("wma", "wmav2"))).isTrue()
        assertThat(everythingDecodes.trackUnsupported(FormatSupport.Track("m4a", "alac"))).isTrue()
        assertThat(everythingDecodes.trackUnsupported(FormatSupport.Track("aif", "pcm_s16be"))).isTrue()
        assertThat(everythingDecodes.trackUnsupported(FormatSupport.Track("caf", "aac"))).isTrue()
        assertThat(everythingDecodes.trackUnsupported(FormatSupport.Track("mpg", "mp2"))).isTrue()
    }

    @Test
    fun listenerFamiliesAreAccepted() {
        listOf("m4b" to "aac", "m4a" to "aac", "mp3" to "mp3", "flac" to "flac", "opus" to "opus", "ogg" to "vorbis", "ogg" to "opus")
            .forEach { (ext, codec) -> assertThat(everythingDecodes.trackUnsupported(FormatSupport.Track(ext, codec))).isFalse() }
    }

    @Test
    fun wavLabelledAudioMpegIsJudgedByCodecNotMime() {
        // The server reports audio/mpeg for WAV; only extension and codec count.
        assertThat(everythingDecodes.trackUnsupported(FormatSupport.Track("wav", "pcm_s16le"))).isFalse()
    }

    @Test
    fun aMissingDecoderOnThisPhoneRefusesTheBook() {
        val noOpus = FormatSupport { codec -> codec != "opus" }
        assertThat(noOpus.bookUnsupported(listOf(FormatSupport.Track("mp3", "mp3"), FormatSupport.Track("opus", "opus")))).isTrue()
        val unknown = FormatSupport { null }
        assertThat(unknown.trackUnsupported(FormatSupport.Track("mp3", "mp3"))).isFalse()
    }
}
