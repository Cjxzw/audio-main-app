package com.agent.voiceassistant.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.agent.voiceassistant.MainActivity
import com.agent.voiceassistant.R
import com.agent.voiceassistant.service.DiagLog
import com.agent.voiceassistant.service.VoiceAgentService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList

/**
 * Standard Android media entry point for Main. External controllers such as watches,
 * headsets and car systems can discover this service without using the Telecom stack.
 */
class MainMediaLibraryService : MediaLibraryService() {

    private lateinit var assistantPlayer: AssistantMediaPlayer
    private lateinit var librarySession: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .build(),
        )

        assistantPlayer = AssistantMediaPlayer(Looper.getMainLooper(), object : AssistantMediaPlayer.Callbacks {
            override fun onPlayRequested() {
                DiagLog.i("media3.control.play", "source=external_controller", showInUi = true)
                VoiceAgentService.wake(this@MainMediaLibraryService)
            }

            override fun onPauseRequested() {
                DiagLog.i("media3.control.pause", "source=external_controller", showInUi = true)
                VoiceAgentService.sleep(this@MainMediaLibraryService)
            }

            override fun onStopRequested() {
                DiagLog.i("media3.control.stop", "source=external_controller", showInUi = true)
                VoiceAgentService.sleep(this@MainMediaLibraryService)
            }
        })
        assistantPlayer.setAssistantState(active = false, status = "休眠中")

        val sessionActivity = PendingIntent.getActivity(
            this,
            12,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        librarySession = MediaLibrarySession.Builder(this, assistantPlayer, object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val children = if (parentId == ROOT_ID) {
                    ImmutableList.of(currentItem())
                } else {
                    ImmutableList.of()
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                controller: MediaSession.ControllerInfo,
                mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> {
                return if (mediaId == MEDIA_ID) {
                    Futures.immediateFuture(LibraryResult.ofItem(currentItem(), null))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int,
            ): Int {
                DiagLog.i("media3.control.command", "command=$playerCommand", showInUi = true)
                return SessionResult.RESULT_SUCCESS
            }
        })
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity)
            .build()
        DiagLog.i("media3.service.ready", "session=$SESSION_ID", showInUi = true)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = librarySession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_STATE) {
            val status = intent.getStringExtra(EXTRA_STATUS) ?: "休眠中"
            assistantPlayer.setAssistantState(
                active = intent.getBooleanExtra(EXTRA_ACTIVE, false),
                status = status,
            )
            intent.getStringExtra(EXTRA_TITLE)?.let { assistantPlayer.setNowPlaying(it, status) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        librarySession.release()
        assistantPlayer.release()
        DiagLog.i("media3.service.destroyed")
        super.onDestroy()
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle("枢卫 Main")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private fun currentItem(): MediaItem = MediaItem.Builder()
        .setMediaId(MEDIA_ID)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle("枢卫 Main")
                .setArtist("枢卫语音助手")
                .setAlbumTitle("Main 语音会话")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "main_media_session"
        private const val NOTIFICATION_ID = 41
        private const val SESSION_ID = "shordway-main"
        private const val ROOT_ID = "shordway-root"
        private const val MEDIA_ID = "shordway-main-session"
        private const val ACTION_UPDATE_STATE = "com.agent.voiceassistant.media.UPDATE_STATE"
        private const val EXTRA_ACTIVE = "active"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_TITLE = "title"

        fun ensureStarted(context: android.content.Context) {
            context.startService(Intent(context, MainMediaLibraryService::class.java))
        }

        fun publishState(context: android.content.Context, active: Boolean, status: String) {
            publishState(context, active, status, null)
        }

        fun publishNowPlaying(context: android.content.Context, title: String, status: String) {
            publishState(context, active = true, status = status, title = title)
        }

        private fun publishState(context: android.content.Context, active: Boolean, status: String, title: String?) {
            val intent = Intent(context, MainMediaLibraryService::class.java)
                .setAction(ACTION_UPDATE_STATE)
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_STATUS, status)
            if (!title.isNullOrBlank()) intent.putExtra(EXTRA_TITLE, title.take(120))
            context.startService(intent)
        }
    }
}
