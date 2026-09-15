# k8s 전환

`infra/` 의 Compose 배포를 **대체한다.** 전면 전환이며, 컷오버 뒤 서버의
`/srv/mupid` 도 정리한다. `ai_server` 는 개발 중이라 1차 범위에서 빠진다.

서버에서 이 배포가 쓰는 곳은 `/srv/mupid-k8s/` 다. `/srv/mupid` 를 읽는 곳은
없다 — 프론트엔드는 이미지에 들어갔고 acme 웹루트는 `/srv/mupid-k8s/acme` 로
옮긴다. 호스트에서 읽는 것은 certbot 의 `/etc/letsencrypt` 뿐이다.

| 문서 | 내용 |
|---|---|
| [PLAN.md](PLAN.md) | 전환 범위, 결정 사항, 단계 |
| [OPERATIONS-K8S.md](OPERATIONS-K8S.md) | 배포·검증·롤백, 컷오버 전 준비 |
| [BASELINE.md](BASELINE.md) | 두 노드 실측 결과와 그로부터 나온 조치 사항 |
| [cluster/PHASE0.md](cluster/PHASE0.md) | 실측 절차 (읽기 전용) |
| [ansible/CONTROL-NODE.md](ansible/CONTROL-NODE.md) | CONTROLLER(라즈베리파이) 상태와 남은 작업 |
| [cluster/security-group.md](cluster/security-group.md) | 노드 간 보안그룹 규칙 |

## 구성

두 노드 모두 4 vCPU / 15.4GB / 309G, 같은 VPC `172.26.0.0/16`.

| | NODE_A | NODE_B |
|---|---|---|
| 역할 라벨 | `mupid.io/role=edge` | `mupid.io/role=ai` |
| k3s | server | agent |
| 단독 워크로드 | edge · api · mysql · redis<br>storage(master·filer+s3) · clickhouse-keeper<br>dbstudio-replica | kafka · dbstudio(마스터)<br>(`ai_server` 자리) |
| 양쪽에 하나씩 | clickhouse(샤드 01) · storage-volume | clickhouse(샤드 02) · storage-volume |
| 호스트에 남는 것 | certbot, 백업 타이머 | — (Runner 는 CONTROLLER 로 이전) |

주소는 `.env` 에 있다. CI/CD 와 이미지 빌드는 클러스터 밖의 CONTROLLER(라즈베리파이)
에서 돌고, 그 노드는 k3s 멤버가 아니다.

## 파일

```
.env.example  노드 접속 정보의 키 목록. 값은 .env 에 넣는다 (커밋 안 됨)
ansible/   preflight.yml  cluster.yml  teardown.yml  inventory.ini  roles/
cluster/   PHASE0.md  security-group.md
image/     web.Dockerfile   프론트엔드를 구운 엣지 nginx 이미지
           ai.Dockerfile    ai_server 이미지 (이번 전환 범위 밖)
base/      네임스페이스·ConfigMap·워크로드 + nginx/default.conf
           clickhouse/      api/clickhouse 의 사본 + k8s 용 keeper_config.xml
deploy/    ssh-entrypoint-k8s.sh  receive-k8s.py  rbac.yaml
ci/        k8s-release.yml  package-web.sh  deploy-k8s.sh
overlays/  cutover(80·443)             기존 배포를 걷어낸 뒤 적용한다
tests/     test_manifests.py            클러스터 없이 도는 정적 검사
```

## 이미지 전달

레지스트리를 두지 않는다. CI 가 이미지를 tar 로 만들어 SSH 로 밀어 넣고
`k3s ctr -n k8s.io images import` 로 적재한다 — 기존 Compose 경로와 같은
보안 모델이다. 강제 명령은 한 가지 모양만 받는다.

```
deploy-k8s <api|web> <40자리 커밋 SHA> <64자리 체크섬>
```

`deploy/receive-k8s.py` 는 업로드가 끝나고 체크섬이 맞고 아카이브가 요청한
태그 **하나만** 담고 있음을 확인하기 전까지 클러스터를 건드리지 않는다.
`kubectl set image` 뒤 `rollout status` 가 실패하면 `rollout undo` 한다.

`kubectl` 자격증명은 `deploy/rbac.yaml` 의 네임스페이스 한정 ServiceAccount 다.
`/etc/rancher/k3s/k3s.yaml` 은 cluster-admin 이라 쓰지 않는다 — 이 엔드포인트는
CI 키로 닿을 수 있다.

**이미지는 노드마다 따로 적재된다.** 그래서 `mupid-` 로 시작하는 이미지를 쓰는
워크로드에는 전부 `nodeSelector` 가 걸려 있고, 핀이 빠지면 다른 노드로
스케줄되어 `ErrImageNeverPull` 이 난다. 테스트가 이걸 막는다.

## 설계에서 중요한 것

**엣지는 기존 `nginx.conf` 를 그대로 쓴다.** `base/nginx/default.conf` 는
`infra/deploy/nginx.conf` 의 사본이고, 어긋나면 테스트가 실패한다. Ingress 로
다시 쓰지 않은 이유는 음원 경로가 `auth_request` 와 `X-Accel-Redirect` +
`internal` location 에 기대고 있기 때문이다.

**빈 상태에서 시작한다.** 기존 볼륨을 옮기지 않는다 — 팀 결정으로 운영 데이터를
버리고 새로 구축하므로 PVC 는 비어 있는 채로 만들어지고, 스키마와 시드 데이터는
기동 후에 넣는다([OPERATIONS-K8S.md](OPERATIONS-K8S.md)). `ddl-auto` 가 `none` 이라
**앱이 테이블을 만들지 않는다.**

**`requests` 는 예약이다.** 노드당 4 vCPU 뿐이라 합계를 2.0 코어 이하로 묶었다.
넉넉히 잡으면 Pod 가 Pending 으로 남는다. kubelet 의 `system-reserved` 는 아직
Compose 스택과 GitLab Runner 가 호스트에 있다는 전제로 잡혀 있다 — 둘 다 떠난 뒤에
다시 잡는다.

**AI 는 nginx 에 노출하지 않는다.** `/internal` 에 인증이 없고 Spring 만 호출하는
전제다. readiness 는 `/internal/ready` 라서 MySQL ID 매핑이 없으면 503 이고,
그동안 Pod 가 Service 에서 빠져 있는 것이 의도한 동작이다.

## 클러스터 구축 (Ansible)

서버 작업은 전부 Ansible 이다. bash 스크립트를 서버에 복사해 돌리지 않는다.
노드 join 토큰이 `hostvars` 로 직접 넘어가므로 사람이 토큰을 복사해 옮기지 않는다
(`no_log` 로 출력도 막는다).

`--check` 가 의미 있는 것은 **firewall 플레이뿐이다.** k3s 설치는 `creates:` 가드가
있어 check 모드에서 건너뛰고, 그 뒤 노드 Ready 를 기다리는 작업이 존재하지 않는
클러스터를 상대로 실패한다. check 모드의 산물이지 호스트의 문제가 아니다.

**접속 값은 `.env` 에서 온다.** `inventory.ini` 에는 주소도 키 경로도 없고
`lookup('env', ...)` 만 있다. 내보내지 않고 돌리면 값이 빈 문자열이 되는데,
두 플레이북 첫머리의 `env_check` 플레이가 SSH 를 시도하기 전에 멈춘다.

```bash
cp infra_k8s/.env.example infra_k8s/.env   # 최초 1회, 값을 채운다
cd infra_k8s/ansible
set -a; . ../.env; set +a

ansible-playbook -i inventory.ini preflight.yml                        # 읽기 전용
ansible-playbook -i inventory.ini teardown.yml -e confirm_destroy=DESTROY
ansible-playbook -i inventory.ini cluster.yml --check --tags firewall  # 방화벽 예행
ansible-playbook -i inventory.ini cluster.yml                          # 실제 설치
```

**`teardown.yml` 이 먼저다.** 엣지 Pod 가 80·443 을 hostPort 로 잡는데 Compose
nginx 가 쥐고 있어서, preflight 가 그 포트를 비어 있다고 단언한다. 그리고
**이 플레이북은 `/srv/mupid` 와 데이터 볼륨을 영구 삭제한다** — 가드 인자가
없으면 아무것도 하지 않고, 모든 플레이의 첫 작업이 그 가드다.

**제어 노드는 라즈베리파이다** (`pipi@100.79.100.89`, Debian 13 / aarch64).
Ansible core 2.19.11 과 community.general 11.2.1 을 설치해 두었고, 두 플레이북의
문법 검사와 변수 해석을 거기서 확인했다. 설정·제약·남은 작업은
[ansible/CONTROL-NODE.md](ansible/CONTROL-NODE.md) 에 있다.

**Pi 에서 이미지를 빌드하지 않는다.** aarch64 라 EC2(x86_64)에서 돌지 않는다.

`ansible_host`(= `MUPID_NODE_*_HOST_PUBLIC`)는 CONTROLLER 가 접속할 **공인** 주소이고,
`k3s_node_ip`(= `MUPID_NODE_*_HOST_PRIVATE`)는 두 노드가 서로를 부를 **사설** 주소다.
일부러 다르다 — k3s 가 공인 주소를 광고하면 6443 이 인터넷에 노출된다. 실제로 Pi 에서
사설 주소는 닿지 않고 공인 22 번만 열려 있음을 확인했다.

값이 저장소를 떠났으므로 이 구분은 **`env_check` 가 매 실행마다 단언한다** — 사설
대역인지, 공인 주소와 다른지, 키가 절대 경로에 있고 `0600` 인지. 테스트는 그 단언이
사라지지 않았는지와 `inventory.ini` 에 주소가 다시 박히지 않았는지를 본다.

## 검증

```bash
python -B -m unittest discover -s infra_k8s/tests -v
```

클러스터·Docker 데몬·네트워크를 쓰지 않는다. `kubectl` 이 있으면 `kustomize build`
까지 돌고, 없으면 파일 검사만 돈다.

```bash
kubectl kustomize infra_k8s/overlays/cutover       # 적용될 내용 확인
docker build -f infra_k8s/image/ai.Dockerfile -t mupid-ai:<sha> .
```

## 현재 상태

**전환 완료. 운영 중이다** (2026-09-15).

| | |
|---|---|
| 서비스 | https://j15a204.p.ssafy.io HTTP 200, `/api/actuator/health` UP |
| 인증서 | `certbot renew --dry-run` 통과 (웹루트 `/srv/mupid-k8s/acme`) |
| 워크로드 | 14개 Pod 전부 Running |
| MySQL | 테이블/뷰 30 · FK 48 · `codes` 24행 |
| ClickHouse | 두 샤드에 각 17테이블, 복제 지연 0 |
| Kafka | `mupid.listen.events` 6파티션, 컨슈머 3 배정 |
| SeaweedFS | 버킷 `mupid`, MUBTI 이미지 16장 적재 |
| DB Studio | 마스터(NODE_B) + 리플리카(NODE_A), 스냅샷 동기화 완료 |
| 기존 Compose | 철거됨. `/srv/mupid` 없음 |
| GitLab Runner | CONTROLLER 로 이전 (`mupid-ci-pi`) |

남은 것은 **CI 전환**뿐이다(Phase 5). `k8s-release.yml` 의 job 이 아직 전부
`when: manual` 이고, `mupid-deploy-k8s` 의 `authorized_keys` 에 CI 키가 없다.
배포는 지금 수동으로 같은 수신기 경로를 거친다.

그리고 `system_reserved` 재조정이 남아 있다. 지금 값은 Compose 와 Runner 가
호스트에 있다는 전제인데 둘 다 떠났고, NODE_A 는 예산까지 50m 밖에 남지 않았다.
