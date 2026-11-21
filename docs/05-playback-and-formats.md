# Local playback, chapters and sleep

## The book is the unit of listening

The playback service owns a single active book. The UI sends commands to that service and observes its state. System media controls use the same command path. Switching books checkpoints the old position, releases its timer, and restores the new book’s position. A collapsed activity, locked screen or disconnected server has no effect on the local timeline.

Use Android speech audio attributes, audio focus and standard media-session facilities. Pause on permanent focus loss or headphone disconnect. After a transient interruption, resume only if playback was active and neither the listener nor the timer paused it in the interim. Reattaching headphones or reopening the activity alone must leave a paused book paused.

## A timeline independent of files

Convert server seconds to 64-bit milliseconds once at import, rounding to the nearest millisecond. Order tracks by the server’s playable sequence. Prefer its valid book-relative offsets and cross-check with file durations. When offsets are missing, construct contiguous starts from the ordered verified durations. Normalize rounding discrepancies of a few milliseconds consistently. Investigate material gaps, overlaps or unknown duration using the downloaded files before publishing.

The published timeline is contiguous. Each track's duration is the one the [probe](04-server-and-downloads.md#safe-continuation-across-routes) measures on the downloaded file with the player's own extractors, or the server's duration when no probe value exists, and each track starts where the previous one ends. The player concatenates the files, so no gap or overlap can exist in what it plays; server offsets that disagree with the contiguous starts are logged in the redacted diagnostics and not used. The server's chapter times stay in server time.

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
3. Tolerate missing end times by using the next valid start or the book end. A gap or overlap of at most 5 ms between neighbouring chapters, before the first chapter or after the last, is rounding noise from the conversion to milliseconds: close it by moving the earlier chapter's end (or the first start to zero), never by generating a segment. Preserve uncovered audio as a navigable segment: label audio before the first chapter “Opening” and any later gap, including one after the last chapter, “Interlude”. Store the segment with a generated origin. It behaves as a chapter everywhere: it is listed in Chapters, bounds the chapter scrubber, and end-of-chapter sleep armed inside it stops at its end. Never merge uncovered audio into a neighboring chapter. Never combine server and embedded markers when the validated server map is usable.
4. If markers are unavailable, list a multi-file book as **Tracks** using track titles or ordinals. For a single file show a whole-book timeline. Offer **End of track** or **End of book** in the corresponding sleep sheet. Never display invented chapter titles as author-supplied metadata.

## Media format acceptance

Format coverage applies to DRM-free audiobook tracks that the chosen Audiobookshelf release admits and that Media3 can extract, decode and seek on the phone. Support includes local decode, reliable book-wide seeking and chapter navigation where markers exist. A filename suffix alone cannot establish any of those properties. Inventory the server scanner in M0 and evaluate container, actual codec, seek layout and chapter source separately. The v2.38.0 scanner admits the extensions m4b, mp3, m4a, flac, opus, ogg, oga, mp4, aac, wma, aiff, aif, wav, webm, webma, mka, awb, caf, mpg and mpeg. A matrix row without evidence is an open compatibility item. The listener’s library holds M4B, M4A, MP3, FLAC, Opus and Ogg files, so settle those rows first.

| Seed fixture family | What to exercise |
|---|---|
| M4B and M4A in MP4 | AAC single-file M4B with titled embedded chapters, chapterless M4A, and ALAC-in-MP4 where the server admits it |
| MP3 | Constant and variable bit rate, long file with late seeks, multi-file edition with chapters independent of track boundaries |
| AAC elementary stream | ADTS without an index, long-range seek, duration recovery |
| Ogg | Vorbis and Opus, including an `.opus` file, and tag-derived chapters if the library supplies them |
| FLAC and WAV | FLAC with cue/metadata chapters when available, PCM WAV if recognized by the server |
| WebM or Matroska | Audio-only tracks with admitted extensions, local duration and chapter seek |
| ASF/WMA and remaining recognized families | Include a WMA sample and every additional family discovered in the pinned scanner. Record each as playable or unsupported, and exercise the unsupported path with one of them on the minimum and current Android targets. |

Play local files with Media3 ExoPlayer, its bundled extractors and the phone’s decoders. MP3 files seek through an exact frame index that the app builds from the local file’s frame headers, because Media3 1.11.1 interpolates a VBR file’s Xing table instead, which missed late seeks in a 21-minute fixture by more than a second. The recorded matrix, with the device evidence, is in [the format matrix](format-matrix.md). Measure seek accuracy, start latency and chapter behavior on the test targets. The app bundles no decoder or converter of its own in v1. A combination of container and codec that cannot be extracted, decoded or accurately sought is recorded in the matrix as **unsupported**.

Refuse an unsupported book before it costs a download. Each manifest track carries its file extension in `metadata.ext` and, in `codec`, the codec name that the server’s probe found. The track’s `mimeType` is derived from the extension alone, and a WAV file reports `audio/mpeg`, so never decide from it. A track is unsupported when the matrix records its extension and codec as unsupported on every test target, or when this phone reports no decoder for that codec. If any track of a book is unsupported, details disables the primary action with the unsupported-format copy from the [product brief](01-product.md#copy-and-recovery), and a Download tap creates no job, as an unsupported manifest does in the [download lifecycle](04-server-and-downloads.md#download-lifecycle). Any other combination is downloaded and judged by the probe in step 5 of the [transfer contract](04-server-and-downloads.md#safe-continuation-across-routes): a track that the player cannot open stops the job with the same copy.

Do not mark the format requirement delivered by hiding the item or by falling back to a server stream. Damaged files and DRM-protected files get an actionable explanation and preserve the saved place.

For each row, record a redistributable or generated fixture ID, extension, container, codec, chapter source, server version, device/API, engine, actual resume error, late-seek error, and outcome. Use identifiable audio cues around boundaries so the test measures what was heard as well as the timestamp the decoder reports.

## Transport, progress and completion

- Start new books at zero and resumed books at the stored global offset, less the [resume rewind](#resume-rewind). Support 15-second rewind, 30-second advance, Play/Pause, whole chapter selection, and the chapter scrubber. Clamp skips at the book edges. The slider spans the book when it has no chapter or track divisions.
- Provide 0.75×, 1×, 1.25×, 1.5×, 1.75× and 2×. Persist the last selected speed. Progress stays in media time at all speeds. Sleep duration uses elapsed wall time.
- Persist on explicit seek, pause and other checkpoints in the [storage contract](03-android-and-data.md). The maximum intended loss after unexpected process death during continuous playback is five seconds on the book timeline at every supported speed, plus the measured seek error when playback resumes. A deliberate pause or completed seek should resume within the format’s measured seek tolerance.
- Mark finished on natural end, an explicit seek to book end, or a sleep stop at book end. Listening to an intermediate chapter end continues into the next chapter unless a timer requests a pause. Replay resets position and finished status through one transaction.

## Resume rewind

A listener who comes back to a book after a break hears a little of what came before. The rewind applies once, when playback of a book starts from its saved position. It depends on how the book last stopped and on how long it has rested. Rest is the wall-clock time since the saved position was last written, read from the injected clock. A clock that has moved backwards counts as no rest.

| How the book last stopped | Rested less than five minutes | Rested five minutes or more |
|---|---|---|
| A pause, an interruption, headphone removal, a book switch, a playback failure or process death | Saved position | 10 s before the saved position |
| A timed sleep stopped audible playback | Saved position | 5 min before the saved position |
| End of chapter, End of track or End of book sleep | Saved position | Saved position |
| The listener moved the position while the book was paused | Saved position | Saved position |

- Both rewinds are book time at every speed. Clamp the start at the beginning of the book. A rewind may cross a track or chapter boundary.
- Every way of starting counts: a Play key, a row’s Resume, a headset or car button, the notification, Android Auto, resumption after process death and the resume after a transient interruption.
- Moving the position means a seek, a skip key, the scrubber, a chapter choice, Replay or the previous-position choice after changed audio. It makes the next start exact, whatever stopped the book before.
- A timed timer that expires while the book is already paused stops nothing, so the row for the earlier stop still applies.
- Store the stop kind with the book’s progress, so the rule survives process death and a restart of the phone. It has three values: ordinary for the first row of the table, timed for the second and exact for the last two. Moving the position while the book is paused sets it to exact. The saved position stays at the stop until playback starts, and Downloaded, the ribbon and the player show it while the book rests.

## System controls and resumption

The media notification, the lock screen, wired and Bluetooth headsets, the car’s buttons and Android Auto all act through the one media library session. Present the book to them, not its files. These rules need Media3 1.9 or later and were checked against 1.11.1. Several of the APIs below are marked unstable and need the opt-in annotation.

- Give the player each book as one `MediaItem` whose media ID is the book key. Resolve it in the player’s `MediaSource.Factory` to a `ConcatenatingMediaSource2` over the edition’s ordered local tracks, passing each verified track duration as its placeholder duration. The player position is then book time, system surfaces show whole-book progress, and a seek across a file boundary stays within one item.
- Media3 maps headset Next to `seekToNext`, Previous to `seekToPrevious`, and a double press of Play/Pause on a headset to `seekToNext`. With one book item and repeat mode off, Next does nothing and Previous seeks to the start of the book, which the next checkpoint would save as the listener’s place. Wrap the player in a `ForwardingPlayer` that reports both commands as available, because the session drops Next when the player lacks `COMMAND_SEEK_TO_NEXT`, and turns Next into forward 30 s and Previous into back 15 s, matching the player’s own buttons. Also set ExoPlayer’s seek-forward and seek-back increments to the same values, and keep repeat mode off. Chapter changes stay in Chapters and, in the car, in the two chapter commands of [Android Auto](#android-auto).
- Fill the back and forward slots of the session’s media button preferences with back 15 s and forward 30 s (`CommandButton.ICON_SKIP_BACK_15` and `ICON_SKIP_FORWARD_30`) so the notification and lock screen show them instead of track skips. Each system surface still decides which buttons it shows.
- Declare Media3’s `MediaButtonReceiver` and implement `MediaSession.Callback.onPlaybackResumption`. Return the last active book at its saved position, less the [resume rewind](#resume-rewind), with the persisted speed, or only its metadata when `isForPlayback` is false. A Play press on a Bluetooth headset after process death then resumes the book without opening the app. That press is a user play action. The exemption that lets this press start the service from the background is documented for Bluetooth only. A wired press may be rejected, and the app then stays paused with accurate state.
- The System UI resumption control appears only for a `MediaLibraryService`, which is what the service is. With the receiver and `onPlaybackResumption` in place, the control offers the last active book after the service has stopped and after a restart of the phone. For its metadata-only request, supply the cover as `artworkData` or a content URI, because no network may be available at boot.
- In `onConnect`, accept the app’s own package, the Android Auto controller (`MediaSession.isAutoCompanionController`) and any controller for which `ControllerInfo.isTrusted()` is true, which is how the system’s own surfaces arrive. Refuse every other package, because the titles on this phone are private. Confirm with the system-control cases of the [acceptance matrix](06-delivery-and-tests.md#acceptance-matrix) that the notification, a headset button and the resumption control still get through.

### Android Auto

Android Auto shows what the phone has downloaded and nothing else. It never browses the server, starts a download or needs a sign-in.

- The library root has one level. Its children are the books whose published edition is ready to play, in the order of Downloaded, as playable items of the audiobook media type with title, author and cover. Transfers, remote books and editions that need repair are absent. Ask the car to show the books as a list, by returning the content-style hint for playable items (`MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE`) with its list-item value in the root’s library parameters.
- Give each cover as a `content://` URI that the app’s own provider serves from the cover stored with the edition. The car host loads artwork by URI and takes no bitmaps in a list. A book without a cover gets the cloth fallback.
- A tap on a book arrives as a request to play its media ID. Resolve it in `onSetMediaItems` to the book’s item and its saved position less the [resume rewind](#resume-rewind), exactly as Resume on the phone does. A finished book starts from the beginning. A switch of books from the car checkpoints the previous book like any other switch.
- Play and Pause act as on the phone. Next and Previous, including the steering-wheel buttons, move 30 s forward and 15 s back like a headset’s. The media button preferences offer back 15 s and forward 30 s to every surface, and to the Android Auto controller also Previous chapter and Next chapter as custom commands. The host decides which of them it shows and where.
- Next chapter seeks to the start of the following chapter and does nothing in the last one. Previous chapter seeks to the start of the current chapter, or to the start of the one before it when less than three seconds of the current chapter have played. Generated segments count as chapters, and a book divided only into tracks moves by track. Both commands count as the listener moving the position.
- With nothing downloaded, return a successful result with an empty list, never an error result, and send the nothing-downloaded copy from the [product brief](01-product.md#copy-and-recovery) as a non-fatal session error: a `SessionError` passed to `MediaSession.sendError`, which leaves the playback state as it is. Media3 documents the empty list and the non-fatal error separately and shows no example of the two together, so how a head unit presents the message is an owner check. A playback failure reports the audio-stopped copy and leaves the repair to the phone. No request leaves the phone for any of this.
- An app that was not installed from Google Play appears in Android Auto only after the listener turns on the unknown-sources option in Android Auto’s developer settings.
- Test the list, play on tap, the commands and the empty state with a Media3 `MediaBrowser` in an instrumented test. The Desktop Head Unit needs a phone with Android Auto from the Play Store, so the look on a real head unit, including how it presents the messages and which buttons it shows, is an [owner check](06-delivery-and-tests.md#owner-checks).

## Playback failure handling

A decoder, demuxer or read failure during playback is distinct from a damaged edition found before opening. When a track cannot be initialized, decoded or read:

- Pause at the last successfully decoded checkpoint and persist that book position. The loss stays within five seconds of book time at every supported speed, plus the measured seek error when playback resumes. Never skip past the failing audio into a later chapter or track.
- A track that cannot be opened fails only when playback reaches it. Play the current track to its end and save the boundary itself. Under the half-open rule that position is the failing track at offset zero, and none of its audio has played. If a sleep stop lands on the same boundary, the timer fires first and completes normally, and the failure surfaces on the next play. A seek or resume that lands inside a track which cannot be opened plays nothing from it: a failed seek keeps the position saved before the seek, and a failed resume keeps the saved place.
- Set the edition to **Repair needed** while leaving its saved place, duration snapshot and finished flag unchanged. Do not mark the book finished.
- Never fall back to a server stream or hide the item. Surface the “Audio stopped during playback” copy from the [product brief](01-product.md).
- Retry a transient read error once from the same position. If the retry also fails, pause as described above. Unsupported codecs and DRM-protected files keep the existing format rules and preserve the place.
- After repair or re-download, restore the saved position when the edition fingerprint still matches. Otherwise use the previous-position choice required for materially changed audio.
- The failure pause cancels any pending auto-resume and leaves an armed sleep timer as it is, as the [sleep table](#sleep-behavior) states.

## Sleep behavior

| Situation | Required result |
|---|---|
| Start a timed sleep | Store an elapsed-realtime deadline in the playback service. Display remaining minutes, updating from the clock. Screen lock and playback speed do not change the deadline. |
| Start end-of-chapter sleep | Capture the current chapter’s absolute end, clamped to the edition duration. Schedule a `PlayerMessage` at that book position with `setDeleteAfterDelivery(true)`. Messages arrive on the playback thread by default, so deliver it on the application looper, where its handler pauses. Retarget by cancelling it and scheduling a replacement. Measure the overshoot on each test device against the 100 ms tolerance in A13, and clip playback at the chapter end on a device that exceeds it. Use the same timeline as seek and progress. A UI polling timer cannot own the stop. |
| Timer fires | Pause, cancel any auto-resume, flush progress, clear the timer, update the session and UI. For chapter mode commit the exact boundary. At most 100 ms of the next chapter may sound before the pause takes effect. Record the stop kind for the [resume rewind](#resume-rewind): a timed stop when a timed timer stopped audible playback, an exact stop for the chapter, track and book modes. |
| Listener pauses or gets a call | Timed mode keeps its original wall-clock deadline. Expiry while paused prevents interruption recovery from starting playback, clears the timer and leaves the saved position and its stop kind as they were. Chapter mode stays armed while paused on the same book. |
| Listener seeks | A timed deadline stays fixed. A chapter or track timer retargets to the end of the destination chapter or track and updates its label. Natural progression leaves the original end armed. |
| Listener changes mode, book or explicitly stops | Changing mode atomically replaces the previous timer. Switching book, reaching book end or explicitly stopping clears it. |
| Expiry lands exactly on an internal track or chapter boundary | Commit the boundary position and stop within the same 100 ms tolerance, so no more of the next track or chapter than that sounds. |
| End-of-track sleep armed near a track end | Stop at the track boundary within the same tolerance and commit the exact boundary. When another track follows, the book stays unfinished and the next play continues into that track. |
| End-of-chapter sleep armed for the final chapter, or End of book | Pause at book end, mark the book finished, clear the timer, and do not arm a next chapter. |
| Playback fails | The failure pause cancels any pending auto-resume and leaves an armed timer as it is. A timed timer keeps its deadline and, when that passes during the pause, clears itself as it does after any pause. A chapter, track or book timer stays armed while the service keeps the book loaded and stops at its boundary once playback continues. A transient read error that its single retry clears leaves the timer and its deadline unchanged. If a timer expires during the retry, the timer fires and the retry does not restart playback. |
| Process dies | Playback stops. Restore the last durable position in a paused state on relaunch and clear the stale sleep request. A Bluetooth headset Play after process death resumes at that position, less the [resume rewind](#resume-rewind), with no timer. Moving between activity screens leaves the service timer intact. |

## References

- [Android Auto media apps](https://developer.android.com/training/cars/media/auto)
- [Media3 playback resumption and Android Auto controllers](https://developer.android.com/media/media3/session/background-playback)
- [Media3 supported containers, decoders and seeking](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Android supported media formats](https://developer.android.com/media/platform/supported-formats)
- [Audiobookshelf format and seeking notes](https://audiobookshelf.org/docs/faq/server/)
- [server track ordering and metadata sources](https://audiobookshelf.org/docs/documentation/libraries/book-library/directory-structure/)
