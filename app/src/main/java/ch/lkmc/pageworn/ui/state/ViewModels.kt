package ch.lkmc.pageworn.ui.state

import kotlinx.coroutines.delay
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.sleep.SleepSchedule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.account.ServerNotice
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.PlayProblem
import ch.lkmc.pageworn.playback.PlayRefusal
import ch.lkmc.pageworn.ui.model.Destination
import ch.lkmc.pageworn.ui.model.DownloadedUiState
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.MiniPlayerUi
import ch.lkmc.pageworn.ui.model.PlayerUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.LinkedHashMap

/** Caches edition plans (timeline, files, cover) by generation for row labels and the player. */
class PlanCache(private val container: AppContainer) {
    // Access-ordered so the most recently used generations survive a long session; every other
    // generation is re-read from Room when it becomes current again.
    private val plans = object : LinkedHashMap<String, EditionPlan>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EditionPlan>): Boolean = size > MAX_CACHED_PLANS
    }
    private val lock = Any()

    suspend fun plan(edition: LocalEditionEntity): EditionPlan? =
        synchronized(lock) { plans[edition.generationId] } ?: run {
            val fresh = container.editionStore.anyPlan(edition.key) ?: return null
            synchronized(lock) { plans[fresh.generationId] = fresh }
            fresh
        }

    private companion object {
        const val MAX_CACHED_PLANS = 16
    }

    suspend fun timelines(editions: List<LocalEditionEntity>): Map<String, BookTimeline> =
        editions.mapNotNull { e -> plan(e)?.let { e.generationId to it.timeline } }.toMap()
}

/** The book the mini-player and full player show: the loaded one, or the last active book at rest. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(private val container: AppContainer) : ViewModel() {
    private val plans = PlanCache(container)
    private val connection get() = container.playerConnection

    /** Why the last tap on Play started nothing. */
    val problem: StateFlow<PlayProblem?> = connection.problem

    private val shownKey: Flow<BookKey?> = combine(
        container.playbackHub.state.map { it.starting ?: it.key }.distinctUntilChanged(),
        container.settings.lastActiveBook,
        problem,
    ) { active, last, problem ->
        problem?.takeIf { it.refusal == PlayRefusal.NEEDS_CHOICE }?.key ?: active ?: last
    }.distinctUntilChanged()

    private data class Shown(val plan: EditionPlan, val edition: LocalEditionEntity?, val progress: BookProgressEntity?)

    private val shown: Flow<Shown?> = shownKey.flatMapLatest { key ->
        if (key == null) {
            flowOf(null)
        } else {
            combine(
                container.database.editions().observeEdition(key.profileId, key.itemId),
                container.database.progress().observe(key.profileId, key.itemId),
            ) { edition, progress ->
                edition?.let { e -> plans.plan(e)?.let { Shown(it, e, progress) } }
            }
        }
    }

    private val playerStates = combine(shown, container.playbackHub.state, container.sleepSettings.settings) { s, live, sleep ->
        s?.let { PlayerStates.player(it.plan, it.edition, it.progress, live, sleep) }
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    val player: StateFlow<PlayerUiState?> = playerStates.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True once [player] reflects local state, so null then means there is no book to show. */
    val playerLoaded: StateFlow<Boolean> = playerStates.map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val mini: StateFlow<MiniPlayerUi?> = combine(shown, container.playbackHub.state) { s, live ->
        s?.let { PlayerStates.mini(it.plan, it.edition, it.progress, live) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // A schedule edge can pass while nothing plays and no playback service runs to notice it,
        // so the mode is re-checked when it is due: at the window's next edge, not on a fixed poll.
        viewModelScope.launch {
            container.sleepSettings.settings.collect { mode ->
                container.sleepSettings.refresh()
                val now = container.liveClock.wallMillis()
                val delayMs = if (mode.automatic) {
                    (SleepSchedule.nextEdge(mode.window, now, ZoneId.systemDefault()).atMillis - now)
                        .coerceIn(1, MAX_SCHEDULE_WAIT_MS)
                } else SCHEDULE_IDLE_WAIT_MS
                delay(delayMs)
            }
        }
    }

    private fun currentKey(): BookKey? = player.value?.face?.key

    /** [key] is the book of the key that was tapped, never whichever book is shown by now. */
    fun playPause(key: BookKey, action: KeyAction) {
        when (action) {
            KeyAction.PLAY -> connection.playFromKey(key)
            KeyAction.PAUSE -> connection.pauseFromKey(key)
        }
    }

    fun dismissProblem() = connection.dismissProblem()

    fun skipBack() {
        val key = currentKey() ?: return
        container.bookTransport.skipBack(key)
    }

    fun skipForward() {
        val key = currentKey() ?: return
        container.bookTransport.skipForward(key)
    }

    fun seekTo(positionMs: Long) {
        val key = currentKey() ?: return
        container.bookTransport.seekTo(key, positionMs)
    }

    fun selectSegment(startMs: Long) {
        val key = currentKey() ?: return
        connection.play(key, startMs)
    }

    /** A chapter row in a book's details starts that book there. */
    fun playFrom(key: BookKey, startMs: Long) {
        connection.play(key, startMs)
    }

    fun setSpeed(speed: Float) {
        container.settings.setSpeed(speed)
        connection.setSpeed(speed)
    }

    /** Turns sleep mode on with [length], or off for null. */
    fun setSleep(length: SleepLength?) {
        connection.setSleep(length)
    }

    private val transferActions = TransferActions(container)

    /** Repair download of the shown book: stages a fresh generation and keeps the saved place. */
    fun repair() {
        viewModelScope.launch { shownKey.first()?.let(transferActions::download) }
    }

    /** After changed audio: keep the previous position (exact) or start over. */
    fun resolveContinuity(usePrevious: Boolean) {
        val key = currentKey() ?: return
        viewModelScope.launch {
            val plan = container.editionStore.anyPlan(key) ?: return@launch
            val previous = container.progressStore.read(key)?.positionMs ?: 0L
            val position = if (usePrevious) plan.timeline.clamp(previous) else 0L
            container.progressStore.saveOutsideSession(key, position, plan.timeline.durationMs, plan.fingerprint, StopKind.EXACT)
        }
    }
}

class DownloadedViewModel(private val container: AppContainer) : ViewModel() {
    private val plans = PlanCache(container)
    private val actions = TransferActions(container)

    /**
     * The player publishes its position every 250 ms, but a downloaded row shows a minute-scale
     * remaining time. Coarsen the clock and skip unchanged states so a playing book does not
     * rebuild and re-sort the whole list four times a second (Library already does the same).
     */
    private val rowsLive = container.playbackHub.state
        .map { it.rowDisplay() }
        .distinctUntilChanged { a, b -> a.sameRowsAs(b) }

    fun pauseTransfer(jobId: String) = actions.pause(jobId)
    fun resumeTransfer(jobId: String) = actions.resume(jobId)
    fun cancelTransfer(jobId: String) = actions.cancel(jobId)

    private val showings = MutableStateFlow(0)

    /** Downloaded came on screen: the order catches up with what was played meanwhile. */
    fun onShown() {
        showings.value++
    }

    // The order on screen and the showing it belongs to; only the one collector of [state] uses them.
    private var heldOrder: List<BookKey> = emptyList()
    private var orderedFor = -1

    val state: StateFlow<DownloadedUiState> = combine(
        container.editionStore.observeEverything(),
        combine(container.database.progress().observeEverything(), showings) { progress, showing -> progress to showing },
        container.database.transfers().observeActive(),
        rowsLive,
        container.notices.downloadedStatus,
    ) { editions, (progress, showing), transfers, live, status ->
        if (showing != orderedFor) heldOrder = emptyList()
        val (transferRows, bookRows) = RowBuilders.downloadedRows(
            editions = editions,
            dirOf = { container.editionFiles.published(it.generationId) },
            progress = progress.associateBy { it.key },
            timelines = plans.timelines(editions),
            transfers = transfers,
            live = live,
            heldOrder = heldOrder,
        )
        heldOrder = bookRows.map { it.face.key }
        orderedFor = showing
        DownloadedUiState(
            transfers = transferRows,
            books = bookRows,
            status = status,
            empty = if (transferRows.isEmpty() && bookRows.isEmpty()) RowBuilders.noDownloads() else null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadedUiState())
}

/**
 * Which main section shows. A cold launch decides from local state alone (A17): Downloaded when a
 * downloaded book exists, otherwise the section shown last, and Library when nothing is local or
 * cached. A warm start keeps this view model and so keeps the section. Without a profile the
 * launch opens connection setup; with nothing local or cached, a failed first refresh opens it in
 * the state that blocked it.
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val remembered = container.settings.lastDestination?.let { runCatching { Destination.valueOf(it) }.getOrNull() } ?: Destination.LIBRARY
    private val _destination = MutableStateFlow(remembered)
    val destination: StateFlow<Destination> = _destination

    /** False until the cold-launch decision is made, so the wrong section never flashes. */
    private val _decided = MutableStateFlow(false)
    val decided: StateFlow<Boolean> = _decided

    /** Connection setup to open over the main sections, or null. */
    private val _connectMode = MutableStateFlow<ConnectMode?>(null)
    val connectMode: StateFlow<ConnectMode?> = _connectMode
    private val _blockedAddressId = MutableStateFlow<String?>(null)
    val blockedAddressId: StateFlow<String?> = _blockedAddressId

    init {
        viewModelScope.launch {
            val profile = container.database.profiles().current()
            val local = container.editionStore.observeEverything().first().isNotEmpty()
            val cached = profile != null && container.database.libraries().forProfile(profile.profileId).any { it.publishedRefreshId != null }
            when {
                local -> _destination.value = Destination.DOWNLOADED
                profile != null && !cached -> _destination.value = Destination.LIBRARY
            }
            if (profile == null) _connectMode.value = ConnectMode.FIRST
            _decided.value = true
            if (profile != null && !local && !cached) awaitLaunchBlock(profile.profileId)
        }
    }

    /** Waits for the launch refresh to publish a catalog or to fail in a state that blocks it. */
    private suspend fun awaitLaunchBlock(profileId: String) {
        val (libraries, notice) = combine(container.database.libraries().observe(profileId), container.accounts.notice) { libraries, notice -> libraries to notice }
            .first { (libraries, notice) -> libraries.any { it.publishedRefreshId != null } || blocksLaunch(notice) }
        if (libraries.any { it.publishedRefreshId != null }) return

        val addresses = container.database.addresses().forProfile(profileId)
        _blockedAddressId.value = when (notice) {
            is ServerNotice.LocalNetworkDenied -> addresses.firstOrNull { it.label == notice.label }?.addressId
            is ServerNotice.CertificateChanged -> addresses.firstOrNull { it.label == notice.label }?.addressId
            else -> addresses.firstOrNull()?.addressId
        }
        _connectMode.value = ConnectMode.LAUNCH_BLOCKED
    }

    private fun blocksLaunch(notice: ServerNotice?): Boolean =
        notice is ServerNotice.Unreachable || notice is ServerNotice.LocalNetworkDenied || notice is ServerNotice.CertificateChanged

    fun connectFinished() { _connectMode.value = null }

    fun select(destination: Destination) {
        if (destination != _destination.value) container.notices.leftDownloaded(_destination.value, destination)
        _destination.value = destination
        container.settings.lastDestination = destination.name
    }
}

/** How often the schedule is re-checked while the automatic window is off. */
private const val SCHEDULE_IDLE_WAIT_MS = 60_000L

/** The longest wait for the next window edge, so a clock jump cannot stall the check. */
private const val MAX_SCHEDULE_WAIT_MS = 10 * 60_000L
