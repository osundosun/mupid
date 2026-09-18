#!/bin/sh
# 구운 APK 를 노드로 보내 nginx 가 내려주게 한다.
#
#   sh infra_k8s/ci/deploy-apk.sh
#
# 이미지 배포(deploy-k8s.sh)와 같은 계정·같은 키를 쓰지만 **다른 명령**이다.
# 강제 명령(ssh-entrypoint-k8s.sh)이 `upload-apk <커밋> <sha256>` 을 따로 받아
# receive-apk.py 로 넘긴다. 저쪽은 컨테이너 이미지를 들여와 kubectl 로 태그를
# 바꾸고, 이쪽은 파일 하나를 정해진 자리에 놓을 뿐이라 클러스터를 건드리지 않는다.
set -eu
: "${MUPID_DEPLOY_K8S_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_K8S_KNOWN_HOSTS:?GitLab file variable required}"
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

apk=${1:-.release/app/mupid.apk}
[ -s "$apk" ] || { echo "APK 가 없다: $apk — app-build 를 먼저 돌린다" >&2; exit 64; }

# **versionCode 를 APK 바이트에서 읽어 넘긴다.** 노드는 이 값을 알 방법이 없다
# (aapt2 가 없고, APK 안 tauri.conf.json 의 version 은 `0.1.0` 그대로다).
# 앱은 이 값으로만 "새 APK 가 있나" 를 판단하므로(builtAt 의 짝이다),
# `build.gradle.kts` 와 **같은 식**(MUPID_NATIVE_BUILD / 1000)으로 여기서 만든다.
# 빈 값으로 넘기면 수신기가 거부한다 — 조용히 1000 으로 굳는 것보다 낫다.
stamp=${MUPID_NATIVE_BUILD:?MUPID_NATIVE_BUILD 가 없다 — build-apk.sh 가 정한다}
case "$stamp" in
  ''|*[!0-9]*) echo "MUPID_NATIVE_BUILD 가 숫자가 아니다: $stamp" >&2; exit 64 ;;
esac
version_code=$((stamp / 1000))
# 안드로이드 상한. gradle 의 require 와 같은 값이다.
[ "$version_code" -ge 1 ] && [ "$version_code" -le 2100000000 ] || {
  echo "versionCode 가 상한을 벗어난다: $version_code (MUPID_NATIVE_BUILD 단위를 본다)" >&2
  exit 64
}

base=${MUPID_K8S_BASE_URL:-https://j15a204.p.ssafy.io}

umask 077
key=$(mktemp)
known=$(mktemp)
served_file=$(mktemp)
# 원격의 stderr 를 잡아 둔다. **실패 이유가 거기에만 있다** — 그대로 흘리면
# job 로그에 `exit code 2` 만 남고, 원인은 이미 지나간 뒤다.
err=$(mktemp)
trap 'rm -f "$key" "$known" "$served_file" "$err"' EXIT

# JSON 에서 값을 하나 꺼낸다. 셸로 긁으면 형식이 조금만 달라져도 조용히 빈 값을
# 집어 가는데, 그 실패는 "업데이트 안내가 안 뜬다" 로만 보인다.
json_field() {  # <파일> <필드>
  node -e '
    const fs = require("fs");
    const value = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))[process.argv[2]];
    if (value === undefined || value === null) process.exit(64);
    process.stdout.write(String(value));
  ' "$1" "$2"
}

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

checksum=$(sha256sum "$apk" | cut -d ' ' -f 1)
size=$(wc -c < "$apk")
printf 'APK %s 바이트, sha256 %s, versionCode %s\n' "$size" "$checksum" "$version_code"

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "upload-apk $CI_COMMIT_SHA $checksum $version_code" < "$apk" 2>"$err" || {
  status=$?
  # 원격의 말을 먼저 그대로 보여준다. 이것이 없으면 job 로그에 `exit code 2`
  # 만 남고, 그 숫자는 어느 고장인지 말해 주지 않는다 — 실제로 그렇게 실패했다.
  cat "$err" >&2
  # 64 는 강제 명령이 모양을 거절했다는 뜻이다. 원인이 거의 하나뿐이라
  # (노드의 entrypoint 가 아직 옛것) 짚어 준다.
  # Compose 경로가 같은 상황을 RECEIVER_UPGRADE_REQUIRED 로 안내한다.
  if [ "$status" -eq 64 ]; then
    echo 'UPLOADER_UPGRADE_REQUIRED: 노드의 /usr/local/sbin/mupid-ssh-entrypoint-k8s 가' >&2
    echo '옛것이다. upload-apk 이 versionCode 를 받는 새 모양을 모른다.' >&2
    echo 'ssh-entrypoint-k8s.sh 와 receive-apk.py 를 노드에 설치한 뒤 다시 돌린다.' >&2
  fi
  # sudo 가 비밀번호를 요구했다 = 수신기는 설치돼 있는데 **sudoers 규칙이 없다.**
  # 파일을 넣는 것과 sudo 를 허용하는 것은 다른 일이다. 그 사실을 짚어 준다.
  if grep -q 'a terminal is required\|a password is required' "$err"; then
    echo 'RECEIVER_NOT_PERMITTED: 노드에 수신기는 있으나 mupid-deploy-k8s 가 sudo 로' >&2
    echo '그것을 실행할 수 없다. /etc/sudoers.d/ 에 규칙이 필요하다:' >&2
    echo '  mupid-deploy-k8s ALL=(root) NOPASSWD: /usr/local/sbin/mupid-receive-apk' >&2
    echo '받는 쪽(또는 저장소의 infra_k8s/OPERATIONS-K8S.md)을 본다.' >&2
  fi
  exit "$status"
}

# 실제로 내려받히는지 본다. 수신기가 성공했다고 해도 nginx 설정이나 마운트가
# 어긋나 있으면 404 가 난다 — 그건 올린 사람이 아니라 받는 사람이 겪는다.
#
# 엣지는 파드 교체 직후 잠깐 502 를 줄 수 있으므로 몇 번 다시 본다.
attempt=0
while [ "$attempt" -lt 10 ]; do
  served=$(curl --fail --silent --max-time 20 "$base/download/mupid.apk.sha256" 2>/dev/null || true)
  case "$served" in
    "$checksum "*) ;;
    *)
      attempt=$((attempt + 1))
      sleep 6
      continue
      ;;
  esac
  # **매니페스트까지 확인한다.** 체크섬 파일만 보면 앱이 읽는 native.json 이
  # 없거나 옛 값을 들고 있어도 통과한다 — 그 증상은 "업데이트 안내가 안 뜬다"
  # 이고, 원인은 여기서 이미 지나간 뒤다.
  if curl --fail --silent --max-time 20 -o "$served_file" "$base/download/native.json" 2>/dev/null; then
    served_code=$(json_field "$served_file" versionCode 2>/dev/null || true)
    if [ "$served_code" = "$version_code" ]; then
      printf '확인: %s/download/mupid.apk 가 이번 APK 다 (versionCode %s)\n' "$base" "$version_code"
      exit 0
    fi
    echo "native.json 이 아직 이번 판이 아니다: $served_code != $version_code" >&2
  else
    echo "native.json 을 받지 못했다 — nginx 의 /download/native.json 을 본다" >&2
  fi
  attempt=$((attempt + 1))
  sleep 6
done

echo "올리기는 했는데 $base/download/mupid.apk.sha256 가 이번 체크섬을 돌려주지 않는다" >&2
echo "엣지 파드의 downloads 마운트와 nginx 의 /download/ 위치를 본다" >&2
exit 65
