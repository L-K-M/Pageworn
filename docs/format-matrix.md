# Format matrix

This is the format coverage record that [media format acceptance](05-playback-and-formats.md#media-format-acceptance) asks for. Every row is a generated, redistributable fixture from [`fixtures/manifest.json`](../fixtures/manifest.json), decoded by the app's own extractor settings and the phone's decoders, with no bundled decoder. The instrumented test `FormatMatrixTest` produced it; the raw reports are in [`evidence/`](evidence/).

| Item | Value |
|---|---|
| Server release admitting the extensions | Audiobookshelf v2.38.0 scanner (`SupportedAudioTypes` in `server/utils/globals.js`): m4b, mp3, m4a, flac, opus, ogg, oga, mp4, aac, wma, aiff, aif, wav, webm, webma, mka, awb, caf, mpg, mpeg |
| Engine | Media3 ExoPlayer 1.11.1, `BookMediaSourceFactory.extractorsFactory()` |
| Minimum target | Android 14 (API 34) emulator, `google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys` |
| Current target | Android 17 (API 37) emulator, `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.051.A3/16571990:userdebug/dev-keys` |
| Date | 21 November 2026 |

**Method.** Each fixture is 60 s (the long MP3 21 min) of a quiet 180 Hz bed with 400 ms tone cues at known times, near the start and near 90 %. The test seeks one second before a cue, plays, and finds the cue's onset in the decoded PCM that the player is about to output. The audible error is where the cue was heard minus where it was written: it measures what a listener hears, not the decoder's timestamp. "Resume / late seek" lists the error at the start cue and at the last cue. The pass threshold is 1 s (A14). These are emulator results with software decoders; audible seek accuracy on the listener's phone is an [owner check](06-delivery-and-tests.md#owner-checks).

| Fixture | Ext | Container (ffprobe) | Codec | Chapter source | API 34: resume / late seek | API 37: resume / late seek | Reason when unsupported |
|---|---|---|---|---|---|---|---|
| `fmt-m4b-aac` | m4b | mov,mp4,m4a,3gp,3g2,mj2 | aac | embedded (2 markers) | playable, +2 / +7 ms | playable, +2 / +7 ms |  |
| `fmt-m4a-aac` | m4a | mov,mp4,m4a,3gp,3g2,mj2 | aac | none | playable, +2 / +7 ms | playable, +2 / +7 ms |  |
| `fmt-m4a-alac` | m4a | mov,mp4,m4a,3gp,3g2,mj2 | alac | none | unsupported | unsupported | No ALAC decoder on the phone (MediaCodecList has none; Pageworn bundles none). |
| `fmt-mp4-aac` | mp4 | mov,mp4,m4a,3gp,3g2,mj2 | aac | none | playable, +2 / +7 ms | playable, +2 / +7 ms |  |
| `fmt-mp3-cbr` | mp3 | mp3 | mp3 | none | playable, +7 / +2 ms | playable, +7 / +2 ms |  |
| `fmt-mp3-vbr` | mp3 | mp3 | mp3 | none | playable, +7 / +2 ms | playable, +7 / +2 ms |  |
| `fmt-aac-adts` | aac | aac | aac | none | playable, +2 / +7 ms | playable, +2 / +7 ms |  |
| `fmt-ogg-vorbis` | ogg | ogg | vorbis | none | playable, -18 / -13 ms | playable, -18 / -13 ms |  |
| `fmt-oga-vorbis` | oga | ogg | vorbis | none | playable, -18 / -13 ms | playable, -18 / -13 ms |  |
| `fmt-opus-opus` | opus | ogg | opus | none | playable, -60 / -60 ms | playable, -80 / -80 ms |  |
| `fmt-ogg-opus` | ogg | ogg | opus | none | playable, -60 / -60 ms | playable, -80 / -80 ms |  |
| `fmt-flac-flac` | flac | flac | flac | embedded Vorbis-comment tags | playable, -18 / -63 ms | playable, -18 / -63 ms |  |
| `fmt-mp3-chapters` | mp3 | mp3 | mp3 | embedded ID3v2 CHAP frames (2) | playable, +7 / +2 ms | playable, +7 / +2 ms |  |
| `fmt-opus-chapters` | opus | ogg | opus | embedded `CHAPTERnnn` comments (2) | playable, -60 / -60 ms | playable, -80 / -80 ms |  |
| `fmt-ogg-vorbis-chapters` | ogg | ogg | vorbis | embedded `CHAPTERnnn` comments (2) | playable, -18 / -13 ms | playable, -18 / -13 ms |  |
| `fmt-wav-pcm` | wav | wav | pcm_s16le | none | playable, -48 / -48 ms | playable, -48 / -48 ms |  |
| `fmt-aiff-pcm` | aiff | aiff | pcm_s16be | none | unsupported | unsupported | No Media3 extractor reads AIFF. |
| `fmt-aif-pcm` | aif | aiff | pcm_s16be | none | unsupported | unsupported | No Media3 extractor reads AIFF. |
| `fmt-webm-opus` | webm | matroska,webm | opus | none | playable, -60 / -60 ms | playable, -80 / -80 ms |  |
| `fmt-webma-opus` | webma | matroska,webm | opus | none | playable, -60 / -60 ms | playable, -80 / -80 ms |  |
| `fmt-mka-vorbis` | mka | matroska,webm | vorbis | none | playable, +2 / +7 ms | playable, +2 / +7 ms |  |
| `fmt-mka-aac` | mka | matroska,webm | aac | none | playable, +2 / +7 ms | playable, +2 / +7 ms |  |
| `fmt-wma-wmav2` | wma | asf | wmav2 | none | unsupported | unsupported | No Media3 extractor reads ASF, and no WMA decoder. |
| `fmt-awb-amrwb` | awb | amr | amr_wb | none | playable, +0 / +0 ms | playable, +0 / +0 ms |  |
| `fmt-caf-alac` | caf | caf | alac | none | unsupported | unsupported | No Media3 extractor reads CAF. |
| `fmt-caf-mp3` | caf | caf | mp3 | none | playable, +7 / +2 ms | playable, +7 / +2 ms | No CAF extractor; the MP3 extractor finds the frames after the CAF header. |
| `fmt-caf-aac` | caf | none | none | none | unsupported | unsupported | No fixture: ffmpeg 9.0.2 cannot mux AAC into CAF. CAF itself has no Media3 extractor (see fmt-caf-alac), so every CAF file is refused. |
| `fmt-mpg-mp2` | mpg | mpeg | mp2 | none | unsupported | unsupported | MPEG-PS is extracted, but there is no MPEG audio layer II decoder. |
| `fmt-mpeg-mp2` | mpeg | mpeg | mp2 | none | unsupported | unsupported | MPEG-PS is extracted, but there is no MPEG audio layer II decoder. |
| `fmt-mp3-vbr-long` | mp3 | mp3 | mp3 | none | playable, +7 / +12 ms | playable, +7 / +12 ms |  |

## What the matrix decides

- **Playable on both targets:** M4B, M4A and MP4 with AAC; MP3 constant and variable bit rate, including a 21-minute VBR file seeked at 10, 18 and 20 minutes; ADTS AAC without an index; Ogg Vorbis (`.ogg`, `.oga`); Opus (`.opus`, Opus in `.ogg`); FLAC; PCM WAV; WebM and Matroska audio (`.webm`, `.webma`, `.mka`) with Opus, Vorbis and AAC; AMR-WB (`.awb`). The listener's families (M4B, M4A, MP3, FLAC, Opus, Ogg) all pass, as A14 requires.
- **Unsupported on both targets, refused before download** (`FormatMatrix` in the app): the extensions `aiff`, `aif` and `wma` whatever their codec, `caf` with any codec but MP3, and the codecs `alac`, `wmav1`, `wmav2`, `wmapro`, `wmalossless`, `wmavoice`, `mp1` and `mp2` in any container. A phone that reports no decoder for a book's codec refuses it the same way. Every other combination is downloaded and judged by the probe.
- **Chapter markers** in every family of the listener's library that can carry them (MP4 `chpl`, ID3v2 CHAP, Ogg and FLAC `CHAPTERnnn` comments) are read by the probe after download on both targets (`EmbeddedChaptersProbeTest`), for the [chapter fallback](05-playback-and-formats.md#chapter-map-and-fallback).
- **Open items:** AAC in CAF has no fixture (ffmpeg cannot write one) and is refused through the CAF rule. MPEG-PS with MP3 or AC-3 audio, and ALAC on a phone that ships an ALAC decoder, have no evidence yet and are judged by the probe after download.

## Observed and corrected: MP3 seeking

Media3 1.11.1 seeks a VBR MP3 that has a Xing table by interpolating that table's 100 entries, and uses its index seeker only when a file has no table (`FLAG_ENABLE_INDEX_SEEKING` no longer overrides a Xing table). In the 21-minute fixture that put the 18- and 20-minute cues 1.4 s or more away from the requested position. Pageworn plays local files only, so `ExactMp3ExtractorsFactory` scans an MP3's frame headers once, hands the player an exact frame index as its seek map and corrects the sample timestamps after each seek. The table above is measured with that correction; without it the late seeks failed A14.

## Observed and corrected: MP3 in CAF

The first matrix refused every CAF file, because no Media3 extractor reads CAF. A file with MP3 audio in a CAF container, named `.mp3`, then downloaded and played: Media3's MP3 extractor searches past unknown leading bytes for MPEG frame headers and found the frames after the CAF header. The `fmt-caf-mp3` row measures that combination with the same cues and seeks as the others, and the refusal now spares CAF with MP3. A file the probe cannot open, such as AIFF audio named `.mp3` (`mislabelled-mp3`), still stops its download after the probe with the unsupported-format copy (A24b).
