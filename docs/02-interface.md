# Interface and visual design

Pageworn should feel like opening a clothbound book. The listener sees the title of the section or the book immediately. The app name belongs in the launcher and Android’s system surfaces, with the [launcher icon](08-assets-and-implementation.md#launcher-icon). In the app, place the section heading directly below the status inset. On the player, the only item above the cover is the collapse control.

Use the [four mockups](07-screen-gallery.md) for appearance, the values below for layout, and the [asset guide](08-assets-and-implementation.md) for production layers. All sizes are Android dp unless marked sp. The target portrait composition is 390 × 844 dp. Adapt its proportions to the actual window.

## Material, meaning and placement

| Element | Material | Function |
|---|---|---|
| Page | Quiet warm paper, dark charcoal stock at night | Reading surface. Titles, metadata and controls sit directly on it. |
| Hardcover | Illustrated cover art, a shallow spine crease, lightly raised edge | Identifies the book consistently in a row, mini-player and full player. |
| Place ribbon | Narrow oxblood tail emerging from the lower edge near the book spine | Indicates a saved position. Pair it with chapter or elapsed-time text. |
| Destination bookmarks | Two wide woven tabs over a green cloth binding | Switch between Library and Downloaded. The selected tab hangs lower. |
| Playback keys | Oxblood domed face in a brass rim, smaller ivory skip keys | Direct audio controls. The inlay identifies the action performed on tap. |
| Marks on paper | Dark graphite by day, warm ivory pencil by night | Settings, search, collapse, speed, chapters, sleep and small status/action icons. |

Keep the three depths consistent. Pencil sits flush with the sheet, ribbon disappears *behind* the sheet, and a key stands slightly proud of it. In the artwork a tiny shadow falls below each key or book. Keep the rest of the page quiet.

## Type, color and layout

| Role | Day | Night |
|---|---|---|
| Main paper | `#F3EBD9` | `#201C19` |
| Raised paper / sheet | `#FAF5E9` | `#29231F` |
| Primary ink / pencil | `#30251E` | `#F2E7D4` |
| Secondary ink | `#625243` | `#C4B49F` |
| Interactive accent / focus | `#833828` | `#F0A489` |
| Ink on filled accent | `#F3EBD9` | `#201C19` |
| Cloth at foot of paper | Bottle green `#233831` | Deep green `#172722` |

Use Spectral for section titles, book titles and ribbon labels. Bundle the styles the screens use under its SIL Open Font License and list that licence in the app’s open-source notices. Use Roboto or the system sans for times, status and compact metadata. Suggested sizes are 32/38 sp for a screen heading, 24/30 for the player title, 18/24 for row titles, 16/22 for ribbon and utility labels, 14/20 for metadata. Use tabular numbers for moving time readouts if available.

Start on a 4 dp grid. Outer margins are 16–24 dp, cover-to-copy gap 12 dp, row height content-driven. A book thumbnail fits a 64–84 dp frame. Use original cover proportions within the frame. Distinct generated sample covers are provided for previews. Production rows use Audiobookshelf covers, or a blank cloth cover with a live title and author.

## Header and navigation

**Library / Downloaded.** Put the destination heading first, with Settings in a 48 dp hit region on the same row. There is no separate brand row. Search sits below the Library heading. If the listener can access several libraries, expose the active library in a selector. While a book is loaded, the mini-player sits above the lower edge of the paper. Reserve its measured height so the last list item scrolls into view. Before the first book is opened there is no mini-player and no reserved space for it.

**Binding at the foot of the screen.** Let the paper end just below the mini-player or list. Draw two or three 1 dp page edges, then a shallow cast shadow. Under those edges, reveal a 60–72 dp band of bottle-green bookcloth, above the system gesture inset. The woven bookmarks emerge from under the paper and over the cloth. Clip their tops to the paper seam. Their fishtail ends stay clear of system gestures.

**Two permanent bookmarks.** Flax is always **Library**, on the leading side. Oxblood is always **Downloaded**, on the trailing side. In a left-to-right locale this puts Library on the left. Use two equal-width stationary selection regions. Render the fabric about 112–128 dp wide, with enough room for the localized label. In the resting position show about 44 dp of fabric. Move the selected tail and its label 8 dp farther out. Its hit region remains fixed. Expose the selected state to TalkBack. Give the selected label a slightly heavier weight so the state is clear even with reduced motion. Long text can wrap and increase the binding band height.

Screens also change at once, with no cross-fade: a screen that is leaving must never take the tap meant for the one the listener already sees (Navigation’s default fade held taps for 700 ms). The mini-player takes every touch on its own area, so none falls through to the list beneath.

A tab tap switches destinations immediately and animates the tail over about 140 ms. No pull gesture is required. Preserve scroll and search per destination. Hide the bottom binding on the full player, details and modal sheets; system Back returns to the originating screen. At wide sizes, keep tabs at the foot of the library pane and use the second pane for details when space permits.

## Playback controls

| Control | Face and mark | Interaction |
|---|---|---|
| Full Play/Pause | 72 dp oxblood round key, fine antique-brass rim, ivory triangle or two bars | A tap does what the key showed under the finger, even when the player has changed since that frame was drawn. A second tap within the system’s double-tap time is the same tap, measured by when the finger lifted. Draw Pause as soon as playback is requested, with a short turning arc on the rim while nothing is heard yet. Draw Play when paused, stopped, ended or interrupted by another app’s audio. |
| Skip back / forward | Ivory pill key about 44 dp tall at the art’s own 1.8:1 proportion, so roughly 80 × 44 dp, with a dark engraved arrow and 15 / 30 numeral | Skip backward 15 s or forward 30 s. Clamp at the book edges. |
| Row and mini-player Play/Pause | 30–36 dp oxblood key in at least a 56 dp hit region | Use the same icons and state source as the full player. |
| Pause transfer | Small ivory key with dark pause bars, within the transfer row | Pauses this download. Accessible name includes the title and the word “download”. |
| Lower utility row | 24–28 dp pencil mark and a typeset label for Speed, Chapters or Tracks, and Sleep | Open accessible sheets. Speed displays the actual multiplier. Active sleep mode exposes its target or time remaining. The Chapters utility reads Tracks for a multi-file edition without chapter marks. For a single undivided file it stays in place, disabled, with the reason “This book has no chapters.” |

On press, depress the face visually by about 1–2 dp, tighten its lower shadow, then release. Keep a press visible for a tenth of a second, however short the tap. Trigger one subtle haptic for an accepted transport tap when the user’s device settings allow it. Keep the glyph aligned with the key. A Play tap shows Pause at once, and the media session confirms or withdraws it. The second tap of a double tap, before anything is heard, is the same tap. Every other tap ends in the state its key showed or in a message on the screen. Media-session callbacks govern the visible playing state after that. Audio focus loss, sleep expiry and notification actions must update all instances together.

For the player, center a cover around 200–224 dp on a regular-height phone. Below it place the live book title, author, chapter name, chapter slider, chapter elapsed/remaining, and whole-book elapsed/total. A visible thumb, adjustable semantics and ± controls make seeking discoverable. Then place the three transport keys, followed by the pencil utilities. Collapse artwork first on short screens. All control rows can scroll into view.

## Pencil drawing system

Use a strong outline with slight variation in pressure, a soft graphite edge and very sparse hatching. The gear has a clear center hole, Chapters looks like a tiny open book, and Sleep is a hatched crescent. Keep the illustrations legible at icon size. The supplied PNGs carry alpha so the implementation can tint the strokes for either theme. Labels remain live type.

Change the speed drawing when playback speed changes. The supplied 1× image is for 1× only. Render other supported values as slightly oblique serif type at the same optical size, followed by the multiplication sign. Expose the exact value to assistive technology. The playback key symbols are ivory or dark inlays. Android’s own status and navigation indicators follow the operating system.

## Screens and states

| Screen | Main content and states |
|---|---|
| Connect | Heading “Connect to server”, address and optional label, Continue, then account fields. Indicate invalid URL, an unrecognized or changed certificate, a mismatched pairing, failed credentials, a different account, unreachable address, denied local-network permission, a server older than 2.26.0 and a server without password sign-in separately, each as a message under the field it concerns. The unreachable state offers Retry. An unrecognized or changed certificate shows its SHA-256 fingerprint with its Trust action, as the [transport rules](04-server-and-downloads.md#a-profile-can-have-several-addresses) describe. |
| Library | Heading, Settings, optional library selector, search, catalog rows. A remote book shows Download, or the download-disabled message when the account cannot download. A Download tap on a book in a format the phone cannot play starts no transfer and shows the unsupported-format message on the row. A transfer shows progress, and a ready book offers a Play or Resume key and an accessible “Downloaded” state. Use the graphite check when showing completion without a playback action. A remote cover tap opens details. A first load that is still filling, an incomplete first load and an unreachable server over a cached catalog each show a [status line](#status-lines-and-empty-states) above the rows, which stay browsable and searchable from the pages or cache at hand. With no accessible book library, the selector, search and rows give way to the empty state. |
| Downloaded | In-progress transfers first, followed by locally ready books. Show bytes for unknown totals. “New”, chapter/time remaining, “Finished”, and “Repair needed” distinguish local states. Order ready books by the [sort in the product brief](01-product.md#launch-and-state-priorities). With no transfers and no local books, show the no-downloads empty state. Airplane mode retains all local controls. |
| Book detail | Back, cover, title, author/narrator, total duration and available size, primary stateful action, description and chapter list. For a book in a format the phone cannot play, the primary action is disabled with the unsupported-format message under it. Selecting a downloaded chapter starts local playback at its beginning. The list is headed Tracks for a multi-file edition without chapter marks and is omitted for a single undivided file. |
| Player | Back/collapse, art, live title and chapter, time and controls. The chapter slider is book-wide only when the edition has a single undivided segment. A multi-file edition without chapter marks shows the track title or ordinal where the chapter goes, and the slider spans the track. A single undivided file omits the chapter line and the separate whole-book readout, since the slider already spans the book. After a playback failure, keep the saved position visible, show the audio-stopped message with its Repair download action below the title block, and disable the transport keys with that reason. |
| Speed | 0.75×, 1×, 1.25×, 1.5×, 1.75× and 2×. Show one selected radio for the current speed. A choice applies on tap, persists as the last selected speed, and dismisses the sheet. |
| Chapters | Ordered titles and durations. Pair a place marker with the current chapter’s accessible state. On activation seek and dismiss. Generated Opening and Interlude segments are listed in order like any chapter, in secondary ink. For a multi-file edition without chapter marks the sheet is titled Tracks and lists track titles or ordinals. |
| Sleep | Off, 15/30/45/60 minutes, End of chapter. Show one selected radio. Display “End of track” or “End of book” for editions without chapter marks as applicable. Choices apply on tap. |
| Settings | Opens from the gear on Library and Downloaded. A plain list on the paper under the heading “Settings”: Server addresses; Account, which names the signed-in user and offers Sign out, or Sign in while signed out; Export diagnostics; Open-source licences; and the app version as text. Sign out asks once, in a native confirmation dialog with the sign-out copy. Export diagnostics opens the system’s save dialog. |
| Server addresses | Reached from Settings. Edit, pair, prioritize and test Home/Tailscale or other aliases. Include keyboard/TalkBack reorder actions. Changes to address selection preserve the local book identity. An address blocked by a denied local-network permission says so and offers its Allow access action. An address with an unrecognized or changed certificate shows that message with the fingerprint and its Trust action. A blank label keeps the previous one. Remove revokes that address's session and deletes its credential. Removing the last address keeps the profile, catalog, downloads and places; Library then shows the unreachable state until Add address pairs another. |

Catalog and transfer rows reflect the [download lifecycle](04-server-and-downloads.md#download-lifecycle): available, queued, transferring, waiting for connection, paused or recoverable failure, verifying, ready or repair needed. Pair an icon with state text, and never label a paused or waiting transfer as active.

### Status lines and empty states

Recoverable conditions stay on the page. None of them opens a dialog. Sign out is a deliberate action, not a condition, and confirms in a native dialog. Each shows its sentence from the [copy table](01-product.md#copy-and-recovery) as live type on the paper, in one of three forms. A text action is a short label in the accent color with button semantics and a 48 dp target.

| Form | Placement and style | Used for |
|---|---|---|
| Status line | Between the heading block and the first row, below search in Library. 14/20 sans in secondary ink, wrapping as needed, with at most one text action. The rows below stay usable. | Library still loading, with a small native indeterminate progress mark. Library incomplete, with Retry. Unreachable server over a cached catalog, with the last refresh time and Retry. Local network access denied over a cached catalog, with its Allow access action. Certificate changed over a cached catalog, with an action that opens Server addresses. Session expired or signed out over a cached catalog, with Sign in. Notifications disabled, in Downloaded, with its settings action. |
| Empty state | In place of the rows, starting at the first row position. 16/22 sans in secondary ink, with its action below as a text action. No illustration and no app name. | No downloads. No audiobook libraries. Empty library. No search results. Unreachable server with no cached catalog. Local network access denied with no cached catalog. Certificate changed with no cached catalog. Session expired or signed out with no cached catalog. |
| Row or detail message | On the row’s metadata line, under the detail’s primary action, below the player’s title block, or under the Connect field it concerns, in 14/20 sans in secondary ink, paired with the state icon. The row, detail, player or form keeps its own actions. | Download permission missing, storage full, removed audio, damaged copy, audio stopped during playback, server sent no audio, unsupported audio format, local network access denied, server too old, password sign-in unavailable, unrecognized certificate, certificate changed and different account. |

Where the copy table names two actions for a status line or empty state, the first is its text action. The second is the control already on screen, such as the Downloaded bookmark, the library selector or the address list. When no paired address answers and a denied local-network permission or a changed certificate blocks one of them, that address’s message takes the place of the unreachable-server message, as [the address rules](04-server-and-downloads.md#a-profile-can-have-several-addresses) describe. Announce a status line once, politely, when it appears or its text changes. Keep its action separately focusable. Remove the line as soon as its condition ends.

## Accessibility and acceptance

- Every action has at least a 48 × 48 dp target. Keep the mini-player body, its transport and the destinations independently focusable. TalkBack should announce e.g. “Downloaded, tab, selected”, “Pause The Night Orchard” and “Playback speed, 1.25 times”.
- Use native headings, tab selection, radio groups, progress, range actions, disabled semantics and current values. Decorative art has no independent focus. A disabled key also has a readable reason where appropriate.
- Default label/ground pairs target at least 4.5:1 for normal text. Verify meaningful control outlines and focus indicators at 3:1. Measure the final composite over textured cloth and paper as well as on flat tokens. Provide a stronger opaque text backing or flatter material in high-contrast mode when needed.
- Check both themes at 320 dp width, short landscape and 200% text. Buttons remain reachable, labels may wrap, and the navigation area respects 3-button and gesture insets. Respect reduced motion and device haptic settings.
- Visual review starts with the heading aligned to the Settings gear and the player cover directly below the collapse control. Any second line of app branding in the screen header is a regression.

[Android Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
