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
- **Verify formats with the manifest codec.** PR #39 threads the Audiobookshelf codec into the post-download verifier, so MP3 frames inside a CAF container reach the probe instead of being refused after the bytes were fetched. A `TrackProbe` test double covers the gate on the JVM.
- **Hold Downloaded rows to the checkpoint clock.** PR #43 strips volatile playback fields and holds the moving position to five seconds before combining, so a playing book no longer rebuilds every row four times a second. Transport changes still pass through at once.
- **Keep a published cover on a transferring row.** PR #46 prefers the edition's stored cover during a repair or replacement, falling back to the server thumbnail only when no local file exists.
- **Select the sleep length that is actually running.** PR #53 selects a carried-over timer, or a running length whose preset was removed, and lists that length while it runs so exactly one radio carries the countdown.
- **Settle Library search before querying.** PR #58 debounces non-empty queries by 200 ms while keeping the field immediate, so a large catalog is not re-searched and re-sorted once per character.
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

The same lifetime question applies to `EditionStore.bookLocks` and
`ProgressStore.handedOut`, which keep one mutex or session generation per book
forever, and to `ServerAccess.connections`, which is replaced rather than
evicted when a policy changes.

**First change.** Tie entries to the published generation, clear them when an edition is removed, or bound the map. Give book locks and handed-out session counters the same treatment: keep them while a book is reachable in the session, then let them go. No behavior change is needed beyond memory.

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

### Disable Download after a proven format refusal

**Evidence.** Until the pre-tap verdict above lands, a Download tap that
fetches a manifest and finds an unsupported format leaves the button enabled,
so the same refusal repeats on every tap. `DetailAction.Unsupported` already
exists and renders a disabled button with the message; nothing produces it.

**First change.** Once a refusal proves the format unsupported, represent that
visit as `DetailAction.Unsupported` instead of the plain Download action. Keep
the action enabled for transient route and permission refusals.

**Acceptance.** A repeated tap does not repeat the fetch; the message stays;
leaving and reopening the screen clears the visit-local verdict.

### Decide how a repair appears next to its book

**Evidence.** A repair enqueues a transfer while the damaged edition stays
published until the new generation commits, so Downloaded can show the same
title twice: once in the Downloading group and once as Repair needed with two
different sets of actions.

**First change.** Decide whether the old copy remains playable during a repair.
If it does, make the two roles unmistakable; if it does not, hide the book row
while its transfer is active. Add a state test for a damaged edition with an
active repair job.

**Acceptance.** A listener can tell which row plays and which replaces it, and
the book appears once after publication.

## Connection, settings and diagnostics

### Name the pinned route in manual mode

**Evidence.** Manual mode filters candidates to the pinned address. If that
address is gone, disabled or no longer paired, the candidate pool is empty and
`RouteSelector` reports `Unreachable(null)`, so the listener sees a generic
“Can’t reach your server” instead of the route they chose.

**First change.** Pass the pinned address row (or its label) into the selector,
or restore the label in `ServerAccess.route` when manual mode finds no
candidate. Cover a removed pin, a disabled pin and a certificate-blocked pin.

**Acceptance.** Every manual-mode failure names the pinned address; automatic
mode is unchanged.

### Clear a stale diagnostics export failure

**Evidence.** `SettingsViewModel.exportFailed` is cleared only by a successful
export. After one failed save, a later successful export leaves “Couldn’t export
diagnostics” on screen.

**First change.** Clear the flag when a new create-document launch starts.
A save-failure test needs a URI that fails to open, or extract the flag into a
testable state change.

**Acceptance.** A retry that succeeds shows no failure message; a genuine
failure still does.

### Distinguish an unreachable server from a malformed refresh

**Evidence.** `AccountService.refreshCatalog` maps every failing page to the
unreachable notice, including a well-formed HTTP error or an unparseable body
from a reachable server. A first load then shows “Can’t reach your server”
instead of “Your library didn’t finish loading”, which sends the listener to
check their network when the server answered badly.

**First change.** Carry a distinct server-error notice out of a refresh
failure, or prefer `ListingStatus.INCOMPLETE` for a first load while keeping
the cached-catalog status line for later failures.

**Acceptance.** A reachable server that returns a malformed page shows the
incomplete copy with Retry; an unreachable server shows the unreachable copy
with the last refresh time; a cached catalog still browses through both.

## Layout and navigation

### Align the two section headings

**Evidence.** Downloaded draws a pencil rule under its heading and Library does
not, so the two permanent destinations read as slightly different surfaces.

**First change.** Choose one treatment and apply it to both. Add a visual check
that compares the heading block, search row and first row position on both
destinations at 100% and 200% text.

**Acceptance.** The same rule, spacing and heading baseline appear in Library
and Downloaded in light and dark.

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
- A ribbon tail that lengthens with progress, clamped to a tasteful range, so
  the shelf itself tells how far each book is while the exact time stays in
  text. Keep it distinct from any temporary marker.
- A live landing preview while dragging the whole-book bar: the chapter or part
  under the finger and its time, not just the scale's own numbers.
- A one-off sleep timer on a long-press of the Sleep utility, without changing
  the persisted mode.
- The live sleep countdown where the listener already looks: the media metadata
  subtitle on the lock screen, and optionally the Now reading widget, keeping
  the no-running-clocks rule everywhere else.
- Widget picker artwork generated from the cover pipeline, instead of the blank
  preview frames the launcher draws today.
- Per-book playback speed, with the global setting as the default for a new book.
- Local-only listening insights: time listened this week, a finished shelf, a day streak. No telemetry, nothing leaves the phone.
- Pencil bookmarks or short clips at the current position, listed in details and seekable, distinct from the single saved place.
- Pull-to-refresh in Library, mapped to the existing refresh action.
- A Quick Settings tile that turns Sleep on with the chosen length or off through the existing session command.
- A launcher shortcut that resumes the last book at its saved place.
- A real dark theme later, adjusting every colour and material rather than swapping tokens.

## Test reliability

### Diagnose the intermittent concurrent refresh failure

During a full JVM run, `AddressSessionTest.fiveConcurrentUnauthorizedCallsShareOneRefresh` failed its all-success assertion once and passed when rerun alone. The test waits up to five seconds inside the mock server for five old-token requests. Its assertion hides which request failed and why.

Capture the redacted result category for each synthetic request, the latch timeout result, refresh count and arrival order. Repeat under parallel test load. If the barrier races a client timeout, coordinate the fixture deterministically. If renewal itself races, fix the session and retain a reproducer. Acceptance requires five successful calls, one refresh and one rotated token pair, with credentials excluded from logs.

### Make late renderer callbacks explicit in controller tests

PR #44 added a fake player whose `isPlaying` change arrives after the application-thread pause, which is what a device does. Reuse that technique for other ordering-sensitive cases: a failure arriving during the fade, a seek completing after a pause, a timer expiring during a transient interruption, and the case where no `isPlaying` callback follows at all.

**Acceptance.** Every stop-kind and volume assertion observes the final persisted state, not merely the first write Room emits.
