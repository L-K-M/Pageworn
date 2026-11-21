package ch.lkmc.pageworn.data

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.progress.ResumeRewind
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.ProgressDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders progress writes. Every write carries a token: the playback generation (one per session of
 * a book, or one per write made while no session holds it) and a sequence assigned when the
 * position was captured. A write lands only if its token is newer than the stored one, so a delayed
 * callback from an earlier book, session or pre-seek sample never overwrites newer progress.
 */
class ProgressStore(private val dao: ProgressDao, private val clock: Clock) {
    private val mutex = Mutex()
    private val handedOut = HashMap<BookKey, Long>()

    data class Token(val generation: Long, val sequence: Long)

    /** A source of tokens for one playback generation. Call [next] when a position is captured. */
    class Session(val key: BookKey, val generation: Long) {
        private var sequence = 0L

        @Synchronized
        fun next(): Token = Token(generation, ++sequence)
    }

    suspend fun read(key: BookKey): BookProgressEntity? = dao.get(key.profileId, key.itemId)

    fun observe(key: BookKey): Flow<BookProgressEntity?> = dao.observe(key.profileId, key.itemId)

    fun observeAll(profileId: String): Flow<List<BookProgressEntity>> = dao.observeAll(profileId)

    /** Starts a new playback generation for [key], newer than every earlier write. */
    suspend fun newSession(key: BookKey): Session = mutex.withLock {
        val stored = dao.get(key.profileId, key.itemId)?.playbackGeneration ?: 0L
        val generation = maxOf(stored, handedOut[key] ?: 0L) + 1
        handedOut[key] = generation
        Session(key, generation)
    }

    /**
     * Saves [positionMs] for [key] under [token]. [listened] marks that audio was playing up to this
     * write, which moves the book to the top of Downloaded. Returns whether the write landed.
     */
    suspend fun save(
        key: BookKey,
        token: Token,
        positionMs: Long,
        durationMs: Long,
        fingerprint: String?,
        stopKind: StopKind,
        finished: Boolean = false,
        listened: Boolean = false,
    ): Boolean = mutex.withLock {
        val now = clock.wallMillis()
        val stored = dao.get(key.profileId, key.itemId)
        dao.writeIfNewer(
            BookProgressEntity(
                profileId = key.profileId,
                itemId = key.itemId,
                positionMs = positionMs.coerceIn(0, durationMs.coerceAtLeast(0)),
                durationAtSaveMs = durationMs,
                editionFingerprint = fingerprint ?: stored?.editionFingerprint,
                finished = finished,
                updatedAt = now,
                stopKind = stopKind,
                lastListenedAt = if (listened) now else stored?.lastListenedAt,
                playbackGeneration = token.generation,
                writeSequence = token.sequence,
                pendingContinuityChoice = false,
            ),
        )
    }

    /** A write made while no session holds the book (Replay, Start over, a seek in details). */
    suspend fun saveOutsideSession(
        key: BookKey,
        positionMs: Long,
        durationMs: Long,
        fingerprint: String?,
        stopKind: StopKind,
        finished: Boolean = false,
    ): Boolean {
        val session = newSession(key)
        return save(key, session.next(), positionMs, durationMs, fingerprint, stopKind, finished)
    }

    /**
     * Marks that the re-downloaded audio of [key] changed materially: before first play the listener
     * chooses Use previous position or Start over. The saved position is kept.
     */
    suspend fun requireContinuityChoice(key: BookKey, newFingerprint: String) = mutex.withLock {
        val stored = dao.get(key.profileId, key.itemId) ?: return@withLock
        if (stored.editionFingerprint == null || stored.editionFingerprint == newFingerprint) return@withLock
        dao.upsert(stored.copy(pendingContinuityChoice = true))
    }

    /** Where playback of [key] starts when it starts from its saved place, with the resume rewind. */
    suspend fun resumePosition(key: BookKey, durationMs: Long): Long {
        val p = read(key) ?: return 0L
        val start = ResumeRewind.startPosition(p.positionMs, p.stopKind, p.updatedAt, clock.wallMillis(), p.finished)
        return start.coerceIn(0, durationMs)
    }
}
