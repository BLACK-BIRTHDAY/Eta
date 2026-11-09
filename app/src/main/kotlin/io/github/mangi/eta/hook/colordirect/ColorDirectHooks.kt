package io.github.mangi.eta.hook.colordirect

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.DexKitTargets
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.system.CircleToSearchInvoker
import io.github.mangi.eta.hook.system.OplusScreenDirectPolicy

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.provider.Settings
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File
import java.lang.ref.Reference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object ColorDirectHooks {
    private const val SOURCE = "ColorDirectActivity"
    private const val DIRECT_CLIENT_CLASS =
        "com.coloros.colordirectservice.ColorAppServicesManagerClient"
    private const val DIRECT_GESTURE_STUB_CLASS =
        "com.coloros.colordirectservice.ColorAppServicesManagerClient\$IScreenDirectGestureInterfaceStub"
    private const val DIRECT_GESTURE_CALLBACK_CLASS =
        "com.oplus.gesture.screendirect.IScreenDirectGestureCallBack"
    private const val ABSTRACT_DIRECT_SERVICE_CLASS =
        "com.coloros.colordirectservice.core.AbstractDirectService"
    private const val COMMON_UTILS_CLASS =
        "com.coloros.direct.setting.util.CommonUtils"
    private const val DIRECT_VIBRATOR_UTILS_CLASS =
        "com.coloros.direct.setting.util.DirectVibratorUtils"
    private const val GESTURE_TRIGGER_SWITCH_KEY = "gesture_trigger_switch"
    private const val DIRECT_EXT_FINGER_TRIGGER = "fingerTrigger"
    private const val DIRECT_EXT_TOUCH_INFO = "touchInfo"
    private const val DIRECT_EXT_FINGER_COUNT = "fingerCount"

    @Volatile
    private var lastHandledUptime = 0L

    @Volatile
    private var getTopComponentNameMethod: Method? = null

    @Volatile
    private var vibrateMethod: Method? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ColorDirect")
        return hooks.install {
            hookGestureBindFastPath(hooks, classLoader)
            hookAbstractDirectServiceUnbind(hooks, classLoader)
            hookCollectInfoActivity(
                hooks, classLoader, applicationInfo, module.moduleApplicationInfo,
            )
        }
    }

    private fun hookGestureBindFastPath(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val logger = hooks.logger
        val clientClass = HookSupport.findClassOrNull(classLoader, DIRECT_CLIENT_CLASS)
        val stubClass = HookSupport.findClassOrNull(classLoader, DIRECT_GESTURE_STUB_CLASS)
        val callbackClass = HookSupport.findClassOrNull(classLoader, DIRECT_GESTURE_CALLBACK_CLASS)
        if (clientClass == null || stubClass == null || callbackClass == null) {
            hooks.skipped(
                id = "colordirect.gesture-bind-fastpath",
                description = "IScreenDirectGestureInterfaceStub.registerGestureCallBack",
                detail = "未找到 ColorAppServicesManagerClient 手势回调入口，保留 CollectInfoActivity 拦截",
            )
            return
        }

        val registerMethod = HookSupport.findMethod(
            stubClass,
            "registerGestureCallBack",
            callbackClass,
        )
        val cancelMethod = HookSupport.findMethod(callbackClass, "onCancelGestureSuccess")
        val serviceRefField = HookSupport.findField(stubClass, "mService")
        val bindIntentField = resolveUniqueIntentField(clientClass)

        if (registerMethod == null ||
            cancelMethod == null ||
            serviceRefField == null ||
            !Reference::class.java.isAssignableFrom(serviceRefField.type) ||
            bindIntentField == null
        ) {
            hooks.missing(
                id = "colordirect.gesture-bind-fastpath",
                description = "IScreenDirectGestureInterfaceStub.registerGestureCallBack",
                detail = "手势回调快速入口签名不完整，保留 CollectInfoActivity 拦截",
            )
            return
        }

        hooks.intercept(
            id = "colordirect.gesture-bind-fastpath",
            executable = registerMethod,
            description = "IScreenDirectGestureInterfaceStub.registerGestureCallBack",
        ) { chain ->
            val stub = chain.getThisObject() ?: return@intercept chain.proceed()
            val callback = chain.getArg(0) ?: return@intercept chain.proceed()
            val service = runCatching {
                (serviceRefField.get(stub) as? Reference<*>)?.get() as? Service
            }.getOrNull() ?: return@intercept chain.proceed()
            val bindIntent = runCatching {
                bindIntentField.get(service) as? Intent
            }.getOrNull()

            val decision = OplusScreenDirectPolicy.decideBindIntent(
                featureEnabled = Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH),
                gestureSwitchEnabled = isGestureTriggerSwitchEnabled(service),
                intent = bindIntent,
                topActivity = resolveTopComponent(classLoader),
            )

            when (decision) {
                OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL -> chain.proceed()
                OplusScreenDirectPolicy.Decision.SUPPRESS -> {
                    scheduleCancelDirectGestureSession(cancelMethod, callback)
                    null
                }
                OplusScreenDirectPolicy.Decision.TRIGGER_CIRCLE_TO_SEARCH -> {
                    if (tryStartCircleToSearch(service, logger, classLoader = classLoader, performHaptic = true)) {
                        scheduleCancelDirectGestureSession(cancelMethod, callback)
                        null
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun hookAbstractDirectServiceUnbind(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val abstractServiceClass = HookSupport.findClassOrNull(classLoader, ABSTRACT_DIRECT_SERVICE_CLASS)
        val onUnbindMethod = abstractServiceClass?.let {
            HookSupport.findMethod(it, "onUnbind", Intent::class.java)
        }
        if (onUnbindMethod == null || onUnbindMethod.returnType != Boolean::class.javaPrimitiveType) {
            hooks.skipped(
                id = "colordirect.keep-process-warm",
                description = "AbstractDirectService.onUnbind",
                detail = "未找到 AbstractDirectService.onUnbind(Intent)",
            )
            return
        }

        hooks.intercept(
            id = "colordirect.keep-process-warm",
            executable = onUnbindMethod,
            description = "AbstractDirectService.onUnbind",
        ) { chain ->
            if (!Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val intent = chain.getArg(0) as? Intent
            val isDirectManagerBind = intent?.component?.className == DIRECT_CLIENT_CLASS ||
                intent?.action == DIRECT_CLIENT_CLASS
            if (isDirectManagerBind) {
                // AbstractDirectService.onUnbind 会在解绑时置位自杀标记，导致 onDestroy 调用 Process.killProcess 强杀自身进程。
                // 双指一圈即搜开启时直接返回 false（与 super.onUnbind 一致），保留进程热驻留。
                false
            } else {
                chain.proceed()
            }
        }
    }

    private fun resolveUniqueIntentField(clazz: Class<*>): Field? =
        clazz.declaredFields.singleOrNull { field ->
            !Modifier.isStatic(field.modifiers) && field.type == Intent::class.java
        }?.apply { isAccessible = true }

    private fun isGestureTriggerSwitchEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(context.contentResolver, GESTURE_TRIGGER_SWITCH_KEY, 1) == 1
        }.getOrDefault(true)

    private fun resolveTopComponent(classLoader: ClassLoader): ComponentName? =
        runCatching {
            val method = getTopComponentNameMethod ?: HookSupport.findClassOrNull(
                classLoader,
                COMMON_UTILS_CLASS,
            )?.let { HookSupport.findMethod(it, "getTopComponentName") }
                ?.also { getTopComponentNameMethod = it }
                ?: return null
            method.invoke(null) as? ComponentName
        }.getOrNull()

    private fun performDirectHaptic(classLoader: ClassLoader, context: Context) {
        runCatching {
            val method = vibrateMethod ?: HookSupport.findClassOrNull(
                classLoader,
                DIRECT_VIBRATOR_UTILS_CLASS,
            )?.let { HookSupport.findMethod(it, "vibrate", Context::class.java) }
                ?.also { vibrateMethod = it }
                ?: return
            method.invoke(null, context)
        }
    }

    private fun scheduleCancelDirectGestureSession(cancelMethod: Method, callback: Any) {
        val posted = Handler(Looper.getMainLooper()).post {
            runCatching { cancelMethod.invoke(callback) }
        }
        if (!posted) {
            runCatching { cancelMethod.invoke(callback) }
        }
    }

    private fun hookCollectInfoActivity(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        applicationInfo: ApplicationInfo,
        moduleApplicationInfo: ApplicationInfo,
    ) {
        val logger = hooks.logger
        val activityClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_COLLECT_ACTIVITY_CLASS
        )
        if (activityClass == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity Intent 处理入口",
                detail = "未找到 CollectInfoActivity，无法接管双指识屏 Activity 入口"
            )
            return
        }
        val startInfoClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_START_INFO_CLASS
        )

        val method = DexKitTargets(
            apkPath = applicationInfo.sourceDir,
            classLoader = classLoader,
            logger = logger,
            cacheDirectory = applicationInfo.dataDir?.let { File(it, "cache/eta-dexkit") },
            moduleNativeLibraryDirectory = moduleApplicationInfo.nativeLibraryDir,
            moduleApkPath = moduleApplicationInfo.sourceDir,
        ).use { targets ->
            ColorDirectTargets.findCollectIntent(targets, activityClass)
        }
        if (method == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity Intent 处理入口",
                detail = "未唯一定位 CollectInfoActivity Intent 处理方法，保留系统双指识屏"
            )
            return
        }

        hooks.intercept(
            id = "colordirect.collect-info",
            executable = method,
            description = "CollectInfoActivity Intent 处理入口"
        ) { chain ->
            // 开关关闭则走原双指识屏逻辑。
            if (!Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val activity = chain.getThisObject() as? Activity
            val intent = chain.getArg(0) as? Intent
            if (activity == null || !isDoubleFingerCollectIntent(intent, startInfoClass)) {
                return@intercept chain.proceed()
            }

            if (tryStartCircleToSearch(activity, logger)) {
                finishColorDirectActivity(activity)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun tryStartCircleToSearch(
        context: Context,
        logger: ModuleLogger,
        classLoader: ClassLoader? = null,
        performHaptic: Boolean = false,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandledUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$SOURCE: 命中去重窗口，吞掉重复双指识屏" }
            return true
        }

        if (!CircleToSearchInvoker.isAvailable(context, logger, SOURCE, "回退小布双指识屏")) {
            return false
        }

        if (!CircleToSearchInvoker.trigger(logger, "$SOURCE 双指识屏")) {
            return false
        }

        lastHandledUptime = now
        if (performHaptic && classLoader != null) {
            performDirectHaptic(classLoader, context)
        }
        logger.debug { "$SOURCE: 命中双指识屏，已转发 Circle to Search" }
        return true
    }

    @Suppress("DEPRECATION")
    private fun finishColorDirectActivity(activity: Activity) {
        activity.finishAndRemoveTask()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }

    private fun isDoubleFingerCollectIntent(intent: Intent?, startInfoClass: Class<*>?): Boolean {
        val directExt = resolveDirectExt(intent, startInfoClass) ?: return false
        return runCatching {
            val json = JSONObject(directExt)
            json.optBoolean(DIRECT_EXT_FINGER_TRIGGER, false) &&
                json.optJSONObject(DIRECT_EXT_TOUCH_INFO)
                    ?.optInt(DIRECT_EXT_FINGER_COUNT, 0) == ModuleConfig.COLOR_DIRECT_DOUBLE_FINGER_COUNT
        }.getOrDefault(false)
    }

    private fun resolveDirectExt(intent: Intent?, startInfoClass: Class<*>?): String? {
        if (intent == null) return null
        intent.getStringExtra(ModuleConfig.COLOR_DIRECT_EXTRA_DIRECT_EXT)?.let { return it }
        val startInfo = resolveStartInfo(intent, startInfoClass) ?: return null
        return HookSupport.invokeNoArgs(startInfo, "getDirectExt") as? String
    }

    @Suppress("DEPRECATION")
    private fun resolveStartInfo(intent: Intent, startInfoClass: Class<*>?): Any? {
        if (startInfoClass != null && Parcelable::class.java.isAssignableFrom(startInfoClass)) {
            @Suppress("UNCHECKED_CAST")
            val typedStartInfoClass = startInfoClass as Class<Parcelable>
            intent.getParcelableExtra(
                ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO,
                typedStartInfoClass
            )?.let { return it }
        }

        return runCatching {
            intent.extras?.get(ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO)
        }.getOrNull()
    }
}
