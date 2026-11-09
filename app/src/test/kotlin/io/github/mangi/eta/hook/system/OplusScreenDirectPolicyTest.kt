package io.github.mangi.eta.hook.system

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OplusScreenDirectPolicyTest {

    @Test
    fun `双指长按且开关开启时触发一圈即搜`() {
        val decision = OplusScreenDirectPolicy.decide(
            featureEnabled = true,
            gestureSwitchEnabled = true,
            actionType = OplusScreenDirectPolicy.MODE_MULTI_PRESSED,
            pointerCount = OplusScreenDirectPolicy.MULTI_POINTER_PRESSED,
            topActivity = ComponentName("io.github.mangi.eta", "io.github.mangi.eta.ui.MainActivity"),
        )
        assertEquals(OplusScreenDirectPolicy.Decision.TRIGGER_CIRCLE_TO_SEARCH, decision)
    }

    @Test
    fun `顶层窗口未知时仍允许双指长按触发一圈即搜`() {
        val decision = OplusScreenDirectPolicy.decide(
            featureEnabled = true,
            gestureSwitchEnabled = true,
            actionType = OplusScreenDirectPolicy.MODE_MULTI_PRESSED,
            pointerCount = OplusScreenDirectPolicy.MULTI_POINTER_PRESSED,
            topActivity = null,
        )
        assertEquals(OplusScreenDirectPolicy.Decision.TRIGGER_CIRCLE_TO_SEARCH, decision)
    }

    @Test
    fun `开关关闭或系统手势总开关关闭时放行原逻辑`() {
        assertEquals(
            OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL,
            OplusScreenDirectPolicy.decide(
                featureEnabled = false,
                gestureSwitchEnabled = true,
                actionType = OplusScreenDirectPolicy.MODE_MULTI_PRESSED,
                pointerCount = OplusScreenDirectPolicy.MULTI_POINTER_PRESSED,
                topActivity = ComponentName("io.github.mangi.eta", "io.github.mangi.eta.ui.MainActivity"),
            )
        )
        assertEquals(
            OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL,
            OplusScreenDirectPolicy.decide(
                featureEnabled = true,
                gestureSwitchEnabled = false,
                actionType = OplusScreenDirectPolicy.MODE_MULTI_PRESSED,
                pointerCount = OplusScreenDirectPolicy.MULTI_POINTER_PRESSED,
                topActivity = ComponentName("io.github.mangi.eta", "io.github.mangi.eta.ui.MainActivity"),
            )
        )
    }

    @Test
    fun `单指长按或大面积长按或非双指触控放行原逻辑`() {
        for (actionType in listOf(0, 3, -1, null)) {
            assertEquals(
                OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL,
                OplusScreenDirectPolicy.decide(
                    featureEnabled = true,
                    gestureSwitchEnabled = true,
                    actionType = actionType,
                    pointerCount = OplusScreenDirectPolicy.MULTI_POINTER_PRESSED,
                    topActivity = ComponentName("io.github.mangi.eta", "io.github.mangi.eta.ui.MainActivity"),
                )
            )
        }
        for (pointerCount in listOf(1, 3, 0)) {
            assertEquals(
                OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL,
                OplusScreenDirectPolicy.decide(
                    featureEnabled = true,
                    gestureSwitchEnabled = true,
                    actionType = OplusScreenDirectPolicy.MODE_MULTI_PRESSED,
                    pointerCount = pointerCount,
                    topActivity = ComponentName("io.github.mangi.eta", "io.github.mangi.eta.ui.MainActivity"),
                )
            )
        }
    }

    @Test
    fun `桌面或最近任务或一圈即搜自身位于前台时静默忽略不拉起识屏进程`() {
        val suppressedTargets = listOf(
            ComponentName("com.android.launcher", "com.android.launcher.Launcher"),
            ComponentName("com.oppo.launcher", "com.oppo.launcher.Launcher"),
            ComponentName("com.coloros.recents", "com.coloros.recents.RecentsActivity"),
            ComponentName(
                ModuleConfig.GOOGLE_PACKAGE,
                "com.google.android.apps.lens.ContextualSearchEntrypoint"
            ),
            ComponentName(
                ModuleConfig.GOOGLE_PACKAGE,
                "com.google.android.apps.search.omnient.host.activity.OmnientActivity"
            ),
            ComponentName(
                ModuleConfig.GOOGLE_PACKAGE,
                "com.google.android.apps.search.lens.LensientActivity"
            ),
        )
        for (topActivity in suppressedTargets) {
            assertEquals(
                "Expected SUPPRESS for $topActivity",
                OplusScreenDirectPolicy.Decision.SUPPRESS,
                OplusScreenDirectPolicy.decide(
                    featureEnabled = true,
                    gestureSwitchEnabled = true,
                    actionType = OplusScreenDirectPolicy.MODE_MULTI_PRESSED,
                    pointerCount = OplusScreenDirectPolicy.MULTI_POINTER_PRESSED,
                    topActivity = topActivity,
                )
            )
        }
    }

    @Test
    fun `ColorAppServicesManagerClient绑定Intent仅在双指按压时命中快速通道`() {
        val twoFingerIntent = Intent()
            .putExtra(OplusScreenDirectPolicy.ACTION_TYPE_KEY, OplusScreenDirectPolicy.ACTION_TYPE_PRESS)
            .putExtra(OplusScreenDirectPolicy.PRESS_TYPE_KEY, OplusScreenDirectPolicy.MODE_MULTI_PRESSED)
            .putExtra(OplusScreenDirectPolicy.POINT_COUNT_KEY, OplusScreenDirectPolicy.MULTI_POINTER_PRESSED)

        assertEquals(
            OplusScreenDirectPolicy.Decision.TRIGGER_CIRCLE_TO_SEARCH,
            OplusScreenDirectPolicy.decideBindIntent(
                featureEnabled = true,
                gestureSwitchEnabled = true,
                intent = twoFingerIntent,
                topActivity = ComponentName("io.github.mangi.eta", "io.github.mangi.eta.ui.MainActivity"),
            )
        )

        val singleFingerIntent = Intent(twoFingerIntent)
            .putExtra(OplusScreenDirectPolicy.PRESS_TYPE_KEY, 0)
            .putExtra(OplusScreenDirectPolicy.POINT_COUNT_KEY, 1)

        assertEquals(
            OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL,
            OplusScreenDirectPolicy.decideBindIntent(
                featureEnabled = true,
                gestureSwitchEnabled = true,
                intent = singleFingerIntent,
                topActivity = ComponentName("io.github.mangi.eta", "io.github.mangi.eta.ui.MainActivity"),
            )
        )
        assertEquals(
            OplusScreenDirectPolicy.Decision.PROCEED_ORIGINAL,
            OplusScreenDirectPolicy.decideBindIntent(
                featureEnabled = true,
                gestureSwitchEnabled = true,
                intent = null,
                topActivity = null,
            )
        )
    }
}
