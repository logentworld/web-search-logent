package dev.logent.websearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHtmlTest {
    @Test
    fun `DuckDuckGo HTML에서 제목 URL 요약을 뽑고 uddg 래퍼를 벗겨요`() {
        val html = """
            <html><body>
            <div class="result results_links">
              <h2><a rel="nofollow" class="result__a"
                href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2F&amp;rut=abc">
                Kotlin <b>Docs</b></a></h2>
              <a class="result__snippet" href="x">Kotlin <b>documentation</b> &amp; guides</a>
            </div>
            <div class="result results_links">
              <h2><a rel="nofollow" class="result__a" href="https://example.com/page?x=1&amp;y=2">Example</a></h2>
              <div class="result__snippet">Example <i>snippet</i></div>
            </div>
            </body></html>
        """.trimIndent()

        val hits = SearchHtml.parseDuckDuckGo(html, 5)

        assertEquals(2, hits.size)
        assertEquals("Kotlin Docs", hits[0].title)
        assertEquals("https://kotlinlang.org/docs/", hits[0].url)
        assertEquals("Kotlin documentation & guides", hits[0].snippet)
        assertEquals("Example", hits[1].title)
        assertEquals("https://example.com/page?x=1&y=2", hits[1].url)
        assertEquals("Example snippet", hits[1].snippet)
    }

    @Test
    fun `DuckDuckGo HTML에서 잘못된 URL은 제외해요`() {
        val html = """
            <a class="result__a" href="javascript:alert(1)">스크립트</a>
            <div class="result__snippet">버려요</div>
            <a class="result__a" href="/relative/path">상대 경로</a>
            <div class="result__snippet">버려요</div>
            <a class="result__a" href="not a url">공백</a>
            <div class="result__snippet">버려요</div>
            <a class="result__a" href="ftp://example.com/file">FTP</a>
            <div class="result__snippet">버려요</div>
            <a class="result__a" href="https://example.com/ok">정상</a>
            <div class="result__snippet">남겨요</div>
        """.trimIndent()

        val hits = SearchHtml.parseDuckDuckGo(html, 10)

        assertEquals(1, hits.size)
        assertEquals("https://example.com/ok", hits[0].url)
        assertEquals("정상", hits[0].title)
    }

    @Test
    fun `Jina Markdown에서 번호 링크와 요약을 뽑아요`() {
        val markdown = """
            # DuckDuckGo lite
            1. [Kotlin](https://kotlinlang.org/docs/)
               Kotlin **docs** page.

            2. [Example](https://example.com/a)
               Example [snippet](https://example.com/ignored)
        """.trimIndent()

        val hits = SearchHtml.parseMarkdownResults(markdown, 5)

        assertEquals(2, hits.size)
        assertEquals("Kotlin", hits[0].title)
        assertEquals("https://kotlinlang.org/docs/", hits[0].url)
        assertEquals("Kotlin docs page.", hits[0].snippet)
        assertEquals("Example", hits[1].title)
        assertEquals("https://example.com/a", hits[1].url)
    }

    @Test
    fun `Bing b_algo 블록과 ck 리다이렉트를 풀어요`() {
        val html = """
            <ol>
              <li class="b_algo"><h2><a href="https://example.com/bing">Bing Result</a></h2>
                <p>Bing <strong>snippet</strong></p></li>
              <li class="b_algo"><h2><a
                href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1aHR0cHM6Ly9leGFtcGxlLm9yZy9wYXRo&amp;ntb=1">
                Encoded</a></h2><p>Second</p></li>
              <li class="b_algo"><h2><a href="javascript:alert(1)">Bad</a></h2><p>Bad</p></li>
            </ol>
        """.trimIndent()

        val hits = SearchHtml.parseBing(html, 5)

        assertEquals(2, hits.size)
        assertEquals("Bing Result", hits[0].title)
        assertEquals("https://example.com/bing", hits[0].url)
        assertEquals("Bing snippet", hits[0].snippet)
        assertEquals("https://example.org/path", hits[1].url)
    }

    @Test
    fun `엔티티와 태그를 표시용 텍스트로 정리해요`() {
        assertEquals("A & B < C", SearchHtml.decodeEntities("A &amp; B &lt; C"))
        assertEquals("한글 😀", SearchHtml.decodeEntities("&#54620;글 &#x1F600;"))
        assertEquals("알 수 없음 &bogus;", SearchHtml.decodeEntities("알 수 없음 &bogus;"))
    }

    @Test
    fun `URL 검사는 http와 https만 허용해요`() {
        assertEquals("https://example.com/a", SearchHtml.normalizeResultUrl(" https://example.com/a "))
        assertEquals("http://example.com", SearchHtml.normalizeResultUrl("http://example.com"))
        assertNull(SearchHtml.normalizeResultUrl("javascript:alert(1)"))
        assertNull(SearchHtml.normalizeResultUrl("https://"))
        assertNull(SearchHtml.normalizeResultUrl("//example.com/a"))
        assertNull(SearchHtml.normalizeResultUrl("https://exa mple.com"))
        assertNull(SearchHtml.normalizeResultUrl("https://example.com/" + "a".repeat(2_100)))
        assertTrue(SearchHtml.parseDuckDuckGo("", 5).isEmpty())
    }
}
