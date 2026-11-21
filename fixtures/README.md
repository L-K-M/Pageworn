# Audio test fixtures

Synthetic, redistributable audio fixtures for Pageworn's playback, chapter,
seek and format-matrix tests. They are generated locally and are not committed;
`fixtures/out/` is gitignored. The committed `fixtures/manifest.json` describes
the fixtures and records the tool versions, containers, codecs, measured
durations, checksums and the exact generation commands.

## Regenerate

From the repository root:

```sh
python3 fixtures/generate.py              # every fixture
python3 fixtures/generate.py --only <id>  # one fixture, e.g. --only fmt-mp3-cbr
```

Requirements: Python 3 (standard library only) and ffmpeg/ffprobe 9.x on `PATH`.
The generator deletes and recreates `fixtures/out/`, renders the PCM in Python,
encodes it with ffmpeg, measures each file with ffprobe, and writes the manifest
to `fixtures/out/assets/fixtures/manifest.json` and `fixtures/manifest.json`.
ffmpeg writes bit-exact output (no encoder tags, fixed Ogg serial numbers and
Matroska IDs), so a regeneration with the same ffmpeg reproduces the checksums
in the manifest.

The AMR-WB fixture needs an encoder that the local ffmpeg does not build. The
generator streams the WAV through a Docker image that carries `libvo_amrwbenc`
(`thebasicprocess/ffmpeg-amrwb`, with `linuxserver/ffmpeg` and
`jrottenberg/ffmpeg` as fallbacks). If no Docker route is available it records
the fixture as `not-generated` with the reason.

## Signal

Every fixture is mono and carries the same signal: a continuous 180 Hz sine bed
at amplitude 0.05, plus 400 ms sine cues at amplitude 0.5 with 10 ms linear
fades. Cue frequencies come from `[700, 900, 1100, 1300, 1500, 1700, 1900,
2100, 2300, 2500]` Hz in time order. Sample rate is 44100 Hz, except Opus-based
fixtures (48000 Hz) and AMR-WB (16000 Hz). Vorbis is the exception to mono:
ffmpeg's native Vorbis encoder only accepts two channels, so those samples are
dual-mono (identical signal in both channels).

## Use in tests

Instrumented tests read the fixtures from the test APK's assets, which
`app/build.gradle.kts` points at `fixtures/out/assets`. The asset path for a
fixture is `fixtures/<id>/<file>`; the manifest key is the fixture id. Run the
generator before building the instrumentation APK.

Raw ADTS AAC and raw AMR-WB have no duration header, so ffprobe estimates their
duration from the bitrate and can be off by more than the 100 ms the other
containers meet. The manifest reports the measured value honestly; decoding the
files gives the nominal duration.
