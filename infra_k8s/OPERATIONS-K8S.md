# 운영 절차 (k8s)

배포·검증·롤백, 그리고 컷오버 전에 서버에서 한 번 해야 하는 준비 작업.
클러스터 설치 자체는 [ansible/CONTROL-NODE.md](ansible/CONTROL-NODE.md) 에 있다.

## 배포

CI 의 `k8s-deploy-api` · `k8s-deploy-web` 버튼이 전부다. 각각 이미지를 만들어
SSH 로 밀어 넣고, 수신기가 적재·교체·검증까지 한다.

```
deploy-k8s <api|web> <40자리 커밋 SHA> <64자리 체크섬>
```

수신기가 하는 일은 순서가 정해져 있고, **클러스터를 건드리는 건 마지막 두 단계뿐**이다.

1. stdin 을 받아 1GiB 상한 확인
2. sha256 대조 — 틀리면 여기서 끝난다
3. 아카이브의 `manifest.json` 이 요청한 태그 **하나만** 담았는지 확인
4. `k3s ctr -n k8s.io images import`
5. `kubectl set image` → `kubectl rollout status --timeout=180s`
6. 실패 시 `kubectl rollout undo` 후 재확인

출력은 `DEPLOYED` · `ALREADY_DEPLOYED` · `ROLLED_BACK` · `ROLLBACK_FAILED` 중 하나다.
`ROLLBACK_FAILED` 만 사람이 개입해야 한다.

### 수동 배포

CI 없이 같은 일을 하려면 이미지 tar 를 만들어 같은 명령으로 보낸다. 다른 경로는 없다 —
`mupid-deploy-k8s` 계정은 이 한 가지 명령 외에는 아무것도 실행하지 못한다.

```bash
checksum=$(sha256sum image.tar.gz | cut -d ' ' -f 1)
ssh -i <key> mupid-deploy-k8s@j15a204.p.ssafy.io \
  "deploy-k8s api $sha $checksum" < image.tar.gz
```

## 매니페스트를 다시 적용할 때 — 이미지가 되돌아간다

`base/` 의 이미지 태그는 `mupid-api:placeholder` · `mupid-web:placeholder` 다.
배포는 `kubectl set image` 로 태그만 바꾸므로, **그냥 `kubectl apply -k` 하면
running 이미지가 placeholder 로 덮여 `ErrImageNeverPull` 이 난다.**

매니페스트를 바꿔 적용할 때는 현재 태그를 같이 넘긴다. 수신기가 노드에
`/srv/mupid-k8s/current-api` · `current-web` 로 기록해 둔다.

```bash
cd infra_k8s/overlays/cutover
kustomize edit set image \
  mupid-api:placeholder=$(cat /srv/mupid-k8s/current-api) \
  mupid-web:placeholder=$(cat /srv/mupid-k8s/current-web)
kubectl apply -k .
git checkout kustomization.yaml    # 태그를 저장소에 커밋하지 않는다
```

## 처음부터 구축

운영 데이터를 버리고 새로 세운다. 순서대로 실행한다.

### 1. 기존 배포 철거

```bash
cd infra_k8s/ansible && set -a; . ../.env; set +a
ansible-playbook -i inventory.ini teardown.yml -e confirm_destroy=DESTROY
```

`/srv/mupid` 와 MySQL·SeaweedFS·Redis 볼륨이 사라진다. 지우기 전에 `.env` 와
`config/` 를 `/srv/mupid-k8s/salvage/` 로 옮기고, certbot 의 웹루트를
`/srv/mupid-k8s/acme` 로 돌려놓는다. NODE_B 의 GitLab Runner 는 건드리지 않는다.

**이 시점부터 서비스가 내려가고, 엣지 Pod 가 뜰 때까지 인증서 갱신도 동작하지 않는다.**
만료는 2026-12-06 이라 시간은 있다. 4번에서 확인한다.

### 2. 클러스터

```bash
ansible-playbook -i inventory.ini cluster.yml
```

보안그룹 3포트가 먼저 들어가 있어야 한다([cluster/security-group.md](cluster/security-group.md)).

### 3. Secret 과 자격증명

`salvage/.env` 를 바탕으로 `/srv/mupid-k8s/.env` 를 만든다. `MUPID_GMAIL_*` 는
그대로 옮기고, `MUPID_JWT_SECRET` · MySQL 비밀번호 · `MUPID_S3_*` 는 새로 만들어도
된다 — 데이터가 없으므로 맞출 대상이 없다.

```bash
kubectl -n mupid create secret generic mupid-secrets --from-env-file=/srv/mupid-k8s/.env
kubectl -n mupid create secret generic mupid-storage-s3 --from-file=s3.json=/srv/mupid-k8s/s3.json

sudo kubectl apply -f infra_k8s/deploy/rbac.yaml
sudo kubectl -n mupid create token deployer --duration=8760h   # deploy.kubeconfig 로
```

`/srv/mupid-k8s/deploy.kubeconfig` 는 `chmod 600`, 소유자 root.
**`/etc/rancher/k3s/k3s.yaml` 을 쓰지 않는다** — cluster-admin 이고 이 엔드포인트는
CI 키로 닿는다.

### 4. 워크로드

```bash
kubectl apply -k infra_k8s/overlays/cutover
```

api·edge 는 `placeholder` 태그라 이미지가 들어올 때까지 `ErrImageNeverPull` 이다.
정상이다 — CI 의 `k8s-deploy-api` · `k8s-deploy-web` 을 돌리면 해소된다.

엣지가 뜨면 인증서 갱신 경로를 확인한다. **반드시 통과해야 한다.**

```bash
sudo certbot renew --dry-run
```

### 5. SeaweedFS 버킷 — 없으면 403 이 난다

`s3.json` 의 신원은 권한이 버킷 단위로 묶여 있다(`Write:mupid` 등). 그 버킷이
없으면 SeaweedFS 는 권한 검사에서 먼저 걸러 **404 가 아니라 403 Access Denied**
를 돌려준다. 메시지만 보면 자격증명 문제로 보이지만 실제로는 버킷이 없는 것이다.

빈 스토리지에는 버킷이 없다. 앱이 만들지 않으므로 여기서 만든다.

`weed shell` 은 명령을 인자가 아니라 stdin 으로 받는다 (`-c` 가 없다).

```bash
echo 's3.bucket.create -name mupid' | kubectl -n mupid exec -i \
  statefulset/storage -- weed shell -master=storage-master:9333

# 확인
echo 's3.bucket.list' | kubectl -n mupid exec -i \
  statefulset/storage -- weed shell -master=storage-master:9333
```

버킷 이름은 `.env` 의 `MUPID_S3_BUCKET` 과 `s3.json` 의 `actions` 에 적힌 이름이
같아야 한다.

### 6. 스키마와 시드 — 앱이 만들지 않는다

`ddl-auto` 가 `none` 이라 **테이블도 기준 데이터도 사람이 넣는다**
(`api/knowledge/decision/0011`). 빈 MySQL 로 API 를 띄우면 첫 쿼리에서 깨진다.

```bash
kubectl -n mupid exec -i statefulset/mysql -- \
  mysql -umupid_app -p"$PASSWORD" mupid < api/knowledge/domain/erd.sql
kubectl -n mupid exec -i statefulset/mysql -- \
  mysql -umupid_app -p"$PASSWORD" mupid < api/knowledge/domain/seed-codes.sql
```

**엔티티와 컬럼이 맞는지 적용 뒤에 확인한다.** `deploy/schema-check.py` 가 두 방향을
한 번에 본다 — erd.sql ↔ DB(타입·ENUM 값 포함), 엔티티 ↔ DB.

```bash
# 노드에서 스키마를 뜨고
sudo infra_k8s/deploy/schema-dump.sh > live.tsv

# 저장소가 있는 곳에서 대조한다. 차이가 있으면 종료 코드가 1 이다.
python3 infra_k8s/deploy/schema-check.py --schema live.tsv

# 머지 전에 "이 브랜치는 ALTER 가 필요한가" 를 보려면
git show <ref>:api/knowledge/domain/erd.sql > /tmp/erd.sql
python3 infra_k8s/deploy/schema-check.py --schema live.tsv --erd /tmp/erd.sql
```

`ddl-auto` 가 `none` 이라 앱이 컬럼을 따라 만들지 않는다. 어긋나면 배포 직후가 아니라
**그 컬럼을 처음 쓰는 요청에서** 터진다.

```bash
kubectl -n mupid exec statefulset/mysql -- \
  mysql -umupid_app -p"$PASSWORD" mupid -e 'DESCRIBE onboarding_survey'
```

`seed-codes.sql` 을 빠뜨리면 **온보딩이 503 으로 막힌다.** 코드 표가 비면 설문이
주고받을 번호가 없다.

### 7. 분석 스택

```bash
# Kafka 토픽. 파티션 6 = ClickHouse 2노드 x kafka_num_consumers 3.
kubectl -n mupid exec statefulset/kafka -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic mupid.listen.events --partitions 6 --replication-factor 1

# ClickHouse DDL. ON CLUSTER 라 한 노드에서 실행하면 두 샤드에 다 생긴다.
kubectl -n mupid exec -i clickhouse-0 -- \
  clickhouse-client --user mupid --password "$CH_PASSWORD" --multiquery \
  < api/clickhouse/init/01-schema.sql
```

토픽이 DDL 보다 먼저다. `kafka_listen_events` 가 없는 토픽을 구독하면 소비가 시작되지
않는다(`KAFKA_AUTO_CREATE_TOPICS_ENABLE` 이 false 다 — 파티션 1짜리가 자동 생성되면
컨슈머 다섯이 놀게 되므로 일부러 껐다).

### 8. DB Studio 클러스터 (선택)

마스터 1(NODE_B) + 리플리카 1(NODE_A). 어느 쪽에 접속해도 같은 계정·같은 DB 목록을
본다. 노드마다 자기 SQLite 를 갖고 마스터가 행 단위로 복제를 흘려 보낸다.

Secret 을 먼저 만든다. **두 값 모두 두 노드에서 같아야 하고, 마스터 키를 잃으면
저장해 둔 DB 접속 정보를 복구할 수 없다.**

```bash
key() { head -c 32 /dev/urandom | base64 -w0; }
kubectl -n mupid create secret generic dbstudio-secrets \
  --from-literal=DBSTUDIO_MASTER_KEY="$(key)" \
  --from-literal=DBSTUDIO_CLUSTER_SECRET="$(key)"
```

**외부 포트를 열지 않는다.** 등록해 둔 모든 DB 자격증명이 여기 있다. 두 Service 는
ClusterIP 이고 nodePort·hostPort 가 없으며 엣지 nginx 에도 업스트림이 없다. 접근은
내부 서비스를 통한 프록시로 하고, 그때까지는 port-forward 로 본다.

```bash
kubectl -n mupid port-forward svc/dbstudio 8080:8080          # 마스터
kubectl -n mupid port-forward svc/dbstudio-replica 8081:8080  # 리플리카
```

프록시를 붙일 때도 노드 포트를 새로 열지 않는다 — 클러스터 안에서
`http://dbstudio:8080` 으로 부른다. 테스트가 `edge` 외의 워크로드에 hostPort 가
생기는 것과 nginx.conf 에 `dbstudio` 가 들어가는 것을 막는다.

#### 확인

관리 → 클러스터(슈퍼 어드민 전용). `기준 노드` 가 마스터, 리플리카는 `동기화됨`
이어야 한다. 50건 이상 지연이 빨강이다.

```bash
# 복제가 붙었는지
kubectl -n mupid logs sts/dbstudio-replica --tail=20

# 실제로 어느 노드가 처리했는지는 응답 헤더에 있다
curl -sI http://127.0.0.1:8080/api/v1/health | grep -i x-cluster-node
```

#### 마스터가 멈추면

리플리카는 **읽기 전용으로 계속 동작한다.** 변경 요청은 503 이다. 재시작·배포처럼
곧 돌아올 정지라면 기다리면 된다 — 마스터가 돌아오면 리플리카가 다음 주기에 스스로
따라잡는다. 어떤 조작도 필요 없다.

마스터가 멈춘 동안에는 지표 수집과 매크로 정기 실행도 함께 멈춘다(둘 다 마스터 몫).
그 시간대의 지표에는 구멍이 남는다.

#### 승격 (길어질 정지에만)

**자동 승격이 없다.** 옛 마스터가 살아 있는 채로 새 마스터를 띄우면 진실이 둘이 되고
합칠 수 없다. 1번을 건너뛰지 않는다.

```bash
# 1. 옛 마스터를 확실히 멈춘다. k8s 에서는 replicas=0 이 "자동 재시작도 끈다" 이다.
kubectl -n mupid scale statefulset/dbstudio --replicas=0

# 2. 리플리카의 복제 지점을 확인한다 — 그 차이가 잃게 되는 변경의 양이다.
kubectl -n mupid logs sts/dbstudio-replica --tail=5

# 3. 리플리카를 마스터로 바꿔 띄운다. 데이터·Secret 은 그대로 둔다.
kubectl -n mupid set env statefulset/dbstudio-replica \
  DBSTUDIO_CLUSTER_ROLE=master DBSTUDIO_CLUSTER_MASTER-

# 4. Service `dbstudio` 가 새 마스터를 가리키게 한다. 리플리카들의
#    DBSTUDIO_CLUSTER_MASTER 가 이 이름을 쓰므로 여기만 바꾸면 된다.
kubectl -n mupid patch service dbstudio \
  -p '{"spec":{"selector":{"app":"dbstudio","dbstudio.io/role":"replica"}}}'
```

옛 마스터를 되살릴 때는 **반드시 리플리카로** 띄운다. 그 노드는 자기 데이터를 버리고
새 마스터의 스냅샷을 받는다 — 정지 이후 그 노드에 저장된 변경은 사라진다.

이 상태는 매니페스트와 어긋나 있다. 정리되면 `base/dbstudio.yaml` 의 역할·노드·
Service selector 를 바꿔 커밋하고 다시 적용한다.

#### 하면 안 되는 것

- **데이터 디렉터리(PVC)를 복사해 새 노드를 만들지 않는다.** 노드의 정체성은
  `node.id` 파일이다. 복사하면 두 노드가 같은 ID 로 참여해 노드 목록의 한 줄을 서로
  덮어쓰고 복제 지점이 오락가락한다. 새 노드는 빈 PVC 로 띄운다.
- **두 노드의 버전을 따로 올리지 않는다.** 버전이 섞이면 마스터에만 있는 컬럼을
  건너뛰고 스냅샷에 없는 표는 손대지 않은 채, **어디에도 오류가 남지 않는다.**
  매니페스트가 같은 digest 를 쓰므로 함께 올라가고, 테스트가 이를 검사한다.

### 9. CI 를 k8s 경로로

`infra_k8s/ci/k8s-release.yml` 의 job 은 전부 `when: manual` 이다. 위까지 끝나고
배포가 두어 번 정상으로 돌면 그 규칙을 `infra/ci/release.yml` 과 같은 것으로 바꾸고
(`develop` 보호 브랜치 + `MUPID_DEPLOY_ENABLED`), `.gitlab-ci.yml` 에서
`infra/ci/*` include 세 줄을 뺀다.

## 기동 순서

Service 이름은 Pod 보다 먼저 존재해야 한다. nginx 가 업스트림 이름을 기동 시 한 번만
해석하기 때문이다. 같은 오버레이 안에 있으므로 `apply -k` 한 번이면 되고, 너무 일찍
뜬 Pod 는 재시작하면서 스스로 해결된다.

상태저장 쪽이 먼저 준비되는 편이 로그가 읽기 쉽다.

```bash
kubectl -n mupid rollout status statefulset/mysql
kubectl -n mupid rollout status statefulset/storage-master
kubectl -n mupid rollout status statefulset/storage-volume
kubectl -n mupid rollout status statefulset/storage
kubectl -n mupid rollout status statefulset/clickhouse-keeper
kubectl -n mupid rollout status statefulset/clickhouse
kubectl -n mupid rollout status statefulset/kafka
kubectl -n mupid rollout status deployment/api
kubectl -n mupid rollout status deployment/edge
```

## 검증

```bash
base=https://j15a204.p.ssafy.io          # 컷오버 전에는 :8443

curl -fsS "$base/api/actuator/health" | grep '"status":"UP"'
curl -fsS "$base/deployment.txt"          # 배포한 커밋 SHA 와 같아야 한다
curl -fsS "$base/" -o /dev/null -w '%{http_code}\n'

# S3 게이트웨이는 인증 없는 요청을 거절해야 한다. probe 가 403 을 실패로 보기 때문에
# readiness 는 filer 를 보고, 이 단언은 여기서 한다.
kubectl -n mupid exec deploy/api -- \
  curl -s -o /dev/null -w '%{http_code}\n' http://storage:8333/   # 403

# ClickHouse 두 샤드가 서로를 보는지. macros 가 비어 있으면 복제가 깨진다.
kubectl -n mupid exec clickhouse-0 -- clickhouse-client -q 'SELECT * FROM system.macros'
kubectl -n mupid exec clickhouse-1 -- clickhouse-client -q 'SELECT * FROM system.macros'
kubectl -n mupid exec clickhouse-0 -- \
  clickhouse-client --host clickhouse-1.clickhouse -q 'SELECT 1'

# SeaweedFS 볼륨 서버가 둘 다 master 에 등록됐는지.
kubectl -n mupid exec statefulset/storage-master -- \
  wget -qO- http://127.0.0.1:9333/cluster/status
```

## 롤백

배포 실패는 수신기가 이미 되돌린다. 그 뒤에 되돌리려면:

```bash
kubectl -n mupid rollout undo deployment/api
kubectl -n mupid rollout status deployment/api
```

엣지는 `strategy: Recreate` 다. hostPort 를 두 Pod 가 동시에 쥘 수 없어서이며,
**프론트 배포와 롤백에 수 초의 중단이 있다.** api 는 rolling 이라 중단이 없지만
구·신이 잠깐 같이 도므로 **DB 스키마 호환이 전제**다. 호환되지 않는 변경은
rolling 대상이 아니다.

## 되돌릴 수 없는 지점

**`teardown.yml` 이 그 지점이다.** 그 뒤로는 네임스페이스를 지워도 원래 배포로
돌아가지 않는다 — 컨테이너·볼륨·`/srv/mupid` 가 모두 사라진 상태이기 때문이다.
운영 데이터를 버리고 새로 구축하기로 한 결정에 따른 것이고, 그래서 플레이북이
`-e confirm_destroy=DESTROY` 없이는 아무것도 하지 않는다.

철거 뒤에 클러스터 쪽에서 잘못된 것은 전부 복구 가능하다. 네임스페이스를 지우고
4~6번을 다시 하면 된다. 데이터가 없으므로 잃을 것도 없다.

## `dbstudio-tailscale` Secret
Tailscale auth key.
```bash
kubectl create secret generic dbstudio-tailscale --from-literal=TS_AUTHKEY='tskey-auth-...' -n mupid
```
