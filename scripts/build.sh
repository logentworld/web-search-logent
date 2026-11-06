#!/usr/bin/env bash
# AAR을 D8로 변환해 서명된 .lpg를 만들고 SHA256SUMS를 남겨요.
set -euo pipefail
cd "$(dirname "$0")/.."
version="$(tr -d '[:space:]' < VERSION)"
name="logent-web-search"
dist="$PWD/build/dist"
key="${LOGENT_PLUGIN_SIGNING_KEY_FILE:-${LOGENT_CONTENT_SIGNING_KEY_FILE:-}}"
: "${key:?플러그인 .lpg를 서명할 Git 밖 개인 키 파일을 지정해 주세요.}"
[[ -f "$key" ]] || { echo "개인 키가 없어요: $key" >&2; exit 1; }
key="$(readlink -f "$key")"
for tool in python3 openssl base64; do
    command -v "$tool" >/dev/null || { echo "필수 도구가 없어요: $tool" >&2; exit 1; }
done
if [[ -n "${LOGENT_PLUGIN_PUBLIC_KEY_FILE:-}" ]]; then
    public="$LOGENT_PLUGIN_PUBLIC_KEY_FILE"
else
    mkdir -p build/keys
    base64 --decode content-public-key.der.b64 > build/keys/content-public.der
    public="$PWD/build/keys/content-public.der"
fi
[[ -s "$public" ]] || { echo "공개키가 없어요: $public" >&2; exit 1; }
public="$(readlink -f "$public")"
export LOGENT_PLUGIN_SIGNING_KEY_FILE="$key"
export LOGENT_PLUGIN_PUBLIC_KEY_FILE="$public"
./scripts/gradle.sh :plugin:pluginArtifacts "$@"
lpg="$dist/$name-v$version.lpg"
[[ -s "$lpg" ]] || { echo "서명된 .lpg가 없어요: $lpg" >&2; exit 1; }
artifacts=("$lpg")
for extra in "$dist/$name-v$version.aar"; do
    if [[ -s "$extra" ]]; then artifacts+=("$extra"); fi
done
python3 - "$dist" "${artifacts[@]}" <<'PY'
import hashlib
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
with (root / 'SHA256SUMS').open('w', encoding='ascii') as output:
    for name in sys.argv[2:]:
        path = pathlib.Path(name)
        with path.open('rb') as asset:
            digest = hashlib.file_digest(asset, 'sha256').hexdigest()
        output.write(f'{digest}  {path.name}\n')
PY
printf '산출물을 만들었어요: %s\n' "$dist"
