# Android architecture and durable state

## Runtime ownership

Use a single Android application module to start. Organize by feature or by the boundaries below, following the host repo’s conventions. Use Kotlin coroutines and immutable UI state. The UI observes Room and the playback controller. A composable going off-screen must never cancel an intentional book download or a sleep timer.

| Boundary | Owns | Exposes |
|---|---|---|
| Compose screens and ViewModels | Navigation, input, transient selection, presentation of errors | User intent such as download, resume, remove or set sleep |
| Catalog and address resolution | Account-scoped catalog refresh, approved origins, route choice, authentication | Paged book summaries, last successful refresh, manifest acquisition |
| Transfer coordinator | Durable queue, partial files, retries, one active job per book | Per-book progress and a verified local edition |
| Media session service | Local playback engine, audio focus, headphone and lock-screen commands, sleep deadline | One source of live player state for system controls and UI |
| Book timeline and progress store | Time conversions, chapter lookup, checkpoints, finished state | Resume position and status for each book |
| Persistence | Room for structured state, app-private storage for media, Keystore-backed token encryption | Transactional updates and recoverable files |

Start with Compose Navigation, Room, OkHttp, a JSON decoder tolerant of unknown fields, and AndroidX Media3. Use the repo's dependency catalog and pin resolved versions. Inject a clock, local file store and network transport where tests need deterministic faults. The player takes only committed local file handles. Keep HTTP libraries outside its playback data path.

## Identity and schema

Treat server IDs as opaque strings. Use 64-bit integers for times in milliseconds and for byte counts. Use a local profile UUID as the stable root for one server and account. A URL identifies a route. The book key is `(profileId, itemId)`, including when library metadata changes or titles collide.

| Record | Keys and minimum fields | Lifetime |
|---|---|---|
| Profile and Address | Profile ID, server account ID, pairing evidence. Address ID, profile ID, normalized origin and path prefix, label, priority, enabled, last success, credential reference | Pairing an alias adds an address to the profile. Token material stays out of Room. |
| Library and CatalogBook | Profile and library ID, item ID, title, author, narrator, artwork cache key, duration, last refresh generation | Refresh a complete generation before removing stale remote entries. Downloaded editions survive catalog changes. |
| LocalEdition | Book key, revision fingerprint, generation ID, state, duration, private relative directory, cover reference | Exactly one published generation per book. Stage a replacement separately until verified. Snapshot display metadata with the edition. |
| Track and Chapter | Generation ID and ordinal, source file ID, source revision, file size, local filename, start and duration. Chapters store title, origin, start and end | A generation pins ordering, offsets, chapter map and media bytes together. Chapter rows can span track boundaries. |
| Transfer | Book key, job ID, staging generation, manifest fingerprint, state, failure category, per-file byte count and validator | Unique active job per book. Workers reopen by ID and reconcile the journal with actual file lengths. |
| BookProgress | Book key, absolute position, duration at save, edition fingerprint, finished flag, update time | Retained on transfer cancellation and audio removal. A new item with the same title has a different key. |
| Playback settings | Last active book, chosen speed, last destination, theme preference if exposed | Lightweight preferences. A timed sleep request belongs to the active playback service. |

Keep source metadata, credential material and display metadata separate. Avoid storing server absolute filesystem paths. Generate local paths from internal IDs and sanitized file extensions. Keep audio under persistent app-private storage, outside cache and OS backup. Exclude credentials and device-bound transfer state from backup. Cache artwork can be evicted. Copy a thumbnail into the committed edition so Downloaded still renders offline.

Define the edition fingerprint as a digest of the ordered source file IDs, sizes and modification times, the ordered track durations and the chapter map. Cover, title, author and description changes leave it intact; reordering, replacing or re-encoding audio changes it. Rule 7 uses this fingerprint to tell a metadata refresh from a material audio change.

## Persistence and recovery rules

1. Enqueue under a unique book key. Repeated taps attach to the existing transfer. Journal file lengths for display, but use the bytes on disk when deciding where to resume. Run one active book at a time by default; a user-paused book yields the slot to the next queued book and resumes when the listener resumes it.
2. Write all audio, a compact manifest, and optional cover to a staging directory on the same volume as the final edition. Missing artwork uses the cloth fallback. Missing mandatory tracks keep the edition in staging.
3. Verify every file as defined in the [transfer contract](04-server-and-downloads.md), finish the chapter map, and atomically rename the directory. In a Room transaction point the published edition at that directory, install its track/chapter rows, and mark the transfer complete. The filesystem rename and the database transaction have separate failure boundaries.
4. On startup or before opening a book, recover an interrupted publish from the finalized manifest and generation ID. Promote only a complete matching generation. Quarantine or remove abandoned staging that belongs to a cancelled job. A missing published file changes state to Repair needed while keeping progress.
5. During replacement, readers keep using their current edition until playback releases it. Clean up a superseded generation after no session holds it. For explicit removal, pause the book, checkpoint, mark removal pending, delete audio, then clear the edition rows. Retry unfinished cleanup after process death. A second removal is harmless.
6. Save immediately on pause, seek, book switch, timer expiry and completion. While playing, checkpoint at least every five seconds. Serialize writes from the service by book and playback generation. A delayed callback from the previous book or generation must never overwrite newer progress. Intentional rewinds must persist.
7. For the same edition, restore the saved time and clamp to duration. For reordered or materially changed audio after re-download, retain the old position and offer **Use previous position** or **Start over** before first play. Show the old time and new duration. A cover or description edit alone leaves playback continuity intact.

## Android integration

- Use a `MediaSessionService` and a media session for playback. Declare the appropriate playback service type and permissions for the target SDK. The activity binds via a controller and releases it on lifecycle changes. Only a user play action starts or resumes audible playback.
- Downloads are user-initiated, durable work. On Android 14+ prefer a user-initiated data transfer job for a long foreground-requested transfer. On older supported versions use a foreground-capable worker with a progress notification. Both paths use the same queue, storage contract and cancellation semantics. Recover a stopped job through its recorded state under platform scheduling rules.
- Keep audio, editions and device-bound transfer state out of OS backup with `fullBackupContent` and `dataExtractionRules`; cache artwork may be backed up or evicted. Keep tokens encrypted using a key protected by Android Keystore. Replace refreshed token pairs together. Redact credentials, URL queries and book filenames from network diagnostics. Reauthentication leaves local media accessible to the paired profile.
- Record the target SDK and the resulting local-network permission behavior in M0. Android 16 uses `NEARBY_WIFI_DEVICES`; Android 17 (target SDK 37) uses `ACCESS_LOCAL_NETWORK` and blocks local network access by default. Do not declare the Android 17 permission when targeting SDK 36 or lower.
- For an explicit cleartext origin, scope the allowance as narrowly as the platform allows and record the tradeoff. Never let a cleartext allowance weaken certificate validation for HTTPS origins or send credentials to an unpaired destination.
- Migrate the Room schema additively. Run an upgrade test with an existing download and saved place before shipping a schema change. A schema mismatch must never trigger silent deletion of the listening library.

## References

- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
- [user-initiated data transfer](https://developer.android.com/develop/background-work/background-tasks/uidt)
- [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
