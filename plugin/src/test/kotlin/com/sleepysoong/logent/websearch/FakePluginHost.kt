package com.sleepysoong.logent.websearch

import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import com.sleepysoong.logent.plugin.ProcessResult

/** 플러그인 호스트를 흉내 내 설정·HTTP 요청을 검사해요. */
internal class FakePluginHost(
    override val appVersion: String = "0.0.0-test",
    override val apkPath: String = "/data/app/~~test/com.sleepysoong.logent-1/base.apk",
    val settings: MutableMap<String, String> = mutableMapOf(),
) : PluginHost {
    val requests = mutableListOf<PluginHttpRequest>()
    val progressMessages = mutableListOf<String>()
    var respond: (PluginHttpRequest) -> Result<PluginHttpResponse> = {
        Result.failure(IllegalStateException("HTTP 응답이 없어요."))
    }

    override suspend fun shell(command: String, timeoutSeconds: Int): Result<ProcessResult> =
        Result.failure(IllegalStateException("테스트에서는 셸을 쓰지 않아요."))

    override suspend fun ensureDesktop(): Result<Unit> = Result.success(Unit)

    override suspend fun readAsset(path: String): Result<ByteArray> =
        Result.failure(IllegalArgumentException("테스트에서는 자산을 읽지 않아요: $path"))

    override fun setting(key: String): String? = settings[key]

    override fun progress(message: String) {
        progressMessages += message
    }

    override suspend fun http(request: PluginHttpRequest): Result<PluginHttpResponse> {
        requests += request
        return respond(request)
    }
}
