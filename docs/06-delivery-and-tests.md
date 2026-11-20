# Delivery sequence and acceptance tests

Build one end-to-end behavior at a time. A milestone is complete when its checks run on the stated target and the result is recorded. Label unavailable hardware or server checks as pending. Keep later work independent of the availability of personal credentials by using a sanitized contract fixture and a local test server.

## Milestones

| ID | Deliverable | Depends on | Exit check |
|---|---|---|---|
| M0 | Thin Android shell, compatibility inventory, and local playback spike | Existing repo conventions | Build and run an installable debug app. Play a bundled chaptered M4B and a multi-file reference book through a service. A seek to book time 115 s yields track B at 25 s. Pin the Android/server targets and list scanner-admitted formats and any decoder gaps. |
| M1 | Book identity, Room state, and two working destinations styled with the design tokens | M0 | In Library and Downloaded, demonstrate remote, transferring and ready states from fixture data. Pause A, listen to B, restart, and restore each book independently. Verify one light and one dark screen at large text. |
| M2 | Real sign-in, pairing, failover and catalog | M1 | With a non-admin account, browse every catalog page including duplicate titles. Pair LAN and Tailscale addresses. Switching between them keeps one book record. Test incorrect credentials, mismatched pairing and denied local-network permission. |
| M3 | Durable download path | M2 | A real server book downloads, publishes once and plays in airplane mode. Simulate a dropped connection, pause/resume, origin change, process death, storage failure, and a server that ignores Range. Cancel and remove have distinct outcomes. |
| M4 | Full playback integration and validated format coverage | M0, M3 | Finish the matrix for the pinned release. Implement the local compatibility path for gaps. Verify audible late seeks, fallback chapters, skip/scrub, playback speed, notification controls, headphone disconnect and process recovery. |
| M5 | Sleep behavior, accessibility, and final visual pass | M4 | Run the locked-screen tests below, review the compact and wide layouts, and confirm every primary action with TalkBack at 200% text. Verify all four product journeys on a physical phone. |

For M0, read [playback and formats](05-playback-and-formats.md). For M1 use the [interface](02-interface.md) and [data model](03-android-and-data.md). For M2–M3 use the [server and download contract](04-server-and-downloads.md). For M4–M5 use the [product journeys](01-product.md) as the final walkthrough.

## Fixtures a contributor can reproduce

- **Boundary book.** Generate two tracks of 90 s and 120 s with distinguishable audible cues. Supply three server chapters at 0, 75 and 140 s. Keep the end at 210 s. Use this fixture for cross-track seek, independent chapter boundaries and chapter sleep.
- **Single M4B.** One DRM-free AAC M4B with three embedded titled chapters and a cover. Supply a second server chapter list with edited titles to prove that server edits win. Repeat with the server list removed to exercise embedded fallback.
- **No chapters.** A single-file book and a multi-file book with no markers. Include missing artwork and narrator, a long title, two authors, and two distinct item IDs with the same title.
- **Formats.** Generate short samples for the [format matrix](05-playback-and-formats.md) and at least one long VBR MP3 with identifiable cues near the end. Keep generation commands, tool versions, checksums and redistribution rights with the fixtures. Generate large audio locally instead of adding hours of binary media to Git.
- **Fixture manifest.** Keep a machine-readable manifest beside generated fixtures with the fixture ID, generation command, tool and version, container, codec, duration, checksum, redistribution right and the server release that admitted it. Tests reference fixtures by ID rather than by a machine-specific path.
- **Network.** Serve a sanitized catalog from a controllable HTTP fixture with two origins and optional path prefix. Include paginated results, an empty library, denied download, token rotation, mismatched account/library IDs, 200/206/416 responses, truncated bodies and incompatible entity validators. Run at least one full smoke test against the pinned Audiobookshelf version.

## Acceptance matrix

| ID | Arrange and act | Pass condition |
|---|---|---|
| A01 | Clean install, sign in, select a book library, find an item on page two | Results are complete after refresh. Only accessible audio items appear. Two equal titles remain independent. |
| A02 | Pair two origins for one profile, then attempt pairing a different server/account | Valid aliases share all book data. Mismatch stays unpaired. A request recorder sees each credential only at its paired origin and prefix. |
| A03 | Deny a download, expire a token, then make the server unreachable | 403 never starts a transfer. Concurrent 401s trigger one refresh per origin. Previously downloaded books open while the server is unavailable. |
| A04 | Tap Download twice, interrupt it halfway through track B, move from LAN to Tailscale | One job publishes one edition. Verified track A is reused. B resumes only with a matching validator and byte range, otherwise restarts cleanly. |
| A05 | Inject full 200 on range request, mismatched 206, unexpected HTML, truncated file and 416 | No corrupt appends and no incomplete Ready books. Restart/repair is visible and retries do not duplicate audio. |
| A06 | Kill the process just before rename, after rename but before Room commit, and after commit | Recovery either safely republishes the complete generation or leaves it resumable. No half-ready edition and no lost progress. |
| A07 | Run out of space, free space, retry, then remove and re-download the same book | Retry completes the book. Removal frees media bytes while retaining progress. The re-downloaded edition resumes. |
| A08 | Launch Downloaded with airplane mode on and an expired login. Play, seek chapters, lock, pause and relaunch | Cover, metadata, chapters, sleep and saved position remain usable. An instrumented transport observes zero outgoing requests initiated by playback. |
| A09 | Seek to 75, 90, 115, 140 and 210 s in the boundary book, then rewind | Audible cues and player state select the correct interval. At 115 s the decoder is 25 s into B. At 210 s the book is finished. A deliberate rewind persists. |
| A10 | Pause A late, play B, deliver A’s delayed callback, then kill/reopen | Both correct book positions survive. Explicit resume is within 1 s of the audible target for reference fixtures. Abrupt death can lose at most one 5 s checkpoint interval plus measured seek tolerance. |
| A11 | Apply a catalog metadata edit, then re-download an edition with reordered tracks | The metadata-only change preserves position. For changed audio, the listener sees the old position and chooses whether to reuse it. |
| A12 | Choose a 15 min timer at 2×, lock the phone, and interrupt playback with a call | Pause occurs at 15 wall-clock minutes within 2 s in the device test. If already paused at expiry, the call ending cannot restart playback. Use a fake clock for the fast automated variant. |
| A13 | At 115 s choose End of chapter, lock the phone, then let the boundary arrive | Save exactly 140 s. No audible chapter-3 cue plays. Resume begins at chapter 3. Retest with a manual chapter change while armed. |
| A14 | Run all format fixtures near the start and near 90% duration, on minimum and current Android targets | Decode, seek, resume, chapter fallback and reported duration pass. Target audible seek error ≤1 s on valid fixtures. Record any exception by format/device and leave coverage open until fixed. |
| A15 | At 320 dp and 200% font, use TalkBack to download, play, choose a chapter, set sleep and remove a download | Controls retain unique names, logical focus order, 48 dp targets, visible state and reachable actions. Artwork can shrink while transport remains usable. |
| A16 | Pull headphones, use notification Play/Pause, reopen from the mini-player, then replay a finished book | One session owns playback. Headphone removal pauses once. All surfaces agree on the state. Replay starts at zero. |
| A17 | Cold launch with an unreachable server and at least one downloaded book; then warm-start from a list and from the player | Cold launch opens Downloaded and renders local content before network checks finish. The cached library shows its last refresh time and an offline status. A warm start preserves the screen, list scroll, query and open player. |
| A18 | With one finished book, one partially listened book and one never-played download, relaunch and replay the finished book | Downloaded sorts by last-listened time, then by download completion for never-played books, then by title and item ID. Replay starts at zero and the partially listened book resumes at its place. |
| A19 | Add, rename, reorder, test and remove a second address for one profile; then remove the active address | Each action updates the ordered list and the active route. Removing an address preserves the profile, downloads and saved positions. A cleartext route needs an explicit per-origin opt-in with the tradeoff explained. |
| A20 | Open a book with an account that can browse but cannot download; corrupt a committed file; run Repair and fail it | The first shows the download-disabled state and starts no transfer. The second shows the damaged state. A failed repair preserves the last usable edition and saved position; a successful repair resumes at the saved place. |
| A21 | Fill storage during a download, observe the error, free space and retry | The error shows the required additional space when it is known. Retry completes without duplicating bytes. Cancel releases the staging bytes. |
| A22 | With airplane mode and an expired login, play, seek, change speed and set each timed sleep choice; then kill and relaunch | Playback, chapters, sleep, cover and progress remain usable with zero outgoing requests. Speed persists. Each timed choice expires on wall-clock time and clears; Off leaves playback running. |
| A23 | Inspect captured network diagnostics, a backup snapshot and a Room upgrade from a previous schema | Diagnostics redact credentials, URL queries and filenames. Backups exclude credentials, media and device-bound transfer state. The upgrade preserves downloads, editions and saved positions. |
| A24 | Play a chapterless single-file book and a chapterless multi-file book | The single-file book shows one whole-book timeline. The multi-file book lists Tracks with titles or ordinals and offers End of track and End of book. Both seek and sleep work. |
| A25 | With several libraries, search by title and by author, and open a mixed-media item | Only accessible audio items appear; the mixed-media item appears only when it has playable tracks. Search matches title and author in the selected library. Zero and several libraries both resolve. |

## Coverage map

Every release-scope requirement needs at least one acceptance case. A requirement without a case stays open until a case is added or the requirement is cut.

| Requirement area | Acceptance |
|---|---|
| One account, one server, several addresses | A02, A04, A19 |
| Library browse, search and details | A01, A25 |
| Downloaded works offline and removes cleanly | A07, A08, A15, A20 |
| Partial transfer progress, pause, resume and cancel | A04, A05, A07, A21 |
| Per-book progress across book switches, app death, address changes and re-download | A07, A10, A11 |
| Format and chapter coverage | A09, A14, A24 |
| Sleep | A12, A13, A22 |
| Launch and state priorities | A17 |
| Sort and replay | A18 |
| Diagnostics, keys and backups | A23 |

## Open questions to close in M0

- Whether the pinned server exposes a stable server instance ID for pairing, or only account and library IDs.
- Whether the server supplies trusted per-file digests or only lengths, media types and validators.
- The storage headroom margin for download preflight and the default download concurrency.
- Which Audiobookshelf release is the support floor and which release is the test target.

## Verification and handoff

Use JVM tests for timeline math, identity, route selection, timer decisions and transfer response handling. Use an instrumented app for Room/file recovery, service lifecycle, Compose semantics and media controls. Use a physical phone for Bluetooth, locked-screen sleep, real network switching and audible seek accuracy. Before publishing an Android compatibility claim, record the OS/API, device, codec and result. Emulator-only checks should be identified as such.

### Milestone report

1. Milestone and user-visible behavior delivered.
2. Files and key design decision changed.
3. Actual build/test commands, target versions and checks that passed.
4. Failing or unrun acceptance IDs, with reason and reproducible input.
5. First unfinished milestone or acceptance case.

**Release check.** Start on home Wi-Fi. Download a multi-track book. Switch to Tailscale during the transfer. Once ready, turn on airplane mode, seek across a file boundary, set End of chapter, lock the phone, and restart Pageworn after the stop. The next words should be the start of the next chapter. Repeat on a second book and verify the first still remembers its place.
