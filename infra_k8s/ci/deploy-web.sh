#!/bin/sh
# 웹 번들을 노드로 보내 nginx 가 내려주게 한다.
#
#   sh infra_k8s/ci/deploy-web.sh [frontend/dist/update]
#
# **APK 와 같은 디렉터리에 얹는다**(/srv/mupid-k8s/downloads). 마운트도 nginx
# 블록도 이미 있고, 파드는 그것을 readOnly 로 보고 있다. 새 트리를 만들면
# 매니페스트·마운트·nginx 세 곳을 새로 맞춰야 하고, 그 중 하나가 어긋나면
# 받는 사람만 404 를 본다 — 올린 쪽은 성공했다고 나온다.
#
# 하는 일은 APK 쪽(deploy-apk.sh)과 같다: 체크섬 → 전송 → **엣지로 확인**.
# 확인을 빼면 안 된다. 수신기가 성공했다고 해도 nginx 의 location 이나 마운트가
# 어긋나 있으면 앱은 404 를 받고, 그 증상은 "업데이트가 안 된다" 로만 보인다.
#
# 입력은 `npm --prefix frontend run build:archive` 의 출력이다. 그 스크립트가
# `MUPID_WEB_BUILD` 를 받아 스탬프를 정하고, 아카이브 안 `app/build.json` ·
# `web.json` · 아카이브 이름 셋이 같은 값을 들고 나온다. **여기서 다시 확인한다** —
# 셋이 어긋나면 앱이 자기 번들을 영영 다시 받는다.
set -eu
: "${MUPID_DEPLOY_K8S_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_K8S_KNOWN_HOSTS:?GitLab file variable required}"
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

directory=${1:-frontend/dist/update}
manifest=$directory/web.json
[ -s "$manifest" ] || {
  echo "웹 매니페스트가 없다: $manifest — build:archive 를 먼저 돌린다" >&2
  exit 64
}

# JSON 에서 값을 하나 꺼낸다. 셸로 긁으면 형식이 조금만 달라져도 조용히 빈 값을
# 집어 가는데, 그 실패는 "업데이트가 안 뜬다" 로만 보인다. 이 job 은 어차피
# node 를 쓴다(프론트를 굽는다) — 새 의존성을 들이지 않는다.
json_field() {  # <파일> <필드>
  node -e '
    const fs = require("fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))[process.argv[2]];
    if (value === undefined || value === null) process.exit(64);
    process.stdout.write(String(value));
  ' "$1" "$2"
}

built_at=$(json_field "$manifest" builtAt)
expected=$(json_field "$manifest" sha256)
bundle=$(json_field "$manifest" bundle)
name=$(printf '%s\n' "$bundle" | sed 's#.*/##')

case "$built_at" in
  ''|*[!0-9]*) echo "builtAt 이 숫자가 아니다: $built_at" >&2; exit 64 ;;
esac
case "$name" in
  web-*.tar.gz) ;;
  *) echo "아카이브 이름이 규칙과 다르다: $name (web-<타임스탬프>.tar.gz 여야 한다)" >&2
     exit 64 ;;
esac

# **이름의 타임스탬프와 builtAt 이 같아야 한다.** 다르면 앱이 자기 번들을
# 영영 다시 받는다(설계 12장 10번).
case "$name" in
  "web-$built_at.tar.gz") ;;
  *) echo "아카이브 이름($name)과 builtAt($built_at)이 어긋난다" >&2; exit 65 ;;
esac

[ -s "$directory/$name" ] || {
  echo "아카이브가 없다: $directory/$name" >&2; exit 64; }

# 매니페스트가 말하는 해시와 실제 아카이브가 다르면 올려 봐야 수신기가 거부한다
# (그건 옳다). 미리 걸러 내려받는 사람에게 닿기 전에 멈춘다.
checksum=$(sha256sum < "$directory/$name" | cut -d ' ' -f 1)
if [ "$checksum" != "$expected" ]; then
  echo "web.json 의 sha256 과 아카이브가 다르다: 기대 $expected, 실제 $checksum" >&2
  exit 65
fi

base=${MUPID_K8S_BASE_URL:-https://j15a204.p.ssafy.io}
size=$(wc -c < "$directory/$name")
printf '웹 번들 %s 바이트, builtAt %s, sha256 %s\n' "$size" "$built_at" "$checksum"

umask 077
key=$(mktemp)
known=$(mktemp)
served_file=$(mktemp)
trap 'rm -f "$key" "$known" "$served_file"' EXIT

# GitLab 의 File 변수는 경로를, 일반 Variable 은 값을 준다. 둘 다 받는다.
# 구분하지 않고 `< "$VAR"` 로 열면 Variable 로 등록돼 있을 때 셸이 열기 실패
# 메시지에 개인키 전문을 실어 출력한다 — 실제로 겪었다.
read_secret() {
  if [ -f "$1" ]; then cat "$1"; else printf '%s\n' "$1"; fi
}
read_secret "$MUPID_DEPLOY_K8S_SSH_KEY" | tr -d '\r' > "$key"
printf '\n' >> "$key"
chmod 600 "$key"
read_secret "$MUPID_DEPLOY_K8S_KNOWN_HOSTS" | tr -d '\r' > "$known"
chmod 600 "$known"

ssh-keygen -y -P '' -f "$key" >/dev/null 2>&1 || {
  echo 'MUPID_DEPLOY_K8S_SSH_KEY 를 개인키로 읽지 못했다 (내용은 출력하지 않는다)' >&2
  exit 64
}

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "upload-web $CI_COMMIT_SHA $checksum" < "$directory/$name" || {
  # 64 는 강제 명령이 모양을 거절했다는 뜻이다. 이 동사는 **새로 늘었으므로**
  # 노드의 entrypoint 가 옛것이면 그대로 거절된다. 그 사실을 짚어 준다 —
  # 그러지 않으면 "ssh 가 64 로 끝났다" 만 남는다.
  status=$?
  if [ "$status" -eq 64 ]; then
    echo 'UPLOADER_UPGRADE_REQUIRED: 노드의 /usr/local/sbin/mupid-ssh-entrypoint-k8s 에' >&2
    echo 'upload-web 갈래가 없다. 이 동사는 이번에 늘었다.' >&2
    echo 'ssh-entrypoint-k8s.sh 와 receive-web.py 를 노드에 설치한 뒤 다시 돌린다.' >&2
  fi
  exit "$status"
}

# 실제로 내려받히는지 본다. 수신기가 성공했다고 해도 nginx 설정이나 마운트가
# 어긋나 있으면 앱은 404 를 받는다 — 그건 받는 사람이 아니라 **올린 사람이**
# 먼저 겪어야 한다.
#
# 엣지는 파드 교체 직후 잠깐 502 를 줄 수 있으므로 몇 번 다시 본다.
attempt=0
while [ "$attempt" -lt 10 ]; do
  attempt=$((attempt + 1))
  if curl --fail --silent --max-time 20 -o "$served_file" "$base/download/web.json" 2>/dev/null; then
    # 서버는 아카이브를 전개해 값을 만들었다. 내려온 값이 이번 판과 같으면
    # 올린 바이트가 그대로 도착한 것이다.
    served_at=$(json_field "$served_file" builtAt 2>/dev/null || true)
    served_sha=$(json_field "$served_file" sha256 2>/dev/null || true)
    if [ "$served_at" = "$built_at" ] && [ "$served_sha" = "$checksum" ]; then
      # 아카이브 자체도 한 번 받아 본다. 매니페스트만 맞고 파일이 404 인 경우가
      # 실제로 가능하다 — location 이 아카이브 이름 규칙과 어긋난 경우다.
      code=$(curl --silent --max-time 30 -o /dev/null -w '%{http_code}' "$base$bundle" 2>/dev/null || true)
      if [ "$code" = "200" ]; then
        printf '확인: %s%s 를 앱이 받는다 (builtAt %s)\n' "$base" "$bundle" "$built_at"
        exit 0
      fi
      echo "web.json 은 맞는데 아카이브가 $code 다: $base$bundle" >&2
    else
      echo "web.json 이 아직 이번 판이 아니다: builtAt $served_at != $built_at" >&2
    fi
  fi
  sleep 6
done

echo "올리기는 했는데 $base/download/web.json 이 이번 판($built_at, $checksum)을 돌려주지 않는다" >&2
echo "엣지 파드의 downloads 마운트와 nginx 의 /download/ location 을 본다" >&2
exit 65
