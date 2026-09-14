# 보안그룹 규칙 (k3s 노드 간)

호스트 방화벽(`ansible/roles/firewall`)과 **별개 층**이다. 둘 다 열려야 통신이 된다.
한쪽만 열면 증상이 똑같이 "노드는 Ready 인데 Pod 통신만 안 됨" 으로 나타난다.

## 주소

| 노드 | 역할 | 사설 IP |
|---|---|---|
| A (기본 EC2) | k3s server, 엣지, 상태저장 | **172.26.6.231** |
| B (추가 EC2) | k3s agent, AI | **172.26.0.168** |

두 노드는 같은 VPC `172.26.0.0/16` 에 있다.
**모든 규칙의 소스는 상대 노드의 사설 IP 다.** 공인 IP 나 `0.0.0.0/0` 을 쓰지 않는다.
Phase 0 도달성 점검을 공인 IP(`52.78.64.52`, `3.38.166.108`)로 돌렸는데, 그 주소로 열면
인터넷에 kube-apiserver 를 노출하는 것이 된다.

## 추가할 인바운드 규칙

### 노드 A 보안그룹

| 유형 | 프로토콜 | 포트 | 소스 | 용도 |
|---|---|---|---|---|
| 사용자 지정 TCP | TCP | 6443 | `172.26.0.168/32` | kube-apiserver (agent 가 접속) |
| 사용자 지정 TCP | TCP | 10250 | `172.26.0.168/32` | kubelet (metrics·logs·exec) |
| 사용자 지정 UDP | UDP | 8472 | `172.26.0.168/32` | flannel VXLAN (Pod 간 트래픽 전부) |

### 노드 B 보안그룹

| 유형 | 프로토콜 | 포트 | 소스 | 용도 |
|---|---|---|---|---|
| 사용자 지정 TCP | TCP | 10250 | `172.26.6.231/32` | kubelet |
| 사용자 지정 UDP | UDP | 8472 | `172.26.6.231/32` | flannel VXLAN |

노드 B 에는 6443 을 열지 않는다. apiserver 는 A 에만 있고 B 는 나가는 쪽으로 접속한다.

## 열지 않는 것

- **8080·8443** — 병행 검증용 엣지 포트다. 인터넷에 열지 않고 SSH 터널이나 노드 안에서
  확인한다. 운영 트래픽은 컷오버 전까지 기존 80/443 이 받는다.
- **ClickHouse 9000·9009, Keeper 9181, Kafka 9092** — 이번 범위 밖이다. Phase 6 이후
  analytics 를 올릴 때 그때 상대 노드 사설 IP 에만 연다. 현재 네 포트 모두 사용되지 않는다.
- **10251·10252·2379·2380** — k3s 는 단일 server 에 내장 sqlite 를 쓰므로 etcd 포트가 없다.

## 확인

규칙을 넣은 뒤 사설 IP 로 다시 점검한다. Phase 0 때와 달리 `open` 이 나와야 한다.

```bash
# 노드 B 에서
for p in 6443 10250; do
  timeout 2 bash -c "echo > /dev/tcp/172.26.6.231/$p" 2>/dev/null \
    && echo "$p open" || echo "$p closed"
done
```

UDP 8472 는 이 방식으로 확인되지 않는다. k3s 설치 후 Pod 간 통신으로 판정한다.

```bash
# 노드 A 에서, 양쪽 노드에 하나씩 떠 있는 Pod 사이를 확인
sudo k3s kubectl -n mupid get pods -o wide
sudo k3s kubectl -n mupid exec deploy/api -- curl -sS --max-time 3 http://ai:8100/health
```
