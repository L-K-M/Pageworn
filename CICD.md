# CI/CD

Pageworn requires JDK 21, Android SDK platform `android-37.0` and build tools
`37.0.0`. Use the pinned Gradle wrapper from the Android project. Set
`ANDROID_HOME` to the SDK directory or configure `sdk.dir` in `local.properties`.
Install the APK on Android 12 (API 31) or newer.

```sh
scripts/build.sh
scripts/build.sh --install
scripts/build.sh --check
```

The build script assembles the debug APK and runs JVM tests by default.
`--debug` selects the same build explicitly. Add `--clean` to clear Gradle
build output first. `--install` installs and launches the resulting APK on
a connected phone or emulator. Enable USB or wireless debugging and set
`ANDROID_SERIAL` if several devices are connected. Android requires the same
signing key and an equal or greater version code to replace an installed copy.
`--check` prints the planned build and install options without running them.

Android CI builds the debug APK, runs Android lint, compiles the instrumented
tests and runs JVM tests on every push and pull request.
The **Repository checks** workflow separately validates the scripts and release
version encoding, including before the Android project lands. Emulator and
physical-device checks follow the [milestone plan](docs/06-delivery-and-tests.md).

## Pull request review

The standard GLM 5.3 workflow reviews ready PRs from branches in this repository
using the pinned `L-K-M/zai-code-review` action. It uses the base branch's
workflow and cancels stale runs when new commits arrive. It requires the
`ZAI_API_KEY` repository secret. `MIMO_API_KEY` enables the MiMo fast tier and
`STEPFUN_API_KEY` enables the StepFun fallback. A skipped or failed run leaves
the revision without an automated review. Follow the review rules in
[AGENTS.md](AGENTS.md).

## Signed GitHub releases

Once the [M6 checks](docs/06-delivery-and-tests.md#milestones) pass, run from a
clean `main` checkout that matches `origin/main`. Install `lkm-release` from
the [shared release tool](https://github.com/L-K-M/release-tool).

```sh
scripts/release.sh --check      # inspect the release configuration
scripts/release.sh 1.2.3        # create local tag v1.2.3
scripts/release.sh 1.2.3 --push # create and push that tag instead
```

The tag sets `versionName` to `1.2.3` and `versionCode` to `1002003`.
Both the script and CI require a version newer than other release tags. Minor
and patch each use three places in the version code. A tag on `main` triggers
the JVM tests and a signed, shrunk build. CI verifies the APK signature, app ID and
version before publishing `pageworn-v1.2.3.apk`. Release notes include the
Android Auto unknown-sources setting.

Add these repository secrets before tagging:

| Secret | Value |
| --- | --- |
| `PAGEWORN_KEYSTORE_BASE64` | Base64 of the release keystore file |
| `PAGEWORN_KEYSTORE_PASSWORD` | Keystore password |
| `PAGEWORN_KEY_ALIAS` | Alias of the release signing key |
| `PAGEWORN_KEY_PASSWORD` | Signing key password |

Keep the same key for upgrades. CI decodes it into a temporary file for the
build and removes that file when the signing step exits. For a local smoke
build, point `PAGEWORN_KEYSTORE_FILE` to a throwaway keystore outside the repo,
export the other three signing inputs, then run
`scripts/build.sh --release 1.2.3`. Add `--install` to install and launch
that signed build. The resulting release APK is a test fixture. The install
and playback smoke check is
[A31](docs/06-delivery-and-tests.md#acceptance-matrix).

The Android build generates the dependency notices from each variant’s runtime
classpath. To inspect the release notices without building a signed APK, run
`./gradlew :app:prepareLibraryDefinitionsRelease` and open
`app/build/generated/aboutLibraries/release/res/raw/aboutlibraries.json`.
Settings also includes the Spectral licence from the bundled font resources.
