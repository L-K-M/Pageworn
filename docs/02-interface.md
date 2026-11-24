# Interface and visual design

Pageworn should feel like opening a clothbound book. The listener sees the title of the section or the book immediately. The app name belongs in the launcher and Android’s system surfaces, with the [launcher icon](08-assets-and-implementation.md#launcher-icon). In the app, place the section heading directly below the status inset. On the player, the only item above the cover is the collapse control.

Use the [four mockups](07-screen-gallery.md) for appearance, the values below for layout, and the [asset guide](08-assets-and-implementation.md) for production layers. All sizes are Android dp unless marked sp. The target portrait composition is 390 × 844 dp. Adapt its proportions to the actual window. The mockups are drawn larger than an ordinary Android app. On 2026-11-23 the owner found them too large on a phone 360 dp wide and asked for the sizes of other apps, so the sizes below are smaller than the drawn ones and take precedence over them.

## Material, meaning and placement

| Element | Material | Function |
|---|---|---|
| Page | Quiet warm paper | Reading surface. Titles, metadata and controls sit directly on it. |
| Hardcover | Illustrated cover art, a shallow spine crease, lightly raised edge | Identifies the book consistently in a row, mini-player and full player. |
| Place ribbon | Narrow oxblood tail emerging from the lower edge near the book spine | Indicates a saved position. Pair it with chapter or elapsed-time text. |
| Destination bookmarks | Two wide woven tabs over a green cloth binding | Switch between Library and Downloaded. The selected tab hangs lower. |
| Playback keys | Oxblood domed face in a brass rim, smaller ivory skip keys | Direct audio controls. The inlay identifies the action performed on tap. |
| Marks on paper | Dark graphite pencil | Settings, search, collapse, speed, chapters, sleep and small status/action icons. |

Keep the three depths consistent. Pencil sits flush with the sheet, ribbon disappears *behind* the sheet, and a key stands slightly proud of it. In the artwork a tiny shadow falls below each key or book. Keep the rest of the page quiet.

## Type, color and layout

| Role | Color |
|---|---|
| Main paper | `#F3EBD9` |
| Raised paper / sheet | `#FAF5E9` |
| Primary ink / pencil | `#30251E` |
| Secondary ink | `#625243` |
| Interactive accent / focus | `#833828` |
| Ink on filled accent | `#F3EBD9` |
| Cloth at foot of paper | Bottle green `#233831` |

Pageworn has this one palette and keeps it when the phone is in dark mode. The window, the widgets and the dark icons of Android’s bars follow it too. Android must not darken or invert it either. With “Make more apps dark” on, Android inverts every window whose theme claims to be light (`isLightTheme`), and `forceDarkAllowed` does not prevent it: the API 37 emulator showed that, and its framework code agrees. So the theme turns off `forceDarkAllowed` for the older automatic darkening and does not claim to be light. The plan used to give a night column. On 2026-11-23 the owner found the app broken in dark mode and asked for the designed theme everywhere. Another theme may come later, with every color and material adjusted for it, not only these tokens.

Use Spectral for section titles, book titles and ribbon labels. Bundle the styles the screens use under its SIL Open Font License and list that licence in the app’s open-source notices. Use Roboto or the system sans for times, status and compact metadata. Sizes are 26/32 sp Bold for a screen heading, 22/28 Bold for the player and details title, 20/26 Bold for a sheet title, 16/21 SemiBold for row titles, 15/20 Regular for authors and the chapter line, 16 sp for bookmark labels, 15/20 for utility labels and 14/20 for metadata. The player's times are 14 sp Spectral, as drawn, which takes precedence over the sans for those readouts. Use tabular numbers for moving time readouts if available.

Start on a 4 dp grid. Outer margins are 16–24 dp, cover-to-copy gap 14 dp, row height content-driven. A book thumbnail fits a 72 dp frame, and 60 dp from 150% text. The details cover is 168 dp, and its Play key 68 dp. Use original cover proportions within the frame: the book takes the art's shape, with rounded corners, a shadow, and a crease and edge light drawn over flat art. Distinct generated sample covers are provided for previews. Production rows use Audiobookshelf covers, or a blank cloth cover with a live title and author.

## Header and navigation

**Library / Downloaded.** Put the destination heading first, with Settings, a 24 dp pencil gear in a 48 dp hit region, on the same row. There is no separate brand row. Search sits below the Library heading. Under it, at the right, a 48 dp Sort control names the order (“Sort Newest first”) with a pencil chevron. TalkBack reads it as “Sort Library” with the full order as its state. It opens a sheet of four radio choices: Date added, newest first; Date added, oldest first; Title; Author. A choice applies on tap, dismisses the sheet and returns the list to its top. If the listener can access several libraries, expose the active library in a selector. While a book is loaded, the mini-player sits above the lower edge of the paper. It holds a 44 dp cover, the title and the chapter on one line each, and the Play key. From 150% text it leaves the cover out. Reserve its measured height so the last list item scrolls into view. Before the first book is opened there is no mini-player and no reserved space for it.

**Binding at the foot of the screen.** Let the paper end just below the mini-player or list. Draw five stacked page edges with rounded corners, so the cloth shows at the corners. Under those edges, reveal a 68 dp band (86 dp from 150% text) of bottle-green bookcloth, followed by 12 dp of clear cloth before the system navigation inset. Keep that gap even if the inset is zero. The woven bookmarks emerge from under the paper and over the cloth. Clip their tops to the paper seam. Their fishtail ends stay clear of system gestures.

**Two permanent bookmarks.** Flax is always **Library**, on the leading side. Oxblood is always **Downloaded**, on the trailing side. In a left-to-right locale this puts Library on the left. Use two equal-width stationary selection regions. Render the fabric 112 dp wide (152 dp from 150% text, which “Downloaded” needs at 200%), with enough room for the localized label. In the resting position show 44 dp of fabric (56 dp from 150% text). Move the selected tail and its label 6 dp farther out. Its hit region remains fixed. Expose the selected state to TalkBack. Give the selected label a slightly heavier weight and a dashed pencil underline so the state is clear even with reduced motion. A label too wide for the fabric shrinks to fit on one line, down to 10 sp, and the band keeps its height.

Screens also change at once, with no cross-fade: a screen that is leaving must never take the tap meant for the one the listener already sees (Navigation’s default fade held taps for 700 ms). The mini-player takes every touch on its own area, so none falls through to the list beneath.

A tab tap switches destinations immediately and animates the tail over about 140 ms. No pull gesture is required. Preserve scroll and search per destination. Hide the bottom binding on the full player, details and modal sheets; system Back returns to the originating screen. At wide sizes, keep tabs at the foot of the library pane and use the second pane for details when space permits.

## Playback controls

| Control | Face and mark | Interaction |
|---|---|---|
| Full Play/Pause | 80 dp (68 dp on a short or narrow screen) oxblood round key, fine antique-brass rim, ivory triangle or two bars | A tap does what the key showed under the finger, even when the player has changed since that frame was drawn. A second tap within the system’s double-tap time is the same tap, measured by when the finger lifted. Draw Pause as soon as playback is requested, with a short turning arc on the rim while nothing is heard yet. Draw Play when paused, stopped, ended or interrupted by another app’s audio. |
| Skip back / forward | Ivory pill key at the art’s own 1.8:1 proportion, 80 × 44 dp (66 × 36 dp below 360 dp width, in a 48 dp tall target), with a dark engraved arrow and 15 / 30 numeral | Skip backward 15 s or forward 30 s. Clamp at the book edges. |
| Row and mini-player Play/Pause | 40 dp oxblood key in a 48 dp hit region | Use the same icons and state source as the full player. |
| Pause transfer | Small ivory key with dark pause bars, within the transfer row | Pauses this download. Accessible name includes the title and the word “download”. |
| Lower utility row | 28 dp pencil mark and a typeset label for Speed, Chapters or Tracks, and Sleep | Open accessible sheets. Speed displays the actual multiplier. While a sleep timer runs the Sleep label carries a live countdown, and with the mode on and no timer it shows the length; Off shows nothing. The Chapters utility reads Tracks for a multi-file edition without chapters and Parts for a single file without them. |

On press, depress the face visually by about 1–2 dp, tighten its lower shadow, then release. Keep a press visible for a tenth of a second, however short the tap. Trigger one subtle haptic for an accepted transport tap when the user’s device settings allow it. Keep the glyph aligned with the key. A Play tap shows Pause at once, and the media session confirms or withdraws it. The second tap of a double tap is the same tap. Every other tap ends in the state its key showed or in a message on the screen. Media-session callbacks govern the visible playing state after that. Audio focus loss, sleep expiry and notification actions must update all instances together.

For the player, center a cover as drawn in the mockup: half the screen’s width and at most 200 dp on a regular-height phone, 136 dp on a short one. Below it place the live book title, author, chapter name, chapter bar, chapter elapsed/remaining, book bar and whole-book elapsed/total. The chapter bar is 8 dp with the accent fill. The book bar is 5 dp with a secondary-ink fill and a smaller thumb, so the two never read as one another. Both have round ends, a small visible thumb, a 48 dp touch height and a focus ring. A drag previews where it would land in that bar’s own time line. Name them “Chapter position” (or Track, or Part) and “Book position”, each with its own value, range and Back 15 and Forward 30 actions, which make seeking discoverable. Then place the three transport keys, followed by the pencil utilities. Collapse artwork first on short screens. All control rows can scroll into view.

## Pencil drawing system

Use a strong outline with slight variation in pressure, a soft graphite edge and very sparse hatching. The gear has a clear center hole, Chapters looks like a tiny open book, and Sleep is a hatched crescent. Keep the illustrations legible at icon size. The supplied PNGs carry alpha so the implementation can tint the strokes for either theme. Labels remain live type.

Change the speed drawing when playback speed changes. The supplied 1× image is for 1× only. Render other supported values as slightly oblique serif type at the same optical size, followed by the multiplication sign. Expose the exact value to assistive technology. The playback key symbols are ivory or dark inlays. Android’s own status and navigation indicators follow the operating system, with dark icons over the paper.

## Screens and states

| Screen | Main content and states |
|---|---|
| Connect | Heading “Connect to server”, address and optional label, Continue, then account fields. Indicate invalid URL, an unrecognized or changed certificate, a mismatched pairing, failed credentials, a different account, unreachable address, denied local-network permission, a server older than 2.26.0 and a server without password sign-in separately, each as a message under the field it concerns. The unreachable state offers Retry. An unrecognized or changed certificate shows its SHA-256 fingerprint with its Trust action, as the [transport rules](04-server-and-downloads.md#a-profile-can-have-several-addresses) describe. |
| Library | Heading, Settings, optional library selector, search, catalog rows. A remote book shows Download, or the download-disabled message when the account cannot download. A Download tap on a book in a format the phone cannot play starts no transfer and shows the unsupported-format message on the row. A transfer shows progress, and a ready book offers a Play or Resume key and an accessible “Downloaded” state. Use the graphite check when showing completion without a playback action. A remote cover tap opens details. A first load that is still filling, an incomplete first load and an unreachable server over a cached catalog each show a [status line](#status-lines-and-empty-states) above the rows, which stay browsable and searchable from the pages or cache at hand. With no accessible book library, the selector, search and rows give way to the empty state. |
| Downloaded | In-progress transfers first, followed by locally ready books as a grid of covers, three or more to a row as the width allows. The shelf keeps the page’s 20 dp side margins, in line with the heading, rules and transfer rows. A cover tap opens details. A small play key rests on a playable cover’s lower corner, on the art whatever its shape, its rim just past the corner and its 48 dp target clear of the cover’s centre. A generated cover moves its author line to the start, clear of the key. A saved position shows the place ribbon. A book that needs repair has a faded cover with “Repair needed” under it. Each cover’s local state, “New”, the chapter and time remaining, “Finished”, or a damaged copy’s reason, is what TalkBack reads. Show bytes for unknown totals. Order ready books by the [sort in the product brief](01-product.md#launch-and-state-priorities). With no transfers and no local books, show the no-downloads empty state. Airplane mode retains all local controls. |
| Book detail | Back, cover, title, author/narrator, total duration and available size, primary stateful action, description and chapter list. For a book in a format the phone cannot play, the primary action is disabled with the unsupported-format message under it. Selecting a downloaded chapter starts local playback at its beginning. The list is headed Tracks for a multi-file edition without chapters and Parts for a single file without them. For a book that is not on the phone, details read the item once through the profile's route when they open, and show its description, its chapter or track list (for reading; it plays once downloaded) and the format verdict. Generated parts are not listed before a download. Without a route the page shows what the catalog holds. |
| Player | Back/collapse, art, live title and chapter, time and controls. Every book has both bars. A multi-file edition without chapters shows the track title or ordinal where the chapter goes, and the first bar spans the track. A single file without chapters shows “Part 13” there, and the first bar spans that part. A book of one part still has both bars, so the layout is the same for every book. After a playback failure, keep the saved position visible, show the audio-stopped message with its Repair download action below the title block, and disable the transport keys with that reason. |
| Speed | 0.75×, 1×, 1.25×, 1.5×, 1.75× and 2×. Show one selected radio for the current speed. A choice applies on tap, persists as the last selected speed, and dismisses the sheet. |
| Chapters | Ordered titles and durations. Pair a place marker with the current chapter’s accessible state. On activation seek and dismiss. Generated Opening and Interlude segments are listed in order like any chapter, in secondary ink. For a multi-file edition without chapters the sheet is titled Tracks and lists track titles or ordinals. For a single file without chapters it is titled Parts and lists them in secondary ink. A long list opens with the current row in view. |
| Sleep | Off, the listener's presets from Settings, and End of chapter. Show one selected radio, with the remaining time on the selected row. Display “End of track” or “End of part” for editions without chapters as applicable. Choosing a length turns the mode on and starts a fresh timer if a book is playing; Off cancels a running timer. Choices apply on tap. |
| Settings | Opens from the gear on Library and Downloaded. A plain list on the paper under the heading “Settings”: Server addresses; Sleep timer; Account, which names the signed-in user and offers Sign out, or Sign in while signed out; Export diagnostics; Open-source licences; and the app version as text. Sign out asks once, in a native confirmation dialog with the sign-out copy. Export diagnostics opens the system’s save dialog. |
| Sleep timer | Reached from Settings. Edit the presets: 1 to 8 distinct lengths from 1 to 720 minutes, kept ascending. Removing the chosen length moves the choice to the nearest remaining preset; a running timer keeps its length. End of chapter is always offered and is not a preset. Set the optional automatic window as a daily start and end in local time; the start belongs to the window and the end does not, it may cross midnight, and the two must differ. |
| Server addresses | Reached from Settings. Edit, pair, prioritize and test Home/Tailscale or other aliases. Include keyboard/TalkBack reorder actions. Changes to address selection preserve the local book identity. An address blocked by a denied local-network permission says so and offers its Allow access action. An address with an unrecognized or changed certificate shows that message with the fingerprint and its Trust action. A blank label keeps the previous one. Remove revokes that address's session and deletes its credential. Removing the last address keeps the profile, catalog, downloads and places; Library then shows the unreachable state until Add address pairs another. |

Catalog and transfer rows reflect the [download lifecycle](04-server-and-downloads.md#download-lifecycle): available, queued, transferring, waiting for connection, paused or recoverable failure, verifying, ready or repair needed. Pair an icon with state text, and never label a paused or waiting transfer as active.

### Settings, forms and dialogs

Settings, Server addresses, Licences, Book detail and connection setup are paper, like the rest.

- A pencil chevron and “Back” lead out, above the screen's heading and a hairline. Connection setup has them when it was opened to add an address or sign in again. A first connection and a blocked launch have nowhere to lead back to, and a blocked launch offers Open Downloaded instead.
- Related rows share a raised slip with rounded corners and a pencilled edge, under a section label: Server, Account and About in Settings. A row that leads on ends in a pencil chevron. An expanded licence turns its chevron down and reads Expanded or Collapsed as its state.
- Each server address has its own slip: label, URL and switch, then pencil checks for In use and Pinned (In use marks the last address that answered among those the route may take, so with a pin it is the pinned one or none), “Unencrypted access allowed” where it is, “Last reached” with the date of its last success, its messages, and its actions in wrapping lines, with those that resolve a problem first.
- Fields, Library’s search included, are bounded by a pencil edge of at least 3:1 against the paper, in full ink when focused, and keep their names once filled. The main action of a screen is an oxblood leather button with a brass rim, at least 48 dp tall. Switches, radios and dialogs use the palette, and errors use the accent, never Material red.
- Sheets share a brass handle, a 20 sp title and 48 dp rows.

### Status lines and empty states

Recoverable conditions stay on the page. None of them opens a dialog. Sign out is a deliberate action, not a condition, and confirms in a native dialog. Each shows its sentence from the [copy table](01-product.md#copy-and-recovery) as live type on the paper, in one of three forms. A text action is a short label in the accent color with button semantics and a 48 dp target.

| Form | Placement and style | Used for |
|---|---|---|
| Status line | Between the heading block and the first row, below search in Library. 14/20 sans in secondary ink, wrapping as needed, with at most one text action. The rows below stay usable. | Library still loading, with a small native indeterminate progress mark. Library incomplete, with Retry. Unreachable server over a cached catalog, with the last refresh time and Retry. Local network access denied over a cached catalog, with its Allow access action. Certificate changed over a cached catalog, with an action that opens Server addresses. Session expired or signed out over a cached catalog, with Sign in. Notifications disabled, in Downloaded, with its settings action. |
| Empty state | In place of the rows, starting at the first row position. 16/22 sans in secondary ink, with its action below as a text action. No illustration and no app name. | No downloads. No audiobook libraries. Empty library. No search results. Unreachable server with no cached catalog. Local network access denied with no cached catalog. Certificate changed with no cached catalog. Session expired or signed out with no cached catalog. |
| Row or detail message | On the row’s metadata line, under the detail’s primary action, below the player’s title block, or under the Connect field it concerns, in 14/20 sans in secondary ink, paired with the state icon. The row, detail, player or form keeps its own actions. | Download permission missing, storage full, removed audio, damaged copy, audio stopped during playback, server sent no audio, unsupported audio format, local network access denied, server too old, password sign-in unavailable, unrecognized certificate, certificate changed and different account. |

Where the copy table names two actions for a status line or empty state, the first is its text action. The second is the control already on screen, such as the Downloaded bookmark, the library selector or the address list. When no paired address answers and a denied local-network permission or a changed certificate blocks one of them, that address’s message takes the place of the unreachable-server message, as [the address rules](04-server-and-downloads.md#a-profile-can-have-several-addresses) describe. Announce a status line once, politely, when it appears or its text changes. Keep its action separately focusable. Remove the line as soon as its condition ends.

## Home screen widgets

The widgets are the same book on the home screen: paper, page edges over green cloth, bound covers with the place ribbon and the app’s own keys. Like the app, they keep the day paper when the phone is in dark mode. Android’s launchers cannot load the bundled Spectral, so live widget text uses the system serif. Cover lettering is drawn by the app and keeps Spectral.

**Now reading** shows the player’s book, as the player picks it: one that waits for a choice of where to start, otherwise the one starting, loaded or last played. Without one on the phone it shows the first book of Downloaded. Its arrangement follows its size and the system’s font scale.

| Arrangement | Least size at normal type | Shows |
|---|---|---|
| Compact | 110 × 57 | Cover and Play or Pause |
| Square | 120 × 139 | Cover, title, segment, both bars and Play or Pause |
| Strip | 240 × 77 | The same, in a row |
| Player | 250 × 157 | Adds the time left in the segment, the book line and both skips |
| Tall | 310 × 260 | Adds the author and larger cover and keys |

Each least height grows with the type. At a font scale of 1.5 and more, the player arrangement leaves out the time left so the segment keeps its name.

**Bookshelf** shows up to four books, newest download first, standing on the page with their keys on the binding below. Playing never reorders them. It holds one book from 110 dp of width and one more for each further 70 dp, which grows with the type. Titles appear from 164 dp of height.

- A key does what it shows, for the book it was drawn for. A widget gets no touch times, so Play/Pause keeps its action for one double-tap timeout after a tap: the second tap of a double tap is the same tap.
- A tap beside the keys opens the player. For a book that is not the player’s, and for a cover on the shelf, it opens that book’s details. A link to a book that left the phone opens Downloaded.
- A book that cannot play has no key. Now reading gives the player’s reason and leads there. The shelf shows “Repair” or “Choose” and leads to details.
- “This copy couldn’t be opened.” leaves the widgets and the screens as soon as that book has an intact copy again.
- A failed start replaces the progress with “Couldn’t start playback. Another app may be using audio.” and keeps Play. On the smallest arrangement, and on a shelf too short for titles, that message takes the covers’ place. On the strip and square it takes the title’s place too.
- A notice never stops inside a line or a word. The tall arrangement always shows it whole. Below a font scale of 1.2 the player arrangement and a shelf of three or more books do too. The others show its first sentence, and from that scale the shelf’s notice takes the covers’ place with titles as well. Type shrinks only as far as the room asks, and at 200 % every notice but the compact arrangement’s is larger than at normal type. All read out the whole notice.
- While a book plays, the chapter bar moves in 30-second steps and the book bar each minute. A new segment, transport change or problem shows at once. A book at rest shows its exact place. There are no running clocks.
- Without downloads, each widget says “Your downloaded books will appear here.” and opens the app.
- Every key is at least 48 × 48 dp and is named. Now reading reads out the title, author, any notice, segment and book position as one target.
- The widgets ask to stay off the lock screen, because they show what is being listened to.

## Accessibility and acceptance

- Every action has at least a 48 × 48 dp target. Keep the mini-player body, its transport and the destinations independently focusable. TalkBack should announce e.g. “Downloaded, tab, selected”, “Pause The Night Orchard” and “Playback speed, 1.25 times”.
- Use native headings, tab selection, radio groups, progress, range actions, disabled semantics and current values. Decorative art has no independent focus. A disabled key also has a readable reason where appropriate.
- Default label/ground pairs target at least 4.5:1 for normal text. Verify meaningful control outlines and focus indicators at 3:1. Measure the final composite over textured cloth and paper as well as on flat tokens. Provide a stronger opaque text backing or flatter material in high-contrast mode when needed.
- Check both themes at 320 dp width, short landscape and 200% text. Buttons remain reachable, labels may wrap, and the navigation area respects 3-button and gesture insets. Respect reduced motion and device haptic settings.
- Visual review starts with the heading aligned to the Settings gear and the player cover directly below the collapse control. Any second line of app branding in the screen header is a regression.

[Android Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
