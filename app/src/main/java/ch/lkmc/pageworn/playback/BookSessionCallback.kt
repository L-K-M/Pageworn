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
import androidx.media3.session.SessionResult
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.BookKey
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

/**
 * Session callback: who may connect, what the car sees, and how a book media ID becomes a playable
 * item. The playback rules themselves (positions, rewind, sleep, failures) live in [PlaybackController].
 */
class BookSessionCallback(
    private val service: MediaLibraryService,
    private val container: AppContainer,
) : MediaLibrarySession.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val isOwn = controller.packageName == service.packageName
        val isCar = session.isAutoCompanionController(controller)
        if (!isOwn && !isCar && !controller.isTrusted) {
            return MediaSession.ConnectionResult.reject()
        }
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(PagewornCommands.NEXT_CHAPTER, Bundle.EMPTY))
            .add(SessionCommand(PagewornCommands.PREVIOUS_CHAPTER, Bundle.EMPTY))
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
        if (parentId != ROOT_ID) return@future LibraryResult.ofError(SessionError_NOT_FOUND)
        val books = container.editionSource.readyBooks()
        val items = books.map { browsableItem(it, browser.packageName) }
        val from = (page * pageSize).coerceAtMost(items.size)
        val to = (from + pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val key = BookKey.fromMediaId(mediaId) ?: return@future LibraryResult.ofError(SessionError_NOT_FOUND)
        val plan = container.editionSource.plan(key) ?: return@future LibraryResult.ofError(SessionError_NOT_FOUND)
        LibraryResult.ofItem(browsableItem(plan, browser.packageName), null)
    }

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
        val plan = container.editionSource.plan(key) ?: throw UnsupportedOperationException("Book is not ready")
        val position = if (startPositionMs == C.TIME_UNSET) 0L else plan.timeline.clamp(startPositionMs)
        MediaSession.MediaItemsWithStartPosition(listOf(playableItem(plan)), 0, position)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        mediaItems.mapNotNull { item ->
            BookKey.fromMediaId(item.mediaId)?.let { container.editionSource.plan(it) }?.let { playableItem(it) }
        }.toMutableList()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val player = session.player
        val item = player.currentMediaItem
        val plan = item?.localConfiguration?.tag as? EditionPlan
            ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
        val timeline = plan.timeline
        when (customCommand.customAction) {
            PagewornCommands.NEXT_CHAPTER -> timeline.nextSegmentStart(player.currentPosition)?.let { player.seekTo(it) }
            PagewornCommands.PREVIOUS_CHAPTER -> player.seekTo(timeline.previousSegmentTarget(player.currentPosition))
            else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /** Item for browsing (car list, item lookups): metadata only, no local file reference. */
    private fun browsableItem(plan: EditionPlan, callerPackage: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(plan.key.mediaId)
            .setMediaMetadata(metadata(plan, callerPackage))
            .build()

    /** Item handed to the player: carries the resolved edition for [BookMediaSourceFactory]. */
    fun playableItem(plan: EditionPlan): MediaItem =
        MediaItem.Builder()
            .setMediaId(plan.key.mediaId)
            .setUri("pageworn://book/${plan.key.profileId}/${plan.generationId}")
            .setTag(plan)
            .setMediaMetadata(metadata(plan, null))
            .build()

    private fun metadata(plan: EditionPlan, callerPackage: String?): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(plan.title)
            .setArtist(plan.author)
            .setAlbumTitle(plan.title)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setDurationMs(plan.timeline.durationMs)
            .setArtworkUri(CoverProvider.uriFor(service, plan.key, plan.generationId, callerPackage))
            .build()

    companion object {
        const val ROOT_ID = "root"
        private const val SessionError_NOT_FOUND = androidx.media3.session.SessionError.ERROR_BAD_VALUE
    }
}

object PagewornCommands {
    const val NEXT_CHAPTER = "ch.lkmc.pageworn.NEXT_CHAPTER"
    const val PREVIOUS_CHAPTER = "ch.lkmc.pageworn.PREVIOUS_CHAPTER"
}
