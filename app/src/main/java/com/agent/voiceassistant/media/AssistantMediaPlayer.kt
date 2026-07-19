package com.agent.voiceassistant.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A media-session-facing player for the always-available Main voice session.
 * It exposes a real playable media item without producing silent audio or holding audio focus.
 */
class AssistantMediaPlayer(
    looper: Looper,
    private val callbacks: Callbacks,
) : SimpleBasePlayer(looper) {

    interface Callbacks {
        fun onPlayRequested()
        fun onPauseRequested()
        fun onStopRequested()
    }

    private val mediaId = "shordway-main-session"
    private var active = false
    private var title = "枢卫 Main"
    private var status = "休眠中"
    private var volume = 1f

    private val mediaItem: MediaItem
        get() = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata())
            .build()

    fun setAssistantState(active: Boolean, status: String) {
        this.active = active
        this.status = status
        invalidateState()
    }

    fun setNowPlaying(title: String, status: String) {
        this.title = title.ifBlank { "枢卫 Main" }
        this.status = status
        invalidateState()
    }

    override fun getState(): State {
        val metadata = metadata()
        val item = SimpleBasePlayer.MediaItemData.Builder(mediaId)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .setIsDynamic(true)
            .setDurationUs(C.TIME_UNSET)
            .build()

        return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlayWhenReady(active, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(0L)
            .setVolume(volume)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            callbacks.onPlayRequested()
        } else {
            callbacks.onPauseRequested()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        callbacks.onStopRequested()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume.coerceIn(0f, 1f)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun metadata(): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setDisplayTitle(title)
        .setArtist("枢卫语音助手")
        .setAlbumTitle("Main 语音会话")
        .setAlbumArtist("Shordway")
        .setSubtitle(status)
        .setDescription("个人语音助手：$status")
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
}
