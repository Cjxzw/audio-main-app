package com.agent.voiceassistant.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.voiceassistant.service.VoiceAgentService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainMediaSessionInstrumentedTest {

    @Test
    fun packageUsesMediaOnlyIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = PackageManager.GET_PERMISSIONS.toLong() or PackageManager.GET_SERVICES.toLong()
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags.toInt())
        }

        assertFalse(packageInfo.requestedPermissions.orEmpty().contains("android.permission.MANAGE_OWN_CALLS"))
        assertFalse(packageInfo.services.orEmpty().any { it.name.contains("telecom", ignoreCase = true) })
    }

    @Test
    fun dormantBootstrapPublishesPersistentStatusNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation
                .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                .close()
        }
        VoiceAgentService.bootstrap(context)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var statusNotification = notificationManager.activeNotifications.firstOrNull {
            it.id == AssistantNotificationContract.NOTIFICATION_ID
        }
        while (statusNotification == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
            statusNotification = notificationManager.activeNotifications.firstOrNull {
                it.id == AssistantNotificationContract.NOTIFICATION_ID
            }
        }

        assertTrue(statusNotification != null)
        assertTrue(statusNotification?.notification?.flags?.and(android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue(statusNotification?.notification?.actions?.isNotEmpty() == true)
        assertEquals(
            1,
            notificationManager.activeNotifications.count {
                it.packageName == context.packageName &&
                    it.id == AssistantNotificationContract.NOTIFICATION_ID
            },
        )
        assertFalse(notificationManager.activeNotifications.any { it.id == 1 })
    }

    @Test
    fun prepareFromExternalControllerIsSupportedAndDoesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MainMediaLibraryService.ensureStarted(context)

        val token = SessionToken(context, ComponentName(context, MainMediaLibraryService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                assertTrue(controller.availableCommands.contains(Player.COMMAND_PREPARE))
                assertFalse(controller.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))

                controller.prepare()

                assertEquals(Player.STATE_READY, controller.playbackState)
                assertFalse(controller.playWhenReady)
            } finally {
                controller.release()
            }
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var mediaNotification = notificationManager.activeNotifications.firstOrNull { it.id == 41 }
        while (mediaNotification == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
            mediaNotification = notificationManager.activeNotifications.firstOrNull { it.id == 41 }
        }

        assertTrue(mediaNotification != null)
        assertTrue(mediaNotification?.notification?.actions?.isNotEmpty() == true)
    }
}
