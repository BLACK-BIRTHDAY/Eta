package fuck.andes.hook.xiaoai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoAiHookStateTest {
    @Test
    fun versionGateOnlyAcceptsTheAnalyzedApk() {
        assertTrue(XiaoAiTakeoverPolicy.isSupportedVersion(507013032L))
        assertFalse(XiaoAiTakeoverPolicy.isSupportedVersion(507013031L))
        assertFalse(XiaoAiTakeoverPolicy.isSupportedVersion(-1L))
    }

    @Test
    fun outboundMatchRequiresNlpRequestAndTheSameDialogId() {
        val queryInfo = XiaoAiQueryCache.QueryInfo(
            dialogId = "dialog-1",
            query = "测试",
            imageFileId = null,
            documentInput = false,
            capturedAt = 0L,
        )

        assertTrue(
            XiaoAiTakeoverPolicy.matchesOutboundEvent(
                fullName = "Nlp.Request",
                eventId = "dialog-1",
                queryInfo = queryInfo,
            )
        )
        assertFalse(
            XiaoAiTakeoverPolicy.matchesOutboundEvent(
                fullName = "SpeechRecognizer.Recognize",
                eventId = "dialog-1",
                queryInfo = queryInfo,
            )
        )
        assertFalse(
            XiaoAiTakeoverPolicy.matchesOutboundEvent(
                fullName = "Nlp.Request",
                eventId = "dialog-2",
                queryInfo = queryInfo,
            )
        )
    }

    @Test
    fun takeoverRequiresEnabledConfigAndHonorsBothPrefixForms() {
        assertNull(
            XiaoAiTakeoverPolicy.decide(
                query = "/agent 测试",
                hasImage = false,
                customModelEnabled = false,
                requirePrefix = true,
            )
        )
        assertEquals(
            "测试",
            XiaoAiTakeoverPolicy.decide(
                query = "/agent 测试",
                hasImage = false,
                customModelEnabled = true,
                requirePrefix = true,
            )?.prompt,
        )
        assertEquals(
            "测试",
            XiaoAiTakeoverPolicy.decide(
                query = "/agent%20测试",
                hasImage = false,
                customModelEnabled = true,
                requirePrefix = true,
            )?.prompt,
        )
        assertNull(
            XiaoAiTakeoverPolicy.decide(
                query = "测试",
                hasImage = false,
                customModelEnabled = true,
                requirePrefix = true,
            )
        )
    }

    @Test
    fun imageOnlyRequestGetsDefaultPromptWhenPrefixIsNotRequired() {
        assertEquals(
            "请分析这张图片",
            XiaoAiTakeoverPolicy.decide(
                query = "blank",
                hasImage = true,
                customModelEnabled = true,
                requirePrefix = false,
            )?.prompt,
        )
        assertNull(
            XiaoAiTakeoverPolicy.decide(
                query = "blank",
                hasImage = true,
                customModelEnabled = true,
                requirePrefix = true,
            )
        )
    }

    @Test
    fun queryCacheIsBoundedConsumedOnceAndExpires() {
        var now = 0L
        val cache = XiaoAiQueryCache(
            capacity = 2,
            ttlMillis = 100L,
            clock = { now },
        )
        cache.put("one", "1", null)
        cache.put("two", "2", "image")
        cache.put("three", "3", null)

        assertNull(cache.take("one"))
        assertEquals("image", cache.take("two")?.imageFileId)
        assertNull(cache.take("two"))
        now = 101L
        assertNull(cache.take("three"))
        assertEquals(0, cache.size())
    }

    @Test
    fun recentIdsSuppressDuplicatesWithinTtl() {
        var now = 0L
        val recent = XiaoAiRecentIds(
            capacity = 2,
            ttlMillis = 100L,
            clock = { now },
        )
        recent.add("dialog")
        assertTrue(recent.contains("dialog"))
        now = 101L
        assertFalse(recent.contains("dialog"))
    }

    @Test
    fun runSlotReplacesAndConditionallyClearsTheActiveRun() {
        val slot = XiaoAiRunSlot<Any>()
        val first = Any()
        val second = Any()

        assertNull(slot.replace(first))
        assertSame(first, slot.replace(second))
        assertFalse(slot.clear(first))
        assertSame(second, slot.get())
        assertTrue(slot.clear(second))
        assertNull(slot.get())
    }
}
