# Pageworn improvement backlog

These tasks build on the offline-first player, its paper surfaces, Spectral titles and oxblood place ribbon. The November 2026 review used the implementation at `9e4be6d`, the gallery images and a synthetic library on an API 37 emulator. Each task includes a way to decide whether the change helps.

## Implemented in open pull requests

- **Keep chapter work off the playback tick.** PR #34. `PlayerStates.player` now carries the timeline's own segment list and current index instead of formatting every chapter row four times a second; the lazy sheet formats only visible rows. The regression test asserts two live states share one list while the index advances.
- **Name the state of every library transfer.** PR #35. Library rows pair the progress icon with the state word at every text size, not only from 150 percent, so paused and waiting downloads no longer read as bare rings.

## Listening and browsing performance

### Bound thumbnail cache bookkeeping

**Evidence.** `RemoteCovers.write` renames a temp file and then calls `evictIfNeeded`, which lists and deletes every file under the byte cap, including another key's `cover-*.tmp` before its rename. `RemoteCovers` also retains mutexes for keys after requests finish, and expiry records for failed keys can survive until a successful retry.

**First change.** Measure retained keys, directory walks and elapsed time for 5,000 distinct thumbnails at two widths, with a warm cache and an unreachable server. Restrict eviction and byte accounting to committed thumbnails. If bookkeeping grows with historical requests, bound the lock table and prune expired failure entries. Measure filesystem cost separately before introducing an index.

**Acceptance.** Concurrent calls for the same key still share one fetch, including when a waiter cancels. A staged temp file survives eviction under a tight byte limit. Verify the ten-minute retry boundary, replacement of older revisions, and eviction under the byte limit.

### Keep the exact MP3 index off the first-play path

**Evidence.** `ExactSeekMp3Extractor.index` lazily scans every frame of a VBR file the first time it opens, on the extractor's init call. A multi-hour file can delay the first Play after the starting arc is drawn. The scan is cached by path, length and mtime.

**First change.** Measure preparation time for a long VBR book on a cold cache, then move the scan off the playback thread or keep the starting state until the index is ready. Keep exact late seeks.

**Acceptance.** The first Play of a long VBR file shows its starting state immediately, and a seek near the end still lands within the format matrix tolerance.

### Bound the plan cache

**Evidence.** `PlanCache` keeps an edition plan for every generation it has seen and never evicts. Plans are small, but a long-lived install accumulates one per download, repair and replacement.

**First change.** Tie entries to the published generation, clear them when an edition is removed, or bound the map. No behavior change is needed beyond memory.

## Details correctness

### Refuse an unsupported format before the tap

**Evidence.** `DetailViewModel.build` can return `Download`, `DownloadDisabled`, `Repair` or `Play`, but never `DetailAction.Unsupported`; `LibraryViewModel` passes `unsupported = false`. The refusal appears only after a tap, when the coordinator reads the manifest. Spec A24b wants details to disable Download with the unsupported-format copy, and the Library row to show it without starting a job. The pinned catalog list response carries no codec or extension, only media metadata, so this needs either stored format summaries captured at item fetch or an on-demand manifest read in details.

**First change.** Decide between an item-detail fetch cached by profile, item and revision, and carrying format information in a schema change. Then have details and the Library row share one verdict. Never mark a book unsupported because the network was unavailable.

**Acceptance.** Opening an unsupported book shows the disabled action before any tap, starts no job and requests no audio. An offline previously downloaded book still opens and plays. A cached verdict cannot outlive the item's revision.

### Handle a detail link whose book is gone

**Evidence.** `DetailViewModel.build` returns null when both the catalog row and the edition are absent, and `AppNavHost` renders nothing for null. Removing the last local copy, or opening a stale link after a server-side removal and catalog refresh, leaves a blank paper screen with no route except Back.

**First change.** Distinguish loading from missing and offer a route to Downloaded. Verify that removing a book keeps its saved place.

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

## Playback investigation and design experiment

### Verify the track-boundary stop with audio output

The November 22 owner report records A10d reaching 92,967 ms at a 90,000 ms stop, while the saved place was exactly 90,000 ms and no byte of track B was read. A later widget run passed without audio output. The November 23 sleep-mode report records the API 31 failure suite passing, with the output configuration unspecified.

Run `PlaybackFailureTest#a10dATrackThatCannotOpenEndsPlaybackAtItsBoundary` on API 31 with audio output enabled, then with it disabled. Record controller position, last audible sample or sink event, reads from B and durable position. If the difference reproduces, establish which clock diverged before adjusting the controller or the test. Use the existing 90,500 ms controller limit.

### Let a listener undo a misplaced whole-book seek

A small movement on the book scrubber can cross hours of audio. Prototype a transient “Return to 2:14:36” action after a whole-book drag commits. A pencil tick can show the departure point without reusing the saved-place ribbon. Compare the text action alone with text plus tick.

Test an accidental seek, an intentional seek, repeated scrubs and switching books. Undo must return once to the last departure point, preserve play or pause, and never seek a different book. Check discoverability with TalkBack and agree on the lifetime with listeners before persisting any new state.

## Delight candidates

- Fine chapter-boundary ticks on the whole-book bar, drawn like pencil marks, to make the two scales' relationship visible without new chrome.
- A soft page-turn settle when the player collapses back to the list, respecting reduced motion.
- A pressed shadow and haptic on the bookmark tabs, matching the keys.
- A short ribbon pulse on the mini-player when a new segment begins.
- A faint cloth spine shadow behind the standing covers on the Bookshelf widget.

## Test reliability

### Diagnose the intermittent concurrent refresh failure

During a full JVM run, `AddressSessionTest.fiveConcurrentUnauthorizedCallsShareOneRefresh` failed its all-success assertion once and passed when rerun alone. The test waits up to five seconds inside the mock server for five old-token requests. Its assertion hides which request failed and why.

Capture the redacted result category for each synthetic request, the latch timeout result, refresh count and arrival order. Repeat under parallel test load. If the barrier races a client timeout, coordinate the fixture deterministically. If renewal itself races, fix the session and retain a reproducer. Acceptance requires five successful calls, one refresh and one rotated token pair, with credentials excluded from logs.
