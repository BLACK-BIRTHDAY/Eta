package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity

/**
 * Android 16 (API 36+) / ColorOS 16 官方流体云与状态栏实况胶囊管理器。
 *
 * 遵循 Promoted Ongoing Notification 规范，无需逆向 SystemUI，
 * 直接利用系统级通道在状态栏展示 Agent 思考、代码修改、终端构建测试等实时进度。
 */
internal object EtaLiveUpdateManager {

    private const val CHANNEL_ID = "eta_live_status_v2"
    private const val CHANNEL_NAME = "Eta 实时流体云与状态栏胶囊"
    const val NOTIFICATION_ID = 19999
    private const val SUCCESS_DISMISS_DELAY_MS = 8000L

    const val ACTION_CANCEL_RUN = "io.github.mangi.eta.agent.runtime.CANCEL_RUN"
    const val EXTRA_RUN_ID = "run_id"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var boundService: Service? = null
    private var currentRunId: String? = null
    private var isPromoted = false

    private val autoDismissRunnable = Runnable {
        dismiss()
    }

    fun initChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "在状态栏与锁屏呈现 Agent 代码编写、任务执行与终端编译进度"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 在前台服务启动时建立流体云胶囊
     */
    @Synchronized
    fun start(service: Service, runId: String, initialPrompt: String) {
        boundService = service
        currentRunId = runId
        mainHandler.removeCallbacks(autoDismissRunnable)
        initChannel(service)

        val shortText = "🧠 构思"
        val detail = initialPrompt.ifBlank { "正在执行任务..." }
        val notification = buildNotification(service, runId, shortText, detail, progress = null, isOngoing = true)

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
            isPromoted = true
            AndroidAgentLogger.info("EtaLiveUpdateManager: startForeground with Promoted Ongoing Capsule")
        } catch (t: Throwable) {
            AndroidAgentLogger.error("EtaLiveUpdateManager: Failed to start foreground capsule: ${t.message}")
        }
    }

    /**
     * 更新当前胶囊状态（受节流器保护，异步非阻塞调用）
     */
    @Synchronized
    fun update(runId: String, shortText: String, detailText: String, progress: Int? = null) {
        if (currentRunId != null && currentRunId != runId) return
        val service = boundService ?: return
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val notification = buildNotification(service, runId, shortText, detailText, progress, isOngoing = true)
        runCatching {
            nm.notify(NOTIFICATION_ID, notification)
        }.onFailure {
            AndroidAgentLogger.warn("EtaLiveUpdateManager: update notify failed: ${it.message}")
        }
    }

    /**
     * 任务完成或失败时的终态流转
     */
    @Synchronized
    fun finish(runId: String, success: Boolean, summary: String) {
        if (currentRunId != null && currentRunId != runId) return
        val service = boundService ?: return
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val shortText = if (success) "✅ 完成" else "⚠️ 异常"
        val detail = if (summary.isNotBlank()) summary else (if (success) "任务已完成" else "任务中断")
        val notification = buildNotification(service, runId, shortText, detail, progress = null, isOngoing = !success)

        runCatching {
            nm.notify(NOTIFICATION_ID, notification)
        }

        if (success) {
            // 成功时根据 Q3 决策，8 秒后自动优雅收回
            mainHandler.removeCallbacks(autoDismissRunnable)
            mainHandler.postDelayed(autoDismissRunnable, SUCCESS_DISMISS_DELAY_MS)
        }
    }

    /**
     * 主动销毁胶囊并退出前台
     */
    @Synchronized
    fun dismiss() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        val service = boundService
        if (service != null && isPromoted) {
            runCatching {
                service.stopForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure {
                runCatching { service.stopForeground(true) }
            }
        }
        val context = service ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
        isPromoted = false
        currentRunId = null
    }

    private fun buildNotification(
        context: Context,
        runId: String,
        shortText: String,
        detailText: String,
        progress: Int?,
        isOngoing: Boolean
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("💻 Eta Coding Agent")
            .setContentText(detailText)
            .setSubText("Eta Agent")
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(createClickPendingIntent(context))

        if (isOngoing) {
            builder.addAction(createCancelAction(context, runId))
        }

        // Android 16 (API 36+) 官方原生 Promoted Ongoing 与实时胶囊规范
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(shortText)

            if (progress != null) {
                val style = Notification.ProgressStyle()
                    .setProgress(progress.coerceIn(0, 100))
                    .setStyledByProgress(true)
                builder.setStyle(style)
            }
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(detailText))
        }

        return builder.build()
    }

    /**
     * 点击流体云胶囊时，以 ColorOS 自由小窗（Freeform Floating Window）模式优雅弹出 Eta
     */
    private fun createClickPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // ColorOS 自由悬浮小窗意图参数
            putExtra("android.activity.windowingMode", 5) // WINDOWING_MODE_FREEFORM
            putExtra("com.oplus.intent.extra.WINDOW_MODE", 100)
            putExtra("oplus_freeform_window", true)
        }
        return PendingIntent.getActivity(
            context,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 胶囊展开卡片上的“终止”按钮 Action
     */
    private fun createCancelAction(context: Context, runId: String): Notification.Action {
        val intent = Intent(ACTION_CANCEL_RUN).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_RUN_ID, runId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
            "终止",
            pi
        ).build()
    }
}
