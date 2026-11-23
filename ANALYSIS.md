# Pageworn improvement backlog

These tasks build on the offline-first player, its paper surfaces, Spectral titles and oxblood place ribbon. The November 2026 review used the implementation at `9e4be6d`, later main revisions, the gallery images and a synthetic library on an API 37 emulator. Each task includes a way to decide whether the change helps.

## Implemented in open pull requests

- **Keep chapter work off the playback tick.** PR #34. `PlayerStates.player` now carries the timeline's own segment list and current index instead of formatting every chapter row four times a second; the lazy sheet formats only visible rows. The regression test asserts two live states share one list while the index advances.
- **Name the state of every library transfer.** PR #35. Library rows pair the progress icon with the state word at every text size, not only from 150 percent, so paused and waiting downloads no longer read as bare rings.
- **Protect writes during cover cache eviction.** PR #25 limits eviction to published thumbnails and reclaims abandoned temporary files on the next process's first write.
- **Return from missing book details.** PR #27 distinguishes loading from a missing book. An API 37 removal test checks the return to Downloaded and the saved place.
- **Retry a download in place.** PR #28 adds Retry to Library and details while retaining Pause and Cancel where available.
- **Mark a saved place on the details cover.** PRs #29 and #38 show the ribbon for Resume and leave fresh and finished books unmarked.
- **Bound cover decoding.** PR #30 samples against the longer edge across Compose, cached remote art and widgets. A 256 × 8192 portrait decodes to 8 × 256 for a 128 px target.
- **Verify formats with the manifest codec.** PR #39 threads the Audiobookshelf codec into the post-download verifier, so MP3 frames inside a CAF container reach the probe instead of being refused after the bytes were fetched. A `TrackProbe` test double covers the gate on the JVM.
- **Hold Downloaded rows to the checkpoint clock.** PR #43 strips volatile playback fields and holds the moving position to five seconds before combining, so a playing book no longer rebuilds every row four times a second. Transport changes still pass through at once.
- **Keep a published cover on a transferring row.** PR #46 prefers the edition's stored cover during a repair or replacement, falling back to the server thumbnail only when no local file exists.
- **Select the sleep length that is actually running.** PR #53 selects a carried-over timer, or a running length whose preset was removed, and lists that length while it runs so exactly one radio carries the countdown.
- **Settle Library search before querying.** PR #58 debounces non-empty queries by 200 ms while keeping the field immediate, so a large catalog is not re-searched and re-sorted once per character.
- **Keep an end-of-chapter sleep stop exact.** PR #44. ExoPlayer reports the pause inline but `isPlaying` false later, so the pause checkpoint could store an ordinary stop over the exact boundary and turn a rested resume into a ten-second rewind. The controller arms the exact pause kind before pausing; the regression test defers that callback and fails on the old code.
- **Read covers and file checks off the main thread.** PR #49. The System UI resumption control no longer reads up to 2 MB of cover bytes on the main thread, and opening a book verifies track sizes on the IO dispatcher rather than during media-item resolution.
- **Keep an incomplete first load in the status line.** PR #52. The staged rows stay browsable and Retry sits between the heading block and the rows, rather than in the centred empty-state form.
- **Disable detail Download after a refusal.** PR #54. An unsupported format or a per-book download denial replaces the primary action with the specified disabled action while the refusal lasts. The pre-tap verdict below remains open.
- **Treat an unreadable library list as a sign-in failure.** PR #56. A failed `/api/libraries` read revokes the fresh session and reports the address problem instead of storing an empty profile or claiming a different server.
- **Draw focus rings outside keys and paper rows.** PR #57. The key ring surrounds the face, and text actions and disclosure rows gain the same pencil outline as the other controls.
- **Say that a removal keeps the place.** PR #60. The long-unused `AUDIO_REMOVED` copy now appears under the detail action until a download starts or an edition returns.
- **Refresh the catalog after signing in again.** PR #37 makes `SIGN_IN` refresh libraries and the catalog like the other reconnect modes, so a cached Library cannot stay stale behind a restored session.
- **Coarsen Downloaded's live position.** PR #42 floors a playing row's place to the minute and skips unchanged live states; PR #43 holds it to the checkpoint clock. Both replace the four-per-second rebuild of every row.
- **Bound the plan cache.** PR #45 turns `PlanCache` into an access-ordered LRU of 16 generations instead of an unbounded map.
- **Load progress once for the car's book list.** PR #50 replaces the per-edition `book_progress` query in `readyBooks()` with one `ProgressDao.all()`.
- PR #48 overlaps PR #35 (transfer state text); keep one when they merge.

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

PR #45 bounds this map as an access-ordered LRU of 16 plans. The remaining work is the same lifetime question for the other tables below.

The same lifetime question applies to `EditionStore.bookLocks` and
`ProgressStore.handedOut`, which keep one mutex or session generation per book
forever, and to `ServerAccess.connections`, which is replaced rather than
evicted when a policy changes.

**First change.** Give book locks and handed-out session counters the same bounded lifetime: keep them while a book is reachable in the session, then let them go. No behavior change is needed beyond memory.

### Rebuild Downloaded rows only when their inputs change

**Evidence.** `DownloadedViewModel.state` combines the full `playbackHub.state` and is shared eagerly. Every 250 ms playback tick walks all editions through `plans.timelines` and rebuilds every transfer and book row, even while another screen is showing. Library deliberately feeds `transportOnly()` with `distinctUntilChanged` for the same reason.

**First change.** PRs #42 and #43 coarsen or hold the position and skip unchanged states. Still open: switch the flow to `WhileSubscribed` when no screen needs it, cache timelines against the edition generation set, and measure allocations during a minute of playback with the player closed.

**Acceptance.** Starting or pausing a book updates the row keys and badge, a resting row keeps its order, and a tick inside a chapter performs no timeline lookups or row rebuilds.

## State and lifecycle correctness

### Honour the remembered section on a reachable cold launch

**Evidence.** `MainViewModel` selects `Destination.DOWNLOADED` as soon as one local edition exists, before any network decision. `docs/01-product.md` says a saved profile remembers the last main section, and reserves the Downloaded cold launch for an unreachable catalog with local books. A listener who last left Library on a healthy server is dropped into Downloaded after every restart.

**First change.** Start on the remembered destination and move to Downloaded when the launch refresh reports unreachable and local books exist. Keep the no-blocking rule: the local list must render before the network answer.

**Acceptance.** Cold launch with a cached catalog and local books opens the remembered section. Cold launch with an unreachable server and local books still opens Downloaded, as A17 requires, and shows the cached catalog with its Retry. Warm starts are unchanged.

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

**Acceptance.** The documented rule and the stored `stopKind` agree, and a deliberate chapter choice still never rewinds.

### Do not flash the empty state before Room emits

**Evidence.** `DownloadedViewModel.state` starts as an empty `DownloadedUiState`, so the no-downloads empty state can appear for a frame before Room emits, even on a phone full of books. `LibraryUiState` has the same shape. This is cosmetic but visible on a cold launch.

**First change.** Suppress the empty state until the first editions/transfers emission, or seed the initial state from a quick local read. Keep the true empty state immediate when there really is nothing.

**Acceptance.** A cold launch with downloads never shows the no-downloads sentence; a phone with none still does.

## Details correctness

### Refuse an unsupported format before the tap

**Evidence.** `DetailViewModel.build` can return `Download`, `DownloadDisabled`, `Repair` or `Play`, but never `DetailAction.Unsupported`; `LibraryViewModel` passes `unsupported = false`. The refusal appears only after a tap, when the coordinator reads the manifest. Spec A24b wants details to disable Download with the unsupported-format copy, and the Library row to show it without starting a job. The pinned catalog list response carries no codec or extension, only media metadata, so this needs either stored format summaries captured at item fetch or an on-demand manifest read in details.

**First change.** Decide between an item-detail fetch cached by profile, item and revision, and carrying format information in a schema change. Then have details and the Library row share one verdict. Never mark a book unsupported because the network was unavailable.

**Acceptance.** Opening an unsupported book shows the disabled action before any tap, starts no job and requests no audio. An offline previously downloaded book still opens and plays. A cached verdict cannot outlive the item's revision. PR #54 covers the after-tap disabled state; this entry is the pre-tap verdict.

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

### Recompute local-network permission state when Addresses resumes

**Evidence.** `AddressesViewModel` computes `denied` inside its `combine` transform, so the answer changes only when addresses, test results or the pin emit. Granting the Android 17 local-network permission in system settings and returning to the app changes none of them; the row keeps the denied message and the Allow-access action even though a request would now succeed.

**First change.** Expose permission state as an observed flow, or recompute on screen entry and resume. Keep the DNS classification off the main thread as it is today.

**Acceptance.** Deny, open Library and see the local-network message; grant in system settings, return, and the message clears without tapping Test. On Android 12 to 16 nothing is requested.

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

### Distribute spare height in the player

**Evidence.** Source reading suggests the two `Spacer(Modifier.weight(1f))` calls inside the scrolling player column might collapse: a vertical scroll passes children an unbounded maximum height, and Compose derives weighted space from the minimum constraint in that case. A measurement on an API 37 emulator (1080 × 2400 at 420 dpi) found the opposite: the chapter times end at y ≈ 1539, the transport row occupies 1733–1943 and the utilities 2137–2295, two equal 194 px gaps, so the weights did distribute. Re-measure with `uiautomator` or compose bounds on a regular-height device before changing this; if a device shows collapse, split the player into a scrolling upper region and a fixed lower control stack, or insert explicit spacers only when the natural content fits.

**Prototype.** If measurement confirms collapse on any supported device, split the player into a scrolling upper region and a fixed lower control stack. Keep every control row scrollable at 200% text and in short landscape.

**Acceptance.** On a regular-height phone the transport keys and utilities keep their designed separation and no large empty area sits below the utilities. At 200% text the same rows can still be brought into view, and a short screen collapses the artwork first.

### Depress the leather button on press

**Evidence.** `PaperButton` flattens its gradient and drops its shadow while pressed but never translates the face, unlike the playback keys. The interface specification asks every key to depress by 1-2 dp and tighten its shadow.

**First change.** Apply the same `graphicsLayer { translationY }` treatment under the same reduced-motion guard.

**Acceptance.** The main action reads as the same hardware family as the transport keys, and reduced-motion devices see no movement.

### Keep the place ribbon peek within 6-10 dp

**Evidence.** `BookCover` sizes the ribbon peek as `max(size * 0.11f, 8.dp)`: about 18.5 dp on the 168 dp detail cover and 22 dp on a 200 dp player cover, while the asset guide caps the peek at 6-10 dp. The oversized ribbon hangs far below large covers and dominates the artwork.

**First change.** Clamp the peek to the specified band regardless of cover size and keep only the ribbon width proportional.

**Acceptance.** The row, detail and player ribbons all peek within 6-10 dp, and the saved-place mark still reads as part of the book.

### Keep the transfer pause key at large text

**Evidence.** At 150% text `DownloadedScreen` replaces the ivory transfer pause key with a plain text "Pause" action, although the asset guide prefers shrinking drawn art before replacing controls. The drawn control language disappears exactly at the size the design checks.

**Prototype.** Keep the 32 dp ivory key in its 48 dp target beside or below the metadata at compact sizes, and let the action row wrap without replacing it.

**Acceptance.** A paused download can be paused from both Library and Downloaded at 100% and 200% text with the same visual control, and the row does not overflow a 320 dp screen.

## Accessibility and small polish

- **Merge the player utilities' semantics.** Speed, Chapters/Tracks/Parts and Sleep set a content description on the clickable column without merging descendants, so TalkBack may visit the label, value and icon separately after the description.
- **Reserve the mini-player's height.** `MainScaffold` measures the mini-player with `onSizeChanged` and applies bottom padding afterwards, so the list can jump for one frame when it appears or disappears. The measured heights are known (60/68 dp), so reserve them directly.
- **Keep the progress ring decorative.** The compact transfer ring repeats its percentage in visible text and semantics; the ring itself should stay cleared for TalkBack so the row reads once.
- **Check focus indicators over texture at high contrast.** The reports claim the 3:1 focus check; repeat it over the textured paper and cloth with the final composite.
- **Keep the search field's name and single line.** The hint disappears as soon as the query is non-empty, leaving only the pencil glyph and the semantics description, and the field becomes multi-line at 150% text. Give it the persistent floating-label treatment of the paper fields, or a compact visible label, and pin `singleLine = true`.
- **Announce transfer state changes politely.** A transfer that moves between active, paused, waiting and failed while the Library list is on screen changes only a state description. Announce the phase once when it changes for a visible row, without stealing focus.
- **Refresh reduced-motion and high-contrast state while the process lives.** `PagewornTheme` reads both once per composition; toggling the animator duration scale or a high-text-contrast accessibility setting does not recreate the activity. Observe the change or refresh the flags on configuration change, and give the weakest secondary-ink lines an opaque backing in high contrast.

## Product gaps needing an owner decision

These change the written release scope, so agree on the behavior before building them. Launcher shortcuts, bookmarks and clips, per-book speed, pull-to-refresh, a Quick Settings sleep tile and a real dark theme are already listed under delight candidates.

### Storage usage at a glance

Settings shows per-book sizes in details but no total library size, no largest-first view and no remove-finished action. A total in Settings and a confirmed bulk-remove flow would make the storage margin easier to manage. Removal stays explicit: one confirmation naming what stays (saved places) and what goes (audio), and busy transfers stay out of the bulk path. The same total could explain the free-space margin before a download starts.

### Filters, series and grouping

Downloaded is a flat sorted list. All/unfinished/finished/new filters, series and collection headers, and author indexes would help a large library. Keep the documented sort stable while a filter is active, and keep equal titles independent by item ID.

### More sleep conveniences

A shake-to-extend gesture, an “add five minutes” action on a running timer and a persistent countdown outside the player sheet are small comforts. The sensor belongs in the playback service; every control must respect reduced motion and announce itself once. Preserve the current rules for fresh timers after a timed stop.

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
- Brass-rimmed scrubber thumbs: a thin brass ring around each progress thumb, echoing the key rims while keeping the accent-versus-ink fill distinction.
- Deckled page edges: vary each page layer's corner radius and offset slightly and soften the graphite stroke, so the footer reads as hand-cut sheets rather than concentric rounded rectangles.
- A blind-stamped inset frame on cloth fallback covers, in a darker cloth tone, to give art-free books more craft without lettering.
- The selected bookmark's dashed pencil underline reused under a focused utility or selected radio, unifying selection cues for reduced-motion users.
- A brief place-ribbon settle on a cover when its download completes, tying the completion notice to the object the listener will look for.
- Chapter skip commands in the media notification overflow, so bedtime chapter turns do not need the phone unlocked.
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

## Investigated and deliberately left unchanged

- **Volume after a faded timed stop.** The player keeps the faded volume after a timer expires and restores it in `beforePlay`. The existing fade test asserts that behaviour, and the contract's “restore full volume for the next start” is met there. A failure or focus loss during the fade also reaches the next start at full volume; changing this would need a test decision first.
- **Debouncing Library search.** A flow `debounce` in `LibraryViewModel` fails the Robolectric JVM tests because `shadowOf(mainLooper).idle()` does not advance delayed tasks, so the listing never arrives. If this is taken up, either teach the tests to advance the clock (`idleFor`) or throttle with a test clock rather than wall time.
- **A book of one part still has both bars, finished books stay finished until Replay, and an end-of-book seek within 250 ms marks the book finished.** These are specified and tested; recorded here only so they are not “simplified” later.
- **Both scrubbers already draw round caps**, with the smaller thumb on the book bar, and the time row lines up with the visible bar ends.
- **Catalog search escapes LIKE wildcards**, so `%`, `_` and the escape character match literally.
- **Progress writes are ordered by playback generation and sequence**, and a late pause sample cannot move a newer place backwards.

## Test reliability

### Diagnose the intermittent concurrent refresh failure

During a full JVM run, `AddressSessionTest.fiveConcurrentUnauthorizedCallsShareOneRefresh` failed its all-success assertion once and passed when rerun alone. The test waits up to five seconds inside the mock server for five old-token requests. Its assertion hides which request failed and why.

Capture the redacted result category for each synthetic request, the latch timeout result, refresh count and arrival order. Repeat under parallel test load. If the barrier races a client timeout, coordinate the fixture deterministically. If renewal itself races, fix the session and retain a reproducer. Acceptance requires five successful calls, one refresh and one rotated token pair, with credentials excluded from logs.

### Make late renderer callbacks explicit in controller tests

PR #44 added a fake player whose `isPlaying` change arrives after the application-thread pause, which is what a device does. Reuse that technique for other ordering-sensitive cases: a failure arriving during the fade, a seek completing after a pause, a timer expiring during a transient interruption, and the case where no `isPlaying` callback follows at all.

**Acceptance.** Every stop-kind and volume assertion observes the final persisted state, not merely the first write Room emits.
