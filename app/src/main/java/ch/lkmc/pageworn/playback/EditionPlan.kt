package ch.lkmc.pageworn.playback

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.BookTimeline
import java.io.File

/**
 * A published edition in the form the player needs: committed local files in play order and the
 * verified timeline. The player takes nothing else, in particular no network address.
 */
data class EditionPlan(
    val key: BookKey,
    val generationId: String,
    val fingerprint: String,
    val title: String,
    val author: String?,
    val coverFile: File?,
    val trackFiles: List<File>,
    val timeline: BookTimeline,
) {
    init {
        require(trackFiles.size == timeline.tracks.size) { "One file per track" }
    }
}

/** Supplies published editions to the playback service. Room-backed in the app, in-memory in tests. */
interface EditionSource {
    /** The published, playable edition of [key], or null when none is ready. */
    suspend fun plan(key: BookKey): EditionPlan?

    /** Books whose published edition is ready to play, in Downloaded order. */
    suspend fun readyBooks(): List<EditionPlan>
}

/** Simple [EditionSource] for the playback spike and tests. */
class InMemoryEditionSource : EditionSource {
    private val plans = LinkedHashMap<BookKey, EditionPlan>()

    @Synchronized
    fun put(plan: EditionPlan) {
        plans[plan.key] = plan
    }

    @Synchronized
    fun clear() = plans.clear()

    override suspend fun plan(key: BookKey): EditionPlan? = synchronized(this) { plans[key] }

    override suspend fun readyBooks(): List<EditionPlan> = synchronized(this) { plans.values.toList() }
}
