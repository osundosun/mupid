# k8s 전면 전환 계획

작성 2026-09-14, 전환 범위 개정 2026-09-15.

**기존 Compose 배포를 걷어내고 k8s 로 새로 구축한다.** 처음 계획은 병행 운영이었고
컷오버를 별도 승인 사항으로 미뤄 두었으나, 팀 회의에서 전면 전환으로 바뀌었다.

**운영 데이터는 버린다.** 이용자가 없는 시점이라 서비스 중단과 데이터 폐기를 감수하고
빈 상태에서 시작하기로 했다(2026-09-15 결정). 그래서 병행 운영도, 8443 검증 기간도,
백업 복원도 없다 — 철거하고, 세우고, 스키마를 넣는다. 계획이 그만큼 단순해졌지만
**철거 시점이 유일한 비가역 지점**이 됐다.

## 전환 범위

| | 대상 | 비고 |
|---|---|---|
| 들어감 | 프론트엔드 · 백엔드(Spring) · MySQL · Redis · SeaweedFS | 1차 |
| 들어감 | ClickHouse(2노드 분산) · Kafka · Keeper | analytics |
| 들어감 | DB Studio (마스터 1 + 리플리카 1) | 운영 도구. 외부 노출 없음 |
| **빠짐** | `ai_server` | 개발 중. `base/ai.yaml` 은 있고 kustomization 에서만 뺐다 |

`ai_server` 는 이미지가 생기면 `base/kustomization.yaml` 에 한 줄을 되돌리면 된다.
NODE_B 의 자원은 그 몫으로 비워 둔다.

## 결정된 사항

| 항목 | 결정 | 이유 |
|---|---|---|
| 전환 방식 | 전면 전환 + 기존 자산 정리 | 팀 결정 |
| 배포판 | k3s | 컨트롤플레인 ~1GB. local-path 내장 |
| 엣지·TLS | 기존 `infra/deploy/nginx.conf` 를 ConfigMap 으로 그대로 | `auth_request`·`X-Accel-Redirect` 가 검증된 구성이다 |
| 프론트엔드 | **엣지 nginx 이미지에 구워 넣는다** | 전달 경로가 api 와 같아지고 롤백이 `rollout undo` 하나로 통일된다 |
| 이미지 전달 | **레지스트리 없이 SSH tar → `k3s ctr images import`** | 강제 명령·체크섬 보안 모델을 그대로 쓴다 |
| CI/CD | **CONTROLLER(라즈베리파이)로 이전** | NODE_B 를 `ai_server` 에 온전히 넘긴다 |
| 크로스 빌드 | `FROM --platform=$BUILDPLATFORM` 빌더 스테이지 | Pi 는 aarch64, 노드는 x86_64. jar 는 아키텍처 중립이라 컴파일은 네이티브로 돈다 |
| ClickHouse | **두 노드에 샤드 하나씩** | 팀 결정. Compose analytics 토폴로지 그대로 |
| Keeper | 1대, NODE_A | 2대는 정족수가 2라 한 대도 못 견딘다. 3대는 호스트가 없다 |
| Kafka | 1대, NODE_B | 브로커 수는 ClickHouse 노드 수와 무관하다 |

## 노드

| | CONTROLLER (라즈베리파이) | NODE_A | NODE_B |
|---|---|---|---|
| 역할 | Ansible 제어, CI/CD, 이미지 빌드 | k3s server, 엣지 | k3s agent |
| 클러스터 멤버 | **아니다** | ✓ | ✓ |
| 아키텍처 | aarch64 | x86_64 | x86_64 |
| 단독 워크로드 | — | edge · api · mysql · redis · SeaweedFS master/filer/s3 · ch-keeper | kafka · (`ai_server`) |
| 양쪽 공통 | — | clickhouse · storage-volume | clickhouse · storage-volume |

접속 정보는 `infra_k8s/.env` 에 있고 `ansible/inventory.ini` 가 환경변수로 읽는다.
값은 저장소에 두지 않는다.

## 설계에서 중요한 것

1. **레지스트리가 없다.** 이미지는 노드마다 따로 적재된다. 그래서 `mupid-` 로 시작하는
   이미지를 쓰는 워크로드에 전부 `nodeSelector` 가 걸려 있다. 핀이 빠지면 다른 노드로
   스케줄되어 `ErrImageNeverPull` 이 난다. 공개 이미지(ClickHouse·Kafka·SeaweedFS)는
   자유롭게 뜰 수 있어서, 양쪽에 두는 워크로드는 `podAntiAffinity` 로 흩는다.
2. **`requests` 는 예약이다.** 노드당 4 vCPU 뿐이라 합계를 2.0 코어 이하로 묶었다.
   넉넉히 잡으면 Pod 가 Pending 으로 남는다.
3. **k3s 는 Secret 을 평문 저장한다.** `--secrets-encryption` 을 켜고, Secret 은
   관리자가 서버에서 `--from-env-file` 로 만든다.
4. **분석 포트가 노드 밖으로 안 나간다.** Compose 는 ClickHouse 9000·9009, Keeper 9181,
   Kafka 9092 를 호스트에 게시하고 보안그룹으로 통과시켜야 했다. k8s 에서는 전부 Pod
   네트워크 안이라 **새로 열 인바운드 규칙이 하나도 없다.** 노출면이 실제로 줄었다.
5. **배포 계정은 cluster-admin 이 아니다.** `deploy/rbac.yaml` 의 네임스페이스 한정
   ServiceAccount 로 Deployment 의 이미지 태그만 바꿀 수 있다.

## 자원 배분

두 노드 사양이 같다 — 각 **4 vCPU / 15,785MB / 309G**. swap 이 없어 초과분은 즉시
OOMKill 이다. 근거는 [BASELINE.md](BASELINE.md).

| | NODE_A | NODE_B |
|---|---|---|
| `requests.cpu` 합계 | **1,950m** / 2,000m 예산 | **750m** / 2,000m |
| `limits.memory` 합계 | 약 11.3Gi | 약 7.3Gi |
| available 메모리 | 13,849MB | 15,004MB |

NODE_A 는 예산까지 50m 밖에 남지 않았고, `ai_server` 가 돌아오면 NODE_B 도
2,250m 로 예산을 넘는다.
그 시점에는 Runner 가 CONTROLLER 로 옮겨간 뒤이므로 `group_vars/all.yml` 의
`system_reserved` 를 낮춰 예산을 다시 잡는다. 지금 값은 Compose 스택과 Runner 가
호스트에 있다는 전제로 잡혀 있다.

## 단계

### Phase 0 · 실측 — **완료**
`BASELINE.md`. 같은 확인을 `ansible/preflight.yml` 이 단언으로 재수행한다.

### Phase 1 · 매니페스트·이미지 — **완료 (로컬, 서버 무변경)**
- `base/` 워크로드, `overlays/`, `image/web.Dockerfile`, `image/ai.Dockerfile`
- `deploy/` 수신기·강제 명령·RBAC, `ci/` 배포 job
- `tests/test_manifests.py` — 클러스터 없이 도는 정적 검사

### Phase 2 · 철거 — **완료 (2026-09-15)**
운영 데이터를 버리고 새로 구축하기로 했으므로 병행 운영도 데이터 이관도 없다.
이용자가 없는 시점에 서비스를 내린다.

- 보안그룹 3포트 추가([cluster/security-group.md](cluster/security-group.md)) — 콘솔 작업
- `ansible-playbook teardown.yml -e confirm_destroy=DESTROY`
  - `.env`·`config/` 를 `/srv/mupid-k8s/salvage/` 로 옮긴 뒤 스택·볼륨·`/srv/mupid` 삭제
  - certbot 웹루트를 `/srv/mupid-k8s/acme` 로 이전
  - NODE_B 의 GitLab Runner 는 건드리지 않는다 — 아직 CI 를 담당한다
- **여기가 되돌릴 수 없는 지점이다.** 이후로는 앞으로 가는 길만 있다.

### Phase 3 · 클러스터와 기동 — **완료 (2026-09-15)**
- `ansible-playbook cluster.yml` — ufw, k3s server/agent, 노드 라벨
- Secret 2종, 배포용 kubeconfig
- `kubectl apply -k overlays/cutover` → 첫 배포로 api·web 이미지 적재
- `certbot renew --dry-run` 통과 확인 — 엣지가 새 웹루트를 서빙해야 통과한다

### Phase 4 · 데이터 부트스트랩 — **완료 (2026-09-15)**
빈 DB 로 시작하므로 **테이블은 앱이 만들어 주지 않는다.** `ddl-auto` 가 `none` 이다.

- MySQL: `erd.sql` 적용 — 테이블/뷰 30, FK 48. `mupid_app` 계정은 자동 생성이 아니라
  사람이 만든다(Compose 는 `prepare-env.py` 가 했다).
- `codes` 는 이제 기동 때 `BootstrapRunner` 가 채운다. `seed-codes.sql` 은 앱을
  띄우지 않고 DB 만 채울 때 쓴다.
- Kafka 토픽 6파티션 → ClickHouse `01-schema.sql` (`ON CLUSTER`, 두 샤드에 17테이블)
- 검증 완료 — 홈 200, `/api/actuator/health` UP, `certbot renew --dry-run` 통과

### Phase 5 · CI 전환
- `k8s-release.yml` 의 `when: manual` 을 걷고 `.gitlab-ci.yml` 에서 `infra/ci/*` 를 뺀다
- 배포가 두어 번 정상으로 돈 뒤에 한다

### Phase 6 · CI/CD 를 CONTROLLER 로 — **완료 (2026-09-15)**
- Runner 를 Pi 로 이전하고 NODE_B 의 Runner 를 내렸다. 등록(`id 2083`)은 그대로 쓰고
  이름만 `mupid-ci-pi` 로 바꿨다. job 상한은 호스트에 맞춰 4g → 2g.
- CI 이미지 네 개가 arm64 에서 받아지는 것을 확인했다(buildkit digest 포함).
- 남음: `system_reserved` 재조정. 현재 값은 Compose 와 Runner 가 호스트에 있다는
  전제인데 둘 다 떠났다. NODE_B 는 이제 Kafka 와 ClickHouse 한 샤드뿐이다.

## 되돌릴 수 없는 지점

**Phase 2 의 `teardown.yml` 하나뿐이다.** 그 전에는 아무것도 바뀌지 않았고, 그 뒤로는
클러스터 쪽에서 잘못된 것을 전부 다시 할 수 있다 — 데이터가 없으므로 잃을 것이 없다.
플레이북이 `-e confirm_destroy=DESTROY` 없이는 아무것도 하지 않는 이유다.

## 미해결

- **Phase 5(CI 전환)만 남았다.** `mupid-deploy-k8s` 의 `authorized_keys` 에 CI 키를
  넣고, `k8s-release.yml` 의 `when: manual` 을 걷고, `.gitlab-ci.yml` 에서
  `infra/ci/*` 를 뺀다.
- `system_reserved` 재조정 (Phase 6 참고)
- `ai_server` RSS 실측, 아티팩트 전달 주체·용량·갱신 주기
- CONTROLLER 의 빌드 자원 실측 — 아직 CI job 을 Pi 에서 돌려 보지 않았다.
- **BASELINE #7 은 해소됐다.** 기존 서버에서 헬스체크에 실패하던 analytics 코드가
  k8s 에서는 정상 기동한다. 원인이 ClickHouse 부재였다는 추정과 맞는다.

## 기존 운영에서 발견한 것

k8s 와 별개로, 옮기는 과정에서 드러난 현재 구성의 문제 둘. 매니페스트에서는 고쳤다.

- **SeaweedFS `-volume.max=5`** — 볼륨당 1024MB 이라 50Gi 디스크를 5GB 로 묶고 있다.
- **Kafka 볼륨이 쓰이지 않는다** — `/var/lib/kafka/data` 를 마운트하면서
  `KAFKA_LOG_DIRS` 를 주지 않아 실제 로그 디렉터리는 컨테이너 안의
  `/tmp/kraft-combined-logs` 다. analytics 프로필이 한 번도 기동된 적이 없어
  드러나지 않았다.
