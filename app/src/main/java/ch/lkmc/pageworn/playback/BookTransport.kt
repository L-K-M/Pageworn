package ch.lkmc.pageworn.playback

import androidx.media3.session.MediaController
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ProgressStore
import kotlinx.coroutines.Job

/**
 * Moves the place of one named book, for every surface of the app: through the session if the
 * player holds that book when the move's turn comes, otherwise in its saved place. A key moves the
 * book it was drawn for, never whichever book is loaded by the time the tap arrives.
 */
class BookTransport(
    private val connection: PlayerConnection,
    private val editions: EditionStore,
    private val progress: ProgressStore,
) {
    fun skipBack(key: BookKey): Job = move(key, { it.seekBack() }, { it - BookPlayer.SEEK_BACK_MS })

    fun skipForward(key: BookKey): Job = move(key, { it.seekForward() }, { it + BookPlayer.SEEK_FORWARD_MS })

    fun seekTo(key: BookKey, positionMs: Long): Job = move(key, { it.seekTo(positionMs) }, { positionMs })

    private fun move(key: BookKey, inPlayer: (MediaController) -> Unit, target: (Long) -> Long): Job =
        connection.moveBook(key, inPlayer) { moveRestingPlace(key, target) }

    /** Moving the place of a resting book: an exact stop, and at the book's end the finished state. */
    private suspend fun moveRestingPlace(key: BookKey, target: (Long) -> Long) {
        val plan = editions.anyPlan(key) ?: return
        val from = progress.read(key)?.positionMs ?: 0L
        val to = plan.timeline.clamp(target(from))
        progress.saveOutsideSession(
            key, to, plan.timeline.durationMs, plan.fingerprint, StopKind.EXACT,
            finished = plan.timeline.isAtEnd(to),
        )
    }
}
