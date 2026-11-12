package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.GeneratedImageStore
import io.github.mangi.eta.agent.runtime.AgentLegacyConversationProjection
import io.github.mangi.eta.ui.components.isChatVideoSource
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun toolRoundTripPreservesReasoningContentForCompatibleProviders() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("reasoning_content", "先分析工具参数")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "device_info")
                                .put("arguments", "{}")
                        )
                )
            )

        val durable = AgentConversationCodec.durableMessage(assistant)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("先分析工具参数", replayed.getString("reasoning_content"))
        assertEquals("call-1", replayed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun durableImageObservationNeverPersistsBase64Payload() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userMessage(
                text = "屏幕观察",
                images = listOf(
                    AgentModelClient.ModelImage(
                        reference = "data:image/png;base64,${"A".repeat(20_000)}",
                        mimeType = "image/png",
                        bytes = 15_000,
                    )
                ),
            )
        )

        assertFalse(message.contentJson.contains("base64"))
        assertTrue(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun ipcTranscriptHasHardBudgetAndNeverStartsWithOrphanToolResult() {
        val messages = buildList {
            repeat(60) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
                add(
                    AgentModelClient.ConversationMessage(
                        role = "tool",
                        toolCallId = "call-$index",
                        content = "结果-${"y".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "最终答案"))
        }

        val encoded = AgentLegacyConversationProjection.encode(messages, AgentLegacyConversationProjection.DIRECT_CHARS)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentLegacyConversationProjection.DIRECT_CHARS)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.first().role == "tool")
        assertTrue(decoded.first().content.contains("容量上限已压缩"))
        assertTrue(decoded.last().content.contains("最终答案"))
    }

    @Test
    fun conversationCheckpointPreservesEveryMessageBeyondLegacyBudget() {
        val messages = buildList {
            repeat(110) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "继续处理最新任务"))
        }

        val encoded = AgentConversationCodec.encodeConversationCheckpoint(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length > 96_000)
        assertEquals(messages, decoded)
        assertEquals("继续处理最新任务", decoded.last().content)
    }

    @Test
    fun responsesOutputItemsStayInMemoryAndNeverEnterStableTranscript() {
        val source = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(
            source,
            JSONArray().put(
                JSONObject()
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque-secret"),
            ),
        )
        val history = AgentConversationCodec.assistantHistoryMessage(source, emptyList())
        assertTrue(ResponsesEphemeralState.outputItems(history) != null)

        val stable = AgentConversationCodec.durableMessage(history)
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(listOf(stable))
        assertFalse(encoded.contains("opaque-secret"))
        assertFalse(encoded.contains("_eta_responses_output_items"))
    }

    @Test
    fun oversizedThoughtSignatureIsDroppedInDurableMessage() {
        val oversizedSig = "A".repeat(70_000)
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "完成任务")
            .put("thoughtSignature", oversizedSig)

        val durable = AgentConversationCodec.durableMessage(assistant)
        assertEquals("", durable.thoughtSignature)

        // Normal signature is preserved
        val normalSig = "NormalSig123"
        val normalAssistant = JSONObject()
            .put("role", "assistant")
            .put("content", "完成任务")
            .put("thoughtSignature", normalSig)

        val normalDurable = AgentConversationCodec.durableMessage(normalAssistant)
        assertEquals(normalSig, normalDurable.thoughtSignature)
    }

    @Test
    fun durableMessageMigratesInlineDataUrlsToFiles() {
        val pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "请看图片：\n\n![Generated Image](data:image/png;base64,$pngBase64)")

        val durable = AgentConversationCodec.durableMessage(assistant)
        assertTrue("Content should contain file: URI: ${durable.content}", durable.content.contains("![Generated Image](file:"))
        assertFalse("Content must NOT contain base64: ${durable.content}", durable.content.contains("base64"))
    }

    @Test
    fun toJsonObjectReplacesMarkdownImagesWithGeneratedImagePlaceholderForAssistant() {
        val message = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "生成的图：![Generated Image](file:///tmp/generated_images/img-123.png) 以及 ![Custom Plot](data:image/png;base64,ABCDEF) 和说明",
            contentJson = "",
        )

        val json = AgentConversationCodec.toJsonObject(message)
        val content = json.getString("content")

        assertTrue("Should contain [Generated Image]: $content", content.contains("[Generated Image]"))
        assertTrue("Should contain [Generated Image: Custom Plot]: $content", content.contains("[Generated Image: Custom Plot]"))
        assertFalse("Must NOT contain file: URI: $content", content.contains("file:"))
        assertFalse("Must NOT contain data:image/ URI: $content", content.contains("data:image/"))
        assertFalse("Must NOT contain ABCDEF: $content", content.contains("ABCDEF"))
    }

    @Test
    fun toJsonObjectPreservesUserMarkdownImages() {
        val message = AgentModelClient.ConversationMessage(
            role = "user",
            content = "用户消息 ![User Photo](file:///path/photo.png)",
            contentJson = "",
        )

        val json = AgentConversationCodec.toJsonObject(message)
        assertEquals("用户消息 ![User Photo](file:///path/photo.png)", json.getString("content"))
    }

    @Test
    fun generatedImageStorePersistsMp4VideoWithVidPrefixAndMp4Extension() {
        val tempDir = Files.createTempDirectory("eta-video-test").toFile()
        try {
            // Minimal ISO Base Media / MP4 ftyp header bytes: 00 00 00 18 'f' 't' 'y' 'p' 'm' 'p' '4' '2' ...
            val mp4Bytes = byteArrayOf(
                0x00, 0x00, 0x00, 0x18,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
                0x00, 0x00, 0x00, 0x00,
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
            )
            val b64 = Base64.getEncoder().encodeToString(mp4Bytes)

            val uri = GeneratedImageStore.persistBase64("video/mp4", b64, tempDir)
            assertNotNull(uri)
            assertTrue("Expected vid-*.mp4 URI but got $uri", uri!!.contains("/vid-") && uri.endsWith(".mp4"))
            assertTrue(GeneratedImageStore.isVideoMedia("video/mp4", uri))
            assertTrue(isChatVideoSource(uri))
            assertFalse(isChatVideoSource("file:///tmp/img-123.jpg"))

            // Legacy mis-suffixed .jpg containing MP4 ftyp header is repaired on migration
            val legacyFile = File(tempDir, "img-40f45db38f5b809ba304af71.jpg").apply {
                writeBytes(mp4Bytes)
            }
            val legacyMarkdown = "生成结果：\n\n![Generated Image](${legacyFile.toURI()})"
            val migrated = GeneratedImageStore.migrateInlineDataUrlsToFiles(legacyMarkdown, tempDir)
            assertTrue("Expected repaired vid-*.mp4 markdown: $migrated", migrated.contains("![Generated Video](") && migrated.contains("vid-40f45db38f5b809ba304af71.mp4)"))
            // Second pass (after rename) must also resolve to vid-*.mp4 idempotently
            val migratedSecondPass = GeneratedImageStore.migrateInlineDataUrlsToFiles(legacyMarkdown, tempDir)
            assertEquals(migrated, migratedSecondPass)

            // Context sanitization turns video markdown into [Generated Video]
            val sanitized = GeneratedImageStore.sanitizeMarkdownImagesForContext(migrated)
            assertTrue("Expected [Generated Video] placeholder: $sanitized", sanitized.contains("[Generated Video]"))
            assertFalse(sanitized.contains("file:"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
