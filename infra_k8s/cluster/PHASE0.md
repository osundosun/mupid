# Phase 0 · 실측 절차

목적은 하나다. **자원 배분표를 추측이 아닌 실측으로 바꾼다.**
서버를 읽기만 하며 설치·재시작·배포·파일 변경이 없다.

**2026-09-14 에 두 노드 모두 수집을 마쳤다. 결과는 [BASELINE.md](../BASELINE.md) 다.**
이 문서는 절차이며, 서버가 바뀌거나 Phase 2 를 다시 돌리기 전에 재실행한다.

최초 수집은 일회성 bash 스크립트로 했다. 지금은 같은 확인이 Ansible 의 preflight role
로 들어가 있다. 값을 읽어 사람이 판단하던 것이 **자동 단언(assert)** 이 되므로 설치
직전마다 다시 돌릴 수 있다.

## 1. 두 노드 점검

```bash
cd infra_k8s/ansible
ansible-galaxy collection install -r requirements.yml      # 최초 1회
ansible-playbook -i inventory.ini preflight.yml
```

읽기 전용이고 운영 중에 돌려도 된다. 확인하는 것:

| 단언 | 왜 |
|---|---|
| Ubuntu 24.04 | 기존 인프라가 전제하는 배포판 |
| vCPU ≥ 4, RAM ≥ 15GB | 매니페스트의 request 를 이 사양에 맞춰 잡았다 |
| cgroup v2 | v1 이면 메모리 상한 동작이 달라진다 |
| br_netfilter·overlay·vxlan·iptable_nat | flannel 이 필요로 한다 |
| 6443·10250·8472·8080·8443 미사용 | k3s 와 병행 엣지가 쓸 포트 |
| ufw 의 routed 기본값 | `deny` 면 경고를 띄운다 — 아래 |

**`ufw: deny (routed)` 경고가 핵심이다.** Pod↔Pod·Pod↔Service 패킷이 전부 FORWARD 를
지나므로, 이 상태로 k3s 를 올리면 노드는 Ready 인데 Pod 통신만 조용히 실패한다.
`cluster.yml` 의 firewall role 이 CNI 인터페이스에 한해 FORWARD 를 연다.

단언이 깨지면 그 노드에서 멈춘다. 설치 전에 막히는 편이 join 타임아웃으로 발견하는
것보다 낫다.

## 2. AI 서비스 메모리 실측 (서버가 아닌 곳에서)

`ai_server` 는 아직 어느 서버에도 없다. 모델·색인이 있는 환경에서 잰다. 색인을 전부
메모리에 올리므로 **기동 직후가 아니라 실제 추천을 몇 번 돌린 뒤**의 RSS 가 예약할 값이다.

```bash
export MUPID_AI_ARTIFACT_DIR=<아티팩트 경로>
uvicorn ai_server.app.main:app --host 127.0.0.1 --port 8100 &
pid=$!
sleep 20 && curl -s localhost:8100/health

# 콜드스타트가 아닌 경로를 반드시 한 번 이상 태운다 (이력 있는 사용자 p50 2.22초)
for i in 1 2 3; do
  curl -s -X POST localhost:8100/internal/recommendations/daily \
    -H 'content-type: application/json' \
    -d '{"recommendation_id":5001,"user_id":15,"limit":10}' > /dev/null
done

echo "RSS(KB) = $(ps -o rss= -p $pid)"
du -sh "$MUPID_AI_ARTIFACT_DIR"
kill $pid
```

`daily` 와 `next` 의 RSS 가 다르면 큰 쪽을 쓴다. 이 값으로 `base/ai.yaml` 의
`limits.memory` 를 확정한다. 노드 B 는 swap 이 없어 초과 즉시 OOMKill 이다.

컨테이너로 재도 된다. 이미지 기동 자체는 이미 확인했다 — 아티팩트가 없으면
`/health` 200 · `/internal/ready` 503 이 나오고, 이게 정상 동작이다.

```bash
docker build -f infra_k8s/image/ai.Dockerfile -t mupid-ai:local .
docker run --rm -e MUPID_AI_ARTIFACT_DIR=/artifacts \
  -v <아티팩트 경로>:/artifacts:ro -p 8100:8100 mupid-ai:local
```

## 3. 이 단계에서 하지 않는 것

- k3s 설치, 패키지 설치, sysctl·방화벽·보안그룹 변경 → Phase 2 (`ansible/cluster.yml`)
- 컨테이너 재시작, compose 파일 수정, 배포 → 전 단계에 걸쳐 없음
- `/srv/mupid/` 아래 파일 생성 → k8s 자산은 전부 `/srv/mupid-k8s/`
