#!/bin/sh
# 잘라 낸 추천 색인을 노드로 보낸다.
#
#   MUPID_DEPLOY_K8S_SSH_KEY=~/.ssh/mupid-deploy-k8s \
#   MUPID_DEPLOY_K8S_KNOWN_HOSTS=~/.ssh/mupid-known-hosts \
#   sh infra_k8s/ci/deploy-index.sh [색인 디렉터리]
#
# **CI job 이 아니다.** 전체 색인 537MB 는 저장소에도 러너에도 없다 — GPU 배치가
# 만들어 그 장비에 있다. 그래서 이 스크립트는 산출물을 들고 있는 쪽에서 사람이
# 돌린다. `infra_k8s/ci/` 에 두는 것은 같은 배포 키·같은 강제 명령을 쓰기 때문이다.
#
# 손으로 scp 하던 것과 무엇이 다른가 — 체크섬을 맞추고, 받는 쪽이 파드와 **같은
# 무결성 검사**를 미리 돌리고, 교체가 원자적이고, 판마다 커밋이 붙어 되돌릴 수 있다.
#
# 입력은 `python -m ai_server.tools.slice_index --out <디렉터리>` 의 출력이다.
#
# 강제 명령(ssh-entrypoint-k8s.sh)이 `upload-index <커밋> <sha256>` 을 받아
# receive-index.py 로 넘긴다. 받는 쪽은 새 판을 옆에 풀고 current 심볼릭 링크를
# 원자적으로 갈아 끼운다 — 한 파일씩 덮으면 그 사이에 기동한 파드가 서로 다른 판의
# 색인을 섞어 읽고, 그때 서비스는 정상처럼 뜨면서 엉뚱한 곡을 추천한다.
set -eu
: "${MUPID_DEPLOY_K8S_SSH_KEY:?배포 개인키 (파일 경로 또는 값)}"
: "${MUPID_DEPLOY_K8S_KNOWN_HOSTS:?노드의 known_hosts (파일 경로 또는 값)}"
# CI 밖에서도 돈다. 판을 식별할 커밋이 필요하니 없으면 현재 HEAD 를 쓴다 —
# 색인을 올린 시점의 코드가 무엇이었는지가 되돌릴 때의 유일한 단서다.
commit=${CI_COMMIT_SHA:-$(git rev-parse HEAD 2>/dev/null || true)}
printf '%s\n' "${commit:?커밋을 알 수 없다 — CI_COMMIT_SHA 를 지정한다}" \
  | grep -Eq '^[0-9a-f]{40}$'

index=${1:-.release/ai-index}
inner=$index/data/proc/mupid
for name in moments.parquet moments_emb.npy features_v1.npz stage1_section.npy; do
  [ -s "$inner/$name" ] || {
    echo "색인이 없다: $inner/$name" >&2
    echo '먼저 전체 색인이 있는 곳에서 아래를 돌린다:' >&2
    echo "  python -m ai_server.tools.slice_index --out $index" >&2
    exit 64
  }
done

umask 077
key=$(mktemp)
known=$(mktemp)
bundle=$(mktemp)
trap 'rm -f "$key" "$known" "$bundle"' EXIT

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

# 재현 가능한 묶음. 같은 색인이면 같은 체크섬이어야 로그를 비교할 수 있다.
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
  -czf "$bundle" -C "$index" data
checksum=$(sha256sum < "$bundle" | cut -d ' ' -f 1)
printf '색인 묶음 %s 바이트, sha256 %s\n' "$(wc -c < "$bundle")" "$checksum"

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "upload-index $commit $checksum" < "$bundle"

# 파드는 기동 시점의 심볼릭 링크를 따라간다. 갈아 끼우기만 하면 **도는 파드는 계속
# 옛 판을 읽는다** — 올렸는데 반영이 안 된 것처럼 보이는 전형적인 자리다.
#
# replicas 가 0 이면(아직 안 띄웠다) 롤아웃할 대상이 없다. 그건 실패가 아니다.
echo '색인 교체 완료. 파드가 새 판을 읽으려면 롤아웃이 필요하다:'
echo '  kubectl -n mupid rollout restart deployment/ai'
echo '(ai 는 아직 replicas: 0 이다 — 띄운 뒤부터 이 단계가 의미를 가진다)'
