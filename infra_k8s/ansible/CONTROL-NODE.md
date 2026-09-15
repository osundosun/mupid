# CONTROLLER (라즈베리파이)

Ansible 제어 노드이자 **GitLab Runner 호스트**다. 클러스터 멤버는 아니다.

aarch64 라 EC2(x86_64)용 이미지를 그냥 빌드하면 노드에서 돌지 않는다. 그래서
`infra/image/build.Dockerfile` 의 빌더 스테이지가 `--platform=$BUILDPLATFORM`
이고 `package-image.sh` 가 `--opt platform=linux/amd64` 를 준다 — 컴파일은
여기서 네이티브로 돌고 런타임 이미지만 amd64 로 나온다. 산출물이 jar 와 정적
파일이라 아키텍처와 무관하기 때문에 성립한다.

## GitLab Runner (2026-09-15 이전 완료)

노드 B 에서 옮겨 왔다. 같은 등록(`id 2083`)을 그대로 쓰고 이름만
`mupid-ci-ec2` → `mupid-ci-pi` 로 바꿨다. 노드 B 쪽은 `compose down` 으로 내렸다.

```bash
sudo docker ps --filter name=mupid-runner
sudo docker exec mupid-runner-runner-1 gitlab-runner verify
```

**job 상한을 낮췄다.** EC2 는 15.4GB 였지만 Pi 는 3.8GB 이고 이미 다른 컨테이너가
돌고 있다(dbstudio·mysql·nginx·tailscale, 합계 ~820MB). job 4g → **2g**,
service 1g → **512m**. `infra/runner/configure.py` 가 이 값을 단언한다.

CI 가 쓰는 이미지 네 개는 arm64 가 모두 있다 — `moby/buildkit@sha256:80b15f…`
(digest 고정이지만 멀티아키 인덱스), `azul-zulu:21-jdk`, `node:22-bookworm-slim`,
`alpine:3.22`. 실제로 받아지는 것을 확인했다.

**SD 카드다.** 빌드 IO 가 EC2 보다 느리다. 디스크는 29G 중 여유 11G 로,
Gradle 캐시·`node_modules`·BuildKit 캐시가 함께 들어가면 넉넉하지 않다.

## 현재 상태 (2026-09-14 확인)

| 항목 | 값 |
|---|---|
| 접속 | `pipi@100.79.100.89` (Tailscale) |
| OS / 아키텍처 | Debian 13 (trixie) / aarch64, 커널 6.18 |
| Python | 3.13.5 |
| Ansible | **core 2.19.11 / ansible 12** — 설치 완료 |
| community.general | **11.2.1** — `requirements.yml` 의 하한 3.4.0 을 크게 넘는다 |
| 자원 | RAM 3.8GB (available 1.7GB), 디스크 여유 8.6GB |
| sudo | 무암호 |

`apt install ansible` 로 넣었다. Debian 13 패키지가 컬렉션을 함께 담고 있어
`ansible-galaxy collection install -r requirements.yml` 은 이 제어 노드에서는
**필요 없다.** 다른 제어 노드를 쓸 때를 위해 `requirements.yml` 은 남겨 둔다.

Debian 13 은 PEP 668 외부 관리 환경이라 `pip install ansible` 이 거부된다.
apt 또는 pipx 를 쓴다.

## 확인한 것

- 두 플레이북 `--syntax-check` 통과.
- 인벤토리의 파생 변수가 노드별로 바르게 풀린다.
  `node-a` → peer `172.26.0.168`, `node-b` → peer `172.26.6.231`,
  `system_reserved[node_role]` 도 역할별로 해석된다.
- `community.general.ufw` 에 `route`·`direction`·`interface`·`from_ip`·`to_port`·
  `comment` 가 모두 있다. firewall role 이 쓰는 조합 그대로다.
- **Pi → EC2 공인 22 번이 양쪽 다 열려 있다.**
  `3.38.166.108:22` open, `52.78.64.52:22` open.
- **Pi → EC2 사설 주소는 닿지 않는다.** `172.26.6.231`·`172.26.0.168` 모두 unreachable.
  예상된 결과다. EC2 는 Tailscale 에 들어와 있지 않고 Pi 는 그 VPC 밖이다.
  그래서 인벤토리가 `ansible_host`(공인, 제어 노드용)와 `k3s_node_ip`(사설, 노드 간)를
  나눠 둔 것이다. 이 구분이 무너지면 k3s 가 공인 주소를 광고해 6443 이 인터넷에 열린다.

## EC2 접속 키

`~/.ssh/J15A204T.pem` (RSA 2048, `SHA256:iQ+s7IRfVw7i+z/kPMDuXK0vUxmK0eAJMWdYwbc8Ssc`).
권한이 `0664` 로 올라와 있어 `0600` 으로 고쳤다 — SSH 는 그룹·기타 읽기 권한이 붙은
개인키를 거부한다. 경로는 `../.env` 의 `MUPID_NODE_A_SSH_KEY`·`MUPID_NODE_B_SSH_KEY`
에 넣으므로 매번 `-e` 로 줄 필요가 없다. `env_check` 가 절대 경로인지와 `0600` 인지를
실행 전에 확인한다. `.env` 는 저장소에 올라가지 않는다.

이 키는 두 EC2 의 관리자 접근을 연다. 전용 키로 좁히는 편이 낫지만(`ssh-keygen` 으로
만들고 공개키만 `authorized_keys` 에 등록), 지금은 이 PEM 을 쓴다.

## 확인 완료 (2026-09-14, 실제 실행)

```
ansible -i inventory.ini k3s -m ping        node-a SUCCESS / node-b SUCCESS
ansible-playbook preflight.yml              node-a ok=13 changed=0
                                            node-b ok=13 changed=0
ansible-playbook cluster.yml --check --tags firewall
                                            node-a changed=4  node-b changed=3
```

preflight 는 두 노드에서 **변경 0건**으로 통과했고, 보고 값이 `BASELINE.md` 와 일치한다.
4 vCPU / 15,785MB / swap 0 / cgroup2fs / 293GB·301GB 여유 / k3s 미설치.
**ufw routed 경고가 양쪽에서 정상 발동했다** — 탐지가 동작한다는 증거다.

firewall 예행은 의도한 비대칭을 보여 준다. 6443 규칙은 node-a 에만 생기고
(apiserver 는 서버 노드에만 있다), CNI route 규칙 8개는 양쪽에 생긴다.
`--check` 라 아무것도 바뀌지 않았다.

## 호스트 키 — 지문을 대조할 것

`ansible.cfg` 는 `host_key_checking = True` 다. 끄지 않는다.

접속을 확인하려고 `ssh-keyscan` 으로 받은 키를 Pi 의 `known_hosts` 에 넣었다.
**이건 처음 본 키를 그대로 믿은 것(TOFU)이고 검증이 아니다.** GitLab 의
`MUPID_DEPLOY_KNOWN_HOSTS` 에 검증된 값이 이미 있으므로 아래 지문과 대조한다.
다르면 `known_hosts` 에서 해당 줄을 지우고 GitLab 값으로 다시 넣는다.

| 호스트 | 종류 | 지문 |
|---|---|---|
| 3.38.166.108 (node-a) | ED25519 | `SHA256:BF4aje0cUO/d1eyhWw4Obm9vWL1NolCjw8MlnfKfBWs` |
| 3.38.166.108 (node-a) | RSA | `SHA256:QIUW3rT9fO8OXPhtrJh/ZhPcXb2MhLhNmYYsq7rEa1s` |
| 52.78.64.52 (node-b) | ED25519 | `SHA256:Vk0az+LfrAko+yOn96gS2tU54XZdtAEkNF+tajssmys` |
| 52.78.64.52 (node-b) | RSA | `SHA256:tWf13shKTw143+Ge+k0ax+waD1VvnJScgOe9Lod2ah0` |

## 저장소 배치

플레이북은 저장소 안에 있으므로 Pi 에서 clone 해서 쓴다. 사본을 손으로 옮기면
워크스테이션 쪽과 어긋난다.

지금 Pi 의 `/tmp/mupid-ansible/` 에 이번 검증에 쓴 사본이 남아 있다. **이 세션 시점의
스냅샷이므로 실제 운영에는 쓰지 않는다.** clone 한 뒤 지운다.

```bash
git clone <저장소> ~/mupid && cd ~/mupid/infra_k8s
cp .env.example .env    # 값을 채운다. 저장소에 올라가지 않는다.
```

## 실행 순서

`.env` 를 환경으로 내보내고 시작한다. `inventory.ini` 에는 주소도 키 경로도 없고
`lookup('env', ...)` 만 있어서, 내보내지 않으면 값이 빈 문자열이 된다. 그 경우
두 플레이북 첫머리의 `env_check` 플레이가 SSH 전에 무엇이 비었는지 말하고 멈춘다.

```bash
cd ~/mupid/infra_k8s/ansible
set -a; . ../.env; set +a
ansible-playbook -i inventory.ini preflight.yml                        # 읽기 전용
ansible-playbook -i inventory.ini cluster.yml --check --tags firewall  # 방화벽 예행
ansible-playbook -i inventory.ini cluster.yml                          # 설치
```

`cluster.yml` 전에 AWS 보안그룹 규칙이 들어가 있어야 한다
([../cluster/security-group.md](../cluster/security-group.md)).
ufw 는 플레이북이 처리하지만 보안그룹은 콘솔 작업이다.
