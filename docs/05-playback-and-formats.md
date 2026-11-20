# Local playback, chapters and sleep

## The book is the unit of listening

The playback service owns a single active book. The UI sends commands to that service and observes its state. System media controls use the same command path. Switching books checkpoints the old position, releases its timer, and restores the new book’s position. A collapsed activity, locked screen or disconnected server has no effect on the local timeline.

Use Android speech audio attributes, audio focus and standard media-session facilities. Pause on permanent focus loss or headphone disconnect. After a transient interruption, resume only if playback was active and neither the listener nor the timer paused it in the interim. Reattaching headphones or reopening the activity alone must leave a paused book paused.

## A timeline independent of files

Convert server seconds to 64-bit milliseconds once at import, rounding to the nearest millisecond. Order tracks by the server’s playable sequence. Prefer its valid book-relative offsets and cross-check with file durations. When offsets are missing, construct contiguous starts from the ordered verified durations. Normalize rounding discrepancies of a few milliseconds consistently. Investigate material gaps, overlaps or unknown duration using the downloaded files before publishing.

Use half-open intervals `[start, end)`. An internal boundary belongs to the following track or chapter. The book end is a distinct finished state. A global seek maps to the track containing the requested position and a track-local offset. For a chapter-relative seek, add the chapter start before mapping. A track-local timestamp becomes a book position by adding the track start. With the single-item player described under [system controls](#system-controls-and-resumption), the player already reports book time and this mapping serves chapter lookup, persistence and diagnostics. Clamp all external seek requests to the edition’s duration. The same mapping resolves every timer and scrubber decision: a boundary callback at an internal interval edge selects the following track or chapter at offset zero and commits that edge as the saved position.

| Example fixture | Value |
|---|---|
| Track A | 0–90 s |
| Track B | 90–210 s |
| Chapter 1 | 0–75 s |
| Chapter 2 | 75–140 s |
| Chapter 3 | 140–210 s |

In this fixture, chapter 2 at +40 s is book position **115 s**, or **25 s into track B**. Seeking to exactly 90 s selects track B at zero. End-of-chapter sleep armed at 115 s must pause at book position 140 s, which is 50 s into B. Reopening shows chapter 3 at its start. At 210 s mark the book finished and offer Replay from the beginning.

## Chapter map and fallback

1. Use book-level Audiobookshelf chapters first, but validate them against the edition’s verified duration before storing: drop intervals that start outside `[0, duration)`, drop empty intervals, trim overlapping ends to the next start, deduplicate identical starts, and clamp an overflowing end to the book end. This preserves chapter edits made on the server while keeping a stale or malformed server list from breaking navigation or the sleep timer. Store the validated map in the local edition so navigation works without a network.
2. When the validated server chapter list is empty or unusable, import valid embedded chapter markers if available. Convert per-file times to absolute times, normalize them, and apply the same validation as step 1. Keep available titles. Fill a missing title with “Chapter 1”, “Chapter 2”, and so on.
3. Tolerate missing end times by using the next valid start or the book end. Preserve uncovered audio as a navigable segment: label audio before the first chapter “Opening” and any later gap, including one after the last chapter, “Interlude”. Store the segment with a generated origin. It behaves as a chapter everywhere: it is listed in Chapters, bounds the chapter scrubber, and end-of-chapter sleep armed inside it stops at its end. Never merge uncovered audio into a neighboring chapter. Never combine server and embedded markers when the validated server map is usable.
4. If markers are unavailable, list a multi-file book as **Tracks** using track titles or ordinals. For a single file show a whole-book timeline. Offer **End of track** or **End of book** in the corresponding sleep sheet. Never display invented chapter titles as author-supplied metadata.

## Media format acceptance

“All formats” applies to DRM-free audiobook tracks admitted by the chosen Audiobookshelf release. Support includes local decode, reliable book-wide seeking and chapter navigation where markers exist. A filename suffix alone cannot establish any of those properties. Inventory the server scanner in M0 and evaluate container, actual codec, seek layout and chapter source separately. A matrix row without evidence is an open compatibility item.

| Seed fixture family | What to exercise |
|---|---|
| M4B and M4A in MP4 | AAC single-file M4B with titled embedded chapters, chapterless M4A, and ALAC-in-MP4 where the server admits it |
| MP3 | Constant and variable bit rate, long file with late seeks, multi-file edition with chapters independent of track boundaries |
| AAC elementary stream | ADTS without an index, long-range seek, duration recovery |
| Ogg | Vorbis and Opus, including an `.opus` file, and tag-derived chapters if the library supplies them |
| FLAC and WAV | FLAC with cue/metadata chapters when available, PCM WAV if recognized by the server |
| WebM or Matroska | Audio-only tracks with admitted extensions, local duration and chapter seek |
| ASF/WMA and remaining recognized families | Include a decodable WMA sample and every additional family discovered in the pinned scanner. Exercise fallback on the minimum and current Android test devices. |

Begin with Media3 ExoPlayer playing local files. Measure seek accuracy, start latency and chapter behavior on real devices. If a valid source cannot be extracted, decoded or accurately sought, implement a **local compatibility path**. The proposed fallback is an on-device importer using a maintained native demuxer/decoder. It produces a seekable local rendition before marking the edition ready. Remux where possible and convert only when required. Carry the original chapter map and verify that output duration, encoder delay and offsets preserve the book timeline. Budget temporary storage and expose a **Preparing audio** substate under Verifying. Confirm distributed binary provenance, supported ABIs and redistribution terms before choosing the native build.

If a single local engine passes the entire matrix more simply, document that decision and use it behind the same service contract. A valid recognized format that still fails keeps coverage open. Do not mark the format requirement delivered by hiding the item or by falling back to a server stream. Damaged files and DRM-protected files get an actionable explanation and preserve the saved place.

For each row, record a redistributable or generated fixture ID, extension, container, codec, chapter source, server version, device/API, engine, actual resume error, late-seek error, and outcome. Use identifiable audio cues around boundaries so the test measures what was heard as well as the timestamp the decoder reports.

## Transport, progress and completion

- Start new books at zero and resumed books at the stored global offset. Support 15-second rewind, 30-second advance, Play/Pause, whole chapter selection, and the chapter scrubber. Clamp skips at the book edges. The slider spans the book when it has no chapter or track divisions.
- Provide 0.75×, 1×, 1.25×, 1.5×, 1.75× and 2×. Persist the last selected speed. Progress stays in media time at all speeds. Sleep duration uses elapsed wall time.
- Persist on explicit seek, pause and other checkpoints in the [storage contract](03-android-and-data.md). The maximum intended loss after unexpected process death during continuous playback is five seconds on the book timeline at every supported speed, plus the measured seek error when playback resumes. A deliberate pause or completed seek should resume within the format’s measured seek tolerance.
- Mark finished on natural end, an explicit seek to book end, or a sleep stop at book end. Listening to an intermediate chapter end continues into the next chapter unless a timer requests a pause. Replay resets position and finished status through one transaction.

## System controls and resumption

The media notification, the lock screen, and wired and Bluetooth headsets all act through the one media session. Present the book to them, not its files. These rules need Media3 1.9 or later and were checked against 1.11.1. Several of the APIs below are marked unstable and need the opt-in annotation.

- Give the player each book as one `MediaItem` whose media ID is the book key. Resolve it in the player’s `MediaSource.Factory` to a `ConcatenatingMediaSource2` over the edition’s ordered local tracks, passing each verified track duration as its placeholder duration. The player position is then book time, system surfaces show whole-book progress, and a seek across a file boundary stays within one item.
- Media3 maps headset Next to `seekToNext`, Previous to `seekToPrevious`, and a double press of Play/Pause on a headset to `seekToNext`. With one book item and repeat mode off, Next does nothing and Previous seeks to the start of the book, which the next checkpoint would save as the listener’s place. Wrap the player in a `ForwardingPlayer` that reports both commands as available, because the session drops Next when the player lacks `COMMAND_SEEK_TO_NEXT`, and turns Next into forward 30 s and Previous into back 15 s, matching the player’s own buttons. Also set ExoPlayer’s seek-forward and seek-back increments to the same values, and keep repeat mode off. Chapter changes stay in Chapters.
- Fill the back and forward slots of the session’s media button preferences with back 15 s and forward 30 s (`CommandButton.ICON_SKIP_BACK_15` and `ICON_SKIP_FORWARD_30`) so the notification and lock screen show them instead of track skips. Each system surface still decides which buttons it shows.
- Declare Media3’s `MediaButtonReceiver` and implement `MediaSession.Callback.onPlaybackResumption`. Return the last active book at its saved position with the persisted speed, or only its metadata when `isForPlayback` is false. A Play press on a Bluetooth headset after process death then resumes the book without opening the app. That press is a user play action. On Android 12+ the exemption that lets this press start the service is documented for Bluetooth only. A wired press may be rejected, and the app then stays paused with accurate state.
- The System UI resumption control appears only for a `MediaLibraryService`. The plan’s `MediaSessionService` does not offer it, and adopting a library service is a later decision.

## Playback failure handling

A decoder, demuxer or read failure during playback is distinct from a damaged edition found before opening. When a track cannot be initialized, decoded or read:

- Pause at the last successfully decoded checkpoint and persist that book position. The loss stays within five seconds of book time at every supported speed, plus the measured seek error when playback resumes. Never skip past the failing audio into a later chapter or track.
- A track that cannot be opened fails only when playback reaches it. Play the current track to its end and save the boundary itself. Under the half-open rule that position is the failing track at offset zero, and none of its audio has played. If a sleep stop lands on the same boundary, the timer fires first and completes normally, and the failure surfaces on the next play. A seek or resume that lands inside a track which cannot be opened plays nothing from it: a failed seek keeps the position saved before the seek, and a failed resume keeps the saved place.
- Set the edition to **Repair needed** while leaving its saved place, duration snapshot and finished flag unchanged. Do not mark the book finished.
- Never fall back to a server stream or hide the item. Surface the “Audio stopped during playback” copy from the [product brief](01-product.md).
- Retry a transient read error once from the same position. If the retry also fails, pause as described above. Unsupported codecs and DRM-protected files keep the existing format rules and preserve the place.
- After repair or re-download, restore the saved position when the edition fingerprint still matches. Otherwise use the previous-position choice required for materially changed audio.
- The failure pause clears any armed sleep timer and cancels any pending auto-resume, as the [sleep table](#sleep-behavior) states.

## Sleep behavior

| Situation | Required result |
|---|---|
| Start a timed sleep | Store an elapsed-realtime deadline in the playback service. Display remaining minutes, updating from the clock. Screen lock and playback speed do not change the deadline. |
| Start end-of-chapter sleep | Capture the current chapter’s absolute end, clamped to the edition duration. Schedule a `PlayerMessage` at that book position with `setDeleteAfterDelivery(true)`. Messages arrive on the playback thread by default, so deliver it on the application looper, where its handler pauses. Retarget by cancelling it and scheduling a replacement. Measure the overshoot on each test device against the 100 ms tolerance in A13, and clip playback at the chapter end on a device that exceeds it. Use the same timeline as seek and progress. A UI polling timer cannot own the stop. |
| Timer fires | Pause, cancel any auto-resume, flush progress, clear the timer, update the session and UI. For chapter mode commit the exact boundary. At most 100 ms of the next chapter may sound before the pause takes effect. |
| Listener pauses or gets a call | Timed mode keeps its original wall-clock deadline. Expiry while paused prevents interruption recovery from starting playback. Chapter mode stays armed while paused on the same book. |
| Listener seeks | A timed deadline stays fixed. A chapter or track timer retargets to the end of the destination chapter or track and updates its label. Natural progression leaves the original end armed. |
| Listener changes mode, book or explicitly stops | Changing mode atomically replaces the previous timer. Switching book, reaching book end or explicitly stopping clears it. |
| Expiry lands exactly on an internal track or chapter boundary | Commit the boundary position and stop within the same 100 ms tolerance, so no more of the next track or chapter than that sounds. |
| End-of-track sleep armed near a track end | Stop at the track boundary within the same tolerance and commit the exact boundary. When another track follows, the book stays unfinished and the next play continues into that track. |
| End-of-chapter sleep armed for the final chapter, or End of book | Pause at book end, mark the book finished, clear the timer, and do not arm a next chapter. |
| Playback fails | The failure pause clears every armed timer and cancels any pending auto-resume. A transient read error that its single retry clears leaves the timer and its deadline unchanged. If a timer expires during the retry, the timer fires and the retry does not restart playback. |
| Process dies | Playback stops. Restore the last durable position in a paused state on relaunch and clear the stale sleep request. A Bluetooth headset Play after process death resumes at that position with no timer. Moving between activity screens leaves the service timer intact. |

## References

- [Media3 supported containers, decoders and seeking](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Android supported media formats](https://developer.android.com/media/platform/supported-formats)
- [Audiobookshelf format and seeking notes](https://audiobookshelf.org/docs/faq/server/)
- [server track ordering and metadata sources](https://audiobookshelf.org/docs/documentation/libraries/book-library/directory-structure/)
