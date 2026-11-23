package ch.lkmc.pageworn.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.EditionAssembler
import ch.lkmc.pageworn.data.EditionFiles
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.FailureCategory
import ch.lkmc.pageworn.data.db.PairingState
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.formats.FormatSupport
import ch.lkmc.pageworn.formats.MediaProbe
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ManifestSourceTrack
import ch.lkmc.pageworn.server.ResumeRequest
import ch.lkmc.pageworn.server.ServerResult
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Publication recovery on the device's own database and file system (A06, A06b, A06c): the state a
 * process death leaves at each kill point is built with the boundary book's real audio, and a
 * fresh coordinator and edition store, as a relaunched process has, recover it. The staged files
 * are complete, so recovery requests no audio; the channel fails the test if it is asked.
 */
@RunWith(AndroidJUnit4::class)
class PublicationRecoveryTest {
    private val container get() = TestApp.container
    private val db get() = container.database
    private val files get() = container.editionFiles
    private val audio by lazy { Fixtures.install("boundary-book") }
    private val key = TestApp.key(ITEM)

    private val manifest by lazy {
        var offset = 0L
        ItemManifest(ITEM, 1, "Boundary Book", "Fixture Author", null, null, "book",
            audio.mapIndexed { index, file ->
                val duration = TestApp.probeDuration(file)
                ManifestSourceTrack(index, "ino-$index", offset, duration, file.length(), file.lastModified(), "m4a", "aac", "audio/mp4")
                    .also { offset += duration }
            }, emptyList(), coverPresent = false)
    }

    private val routes = object : TransferRoutes {
        override suspend fun manifest(itemId: String): Routed<ItemManifest> = Routed.Done(manifest, ADDRESS)

        override suspend fun channel(addressId: String): DownloadChannel = object : DownloadChannel {
            override fun url(itemId: String, fileId: String) = "http://recovery.invalid/$itemId/$fileId"
            override suspend fun open(itemId: String, fileId: String, resume: ResumeRequest?): ServerResult<Response> =
                throw AssertionError("recovery requested audio for $fileId")
            override suspend fun cover(itemId: String): ByteArray? = null
        }
    }

    /** What a relaunched process builds: no state carried over from the one that died. */
    private fun relaunched(): Pair<TransferCoordinator, EditionStore> {
        val editions = EditionStore(db, files, container.liveClock, container.appScope)
        return TransferCoordinator(db, files, editions, routes, ProbeTrackVerifier(MediaProbe(TestApp.context)), FormatSupport(), container.liveClock) to editions
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        TestApp.reset()
        db.profiles().upsert(ProfileEntity(TestApp.PROFILE, "listener", "Listener", null, null, "2.38.0", 0))
        db.addresses().upsertAll(listOf(AddressEntity(ADDRESS, TestApp.PROFILE, "https://home.invalid/", "Home", 0,
            pairingState = PairingState.PAIRED, sessionState = SessionState.SIGNED_IN)))
        container.progressStore.saveOutsideSession(key, 115_000, 210_000, null, StopKind.ORDINARY)
    }

    private enum class KillPoint { BEFORE_RENAME, AFTER_RENAME, AFTER_COMMIT }

    /** Stages the whole book for [jobId] as verification would, up to [point]. */
    private suspend fun stageUpTo(jobId: String, point: KillPoint, editions: EditionStore): String {
        val job = checkNotNull(db.transfers().get(jobId))
        val staging = files.staging(job.stagingGenerationId).apply { mkdirs() }
        val tracks = manifest.tracks.zip(audio).map { (track, file) ->
            val name = EditionFiles.trackFileName(track.index, track.extension)
            file.copyTo(File(staging, name), overwrite = true)
            EditionAssembler.StagedTrack(name, track.fileId, track.mtimeMs!!, track.sizeBytes!!, track.durationMs, null, track.extension, track.codec)
        }
        val edition = EditionAssembler.assemble(job.stagingGenerationId, jobId, key,
            EditionAssembler.Metadata(manifest.title, manifest.authors, null, null), tracks, emptyList(), emptyList(), null)
        files.writeManifest(staging, edition)
        db.transfers().upsert(job.copy(state = TransferState.VERIFYING))
        if (point >= KillPoint.AFTER_RENAME) files.finalize(job.stagingGenerationId)
        if (point >= KillPoint.AFTER_COMMIT) editions.publish(edition)
        return job.stagingGenerationId
    }

    private suspend fun TransferCoordinator.runToRest(jobId: String) {
        repeat(4) {
            if (db.transfers().get(jobId)?.state == TransferState.COMPLETE) return
            if (!runDue()) return
        }
    }

    private suspend fun assertOneReadyEdition(generationId: String) {
        val edition = checkNotNull(db.editions().edition(key.profileId, key.itemId))
        assertThat(edition.state).isEqualTo(EditionState.READY)
        assertThat(edition.generationId).isEqualTo(generationId)
        assertThat(db.editions().all()).hasSize(1)
        val published = files.published(generationId)
        db.editions().tracks(generationId).forEach { assertThat(File(published, it.localFilename).length()).isGreaterThan(0L) }
        // No orphan generation remains, staged or published.
        assertThat(files.stagingRoot.list().orEmpty().toList()).isEmpty()
        assertThat(files.editionsRoot.list().orEmpty().toList()).containsExactly(generationId)
        assertThat(container.progressStore.read(key)!!.positionMs).isEqualTo(115_000)
    }

    @Test
    fun a06EveryKillPointEndsWithOneReadyEditionAndTheSavedPlace() = runBlocking<Unit> {
        for (point in KillPoint.entries) {
            val (first, firstEditions) = relaunched()
            val jobId = (first.download(key) as DownloadStart.Enqueued).jobId
            val generation = stageUpTo(jobId, point, firstEditions)

            val (recovered, _) = relaunched()
            recovered.ensureRecovered()
            recovered.runToRest(jobId)
            assertOneReadyEdition(generation)

            container.editionStore.remove(key)
            assertThat(container.progressStore.read(key)!!.positionMs).isEqualTo(115_000)
        }
    }

    @Test
    fun a06bCancelAfterRenameIsReclaimedAndALaterDownloadPublishesOnce() = runBlocking<Unit> {
        val (first, firstEditions) = relaunched()
        val cancelled = (first.download(key) as DownloadStart.Enqueued).jobId
        val renamed = stageUpTo(cancelled, KillPoint.AFTER_RENAME, firstEditions)
        first.cancel(cancelled)

        val (recovered, _) = relaunched()
        recovered.ensureRecovered()
        assertThat(db.transfers().get(cancelled)!!.state).isEqualTo(TransferState.CANCELLED)
        assertThat(files.published(renamed).exists()).isFalse()
        assertThat(db.editions().edition(key.profileId, key.itemId)).isNull()

        val later = (recovered.download(key) as DownloadStart.Enqueued).jobId
        val (afterLater, laterEditions) = relaunched()
        val generation = stageUpTo(later, KillPoint.BEFORE_RENAME, laterEditions)
        afterLater.ensureRecovered()
        afterLater.runToRest(later)
        assertOneReadyEdition(generation)
    }

    @Test
    fun a06bCancelThatLosesToPublicationKeepsTheReadyBook() = runBlocking<Unit> {
        val (first, firstEditions) = relaunched()
        val jobId = (first.download(key) as DownloadStart.Enqueued).jobId
        val generation = stageUpTo(jobId, KillPoint.AFTER_COMMIT, firstEditions)
        db.transfers().upsert(checkNotNull(db.transfers().get(jobId)).copy(state = TransferState.COMPLETE))
        first.cancel(jobId)

        val (recovered, _) = relaunched()
        recovered.ensureRecovered()
        assertOneReadyEdition(generation)
    }

    @Test
    fun a06cFailedRepairKeepsTheEditionAndARemovalDuringPublicationFinishesAfterRelaunch() = runBlocking<Unit> {
        // A usable edition, which then needs repair.
        val (first, firstEditions) = relaunched()
        val initial = (first.download(key) as DownloadStart.Enqueued).jobId
        val earlier = stageUpTo(initial, KillPoint.BEFORE_RENAME, firstEditions)
        val (running, editions) = relaunched()
        running.ensureRecovered()
        running.runToRest(initial)
        assertOneReadyEdition(earlier)
        editions.markRepairNeeded(key, earlier)

        // A replacement whose tracks the player cannot open fails verification and changes nothing.
        val failed = (running.download(key) as DownloadStart.Enqueued).jobId
        val failedGeneration = checkNotNull(db.transfers().get(failed)).stagingGenerationId
        val staging = files.staging(failedGeneration).apply { mkdirs() }
        manifest.tracks.forEach { track ->
            File(staging, EditionFiles.trackFileName(track.index, track.extension)).writeBytes(ByteArray(track.sizeBytes!!.toInt()) { 0x5A })
        }
        running.runToRest(failed)
        assertThat(db.transfers().get(failed)!!.failureCategory).isEqualTo(FailureCategory.UNSUPPORTED_FORMAT)
        assertThat(db.editions().edition(key.profileId, key.itemId)!!.generationId).isEqualTo(earlier)
        assertThat(db.editions().edition(key.profileId, key.itemId)!!.state).isEqualTo(EditionState.REPAIR_NEEDED)
        assertThat(files.published(earlier).list().orEmpty().toList()).isNotEmpty()
        running.cancel(failed)

        // Another replacement reaches the rename; the book is removed before it is committed,
        // and the process dies once the removal is recorded.
        val replacement = (running.download(key) as DownloadStart.Enqueued).jobId
        val pending = stageUpTo(replacement, KillPoint.AFTER_RENAME, editions)
        editions.remove(key)

        val (recovered, _) = relaunched()
        recovered.ensureRecovered()
        assertThat(db.transfers().get(replacement)!!.state).isEqualTo(TransferState.CANCELLED)
        assertThat(db.editions().edition(key.profileId, key.itemId)).isNull()
        assertThat(files.published(earlier).exists()).isFalse()
        assertThat(files.published(pending).exists()).isFalse()
        assertThat(files.staging(pending).exists()).isFalse()
        assertThat(container.progressStore.read(key)!!.positionMs).isEqualTo(115_000)
    }

    private companion object {
        const val ITEM = "boundary-book"
        const val ADDRESS = "home"
    }
}
