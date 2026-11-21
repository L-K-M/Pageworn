# Pageworn

Pageworn is an Android audiobook player for one listener with an [Audiobookshelf](https://www.audiobookshelf.org/) server. The plan, the interface specification and the art live in [`docs/`](docs/README.md); start there.

## Build requirements

| Requirement | Version |
|---|---|
| JDK | 21 to run Gradle (the app compiles to Java 17 bytecode). Temurin 21 is what CI and the reference machine use. |
| Android SDK | Platform `android-37.0` (compile and target SDK 37), build tools 37.0.0. Minimum SDK is 34 (Android 14). |
| Gradle | 9.8.2 through the checked-in wrapper; do not install Gradle separately. |
| Android Gradle Plugin, Kotlin | Pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) together with every library version. |
| Fixture generation (optional) | Python 3 and ffmpeg 9.x, see [`fixtures/README.md`](fixtures/README.md). |
| Reference server (optional) | Docker, for the local Audiobookshelf v2.38.0 instance described in [`server-fixture/README.md`](server-fixture/README.md). |

Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=/path/to/sdk`.

The Compose BOM is pinned to 2026.09.00 (Compose 1.12). Compose 1.13 requires compile SDK 37.1, and the plan fixes compile SDK 37.

## Commands

```sh
./gradlew :app:assembleDebug            # debug APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest        # JVM tests (CI runs these and the debug build on every push)
./gradlew :app:installDebug             # install on the connected emulator or device
python3 fixtures/generate.py            # generate the audio fixtures that instrumented tests use
./gradlew :app:connectedDebugAndroidTest   # instrumented tests on every connected device
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest   # on one emulator
```

The instrumented tests read the generated fixtures from the test APK's assets. Generate them before running the tests; a test that needs a missing fixture fails and names it.

Release builds are described in [`docs/06-delivery-and-tests.md`](docs/06-delivery-and-tests.md#build-ci-and-release) and in [`.github/workflows/release.yml`](.github/workflows/release.yml).

## Licence

The code and art in this repository are in the public domain under the [Unlicense](LICENSE). The bundled Spectral typeface is licensed under the SIL Open Font License 1.1.
