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
kube kustomize "$overlay" > "$rendered"

# 태그를 **클러스터에서** 읽는다. 파일(`current-<component>`)은 이미지를 받은
# 노드에만 생기는데, ai 는 node-b 가 받고 매니페스트는 node-a 가 적용한다 —
# 그래서 파일만 보면 ai 의 태그를 못 찾거나, 더 나쁘게는 **옛 태그를 쓴다.**
# 실제로 그 일이 났다: node-a 의 current-ai 가 한 판 뒤처져 있었고, 그 태그가
# 클러스터에 적용되어 node-b 에 없는 이미지를 요구하게 됐다.
#
# 파일은 보조로 남긴다. 최초 설치처럼 워크로드가 아직 없을 때를 위해서다.
#
# **파일로 넘긴다.** `python3 - <<'PY'` 는 heredoc 이 stdin 을 차지하므로
# 파이프로 준 데이터가 프로그램에게 닿지 않는다 — 조용히 빈 값이 되어, 클러스터를
# 본다고 적어 놓고 실제로는 파일만 보고 있었다.
live=$(mktemp)
trap 'rm -f "$rendered" "$live"' EXIT
kube -n mupid get deploy -o 'jsonpath={range .items[*]}{.metadata.name}={.spec.template.spec.containers[0].image}{"\n"}{end}' > "$live" 2>/dev/null || true

# 치환 결과를 잡아 둔다. 끝의 확인이 "최초 설치라 봐준 것" 인지 판단해야 한다.
subst=$(python3 - "$rendered" "$root" "$live" <<'PY'
import pathlib, re, sys

rendered, root, live_path = (pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]),
                             pathlib.Path(sys.argv[3]))
text = rendered.read_text(encoding="utf-8")

# 컴포넌트 이름과 Deployment 이름이 같지 않다. web 은 클러스터에서 `edge` 다 —
# 이 한 글자 차이로 클러스터 조회가 조용히 빗나간다.
WORKLOAD = {"api": "api", "web": "edge", "ai": "ai"}

live = {}
if live_path.exists():
    for line in live_path.read_text(encoding="utf-8").splitlines():
        name, _, image = line.strip().partition("=")
        if name and image:
            live[name] = image

replaced, missing = [], []
for component in ("api", "web", "ai"):
    placeholder = f"mupid-{component}:placeholder"
    if placeholder not in text:
        continue
    shape = re.compile(rf"mupid-{component}:[0-9a-f]{{40}}")
    tag = source = None
    candidate = live.get(WORKLOAD[component], "")
    if shape.fullmatch(candidate):
        tag, source = candidate, "클러스터"
    else:
        recorded = root / f"current-{component}"
        if recorded.exists():
            candidate = recorded.read_text(encoding="utf-8").strip()
            if not shape.fullmatch(candidate):
                raise SystemExit(
                    f"{recorded} 의 내용이 이미지 태그 모양이 아니다: {candidate!r}")
            tag, source = candidate, str(recorded)
    if tag is None:
        missing.append(component)
        continue
    text = text.replace(placeholder, tag)
    replaced.append(f"  {placeholder} -> {tag}  ({source})")

rendered.write_text(text, encoding="utf-8")
for line in replaced:
    print(line)
for component in missing:
    print(f"  mupid-{component}: 태그를 찾지 못했다 — 최초 설치로 보고 placeholder 로 둔다")
PY
)
printf '%s\n' "$subst"

kube apply -f "$rendered"

# 적용 뒤에 남아 있으면 안 된다. 최초 설치라면 위에서 이미 그렇게 말했다.
echo "=== placeholder 로 남은 워크로드 ==="
stuck=$(kube -n mupid get deploy,statefulset \
  -o jsonpath='{range .items[*]}{.metadata.name} {.spec.template.spec.containers[*].image}{"\n"}{end}' \
  | grep ':placeholder' || true)
if [ -n "$stuck" ]; then
  printf '%s\n' "$stuck" | sed 's/^/  /'
  # **실패로 끝낸다.** 예전에는 경고만 찍고 성공으로 끝났고, 그래서 ai 가
  # placeholder 로 올라간 배포가 초록으로 지나갔다. maxUnavailable 이 0 이라
  # 옛 Pod 가 계속 서빙해 겉으로는 멀쩡해 보이는 것이 이 함정의 핵심이다.
  #
  # 최초 설치라면 위에서 "태그를 찾지 못했다" 고 이미 말했다. 그 경우까지
  # 막으면 부트스트랩이 안 되므로, 방금 그렇게 말한 컴포넌트만 봐준다.
  for name in $(printf '%s\n' "$stuck" | awk '{print $1}'); do
    if ! printf '%s' "$subst" | grep -q "mupid-$name: 태그를 찾지 못했다"; then
      echo "  $name 은 태그가 있는데도 placeholder 로 남았다 — 실패다" >&2
      exit 65
    fi
  done
  echo "  (최초 설치 — 첫 배포가 채운다)"
else
  echo "  없음"
fi
