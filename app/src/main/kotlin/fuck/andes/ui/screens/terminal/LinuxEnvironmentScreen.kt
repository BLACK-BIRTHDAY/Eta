package fuck.andes.ui.screens.terminal

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.agent.terminal.AlpineEnvironmentInstaller
import fuck.andes.agent.terminal.AlpineEnvironmentHealth
import fuck.andes.agent.terminal.AlpineApkAnalysisInstaller
import fuck.andes.agent.terminal.AlpineEnvironmentState
import fuck.andes.agent.terminal.AlpineEnvironmentStatus
import fuck.andes.agent.terminal.AlpineInstallProgress
import fuck.andes.agent.terminal.AlpineInstallResult
import fuck.andes.agent.terminal.ApkAnalysisInstallProgress
import fuck.andes.agent.terminal.ApkAnalysisInstallResult
import fuck.andes.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val installer = remember(context.applicationContext) {
        AlpineEnvironmentInstaller(context.applicationContext)
    }
    val apkAnalysisInstaller = remember(context.applicationContext) {
        AlpineApkAnalysisInstaller(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf(installer.status()) }
    var installing by remember { mutableStateOf(false) }
    var checkingHealth by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var health by remember { mutableStateOf<AlpineEnvironmentHealth?>(null) }
    var apkAnalysisReady by remember { mutableStateOf(apkAnalysisInstaller.isReady()) }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }
    var apkAnalysisResultMessage by remember { mutableStateOf<String?>(null) }

    MiuixScaffoldPage(
        title = "Linux 工具环境",
        onBack = onBack,
    ) {
        item(key = "status-title") { SmallTitle("环境状态") }
        item(key = "status-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = status.title(),
                    summary = progress?.summary() ?: status.summary(),
                    endActions = {
                        TextButton(
                            text = when {
                                installing -> "安装中"
                                status.state == AlpineEnvironmentState.READY -> "已就绪"
                                status.state == AlpineEnvironmentState.BASE_READY && status.version != null -> "升级工具"
                                status.state == AlpineEnvironmentState.BASE_READY -> "继续安装"
                                else -> "下载并安装"
                            },
                            enabled = !installing && status.state != AlpineEnvironmentState.READY,
                            onClick = {
                                if (installing) return@TextButton
                                installing = true
                                resultMessage = null
                                coroutineScope.launch {
                                    val result = installer.install { update ->
                                        withContext(Dispatchers.Main.immediate) {
                                            progress = update
                                        }
                                    }
                                    status = installer.status()
                                    apkAnalysisReady = apkAnalysisInstaller.isReady()
                                    health = null
                                    progress = null
                                    installing = false
                                    resultMessage = result.toMessage()
                                }
                            },
                        )
                    },
                )
            }
        }

        if (status.state == AlpineEnvironmentState.READY) {
            item(key = "health-title") { SmallTitle("环境检查") }
            item(key = "health-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = health?.title() ?: "尚未检查",
                        summary = health?.summary() ?: "检查核心命令、工作区挂载、共享存储与剩余空间",
                        endActions = {
                            val repairNeeded = health?.healthy == false
                            TextButton(
                                text = when {
                                    installing -> "忙碌"
                                    checkingHealth -> "检查中"
                                    repairNeeded -> "修复"
                                    else -> "检查"
                                },
                                enabled = !checkingHealth && !installing,
                                onClick = {
                                    if (checkingHealth || installing) return@TextButton
                                    if (repairNeeded) {
                                        installing = true
                                        health = null
                                        resultMessage = null
                                        coroutineScope.launch {
                                            val result = installer.install(forceToolInstall = true) { update ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    progress = update
                                                }
                                            }
                                            status = installer.status()
                                            progress = null
                                            installing = false
                                            resultMessage = result.toMessage()
                                        }
                                    } else {
                                        checkingHealth = true
                                        coroutineScope.launch {
                                            health = installer.inspectHealth()
                                            checkingHealth = false
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        if (status.state == AlpineEnvironmentState.READY) {
            item(key = "optional-tools-title") { SmallTitle("可选工具") }
            item(key = "apk-analysis-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = "APK 分析",
                        summary = apkAnalysisProgress?.summary() ?: if (apkAnalysisReady) {
                            "JADX、Apktool、smali 与 baksmali 已就绪；Apktool 暂不支持回编译"
                        } else {
                            "安装 OpenJDK 17、JADX、Apktool、smali 与 baksmali；下载约 84 MB，需预留 768 MB；官方下载不可达时尝试校验镜像"
                        },
                        endActions = {
                            TextButton(
                                text = when {
                                    apkAnalysisReady -> "已安装"
                                    installing -> "安装中"
                                    else -> "安装"
                                },
                                enabled = !installing && !apkAnalysisReady,
                                onClick = {
                                    if (installing || apkAnalysisReady) return@TextButton
                                    installing = true
                                    apkAnalysisResultMessage = null
                                    coroutineScope.launch {
                                        val result = apkAnalysisInstaller.install { update ->
                                            withContext(Dispatchers.Main.immediate) {
                                                apkAnalysisProgress = update
                                            }
                                        }
                                        apkAnalysisReady = apkAnalysisInstaller.isReady()
                                        apkAnalysisProgress = null
                                        installing = false
                                        apkAnalysisResultMessage = result.toMessage()
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        apkAnalysisResultMessage?.let { message ->
            item(key = "apk-analysis-result-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(title = message)
                }
            }
        }

        resultMessage?.let { message ->
            item(key = "result-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(title = message)
                }
            }
        }

        item(key = "details-title") { SmallTitle("说明") }
        item(key = "details-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = "与 Android Root Shell 分离",
                    summary = "系统、应用、日志和 Magisk 操作仍使用 Android 环境；Python、Git、jq、zip 等通用工具使用 Alpine 环境。",
                )
                BasicComponent(
                    title = "Agent 基础工具",
                    summary = "预装 rg、fd、Git、SSH、curl、rsync、diff、patch、jq、SQLite、进程工具和常用压缩格式，适合搜索、修改、传输与诊断。",
                )
                BasicComponent(
                    title = "Python 工具",
                    summary = "预装 Python、pip、venv、pipx、uv 与 Ruff；项目依赖仍优先安装到独立虚拟环境。",
                )
                BasicComponent(
                    title = "按需扩展",
                    summary = "Node.js、编译器、tmux、Vim 与网络抓包工具不默认占用空间，可按项目需要使用 apk add 安装。",
                )
                BasicComponent(
                    title = "稳定工作区",
                    summary = "Linux 默认进入 /workspace，并继续兼容 /data/local/tmp/fuck_andes；共享存储位于 /sdcard。",
                )
                BasicComponent(
                    title = "权限边界",
                    summary = "环境通过 Root chroot 运行，并用独立 mount namespace 避免挂载泄漏；它提供工具链，不是安全沙箱。",
                )
            }
        }
    }
}

private fun AlpineEnvironmentStatus.title(): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> "尚未安装"
    AlpineEnvironmentState.BASE_READY -> "基础环境已就绪"
    AlpineEnvironmentState.READY -> "Alpine ${version ?: ""} 已就绪".trim()
}

private fun AlpineEnvironmentStatus.summary(): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> "需要 Root 与 Magisk、KernelSU 或 APatch BusyBox"
    AlpineEnvironmentState.BASE_READY -> if (version == null) {
        "常用工具安装尚未完成，可以从当前进度继续"
    } else {
        "基础环境可用，升级后补齐新版 Agent 与 Python 工具集"
    }
    AlpineEnvironmentState.READY -> "Agent 可通过 terminal 的 environment=linux 在 /workspace 工作"
}

private fun AlpineEnvironmentHealth.title(): String = when {
    healthy -> "环境正常"
    missingTools.isNotEmpty() -> "缺少 ${missingTools.size} 个核心命令"
    !workspaceReady -> "工作区挂载异常"
    else -> "环境需要检查"
}

private fun AlpineEnvironmentHealth.summary(): String {
    val details = buildList {
        if (missingTools.isNotEmpty()) add("缺少 ${missingTools.joinToString(", ")}")
        add(if (workspaceReady) "/workspace 可用" else "/workspace 不可用")
        add(if (sharedStorageReady) "/sdcard 可用" else "/sdcard 当前不可用")
        add("剩余 ${availableBytes.toReadableSize()}")
    }
    return details.joinToString(" · ")
}

private fun Long.toReadableSize(): String {
    val gibibyte = 1024L * 1024L * 1024L
    val mebibyte = 1024L * 1024L
    return if (this >= gibibyte) {
        "%.1f GB".format(this.toDouble() / gibibyte)
    } else {
        "${this / mebibyte} MB"
    }
}

private fun AlpineInstallProgress.summary(): String {
    if (stage.displayName != "下载 Alpine 基础环境" || totalBytes <= 0L) {
        return stage.displayName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return "${stage.displayName} · $percent%"
}

private fun ApkAnalysisInstallProgress.summary(): String {
    if (stage != fuck.andes.agent.terminal.ApkAnalysisInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stage.displayName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    val name = when (artifactName) {
        "jadx" -> "JADX"
        "apktool" -> "Apktool"
        "smali" -> "smali"
        "baksmali" -> "baksmali"
        else -> "工具"
    }
    return "${stage.displayName} · $name $percent%"
}

private fun AlpineInstallResult.toMessage(): String = when (this) {
    AlpineInstallResult.AlreadyReady -> "Linux 工具环境已经就绪"
    is AlpineInstallResult.Installed -> "Alpine $version 与常用工具安装完成"
    is AlpineInstallResult.UnsupportedAbi -> "暂不支持设备架构：$abi"
    AlpineInstallResult.RootUnavailable -> "未获得 Root 权限，请在 Root 管理器中授权 Eta"
    AlpineInstallResult.BusyBoxUnavailable -> "Root 环境缺少可用的 BusyBox 或必要 applet"
    AlpineInstallResult.EnvironmentUnavailable -> "当前 Root 环境无法创建隔离 mount namespace 或 chroot"
    is AlpineInstallResult.Failed -> "${stage.displayName}失败，请检查网络或稍后重试"
}

private fun ApkAnalysisInstallResult.toMessage(): String = when (this) {
    ApkAnalysisInstallResult.AlreadyReady -> "APK 分析工具已经就绪"
    ApkAnalysisInstallResult.EnvironmentNotReady -> "请先完成 Linux 基础工具安装"
    is ApkAnalysisInstallResult.InsufficientSpace ->
        "空间不足：至少需要 ${requiredBytes.toReadableSize()}，当前剩余 ${availableBytes.toReadableSize()}"
    ApkAnalysisInstallResult.Installed -> "JADX、Apktool、smali 与 baksmali 安装完成"
    is ApkAnalysisInstallResult.Failed -> "${stage.displayName}失败，可以稍后重试"
}
