# Local playback, chapters and sleep

## The book is the unit of listening

The playback service owns a single active book. The UI sends commands to that service and observes its state. System media controls use the same command path. Switching books checkpoints the old position, releases its timer, and restores the new book’s position. A collapsed activity, locked screen or disconnected server has no effect on the local timeline.

Use Android speech audio attributes, audio focus and standard media-session facilities. Pause on permanent focus loss or headphone disconnect. After a transient interruption, resume only if playback was active and neither the listener nor the timer paused it in the interim. Reattaching headphones or reopening the activity alone must leave a paused book paused.

## A timeline independent of files

Convert server seconds to 64-bit milliseconds once at import, rounding to the nearest millisecond. Order tracks by the server’s playable sequence. Prefer its valid book-relative offsets and cross-check with file durations. When offsets are missing, construct contiguous starts from the ordered verified durations. Normalize rounding discrepancies of a few milliseconds consistently. Investigate material gaps, overlaps or unknown duration using the downloaded files before publishing.

Use half-open intervals `[start, end)`. An internal boundary belongs to the following track or chapter. The book end is a distinct finished state. A global seek maps to the track containing the requested position and a track-local offset. For a chapter-relative seek, add the chapter start before mapping. A player timestamp becomes the saved book position by adding the active track start. Clamp all external seek requests to the edition’s duration. The same mapping resolves every timer and scrubber decision: a boundary callback at an interval edge selects the following track or chapter at offset zero and commits that edge as the saved position.

| Example fixture | Value |
|---|---|
| Track A | 0–90 s |
| Track B | 90–210 s |
| Chapter 1 | 0–75 s |
| Chapter 2 | 75–140 s |
| Chapter 3 | 140–210 s |

In this fixture, chapter 2 at +40 s is book position **115 s**, or **25 s into track B**. Seeking to exactly 90 s selects track B at zero. End-of-chapter sleep armed at 115 s must pause at book position 140 s, which is 50 s into B. Reopening shows chapter 3 at its start. At 210 s mark the book finished and offer Replay from the beginning.

## Chapter map and fallback

1. Use valid book-level Audiobookshelf chapters first. This preserves chapter edits made on the server. Store the metadata in the local edition so navigation works without a network.
2. When the server chapter list is absent or unusable, import valid embedded chapter markers if available. Convert per-file times to absolute times, then normalize and deduplicate. Keep available titles. Fill a missing title with “Chapter 1”, “Chapter 2”, and so on.
3. Tolerate missing end times by using the next valid start or the book end. Drop empty or out-of-range intervals, and trim overlapping ends to the next start. Preserve uncovered audio as a navigable segment labeled “Opening” or “Interlude”. Prefer a usable server chapter map over combining duplicate server and embedded markers.
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
- Persist on explicit seek, pause and other checkpoints in the [storage contract](03-android-and-data.md). The maximum intended loss after unexpected process death during continuous playback is five seconds. A deliberate pause or completed seek should resume within the format’s measured seek tolerance.
- Mark finished on natural end or an explicit seek to book end. Listening to an intermediate chapter end continues into the next chapter unless a timer requests a pause. Replay resets position and finished status through one transaction.

## Sleep behavior

| Situation | Required result |
|---|---|
| Start a timed sleep | Store an elapsed-realtime deadline in the playback service. Display remaining minutes, updating from the clock. Screen lock and playback speed do not change the deadline. |
| Start end-of-chapter sleep | Capture the current chapter’s absolute end. Configure an engine boundary callback or clipped playback boundary. Use the same timeline as seek and progress. A UI polling timer cannot own the stop. |
| Timer fires | Pause, cancel any auto-resume, flush progress, clear the timer, update the session and UI. For chapter mode commit the exact boundary. Do not play an audible chunk of the next chapter. |
| Listener pauses or gets a call | Timed mode keeps its original wall-clock deadline. Expiry while paused prevents interruption recovery from starting playback. Chapter mode stays armed while paused on the same book. |
| Listener seeks | A timed deadline stays fixed. A chapter timer retargets to the end of the destination chapter and updates its label. Natural progression leaves the original end armed. |
| Listener changes mode, book or explicitly stops | Changing mode atomically replaces the previous timer. Switching book, reaching book end or explicitly stopping clears it. |
| Expiry lands exactly on a track or chapter boundary | Commit the boundary position and stop before any audio from the following interval. No audible cue from the next track or chapter plays. |
| End-of-track sleep armed near a track end | Stop exactly at the track boundary, commit it, and leave the book unfinished. The next play continues into the following track. |
| End-of-chapter sleep armed for the final chapter | Pause at book end, mark the book finished, clear the timer, and do not arm a next chapter. |
| Process dies | Playback stops. Restore the last durable position in a paused state on relaunch and clear the stale sleep request. Moving between activity screens leaves the service timer intact. |

## References

- [Media3 supported containers, decoders and seeking](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Android supported media formats](https://developer.android.com/media/platform/supported-formats)
- [Audiobookshelf format and seeking notes](https://audiobookshelf.org/docs/faq/server/)
- [server track ordering and metadata sources](https://audiobookshelf.org/docs/documentation/libraries/book-library/directory-structure/)
