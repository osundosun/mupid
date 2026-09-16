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
if [[ $command =~ ^deploy-k8s\ (api|web)\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-k8s \
    "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
fi

# upload-env <40-hex commit> <64-hex sha256>
# 운영 환경변수를 갈아 끼우고 api 를 재시작한다. **CI 에 secrets 권한을 주지
# 않으려고** 노드의 root 가 대신 쓴다 — 배포 계정의 RBAC 은 secrets 를 막고
# 있고, 열면 CI 키를 훔친 쪽이 DB 비밀번호와 JWT 서명키를 읽는다.
if [[ $command =~ ^upload-env\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-env \
    "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi

echo 'Only versioned k8s deployment uploads are accepted' >&2
exit 64
