# Product brief

## What opening Pageworn should feel like

A listener leaves home halfway through an audiobook. On the train they open Pageworn, tap the book they were listening to, and hear the next sentence. That evening, they set **End of chapter**, lock the phone, and fall asleep. In the morning the ribbon still marks the point where playback stopped.

## Release scope

| Requirement | Observable behavior |
|---|---|
| Android only, one listening client | The app works with one account on one server. Two server addresses open the same catalog, downloads and resume positions. |
| Library | Browse authorized audiobook libraries, choose a library if several exist, search by title or author in the selected library, open details, and download. Cache the most recently refreshed catalog. |
| Downloaded | Open, play, inspect, or remove completed books in airplane mode. Show active, waiting, and failed transfers in a separate downloads-in-progress group. |
| Download-only playback | The player accepts a locally committed book. A tap on a remote book opens details with **Download**. Partial transfers offer progress, pause, resume and cancel. |
| Progress by book | Keep an independent position for every book on this device. Survive app death, a switch to another book, address changes, and removal and re-download of the same edition. |
| Format and chapter coverage | Play single- and multi-file audiobooks including unusual formats. Use the book’s chapter map across file boundaries. A book without chapter marks remains playable. |
| Sleep | Offer 15, 30, 45 and 60 minutes, end of chapter, and off. Expiry pauses and saves the position. |
| Multiple addresses | Configure human-readable aliases such as Home and Tailscale. Show the address used for the last successful connection and offer a manual choice. |

Pageworn v1 serves audiobooks. Ebooks, podcasts, streaming, remote playback, cross-device sync, server administration and cloud accounts fall outside this release. A mixed-media item appears when it contains playable audiobook tracks.

## Four journeys to implement end to end

1. **First book.** Enter a server address, sign in, choose a book in Library, and tap Download. Leave the screen. Open the completion notification or find the book under Downloaded. The first Play starts at the beginning.
2. **A second route home.** Add the Tailscale URL for the same account and server. Leave home during a download. The app renegotiates the transfer through a paired reachable address and finishes one local copy. A safe restart of the current track is acceptable when continuation cannot be verified.
3. **Two books on the go.** Pause book A, play book B, then return to A. Resume A at its saved whole-book position. Remove A’s audio to free space. Re-download the same edition and resume at the saved position.
4. **Bedtime.** From the player, choose End of chapter. Lock the phone. At the boundary, audio stops, progress is committed, and the timer clears. Pressing Play the next morning continues from the boundary.

## Launch and state priorities

- First install opens connection setup. With a saved profile, remember the last main section. If the catalog is unreachable and local books exist, a cold launch opens Downloaded. Render local content before network checks complete.
- A warm start preserves the current screen, scrolling position, and open player. Changing connectivity never takes the listener away from a book.
- Sort Downloaded by last listened, then by download completion for unplayed books. Keep finished books available until removed. Library defaults to title order and uses stable item IDs during refresh.
- Distinguish **Downloaded** (file availability) from **Finished** (reading progress). Both can be true. A listener can replay a finished book.
- One profile owns one collection of local data. An expired login affects new network work. Opening a previously downloaded book requires no successful server request.

## Copy and recovery

| Situation | What the listener sees | Action |
|---|---|---|
| No downloads | “Your downloaded books will appear here.” | Browse library |
| No reachable address | “Can’t reach your server.” Show the last successful refresh if available. | Retry or open Downloaded |
| Session expired | “Sign in to refresh your library.” | Sign in to the affected address |
| Download permission missing | “This account can browse this book. Downloads are disabled.” | Back to Library |
| Storage full | “More space is needed to finish this download.” Show required additional space when known. | Manage downloads or retry |
| Audio removed locally | “Download removed. Your place is saved.” | Download again from details |
| Damaged local copy | “This copy couldn’t be opened.” | Repair download, preserving progress |
| Notifications disabled | “Transfers and playback controls stay available in the app.” | Enable notifications, or use Downloaded |

Implementation details and edge cases live in the [interface](02-interface.md), [transfer contract](04-server-and-downloads.md) and [playback contract](05-playback-and-formats.md).
