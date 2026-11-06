package dev.logent.websearch

import java.net.URLDecoder

/** 검색 결과 한 건이에요. [url]은 리다이렉트 래퍼를 벗긴 실제 주소예요. */
internal data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * 의존성 없는 검색 결과 HTML/Markdown 파서예요.
 *
 * 코어 `HtmlText`의 공급자별 파서와 같은 제목·URL·요약 규칙을 옮겼어요.
 * 파싱 입력은 512KiB로 제한하고, 정규식은 한 번씩만 훑어요.
 */
internal object SearchHtml {
    private const val MAX_PARSE_CHARS = 512 * 1024

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val ICA = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")
    private val HREF_ATTR = Regex("href\\s*=\\s*([\"'])(.*?)\\1", ICA)

    private val TITLE_LINK = Regex(
        "<a\\b[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*>(.*?)</a\\s*>",
        ICA,
    )
    private val SNIPPET = Regex(
        "<(a|div|span|td)\\b[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</\\1\\s*>",
        ICA,
    )
    private val MD_LINK = Regex("^\\s*\\d+\\.\\s*\\[(.*?)\\]\\((.*?)\\)\\s*$")
    private val BING_BLOCK = Regex("<li class=\"b_algo\".*?</li>", ICA)
    private val BING_LINK = Regex("<h2[^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", ICA)
    private val BING_SNIPPET = Regex("<p[^>]*>(.*?)</p>", ICA)

    private val ENTITY = Regex("&(#(?:[xX][0-9A-Fa-f]{1,6}|\\d{1,7})|[A-Za-z][A-Za-z0-9]{1,31});")
    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "middot" to "\u00B7", "bull" to "\u2022",
        "deg" to "\u00B0", "plusmn" to "\u00B1", "times" to "\u00D7", "divide" to "\u00F7",
        "frac12" to "\u00BD", "frac14" to "\u00BC", "frac34" to "\u00BE",
        "sup2" to "\u00B2", "sup3" to "\u00B3", "micro" to "\u00B5",
        "para" to "\u00B6", "sect" to "\u00A7", "cent" to "\u00A2",
        "pound" to "\u00A3", "yen" to "\u00A5", "euro" to "\u20AC",
        "larr" to "\u2190", "rarr" to "\u2192", "harr" to "\u2194",
        "ge" to "\u2265", "le" to "\u2264", "ne" to "\u2260", "infin" to "\u221E",
    )

    /** `&amp;`, `&#65;`, `&#x1F600;` 같은 참조를 실제 문자로 바꿔요. 모르는 이름은 그대로 둬요. */
    fun decodeEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        return ENTITY.replace(value) { match ->
            val body = match.groupValues[1]
            if (body.startsWith("#")) {
                numericEntity(body.substring(1)) ?: match.value
            } else {
                ENTITIES[body] ?: ENTITIES[body.lowercase()] ?: match.value
            }
        }
    }

    /** DuckDuckGo HTML 결과 페이지에서 제목/실제 URL/스니펫을 뽑아요. */
    fun parseDuckDuckGo(html: String, limit: Int): List<SearchHit> {
        if (html.isEmpty() || limit <= 0) return emptyList()
        val source = cap(html)
        val links = TITLE_LINK.findAll(source).toList()
        if (links.isEmpty()) return emptyList()

        val hits = ArrayList<SearchHit>(minOf(links.size, limit))
        val seen = HashSet<String>()
        for ((index, link) in links.withIndex()) {
            val tagEnd = link.value.indexOf('>')
            val href = if (tagEnd >= 0) {
                HREF_ATTR.find(link.value.substring(0, tagEnd))?.groupValues?.get(2).orEmpty()
            } else {
                ""
            }
            val url = normalizeResultUrl(cleanUrl(href)) ?: continue
            if (!seen.add(url)) continue

            val nextStart = links.getOrNull(index + 1)?.range?.first ?: source.length
            val snippetMatch = SNIPPET.find(source, link.range.last + 1)
            val snippet = if (snippetMatch != null && snippetMatch.range.first < nextStart) {
                inlineText(snippetMatch.groupValues[2]).take(400)
            } else {
                ""
            }

            hits += SearchHit(inlineText(link.groupValues[1]).take(200), url, snippet)
            if (hits.size >= limit) break
        }
        return hits
    }

    /** Jina Reader가 돌려준 DuckDuckGo lite Markdown에서 결과를 뽑아요. */
    fun parseMarkdownResults(markdown: String, limit: Int): List<SearchHit> {
        if (markdown.isEmpty() || limit <= 0) return emptyList()
        val source = cap(markdown)
        val hits = ArrayList<SearchHit>(limit)
        val seen = HashSet<String>()
        var title: String? = null
        var url: String? = null
        val snippet = StringBuilder()
        for (line in source.lineSequence()) {
            val match = MD_LINK.find(line)
            if (match != null) {
                val currentUrl = url
                if (title != null && currentUrl != null && seen.add(currentUrl)) {
                    hits += SearchHit(
                        cleanMarkdown(title.orEmpty()).take(200),
                        currentUrl,
                        cleanMarkdown(snippet.toString()).take(400),
                    )
                    if (hits.size >= limit) return hits
                }
                title = match.groupValues[1]
                url = normalizeResultUrl(cleanUrl(match.groupValues[2]))
                snippet.setLength(0)
            } else if (title != null && line.isNotBlank() && snippet.length < 600) {
                if (snippet.isNotEmpty()) snippet.append(' ')
                snippet.append(line.trim())
            }
        }
        val currentUrl = url
        if (title != null && currentUrl != null && seen.add(currentUrl) && hits.size < limit) {
            hits += SearchHit(
                cleanMarkdown(title.orEmpty()).take(200),
                currentUrl,
                cleanMarkdown(snippet.toString()).take(400),
            )
        }
        return hits
    }

    /** Bing HTML 결과 페이지 파서예요. DDG 직접/Jina가 모두 실패했을 때만 써요. */
    fun parseBing(html: String, limit: Int): List<SearchHit> {
        if (html.isEmpty() || limit <= 0) return emptyList()
        val source = cap(html)
        val hits = ArrayList<SearchHit>(limit)
        val seen = HashSet<String>()
        for (block in BING_BLOCK.findAll(source)) {
            val link = BING_LINK.find(block.value) ?: continue
            val url = normalizeResultUrl(bingUrl(decodeEntities(link.groupValues[1]))) ?: continue
            if (!seen.add(url)) continue
            val snippet = BING_SNIPPET.find(block.value)?.let { inlineText(it.groupValues[1]) }.orEmpty()
            hits += SearchHit(inlineText(link.groupValues[2]).take(200), url, snippet.take(400))
            if (hits.size >= limit) break
        }
        return hits
    }

    /**
     * 결과 URL을 검사해요.
     *
     * http/https 스킴과 호스트가 있어야 하며, 공백·제어문자·2,048자 초과는 버려요.
     * 잘못된 URL은 검색 결과에서 제외해 모델이 열 수 없는 주소를 받지 않게 해요.
     */
    fun normalizeResultUrl(raw: String): String? {
        val value = decodeEntities(raw).trim()
        if (value.isEmpty() || value.length > 2_048) return null
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        if (value.any { it in "<>\"{}|\\^`" }) return null
        val parsed = runCatching { java.net.URL(value) }.getOrNull() ?: return null
        val scheme = parsed.protocol.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (parsed.host.isNullOrBlank()) return null
        return value
    }

    private fun inlineText(html: String): String =
        collapse(decodeEntities(TAG.replace(html, " ")))
            .replace(Regex("\\s+([.,!?;:)\\]])"), "$1")

    private fun collapse(raw: String): String {
        if (raw.isEmpty()) return ""
        return WHITESPACE.replace(raw, " ").trim { it.isWhitespace() || it == '\u00A0' }
    }

    private fun cleanMarkdown(raw: String): String {
        val plain = raw
            .replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+.!>-])"), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("^\\s*#+\\s*"), "")
        return collapse(decodeEntities(plain))
    }

    /** Bing의 /ck/a 리다이렉트에 들어 있는 실제 URL을 풀어요. */
    private fun bingUrl(raw: String): String {
        val decoded = decodeEntities(raw).trim()
        if (!decoded.contains("/ck/a")) return decoded
        val query = decoded.substringAfter('?', "")
        for (part in query.split('&')) {
            if (!part.startsWith("u=")) continue
            var value = percentDecode(part.substring(2))
            if (value.startsWith("a1")) value = value.substring(2)
            val url = runCatching {
                val padded = value + "=".repeat((4 - value.length % 4) % 4)
                String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull()
            if (url != null && url.startsWith("http")) return url
        }
        return decoded
    }

    /** `//duckduckgo.com/l/?uddg=...` 래퍼를 벗겨 실제 주소로 만들어요. */
    private fun cleanUrl(raw: String): String {
        val decoded = decodeEntities(raw).trim()
        if (decoded.isEmpty() || !decoded.contains("uddg=")) return decoded
        val query = decoded.substringAfter('?', "")
        if (query.isEmpty()) return decoded
        for (part in query.split('&')) {
            if (!part.startsWith("uddg=")) continue
            val target = percentDecode(part.substring("uddg=".length))
            return target.ifEmpty { decoded }
        }
        return decoded
    }

    private fun percentDecode(value: String): String =
        try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (error: IllegalArgumentException) {
            value
        }

    private fun numericEntity(raw: String): String? {
        val code = try {
            if (raw.startsWith("x") || raw.startsWith("X")) raw.substring(1).toInt(16) else raw.toInt()
        } catch (error: NumberFormatException) {
            return null
        }
        if (code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return null
        return StringBuilder(2).appendCodePoint(code).toString()
    }

    private fun cap(html: String): String =
        if (html.length > MAX_PARSE_CHARS) html.substring(0, MAX_PARSE_CHARS) else html
}
