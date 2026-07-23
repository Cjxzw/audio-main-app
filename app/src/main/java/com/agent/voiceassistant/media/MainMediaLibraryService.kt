package com.agent.voiceassistant.media

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import androidx.media3.common.util.UnstableApi
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
@OptIn(markerClass = [UnstableApi::class])
class MainMediaLibraryService : MediaLibraryService() {

    private lateinit var assistantPlayer: AssistantMediaPlayer
    private lateinit var librarySession: MediaLibrarySession
    private var currentActive = false
    private var currentStatus = "休眠中，等待唤醒"

    override fun onCreate() {
        super.onCreate()
        AssistantNotificationContract.ensureChannel(this)

        assistantPlayer = AssistantMediaPlayer(
            Looper.getMainLooper(),
            getString(R.string.app_name),
            object : AssistantMediaPlayer.Callbacks {
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
            },
        )
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
                    Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
            }

            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int,
            ): Int {
                val supported = AssistantMediaPlayer.SUPPORTED_COMMANDS.contains(playerCommand)
                DiagLog.i(
                    "media3.control.command",
                    "command=$playerCommand supported=$supported controller=${controller.packageName}",
                    showInUi = true,
                )
                return if (supported) {
                    SessionResult.RESULT_SUCCESS
                } else {
                    SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
            }
        })
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity)
            .build()
        addSession(librarySession)
        activeInstance = this
        assistantPlayer.setAssistantState(active = false, status = "休眠中")
        publishUnifiedNotification()
        DiagLog.i("media3.service.ready", "session=$SESSION_ID", showInUi = true)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = librarySession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_UPDATE_STATE) {
            val status = intent.getStringExtra(EXTRA_STATUS) ?: "休眠中"
            currentActive = intent.getBooleanExtra(EXTRA_ACTIVE, false)
            currentStatus = status
            assistantPlayer.setAssistantState(
                active = currentActive,
                status = status,
            )
            intent.getStringExtra(EXTRA_TITLE)?.let { assistantPlayer.setNowPlaying(it, status) }
            publishUnifiedNotification()
        }
        return result
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // VoiceAgentService owns the foreground-service lifecycle; both services share this card.
        publishUnifiedNotification()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        librarySession.release()
        assistantPlayer.release()
        DiagLog.i("media3.service.destroyed")
        super.onDestroy()
    }

    private fun publishUnifiedNotification() {
        getSystemService(NotificationManager::class.java).notify(
            AssistantNotificationContract.NOTIFICATION_ID,
            buildUnifiedNotification(currentActive, currentStatus),
        )
    }

    private fun buildUnifiedNotification(active: Boolean, status: String): Notification {
        val sessionActivity = PendingIntent.getActivity(
            this,
            12,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val controlIntent = Intent(this, VoiceAgentService::class.java)
            .setAction(if (active) VoiceAgentService.ACTION_SLEEP else VoiceAgentService.ACTION_WAKE)
        val controlAction = PendingIntent.getService(
            this,
            if (active) 42 else 41,
            controlIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val actionIcon = if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val actionText = if (active) "休眠" else "唤醒"

        return NotificationCompat.Builder(this, AssistantNotificationContract.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(sessionActivity)
            .addAction(actionIcon, actionText, controlAction)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(librarySession).setShowActionsInCompactView(0))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private fun currentItem(): MediaItem = MediaItem.Builder()
        .setMediaId(MEDIA_ID)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name))
                .setArtist(getString(R.string.app_name))
                .setAlbumTitle(getString(R.string.app_name))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    companion object {
        @Volatile
        private var activeInstance: MainMediaLibraryService? = null

        private const val SESSION_ID = "hanwo-media"
        private const val ROOT_ID = "hanwo-root"
        private const val MEDIA_ID = "hanwo-session"
        private const val ACTION_UPDATE_STATE = "com.agent.voiceassistant.media.UPDATE_STATE"
        private const val EXTRA_ACTIVE = "active"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_TITLE = "title"

        fun ensureStarted(context: android.content.Context) {
            context.startService(Intent(context, MainMediaLibraryService::class.java))
        }

        fun buildForegroundNotification(active: Boolean, status: String): Notification? {
            val service = activeInstance ?: return null
            return service.buildUnifiedNotification(active, status)
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
