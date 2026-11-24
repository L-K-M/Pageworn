package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.formats.FormatMatrix
import ch.lkmc.pageworn.formats.TrackProbe
import java.io.File

/**
 * Opens each staged track with the player's extractors and decoders (step 5). A track the player
 * cannot open, or one in a format the matrix records as unsupported, stops the job as an
 * unsupported manifest does, whatever its file name says.
 */
class ProbeTrackVerifier(private val probe: TrackProbe) : TrackVerifier {
    override suspend fun verify(file: File, extension: String, codec: String?): TrackCheck {
        // The manifest's codec decides container rules such as MP3 frames inside CAF, which the
        // format matrix records as playable. Without it every CAF file would stop here.
        if (FormatMatrix.recordedUnsupported(extension, codec)) {
            return TrackCheck.Unsupported("${listOfNotNull(extension, codec).joinToString("/")} is unsupported")
        }
        val result = probe.probe(file)
        val duration = result.durationMs
        if (!result.playable || duration == null) return TrackCheck.Unsupported(result.error ?: "not playable")
        // The manifest is untrusted evidence. A file that opens and decodes still cannot be one of
        // the codecs the matrix refuses on every target, whatever the manifest claimed.
        if (result.codecs != null && FormatMatrix.recordedUnsupported(extension, result.codecs)) {
            return TrackCheck.Unsupported("${listOfNotNull(extension, result.codecs).joinToString("/")} is unsupported")
        }
        // Publish the codec the file actually carries, falling back to the manifest only when the
        // probe could not name one. The probe also reads the embedded markers below, which the
        // chapter map uses when the server's list is unusable.
        return TrackCheck.Playable(duration, codec = result.codecs ?: codec, chapters = result.chapters)
    }
}
