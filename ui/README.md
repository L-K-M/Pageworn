# Pageworn Android implementation handoff

Pageworn is an Android audiobook player for one listener with an Audiobookshelf server. Choose a book in **Library**, download it, and listen from **Downloaded**. A book keeps its place even when a download is removed. Its cloth cover, paper surfaces and place ribbon come from a well-used hardcover.

Place this directory at `docs/pageworn/`. The documents use relative links and can move together. This is a build specification. Test cases describe expected behavior and have yet to be run against an implementation.

## Visual handoff

Start with the [four final screen mockups](07-screen-gallery.md), [interface specification](02-interface.md) and [individual asset guide](08-assets-and-implementation.md). The `art/` directory contains individual PNG components, tintable pencil masks, example covers and a manifest. The screens open directly on their destination or book titles. Pageworn remains the product name in the launcher, notification identity and repository.

The documentation and media live together under `docs/pageworn/`. Copy the art used by the Android app into its resources as described in the asset guide. Use the PNG mockups for visual review.

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

## Starting an implementation session

Read the product brief, then the first unfinished milestone in the delivery plan. Follow its links for implementation detail. Preserve the host repo’s existing conventions and build configuration where compatible. Product behavior takes precedence over example class names. Where Android or server behavior depends on a version, record the version and the observed result alongside the relevant test.

### First prompt for a coding agent

> Read `docs/pageworn/README.md`, `01-product.md`, and milestone M0 in `06-delivery-and-tests.md` under the same directory. Inspect this repo. Implement M0 and run its checks. Use the fixture timelines in the playback spec. Report the files changed, the checks run, and any real-device or server checks still outstanding. Leave later milestones for subsequent changes.

### Prompt for a visual implementation session

> Read `docs/pageworn/02-interface.md`, `07-screen-gallery.md`, and `08-assets-and-implementation.md` in that directory. Open the referenced PNGs. Build the Downloaded screen and the player using the supplied art and native Compose controls. Demonstrate both tab selections, playing and paused states, an active download, and end-of-chapter sleep. Check a 390 dp phone and a 320 dp phone with 200% text. Report which visual and accessibility checks you ran.

## Decisions carried into the plan

| Decision | Working interpretation |
|---|---|
| One client | One Android installation, one account, one logical server with multiple addresses. Local per-book progress is authoritative. |
| All book formats | DRM-free **audiobook** files the target Audiobookshelf release recognizes. Inventory the formats and exercise local decoding and seeking. Track actual coverage in M0 and M4. |
| Sleep mode | Pause after a wall-clock duration or at the end of the current chapter. Support the locked screen and headphone controls. |
| Design baseline | Kotlin, Jetpack Compose, Room and a Media3 session. Proposed minimum Android version is 8.0 (API 26). Default sign-in uses an Audiobookshelf username and password. |
| Network | Browse and transfer through a configured address. Playback, chapters, the timer and progress read from the phone. |

The Android floor and sign-in method are editable engineering defaults. Check them against the intended phone and server during M0. Keep implementation evidence with the milestone so the next contributor can reproduce it.
