# Product brief

## What opening Pageworn should feel like

A listener leaves home halfway through an audiobook. On the train they open Pageworn, tap the book they were listening to, and hear the last few words again before the story goes on. That evening, they turn on Sleep with **End of chapter**, lock the phone, and fall asleep. In the morning the ribbon still marks the point where playback stopped.

## Release scope

| Requirement | Observable behavior |
|---|---|
| Android only, one listening client | The app works with one account on one server. Two server addresses open the same catalog, downloads and resume positions. Signing out ends the session and keeps the downloaded books and saved places for that account. |
| Library | Browse authorized audiobook libraries, search by title or author in the selected library, open details, and download. Remember the chosen library per profile and show a selector when several exist; an account with no accessible book libraries shows an explicit empty state. Cache the most recently refreshed catalog. |
| Downloaded | Open, play, inspect, or remove completed books in airplane mode. Show active, waiting, and failed transfers in a separate downloads-in-progress group. |
| Download-only playback | The player accepts a locally committed book. A tap on a remote book’s cover opens details with **Download**. Partial transfers offer progress, pause, resume and cancel. |
| Progress by book | Keep an independent position for every book on this device. Survive app death, a switch to another book, address changes, and removal and re-download of the same edition. |
| Format and chapter coverage | Play single- and multi-file audiobooks in every format that the server accepts and Android’s player can play. A book in a format the phone cannot play says so and starts no download. Use the book’s chapter map across file boundaries. A book without chapter marks remains playable. |
| Sleep | A mode with Off, the listener's presets (15, 30, 45 and 60 minutes by default) or end of chapter. While it is on, every start of playback stops after the chosen time, fading out over the last three seconds and saving the position. An optional daily window turns it on and off automatically. After a timed stop, a book that has rested five minutes or more starts five minutes earlier. |
| Resume after a rest | A book that has rested five minutes or more resumes ten seconds before its saved position. A timed sleep stop takes the five minutes of the Sleep row instead. A stop at the end of a chapter resumes exactly, and so does a position the listener moved while the book was paused. |
| Multiple addresses | Configure human-readable aliases such as Home and Tailscale. Show the address used for the last successful connection and offer a manual choice. An address uses HTTPS, HTTPS with a certificate the listener has confirmed, or explicitly allowed HTTP. Downloads run on any network, including mobile data. |
| In the car | Android Auto lists the downloaded books, most recently played first. A tap resumes the book. |
| Home screen widgets | Now reading shows the current book, both progress scales, Play or Pause and the skips. Bookshelf shows the newest downloads, each with Play or Pause. Both work with the app closed and without a network. |
| Diagnostics | Settings can export a redacted connection log as a file to share. |

Pageworn v1 serves audiobooks. Ebooks, podcasts, streaming, remote playback, cross-device sync, server administration and cloud accounts fall outside this release. So do OpenID Connect sign-in, a bundled decoder for formats Android’s player cannot play, switching accounts inside the app, crash reporting, analytics and languages other than English. A mixed-media item appears when it contains playable audiobook tracks.

## Four journeys to implement end to end

1. **First book.** Enter a server address, sign in, choose a book in Library, and tap Download. Leave the screen. Open the completion notification or find the book under Downloaded. The first Play starts at the beginning.
2. **A second route home.** Add the Tailscale URL for the same account and server. Leave home during a download. The app renegotiates the transfer through a paired reachable address and finishes one local copy. When continuation cannot be verified, restart the current file from byte zero; the saved playback position is unaffected.
3. **Two books on the go.** Pause book A, play book B, then return to A. A resumes from its saved whole-book position, ten seconds earlier once it has rested five minutes. Remove A’s audio to free space. Re-download the same edition and resume at the saved position.
4. **Bedtime.** From the player, turn on Sleep with **End of chapter**. Lock the phone. At the boundary, audio fades out and stops, progress is committed, and the timer clears. Pressing Play the next morning continues from the boundary and starts a fresh timer. After a timed sleep instead, that Play continues from five minutes before the stop.

## Launch and state priorities

- First install opens connection setup, and the first sign-in opens Library. With a saved profile, remember the last main section. If the catalog is unreachable and local books exist, a cold launch opens Downloaded. If it is unreachable and neither local books nor a cached catalog exist, the launch opens connection setup showing the unreachable-server state with Retry. It shows the local-network copy instead when a denied permission blocks a paired address, and the certificate-changed copy when a changed certificate blocks one. Downloaded always renders from local state alone. A network probe never blocks, delays or replaces the local list.
- A warm start preserves the current screen, scrolling position, and open player. Changing connectivity never takes the listener away from a book.
- Sort Downloaded by last-listened time descending, then by download completion time descending for never-played books, then by title and stable item ID. Hold that order while the list is on screen, so a book that starts stays under the finger that started it. Sort again when the section is next shown or its set of books changes. Keep finished books available until removed. Library opens with the newest book first, by the date it was added to the server. The listener can choose oldest first, title or author instead, and the choice holds across relaunches. Books that tie keep the server’s title order, then item ID. A book without the value sorted on comes last. Published, staged, offline and searched rows use the same order, and refresh uses stable item IDs.
- Distinguish **Downloaded** (file availability) from **Finished** (reading progress). Both can be true. A listener can replay a finished book.
- One profile owns one collection of local data. An expired login or a sign-out affects only new network work. Opening a previously downloaded book requires no successful server request.

## Copy and recovery

| Situation | What the listener sees | Action |
|---|---|---|
| No downloads | “Your downloaded books will appear here.” | Browse library |
| Library still loading | “Loading your library…” | Browse the books shown so far |
| Library incomplete | “Your library didn’t finish loading.” | Retry |
| No audiobook libraries | “This account has no audiobook libraries.” | Check again, or open Downloaded |
| Empty library | “This library has no audiobooks yet.” | Check again, or choose another library |
| No search results | “No books match your search.” | Clear the search |
| No reachable address | “Can’t reach your server.” Show the last successful refresh if available. | Retry or open Downloaded |
| Local network access denied | “Allow local network access to reach [address label].” | Allow access, or use another address |
| Server too old | “This server needs Audiobookshelf 2.26 or later.” | Update the server, then try again |
| Password sign-in unavailable | “This server doesn’t offer password sign-in.” | Enable password sign-in on the server, then try again |
| Server sent no audio | “The server sent no audio for this download.” | Check the server’s file delivery, then retry |
| Unsupported audio format | “Pageworn can’t play this book’s audio format.” | Back to Library, or Cancel a transfer that the check stopped |
| Session expired or signed out | “Sign in to refresh your library.” | Sign in to the affected address |
| Signing out | “Sign out? Your downloaded books and saved places stay on this phone.” | Sign out, or Cancel |
| Different account | “This phone holds books for [account name]. Sign in with that account.” | Sign in again |
| Invalid address | “Enter a web address, such as https://books.example.com/audiobookshelf.” Shown under the address field; addresses with a user name, a query or a fragment count as invalid. | Correct the address |
| Wrong user name or password | “The user name or password is incorrect.” Shown under the password field. | Correct them and continue |
| Too many sign-ins | “Too many sign-in attempts. Try again in a few minutes.” Shown under the password field when the server refuses more sign-ins from this phone for now. | Wait, then continue |
| Unencrypted address | “Pageworn will send your password and books to this address without encryption. Anyone on the same network can read them.” Shown before an http address is first used, with a switch the listener turns on to allow it for that address. | Allow unencrypted access, or use an https address |
| Local network access needed | “Pageworn needs local network access to reach [address].” Shown just before the system asks, on Android 17. | Continue, which opens the system's request |
| Confirm another address | “Signed in as [user name]. This address reaches the server with your library [library name].” | Add address, or Cancel |
| Mismatched pairing | “This address leads to a different server.” Shown when the account matches but the server lacks the library seen at first sign-in. | Check the address, or use another address |
| Unrecognized certificate | “Your phone doesn’t recognize this address’s certificate.” Show the certificate’s SHA-256 fingerprint. | Trust this certificate, or use another address |
| Certificate changed | “The certificate for [address label] has changed.” Connection setup and Server addresses add the new fingerprint. | Server addresses, or use another address. Where the fingerprint shows, Trust the new certificate takes the place of the first. |
| Download permission missing | “This account can browse this book. Downloads are disabled.” | Back to Library, or Retry a transfer that the denial stopped |
| Storage full | “More space is needed to finish this download.” Show required additional space when known. | Manage downloads or retry |
| Audio removed locally | “Download removed. Your place is saved.” | Download again from details |
| Damaged local copy | “This copy couldn’t be opened.” | Repair download, preserving progress |
| Audio stopped during playback | “Playback paused at your last position.” | Repair download, preserving progress, or choose another book |
| Play started nothing | “Couldn’t start playback. Another app may be using audio.” Shown above the mini-player, in the player or in details, wherever the book was tapped. | Play again, or Dismiss |
| Changed audio awaits a choice | “Choose where to start.” as the disabled transport’s reason, beside the previous position and the new duration. A Play on that book opens the player on this choice. | Use previous position, or Start over |
| Notifications disabled | “Transfers and playback controls stay available in the app.” | Enable notifications, or use Downloaded |
| Nothing downloaded, in the car | “Download books on your phone to listen here.” | None on the car screen |

Implementation details and edge cases live in the [interface](02-interface.md), [transfer contract](04-server-and-downloads.md) and [playback contract](05-playback-and-formats.md).
