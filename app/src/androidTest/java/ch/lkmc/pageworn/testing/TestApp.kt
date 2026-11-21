package ch.lkmc.pageworn.testing

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.SystemClockSource
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.debug.LocalEditions
import ch.lkmc.pageworn.formats.MediaProbe
import ch.lkmc.pageworn.playback.PlaybackService
import ch.lkmc.pageworn.playback.ProgressWriter
import kotlinx.coroutines.runBlocking
import java.io.File

/** Shared arrangement for instrumented tests: a clean app state and fixture editions. */
object TestApp {
    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val app: PagewornApp get() = context.applicationContext as PagewornApp
    val container: AppContainer get() = app.container
    const val PROFILE = "00000000-0000-4000-8000-0000000000aa"

    fun key(itemId: String) = BookKey(PROFILE, itemId)

    /** Stops playback, clears Room, audio and settings, and restores the real clock and writer. */
    fun reset(clock: Clock = SystemClockSource) {
        context.stopService(Intent(context, PlaybackService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        container.clock = clock
        container.progressWriter = ProgressWriter.of(container.progressStore, container.appScope)
        container.playbackHub.clear()
        container.database.clearAllTables()
        container.editionFiles.root.deleteRecursively()
        container.settings.setLastActiveBook(null)
        container.settings.setSpeed(1f)
    }

    private val probedDurations = HashMap<File, Long>()

    fun probeDuration(file: File): Long = probedDurations.getOrPut(file) {
        runBlocking { MediaProbe(context).probe(file) }.durationMs ?: throw AssertionError("Fixture ${file.name} did not open")
    }

    /** The boundary book: tracks of 90 s and 120 s, server chapters at 0, 75 and 140 s by default. */
    fun publishBoundaryBook(
        itemId: String = "boundary-book",
        title: String = "Boundary Book",
        chapters: List<RawChapter> = listOf(RawChapter(0, 75_000, "Chapter One"), RawChapter(75_000, 140_000, "Chapter Two"), RawChapter(140_000, 210_000, "Chapter Three")),
        fixtureId: String = "boundary-book",
    ): BookKey = runBlocking {
        val key = key(itemId)
        val files = Fixtures.install(fixtureId)
        LocalEditions.publish(
            container, key, title, "Fixture Author",
            files.map { LocalEditions.Track(it, probeDuration(it), it.nameWithoutExtension) },
            serverChapters = chapters,
        )
        key
    }

    fun publishSingleM4b(itemId: String = "single-m4b", serverChapters: List<RawChapter> = emptyList()): BookKey = runBlocking {
        val key = key(itemId)
        val file = Fixtures.install("single-m4b").single()
        LocalEditions.publish(
            container, key, "The Night Orchard", "Elin Ward",
            listOf(LocalEditions.Track(file, probeDuration(file))),
            serverChapters = serverChapters,
            embeddedChapters = listOf(listOf(RawChapter(0, 100_000, "The Orchard Gate"), RawChapter(100_000, 200_000, "Under the Pear Tree"), RawChapter(200_000, 300_000, "Moonrise"))),
        )
        key
    }
}
