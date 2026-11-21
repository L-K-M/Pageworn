package ch.lkmc.pageworn.playback

import androidx.media3.common.Player

/**
 * Forwards every listener callback unchanged except the available commands, which pass through
 * [adjust]. Generated from the Media3 1.11.1 Player.Listener interface: Kotlin interface delegation
 * does not forward Java default methods, so each one is spelled out.
 */
internal class CommandAdjustingListener(
    private val listener: Player.Listener,
    private val adjust: (Player.Commands) -> Player.Commands,
) : Player.Listener {
    override fun onEvents(p0: androidx.media3.common.Player, p1: Player.Events) = listener.onEvents(p0, p1)
    override fun onTimelineChanged(p0: androidx.media3.common.Timeline, p1: Int) = listener.onTimelineChanged(p0, p1)
    override fun onMediaItemTransition(p0: androidx.media3.common.MediaItem?, p1: Int) = listener.onMediaItemTransition(p0, p1)
    override fun onTracksChanged(p0: androidx.media3.common.Tracks) = listener.onTracksChanged(p0)
    override fun onMediaMetadataChanged(p0: androidx.media3.common.MediaMetadata) = listener.onMediaMetadataChanged(p0)
    override fun onPlaylistMetadataChanged(p0: androidx.media3.common.MediaMetadata) = listener.onPlaylistMetadataChanged(p0)
    override fun onIsLoadingChanged(p0: Boolean) = listener.onIsLoadingChanged(p0)
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onLoadingChanged(p0: Boolean) = listener.onLoadingChanged(p0)
    override fun onAvailableCommandsChanged(p0: Player.Commands) = listener.onAvailableCommandsChanged(adjust(p0))
    override fun onTrackSelectionParametersChanged(p0: androidx.media3.common.TrackSelectionParameters) = listener.onTrackSelectionParametersChanged(p0)
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPlayerStateChanged(p0: Boolean, p1: Int) = listener.onPlayerStateChanged(p0, p1)
    override fun onPlaybackStateChanged(p0: Int) = listener.onPlaybackStateChanged(p0)
    override fun onPlayWhenReadyChanged(p0: Boolean, p1: Int) = listener.onPlayWhenReadyChanged(p0, p1)
    override fun onPlaybackSuppressionReasonChanged(p0: Int) = listener.onPlaybackSuppressionReasonChanged(p0)
    override fun onIsPlayingChanged(p0: Boolean) = listener.onIsPlayingChanged(p0)
    override fun onRepeatModeChanged(p0: Int) = listener.onRepeatModeChanged(p0)
    override fun onShuffleModeEnabledChanged(p0: Boolean) = listener.onShuffleModeEnabledChanged(p0)
    override fun onPlayerError(p0: androidx.media3.common.PlaybackException) = listener.onPlayerError(p0)
    override fun onPlayerErrorChanged(p0: androidx.media3.common.PlaybackException?) = listener.onPlayerErrorChanged(p0)
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onPositionDiscontinuity(p0: Int) = listener.onPositionDiscontinuity(p0)
    override fun onPositionDiscontinuity(p0: Player.PositionInfo, p1: Player.PositionInfo, p2: Int) = listener.onPositionDiscontinuity(p0, p1, p2)
    override fun onPlaybackParametersChanged(p0: androidx.media3.common.PlaybackParameters) = listener.onPlaybackParametersChanged(p0)
    override fun onSeekBackIncrementChanged(p0: Long) = listener.onSeekBackIncrementChanged(p0)
    override fun onSeekForwardIncrementChanged(p0: Long) = listener.onSeekForwardIncrementChanged(p0)
    override fun onMaxSeekToPreviousPositionChanged(p0: Long) = listener.onMaxSeekToPreviousPositionChanged(p0)
    override fun onAudioSessionIdChanged(p0: Int) = listener.onAudioSessionIdChanged(p0)
    override fun onAudioAttributesChanged(p0: androidx.media3.common.AudioAttributes) = listener.onAudioAttributesChanged(p0)
    override fun onVolumeChanged(p0: Float) = listener.onVolumeChanged(p0)
    override fun onSkipSilenceEnabledChanged(p0: Boolean) = listener.onSkipSilenceEnabledChanged(p0)
    override fun onDeviceInfoChanged(p0: androidx.media3.common.DeviceInfo) = listener.onDeviceInfoChanged(p0)
    override fun onDeviceVolumeChanged(p0: Int, p1: Boolean) = listener.onDeviceVolumeChanged(p0, p1)
    override fun onVideoSizeChanged(p0: androidx.media3.common.VideoSize) = listener.onVideoSizeChanged(p0)
    override fun onSurfaceSizeChanged(p0: Int, p1: Int) = listener.onSurfaceSizeChanged(p0, p1)
    override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCues(p0: List<androidx.media3.common.text.Cue>) = listener.onCues(p0)
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCues(p0: androidx.media3.common.text.CueGroup) = listener.onCues(p0)
    override fun onMetadata(p0: androidx.media3.common.Metadata) = listener.onMetadata(p0)

    override fun equals(other: Any?) = other is CommandAdjustingListener && other.listener == listener

    override fun hashCode() = listener.hashCode()
}
