#!/bin/sh
# 매니페스트를 노드로 보내 적용시킨다.
#
#   sh infra_k8s/ci/deploy-manifests.sh
#
# 저장소에서 매니페스트를 고쳐 머지해도 클러스터는 모른다. 사람이 노드에 올려
# mupid-apply 를 돌려야 반영된다. 그 탓에 하루에 세 번 같은 사고가 났다 —
# Secret 은 갱신됐는데 파드가 그 키를 안 읽고, nginx 수정이 k8s 사본에만 없고,
# APK 는 올라갔는데 /download/ 가 404 였다.
#
# **적용은 노드의 root 가 한다.** CI 는 묶음을 보내기만 한다. 배포 계정의 RBAC 은
# 그대로 좁게 두고(이미지 태그만), 클러스터를 바꾸는 일은 노드에 남긴다 —
# 환경변수 수신기와 같은 구조다.
#
# `kubectl apply -k` 를 직접 쓰지 않는다. 오버레이를 그대로 적용하면 이미지
# 태그가 placeholder 로 덮여, 겉보기엔 멀쩡한데 파드가 죽는 순간 서비스가
# 내려간다. 수신기가 mupid-apply 를 거치게 되어 있다.
set -eu
: "${MUPID_DEPLOY_K8S_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_K8S_KNOWN_HOSTS:?GitLab file variable required}"
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

[ -d infra_k8s/base ] && [ -d infra_k8s/overlays ] || {
  echo 'infra_k8s/base 또는 overlays 가 없다' >&2; exit 64; }

umask 077
key=$(mktemp)
known=$(mktemp)
bundle=$(mktemp)
trap 'rm -f "$key" "$known" "$bundle"' EXIT

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

# 보내는 것은 매니페스트뿐이다. 수신기도 이 두 경로 밖은 거부한다.
# 재현 가능한 묶음을 만든다 — 같은 내용이면 같은 체크섬이어야 로그를 비교할 수 있다.
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
  -czf "$bundle" infra_k8s/base infra_k8s/overlays
checksum=$(sha256sum < "$bundle" | cut -d ' ' -f 1)
printf '매니페스트 묶음 %s 바이트, sha256 %s\n' "$(wc -c < "$bundle")" "$checksum"

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "apply-manifests $CI_COMMIT_SHA $checksum" < "$bundle"
