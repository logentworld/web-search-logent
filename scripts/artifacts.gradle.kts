// 테스트·릴리스가 같은 AAR 스냅샷을 배포하도록 모듈 안에서 등록해요.
val repository = projectDir.parentFile
val releaseVersion = repository.resolve("VERSION").readText().trim()
require(Regex("[0-9]+[.][0-9]+[.][0-9]+").matches(releaseVersion)) { "VERSION은 x.y.z 형식으로 써 주세요." }
val distribution = repository.resolve("build/dist")
val pluginId = "dev.logent.web-search"
val pluginName = "Web Search"
val pluginEntry = "dev.logent.websearch.WebSearchPlugin"
val releaseAar = tasks.register<Copy>("pluginReleaseAar") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/aar/${project.name}-release.aar"))
    into(distribution)
    rename { "logent-web-search-v${releaseVersion}.aar" }
}
val pluginPackage = tasks.register<Exec>("pluginPackage") {
    group = "distribution"
    description = "classes.jar를 D8로 변환하고 manifest·서명과 묶어 .lpg를 만들어요."
    dependsOn(releaseAar)
    val aar = distribution.resolve("logent-web-search-v${releaseVersion}.aar")
    val lpg = distribution.resolve("logent-web-search-v${releaseVersion}.lpg")
    val pinnedPublicKey = repository.resolve("build/keys/content-public.der")
    doFirst {
        val key = System.getenv("LOGENT_PLUGIN_SIGNING_KEY_FILE")
            ?: System.getenv("LOGENT_CONTENT_SIGNING_KEY_FILE")
            ?: throw GradleException("플러그인 .lpg를 서명할 Git 밖 개인 키 파일을 LOGENT_PLUGIN_SIGNING_KEY_FILE로 지정해 주세요.")
        val publicKey = System.getenv("LOGENT_PLUGIN_PUBLIC_KEY_FILE")
            ?.let { file(it) }
            ?: pinnedPublicKey
        if (!publicKey.isFile) throw GradleException("공개키가 없어요: $publicKey")
        if (!aar.isFile) throw GradleException("AAR이 없어요: $aar")
        commandLine(
            "python3",
            repository.resolve("scripts/plugin-pack.py").absolutePath,
            "build",
            "--aar", aar.absolutePath,
            "--id", pluginId,
            "--name", pluginName,
            "--version", releaseVersion,
            "--entry", pluginEntry,
            "--api-min", "3",
            "--api-max", "3",
            "--key", file(key).absolutePath,
            "--public-key", publicKey.absolutePath,
            "--output", lpg.absolutePath,
        )
    }
}
tasks.register("pluginArtifacts") {
    group = "distribution"
    description = "서명된 .lpg와 보조 AAR을 만들어요."
    dependsOn(pluginPackage, releaseAar)
}
