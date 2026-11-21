package ch.lkmc.pageworn.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

internal data class BookSource(val edition: EditionPlan, val audioEndMs: Long?)

/** Builds the media items that present a book, never its files, to the player and system surfaces. */
class BookItems(private val context: Context) {

    /** Item handed to the player: carries the resolved edition for [BookMediaSourceFactory]. */
    fun playable(plan: EditionPlan, audioEndMs: Long? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(plan.key.mediaId)
            .setUri("pageworn://book/${plan.generationId}")
            .setTag(BookSource(plan, audioEndMs))
            .setMediaMetadata(metadata(plan, SYSTEM_UI))
            .build()

    /** Item for browsing (the car list, item lookups): metadata only, no local file reference. */
    fun browsable(plan: EditionPlan, callerPackage: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(plan.key.mediaId)
            .setMediaMetadata(metadata(plan, callerPackage))
            .build()

    /** Metadata for the resumption control after a restart: the cover travels as bytes, offline. */
    fun forResumptionMetadata(plan: EditionPlan, artwork: ByteArray?): MediaItem =
        MediaItem.Builder()
            .setMediaId(plan.key.mediaId)
            .setMediaMetadata(
                metadata(plan, null).buildUpon()
                    .setArtworkUri(null)
                    .apply { if (artwork != null) setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                    .build(),
            )
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
            .setArtworkUri(CoverProvider.uriFor(context, plan.key, plan.generationId, callerPackage))
            .build()

    private companion object {
        // Platform media controls read the session's artwork URI separately from the car browser.
        const val SYSTEM_UI = "com.android.systemui"
    }
}
