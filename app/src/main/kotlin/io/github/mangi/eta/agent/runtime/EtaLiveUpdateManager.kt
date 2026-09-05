package io.github.mangi.eta.agent.runtime

import android.app.Activity
import android.app.Application
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity

/**
 * Android 16 (API 36+) / ColorOS 16 官方流体云与状态栏实况胶囊管理器。
 *
 * 遵循 Promoted Ongoing Notification 规范，无需逆向 SystemUI。
 * 智能感知前后台：当用户在前台看着对话框时，保持完全静默（零横幅、零通知打扰）；
 * 仅当用户切换到后台（桌面、其他应用、锁屏）时，才升起流体云状态栏胶囊与锁屏实时卡片。
 */
internal object EtaLiveUpdateManager : Application.ActivityLifecycleCallbacks {

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

    // 前后台感知状态
    @Volatile
    var isAppInForeground = false
        private set
    private var startedActivityCount = 0

    // 缓存最新进度，以便从前台切到后台时瞬间恢复流体云
    private var latestShortText = "🧠 构思"
    private var latestDetailText = "正在执行任务..."
    private var latestProgress: Int? = null

    private val autoDismissRunnable = Runnable {
        dismiss()
    }

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        initChannel(application)
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

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++
        checkForegroundState(startedActivityCount > 0)
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = maxOf(0, startedActivityCount - 1)
        checkForegroundState(startedActivityCount > 0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    @Synchronized
    private fun checkForegroundState(foreground: Boolean) {
        if (isAppInForeground == foreground) return
        isAppInForeground = foreground

        if (foreground) {
            // 用户回到前台看着对话框：立即撤销通知栏与胶囊，保持前台视觉纯净
            hideNotificationOnly()
        } else {
            // 用户切到后台（桌面、其他应用、锁屏）：若当前有运行任务，立即升起流体云胶囊
            if (currentRunId != null) {
                showNotification(latestShortText, latestDetailText, latestProgress)
            }
        }
    }

    /**
     * 在前台服务启动时注册任务
     */
    @Synchronized
    fun start(service: Service, runId: String, initialPrompt: String) {
        boundService = service
        currentRunId = runId
        mainHandler.removeCallbacks(autoDismissRunnable)
        initChannel(service)

        latestShortText = "🧠 构思"
        latestDetailText = initialPrompt.ifBlank { "正在执行任务..." }
        latestProgress = null

        // 仅在用户处于后台时才挂载流体云通知；若用户正在看对话框，保持完全静默
        if (!isAppInForeground) {
            showNotification(latestShortText, latestDetailText, latestProgress)
        } else {
            AndroidAgentLogger.debug { "EtaLiveUpdateManager: App in foreground, suppressing live notification" }
        }
    }

    /**
     * 更新当前胶囊状态（受节流器保护，异步非阻塞调用）
     */
    @Synchronized
    fun update(runId: String, shortText: String, detailText: String, progress: Int? = null) {
        if (currentRunId != null && currentRunId != runId) return
        latestShortText = shortText
        latestDetailText = detailText
        latestProgress = progress

        // 若用户在前台看着对话框，绝不发送通知打扰
        if (isAppInForeground) return

        showNotification(shortText, detailText, progress)
    }

    /**
     * 任务完成或失败时的终态流转
     */
    @Synchronized
    fun finish(runId: String, success: Boolean, summary: String) {
        if (currentRunId != null && currentRunId != runId) return

        // 若用户在前台看着对话框，用户已直接看到界面回答，彻底静默并不留任何通知
        if (isAppInForeground) {
            dismiss()
            return
        }

        // 若用户在后台，弹出终态通知：成功则 8 秒后自动收回，失败则常驻
        val service = boundService ?: return
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val shortText = if (success) "✅ 完成" else "⚠️ 异常"
        val detail = if (summary.isNotBlank()) summary else (if (success) "任务已完成" else "任务中断")
        val notification = buildNotification(service, runId, shortText, detail, progress = null, isOngoing = !success)

        runCatching {
            nm.notify(NOTIFICATION_ID, notification)
        }

        if (success) {
            mainHandler.removeCallbacks(autoDismissRunnable)
            mainHandler.postDelayed(autoDismissRunnable, SUCCESS_DISMISS_DELAY_MS)
        }
    }

    private fun showNotification(shortText: String, detailText: String, progress: Int?) {
        val service = boundService ?: return
        val runId = currentRunId ?: return
        val notification = buildNotification(service, runId, shortText, detailText, progress, isOngoing = true)

        try {
            if (!isPromoted) {
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
            } else {
                val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            runCatching { nm?.notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun hideNotificationOnly() {
        val service = boundService
        if (service != null && isPromoted) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 34) {
                    service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    service.stopForeground(true)
                }
            }
            isPromoted = false
        }
        val nm = service?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIFICATION_ID)
    }

    /**
     * 主动销毁胶囊并退出前台
     */
    @Synchronized
    fun dismiss() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        hideNotificationOnly()
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
