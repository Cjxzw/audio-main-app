package com.agent.voiceassistant.media

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A media-session-facing player for the always-available Main voice session.
 * It exposes a real playable media item without producing silent audio or holding audio focus.
 */
@OptIn(markerClass = [UnstableApi::class])
class AssistantMediaPlayer(
    looper: Looper,
    private val appName: String,
    private val callbacks: Callbacks,
) : SimpleBasePlayer(looper) {

    interface Callbacks {
        fun onPlayRequested()
        fun onPauseRequested()
        fun onStopRequested()
    }

    private val mediaId = "hanwo-session"
    private var active = false
    private var title = appName
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
        this.title = title.ifBlank { appName }
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
            .setAvailableCommands(SUPPORTED_COMMANDS)
            .setPlayWhenReady(active, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(0L)
            .setVolume(volume)
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        // Some standard controllers prepare the session before issuing play.
        invalidateState()
        return Futures.immediateVoidFuture()
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
        .setArtist(appName)
        .setAlbumTitle(appName)
        .setAlbumArtist(appName)
        .setSubtitle(status)
        .setDescription("个人语音助手：$status")
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    companion object {
        val SUPPORTED_COMMANDS: Player.Commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_MEDIA_ITEMS_METADATA)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_GET_VOLUME)
            .add(Player.COMMAND_SET_VOLUME)
            .build()
    }
}
