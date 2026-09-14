# k8s 배포 옵션

`infra/` 의 Compose 배포를 **대체하지 않는다.** 같은 서버에 k3s 를 추가로 올려
병행 운영하는 선택지이며, 컷오버는 별도 승인 사항이다.

이 폴더 밖의 파일은 바꾸지 않는다. 서버에서도 `/srv/mupid/` 에 쓰지 않고
`/srv/mupid-k8s/` 를 쓴다. 예외는 `/etc/letsencrypt`·`/srv/mupid/acme`·`/srv/mupid/web`
읽기 전용 마운트 세 개뿐이다.

| 문서 | 내용 |
|---|---|
| [PLAN.md](PLAN.md) | 결정 사항, 병행 운영 제약, 단계 |
| [BASELINE.md](BASELINE.md) | 두 노드 실측 결과와 그로부터 나온 조치 사항 |
| [cluster/PHASE0.md](cluster/PHASE0.md) | 실측 절차 (읽기 전용) |
| [ansible/CONTROL-NODE.md](ansible/CONTROL-NODE.md) | 제어 노드(라즈베리파이) 상태와 남은 작업 |
| [cluster/security-group.md](cluster/security-group.md) | 노드 간 보안그룹 규칙 |
| OPERATIONS-K8S.md | 기동·검증·롤백 (Phase 3 에서 작성) |

## 구성

두 노드 모두 4 vCPU / 15.4GB / 309G, 같은 VPC `172.26.0.0/16`.

| | 노드 A `172.26.6.231` | 노드 B `172.26.0.168` |
|---|---|---|
| 역할 라벨 | `mupid.io/role=edge` | `mupid.io/role=ai` |
| k3s | server | agent |
| k8s 워크로드 | edge · api · mysql · redis · storage | **ai** |
| 그대로 두는 것 | Compose 스택, certbot, 백업 타이머 | GitLab Runner |

## 파일

```
ansible/   preflight.yml  cluster.yml  inventory.ini  roles/
cluster/   PHASE0.md  security-group.md
image/     ai.Dockerfile                 ai_server 컨테이너 이미지 (신규)
base/      네임스페이스·ConfigMap·6개 워크로드 + nginx/default.conf
overlays/  parallel(8080·8443)  cutover(80·443)
tests/     test_manifests.py            클러스터 없이 도는 정적 검사
```

## 설계에서 중요한 것

**엣지는 기존 `nginx.conf` 를 그대로 쓴다.** `base/nginx/default.conf` 는
`infra/deploy/nginx.conf` 의 사본이고, 어긋나면 테스트가 실패한다. Ingress 로
다시 쓰지 않은 이유는 음원 경로가 `auth_request` 와 `X-Accel-Redirect` +
`internal` location 에 기대고 있기 때문이다.

**Registry 가 없다.** 이미지는 기존 SSH 방식으로 보내 `k3s ctr images import` 로
넣는다(Docker 의 containerd 와 별개다). 그래서 모든 워크로드에 `nodeSelector` 를
걸어 한 노드에만 이미지가 있으면 되도록 했다. 핀이 빠지면 다른 노드로 스케줄되어
`ErrImageNeverPull` 이 난다 — 테스트가 이걸 막는다.

**볼륨을 공유하지 않는다.** k8s 쪽 PVC 는 새로 만들어 백업 복원으로 채운다.
`mupid-mysql-data` 를 같이 마운트하면 mysqld 두 개가 같은 디렉터리를 잡아 깨진다.

**`requests` 는 예약이다.** 노드당 4 vCPU 뿐이라 합계를 2.0 코어 이하로 묶었다.
넉넉히 잡으면 Pod 가 Pending 으로 남는다. kubelet 의 `system-reserved` 로 Compose
스택과 GitLab Runner 몫을 따로 떼어 둔다 — kubelet 은 그 프로세스들을 보지 못한다.

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

```bash
cd infra_k8s/ansible
ansible-playbook -i inventory.ini preflight.yml                      # 읽기 전용
ansible-playbook -i inventory.ini cluster.yml --check --tags firewall  # 방화벽 예행
ansible-playbook -i inventory.ini cluster.yml                        # 실제 설치
```

**제어 노드는 라즈베리파이다** (`pipi@100.79.100.89`, Debian 13 / aarch64).
Ansible core 2.19.11 과 community.general 11.2.1 을 설치해 두었고, 두 플레이북의
문법 검사와 변수 해석을 거기서 확인했다. 설정·제약·남은 작업은
[ansible/CONTROL-NODE.md](ansible/CONTROL-NODE.md) 에 있다.

**Pi 에서 이미지를 빌드하지 않는다.** aarch64 라 EC2(x86_64)에서 돌지 않는다.

`inventory.ini` 에서 `ansible_host` 는 제어 노드가 접속할 **공인** 주소이고,
`k3s_node_ip` 는 두 노드가 서로를 부를 **사설** 주소다. 일부러 다르다 — k3s 가 공인
주소를 광고하면 6443 이 인터넷에 노출된다. 실제로 Pi 에서 사설 주소는 닿지 않고
공인 22 번만 열려 있음을 확인했다. 테스트가 이 구분을 검사한다.

## 검증

```bash
python -B -m unittest discover -s infra_k8s/tests -v
```

클러스터·Docker 데몬·네트워크를 쓰지 않는다. `kubectl` 이 있으면 `kustomize build`
까지 돌고, 없으면 파일 검사만 돈다.

```bash
kubectl kustomize infra_k8s/overlays/parallel      # 적용될 내용 확인
docker build -f infra_k8s/image/ai.Dockerfile -t mupid-ai:<sha> .
```

## 현재 상태

Phase 0(실측)과 Phase 1(매니페스트·이미지)까지 로컬에서 끝났다.
**서버에는 아무것도 적용하지 않았다.** 다음은 Phase 2(k3s 설치)이며 그 전에
`BASELINE.md` 의 조치 사항 — ufw·보안그룹·최근 배포 실패 확인 — 이 해결되어야 한다.
