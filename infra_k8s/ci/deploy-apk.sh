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

base=${MUPID_K8S_BASE_URL:-https://j15a204.p.ssafy.io}

umask 077
key=$(mktemp)
known=$(mktemp)
trap 'rm -f "$key" "$known"' EXIT

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
printf 'APK %s 바이트, sha256 %s\n' "$size" "$checksum"

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "upload-apk $CI_COMMIT_SHA $checksum" < "$apk"

# 실제로 내려받히는지 본다. 수신기가 성공했다고 해도 nginx 설정이나 마운트가
# 어긋나 있으면 404 가 난다 — 그건 올린 사람이 아니라 받는 사람이 겪는다.
#
# 엣지는 파드 교체 직후 잠깐 502 를 줄 수 있으므로 몇 번 다시 본다.
attempt=0
while [ "$attempt" -lt 10 ]; do
  served=$(curl --fail --silent --max-time 20 "$base/download/mupid.apk.sha256" 2>/dev/null || true)
  case "$served" in
    "$checksum "*) printf '확인: %s/download/mupid.apk 가 이번 APK 다\n' "$base"; exit 0 ;;
  esac
  attempt=$((attempt + 1))
  sleep 6
done

echo "올리기는 했는데 $base/download/mupid.apk.sha256 가 이번 체크섬을 돌려주지 않는다" >&2
echo "엣지 파드의 downloads 마운트와 nginx 의 /download/ 위치를 본다" >&2
exit 65
