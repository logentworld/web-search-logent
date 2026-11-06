package dev.logent.websearch

import kotlinx.coroutines.runBlocking

/** 네트워크 대신 미리 정한 응답을 돌려주고 요청을 기록하는 전송이에요. */
internal class FakeTransport : SearchTransport {
    data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    val calls = mutableListOf<Call>()
    var respond: (Call) -> Result<SearchHttpResponse> = { Result.failure(IllegalStateException("응답이 없어요.")) }

    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): Result<SearchHttpResponse> {
        val call = Call(method, url, headers, body)
        calls += call
        return respond(call)
    }

    fun searchOnce(
        query: String,
        limit: Int = 5,
        recency: String? = null,
        key: String = "",
    ): SearchOutcome = runBlocking {
        WebSearchClient(this@FakeTransport) { key }.search(query, limit, recency)
    }
}
