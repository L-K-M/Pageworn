package ch.lkmc.pageworn.transfer

import android.app.job.JobScheduler
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.system.Os
import androidx.media3.common.MediaItem
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.account.AccountService
import ch.lkmc.pageworn.account.CheckedAddress
import ch.lkmc.pageworn.account.Outcome
import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.account.SignInResult
import ch.lkmc.pageworn.catalog.RefreshOutcome
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.FailureCategory
import ch.lkmc.pageworn.data.db.FileTransferState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferFileEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.server.ManifestSourceTrack
import ch.lkmc.pageworn.server.NoRoute
import ch.lkmc.pageworn.testing.FaultProxy
import ch.lkmc.pageworn.testing.FaultProxy.Companion.DOWNLOAD_PATH
import ch.lkmc.pageworn.testing.FaultProxy.Companion.fileDownload
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.state.refusalOf
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M3 exit check: real downloads from the local Audiobookshelf v2.38.0 reference instance,
 * through the transfer coordinator and its user-initiated job, as the app runs them. Two
 * `server-fixture/fault_proxy.py` instances front the one server as two origins, "Home"
 * (`http://10.0.2.2:13380/`) and "Remote" (`http://10.0.2.2:13381/`). Each records the requests
 * it forwards and injects the faults a phase asks for through its control port.
 *
 * The phases run in order as separate instrumentation runs, each continuing the state the one
 * before left, because the host changes the world between some of them: it turns on airplane
 * mode for phase 2, and between phases 5a and 5b it lets the platform stop the process, starts
 * the app, kills its process mid-file and starts it again. `server-fixture/m3-check.sh` runs
 * them and passes the disposable account's credentials as instrumentation arguments. Without
 * those arguments every phase skips itself.
 */
@RunWith(AndroidJUnit4::class)
class DownloadIntegrationTest {
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private val db get() = container.database
    private val home = FaultProxy(HOME_CONTROL)
    private val remote = FaultProxy(REMOTE_CONTROL)

    private fun arg(name: String): String = args.getString(name).also { assumeTrue("needs -e $name", it != null) }!!

    /** The profile phase 1 signed in; a later phase without it skips. */
    private suspend fun profileId(): String {
        arg("absListener")
        val profile = db.profiles().current()
        assumeTrue("needs phase 1", profile != null)
        return profile!!.profileId
    }

    private suspend fun AccountService.checked(url: String, label: String): CheckedAddress =
        when (val r = checkAddress(url, label, allowCleartext = true, trustedSha256 = null)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> throw AssertionError("address $url: ${r.problem}")
        }

    private suspend fun AccountService.pair(url: String, label: String, username: String, password: String): String {
        val pairing = signIn(checked(url, label), username, password)
        assertWithMessage("pairing $label").that(pairing).isInstanceOf(SignInResult.ConfirmPairing::class.java)
        return confirmPairing((pairing as SignInResult.ConfirmPairing).pairing)
    }

    private suspend fun addressId(label: String) = db.addresses().forProfile(profileId()).single { it.label == label }.addressId

    private suspend fun key(title: String): BookKey {
        val profile = profileId()
        val library = db.libraries().forProfile(profile).single { it.name == LIBRARY }
        val row = container.catalog.observeListing(profile, library.libraryId, "").first().rows.single { it.title == title }
        return BookKey(profile, row.itemId)
    }

    private suspend fun tracks(key: BookKey): List<ManifestSourceTrack> {
        val read = container.serverAccess.route { c -> c.session.authorized { token -> c.client.item(token, key.itemId) } }
        return (read as? Routed.Done)?.value?.tracks ?: throw AssertionError("manifest of ${key.itemId}: $read")
    }

    /** Runs [block] with the app on screen: the platform accepts a transfer job only then. */
    private fun <T> visible(block: suspend () -> T): T =
        ActivityScenario.launch(MainActivity::class.java).use { runBlocking { block() } }

    private suspend fun enqueued(key: BookKey): String {
        val start = container.transfers.download(key)
        assertWithMessage("Download of ${key.itemId}").that(start).isInstanceOf(DownloadStart.Enqueued::class.java)
        return (start as DownloadStart.Enqueued).jobId
    }

    private suspend fun await(timeoutMs: Long, what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out after $timeoutMs ms waiting for $what; jobs: ${db.transfers().allActive()}")
            }
            delay(POLL_MS)
        }
    }

    private suspend fun job(jobId: String): TransferEntity = checkNotNull(db.transfers().get(jobId))

    private suspend fun awaitJob(jobId: String, timeoutMs: Long, what: String, condition: (TransferEntity) -> Boolean): TransferEntity {
        await(timeoutMs, what) { condition(job(jobId)) }
        return job(jobId)
    }

    /** Waits until track [ordinal] has bytes on disk and is not complete: the copy is in that file. */
    private suspend fun awaitInFile(jobId: String, ordinal: Int, timeoutMs: Long = 60_000): TransferFileEntity {
        await(timeoutMs, "bytes of track $ordinal") {
            val row = db.transfers().files(jobId).firstOrNull { it.ordinal == ordinal }
            row != null && row.state != FileTransferState.COMPLETE && row.bytesJournal > 0
        }
        return db.transfers().files(jobId).single { it.ordinal == ordinal }
    }

    private suspend fun awaitReady(key: BookKey, timeoutMs: Long = READY_TIMEOUT_MS) {
        await(timeoutMs, "${key.itemId} ready") { db.editions().edition(key.profileId, key.itemId)?.state == EditionState.READY }
    }

    private suspend fun stagedLength(job: TransferEntity, ordinal: Int): Long {
        val row = db.transfers().files(job.jobId).single { it.ordinal == ordinal }
        return File(container.editionFiles.staging(job.stagingGenerationId), row.localFilename).length()
    }

    /**
     * One edition for the book, whose every track has the server file's exact bytes. It downloads
     * each file again to compare, so a phase checks the proxies' logs before calling it.
     */
    private suspend fun assertPublishedOnce(key: BookKey) {
        assertThat(db.editions().all().count { it.profileId == key.profileId && it.itemId == key.itemId }).isEqualTo(1)
        val edition = checkNotNull(db.editions().edition(key.profileId, key.itemId))
        val stored = db.editions().tracks(edition.generationId)
        val sources = tracks(key)
        assertThat(stored).hasSize(sources.size)
        stored.sortedBy { it.ordinal }.zip(sources).forEach { (track, source) ->
            val file = File(container.editionFiles.published(edition.generationId), track.localFilename)
            assertWithMessage("size of ${track.localFilename}").that(file.length()).isEqualTo(source.sizeBytes)
            assertWithMessage("bytes of ${track.localFilename}").that(digest(file.inputStream())).isEqualTo(serverDigest(key, source.fileId))
        }
    }

    private suspend fun serverDigest(key: BookKey, fileId: String): String {
        val read = container.serverAccess.route { c -> c.session.authorized { token -> c.client.download(token, key.itemId, fileId, null) } }
        val response = (read as? Routed.Done)?.value ?: throw AssertionError("download of $fileId: $read")
        return withContext(Dispatchers.IO) { response.use { digest(it.body.byteStream()) } }
    }

    private fun digest(input: InputStream): String = input.use { stream ->
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            sha.update(buffer, 0, count)
        }
        sha.digest().joinToString("") { "%02x".format(it) }
    }

    /** A measured value for the milestone report; m3-check.sh prints it. */
    private fun observe(text: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", text) })
    }

    private fun itemPath(key: BookKey) = "/api/items/${key.itemId}/"

    private fun downloads(): List<FaultProxy.Seen> = home.downloads() + remote.downloads()

    private fun noFaults() {
        home.rules()
        remote.rules()
    }

    /**
     * Grows [filler] with allocated blocks until about [leaveBytes] stay free on the app's volume,
     * and returns how long the allocation took.
     */
    /** Grows [filler] until [leaveBytes] remain usable; returns the bytes it added. */
    private fun fill(filler: File, leaveBytes: Long): Long {
        val free = container.editionFiles.usableSpace()
        val more = free - leaveBytes
        check(more > 0) { "only $free bytes are free" }
        RandomAccessFile(filler, "rw").use { Os.posix_fallocate(it.fd, it.length(), more) }
        return more
    }

    /**
     * Keeps the volume full while [block] runs. A nearly full volume makes the platform purge cached
     * data (`DeviceStorageMonitorService` through installd), so one allocation does not keep it
     * full: on the API 34 emulator the purge freed about 600 kB and the copy finished its last
     * 2.2 MB. Returns what the top-up added back and the block's result.
     */
    private suspend fun <T> keptFull(filler: File, block: suspend () -> T): Pair<Long, T> = coroutineScope {
        var refilled = 0L
        val topUp = launch(Dispatchers.IO) {
            while (isActive) {
                if (container.editionFiles.usableSpace() > FULL_DISK_LEAVE_BYTES + REFILL_THRESHOLD_BYTES) {
                    refilled += fill(filler, leaveBytes = FULL_DISK_LEAVE_BYTES)
                }
                delay(REFILL_INTERVAL_MS)
            }
        }
        try {
            val result = block()
            refilled to result
        } finally {
            topUp.cancelAndJoin()
        }
    }

    // --- Phase 1 --------------------------------------------------------------------------------

    @Test
    fun phase1DownloadPublishesOnceThroughDroppedConnectionsAndIgnoredRanges() = runBlocking<Unit> {
        val listener = arg("absListener")
        val password = arg("absListenerPassword")
        TestApp.reset()
        home.reset()
        remote.reset()
        val service = container.accounts
        assertThat(service.signIn(service.checked(HOME, "Home"), listener, password)).isInstanceOf(SignInResult.Connected::class.java)
        service.pair(REMOTE, "Remote", listener, password)
        assertThat(service.refreshLibraries()).isTrue()
        val library = db.libraries().forProfile(profileId()).single { it.name == LIBRARY }
        assertThat(service.refreshCatalog(library.libraryId)).isInstanceOf(RefreshOutcome.Published::class.java)

        // Track A: a dropped connection, a 503 on the resume, then a server that ignores Range.
        // Track B: a dropped connection, then a resume.
        val key = key(BOUNDARY)
        val (a, b) = tracks(key)
        home.rules(
            FaultProxy.drop(fileDownload(a.fileId), bytes = a.sizeBytes!! / 3),
            FaultProxy.status(fileDownload(a.fileId), 503),
            FaultProxy.ignoreRange(fileDownload(a.fileId)),
            FaultProxy.drop(fileDownload(b.fileId), bytes = b.sizeBytes!! / 2),
        )
        val jobId = visible {
            val id = enqueued(key)
            // A second tap attaches to the same job (A04).
            assertThat(container.transfers.download(key)).isEqualTo(DownloadStart.Attached(id))
            // A04c: a user-initiated job that takes any network, metered or not.
            val info = context.getSystemService(JobScheduler::class.java).getPendingJob(TransferJobService.JOB_ID)
            assertWithMessage("scheduled transfer job").that(info).isNotNull()
            assertThat(info!!.isUserInitiated).isTrue()
            assertThat(info.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isTrue()
            assertThat(info.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isFalse()
            awaitReady(key)
            id
        }
        assertThat(job(jobId).state).isEqualTo(TransferState.COMPLETE)

        val forA = home.downloads().filter { it.path.endsWith(fileDownload(a.fileId)) }
        assertThat(forA.map { it.rule }).containsExactly("drop", "status", "ignore_range").inOrder()
        assertThat(forA[0].range).isNull()
        // The resume names the bytes on disk and a strong validator: the stored date, never the weak tag.
        assertThat(forA[1].range).matches("bytes=[1-9][0-9]*-")
        assertThat(forA[1].ifRange).isNotNull()
        assertThat(forA[1].ifRange).doesNotContain("W/")
        // Asked for the same range again after the 503; the server ignored it and sent all of A.
        assertThat(forA[2].range).isEqualTo(forA[1].range)
        assertThat(forA[2].status).isEqualTo(200)
        assertThat(forA[2].bodyBytes).isEqualTo(a.sizeBytes)
        val forB = home.downloads().filter { it.path.endsWith(fileDownload(b.fileId)) }
        assertThat(forB.map { it.rule }).containsExactly("drop", null).inOrder()
        assertThat(forB[1].range).matches("bytes=[1-9][0-9]*-")
        assertThat(forB[1].status).isEqualTo(206)
        assertThat(forB[1].bodyBytes + forB[1].range!!.removePrefix("bytes=").removeSuffix("-").toLong()).isEqualTo(b.sizeBytes)
        assertThat(remote.downloads()).isEmpty()
        assertPublishedOnce(key)
    }

    // --- Phase 2: the host turned on airplane mode ------------------------------------------

    @Test
    fun phase2PlaysAcrossTheFileBoundaryWithoutAnyNetwork() = runBlocking<Unit> {
        profileId()
        assumeTrue("needs -e offline true", args.getString("offline") == "true")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertWithMessage("airplane mode leaves no network").that(connectivity.activeNetwork).isNull()
        val key = key(BOUNDARY)
        onMain {
            val controller = connectController(context)
            try {
                controller.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build(), 85_000)
                controller.prepare()
                controller.play()
                controller.awaitUntil(20_000, "playback past the end of track A at 90 s") { isPlaying && currentPosition > 92_000 }
            } finally {
                controller.stop()
                controller.release()
            }
        }
    }

    // --- Phase 3 --------------------------------------------------------------------------------

    @Test
    fun phase3PauseResumeAndCancelAndRemoveHaveDistinctOutcomes() = runBlocking<Unit> {
        profileId()
        home.reset()
        remote.reset()
        home.rules(FaultProxy.throttle(DOWNLOAD_PATH, SLOW_BYTES_PER_SECOND))
        val notes = key(FIELD_NOTES)
        val small = key(SMALL_HOURS)
        val boundary = key(BOUNDARY)
        visible {
            // Pause keeps the partial file and sends nothing more; Resume continues from its bytes.
            val id = enqueued(notes)
            val partial = awaitInFile(id, 0)
            container.transfers.pause(id)
            val paused = job(id)
            assertThat(paused.state).isEqualTo(TransferState.PAUSED)
            assertThat(paused.userPaused).isTrue()
            val kept = stagedLength(paused, 0)
            assertThat(kept).isGreaterThan(0L)
            val requestsAtPause = home.downloads().size
            delay(PAUSE_HOLD_MS)
            assertThat(home.downloads()).hasSize(requestsAtPause)
            assertThat(job(id).state).isEqualTo(TransferState.PAUSED)
            container.transfers.resume(id)
            awaitReady(notes)
            val resumed = home.downloads().drop(requestsAtPause).first { it.path.endsWith(fileDownload(partial.sourceFileId)) }
            assertThat(resumed.range).isEqualTo("bytes=$kept-")
            assertThat(resumed.status).isEqualTo(206)
            assertPublishedOnce(notes)

            // Cancel ends the transfer, frees its staged bytes and publishes nothing.
            val cancelId = enqueued(small)
            awaitInFile(cancelId, 0)
            val running = job(cancelId)
            container.transfers.cancel(cancelId)
            assertThat(job(cancelId).state).isEqualTo(TransferState.CANCELLED)
            await(10_000, "cancelled staging reclaimed") { !container.editionFiles.staging(running.stagingGenerationId).exists() }
            assertThat(db.editions().edition(small.profileId, small.itemId)).isNull()
            assertThat(db.transfers().active(small.profileId, small.itemId)).isNull()

            // Remove deletes a ready book's audio and keeps its saved place, which a new download resumes (A07).
            container.progressStore.saveOutsideSession(boundary, 115_000, 210_000, null, StopKind.ORDINARY)
            val edition = checkNotNull(db.editions().edition(boundary.profileId, boundary.itemId))
            container.editionStore.remove(boundary)
            assertThat(db.editions().edition(boundary.profileId, boundary.itemId)).isNull()
            assertThat(container.editionFiles.published(edition.generationId).exists()).isFalse()
            assertThat(container.progressStore.read(boundary)!!.positionMs).isEqualTo(115_000)
            noFaults()
            enqueued(boundary)
            awaitReady(boundary)
            assertThat(container.progressStore.read(boundary)!!.positionMs).isEqualTo(115_000)
            assertPublishedOnce(boundary)
        }
    }

    // --- Phase 4 --------------------------------------------------------------------------------

    @Test
    fun phase4RemovedServingAddressAndOriginChange() = runBlocking<Unit> {
        val listener = arg("absListener")
        val password = arg("absListenerPassword")
        profileId()
        home.reset()
        remote.reset()
        val service = container.accounts

        // A04b: remove the address serving the transfer during track B. The job waits, resumes
        // through Remote, reuses the verified track A and restarts the partial B from byte zero.
        val boundary = key(BOUNDARY)
        container.editionStore.remove(boundary)
        val (a, b) = tracks(boundary)
        home.rules(FaultProxy.throttle(DOWNLOAD_PATH, SLOW_BYTES_PER_SECOND))
        visible {
            val id = enqueued(boundary)
            awaitInFile(id, 1)
            assertThat(job(id).servingAddressId).isEqualTo(addressId("Home"))
            service.remove(addressId("Home"))
            awaitReady(boundary)
        }
        assertThat(home.downloads().count { it.path.endsWith(fileDownload(a.fileId)) }).isEqualTo(1)
        assertThat(remote.downloads().none { it.path.endsWith(fileDownload(a.fileId)) }).isTrue()
        val bOnRemote = remote.downloads().filter { it.path.endsWith(fileDownload(b.fileId)) }
        assertThat(bOnRemote).isNotEmpty()
        assertThat(bOnRemote.first().range).isNull()
        assertPublishedOnce(boundary)

        // A04: the serving address stops answering during the second file. Pair Home again, so it
        // follows Remote in priority; the transfer moves to Home after Remote drops it.
        service.pair(HOME, "Home", listener, password)
        home.reset()
        remote.reset()
        val notes = key(FIELD_NOTES)
        container.editionStore.remove(notes)
        val noteTracks = tracks(notes)
        assertWithMessage("a multi-file book").that(noteTracks.size).isAtLeast(2)
        val second = noteTracks[1]
        remote.rules(
            FaultProxy.drop(fileDownload(second.fileId), bytes = second.sizeBytes!! / 2),
            FaultProxy.throttle(DOWNLOAD_PATH, SLOW_BYTES_PER_SECOND),
        )
        visible {
            val id = enqueued(notes)
            assertThat(job(id).servingAddressId ?: addressId("Remote")).isEqualTo(addressId("Remote"))
            awaitJob(id, 60_000, "the dropped second file") { it.failureCategory == FailureCategory.NETWORK }
            remote.rules(FaultProxy.refuse(".*"))
            awaitReady(notes)
            assertThat(job(id).servingAddressId).isEqualTo(addressId("Home"))
        }
        assertThat(home.downloads().none { it.path.endsWith(fileDownload(noteTracks[0].fileId)) }).isTrue()
        assertThat(home.downloads().first { it.path.endsWith(fileDownload(second.fileId)) }.range).isNull()
        assertPublishedOnce(notes)
    }

    // --- Phase 5: process death -------------------------------------------------------------------

    /** Starts a slow transfer and ends mid-file; the instrumentation's end stops the process. */
    @Test
    fun phase5aLeaveATransferMidFile() = runBlocking<Unit> {
        profileId()
        home.reset()
        remote.reset()
        home.rules(FaultProxy.throttle(DOWNLOAD_PATH, CRAWL_BYTES_PER_SECOND))
        remote.rules(FaultProxy.throttle(DOWNLOAD_PATH, CRAWL_BYTES_PER_SECOND))
        val orchard = key(ORCHARD)
        container.editionStore.remove(orchard)
        visible {
            val id = enqueued(orchard)
            awaitInFile(id, 0)
        }
    }

    /**
     * After the host started the app, killed its process during the resumed copy and started it
     * again: the transfer completes once, and every request after the first continued the bytes on disk.
     */
    @Test
    fun phase5bRecoversAfterProcessDeath() = runBlocking<Unit> {
        profileId()
        assumeTrue("needs -e killed true", args.getString("killed") == "true")
        noFaults()
        val orchard = key(ORCHARD)
        visible { awaitReady(orchard) }
        val fileId = tracks(orchard).single().fileId
        val requests = downloads().filter { it.path.endsWith(fileDownload(fileId)) && it.status != null }
        observe("process death: ${requests.size} requests, ranges ${requests.map { it.range }}")
        // The first copy, the resume after the platform stopped the process, the resume after the kill.
        assertThat(requests.size).isAtLeast(3)
        assertThat(requests.first().range).isNull()
        assertThat(requests.drop(1).map { it.status }.toSet()).containsExactly(206)
        assertPublishedOnce(orchard)
    }

    // --- Phase 6 --------------------------------------------------------------------------------

    @Test
    fun phase6StorageMarginAndAFullDisk() = runBlocking<Unit> {
        profileId()
        home.reset()
        remote.reset()
        val filler = File(context.filesDir, "filler.bin")
        try {
            // A21b: 500 MB above the book's size is below the 1 GB margin: no audio is requested.
            val small = key(SMALL_HOURS)
            // Run on its own after a full run, the phase starts from that run's downloads.
            container.editionStore.remove(small)
            val size = tracks(small).sumOf { it.sizeBytes!! }
            fill(filler, leaveBytes = size + 500_000_000L)
            visible {
                val id = enqueued(small)
                val rested = awaitJob(id, 30_000, "storage-full rest") { it.state == TransferState.FAILED }
                assertThat(rested.failureCategory).isEqualTo(FailureCategory.STORAGE_FULL)
                assertThat(rested.shortfallBytes).isIn(Range.closed(400_000_000L, 600_000_000L))
                assertThat(downloads()).isEmpty()
                filler.delete()
                container.transfers.resume(id)
                awaitReady(small)
            }
            assertPublishedOnce(small)

            // A21: the disk fills during the copy. The job rests with the space it needs and keeps
            // its bytes; Retry continues them.
            // Most of the space goes before the download, leaving the margin it needs to start, so
            // the last allocation is small enough to land while the copy runs.
            val orchard = key(ORCHARD)
            container.editionStore.remove(orchard)
            home.reset()
            remote.reset()
            home.rules(FaultProxy.throttle(DOWNLOAD_PATH, CRAWL_BYTES_PER_SECOND))
            remote.rules(FaultProxy.throttle(DOWNLOAD_PATH, CRAWL_BYTES_PER_SECOND))
            val orchardSize = tracks(orchard).single().sizeBytes!!
            fill(filler, leaveBytes = orchardSize + SpacePolicy.MARGIN_BYTES + MARGIN_SLACK_BYTES)
            visible {
                val id = enqueued(orchard)
                awaitInFile(id, 0)
                val filled = fill(filler, leaveBytes = FULL_DISK_LEAVE_BYTES)
                val (freed, rested) = keptFull(filler) {
                    awaitJob(id, 60_000, "storage-full rest") { it.state == TransferState.FAILED }
                }
                observe("full disk: ${filled / 1_000_000} MB allocated mid-copy, ${freed / 1_000} kB refilled after the platform freed space")
                observe("full disk: ${rested.failureCategory}, shortfall ${rested.shortfallBytes}")
                assertThat(rested.failureCategory).isEqualTo(FailureCategory.STORAGE_FULL)
                assertThat(rested.shortfallBytes).isNotNull()
                val kept = stagedLength(rested, 0)
                assertThat(kept).isGreaterThan(0L)
                filler.delete()
                noFaults()
                val before = downloads().size
                container.transfers.resume(id)
                awaitReady(orchard)
                assertThat(downloads().drop(before).first().range).isEqualTo("bytes=$kept-")
            }
            assertPublishedOnce(orchard)
        } finally {
            filler.delete()
        }
    }

    // --- Phase 7 --------------------------------------------------------------------------------

    @Test
    fun phase7UnsupportedFormatAndExcludedFile() = runBlocking<Unit> {
        profileId()
        home.reset()
        remote.reset()
        // A24b: a book in a format the matrix refuses starts no job and requests no audio.
        val wma = key(WMA)
        assertThat(container.transfers.download(wma)).isEqualTo(DownloadStart.Refused(Refusal.UnsupportedFormat))
        assertThat(db.transfers().active(wma.profileId, wma.itemId)).isNull()
        assertThat(downloads().filter { it.path.contains(itemPath(wma)) }).isEmpty()

        // A25: the excluded file never downloads; the edition holds the remaining tracks.
        val excluded = key(EXCLUDE_TRACK)
        val listed = tracks(excluded).map { it.fileId }
        assertThat(listed).hasSize(2)
        visible {
            enqueued(excluded)
            awaitReady(excluded)
        }
        val requested = downloads().filter { it.path.contains(itemPath(excluded)) }
        assertThat(requested.map { it.path.substringAfter("/file/").substringBefore("/") }.toSet()).containsExactlyElementsIn(listed)
        assertPublishedOnce(excluded)
    }

    // --- Phase 8 --------------------------------------------------------------------------------

    /** A30, the transfer: sign-out stops it with its files kept, and signing in again continues it. */
    @Test
    fun phase8SignOutDuringATransferThenSignInContinuesIt() = runBlocking<Unit> {
        val listener = arg("absListener")
        val password = arg("absListenerPassword")
        profileId()
        home.reset()
        remote.reset()
        home.rules(FaultProxy.throttle(DOWNLOAD_PATH, SLOW_BYTES_PER_SECOND))
        remote.rules(FaultProxy.throttle(DOWNLOAD_PATH, SLOW_BYTES_PER_SECOND))
        val service = container.accounts
        val small = key(SMALL_HOURS)
        container.editionStore.remove(small)
        visible {
            val id = enqueued(small)
            awaitInFile(id, 0)
            val serving = checkNotNull(db.addresses().get(checkNotNull(job(id).servingAddressId)))
            service.signOut()
            val waiting = awaitJob(id, 30_000, "the transfer waiting after sign-out") { it.state == TransferState.WAITING_FOR_CONNECTION }
            val kept = stagedLength(waiting, 0)
            assertThat(kept).isGreaterThan(0L)
            // Each address received one sign-out for its own session.
            assertThat(home.log().count { it.method == "POST" && it.path.startsWith("/logout") }).isEqualTo(1)
            assertThat(remote.log().count { it.method == "POST" && it.path.startsWith("/logout") }).isEqualTo(1)
            noFaults()
            val before = downloads().size
            assertThat(service.signIn(service.checked(serving.baseUrl, serving.label), listener, password)).isInstanceOf(SignInResult.Connected::class.java)
            awaitReady(small)
            assertThat(downloads().drop(before).first().range).isEqualTo("bytes=$kept-")
        }
        assertPublishedOnce(small)
        // The other address needs its own sign-in again.
        val other = db.addresses().forProfile(profileId()).single { it.sessionState != SessionState.SIGNED_IN }
        assertThat(service.signIn(service.checked(other.baseUrl, other.label), listener, password)).isInstanceOf(SignInResult.Connected::class.java)
    }

    // --- Phase 9: Android 17, the host revoked the local-network permission -----------------------

    /**
     * A26b, the Download tap: with the permission revoked, every address here is local, so the tap
     * starts no transfer and names the permission, never the unreachable state. The host checks
     * that the proxies received nothing; the device cannot reach their control ports now.
     */
    @Test
    fun phase9LocalNetworkRevokedDownloadTapStartsNothing() = runBlocking<Unit> {
        profileId()
        val local = container.localNetwork
        assumeTrue("Android 17 only", local.applies)
        assertWithMessage("the host revokes the permission before this phase").that(local.granted()).isFalse()
        val small = key(SMALL_HOURS)
        container.editionStore.remove(small)
        val start = container.transfers.download(small)
        assertThat(start).isInstanceOf(DownloadStart.Refused::class.java)
        val reason = ((start as DownloadStart.Refused).reason as Refusal.NoRoute).reason
        assertThat(reason).isInstanceOf(NoRoute.LocalNetworkDenied::class.java)
        assertThat(refusalOf(small, start)!!.text).isEqualTo(Copy.localNetworkDenied((reason as NoRoute.LocalNetworkDenied).label))
        assertThat(db.transfers().active(small.profileId, small.itemId)).isNull()
        // Downloaded keeps working without the permission.
        assertThat(container.editionStore.plan(key(BOUNDARY))).isNotNull()
    }

    // --- Phase 10: the host turned Wi-Fi off -------------------------------------------------

    /** A04c: on a metered mobile network alone, the transfer starts at once and the book becomes ready. */
    @Test
    fun phase10MeteredMobileDataAloneDownloadsAtOnce() = runBlocking<Unit> {
        profileId()
        assumeTrue("needs -e metered true", args.getString("metered") == "true")
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        assertWithMessage("an active network").that(network).isNotNull()
        assertThat(network!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).isFalse()
        assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isFalse()
        observe("metered network: cellular=${network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}")
        home.reset()
        remote.reset()
        val orchard = key(ORCHARD)
        container.editionStore.remove(orchard)
        visible {
            val started = System.currentTimeMillis()
            val id = enqueued(orchard)
            awaitJob(id, 10_000, "the transfer starting") { it.state == TransferState.TRANSFERRING || it.state == TransferState.COMPLETE }
            observe("transfer started ${System.currentTimeMillis() - started} ms after the tap")
            awaitReady(orchard)
        }
        assertPublishedOnce(orchard)
    }

    private companion object {
        const val HOME = "http://${FaultProxy.HOST}:13380/"
        const val REMOTE = "http://${FaultProxy.HOST}:13381/"
        const val HOME_CONTROL = 13390
        const val REMOTE_CONTROL = 13391
        const val LIBRARY = "Audiobooks"
        const val BOUNDARY = "Boundary Book"
        const val FIELD_NOTES = "Field Notes on Rain"
        const val SMALL_HOURS = "Small Hours"
        const val ORCHARD = "The Night Orchard"
        const val WMA = "WMA Book"
        const val EXCLUDE_TRACK = "Exclude Track Book"
        const val POLL_MS = 100L
        const val READY_TIMEOUT_MS = 240_000L
        const val PAUSE_HOLD_MS = 3_000L
        const val SLOW_BYTES_PER_SECOND = 100_000L
        const val CRAWL_BYTES_PER_SECOND = 40_000L
        const val FULL_DISK_LEAVE_BYTES = 1_000_000L
        const val REFILL_THRESHOLD_BYTES = 64_000L
        const val REFILL_INTERVAL_MS = 50L
        const val MARGIN_SLACK_BYTES = 50_000_000L
    }
}
