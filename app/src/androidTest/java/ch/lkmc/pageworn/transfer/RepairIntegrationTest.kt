package ch.lkmc.pageworn.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.account.CheckedAddress
import ch.lkmc.pageworn.account.Outcome
import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.account.SignInResult
import ch.lkmc.pageworn.catalog.RefreshOutcome
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.ResumeRewind
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.server.ManifestSourceTrack
import ch.lkmc.pageworn.testing.FaultProxy
import ch.lkmc.pageworn.testing.FaultProxy.Companion.fileDownload
import ch.lkmc.pageworn.testing.RestartMarker
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.state.DownloadRefusal
import ch.lkmc.pageworn.ui.state.TransferActions
import ch.lkmc.pageworn.ui.state.refusalOf
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Repair through the local Audiobookshelf v2.38.0 reference instance (A10c, A20), behind the
 * "Home" fault proxy of `server-fixture/fault_proxy.py`, which records every request it forwards.
 * `server-fixture/m4-check.sh` runs the phases in name order, each in its own instrumentation run,
 * and passes the disposable accounts as arguments; without them every phase skips itself. The
 * last phase must run in a new process: it is the relaunch of A10c.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RepairIntegrationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private val db get() = container.database
    private val home = FaultProxy(HOME_CONTROL)

    private fun arg(name: String): String = args.getString(name).also { assumeTrue("needs -e $name", it != null) }!!

    /** Signs in at Home from a clean app and reads the catalog. */
    private suspend fun signIn(username: String, password: String) {
        TestApp.reset()
        home.reset()
        val service = container.accounts
        val address = when (val r = service.checkAddress(HOME, "Home", allowCleartext = true, trustedSha256 = null)) {
            is Outcome.Ok<CheckedAddress> -> r.value
            is Outcome.Err -> throw AssertionError("address $HOME: ${r.problem}")
        }
        assertThat(service.signIn(address, username, password)).isInstanceOf(SignInResult.Connected::class.java)
        assertThat(service.refreshLibraries()).isTrue()
        val library = db.libraries().forProfile(profileId()).single { it.name == LIBRARY }
        assertThat(service.refreshCatalog(library.libraryId)).isInstanceOf(RefreshOutcome.Published::class.java)
    }

    /** The listener's profile, which phase 2 signed in; a later phase without it skips. */
    private suspend fun profileId(): String {
        val profile = db.profiles().current()
        assumeTrue("needs the profile of an earlier phase", profile != null)
        return profile!!.profileId
    }

    private suspend fun boundary(): BookKey = book(BOUNDARY)

    private suspend fun book(title: String): BookKey {
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

    private suspend fun await(timeoutMs: Long, what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out after $timeoutMs ms waiting for $what; jobs: ${db.transfers().allActive()}")
            delay(POLL_MS)
        }
    }

    private suspend fun edition(key: BookKey) = checkNotNull(db.editions().edition(key.profileId, key.itemId))

    private suspend fun stored(key: BookKey): BookProgressEntity = checkNotNull(container.progressStore.read(key))

    private suspend fun publishedTrack(key: BookKey, ordinal: Int): File {
        val e = edition(key)
        return File(container.editionFiles.published(e.generationId), db.editions().tracks(e.generationId).single { it.ordinal == ordinal }.localFilename)
    }

    /** Enqueues the download, or the repair of an edition that needs it, and waits until the book is ready. */
    private suspend fun downloadToReady(key: BookKey): String {
        val start = container.transfers.download(key)
        assertWithMessage("Download of ${key.itemId}").that(start).isInstanceOf(DownloadStart.Enqueued::class.java)
        val jobId = (start as DownloadStart.Enqueued).jobId
        await(READY_TIMEOUT_MS, "${key.itemId} ready") { db.editions().edition(key.profileId, key.itemId)?.state == EditionState.READY }
        return jobId
    }

    private fun item(key: BookKey) = MediaItem.Builder().setMediaId(key.mediaId).build()

    /**
     * Plays [key] from its saved place and returns where audio started, as the service's player
     * reports it, after [playMs] of playback. The controller stays loaded with the book.
     */
    private suspend fun MediaController.playFromTheSavedPlace(key: BookKey, playMs: Long): Long {
        setMediaItem(item(key))
        prepare()
        play()
        val started = withTimeout(15_000) { container.playbackHub.state.first { it.isPlaying }.positionMs }
        delay(playMs)
        return started
    }

    private fun observe(text: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", text) })

    private fun awaitText(text: String) = composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** Opens the player for the Boundary Book from the mini-player and checks its failure state. */
    private fun assertPlayerDisabledWith(message: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasContentDescription("Open player, $BOUNDARY")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Open player, $BOUNDARY").performClick()
        awaitText(message)
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithText(Copy.REPAIR_DOWNLOAD).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Resume $BOUNDARY", substring = true) or hasContentDescription("Play $BOUNDARY", substring = true)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Back 15 seconds", substring = true).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Forward 30 seconds", substring = true).assertIsNotEnabled()
    }

    // --- A20: an account that can browse but cannot download ----------------------------------------

    @Test
    fun repair1BrowseOnlyAccountStartsNoTransfer() = runBlocking<Unit> {
        signIn(arg("absBrowser"), arg("absBrowserPassword"))
        val key = boundary()
        val start = container.transfers.download(key)
        assertThat(start).isEqualTo(DownloadStart.Refused(Refusal.DownloadsDisabled))
        assertThat(refusalOf(key, start)).isEqualTo(DownloadRefusal(key, Copy.DOWNLOAD_DISABLED, onRow = true))
        assertThat(db.transfers().allActive()).isEmpty()
        assertThat(home.downloads()).isEmpty()
    }

    // --- A20: a short track, a failed repair and a successful one -----------------------------------

    @Test
    fun repair2AShortTrackIsADamagedCopyAndOnlyASuccessfulRepairReplacesIt() = runBlocking<Unit> {
        signIn(arg("absListener"), arg("absListenerPassword"))
        val key = boundary()
        visible { downloadToReady(key) }
        val damaged = edition(key).generationId

        // A saved place at 60 s, then the player lets go of the book.
        onMain {
            val c = connectController(context)
            c.setMediaItem(item(key), 60_000)
            c.prepare()
            c.play()
            c.awaitUntil(10_000, "playing") { isPlaying }
            c.pause()
            c.awaitUntil(description = "paused") { !isPlaying }
            delay(300)
            c.stop()
            c.clearMediaItems()
            c.release()
        }
        val place = stored(key)
        assertThat(place.positionMs).isIn(Range.closed(60_000L, 61_000L))

        // Track A loses half its bytes on the disk. Opening the book finds it before the player
        // reads anything: Repair needed, as a damaged copy, and nothing plays.
        val trackA = publishedTrack(key, 0)
        RandomAccessFile(trackA, "rw").use { it.setLength(trackA.length() / 2) }
        onMain {
            val c = connectController(context)
            c.setMediaItem(item(key))
            c.prepare()
            c.play()
            await(10_000, "Repair needed") { edition(key).state == EditionState.REPAIR_NEEDED }
            delay(1_000)
            assertThat(container.playbackHub.state.value.isPlaying).isFalse()
            c.release()
        }
        assertThat(stored(key)).isEqualTo(place)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText(Copy.DAMAGED_COPY)
            composeRule.onNodeWithText(Copy.DAMAGED_COPY).assertIsDisplayed()
        }

        // A repair that fails: the server answers 404 for track A until the job stops. The damaged
        // edition and the saved place stay as they were.
        val (a, _) = tracks(key)
        home.rules(FaultProxy.status(fileDownload(a.fileId), 404, count = 0))
        val jobId = visible {
            val start = container.transfers.download(key)
            assertThat(start).isInstanceOf(DownloadStart.Enqueued::class.java)
            val id = (start as DownloadStart.Enqueued).jobId
            await(60_000, "the repair to stop") { db.transfers().get(id)?.state == TransferState.FAILED }
            id
        }
        val failed = checkNotNull(db.transfers().get(jobId))
        observe("failed repair: ${failed.state} ${failed.failureCategory}, ${home.downloads().count { it.status == 404 }} answers of 404")
        assertThat(edition(key).generationId).isEqualTo(damaged)
        assertThat(edition(key).state).isEqualTo(EditionState.REPAIR_NEEDED)
        assertThat(stored(key)).isEqualTo(place)

        // Retry without the fault: a new generation replaces the damaged one, and playback starts
        // at the saved place.
        home.rules()
        visible {
            container.transfers.resume(jobId)
            await(READY_TIMEOUT_MS, "repaired") { db.editions().edition(key.profileId, key.itemId)?.state == EditionState.READY }
        }
        assertThat(edition(key).generationId).isNotEqualTo(damaged)
        val expected = ResumeRewind.startPosition(place.positionMs, place.stopKind, place.updatedAt, System.currentTimeMillis(), place.finished)
        val started = onMain {
            val c = connectController(context)
            val at = c.playFromTheSavedPlace(key, playMs = 1_000)
            c.pause()
            c.awaitUntil(description = "paused") { !isPlaying }
            c.stop()
            c.clearMediaItems()
            c.release()
            at
        }
        observe("after the repair playback started at $started ms; saved ${place.positionMs} ms, expected $expected ms")
        assertThat(started).isIn(Range.closed(expected - 300, expected + 1_000))
    }

    // --- A10c: a track that fails during playback, then a relaunch and a repair --------------------

    @Test
    fun repair3AZeroedTrackFailsWhenPlaybackReachesIt() {
        val key = runBlocking { boundary() }
        assumeTrue("needs the repaired book of phase 2", runBlocking { edition(key).state } == EditionState.READY)
        RestartMarker.clear()
        // Track B keeps its size, so the check before opening passes; none of its bytes can be read
        // as audio, and playback fails when it reaches the boundary into it.
        val trackB = runBlocking { publishedTrack(key, 1) }
        RandomAccessFile(trackB, "rw").use { file -> file.write(ByteArray(file.length().toInt())) }
        val requestsBefore = home.log().size
        val c = onMain {
            val c = connectController(context)
            c.setMediaItem(item(key), 85_000)
            c.prepare()
            c.play()
            withTimeout(30_000) { container.playbackHub.state.first { it.failed } }
            delay(1_000)
            assertThat(c.isPlaying).isFalse()
            assertThat(c.playWhenReady).isFalse()
            c
        }
        val place = runBlocking { stored(key) }
        observe("zeroed track B: saved ${place.positionMs} ms as ${place.stopKind}, ${home.log().size - requestsBefore} requests while playing")
        // The last checkpoint at or before the failure: the boundary itself, track B at zero.
        assertThat(place.positionMs).isEqualTo(B_START_MS)
        assertThat(place.finished).isFalse()
        assertThat(runBlocking { edition(key).state }).isEqualTo(EditionState.REPAIR_NEEDED)
        // Nothing reached the server for playback or audio.
        assertThat(home.log().size).isEqualTo(requestsBefore)

        ActivityScenario.launch(MainActivity::class.java).use { assertPlayerDisabledWith(Copy.PLAYBACK_STOPPED) }
        onMain { c.release() }
        RestartMarker.write("a10c", mapOf("position" to place.positionMs))
    }

    @Test
    fun repair4AfterARelaunchTheRepairRestoresPlaybackFromThePlace() {
        val marker = RestartMarker.readAfterRestart("a10c")
        val key = runBlocking { boundary() }
        val place = runBlocking { stored(key) }
        assertThat(place.positionMs).isEqualTo(marker.getValue("position"))
        assertThat(place.finished).isFalse()
        assertThat(runBlocking { edition(key).state }).isEqualTo(EditionState.REPAIR_NEEDED)
        ActivityScenario.launch(MainActivity::class.java).use { assertPlayerDisabledWith(Copy.DAMAGED_COPY) }

        visible { downloadToReady(key) }
        val expected = ResumeRewind.startPosition(place.positionMs, place.stopKind, place.updatedAt, System.currentTimeMillis(), place.finished)
        val (started, playedTo) = onMain {
            val c = connectController(context)
            val at = c.playFromTheSavedPlace(key, playMs = 3_000)
            val now = container.playbackHub.state.value
            assertThat(now.failed).isFalse()
            c.pause()
            c.awaitUntil(description = "paused") { !isPlaying }
            c.stop()
            c.clearMediaItems()
            c.release()
            at to now.positionMs
        }
        observe("after the relaunch and repair playback started at $started ms (expected $expected ms) and reached $playedTo ms")
        assertThat(started).isIn(Range.closed(expected - 300, expected + 1_000))
        assertThat(playedTo).isGreaterThan(B_START_MS + 1_500)
        assertThat(runBlocking { edition(key).state }).isEqualTo(EditionState.READY)
        RestartMarker.clear()
    }

    @Test
    fun a16bDownloadsAndPlaybackWorkWithNotificationsDenied() {
        val username = arg("absListener")
        val password = arg("absListenerPassword")
        assumeTrue("the host installs fresh and leaves notifications denied", !TransferNotifications.enabled(context))
        val manager = context.getSystemService(NotificationManager::class.java)
        val actions = TransferActions(container)
        val boundary = runBlocking {
            signIn(username, password)
            boundary()
        }
        home.rules(FaultProxy.throttle(FaultProxy.DOWNLOAD_PATH, 200_000))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            actions.download(boundary)
            runBlocking {
                await(15_000, "the notification permission notice") {
                    container.notices.downloadedStatus.value != null
                }
            }
            assertThat(runBlocking { db.editions().edition(boundary.profileId, boundary.itemId) }).isNull()
            scenario.moveToState(Lifecycle.State.CREATED)
            runBlocking {
                await(READY_TIMEOUT_MS, "the download finished while backgrounded") {
                    db.editions().edition(boundary.profileId, boundary.itemId)?.state == EditionState.READY
                }
            }
            assertThat(manager.activeNotifications.map { it.id }).doesNotContain(TransferNotifications.COMPLETION_ID)
        }
        home.rules()

        val openDownloaded = ActivityScenario.launch(MainActivity::class.java).use {
            awaitText(Copy.NOTIFICATIONS_DISABLED)
            composeRule.onNodeWithText(Copy.ENABLE_NOTIFICATIONS).assertIsDisplayed()
            composeRule.onNode(tab("Library")).performClick()
            composeRule.onNode(tab("Downloaded")).performClick()
            composeRule.onNodeWithText(Copy.NOTIFICATIONS_DISABLED).assertDoesNotExist()

            composeRule.onNodeWithContentDescription("Play $BOUNDARY").performClick()
            runBlocking { withTimeout(10_000) { container.playbackHub.state.first { it.isPlaying } } }
            onMain {
                val c = connectController(context)
                try {
                    c.pause()
                    c.awaitUntil(description = "paused") { !playWhenReady }
                } finally {
                    c.release()
                }
            }
            val small = runBlocking { book("Small Hours") }
            actions.download(small)
            runBlocking {
                await(READY_TIMEOUT_MS, "the second book is ready") {
                    db.editions().edition(small.profileId, small.itemId)?.state == EditionState.READY
                }
            }
            composeRule.onNodeWithText(Copy.NOTIFICATIONS_DISABLED).assertDoesNotExist()

            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            assertThat(TransferNotifications.enabled(context)).isTrue()
            val orchard = runBlocking { book("The Night Orchard") }
            actions.download(orchard)
            runBlocking {
                await(READY_TIMEOUT_MS, "the permitted completion notification") {
                    manager.activeNotifications.any {
                        it.id == TransferNotifications.COMPLETION_ID &&
                            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "The Night Orchard"
                    }
                }
                assertThat(edition(orchard).state).isEqualTo(EditionState.READY)
            }
            composeRule.onNode(tab("Library")).performClick()
            manager.activeNotifications.single { it.id == TransferNotifications.COMPLETION_ID }
                .notification.contentIntent
        }
        try {
            openDownloaded.send()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(tab("Downloaded") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(tab("Downloaded")).assertIsSelected()
        } finally {
            manager.cancel(TransferNotifications.COMPLETION_ID)
            onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
        }
    }

    private companion object {
        const val HOME = "http://${FaultProxy.HOST}:13380/"
        const val HOME_CONTROL = 13390
        const val LIBRARY = "Audiobooks"
        const val BOUNDARY = "Boundary Book"
        const val B_START_MS = 90_000L
        const val POLL_MS = 100L
        const val READY_TIMEOUT_MS = 240_000L
    }
}
