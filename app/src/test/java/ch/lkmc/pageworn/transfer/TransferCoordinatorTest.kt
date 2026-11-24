package ch.lkmc.pageworn.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.timeline.ChapterOrigin
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.data.EditionAssembler
import ch.lkmc.pageworn.data.EditionFiles
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ProgressStore
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.FailureCategory
import ch.lkmc.pageworn.data.db.FileTransferState
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.formats.DecoderAvailability
import ch.lkmc.pageworn.formats.FormatSupport
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ManifestSourceTrack
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.ui.model.ContinuityChoice
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.state.PlayerStates
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var db: PagewornDatabase
    private lateinit var files: EditionFiles
    private lateinit var editions: EditionStore
    private lateinit var routes: FakeTransferServer
    private lateinit var coordinator: TransferCoordinator
    private val clock = FakeClock()
    private var sequence = 0
    private var freeBytes = 100_000_000_000L
    private val unsupported = mutableSetOf<String>()

    /** Where the store cleans up after a released lease; [settled] waits for that work. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private suspend fun settled() = background.coroutineContext.job.children.toList().joinAll()

    /** Markers the probe finds, by staged file name. */
    private val probedChapters = mutableMapOf<String, List<RawChapter>>()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagewornDatabase::class.java).build()
        files = EditionFiles(folder.newFolder("media"))
        editions = EditionStore(db, files, clock, background)
        db.profiles().upsert(ProfileEntity(PROFILE, "listener", "Listener", null, null, "2.38.0", clock.wall))
        db.addresses().upsertAll(listOf(address("home", 0), address("remote", 1)))
        routes = FakeTransferServer { db.addresses().forProfile(PROFILE) }
        coordinator = freshCoordinator()
    }

    @After fun tearDown() {
        background.cancel()
        db.close()
    }

    private fun freshCoordinator(transferRoutes: TransferRoutes = routes) = TransferCoordinator(db, files, editions, transferRoutes,
        TrackVerifier { file, _, _ ->
            if (file.name in unsupported) TrackCheck.Unsupported("fixture unsupported")
            else TrackCheck.Playable(90_000, "aac", probedChapters[file.name].orEmpty())
        }, FormatSupport(DecoderAvailability { true }), clock, { freeBytes }, { "id-${++sequence}" })

    private fun address(id: String, priority: Int) = AddressEntity(id, PROFILE, "https://$id.example/", id, priority)

    private fun source(seed: Int, size: Int = 128 * 1024): ByteArray = ByteArray(size) { ((it + seed) % 251).toByte() }

    private fun book(id: String, vararg tracks: Pair<String, ByteArray>): BookKey {
        routes.manifests[id] = ItemManifest(id, 1, id, null, null, null, "book",
            tracks.mapIndexed { index, (fileId, data) ->
                ManifestSourceTrack(index, fileId, index * 90_000L, 90_000, data.size.toLong(), 1_000,
                    "m4a", "aac", "audio/mp4")
            }, emptyList(), false)
        tracks.forEach { (fileId, data) ->
            routes.sources["home" to fileId] = data
            routes.sources["remote" to fileId] = data
        }
        return BookKey(PROFILE, id)
    }

    private suspend fun enqueue(key: BookKey): String = (coordinator.download(key) as DownloadStart.Enqueued).jobId
    private suspend fun job(id: String) = checkNotNull(db.transfers().get(id))
    private suspend fun staged(id: String, ordinal: Int) = File(files.staging(job(id).stagingGenerationId),
        db.transfers().files(id)[ordinal].localFilename)
    private suspend fun published(key: BookKey, ordinal: Int): ByteArray {
        val edition = checkNotNull(db.editions().edition(key.profileId, key.itemId))
        return File(files.published(edition.generationId), db.editions().tracks(edition.generationId)[ordinal].localFilename).readBytes()
    }
    private suspend fun ready(key: BookKey) {
        val edition = db.editions().edition(key.profileId, key.itemId)
        assertThat(edition?.state).isEqualTo(EditionState.READY)
        assertThat(files.published(edition!!.generationId).isDirectory).isTrue()
        assertThat(db.editions().tracks(edition.generationId)).isNotEmpty()
    }

    /** Paused is saved a moment before the copy is cancelled. Data released in between is all copied. */
    private suspend fun awaitPauseReachedTheCopy(id: String, run: Job) = withTimeout(5_000) {
        while (job(id).state != TransferState.PAUSED || run.descendants().none { it.isCancelled }) yield()
    }

    private fun Job.descendants(): Sequence<Job> = children.flatMap { sequenceOf(it) + it.descendants() }

    private suspend fun drive(id: String, limit: Int = 8) {
        repeat(limit) {
            if (job(id).state == TransferState.COMPLETE) return
            val next = job(id).nextRetryAt
            if (next != null && next > clock.wall) clock.advance(next - clock.wall + 1)
            if (!coordinator.runDue()) return
        }
    }
    private fun progress(id: String) = BookProgressEntity(PROFILE, id, 27_000, 180_000, null, false,
        clock.wall, StopKind.ORDINARY, clock.wall, 1, 1)
    private suspend fun disable(vararg ids: String) {
        ids.forEach { id -> db.addresses().get(id)?.let { db.addresses().upsert(it.copy(enabled = false)) } }
    }

    @Test fun a28_probedMarkersBecomeTheChaptersOnlyWithoutAUsableServerList() = runBlocking<Unit> {
        val key = book("a28", "A" to source(1), "B" to source(2))
        probedChapters["000.m4a"] = listOf(RawChapter(0, null, "The Gate"))
        probedChapters["001.m4a"] = listOf(RawChapter(0, 40_000, " "), RawChapter(40_000, null, "The Tree"))
        val id = enqueue(key)
        drive(id)
        ready(key)
        val generation = checkNotNull(db.editions().edition(PROFILE, "a28")).generationId
        // Each file's markers move to book time by the file's start; a blank title is numbered.
        assertThat(db.editions().chapters(generation).map { Triple(it.startMs, it.endMs, it.title) }).containsExactly(
            Triple(0L, 90_000L, "The Gate"),
            Triple(90_000L, 130_000L, "Chapter 2"),
            Triple(130_000L, 180_000L, "The Tree"),
        ).inOrder()
        assertThat(db.editions().chapters(generation).map { it.origin }.distinct()).containsExactly(ChapterOrigin.EMBEDDED)

        // A usable server list wins, and the markers are not mixed into it.
        editions.remove(key)
        routes.manifests["a28"] = routes.manifests.getValue("a28").copy(chapters = listOf(RawChapter(0, 180_000, "Whole")))
        val again = enqueue(key)
        drive(again)
        val replaced = checkNotNull(db.editions().edition(PROFILE, "a28")).generationId
        assertThat(db.editions().chapters(replaced).map { it.title to it.origin }).containsExactly("Whole" to ChapterOrigin.SERVER)
    }

    @Test fun a04_duplicateTapRouteSwitchRestartsPartialButReusesComplete() = runBlocking {
        val a = source(1)
        val b = source(2)
        val changed = source(3)
        val key = book("a04", "A" to a, "B" to b)
        routes.sources["remote" to "B"] = changed
        routes.script("home", "B", FakeTransferServer.Reply(cutAfter = 4_096))
        val id = enqueue(key)
        assertThat(coordinator.download(key)).isEqualTo(DownloadStart.Attached(id))
        assertThat(coordinator.runDue()).isTrue()
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.NETWORK)
        assertThat(staged(id, 1).length()).isGreaterThan(0L)
        routes.selected = "remote"
        drive(id)
        ready(key)
        assertThat(published(key, 1)).isEqualTo(changed)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        assertThat(routes.requests.filter { it.address == "remote" && it.fileId == "B" }.single().range).isNull()
        assertThat(db.editions().all()).hasSize(1)
    }

    @Test fun a04b_removedAddressWaitsAndNewAddressReusesA() = runBlocking {
        val a = source(4)
        val b = source(5)
        val changed = source(6)
        val key = book("a04b", "A" to a, "B" to b)
        routes.sources["remote" to "B"] = changed
        val gate = FakeTransferServer.Gate(1_024, failAfterRelease = true)
        routes.script("home", "B", FakeTransferServer.Reply(gate = gate))
        val id = enqueue(key)
        val running = launch(Dispatchers.Default) { coordinator.runDue() }
        withTimeout(5_000) { gate.reached.await() }
        disable("home")
        val changedAddress = launch(Dispatchers.Default) { coordinator.addressesChanged(db.addresses().forProfile(PROFILE)) }
        gate.release.complete(Unit)
        changedAddress.join()
        running.join()
        assertThat(job(id).state).isEqualTo(TransferState.WAITING_FOR_CONNECTION)
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        routes.selected = "remote"
        drive(id)
        ready(key)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        assertThat(routes.requests.filter { it.address == "remote" && it.fileId == "B" }.single().range).isNull()
        assertThat(published(key, 1)).isEqualTo(changed)
    }

    @Test fun a04b_allAddressesDisabledWaitsThenCompletesWithoutDuplicateAudio() = runBlocking {
        val a = source(7)
        val b = source(8)
        val key = book("a04b-no-route", "A" to a, "B" to b)
        routes.script("home", "B", FakeTransferServer.Reply(cutAfter = 4_096))
        val id = enqueue(key)
        coordinator.runDue()
        disable("home", "remote")
        coordinator.addressesChanged(db.addresses().forProfile(PROFILE))
        clock.advance(10_000)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.WAITING_FOR_CONNECTION)
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        db.addresses().upsert(address("remote", 1))
        routes.selected = "remote"
        coordinator.addressesChanged(db.addresses().forProfile(PROFILE))
        drive(id)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(a)
        assertThat(published(key, 1)).isEqualTo(b)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
    }

    @Test fun a04b_addressRestoredDuringAFailedRouteLookupRetriesWithoutBackoff() = runBlocking<Unit> {
        val staleDecision = CompletableDeferred<Unit>()
        val returnDecision = CompletableDeferred<Unit>()
        coordinator = freshCoordinator(object : TransferRoutes by routes {
            override suspend fun manifest(itemId: String): Routed<ItemManifest> {
                val result = routes.manifest(itemId)
                if (result is Routed.Unavailable) {
                    staleDecision.complete(Unit)
                    returnDecision.await()
                }
                return result
            }
        })
        val key = book("address-restored", "A" to source(9))
        val id = enqueue(key)
        disable("home", "remote")
        val firstAttempt = launch { coordinator.runDue() }
        withTimeout(5_000) { staleDecision.await() }
        db.addresses().upsert(address("home", 0))
        coordinator.addressesChanged(db.addresses().forProfile(PROFILE))
        returnDecision.complete(Unit)
        firstAttempt.join()

        assertThat(job(id).state).isEqualTo(TransferState.WAITING_FOR_CONNECTION)
        assertThat(coordinator.runDue()).isTrue()
        ready(key)
    }

    @Test fun a05_validShort206AdvancesPartialAnd200ReplacesIt() = runBlocking {
        val data = source(9)
        val key = book("a05-short", "A" to data)
        routes.script("home", "A", FakeTransferServer.Reply(body = data.copyOfRange(0, 100)),
            FakeTransferServer.Reply(code = 206, body = data.copyOfRange(100, 200), contentRange = "bytes 100-199/${data.size}"),
            FakeTransferServer.Reply(code = 500))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(staged(id, 0).length()).isEqualTo(200L)
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
        assertThat(routes.requests[1].range).isEqualTo("bytes=100-")
        routes.script("home", "A", FakeTransferServer.Reply(ignoreRange = true))
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests.last().range).isEqualTo("bytes=200-")
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05_invalid206PreservesPrefixBeforeRecovery() = runBlocking {
        val data = source(10)
        val key = book("a05-invalid", "A" to data)
        val prefix = data.copyOfRange(0, 100)
        routes.script("home", "A", FakeTransferServer.Reply(body = prefix),
            FakeTransferServer.Reply(code = 206, body = data.copyOfRange(100, 200), contentRange = "bytes 99-198/${data.size}"),
            FakeTransferServer.Reply(code = 200, body = prefix),
            FakeTransferServer.Reply(code = 206, body = data.copyOfRange(100, 200), contentRange = "bytes 100-199/${data.size}", etag = "\"wrong\""),
            FakeTransferServer.Reply(code = 500))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(staged(id, 0).length()).isEqualTo(100L)
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05_noAudio204NeverPublishes() = runBlocking {
        val key = book("a05-accel", "A" to source(11))
        routes.script("home", "A", FakeTransferServer.Reply(code = 204, accel = true))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.FAILED)
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.NO_AUDIO)
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
    }

    @Test fun a05_truncated200NeverPublishesAnd416RestartsPartial() = runBlocking {
        val data = source(12)
        val key = book("a05-416", "A" to data)
        routes.script("home", "A", FakeTransferServer.Reply(body = data.copyOfRange(0, 100)),
            FakeTransferServer.Reply(code = 416), FakeTransferServer.Reply(code = 500))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
        assertThat(staged(id, 0).length()).isEqualTo(0L)
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05b_serverErrorBacksOffThenSucceeds() = runBlocking {
        val data = source(13)
        val key = book("a05b-500", "A" to data)
        routes.script("home", "A", FakeTransferServer.Reply(code = 500))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.FAILED)
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.SERVER)
        assertThat(job(id).nextRetryAt).isGreaterThan(clock.wall)
        assertThat(coordinator.runDue()).isFalse()
        clock.wall = job(id).nextRetryAt!! + 1
        drive(id)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05b_deniedLaterFileNeedsExplicitResumeAndKeepsA() = runBlocking {
        val a = source(14)
        val b = source(15)
        val key = book("a05b-403", "A" to a, "B" to b)
        routes.script("home", "B", FakeTransferServer.Reply(code = 403))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.FAILED)
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.DOWNLOAD_DENIED)
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        clock.advance(86_400_000)
        assertThat(coordinator.runDue()).isFalse()
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        assertThat(published(key, 1)).isEqualTo(b)
    }

    @Test fun a05b_explicitResumeAfter403KeepsVerifiedFile() = runBlocking {
        val a = source(38)
        val b = source(39)
        val key = book("a05b-retry", "A" to a, "B" to b)
        routes.script("home", "B", FakeTransferServer.Reply(code = 403))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        assertThat(published(key, 1)).isEqualTo(b)
    }

    @Test fun a05c_persistent404RefetchesOnceThenCancelReclaims() = runBlocking {
        val a = source(16)
        val key = book("a05c-404", "A" to a, "B" to source(17))
        routes.script("home", "B", FakeTransferServer.Reply(code = 404), FakeTransferServer.Reply(code = 404),
            FakeTransferServer.Reply(code = 404), FakeTransferServer.Reply(code = 404))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.SOURCE_MISMATCH)
        assertThat(routes.manifestCalls[key.itemId]).isEqualTo(3) // enqueue, run, one reconciliation
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        val before = routes.manifestCalls[key.itemId]!!
        coordinator.resume(id)
        coordinator.runDue()
        assertThat(routes.manifestCalls[key.itemId]).isGreaterThan(before)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        val generation = job(id).stagingGenerationId
        coordinator.cancel(id)
        assertThat(files.staging(generation).exists()).isFalse()
    }

    @Test fun a05c_resumeAfter404ReadsManifestFirstAndCancelReclaims() = runBlocking {
        val a = source(40)
        val key = book("a05c-retry", "A" to a, "B" to source(41))
        routes.script("home", "B", FakeTransferServer.Reply(code = 404), FakeTransferServer.Reply(code = 404))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        val before = routes.manifestCalls[key.itemId]!!
        coordinator.resume(id)
        coordinator.runDue()
        assertThat(routes.manifestCalls[key.itemId]).isGreaterThan(before)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        val generation = job(id).stagingGenerationId
        coordinator.cancel(id)
        assertThat(files.staging(generation).exists()).isFalse()
    }

    @Test fun a05c_changedManifestAfterPauseStopsWithoutAutomaticRetry() = runBlocking {
        val key = book("a05c-changed", "A" to source(18))
        val gate = FakeTransferServer.Gate(1_024)
        routes.script("home", "A", FakeTransferServer.Reply(gate = gate))
        val id = enqueue(key)
        val running = launch(Dispatchers.Default) { coordinator.runDue() }
        withTimeout(5_000) { gate.reached.await() }
        val pausing = launch(Dispatchers.Default) { coordinator.pause(id) }
        awaitPauseReachedTheCopy(id, running)
        gate.release.complete(Unit)
        pausing.join()
        running.join()
        routes.manifestErrors[key.itemId] = ServerError.Unexpected(200, "play order cannot join")
        coordinator.resume(id)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.FAILED)
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.SOURCE_MISMATCH)
        assertThat(coordinator.runDue()).isFalse()
    }

    @Test fun a05d_oldLastModifiedResumesUsingDate() = runBlocking {
        weakDateCase("a05d-old", OLD_DATE, NEW_DATE, true)
    }

    @Test fun a05d_recentLastModifiedRestartsAtZero() = runBlocking {
        weakDateCase("a05d-recent", RECENT_DATE, NEW_DATE, false)
    }

    private suspend fun weakDateCase(id: String, modified: String, date: String, shouldResume: Boolean) {
        val data = source(19)
        val key = book(id, "A" to data)
        routes.defaultEtag = "W/\"weak\""
        routes.defaultLastModified = modified
        routes.defaultDate = date
        routes.script("home", "A", FakeTransferServer.Reply(cutAfter = 4_096))
        val jobId = enqueue(key)
        coordinator.runDue()
        assertThat(staged(jobId, 0).length()).isGreaterThan(0L)
        coordinator.resume(jobId)
        drive(jobId)
        ready(key)
        val second = routes.requests[1]
        if (shouldResume) {
            assertThat(second.range).isEqualTo("bytes=4096-")
            assertThat(second.ifRange).isEqualTo(modified)
        } else {
            assertThat(second.range).isNull()
            assertThat(second.ifRange).isNull()
        }
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05d_changedRevisionRestartsAtZero() = runBlocking {
        val data = source(20)
        val key = book("a05d-revision", "A" to data)
        routes.defaultEtag = "W/\"weak\""
        routes.defaultLastModified = OLD_DATE
        routes.defaultDate = NEW_DATE
        routes.script("home", "A", FakeTransferServer.Reply(cutAfter = 4_096))
        val id = enqueue(key)
        coordinator.runDue()
        val manifest = routes.manifests[key.itemId]!!
        routes.manifests[key.itemId] = manifest.copy(tracks = manifest.tracks.map { it.copy(mtimeMs = 2_000) })
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests[1].range).isNull()
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05d_changedSizeRestartsAtZero() = runBlocking {
        val original = source(42)
        val changed = source(43, original.size + 1)
        val key = book("a05d-size", "A" to original)
        routes.defaultEtag = "W/\"weak\""
        routes.defaultLastModified = OLD_DATE
        routes.defaultDate = NEW_DATE
        routes.script("home", "A", FakeTransferServer.Reply(cutAfter = 4_096))
        val id = enqueue(key)
        coordinator.runDue()
        val manifest = routes.manifests[key.itemId]!!
        routes.manifests[key.itemId] = manifest.copy(tracks = manifest.tracks.map { it.copy(sizeBytes = changed.size.toLong()) })
        routes.sources["home" to "A"] = changed
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests[1].range).isNull()
        assertThat(published(key, 0)).isEqualTo(changed)
    }

    @Test fun a06_recoveryBeforeRenameAfterRenameAndAfterCommit() = runBlocking {
        for (point in 0..2) {
            val id = "a06-$point"
            val data = source(21 + point)
            val key = book(id, "A-$point" to data)
            val saved = progress(id)
            db.progress().upsert(saved)
            val jobId = enqueue(key)
            val transfer = job(jobId)
            val track = routes.manifests[id]!!.tracks.single()
            val manifest = EditionAssembler.assemble(transfer.stagingGenerationId, jobId, key,
                EditionAssembler.Metadata(id, null, null, null),
                listOf(EditionAssembler.StagedTrack(EditionFiles.trackFileName(0, "m4a"), track.fileId,
                    track.mtimeMs!!, data.size.toLong(), 90_000, null, "m4a", "aac")),
                emptyList(), emptyList(), null)
            val dir = files.staging(transfer.stagingGenerationId).apply { mkdirs() }
            File(dir, EditionFiles.trackFileName(0, "m4a")).writeBytes(data)
            files.writeManifest(dir, manifest)
            db.transfers().upsert(transfer.copy(state = TransferState.VERIFYING))
            if (point >= 1) files.finalize(transfer.stagingGenerationId)
            if (point == 2) editions.publish(manifest)
            coordinator = freshCoordinator()
            coordinator.ensureRecovered()
            drive(jobId)
            ready(key)
            assertThat(db.progress().get(PROFILE, id)).isEqualTo(saved)
            assertThat(files.staging(transfer.stagingGenerationId).exists()).isFalse()
            assertThat(files.published(transfer.stagingGenerationId).isDirectory).isTrue()
        }
        assertThat(db.editions().all()).hasSize(3)
    }

    @Test fun a06b_cancelSurvivesRestartAndLaterDownloadPublishes() = runBlocking {
        val key = book("a06b", "A" to source(24))
        val gate = FakeTransferServer.Gate(1_024)
        routes.script("home", "A", FakeTransferServer.Reply(gate = gate))
        val id = enqueue(key)
        val running = launch(Dispatchers.Default) { coordinator.runDue() }
        withTimeout(5_000) { gate.reached.await() }
        val cancelling = launch(Dispatchers.Default) { coordinator.cancel(id) }
        gate.release.complete(Unit)
        cancelling.join()
        running.join()
        assertThat(job(id).state).isEqualTo(TransferState.CANCELLED)
        coordinator = freshCoordinator()
        coordinator.ensureRecovered()
        assertThat(files.staging(job(id).stagingGenerationId).exists()).isFalse()
        val next = enqueue(key)
        drive(next)
        ready(key)
        val saved = progress(key.itemId)
        db.progress().upsert(saved)
        coordinator.cancel(id)
        ready(key)
        assertThat(db.progress().get(PROFILE, key.itemId)).isEqualTo(saved)
        assertThat(db.editions().all()).hasSize(1)
    }

    // Only an edition that needs repair is replaced (a Download tap on a Ready book starts no
    // job), so the failed replacement must leave that edition exactly as it was.
    @Test fun a06c_failedRepairKeepsEarlierEdition() = runBlocking {
        val key = book("a06c", "A" to source(25))
        val initial = enqueue(key)
        drive(initial)
        ready(key)
        val previous = db.editions().edition(PROFILE, key.itemId)!!
        val saved = progress(key.itemId)
        db.progress().upsert(saved)
        editions.markRepairNeeded(key, previous.generationId)
        unsupported += "000.m4a"
        val repair = enqueue(key)
        coordinator.runDue()
        assertThat(job(repair).failureCategory).isEqualTo(FailureCategory.UNSUPPORTED_FORMAT)
        assertThat(files.published(previous.generationId).isDirectory).isTrue()
        assertThat(db.editions().edition(PROFILE, key.itemId)?.generationId).isEqualTo(previous.generationId)
        assertThat(db.progress().get(PROFILE, key.itemId)).isEqualTo(saved)
        assertThat(db.editions().edition(PROFILE, key.itemId)?.state).isEqualTo(EditionState.REPAIR_NEEDED)
        assertThat(files.published(previous.generationId).listFiles().orEmpty().map { it.name }).contains("000.m4a")
    }

    @Test fun a21_spaceFallsBeforeSecondFileThenResumeAndCancelReclaims() = runBlocking {
        val a = source(26)
        val b = source(27)
        val key = book("a21-mid", "A" to a, "B" to b)
        val gate = FakeTransferServer.Gate(1_024)
        routes.script("home", "A", FakeTransferServer.Reply(gate = gate))
        val id = enqueue(key)
        val running = launch(Dispatchers.Default) { coordinator.runDue() }
        withTimeout(5_000) { gate.reached.await() }
        freeBytes = SpacePolicy.MARGIN_BYTES + b.size - 500L
        gate.release.complete(Unit)
        running.join()
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.STORAGE_FULL)
        assertThat(job(id).shortfallBytes).isEqualTo(500L)
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        assertThat(routes.requests.none { it.fileId == "B" }).isTrue()
        freeBytes = 100_000_000_000L
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        val other = book("a21-cancel", "C" to source(28))
        freeBytes = 0
        val otherId = enqueue(other)
        coordinator.runDue()
        val generation = job(otherId).stagingGenerationId
        coordinator.cancel(otherId)
        assertThat(files.staging(generation).exists()).isFalse()
    }

    @Test fun a21_failedWriteRestsAsStorageFullAndRetryCompletes() = runBlocking {
        // The phone refuses to write track B (here a read-only file stands in for a full disk):
        // the job rests with the storage-full category instead of retrying as a network failure.
        val a = source(38)
        val b = source(39)
        val key = book("a21-write", "A" to a, "B" to b)
        val id = enqueue(key)
        val blocked = File(files.staging(job(id).stagingGenerationId).apply { mkdirs() }, EditionFiles.trackFileName(1, "m4a"))
        blocked.createNewFile()
        blocked.setWritable(false)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.FAILED)
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.STORAGE_FULL)
        // Free space looks sufficient, so the shortfall is unknown and not shown.
        assertThat(job(id).shortfallBytes).isNull()
        assertThat(staged(id, 0).readBytes()).isEqualTo(a)
        clock.advance(86_400_000)
        assertThat(coordinator.runDue()).isFalse()
        blocked.setWritable(true)
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests.count { it.fileId == "A" }).isEqualTo(1)
        assertThat(published(key, 1)).isEqualTo(b)
    }

    @Test fun a21b_preflightRequiresOneGbMarginBeforeAnyAudio() = runBlocking {
        val data = source(29)
        val key = book("a21b-preflight", "A" to data)
        freeBytes = data.size + 500_000_000L
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.FAILED)
        assertThat(job(id).failureCategory).isEqualTo(FailureCategory.STORAGE_FULL)
        assertThat(job(id).shortfallBytes).isEqualTo(500_000_000L)
        assertThat(routes.requests).isEmpty()
        freeBytes = data.size + SpacePolicy.MARGIN_BYTES
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun pause_duringCopyKeepsPartialAndYieldsSlot() = runBlocking {
        val a = source(30)
        val key = book("pause", "A" to a)
        val next = book("next", "N" to source(31))
        val gate = FakeTransferServer.Gate(1_024)
        routes.script("home", "A", FakeTransferServer.Reply(gate = gate))
        val id = enqueue(key)
        val nextId = enqueue(next)
        val running = launch(Dispatchers.Default) { coordinator.runDue() }
        withTimeout(5_000) { gate.reached.await() }
        val pausing = launch(Dispatchers.Default) { coordinator.pause(id) }
        awaitPauseReachedTheCopy(id, running)
        gate.release.complete(Unit)
        pausing.join()
        running.join()
        assertThat(job(id).state).isEqualTo(TransferState.PAUSED)
        assertThat(job(id).userPaused).isTrue()
        assertThat(staged(id, 0).length()).isGreaterThan(0L)
        assertThat(staged(id, 0).length()).isLessThan(a.size.toLong())
        assertThat(coordinator.runDue()).isTrue()
        ready(next)
        assertThat(job(nextId).state).isEqualTo(TransferState.COMPLETE)
        assertThat(coordinator.runDue()).isFalse()
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(routes.requests.filter { it.fileId == "A" }[1].range).isNotNull()
        assertThat(published(key, 0)).isEqualTo(a)
    }

    @Test fun a05_wrong206BodyLengthKeepsUsablePrefix() = runBlocking {
        val data = source(32)
        val key = book("a05-length", "A" to data)
        routes.script("home", "A", FakeTransferServer.Reply(body = data.copyOfRange(0, 100)),
            FakeTransferServer.Reply(code = 206, body = data.copyOfRange(100, 150),
                contentRange = "bytes 100-199/${data.size}"), FakeTransferServer.Reply(code = 500))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(staged(id, 0).readBytes()).isEqualTo(data.copyOfRange(0, 100))
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05_encoded206KeepsUsablePrefix() = runBlocking {
        val data = source(33)
        val key = book("a05-encoding", "A" to data)
        routes.script("home", "A", FakeTransferServer.Reply(body = data.copyOfRange(0, 100)),
            FakeTransferServer.Reply(code = 206, body = data.copyOfRange(100, data.size),
                contentRange = "bytes 100-${data.size - 1}/${data.size}", contentEncoding = "gzip"),
            FakeTransferServer.Reply(code = 500))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(staged(id, 0).readBytes()).isEqualTo(data.copyOfRange(0, 100))
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
        coordinator.resume(id)
        drive(id)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(data)
    }

    @Test fun a05_htmlResponseNeverPublishes() = runBlocking {
        val key = book("a05-html", "A" to source(34))
        routes.script("home", "A", FakeTransferServer.Reply(body = "<html>login</html>".toByteArray(),
            contentType = "text/html"))
        val id = enqueue(key)
        coordinator.runDue()
        assertThat(job(id).state).isEqualTo(TransferState.FAILED)
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
    }

    @Test fun a06b_cancelledFinalizedGenerationIsReclaimedOnRecovery() = runBlocking {
        val data = source(35)
        val key = book("a06b-finalized", "A" to data)
        val id = enqueue(key)
        val transfer = job(id)
        val track = routes.manifests[key.itemId]!!.tracks.single()
        val manifest = EditionAssembler.assemble(transfer.stagingGenerationId, id, key,
            EditionAssembler.Metadata(key.itemId, null, null, null),
            listOf(EditionAssembler.StagedTrack(EditionFiles.trackFileName(0, "m4a"), track.fileId,
                track.mtimeMs!!, data.size.toLong(), 90_000, null, "m4a", "aac")),
            emptyList(), emptyList(), null)
        val staging = files.staging(transfer.stagingGenerationId).apply { mkdirs() }
        File(staging, EditionFiles.trackFileName(0, "m4a")).writeBytes(data)
        files.writeManifest(staging, manifest)
        db.transfers().upsert(transfer.copy(state = TransferState.VERIFYING))
        files.finalize(transfer.stagingGenerationId)
        coordinator.cancel(id)
        coordinator = freshCoordinator()
        coordinator.ensureRecovered()
        assertThat(job(id).state).isEqualTo(TransferState.CANCELLED)
        assertThat(files.published(transfer.stagingGenerationId).exists()).isFalse()
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
        val later = enqueue(key)
        drive(later)
        ready(key)
        assertThat(db.editions().all()).hasSize(1)
    }

    @Test fun a06c_removalCancelsPendingRepairAndPreservesProgress() = runBlocking {
        val key = book("a06c-removal", "A" to source(36))
        val initial = enqueue(key)
        drive(initial)
        val previous = db.editions().edition(PROFILE, key.itemId)!!
        val saved = progress(key.itemId)
        db.progress().upsert(saved)
        editions.markRepairNeeded(key, previous.generationId)
        unsupported += "000.m4a"
        val repair = enqueue(key)
        coordinator.runDue()
        // A stopped repair leaves a pending generation for removal to invalidate.
        val stagedGeneration = job(repair).stagingGenerationId
        editions.remove(key)
        coordinator = freshCoordinator()
        coordinator.ensureRecovered()
        assertThat(job(repair).state).isEqualTo(TransferState.CANCELLED)
        assertThat(db.editions().edition(PROFILE, key.itemId)).isNull()
        assertThat(files.published(previous.generationId).exists()).isFalse()
        assertThat(files.staging(stagedGeneration).exists()).isFalse()
        assertThat(db.progress().get(PROFILE, key.itemId)).isEqualTo(saved)
    }

    // docs/03 rule 5: a replaced copy goes once no session holds it, not at the next start.
    @Test fun a06c_aReplacedCopyIsDeletedWhenPlaybackLetsGoOfIt() = runBlocking {
        val key = book("a06c-held", "A" to source(61), "B" to source(62))
        drive(enqueue(key))
        val old = db.editions().edition(PROFILE, key.itemId)!!.generationId
        assertThat(editions.leaseForPlayback(key)?.generationId).isEqualTo(old)
        editions.markRepairNeeded(key, old)
        drive(enqueue(key))
        val new = db.editions().edition(PROFILE, key.itemId)!!.generationId
        assertThat(new).isNotEqualTo(old)
        assertThat(files.published(old).isDirectory).isTrue()
        assertThat(editions.leaseForPlayback(key)?.generationId).isEqualTo(new)
        editions.release(new)

        editions.release(old)
        settled()

        assertThat(files.published(old).exists()).isFalse()
        ready(key)
    }

    @Test fun a06c_releasingTheCurrentCopyKeepsIt() = runBlocking {
        val key = book("a06c-current", "A" to source(63))
        drive(enqueue(key))
        val current = db.editions().edition(PROFILE, key.itemId)!!.generationId
        assertThat(editions.leaseForPlayback(key)?.generationId).isEqualTo(current)
        assertThat(editions.leaseForPlayback(key)?.generationId).isEqualTo(current)

        editions.release(current)
        settled()
        editions.release(current)
        settled()

        ready(key)
        assertThat(files.published(current).isDirectory).isTrue()
    }

    @Test fun a20_aTrackThatLostBytesOnTheDiskIsFoundDamagedBeforeOpening() = runBlocking {
        val key = book("damaged", "a" to source(1), "b" to source(2))
        drive(enqueue(key))
        ready(key)
        assertThat(editions.checkBeforeOpening(key)).isTrue()
        val edition = checkNotNull(db.editions().edition(key.profileId, key.itemId))
        val track = File(files.published(edition.generationId), db.editions().tracks(edition.generationId)[1].localFilename)
        RandomAccessFile(track, "rw").use { it.setLength(track.length() / 2) }
        assertThat(editions.checkBeforeOpening(key)).isFalse()
        assertThat(db.editions().edition(key.profileId, key.itemId)?.state).isEqualTo(EditionState.REPAIR_NEEDED)
        assertThat(editions.plan(key)).isNull()
    }

    @Test fun a11_onlyChangedAudioAsksWhereToStart() = runBlocking<Unit> {
        val first = source(41)
        val second = source(42)
        val key = book("a11", "A" to first, "B" to second)
        drive(enqueue(key))
        val original = db.editions().edition(PROFILE, key.itemId)!!
        val saved = progress(key.itemId).copy(editionFingerprint = original.fingerprint)
        db.progress().upsert(saved)

        routes.manifests[key.itemId] = routes.manifests.getValue(key.itemId).copy(title = "A new title")
        editions.remove(key)
        drive(enqueue(key))

        assertThat(db.editions().edition(PROFILE, key.itemId)?.title).isEqualTo("A new title")
        assertThat(db.progress().get(PROFILE, key.itemId)).isEqualTo(saved)

        book("a11", "B" to second, "A" to first)
        editions.remove(key)
        drive(enqueue(key))

        val changed = checkNotNull(db.progress().get(PROFILE, key.itemId))
        assertThat(changed).isEqualTo(saved.copy(pendingContinuityChoice = true))
        val plan = checkNotNull(editions.plan(key))
        val player = PlayerStates.player(plan, db.editions().edition(PROFILE, key.itemId), changed, LivePlayback())
        assertThat(player.continuityChoice).isEqualTo(ContinuityChoice(Format.hoursMinutes(27_000), Format.hoursMinutes(180_000)))
        assertThat(player.transportEnabled).isFalse()
        assertThat(player.transportDisabledReason).isEqualTo(Copy.CHOOSE_START)

        ProgressStore(db.progress(), clock).saveOutsideSession(key, 27_000, 180_000, plan.fingerprint, StopKind.EXACT)

        val chosen = checkNotNull(db.progress().get(PROFILE, key.itemId))
        assertThat(chosen.pendingContinuityChoice).isFalse()
        assertThat(chosen.positionMs).isEqualTo(27_000)
    }

    @Test fun a07_removeAndRedownloadPreservesSavedPlace() = runBlocking {
        val data = source(37)
        val key = book("a07", "A" to data)
        val first = enqueue(key)
        drive(first)
        val original = db.editions().edition(PROFILE, key.itemId)!!
        val saved = progress(key.itemId)
        db.progress().upsert(saved)
        editions.remove(key)
        assertThat(files.published(original.generationId).exists()).isFalse()
        assertThat(db.progress().get(PROFILE, key.itemId)).isEqualTo(saved)
        val second = enqueue(key)
        drive(second)
        ready(key)
        assertThat(published(key, 0)).isEqualTo(data)
        assertThat(db.progress().get(PROFILE, key.itemId)).isEqualTo(saved)
    }

    companion object {
        private const val PROFILE = "profile"
        private const val OLD_DATE = "Sat, 21 Nov 2026 15:00:00 GMT"
        private const val RECENT_DATE = "Sat, 21 Nov 2026 15:01:30 GMT"
        private const val NEW_DATE = "Sat, 21 Nov 2026 15:02:00 GMT"
    }
}
