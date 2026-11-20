# Interface and visual design

Pageworn should feel like opening a clothbound book. The listener sees the title of the section or the book immediately. The app name belongs in the launcher and Android’s system surfaces. In the app, place the section heading directly below the status inset. On the player, the only item above the cover is the collapse control.

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

Use Spectral or a metrically checked Android serif fallback for section titles, book titles and ribbon labels. Use Roboto or the system sans for times, status and compact metadata. Suggested sizes are 32/38 sp for a screen heading, 24/30 for the player title, 18/24 for row titles, 16/22 for ribbon and utility labels, 14/20 for metadata. Use tabular numbers for moving time readouts if available. When bundling a font, include its redistribution license.

Start on a 4 dp grid. Outer margins are 16–24 dp, cover-to-copy gap 12 dp, row height content-driven. A book thumbnail fits a 64–84 dp frame. Use original cover proportions within the frame. Distinct generated sample covers are provided for previews. Production rows use Audiobookshelf covers, or a blank cloth cover with a live title and author.

## Header and navigation

**Library / Downloaded.** Put the destination heading first, with Settings in a 48 dp hit region on the same row. There is no separate brand row. Search sits below the Library heading. If the listener can access several libraries, expose the active library in a selector. While a book is loaded, the mini-player sits above the lower edge of the paper. Reserve its measured height so the last list item scrolls into view. Before the first book is opened there is no mini-player and no reserved space for it.

**Binding at the foot of the screen.** Let the paper end just below the mini-player or list. Draw two or three 1 dp page edges, then a shallow cast shadow. Under those edges, reveal a 60–72 dp band of bottle-green bookcloth, above the system gesture inset. The woven bookmarks emerge from under the paper and over the cloth. Clip their tops to the paper seam. Their fishtail ends stay clear of system gestures.

**Two permanent bookmarks.** Flax is always **Library**, on the leading side. Oxblood is always **Downloaded**, on the trailing side. In a left-to-right locale this puts Library on the left. Use two equal-width stationary selection regions. Render the fabric about 112–128 dp wide, with enough room for the localized label. In the resting position show about 44 dp of fabric. Move the selected tail and its label 8 dp farther out. Its hit region remains fixed. Expose the selected state to TalkBack. Give the selected label a slightly heavier weight so the state is clear even with reduced motion. Long text can wrap and increase the binding band height.

A tab tap switches destinations immediately and animates the tail over about 140 ms. No pull gesture is required. Preserve scroll and search per destination. Hide the bottom binding on the full player, details and modal sheets; system Back returns to the originating screen. At wide sizes, keep tabs at the foot of the library pane and use the second pane for details when space permits.

## Playback controls

| Control | Face and mark | Interaction |
|---|---|---|
| Full Play/Pause | 72 dp oxblood round key, fine antique-brass rim, ivory triangle or two bars | One tap toggles through the media controller. Draw Pause when audio is playing and Play when paused. |
| Skip back / forward | Roughly 60 × 44 dp ivory key, dark engraved arrow and 15 / 30 numeral | Skip backward 15 s or forward 30 s. Clamp at the book edges. |
| Row and mini-player Play/Pause | 30–36 dp oxblood key in at least a 48 dp hit region | Use the same icons and state source as the full player. |
| Pause transfer | Small ivory key with dark pause bars, within the transfer row | Pauses this download. Accessible name includes the title and the word “download”. |
| Lower utility row | 24–28 dp pencil mark and a typeset label for Speed, Chapters or Sleep | Open accessible sheets. Speed displays the actual multiplier. Active sleep mode exposes its target or time remaining. |

On press, depress the face visually by about 1–2 dp, tighten its lower shadow, then release. Trigger one subtle haptic for an accepted transport tap when the user’s device settings allow it. Keep the glyph aligned with the key. Media-session callbacks govern the visible playing state. Audio focus loss, sleep expiry and notification actions must update all instances together.

For the player, center a cover around 200–224 dp on a regular-height phone. Below it place the live book title, author, chapter name, chapter slider, chapter elapsed/remaining, and whole-book elapsed/total. A visible thumb, adjustable semantics and ± controls make seeking discoverable. Then place the three transport keys, followed by the pencil utilities. Collapse artwork first on short screens. All control rows can scroll into view.

## Pencil drawing system

Use a strong outline with slight variation in pressure, a soft graphite edge and very sparse hatching. The gear has a clear center hole, Chapters looks like a tiny open book, and Sleep is a hatched crescent. Keep the illustrations legible at icon size. The supplied PNGs carry alpha so the implementation can tint the strokes for either theme. Labels remain live type.

Change the speed drawing when playback speed changes. The supplied 1× image is for 1× only. Render other supported values as slightly oblique serif type at the same optical size, followed by the multiplication sign. Expose the exact value to assistive technology. The playback key symbols are ivory or dark inlays. Android’s own status and navigation indicators follow the operating system.

## Screens and states

| Screen | Main content and states |
|---|---|
| Connect | Heading “Connect to server”, address and optional label, Continue, then account fields. Indicate invalid URL, certificate/identity mismatch, failed credentials, and unreachable address separately. The unreachable state offers Retry. |
| Library | Heading, Settings, optional library selector, search, catalog rows. A remote book shows Download, or the download-disabled message when the account cannot download. A transfer shows progress, and a ready book offers a Play or Resume key and an accessible “Downloaded” state. Use the graphite check when showing completion without a playback action. A remote cover tap opens details. |
| Downloaded | In-progress transfers first, followed by locally ready books. Show bytes for unknown totals. “New”, chapter/time remaining, “Finished”, and “Repair needed” distinguish local states. Airplane mode retains all local controls. |
| Book detail | Back, cover, title, author/narrator, total duration and available size, primary stateful action, description and chapter list. Selecting a downloaded chapter starts local playback at its beginning. |
| Player | Back/collapse, art, live title and chapter, time and controls. The chapter slider is book-wide only when the edition has a single undivided segment. |
| Speed | 0.75×, 1×, 1.25×, 1.5×, 1.75× and 2×. Show one selected radio for the current speed. A choice applies on tap, persists as the last selected speed, and dismisses the sheet. |
| Chapters | Ordered titles and durations. Pair a place marker with the current chapter’s accessible state. On activation seek and dismiss. |
| Sleep | Off, 15/30/45/60 minutes, End of chapter. Show one selected radio. Display “End of track” or “End of book” for editions without chapter marks as applicable. Choices apply on tap. |
| Server addresses | A setting to edit, pair, prioritize and test Home/Tailscale or other aliases. Include keyboard/TalkBack reorder actions. Changes to address selection preserve the local book identity. |

Catalog and transfer rows reflect the [download lifecycle](04-server-and-downloads.md#download-lifecycle): available, queued, transferring, waiting for connection, paused or recoverable failure, verifying, ready or repair needed. Pair an icon with state text, and never label a paused or waiting transfer as active.

## Accessibility and acceptance

- Every action has at least a 48 × 48 dp target. Keep the mini-player body, its transport and the destinations independently focusable. TalkBack should announce e.g. “Downloaded, tab, selected”, “Pause The Night Orchard” and “Playback speed, 1.25 times”.
- Use native headings, tab selection, radio groups, progress, range actions, disabled semantics and current values. Decorative art has no independent focus. A disabled key also has a readable reason where appropriate.
- Default label/ground pairs target at least 4.5:1 for normal text. Verify meaningful control outlines and focus indicators at 3:1. Measure the final composite over textured cloth and paper as well as on flat tokens. Provide a stronger opaque text backing or flatter material in high-contrast mode when needed.
- Check both themes at 320 dp width, short landscape and 200% text. Buttons remain reachable, labels may wrap, and the navigation area respects 3-button and gesture insets. Respect reduced motion and device haptic settings.
- Visual review starts with the heading aligned to the Settings gear and the player cover directly below the collapse control. Any second line of app branding in the screen header is a regression.

[Android Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
