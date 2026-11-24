package ch.lkmc.pageworn.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.sleep.SleepLength
import com.google.common.collect.ImmutableList
import com.google.common.io.ByteStreams
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.guava.future
import java.io.IOException
import java.io.InputStream

/**
 * Session callback: who may connect, what the car sees, and how a book media ID becomes a playable
 * item. The playback rules themselves live in [PlaybackController].
 */
class BookSessionCallback(
    /** The app's own package: its UI connects as a controller like any other surface. */
    private val ownPackage: String,
    private val container: AppContainer,
    private val controller: () -> PlaybackController,
    private val items: BookItems,
) : MediaLibrarySession.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun release() = scope.cancel()

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val isOwn = controller.packageName == ownPackage
        val isCar = session.isAutoCompanionController(controller)
        // The titles on this phone are private: only the app, Android Auto and the system's own
        // trusted surfaces (notification, lock screen, resumption control) may connect.
        if (!isOwn && !isCar && !controller.isTrusted) {
            return MediaSession.ConnectionResult.reject()
        }
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(PagewornCommands.NEXT_CHAPTER, Bundle.EMPTY))
            .add(SessionCommand(PagewornCommands.PREVIOUS_CHAPTER, Bundle.EMPTY))
            .apply {
                if (isOwn) {
                    add(SessionCommand(PagewornCommands.SET_SLEEP, Bundle.EMPTY))
                    add(SessionCommand(PagewornCommands.SEEK_IN_BOOK, Bundle.EMPTY))
                }
            }
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .setMediaButtonPreferences(mediaButtonPreferences(forCar = isCar))
            .build()
    }

    /** Back 15 s and forward 30 s for every surface; the car also gets the two chapter commands. */
    fun mediaButtonPreferences(forCar: Boolean): ImmutableList<CommandButton> {
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("Back 15 seconds")
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("Forward 30 seconds")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        if (!forCar) return ImmutableList.of(back, forward)
        val previousChapter = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setSessionCommand(SessionCommand(PagewornCommands.PREVIOUS_CHAPTER, Bundle.EMPTY))
            .setDisplayName("Previous chapter")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        val nextChapter = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setSessionCommand(SessionCommand(PagewornCommands.NEXT_CHAPTER, Bundle.EMPTY))
            .setDisplayName("Next chapter")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        return ImmutableList.of(back, forward, previousChapter, nextChapter)
    }

    // --- The car's list ------------------------------------------------------------------------

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        val root = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Downloaded")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .build(),
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, MediaLibraryService.LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        if (parentId != ROOT_ID) return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val books = container.editionSource.readyBooks()
        if (books.isEmpty()) {
            // An empty list is a successful result; the message is a non-fatal session error.
            session.sendError(browser, SessionError(SessionError.ERROR_NOT_SUPPORTED, NOTHING_DOWNLOADED))
        }
        val items = books.map { items.browsable(it, browser.packageName) }
        val from = (page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).coerceAtMost(items.size)
        val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val key = BookKey.fromMediaId(mediaId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val plan = container.editionSource.plan(key) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItem(items.browsable(plan, browser.packageName), null)
    }

    // --- Resolving books for the player --------------------------------------------------------

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val requested = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            ?: mediaItems.firstOrNull()
            ?: throw UnsupportedOperationException("No media item")
        val key = BookKey.fromMediaId(requested.mediaId) ?: throw UnsupportedOperationException("Not a book")
        val explicit = startPositionMs.takeIf { it != C.TIME_UNSET && startIndex != C.INDEX_UNSET }
        val (item, start) = controller().resolve(key, explicit) ?: throw UnsupportedOperationException("Book is not ready")
        MediaSession.MediaItemsWithStartPosition(listOf(item), 0, start)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        // One book at a time: adding resolves the first book like a set.
        val key = mediaItems.firstNotNullOfOrNull { BookKey.fromMediaId(it.mediaId) } ?: throw UnsupportedOperationException("Not a book")
        val (item, _) = controller().resolve(key, null) ?: throw UnsupportedOperationException("Book is not ready")
        mutableListOf(item)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val key = container.settings.lastActiveBook.value ?: throw UnsupportedOperationException("No book to resume")
        if (isForPlayback) {
            val (item, start) = controller().resolve(key, null) ?: throw UnsupportedOperationException("Book is not ready")
            MediaSession.MediaItemsWithStartPosition(listOf(item), 0, start)
        } else {
            // Metadata only, for the resumption control after a restart: no network, cover as bytes.
            val plan = container.editionSource.plan(key) ?: throw UnsupportedOperationException("Book is not ready")
            val artwork = plan.coverFile?.let { file ->
                withContext(Dispatchers.IO) {
                    try {
                        file.takeIf { it.isFile && it.length() < MAX_COVER_BYTES }
                            ?.inputStream()?.use(::readResumptionArtwork)
                    } catch (_: IOException) {
                        null
                    }
                }
            }
            val position = container.progressStore.read(key)?.positionMs ?: 0L
            MediaSession.MediaItemsWithStartPosition(listOf(items.forResumptionMetadata(plan, artwork)), 0, position)
        }
    }

    // --- Custom commands -----------------------------------------------------------------------

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val c = controller()
        if (customCommand.customAction == PagewornCommands.SET_SLEEP) {
            // Sleep mode needs no book: it applies to the next start.
            val length = when (args.getString(PagewornCommands.ARG_SLEEP_MODE)) {
                PagewornCommands.SLEEP_OFF -> null
                PagewornCommands.SLEEP_END_OF_SEGMENT -> SleepLength.EndOfSegment
                PagewornCommands.SLEEP_TIMED -> SleepLength.Minutes(
                    args.getInt(PagewornCommands.ARG_SLEEP_MINUTES).coerceIn(SleepLength.MIN_MINUTES, SleepLength.MAX_MINUTES),
                )
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            c.setSleep(length)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        val plan = c.loadedPlan ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val player = session.player
        val timeline = plan.timeline
        when (customCommand.customAction) {
            PagewornCommands.NEXT_CHAPTER -> timeline.nextSegmentStart(player.currentPosition)?.let { player.seekTo(it) }
            PagewornCommands.PREVIOUS_CHAPTER -> player.seekTo(timeline.previousSegmentTarget(player.currentPosition))
            PagewornCommands.SEEK_IN_BOOK -> player.seekTo(timeline.clamp(args.getLong(PagewornCommands.ARG_POSITION_MS)))
            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    companion object {
        const val ROOT_ID = "root"
        const val NOTHING_DOWNLOADED = "Download books on your phone to listen here."
        /** A bound on the artwork bytes embedded in resumption metadata. */
        const val MAX_COVER_BYTES = 2_000_000
    }
}

/** The file can grow after its size check, so enforce the limit on the stream too. */
internal fun readResumptionArtwork(input: InputStream): ByteArray? {
    val limit = BookSessionCallback.MAX_COVER_BYTES
    return ByteStreams.limit(input, limit.toLong()).readBytes().takeIf { it.size < limit }
}

object PagewornCommands {
    const val NEXT_CHAPTER = "ch.lkmc.pageworn.NEXT_CHAPTER"
    const val PREVIOUS_CHAPTER = "ch.lkmc.pageworn.PREVIOUS_CHAPTER"
    const val SET_SLEEP = "ch.lkmc.pageworn.SET_SLEEP"
    const val SEEK_IN_BOOK = "ch.lkmc.pageworn.SEEK_IN_BOOK"
    const val ARG_SLEEP_MODE = "mode"
    const val ARG_SLEEP_MINUTES = "minutes"
    const val ARG_POSITION_MS = "position"
    const val SLEEP_OFF = "off"
    const val SLEEP_TIMED = "timed"
    const val SLEEP_END_OF_SEGMENT = "end"
}
