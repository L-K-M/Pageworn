# Pageworn improvement backlog

These tasks build on the offline-first player, its paper surfaces, Spectral titles and oxblood place ribbon. The November 2026 review used the implementation at `9e4be6d`, the earlier gallery images, and a synthetic library on an API 37 emulator. Each task includes a way to decide whether the change helps.

## Listening and browsing performance

### Keep chapter work off the playback tick

**Evidence.** `PlayerStates.player` maps every segment to a formatted row on each position update. `PlayerViewModel` collects eagerly, and `WidgetStates.reading` constructs the same chapter list for a widget that displays one segment. Details also rebuilds the list from the playback flow. `BookDetailScreen` composes the entire list in a scrolling column.

**First change.** Measure allocations during 60 seconds of playback with 3 and 500 chapters, with the player visible, closed and with Now reading on the launcher. Separate immutable titles, starts and formatted durations from the selected index. Scope any cached data to the current edition generation. For details, compare a keyed lazy list with the existing column. The debug emulator frame samples varied too much to infer a phone frame-rate improvement.

**Acceptance.** A tick within a chapter formats zero chapter rows. A seek across a boundary selects the correct row immediately. Repairing the edition picks up changed titles and timings. The details action and last chapter remain scrollable at 200% text.

### Bound thumbnail cache bookkeeping

**Evidence.** `RemoteCovers` retains mutexes for keys after requests finish, and expiry records for failed keys can survive until a successful retry. Each new file triggers a directory scan for old revisions and another for eviction.

**First change.** Measure retained keys, directory walks and elapsed time for 5,000 distinct thumbnails at two widths. Repeat with a warm cache and an unreachable server. If bookkeeping grows with historical requests, bound the lock table and prune expired failure entries. Measure filesystem cost separately before introducing an index.

**Acceptance.** Concurrent calls for the same key still share one fetch, including when a waiter cancels. Verify the ten-minute retry boundary, replacement of older revisions, and eviction under the byte limit.

## Layout and navigation

### Reclaim list space in short landscape

**Evidence.** On API 37 at 420 dpi, a 1920 × 840 px window and 200% text, Downloaded showed its heading above the fixed mini-player and binding. No book was visible until scrolling. At 840 × 1500 px the first transfer was visible. The 12 dp gap under the bookmarks and large-text touch targets are deliberate.

**Prototype.** Use available height to choose a compact landscape arrangement in the main scaffold. Compare a side-mounted binding with a shorter, single-line mini-player. Verify 3-button and gesture navigation, offline status, a waiting transfer, and a long current title.

**Acceptance.** At least one book or transfer is identifiable at rest. Both destinations, the current book and its Play key remain separately reachable with touch and TalkBack.

### Give wide windows a facing details page

**Evidence.** The interface specification calls for details beside the library where space permits. `AppNavHost` currently replaces the list with a full-screen destination. The single-column composition leaves room for a second page on a tablet.

**Prototype.** Keep the library, query, sort and binding on the leading page. Show the selected book and its actions on the facing page. Use a quiet seam between the two paper surfaces. Determine the breakpoint from the measured room for both pages with large type.

**Acceptance.** At 320, 600 and 840 dp, exercise selecting a book, Back, rotation and resizing while details are open. Preserve selection, scroll position and the playing book. Check keyboard focus order across panes and avoid duplicate transport announcements.

### Open details from the title as well as the cover

**Evidence.** Library and Downloaded expose the thumbnail as the details target. The larger title beside it does nothing on tap. A listener trying to find the narrator or file size has to discover the cover shortcut.

**Prototype.** Group cover and identifying text into one labelled action. Leave Play, Download, transfer controls and progress announcements independent. Try a ready book, remote book and waiting transfer at normal and 200% text.

**Acceptance.** A tap on the title or cover opens the intended book. A transport tap runs only its own action. In TalkBack traversal, each book has one details action, its metadata is readable, and the action names distinguish books with the same title.

### Read the jacket copy before downloading

**Evidence.** The detail page currently takes its description from the local edition. An undownloaded book can show the cover, author, length and download size while leaving most of the page empty. Audiobookshelf item metadata already feeds a description into the download manifest.

**First change.** Verify which pinned catalog responses include a plain description. If the list omits it, use an on-demand detail lookup for the selected book. Render the existing summary and actions immediately. Cache available prose by profile, item and revision and use plain text. Compare a short description with an expandable long one on a 360 dp phone before fixing a line limit.

**Acceptance.** Opening one remote book makes at most one metadata request for its revision, starts no transfer and leaves Download reachable. Revisiting it offline shows the cached prose. A missing description leaves a tidy page, and a response for the previous book never appears under the current title.

## Playback investigation and design experiment

### Verify the track-boundary stop with audio output

The November 22 owner report records A10d reaching 92,967 ms at a 90,000 ms stop, while the saved place was exactly 90,000 ms and no byte of track B was read. A later widget run passed without audio output. The November 23 sleep-mode report records the API 31 failure suite passing, with the output configuration unspecified.

Run `PlaybackFailureTest#a10dATrackThatCannotOpenEndsPlaybackAtItsBoundary` on API 31 with audio output enabled, then with it disabled. Record controller position, last audible sample or sink event, reads from B and durable position. If the difference reproduces, establish which clock diverged before adjusting the controller or the test. Use the existing 90,500 ms controller limit.

### Let a listener undo a misplaced whole-book seek

A small movement on the book scrubber can cross hours of audio. Prototype a transient “Return to 2:14:36” action after a whole-book drag commits. A pencil tick can show the departure point without reusing the saved-place ribbon. Compare the text action alone with text plus tick.

Test an accidental seek, an intentional seek, repeated scrubs and switching books. Undo must return once to the last departure point, preserve play or pause, and never seek a different book. Check discoverability with TalkBack and agree on the lifetime with listeners before persisting any new state.

## Test reliability

### Diagnose the intermittent concurrent refresh failure

During a full JVM run, `AddressSessionTest.fiveConcurrentUnauthorizedCallsShareOneRefresh` failed its all-success assertion once and passed when rerun alone. The test waits up to five seconds inside the mock server for five old-token requests. Its assertion hides which request failed and why.

Capture the redacted result category for each synthetic request, the latch timeout result, refresh count and arrival order. Repeat under parallel test load. If the barrier races a client timeout, coordinate the fixture deterministically. If renewal itself races, fix the session and retain a reproducer. Acceptance requires five successful calls, one refresh and one rotated token pair, with credentials excluded from logs.
