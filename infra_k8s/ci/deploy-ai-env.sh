#!/bin/sh
# 추천 서버 환경변수를 노드로 보낸다.
#
#   sh infra_k8s/ci/deploy-ai-env.sh
#
# MUPID_AI_ENV (File 형식·보호된 CI 변수) 의 내용을 그대로 보낸다. 노드의 root
# 수신기가 Secret `mupid-ai-secrets` 를 갈아 끼우고 **ai 만** 재시작한다.
#
# ## 왜 MUPID_API_ENV 와 나누나
#
# 추천 서버가 Spring 내부 카탈로그 API 를 부르게 되면서 ROLE_SYSTEM 리프레시
# 토큰을 들고 있어야 한다. 그 값을 `MUPID_API_ENV` 에 섞으면 **Spring 파드도 함께
# 받는다** — Spring 은 쓰지 않는 자격이고, 한 곳이 새면 둘 다 새는 모양이 된다.
# 나눠 두면 각 파드가 제 몫만 본다.
#
# 재시작 범위도 나뉜다. AI 값 하나 고치자고 `MUPID_API_ENV` 를 건드리면 api 까지
# 함께 롤아웃된다.
#
# **CI 는 Secret 을 직접 건드리지 않는다.** 배포 계정의 RBAC 은 secrets 를 막고
# 있다 — 열면 CI 키를 훔친 쪽이 DB 비밀번호·JWT 서명키를 전부 읽게 된다. 그래서
# 내용만 보내고 쓰는 것은 노드가 한다 (`deploy-env.sh` 와 같은 구조).
#
# 내용이 예전과 같으면 수신기가 UNCHANGED 를 돌려주고 아무것도 하지 않는다.
set -eu
: "${MUPID_AI_ENV:?GitLab file variable required}"
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
# 그러면 토큰이 조용히 틀린 값이 된다.
read_secret "$MUPID_AI_ENV" | tr -d '\r' > "$body"
lines=$(grep -cE '^MUPID_[A-Z0-9_]+=' "$body" || true)
[ "$lines" -ge 2 ] || {
  echo "MUPID_AI_ENV 에서 MUPID_* 항목을 $lines 개만 찾았다 — 내용을 확인한다" >&2
  exit 64
}
checksum=$(sha256sum < "$body" | cut -d ' ' -f 1)
printf '보내는 항목 %s개\n' "$lines"

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "upload-ai-env $CI_COMMIT_SHA $checksum" < "$body"
