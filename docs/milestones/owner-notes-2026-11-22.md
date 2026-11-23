# Follow-up report: the owner's notes of 22 November 2026

Date: 22 November 2026. Target: the owner's five notes on the finished milestones, which override the plan where they differ. The plan changed in the same commits.

## 1. Delivered

| Note | Change |
|---|---|
| The screens lack the mockups' details | Page edges and creases, shaded bookmarks, bound covers that keep their shape, brass rims, one set of paper controls and sheets, and the mockups' type sizes and spacing. |
| Sort Library, newest first | Four orders, with date added, newest first, as the default. The choice survives a relaunch. Books without a date come last, and books cached before this change get theirs at the first refresh. |
| Play is unreliable | A key sends what it showed when it was touched, and a double tap is one tap by the touches' own times. A start shows at once and reports when it fails. A resting book keeps how and when it stopped. |
| Two bars, and ten-minute chunks | A bar for the chapter, track or part and one for the book. A single file without chapters has ten-minute parts, with the remainder last. |
| Settings and Server addresses look undesigned | Settings, Server addresses, Licences, connection setup and details are raised paper slips with pencil marks, leather buttons and the palette's own fields, switches and dialogs. |

Choices the notes left open, now written into the plan:

- A book of several files without chapters keeps its tracks as segments. One chapter around the whole book counts as none.
- A double tap is one tap on Pause as well, so Play followed by Pause within about 300 ms keeps playing.
- An authored chapter title gets no "Chapter N" in front, although the mockup draws one, because the [playback contract](../05-playback-and-formats.md) forbids invented titles.

## 2. Files and design decisions

- Playback: `ui/components/Keys.kt`, `playback/PlayerConnection.kt`, `playback/LivePlayback.kt` and `playback/PlaybackController.kt`. A pause's place follows the player until it settles, up to about 120 ms, and a seek ends that.
- Segments: `core/timeline/BookTimeline.kt` owns parts, and `ui/player/PlayerScrubbers.kt` draws both scales. A drag keeps the scale it began on.
- Order: `catalog/CatalogSort.kt` sorts the published, staged and searched rows off the main thread. Room schema 2 adds a nullable `addedAt` without guessing values.
- Design: `ui/components/PaperControls.kt` holds the shared controls, and `ui/theme/Theme.kt` the tokens.
- Plan: documents 01 to 08 and the README, with new cases A16g and A18b and changed cases A14, A15, A24 and A28b.

Independent reviews followed. The design review finished with eight findings: one was refuted, six were fixed in code and one corrected the plan's cover size to the mockup's. The reviews of Play, the two bars and the sort timed out twice without a final list, so their streamed notes were checked by hand. They exposed a regression from `09bd532`: pause, seek into a track that cannot open, and the failed target replaced the saved place. `c6efe46` fixes it. They also found a scroll reset on relaunch, fixed in `68966f4`. The last completeness review timed out twice as well, and none of its partial findings held. There was no pull request and so no automated PR review, because the owner asked for commits on `main`.

## 3. Builds, tests and targets

JDK 21, compile and target API 37, minimum API 31, Media3 1.11.1, Audiobookshelf v2.38.0 in a local container.

```sh
scripts/build.sh
./gradlew :app:lintDebug :app:compileDebugAndroidTestKotlin
adb -s <emulator> shell am instrument -w -r -e package ch.lkmc.pageworn.<ui|formats|playback> \
  ch.lkmc.pageworn.test/androidx.test.runner.AndroidJUnitRunner
scripts/build.sh --release 1.2.5   # with the four throwaway PAGEWORN_* inputs, then without
```

| Check | Tree | Target | Result |
|---|---|---|---|
| Build, JVM tests, lint | `c71e973` | JVM, Robolectric API 34 | 230 tests, none failing or skipped |
| UI suite | `4fb0b8d` | API 37 and API 31 | 25 of 26 on each, connect journey skipped for lack of arguments |
| Playback suite | `4fb0b8d` | API 37 and API 31 | No failure on API 37 (44 of 58 entries passed, the rest are assumptions and observations). On API 31 only A10d failed |
| UI and format suites | `32b9c02` | API 37 and API 31 | 26 of 27 (connect skipped) and 8 of 8 |
| Playback suite | `32b9c02` | API 31 | Only A10d failed |
| UI suite, six M2 server phases | `c0dc319` | API 31 | 27 of 28 (connect skipped), and six of six phases |
| A23 upgrade over an installed older build | `c0dc319` | API 31 | Schema 1 to 2 kept 2 editions, 3 tracks, 6 chapters, 1 place, 5 cached rows and all 6 files |
| UI suite | `661aa89`, before its last test edits | API 31 | 26 of 28, connect skipped. The failing class passed 2 of 2 after its test was corrected |
| Separate-process restarts | `09bd532` | API 37 and API 31 | A10, A10b and A29 passed |
| A31, build half | `1727261` | Host | Minified APK, v2 signature verified, 1.2.5 (1002005), API 31 to 37. Without inputs it fails and names all four. The throwaway key was deleted |
| Rendering by eye | `b64ddf3` | Robolectric, native graphics, API 34 | Addresses by day, night, high contrast and at 320 dp with 200% text. Connection errors, Search, Parts, the active sleep target and the sign-out dialog |
| CI | `4fb0b8d`, `32b9c02`, `c0dc319`, `661aa89`, `b64ddf3` | GitHub | CI and Repository checks passed |

The emulators are Google APIs arm64 images (`system-images/android-37.0` and `android-31`). The physical phone attached to the build machine was never used.

## 4. Failing and unrun cases

**Failing.** A10d on API 31, `PlaybackFailureTest#a10dATrackThatCannotOpenEndsPlaybackAtItsBoundary`. The controller's position ran to 92,967 ms against the 90,500 ms limit. The saved place was exactly 90,000 ms and no byte of track B was read. It fails in the same way on `0786e82`, before this round. The cause is unverified, and the test is unchanged. Reproduce by running that method on API 31.

**Not run on the final code.** Both emulators reached the maximum run time of their background commands and were stopped, API 37 first. They were not restarted.

| IDs | Unrun | Last evidence |
|---|---|---|
| Every device case | Instrumented suites on `c6efe46` and later | JVM regressions for each of those fixes, and the runs above |
| A18b, A23 upgrade, the new layout | API 37 | API 31 and JVM |
| A14, A24 | API 37 playback suite after the parts change (the run was cut short) | API 37 format and UI suites, API 31 playback |
| A16g | The 30-tap manual series after `4fb0b8d` | 30 of 30 at `d9269fd`, raw-touch suite since |
| A01, A26, A30 connect journey | The journey after its last test fix (scroll to Sign in) | Passed on API 31 before the design pass, failed on test scrolling after it |
| A08, A12b, A16 headphone removal, A16e, A22 | Phases that need their host scripts | [M4](M4.md) and [M5](M5.md) |
| A03 to A07, A10c, A16b, A20, A21, A21b, A24b, A26b, A26c | M3 and M4 host and repair phases | [M3](M3.md) and [M4](M4.md), and the JVM suite |
| A31 | Install, sign in, download and play this release | [M6](M6.md) on API 37 |
| V01 to V05 | Both targets after the last fixes, including focus rings and landscape by eye | API 31 suite and screenshots, Robolectric renders |

**Open since the milestones.**

| IDs | Open part and reason |
|---|---|
| A03 | A token that expires during a real multi-file download. The shared reference server issues one-hour tokens. |
| A04 | Changed bytes on a second route run on the JVM only, because one server serves both. A real move to Tailscale is the owner's. |
| A05, A05b, A05c | 403, 404 and malformed ranges run on the JVM, as the plan assigns, and not through the server. |
| A06, A06b, A06c | No SIGKILL at the exact instants around the rename. Each recovery point is tested directly. |
| A08 | The whole journey through the screens. Its parts have passed separately. |
| A01b, A01c | Failed, delayed and first-load refreshes through the screens on a device. The refresh rules have JVM tests. |
| A02 | Pairing against a second real server. One server serves both origins, and another server’s refusal has a JVM test since `c71e973`. |
| A11 | Re-download of changed audio on a device. Its continuity rule has a JVM test since `1727261`. |
| A12, A16c, A16d, A16e, A29 | Calls, wired and Bluetooth starts, and resumption after a reboot need the phone. |
| A15, A17, A19, V02, V04 | Spoken TalkBack traversal and announcements need a listener. Names, roles and states are tested. |
| A17b | The denied-permission variant for a never-loaded library. The other two run on the JVM since `1727261`. |
| A25 | Selecting among several server libraries and the mixed-media item through the screens. Search in one of several runs on the JVM. |
| A26 | An old server and one without password sign-in run on the JVM only, because the reference is v2.38.0. |
| A26b | Denying the dialog by touch, and continuing through an address that needs no permission. Every address the emulator reaches is local. |
| A27 | The grouped screen with four real jobs on a device. Groups and actions have JVM tests since `1727261`. |
| V03 | Pausing from the notification shade by touch. The system media pause passed. |
| Format matrix | AAC in CAF, MPEG-PS with MP3 or AC-3, and ALAC with a decoder have no evidence. |

Minor findings left open: switching libraries keeps the scroll offset, and after a repair the Play key can show Play again for a moment while the repaired book loads.

## 5. First unfinished case

A10d on API 31. Then an instrumented run of the final code on both API levels.

## 6. Completing commit and owner checks

The notes are implemented at `661aa89`. The review fixes complete at `b64ddf3`, and the tests for cases that had none (A02, A11, A17b, A25, A27) at `c71e973`. This report is committed on top of them.

Every [owner check](../06-delivery-and-tests.md#owner-checks) stays pending, with the steps in the [M6 handoff](M6.md#owner-handoff): the server's routes and nginx, the phone and its sound, Android Auto, the four journeys with the release check, and the release secrets with a tagged build.
