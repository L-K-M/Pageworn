# Pageworn improvement backlog

These tasks build on the offline-first player, its paper surfaces, Spectral titles and oxblood place ribbon. The November 2026 review used the implementation at `9e4be6d`, later main revisions, the gallery images and a synthetic library on an API 37 emulator. Each task includes a way to decide whether the change helps.

## Implemented

Each entry names the pull request that carried it. Entries were recorded while their PR was
open; check its state before relying on one. Anything not listed here is still open.

### Merged into main

- **Verify formats with the manifest codec.** PR #39 threads the Audiobookshelf codec into the post-download verifier, so MP3 frames inside a CAF container reach the probe instead of being refused after the bytes were fetched. The probe's own codec is re-checked against the matrix and becomes the published fact. A `TrackProbe` test double covers the gate on the JVM.
- **Keep a published cover on a transferring row.** PR #46 prefers the edition's stored cover during a repair or replacement, falling back to the server thumbnail only when no local file exists.
- **Select the sleep length that is actually running.** PR #53 selects a carried-over timer, or a running length whose preset was removed, and lists that length while it runs so exactly one radio carries the countdown.
- **Settle Library search before querying.** PR #58 debounces non-empty queries by 200 ms while keeping the field immediate, so a large catalog is not re-searched and re-sorted once per character.
- **Refresh the catalog after signing in again.** PR #37 makes `SIGN_IN` refresh libraries and the catalog like the other reconnect modes, so a cached Library cannot stay stale behind a restored session.
- **Mark a saved place on the details cover.** PR #38 shows the ribbon for Resume and leaves fresh and finished books unmarked; its companion PR #29 is still open.
- **Draw focus rings outside keys and paper rows.** PR #57. The key ring surrounds the face, and text actions and disclosure rows gain the same pencil outline as the other controls.
- **Treat an unreadable library list as a sign-in failure.** PR #56. A failed `/api/libraries` read revokes the fresh session and reports the address problem instead of storing an empty profile or claiming a different server; its companion PR #41 is still open.
- **Load progress once for the car's book list.** PR #50 replaces the per-edition `book_progress` query in `readyBooks()` with one `ProgressDao.all()`.
- **Say that a removal keeps the place.** PR #60. The long-unused `AUDIO_REMOVED` copy now appears under the detail action until a download starts or an edition returns.
- **Bound the plan cache.** PR #45 turns `PlanCache` into an access-ordered LRU of 16 generations instead of an unbounded map.
- **Coarsen Downloaded's live position.** PR #42 floors a playing row's place to the minute and skips unchanged live states, replacing the four-per-second rebuild of every row. A second approach in PR #43 was closed as a duplicate once #42 merged.
- PR #48 overlaps PR #35 (transfer state text); keep one when they merge.
- **Centralize the Library row message.** PR #36 moves the row's metadata line into `RowBuilders.catalogRowMessage`: a transfer always shows its state text joined with any failure message (blank parts dropped), and `Copy.REPAIR_NEEDED` is shared with the Downloaded badge. `CatalogRowMessageTest` pins the precedence and blank handling.
- **Fade and crossfade cover art.** PR #51 fades fetched covers in over the cloth (180 ms, off under reduced motion), keeps the previous art on screen while a changed cover loads, crossfades instead of popping, and rethrows `CancellationException` so a cancelled load is never mistaken for missing art.
- **Pull down on Library to refresh the catalog.** PR #59 wraps the list in a `PullToRefreshBox`; refreshes from pulls, retries and library switches are serialized behind a mutex so the spinner never drops while work is queued.
- **Settle list rows into place.** PR #62 applies `Modifier.animateItem()` to Library and Downloaded rows and the Downloading header, and omits the trailing divider at the bottom of each list.
- **Wait for the sleep window's next edge.** PR #64 replaces the 30 s sleep poll with a `collectLatest` loop that delays until `SleepSchedule.nextEdge` (overnight ends after midnight included, DST-tested) and falls back to idle polling if no edge resolves, so the watcher cannot crash.
- **Keep the Library search field bounded at large font scales.** PR #65 wraps at two lines from 150% text, pins `ImeAction.Search` with a focus-clearing action, and strips newlines from hardware-Enter or pasted text.
- PR #40 was closed as a duplicate of #60 (removal notice) and PR #47 as a duplicate of #42 (Downloaded row tick).

### Open pull requests

- **Keep chapter work off the playback tick.** PR #34. `PlayerStates.player` now carries the timeline's own segment list and current index instead of formatting every chapter row four times a second; the lazy sheet formats only visible rows. The regression test asserts two live states share one list while the index advances.
- **Name the state of every library transfer.** PR #35. Library rows pair the progress icon with the state word at every text size, not only from 150 percent, so paused and waiting downloads no longer read as bare rings.
- **Protect writes during cover cache eviction.** PR #25 limits eviction to published thumbnails and reclaims abandoned temporary files on the next process's first write.
- **Return from missing book details.** PRs #27 and #32 distinguish loading from a missing book. An API 37 removal test checks the return to Downloaded and the saved place.
- **Retry a download in place.** PR #28 adds Retry to Library and details while retaining Pause and Cancel where available.
- **Bound cover decoding.** PR #30 samples against the longer edge across Compose, cached remote art and widgets. A 256 × 8192 portrait decodes to 8 × 256 for a 128 px target.
- **Keep an end-of-chapter sleep stop exact.** PR #44. ExoPlayer reports the pause inline but `isPlaying` false later, so the pause checkpoint could store an ordinary stop over the exact boundary and turn a rested resume into a ten-second rewind. The controller arms the exact pause kind before pausing; the regression test defers that callback and fails on the old code.
- **Read covers and file checks off the main thread.** PR #49. The System UI resumption control no longer reads up to 2 MB of cover bytes on the main thread, and opening a book verifies track sizes on the IO dispatcher rather than during media-item resolution.
- **Keep an incomplete first load in the status line.** PR #52. The staged rows stay browsable and Retry sits between the heading block and the rows, rather than in the centred empty-state form.
- **Disable detail Download after a refusal.** PR #54. An unsupported format or a per-book download denial replaces the primary action with the specified disabled action while the refusal lasts. The pre-tap verdict below remains open.
- **Show a remote book's description on its details page.** PR #55. The pinned minified listing already carries `media.metadata.description` (verified against the captured v2.38.0 fixtures), so the catalog now stores it (`catalog_book`/`catalog_staging`, schema 3) and details fall back to it when no downloaded edition snapshots its own copy. The HTML-only fallback is stripped to plain text in both parse paths.
- **Project progress to what Library rows draw.** PR #61. Library rows use only hasPlace/finished, so the view model now projects `BookProgressEntity` to that pair with `distinctUntilChanged`; a moving position no longer re-maps every row of a large library on each checkpoint. Downloaded rows still read exact positions.
- **Clear stale cover art during a source change.** PR #26 resets the Compose bitmap when the artwork source changes. The API 37 regression checks the cloth placeholder while a replacement is delayed.
- **Keep the held shelf order without sorting again.** PR #31 uses the existing order while the membership stays the same. The JVM regression reduces three held rows from eleven progress lookups to three.
- **Compose long chapter lists lazily.** PR #33 keeps chapter and removal actions reachable. The API 37 regression bounds the initial work for a 400-part edition and checks the controls at 320 dp.

## Listening and browsing performance

### Publish only when live state changes

**Evidence.** `PlaybackController.tickLoop` calls `publish()` every 250 ms, and the player and mini-player combine the hub state directly. At rest every visible field is unchanged. The tick still constructs a `LivePlayback` candidate every 250 ms, while StateFlow suppresses equal snapshots. Measure candidate allocation separately from any downstream player-state work. PR #34 removed the per-tick chapter formatting, but the publish and the surrounding state construction remain.

**First change.** Measure allocations and downstream emissions during a minute at rest. If idle allocations are material, skip constructing the candidate until a change or choose a slower idle cadence. Keep immediate publishes for `starting`, `held`, failure, sleep arming and countdown changes, which are event-driven. Be careful that a settled pause still publishes once after its place moves.

**Acceptance.** A paused book with a sheet closed produces no player-state emissions while idle; playing position, the sleep countdown and every transport transition still appear immediately.

### Decode paper and cloth tiles once

**Evidence.** `paperBackground()` and `BindingFooter` decode their texture with `BitmapFactory.decodeResource` behind `remember(context)`, so each usage has its own copy and a screen recreation decodes again. The tiles are small, but the textures are constant for the process lifetime.

**First change.** Hold decoded `ImageBitmap`s in a process-wide cache keyed by resource id, or provide them through a composition local from the activity. Keep high-contrast handling unchanged.

**Acceptance.** Repeated navigation and rotation do not re-decode; high contrast still drops both textures.

### Bound thumbnail cache bookkeeping

**Evidence.** The thumbnail cache walks the directory for older revisions and eviction after publication. It retains mutexes for keys after requests finish, and expiry records for failed keys can survive until a successful retry.

**First change.** Measure retained keys, directory walks and elapsed time for 5,000 distinct thumbnails at two widths, with a warm cache and an unreachable server. If bookkeeping grows with historical requests, bound the lock table and prune expired failure entries. Measure filesystem cost separately before introducing an index. Compare repeated decodes when scrolling out and back before choosing a byte-budgeted bitmap cache.

**Acceptance.** Concurrent calls for the same key still share one fetch, including when a waiter cancels. Preserve the staged-file and abandoned-write regressions from PR #25. Verify the ten-minute retry boundary for genuinely missing covers, replacement of older revisions, and eviction under the byte limit. Include square, landscape and very tall artwork.

### Measure long chapter lists in details

Compare the time to first interactive frame for a three-chapter book and one with 500 chapters once the lazy details rows from PR #33 are available. The baseline regression composed all 400 parts. Separate initial layout from ongoing model allocations during 60 seconds of playback with the player visible, closed and with Now reading on the launcher. Details still rebuilds the complete segment model on playback ticks although its rows do not display the current-segment flag.

Check that the last chapter and removal action remain reachable at 200% text, and that a repaired edition shows its new titles. Initial debug-emulator frame samples varied too much to infer a phone frame-rate improvement.

### Keep the exact MP3 index off the first-play path

**Evidence.** `ExactSeekMp3Extractor.index` lazily scans every frame of a VBR file the first time it opens, on the extractor's init call. A multi-hour file can delay the first Play after the starting arc is drawn. The scan is cached by path, length and mtime. Long indexes store two frame arrays each. Measure heap use cycling through four multi-hour files before choosing a smaller memory budget or a disk-backed index.

**First change.** Measure preparation time for a long VBR book on a cold cache, then move the scan off the playback thread or keep the starting state until the index is ready. Keep exact late seeks. The five-second start report in `PlayerConnection` can fire while this scan runs, so the two changes should agree on what “started” means.

**Acceptance.** The first Play of a long VBR file shows its starting state immediately, and a seek near the end still lands within the format matrix tolerance.

### Bound the plan cache

**Evidence.** `PlanCache` keeps an edition plan for every generation it has seen and never evicts. Plans are small, but a long-lived install accumulates one per download, repair and replacement.

PR #45 bounds this map as an access-ordered LRU of 16 plans. The remaining work is the same lifetime question for the other tables below.

The same lifetime question applies to `EditionStore.bookLocks` and
`ProgressStore.handedOut`, which keep one mutex or session generation per book
forever, and to `ServerAccess.connections`, which is replaced rather than
evicted when a policy changes.

**First change.** Keep book locks and handed-out session counters for active users, then release them without allowing overlapping locks or late writes to reopen the generation. Apply the same lifetime analysis to retained address connections. No behavior change is needed beyond memory.

### Profile shelf rebuilding while a download and playback run

`LibraryViewModel.build` remaps the whole catalog for local changes such as transfer journal writes. Downloaded builds transfer and book rows eagerly, including when it is hidden. Measure a 10,000-item library and a 200-download shelf during fast scrolling, query typing and player transitions. Record frame timing, allocations and row-building time. Compare the Downloaded changes in PRs #31 and #43 before changing its cadence further.

Try a lifecycle-aware collector and separate projections for static metadata, transport and position. Cache timelines by edition generation. Keep transport state and relevant chapter boundaries immediate, preserve stable row order, and use traces to decide whether paging or stable callback reuse helps. A hidden shelf should have no row rebuilds, while a visible shelf keeps position and badges current at their displayed precision.

**First change.** PRs #42 and #43 coarsen or hold the position and skip unchanged states. Still open: switch the flow to `WhileSubscribed` when no screen needs it, cache timelines against the edition generation set, and measure allocations during a minute of playback with the player closed.

### Distinguish network thumbnail failures from missing art

`AppContainer` maps every failed routed cover request to null, which `RemoteCovers` negatively caches for ten minutes. An offline browse can therefore keep returning cloth after the server has recovered. Carry an explicit result for a confirmed absent cover versus a transient route or response failure into the cache. In a disposable fixture, start offline, show cloth, restore a working route, and verify a retry reveals the cover without recreating the process. Keep negative caching for genuinely missing artwork and avoid issuing a network request per visible row while the server is already known to be offline.

### Page the Android Auto shelf before building playback plans

`EditionStore.readyBooks` now loads progress in one query (PR #50); it still builds every ready plan before `BookSessionCallback.onGetChildren` slices a page. For the car path, order the lightweight edition records, then construct plans for the requested page. Decide how missing files affect page boundaries and the empty state. Exercise a 200-edition shelf with two pages, stable recency, removed books, and offline startup. Record query count and latency and leave widget estimates independent.

## State and lifecycle correctness

### Honour the remembered section on a reachable cold launch

**Evidence.** `MainViewModel` selects `Destination.DOWNLOADED` as soon as one local edition exists, before any network decision. `docs/01-product.md` says a saved profile remembers the last main section, and reserves the Downloaded cold launch for an unreachable catalog with local books. A listener who last left Library on a healthy server is dropped into Downloaded after every restart.

**First change.** Start on the remembered destination and move to Downloaded when the launch refresh reports unreachable and local books exist. Render the local state before the network answer. Decide with the owner whether to switch once the answer arrives, and keep the chosen destination after the listener interacts.

**Acceptance.** Cold launch with a cached catalog and local books opens the remembered section. Cold launch with an unreachable server and local books still opens Downloaded, as A17 requires, and shows the cached catalog with its Retry. Warm starts retain the current screen. Exercise cached-only and empty profiles as well.

### Recover from credential vault read and write failures

**Evidence.** `KeystoreTokenVault.read` decodes Base64 and reads the file outside its `runCatching`; a truncated credential file throws into the caller. `write` lets a failed temp-file rename throw. Sign-in runs in a view-model scope where an uncaught exception is fatal, although the documented recovery is simply a new sign-in.

**First change.** Return null from `read` on storage or format errors and let callers show the normal sign-in prompt; report a failed persist instead of throwing.

**Acceptance.** A corrupt credential file leaves the address signed out with no crash, and a failed write surfaces as a sign-in failure the listener can retry.

### Dismiss the notifications notice when leaving Downloaded

**Evidence.** `AppNotices.leftDownloaded` runs only when the bottom bookmark changes section. Opening Settings, details or the player from Downloaded leaves the pending notice alive, although the copy table says it stays only until the listener uses its action or goes from Downloaded to another screen.

**First change.** Clear the notice when the main route leaves the Downloaded tab, not only when the tab selection changes.

**Acceptance.** The notice clears on Back to Library, on opening details or the player, and on the tab switch; the setting action still works when it is shown.

## Playback correctness

### Decide how a seek made during playback records its stop

**Evidence.** `onPositionDiscontinuity` sets the pending seek's stop kind to `ORDINARY` while `playWhenReady` is true and `EXACT` while paused (`PlaybackController.kt`). The resume-rewind table only names moving the position while paused as exact, but the following bullet says a seek, skip, scrub or chapter choice makes the next start exact “whatever stopped the book before”. A listener who scrubs during playback, pauses, leaves for an hour and returns currently gets a ten-second rewind.

**First change.** Settle the rule with the owner or a test: either record `EXACT` for every deliberate move, or document that a move made while playing keeps the ordinary rewind. Add a regression test that seeks during playback, pauses, rests past the threshold and starts again.

**Acceptance.** The documented rule and the stored `stopKind` agree, and a deliberate chapter choice still never rewinds. Include pausing immediately after the seek and after further audible playback. Preserve the delayed-seek guard and the timed-rewind rule.

### Verify rewind after transient focus recovery

The playback contract counts automatic focus recovery among starts for resume rewind. ExoPlayer resumes internally after a transient interruption, so `BookPlayer.play` does not get a chance to apply the rewind. Simulate a transient loss with a fake clock on either side of five minutes. Verify that user Pause, sleep expiry and permanent loss prevent auto-resume, and that a short interruption leaves the position and sleep deadline intact.

### Do not flash the empty state before Room emits

**Evidence.** `DownloadedViewModel.state` starts as an empty `DownloadedUiState`, so the no-downloads empty state can appear for a frame before Room emits, even on a phone full of books. `LibraryUiState` has the same shape. This is cosmetic but visible on a cold launch.

**First change.** Suppress the empty state until the first editions/transfers emission, or seed the initial state from a quick local read. Keep the true empty state immediate when there really is nothing.

**Acceptance.** A cold launch with downloads never shows the no-downloads sentence; a phone with none still does.

### Serve car covers without blocking a binder thread

**Evidence.** `CoverProvider.openAssetFile` calls `runBlocking { container.coverFileFor(key) }`, a Room read on a binder thread. It is not an ANR (binder, not main), but it parks a binder thread on the database.

**First change.** Keep a small in-memory cover-path map maintained by `EditionStore` on publish/remove/recover, or read through a synchronous DAO on a dedicated executor. Verify the car list still gets a fresh URI per generation and a cloth fallback for art-free books.

## Details correctness

### Refuse an unsupported format before the tap

**Evidence.** `DetailViewModel.build` can return `Download`, `DownloadDisabled`, `Repair` or `Play`, but never `DetailAction.Unsupported`; `LibraryViewModel` passes `unsupported = false`. The refusal appears only after a tap, when the coordinator reads the manifest. Spec A24b wants details to disable Download with the unsupported-format copy, and the Library row to show it without starting a job. The pinned catalog list response carries no codec or extension, only media metadata, so this needs either stored format summaries captured at item fetch or an on-demand manifest read in details.

**First change.** Decide between an item-detail fetch cached by profile, item and revision, and carrying format information in a schema change. Then have details and the Library row share one verdict. Never mark a book unsupported because the network was unavailable.

**Acceptance.** Opening an unsupported book shows the disabled action before any tap, starts no job and requests no audio. An offline previously downloaded book still opens and plays. A cached verdict cannot outlive the item's revision. PR #54 covers disabling the action after a proven refusal. Keep its visit-local verdict and avoid repeatedly fetching the same manifest after a confirmed unsupported format. A transient route error remains retryable.

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

### Recompute local-network permission state when Addresses resumes

**Evidence.** `AddressesViewModel` computes `denied` inside its `combine` transform, so the answer changes only when addresses, test results or the pin emit. Granting the Android 17 local-network permission in system settings and returning to the app changes none of them; the row keeps the denied message and the Allow-access action even though a request would now succeed.

**First change.** Expose permission state as an observed flow, or recompute on screen entry and resume. Keep the DNS classification off the main thread as it is today.

**Acceptance.** Deny, open Library and see the local-network message; grant in system settings, return, and the message clears without tapping Test. On Android 12 to 16 nothing is requested.

### Bind catalog rows to the active library and search request

`ui/state/LibraryViewModel.kt` combines selection and query with a separately collected listing. A new selection can render with the preceding library's rows until Room emits the new query. The old list can similarly show under new search text. Key listing emissions by profile, library, normalized query and sort. Gate rows and callbacks with that identity, while keeping raw text responsive. Test with a deliberately delayed second Room listing and rapid A → B → A switching. Keep cached rows for a refresh of the same library and query. Give each library a deliberate scroll-restoration rule. The DAO runs leading-wildcard title/author LIKE and an ORDER BY on each keystroke, then sorts in Kotlin for the chosen catalog order. Benchmark Room on synthetic libraries before choosing a small debounce, an index, or paging. Preserve literal wildcard escaping and substring behavior.

### Recover and prune interrupted catalog refreshes

`catalog/CatalogRefresher.kt` persists RUNNING attempts and stages each page, but process death can leave a first library filling indefinitely until another refresh is started. Attempts also accumulate. Seed an interrupted first refresh and inspect launch and retry. Define what constitutes an abandoned attempt, show its staged rows with Retry, and prune terminal history only after preserving the monotonic sequence and the currently visible first-load attempt. Test a process restart and overlapping attempts. Verify that the last complete catalog remains available offline.

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

**Acceptance.** At 320, 600 and 840 dp, exercise selecting a book, Back, rotation and resizing while details are open. Preserve selection, scroll position and the playing book. Check keyboard focus order across panes and avoid duplicate transport announcements. Cover a foldable hinge and both Android navigation inset styles.

### Open details from the title as well as the cover

**Evidence.** Library and Downloaded expose the thumbnail as the details target. The larger title beside it does nothing on tap, so a listener looking for the narrator or file size must discover the cover shortcut.

**Prototype.** Group cover and identifying text into one labelled action. Leave Play, Download, transfer controls and progress announcements independent. Try a playing book, a remote book and a waiting transfer at normal and 200% text with wrapped titles. Verify Enter opens details from the descriptive part without toggling playback.

**Acceptance.** A tap on the title or cover opens the intended book. A transport tap runs only its own action. In TalkBack traversal each book has one details action, its metadata is readable, and action names distinguish books with the same title.

### Read the jacket copy before downloading

**Done in PR #55.** The pinned minified listing does include `media.metadata.description` (checked against the captured v2.38.0 fixtures), so no extra request is needed: the refresh stores it on the catalog row (schema 3) and details show it for a book that was never downloaded, offline included. The HTML-only fallback is tag-stripped to plain text.

**Remaining.** Long descriptions: compare a short description with an expandable long one on a 360 dp phone before fixing a line limit. A missing description must leave a tidy page.

### Remove a download from its Downloaded row

**Evidence.** Remove lives in details; the product brief lists remove under Downloaded. A listener who wants to free space opens a book, scans past its description and chapters, then finds the action at the bottom.

**Prototype.** A row overflow or long-press action calling the existing removal path, with the same confirmation the plan wants for destructive actions and the same preservation of the saved place. Keep the busy transfer rows distinct.

**Acceptance.** Removing from the row frees the audio, keeps the place and returns to a correct list order; a cancelled transfer is unaffected.

### Distribute spare height in the player

**Evidence.** The API 37 player capture shows generous gaps around the transport: at 1080 × 2400 and 420 dpi the chapter times end near y 1539, the transport row occupies 1733–1943 and the utilities 2137–2295, two equal 194 px gaps. `PlayerScreen` uses two weighted spacers in a scrollable column constrained to at least the viewport height; source reading raises the question because a scrolling column measures children with an unbounded maximum height and weighted space can be derived from the minimum constraint. Verify actual bounds at regular and short heights before changing the weights.

**Prototype.** Tighten the groups of seek scales, primary transport and utilities. Compare a scrolling upper region with a compact lower stack, or insert bounded spacers only when natural content fits. Preserve 48 dp targets and heavier brass and oxblood treatment on Play/Pause.

**Acceptance.** Regular-height screens keep the designed separation. In short landscape and at 200% text, long titles and error notices can still push every control into reachable scroll.

### Depress the leather button on press

**Evidence.** `PaperButton` flattens its gradient and drops its shadow while pressed but never translates the face, unlike the playback keys. The interface specification asks every key to depress by 1-2 dp and tighten its shadow.

**First change.** Apply the same `graphicsLayer { translationY }` treatment under the same reduced-motion guard.

**Acceptance.** The main action reads as the same hardware family as the transport keys, and reduced-motion devices see no movement.

### Keep the place ribbon peek within 6–10 dp

**Evidence.** `BookCover` sizes the ribbon peek as `max(size * 0.11f, 8.dp)`: about 18.5 dp on the 168 dp detail cover and 22 dp on a 200 dp player cover, while the asset guide caps the peek at 6-10 dp. The oversized ribbon hangs far below large covers and dominates the artwork.

**First change.** Clamp the peek to the specified band regardless of cover size and keep the ribbon width proportional. As a separate prototype, map the tail length within that band to coarse progress while leaving the exact position in text. Keep it distinct from temporary marks and compare a static ribbon with the prototype in rows and on larger covers.

**Acceptance.** The row, detail and player ribbons all peek within 6–10 dp. Check portrait and wide artwork, cloth fallback, finished and new books, and 200% type.

### Group transfer state and actions at large text

**Evidence.** The API 37 200% Downloaded capture used the “Downloading” heading while the only transfer waited for a connection. Pause was a standalone line above Retry and Cancel. The compact layout replaces the ivory Pause key with text from 150%.

**Prototype.** Use a neutral group heading, keep progress with its state and put actions in one wrapping group. Compare a more compact ivory pause key with text, keeping a 48 dp target for either. Distinguish Cancel from the immediate actions and use the same role names in Library and Downloaded.

**Acceptance.** At 320 and 411 dp, all actions are reachable at normal and 200% text in queued, waiting, paused, failed and verifying states. TalkBack reads the state, progress and failure explanation once. Also verify state changes are announced politely without stealing focus.

### Reduce the space above the Library results

On the API 37 cached Library, the search box, sort line, offline message and Retry occupy several separate bands before the first book. Prototype one compact search/filter toolbar at ordinary text and a full-width rearrangement at accessibility sizes. Show the current sort and offer a discoverable Refresh action. Try pull-to-refresh through the same command without making the gesture the only way to refresh. Keep recovery text beside its action, avoid hiding the offline notice, and retain a first-load progress state. Validate the first screen's visible row count and reachability under TalkBack before adjusting spacing.

### Give Downloaded useful shelf controls

Prototype lightweight filters for Listening, New and Finished, plus an optional search field for a large local shelf. Provide a storage summary and a deliberate way to find large downloads to remove. Keep the current ordered shelf as the default, keep ongoing transfers visible, distinguish on-device availability from finished progress, and preserve saved places after deletion. Test with no server and pending continuity choices. Avoid bulk destructive actions until single-book removal and active playback behavior are clear.

## Accessibility and small polish

- **Merge the player utilities' semantics.** Speed, Chapters/Tracks/Parts and Sleep set a content description on the clickable column without merging descendants, so TalkBack may visit the label, value and icon separately after the description.
- **Reserve the mini-player's height.** `MainScaffold` measures the mini-player with `onSizeChanged` and applies bottom padding afterwards, so the list can jump for one frame when it appears or disappears. The measured heights are known (60/68 dp), so reserve them directly.
- **Keep the progress ring decorative.** The compact transfer ring repeats its percentage in visible text and semantics; the ring itself should stay cleared for TalkBack so the row reads once.
- **Check focus indicators over texture at high contrast.** The reports claim the 3:1 focus check; repeat it over the textured paper and cloth with the final composite.
- **Keep the search field's name and single line.** The hint disappears as soon as the query is non-empty, leaving only the pencil glyph and the semantics description. PR #65 already bounded the field to two lines from 150% text with newline sanitizing; the remaining idea is the persistent floating-label treatment of the paper fields, or a compact visible label.
- **Rotate the library selector's chevron when its menu is open**, as the disclosure rows already do.
- **Announce transfer state changes politely.** A transfer that moves between active, paused, waiting and failed while the Library list is on screen changes only a state description. Announce the phase once when it changes for a visible row, without stealing focus.
- **Refresh reduced-motion and high-contrast state while the process lives.** `PagewornTheme` reads both once per composition; toggling the animator duration scale or a high-text-contrast accessibility setting does not recreate the activity. Observe the change or refresh the flags on configuration change, and give the weakest secondary-ink lines an opaque backing in high contrast.
- **Mark the Connect screen's cleartext tradeoff with an icon.** The http-address warning is plain secondary-ink text; a small pencil lock or exclamation mark beside it would make the security choice scannable without breaking the system-form look.
- **Theme the system-owned surfaces.** Material time pickers, the exposed library dropdown and alert dialogs inherit the custom colors but keep the platform elevation and shadow language. A themed `TimePickerDefaults` and matching dialog container would close the gap with the paper-and-brass screens.
- **Richer empty-state typography.** Empty states are deliberately plain per spec; a larger first line plus a short supporting line would still fit without illustrations.
- **Give the Library header the same rule Downloaded has** (or record the asymmetry as deliberate): the two destinations share a row language but only Downloaded draws a rule under its heading.
- **Skip widget work for hidden covers.** `WidgetViews.reading()` decodes the cover bitmap even when the cover is `GONE` behind a notice, and rebuilds `RemoteViews` mappings for every placement; skip the decode and the hidden `setImageViewBitmap` calls.
- **Brief first-run explainer.** First run lands on Connect; a one-time note explaining Library vs Downloaded, the two text scales and the place ribbon would orient new listeners without changing the flow.

## Product gaps needing an owner decision

These change the written release scope, so agree on the behavior before building them. Launcher shortcuts, bookmarks and clips, per-book speed, pull-to-refresh, a Quick Settings sleep tile and a real dark theme are already listed under delight candidates.

### Storage usage at a glance

Settings shows per-book sizes in details but no total library size, no largest-first view and no remove-finished action. A total in Settings and a confirmed bulk-remove flow would make the storage margin easier to manage. Removal stays explicit: one confirmation naming what stays (saved places) and what goes (audio), and busy transfers stay out of the bulk path. The same total could explain the free-space margin before a download starts.

### Filters, series and grouping

Downloaded is a flat sorted list. All/unfinished/finished/new filters, series and collection headers, and author indexes would help a large library. A local search within Downloaded (title/author, no network) is the cheapest first step once many books are on the phone. Keep the documented sort stable while a filter is active, and keep equal titles independent by item ID.

### More sleep conveniences

A shake-to-extend gesture, an “add five minutes” action on a running timer and a persistent countdown outside the player sheet are small comforts. The sensor belongs in the playback service; every control must respect reduced motion and announce itself once. Preserve the current rules for fresh timers after a timed stop.

## Delight candidates

- Fine chapter-boundary ticks on the whole-book bar, drawn like pencil marks, to make the two scales' relationship visible without new chrome.
- Time-left on a ready Library row, matching the Downloaded badge ("Chapter 4 · 2 h 12 min left"), so the two sections read as one library.
- A soft page-turn settle when the player collapses back to the list, respecting reduced motion.
- A pressed shadow and haptic on the bookmark tabs, matching the keys.
- A short ribbon pulse on the mini-player when a new segment begins.
- A faint cloth spine shadow behind the standing covers on the Bookshelf widget.
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
- A Quick Settings tile that turns Sleep on with the chosen length or off through the existing session command.
- A launcher shortcut that resumes the last book at its saved place.
- A real dark theme later, adjusting every colour and material rather than swapping tokens.
- Brass-rimmed scrubber thumbs: a thin brass ring around each progress thumb, echoing the key rims while keeping the accent-versus-ink fill distinction.
- Deckled page edges: vary each page layer's corner radius and offset slightly and soften the graphite stroke, so the footer reads as hand-cut sheets rather than concentric rounded rectangles.
- A blind-stamped inset frame on cloth fallback covers, in a darker cloth tone, to give art-free books more craft without lettering.
- The selected bookmark's dashed pencil underline reused under a focused utility or selected radio, unifying selection cues for reduced-motion users.
- A brief place-ribbon settle on a cover when its download completes, tying the completion notice to the object the listener will look for.
- Chapter skip commands in the media notification overflow, so bedtime chapter turns do not need the phone unlocked.
- Previous/next chapter keys in the phone player itself (the car has them as overflow commands; the player currently reaches chapters only through the sheet).
- A native confirmation before removing a server address; a mis-tap in a wrapping action row currently revokes a paired alias.
- A brief chapter-title bookplate over the cover when a chapter begins, suppressed by reduced motion.
- A folded dog-ear on a cover once the book is finished, paired with the existing badge.
- A dated reading log drawn as pencil marginalia: finished and abandoned books, nothing leaving the phone.
- A subtle cover-tinted cloth accent, opt-in only, so the fixed palette remains the default.

## Playback investigation and design experiment

### Verify the track-boundary stop with audio output

The November 22 owner report records A10d reaching 92,967 ms at a 90,000 ms stop, while the saved place was exactly 90,000 ms and no byte of track B was read. A later widget run passed without audio output. The November 23 sleep-mode report records the API 31 failure suite passing, with the output configuration unspecified.

Run `PlaybackFailureTest#a10dATrackThatCannotOpenEndsPlaybackAtItsBoundary` on API 31 with audio output enabled, then with it disabled. Record controller position, last audible sample or sink event, reads from B and durable position. If the difference reproduces, establish which clock diverged before adjusting the controller or the test. Use the existing 90,500 ms controller limit.

### Let a listener undo a misplaced whole-book seek

A small movement on the book scrubber can cross hours of audio. Prototype a transient “Return to 2:14:36” action after a whole-book drag commits. A pencil tick can show the departure point without reusing the saved-place ribbon. Compare the text action alone with text plus tick.

Test an accidental seek, an intentional seek, repeated scrubs and switching books. Undo must return once to the last departure point, preserve play or pause, and never seek a different book. Check discoverability with TalkBack and agree on the lifetime with listeners before persisting any new state.

### Verify seek feedback under real playback callbacks

`ui/player/PlayerScrubbers.kt` releases the optimistic thumb position on any position change, including a normal playback tick that can precede a seek acknowledgement. Exercise large seeks in long VBR MP3s, across chapter boundaries, and during a 2× end-of-chapter fade. If the old thumb flashes, hold the target until a matching discontinuity or an explicit timeout. Define behavior for a rejected seek and a second drag. Keep exact-millisecond paused seeks and both TalkBack action ranges.

### Measure audio continuity when arming chapter sleep

For a boundary inside a track, `PlaybackController.armEndOf` rebuilds and prepares the media source immediately to prevent the next chapter from bleeding through the audio sink. The boundary test exercises arming close to the stop. Measure audible output or sink events when arming several minutes early in a long local file at 1× and 2×. If the first rebuild produces a gap, prototype a later clip with enough lead time to guarantee the existing 100 ms boundary tolerance. Check seeks, fade, Pause during the fade and focus suppression.

### Help a listener find where they drifted off

After a timed sleep stop, optionally present the final stop and the already supported rewind position as nearby marks on the chapter scale. Let the listener audition from either with a single tap, without changing the default rewind or the five-minute timer extension. Validate after process restart and when resuming through headphones. A separate visual prototype could use a restrained ring around the main key during the last few seconds, with a static cue for reduced motion. Keep the existing live countdown readable and avoid a second spoken announcement. Start with an in-app prototype and see whether the extra choice helps the morning journey.

### Show listening estimates at the chosen speed

Use the existing clock for the chapter and book positions. In the chapter sheet or below the current chapter, try a quiet “About 12 min to listen” estimate that responds to playback speed. Keep elapsed/total book time stable and label the estimate explicitly for TalkBack. Check meaningful rounding at short durations and consider the current sleep cut-off as a separate optional cue.

### Add a local margin mark

Offer a quick “Mark this moment” action in the player and a chronological list in details with time and optional note. Consider short local clips only after the simpler seekable mark proves useful. Store a mark with book key and edition fingerprint so the user can decide how to reuse a mark after the audio changes. Keep marks after removing audio, allow quick seek without surprising autoplay, and announce the confirmation without interrupting playback. Prototype the entry point with accessibility actions before adding another permanent player utility.

## Investigated and deliberately left unchanged

- **Volume after a faded timed stop.** The player keeps the faded volume after a timer expires and restores it in `beforePlay`. The existing fade test asserts that behaviour, and the contract's “restore full volume for the next start” is met there. A failure or focus loss during the fade also reaches the next start at full volume; changing this would need a test decision first.
- **Debouncing Library search.** A flow `debounce` in `LibraryViewModel` fails the Robolectric JVM tests because `shadowOf(mainLooper).idle()` does not advance delayed tasks, so the listing never arrives. If this is taken up, either teach the tests to advance the clock (`idleFor`) or throttle with a test clock rather than wall time.
- **A book of one part still has both bars, finished books stay finished until Replay, and an end-of-book seek within 250 ms marks the book finished.** These are specified and tested; recorded here only so they are not “simplified” later.
- **Both scrubbers already draw round caps**, with the smaller thumb on the book bar, and the time row lines up with the visible bar ends.
- **Catalog search escapes LIKE wildcards**, so `%`, `_` and the escape character match literally.
- **Progress writes are ordered by playback generation and sequence**, and a late pause sample cannot move a newer place backwards.
- **Player vertical distribution is correct**: the two weighted spacers around the transport row measure as equal gaps on an API 37 device.
- **End-of-chapter sleep stop stays EXACT**: the explicit write is protected by the seek discontinuity from the end-of-segment clip, so the pause does not overwrite it with ORDINARY on the common path.
- **Room schema, migrations, backup exclusion, foreground-service types and the network security configuration** match `docs/03-android-and-data.md`.

## Download and publication reliability

### Stop an active repair when its edition is removed

`EditionStore.remove` invalidates an unpublished repair in Room. The copy coroutine can continue requesting and writing tracks until its next guarded state update. Reproduce with a repair held mid-stream using a disposable slow response, then remove the edition from details and assert the HTTP body closes and the staged files disappear promptly. Make cancellation ownership explicit between `EditionStore` and `TransferCoordinator`. Keep the pending-removal and cancellation writes atomic, stop the matching writer before reclaiming staging, and cover both orders of a removal racing final publication. Preserve the saved place and keep other jobs runnable.

### Keep first-download order after publication recovery

The recovery path in `data/EditionStore.kt` can assign the recovery time as `publishedAt` to a finalized generation whose database commit was interrupted. Check whether the queue can reach a state where that timestamp changes never-played order. Extend the filesystem-finalized/Room-uncommitted fixture with two books and verify chronology under a reproducible restart sequence. If the original completion time needs to survive, persist it with the verified generation and allow older manifests that lack it.

### Verify interrupted publication before playback-only startup

Transfer startup and Activity startup recover editions after the filesystem finalize step. A media-button start can enter the Media3 service without either. Seed the existing finalized-but-uncommitted fixture, kill the process, and invoke a Bluetooth or media-session start while the Activity stays closed. Decide whether recovery can resolve the selected book without delaying starts of already published editions. Reuse the idempotent recovery path and protect active staging against reclamation.

## Test reliability

### Clear a stale test-results binary after a killed run

On this host, a Gradle run killed mid-test leaves `app/build/test-results/testDebugUnitTest/binary` in a state where the next `:app:testDebugUnitTest` fails with `EOFException`/`NoSuchFileException` while reading the previous results, before any test runs. `rm -rf app/build/test-results` or `:app:cleanTestDebugUnitTest` recovers. Worth remembering when a local run fails this way; CI is unaffected.

### Diagnose the intermittent concurrent refresh failure

During a full JVM run, `AddressSessionTest.fiveConcurrentUnauthorizedCallsShareOneRefresh` failed its all-success assertion once and passed when rerun alone. The test waits up to five seconds inside the mock server for five old-token requests. Its assertion hides which request failed and why.

Capture the redacted result category for each synthetic request, the latch timeout result, refresh count and arrival order. Repeat under parallel test load. If the barrier races a client timeout, coordinate the fixture deterministically. If renewal itself races, fix the session and retain a reproducer. Acceptance requires five successful calls, one refresh and one rotated token pair, with credentials excluded from logs.

### Make late renderer callbacks explicit in controller tests

PR #44 added a fake player whose `isPlaying` change arrives after the application-thread pause, which is what a device does. Reuse that technique for other ordering-sensitive cases: a failure arriving during the fade, a seek completing after a pause, a timer expiring during a transient interruption, and the case where no `isPlaying` callback follows at all.

**Acceptance.** Every stop-kind and volume assertion observes the final persisted state, not merely the first write Room emits.

### Isolate device-wide configuration in the visual suite

The API 37 visual checks passed 6 of 6. The 200% text test timed out in its setup waiting for the Activity font scale/night mode after running VisualChecksTest in the same invocation. It passed in isolation. Repeat both test orders to find the shared state. Capture shell output and the resource snapshot on failure, restore original mode and scale, and scope device mutations to disposable emulators. Gradle's connected task on this host can print success even when its internal adb invocation cannot see a private device. Require the runner's actual test count.

## Review evidence

The review built the APK under JDK 21.0.13 with SDK platform 37.0 and compared the live cached Library, player and 200% Downloaded on a disposable API 37 emulator at 1080 × 2400 px and 420 dpi with the mockups and archived captures. The API 34 audit passed eight progress-order and seven playback-failure tests. Its lock-screen assertions timed out waiting for the emulator’s keyguard. Physical audio, TalkBack speech, frame timing, the owner's server and car behavior still need direct verification. Paths such as `ui/detail/BookDetailScreen.kt` are relative to `app/src/main/java/ch/lkmc/pageworn/`.
