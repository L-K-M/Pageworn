package ch.lkmc.pageworn.ui.preview

import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.core.timeline.Segment
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.components.LocalCoverPainter
import ch.lkmc.pageworn.ui.components.MainScaffold
import ch.lkmc.pageworn.ui.downloaded.DownloadedScreen
import ch.lkmc.pageworn.ui.library.LibraryScreen
import ch.lkmc.pageworn.ui.model.*
import ch.lkmc.pageworn.ui.player.PlayerScreen
import ch.lkmc.pageworn.ui.player.LocalPreviewSleepSheet
import ch.lkmc.pageworn.ui.theme.PagewornTheme

private fun face(id: String, title: String, author: String) = BookFace(BookKey("preview", id), title, author, CoverImage.Cloth)
private val orchard = face("night_orchard", "The Night Orchard", "Elin Ward")
private val coast = face("glass_coast", "The Glass Coast", "Mara Ellis")
private val rain = face("field_notes_on_rain", "Field Notes on Rain", "Ada Bell")
private val hours = face("small_hours", "Small Hours", "Kit Mercer")
private val transfer = TransferUi("demo", TransferPhase.TRANSFERRING, 62, 100, "Downloading", canPause = true, canResume = false, canRetry = false, canCancel = true)

@Composable
private fun DemoArt(content: @Composable () -> Unit) {
    val painter: @Composable (BookFace) -> Painter? = { book ->
        when (book.key.itemId) {
            "night_orchard" -> painterResource(R.drawable.pw_cover_night_orchard)
            "glass_coast" -> painterResource(R.drawable.pw_cover_glass_coast)
            "field_notes_on_rain" -> painterResource(R.drawable.pw_cover_field_notes_on_rain)
            "small_hours" -> painterResource(R.drawable.pw_cover_small_hours)
            else -> null
        }
    }
    CompositionLocalProvider(LocalCoverPainter provides painter, content = content)
}

@Preview(name = "Library", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
fun LibraryPreview() {
    PagewornTheme { DemoArt {
        MainScaffold(Destination.LIBRARY, {}, MiniPlayerUi(orchard, "Chapter 4 · The Gate", showsPause = false, starting = false, enabled = true), {}, {}) { padding ->
            LibraryScreen(state = LibraryUiState(rows = listOf(
                CatalogRow(orchard, CatalogAction.Ready(showsPause = false, starting = false, StartAction.RESUME)),
                CatalogRow(coast, CatalogAction.Transferring(transfer)),
                CatalogRow(rain, CatalogAction.Ready(showsPause = false, starting = false, StartAction.PLAY)),
                CatalogRow(hours, CatalogAction.Download),
            )), listState = rememberLazyListState(), onQueryChange = {}, onSelectLibrary = {}, onSelectSort = {}, onOpenBook = {}, onDownload = {}, onPlayPause = { _, _ -> }, onPauseTransfer = {}, onResumeTransfer = {}, onCancelTransfer = {}, onAction = {}, onOpenSettings = {}, contentPadding = padding)
        }
    } }
}

@Composable
private fun DownloadedExample() {
    DemoArt {
        MainScaffold(Destination.DOWNLOADED, {}, MiniPlayerUi(orchard, "Chapter 4 · The Gate", showsPause = false, starting = false, enabled = true), {}, {}) { padding ->
            DownloadedScreen(state = DownloadedUiState(
                transfers = listOf(TransferRow(coast, transfer)),
                books = listOf(
                    ReadyBookRow(orchard, ReadyBadge.InProgress("Chapter 4 · 2 h 12 min left"), hasPlace = true, showsPause = false, starting = false, playable = true),
                    ReadyBookRow(rain, ReadyBadge.New, hasPlace = false, showsPause = false, starting = false, playable = true),
                ),
            ), listState = rememberLazyListState(), onOpenBook = {}, onPlayPause = { _, _ -> }, onPauseTransfer = {}, onResumeTransfer = {}, onRetryTransfer = {}, onCancelTransfer = {}, onAction = {}, onOpenSettings = {}, contentPadding = padding)
        }
    }
}

@Preview(name = "Downloaded", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
fun DownloadedPreview() { PagewornTheme { DownloadedExample() } }

@Preview(name = "Downloaded 320 · 200%", widthDp = 320, heightDp = 844, fontScale = 2f, showSystemUi = true)
@Composable
fun DownloadedLargeTypePreview() { PagewornTheme { DownloadedExample() } }

private val previewSleepOptions = listOf(SleepOption(null, "Off")) +
    listOf(15, 30, 45, 60).map { SleepOption(SleepLength.Minutes(it), "$it minutes") } +
    SleepOption(SleepLength.EndOfSegment, "End of chapter")

private val player = PlayerUiState(
    face = orchard, segmentLine = "Chapter 4 · The Gate", segmentName = "Chapter", segmentStartMs = 119 * 60_000L + 40_000L,
    segmentEndMs = 140 * 60_000L + 40_000L, positionMs = 128 * 60_000L,
    elapsedText = "8:20", remainingText = "−12:40", bookLine = "Book · 2 h 08 min of 4 h 20 min",
    bookDurationMs = 260 * 60_000L,
    showsPause = true, transportEnabled = true, speed = 1f,
    sleep = SleepUi(previewSleepOptions, null, null, "Sleep, off"),
    segmentsLabel = "Chapters", division = Division.CHAPTERS, segmentIndex = 4, segments = listOf(
        Segment(4, 119 * 60_000L + 40_000L, 140 * 60_000L + 40_000L, "The Gate", generated = false),
    ), hasPlace = true,
)

@Composable
private fun PlayerExample(state: PlayerUiState, showSleepSheet: Boolean = false) {
    DemoArt { CompositionLocalProvider(LocalPreviewSleepSheet provides showSleepSheet) {
        PlayerScreen(state = state, onCollapse = {}, onPlayPause = {}, onSkipBack = {}, onSkipForward = {}, onSeekTo = {}, onSelectSpeed = {}, onSelectSegment = {}, onSelectSleep = {}, onRepair = {}, onUsePreviousPosition = {}, onStartOver = {})
    } }
}

@Preview(name = "Player", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
fun PlayerPreview() { PagewornTheme { PlayerExample(player) } }

@Preview(name = "Player · sleep", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
fun PlayerSleepPreview() {
    PagewornTheme {
        PlayerExample(player.copy(sleep = SleepUi(previewSleepOptions, SleepLength.EndOfSegment, "12 min 40 sec remaining", "Sleep, 12 minutes 40 seconds remaining", "12:40")), showSleepSheet = true)
    }
}
