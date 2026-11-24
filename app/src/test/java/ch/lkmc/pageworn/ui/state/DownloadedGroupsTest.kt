package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.FailureCategory
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.ReadyBadge
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** A27: transfers form their own group, each with the actions its state allows. */
class DownloadedGroupsTest {
    private val active = job("active", TransferState.TRANSFERRING)
    private val waiting = job("waiting", TransferState.WAITING_FOR_CONNECTION)
    private val failed = job("failed", TransferState.FAILED, FailureCategory.STORAGE_FULL)
    private val completed = job("ready", TransferState.COMPLETE)
    private val edition = LocalEditionEntity(
        PROFILE, "ready", "generation", "print", EditionState.READY, 210_000, "generation", null, "Ready", null,
        narrators = null, description = null, chapterSource = null, publishedAt = 1, sizeBytes = 1,
    )

    @Test
    fun a27TransfersAndReadyBooksFormSeparateGroups() {
        val (transfers, books) = rows(active, waiting, failed, completed)

        assertThat(transfers.map { it.transfer.jobId }).containsExactly("active", "waiting", "failed")
        assertThat(books.map { it.face.key }).containsExactly(BookKey(PROFILE, "ready"))

        val byJob = transfers.associate { it.transfer.jobId to it.transfer }
        assertThat(byJob.getValue("active").canPause).isTrue()
        assertThat(byJob.getValue("failed").canRetry || byJob.getValue("failed").canResume).isTrue()
        byJob.values.forEach {
            assertThat(it.canCancel).isTrue()
            assertThat(it.stateText).isNotEmpty()
        }
    }

    @Test
    fun a27PausingTheActiveTransferChangesOnlyItsActions() {
        val before = rows(active, waiting, failed).first
        val after = rows(active.copy(state = TransferState.PAUSED, userPaused = true), waiting, failed).first

        assertThat(after.first().transfer.canPause).isFalse()
        assertThat(after.first().transfer.canResume).isTrue()
        assertThat(after.drop(1)).isEqualTo(before.drop(1))
    }

    @Test
    fun heldOrderSkipsSortingWhenProgressChanges() {
        val alpha = book("alpha", publishedAt = 100)
        val beta = book("beta", publishedAt = 200)
        val gamma = book("gamma", publishedAt = 300)

        val first = books(listOf(alpha, beta, gamma))
        assertThat(first.map { it.face.key }).containsExactly(gamma.key, beta.key, alpha.key).inOrder()
        val held = first.map { it.face.key }

        // A fresh sort would now lead with alpha; the held shelf order stays put.
        val progress = mapOf(
            alpha.key to listening(alpha.key, lastListenedAt = 900),
            beta.key to listening(beta.key, lastListenedAt = 800),
            gamma.key to listening(gamma.key, lastListenedAt = 700),
        )
        var progressReads = 0
        val counted = object : HashMap<BookKey, BookProgressEntity>(progress) {
            override fun get(key: BookKey): BookProgressEntity? {
                progressReads++
                return super.get(key)
            }
        }
        val after = books(listOf(alpha, beta, gamma), counted, held)

        assertThat(after.map { it.face.key }).containsExactly(gamma.key, beta.key, alpha.key).inOrder()
        // Each row reads its progress once. Re-sorting would also read it during comparisons.
        assertThat(progressReads).isEqualTo(after.size)
    }

    @Test
    fun addingOrRemovingABookSortsTheNewSet() {
        val alpha = book("alpha", publishedAt = 100)
        val beta = book("beta", publishedAt = 200)
        val held = books(listOf(alpha, beta)).map { it.face.key }

        val gamma = book("gamma", publishedAt = 300)
        val added = books(listOf(alpha, beta, gamma), heldOrder = held)
        assertThat(added.map { it.face.key }).containsExactly(gamma.key, beta.key, alpha.key).inOrder()

        val removed = books(listOf(alpha, gamma), heldOrder = added.map { it.face.key })
        assertThat(removed.map { it.face.key }).containsExactly(gamma.key, alpha.key).inOrder()
    }

    @Test
    fun heldOrderUsesCurrentEditionMetadataAfterRepair() {
        val damaged = book("alpha", generationId = "gen-1", state = EditionState.REPAIR_NEEDED, publishedAt = 100)
        val beta = book("beta", publishedAt = 200)
        val held = books(listOf(damaged, beta)).map { it.face.key }

        val repaired = damaged.copy(
            generationId = "gen-2", fingerprint = "fingerprint-gen-2",
            state = EditionState.READY, title = "Alpha (repaired)",
        )
        // A fresh sort would lead with the repaired book once it has been listened to.
        val progress = mapOf(repaired.key to listening(repaired.key, positionMs = 5_000, lastListenedAt = 900))
        val after = books(listOf(repaired, beta), progress, held)

        assertThat(after.map { it.face.key }).containsExactly(beta.key, repaired.key).inOrder()
        val alpha = after.first { it.face.key == repaired.key }
        assertThat(alpha.face.title).isEqualTo("Alpha (repaired)")
        assertThat(alpha.playable).isTrue()
        assertThat(alpha.badge).isNotInstanceOf(ReadyBadge.RepairNeeded::class.java)
    }

    private fun rows(vararg jobs: TransferEntity) =
        RowBuilders.downloadedRows(listOf(edition), { File("editions") }, emptyMap(), emptyMap(), jobs.toList(), LivePlayback())

    private fun books(
        editions: List<LocalEditionEntity>,
        progress: Map<BookKey, BookProgressEntity> = emptyMap(),
        heldOrder: List<BookKey> = emptyList(),
    ) = RowBuilders.downloadedRows(editions, { File("editions") }, progress, emptyMap(), emptyList(), LivePlayback(), heldOrder).second

    private fun book(
        itemId: String,
        generationId: String = "gen-$itemId",
        state: EditionState = EditionState.READY,
        publishedAt: Long = 1L,
    ) = LocalEditionEntity(
        PROFILE, itemId, generationId, "fingerprint-$itemId", state, 60_000, generationId, null, itemId,
        null, narrators = null, description = null, chapterSource = null, publishedAt = publishedAt, sizeBytes = 1,
    )

    private fun listening(key: BookKey, positionMs: Long = 0, lastListenedAt: Long? = null) = BookProgressEntity(
        profileId = key.profileId, itemId = key.itemId, positionMs = positionMs, durationAtSaveMs = 60_000,
        editionFingerprint = null, finished = false, updatedAt = 1, stopKind = StopKind.EXACT,
        lastListenedAt = lastListenedAt, playbackGeneration = 1, writeSequence = 1,
    )

    private fun job(id: String, state: TransferState, failure: FailureCategory? = null) = TransferEntity(
        jobId = id, profileId = PROFILE, itemId = id, stagingGenerationId = "staged-$id", manifestFingerprint = null,
        state = state, activeMarker = 1, failureCategory = failure, createdAt = 1, updatedAt = 1, title = id, authors = null,
    )

    private companion object {
        const val PROFILE = "profile"
    }
}
