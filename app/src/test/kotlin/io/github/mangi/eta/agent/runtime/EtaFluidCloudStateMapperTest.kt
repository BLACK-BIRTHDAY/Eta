package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaFluidCloudStateMapperTest {

    @Before
    fun setUp() {
        EtaFluidCloudStateMapper.reset()
    }

    @Test
    fun formatToolTextsHandlesPresentToUser() {
        val (short, detail) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "present_to_user",
            args = "{\"reason\":\"到达支付页\"}",
            command = null,
        )
        assertEquals("🖥️ 呈递", short)
        assertEquals("正在将虚拟屏画面移交主屏...", detail)

        // Also test default argument overload
        val (shortDefault, detailDefault) = EtaFluidCloudStateMapper.formatToolTexts("present_to_user")
        assertEquals("🖥️ 呈递", shortDefault)
        assertEquals("正在将虚拟屏画面移交主屏...", detailDefault)
    }

    @Test
    fun formatToolTextsHandlesLaunchApp() {
        val (short, detail) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "launch_app",
            args = "{\"package_name\":\"com.tencent.mm\",\"display\":\"virtual\"}",
            command = null,
        )
        assertEquals("🚀 启动", short)
        assertEquals("正在启动应用...", detail)
    }

    @Test
    fun formatToolTextsHandlesOpenUri() {
        val (short, detail) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "open_uri",
            args = "{\"uri\":\"https://example.com\"}",
            command = null,
        )
        assertEquals("🔗 打开", short)
        assertEquals("正在打开链接...", detail)
    }

    @Test
    fun formatToolTextsHandlesEditAndWrite() {
        val (short1, detail1) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Edit",
            args = "{\"file_path\":\"/path/to/Main.kt\"}",
            command = null,
        )
        assertEquals("📝 写入", short1)
        assertEquals("正在写入: Main.kt (第 1 个文件)", detail1)

        val (short2, detail2) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Write",
            args = "{\"path\":\"/path/to/Other.kt\"}",
            command = null,
        )
        assertEquals("📝 写入", short2)
        assertEquals("正在写入: Other.kt (第 2 个文件)", detail2)

        // Without file path
        EtaFluidCloudStateMapper.reset()
        val (shortEmpty, detailEmpty) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Edit",
            args = "",
            command = null,
        )
        assertEquals("📝 编写", shortEmpty)
        assertEquals("正在修改代码 (0)", detailEmpty)
    }

    @Test
    fun formatToolTextsHandlesRead() {
        val (short, detail) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Read",
            args = "{\"file_path\":\"/docs/spec.md\"}",
            command = null,
        )
        assertEquals("📖 读取", short)
        assertEquals("正在读取: spec.md", detail)

        val (shortEmpty, detailEmpty) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Read",
            args = "",
            command = null,
        )
        assertEquals("📖 查看", shortEmpty)
        assertEquals("正在查看文件", detailEmpty)
    }

    @Test
    fun formatToolTextsHandlesBash() {
        val (shortCmd, detailCmd) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Bash",
            args = "",
            command = "git status",
        )
        assertEquals("🔨 gi", shortCmd)
        assertEquals("正在执行命令: git status", detailCmd)

        val (shortArgs, detailArgs) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Bash",
            args = "{\"command\":\"curl https://api.com\"}",
            command = null,
        )
        assertEquals("🔨 cu", shortArgs)
        assertEquals("正在执行命令: curl https://api.com", detailArgs)

        val (shortFallback, detailFallback) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "Bash",
            args = "",
            command = null,
        )
        assertEquals("🔨 ", shortFallback)
        assertEquals("正在执行命令: ", detailFallback)
    }

    @Test
    fun formatToolTextsHandlesGrepAndGlob() {
        val (shortGrep, detailGrep) = EtaFluidCloudStateMapper.formatToolTexts("Grep")
        assertEquals("🔍 搜索", shortGrep)
        assertEquals("正在搜索工程代码库...", detailGrep)

        val (shortGlob, detailGlob) = EtaFluidCloudStateMapper.formatToolTexts("Glob")
        assertEquals("🔍 搜索", shortGlob)
        assertEquals("正在搜索工程代码库...", detailGlob)
    }

    @Test
    fun formatToolTextsHandlesFallbackTools() {
        val (short, detail) = EtaFluidCloudStateMapper.formatToolTexts(
            toolName = "custom_tool",
            args = "{}",
            command = null,
        )
        assertEquals("⚡ 执行", short)
        assertEquals("正在执行工具: custom_tool", detail)
    }
}
