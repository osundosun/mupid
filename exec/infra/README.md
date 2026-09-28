# 인프라·배포 (운영)

운영 서비스 주소: `https://j15a204.p.ssafy.io`

운영은 **k3s(Kubernetes) 2노드 클러스터**이며 정의는 [infra_k8s/](../../infra_k8s/)에 있다.
`infra/`의 Docker Compose 배포는 2026-09-15에 철거됐다(아래 [이력](#이력--infra-compose-배포) 참고).
`infra/`는 CI 설정([infra/ci/](../../infra/ci/))과 이미지 정의([infra/image/](../../infra/image/)),
러너 설정([infra/runner/](../../infra/runner/))을 담는 곳으로만 남아 있다.

원문 문서:

| 문서 | 내용 |
|---|---|
| [infra_k8s/README.md](../../infra_k8s/README.md) | 구성, 이미지 전달 방식, 설계 결정 |
| [infra_k8s/PLAN.md](../../infra_k8s/PLAN.md) | 전환 단계(Phase 0~6)와 결정 사항 |
| [infra_k8s/OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) | 배포·검증·롤백, 처음부터 구축하는 절차 |
| [infra_k8s/BASELINE.md](../../infra_k8s/BASELINE.md) | 두 노드 실측값 |
| [infra_k8s/ansible/CONTROL-NODE.md](../../infra_k8s/ansible/CONTROL-NODE.md) | 제어 노드(라즈베리파이), GitLab Runner |
| [infra_k8s/cluster/security-group.md](../../infra_k8s/cluster/security-group.md) | AWS 보안그룹 규칙 |
| [infra/ci/VARIABLES.md](../../infra/ci/VARIABLES.md) | GitLab CI/CD 변수 전체 목록 |

---

## 1. 전체 구성도

```
                 사용자 (브라우저 / Android 앱)
                          │ HTTPS 443 (HTTP 80 → 308 리다이렉트)
                          ▼
┌──────────────────── NODE_A  (k3s server, 라벨 mupid.io/role=edge) ─────────────────────┐
│  edge (nginx 1.27 + 정적 프론트엔드, hostPort 80/443)                                   │
│    ├─ /api/          → api:8800 (Spring Boot)                                           │
│    ├─ /storage/, /upload/…  → storage:8888 (SeaweedFS filer, auth_request 로 Spring 인증) │
│    └─ /download/     → 호스트 /srv/mupid-k8s/downloads (APK·웹 번들)                    │
│  api · mysql · redis · storage(master·filer+S3) · clickhouse-keeper                      │
│  호스트: certbot(/etc/letsencrypt, 웹루트 /srv/mupid-k8s/acme), 배포 수신기(SSH 강제 명령) │
└────────────────────────────────────────────────────────────────────────────────────────┘
          │  VPC 사설망 (6443 / 10250 / 8472-udp flannel)
┌──────────────────── NODE_B  (k3s agent, 라벨 mupid.io/role=ai) ────────────────────────┐
│  ai (FastAPI 추천 서버, ClusterIP 전용) · ai-labeler(CronJob) · qdrant · kafka          │
│  dbstudio(리플리카 + tailscale 사이드카, 마스터는 클러스터 밖 tailnet)                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
  양쪽 노드에 1개씩: clickhouse(샤드 01/02) · storage-volume(SeaweedFS 볼륨 서버)

 클러스터 밖
  CONTROLLER (라즈베리파이, Tailscale)  Ansible 제어 노드 ─ SSH(공인 22) ─▶ NODE_A/B
  GitLab Runner (mupid-ci, docker executor) 이미지 빌드 → tar 로 SSH 전송 → 노드에서 import
  JupyterHub GPU 서버                     분석 배치(music_preprocessing), CI 가 HTTP API 로 배포
```

- 두 노드 모두 4 vCPU / 15.4GB / 309G, Ubuntu 24.04.4 LTS, 같은 VPC (출처: [BASELINE.md](../../infra_k8s/BASELINE.md))
- 네임스페이스: `mupid` ([base/namespace.yaml](../../infra_k8s/base/namespace.yaml))
- **컨테이너 레지스트리를 두지 않는다.** CI가 이미지를 tar로 만들어 SSH로 보내고 노드에서
  `k3s ctr -n k8s.io images import`로 적재한다. 그래서 `mupid-*` 이미지를 쓰는 워크로드는 전부
  `nodeSelector`로 노드가 고정돼 있다(안 그러면 `ErrImageNeverPull`).
- AI(`ai`), Qdrant, DB Studio는 nginx에 노출하지 않는다(ClusterIP 전용).

## 2. 서버·버전 표

### 2-1. 호스트·클러스터

| 구분 | 종류 / 버전 | 근거 |
|---|---|---|
| 노드 OS | Ubuntu 24.04.4 LTS (커널 6.17, AWS EC2 x86_64) ×2 | [BASELINE.md](../../infra_k8s/BASELINE.md) |
| 오케스트레이션 | k3s (server 1 + agent 1), pod CIDR `10.42.0.0/16`, service CIDR `10.43.0.0/16` | [ansible/group_vars/all.yml](../../infra_k8s/ansible/group_vars/all.yml) |
| k3s 버전 | `k3s_version: ''` → 설치 당일 stable 채널 | 같은 파일 |
| 제어 노드 | Raspberry Pi, Debian 13 aarch64, Python 3.13.5, Ansible core 2.19.11, community.general 11.2.1 | [CONTROL-NODE.md](../../infra_k8s/ansible/CONTROL-NODE.md) |
| TLS | Let's Encrypt (certbot, 노드 A 호스트에서 갱신, 웹루트 방식) | [edge.yaml](../../infra_k8s/base/edge.yaml) |
| kubelet 예약 | `system_reserved` edge 1000m/3Gi, ai 1000m/2Gi, `eviction_hard: memory.available<500Mi` | [group_vars/all.yml](../../infra_k8s/ansible/group_vars/all.yml) |

운영 두 노드는 `v1.36.4+k3s1`, Ubuntu 24.04.4 다(2026-09-28 `kubectl get nodes` 로 확인). 저장소에는 고정값이 없으니 같은 버전으로 다시 설치하려면 `k3s_version` 에 이 값을 넣는다.

### 2-2. 워크로드 (이미지 태그는 매니페스트 기준)

| 워크로드 | 종류 | 이미지 | 노드 | requests / limits | PVC |
|---|---|---|---|---|---|
| edge (웹서버) | Deployment, `Recreate` | `mupid-web:<커밋SHA>` ← `nginx:1.27-alpine` ([web.Dockerfile](../../infra_k8s/image/web.Dockerfile)) | A | 100m·64Mi / 256Mi | — |
| api (WAS) | Deployment | `mupid-api:<커밋SHA>` ← `azul-zulu:21-jre` ([infra/image/Dockerfile](../../infra/image/Dockerfile)), 포트 8800 | A | 500m·700Mi / 2Gi | — |
| ai (추천 서버) | Deployment, replicas 1 | `mupid-ai:<커밋SHA>` ← `python:3.12-slim`, uvicorn 8100 ([ai.Dockerfile](../../infra_k8s/image/ai.Dockerfile)) | B | 500m·512Mi / 3000m·2Gi | hostPath `/srv/mupid-k8s/ai-artifacts`, `ai-state` |
| ai-labeler | CronJob `0 4 * * *` | `mupid-ai` | B | 250m·512Mi / 2000m·2Gi | 위와 같음 |
| mysql | StatefulSet | `mysql:8.4` (utf8mb4) | A | 500m·600Mi / 2Gi | 20Gi |
| redis | StatefulSet | `redis:7.4-alpine` | A | 100m·64Mi / 512Mi | 5Gi |
| clickhouse | StatefulSet ×2 (샤드) | `clickhouse/clickhouse-server:25.3` | A·B | 300m·1Gi / 4Gi | 30Gi |
| clickhouse-keeper | StatefulSet | `clickhouse/clickhouse-keeper:25.3` | A | 50m·128Mi / 512Mi | 5Gi |
| kafka (KRaft 단일) | StatefulSet | `apache/kafka:3.9.1` | B | 200m·512Mi / 2Gi | 20Gi |
| storage (filer+S3) | StatefulSet | `chrislusf/seaweedfs@sha256:08d5…` (digest 고정) | A | 150m·200Mi / 768Mi | 10Gi |
| storage-master | StatefulSet | 같음 | A | 50m·64Mi / 256Mi | 1Gi |
| storage-volume | StatefulSet (양 노드) | 같음 | A·B | 100m·128Mi / 512Mi | 50Gi |
| qdrant | StatefulSet | `qdrant/qdrant@sha256:1236…` (v1.19.1 digest) | B | 200m·384Mi / 1500m·3Gi | 20Gi + 스냅샷 30Gi |
| qdrant-snapshot | CronJob `30 5 * * *` | `curlimages/curl@sha256:58ad…` | B | 10m·16Mi / 200m·64Mi | — |
| dbstudio | StatefulSet (`DBSTUDIO_CLUSTER_ROLE=replica`) | `ghcr.io/yejun614/db-studio@sha256:75ed…` (v0.2.3-alpine) + `tailscale/tailscale:v1.102.3` 사이드카 | B | 150m·192Mi / 768Mi | 5Gi |

출처: [infra_k8s/base/](../../infra_k8s/base/) 의 각 `*.yaml`. 스토리지 클래스는 k3s 기본 local-path.

### 2-3. 애플리케이션 런타임

| 구분 | 버전 | 근거 |
|---|---|---|
| JVM | Java 21 (Azul Zulu, 빌드 `azul-zulu:21-jdk` / 실행 `azul-zulu:21-jre`), `JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul` | [build.Dockerfile](../../infra/image/build.Dockerfile), [Dockerfile](../../infra/image/Dockerfile) |
| Spring Boot | 4.1.2-SNAPSHOT (Gradle, `bootJar`) | [api/build.gradle](../../api/build.gradle) |
| 웹서버 | nginx 1.27-alpine (프론트 정적 파일을 이미지에 포함) | [web.Dockerfile](../../infra_k8s/image/web.Dockerfile) |
| 프론트 빌드 | Node 22 (`node:22-bookworm-slim`), vinext 정적 빌드 | [web.build.Dockerfile](../../infra_k8s/image/web.build.Dockerfile) |
| AI 서버 | Python 3.12, FastAPI + uvicorn | [ai.Dockerfile](../../infra_k8s/image/ai.Dockerfile) |

### 2-4. nginx 주요 설정 ([base/nginx/default.conf](../../infra_k8s/base/nginx/default.conf))

| 항목 | 값 |
|---|---|
| server_name | `j15a204.p.ssafy.io` |
| 80 | `/.well-known/acme-challenge/` (웹루트 `/var/www/acme`), 나머지는 308 → https |
| 443 | `ssl_certificate /etc/letsencrypt/live/j15a204.p.ssafy.io/fullchain.pem`, TLSv1.2·1.3 |
| 업스트림 | `mupid_api` = `api:8800`, `mupid_storage` = `storage:8888` |
| 본문 상한 | 기본 20m, 청크 업로드 100m, 시스템 업로드 600m |
| 음원/업로드 | `auth_request` 로 Spring에 인증 후 SeaweedFS로 프록시 (그래서 Ingress를 쓰지 않는다) |
| 다운로드 | `/download/mupid.apk`, `native.json`, `web.json`(no-store), `web-<숫자>.tar.gz`(immutable) |

이 파일은 [infra/deploy/nginx.conf](../../infra/deploy/nginx.conf)의 사본이며 어긋나면 [tests/test_manifests.py](../../infra_k8s/tests/test_manifests.py)가 실패한다.

## 3. 처음부터 구축 절차

상세 명령은 [OPERATIONS-K8S.md § 처음부터 구축](../../infra_k8s/OPERATIONS-K8S.md)을 따른다. 요약:

| # | 단계 | 명령 / 작업 |
|---|---|---|
| 0 | AWS 보안그룹 | 노드 간 6443(A만)·10250·8472/udp, 소스는 상대 노드 사설 IP/32 ([security-group.md](../../infra_k8s/cluster/security-group.md)). 외부는 22·80·443만 |
| 1 | 제어 노드 준비 | `cp infra_k8s/.env.example infra_k8s/.env` 후 값 채움 → `set -a; . ../.env; set +a` (ansible 디렉터리에서) |
| 2 | 사전 점검 | `ansible-playbook -i inventory.ini preflight.yml` (읽기 전용) |
| 3 | (기존 배포가 있으면) 철거 | `ansible-playbook -i inventory.ini teardown.yml -e confirm_destroy=DESTROY` — **되돌릴 수 없음** |
| 4 | 클러스터 설치 | `ansible-playbook -i inventory.ini cluster.yml` (ufw, k3s server/agent, 노드 라벨) |
| 5 | 배포 수신기 설치 | `ansible-playbook -i inventory.ini receivers.yml` ([receivers.yml](../../infra_k8s/ansible/receivers.yml)). 배포 계정 `mupid-deploy-k8s` 와 `authorized_keys` 강제 명령은 사람이 미리 만든다 |
| 6 | Secret·자격증명 | 노드에서 `/srv/mupid-k8s/.env` 작성 → `kubectl -n mupid create secret generic mupid-secrets --from-env-file=...`, `mupid-storage-s3 --from-file=s3.json=...`, `dbstudio-secrets`, `dbstudio-tailscale`. `kubectl apply -f infra_k8s/deploy/rbac.yaml` 후 `deployer` 토큰으로 `/srv/mupid-k8s/deploy.kubeconfig`(600, root) |
| 7 | 워크로드 | `kubectl apply -k infra_k8s/overlays/cutover` (이후 재적용은 반드시 [deploy/apply.sh](../../infra_k8s/deploy/apply.sh)) → `sudo certbot renew --dry-run` 통과 확인 |
| 8 | SeaweedFS 버킷 | `echo 's3.bucket.create -name mupid' \| kubectl -n mupid exec -i statefulset/storage -- weed shell -master=storage-master:9333` (없으면 403) |
| 9 | MySQL 스키마·시드 | `api/knowledge/domain/erd.sql` → `seed-codes.sql` 적용 (`ddl-auto: none`이라 앱이 만들지 않음). `mupid_app` 계정도 사람이 만든다. [schema-check.py](../../infra_k8s/deploy/schema-check.py)로 대조 |
| 10 | 분석 스택 | Kafka 토픽 `mupid.listen.events`(파티션 6) 생성 **후** `api/clickhouse/init/01-schema.sql` (ON CLUSTER) |
| 11 | Qdrant | 키 주입 후 `rollout restart statefulset/qdrant`, 컬렉션 생성(`python -m ai_server.tools.qdrant_collection --api-key-stdin`), 벡터는 JupyterHub 배치 `vectors-send`로 채움 |
| 12 | 이미지 배포 | CI `k8s-deploy-api` · `k8s-deploy-web` · `k8s-deploy-ai` 실행 (그 전까지 api/edge는 `placeholder` 태그라 `ErrImageNeverPull`이 정상) |
| 13 | 검증 | `curl https://j15a204.p.ssafy.io/api/actuator/health` → UP, `/deployment.txt` = 배포 커밋 SHA |

기동 순서: mysql → storage-master → storage-volume → storage → clickhouse-keeper → clickhouse → kafka → api → edge
(`kubectl -n mupid rollout status ...`로 확인).

## 4. CI/CD

### 4-1. 파이프라인 구조 ([.gitlab-ci.yml](../../.gitlab-ci.yml))

stage: `verify` → `area-gate` → `ai-review` → `package` → `deploy`

| 파일 | 주요 job | 이미지 | 비고 |
|---|---|---|---|
| [infra/ci/labels.yml](../../infra/ci/labels.yml) | `area-preflight` | `alpine:3` | MR 영역 라벨(Frontend·Backend·AI·Data·Infra)이 검증 job을 고른다 |
| [infra/ci/frontend.yml](../../infra/ci/frontend.yml) | `frontend-verify` | `node:22-bookworm-slim` | |
| [infra/ci/backend.yml](../../infra/ci/backend.yml) | `backend-verify` | `azul-zulu:21-jdk` | |
| [infra/ci/ai-server.yml](../../infra/ci/ai-server.yml) | `ai-server-verify` | `mupid-ci-ai:1` (러너에서 직접 구움) | |
| [infra/ci/data.yml](../../infra/ci/data.yml) | `data-verify`·`data-check`·`data-deploy`·`data-batch` | `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` | JupyterHub 배포 |
| [infra/ci/app.yml](../../infra/ci/app.yml) | `app-verify`·`app-build`·`app-web-release` | `mupid-ci-android:1` | APK·웹 번들 → `/download/` |
| [infra/ci/model.yml](../../infra/ci/model.yml) | `model-verify`·`model-publish` | uv python3.12 | [models.lock](../../models.lock) 기반 |
| [infra/ci/manifests.yml](../../infra/ci/manifests.yml), [infra.yml](../../infra/ci/infra.yml) | `manifests-verify`·`infra-verify` | `python:3.12-slim` | 매니페스트 정적 검사 |
| [infra/ci/area-gate.yml](../../infra/ci/area-gate.yml) | `area-gate` | `alpine:3` | 영역 검증이 실제로 돌았는지 확인 |
| [infra_k8s/ci/k8s-release.yml](../../infra_k8s/ci/k8s-release.yml) | 아래 표 | `moby/buildkit@sha256:80b1…`, `alpine:3` | 보호 브랜치 `master` 전용 |

### 4-2. 배포 job ([k8s-release.yml](../../infra_k8s/ci/k8s-release.yml))

조건: `master` push + 보호 브랜치 + `MUPID_DEPLOY_ENABLED == "true"`. `resource_group: mupid-k8s`로 직렬 실행.

| 순서 | job | 하는 일 | 노드 수신기 |
|---|---|---|---|
| 1 | `k8s-deploy-manifests` | `infra_k8s/{base,overlays}` 묶음 전송 → 적용 | `receive-manifests.py` |
| 2 | `k8s-deploy-env` | `MUPID_API_ENV` 내용 전송 → Secret `mupid-secrets` 교체, api 재시작 (같으면 `UNCHANGED`) | `receive-env.py` |
| 3 | `k8s-deploy-ai-env` | `MUPID_AI_SERVER_ENV` → Secret `mupid-ai-secrets`, ai만 롤아웃 | `receive-ai-env.py` |
| 4 | `k8s-deploy-api` | `package-image.sh`(buildkit, `linux/amd64`) → `deploy-k8s.sh api` | `receive-k8s.py` |
| 4 | `k8s-deploy-web` | `package-web.sh` → `deploy-k8s.sh web` | `receive-k8s.py` |
| 4 | `k8s-deploy-ai` | `package-ai.sh`(채택 모델 가중치 굽기) → `deploy-k8s.sh ai` → `model_publish.py adopt` (`ai_server/**` 등 변경 시만) | node-b `receive-k8s.py` |
| — | `app-build`·`app-web-release` | `deploy-apk.sh`·`deploy-web.sh` → `/srv/mupid-k8s/downloads` | `receive-apk.py`·`receive-web.py` |

수신기 동작: 1GiB 상한 → sha256 대조 → 아카이브에 요청 태그 하나만 있는지 → `k3s ctr images import` →
`kubectl set image` → `rollout status --timeout=180s` → 실패 시 `rollout undo`.
결과는 `DEPLOYED`·`ALREADY_DEPLOYED`·`ROLLED_BACK`·`ROLLBACK_FAILED`(사람 개입 필요).

SSH 강제 명령 형식: `deploy-k8s <api|web|ai> <40자리 SHA> <64자리 sha256>`
([ssh-entrypoint-k8s.sh](../../infra_k8s/deploy/ssh-entrypoint-k8s.sh)). 배포 계정은 이 명령 외에 실행할 수 없다.

### 4-3. GitLab Runner

| 항목 | 값 | 근거 |
|---|---|---|
| 태그 | `mupid-ci` (docker executor, 전용 러너 1개) | CI 파일 전체 |
| 러너 이미지 | `gitlab/gitlab-runner@sha256:af03…`, 매니저 0.5 CPU / 512m | [infra/runner/compose.yml](../../infra/runner/compose.yml) |
| 호스트 | 라즈베리파이(`mupid-ci-pi`, `/srv/mupid-runner/config`) → 이후 macOS(Docker Desktop, [compose.macos.yml](../../infra/runner/compose.macos.yml))로 이전 | [CONTROL-NODE.md](../../infra_k8s/ansible/CONTROL-NODE.md), [backend.yml](../../infra/ci/backend.yml) 주석 |
| 한도 설정 | `python3 infra/runner/configure.py` (`MUPID_RUNNER_CONFIG`, `MUPID_RUNNER_CONCURRENT`) | [configure.py](../../infra/runner/configure.py) |
| CI 전용 이미지 | `sh infra/runner/build-images.sh` → `mupid-ci-ai:1`, `mupid-ci-android:1` (레지스트리 없음, `pull_policy: if-not-present`) | [build-images.sh](../../infra/runner/build-images.sh) |

> TODO: 팀 확인 — 현재 러너가 도는 호스트(Pi / macOS 노트북)와 등록 토큰 보관 위치. macOS 노트북은 잠자기 시 러너가 오프라인이 된다.

## 5. 환경 변수·시크릿 키 표

값은 어디에도 적지 않는다. 아래는 **키 이름**과 주입 위치뿐이다.

### 5-1. Secret `mupid-secrets` (ns `mupid`)

입력: GitLab File 변수 `MUPID_API_ENV` → `receive-env.py` → Secret. 키 선언 목록은 [infra/deploy/.env.example](../../infra/deploy/.env.example).
api 파드는 `envFrom` 없이 [api.yaml](../../infra_k8s/base/api.yaml)의 `secretKeyRef`로 키를 하나씩 받는다.

| 키 | 받는 파드 | 용도 |
|---|---|---|
| `MUPID_MYSQL_ROOT_PASSWORD` | mysql | root 비밀번호 (api에는 주지 않음) |
| `MUPID_MYSQL_PASSWORD` | api | `mupid_app` 비밀번호 |
| `MUPID_JWT_SECRET` | api | JWT 서명 키 |
| `MUPID_S3_ENDPOINT` · `MUPID_S3_ACCESS_KEY` · `MUPID_S3_SECRET` · `MUPID_S3_BUCKET` | api | SeaweedFS S3 (버킷 `mupid`와 일치해야 함) |
| `MUPID_CLICKHOUSE_PASSWORD` | api, clickhouse | ClickHouse `mupid` 계정 |
| `MUPID_CLICKHOUSE_SERVING_PASSWORD` | clickhouse, ai | 읽기 계정 `mupid_serving` (비면 잠김) |
| `MUPID_KAFKA_CLUSTER_ID` | kafka | KRaft 클러스터 ID |
| `MUPID_GMAIL_CLIENT_ID` · `MUPID_GMAIL_CLIENT_SECRET` · `MUPID_GMAIL_TOKEN` · `MUPID_GMAIL_SEND_ADDRESS` | api | 인증 메일 발송 |
| `MUPID_MATTERMOST_WEBHOOK_URL` | api | 문의 알림 (선택) |
| `MUPID_CLIENT_WEBSITE` | api | 공식 클라이언트 주소 |
| `MUPID_AI_GMS_KEY` · `MUPID_AI_BASE_URL` · `MUPID_AI_MODEL` | api | LLM 대화 (선택, 키 없으면 `/api/v1/ai/chat` 503) |
| `MUPID_RECOMMENDER_INTERNAL_TOKEN` | api | 추천 서버 `/internal` 공유 비밀 (`MUPID_AI_INTERNAL_TOKEN`과 같은 값) |
| `MUPID_AI_INTERNAL_TOKEN` | ai | 위와 짝 |
| `MUPID_QDRANT_API_KEY` | qdrant, qdrant-snapshot, api | Qdrant 쓰기 키 |

### 5-2. 그 밖의 Secret

| Secret | 키 | 만드는 법 |
|---|---|---|
| `mupid-ai-secrets` | `MUPID_QDRANT_READ_ONLY_API_KEY`, `MUPID_VECTOR_BACKEND`, `MUPID_QDRANT_URL`, `MUPID_QDRANT_COLLECTION`, `MUPID_QDRANT_TIMEOUT` (전부 선택) | GitLab File 변수 `MUPID_AI_SERVER_ENV` → `receive-ai-env.py` |
| `mupid-storage-s3` | `s3.json` (SeaweedFS S3 신원·권한) | `kubectl create secret generic mupid-storage-s3 --from-file=s3.json=/srv/mupid-k8s/s3.json` |
| `dbstudio-secrets` | `DBSTUDIO_MASTER_KEY`, `DBSTUDIO_CLUSTER_SECRET` (두 노드 동일, 분실 시 복구 불가) | `kubectl create secret generic ... --from-literal` |
| `dbstudio-tailscale` | `TS_AUTHKEY` | Tailscale auth key |

### 5-3. ConfigMap `api-config` (비밀 아님, 커밋됨 — [config.yaml](../../infra_k8s/base/config.yaml))

`MUPID_PORT=8800`, `MUPID_MYSQL_USER`, `MUPID_MYSQL_URL`(jdbc:mysql://mysql:3306/mupid…), `MUPID_REDIS_HOST/PORT/KEY_PREFIX`,
`MUPID_CLIENT_WEBSITE`, `MUPID_CLICKHOUSE_URL/USER`, `MUPID_KAFKA_BOOTSTRAP`(kafka:9092), `MUPID_KAFKA_TOPIC`(mupid.listen.events),
`MUPID_RECOMMENDER_BASE_URL`(http://ai:8100), `MUPID_QDRANT_URL`(http://qdrant:6333)

### 5-4. 빌드 시 환경 변수

| 변수 | 어디서 | 뜻 |
|---|---|---|
| `MUPID_COMMIT_SHA` (build-arg) | [package-web.sh](../../infra_k8s/ci/package-web.sh) → web.build.Dockerfile | `/deployment.txt`에 기록 |
| `MUPID_DEPLOY_TARGET=ec2`, `NEXT_TELEMETRY_DISABLED=1` | [web.build.Dockerfile](../../infra_k8s/image/web.build.Dockerfile) | 없으면 SSR로 빌드돼 `index.html`이 안 생김 |
| `--opt platform=linux/amd64` | package-image.sh / package-web.sh / package-ai.sh | arm64 러너에서 x86_64 노드용 이미지 생성 |
| `MUPID_BUILD_STAMP` → `MUPID_NATIVE_BUILD`, `MUPID_WEB_BUILD` | [infra/ci/build-stamp.sh](../../infra/ci/build-stamp.sh) | APK versionCode·웹 번들 `builtAt` (같은 출처여야 함) |
| `MUPID_API_ORIGIN`, `ANDROID_*`, `GOOGLE_SERVICES_JSON_BASE64` | `MUPID_APP_ENV` ([app/deploy.env.example](../../app/deploy.env.example)) | APK 서버 주소(컴파일 시 고정)·서명·Firebase |

### 5-5. GitLab CI/CD 변수 (이름만, 전부 Protected)

| 변수 | 형식 | 용도 |
|---|---|---|
| `MUPID_DEPLOY_ENABLED` | Variable | 배포 job 스위치 (`true`) |
| `MUPID_K8S_BASE_URL` | Variable | 배포 후 health 확인 주소 |
| `MUPID_DEPLOY_K8S_SSH_KEY` | File | `mupid-deploy-k8s` 개인키 |
| `MUPID_DEPLOY_K8S_KNOWN_HOSTS` | File | 노드 호스트 키 (TOFU 금지) |
| `MUPID_DEPLOY_K8S_SSH_HOST` | Variable | api·web 배포 주소 (기본 node-a, 보통 비움) |
| `MUPID_AI_NODE_SSH` | Variable | **필수**, ai 이미지·색인을 보낼 node-b 주소 |
| `MUPID_API_ENV` | File | `mupid-secrets` 내용 |
| `MUPID_AI_SERVER_ENV` | File | `mupid-ai-secrets` 내용 |
| `MUPID_APP_ENV` | File | APK 빌드 값 (키스토어 등) |
| `MUPID_DATA_ENV` | File | JupyterHub 배포·배치 값 ([music_preprocessing/deploy.env.example](../../music_preprocessing/deploy.env.example)) |
| `MUPID_SYSTEM_CI_REFRESH_TOKEN` | Variable(Masked) 또는 File | `system-ci` 계정 장기 리프레시 토큰 (model-publish·k8s-deploy-ai) |
| `MUPID_MODEL_RECOMPUTE_GATE` | Variable | 모델 게시 재계산 관문 (`true`/비움) |
| `MUPID_BUILD_STAMP` | Variable | (선택) 빌드 스탬프 고정 |

정리 대상(쓰지 않음): `MUPID_DEPLOY_SSH_KEY`, `MUPID_DEPLOY_KNOWN_HOSTS`, `MUPID_JUPYTER_*`, `GITLAB_REVIEW_TOKEN`, `MUPID_FRONTEND_ENV`(이름만 있음).
상세: [infra/ci/VARIABLES.md](../../infra/ci/VARIABLES.md).

### 5-6. 제어 노드 `.env` ([infra_k8s/.env.example](../../infra_k8s/.env.example))

`MUPID_CONTROLLER_HOST_PUBLIC/PRIVATE/SSH_USER/SSH_KEY`, `MUPID_NODE_A_HOST_PUBLIC/PRIVATE/SSH_USER/SSH_KEY`,
`MUPID_NODE_B_HOST_PUBLIC/PRIVATE/SSH_USER/SSH_KEY`.
PUBLIC은 Ansible 접속용, PRIVATE는 k3s가 광고할 VPC 사설 주소 — 섞이면 6443이 인터넷에 열린다(`env_check`가 매번 검사).

## 6. 배포 특이사항

1. **매니페스트를 `kubectl apply -k`로 그냥 재적용하지 않는다.** base의 이미지 태그가 `mupid-*:placeholder`라 실행 중 이미지가
   덮여 `ErrImageNeverPull`이 난다. [deploy/apply.sh](../../infra_k8s/deploy/apply.sh)가 `/srv/mupid-k8s/current-<component>`의 태그로 치환해 적용한다.
2. **Secret 키를 더할 때 세 곳을 같이 고친다**: `MUPID_API_ENV` 변수, [api.yaml](../../infra_k8s/base/api.yaml)의 `secretKeyRef`, [infra/deploy/.env.example](../../infra/deploy/.env.example). 노드에만 수동으로 넣으면 다음 `k8s-deploy-env`가 옛 내용으로 되돌린다.
3. **Qdrant 키를 바꾸면 Qdrant도 재시작**해야 한다 (`kubectl -n mupid rollout restart statefulset/qdrant`). 수신기는 api·ai만 재시작한다.
4. **`MUPID_RECOMMENDER_INTERNAL_TOKEN`과 `MUPID_AI_INTERNAL_TOKEN`은 동시에 켠다.** 한쪽만 켜면 추천이 401.
5. **`ddl-auto: none`** — 컬럼 추가가 있는 배포는 ALTER를 먼저 수동 적용한다. 어긋나면 그 컬럼을 처음 쓰는 요청에서 터진다(`schema-check.py`로 사전 대조).
6. **edge는 `Recreate`** (hostPort 80/443 때문) → 프론트 배포·롤백 시 수 초 중단. api는 rolling이라 구·신 버전이 잠깐 공존 → DB 스키마 호환 필수.
7. **배포 수신기 설치는 CI로 못 한다.** `infra_k8s/deploy/`를 고친 뒤 `receivers.yml`을 제어 노드에서 돌린다. 수신기마다 `/etc/sudoers.d/` 규칙이 필요하고 빠지면 `sudo: a password is required`로 실패한다.
8. **같은 SHA 재배포는 거절될 수 있다.** 같은 커밋에 체크섬이 다른 이미지가 오면 `An immutable release already exists with a different checksum` ([receive-k8s.py](../../infra_k8s/deploy/receive-k8s.py)).
9. **이미지는 노드별로 적재**된다. `mupid-*` 워크로드에서 `nodeSelector`를 빼면 안 된다.
10. **추천 풀은 flow만.** [ai.yaml](../../infra_k8s/base/ai.yaml)이 `MUPID_POOL=flow`, `MUPID_POOL_EXTRA=''`로 고정 — Jamendo 곡이 추천에 섞이지 않게 한다.
11. **SeaweedFS 버킷이 없으면 404가 아니라 403**이 난다. 빈 스토리지에는 버킷을 먼저 만든다.
12. **Kafka 토픽이 ClickHouse DDL보다 먼저.** `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`.
13. **DB Studio·Qdrant·AI는 외부 포트를 열지 않는다.** 접근은 `kubectl port-forward` 또는 Tailscale.
    현재 매니페스트의 DB Studio는 NODE_B의 리플리카 하나이며 `DBSTUDIO_CLUSTER_MASTER`가 클러스터 밖 tailnet 주소를 가리킨다
    ([dbstudio.yaml](../../infra_k8s/base/dbstudio.yaml)). OPERATIONS-K8S.md 8절의 "마스터 NODE_B + 리플리카 NODE_A" 서술과 다르다.
14. 인증서 만료일은 [BASELINE.md](../../infra_k8s/BASELINE.md) 기준 2026-12-06. 갱신은 호스트 certbot(웹루트 `/srv/mupid-k8s/acme`).

> TODO: 팀 확인 — 인증서 갱신 후 edge 파드의 nginx reload 방법(certbot deploy-hook). 저장소에 k8s용 hook 정의가 없다.
> TODO: 팀 확인 — 노드 A 호스트의 백업 타이머(`mupid-backup.timer`)가 k8s 전환 뒤에도 유효한지.
> TODO: 팀 확인 — `system_reserved` 재조정 여부 (README에 "남은 작업"으로 기록됨).
> TODO: 팀 확인 — DB Studio 마스터가 도는 호스트(클러스터 밖 tailnet 노드)와 그 구축 방법.

## 7. 설정 파일 목록

| 파일 | 내용 | 커밋 여부 |
|---|---|---|
| [infra_k8s/.env.example](../../infra_k8s/.env.example) → `infra_k8s/.env` | 노드 접속 주소·SSH 키 경로 | 예시만 커밋 |
| [infra_k8s/ansible/inventory.ini](../../infra_k8s/ansible/inventory.ini) | 노드 그룹, `node_role` (주소는 env lookup) | 커밋 |
| [infra_k8s/ansible/group_vars/all.yml](../../infra_k8s/ansible/group_vars/all.yml) | k3s 버전·CIDR·포트·kubelet 예약 | 커밋 |
| [infra_k8s/ansible/cluster.yml](../../infra_k8s/ansible/cluster.yml) · [preflight.yml](../../infra_k8s/ansible/preflight.yml) · [teardown.yml](../../infra_k8s/ansible/teardown.yml) · [receivers.yml](../../infra_k8s/ansible/receivers.yml) | 플레이북 | 커밋 |
| [infra_k8s/base/kustomization.yaml](../../infra_k8s/base/kustomization.yaml) | 리소스 목록, ConfigMap 생성기 | 커밋 |
| [infra_k8s/base/config.yaml](../../infra_k8s/base/config.yaml) | ConfigMap `api-config`, `storage-filer` | 커밋 |
| [infra_k8s/base/nginx/default.conf](../../infra_k8s/base/nginx/default.conf) | 엣지 nginx | 커밋 |
| [infra_k8s/base/clickhouse/](../../infra_k8s/base/clickhouse/) | ClickHouse server·users·keeper XML | 커밋 |
| [infra_k8s/overlays/cutover/](../../infra_k8s/overlays/cutover/) | 엣지 hostPort 80/443 패치 | 커밋 |
| [infra_k8s/deploy/rbac.yaml](../../infra_k8s/deploy/rbac.yaml) | 배포용 ServiceAccount `deployer` (이미지 교체만) | 커밋 |
| [infra/deploy/.env.example](../../infra/deploy/.env.example) | `mupid-secrets` 키 목록 | 예시만 커밋 |
| 노드 `/srv/mupid-k8s/.env` | `mupid-secrets` 원본 (수신기가 씀) | **서버에만** |
| 노드 `/srv/mupid-k8s/s3.json` | SeaweedFS S3 신원 | **서버에만** |
| 노드 `/srv/mupid-k8s/deploy.kubeconfig` | `deployer` 토큰 | **서버에만** |
| 노드 `/srv/mupid-k8s/current-{api,web,ai}` | 현재 배포 태그 | 서버에만 (수신기가 씀) |
| 노드 `/etc/letsencrypt/` | 인증서 | 서버에만 |
| [infra/ci/VARIABLES.md](../../infra/ci/VARIABLES.md) | CI 변수 정의 | 커밋 |
| [app/deploy.env.example](../../app/deploy.env.example) · [music_preprocessing/deploy.env.example](../../music_preprocessing/deploy.env.example) | `MUPID_APP_ENV`·`MUPID_DATA_ENV` 키 목록 | 예시만 커밋 |
| [infra/runner/compose.yml](../../infra/runner/compose.yml) · [compose.macos.yml](../../infra/runner/compose.macos.yml) · [configure.py](../../infra/runner/configure.py) | GitLab Runner | 커밋 (`config.toml`은 러너 호스트에만) |

## 이력 — infra/ Compose 배포

2026-09-15 이전에는 단일 EC2에서 Docker Compose([infra/deploy/compose.yml](../../infra/deploy/compose.yml))로 운영했다.
k8s 전환 때 운영 데이터를 버리고 새로 구축하기로 해 `teardown.yml`로 `/srv/mupid`와 볼륨을 삭제했다.
`infra/ci/release.yml`은 `.gitlab-ci.yml` include에서 빠졌다. 지금 유효한 것은 `infra/deploy/.env.example`(Secret 키 목록)과
`infra/deploy/nginx.conf`(엣지 설정 원본)뿐이다.
