# Delivery sequence and acceptance tests

Build one end-to-end behavior at a time. A milestone is complete when its checks run on the stated target and the result is recorded. Label unavailable hardware or server checks as pending. Keep later work independent of the availability of personal credentials by using a sanitized contract fixture and a local test server.

## Milestones

| ID | Deliverable | Depends on | Exit check |
|---|---|---|---|
| M0 | Thin Android shell, compatibility inventory, and local playback spike | See [M0 build and test baseline](#m0-build-and-test-baseline) | Build and run an installable debug app. Play a bundled chaptered M4B and a multi-file reference book through a service. A seek to book time 115 s yields track B at 25 s. Pin the Android/server targets and list scanner-admitted formats and any decoder gaps. |
| M1 | Book identity, Room state, and two working destinations styled with the design tokens | M0 | In Library and Downloaded, demonstrate available, transferring and ready states from fixture data. Pause A, listen to B, restart, and restore each book independently. Verify one light and one dark screen at large text. |
| M2 | Real sign-in, pairing, failover and catalog | M1 | With a non-admin account, browse every catalog page including duplicate titles. Pair LAN and Tailscale addresses. Switching between them keeps one book record. Test incorrect credentials, mismatched pairing and denied local-network permission. |
| M3 | Durable download path | M2 | A real server book downloads, publishes once and plays in airplane mode. Simulate a dropped connection, pause/resume, origin change, removal of the serving address, process death, storage failure, and a server that ignores Range. Cancel and remove have distinct outcomes. |
| M4 | Full playback integration and validated format coverage | M0, M3 | Finish the matrix for the pinned release. Implement the local compatibility path for gaps. Verify audible late seeks, fallback chapters, skip/scrub, playback speed, notification controls, headphone disconnect, a track that fails during playback, and process recovery. |
| M5 | Sleep behavior, accessibility, and final visual pass | M4 | Run the locked-screen tests below, review the compact and wide layouts, and confirm every primary action with TalkBack at 200% text. Verify all four product journeys on a physical phone. |

For M0, read [playback and formats](05-playback-and-formats.md). For M1 use the [interface](02-interface.md) and [data model](03-android-and-data.md). For M2–M3 use the [server and download contract](04-server-and-downloads.md). For M4–M5 use the [product journeys](01-product.md) as the final walkthrough.

### M0 build and test baseline

- If the repository has no Android project, initialize the single-module project at the repository root. Check in the Gradle wrapper and pinned build and dependency versions. Record the JDK and Android SDK requirements and the build, install and test commands so a fresh checkout can reproduce the spike.
- Build, install and exercise the playback service using local fixtures. Record the Android device or emulator and API level used. The reference seek uses the Boundary book defined below.
- Identify the intended Audiobookshelf release. If it is unknown, pin a reference release and label it provisional. Inventory that release’s scanner and use a local instance with a disposable account to check sign-in and capture the sanitized track-to-download mapping. Record the release and distinguish observations from the reference instance from checks against the listener’s deployment. Keep deployment-specific checks pending when the deployment is unavailable.

## Visual integration checks

Run these alongside M1 and M5. The [screen gallery](07-screen-gallery.md) gives the reference state and the [asset guide](08-assets-and-implementation.md) defines layer order and sizing.

| ID | Exercise | Expected |
|---|---|---|
| V01 | Open Library, Downloaded, player and sleep sheet | Section or book heading appears once. There is no redundant app-name line or vacant row. |
| V02 | Switch destinations with touch, keyboard and TalkBack | Library retains its flax fabric and Downloaded its red fabric. The active tail extends, focus stays stable, and the tab reports its selection. |
| V03 | Compare mini and full player, then pause via the notification | Every visible key shows the action available next. Download pause remains a separate control. |
| V04 | Change speed, select a chapter, open sleep and switch to night theme | Each pencil icon reads clearly. Actual speed and timer choice are announced. Only one radio is selected. |
| V05 | Enable 200% text, high contrast and reduced motion, then rotate | Labels remain live and legible, tab and key targets remain reachable, and every state change works without animation. |

## Fixtures a contributor can reproduce

- **Boundary book.** Generate two tracks of 90 s and 120 s with distinguishable audible cues. Supply three server chapters at 0, 75 and 140 s. Keep the end at 210 s. Use this fixture for cross-track seek, independent chapter boundaries and chapter sleep.
- **Single M4B.** One DRM-free AAC M4B with three embedded titled chapters and a cover. Supply a second server chapter list with edited titles to prove that server edits win. Repeat with the server list removed to exercise embedded fallback.
- **No chapters.** A single-file book and a multi-file book with no markers. Build the multi-file book from the boundary book’s two tracks without a chapter list, so its first track ends at 90 s. Include missing artwork and narrator, a long title, two authors, and two distinct item IDs with the same title.
- **Formats.** Generate short samples for the [format matrix](05-playback-and-formats.md) and at least one long VBR MP3 with identifiable cues near the end. Keep generation commands, tool versions, checksums and redistribution rights with the fixtures. Generate large audio locally instead of adding hours of binary media to Git.
- **Fixture manifest.** Keep a machine-readable manifest beside generated fixtures with the fixture ID, generation command, tool and version, container, codec, duration, checksum, redistribution right and, for format samples, the server release that admitted it. Tests reference fixtures by ID rather than by a machine-specific path.
- **Network.** Serve a sanitized catalog from a controllable HTTP fixture with two origins and optional path prefix. Include two libraries with different page counts, per-page failure and delay controls, paginated results, an empty library, an account with no book libraries, a mixed-media item, denied download, token rotation, mismatched account/library IDs, a certificate failure, 200/206/416 responses, a transient 5xx, a content-coded 206, truncated bodies, incompatible entity validators, and a second-route response with the same size and ETag but different bytes. Run at least one full smoke test against the pinned Audiobookshelf version.

## Acceptance matrix

| ID | Arrange and act | Pass condition |
|---|---|---|
| A01 | Clean install, sign in, select a book library, find an item on page two | Results are complete after refresh. Only accessible audio items appear. Two equal titles remain independent. |
| A01b | Cache two pages of library A and one page of B, and download one book from A with a saved place. Change A on the server, fail its second page during refresh, then reopen offline. Next, delay that page during a retry and let a newer refresh of A publish an empty result first. | After failure, browsing and title/author search show the original A with its old refresh time. After the empty refresh, A stays empty even when the delayed page arrives. B, a downloaded A book and its saved place survive both attempts. |
| A02 | Pair two origins for one profile, then attempt pairing a different server/account | Valid aliases share all book data. Mismatch stays unpaired. A request recorder sees each credential only at its paired origin and prefix. |
| A03 | Deny a download, expire a token, then make the server unreachable | 403 never starts a transfer. Concurrent 401s trigger one refresh per origin. Previously downloaded books open while the server is unavailable. |
| A04 | Tap Download twice, interrupt it halfway through track B, move from LAN to Tailscale. Serve changed B bytes with the same size and ETag on the second route, with no trusted digest. | One job publishes one edition. Verified track A is reused if the refreshed manifest still identifies the same A. B starts at byte zero on the second route and matches its complete response. |
| A04b | Remove the address serving a transfer during track B, then pair and enable a second address; separately disable every address | The job waits with its staged files intact and resumes through the new route after reconciliation. Verified complete files that the refreshed manifest still identifies are reused, partial track B restarts from byte zero unless byte identity is proven, and one edition is published. With no route enabled it stays waiting instead of failing; re-enabling an address completes the book without duplicating audio. |
| A05 | On one route, exercise a valid short 206 and 206s with a mismatched validator, invalid range bounds or body length, and non-identity content coding. Also inject a full 200, unexpected HTML, truncated file and 416. | A valid short range advances the offset and leaves the track partial. Invalid ranges preserve the preceding usable prefix. A full 200 starts from zero. Publication requires a complete, verified file. Restart or repair remains visible and retries avoid duplicate audio. |
| A05b | Serve a transient 5xx followed by success, then serve 403 on a later request | The transient failure retries with backoff and completes; the 403 ends the job immediately with the permission copy and produces no retry storm. |
| A06 | Kill the process just before rename, after rename but before Room commit, and after commit | Recovery either safely republishes the complete generation or leaves it resumable. No half-ready edition and no lost progress. |
| A06b | Queue Cancel while transferring. Deliver it after rename but before publish, kill the process, then relaunch and download again. Repeat with Cancel arriving after publish. | Recovery cleans up the cancelled generation without publishing it. The later job publishes once. A Cancel that loses to publish leaves the Ready book and its saved place intact. |
| A06c | With a usable edition, fail verification of a replacement, then stage another replacement. Remove the book while publication is pending and kill the process after removal is recorded. | The failed replacement leaves the earlier edition usable. After relaunch, removal finishes, neither generation is available for playback, and the saved place survives. |
| A07 | Run out of space, free space, retry, then remove and re-download the same book | Retry completes the book. Removal frees media bytes while retaining progress. The re-downloaded edition resumes. |
| A08 | Launch Downloaded with airplane mode on and an expired login. Play, seek chapters, lock, pause and relaunch | Cover, metadata, chapters, sleep and saved position remain usable. An instrumented transport observes zero outgoing requests initiated by playback. |
| A09 | Seek to 75, 90, 115, 140 and 210 s in the boundary book, then rewind | Audible cues and player state select the correct interval. At 115 s the decoder is 25 s into B. At 210 s the book is finished. A deliberate rewind persists. |
| A10 | Pause A late, play B, deliver A’s delayed callback, then kill/reopen | Both correct book positions survive. Explicit resume is within 1 s of the audible target for reference fixtures. Abrupt death can lose at most 5 s of book time plus measured seek tolerance. |
| A10b | At 2× in the boundary book, hold a periodic position sample from 115 s. Seek back to 75 s, pause, deliver the held sample and relaunch. Then resume at 2× and kill the process just before the next scheduled checkpoint. | The first relaunch restores 75 s within the fixture’s seek tolerance. On the second relaunch the lost book time is at most 5 s plus measured seek tolerance. |
| A10c | Corrupt one track of a playing multi-file edition, then let playback reach it; relaunch and repair | Playback pauses at the last checkpoint at or before the failure, the saved place survives, and the book is not finished. Repair restores playback from that position. |
| A11 | Apply a catalog metadata edit, then re-download an edition with reordered tracks | The metadata-only change preserves position. For changed audio, the listener sees the old position and chooses whether to reuse it. |
| A12 | Choose a 15 min timer at 2×, lock the phone, and interrupt playback with a call | Pause occurs at 15 wall-clock minutes within 2 s in the device test. If already paused at expiry, the call ending cannot restart playback. Use a fake clock for the fast automated variant. |
| A13 | At 115 s choose End of chapter, lock the phone, then let the boundary arrive | Save exactly 140 s. No audible chapter-3 cue plays. Resume begins at chapter 3. Retest with a manual chapter change while armed. |
| A13b | In the boundary book, seek to 200 s, choose End of chapter, lock the phone, and wait for book end | Playback pauses exactly at 210 s, marks the book finished, clears the timer, and offers Replay. No next chapter is armed or plays. |
| A13c | In the no-chapter multi-file fixture, arm End of track at 89 s and lock the phone | Playback stops exactly at 90 s with no audible track B audio, stays unfinished, and the next play starts at track B. |
| A14 | Run all format fixtures near the start and near 90% duration, on minimum and current Android targets | Decode, seek, resume, chapter fallback and reported duration pass. Target audible seek error ≤1 s on valid fixtures. Record any exception by format/device and leave coverage open until fixed. |
| A15 | At 320 dp and 200% font, use TalkBack to download, play, choose a chapter, set sleep and remove a download | Controls retain unique names, logical focus order, 48 dp targets, visible state and reachable actions. Artwork can shrink while transport remains usable. |
| A16 | Pull headphones, use notification Play/Pause, reopen from the mini-player, then replay a finished book | One session owns playback. Headphone removal pauses once. All surfaces agree on the state. Replay starts at zero. |
| A16b | Fresh install on Android 13+ with notifications denied: enqueue a download, background the app, finish it, then play a completed book | The transfer finishes and its state and actions stay reachable in Downloaded with no completion notification. Local playback and its media controls work. After the permission is granted, later transfers post completion notifications. |
| A16c | With the app in the background and its playback service stopped, press Play on a headset on a build targeting Android 12+ | No crash or silent stall. Either the press starts audible playback, or the platform rejects the service start and the in-app state is accurate when the listener returns. |
| A17 | Cold launch with an unreachable server and at least one downloaded book; then warm-start from a list and from the player. Repeat the cold launch with no downloaded books and no cached catalog. | Cold launch opens Downloaded and renders local content before network checks finish. The cached library shows the “Can’t reach your server.” state with its last successful refresh. A warm start preserves the screen, list scroll, query and open player. With nothing local, the launch opens connection setup in the unreachable-server state with Retry. |
| A18 | With one finished book, one partially listened book and one never-played download, relaunch and replay the finished book | Downloaded sorts by last-listened time descending, then by download completion time descending for never-played books, then by title and stable item ID. Replay starts at zero and the partially listened book resumes at its place. |
| A19 | Add, rename, reorder, test and remove a second address for one profile; then remove the active address | Each action updates the ordered list and the active route. Removing an address, even the last one, preserves the profile, downloads and saved positions. A cleartext route needs an explicit per-origin opt-in with the tradeoff explained. |
| A20 | Open a book with an account that can browse but cannot download; corrupt a committed file; run Repair and fail it, then repair it successfully | The first shows the “Downloads are disabled.” message and starts no transfer. The second shows Repair needed with the damaged-copy message. A failed repair preserves the last usable edition and saved position. A successful repair of the same edition resumes at the saved place. |
| A21 | Fill storage during a download, observe the error, free space and retry | The error shows the required additional space when it is known. Retry completes without duplicating bytes. Cancel releases the staging bytes. |
| A22 | With airplane mode and an expired login, play, seek, change speed and set each timed sleep choice and Off; then kill and relaunch | Playback, chapters, sleep, cover and progress remain usable with zero outgoing requests. Speed persists. Each timed choice expires on wall-clock time and clears; Off leaves playback running. |
| A23 | Inspect captured network diagnostics, a backup snapshot and a Room upgrade from a previous schema | Diagnostics redact credentials, URL queries and filenames. Backups exclude credentials, audio, editions and device-bound transfer state. The upgrade preserves downloads, editions and saved positions. |
| A24 | Play a chapterless single-file book and a chapterless multi-file book | The single-file book shows one whole-book timeline and offers End of book. The multi-file book lists Tracks with titles or ordinals and offers End of track. Both seek and sleep work. |
| A25 | With several libraries, search by title and by author, open a mixed-media item, then relaunch. Repeat with an account that has no book libraries. | Only accessible audio items appear; the mixed-media item appears only when it has playable tracks. Search matches title and author in the selected library. The chosen library is restored after relaunch. An account with no book libraries shows the empty state. |
| A26 | During connection setup, enter an invalid URL, an unreachable host, a certificate failure and wrong credentials | Each condition surfaces its own message, no partial profile is created, and a later valid sign-in still succeeds. |
| A27 | With one active transfer, one waiting, one in recoverable failure and one completed book, open Downloaded and pause the active transfer | Transfers appear in a separate in-progress group with status and the actions that state allows; completed books stay below. A paused or waiting transfer never shows as active. |
| A28 | Give the boundary book a server chapter list of 0–75 s, 75–150 s and 140–300 s, plus a duplicate 140–300 s entry and an entry at 250–300 s. Then remove the server chapter list from the single M4B. | The stored map is 0–75, 75–140 and 140–210 s, and end-of-chapter sleep in the last chapter stops at the book end. Without a usable server list, the M4B’s embedded markers are imported and titled instead. |

## Coverage map

Every release-scope requirement needs at least one acceptance case or visual check. A requirement without a case stays open until a case is added or the requirement is cut.

| Requirement area | Acceptance |
|---|---|
| One account, one server, several addresses | A02, A04, A04b, A19 |
| Library browse, search and details | A01, A01b, A25 |
| Session renewal and download permission | A03, A05b, A20 |
| Downloaded works offline and removes cleanly | A03, A07, A08, A15, A20 |
| Partial transfer progress, pause, resume and cancel | A04, A04b, A05, A05b, A07, A21, A27 |
| Publication, cancellation and removal across recovery | A06, A06b, A06c |
| Per-book progress across book switches, app death, address changes and re-download | A07, A10, A10b, A11, A19 |
| Playback failure and repair | A10c, A20 |
| Format and chapter coverage | A09, A14, A24, A28 |
| Sleep | A12, A13, A13b, A13c, A22 |
| Launch and state priorities | A17 |
| Sort and replay | A16, A18 |
| System controls, notifications and background limits | A16, A16b, A16c |
| Diagnostics, keys and backups | A23 |
| Connection and setup errors | A26 |
| Transfer grouping and row states | A27 |
| Visual design and accessibility | A15, V01–V05 |

## Open questions to close in M0

- Whether the pinned server exposes a stable server instance ID for pairing, or only account and library IDs.
- Whether the server supplies trusted per-file digests or only lengths, media types and validators, and whether its entity tags are strong. Without a strong tag the [range rules](04-server-and-downloads.md#safe-continuation-across-routes) restart an interrupted file from byte zero.
- The storage headroom margin for download preflight, and whether any target needs more than the default of one book transferring at a time.
- Which Audiobookshelf release is the support floor and which release is the test target.

## Verification and handoff

Use JVM tests for timeline math, identity, route selection, timer decisions and transfer response handling. Use an instrumented app for Room/file recovery, service lifecycle, Compose semantics and media controls. Use a physical phone for Bluetooth, locked-screen sleep, real network switching and audible seek accuracy. Before publishing an Android compatibility claim, record the OS/API, device, codec and result. Emulator-only checks should be identified as such.

### Milestone report

1. Milestone and user-visible behavior delivered.
2. Files and key design decisions changed.
3. Actual build/test commands, target versions and checks that passed.
4. Failing or unrun acceptance IDs, with reason and reproducible input.
5. First unfinished milestone or acceptance case.

**Release check.** Start on home Wi-Fi. Download a multi-track book. Switch to Tailscale during the transfer. Once ready, turn on airplane mode, seek across a file boundary, set End of chapter, lock the phone, and restart Pageworn after the stop. The next words should be the start of the next chapter. Repeat on a second book and verify the first still remembers its place.
