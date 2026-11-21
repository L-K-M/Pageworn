package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.formats.FormatMatrix
import ch.lkmc.pageworn.formats.MediaProbe
import java.io.File

/**
 * Opens each staged track with the player's extractors and decoders (step 5). A track the player
 * cannot open, or one in a format the matrix records as unsupported, stops the job as an
 * unsupported manifest does, whatever its file name says.
 */
class ProbeTrackVerifier(private val probe: MediaProbe) : TrackVerifier {
    override suspend fun verify(file: File, extension: String): TrackCheck {
        if (FormatMatrix.recordedUnsupported(extension, null)) return TrackCheck.Unsupported("$extension is unsupported")
        val result = probe.probe(file)
        val duration = result.durationMs
        if (!result.playable || duration == null) return TrackCheck.Unsupported(result.error ?: "not playable")
        // The server's codec name stays on the track; the probe only proves the file opens.
        return TrackCheck.Playable(duration, codec = null)
    }
}
