package ch.lkmc.pageworn.transfer

import androidx.room.withTransaction
import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.data.EditionAssembler
import ch.lkmc.pageworn.data.EditionFiles
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.FailureCategory
import ch.lkmc.pageworn.data.db.FileTransferState
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.PairingState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferFileEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.formats.FormatSupport
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ManifestSourceTrack
import ch.lkmc.pageworn.server.NoRoute
import ch.lkmc.pageworn.server.ServerError
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a Download tap on an Available or Repair needed book did. */
sealed interface DownloadStart {
    data class Enqueued(val jobId: String) : DownloadStart

    /** The book already had an active job; the tap attached to it (rule 1). */
    data class Attached(val jobId: String) : DownloadStart

    data class Refused(val reason: Refusal) : DownloadStart
}

/** Why a Download tap created no job (the Available row of the download lifecycle). */
sealed interface Refusal {
    data object DownloadsDisabled : Refusal
    data object UnsupportedFormat : Refusal

    /** The item has no audio the phone could stage, such as an ebook or excluded tracks only. */
    data object NoAudio : Refusal

    /** No address could serve the manifest; [reason] chooses the copy. */
    data class NoRoute(val reason: ch.lkmc.pageworn.server.NoRoute) : Refusal

    /** The server answered, but not with a manifest the contract accepts. */
    data class Server(val error: ServerError) : Refusal

    data object RemovalPending : Refusal
    data object AlreadyDownloaded : Refusal
}

/**
 * The transfer coordinator (docs/03-android-and-data.md, runtime ownership): a durable queue
 * that downloads one book at a time into a staging generation, continues partial files only as
 * the rules of safe continuation allow, verifies the result and publishes it once.
 *
 * Every state lives in Room, so a process death loses nothing: [recover] turns interrupted work
 * back into queued work, and the bytes on disk, not the journal, decide where a file continues.
 * [runQueue] runs inside the platform service hosting downloads. One run at a time holds
 * [runLock]. Pause and Cancel write their state first and then stop the running copy, which
 * keeps its written prefix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferCoordinator(
    private val db: PagewornDatabase,
    private val files: EditionFiles,
    private val editions: EditionStore,
    private val routes: TransferRoutes,
    private val verifier: TrackVerifier,
    private val formats: FormatSupport,
    private val clock: Clock,
    private val usableSpace: () -> Long = files::usableSpace,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onPublished: (title: String) -> Unit = {},
) {
    /** Set by the app after construction to start the platform service that runs [runQueue]. */
    @Volatile var scheduler: TransferScheduler = TransferScheduler { false }

    private val transfers get() = db.transfers()
    private val runLock = Mutex()
    private val recovery = Mutex()
    private var recovered = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val retryForAddressChange = AtomicBoolean()

    /** The job being copied now and its coroutine, so Pause and Cancel can stop it. */
    private class Running(val jobId: String, val job: Job)

    @Volatile private var running: Running? = null

    /** True while [runQueue] runs, including while it waits for a retry. */
    @Volatile private var queueRunning = false

    /** The latest progress of the running copy, written to Room by the flusher in [runOne]. */
    private class JournalEntry(val jobId: String, val ordinal: Int, val fileBytes: Long, val jobBytes: Long)

    private val pendingJournal = AtomicReference<JournalEntry?>(null)

    /** Ends a run whose job the listener paused or cancelled meanwhile. */
    private class StoppedByListener : CancellationException("The listener stopped this job")

    private val _active = MutableStateFlow<String?>(null)

    /** The job ID currently transferring or verifying, for the notification. */
    val active: StateFlow<String?> = _active.asStateFlow()

    // --- Listener actions --------------------------------------------------------------------

    /**
     * The Download action. Reads a fresh manifest, checks permission and format support, and
     * enqueues one job for the book. Space is checked by the job before its first audio request,
     * so a shortfall shows on the transfer row with Retry (A21b).
     */
    suspend fun download(key: BookKey): DownloadStart {
        transfers.active(key.profileId, key.itemId)?.let { return DownloadStart.Attached(it.jobId) }
        if (db.editions().pendingRemoval(key.profileId, key.itemId) != null) return DownloadStart.Refused(Refusal.RemovalPending)
        val edition = db.editions().edition(key.profileId, key.itemId)
        if (edition?.state == EditionState.READY) return DownloadStart.Refused(Refusal.AlreadyDownloaded)
        val profile = db.profiles().current()
        if (profile == null || profile.profileId != key.profileId) return DownloadStart.Refused(Refusal.NoRoute(NoRoute.SignedOut))
        if (!profile.canDownload) return DownloadStart.Refused(Refusal.DownloadsDisabled)

        val manifest = when (val read = routes.manifest(key.itemId)) {
            is Routed.Done -> read.value
            is Routed.Unavailable -> return DownloadStart.Refused(Refusal.NoRoute(read.reason))
            is Routed.Failed -> return DownloadStart.Refused(Refusal.Server(read.error))
        }
        if (manifest.tracks.isEmpty()) return DownloadStart.Refused(Refusal.NoAudio)
        if (formats.bookUnsupported(manifest.tracks.map { FormatSupport.Track(it.extension, it.codec) })) {
            return DownloadStart.Refused(Refusal.UnsupportedFormat)
        }

        val now = clock.wallMillis()
        val job = TransferEntity(
            jobId = newId(),
            profileId = key.profileId,
            itemId = key.itemId,
            stagingGenerationId = newId(),
            manifestFingerprint = null,
            state = TransferState.QUEUED,
            activeMarker = 1,
            createdAt = now,
            updatedAt = now,
            bytesTotal = manifest.tracks.sumKnown(),
            replacesGenerationId = edition?.generationId,
            title = manifest.title,
            authors = manifest.authors,
            coverUpdatedAt = manifest.updatedAt,
        )
        // The unique index on the active marker settles a race between two taps.
        val inserted = runCatching { transfers.insert(job) }.isSuccess
        if (!inserted) {
            transfers.active(key.profileId, key.itemId)?.let { return DownloadStart.Attached(it.jobId) }
            error("Could not enqueue ${key.itemId}")
        }
        start()
        return DownloadStart.Enqueued(job.jobId)
    }

    /** Pause: persists across relaunch, keeps the staged files, and yields the slot (rule 1). */
    suspend fun pause(jobId: String) {
        update(jobId) { job ->
            if (!job.state.active || job.state == TransferState.VERIFYING) null
            else job.copy(state = TransferState.PAUSED, userPaused = true, nextRetryAt = null)
        } ?: return
        stopRun(jobId)
        wake.trySend(Unit)
    }

    /**
     * Resume a paused job, or Retry a stopped or waiting one. The run reads a fresh manifest
     * before any audio request, which is the refresh action of step 1.
     */
    suspend fun resume(jobId: String) {
        update(jobId) { job ->
            if (!job.state.active || job.state == TransferState.TRANSFERRING || job.state == TransferState.VERIFYING) null
            else job.copy(
                state = TransferState.QUEUED, userPaused = false, failureCategory = null, shortfallBytes = null,
                nextRetryAt = null, attempt = 0,
            )
        } ?: return
        start()
    }

    /**
     * Cancel names its job and affects only that job. The cancellation is committed before the
     * copy stops or any file is deleted, so it survives a process death, and a publication that
     * committed first wins: the book stays Ready (A06b).
     */
    suspend fun cancel(jobId: String) {
        val cancelled = db.withTransaction {
            val job = transfers.get(jobId)?.takeIf { it.state.active } ?: return@withTransaction null
            job.copy(state = TransferState.CANCELLED, activeMarker = null, updatedAt = clock.wallMillis()).also { transfers.upsert(it) }
        } ?: return
        stopRun(jobId)
        reclaim(cancelled)
        wake.trySend(Unit)
    }

    /** Called when the app comes to the foreground: platform work can be scheduled only then. */
    suspend fun onForeground() {
        ensureRecovered()
        if (!queueRunning && transfers.allActive().any { runnableLater(it) }) scheduler.schedule()
    }

    // --- The queue ----------------------------------------------------------------------------

    /**
     * Runs queued work until nothing can run without the listener, one book at a time. Waits in
     * between for a retry that is due later or for a change of addresses. Returns when every
     * remaining job is paused or stopped.
     */
    suspend fun runQueue() = runLock.withLock {
        ensureRecovered()
        do {
            queueRunning = true
            try {
                drain()
            } finally {
                queueRunning = false
            }
            // A Download or Resume that arrived after the drain found nothing, but before the
            // flag dropped, saw a running queue and scheduled no job: drain again for it.
        } while (transfers.allActive().any { runnableLater(it) })
    }

    private suspend fun drain() {
        coroutineScope {
            val watcher = launch { watchAddresses() }
            try {
                while (true) {
                    if (runDue()) continue
                    val due = transfers.allActive().filter { runnableLater(it) }.minOfOrNull { it.nextRetryAt ?: 0L } ?: break
                    withTimeoutOrNull((due - clock.wallMillis()).coerceAtLeast(1)) { wake.receive() }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Runs the first job that is due now, if any, to its next resting state. Tests drive the queue with it. */
    internal suspend fun runDue(): Boolean {
        if (retryForAddressChange.getAndSet(false)) retryWaiting()
        val next = nextRunnable() ?: return false
        runOne(next.jobId)
        return true
    }

    private suspend fun runOne(jobId: String) = coroutineScope {
        val flusher = launch {
            while (isActive) {
                delay(JOURNAL_INTERVAL_MS)
                flushJournal()
            }
        }
        val job = launch { runJob(jobId) }
        running = Running(jobId, job)
        _active.value = jobId
        try {
            job.join()
        } finally {
            running = null
            _active.value = null
            flusher.cancel()
            withContext(NonCancellable) { flushJournal() }
        }
    }

    private suspend fun flushJournal() {
        val entry = pendingJournal.getAndSet(null) ?: return
        transfers.journalFile(entry.jobId, entry.ordinal, entry.fileBytes)
        transfers.journalJob(entry.jobId, entry.jobBytes, clock.wallMillis())
    }

    private suspend fun nextRunnable(): TransferEntity? {
        val now = clock.wallMillis()
        return transfers.allActive().firstOrNull { job ->
            !job.userPaused && when (job.state) {
                TransferState.QUEUED -> true
                TransferState.WAITING_FOR_CONNECTION, TransferState.FAILED -> autoRetries(job) && (job.nextRetryAt ?: 0L) <= now
                else -> false
            }
        }
    }

    /** True for work that continues without the listener, now or after its backoff. */
    private fun runnableLater(job: TransferEntity): Boolean = !job.userPaused && when (job.state) {
        TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.VERIFYING -> true
        TransferState.WAITING_FOR_CONNECTION, TransferState.FAILED -> autoRetries(job)
        else -> false
    }

    /**
     * Transient failures and missing connections retry by themselves. A denial, an unsupported or
     * mismatched manifest, a server without audio, storage and verification wait for Retry.
     */
    private fun autoRetries(job: TransferEntity): Boolean = job.state == TransferState.WAITING_FOR_CONNECTION ||
        job.failureCategory == FailureCategory.NETWORK || job.failureCategory == FailureCategory.SERVER

    private suspend fun stopRun(jobId: String) {
        running?.takeIf { it.jobId == jobId }?.job?.cancelAndJoin()
    }

    private fun start() {
        wake.trySend(Unit)
        if (!queueRunning) scheduler.schedule()
    }

    /**
     * Stops a copy whose address was removed, disabled or signed out, so the job waits with its
     * files intact (A04b), and wakes waiting jobs when the addresses change.
     */
    private suspend fun watchAddresses() {
        db.profiles().observe()
            .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else db.addresses().observe(profile.profileId) }
            // A route probe records a successful contact even if the address cannot serve audio.
            .map { addresses -> addresses.map { it.copy(lastSuccessAt = null) } }
            .distinctUntilChanged()
            .drop(1)
            .collect { addresses -> addressesChanged(addresses) }
    }

    /** The profile's addresses changed: stop a copy whose address left, and retry waiting jobs now. */
    internal suspend fun addressesChanged(addresses: List<AddressEntity>) {
        val current = running
        if (current != null) {
            val serving = transfers.get(current.jobId)?.servingAddressId
            if (serving != null && addresses.none { it.addressId == serving && usable(it) }) {
                current.job.cancelAndJoin()
                transfers.get(current.jobId)?.takeIf { it.state.active && !it.userPaused }?.let {
                    rest(it, TransferState.WAITING_FOR_CONNECTION, FailureCategory.NO_ROUTE, retryNow = true)
                }
            }
        }
        // Apply the retry after the current attempt can no longer write its own backoff.
        retryForAddressChange.set(true)
        wake.trySend(Unit)
    }

    private suspend fun retryWaiting() {
        val now = clock.wallMillis()
        transfers.allActive().filter { it.state == TransferState.WAITING_FOR_CONNECTION && !it.userPaused }
            .forEach { waiting -> update(waiting.jobId) { if (it.state == TransferState.WAITING_FOR_CONNECTION && !it.userPaused) it.copy(nextRetryAt = now) else null } }
    }

    private fun usable(address: AddressEntity) =
        address.enabled && address.pairingState == PairingState.PAIRED && address.sessionState == SessionState.SIGNED_IN

    // --- One job -------------------------------------------------------------------------------

    private suspend fun runJob(jobId: String) {
        try {
            attempt(jobId)
        } catch (stopped: CancellationException) {
            withContext(NonCancellable) {
                // Interrupted by the platform: queue again. Pause and Cancel wrote their state first.
                update(jobId) { if (it.state == TransferState.TRANSFERRING || it.state == TransferState.VERIFYING) it.copy(state = TransferState.QUEUED) else null }
            }
            throw stopped
        }
    }

    private suspend fun attempt(jobId: String) {
        var job = transfers.get(jobId)?.takeIf { it.state.active && !it.userPaused } ?: return
        job = save(job) { it.copy(state = TransferState.TRANSFERRING, failureCategory = null, shortfallBytes = null, nextRetryAt = null) }
        var refetched = false
        while (true) {
            val (manifest, addressId) = when (val read = routes.manifest(job.itemId)) {
                is Routed.Done -> read.value to read.addressId
                is Routed.Unavailable -> return rest(job, TransferState.WAITING_FOR_CONNECTION, categoryOf(read.reason))
                is Routed.Failed -> return failedRequest(job, read.error)
            }
            job = save(job) { it.copy(servingAddressId = addressId, title = manifest.title, authors = manifest.authors, coverUpdatedAt = manifest.updatedAt) }
            if (manifest.tracks.isEmpty() || manifest.tracks.any { it.sizeBytes == null }) {
                // Audiobookshelf reports every audio file's size; a manifest without one is not this contract.
                return rest(job, TransferState.FAILED, FailureCategory.SOURCE_MISMATCH)
            }
            if (formats.bookUnsupported(manifest.tracks.map { FormatSupport.Track(it.extension, it.codec) })) {
                return rest(job, TransferState.FAILED, FailureCategory.UNSUPPORTED_FORMAT)
            }
            val rows = reconcile(job, manifest)
            job = save(job) { it.copy(manifestFingerprint = manifestFingerprint(manifest.tracks), bytesTotal = manifest.tracks.sumKnown(), bytesDone = rows.journaled()) }
            val channel = routes.channel(addressId) ?: return rest(job, TransferState.WAITING_FOR_CONNECTION, FailureCategory.NO_ROUTE)
            when (val copied = copyFiles(job, rows, channel)) {
                CopyOutcome.Done -> return verifyAndPublish(transfers.get(jobId) ?: return, manifest, channel)
                CopyOutcome.Refetch -> {
                    // A 404 for a listed track: read the item once more, then stop if it persists.
                    if (refetched) return rest(job, TransferState.FAILED, FailureCategory.SOURCE_MISMATCH)
                    refetched = true
                }
                is CopyOutcome.Rest -> return rest(transfers.get(jobId) ?: return, copied.state, copied.category, copied.shortfall)
            }
        }
    }

    /**
     * Joins the staged files to the fresh manifest (step 1): a file whose source identity is
     * unchanged at its place in the play order is kept, partial or complete; any other is reset
     * and its bytes deleted.
     */
    private suspend fun reconcile(job: TransferEntity, manifest: ItemManifest): List<TransferFileEntity> {
        val staged = transfers.files(job.jobId)
        val dir = files.staging(job.stagingGenerationId).apply { mkdirs() }
        val keep = sameSource(staged, manifest)
        val rows = manifest.tracks.map { track ->
            val old = staged.firstOrNull { it.ordinal == track.index }
            if (old != null && track.index in keep && old.extension == track.extension) {
                old.copy(durationMs = track.durationMs, codec = track.codec)
            } else {
                old?.let { File(dir, it.localFilename).delete() }
                TransferFileEntity(
                    jobId = job.jobId, ordinal = track.index, sourceFileId = track.fileId, sizeBytes = track.sizeBytes,
                    mtimeMs = track.mtimeMs, extension = track.extension, codec = track.codec, durationMs = track.durationMs,
                    title = null, localFilename = EditionFiles.trackFileName(track.index, track.extension),
                )
            }
        }
        staged.filter { it.ordinal >= rows.size }.forEach { File(dir, it.localFilename).delete() }
        db.withTransaction {
            transfers.deleteFiles(job.jobId)
            transfers.upsertFiles(rows)
        }
        return rows
    }

    private sealed interface CopyOutcome {
        data object Done : CopyOutcome
        data object Refetch : CopyOutcome
        data class Rest(val state: TransferState, val category: FailureCategory, val shortfall: Long? = null) : CopyOutcome
    }

    private suspend fun copyFiles(job: TransferEntity, staged: List<TransferFileEntity>, channel: DownloadChannel): CopyOutcome {
        val dir = files.staging(job.stagingGenerationId)
        val rows = staged.toMutableList()
        for (index in rows.indices) {
            var row = rows[index]
            val size = checkNotNull(row.sizeBytes)
            val file = File(dir, row.localFilename)
            if (row.state == FileTransferState.COMPLETE && file.length() == size) continue

            // Step 2: the remaining bytes plus the margin, checked again before each file.
            SpacePolicy.shortfall(remaining(rows, dir), usableSpace())?.let { return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.STORAGE_FULL, it) }

            var tries = 0
            var mismatches = 0
            while (true) {
                if (++tries > MAX_TRIES_PER_FILE) return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.SERVER)
                val transfer = FileTransfer(
                    file, size, row.validator, row.validatorIsDate, row.urlFingerprint, channel.url(job.itemId, row.sourceFileId),
                    request = { resume -> channel.open(job.itemId, row.sourceFileId, resume) },
                    progress = { onDisk -> journal(job.jobId, rows, index, onDisk) },
                    accepted = { validator, isDate, fingerprint ->
                        row = saveFile(row.copy(state = FileTransferState.PARTIAL, bytesJournal = 0, validator = validator, validatorIsDate = isDate, urlFingerprint = fingerprint))
                        rows[index] = row
                    },
                )
                when (val result = transfer.transfer()) {
                    is FileTransferResult.Complete -> {
                        row = saveFile(row.copy(state = FileTransferState.COMPLETE, bytesJournal = size, validator = result.validator, validatorIsDate = result.isDate, urlFingerprint = result.urlFingerprint))
                        rows[index] = row
                        break
                    }
                    is FileTransferResult.Partial -> {
                        // A valid short range or a short 200: continue at once from the new offset.
                        row = saveFile(row.copy(state = FileTransferState.PARTIAL, bytesJournal = result.bytesOnDisk, validator = result.validator, validatorIsDate = result.isDate, urlFingerprint = result.urlFingerprint))
                        rows[index] = row
                    }
                    FileTransferResult.Restarted -> {
                        row = saveFile(row.copy(state = FileTransferState.PENDING, bytesJournal = 0, validator = null, validatorIsDate = false, urlFingerprint = null))
                        rows[index] = row
                    }
                    is FileTransferResult.Interrupted -> {
                        saveFile(row.copy(state = FileTransferState.PARTIAL, bytesJournal = result.bytesOnDisk, validator = result.validator, validatorIsDate = result.isDate, urlFingerprint = result.urlFingerprint))
                        return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.NETWORK)
                    }
                    is FileTransferResult.StorageFailed -> {
                        // A full disk: keep the prefix and report the space still needed, when known.
                        rows[index] = saveFile(row.copy(state = FileTransferState.PARTIAL, bytesJournal = result.bytesOnDisk, validator = result.validator, validatorIsDate = result.isDate, urlFingerprint = result.urlFingerprint))
                        return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.STORAGE_FULL, SpacePolicy.shortfall(remaining(rows, dir), usableSpace()))
                    }
                    is FileTransferResult.Mismatch -> {
                        // A malformed response leaves the usable prefix and its validator (step 3):
                        // ask for the same range once more, then stop with the prefix kept.
                        if (++mismatches >= 2) return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.SOURCE_MISMATCH)
                    }
                    FileTransferResult.NoAudio -> return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.NO_AUDIO)
                    FileTransferResult.Forbidden -> return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.DOWNLOAD_DENIED)
                    FileTransferResult.NotFound -> return CopyOutcome.Refetch
                    FileTransferResult.SessionExpired -> return CopyOutcome.Rest(TransferState.WAITING_FOR_CONNECTION, FailureCategory.SESSION_EXPIRED)
                    is FileTransferResult.Transient -> return CopyOutcome.Rest(TransferState.FAILED, if (result.detail.startsWith("HTTP 5")) FailureCategory.SERVER else FailureCategory.NETWORK)
                    is FileTransferResult.Unexpected -> return CopyOutcome.Rest(TransferState.FAILED, FailureCategory.SERVER)
                }
            }
        }
        return CopyOutcome.Done
    }

    /** Step 5 and publication (rules 2 to 4): every file complete and playable, then one commit. */
    private suspend fun verifyAndPublish(start: TransferEntity, manifest: ItemManifest, channel: DownloadChannel) {
        var job = save(start) { it.copy(state = TransferState.VERIFYING) }
        val dir = files.staging(job.stagingGenerationId)
        val rows = transfers.files(job.jobId)
        val embeddedChapters = ArrayList<List<RawChapter>>(rows.size)
        val staged = rows.map { row ->
            val file = File(dir, row.localFilename)
            if (!file.isFile || file.length() != row.sizeBytes) {
                saveFile(row.copy(state = FileTransferState.PENDING, bytesJournal = 0, validator = null, urlFingerprint = null))
                return rest(job, TransferState.FAILED, FailureCategory.VERIFICATION)
            }
            when (val check = verifier.verify(file, row.extension)) {
                is TrackCheck.Unsupported -> return rest(job, TransferState.FAILED, FailureCategory.UNSUPPORTED_FORMAT)
                is TrackCheck.Playable -> EditionAssembler.StagedTrack(
                    row.localFilename, row.sourceFileId, row.mtimeMs ?: 0, file.length(), check.durationMs, row.title, row.extension, check.codec ?: row.codec,
                ).also { embeddedChapters += check.chapters }
            }
        }
        val cover = if (manifest.coverPresent) storeCover(dir, channel.cover(job.itemId)) else null
        val edition = try {
            EditionAssembler.assemble(
                job.stagingGenerationId, job.jobId, job.key,
                EditionAssembler.Metadata(manifest.title, manifest.authors, manifest.narrators, manifest.description),
                staged, manifest.chapters, embeddedChapters, coverFile = cover,
            )
        } catch (invalid: IllegalArgumentException) {
            return rest(job, TransferState.FAILED, FailureCategory.VERIFICATION)
        }
        val outcome = try {
            editions.publish(edition)
        } catch (failed: IOException) {
            return rest(job, TransferState.FAILED, FailureCategory.VERIFICATION)
        }
        when (outcome) {
            is EditionStore.PublishOutcome.Published -> {
                onPublished(edition.title)
                editions.sweep(activeWriters())
            }
            is EditionStore.PublishOutcome.Rejected -> {
                job = transfers.get(job.jobId) ?: return
                if (job.state == TransferState.CANCELLED) reclaim(job) else rest(job, TransferState.FAILED, FailureCategory.VERIFICATION)
            }
        }
    }

    private fun storeCover(dir: File, bytes: ByteArray?): String? {
        bytes ?: return null
        val name = EditionFiles.COVER + "." + imageExtension(bytes)
        return runCatching { File(dir, name).writeBytes(bytes); name }.getOrNull()
    }

    // --- State writes --------------------------------------------------------------------------

    /** Read-modify-write of one job row in a transaction; null when [change] declines. */
    private suspend fun update(jobId: String, change: (TransferEntity) -> TransferEntity?): TransferEntity? = db.withTransaction {
        val current = transfers.get(jobId) ?: return@withTransaction null
        change(current)?.copy(updatedAt = clock.wallMillis())?.also { transfers.upsert(it) }
    }

    /**
     * A write by the running copy. It applies to the current row, never a stale copy, and ends
     * the run when the listener paused or cancelled the job meanwhile.
     */
    private suspend fun save(job: TransferEntity, change: (TransferEntity) -> TransferEntity): TransferEntity =
        update(job.jobId) { current -> if (!current.state.active || current.userPaused) null else change(current) }
            ?: throw StoppedByListener()

    private suspend fun saveFile(row: TransferFileEntity): TransferFileEntity = row.also { transfers.upsertFile(it) }

    /**
     * Puts a job to rest. Automatic retries back off exponentially with the per-book attempt
     * count; [retryNow] skips the delay when a route may have come back.
     */
    private suspend fun rest(job: TransferEntity, state: TransferState, category: FailureCategory, shortfall: Long? = null, retryNow: Boolean = false) {
        val retries = state == TransferState.WAITING_FOR_CONNECTION || category == FailureCategory.NETWORK || category == FailureCategory.SERVER
        update(job.jobId) { current ->
            if (!current.state.active || current.userPaused) return@update null
            val attempt = current.attempt + 1
            val delay = if (retryNow) 0L else backoffMs(attempt)
            current.copy(
                state = state, failureCategory = category, shortfallBytes = shortfall, attempt = attempt,
                nextRetryAt = if (retries) clock.wallMillis() + delay else null,
            )
        }
    }

    private suspend fun failedRequest(job: TransferEntity, error: ServerError) = when (error) {
        ServerError.Forbidden -> rest(job, TransferState.FAILED, FailureCategory.DOWNLOAD_DENIED)
        ServerError.NotFound, is ServerError.Unexpected -> rest(job, TransferState.FAILED, FailureCategory.SOURCE_MISMATCH)
        ServerError.SessionExpired -> rest(job, TransferState.WAITING_FOR_CONNECTION, FailureCategory.SESSION_EXPIRED)
        else -> rest(job, TransferState.FAILED, FailureCategory.SERVER)
    }

    /**
     * Journals progress for display; the flusher writes it to Room at most twice a second. Resume
     * decisions read the disk, never this journal.
     */
    private fun journal(jobId: String, rows: MutableList<TransferFileEntity>, index: Int, onDisk: Long) {
        rows[index] = rows[index].copy(bytesJournal = onDisk)
        pendingJournal.set(JournalEntry(jobId, rows[index].ordinal, onDisk, rows.journaled()))
    }

    // --- Recovery ------------------------------------------------------------------------------

    /**
     * After a process start (step 6, rule 4): republish a generation that was renamed but not
     * committed, reclaim cancelled generations, and queue work that a process death interrupted.
     * Runs once per process, before the first run.
     */
    suspend fun ensureRecovered() = recovery.withLock {
        if (recovered) return@withLock
        editions.recover(activeWriters())
        for (job in transfers.cancelled()) reclaim(job)
        for (job in transfers.allActive()) {
            update(job.jobId) { if (it.state == TransferState.TRANSFERRING || it.state == TransferState.VERIFYING) it.copy(state = TransferState.QUEUED) else null }
        }
        recovered = true
    }

    private suspend fun activeWriters(): Set<String> = transfers.allActive().map { it.stagingGenerationId }.toSet()

    /** Frees a cancelled job's generation unless an edition took it, and forgets its file rows. */
    private suspend fun reclaim(job: TransferEntity) {
        if (db.editions().all().none { it.generationId == job.stagingGenerationId }) files.deleteGeneration(job.stagingGenerationId)
        transfers.deleteFiles(job.jobId)
    }

    private fun remaining(rows: List<TransferFileEntity>, dir: File): Long =
        rows.sumOf { row -> ((row.sizeBytes ?: 0L) - File(dir, row.localFilename).length()).coerceAtLeast(0L) }

    private fun categoryOf(reason: NoRoute): FailureCategory = when (reason) {
        is NoRoute.LocalNetworkDenied -> FailureCategory.LOCAL_NETWORK_DENIED
        is NoRoute.CertificateChanged, is NoRoute.CertificateUnrecognized -> FailureCategory.CERTIFICATE_CHANGED
        NoRoute.SignedOut -> FailureCategory.SESSION_EXPIRED
        is NoRoute.Unreachable -> FailureCategory.NO_ROUTE
    }

    companion object {
        const val MAX_TRIES_PER_FILE = 5
        const val JOURNAL_INTERVAL_MS = 500L
        private const val BACKOFF_BASE_MS = 5_000L
        private const val BACKOFF_CAP_MS = 10 * 60_000L

        /** 5 s, 10 s, 20 s, … capped at 10 min. */
        fun backoffMs(attempt: Int): Long =
            (BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 20)).coerceAtMost(BACKOFF_CAP_MS)

        fun imageExtension(bytes: ByteArray): String = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> "img"
        }
    }
}

private fun List<ManifestSourceTrack>.sumKnown(): Long? =
    if (any { it.sizeBytes == null }) null else sumOf { it.sizeBytes!! }

private fun List<TransferFileEntity>.journaled(): Long = sumOf { it.bytesJournal }
