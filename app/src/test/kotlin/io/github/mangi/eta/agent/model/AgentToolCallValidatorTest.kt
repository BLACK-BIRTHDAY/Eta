package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentToolCallValidatorTest {
    @Test
    fun localRefAndAnyOfAcceptEitherDeclaredShape() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "anyOf": [
                    {"required": ["query"]},
                    {"required": ["filter"]}
                  ],
                  "properties": {
                    "query": {"${'$'}ref": "#/${'$'}defs/query"},
                    "filter": {"type": "object"}
                  },
                  "${'$'}defs": {
                    "query": {"type": "string", "minLength": 1}
                  }
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"query":"Eta"}""")))
        assertNull(validator.validate(call("""{"filter":{}}""")))
        assertNotNull(validator.validate(call("{}")))
        assertNotNull(validator.validate(call("""{"query":""}""")))
    }

    @Test
    fun oneOfRequiresExactlyOneMatchingBranch() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "oneOf": [
                    {"required": ["left"]},
                    {"required": ["right"]}
                  ]
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"left":true}""")))
        assertNotNull(validator.validate(call("{}")))
        assertNotNull(validator.validate(call("""{"left":true,"right":true}""")))
    }

    @Test
    fun conditionAndAdditionalPropertiesAreValidated() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "mode": {"enum": ["text", "count"]},
                    "value": {}
                  },
                  "required": ["mode", "value"],
                  "additionalProperties": false,
                  "if": {"properties": {"mode": {"const": "count"}}},
                  "then": {"properties": {"value": {"type": "integer"}}},
                  "else": {"properties": {"value": {"type": "string"}}}
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"mode":"count","value":2}""")))
        assertNull(validator.validate(call("""{"mode":"text","value":"two"}""")))
        assertNotNull(validator.validate(call("""{"mode":"count","value":"2"}""")))
        assertNotNull(validator.validate(call("""{"mode":"text","value":"two","extra":true}""")))
    }

    @Test
    fun booleanSchemasAreNotSilentlyIgnored() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "allowed": true,
                    "blocked": false
                  }
                }
                """.trimIndent()
            )
        )

        assertNull(validator.validate(call("""{"allowed":{"anything":true}}""")))
        assertNotNull(validator.validate(call("""{"blocked":1}""")))
    }

    @Test
    fun normalizeAliasesAutomaticallyFixesCommonDriftKeys() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"}
                  },
                  "required": ["query"]
                }
                """.trimIndent()
            )
        )

        // 传入 "q" 或 "keyword"，自动规整为 "query" 并通过校验
        val normalizedFromQ = validator.normalize(call("""{"q":"小红书"}"""))
        assertNull(validator.validate(normalizedFromQ))
        org.junit.Assert.assertEquals("小红书", JSONObject(normalizedFromQ.argumentsJson).optString("query"))

        val normalizedFromKeyword = validator.normalize(call("""{"keyword":"快速排序"}"""))
        assertNull(validator.validate(normalizedFromKeyword))
        org.junit.Assert.assertEquals("快速排序", JSONObject(normalizedFromKeyword.argumentsJson).optString("query"))

        val normalizedFromSearchTerm = validator.normalize(call("""{"search_term":"BanG Dream"}"""))
        assertNull(validator.validate(normalizedFromSearchTerm))
        org.junit.Assert.assertEquals("BanG Dream", JSONObject(normalizedFromSearchTerm.argumentsJson).optString("query"))
    }

    @Test
    fun normalizeAliasesWrapsSingleUriIntoUrisArray() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "uris": {
                      "type": "array",
                      "items": {"type": "string"}
                    }
                  },
                  "required": ["uris"]
                }
                """.trimIndent()
            )
        )

        // 传入单数 uri 字符串，自动规整并包装为 uris: ["https://example.com"]
        val normalizedFromUri = validator.normalize(call("""{"uri":"https://example.com"}"""))
        assertNull(validator.validate(normalizedFromUri))
        val urisArray = JSONObject(normalizedFromUri.argumentsJson).optJSONArray("uris")
        org.junit.Assert.assertNotNull(urisArray)
        org.junit.Assert.assertEquals(1, urisArray?.length())
        org.junit.Assert.assertEquals("https://example.com", urisArray?.optString(0))

        // 传入 urls 别名
        val normalizedFromUrls = validator.normalize(call("""{"urls":["https://a.com","https://b.com"]}"""))
        assertNull(validator.validate(normalizedFromUrls))
        org.junit.Assert.assertEquals(2, JSONObject(normalizedFromUrls.argumentsJson).optJSONArray("uris")?.length())
    }

    @Test
    fun normalizeAliasesFixesUriAndContentForWriteTools() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "uri": {"type": "string"},
                    "content": {"type": "string"}
                  },
                  "required": ["uri", "content"]
                }
                """.trimIndent()
            )
        )

        // 模型传入 path 和 text，自动规整为 uri 和 content
        val normalized = validator.normalize(call("""{"path":"viking://test.md","text":"hello world"}"""))
        assertNull(validator.validate(normalized))
        val args = JSONObject(normalized.argumentsJson)
        org.junit.Assert.assertEquals("viking://test.md", args.optString("uri"))
        org.junit.Assert.assertEquals("hello world", args.optString("content"))
    }

    @Test
    fun normalizeUnwrapsOuterArgsAndHandlesBareString() {
        val validator = validator(
            JSONObject(
                """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"}
                  },
                  "required": ["query"]
                }
                """.trimIndent()
            )
        )

        // 1. 测试外层多余包裹 {"args": {"query": "Pixel 11"}} 自动解包
        val fromOuterArgs = validator.normalize(call("""{"args":{"query":"Pixel 11"}}"""))
        assertNull(validator.validate(fromOuterArgs))
        org.junit.Assert.assertEquals("Pixel 11", JSONObject(fromOuterArgs.argumentsJson).optString("query"))

        // 2. 测试 searchQuery 驼峰命名
        val fromCamelCase = validator.normalize(call("""{"searchQuery":"Pixel 11"}"""))
        assertNull(validator.validate(fromCamelCase))
        org.junit.Assert.assertEquals("Pixel 11", JSONObject(fromCamelCase.argumentsJson).optString("query"))

        // 3. 测试未知键名但单字符串兜底
        val fromUnknownKey = validator.normalize(call("""{"custom_input":"Pixel 11"}"""))
        assertNull(validator.validate(fromUnknownKey))
        org.junit.Assert.assertEquals("Pixel 11", JSONObject(fromUnknownKey.argumentsJson).optString("query"))

        // 4. 测试裸字符串自动包裹
        val fromBareString = validator.normalize(call("Pixel 11"))
        assertNull(validator.validate(fromBareString))
        org.junit.Assert.assertEquals("Pixel 11", JSONObject(fromBareString.argumentsJson).optString("query"))

        // 5. 测试历史脱敏遗留的 redacted 标记清洗与别名解析
        val fromRedactedWithKeyword = validator.normalize(call("""{"redacted":true,"keyword":"Pixel 11"}"""))
        assertNull(validator.validate(fromRedactedWithKeyword))
        org.junit.Assert.assertFalse(JSONObject(fromRedactedWithKeyword.argumentsJson).has("redacted"))
        org.junit.Assert.assertEquals("Pixel 11", JSONObject(fromRedactedWithKeyword.argumentsJson).optString("query"))
    }

    private fun validator(parameters: JSONObject): AgentToolCallValidator =
        AgentToolCallValidator(
            JSONArray().put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", TOOL_NAME)
                            .put("parameters", parameters),
                    )
            )
        )

    private fun call(argumentsJson: String) = AgentModelClient.ToolCall(
        id = "call-1",
        name = TOOL_NAME,
        argumentsJson = argumentsJson,
    )

    private companion object {
        const val TOOL_NAME = "test_tool"
    }
}
