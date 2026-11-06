package dev.logent.websearch

import dev.logent.plugin.PluginHost
import dev.logent.plugin.PluginHttpRequest

/** 플러그인 HTTP 응답 중 검색에 필요한 값만 담아요. */
internal data class SearchHttpResponse(
    val status: Int,
    val body: String,
)

/**
 * 검색 파이프라인이 쓰는 최소 HTTP 계약이에요.
 *
 * 실제 실행은 [HostSearchTransport]가 `PluginHost.http`로 넘기고, 단위 테스트는
 * 가짜 전송으로 공급자별 응답과 상태 코드를 재현해요.
 */
internal interface SearchTransport {
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): Result<SearchHttpResponse>
}

/**
 * 호스트의 HTTPS 클라이언트로 요청을 보내요.
 *
 * 호스트가 공용 주소·리다이렉트·응답 크기를 검사하므로 플러그인은 URL만 고르고
 * 직접 소켓을 열지 않아요. 비밀 키는 헤더로만 전달하며 로그·프로그레스에 남기지 않아요.
 */
internal class HostSearchTransport(
    private val host: () -> PluginHost?,
) : SearchTransport {
    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): Result<SearchHttpResponse> {
        val target = host() ?: return Result.failure(
            IllegalStateException("플러그인 호스트가 준비되지 않았어요."),
        )
        return target.http(
            PluginHttpRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
                timeoutSeconds = WebSearchClient.TIMEOUT_SECONDS,
                maxResponseBytes = WebSearchClient.MAX_RESPONSE_BYTES,
            ),
        ).map { SearchHttpResponse(it.status, it.body) }
    }
}
