# Web Search for Logent

Logent의 `web_search` 도구를 독립 `.lpg` 플러그인으로 제공해요.
Brave Search API → DuckDuckGo HTML → Jina DuckDuckGo lite → Bing 순으로 검색하고,
제목·URL·요약을 같은 형식으로 돌려줘요.

- 저장소: <https://github.com/logentworld/web-search-logent> (`logent` 브랜치)
- 플러그인 id: `dev.logent.web-search`
- 도구 이름: `web_search`
- 공개 API: `dev.logent:logent-plugin-api:3.3.0`

## 설치

1. 릴리스의 `logent-web-search-v3.0.0.lpg`를 내려받아요.
2. Logent 설정 → 플러그인에서 `.lpg`를 설치하고 Web Search를 켜요.
3. 필요하면 플러그인 상세에서 Brave Search API 키를 입력해요.

키가 없어도 DuckDuckGo·Jina·Bing 무키 경로로 검색해요. 키는 앱 설정에만 저장되고
셸 인자·로그·프롬프트에 넣지 않아요.

## 설정

| 키 | 종류 | 기본값 | 설명 |
|---|---|---|---|
| `brave_api_key` | `SECRET` | 빈 값 | Brave Search API 키예요. 비워 두면 무키 공급자로 폴백해요. |

## 입력

| 필드 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `query` | string | 예 | - | 검색어예요. 최대 300자예요. |
| `limit` | integer | 아니요 | 5 | 결과 수예요. 1~10 범위로 제한해요. |
| `recency` | string | 아니요 | - | `day`, `week`, `month`, `year` 중 하나예요. |

## 동작

1. Brave 키가 있으면 Brave Search API를 먼저 호출해요. 응답이 2xx가 아니거나
   결과가 비면 다음 공급자로 넘어가요.
2. DuckDuckGo HTML에 `POST`로 검색해요. 202 챌린지·429·5xx·빈 결과면 폴백해요.
3. Jina Reader로 DuckDuckGo lite Markdown을 읽어요.
4. 그래도 결과가 없으면 Bing HTML을 읽어요.

모든 공급자가 실패하면 `검색 결과를 해석하지 못했어요 (HTTP 코드). ...` 오류를 돌려줘요.
`http://`와 `https://`가 아니거나 호스트가 없는 URL은 결과에서 제외해요.

결과 본문은 `<untrusted_web>` 경계 안에 넣고, 본문이 위조한 경계 태그는
`&lt;untrusted_web&gt;`처럼 무력화해요. 출력은 24,000자에서 잘리며 닫는 경계는 남겨요.

## 한도

- 검색어 300자, 결과 수 1~10개
- HTTP 제한 시간 30초, 응답 1MiB
- 도구 출력 24,000자(잘림 고지 포함)
- HTML/Markdown 파싱 입력 512KiB

## 개발

```bash
# JVM 단위 테스트와 lint
./scripts/test.sh

# 서명된 .lpg 만들기(개인 키는 Git 밖에 둬요)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
LOGENT_PLUGIN_SIGNING_KEY_FILE="$HOME/.local/share/logent/content-signing.pem" \
./scripts/build.sh

# Maven artifact 대신 로컬 API 소스를 쓸 때
LOGENT_PLUGIN_SIGNING_KEY_FILE=... ./scripts/build.sh -PlogentPluginApiMavenLocal=true
```

산출물은 `build/dist/logent-web-search-v3.0.0.lpg`와
`build/dist/logent-web-search-v3.0.0.aar`, `SHA256SUMS`예요.

## 보안

- 네트워크는 코어의 `PluginHost.http`만 써요. HTTPS만 허용되고 공용 주소·리다이렉트·
  응답 크기는 호스트가 검사해요.
- Brave 키는 `X-Subscription-Token` 헤더로만 전달하고 결과·오류·로그에 남기지 않아요.
- 검색 결과는 신뢰할 수 없는 데이터예요. 모델은 지시가 아니라 참고 자료로만 다뤄야 해요.

## 라이선스

GNU General Public License v3.0이에요. 전문은 `plugin/LICENSE`에 있어요.
출처와 의존성 안내는 `NOTICE`에 있어요.
