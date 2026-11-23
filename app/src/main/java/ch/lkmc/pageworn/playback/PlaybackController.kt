package ch.lkmc.pageworn.playback

import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.progress.ResumeRewind
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ProgressStore
import ch.lkmc.pageworn.data.SettingsStore
import ch.lkmc.pageworn.data.SleepSettings
import ch.lkmc.pageworn.data.SleepSettingsStore
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
    private val sleepSettings: SleepSettingsStore,
    private val clock: Clock,
    private val hub: PlaybackHub,
    private val writer: ProgressWriter,
    private val items: BookItems,
    private val bookSources: BookMediaSourceFactory,
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

    /**
     * The stop kind the pending seek commits with: ordinary when it was made during playback, exact
     * when made while paused, timed once a timer stops playback before the seek completes.
     */
    private var pendingSeekKind = StopKind.EXACT

    /** Audio played since the book was loaded or last moved; a failure before that saves nothing. */
    private var played = false
    private var lastCommittedPosition = C.TIME_UNSET
    private var wasPlaying = false

    /** The save of the last pause, for as long as it is the book's latest one. */
    private var pauseSaved: Saved? = null

    /** Stop kind for the pause the controller itself is about to cause (a timer). */
    private var pauseCause: StopKind? = null

    var sleep: SleepState = SleepState.Off
        private set
    private var sleepMessage: PlayerMessage? = null
    private var clippedAtMs: Long? = null
    private var failed = false
    private var tickJob: Job? = null

    /** A start of playback whose timer starts once the player holds the book and plays it. */
    private var timerOnPlaying = false

    /** Lowers the volume over the last seconds of a timer; null while no fade runs. */
    private var fadeJob: Job? = null

    /** Sleep mode as last seen, so the controller notices the mode turning on. */
    private var modeOn = sleepSettings.settings.value.enabled
    private var lastScheduleCheck = Long.MIN_VALUE

    init {
        exo.addListener(this)
        exo.setPlaybackSpeed(settings.speed.value)
        tickJob = scope.launch { tickLoop() }
        scope.launch { sleepSettings.settings.collect(::onSleepSettings) }
    }

    fun release() {
        tickJob?.cancel()
        stopFade()
        saveSettledPause()
        checkpointIfPlaying()
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
            saveSettledPause()
            checkpointIfPlaying()
            clearSleep()
            clippedAtMs = null
            // Playback goes on with the new book: that is a start, with a fresh timer.
            if (exo.playWhenReady) timerOnPlaying = true
        }
        if (outgoing != null && outgoing.key == key && !failed) {
            val start = explicitStartMs?.let { outgoing.plan.timeline.clamp(it) }
                // Asked again before the player took the book: its position is still another item's.
                ?: if (playerHolds(outgoing)) exo.currentPosition else outgoing.saved?.positionMs ?: 0L
            if (explicitStartMs == null) startResolved = false
            else retargetSleepAfterSeek(start)
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
        played = false
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

    fun playableItem(plan: EditionPlan): MediaItem =
        items.playable(plan, clippedAtMs.takeIf { loaded?.key == plan.key })

    // --- Starting playback ---------------------------------------------------------------------

    /**
     * Every way of starting playback passes through here (the forwarding player's play()). Applies
     * the resume rewind once, from the saved place, and replays a finished book from zero.
     */
    fun beforePlay() {
        // A fade that stopped the last play left the volume low.
        restoreVolume()
        val book = loaded
        if (book == null) {
            timerOnPlaying = true
            return
        }
        expireSleepIfDue()
        resolveStart(book)
        startTimer(book)
    }

    private fun resolveStart(book: Loaded) {
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
        if (pendingSeek) {
            // A seek that has not settled yet is the listener's chosen place; restoring the place
            // saved before it would undo it. It commits as an exact stop once the player is ready.
            startResolved = true
            return
        }
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
            played = true
            if (fadeJob == null) restoreVolume()
            if (timerOnPlaying) loaded?.let(::startTimer)
        } else if (wasPlaying && exo.playbackState != Player.STATE_ENDED && exo.playerError == null) {
            val kind = pauseCause ?: StopKind.ORDINARY
            pauseCause = null
            val before = loaded?.saved
            checkpoint(kind, listened = true)
            pauseSaved = loaded?.saved?.takeIf { it !== before }
        }
        wasPlaying = isPlaying
        publish()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Any pause ends the timer; the next start runs a fresh one.
        if (!playWhenReady) {
            timerOnPlaying = false
            clearSleep()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        // Delivered once the session's own listener has the change, so what the UI is told never
        // runs ahead of what controllers are told.
        if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)) publish()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            // Invalidate samples from before the seek; commit the resolved position once ready there.
            // From here the player's position is the seek's, also if that seek fails: no pause settles there.
            pauseSaved = null
            pendingSeek = true
            pendingSeekKind = if (exo.playWhenReady) StopKind.ORDINARY else StopKind.EXACT
            played = false
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

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        val book = loaded ?: return
        if (exo.mediaItemCount == 0) {
            // The book was stopped and cleared (before removal): release its reader and timer.
            clearSleep()
            clippedAtMs = null
            editions.release(book.plan.generationId)
            loaded = null
            failed = false
        }
        publish()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        settings.setSpeed(playbackParameters.speed)
        publish()
    }

    /**
     * A decode, demux or read failure that the loader's single retry did not cure (playback failure
     * handling in docs/05-playback-and-formats.md). The player stops with the error; this pauses it,
     * so neither the end of an interruption nor a pending resume restarts it, and keeps the place:
     *
     * - A failure during playback saves the last rendered position. A track that cannot be opened
     *   fails once the one before it has played to its end, so a position within half a second of
     *   that boundary is saved as the boundary itself: the failing track at offset zero.
     * - A failed seek keeps the place saved before it, and a failure before any audio played (a
     *   resume into a track that cannot be opened) keeps the saved place.
     * - An End of track or End of chapter timer whose boundary is that same track boundary fires
     *   first and completes normally; the failure surfaces on the next play, as does any failure
     *   that arrives while no play is requested.
     *
     * The edition becomes Repair needed. The failure pause ends any other timer, like every pause.
     */
    override fun onPlayerError(error: PlaybackException) {
        val book = loaded ?: return
        val requested = exo.playWhenReady
        val seekFailed = pendingSeek
        pendingSeek = false
        val timer = sleep as? SleepState.EndOf
        exo.playWhenReady = false
        val position = snapToBoundary(exo.currentPosition)
        Log.i(TAG, "playback failed (${error.errorCodeName}) at ${exo.currentPosition} ms, place $position ms, requested=$requested seek=$seekFailed played=$played")
        if (!seekFailed && played && timer != null && timer.boundaryMs == position) {
            onSleepBoundary(position)
            return
        }
        if (!requested) {
            publish()
            return
        }
        failed = true
        if (!seekFailed && played) save(position, StopKind.ORDINARY, listened = true)
        played = false
        scope.launch { editions.markRepairNeeded(book.key, book.plan.generationId) }
        publish()
    }

    private fun commitSeek() {
        pendingSeek = false
        val book = loaded
        if (book != null && exo.currentPosition >= book.durationMs - END_OF_BOOK_SEEK_MS) {
            // An explicit seek to the end of the book finishes it. ExoPlayer resolves a seek there
            // to just before the end and, while paused, stays ready instead of ending.
            save(book.durationMs, StopKind.EXACT, finished = true, listened = false)
            clearSleep()
            setAudioEnd(null, book.durationMs)
            return
        }
        save(exo.currentPosition, pendingSeekKind, listened = false)
    }

    private fun onBookEnded() {
        val book = loaded ?: return
        val clippedEnd = clippedAtMs
        if (clippedEnd != null) {
            onSleepBoundary(clippedEnd)
            return
        }
        save(book.durationMs, StopKind.EXACT, finished = true, listened = true)
        clearSleep()
        exo.playWhenReady = false
    }

    /**
     * Positions within half a second of an internal track boundary resolve to the boundary itself.
     * A track that cannot be opened fails when the one before it has been read to its end, with up
     * to a few hundred milliseconds of it still in the audio output, or just after the player's clock
     * has crossed into it (observed on both emulators: 27 to 37 ms past the boundary). Either way the
     * place is the failing track at offset zero.
     */
    private fun snapToBoundary(positionMs: Long): Long {
        val timeline = loaded?.plan?.timeline ?: return positionMs
        val p = timeline.clamp(positionMs)
        val boundary = timeline.tracks.drop(1).map { it.startMs }.firstOrNull { kotlin.math.abs(p - it) <= BOUNDARY_SNAP_MS }
        return boundary ?: p
    }

    // --- Saving --------------------------------------------------------------------------------

    /** Saves the current position of the loaded book if the player still holds it. */
    fun checkpoint(kind: StopKind, listened: Boolean) {
        val book = loaded ?: return
        if (!playerHolds(book)) return
        if (pendingSeek) return
        // A finished book keeps its finished place until Replay or a seek moves it; switching to
        // another book after the end must not store it as unfinished at its last position.
        if (book.saved?.finished == true) return
        save(exo.currentPosition, kind, listened = listened)
    }

    /**
     * The checkpoint when playing audio is cut off by a book switch or by the service going away. A
     * book at rest was saved when it stopped; another write would restart its rest and replace how
     * it stopped, and with that the resume rewind.
     */
    private fun checkpointIfPlaying() {
        if (exo.isPlaying) checkpoint(StopKind.ORDINARY, listened = true)
    }

    /**
     * The player reports where audio really stopped a moment after it reports the pause, up to
     * about 120 ms on. Moves the pause's place there and keeps how it stopped.
     */
    private fun saveSettledPause() {
        val book = loaded ?: return
        val paused = pauseSaved ?: return
        if (book.saved !== paused || exo.isPlaying) {
            pauseSaved = null
            return
        }
        if (pendingSeek || !playerHolds(book)) return
        val settled = book.plan.timeline.clamp(exo.currentPosition)
        if (settled == paused.positionMs) return
        save(settled, paused.stopKind, listened = true)
        pauseSaved = book.saved
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
            saveSettledPause()
            checkSchedule()
            expireSleepIfDue()
            startFadeIfDue()
            publish()
        }
    }

    // --- Sleep ---------------------------------------------------------------------------------
    //
    // Sleep mode lives in [sleepSettings]; this runs its timers (sleep behavior in
    // docs/05-playback-and-formats.md). Every start of playback runs a fresh timer while the mode is
    // on, any pause ends it, and the last seconds before a stop fade the volume out.

    /** Turns sleep mode on with [length], or off for null. A book that is playing starts a fresh timer. */
    fun setSleep(length: SleepLength?) {
        // Set first: the store's collector may run inline and would otherwise arm a second time.
        modeOn = length != null
        sleepSettings.choose(length)
        if (length == null) {
            sleepSettings.forgetStop()
            clearSleep()
            setAudioEnd(null, exo.currentPosition)
        } else if (exo.isPlaying) {
            loaded?.let { arm(it, length) }
        } else if (exo.playWhenReady) {
            timerOnPlaying = true
        }
        publish()
    }

    /**
     * Play or Pause during the fade keeps the book playing: the volume comes back at once and a fresh
     * timer of the same length starts. Returns false when no fade runs, so the command acts as usual.
     */
    fun keepPlayingDuringFade(): Boolean {
        val book = loaded
        if (fadeJob == null || !exo.playWhenReady || book == null) return false
        stopFade()
        restoreVolume()
        when (val timer = sleep) {
            SleepState.Off -> return false
            is SleepState.Timed -> sleep = timer.copy(deadlineElapsedMs = clock.elapsedRealtimeMillis() + timer.minutes * MINUTE_MS)
            is SleepState.EndOf -> {
                // The boundary itself belongs to the next segment, whose end is the new target.
                sleepMessage?.cancel()
                sleepMessage = null
                armEndOf(book, timer.boundaryMs, exo.currentPosition)
            }
        }
        publish()
        return true
    }

    /**
     * A start of playback: runs a fresh timer when sleep mode is on, or when a timer stopped playback
     * moments ago. Without one, a clip left by an earlier end-of-chapter timer is removed before audio
     * can reach it.
     */
    private fun startTimer(book: Loaded) {
        timerOnPlaying = false
        if (!playerHolds(book)) {
            timerOnPlaying = true
            return
        }
        sleepSettings.refresh()
        val mode = sleepSettings.settings.value
        val length = if (mode.enabled) mode.length else sleepSettings.recentStop(RECENT_STOP_MS)
        if (length == null) {
            clearSleep()
            setAudioEnd(null, exo.currentPosition)
            publish()
            return
        }
        arm(book, length)
    }

    private fun arm(book: Loaded, length: SleepLength) {
        clearSleep()
        when (length) {
            is SleepLength.Minutes -> {
                setAudioEnd(null, exo.currentPosition)
                sleep = SleepState.Timed(length.minutes, clock.elapsedRealtimeMillis() + length.minutes * MINUTE_MS)
            }
            SleepLength.EndOfSegment -> armEndOf(book, exo.currentPosition, exo.currentPosition)
        }
        publish()
    }

    /** Sleep mode turned on, by the schedule or a settings change: a book that is playing gets its timer now. */
    private fun onSleepSettings(mode: SleepSettings) {
        val turnedOn = mode.enabled && !modeOn
        modeOn = mode.enabled
        if (!turnedOn || sleep != SleepState.Off) return
        val book = loaded ?: return
        if (exo.isPlaying) arm(book, mode.length) else if (exo.playWhenReady) timerOnPlaying = true
    }

    private fun checkSchedule() {
        val now = clock.elapsedRealtimeMillis()
        if (lastScheduleCheck != Long.MIN_VALUE && now - lastScheduleCheck in 0 until SCHEDULE_CHECK_MS) return
        lastScheduleCheck = now
        sleepSettings.refresh()
    }

    /**
     * Targets the end of the segment that contains [targetFromMs]. [playerPositionMs] is where the
     * player is, which a rebuilt source must keep.
     */
    private fun armEndOf(book: Loaded, targetFromMs: Long, playerPositionMs: Long) {
        val timeline = book.plan.timeline
        val boundary = timeline.sleepBoundaryFor(targetFromMs)
        val atEnd = boundary >= timeline.durationMs
        val insideTrack = !atEnd && timeline.tracks.none { it.startMs == boundary }
        sleep = SleepState.EndOf(timeline.division, boundary, atEnd)
        // The audio sink may have queued the next chapter before the pause reaches it. Clip
        // the current file and temporarily replace the remainder with silence so it cannot leak.
        setAudioEnd(boundary.takeIf { insideTrack }, playerPositionMs)
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
            armEndOf(book, positionMs, positionMs)
        }
    }

    private fun onSleepBoundary(boundaryMs: Long) {
        val current = sleep as? SleepState.EndOf ?: return
        if (current.boundaryMs != boundaryMs) return
        sleepMessage?.cancel()
        sleepMessage = null
        sleep = SleepState.Off
        stopFade()
        sleepSettings.recordStop(SleepLength.EndOfSegment)
        if (exo.playWhenReady) {
            exo.pause()
        }
        setAudioEnd(null, boundaryMs)
        // Chapter, track and book modes commit the exact boundary.
        pauseCause = null
        save(boundaryMs, StopKind.EXACT, listened = true)
        publish()
    }

    private fun expireSleepIfDue() {
        val timed = sleep as? SleepState.Timed ?: return
        if (clock.elapsedRealtimeMillis() < timed.deadlineElapsedMs) return
        sleep = SleepState.Off
        stopFade()
        sleepSettings.recordStop(timed.length)
        if (exo.isPlaying) {
            // A timed stop of audible playback: the pause saves the place with the timed stop kind.
            pauseCause = StopKind.TIMED
            exo.pause()
        } else if (exo.playWhenReady && exo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            // Expiry while playback waits for data (after a seek, or for a read retry): the timer
            // stops playback that was asked for, so it saves the timed stop kind, and the data
            // arriving later does not start playback.
            exo.pause()
            if (pendingSeek) pendingSeekKind = StopKind.TIMED else checkpoint(StopKind.TIMED, listened = true)
        } else if (exo.playWhenReady) {
            // Expiry during a transient interruption: recovery must not start playback, and the saved
            // place and its stop kind stay as they were.
            exo.pause()
        }
        publish()
    }

    /** Ends the running timer. Audio that goes on is heard at full volume again at once. */
    private fun clearSleep() {
        sleepMessage?.cancel()
        sleepMessage = null
        sleep = SleepState.Off
        stopFade()
        if (exo.isPlaying) restoreVolume()
    }

    /** Wall time until the running timer stops playback, or null when none runs. */
    private fun sleepRemainingMs(positionMs: Long = exo.currentPosition): Long? = when (val timer = sleep) {
        SleepState.Off -> null
        is SleepState.Timed -> timer.deadlineElapsedMs - clock.elapsedRealtimeMillis()
        is SleepState.EndOf -> ((timer.boundaryMs - positionMs) / exo.playbackParameters.speed.toDouble()).toLong()
    }

    private fun startFadeIfDue() {
        if (fadeJob != null || !exo.isPlaying) return
        val remaining = sleepRemainingMs() ?: return
        if (remaining > FADE_MS + TICK_MS) return
        fadeJob = scope.launch { fade() }
    }

    /**
     * Follows the time left with the volume, falling with its square so the last words do not drop
     * away all at once. A timed timer stops from here, on the fade's finer step; the chapter
     * message stops an end-of-chapter timer on its boundary. A seek that moves the end away ends
     * the fade at full volume.
     */
    private suspend fun fade() {
        while (true) {
            val remaining = sleepRemainingMs()
            if (remaining == null) {
                // The timer ended; whatever ended it decided the volume.
                fadeJob = null
                return
            }
            if (remaining > FADE_MS + TICK_MS) {
                fadeJob = null
                restoreVolume()
                return
            }
            val share = remaining.coerceIn(0, FADE_MS).toFloat() / FADE_MS
            exo.volume = share * share
            if (sleep is SleepState.Timed && remaining <= 0) {
                expireSleepIfDue()
                return
            }
            delay(FADE_STEP_MS)
        }
    }

    private fun stopFade() {
        fadeJob?.cancel()
        fadeJob = null
    }

    private fun restoreVolume() {
        if (exo.volume != 1f) exo.volume = 1f
    }

    private fun setAudioEnd(endMs: Long?, positionMs: Long) {
        if (clippedAtMs == endMs) return
        val book = loaded ?: return
        if (!playerHolds(book)) return
        clippedAtMs = endMs
        exo.setMediaSource(bookSources.createMediaSource(playableItem(book.plan)), positionMs)
        exo.prepare()
    }

    // --- Publishing ----------------------------------------------------------------------------

    private fun publish() {
        val book = loaded
        // After a failure the player may rest at a failed seek's target; the place is the saved one.
        val held = book != null && playerHolds(book)
        val position = if (book != null && held && !failed && exo.playerError == null) {
            book.plan.timeline.clamp(exo.currentPosition)
        } else book?.saved?.positionMs ?: 0L
        val remaining = sleepRemainingMs(position)?.coerceAtLeast(0)
        hub.publish(
            LivePlayback(
                key = book?.key,
                plan = book?.plan,
                // While a chosen book is handed over, the audio still heard is the previous book's.
                isPlaying = held && exo.isPlaying,
                playWhenReady = exo.playWhenReady,
                held = held,
                playbackRequested = !Util.shouldShowPlayButton(exo),
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
        private const val TAG = "PagewornPlayback"

        /**
         * A seek that lands this close to the end of the book is a seek to its end. A paused seek to
         * the end of the boundary book rests 1 ms before it; the window also covers the last frame
         * of every listener format.
         */
        private const val END_OF_BOOK_SEEK_MS = 250L
        const val TICK_MS = 250L

        /** A checkpoint at least every five seconds of media advancement; 4 s plus one tick at 2×. */
        const val CHECKPOINT_MEDIA_MS = 4_000L
        const val BOUNDARY_SNAP_MS = 500L

        private const val MINUTE_MS = 60_000L

        /** The volume falls to silence over the last three seconds before a timer stops playback. */
        const val FADE_MS = 3_000L
        private const val FADE_STEP_MS = 50L

        /** A Play this soon after a timer stopped playback runs a timer of the same length again. */
        const val RECENT_STOP_MS = 5 * 60_000L

        /** How often a playing service looks for a passed edge of the sleep schedule. */
        private const val SCHEDULE_CHECK_MS = 1_000L
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
