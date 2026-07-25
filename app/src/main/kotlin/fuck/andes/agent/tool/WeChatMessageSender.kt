package fuck.andes.agent.tool

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.core.AgentLogger
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

/**
 * 微信消息的窄自动化流程。
 *
 * 只接受精确联系人匹配；发送按钮只点击一次。发送后若无法验证，不重试，避免重复消息。
 */
internal class WeChatMessageSender(
    private val context: Context,
    private val logger: AgentLogger,
    private val isCancelled: () -> Boolean = { false },
) {
    fun execute(args: JSONObject): AgentModelClient.ToolResult {
        val contact = args.getString("contact").trim()
        val message = args.getString("message")
        val mode = args.getString("mode").lowercase(Locale.ROOT)
        val send = mode == "send"
        if (isCancelled()) return sensitiveError("CANCELLED", "运行已停止，本次未发送")
        val service = AgentAccessibilityService.current()
            ?: return sensitiveError("ACCESSIBILITY_UNAVAILABLE", "Eta 无障碍服务尚未连接")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
            ?: return sensitiveError("WECHAT_NOT_INSTALLED", "未安装微信或微信没有可启动入口")
        return runCatching {
            context.startActivity(
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                ),
            )
            if (!waitForPackage(service, WECHAT_PACKAGE, PACKAGE_TIMEOUT_MS)) {
                return sensitiveError("WECHAT_LAUNCH_TIMEOUT", "微信未在时限内进入前台")
            }

            val searchSnapshot = findOrOpenSearch(service)
                ?: return sensitiveError("WECHAT_SEARCH_NOT_FOUND", "无法可靠定位微信搜索入口")
            val searchInput = searchSnapshot.nodes
                .firstOrNull { it.enabled && it.editable && !it.password }
                ?: return sensitiveError("WECHAT_SEARCH_INPUT_NOT_FOUND", "无法可靠定位微信搜索框")
            val searchWrite = service.setTextNode(searchSnapshot, searchInput.index, contact)
            if (!searchWrite.ok) {
                return sensitiveError(searchWrite.code, searchWrite.message.ifBlank { "联系人搜索失败" })
            }

            val resultSnapshot = waitForSnapshot(service, CONTENT_TIMEOUT_MS) { snapshot ->
                exactContactRows(snapshot, contact).isNotEmpty()
            } ?: return sensitiveError("CONTACT_NOT_FOUND", "没有找到精确匹配的微信联系人")
            val rows = exactContactRows(resultSnapshot, contact)
            if (rows.size != 1) {
                return sensitiveError("AMBIGUOUS_CONTACT", "存在多个同名联系人；为避免发错人，本次未打开会话")
            }
            val openResult = service.clickNode(resultSnapshot, rows.single().index)
            if (!openResult.ok) {
                return sensitiveError(openResult.code, openResult.message.ifBlank { "无法打开联系人会话" })
            }

            val displayHeight = service.displaySize()?.second ?: Int.MAX_VALUE
            val chatSnapshot = waitForSnapshot(service, CONTENT_TIMEOUT_MS) { snapshot ->
                snapshot.packageName == WECHAT_PACKAGE &&
                    snapshot.nodes.any { node ->
                        node.enabled && node.editable && !node.password &&
                            node.bounds.centerY() > displayHeight * 0.55
                    }
            } ?: return sensitiveError("CHAT_INPUT_NOT_FOUND", "没有可靠进入联系人聊天页，本次未填写消息")
            val chatInput = chatSnapshot.nodes.first {
                it.enabled && it.editable && !it.password &&
                    it.bounds.centerY() > displayHeight * 0.55
            }
            val writeResult = service.setTextNode(chatSnapshot, chatInput.index, message)
            if (!writeResult.ok) {
                return sensitiveError(writeResult.code, writeResult.message.ifBlank { "填写消息失败" })
            }
            if (!send) {
                logger.info("Agent direct tool action=send_message outcome=drafted")
                return sensitiveOk()
                    .put("mode", "draft")
                    .put("contact_matched", true)
                    .put("message_chars", message.length)
                    .toToolResult()
            }

            if (isCancelled()) {
                return sensitiveError("CANCELLED", "运行已停止；消息已填入但未发送")
            }
            val sendSnapshot = waitForSnapshot(service, CONTENT_TIMEOUT_MS) { snapshot ->
                snapshot.nodes.any { node ->
                    node.enabled &&
                        node.bounds.centerY() > displayHeight * 0.55 &&
                        (node.text == SEND_TEXT || node.desc == SEND_TEXT)
                }
            } ?: return sensitiveError("SEND_BUTTON_NOT_FOUND", "消息已填入，但未找到可验证的发送按钮；本次未发送")
            val sendNodes = deduplicateRows(
                sendSnapshot.nodes.filter { node ->
                    node.enabled &&
                        node.bounds.centerY() > displayHeight * 0.55 &&
                        (node.text == SEND_TEXT || node.desc == SEND_TEXT)
                },
            )
            if (sendNodes.size != 1) {
                return sensitiveError("AMBIGUOUS_SEND_BUTTON", "发送按钮不唯一；消息已填入但未发送")
            }
            val click = service.clickNode(sendSnapshot, sendNodes.single().index)
            if (!click.ok) {
                return sensitiveError(
                    click.code,
                    click.message.ifBlank { "发送动作结果未知；不会自动重试，以免重复发送" },
                )
            }

            val verified = waitForSnapshot(service, SEND_VERIFY_TIMEOUT_MS) { snapshot ->
                val messageVisible = snapshot.nodes.any { it.text == message || it.desc == message }
                val inputCleared = snapshot.nodes.any { node ->
                    node.enabled && node.editable && node.text.isBlank() &&
                        node.bounds.centerY() > displayHeight * 0.55
                }
                val sendButtonGone = snapshot.nodes.none { node ->
                    node.enabled &&
                        node.bounds.centerY() > displayHeight * 0.55 &&
                        (node.text == SEND_TEXT || node.desc == SEND_TEXT)
                }
                inputCleared && (messageVisible || sendButtonGone)
            } != null
            if (!verified) {
                return sensitiveError(
                    "ACTION_OUTCOME_UNKNOWN",
                    "已点击一次发送，但未能可靠验证结果；不会自动重试，请在微信中确认",
                )
            }
            logger.info("Agent direct tool action=send_message outcome=verified")
            sensitiveOk()
                .put("mode", "send")
                .put("contact_matched", true)
                .put("sent", true)
                .put("verified", true)
                .put("message_chars", message.length)
                .toToolResult()
        }.getOrElse {
            sensitiveError("WECHAT_AUTOMATION_FAILED", "微信自动发送流程失败，本次不会自动重试")
        }
    }

    private fun findOrOpenSearch(
        service: AgentAccessibilityService,
    ): AgentAccessibilityService.NodeSnapshot? {
        repeat(MAX_BACK_ATTEMPTS) {
            if (isCancelled()) return null
            if (service.currentPackageName() != WECHAT_PACKAGE) return null
            val snapshot = service.captureNodeSnapshot(MAX_NODES)
            val search = snapshot?.nodes?.firstOrNull { node ->
                node.enabled && (node.text == SEARCH_TEXT || node.desc == SEARCH_TEXT)
            }
            if (snapshot != null && search != null) {
                val clicked = service.clickNode(snapshot, search.index)
                if (!clicked.ok) return null
                return waitForSnapshot(service, CONTENT_TIMEOUT_MS) { current ->
                    current.packageName == WECHAT_PACKAGE &&
                        current.nodes.any { it.enabled && it.editable && !it.password }
                }
            }
            val back = service.globalActionResult("BACK")
            if (!back.ok) return null
            SystemClock.sleep(STEP_SETTLE_MS)
        }
        return null
    }

    private fun exactContactRows(
        snapshot: AgentAccessibilityService.NodeSnapshot,
        contact: String,
    ): List<AgentAccessibilityService.UiNode> {
        val exact = snapshot.nodes.filter { node ->
            node.enabled && !node.editable && (node.text == contact || node.desc == contact)
        }.sortedBy { it.bounds.centerY() }
        return deduplicateRows(exact)
    }

    private fun deduplicateRows(
        nodes: List<AgentAccessibilityService.UiNode>,
    ): List<AgentAccessibilityService.UiNode> {
        val rows = mutableListOf<AgentAccessibilityService.UiNode>()
        nodes.sortedBy { it.bounds.centerY() }.forEach { candidate ->
            if (
                rows.none {
                    abs(it.bounds.centerY() - candidate.bounds.centerY()) <= SAME_ROW_TOLERANCE_PX
                }
            ) {
                rows += candidate
            }
        }
        return rows
    }

    private fun waitForPackage(
        service: AgentAccessibilityService,
        packageName: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (isCancelled()) return false
            if (service.currentPackageName() == packageName) return true
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return service.currentPackageName() == packageName
    }

    private fun waitForSnapshot(
        service: AgentAccessibilityService,
        timeoutMillis: Long,
        predicate: (AgentAccessibilityService.NodeSnapshot) -> Boolean,
    ): AgentAccessibilityService.NodeSnapshot? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (isCancelled()) return null
            val snapshot = service.captureNodeSnapshot(MAX_NODES)
            if (snapshot != null && predicate(snapshot)) return snapshot
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return service.captureNodeSnapshot(MAX_NODES)?.takeIf(predicate)
    }

    private fun sensitiveOk(): JSONObject =
        JSONObject().put("ok", true).put("tool", "send_message")

    private fun JSONObject.toToolResult(): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content = toString(), sensitive = true)

    private fun sensitiveError(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val SEARCH_TEXT = "搜索"
        const val SEND_TEXT = "发送"
        const val MAX_NODES = 120
        const val MAX_BACK_ATTEMPTS = 5
        const val PACKAGE_TIMEOUT_MS = 6_000L
        const val CONTENT_TIMEOUT_MS = 6_000L
        const val SEND_VERIFY_TIMEOUT_MS = 3_000L
        const val POLL_MS = 160L
        const val STEP_SETTLE_MS = 300L
        const val SAME_ROW_TOLERANCE_PX = 24
    }
}
