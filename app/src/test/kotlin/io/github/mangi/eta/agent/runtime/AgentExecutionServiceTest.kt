package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class)
class AgentExecutionServiceTest {
    @Test
    @Config(sdk = [33, 36])
    fun agentRunPromotionEndsWhileTerminalSessionRemains() {
        val context = RuntimeEnvironment.getApplication()
        val leaseId = "test:terminal-session"
        assertTrue(AgentExecutionService.acquire(context, leaseId) {})
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning, activeRunCount = 1)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            // AgentExecutionService updates its notification content text with the localized Reasoning status
            // while keeping android.requestPromotedOngoing false to avoid dual-capsule conflict on ColorOS 16 / Android 16
            assertEquals(context.getString(R.string.overlay_reasoning), notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertFalse(notification.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(notification.extras.containsKey("android.shortCriticalText"))

            // When run status is cleared, AgentExecutionService reverts to the task summary text
            AgentExecutionService.updateRunStatus(context, null, activeRunCount = 0)
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
            val expectedSummary = context.resources.getQuantityString(R.plurals.execution_summary, 1, 1)
            assertEquals(expectedSummary, remaining.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertFalse(remaining.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(remaining.extras.containsKey("android.shortCriticalText"))
            assertTrue(remaining.flags and Notification.FLAG_ONGOING_EVENT != 0)

            // Meanwhile, EtaLiveUpdateManager exclusively owns the Promoted Ongoing notification on API 36 when active in the background
            val liveNotification = EtaLiveUpdateManager.buildNotification(
                context = context,
                runId = "test-run",
                shortText = "🧠 构思",
                detailText = "正在执行任务...",
                progress = null,
                isOngoing = true,
            )
            assertEquals(Build.VERSION.SDK_INT >= 36, liveNotification.extras.getBoolean("android.requestPromotedOngoing"))
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals("🧠 构思", liveNotification.extras.getString("android.shortCriticalText"))
            }

            val completedNotification = EtaLiveUpdateManager.buildNotification(
                context = context,
                runId = "test-run",
                shortText = "✅ 完成",
                detailText = "任务已完成",
                progress = null,
                isOngoing = false,
                requestPromoted = false,
            )
            assertFalse(completedNotification.extras.getBoolean("android.requestPromotedOngoing"))
        } finally {
            AgentExecutionService.release(leaseId)
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun foregroundExecutionUsesOnlyServiceTypesSupportedByThePlatform() {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val service = controller.get()
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                service.foregroundServiceType,
            )
        } finally {
            controller.destroy()
        }
    }
}
