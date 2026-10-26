package io.github.mangi.eta.agent.web

import io.github.mangi.eta.data.model.CustomWebFetchConfig
import io.github.mangi.eta.data.model.CustomWebSearchConfig
import io.github.mangi.eta.data.model.WebHttpMethod
import io.github.mangi.eta.data.model.WebToolBackend
import java.time.Instant
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWebToolsTest {
    @Test fun cachedPagesShareTheSameSnapshotWithoutAnotherRequest() {
        val source = "第一行\n" + "a".repeat(998) + "😀" + "正文".repeat(900)
        WebFixtureServer { WebFixtureResponse(body = source.toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url()).put("max_chars", 1000))
                assertTrue(first.toString(), first.getBoolean("ok"))
                val id = first.getString("document_id")
                val content = StringBuilder(first.getString("text"))
                var page = first
                while (page.getBoolean("has_more")) {
                    page = call(tools, "fetch_url", JSONObject().put("document_id", id).put("offset_chars", page.getInt("next_offset_chars")).put("max_chars", 1000))
                    assertTrue(page.toString(), page.getBoolean("ok"))
                    assertEquals(first.getString("retrieved_at"), page.getString("retrieved_at"))
                    content.append(page.getString("text"))
                }
                assertEquals(source, content.toString())
                assertEquals(1, server.requests.size)
                assertFalse(page.getBoolean("source_truncated"))
            }
        }
    }

    @Test fun snapshotIdsExpireAcrossRunsAndAfterEviction() {
        WebFixtureServer { WebFixtureResponse(body = "text".toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url("/0"))).getString("document_id")
                repeat(4) { call(tools, "fetch_url", JSONObject().put("url", server.url("/${it + 1}"))) }
                assertEquals("WEB_DOCUMENT_EXPIRED", call(tools, "fetch_url", JSONObject().put("document_id", first)).getString("code"))
                tools().use { other -> assertEquals("WEB_DOCUMENT_EXPIRED", call(other, "fetch_url", JSONObject().put("document_id", first)).getString("code")) }
            }
        }
    }

    @Test fun failedInitialPageDoesNotEvictAUsableSnapshot() {
        WebFixtureServer { WebFixtureResponse(body = "text".toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url("/0"))).getString("document_id")
                repeat(3) { call(tools, "fetch_url", JSONObject().put("url", server.url("/${it + 1}"))) }
                val invalid = call(tools, "fetch_url", JSONObject().put("url", server.url("/invalid")).put("offset_chars", 100))
                assertEquals("INVALID_CONTENT_RANGE", invalid.getString("code"))
                assertTrue(call(tools, "fetch_url", JSONObject().put("document_id", first)).getBoolean("ok"))
            }
        }
    }

    @Test fun linksCrossingPageBoundariesRemainAvailableOnBothPages() {
        val content = "x".repeat(985) + " https://example.test/long/source " + "tail".repeat(100)
        WebFixtureServer { WebFixtureResponse(body = content.toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url()).put("max_chars", 1000))
                val second = call(tools, "fetch_url", JSONObject().put("document_id", first.getString("document_id"))
                    .put("offset_chars", first.getInt("next_offset_chars")).put("max_chars", 1000))
                assertEquals("https://example.test/long/source", first.getJSONArray("links").getJSONObject(0).getString("url"))
                assertEquals("https://example.test/long/source", second.getJSONArray("links").getJSONObject(0).getString("url"))
            }
        }
    }

    @Test fun numberedHtmlReferenceSplitAcrossPagesKeepsItsTarget() {
        val html = "<main>" + "x".repeat(994) + "<a href='/source'>link</a></main>"
        WebFixtureServer { WebFixtureResponse(headers = mapOf("Content-Type" to "text/html"), body = html.toByteArray()) }.use { server ->
            tools().use { tools ->
                val first = call(tools, "fetch_url", JSONObject().put("url", server.url()).put("max_chars", 1000))
                assertTrue(first.getString("text").endsWith("["))
                val second = call(tools, "fetch_url", JSONObject().put("document_id", first.getString("document_id"))
                    .put("offset_chars", first.getInt("next_offset_chars")).put("max_chars", 1000))
                assertTrue(second.getString("text").startsWith("1]"))
                assertEquals(server.url("/source"), first.getJSONArray("links").getJSONObject(0).getString("url"))
                assertEquals(server.url("/source"), second.getJSONArray("links").getJSONObject(0).getString("url"))
            }
        }
    }

    @Test fun htmlReturnsNumberedSourcesAndSeparatesSourceLossFromPagination() {
        WebFixtureServer { WebFixtureResponse(headers = mapOf("Content-Type" to "text/html"), body = "<title>标题</title><main>正文<a href='/source'>来源</a></main>".toByteArray()) }.use { server ->
            tools().use { tools ->
                val result = call(tools, "fetch_url", JSONObject().put("url", server.url()))
                assertEquals("标题", result.getString("title"))
                assertTrue(result.getString("text").contains("[1]"))
                assertEquals(server.url("/source"), result.getJSONArray("links").getJSONObject(0).getString("url"))
                assertEquals("untrusted_web_content", result.getString("source_role"))
                assertFalse(result.getBoolean("has_more"))
            }
        }
    }

    @Test fun challengeAndHttpErrorsCannotLookLikeEmptySuccessfulSearch() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(202).message("Accepted")
                .body("<form id='challenge-form'></form>".toResponseBody()).build()
        }).build()
        AgentWebTools(WebHttpTransport(client)).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", "example"))
            assertFalse(result.getBoolean("ok"))
            assertEquals("SEARCH_CHALLENGE", result.getString("code"))
        }
    }

    @Test fun successfulSearchReturnsSourcesWithoutOpeningSharedBrowser() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html")
                .body("<div id='links'><div class='web-result'><h2><a class='result__a' href='https://example.test/page'>来源</a></h2><div class='result__snippet'>摘要</div></div></div>".toResponseBody()).build()
        }).build()
        AgentWebTools(WebHttpTransport(client)).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", "example"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("duckduckgo_html", result.getString("provider"))
            assertEquals("first_page", result.getString("scope"))
            assertEquals("https://example.test/page", result.getJSONArray("results").getJSONObject(0).getString("url"))
        }
    }

    @Test fun invalidParametersAndClosedToolsHaveStableErrors() {
        val tools = tools()
        assertEquals("INVALID_ARGUMENT", call(tools, "fetch_url", JSONObject().put("url", "https://example.test").put("document_id", "id")).getString("code"))
        tools.close()
        assertEquals("WEB_CANCELLED", call(tools, "fetch_url", JSONObject().put("url", "https://example.test")).getString("code"))
    }

    @Test fun deepSeekSearchSendsBearerAndReturnsSummaryMetadata() {
        var capturedAuth: String? = null
        var capturedPath: String? = null
        var capturedBody: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            capturedAuth = request.header("Authorization")
            capturedPath = request.url.encodedPath
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            capturedBody = JSONObject(buffer.readUtf8())
            val responseJson = JSONObject().put(
                "results",
                JSONArray().put(
                    JSONObject()
                        .put("rerank_score", 2.4)
                        .put("title", "DeepSeek Search 指南")
                        .put("url", "https://api-docs.deepseek.com/zh-cn/guides/search/")
                        .put("snippet", "短摘要")
                        .put("summary", "长摘要：Search 和 Fetch 接口为 Agent 提供实时网页内容。")
                        .put("content", "")
                        .put("site_name", "DeepSeek Docs")
                        .put("date", "2026-10-17T00:00:00Z")
                        .put("authority", 3),
                ),
            )
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json")
                .body(responseJson.toString().toResponseBody()).build()
        }).build()
        val config = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.DEEPSEEK,
            deepSeekBaseUrl = "https://api.deepseek.com",
            deepSeekApiKey = "sk-deepseek-test",
        )
        val longQuery = "DeepSeek ".repeat(20)
        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", longQuery).put("max_results", 5))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("deepseek", result.getString("provider"))
            assertEquals("Bearer sk-deepseek-test", capturedAuth)
            assertEquals("/v1/tools/search", capturedPath)
            assertEquals(128, capturedBody?.getString("query")?.length)
            assertEquals(5, capturedBody?.getInt("count"))
            assertTrue(result.getBoolean("truncated"))
            val item = result.getJSONArray("results").getJSONObject(0)
            assertEquals("DeepSeek Search 指南", item.getString("title"))
            assertEquals("短摘要", item.getString("snippet"))
            assertEquals("长摘要：Search 和 Fetch 接口为 Agent 提供实时网页内容。", item.getString("summary"))
            assertEquals("DeepSeek Docs", item.getString("site_name"))
            assertEquals(3, item.getInt("authority"))
        }
    }

    @Test fun deepSeekFetchCachesMarkdownAndFallsBackToLocalWhenRemoteFails() {
        var remoteCalls = 0
        var localCalls = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.url.host == "api.deepseek.com") {
                remoteCalls++
                if (remoteCalls == 1) {
                    val json = JSONObject()
                        .put("title", "远端标题")
                        .put("content", "# 标题\n\n" + "正文段落 ".repeat(300) + "https://example.test/ref")
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Type", "application/json")
                        .body(json.toString().toResponseBody()).build()
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                        .body("{}".toResponseBody()).build()
                }
            } else {
                localCalls++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body("<title>本地回退标题</title><main>本地静态正文</main>".toResponseBody()).build()
            }
        }).build()
        val config = WebToolsRuntimeConfig(
            fetchBackend = WebToolBackend.DEEPSEEK,
            deepSeekBaseUrl = "https://api.deepseek.com/v1",
            deepSeekApiKey = "sk-deepseek-test",
        )
        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            val first = call(tools, "fetch_url", JSONObject().put("url", "https://example.test/doc1").put("max_chars", 1000))
            assertTrue(first.toString(), first.getBoolean("ok"))
            assertEquals("deepseek", first.getString("provider"))
            assertEquals("远端标题", first.getString("title"))
            assertEquals("markdown", first.getString("format"))
            assertTrue(first.getBoolean("has_more"))
            val secondPage = call(
                tools,
                "fetch_url",
                JSONObject()
                    .put("document_id", first.getString("document_id"))
                    .put("offset_chars", first.getInt("next_offset_chars"))
                    .put("max_chars", 1000),
            )
            assertTrue(secondPage.getBoolean("ok"))
            assertEquals(1, remoteCalls)
            assertEquals(0, localCalls)

            val fallback = call(tools, "fetch_url", JSONObject().put("url", "https://example.test/doc2"))
            assertTrue(fallback.toString(), fallback.getBoolean("ok"))
            assertEquals("builtin_local", fallback.getString("provider"))
            assertEquals("deepseek", fallback.getString("fallback_from"))
            assertEquals("本地回退标题", fallback.getString("title"))
            assertTrue(fallback.getString("text").contains("本地静态正文"))
            assertEquals(2, remoteCalls)
            assertEquals(1, localCalls)
        }
    }

    @Test fun customSearchAndFetchMapConfiguredJsonPaths() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/custom/search" -> JSONObject().put(
                    "data",
                    JSONObject().put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("headline", "自定义搜索标题")
                                .put("link", "https://example.test/item")
                                .put("desc", "自定义搜索摘要"),
                        ),
                    ),
                ).toString()
                "/custom/fetch" -> JSONObject().put(
                    "data",
                    JSONObject()
                        .put("page_title", "自定义读取标题")
                        .put("markdown", "自定义抓取正文内容"),
                ).toString()
                else -> error("unexpected path: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json")
                .body(body.toResponseBody()).build()
        }).build()
        val config = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.CUSTOM,
            fetchBackend = WebToolBackend.CUSTOM,
            customSearch = CustomWebSearchConfig(
                method = WebHttpMethod.POST,
                url = "https://search.example.test/custom/search",
                resultsPath = "data.items",
                titleField = "headline",
                urlField = "link",
                snippetField = "desc",
            ),
            customFetch = CustomWebFetchConfig(
                method = WebHttpMethod.POST,
                url = "https://fetch.example.test/custom/fetch",
                contentField = "data.markdown",
                titleField = "data.page_title",
            ),
        )
        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            val search = call(tools, "web_search", JSONObject().put("query", "测试 \"引号\""))
            assertTrue(search.toString(), search.getBoolean("ok"))
            assertEquals("custom", search.getString("provider"))
            assertEquals("自定义搜索标题", search.getJSONArray("results").getJSONObject(0).getString("title"))

            val fetch = call(tools, "fetch_url", JSONObject().put("url", "https://example.test/target"))
            assertTrue(fetch.toString(), fetch.getBoolean("ok"))
            assertEquals("custom", fetch.getString("provider"))
            assertEquals("自定义读取标题", fetch.getString("title"))
            assertEquals("自定义抓取正文内容", fetch.getString("text"))
        }
    }

    @Test fun geminiSearchBackendSendsCleanGroundingRequestAndParsesMetadata() {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedAuth: String? = null
        var capturedApiKey: String? = null
        var capturedAccept: String? = null
        var capturedBody: String? = null

        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            capturedPath = request.url.encodedPath
            capturedMethod = request.method
            capturedAuth = request.header("Authorization")
            capturedApiKey = request.header("x-goog-api-key")
            capturedAccept = request.header("Accept")
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            capturedBody = buffer.readUtf8()

            val sseResponse = buildString {
                append("data: ")
                append(
                    JSONObject().put(
                        "candidates",
                        JSONArray().put(
                            JSONObject()
                                .put(
                                    "content",
                                    JSONObject().put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put("text", "Kotlin 协程是用于异步编程的轻量级线程。"),
                                        ),
                                    ),
                                )
                                .put(
                                    "groundingMetadata",
                                    JSONObject()
                                        .put(
                                            "groundingChunks",
                                            JSONArray().put(
                                                JSONObject().put(
                                                    "web",
                                                    JSONObject()
                                                        .put("uri", "https://kotlinlang.org/docs/coroutines-overview.html")
                                                        .put("title", "Coroutines guide | Kotlin"),
                                                ),
                                            ),
                                        )
                                        .put(
                                            "groundingSupports",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("segment", JSONObject().put("text", "Kotlin 协程是用于异步编程的轻量级线程。"))
                                                    .put("groundingChunkIndices", JSONArray().put(0)),
                                            ),
                                        ),
                                ),
                        ),
                    ).toString(),
                )
                append("\n\n")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(sseResponse.toResponseBody())
                .build()
        }).build()

        val config = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.GEMINI,
            geminiBaseUrl = "https://generativelanguage.googleapis.com",
            geminiApiKey = "test-gemini-key",
            geminiModel = "gemini-3.8-flash-tiered",
        )
        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", "Kotlin Coroutines"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("gemini_grounding", result.getString("provider"))

            val results = result.getJSONArray("results")
            assertEquals(1, results.length())
            val first = results.getJSONObject(0)
            assertEquals("Coroutines guide | Kotlin", first.getString("title"))
            assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", first.getString("url"))
            assertEquals("Kotlin 协程是用于异步编程的轻量级线程。", first.getString("snippet"))

            assertEquals("/v1beta/models/gemini-3.8-flash-tiered:streamGenerateContent", capturedPath)
            assertEquals("POST", capturedMethod)
            assertEquals("text/event-stream", capturedAccept)
            assertEquals("test-gemini-key", capturedApiKey)
            assertEquals(null, capturedAuth)

            val reqJson = JSONObject(capturedBody!!)
            assertFalse(reqJson.has("toolConfig"))
            val toolsArr = reqJson.getJSONArray("tools")
            assertEquals(1, toolsArr.length())
            val toolObj = toolsArr.getJSONObject(0)
            assertTrue(toolObj.has("google_search"))
            assertFalse(toolObj.has("functionDeclarations"))
            assertEquals(0, reqJson.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        }
    }

    @Test fun geminiSearchOnProxyGatewayAddsBearerAuth() {
        var capturedAuth: String? = null
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            capturedAuth = request.header("Authorization")
            val sse = "data: " + JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "代理结果"))),
                    ),
                ),
            ).toString() + "\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(sse.toResponseBody()).build()
        }).build()

        val config = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.GEMINI,
            geminiBaseUrl = "https://gemini-proxy.example.com",
            geminiApiKey = "proxy-api-key",
            geminiModel = "gemini-3.8-flash-tiered",
        )
        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            val result = call(tools, "web_search", JSONObject().put("query", "代理搜索测试"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("gemini_grounding", result.getString("provider"))
            assertEquals("Bearer proxy-api-key", capturedAuth)
            val first = result.getJSONArray("results").getJSONObject(0)
            assertEquals("Google Search", first.getString("title"))
            assertEquals("代理结果", first.getString("snippet"))
        }
    }

    @Test fun builtinBackendWithAutoGroundingUsesGeminiAndFallsBackToBuiltinOnError() {
        var geminiAttempts = 0
        var builtinAttempts = 0

        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.url.host == "generativelanguage.googleapis.com") {
                geminiAttempts++
                if (geminiAttempts == 1) {
                    val sse = "data: " + JSONObject().put(
                        "candidates",
                        JSONArray().put(
                            JSONObject()
                                .put(
                                    "content",
                                    JSONObject().put(
                                        "parts",
                                        JSONArray().put(JSONObject().put("text", "Grounded answer")),
                                    ),
                                )
                                .put(
                                    "groundingMetadata",
                                    JSONObject().put(
                                        "groundingChunks",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "web",
                                                JSONObject()
                                                    .put("uri", "https://example.test/gemini-result")
                                                    .put("title", "Gemini Result"),
                                            ),
                                        ),
                                    ),
                                ),
                        ),
                    ).toString() + "\n\n"
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Type", "text/event-stream")
                        .body(sse.toResponseBody()).build()
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(500).message("Internal Server Error")
                        .body("{}".toResponseBody()).build()
                }
            } else {
                builtinAttempts++
                val html = "<html><head><title>Search</title></head><body><div id='links' class='results'>" +
                    "<div class='result results_links web-result'><div class='result__body'>" +
                    "<h2 class='result__title'><a class='result__a' href='https://example.test/builtin-result'>Builtin Title</a></h2>" +
                    "<a class='result__snippet' href='https://example.test/builtin-result'>Builtin snippet</a></div></div>" +
                    "</div></body></html>"
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(html.toResponseBody()).build()
            }
        }).build()

        val config = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.BUILTIN,
            geminiBaseUrl = "https://generativelanguage.googleapis.com",
            geminiApiKey = "test-gemini-key",
            geminiAutoGrounding = true,
        )

        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            val first = call(tools, "web_search", JSONObject().put("query", "query 1"))
            assertTrue(first.toString(), first.getBoolean("ok"))
            assertEquals("gemini_grounding", first.getString("provider"))
            assertEquals(1, geminiAttempts)
            assertEquals(0, builtinAttempts)

            val second = call(tools, "web_search", JSONObject().put("query", "query 2"))
            assertTrue(second.toString(), second.getBoolean("ok"))
            assertEquals("duckduckgo_html", second.getString("provider"))
            assertEquals(2, geminiAttempts)
            assertEquals(1, builtinAttempts)
            assertEquals("Builtin Title", second.getJSONArray("results").getJSONObject(0).getString("title"))
        }
    }

    @Test fun geminiAutoGroundingPropagatesCancellationWithoutFallingBack() {
        var builtinCalled = false
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.url.host != "generativelanguage.googleapis.com") {
                builtinCalled = true
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/plain").body("".toResponseBody()).build()
        }).build()

        val config = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.BUILTIN,
            geminiBaseUrl = "https://generativelanguage.googleapis.com",
            geminiApiKey = "test-gemini-key",
            geminiAutoGrounding = true,
        )

        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            tools.close() // closes tools and marks cancelled
            val result = call(tools, "web_search", JSONObject().put("query", "test query"))
            assertFalse(result.getBoolean("ok"))
            assertEquals("WEB_CANCELLED", result.getString("code"))
            assertFalse("Builtin should never be called when cancelled", builtinCalled)
        }
    }

    @Test fun geminiFetchBackendFallsBackToBuiltinLocalSnapshot() {
        val html = "<title>Gemini Fetch Local</title><main>Local snapshot body</main>"
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(html.toResponseBody()).build()
        }).build()

        val config = WebToolsRuntimeConfig(
            fetchBackend = WebToolBackend.GEMINI,
        )

        AgentWebTools(WebHttpTransport(client), runtimeConfig = { config }).use { tools ->
            val result = call(tools, "fetch_url", JSONObject().put("url", "https://example.test/page"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("Gemini Fetch Local", result.getString("title"))
            assertTrue(result.getString("text").contains("Local snapshot body"))
        }
    }

    private fun tools() = AgentWebTools(WebHttpTransport(OkHttpClient()), now = { Instant.parse("2026-10-05T08:00:00Z") })
    private fun call(tools: AgentWebTools, name: String, args: JSONObject): JSONObject = JSONObject(tools.execute(name, args))
}
