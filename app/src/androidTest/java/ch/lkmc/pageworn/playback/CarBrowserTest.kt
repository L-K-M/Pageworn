package ch.lkmc.pageworn.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.testing.serviceToken
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A16f, the car's list, through a Media3 [MediaBrowser] connected to the playback service: three
 * ready books, one transfer in progress and one edition that needs repair. The car host is a
 * browser like this one; how a head unit shows the list is an owner check.
 */
@RunWith(AndroidJUnit4::class)
class CarBrowserTest {
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private lateinit var clock: FakeClock
    private lateinit var first: BookKey
    private lateinit var second: BookKey
    private lateinit var finished: BookKey
    private lateinit var damaged: BookKey

    @Before
    fun setUp() {
        clock = FakeClock()
        TestApp.reset(clock)
        // Downloaded's order: last listened first. The second book rests at 115 s; the third is finished.
        first = TestApp.publishBoundaryBook(itemId = "car-first", title = "Alpha")
        second = TestApp.publishSingleM4b(itemId = "car-second")
        finished = TestApp.publishBoundaryBook(itemId = "car-finished", title = "Gamma", chapters = emptyList(), fixtureId = "no-chapters-multi")
        damaged = TestApp.publishBoundaryBook(itemId = "car-damaged", title = "Delta")
        runBlocking {
            val db = container.database
            container.editionStore.markRepairNeeded(damaged, checkNotNull(db.editions().edition(damaged.profileId, damaged.itemId)).generationId)
            db.transfers().upsert(
                TransferEntity("job-car", TestApp.PROFILE, "car-transfer", "gen-car", null, TransferState.TRANSFERRING, activeMarker = 1,
                    createdAt = clock.wall, updatedAt = clock.wall, title = "Epsilon", authors = null),
            )
            place(first, 60_000, listenedAgoMs = 60_000)
            place(second, 115_000, listenedAgoMs = 120_000)
            place(finished, 210_000, listenedAgoMs = 180_000, isFinished = true)
            place(damaged, 30_000, listenedAgoMs = 10_000)
        }
    }

    @After
    fun tearDown() = TestApp.reset()

    private suspend fun place(key: BookKey, positionMs: Long, listenedAgoMs: Long, isFinished: Boolean = false) {
        val duration = checkNotNull(container.editionStore.anyPlan(key)).timeline.durationMs
        container.database.progress().upsert(
            BookProgressEntity(key.profileId, key.itemId, positionMs, duration, null, isFinished, clock.wall - listenedAgoMs,
                if (isFinished) StopKind.EXACT else StopKind.ORDINARY, clock.wall - listenedAgoMs, playbackGeneration = 1, writeSequence = 1),
        )
    }

    private suspend fun MediaController.chapter(action: String): Long {
        val result = sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY).await()
        assertWithMessage(action).that(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        delay(300)
        return currentPosition
    }

    private suspend fun MediaController.playAndPause(item: MediaItem): Long {
        setMediaItem(item)
        prepare()
        play()
        awaitUntil(10_000, "playing ${item.mediaId}") { isPlaying }
        pause()
        awaitUntil(description = "paused") { !isPlaying }
        delay(300)
        return currentPosition
    }

    @Test
    fun a16fTheCarListsTheReadyBooksInDownloadedOrderAndPlaysThem() = onMain {
        val errors = CopyOnWriteArrayList<SessionError>()
        val browser = MediaBrowser.Builder(context, serviceToken(context))
            .setListener(object : MediaBrowser.Listener {
                override fun onError(controller: MediaController, sessionError: SessionError) {
                    errors += sessionError
                }
            })
            .buildAsync().await()

        val root = browser.getLibraryRoot(null).await()
        assertThat(root.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(root.params!!.extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)).isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        val children = browser.getChildren(root.value!!.mediaId, 0, 50, null).await().value!!

        // Exactly the ready books, in Downloaded's order; no transfer and no edition that needs repair.
        assertThat(children.map { it.mediaId }).containsExactly(first.mediaId, second.mediaId, finished.mediaId).inOrder()
        children.forEach { child ->
            val m = child.mediaMetadata
            assertThat(m.isPlayable).isTrue()
            assertThat(m.isBrowsable).isFalse()
            assertThat(m.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            assertThat(m.title.toString()).isNotEmpty()
            assertThat(m.artist.toString()).isNotEmpty()
            assertThat(m.artworkUri!!.scheme).isEqualTo("content")
            val cover = context.contentResolver.openInputStream(m.artworkUri!!)!!.use { it.readBytes() }
            assertWithMessage("cover of ${child.mediaId}").that(cover.size).isGreaterThan(0)
        }

        // A tap on the second book starts it at its saved place, as Resume does on the phone.
        val start = browser.playAndPause(children[1])
        assertThat(start).isIn(Range.closed(115_000L, 116_500L))
        // Next and Previous, as from the steering wheel: forward 30 s and back 15 s.
        browser.seekToNext()
        browser.awaitUntil(description = "forward 30 s") { currentPosition in (start + 29_500)..(start + 30_500) }
        browser.seekToPrevious()
        browser.awaitUntil(description = "back 15 s") { currentPosition in (start + 14_500)..(start + 15_500) }
        // Chapters of The Night Orchard: 0, 100 and 200 s.
        assertThat(browser.chapter(PagewornCommands.NEXT_CHAPTER)).isIn(Range.closed(199_900L, 200_100L))
        assertThat(browser.chapter(PagewornCommands.NEXT_CHAPTER)).isIn(Range.closed(199_900L, 200_100L))
        assertThat(browser.chapter(PagewornCommands.PREVIOUS_CHAPTER)).isIn(Range.closed(99_900L, 100_100L))
        assertThat(browser.chapter(PagewornCommands.PREVIOUS_CHAPTER)).isIn(Range.closed(0L, 100L))
        browser.seekTo(105_000)
        // Media3 queues a controller's player commands but dispatches custom commands at once, so
        // a chapter command sent in the same instant can overtake the seek. Wait for the session's
        // own player, as a listener's next press would.
        browser.awaitUntil(description = "the session at 105 s") { container.playbackHub.state.value.positionMs in 104_900..105_100 }
        assertThat(browser.chapter(PagewornCommands.PREVIOUS_CHAPTER)).isIn(Range.closed(99_900L, 100_100L))

        // The finished book starts from the beginning; it is divided into tracks, so the chapter
        // commands move by track. The switch checkpointed the second book.
        val secondPlace = checkNotNull(container.progressStore.read(second)).positionMs
        assertThat(secondPlace).isIn(Range.closed(99_900L, 100_100L))
        assertThat(browser.playAndPause(children[2])).isAtMost(1_500L)
        assertThat(browser.chapter(PagewornCommands.NEXT_CHAPTER)).isIn(Range.closed(89_900L, 90_100L))
        assertThat(browser.chapter(PagewornCommands.PREVIOUS_CHAPTER)).isIn(Range.closed(0L, 100L))

        // With nothing downloaded the list is empty, successfully, with the message as a non-fatal error.
        browser.stop()
        browser.clearMediaItems()
        listOf(first, second, finished, damaged).forEach { container.editionStore.remove(it) }
        val empty = browser.getChildren(root.value!!.mediaId, 0, 50, null).await()
        assertThat(empty.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(empty.value).isEmpty()
        withTimeout(5_000) { while (errors.isEmpty()) delay(20) }
        assertThat(errors.map { it.message }).contains(BookSessionCallback.NOTHING_DOWNLOADED)
        // Nothing here can reach a server: the phone holds no address, and no transfer began.
        assertThat(container.database.addresses().forProfile(TestApp.PROFILE)).isEmpty()
        assertThat(container.database.transfers().allActive().map { it.jobId }).containsExactly("job-car")
        browser.release()
    }

    @Test
    fun a16fOnlyTheAppTheSystemAndTheCarMayConnect() = onMain {
        val player = ExoPlayer.Builder(context).build()
        val callback = BookSessionCallback(context.packageName, container, { error("not used") }, BookItems(context))
        val session = MediaLibrarySession.Builder(context, player, callback).setId("car-browser-test").build()
        try {
            fun info(packageName: String, trusted: Boolean) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                packageName, 1, 1_000_000, MediaLibraryInfo.VERSION_INT, 1, trusted, Bundle.EMPTY, false,
            )
            assertThat(callback.onConnect(session, info("com.example.other", trusted = false)).isAccepted).isFalse()
            // The notification, lock screen and resumption control arrive as trusted controllers.
            assertThat(callback.onConnect(session, info("com.android.systemui", trusted = true)).isAccepted).isTrue()
            val own = callback.onConnect(session, info(context.packageName, trusted = false))
            assertThat(own.isAccepted).isTrue()
            assertThat(own.availableSessionCommands.contains(SessionCommand(PagewornCommands.NEXT_CHAPTER, Bundle.EMPTY))).isTrue()
        } finally {
            session.release()
            player.release()
            callback.release()
        }
    }
}
