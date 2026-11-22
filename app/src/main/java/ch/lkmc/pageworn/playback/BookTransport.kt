package ch.lkmc.pageworn.playback

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Moves the place of one named book, for every surface of the app: through the session while the
 * player holds that book, otherwise in its saved place. A key moves the book it was drawn for,
 * never whichever book is loaded by the time the tap arrives.
 */
class BookTransport(
    private val hub: PlaybackHub,
    private val connection: PlayerConnection,
    private val editions: EditionStore,
    private val progress: ProgressStore,
    private val scope: CoroutineScope,
) {
    /** Moves of a resting book's place, one after another: a skip reads the place it moves. */
    private val restingMoves = Mutex()

    fun skipBack(key: BookKey): Job =
        if (isLoaded(key)) connection.skipBack() else moveRestingPlace(key) { it - BookPlayer.SEEK_BACK_MS }

    fun skipForward(key: BookKey): Job =
        if (isLoaded(key)) connection.skipForward() else moveRestingPlace(key) { it + BookPlayer.SEEK_FORWARD_MS }

    fun seekTo(key: BookKey, positionMs: Long): Job =
        if (isLoaded(key)) connection.seekTo(positionMs) else moveRestingPlace(key) { positionMs }

    private fun isLoaded(key: BookKey) = hub.state.value.key == key

    /** Moving the place of a resting book: an exact stop, and at the book's end the finished state. */
    private fun moveRestingPlace(key: BookKey, target: (Long) -> Long): Job = scope.launch {
        restingMoves.withLock {
            val plan = editions.anyPlan(key) ?: return@withLock
            val from = progress.read(key)?.positionMs ?: 0L
            val to = plan.timeline.clamp(target(from))
            progress.saveOutsideSession(
                key, to, plan.timeline.durationMs, plan.fingerprint, StopKind.EXACT,
                finished = plan.timeline.isAtEnd(to),
            )
        }
    }
}
