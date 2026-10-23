package com.qmusic.player.adapter

import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import java.lang.reflect.Field

/**
 * ForwardingPlayer subclass that enables dynamic delegate swapping via reflection
 * without disconnecting MediaSession or losing player listeners.
 *
 * Intercepts [Player.STATE_ENDED] when [suppressPlaybackEnded] is enabled to prevent
 * Android system notifications from dropping during crossfade song transitions.
 */
class DelegatingForwardingPlayer(initialPlayer: Player) : ForwardingPlayer(initialPlayer) {

    private var currentDelegate: Player = initialPlayer
    private val listeners = mutableMapOf<Player.Listener, Player.Listener>()

    @Volatile
    var suppressPlaybackEnded: Boolean = false

    /** Source of the queue: each ExoPlayer holds one item, so next/previous are routed to the app's own queue. */
    interface QueueNavigator {
        fun hasNext(): Boolean
        fun hasPrevious(): Boolean
        fun next()
        fun previous()
    }

    @Volatile
    var navigator: QueueNavigator? = null

    companion object {
        private val playerField: Field? by lazy {
            try {
                ForwardingPlayer::class.java.getDeclaredField("player").apply {
                    isAccessible = true
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    @Synchronized
    override fun addListener(listener: Player.Listener) {
        val wrappedListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (suppressPlaybackEnded && playbackState == Player.STATE_ENDED) {
                    // Suppress STATE_ENDED so notification does not drop
                    return
                }
                listener.onPlaybackStateChanged(playbackState)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                // The listener (the media session) must see this forwarding player, never the swapped-in one.
                listener.onEvents(this@DelegatingForwardingPlayer, events)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                listener.onTimelineChanged(timeline, reason)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listener.onMediaItemTransition(mediaItem, reason)
            }

            override fun onTracksChanged(tracks: Tracks) {
                listener.onTracksChanged(tracks)
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                listener.onMediaMetadataChanged(mediaMetadata)
            }

            override fun onPlaylistMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                listener.onPlaylistMetadataChanged(mediaMetadata)
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                listener.onIsLoadingChanged(isLoading)
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                listener.onAvailableCommandsChanged(availableCommands)
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                listener.onTrackSelectionParametersChanged(parameters)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                listener.onPlayWhenReadyChanged(playWhenReady, reason)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener.onIsPlayingChanged(isPlaying)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                listener.onRepeatModeChanged(repeatMode)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener.onPlayerError(error)
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                listener.onPlayerErrorChanged(error)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                listener.onPlaybackParametersChanged(playbackParameters)
            }

            override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
                listener.onSeekBackIncrementChanged(seekBackIncrementMs)
            }

            override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
                listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                listener.onAudioSessionIdChanged(audioSessionId)
            }

            override fun onAudioAttributesChanged(audioAttributes: androidx.media3.common.AudioAttributes) {
                listener.onAudioAttributesChanged(audioAttributes)
            }

            override fun onVolumeChanged(volume: Float) {
                listener.onVolumeChanged(volume)
            }

            override fun onMetadata(metadata: Metadata) {
                listener.onMetadata(metadata)
            }
        }

        synchronized(listeners) {
            listeners[listener] = wrappedListener
        }
        // Registered straight on the delegate (not through ForwardingPlayer, which hides its own wrapper object):
        // this very instance can later be taken off the old player when the delegate is swapped.
        currentDelegate.addListener(wrappedListener)
    }

    @Synchronized
    override fun removeListener(listener: Player.Listener) {
        val wrappedListener = synchronized(listeners) {
            listeners.remove(listener)
        } ?: return
        currentDelegate.removeListener(wrappedListener)
    }

    /**
     * Replaces the wrapped Player delegate using reflection so the parent MediaSession
     * continues using this instance seamlessly.
     */
    @Synchronized
    fun setDelegate(newPlayer: Player) {
        if (currentDelegate === newPlayer) return

        val oldPlayer = currentDelegate

        // Take the wrapped listeners off the old player, so what it does while being shut down (stop, no items)
        // never reaches the media session and its notification.
        synchronized(listeners) {
            for (wrapped in listeners.values) {
                try {
                    oldPlayer.removeListener(wrapped)
                } catch (_: Exception) {}
            }
        }

        try {
            playerField?.set(this, newPlayer)
            currentDelegate = newPlayer
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Attach wrapped listeners to new player
        synchronized(listeners) {
            for (wrapped in listeners.values) {
                try {
                    newPlayer.addListener(wrapped)
                } catch (_: Exception) {}
            }
        }
        announceSwap()
    }

    /**
     * Tells every listener that everything may have changed: the session and its notification read the new player's
     * song, state and position again, instead of keeping what the old player last said.
     */
    private fun announceSwap() {
        val snapshot = synchronized(listeners) { listeners.keys.toList() }
        val changed = Player.Events(
            FlagSet.Builder()
                .addAll(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                )
                .build(),
        )
        for (listener in snapshot) {
            try {
                listener.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                listener.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                listener.onMediaMetadataChanged(mediaMetadata)
                listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                listener.onPlaybackStateChanged(playbackState)
                listener.onIsPlayingChanged(isPlaying)
                listener.onAvailableCommandsChanged(availableCommands)
                listener.onEvents(this, changed)
            } catch (_: Exception) {
            }
        }
    }

    fun getCurrentDelegate(): Player = currentDelegate

    // --- Previous / next, exposed to the notification, lock screen and headset buttons ---

    private val queueCommands = listOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands {
        val nav = navigator ?: return super.getAvailableCommands()
        val builder = super.getAvailableCommands().buildUpon()
        if (nav.hasNext()) {
            builder.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.removeAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (nav.hasPrevious()) {
            builder.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        } else {
            builder.removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (command in queueCommands) availableCommands.contains(command) else super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = navigator?.hasNext() ?: super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = navigator?.hasPrevious() ?: super.hasPreviousMediaItem()

    override fun seekToNext() = navigator?.next() ?: super.seekToNext()

    override fun seekToNextMediaItem() = navigator?.next() ?: super.seekToNextMediaItem()

    override fun seekToPrevious() = navigator?.previous() ?: super.seekToPrevious()

    override fun seekToPreviousMediaItem() = navigator?.previous() ?: super.seekToPreviousMediaItem()

    override fun getPlaybackState(): Int {
        val state = super.getPlaybackState()
        if (suppressPlaybackEnded && state == Player.STATE_ENDED) {
            return Player.STATE_READY
        }
        return state
    }
}
