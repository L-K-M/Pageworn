# Interface and visual design

## The physical references

The cover is the visual anchor, the catalog reads like a printed book list, and an oxblood ribbon marks the listener’s place. All three carry information. Keep paper quiet under text. Let the book’s artwork supply most of the color.

| Material | Where it appears | How to render it |
|---|---|---|
| Book cloth | Cover fallback and the narrow binding edge of the player cover | Deterministic cloth color derived from the book ID. A low-opacity, bundled tile and a narrow spine fold. Use the book title and author as the cover lettering. |
| Paper | Lists, details and chapter sheets | Flat warm surface behind text. A fine rule divides rows. Apply grain only to empty margins or decorative areas, at roughly 2% opacity. |
| Binding and page edges | Large cover in details and player | Preserve original art aspect ratio and letterbox with its cloth color. Add a 2 dp page edge and a soft shadow at large sizes. Small thumbnails use a single outline. |
| Place ribbon | In-progress book and selected chapter | A short notched ribbon in the cover margin, paired with a text position. Reserve the ribbon shape for saved position. Use a check with a text label for downloads. |

## Design tokens

| Role | Day | Night |
|---|---|---|
| Main surface | Parchment `#F3EBD9` | Charcoal `#201C19` |
| Raised surface | Warm ivory `#FAF5E9` | Binding `#29231F` |
| Main text | Brown ink `#30251E` | Pale paper `#F2E7D4` |
| Secondary text | Sepia `#625243` | Faded linen `#C4B49F` |
| Accent or focus outline | Oxblood `#833828` | Copper `#F0A489` |
| Primary action text on filled accent | `#F3EBD9` | `#201C19` |

Use the system day/night preference initially. Primary ink on the main surface is about 12.6:1 by day and 13.8:1 by night. Verify the actual composited controls in both themes. Never place essential labels directly on arbitrary cover art.

- **Typography.** Use a bundled Spectral face for book titles and section headings, with Android serif as fallback. Use system Roboto for controls, authors and durations. Start with 30/36 sp for screen titles, 22/28 for a book heading, 17/24 for row titles, 16/24 for body, and 14/20 for secondary metadata. Bundle the font license if redistributing it.
- **Geometry.** Use a 4 dp spacing grid. Compact margins are 16 dp, normal margins 24 dp. Put 12 dp between thumbnail and text. Use 4–8 dp corners on paper and covers. Reserve fully round shapes for the main transport button and slider thumb.
- **Movement.** A press may darken or depress a control by 1 dp. Use a short crossfade when artwork arrives. Respect system animation scale. Page-turn animation is unnecessary for audio transport.

## Navigation and screens

**Library** and **Downloaded** are the two persistent destinations. On phones, show them in a bottom bar. Stack the mini-player immediately above it while a book is loaded; before the first book is opened there is no mini-player and no reserved space for it. On wider windows use a labeled rail and a list/detail arrangement if both panes fit. The mini-player opens the full player. System Back closes a sheet, then the player or detail, then returns to its originating list. Preserve each list’s scroll and query state.

| Screen | Reading order, top to bottom | Main interaction |
|---|---|---|
| Connection | Pageworn title, server address, optional label, Continue. Next show the verified address and sign-in fields. | Continue tests connectivity. Sign in pairs the first address. Surface invalid URL, certificate, unreachable host and rejected credentials separately. |
| Library | Library heading and settings, library selector if needed, search, catalog rows, mini-player, navigation. | Tap a row to open details. Use an explicit trailing download action with a book-specific accessible name. Cached results show last refresh and an offline status. |
| Downloaded | Downloaded heading, in-progress transfers when present, completed books, mini-player, navigation. | Completed rows show cover, title, author, chapter or time remaining, and Play or Resume. Transfer rows show status and their available actions. |
| Book detail | Back, cover and title, author and optional narrator, total duration and size when known, primary action, expandable description, chapter list. | Primary action follows the download state. Before download, chapters are read-only. After download, a chapter tap opens the player at that chapter. |
| Player | Collapse, artwork, book and chapter title, chapter scrubber with times, whole-book position, transport, Chapters and Sleep. | Large Play/Pause centered between rewind 15 seconds and forward 30 seconds. Show speed as a labeled control. Buttons remain reachable at large font sizes. |
| Chapters | Heading, ordered list with chapter number, title, duration and current marker. | Tap to seek and dismiss. The current row exposes “Current chapter” to accessibility services. |
| Sleep | Off, timed choices, End of chapter, active timer summary. | Choose a value to apply and dismiss. With a timer active show remaining time or the target chapter, plus Turn off. |
| Speed | Current speed and the 0.75×, 1×, 1.25×, 1.5×, 1.75× and 2× choices. | Choose a speed to apply, persist it as the last selected speed, and dismiss. |
| Server addresses | Settings entry, account, ordered address rows, active status, Add address, Automatic or chosen route. | Add, test, pair, rename, reorder or remove an alias. Provide Move up/down actions as an alternative to drag. Removing one alias preserves books. |

### Two reference compositions for the first design review

Use a 390 × 844 dp portrait canvas with normal font scale. Inset all interactive content from the system bars. These measurements are starting proportions for a Compose preview.

**Downloaded, three books.** Set the word “Downloaded” flush left on paper with a 24 dp top inset. Start the first row 20 dp below it. Give each book a roughly 60 × 80 dp cover frame, a 12 dp text gutter, and enough height for a two-line title. For a partially listened book, use “Chapter 4 · 2 h 12 min left” under its author. Place its ribbon on the cover’s right edge and a single Resume control at the trailing edge. Under the next two books, use “New” and “Finished” respectively. Put the 64 dp mini-player directly above the tab bar, with reserved bottom space in the list. An empty shelf returns to the empty state rather than showing placeholders for books.

**Player, chapter timer active.** Use a roughly 216 dp square art frame near the top, with the actual cover contained within it. Put the book title and chapter beneath it. Preview the reference book at 115 s. Its chapter slider reads 0:40 elapsed and 0:25 remaining, the book label reads 1:55 of 3:30, and Sleep reads “End of chapter”. A listener can tell which time changes when they drag the slider. Use the same composition in a night preview with unfamiliar or missing art.

### The player at phone size

Use 16–24 dp horizontal padding. Center the cover, bounded by both width and available height. Below it, place the chapter title directly above the slider. On one line show chapter elapsed at the left and chapter remaining at the right. On the next, use a plain book-wide label such as **Book · 2 h 14 min of 8 h 05 min**. Scrubbing previews the destination, releasing commits one seek. Previous/next chapter actions live in Chapters and may also appear when space allows.

At short heights, shrink or hide decorative artwork first. Allow vertical scrolling while the primary transport remains reachable. At 200% text, let controls wrap and allow titles to grow. In landscape, place art to the side only when the controls retain their hit targets. A mini-player shows title, chapter and Play/Pause. Its body and transport are separate focusable controls.

### Component states and accessibility

- A catalog row reflects the [download lifecycle](04-server-and-downloads.md): available, queued, transferring, waiting for connection, paused, verifying, ready or repair needed. Use an icon **and** state text, and never label a paused or waiting transfer as active. An indeterminate transfer shows “Downloading…” with bytes received. Show a percentage only with a known total.
- Give every action at least a 48 × 48 dp touch area. The play button is 64 dp on the full player. Important controls require a visible focus state, a stable TalkBack label and a non-gesture alternative.
- Expose slider range and position through accessibility semantics. Support adjustable steps, skip buttons and chapter selection. Avoid live announcements for every second of playback or percentage point of a transfer.
- For the sleep sheet, announce the chosen mode once. For download completion, use one status announcement. Treat decorative fibers, shadows and ribbons as invisible to accessibility services when the adjacent label carries the meaning.
- Test with TalkBack, switch or keyboard input, both themes, 200% font size, and reduced motion. Test 320 dp width and a short landscape viewport. The same tasks must work with all decorative textures disabled.

## References

- [Android Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
