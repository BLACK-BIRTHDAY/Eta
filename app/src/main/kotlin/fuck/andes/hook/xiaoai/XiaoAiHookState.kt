package fuck.andes.hook.xiaoai

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference

internal object XiaoAiTakeoverPolicy {
    private const val AGENT_PREFIX = "/agent "
    private const val AGENT_ENCODED_PREFIX = "/agent%20"
    private const val IMAGE_ONLY_PLACEHOLDER = "blank"
    private const val IMAGE_ONLY_PROMPT = "请分析这张图片"

    data class Decision(
        val prompt: String,
    )

    fun isSupportedVersion(versionCode: Long): Boolean =
        versionCode == XiaoAiHooks.SUPPORTED_VERSION_CODE

    fun matchesOutboundEvent(
        fullName: String,
        eventId: String,
        queryInfo: XiaoAiQueryCache.QueryInfo?,
    ): Boolean =
        fullName == "Nlp.Request" &&
            eventId.isNotBlank() &&
            queryInfo?.dialogId == eventId

    fun decide(
        query: String,
        hasImage: Boolean,
        customModelEnabled: Boolean,
        requirePrefix: Boolean,
    ): Decision? {
        if (!customModelEnabled) return null
        val trimmed = query.trim()
        val prefixed = when {
            trimmed.startsWith(AGENT_PREFIX) ->
                trimmed.removePrefix(AGENT_PREFIX).trim()

            trimmed.startsWith(AGENT_ENCODED_PREFIX) ->
                trimmed.removePrefix(AGENT_ENCODED_PREFIX).trim()

            else -> null
        }
        if (requirePrefix && prefixed == null) return null

        val prompt = (prefixed ?: trimmed).let { candidate ->
            if (
                hasImage &&
                (candidate.isBlank() || candidate.equals(IMAGE_ONLY_PLACEHOLDER, ignoreCase = true))
            ) {
                IMAGE_ONLY_PROMPT
            } else {
                candidate
            }
        }
        return prompt.takeIf(String::isNotBlank)?.let(::Decision)
    }
}

internal class XiaoAiRunSlot<T : Any> {
    private val current = AtomicReference<T?>()

    fun get(): T? = current.get()

    fun replace(value: T): T? = current.getAndSet(value)

    fun take(): T? = current.getAndSet(null)

    fun clear(value: T): Boolean = current.compareAndSet(value, null)
}

internal class XiaoAiQueryCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class QueryInfo(
        val dialogId: String,
        val query: String,
        val imageFileId: String?,
        val documentInput: Boolean,
        val capturedAt: Long,
    )

    private val entries = LinkedHashMap<String, QueryInfo>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun put(
        dialogId: String,
        query: String,
        imageFileId: String?,
        documentInput: Boolean = false,
    ): Boolean {
        val normalizedId = dialogId.trim()
        if (normalizedId.isBlank()) return false
        val now = clock()
        pruneExpired(now)
        entries.remove(normalizedId)
        entries[normalizedId] = QueryInfo(
            dialogId = normalizedId,
            query = query,
            imageFileId = imageFileId?.trim()?.takeIf(String::isNotBlank),
            documentInput = documentInput,
            capturedAt = now,
        )
        trimToCapacity()
        return true
    }

    @Synchronized
    fun take(dialogId: String): QueryInfo? {
        val now = clock()
        pruneExpired(now)
        return entries.remove(dialogId.trim())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int {
        pruneExpired(clock())
        return entries.size
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, value) -> now - value.capturedAt > ttlMillis }
    }

    private fun trimToCapacity() {
        while (entries.size > capacity) {
            val oldest = entries.entries.firstOrNull()?.key ?: return
            entries.remove(oldest)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}

internal class XiaoAiRecentIds(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = LinkedHashMap<String, Long>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
    }

    @Synchronized
    fun add(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        val now = clock()
        pruneExpired(now)
        entries.remove(normalized)
        entries[normalized] = now
        while (entries.size > capacity) {
            entries.remove(entries.entries.firstOrNull()?.key ?: break)
        }
    }

    @Synchronized
    fun contains(id: String): Boolean {
        val now = clock()
        pruneExpired(now)
        return entries.containsKey(id.trim())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun pruneExpired(now: Long) {
        entries.entries.removeIf { (_, createdAt) -> now - createdAt > ttlMillis }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}
