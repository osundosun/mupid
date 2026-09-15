# Phase 0 · 실측 결과

수집: 2026-09-14 10:20~10:22 UTC, 두 노드에서 읽기 전용 점검 실행.
같은 확인은 이제 `ansible/preflight.yml` 이 단언으로 수행한다.
**두 노드 모두 수집 완료.** 남은 미실측은 `ai_server` RSS 하나뿐이다.

두 노드는 같은 VPC(`172.26.0.0/16`) 안에 있고 사양이 동일하다 — **각 4 vCPU / 15,785MB / 309G**.
OS·커널·cgroup·iptables 구성도 같다. 노드별로 다른 점만 아래에 적는다.

## 노드 A (기본 EC2)

| 항목 | 값 |
|---|---|
| 호스트 | `ip-172-26-6-231.ap-northeast-2`, 사설 **172.26.6.231** |
| OS / 커널 | Ubuntu 24.04.4 LTS / 6.17.0-1019-aws |
| vCPU | **4** (2코어 × 2스레드, Xeon 8175M @2.5GHz) |
| 메모리 | total 15,785MB / available **13,849MB** / **swap 없음** |
| 디스크 | `/` 단일 파티션 309G, 사용 16G, **여유 294G** (`/srv`·`/var/lib` 모두 여기) |
| cgroup | **cgroup2fs** ✓ |
| 커널 모듈 | br_netfilter · overlay · vxlan · iptable_nat 모두 사용 가능 ✓ |
| iptables | v1.8.10 **nft 모드** ✓ |
| k3s 예정 포트 | 6443 · 8472 · 10250 · 8080 · 8443 **전부 비어 있음** ✓ |
| 호스트 리스닝 | 22 · 80 · 443 뿐 (80/443 은 docker-proxy) |
| 기존 k8s | k3s · kubectl · crictl 없음. containerd 는 **Docker 의 것** |

### 실제 점유 (예약이 아닌 실측)

| 컨테이너 | 실사용 | 현재 상한 |
|---|---|---|
| mupid-api | 496MB | 2G |
| mupid-mysql | 465MB | 없음 |
| mupid-storage | 129MB | 2G |
| mupid-nginx | 5MB | 없음 |
| mupid-redis | 3.8MB | 없음 |
| **합계** | **~1.1GB** | |

볼륨도 작다. mysql-data 218MB, storage-data 97KB, redis-data 266B.

## 노드 B (추가 EC2)

| 항목 | 값 | 노드 A 와 다른가 |
|---|---|---|
| 호스트 | `ip-172-26-0-168`, 사설 **172.26.0.168** | 같은 VPC |
| vCPU | 4 (Xeon **8259CL** @2.5GHz) | 모델만 다름 |
| 메모리 | total 15,785MB / available **15,004MB** | **A 보다 1.2G 더 여유** |
| 디스크 | 사용 8.0G, **여유 302G**, `/var/lib` 5.2G | A 보다 깨끗 |
| Load | **0.00 / 0.00 / 0.00** | A 는 0.12 |
| 호스트 리스닝 | **22 번뿐** | A 는 80·443 추가 |
| 컨테이너 | `mupid-runner-runner-1` **20.7MB** / 512MB 한 개 | — |
| k3s 예정 포트 | 6443·8472·10250·8080·8443 전부 비어 있음 ✓ | 같음 |
| ufw | **active** | **같음 — 양쪽 다 조치 필요** |
| cgroup·모듈·nft·AppArmor | A 와 동일 ✓ | 같음 |

### 노드 B 는 사실상 비어 있다

Runner 컨테이너 하나가 20MB 를 쓰고 load 가 0.00 이다. 디스크 5.2G 중 대부분이
Runner 가 당겨 둔 기본 이미지(mysql 1.12G, azul-zulu 612M, node 329M)와 job 캐시 1.9G 다.

**9092·9000·9009·9181 이 모두 비어 있다.** 즉 `infra/deploy/compose.yml` 의 analytics
profile 은 한 번도 기동된 적이 없다. ClickHouse·Kafka 는 정의만 있는 상태가 맞다.

`/srv/mupid/.env` 없음, 인증서 없음, 타이머 0개 — 백업은 노드 A 가 밀어 넣는 구조이므로
예상대로다.

## 갱신된 자원 배분 (두 노드 실측 반영)

메모리는 양쪽 다 넉넉하다. **진짜 제약은 4 vCPU 다.**

### 노드 A — 병행 기동 가능

available 13,849MB 에 k8s 스택 한 벌(limit 합계 ~6.8G)이 들어간다. request 합계는
**2.0 코어 이하**로 잡는다(api 500m, mysql 500m, storage 200m, 나머지 100m).
request 는 실사용이 아니라 예약이라, 넉넉히 잡으면 Pod 가 Pending 으로 남는다.

### 노드 B — AI 서비스에 여유가 크다

| | request | limit |
|---|---|---|
| k3s agent | — | ~0.5G |
| **ai_server** | 2.0G / 1500m | **8.0G / 3000m** |
| (호스트 유지) GitLab Runner | — | 512M |

RSS 실측 전이지만 available 15,004MB 라 8G 상한을 줘도 여유가 있다. 색인을 전부
메모리에 올리는 구조이므로 상한은 넉넉히 두고 request 로 스케줄링만 보장한다.

## 조치가 필요한 발견

### 1. ufw 가 Pod 트래픽을 막는다 — 양쪽 노드 다 조치 필요

`sudo ufw status verbose` 결과, 두 노드 모두 같은 기본 정책이다.

```
Default: deny (incoming), allow (outgoing), deny (routed)
```

**`deny (routed)` 가 문제다.** ufw 의 routed 기본값은 FORWARD 체인 DROP 이고,
flannel 의 Pod→Pod·Pod→Service 트래픽이 전부 FORWARD 를 지난다. 이 상태로 k3s 를 올리면
노드는 Ready 가 되는데 Pod 간 통신만 조용히 안 되는, 원인 찾기 까다로운 실패가 난다.

현재 허용 규칙:

| | 허용 |
|---|---|
| 노드 A | 22, 80/tcp, 443/tcp (v4·v6) |
| 노드 B | **22 뿐** |

노드 B 는 22 밖에 없으므로 kubelet(10250)·VXLAN(8472)을 새로 열어야 한다.

Phase 2 에서 적용할 규칙이다. **지금 실행하지 않는다.**

```bash
# --- 노드 A (172.26.6.231) ---
sudo ufw allow from 172.26.0.168 to any port 6443  proto tcp   # kube-apiserver
sudo ufw allow from 172.26.0.168 to any port 10250 proto tcp   # kubelet
sudo ufw allow from 172.26.0.168 to any port 8472  proto udp   # flannel VXLAN

# --- 노드 B (172.26.0.168) ---
sudo ufw allow from 172.26.6.231 to any port 10250 proto tcp
sudo ufw allow from 172.26.6.231 to any port 8472  proto udp

# --- 양쪽 공통: FORWARD 를 CNI 인터페이스에 한해 연다 ---
sudo ufw route allow in  on cni0
sudo ufw route allow out on cni0
sudo ufw route allow in  on flannel.1
sudo ufw route allow out on flannel.1
```

`cni0`·`flannel.1` 은 k3s 기동 후에 생기므로 **route 규칙은 k3s 설치 직후에 넣는다.**
인터페이스가 없는 상태에서도 ufw 는 규칙을 받아 주지만, 순서를 이렇게 두는 편이 확인하기 쉽다.

이렇게 해도 Pod 통신이 안 되면 `sudo ufw default allow routed` 로 넓혀서 원인을 가른다.
다만 이건 FORWARD 전체를 여는 것이라 **원인 확인용이고, 확인 뒤에는 위 인터페이스 한정
규칙으로 되돌린다.**

### 1-1. ufw 와 보안그룹은 별개 층이다
위는 호스트 방화벽이고, AWS 보안그룹에도 같은 포트를 **상대 노드 사설 IP 에만** 열어야
한다. 둘 중 하나만 열면 통신이 안 된다. 규칙은 `cluster/security-group.md` 에 정리했다.

### 2. 도달성 점검에 공인 IP 를 썼다 — 사설로 다시 잡는다
인자로 준 `52.78.64.52`·`3.38.166.108` 은 공인 IP 다. 두 노드는 **같은 VPC
`172.26.0.0/16`** 안에 있으므로 노드 간 통신은 사설 IP 로 맺는다. 공인 IP 로 6443 을
열면 인터넷에 API 서버를 노출하는 셈이다.

- 노드 A 172.26.6.231 ↔ 노드 B 172.26.0.168
- 보안그룹 규칙은 이 두 주소(또는 VPC CIDR)에만 건다.

### 3. AI 서비스가 CPU 에 묶인다 — 스레드 수를 명시해야 한다
이력 있는 사용자 p50 **2.22초**이고 노드 B 는 **4 vCPU** 다. 동시 처리량이 곧 제약이다.
그리고 PyTorch·NumPy 는 기본적으로 **호스트 코어 수만큼 스레드를 띄운다.** 컨테이너에
CPU limit 을 걸어도 스레드 수는 줄지 않아 오히려 컨텍스트 스위칭으로 느려진다.

Phase 1 매니페스트에서 CPU limit 과 맞춰 명시한다.

```yaml
env:
  - {name: OMP_NUM_THREADS,        value: "3"}
  - {name: OPENBLAS_NUM_THREADS,   value: "3"}
  - {name: MKL_NUM_THREADS,        value: "3"}
```

### 4. CI 빌드와 AI 서비스가 노드 B 에서 경쟁한다
GitLab Runner 는 호스트 Docker 로 계속 돈다(계획대로). 그래서 **k8s 는 Runner 가 쓰는
자원을 모른다.** BuildKit 백엔드 빌드가 돌면 4 vCPU 를 같이 쓰게 되고, k8s 의
`requests.cpu` 는 이 부하를 막아 주지 못한다.

당장은 job 동시성 1 이고 Runner 가 20MB 를 쓰는 수준이라 문제가 없다. 배포 중 AI 응답이
느려지는 것이 확인되면 Runner job 쪽에 cgroup 상한을 거는 것으로 대응한다. 지금 조치하지
않는다.

### 5. containerd 가 분리된다 — 이미지 import 경로 확인
`/usr/bin/containerd` 는 Docker 것이고 k3s 는 `/var/lib/rancher/k3s/agent/containerd` 를
따로 쓴다. 그래서 **`docker load` 가 아니라 `k3s ctr images import`** 여야 한다.
계획대로지만, 같은 이미지가 두 저장소에 중복 적재된다는 뜻이기도 하다(여유 294G·302G, 문제없음).

### 6. 디스크 정리 여지 (급하지 않음)
노드 A `/var/lib/containerd` 7.2G — `mupid-api` 태그가 **22개** 쌓였고 BuildKit 캐시 1.6G.
배포마다 이미지가 남는 구조라 계속 는다. k3s 가 별도 containerd 에 또 쌓으므로 보관 정책을
언젠가 정해야 한다. 노드 B 는 Runner 캐시 1.9G 로 정상 범위다.

### 7. 확인이 필요한 배포 상태 — k8s 와 별개 문제일 수 있음
노드 A 에서 최근 10시간 안에 만들어진 `mupid-api` 이미지가 8개인데 **실행 중인 것은 3일 전
이미지**(`654a9aea`)다. api·nginx 가 43분 전에 올라온 것과 겹쳐 보면, 45분 전
배포(`59b73087`)가 상태 검사에 실패해 이전 이미지로 되돌아간 모양새다. **추정이며 확인이
필요하다.**

```bash
sudo ls -t /srv/mupid/releases | head -5
sudo tail -30 /srv/mupid/releases/59b73087dd06fbecef0fa6a5c4461964358994f1/deploy.log
```

맞다면 롤백은 제대로 동작한 것이지만, **최신 API 이미지가 기동하지 못하는 상태**라는 뜻이다.
Phase 3 은 기동되는 API 이미지가 있어야 검증이 되므로 그전에 정리되어야 한다.

## 사소한 기록

- `net.bridge.bridge-nf-call-iptables` 조회 실패는 br_netfilter 가 아직 로드되지 않아서다.
  모듈은 있고 k3s 가 기동 시 직접 로드한다. 양쪽 다 조치 불필요.
- AppArmor 활성, SELinux 없음. k3s/containerd 기본 프로파일로 동작한다.
- `ulimit -Sn` 1024 / `-Hn` 1048576 (양쪽 동일). 컨테이너는 자체 설정을 받으므로 영향 없음.
- 노드 A 인증서 만료 2026-12-06, certbot·백업 타이머 정상.
- `/srv/mupid-k8s/` 는 아직 양쪽 다 없다.
- Compose CLI v5.5.1 (양쪽 동일).

## 남은 미실측

`ai_server` RSS 하나뿐이다. 모델·색인이 있는 환경에서 `cluster/PHASE0.md` 3절 절차로 잰다.
**기동 직후가 아니라 추천을 몇 번 태운 뒤**의 값이어야 한다. 노드 B 에 여유가 커서
이 값이 계획을 뒤집을 가능성은 낮고, `limits.memory` 를 확정하는 용도다.
