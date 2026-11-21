package ch.lkmc.pageworn.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Presents one book to every system surface. Media3 maps a headset's Next to [seekToNext],
 * Previous to [seekToPrevious] and a Bluetooth double press of Play/Pause to [seekToNext]. With one
 * book item, Next would do nothing and Previous would restart the book, so both are reported as
 * available and turned into forward 30 s and back 15 s, matching the player's own keys.
 */
class BookPlayer(player: Player, private val beforePlay: () -> Unit = {}) : ForwardingPlayer(player) {

    /** Every way of starting playback (keys, headset, notification, car) passes through here. */
    override fun play() {
        if (!playWhenReady) beforePlay()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && !this.playWhenReady) beforePlay()
        super.setPlayWhenReady(playWhenReady)
    }


    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
            .removeAll(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun seekToNext() = seekForward()

    override fun seekToPrevious() = seekBack()

    override fun hasNextMediaItem(): Boolean = false

    override fun hasPreviousMediaItem(): Boolean = false

    override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF

    override fun setRepeatMode(repeatMode: Int) {
        // Repeat stays off: a book ends in its finished state.
    }

    override fun seekBack() {
        seekTo((currentPosition - seekBackIncrement).coerceAtLeast(0L))
    }

    override fun seekForward() {
        val duration = duration
        val target = currentPosition + seekForwardIncrement
        seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    companion object {
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L
    }
}
