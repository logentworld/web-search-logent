package dev.logent.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchClientTest {
    private val braveJson = """
        {"web":{"results":[
          {"title":"Kotlin <b>Docs</b>","url":"https://kotlinlang.org/docs/","description":"Official <b>docs</b>"},
          {"title":"Bad","url":"javascript:alert(1)","description":"무시해요"},
          {"title":"Example","url":"https://example.com/a","description":"Example page"}
        ]}}
    """.trimIndent()

    private val ddgHtml = """
        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2F">Kotlin</a>
        <a class="result__snippet">Kotlin docs</a>
        <a class="result__a" href="https://example.com/ddg">Example</a>
        <div class="result__snippet">DDG result</div>
    """.trimIndent()

    private val jinaMarkdown = """
        1. [Jina Kotlin](https://kotlinlang.org/docs/language-guide.html)
           Jina snippet
    """.trimIndent()

    private val bingHtml = """
        <li class="b_algo"><h2><a href="https://example.com/bing">Bing Result</a></h2>
        <p>Bing snippet</p></li>
    """.trimIndent()

    @Test
    fun `Brave 키가 있으면 Brave를 먼저 쓰고 잘못된 URL은 버려요`() {
        val transport = FakeTransport()
        transport.respond = { call ->
            assertEquals("https://api.search.brave.com/res/v1/web/search?q=kotlin+docs&count=5&safesearch=moderate", call.url)
            assertEquals("secret-key", call.headers["X-Subscription-Token"])
            assertEquals("GET", call.method)
            Result.success(SearchHttpResponse(200, braveJson))
        }

        val found = transport.searchOnce("kotlin docs", key = "secret-key")

        assertEquals("Brave", found.provider)
        assertEquals(2, found.hits.size)
        assertEquals("Kotlin Docs", found.hits[0].title)
        assertEquals("https://kotlinlang.org/docs/", found.hits[0].url)
        assertEquals("Official docs", found.hits[0].snippet)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `키가 없으면 DuckDuckGo HTML을 POST로 써요`() {
        val transport = FakeTransport()
        transport.respond = { call ->
            assertEquals("https://html.duckduckgo.com/html/", call.url)
            assertEquals("POST", call.method)
            assertEquals("q=kotlin+docs&df=w", call.body)
            assertEquals("application/x-www-form-urlencoded", call.headers["Content-Type"])
            Result.success(SearchHttpResponse(200, ddgHtml))
        }

        val found = transport.searchOnce("kotlin docs", recency = "w")

        assertEquals("DuckDuckGo", found.provider)
        assertEquals(2, found.hits.size)
        assertEquals("https://kotlinlang.org/docs/", found.hits[0].url)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `Brave가 429면 DuckDuckGo로 폴백해요`() {
        val transport = FakeTransport()
        transport.respond = { call ->
            when {
                call.url.startsWith("https://api.search.brave.com") ->
                    Result.success(SearchHttpResponse(429, "{\"error\":\"rate limited\"}"))
                call.url == "https://html.duckduckgo.com/html/" ->
                    Result.success(SearchHttpResponse(200, ddgHtml))
                else -> Result.failure(IllegalStateException("예상하지 못한 요청: ${call.url}"))
            }
        }

        val found = transport.searchOnce("kotlin", key = "secret-key")

        assertEquals("DuckDuckGo", found.provider)
        assertEquals(2, transport.calls.size)
        assertTrue(transport.calls[1].url.startsWith("https://html.duckduckgo.com"))
    }

    @Test
    fun `DuckDuckGo가 503이면 Jina lite로 폴백해요`() {
        val transport = FakeTransport()
        transport.respond = { call ->
            when {
                call.url == "https://html.duckduckgo.com/html/" ->
                    Result.success(SearchHttpResponse(503, "busy"))
                call.url.startsWith("https://r.jina.ai/https://lite.duckduckgo.com/lite/?q=kotlin+docs") ->
                    Result.success(SearchHttpResponse(200, jinaMarkdown))
                else -> Result.failure(IllegalStateException("예상하지 못한 요청: ${call.url}"))
            }
        }

        val found = transport.searchOnce("kotlin docs")

        assertEquals("DuckDuckGo lite (Jina)", found.provider)
        assertEquals("https://kotlinlang.org/docs/language-guide.html", found.hits[0].url)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `DuckDuckGo와 Jina가 빈 결과면 Bing으로 폴백해요`() {
        val transport = FakeTransport()
        transport.respond = { call ->
            when {
                call.url == "https://html.duckduckgo.com/html/" ->
                    Result.success(SearchHttpResponse(202, "<html>challenge</html>"))
                call.url.startsWith("https://r.jina.ai/") ->
                    Result.success(SearchHttpResponse(200, "no results"))
                call.url.startsWith("https://www.bing.com/search?q=kotlin+docs") ->
                    Result.success(SearchHttpResponse(200, bingHtml))
                else -> Result.failure(IllegalStateException("예상하지 못한 요청: ${call.url}"))
            }
        }

        val found = transport.searchOnce("kotlin docs")

        assertEquals("Bing", found.provider)
        assertEquals("Bing Result", found.hits[0].title)
        assertEquals(3, transport.calls.size)
        assertEquals(202, found.code)
    }

    @Test
    fun `모든 공급자가 429나 5xx면 빈 결과와 코드를 돌려줘요`() {
        val transport = FakeTransport()
        transport.respond = { call ->
            when {
                call.url.startsWith("https://api.search.brave.com") ->
                    Result.success(SearchHttpResponse(429, ""))
                call.url == "https://html.duckduckgo.com/html/" ->
                    Result.success(SearchHttpResponse(500, "error"))
                call.url.startsWith("https://r.jina.ai/") ->
                    Result.success(SearchHttpResponse(429, "slow down"))
                else -> Result.success(SearchHttpResponse(503, "unavailable"))
            }
        }

        val found = transport.searchOnce("kotlin", key = "secret-key")

        assertTrue(found.hits.isEmpty())
        assertEquals(500, found.code)
        assertEquals("DuckDuckGo", found.provider)
        assertEquals(4, transport.calls.size)
    }

    @Test
    fun `전송 실패도 폴백하고 마지막까지 실패하면 코드 0이에요`() {
        val transport = FakeTransport()
        transport.respond = { Result.failure(java.io.IOException("offline")) }

        val found = transport.searchOnce("kotlin")

        assertTrue(found.hits.isEmpty())
        assertEquals(0, found.code)
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun `recency를 공급자별 파라미터로 바꿔요`() {
        val transport = FakeTransport()
        transport.respond = { call -> Result.success(SearchHttpResponse(200, "")) }

        transport.searchOnce("kotlin", recency = "d", key = "key")
        assertEquals(
            "https://api.search.brave.com/res/v1/web/search?q=kotlin&count=5&safesearch=moderate&freshness=pd",
            transport.calls[0].url,
        )
        assertEquals("q=kotlin&df=d", transport.calls[1].body)
        assertTrue(transport.calls[2].url.startsWith("https://r.jina.ai/https://lite.duckduckgo.com/lite/?q=kotlin&df=d"))
        assertTrue(transport.calls[3].url.endsWith("&filters=ex1%3a%22ez1%22"))
    }

    @Test
    fun `limit은 10을 넘지 않고 Brave count에도 반영해요`() {
        val transport = FakeTransport()
        transport.respond = { call -> Result.success(SearchHttpResponse(200, braveJson)) }

        transport.searchOnce("kotlin", limit = 10, key = "key")

        assertTrue(transport.calls[0].url.contains("&count=10&"))
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `Brave 키는 헤더 밖 결과나 URL에 드러나지 않아요`() {
        val transport = FakeTransport()
        transport.respond = { call ->
            if (call.url.startsWith("https://api.search.brave.com")) {
                Result.success(SearchHttpResponse(401, "{\"message\":\"invalid token\"}"))
            } else {
                Result.success(SearchHttpResponse(200, ddgHtml))
            }
        }

        val found = transport.searchOnce("kotlin", key = "super-secret-token")
        val formatted = WebSearchFormatter.format("kotlin", found)

        assertFalse(formatted.contains("super-secret-token"))
        assertFalse(found.toString().contains("super-secret-token"))
        assertTrue(transport.calls.first().url.contains("q=kotlin"))
        assertFalse(transport.calls.first().url.contains("super-secret-token"))
    }
}
