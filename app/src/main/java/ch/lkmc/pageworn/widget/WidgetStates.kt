package ch.lkmc.pageworn.widget

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.data.DownloadedOrder
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.playback.PlayProblem
import ch.lkmc.pageworn.playback.PlayRefusal
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.state.PlayerStates
import ch.lkmc.pageworn.ui.state.RowBuilders
import java.io.File

/** Where a tap beside a widget's keys leads. */
enum class WidgetDestination { PLAYER, DETAILS }

/** The Play/Pause key of one book on a widget. */
data class WidgetKey(
    val showsPause: Boolean,
    /** What a tap does: what the key shows, except while the tap that changed it settles. */
    val action: KeyAction,
    val starting: Boolean,
    val start: StartAction,
)

/** A Play or Pause that was just tapped. Until it settles, another tap on that key repeats it. */
data class SettlingTap(val key: BookKey, val action: KeyAction, val serial: Long)

data class ReadingWidgetState(
    val face: BookFace,
    val hasPlace: Boolean,
    val segmentName: String,
    val segmentLine: String,
    /** "13 min left" of the chapter, track or part. */
    val segmentLeft: String,
    val segmentPermille: Int,
    val bookLine: String,
    val bookPermille: Int,
    /** Null while the book cannot play: the whole widget then leads to where that is resolved. */
    val key: WidgetKey?,
    val notice: String?,
    val destination: WidgetDestination,
)

/** Why a book on the shelf has no key. */
enum class ShelfHold(val label: String) {
    REPAIR("Repair"),
    CHOOSE_START("Choose"),
}

data class ShelfBook(val face: BookFace, val hasPlace: Boolean, val key: WidgetKey?, val hold: ShelfHold?)

data class ShelfWidgetState(val books: List<ShelfBook>, val notice: String?)

/** Pure builders of the two widgets' states, on the rules of the player and the Downloaded rows. */
object WidgetStates {
    const val SHELF_BOOKS = 4

    data class ReadingBook(val edition: LocalEditionEntity, val destination: WidgetDestination)

    /**
     * The book Now reading shows: the player's book, or the first of Downloaded when the player has
     * none on this phone. The player can open only its own book, so another one opens its details.
     */
    fun readingBook(
        editions: List<LocalEditionEntity>,
        progress: Map<BookKey, BookProgressEntity>,
        live: LivePlayback,
        lastActive: BookKey?,
        problem: PlayProblem?,
    ): ReadingBook? {
        val inPlayer = problem?.takeIf { it.refusal == PlayRefusal.NEEDS_CHOICE }?.key ?: live.starting ?: live.key ?: lastActive
        editions.firstOrNull { it.key == inPlayer }?.let { return ReadingBook(it, WidgetDestination.PLAYER) }

        val first = DownloadedOrder.sort(editions) { progress[it.key]?.lastListenedAt }.firstOrNull() ?: return null
        return ReadingBook(first, WidgetDestination.DETAILS)
    }

    fun reading(
        book: ReadingBook,
        plan: EditionPlan,
        progress: BookProgressEntity?,
        live: LivePlayback,
        problem: PlayProblem?,
        settling: SettlingTap?,
    ): ReadingWidgetState {
        val player = PlayerStates.player(plan, book.edition, progress, live)
        val moving = live.key == plan.key && live.isPlaying
        val inSegment = player.positionMs - player.segmentStartMs
        val segmentPosition = if (moving) coarse(inSegment, SEGMENT_STEP_MS) else inSegment
        val bookPosition = if (moving) coarse(player.positionMs, BOOK_STEP_MS) else player.positionMs
        val notice = player.transportDisabledReason
            ?: problem?.takeIf { it.key == plan.key }?.let(PlayerStates::notice)

        return ReadingWidgetState(
            face = player.face,
            hasPlace = player.hasPlace,
            segmentName = player.segmentName,
            segmentLine = player.segmentLine,
            segmentLeft = minutesLeft(player.segmentEndMs - player.positionMs),
            segmentPermille = permille(segmentPosition, player.segmentEndMs - player.segmentStartMs),
            bookLine = player.bookLine,
            bookPermille = permille(bookPosition, player.bookDurationMs),
            key = if (!player.transportEnabled) null else key(
                plan.key, player.showsPause, player.starting, StartAction.of(player.hasPlace, player.finished), settling,
            ),
            notice = notice,
            destination = book.destination,
        )
    }

    /**
     * The newest downloads first. Playing never reorders the shelf, so no book moves from under the
     * finger that started or paused it.
     */
    fun shelf(
        editions: List<LocalEditionEntity>,
        dirOf: (LocalEditionEntity) -> File,
        progress: Map<BookKey, BookProgressEntity>,
        live: LivePlayback,
        problem: PlayProblem?,
        settling: SettlingTap?,
    ): ShelfWidgetState {
        val books = DownloadedOrder.sort(editions) { null }.take(SHELF_BOOKS).map { edition ->
            // Without a timeline: the shelf has no progress text.
            val row = RowBuilders.readyRow(edition, dirOf(edition), progress[edition.key], timeline = null, live)
            val hold = when {
                edition.state == EditionState.REPAIR_NEEDED -> ShelfHold.REPAIR
                progress[edition.key]?.pendingContinuityChoice == true && !row.showsPause -> ShelfHold.CHOOSE_START
                else -> null
            }
            ShelfBook(
                face = row.face,
                hasPlace = row.hasPlace,
                key = if (hold != null) null else key(edition.key, row.showsPause, row.starting, row.start, settling),
                hold = hold,
            )
        }
        val notice = problem?.takeIf { p -> books.any { it.face.key == p.key } }?.let(PlayerStates::notice)

        return ShelfWidgetState(books, notice)
    }

    private fun key(book: BookKey, showsPause: Boolean, starting: Boolean, start: StartAction, settling: SettlingTap?) = WidgetKey(
        showsPause = showsPause,
        action = settling?.takeIf { it.key == book }?.action ?: if (showsPause) KeyAction.PAUSE else KeyAction.PLAY,
        starting = starting,
        start = start,
    )

    private fun coarse(ms: Long, stepMs: Long) = ms - ms % stepMs

    private fun permille(part: Long, whole: Long): Int =
        if (whole <= 0) 0 else (part * PERMILLE / whole).toInt().coerceIn(0, PERMILLE)

    private fun minutesLeft(ms: Long) = Format.hoursMinutes(ms.coerceAtLeast(0) + ROUND_UP_TO_MINUTE_MS) + " left"

    /** The bars' scale. */
    const val PERMILLE = 1000

    // A widget update is drawn by the launcher, so a playing book moves its bars in steps.
    private const val SEGMENT_STEP_MS = 30_000L
    private const val BOOK_STEP_MS = 60_000L
    private const val ROUND_UP_TO_MINUTE_MS = 59_999L
}
