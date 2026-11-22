# Pageworn Android implementation handoff

Pageworn is an Android audiobook player for one listener with an Audiobookshelf server. Choose a book in **Library**, download it, and listen from **Downloaded**. A book keeps its place even when a download is removed. Its cloth cover, paper surfaces and place ribbon come from a well-used hardcover.

The plan lives in `docs/`. This is a build specification. Test cases describe expected behavior and have yet to be run against an implementation.

## Visual handoff

Start with the [four final screen mockups](07-screen-gallery.md), [interface specification](02-interface.md) and [individual asset guide](08-assets-and-implementation.md). The `art/` directory contains individual PNG components, tintable pencil masks, example covers, the launcher icon’s layers and a manifest. The screens open directly on their destination or book titles. Pageworn remains the product name in the launcher, notification identity and repository.

The documentation and media live together under `docs/`. Copy the art used by the Android app into its resources as described in the asset guide. Use the PNG mockups for visual review.

## Reading map

| File | What it owns |
|---|---|
| [01-product.md](01-product.md) | Scope, everyday journeys, defaults, and definitions of success |
| [02-interface.md](02-interface.md) | Screen layouts, component behavior, materials, type, color, and accessibility |
| [03-android-and-data.md](03-android-and-data.md) | Android stack, service boundaries, storage model, and recovery |
| [04-server-and-downloads.md](04-server-and-downloads.md) | Sign-in, LAN and Tailscale routing, catalog mapping, and resumable downloads |
| [05-playback-and-formats.md](05-playback-and-formats.md) | Book time, chapter navigation, format coverage, progress, and sleep |
| [06-delivery-and-tests.md](06-delivery-and-tests.md) | Ordered milestones, fixtures, acceptance tests, and release criteria |
| [07-screen-gallery.md](07-screen-gallery.md) | Four individual visual targets with state and layout notes |
| [08-assets-and-implementation.md](08-assets-and-implementation.md) | Reusable assets, sizing, native overlays, state handling, and delivery checks |
| [format-matrix.md](format-matrix.md) | Recorded format coverage on the test targets, with the device evidence |
| [milestones/](milestones/) | One report per completed milestone, in the format of the delivery plan |

## Working vocabulary

The documents use these terms precisely. Server fields keep their Audiobookshelf names.

| Term | Meaning |
|---|---|
| Book or item | One Audiobookshelf library item. Its identity is the server item ID within the profile. |
| Track | One playable audio file in the server’s play order. A track may contain several chapters. |
| File | One audio file: the server’s original, identified by its file ID, or its local copy. A file stores one track. |
| Chapter | A server or embedded navigation marker with an absolute book interval. A generated Opening or Interlude segment counts as a chapter for navigation, the scrubber and end-of-chapter sleep. |
| Part | Ten minutes of a single-file book that has no chapters, counted from its start. The last part is the remainder. A part is generated and counts as a chapter for navigation, the scrubber and sleep. |
| Edition | One verified local generation of a book: files, timeline and chapter map. |
| Generation | A staged revision of an edition, identified by a local ID. Only one generation is published at a time. |
| Refresh generation | One attempt to refresh a library’s catalog, staged under its own ID and published only when complete. It is unrelated to an edition’s generation. |
| Playback generation | One playback session of a book in the service. Progress writes carry it so a late callback from an earlier session is discarded. |
| Address, alias or route | One normalized base URL (origin and path prefix) for the profile’s server, listed under a label such as Home or Tailscale. |
| Profile | One local account on one server, rooted at a local UUID. |

## Starting an implementation session

Use the [build and release guide](../CICD.md) for the toolchain, local checks, and signed APK releases.

Read the product brief, then the first unfinished milestone in the delivery plan. Follow its links for implementation detail. Preserve the host repo’s existing conventions and build configuration where compatible. Where the repository has none yet, M0 sets them up as its [build and test baseline](06-delivery-and-tests.md#m0-build-and-test-baseline) describes. Product behavior takes precedence over example class names. Where Android or server behavior depends on a version, record the version and the observed result alongside the relevant test.

The owner asked for implementation work to be committed directly on `main`, without a working branch or a pull request. Commit in small steps that build, and name in each milestone report the commit that completes it.

### First prompt for a coding agent

> Read `docs/README.md`, `docs/01-product.md`, and milestone M0 in `docs/06-delivery-and-tests.md`, including its build and test baseline. Inspect this repo. Implement M0 and run its checks. Use the fixture timelines in `docs/05-playback-and-formats.md`. Report the files changed, the checks run, and any real-device or server checks still outstanding. Leave later milestones for subsequent changes.

### Prompt for a visual implementation session

> Read `docs/02-interface.md`, `docs/07-screen-gallery.md` and `docs/08-assets-and-implementation.md`. Open the referenced PNGs. Build the Downloaded screen and the player using the supplied art and native Compose controls. Demonstrate both tab selections, playing and paused states, an active download, and end-of-chapter sleep. Check a 390 dp phone and a 320 dp phone with 200% text. Report which visual and accessibility checks you ran.

## Decisions carried into the plan

| Decision | Working interpretation |
|---|---|
| One client | One Android installation, one account, one logical server with multiple addresses. Local per-book progress is authoritative. Signing out keeps the downloaded books and saved places for the same account. |
| Book formats | DRM-free **audiobook** files that the target Audiobookshelf release recognizes and Media3 can extract, decode and seek on the phone. A recognized format that Media3 cannot play is refused as unsupported in v1, and the app bundles no decoder of its own. Inventory the formats and exercise local decoding and seeking. Track actual coverage in M0 and M4. |
| Two scales | The player shows two progress bars: one for the chapter and one for the whole book. A book without chapters is broken into ten-minute parts. A multi-file book’s files already act as its chapters, so it keeps its Tracks. |
| Sleep mode | Pause after a wall-clock duration or at the end of the current chapter. Support the locked screen and headphone controls. After a timed stop, a book that has rested five minutes or more starts five minutes earlier. |
| Design baseline | Kotlin, Jetpack Compose, Room and a Media3 library session. The minimum Android version is 12 (API 31) and the target SDK is 37. The application ID is `ch.lkmc.pageworn`. Sign-in uses an Audiobookshelf username and password. The interface ships in English. |
| Network | Browse and transfer through a configured address, over HTTPS or explicitly allowed HTTP, on any network including mobile data. Playback, chapters, the timer and progress read from the phone. |
| In the car | Android Auto lists the downloaded books and resumes one on a tap. Browsing the library and downloading stay on the phone. |
| Distribution | A signed APK on the repository’s GitHub Releases, built and signed by GitHub Actions. The app has no store listing, crash reporting or analytics. |

The owner settled these on 21 November 2026, and the two scales on 22 November 2026, together with the facts about their own setup under [the listener’s deployment](06-delivery-and-tests.md#the-listeners-deployment). Keep implementation evidence with the milestone so the next contributor can reproduce it.
