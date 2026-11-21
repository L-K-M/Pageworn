package ch.lkmc.pageworn.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.progress.ResumeRewind
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ProgressStore
import ch.lkmc.pageworn.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The playback rules of docs/05-playback-and-formats.md, applied on the main thread to the one
 * player: which book is loaded, where it starts (with the resume rewind), when progress is saved
 * and with which stop kind, the sleep timer, and how a playback failure preserves the place.
 */
class PlaybackController(
    private val exo: ExoPlayer,
    private val editions: EditionStore,
    private val progress: ProgressStore,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val hub: PlaybackHub,
    private val writer: ProgressWriter,
    private val items: BookItems,
    private val scope: CoroutineScope,
) : Player.Listener {

    /** What the controller remembers about the saved place of the loaded book. */
    private data class Saved(val positionMs: Long, val stopKind: StopKind, val updatedAt: Long, val finished: Boolean)

    private class Loaded(val plan: EditionPlan, val session: ProgressStore.Session, var saved: Saved?) {
        val key: BookKey get() = plan.key
        val durationMs: Long get() = plan.timeline.durationMs
    }

    private var loaded: Loaded? = null

    /** The start position of a freshly loaded book already includes the resume rewind. */
    private var startResolved = false

    /** A seek whose resolved position is committed once the player is ready there. */
    private var pendingSeek = false
    private var lastCommittedPosition = C.TIME_UNSET
    private var wasPlaying = false

    /** Stop kind for the pause the controller itself is about to cause (a timer). */
    private var pauseCause: StopKind? = null

    var sleep: SleepState = SleepState.Off
        private set
    private var sleepMessage: PlayerMessage? = null
    private var failed = false
    private var tickJob: Job? = null

    init {
        exo.addListener(this)
        exo.setPlaybackSpeed(settings.speed.value)
        tickJob = scope.launch { tickLoop() }
    }

    fun release() {
        tickJob?.cancel()
        checkpoint(StopKind.ORDINARY, listened = exo.isPlaying)
        loaded?.let { editions.release(it.plan.generationId) }
        loaded = null
        hub.clear()
        exo.removeListener(this)
    }

    val loadedKey: BookKey? get() = loaded?.key
    val loadedPlan: EditionPlan? get() = loaded?.plan

    // --- Loading -------------------------------------------------------------------------------

    /**
     * Resolves [key] for the player. [explicitStartMs] is a position the listener chose (a chapter,
     * Replay, the previous-position choice); otherwise the book starts from its saved place less the
     * resume rewind, or from zero when finished. Called on the main thread before the session hands
     * the item to the player, so the outgoing book is checkpointed at its real position first.
     */
    suspend fun resolve(key: BookKey, explicitStartMs: Long?): Pair<MediaItem, Long>? {
        val outgoing = loaded
        if (outgoing != null && outgoing.key != key) {
            // Book switch: checkpoint the old book now, release its timer and reader.
            checkpoint(StopKind.ORDINARY, listened = exo.isPlaying)
            clearSleep()
        }
        if (outgoing != null && outgoing.key == key && !failed) {
            val start = explicitStartMs?.let { outgoing.plan.timeline.clamp(it) } ?: exo.currentPosition
            if (explicitStartMs == null) startResolved = false
            return playableItem(outgoing.plan) to start
        }
        if (!editions.checkBeforeOpening(key)) return null
        val plan = editions.plan(key) ?: return null
        val session = progress.newSession(key)
        val stored = progress.read(key)
        val saved = stored?.let { Saved(it.positionMs, it.stopKind, it.updatedAt, it.finished) }
        if (stored?.pendingContinuityChoice == true && explicitStartMs == null) return null
        val start = explicitStartMs?.let { plan.timeline.clamp(it) }
            ?: saved?.let { ResumeRewind.startPosition(it.positionMs, it.stopKind, it.updatedAt, clock.wallMillis(), it.finished) }?.coerceIn(0, plan.timeline.durationMs)
            ?: 0L
        outgoing?.let { editions.release(it.plan.generationId) }
        editions.acquire(plan.generationId)
        loaded = Loaded(plan, session, saved)
        startResolved = explicitStartMs == null
        failed = false
        pendingSeek = false
        lastCommittedPosition = C.TIME_UNSET
        settings.setLastActiveBook(key)
        if (explicitStartMs != null || saved?.finished == true) {
            // Moving the position (a chapter choice, Replay) makes the next start exact; Replay also
            // resets the finished state in the same write.
            save(start, StopKind.EXACT, finished = false, listened = false)
        }
        publish()
        return playableItem(plan) to start
    }

    fun playableItem(plan: EditionPlan): MediaItem = items.playable(plan)

    // --- Starting playback ---------------------------------------------------------------------

    /**
     * Every way of starting playback passes through here (the forwarding player's play()). Applies
     * the resume rewind once, from the saved place, and replays a finished book from zero.
     */
    fun beforePlay() {
        val book = loaded ?: return
        expireSleepIfDue()
        if (failed) {
            // The next play surfaces a failure again unless the edition was repaired.
            return
        }
        if (exo.playbackState == Player.STATE_ENDED || book.saved?.finished == true) {
            exo.seekTo(0)
            save(0, StopKind.EXACT, finished = false, listened = false)
            startResolved = true
            return
        }
        if (startResolved || exo.playWhenReady) return
        val saved = book.saved ?: return
        val start = ResumeRewind.startPosition(saved.positionMs, saved.stopKind, saved.updatedAt, clock.wallMillis())
            .coerceIn(0, book.durationMs)
        // Restores the saved place exactly (an end-of-chapter stop lands a few milliseconds past
        // its boundary) and applies the rewind after a rest.
        if (kotlin.math.abs(exo.currentPosition - start) > 20) {
            exo.seekTo(start)
        }
        startResolved = true
    }

    // --- Player events -------------------------------------------------------------------------

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startResolved = false
            failed = false
        } else if (wasPlaying && exo.playbackState != Player.STATE_ENDED && exo.playerError == null) {
            val kind = pauseCause ?: StopKind.ORDINARY
            pauseCause = null
            checkpoint(kind, listened = true)
        }
        wasPlaying = isPlaying
        publish()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            // Invalidate samples from before the seek; commit the resolved position once ready there.
            pendingSeek = true
            retargetSleepAfterSeek(newPosition.positionMs)
            if (exo.playbackState == Player.STATE_READY) commitSeek()
        }
        publish()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> if (pendingSeek) commitSeek()
            Player.STATE_ENDED -> onBookEnded()
            else -> Unit
        }
        publish()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        settings.setSpeed(playbackParameters.speed)
        publish()
    }

    override fun onPlayerError(error: PlaybackException) {
        val book = loaded ?: return
        failed = true
        // A failed seek keeps the place saved before it; a failure while playing keeps the last
        // rendered position, snapped to a track boundary the failing track starts at.
        val position = if (pendingSeek) null else snapToBoundary(exo.currentPosition)
        pendingSeek = false
        if (position != null) save(position, StopKind.ORDINARY, listened = true)
        scope.launch { editions.markRepairNeeded(book.key, book.plan.generationId) }
        publish()
    }

    private fun commitSeek() {
        pendingSeek = false
        val kind = if (exo.playWhenReady) StopKind.ORDINARY else StopKind.EXACT
        save(exo.currentPosition, kind, listened = false)
    }

    private fun onBookEnded() {
        val book = loaded ?: return
        save(book.durationMs, StopKind.EXACT, finished = true, listened = true)
        clearSleep()
        exo.playWhenReady = false
    }

    /** Positions within half a second before a track edge resolve to the edge itself. */
    private fun snapToBoundary(positionMs: Long): Long {
        val timeline = loaded?.plan?.timeline ?: return positionMs
        val p = timeline.clamp(positionMs)
        val track = timeline.tracks.firstOrNull { p >= it.endMs - BOUNDARY_SNAP_MS && p <= it.endMs }
        return track?.endMs?.coerceAtMost(timeline.durationMs) ?: p
    }

    // --- Saving --------------------------------------------------------------------------------

    /** Saves the current position of the loaded book if the player still holds it. */
    fun checkpoint(kind: StopKind, listened: Boolean) {
        val book = loaded ?: return
        if (!playerHolds(book)) return
        if (pendingSeek) return
        save(exo.currentPosition, kind, listened = listened)
    }

    private fun save(positionMs: Long, kind: StopKind, finished: Boolean = false, listened: Boolean) {
        val book = loaded ?: return
        val position = book.plan.timeline.clamp(positionMs)
        val token = book.session.next()
        book.saved = Saved(position, kind, clock.wallMillis(), finished)
        lastCommittedPosition = position
        writer.write(book.key, token, position, book.durationMs, book.plan.fingerprint, kind, finished, listened)
    }

    private fun playerHolds(book: Loaded): Boolean = exo.currentMediaItem?.mediaId == book.key.mediaId

    private suspend fun tickLoop() {
        while (scope.isActive) {
            delay(TICK_MS)
            val book = loaded
            if (book != null && exo.isPlaying && playerHolds(book) && !pendingSeek) {
                val position = exo.currentPosition
                if (lastCommittedPosition == C.TIME_UNSET || kotlin.math.abs(position - lastCommittedPosition) >= CHECKPOINT_MEDIA_MS) {
                    save(position, StopKind.ORDINARY, listened = true)
                }
            }
            expireSleepIfDue()
            publish()
        }
    }

    // --- Sleep ---------------------------------------------------------------------------------

    fun setSleepOff() = clearSleep().also { publish() }

    fun setSleepTimed(minutes: Int) {
        clearSleep()
        sleep = SleepState.Timed(minutes, clock.elapsedRealtimeMillis() + minutes * 60_000L)
        publish()
    }

    /** End of the current chapter, track or whole book, depending on the edition's division. */
    fun setSleepEndOfSegment() {
        val book = loaded ?: return
        clearSleep()
        armEndOf(book, exo.currentPosition)
        publish()
    }

    private fun armEndOf(book: Loaded, positionMs: Long) {
        val timeline = book.plan.timeline
        val boundary = timeline.sleepBoundaryFor(positionMs)
        val atEnd = boundary >= timeline.durationMs
        sleep = SleepState.EndOf(timeline.division, boundary, atEnd)
        if (!atEnd) {
            sleepMessage = exo.createMessage { _, _ -> onSleepBoundary(boundary) }
                .setLooper(Looper.getMainLooper())
                .setPosition(0, boundary)
                .setDeleteAfterDelivery(true)
                .send()
        }
    }

    private fun retargetSleepAfterSeek(positionMs: Long) {
        val book = loaded ?: return
        if (sleep is SleepState.EndOf) {
            sleepMessage?.cancel()
            sleepMessage = null
            armEndOf(book, positionMs)
        }
    }

    private fun onSleepBoundary(boundaryMs: Long) {
        val current = sleep as? SleepState.EndOf ?: return
        if (current.boundaryMs != boundaryMs) return
        sleepMessage = null
        sleep = SleepState.Off
        if (exo.playWhenReady) {
            exo.pause()
        }
        // Chapter, track and book modes commit the exact boundary.
        pauseCause = null
        save(boundaryMs, StopKind.EXACT, listened = true)
        publish()
    }

    private fun expireSleepIfDue() {
        val timed = sleep as? SleepState.Timed ?: return
        if (clock.elapsedRealtimeMillis() < timed.deadlineElapsedMs) return
        sleep = SleepState.Off
        if (exo.isPlaying) {
            // A timed stop of audible playback: the pause saves the place with the timed stop kind.
            pauseCause = StopKind.TIMED
            exo.pause()
        } else if (exo.playWhenReady) {
            // Expiry during a transient interruption: recovery must not start playback, and the saved
            // place and its stop kind stay as they were.
            exo.pause()
        }
        publish()
    }

    private fun clearSleep() {
        sleepMessage?.cancel()
        sleepMessage = null
        sleep = SleepState.Off
    }

    // --- Publishing ----------------------------------------------------------------------------

    private fun publish() {
        val book = loaded
        val position = if (book != null && playerHolds(book)) exo.currentPosition else book?.saved?.positionMs ?: 0L
        val remaining = when (val s = sleep) {
            is SleepState.Timed -> (s.deadlineElapsedMs - clock.elapsedRealtimeMillis()).coerceAtLeast(0)
            is SleepState.EndOf -> (s.boundaryMs - position).coerceAtLeast(0)
            SleepState.Off -> null
        }
        hub.publish(
            LivePlayback(
                key = book?.key,
                plan = book?.plan,
                isPlaying = exo.isPlaying,
                playWhenReady = exo.playWhenReady,
                positionMs = position,
                speed = exo.playbackParameters.speed,
                sleep = sleep,
                sleepRemainingMs = remaining,
                failed = failed,
                ended = exo.playbackState == Player.STATE_ENDED || book?.saved?.finished == true,
            ),
        )
    }

    companion object {
        const val TICK_MS = 250L

        /** A checkpoint at least every five seconds of media advancement; 4 s plus one tick at 2×. */
        const val CHECKPOINT_MEDIA_MS = 4_000L
        const val BOUNDARY_SNAP_MS = 500L

        fun divisionLabel(division: Division): String = when (division) {
            Division.CHAPTERS -> "End of chapter"
            Division.TRACKS -> "End of track"
            Division.WHOLE_BOOK -> "End of book"
        }
    }
}

/** Hands progress writes to the store. Tests substitute a writer that can hold a write back. */
fun interface ProgressWriter {
    fun write(
        key: BookKey,
        token: ProgressStore.Token,
        positionMs: Long,
        durationMs: Long,
        fingerprint: String?,
        stopKind: StopKind,
        finished: Boolean,
        listened: Boolean,
    )

    companion object {
        fun of(store: ProgressStore, scope: CoroutineScope) = ProgressWriter { key, token, position, duration, fingerprint, kind, finished, listened ->
            scope.launch { store.save(key, token, position, duration, fingerprint, kind, finished, listened) }
        }
    }
}
