package ch.lkmc.pageworn.widget

import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.PhoneBooks
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.playback.PlayProblem
import ch.lkmc.pageworn.playback.PlayRefusal
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.StartAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A32: what each widget shows and what its keys do, from the app's own state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetStatesTest {
    private val container = ApplicationProvider.getApplicationContext<PagewornApp>().container
    private val books = PhoneBooks(container)
    private lateinit var older: BookKey
    private lateinit var newer: BookKey

    @Before
    fun setUp() = runBlocking<Unit> {
        older = books.download("older")
        newer = books.download("newer")
        val edition = checkNotNull(container.database.editions().edition(older.profileId, older.itemId))
        container.database.editions().upsertEdition(edition.copy(publishedAt = 1_000))
    }

    @Test
    fun nowReadingFollowsThePlayersBook() = runBlocking<Unit> {
        books.place(older, 60_000)

        val rested = reading(lastActive = older)
        assertThat(rested.face.key).isEqualTo(older)
        assertThat(rested.destination).isEqualTo(WidgetDestination.PLAYER)
        assertThat(rested.key?.start).isEqualTo(StartAction.RESUME)

        val starting = reading(LivePlayback(starting = newer), lastActive = older)
        assertThat(starting.face.key).isEqualTo(newer)
        assertThat(starting.key?.showsPause).isTrue()
        assertThat(starting.key?.starting).isTrue()
    }

    @Test
    fun withoutAPlayerBookItShowsTheFirstDownloadAndLeadsToItsDetails() = runBlocking<Unit> {
        for (lastActive in listOf(null, BookKey("profile", "removed"))) {
            val state = reading(lastActive = lastActive)
            assertThat(state.face.key).isEqualTo(newer)
            assertThat(state.destination).isEqualTo(WidgetDestination.DETAILS)
            assertThat(state.key?.start).isEqualTo(StartAction.PLAY)
        }
        assertThat(WidgetStates.readingBook(emptyList(), emptyMap(), LivePlayback(), older, null)).isNull()
    }

    @Test
    fun bothBarsShowTheSavedPlaceExactly() = runBlocking<Unit> {
        // 2 h 08 min 20 s of 4 h 20 min: 8 min 20 s into the thirteenth ten-minute part.
        books.place(older, 7_700_000)

        val state = reading(lastActive = older)

        assertThat(state.segmentLine).isEqualTo("Part 13")
        assertThat(state.segmentPermille).isEqualTo(833)
        assertThat(state.segmentLeft).isEqualTo("2 min left")
        assertThat(state.bookPermille).isEqualTo(493)
        assertThat(state.bookLine).isEqualTo("Book · 2 h 08 min of 4 h 20 min")
    }

    @Test
    fun aPlayingBookRedrawsInStepsAndAtOnceInANewPart() = runBlocking<Unit> {
        suspend fun at(positionMs: Long) = reading(
            LivePlayback(key = older, isPlaying = true, playWhenReady = true, held = true, playbackRequested = true, positionMs = positionMs),
        )

        assertThat(at(7_715_250)).isEqualTo(at(7_715_000))
        assertThat(at(7_740_000).segmentPermille).isGreaterThan(at(7_715_000).segmentPermille)
        val nextPart = at(7_800_250)
        assertThat(nextPart.segmentLine).isEqualTo("Part 14")
        assertThat(nextPart.segmentPermille).isEqualTo(0)
    }

    @Test
    fun changedAudioHasNoKeyUntilTheListenerChooses() = runBlocking<Unit> {
        books.place(older, 60_000)
        container.progressStore.requireContinuityChoice(older, "changed")

        val state = reading(lastActive = older)

        assertThat(state.key).isNull()
        assertThat(state.notice).isEqualTo(Copy.CHOOSE_START)
        assertThat(state.destination).isEqualTo(WidgetDestination.PLAYER)
        val shelved = shelf().books.single { it.face.key == older }
        assertThat(shelved.key).isNull()
        assertThat(shelved.hold).isEqualTo(ShelfHold.CHOOSE_START)
    }

    @Test
    fun aDamagedCopyHasNoKeyAndSaysWhy() = runBlocking<Unit> {
        val edition = editions().single { it.key == older }
        container.editionStore.markRepairNeeded(older, edition.generationId)

        val state = reading(lastActive = older)

        assertThat(editions().single { it.key == older }.state).isEqualTo(EditionState.REPAIR_NEEDED)
        assertThat(state.key).isNull()
        assertThat(state.notice).isEqualTo(Copy.DAMAGED_COPY)
        assertThat(shelf().books.single { it.face.key == older }.hold).isEqualTo(ShelfHold.REPAIR)
    }

    @Test
    fun aStartThatFailedSaysSoAndKeepsPlay() = runBlocking<Unit> {
        val problem = PlayProblem(older, PlayRefusal.DID_NOT_START)

        val state = reading(lastActive = older, problem = problem)

        assertThat(state.notice).isEqualTo(Copy.PLAY_DID_NOT_START)
        assertThat(state.key?.action).isEqualTo(KeyAction.PLAY)
        assertThat(shelf(problem = problem).notice).isEqualTo(Copy.PLAY_DID_NOT_START)
    }

    @Test
    fun theShelfIsNewestFirstAndPlayingDoesNotReorderIt() = runBlocking<Unit> {
        assertThat(shelf().books.map { it.face.key }).containsExactly(newer, older).inOrder()

        val session = container.progressStore.newSession(older)
        container.progressStore.save(older, session.next(), 90_000, PhoneBooks.DURATION_MS, null, StopKind.EXACT, listened = true)
        val playing = shelf(LivePlayback(key = older, held = true, playbackRequested = true, isPlaying = true))

        assertThat(playing.books.map { it.face.key }).containsExactly(newer, older).inOrder()
        assertThat(playing.books.map { it.key?.showsPause }).containsExactly(false, true).inOrder()
    }

    @Test
    fun theShelfHoldsTheFourNewest() = runBlocking<Unit> {
        repeat(4) { books.download("more-$it") }

        assertThat(shelf().books).hasSize(WidgetStates.SHELF_BOOKS)
        assertThat(shelf().books.map { it.face.key }).doesNotContain(older)
    }

    @Test
    fun theSecondTapOfADoubleTapRepeatsTheFirst() = runBlocking<Unit> {
        val live = LivePlayback(starting = older)

        val settling = reading(live, settling = SettlingTap(older, KeyAction.PLAY, 1)).key
        assertThat(settling?.showsPause).isTrue()
        assertThat(settling?.action).isEqualTo(KeyAction.PLAY)
        assertThat(reading(live).key?.action).isEqualTo(KeyAction.PAUSE)
        assertThat(reading(live, settling = SettlingTap(newer, KeyAction.PLAY, 1)).key?.action).isEqualTo(KeyAction.PAUSE)
    }

    private suspend fun editions(): List<LocalEditionEntity> = container.editionStore.observeEverything().first()

    private suspend fun progress() = container.database.progress().observeEverything().first().associateBy { it.key }

    private suspend fun reading(
        live: LivePlayback = LivePlayback(),
        lastActive: BookKey? = null,
        problem: PlayProblem? = null,
        settling: SettlingTap? = null,
    ): ReadingWidgetState {
        val saved = progress()
        val book = checkNotNull(WidgetStates.readingBook(editions(), saved, live, lastActive, problem))
        val plan = checkNotNull(container.editionStore.anyPlan(book.edition.key))
        return WidgetStates.reading(book, plan, saved[plan.key], live, problem, settling)
    }

    private suspend fun shelf(live: LivePlayback = LivePlayback(), problem: PlayProblem? = null) =
        WidgetStates.shelf(editions(), { container.editionFiles.published(it.generationId) }, progress(), live, problem, null)
}
