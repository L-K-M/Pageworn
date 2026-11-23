package ch.lkmc.pageworn.data

import androidx.room.withTransaction
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.core.timeline.ChapterSpan
import ch.lkmc.pageworn.core.timeline.TrackSpan
import ch.lkmc.pageworn.data.db.ChapterEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.PendingRemovalEntity
import ch.lkmc.pageworn.data.db.TrackEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.EditionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Published editions: publication, recovery, replacement and removal, following the persistence
 * and recovery rules in docs/03-android-and-data.md. The filesystem rename and the Room transaction
 * have separate failure boundaries; a finalized directory alone grants no authority to publish.
 */
class EditionStore(
    private val db: PagewornDatabase,
    val files: EditionFiles,
    private val clock: Clock,
) : EditionSource {

    sealed interface PublishOutcome {
        data class Published(val supersededGenerationId: String?) : PublishOutcome
        data class Rejected(val reason: String) : PublishOutcome
    }

    /** Generations a playback session holds; their files are never deleted while held. */
    private val leases = ConcurrentHashMap<String, Int>()

    /** Serializes removal against playback startup and publication for the same book. */
    private val bookLocks = ConcurrentHashMap<BookKey, Mutex>()
    private fun lockFor(key: BookKey) = bookLocks.getOrPut(key) { Mutex() }

    fun acquire(generationId: String) {
        leases.merge(generationId, 1, Int::plus)
    }

    fun release(generationId: String) {
        leases.computeIfPresent(generationId) { _, n -> if (n <= 1) null else n - 1 }
    }

    // --- Publication ---------------------------------------------------------------------------

    /** Finalizes the staged generation of [manifest] and commits it (rule 3). */
    suspend fun publish(manifest: EditionManifest): PublishOutcome {
        val key = BookKey(manifest.profileId, manifest.itemId)
        return lockFor(key).withLock {
            files.writeManifest(files.staging(manifest.generationId).takeIf { it.isDirectory } ?: files.published(manifest.generationId), manifest)
            val dir = files.finalize(manifest.generationId)
            commit(manifest, dir)
        }
    }

    /**
     * The Room half of publication: checks that the job and generation are still the book's current
     * transfer, verified and not cancelled, and that no removal is pending; then points the book at
     * this generation and marks the transfer complete, all in one transaction.
     */
    private suspend fun commit(manifest: EditionManifest, dir: File): PublishOutcome = db.withTransaction {
        val editions = db.editions()
        val transfers = db.transfers()
        if (editions.pendingRemoval(manifest.profileId, manifest.itemId) != null) {
            return@withTransaction PublishOutcome.Rejected("removal pending")
        }
        val job = manifest.jobId?.let { transfers.get(it) }
        if (manifest.jobId != null) {
            if (job == null) return@withTransaction PublishOutcome.Rejected("job ${manifest.jobId} no longer exists")
            if (job.state != TransferState.VERIFYING || job.activeMarker != 1 || job.stagingGenerationId != manifest.generationId) {
                return@withTransaction PublishOutcome.Rejected("job ${job.jobId} is ${job.state}")
            }
        }
        val previous = editions.edition(manifest.profileId, manifest.itemId)
        val now = clock.wallMillis()
        val size = manifest.tracks.sumOf { it.sizeBytes } + (manifest.coverFile?.let { File(dir, it).length() } ?: 0L)
        editions.upsertEdition(
            LocalEditionEntity(
                profileId = manifest.profileId,
                itemId = manifest.itemId,
                generationId = manifest.generationId,
                fingerprint = manifest.fingerprint,
                state = EditionState.READY,
                durationMs = manifest.tracks.sumOf { it.durationMs },
                relativeDir = manifest.generationId,
                coverFile = manifest.coverFile,
                title = manifest.title,
                authors = manifest.authors,
                narrators = manifest.narrators,
                description = manifest.description,
                chapterSource = manifest.chapterSource,
                publishedAt = now,
                sizeBytes = size,
            ),
        )
        editions.deleteTracks(manifest.generationId)
        editions.deleteChapters(manifest.generationId)
        editions.insertTracks(
            manifest.tracks.map {
                TrackEntity(manifest.generationId, it.ordinal, it.sourceFileId, it.sourceRevision, it.sizeBytes, it.localFilename, it.startMs, it.durationMs, it.title, it.extension, it.codec)
            },
        )
        editions.insertChapters(manifest.chapters.map { ChapterEntity(manifest.generationId, it.ordinal, it.title, it.origin, it.startMs, it.endMs) })
        if (job != null) {
            transfers.upsert(job.copy(state = TransferState.COMPLETE, activeMarker = null, updatedAt = now, failureCategory = null))
        }
        val progress = db.progress().get(manifest.profileId, manifest.itemId)
        if (progress != null && progress.editionFingerprint != null && progress.editionFingerprint != manifest.fingerprint) {
            // Reordered or materially changed audio: keep the old place and ask before first play.
            db.progress().upsert(progress.copy(pendingContinuityChoice = true))
        }
        PublishOutcome.Published(previous?.generationId?.takeIf { it != manifest.generationId })
    }

    // --- Recovery ------------------------------------------------------------------------------

    /**
     * Startup recovery (rule 4): resume pending removals first, then republish finalized generations
     * whose job still qualifies, then reclaim unreferenced generations that no writer or reader uses.
     * [activeWriters] are staging generations that a running transfer still writes.
     */
    suspend fun recover(activeWriters: Set<String> = emptySet()) {
        files.ensureRoots()
        for (removal in db.editions().pendingRemovals()) {
            finishRemoval(BookKey(removal.profileId, removal.itemId))
        }
        val published = db.editions().all().map { it.generationId }.toSet()
        files.editionsRoot.listFiles()?.filter { it.isDirectory && it.name !in published }?.forEach { dir ->
            val manifest = files.readManifest(dir)
            if (manifest != null) {
                val key = BookKey(manifest.profileId, manifest.itemId)
                lockFor(key).withLock { commit(manifest, dir) }
            }
        }
        sweep(activeWriters)
        // Missing or short published files: Repair needed, keeping progress.
        for (edition in db.editions().all()) {
            if (edition.state == EditionState.READY && !filesIntact(edition)) {
                db.editions().setState(edition.profileId, edition.itemId, edition.generationId, EditionState.REPAIR_NEEDED)
            }
        }
    }

    /** Deletes generations that no edition, active transfer, writer or playback session references. */
    suspend fun sweep(activeWriters: Set<String> = emptySet()) {
        val referenced = db.editions().referencedGenerations().toSet() + activeWriters + leases.keys
        listOf(files.editionsRoot, files.stagingRoot).forEach { root ->
            root.listFiles()?.filter { it.isDirectory && it.name !in referenced }?.forEach { it.deleteRecursively() }
        }
    }

    /** Every track file is on the disk with the size it was verified at. */
    private suspend fun filesIntact(edition: LocalEditionEntity): Boolean {
        val dir = files.published(edition.generationId)
        return withContext(Dispatchers.IO) {
            db.editions().tracks(edition.generationId).all { track ->
                val file = File(dir, track.localFilename)
                file.isFile && file.length() == track.sizeBytes
            }
        }
    }

    /**
     * Before opening a book: a missing file, or one whose size changed since verification, turns
     * the edition into Repair needed, a damaged copy. Damage that keeps the size surfaces as a
     * playback failure when playback reaches it.
     */
    suspend fun checkBeforeOpening(key: BookKey): Boolean {
        val edition = db.editions().edition(key.profileId, key.itemId) ?: return false
        if (edition.state != EditionState.READY) return false
        if (!filesIntact(edition)) {
            db.editions().setState(key.profileId, key.itemId, edition.generationId, EditionState.REPAIR_NEEDED)
            return false
        }
        return true
    }

    suspend fun markRepairNeeded(key: BookKey, generationId: String) {
        db.editions().setState(key.profileId, key.itemId, generationId, EditionState.REPAIR_NEEDED)
    }

    // --- Removal -------------------------------------------------------------------------------

    /**
     * Explicit removal (rule 5). The caller has paused and checkpointed the book and released its
     * readers. Marks removal pending and invalidates any unpublished transfer in one transaction,
     * deletes the audio, then clears the edition rows. Progress is kept. A second removal is harmless.
     */
    suspend fun remove(key: BookKey) = lockFor(key).withLock {
        db.withTransaction {
            val edition = db.editions().edition(key.profileId, key.itemId)
            db.editions().upsertPendingRemoval(PendingRemovalEntity(key.profileId, key.itemId, edition?.generationId, clock.wallMillis()))
            db.transfers().active(key.profileId, key.itemId)?.let { job ->
                db.transfers().upsert(job.copy(state = TransferState.CANCELLED, activeMarker = null, updatedAt = clock.wallMillis()))
            }
        }
        finishRemovalLocked(key)
    }

    private suspend fun finishRemoval(key: BookKey) = lockFor(key).withLock { finishRemovalLocked(key) }

    private suspend fun finishRemovalLocked(key: BookKey) {
        val edition = db.editions().edition(key.profileId, key.itemId)
        if (edition != null) {
            files.deleteGeneration(edition.generationId)
        }
        db.withTransaction {
            if (edition != null) {
                db.editions().deleteTracks(edition.generationId)
                db.editions().deleteChapters(edition.generationId)
                db.editions().deleteEdition(key.profileId, key.itemId)
            }
            db.editions().clearPendingRemoval(key.profileId, key.itemId)
        }
    }

    suspend fun isRemovalPending(key: BookKey): Boolean = db.editions().pendingRemoval(key.profileId, key.itemId) != null

    // --- Playback source -----------------------------------------------------------------------

    override suspend fun plan(key: BookKey): EditionPlan? = lockFor(key).withLock {
        if (isRemovalPending(key)) return@withLock null
        val edition = db.editions().edition(key.profileId, key.itemId) ?: return@withLock null
        if (edition.state != EditionState.READY) return@withLock null
        toPlan(edition)
    }

    /** The edition's plan regardless of its state, for showing chapters and the saved place. */
    suspend fun anyPlan(key: BookKey): EditionPlan? {
        val edition = db.editions().edition(key.profileId, key.itemId) ?: return null
        return toPlan(edition)
    }

    private suspend fun toPlan(edition: LocalEditionEntity): EditionPlan? {
        val tracks = db.editions().tracks(edition.generationId)
        if (tracks.isEmpty()) return null
        val chapters = db.editions().chapters(edition.generationId)
        val dir = files.published(edition.generationId)
        val timeline = runCatching {
            BookTimeline(
                tracks.map { TrackSpan(it.ordinal, it.startMs, it.durationMs, it.title) },
                chapters.map { ChapterSpan(it.startMs, it.endMs, it.title, it.origin) },
            )
        }.getOrNull() ?: return null
        return EditionPlan(
            key = edition.key,
            generationId = edition.generationId,
            fingerprint = edition.fingerprint,
            title = edition.title,
            author = edition.authors,
            coverFile = edition.coverFile?.let { File(dir, it) },
            trackFiles = tracks.map { File(dir, it.localFilename) },
            timeline = timeline,
        )
    }

    override suspend fun readyBooks(): List<EditionPlan> {
        val profileEditions = db.editions().all().filter { it.state == EditionState.READY }
        val removals = db.editions().pendingRemovals().map { BookKey(it.profileId, it.itemId) }.toSet()
        val progress = profileEditions.associate { it.key to db.progress().get(it.profileId, it.itemId) }
        return DownloadedOrder.sort(profileEditions.filter { it.key !in removals }) { progress[it.key]?.lastListenedAt }
            .mapNotNull { toPlan(it) }
    }

    /** Every published edition on the phone (one profile owns them all), without pending removals. */
    fun observeEverything(): Flow<List<LocalEditionEntity>> =
        combine(db.editions().observeEverything(), db.editions().observePendingRemovals()) { editions, removals ->
            val pending = removals.map { BookKey(it.profileId, it.itemId) }.toSet()
            editions.filter { it.key !in pending }
        }

    fun observeEditions(profileId: String): Flow<List<LocalEditionEntity>> =
        combine(db.editions().observeAll(profileId), db.editions().observePendingRemovals()) { editions, removals ->
            val pending = removals.map { BookKey(it.profileId, it.itemId) }.toSet()
            editions.filter { it.key !in pending }
        }
}

/** The Downloaded order from the product brief. */
object DownloadedOrder {
    /**
     * Last-listened time descending; never-played books by download completion time descending;
     * then title and stable item ID.
     */
    fun sort(editions: List<LocalEditionEntity>, lastListened: (LocalEditionEntity) -> Long?): List<LocalEditionEntity> =
        editions.sortedWith(comparator(lastListened))

    fun comparator(lastListened: (LocalEditionEntity) -> Long?): Comparator<LocalEditionEntity> =
        compareByDescending<LocalEditionEntity> { lastListened(it) != null }
            .thenByDescending { lastListened(it) ?: Long.MIN_VALUE }
            .thenByDescending { it.publishedAt }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            .thenBy { it.itemId }
}
