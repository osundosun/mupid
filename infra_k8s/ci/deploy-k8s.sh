#!/bin/sh
# Transmit one image to the k8s deployment endpoint.
#
#   sh infra_k8s/ci/deploy-k8s.sh api
#   sh infra_k8s/ci/deploy-k8s.sh web
#
# Separate key and separate account from infra/ci/deploy.sh: the Compose
# endpoint keeps its own credential until it is retired, so neither key can
# drive the other path.
#
# There is no `resume` mode. The Compose receiver has one because a re-run must
# not rebuild an image that is already stored; here a repeat of the same commit
# is answered by the receiver with ALREADY_DEPLOYED after it re-checks the
# rollout, which costs one upload and keeps this script to one shape.
set -eu
: "${MUPID_DEPLOY_K8S_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_K8S_KNOWN_HOSTS:?GitLab file variable required}"
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

component=${1:?Expected api, web or ai}

# **이미지는 파드가 뜨는 노드에 있어야 한다.** 레지스트리가 없어서 각 노드의
# containerd 로 직접 들여오기 때문이다. api·web 은 node-a 에 뜨지만 ai 는
# `nodeSelector: mupid.io/role=ai` 로 node-b 에 묶여 있다.
#
# 처음에 그걸 놓쳐서 이미지를 node-a 에만 넣었고, 파드는 node-b 에서
# **ErrImagePull** 로 멈췄다 — 배포 job 은 성공으로 끝난 뒤였다. api·web 이
# node-a 에 떠서 여태 드러나지 않은 구멍이다.
#
# 노드 주소는 변수로 받는다. 기본값은 지금까지 쓰던 엔드포인트(node-a)라
# api·web 의 동작이 바뀌지 않고, ai 만 지정이 필요하다.
endpoint=${MUPID_DEPLOY_K8S_SSH_HOST:-mupid-deploy-k8s@j15a204.p.ssafy.io}
case "$component" in
  api) archive=.release/image.tar.gz ;;
  web) archive=.release/web/image.tar.gz ;;
  ai)  archive=.release/ai/image.tar.gz
       endpoint=${MUPID_AI_NODE_SSH:?ai 는 node-b 에 뜬다 — 그 노드의 배포 주소가 필요하다} ;;
  *) echo 'Unknown component' >&2; exit 64 ;;
esac
[ -s "$archive" ] || { echo "No archive at $archive; run the package job first" >&2; exit 64; }

# The edge answers on 8443 until it takes 80/443, so the verification target is
# a variable rather than the production URL.
base=${MUPID_K8S_BASE_URL:-https://j15a204.p.ssafy.io}

umask 077
key=$(mktemp)
known=$(mktemp)
trap 'rm -f "$key" "$known"' EXIT

# GitLab 의 File 변수는 경로를, 일반 Variable 은 값을 준다. 둘 다 받는다.
# 구분하지 않고 `< "$VAR"` 로 열면 Variable 로 등록돼 있을 때 셸이 열기 실패
# 메시지에 **개인키 전문을 실어 출력한다** — 실제로 job 로그에 찍혀 키를 폐기해야
# 했다. 어느 쪽이든 내용이 화면에 나오지 않게 한다.
read_secret() {
  if [ -f "$1" ]; then cat "$1"; else printf '%s\n' "$1"; fi
}
read_secret "$MUPID_DEPLOY_K8S_SSH_KEY" | tr -d '\r' > "$key"
printf '\n' >> "$key"
chmod 600 "$key"
read_secret "$MUPID_DEPLOY_K8S_KNOWN_HOSTS" | tr -d '\r' > "$known"
chmod 600 "$known"

# 키가 성한지 먼저 본다. 출력과 오류를 모두 버려 내용이 새지 않게 한다.
ssh-keygen -y -P '' -f "$key" >/dev/null 2>&1 || {
  echo 'MUPID_DEPLOY_K8S_SSH_KEY 를 개인키로 읽지 못했다 (내용은 출력하지 않는다)' >&2
  exit 64
}

(cd "$(dirname "$archive")" && sha256sum -c image.tar.gz.sha256)
checksum=$(sha256sum "$archive" | cut -d ' ' -f 1)

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" "$endpoint" \
  "deploy-k8s $component $CI_COMMIT_SHA $checksum" < "$archive"

# The receiver already waited for the rollout; this confirms the change is
# visible through the edge rather than only inside the cluster.
#
# 재시도가 필요하다. 수신기가 DEPLOYED 를 반환한 직후에도 엣지를 통한 첫 요청이
# 502 를 받을 수 있다 — Pod 가 교체되면 Service 엔드포인트가 갱신되기까지 시차가
# 있다. 실제로 배포가 끝났는데 이 확인 한 번이 502 를 맞아 job 이 실패했다.
# 배포 자체는 이미 성공한 뒤이므로, 여기서 한 번 실패했다고 되돌릴 일이 아니다.
confirm() {
  attempt=1
  while [ "$attempt" -le 10 ]; do
    if "$@"; then
      return 0
    fi
    echo "확인 재시도 $attempt/10 (엔드포인트 갱신 대기)" >&2
    attempt=$((attempt + 1))
    sleep 6
  done
  echo "배포는 끝났으나 엣지를 통한 확인이 60초 안에 성공하지 못했다" >&2
  return 1
}

check_api() {
  curl --fail --silent --show-error --max-time 15 "$base/api/actuator/health" 2>/dev/null \
    | grep -q '"status":"UP"'
}
check_web() {
  test "$(curl --fail --silent --max-time 15 "$base/deployment.txt" 2>/dev/null)" = "$CI_COMMIT_SHA"
}

case "$component" in
  api) confirm check_api ;;
  web) confirm check_web ;;
  # ai 는 **엣지로** 확인하지 않는다. nginx 가 /internal 을 밖으로 내보내지 않아
  # 러너에서 닿을 길이 없다. 대신 수신기가 노드에서 `kubectl rollout status` 를
  # 기다리고, replicas 가 1 이 된 지금은 그것이 곧 **readinessProbe 통과**를 뜻한다
  # (`/internal/ready` — 색인 네 개가 행 수까지 맞고 카탈로그가 올라왔는가).
  # replicas 가 0 이던 때에는 rollout status 가 즉시 성공해 아무 의미가 없었다.
  ai)  echo 'ai: 준비 여부는 노드의 rollout status 가 확인했다 (엣지 경로 없음)' ;;
esac
