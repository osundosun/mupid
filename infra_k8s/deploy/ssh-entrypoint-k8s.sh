#!/usr/bin/env bash
# Forced command for the k8s deployment account. Installed as
#   command="/usr/local/sbin/mupid-ssh-entrypoint-k8s",no-port-forwarding,\
#   no-agent-forwarding,no-X11-forwarding,no-pty ssh-ed25519 AAAA...
# in ~mupid-deploy-k8s/.ssh/authorized_keys.
#
# The key never gets a shell. SSH_ORIGINAL_COMMAND is matched against one exact
# shape and anything else is refused, so a stolen CI key can upload a signed
# image and nothing else.
#
# Separate from infra/deploy/ssh-entrypoint.sh and a separate account: the
# Compose endpoint keeps working untouched until it is retired.
set -euo pipefail
command=${SSH_ORIGINAL_COMMAND:-}

# deploy-k8s <component> <40-hex commit> <64-hex sha256>
# The component is an enumeration, not a free string: it selects a workload and
# a container name inside the receiver, and both come from this list.
if [[ $command =~ ^deploy-k8s\ (api|web|ai)\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-k8s \
    "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
fi

# upload-apk <40-hex commit> <64-hex sha256>
# 안드로이드 APK 를 /srv/mupid-k8s/downloads 에 둔다. 이미지 경로와 **다른
# 수신기**로 보낸다 — 저쪽은 컨테이너 이미지를 들여와 kubectl 로 태그를 바꾸고,
# 이쪽은 파일 하나를 정해진 자리에 놓을 뿐이라 클러스터를 건드리지 않는다.
# 합쳐 두면 감사해야 할 코드가 넓어진다.
if [[ $command =~ ^upload-apk\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-apk \
    "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi

# upload-env <40-hex commit> <64-hex sha256>
# 운영 환경변수를 갈아 끼우고 api 를 재시작한다. **CI 에 secrets 권한을 주지
# 않으려고** 노드의 root 가 대신 쓴다 — 배포 계정의 RBAC 은 secrets 를 막고
# 있고, 열면 CI 키를 훔친 쪽이 DB 비밀번호와 JWT 서명키를 읽는다.
if [[ $command =~ ^upload-env\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-env \
    "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi

# apply-manifests <40-hex commit> <64-hex sha256>
# 오버레이 전체를 적용한다. **이 동사가 셋 중 가장 힘이 세다** — 이미지 수신기는
# 태그만, 환경변수 수신기는 Secret 하나만 건드리지만 여기는 클러스터의 모양을
# 바꾼다. 수신기가 받은 내용을 그대로 믿지 않고 경로·네임스페이스를 검사하고,
# 적용은 반드시 mupid-apply 를 거친다(placeholder 함정 때문).
if [[ $command =~ ^apply-manifests\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-manifests \
    "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi

# upload-index <40-hex commit> <64-hex sha256>
# 추천 색인(풀만 잘라 낸 약 80MB)을 노드에 놓는다. **클러스터를 건드리지 않는다** —
# 파일을 풀어 놓고 current 심볼릭 링크를 원자적으로 갈아 끼울 뿐이다. 새 판을 파드가
# 보게 하는 롤아웃은 CI 가 별도로 지시한다.
#
# 지금까지 이 디렉터리는 사람이 채웠다. 매니페스트를 손으로 적용하던 때와 같은 모양이고,
# 그때 "고쳤는데 클러스터에 닿지 않은" 사고가 하루 세 번 났다.
if [[ $command =~ ^upload-index\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-index     "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi

echo 'Only versioned k8s deployment uploads are accepted' >&2
exit 64
