package ch.lkmc.pageworn.formats

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Pre-download format refusal (docs/05-playback-and-formats.md, "Media format acceptance").
 *
 * A track is refused before any audio is fetched when the format matrix records its extension and
 * codec as unsupported on every test target, or when this phone reports no decoder for its codec.
 * The server's `mimeType` is derived from the extension alone and is never consulted. Any other
 * combination is downloaded and judged by [MediaProbe].
 *
 * Evidence: docs/format-matrix.md (Media3 1.11.1, API 34 and API 37 emulators, 21 November 2026).
 */
object FormatMatrix {
    /** Containers no bundled Media3 extractor reads: unsupported whatever the codec. */
    private val unsupportedExtensions = setOf("aiff", "aif", "wma", "caf")

    /** Codecs with no decoder on any test target and none bundled. */
    private val unsupportedCodecs = setOf("alac", "wmav1", "wmav2", "wmapro", "wmalossless", "wmavoice", "mp2", "mp1")

    fun recordedUnsupported(extension: String?, codec: String?): Boolean {
        val ext = extension?.lowercase()?.trimStart('.')
        val c = codec?.lowercase()
        return ext in unsupportedExtensions || c in unsupportedCodecs
    }

    /** Sample MIME type for an ffprobe codec name as Audiobookshelf reports it, or null if unknown. */
    fun mimeForCodec(codec: String?): String? = when (codec?.lowercase()) {
        "aac" -> MimeTypes.AUDIO_AAC
        "mp3" -> MimeTypes.AUDIO_MPEG
        "mp2" -> MimeTypes.AUDIO_MPEG_L2
        "mp1" -> MimeTypes.AUDIO_MPEG_L1
        "opus" -> MimeTypes.AUDIO_OPUS
        "vorbis" -> MimeTypes.AUDIO_VORBIS
        "flac" -> MimeTypes.AUDIO_FLAC
        "alac" -> MimeTypes.AUDIO_ALAC
        "amr_wb" -> MimeTypes.AUDIO_AMR_WB
        "amr_nb" -> MimeTypes.AUDIO_AMR_NB
        "ac3" -> MimeTypes.AUDIO_AC3
        "eac3" -> MimeTypes.AUDIO_E_AC3
        else -> null
    }
}

/** Whether this phone can decode a codec, asked of the platform's codec list. */
fun interface DecoderAvailability {
    /** True or false when known; null for a codec this check cannot map (then the probe decides). */
    fun hasDecoder(codec: String?): Boolean?

    companion object {
        val Platform = DecoderAvailability { codec ->
            val c = codec?.lowercase() ?: return@DecoderAvailability null
            if (c.startsWith("pcm_")) return@DecoderAvailability true
            val mime = FormatMatrix.mimeForCodec(c) ?: return@DecoderAvailability null
            runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty() }.getOrNull()
        }
    }
}

class FormatSupport(private val decoders: DecoderAvailability = DecoderAvailability.Platform) {
    data class Track(val extension: String?, val codec: String?)

    /** True when any track of the book is unsupported: details disable Download and no job starts. */
    fun bookUnsupported(tracks: List<Track>): Boolean = tracks.any { trackUnsupported(it) }

    fun trackUnsupported(track: Track): Boolean =
        FormatMatrix.recordedUnsupported(track.extension, track.codec) || decoders.hasDecoder(track.codec) == false
}
