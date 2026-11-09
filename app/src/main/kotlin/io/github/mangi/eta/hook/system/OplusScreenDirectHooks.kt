package io.github.mangi.eta.hook.system

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import java.lang.reflect.Method

internal object OplusScreenDirectPolicy {
    const val ACTION_TYPE_KEY = "action_type"
    const val ACTION_TYPE_PRESS = "action_type_press"
    const val PRESS_TYPE_KEY = "press_type"
    const val POINT_COUNT_KEY = "point_count"
    const val MODE_MULTI_PRESSED = 1
    const val MULTI_POINTER_PRESSED = 2

    private val EXCLUDED_TOP_PACKAGES = setOf(
        "com.android.launcher",
        "com.oppo.launcher",
        "com.coloros.recents",
    )

    private val GOOGLE_CONTEXTUAL_SEARCH_MARKERS = listOf(
        "ContextualSearch",
        "Omnient",
        "Lensient",
    )

    enum class Decision {
        PROCEED_ORIGINAL,
        SUPPRESS,
        TRIGGER_CIRCLE_TO_SEARCH,
    }

    fun decide(
        featureEnabled: Boolean,
        gestureSwitchEnabled: Boolean,
        actionType: Int?,
        pointerCount: Int?,
        topActivity: ComponentName?,
    ): Decision {
        if (actionType != MODE_MULTI_PRESSED || pointerCount != MULTI_POINTER_PRESSED) {
            return Decision.PROCEED_ORIGINAL
        }
        if (!featureEnabled || !gestureSwitchEnabled) {
            return Decision.PROCEED_ORIGINAL
        }
        if (isExcludedTopActivity(topActivity)) {
            return Decision.SUPPRESS
        }
        return Decision.TRIGGER_CIRCLE_TO_SEARCH
    }

    fun decideBindIntent(
        featureEnabled: Boolean,
        gestureSwitchEnabled: Boolean,
        intent: android.content.Intent?,
        topActivity: ComponentName?,
    ): Decision {
        if (intent == null) return Decision.PROCEED_ORIGINAL
        val actionType = intent.getStringExtra(ACTION_TYPE_KEY)
        if (actionType != ACTION_TYPE_PRESS) {
            return Decision.PROCEED_ORIGINAL
        }
        return decide(
            featureEnabled = featureEnabled,
            gestureSwitchEnabled = gestureSwitchEnabled,
            actionType = intent.getIntExtra(PRESS_TYPE_KEY, -1),
            pointerCount = intent.getIntExtra(POINT_COUNT_KEY, 0),
            topActivity = topActivity,
        )
    }

    private fun isExcludedTopActivity(topActivity: ComponentName?): Boolean {
        if (topActivity == null) return false
        val packageName = topActivity.packageName
        if (packageName in EXCLUDED_TOP_PACKAGES) return true
        if (packageName == ModuleConfig.GOOGLE_PACKAGE) {
            val className = topActivity.className
            if (GOOGLE_CONTEXTUAL_SEARCH_MARKERS.any { className.contains(it) }) {
                return true
            }
        }
        return false
    }
}

internal object OplusScreenDirectHooks {
    private const val SOURCE = "OplusScreenDirect"
    private const val GESTURE_TRIGGER_SWITCH_KEY = "gesture_trigger_switch"
    private const val LINEAR_MOTOR_SERVICE = "linearmotor"
    private const val TWO_FINGER_HAPTIC_EFFECT_TYPE = 49

    @Volatile
    private var lastHandledUptime = 0L

    @Volatile
    private var oplusActivityManagerInstance: Any? = null

    @Volatile
    private var getTopActivityComponentNameMethod: Method? = null

    @Volatile
    private var cachedWaveformEffect: Any? = null

    @Volatile
    private var linearmotorVibrateMethod: Method? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "OplusScreenDirect")
        return hooks.install {
            val managerClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.OPLUS_SCREEN_DIRECT_MANAGER_CLASS,
            )
            if (managerClass == null) {
                skipped(
                    id = "system.oplus-screen-direct",
                    description = "OplusScreenDirectManager.startDirectService",
                    detail = "当前系统未提供 OplusScreenDirectManager，保留 Activity 兜底",
                )
                return@install
            }

            val contextField = HookSupport.findField(managerClass, "mContext")
            val pointerCountField = HookSupport.findField(managerClass, "mPointerCount")
            val startDirectServiceMethod = HookSupport.findMethod(
                managerClass,
                "startDirectService",
                Int::class.javaPrimitiveType!!,
            )
            if (contextField == null ||
                !Context::class.java.isAssignableFrom(contextField.type) ||
                pointerCountField == null ||
                pointerCountField.type != Int::class.javaPrimitiveType ||
                startDirectServiceMethod == null ||
                startDirectServiceMethod.returnType != Void.TYPE
            ) {
                missing(
                    id = "system.oplus-screen-direct",
                    description = "OplusScreenDirectManager.startDirectService",
                    detail = "OplusScreenDirectManager 字段或方法签名不匹配，保留 Activity 兜底",
                )
                return@install
            }

            HookSupport.findMethod(managerClass, "handleMultiLongTouch")?.let { caller ->
                HookSupport.deoptimize(
                    module,
                    hooks.logger,
                    caller,
                    "OplusScreenDirectManager.handleMultiLongTouch()",
                )
            }

            intercept(
                id = "system.oplus-screen-direct",
                executable = startDirectServiceMethod,
                description = "OplusScreenDirectManager.startDirectService",
            ) { chain ->
                val instance = chain.getThisObject() ?: return@intercept chain.proceed()
                val actionType = chain.getArg(0) as? Int
                val pointerCount = runCatching {
                    pointerCountField.getInt(instance)
                }.getOrNull()
                val context = runCatching {
                    contextField.get(instance) as? Context
                }.getOrNull() ?: return@intercept chain.proceed()

                val decision = OplusScreenDirectPolicy.decide(
                    featureEnabled = Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH),
                    gestureSwitchEnabled = isGestureTriggerSwitchEnabled(context),
                    actionType = actionType,
                    pointerCount = pointerCount,
                    topActivity = resolveTopActivityComponent(classLoader),
                )

                when (decision) {
                    OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL -> chain.proceed()
                    OplusScreenDirectPolicy.Decision.SUPPRESS -> {
                        hooks.logger.debug { "$SOURCE: 顶层界面位于桌面或一圈即搜，跳过双指识屏" }
                        null
                    }
                    OplusScreenDirectPolicy.Decision.TRIGGER_CIRCLE_TO_SEARCH -> {
                        if (tryStartCircleToSearch(context, classLoader, hooks.logger)) {
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        }
    }

    private fun tryStartCircleToSearch(
        context: Context,
        classLoader: ClassLoader,
        logger: ModuleLogger,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandledUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$SOURCE: 命中去重窗口，吞掉重复双指长按" }
            return true
        }

        if (!CircleToSearchInvoker.isAvailable(context, logger, SOURCE, "回退小布双指识屏")) {
            return false
        }

        if (!CircleToSearchInvoker.trigger(logger, "$SOURCE 双指长按")) {
            return false
        }

        lastHandledUptime = now
        performOriginalTwoFingerHaptic(context, classLoader, logger)
        logger.debug { "$SOURCE: 已在 system_server 直接转发 Circle to Search，跳过小布识屏进程" }
        return true
    }

    private fun isGestureTriggerSwitchEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(context.contentResolver, GESTURE_TRIGGER_SWITCH_KEY, 1) == 1
        }.getOrDefault(true)

    private fun resolveTopActivityComponent(classLoader: ClassLoader): ComponentName? =
        runCatching {
            val manager = oplusActivityManagerInstance ?: HookSupport.findClassOrNull(
                classLoader,
                "android.app.OplusActivityManager",
            )?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance()
                ?.also { oplusActivityManagerInstance = it }
                ?: return null
            val method = getTopActivityComponentNameMethod ?: HookSupport.findMethod(
                manager.javaClass,
                "getTopActivityComponentName",
            )?.also { getTopActivityComponentNameMethod = it }
                ?: return null
            method.invoke(manager) as? ComponentName
        }.getOrNull()

    private fun performOriginalTwoFingerHaptic(
        context: Context,
        classLoader: ClassLoader,
        logger: ModuleLogger,
    ) {
        val invoked = runCatching {
            val vibrator = context.getSystemService(LINEAR_MOTOR_SERVICE) ?: return@runCatching false
            val effect = cachedWaveformEffect ?: buildWaveformEffect(classLoader)
                ?.also { cachedWaveformEffect = it }
                ?: return@runCatching false
            val vibrateMethod = linearmotorVibrateMethod ?: HookSupport.findMethod(
                vibrator.javaClass,
                "vibrate",
                effect.javaClass,
            )?.also { linearmotorVibrateMethod = it }
                ?: return@runCatching false
            vibrateMethod.invoke(vibrator, effect)
            true
        }.getOrDefault(false)

        if (!invoked) {
            logger.debug { "$SOURCE: 跳过双指长按线性马达震感补发" }
        }
    }

    private fun buildWaveformEffect(classLoader: ClassLoader): Any? =
        runCatching {
            val builderClass = HookSupport.findClassOrNull(
                classLoader,
                "com.oplus.os.WaveformEffect\$Builder",
            ) ?: return null
            val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            HookSupport.findMethod(
                builderClass,
                "setEffectType",
                Int::class.javaPrimitiveType!!,
            )?.invoke(builder, TWO_FINGER_HAPTIC_EFFECT_TYPE)
            HookSupport.findMethod(
                builderClass,
                "setAsynchronous",
                Boolean::class.javaPrimitiveType!!,
            )?.invoke(builder, true)
            HookSupport.findMethod(builderClass, "build")?.invoke(builder)
        }.getOrNull()
}
