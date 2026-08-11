package fuck.andes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("fuck.andes", "fuck.andes"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("fuck.andes:voice", "fuck.andes"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("fuck.andes:voice_session", "fuck.andes"))
    }
}
