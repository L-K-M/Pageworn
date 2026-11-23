# Pageworn improvement backlog

These tasks build on the offline-first player, its paper surfaces, Spectral titles and oxblood place ribbon. The November 2026 review used the implementation at `9e4be6d`, later main revisions, the gallery images and a synthetic library on an API 37 emulator. Each task includes a way to decide whether the change helps.

## Implemented in open pull requests

- **Keep chapter work off the playback tick.** PR #34. `PlayerStates.player` now carries the timeline's own segment list and current index instead of formatting every chapter row four times a second; the lazy sheet formats only visible rows. The regression test asserts two live states share one list while the index advances.
- **Name the state of every library transfer.** PR #35. Library rows pair the progress icon with the state word at every text size, not only from 150 percent, so paused and waiting downloads no longer read as bare rings.
- **Protect writes during cover cache eviction.** PR #25 limits eviction to published thumbnails and reclaims abandoned temporary files on the next process's first write.
- **Return from missing book details.** PR #27 distinguishes loading from a missing book. An API 37 removal test checks the return to Downloaded and the saved place.
- **Retry a download in place.** PR #28 adds Retry to Library and details while retaining Pause and Cancel where available.
- **Mark a saved place on the details cover.** PR #29 shows the ribbon for Resume and leaves fresh and finished books unmarked.
- **Bound cover decoding.** PR #30 samples against the longer edge across Compose, cached remote art and widgets. A 256 × 8192 portrait decodes to 8 × 256 for a 128 px target.
- **Keep an end-of-chapter sleep stop exact.** PR #44. ExoPlayer reports the pause inline but `isPlaying` false later, so the pause checkpoint could store an ordinary stop over the exact boundary and turn a rested resume into a ten-second rewind. The controller arms the exact pause kind before pausing; the regression test defers that callback and fails on the old code.
- **Read covers and file checks off the main thread.** PR #49. The System UI resumption control no longer reads up to 2 MB of cover bytes on the main thread, and opening a book verifies track sizes on the IO dispatcher rather than during media-item resolution.

## Listening and browsing performance

### Publish only when live state changes

**Evidence.** `PlaybackController.tickLoop` calls `publish()` every 250 ms, and the player and mini-player combine the hub state directly. At rest every visible field is unchanged, yet a new `LivePlayback` and a new `PlayerUiState` are built four times a second. PR #34 removed the per-tick chapter formatting, but the publish and the surrounding state construction remain.

**First change.** Compare the candidate state with the last published one and skip equal ticks. Keep immediate publishes for `starting`, `held`, failure, sleep arming and countdown changes, which are event-driven. Be careful that a settled pause still publishes once after its place moves.

**Acceptance.** A paused book with a sheet closed produces no player-state emissions while idle; playing position, the sleep countdown and every transport transition still appear immediately.

### Decode paper and cloth tiles once

**Evidence.** `paperBackground()` and `BindingFooter` decode their texture with `BitmapFactory.decodeResource` behind `remember(context)`, so each usage has its own copy and a screen recreation decodes again. The tiles are small, but the textures are constant for the process lifetime.

**First change.** Hold decoded `ImageBitmap`s in a process-wide cache keyed by resource id, or provide them through a composition local from the activity. Keep high-contrast handling unchanged.

**Acceptance.** Repeated navigation and rotation do not re-decode; high contrast still drops both textures.

### Bound thumbnail cache bookkeeping

**Evidence.** The thumbnail cache walks the directory for older revisions and eviction after publication. It retains mutexes for keys after requests finish, and expiry records for failed keys can survive until a successful retry.

**First change.** Measure retained keys, directory walks and elapsed time for 5,000 distinct thumbnails at two widths, with a warm cache and an unreachable server. If bookkeeping grows with historical requests, bound the lock table and prune expired failure entries. Measure filesystem cost separately before introducing an index.

**Acceptance.** Concurrent calls for the same key still share one fetch, including when a waiter cancels. Preserve the staged-file and abandoned-write regressions from PR #25. Verify the ten-minute retry boundary, replacement of older revisions, and eviction under the byte limit.

### Measure long chapter lists in details

BookDetailScreen lays out the entire chapter list in a scrolling column. Compare opening a three-chapter book and one with 500 chapters, separating time to first interactive frame from ongoing player or widget updates. Try keyed lazy rows if composition or measurement dominates. Check that the last chapter and removal action remain reachable at 200% text, and that a repaired edition shows its new titles. Initial debug-emulator frame samples varied too much to infer a phone frame-rate improvement.

### Keep the exact MP3 index off the first-play path

**Evidence.** `ExactSeekMp3Extractor.index` lazily scans every frame of a VBR file the first time it opens, on the extractor's init call. A multi-hour file can delay the first Play after the starting arc is drawn. The scan is cached by path, length and mtime.

**First change.** Measure preparation time for a long VBR book on a cold cache, then move the scan off the playback thread or keep the starting state until the index is ready. Keep exact late seeks. The five-second start report in `PlayerConnection` can fire while this scan runs, so the two changes should agree on what “started” means.

**Acceptance.** The first Play of a long VBR file shows its starting state immediately, and a seek near the end still lands within the format matrix tolerance.

### Bound the plan cache

**Evidence.** `PlanCache` keeps an edition plan for every generation it has seen and never evicts. Plans are small, but a long-lived install accumulates one per download, repair and replacement.

**First change.** Tie entries to the published generation, clear them when an edition is removed, or bound the map. No behavior change is needed beyond memory.

## Playback correctness

### Decide how a seek made during playback records its stop

**Evidence.** `onPositionDiscontinuity` sets the pending seek's stop kind to `ORDINARY` while `playWhenReady` is true and `EXACT` while paused (`PlaybackController.kt`). The resume-rewind table only names moving the position while paused as exact, but the following bullet says a seek, skip, scrub or chapter choice makes the next start exact “whatever stopped the book before”. A listener who scrubs during playback, pauses, leaves for an hour and returns currently gets a ten-second rewind.

**First change.** Settle the rule with the owner or a test: either record `EXACT` for every deliberate move, or document that a move made while playing keeps the ordinary rewind. Add a regression test that seeks during playback, pauses, rests past the threshold and starts again.

**Acceptance.** The documented rule and the stored `stopKind` agree, and a deliberate chapter choice still never rewinds.

### Do not flash the empty state before Room emits

**Evidence.** `DownloadedViewModel.state` starts as an empty `DownloadedUiState`, so the no-downloads empty state can appear for a frame before Room emits, even on a phone full of books. `LibraryUiState` has the same shape. This is cosmetic but visible on a cold launch.

**First change.** Suppress the empty state until the first editions/transfers emission, or seed the initial state from a quick local read. Keep the true empty state immediate when there really is nothing.

**Acceptance.** A cold launch with downloads never shows the no-downloads sentence; a phone with none still does.

## Details correctness

### Refuse an unsupported format before the tap

**Evidence.** `DetailViewModel.build` can return `Download`, `DownloadDisabled`, `Repair` or `Play`, but never `DetailAction.Unsupported`; `LibraryViewModel` passes `unsupported = false`. The refusal appears only after a tap, when the coordinator reads the manifest. Spec A24b wants details to disable Download with the unsupported-format copy, and the Library row to show it without starting a job. The pinned catalog list response carries no codec or extension, only media metadata, so this needs either stored format summaries captured at item fetch or an on-demand manifest read in details.

**First change.** Decide between an item-detail fetch cached by profile, item and revision, and carrying format information in a schema change. Then have details and the Library row share one verdict. Never mark a book unsupported because the network was unavailable.

**Acceptance.** Opening an unsupported book shows the disabled action before any tap, starts no job and requests no audio. An offline previously downloaded book still opens and plays. A cached verdict cannot outlive the item's revision.

## Layout and navigation

### Reclaim list space in short landscape

**Evidence.** On API 37 at 420 dpi, a 1920 × 840 px window and 200% text, Downloaded showed its heading above the fixed mini-player and binding. No book was visible until scrolling. At 840 × 1500 px the first transfer was visible. The 12 dp gap under the bookmarks and large-text touch targets are deliberate.

**Prototype.** Use available height to choose a compact landscape arrangement in the main scaffold. Compare a side-mounted binding with a shorter, single-line mini-player. Verify 3-button and gesture navigation, offline status, a waiting transfer, and a long current title.

**Acceptance.** At least one book or transfer is identifiable at rest. Both destinations, the current book and its Play key remain separately reachable with touch and TalkBack.

### Give wide windows a facing details page

**Evidence.** The interface specification calls for details beside the library where space permits. `AppNavHost` currently replaces the list with a full-screen destination, leaving room for a second page on a tablet.

**Prototype.** Keep the library, query, sort and binding on the leading page. Show the selected book and its actions on the facing page, with a quiet seam between the two paper surfaces. Determine the breakpoint from the measured room for both pages with large type.

**Acceptance.** At 320, 600 and 840 dp, exercise selecting a book, Back, rotation and resizing while details are open. Preserve selection, scroll position and the playing book. Check keyboard focus order across panes and avoid duplicate transport announcements.

### Open details from the title as well as the cover

**Evidence.** Library and Downloaded expose the thumbnail as the details target. The larger title beside it does nothing on tap, so a listener looking for the narrator or file size must discover the cover shortcut.

**Prototype.** Group cover and identifying text into one labelled action. Leave Play, Download, transfer controls and progress announcements independent. Try a ready book, a remote book and a waiting transfer at normal and 200% text.

**Acceptance.** A tap on the title or cover opens the intended book. A transport tap runs only its own action. In TalkBack traversal each book has one details action, its metadata is readable, and action names distinguish books with the same title.

### Read the jacket copy before downloading

**Evidence.** The detail page takes its description from the local edition, so an undownloaded book can show the cover, author, length and download size while leaving most of the page empty. Audiobookshelf item metadata already feeds a description into the download manifest.

**First change.** Verify which pinned catalog responses include a plain description. If the list omits it, use an on-demand detail lookup for the selected book. Render the existing summary and actions immediately. Cache prose by profile, item and revision, as plain text. Compare a short description with an expandable long one on a 360 dp phone before fixing a line limit.

**Acceptance.** Opening one remote book makes at most one metadata request for its revision, starts no transfer and leaves Download reachable. Revisiting it offline shows the cached prose. A missing description leaves a tidy page, and a response for the previous book never appears under the current title.

### Remove a download from its Downloaded row

**Evidence.** Remove lives in details; the product brief lists remove under Downloaded. A listener who wants to free space opens a book, scans past its description and chapters, then finds the action at the bottom.

**Prototype.** A row overflow or long-press action calling the existing removal path, with the same confirmation the plan wants for destructive actions and the same preservation of the saved place. Keep the busy transfer rows distinct.

**Acceptance.** Removing from the row frees the audio, keeps the place and returns to a correct list order; a cancelled transfer is unaffected.

## Accessibility and small polish

- **Merge the player utilities' semantics.** Speed, Chapters/Tracks/Parts and Sleep set a content description on the clickable column without merging descendants, so TalkBack may visit the label, value and icon separately after the description.
- **Reserve the mini-player's height.** `MainScaffold` measures the mini-player with `onSizeChanged` and applies bottom padding afterwards, so the list can jump for one frame when it appears or disappears. The measured heights are known (60/68 dp), so reserve them directly.
- **Keep the progress ring decorative.** The compact transfer ring repeats its percentage in visible text and semantics; the ring itself should stay cleared for TalkBack so the row reads once.
- **Check focus indicators over texture at high contrast.** The reports claim the 3:1 focus check; repeat it over the textured paper and cloth with the final composite.

## Delight candidates

- Fine chapter-boundary ticks on the whole-book bar, drawn like pencil marks, to make the two scales' relationship visible without new chrome.
- A soft page-turn settle when the player collapses back to the list, respecting reduced motion.
- A pressed shadow and haptic on the bookmark tabs, matching the keys.
- A short ribbon pulse on the mini-player when a new segment begins.
- A faint cloth spine shadow behind the standing covers on the Bookshelf widget.
- Per-book playback speed, with the global setting as the default for a new book.
- Local-only listening insights: time listened this week, a finished shelf, a day streak. No telemetry, nothing leaves the phone.
- Pencil bookmarks or short clips at the current position, listed in details and seekable, distinct from the single saved place.
- The live sleep countdown on the Now reading widget while a timer runs, keeping the no-running-clocks rule elsewhere.
- Pull-to-refresh in Library, mapped to the existing refresh action.
- A Quick Settings tile that turns Sleep on with the chosen length or off through the existing session command.
- A launcher shortcut that resumes the last book at its saved place.
- A real dark theme later, adjusting every colour and material rather than swapping tokens.

## Investigated and deliberately left unchanged

- **Volume after a faded timed stop.** The player keeps the faded volume after a timer expires and restores it in `beforePlay`. The existing fade test asserts that behaviour, and the contract's “restore full volume for the next start” is met there. A failure or focus loss during the fade also reaches the next start at full volume; changing this would need a test decision first.
- **Debouncing Library search.** A flow `debounce` in `LibraryViewModel` fails the Robolectric JVM tests because `shadowOf(mainLooper).idle()` does not advance delayed tasks, so the listing never arrives. If this is taken up, either teach the tests to advance the clock (`idleFor`) or throttle with a test clock rather than wall time.
- **A book of one part still has both bars, finished books stay finished until Replay, and an end-of-book seek within 250 ms marks the book finished.** These are specified and tested; recorded here only so they are not “simplified” later.

## Test reliability

### Diagnose the intermittent concurrent refresh failure

During a full JVM run, `AddressSessionTest.fiveConcurrentUnauthorizedCallsShareOneRefresh` failed its all-success assertion once and passed when rerun alone. The test waits up to five seconds inside the mock server for five old-token requests. Its assertion hides which request failed and why.

Capture the redacted result category for each synthetic request, the latch timeout result, refresh count and arrival order. Repeat under parallel test load. If the barrier races a client timeout, coordinate the fixture deterministically. If renewal itself races, fix the session and retain a reproducer. Acceptance requires five successful calls, one refresh and one rotated token pair, with credentials excluded from logs.

### Make late renderer callbacks explicit in controller tests

PR #44 added a fake player whose `isPlaying` change arrives after the application-thread pause, which is what a device does. Reuse that technique for other ordering-sensitive cases: a failure arriving during the fade, a seek completing after a pause, a timer expiring during a transient interruption, and the case where no `isPlaying` callback follows at all.

**Acceptance.** Every stop-kind and volume assertion observes the final persisted state, not merely the first write Room emits.
