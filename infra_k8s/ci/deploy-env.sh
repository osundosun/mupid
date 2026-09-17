#!/bin/sh
# 운영 환경변수를 노드로 보낸다.
#
#   sh infra_k8s/ci/deploy-env.sh
#
# MUPID_API_ENV (File 형식·보호된 CI 변수) 의 내용을 그대로 보낸다. 노드의
# root 수신기가 Secret 을 갈아 끼우고 api 를 재시작한다.
#
# **CI 는 Secret 을 직접 건드리지 않는다.** 배포 계정의 RBAC 은 secrets 를
# 막고 있고, 열면 CI 키를 훔친 쪽이 DB 비밀번호·JWT 서명키·S3 키를 전부 읽게
# 된다. 그래서 내용만 보내고 쓰는 것은 노드가 한다.
#
# 내용이 예전과 같으면 수신기가 UNCHANGED 를 돌려주고 아무것도 하지 않는다.
# 그래서 master 푸시마다 돌려도 api 가 매번 재시작하지 않는다.
set -eu
: "${MUPID_API_ENV:?GitLab file variable required}"
: "${MUPID_DEPLOY_K8S_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_K8S_KNOWN_HOSTS:?GitLab file variable required}"
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

umask 077
key=$(mktemp)
known=$(mktemp)
body=$(mktemp)
trap 'rm -f "$key" "$known" "$body"' EXIT

# File 변수는 경로를, 일반 Variable 은 값을 준다. 둘 다 받는다 — 구분하지 않고
# `< "$VAR"` 로 열면 Variable 일 때 셸이 오류 메시지에 내용을 실어 출력한다.
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

# CR 을 걷어낸다. 윈도우에서 만든 내용을 붙여 넣으면 값 끝에 \r 이 붙고,
# 그러면 비밀번호가 조용히 틀린 값이 된다.
read_secret "$MUPID_API_ENV" | tr -d '\r' > "$body"
lines=$(grep -cE '^MUPID_[A-Z0-9_]+=' "$body" || true)
[ "$lines" -ge 10 ] || {
  echo "MUPID_API_ENV 에서 MUPID_* 항목을 $lines 개만 찾았다 — 내용을 확인한다" >&2
  exit 64
}
checksum=$(sha256sum < "$body" | cut -d ' ' -f 1)
printf '보내는 항목 %s개\n' "$lines"

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "upload-env $CI_COMMIT_SHA $checksum" < "$body"
