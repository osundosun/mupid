# k8s 배포 옵션 구축 계획

작성일: 2026-09-14. 기존 Compose 배포를 **대체하지 않는다.** `infra/` 는 손대지 않고
이 폴더에만 k8s 경로를 추가한다. 컷오버는 별도 승인 사항이며 이 계획의 완료 조건이 아니다.

## 결정된 사항

| 항목 | 결정 | 이유 |
|---|---|---|
| 전환 범위 | 기존 배포 유지 + k8s 옵션 병행 | 운영 중인 경로를 건드리지 않고 검증한다 |
| 배포판 | k3s | 컨트롤플레인 ~1GB. 15.4GiB 2노드에 맞다. local-path 내장 |
| 엣지·TLS | 기존 `infra/deploy/nginx.conf` 를 ConfigMap 으로 그대로 | `auth_request`·`X-Accel-Redirect` 가 검증된 구성이다. 재작성하지 않는다 |
| 이미지 전달 | 기존 SSH tar 유지 → `k3s ctr images import` | 강제 명령·체크섬 보안 모델을 그대로 쓴다. Registry 미도입 |

## 병행 운영의 제약 (설계 전제)

1. **포트가 겹친다.** 운영 Compose 가 80/443 을 잡고 있다. k8s 엣지는 검증 기간 동안
   8080/8443 으로 띄운다. Compose 쪽은 이미 `${MUPID_HTTP_PORT}` 로 매개변수화돼 있어
   컷오버 시 값만 바꿔 넘긴다. 그 밖의 충돌 후보: 9000/9009(CH), 9181(Keeper), 9092(Kafka).
2. **볼륨을 공유하면 안 된다.** 같은 MySQL 데이터 디렉터리를 두 프로세스가 잡으면 깨진다.
   k8s 쪽은 `mupid-k8s-*` 볼륨을 새로 만들고 **백업 복원으로 데이터를 채운다.**
   기존 `mupid-mysql-data` 를 절대 마운트하지 않는다.
3. **서버 경로를 분리한다.** `/srv/mupid/` 는 읽지도 쓰지도 않는다. k8s 자산은
   `/srv/mupid-k8s/` 아래에 둔다. 예외는 인증서 `/etc/letsencrypt` 읽기 전용 공유뿐이다
   (certbot 은 계속 호스트 소유, 경합 없음).
4. **이미지는 노드마다 import 해야 한다.** Registry 가 없으므로 워크로드를
   `nodeSelector` 로 노드에 고정하고, 그 노드에만 밀어 넣는다. 태그는 커밋 SHA 고정,
   `imagePullPolicy: IfNotPresent`.
5. **k3s 는 Secret 을 평문 저장한다.** `--secrets-encryption` 을 켜고, Secret 은 관리자가
   서버에서 `--from-env-file` 로 만든다. 저장소에 올리지 않는다.
6. **보안그룹이 부족하다.** 노드 간 6443 / 8472(udp, flannel) / 10250 을 상대 노드 IP 에만
   추가로 열어야 한다. 인터넷에 열지 않는다.

## 자원 배분 초안

swap 이 없어 초과분은 즉시 OOMKill 이다.

**두 노드 모두 실측을 마쳤다 — 근거는 `BASELINE.md` 다.** 사양이 같다: 각 **4 vCPU /
15,785MB / 309G**, 같은 VPC `172.26.0.0/16`(A 172.26.6.231, B 172.26.0.168).

| | 노드 A (기본) | 노드 B (추가) |
|---|---|---|
| available 메모리 | 13,849MB | **15,004MB** |
| 현재 실사용 | 컨테이너 5개 합계 **1.1GB** | Runner 1개 **20MB** |
| Load | 0.12 | **0.00** |
| 호스트 점유 포트 | 22·80·443 | 22 |

**제약은 메모리가 아니라 CPU 다.** 양쪽 다 4 vCPU 뿐이라 k8s `requests.cpu` 합계를
노드당 2.0 코어 이하로 잡아야 Pod 가 Pending 에 걸리지 않는다. request 는 실사용이 아니라
예약이기 때문이다.

| Node A · 엣지+상태저장 | request | limit | Node B · AI | request | limit |
|---|---|---|---|---|---|
| k3s server | — | ~1.0G | k3s agent | — | ~0.5G |
| edge nginx | 64M / 100m | 256M | **ai_server** | 2.0G / 1500m | **8.0G / 3000m** |
| api (Spring) | 700M / 500m | 2.0G | Runner(호스트 유지) | — | 512M |
| mysql | 600M / 500m | 2.0G | | | |
| redis | 64M / 100m | 512M | | | |
| storage | 200M / 200m | 1.0G | | | |
| **합계** | **1.6G / 1.4코어** | ~6.8G | **합계** | 2.0G / 1.5코어 | ~8.5G |

노드 A 는 이 표대로 **기존 Compose 스택과 병행 기동이 된다.** 노드 B 는 거의 비어 있어
`ai_server` 에 여유가 크다. ClickHouse(4G)·Kafka(2G)는 병행 기간에 올릴 자리가 없고,
계획대로 범위 밖이다(Phase 6 이후). 노드 B 의 9092·9000·9009·9181 이 모두 비어 있는 것으로
보아 analytics profile 은 한 번도 기동된 적이 없다.

`ai_server` RSS 만 아직 미실측이다. 노드 B 여유가 커서 계획을 뒤집을 값은 아니고
`limits.memory` 를 확정하는 용도다.

## 단계

### Phase 0 · 실측 (서버 읽기 전용)
- 두 노드 vCPU 수 — 문서에 없다. 현재 알려진 건 RAM 15.4GiB, 디스크 여유 약 300GB 뿐이다.
- `infra/deploy/resource-report.sh` 재실행으로 현재 점유 갱신.
- `ai_server` 기동 후 RSS 와 아티팩트 디렉터리 실제 용량.
- 호스트 게시 포트 전수 조사(위 충돌표 확정).
- 산출: `infra_k8s/BASELINE.md`. **서버 변경 없음.**

### Phase 1 · 매니페스트·이미지 (로컬 작업, 서버 무변경)
- `ai_server` 용 Dockerfile 신규 작성 — **지금 없다.** python 3.12-slim + CPU 전용 torch.
- kustomize base + overlay 작성. `kustomize build` 와 정적 검사가 로컬에서 통과할 것.
- readiness 는 `/internal/ready`, liveness 는 `/health`. ready 는 MySQL 매핑 전까지 503 이
  정상이므로 기동 순서와 초기 실패를 문서에 명시한다.
- 산출: `base/`, `overlays/`, `image/ai.Dockerfile`, `tests/`.

### Phase 2 · 클러스터 설치 (점검 시간)
- 노드 A k3s server(`--disable traefik --disable servicelb --secrets-encryption`), 노드 B agent.
- 보안그룹 3포트 추가. 배포 전용 ServiceAccount + 네임스페이스 한정 RBAC.
- **기존 Compose 5개 컨테이너가 그대로 healthy 인지 설치 전후로 확인한다.**

### Phase 3 · 병행 기동 (8443)
- 별도 볼륨에 백업 복원으로 데이터 적재 → mysql/redis/storage → api → edge 순.
- 8443 으로 홈·`/api/actuator/health`·음원 업로드/다운로드 전 경로 검증.
- 실패 시 되돌릴 것이 없다(기존 경로를 건드리지 않았으므로 네임스페이스 삭제로 끝).

### Phase 4 · AI 서비스를 k8s 경로로 운영
- `ai_server` 는 현재 어느 배포 경로에도 없다. **충돌 없이 k8s 가 첫 운영 주인이 된다.**
- 모델·색인은 노드 B 의 `/srv/mupid-k8s/ai-artifacts` 에 관리자가 배치, 읽기 전용 마운트.
  색인 4종은 행 번호로 서로를 가리키므로 묶음 교체만 허용한다.
- Spring → AI 호출(5초 타임아웃, 1회 재시도)을 실제로 연결해 검증.

### Phase 5 · CI 배포 job
- `infra_k8s/ci/k8s-release.yml` 을 **수동 트리거로만** 추가. 기존 `infra/ci/*` 는 미변경.
- `deploy-k8s <40자리 SHA> <64자리 체크섬>` 만 허용하는 별도 강제 명령과 수신기.
- `kubectl rollout status` 실패 시 `rollout undo`. 무중단 롤링이 되지만 구·신 동시 실행이라
  **DB 스키마 호환이 전제**다. 호환되지 않는 변경은 롤링 대상이 아니다.

### Phase 6 · 컷오버 판단 (별도 승인)
- 여기까지는 언제든 k8s 쪽만 지우면 원복된다. 80/443 인수는 이 계획에 포함하지 않는다.
- 분석 스택(ClickHouse/Kafka) 이관 여부도 이 시점에 다시 판단한다. k8s 로 가면
  `extra_hosts`·interserver 주소 게시가 사라지는 대신 macros 를 StatefulSet ordinal 로
  다시 짜야 한다.

## 만들 파일

```
infra_k8s/
├─ PLAN.md  BASELINE.md  README.md  OPERATIONS-K8S.md
├─ ansible/   preflight.yml  cluster.yml  roles/{preflight,firewall,k3s_server,k3s_agent}
├─ cluster/   PHASE0.md  security-group.md
├─ image/     ai.Dockerfile
├─ base/      namespace  api  ai  edge  mysql  redis  storage  configmap-nginx
├─ overlays/  parallel/(8080·8443, 별도 볼륨)   cutover/(80·443)
├─ deploy/    receive-k8s.py  ssh-entrypoint-k8s.sh  rbac.yaml
├─ ci/        k8s-release.yml
└─ tests/     test_k8s_manifests.py
```

## 미해결

- 두 노드 vCPU 수 (Phase 0)
- `ai_server` 아티팩트 전달 주체·용량·갱신 주기
- 병행 기간에 두 스택을 동시에 띄울 메모리가 나오는지 (Phase 0 실측 후 Phase 3 순서 조정)
