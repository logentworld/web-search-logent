package com.sleepysoong.logent.websearch

import com.sleepysoong.logent.plugin.LogentPlugin
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginSetting
import com.sleepysoong.logent.plugin.PluginSettingKind
import com.sleepysoong.logent.plugin.PluginTool
import com.sleepysoong.logent.plugin.ToolAccess
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.integer
import com.sleepysoong.logent.plugin.integerProperty
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.toolSpec
import kotlinx.serialization.json.JsonObject

/**
 * `web_search` 도구를 제공하는 독립 플러그인이에요.
 *
 * 검색은 Brave Search API → DuckDuckGo HTML → Jina DuckDuckGo lite → Bing 순으로
 * 시도하고, 모든 실패 원인은 결과 코드에 남겨요. Brave 키는 설정의 SECRET 필드에서
 * 호출 시점에 읽으므로 키를 바꾸면 다음 검색부터 바로 반영돼요.
 */
class WebSearchPlugin : LogentPlugin {
    override val id: String = "com.sleepysoong.logent.web-search"
    override val name: String = "Web Search"
    override val version: String = BuildConfig.PLUGIN_VERSION
    override val apiVersion: Int = 4

    @Volatile
    private var host: PluginHost? = null

    private val client = WebSearchClient(
        transport = HostSearchTransport { host },
        braveApiKey = { host?.setting(BRAVE_API_KEY).orEmpty() },
    )

    override fun settings(): List<PluginSetting> = listOf(
        PluginSetting(
            key = BRAVE_API_KEY,
            title = "Brave Search API 키",
            description = "Brave Search API 키예요. 비워 두면 DuckDuckGo·Jina·Bing 무키 경로로 폴백해요. " +
                "키는 앱 설정에만 저장되고 셸 인자·로그·프롬프트에 넣지 않아요.",
            kind = PluginSettingKind.SECRET,
            defaultValue = "",
        ),
    )

    override fun tools(): List<PluginTool> = listOf(
        PluginTool(
            spec = toolSpec(
                name = "web_search",
                description = "Brave Search API → DuckDuckGo → Jina → Bing 순으로 웹을 검색해 제목·URL·요약을 돌려준다. " +
                    "Brave 키는 설정에 있으면 쓰고, 없으면 무키 경로로 폴백한다. " +
                    "결과가 없거나 차단되면 web_fetch로 공식 문서 URL을 직접 읽어라. 출처를 인용할 때 URL을 사용한다.",
                required = listOf("query"),
            ) {
                stringProperty("query", "검색어")
                integerProperty("limit", "결과 수 1~10. 기본 5.", 1, 10)
                stringProperty("recency", "최근 기간 필터", listOf("day", "week", "month", "year"))
            },
            access = setOf(ToolAccess.READ_ONLY),
            childVisible = true,
            label = "웹 검색",
        ) { arguments, _ ->
            search(arguments)
        },
    )

    override suspend fun ensureReady(host: PluginHost): Result<Unit> {
        this.host = host
        return Result.success(Unit)
    }

    private suspend fun search(arguments: JsonObject): ToolResult {
        val query = arguments.text("query")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult("query가 필요해요.", isError = true)
        if (query.length > MAX_QUERY_LENGTH) return ToolResult("검색어가 너무 깁니다.", isError = true)
        val limit = (arguments.integer("limit") ?: DEFAULT_LIMIT).coerceIn(1, 10)
        val recency = when (arguments.text("recency")?.lowercase()) {
            "day" -> "d"
            "week" -> "w"
            "month" -> "m"
            "year" -> "y"
            else -> null
        }
        val found = client.search(query, limit, recency)
        if (found.hits.isEmpty()) {
            return ToolResult(
                "검색 결과를 해석하지 못했어요 (HTTP ${found.code}). Brave/DuckDuckGo/Jina/Bing이 모두 응답하지 않았거나 " +
                    "요청을 제한했을 수 있어요. web_fetch로 공식 문서 URL을 직접 읽어 보세요.",
                isError = true,
            )
        }
        return ToolResult(WebSearchFormatter.format(query, found))
    }

    private companion object {
        const val BRAVE_API_KEY = "brave_api_key"
        const val MAX_QUERY_LENGTH = 300
        const val DEFAULT_LIMIT = 5
    }
}
