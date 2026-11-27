# Pageworn

> [!IMPORTANT]
> LLM disclosure: This codebase was written with substantial help from large language models: AI coding agents working from the [`AGENTS.md`](AGENTS.md) brief in this repo.

**Latest release:** v<!-- version -->0.1.0<!-- /version --> · [Download](https://github.com/L-K-M/Pageworn/releases/latest)

Pageworn is an Android audiobook player for one listener with an [Audiobookshelf](https://www.audiobookshelf.org/) server. The plan, the interface specification and the art live in [`docs/`](docs/README.md); start there.

Pageworn runs on Android 12 (API 31) and newer.

## Build, test and release

[CI/CD](CICD.md) lists the toolchain (JDK 21, Android SDK platform `android-37.0`, build tools 37.0.0) and the build, review and signed-release commands. `scripts/build.sh` builds the debug APK and runs the JVM tests, as CI does on every push. Gradle runs through the checked-in 9.8.2 wrapper; [`gradle/libs.versions.toml`](gradle/libs.versions.toml) pins the Android Gradle Plugin, Kotlin and every library. The Compose BOM stays at 2026.09.00 (Compose 1.12) because Compose 1.13 requires compile SDK 37.1, and the plan fixes compile SDK 37.

## Instrumented tests

The instrumented tests play generated audio fixtures from the test APK's assets, so generate them first. That needs Python 3 and ffmpeg 9.x, as [`fixtures/README.md`](fixtures/README.md) describes. A test that needs a missing fixture fails and names it.

```sh
python3 fixtures/generate.py
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Set `ANDROID_SERIAL` to run on one emulator; without it Gradle runs the tests on every connected device. The local Audiobookshelf v2.38.0 reference instance for server contract checks runs in Docker, as [`server-fixture/README.md`](server-fixture/README.md) describes.

## License

Pageworn's own code and art are released into the public domain under the [Unlicense](LICENSE). The bundled Spectral typeface is licensed under the SIL Open Font License 1.1, and the interface sounds keep their own notices. The app's dependency and asset notices ship inside the APK and are shown under Settings.
