#!/usr/bin/env bash
# 매니페스트를 적용한다. **`kubectl apply -k` 를 직접 쓰지 않는다.**
#
#   sudo infra_k8s/deploy/apply.sh /path/to/infra_k8s/overlays/cutover
#
# base/ 의 이미지 태그는 `mupid-api:placeholder` 다. 배포는 `kubectl set image`
# 로 태그만 바꾸므로, 오버레이를 그대로 적용하면 running 이미지가 placeholder 로
# 덮인다. 그러면 새 ReplicaSet 이 `ErrImageNeverPull` 로 멈추는데, maxUnavailable
# 이 0 이라 옛 Pod 가 계속 서빙해서 **겉으로는 멀쩡해 보인다.** 그 Pod 가 죽거나
# 노드가 재부팅되는 순간 서비스가 내려간다.
#
# 이 함정은 OPERATIONS-K8S.md 에 적어 두었는데도 실제로 밟았다. 문서로는 부족해서
# 경로 자체를 바꾼다: 렌더링한 뒤 기록된 태그로 치환해 적용하므로, placeholder 가
# 클러스터에 올라가는 순간이 없다.
set -euo pipefail

overlay=${1:-}
[ -n "$overlay" ] && [ -d "$overlay" ] || {
  echo "usage: $0 <overlay directory>" >&2
  exit 64
}

root=${MUPID_K8S_ROOT:-/srv/mupid-k8s}
export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}

kube() {
  if command -v kubectl >/dev/null 2>&1; then kubectl "$@"; else k3s kubectl "$@"; fi
}

rendered=$(mktemp)
trap 'rm -f "$rendered"' EXIT
kube kustomize "$overlay" > "$rendered"

# 기록이 없으면 최초 설치다. 그때는 placeholder 가 맞다 — 첫 배포가 채운다.
python3 - "$rendered" "$root" <<'PY'
import pathlib, re, sys

rendered, root = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
text = rendered.read_text(encoding="utf-8")
replaced, missing = [], []
for component in ("api", "web"):
    recorded = root / f"current-{component}"
    placeholder = f"mupid-{component}:placeholder"
    if placeholder not in text:
        continue
    if not recorded.exists():
        missing.append(component)
        continue
    tag = recorded.read_text(encoding="utf-8").strip()
    if not re.fullmatch(rf"mupid-{component}:[0-9a-f]{{40}}", tag):
        raise SystemExit(f"{recorded} 의 내용이 이미지 태그 모양이 아니다: {tag!r}")
    text = text.replace(placeholder, tag)
    replaced.append(f"  {placeholder} -> {tag}")

rendered.write_text(text, encoding="utf-8")
for line in replaced:
    print(line)
for component in missing:
    print(f"  mupid-{component}: 기록 없음 — 최초 설치로 보고 placeholder 로 둔다")
PY

kube apply -f "$rendered"

# 적용 뒤에 남아 있으면 안 된다. 최초 설치라면 위에서 이미 그렇게 말했다.
echo "=== placeholder 로 남은 워크로드 ==="
stuck=$(kube -n mupid get deploy,statefulset \
  -o jsonpath='{range .items[*]}{.metadata.name} {.spec.template.spec.containers[*].image}{"\n"}{end}' \
  | grep ':placeholder' || true)
if [ -n "$stuck" ]; then
  printf '%s\n' "$stuck" | sed 's/^/  /'
  echo "  (기록이 있는데도 남아 있다면 실패다)" >&2
else
  echo "  없음"
fi
