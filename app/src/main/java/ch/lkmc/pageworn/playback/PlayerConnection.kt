package ch.lkmc.pageworn.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ProgressStore
import ch.lkmc.pageworn.ui.model.SleepChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why a tap on Play started nothing. Each has something on screen to show for it. */
enum class PlayRefusal {
    /** The audio changed since the place was saved: the listener chooses where to start first. */
    NEEDS_CHOICE,

    /** The copy on the phone is missing, short or marked for repair. */
    DAMAGED_COPY,

    /** The session or the system did not start playback, for example during a call. */
    DID_NOT_START,
}

data class PlayProblem(val key: BookKey, val refusal: PlayRefusal)

/**
 * The UI's command path to the playback service: one media controller, connected on demand. Live
 * state is observed through [PlaybackHub], which the service publishes from the same player the
 * system surfaces see.
 *
 * Every tap is carried out in tap order, in a scope that outlives the screen it was made on, and
 * ends either in the state its key promised or in a [problem].
 */
class PlayerConnection(
    private val context: Context,
    private val hub: PlaybackHub,
    private val editions: EditionStore,
    private val progress: ProgressStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private val connecting = Mutex()
    private val commands = Mutex()

    /** The newest Play or Pause tap; an older one no longer reports how it ended. */
    private var newestTap = 0L

    private val _problem = MutableStateFlow<PlayProblem?>(null)
    val problem: StateFlow<PlayProblem?> = _problem.asStateFlow()

    private suspend fun connected(): MediaController = connecting.withLock {
        controller?.takeIf { it.isConnected } ?: run {
            controller?.release()
            MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            ).buildAsync().await().also { controller = it }
        }
    }

    fun release() {
        controller?.release()
        controller = null
        newestTap++
        _problem.value = null
        hub.endStart()
    }

    fun dismissProblem() {
        _problem.value = null
    }

    /**
     * A tap on a key of [key] that showed Play. The screen can trail the player by a frame or more,
     * so the book may be starting already; that tap must not pause it. The job ends when the start
     * has been reported, and is null when there was nothing to do.
     */
    fun playFromKey(key: BookKey): Job? = if (hub.state.value.showsPause(key)) null else play(key)

    /** A tap on a key of [key] that showed Pause. It pauses that book and no other. */
    fun pauseFromKey(key: BookKey): Job? = if (hub.state.value.showsPause(key)) pause() else null

    /** Starts [key] from its saved place, or exactly at [fromMs] (a chapter choice moves the place). */
    fun play(key: BookKey, fromMs: Long? = null): Job {
        val tap = ++newestTap
        _problem.value = null
        hub.expectStart(key)
        return scope.launch {
            val refusal = inOrder<PlayRefusal?>(PlayRefusal.DID_NOT_START) { start(key, fromMs) }
            val started = refusal == null && withTimeoutOrNull(START_TIMEOUT_MS) {
                hub.state.first { it.key == key && it.held && (it.playbackRequested || it.failed) }
            } != null
            if (tap != newestTap) return@launch
            // The problem first, so the book it names stays shown.
            if (!started) _problem.value = PlayProblem(key, refusal ?: PlayRefusal.DID_NOT_START)
            hub.endStart()
        }
    }

    fun pause(): Job {
        newestTap++
        _problem.value = null
        hub.endStart()
        return launchInOrder { connected().pause() }
    }

    private suspend fun start(key: BookKey, fromMs: Long?): PlayRefusal? {
        val c = connected()
        val live = hub.state.value
        if (live.key == key && !live.failed && c.currentMediaItem?.mediaId == key.mediaId) {
            if (fromMs != null) c.seekTo(fromMs)
            c.play()
            return null
        }
        // The session refuses these too, but a controller is never told of a rejected item: the
        // Play queued behind it would go to whatever book the player held before.
        if (!editions.checkBeforeOpening(key)) return PlayRefusal.DAMAGED_COPY
        if (fromMs == null && progress.read(key)?.pendingContinuityChoice == true) return PlayRefusal.NEEDS_CHOICE
        val item = MediaItem.Builder().setMediaId(key.mediaId).build()
        if (fromMs == null) c.setMediaItem(item) else c.setMediaItem(item, fromMs)
        c.prepare()
        c.play()
        return null
    }

    /**
     * Moves [key] when this command's turn comes: [inPlayer] if the player holds it then, otherwise
     * [atRest]. Which book that is cannot be decided at the tap, because a Play queued before it
     * changes the answer. Taking the turn also keeps a resting move out of a book's loading, where
     * its write would outdate the session that plays it.
     */
    fun moveBook(key: BookKey, inPlayer: (MediaController) -> Unit, atRest: suspend () -> Unit): Job = launchInOrder {
        val handedOver = controller?.takeIf { it.isConnected }?.currentMediaItem?.mediaId
        if (handedOver != null) {
            // The controller has a book as soon as it is sent, the session once it has loaded it.
            withTimeoutOrNull(START_TIMEOUT_MS) { hub.state.first { it.key?.mediaId == handedOver } }
        }
        if (hub.state.value.key == key) inPlayer(connected()) else atRest()
    }

    fun setSpeed(speed: Float) = launchInOrder { connected().setPlaybackSpeed(speed) }

    /** Sets sleep for [key]. A book at rest is loaded paused first, so the timer has a book to stop. */
    fun setSleep(key: BookKey, choice: SleepChoice) = launchInOrder {
        val c = connected()
        if (hub.state.value.key != key) {
            if (choice == SleepChoice.OFF || !editions.checkBeforeOpening(key)) return@launchInOrder
            if (progress.read(key)?.pendingContinuityChoice == true) return@launchInOrder
            c.pause()
            c.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build())
            c.prepare()
            // A custom command overtakes queued player commands: wait until the player has the book.
            withTimeoutOrNull(START_TIMEOUT_MS) { hub.state.first { it.key == key && it.held } } ?: return@launchInOrder
        }
        c.sendCustomCommand(SessionCommand(PagewornCommands.SET_SLEEP, Bundle.EMPTY), sleepArguments(choice)).await()
    }

    /** Pauses and unloads [key] and waits for its readers to go, so its audio can be removed. */
    suspend fun releaseBook(key: BookKey) = withContext(Dispatchers.Main.immediate) {
        commands.withLock {
            if (hub.state.value.key != key) return@withLock
            val c = connected()
            c.pause()
            c.stop()
            c.clearMediaItems()
            withTimeoutOrNull(START_TIMEOUT_MS) { hub.state.first { it.key != key } }
            Unit
        }
    }

    private fun sleepArguments(choice: SleepChoice): Bundle {
        val args = Bundle()
        when (choice) {
            SleepChoice.OFF -> args.putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_OFF)
            SleepChoice.END_OF_SEGMENT -> args.putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_END_OF_SEGMENT)
            else -> {
                args.putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_TIMED)
                args.putInt(
                    PagewornCommands.ARG_SLEEP_MINUTES,
                    when (choice) {
                        SleepChoice.MINUTES_15 -> 15
                        SleepChoice.MINUTES_30 -> 30
                        SleepChoice.MINUTES_45 -> 45
                        else -> 60
                    },
                )
            }
        }
        return args
    }

    private fun launchInOrder(command: suspend () -> Unit): Job = scope.launch { inOrder(Unit) { command() } }

    private suspend fun <T> inOrder(onFailure: T, command: suspend () -> T): T = commands.withLock {
        try {
            command()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "playback command failed", failure)
            onFailure
        }
    }

    private companion object {
        const val TAG = "PagewornPlayback"

        /** Longer than a cold service start and the lookup of the book's files. */
        const val START_TIMEOUT_MS = 5_000L
    }
}
