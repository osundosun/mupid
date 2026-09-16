# AI 추천 서버 — 인프라 인계

Kubernetes 배포에 필요한 것만 적는다. **Docker Compose 에는 넣지 않는다** — 운영은
Kubernetes 로 가기로 했고(Q6), compose 에 두면 두 벌을 맞춰야 한다.

측정일 2026-09-15 · 이미지 `ai_server/Dockerfile` 로 빌드·기동까지 확인함.

---

## 1. 이미지

```
docker build -f ai_server/Dockerfile -t <레지스트리>/mupid-ai-server:<태그> .
```

| 항목 | 값 |
|---|---|
| 베이스 | `python:3.12-slim` (멀티스테이지, wheel 빌드 분리) |
| 크기 | **827 MB** |
| 실행 사용자 | `mupid` (비루트, `--system`) |
| torch | **들어 있지 않다.** 사용자 벡터 계산을 NumPy 로 옮겼다 |
| 포함 | `ai_server/app`, `ai_server/tools` |

산출물은 이미지에 넣지 않는다. 곡이 늘 때마다 재배포해야 하고 513 MB 다.

---

## 2. 포트

| 포트 | 용도 |
|---|---|
| **8100** | HTTP. `MUPID_AI_PORT` 로 바꿀 수 있고 기본값이 8100 이다 |

`/internal/*` 는 **외부에 노출하지 않는다.** Spring 만 부르는 전제이고, Ingress 를
붙인다면 `/health` 외에는 막아야 한다.

---

## 3. 프로브

| 경로 | 용도 | 없을 때 |
|---|---|---|
| `GET /health` | **livenessProbe** — 프로세스 생존만 본다 | 산출물이 없어도 **200** |
| `GET /internal/ready` | **readinessProbe** — 실제로 추천할 수 있는가 | 산출물·ID 매핑이 없으면 **503** |

둘을 바꿔 쓰면 안 된다. `/health` 를 readiness 로 쓰면 **추천이 안 되는 파드로 트래픽이
간다.** `/internal/ready` 를 liveness 로 쓰면 산출물을 붙이기 전에 파드가 계속 재시작한다.

```yaml
livenessProbe:
  httpGet: { path: /health, port: 8100 }
  initialDelaySeconds: 30
  periodSeconds: 15
readinessProbe:
  httpGet: { path: /internal/ready, port: 8100 }
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 6
```

기동 시 카탈로그를 메모리에 올린다. 실측 1.8초였으나 산출물을 네트워크 볼륨에서 읽으면
더 걸린다. `startupProbe` 를 두거나 `initialDelaySeconds` 를 넉넉히 준다.

`/health` 는 **무엇이 없는지 본문에 적어 준다.** 파드가 안 뜨는 원인을 찾을 때 먼저 본다.

---

## 4. 모델 산출물 마운트

| 항목 | 값 |
|---|---|
| 마운트 경로 | **`/artifacts`** (`MUPID_AI_ARTIFACT_DIR` 기본값) |
| 실제 읽는 위치 | `/artifacts/data/proc/mupid/` |
| 접근 | **읽기 전용이면 충분하다** |
| 총 크기 | **513 MB** (`stage1_weights.npz` 포함, `stage1.pt` 제외) |

| 파일 | 크기 | 없으면 |
|---|---:|---|
| `moments_emb.npy` | 385 MB | **기동 실패** — 카탈로그를 못 만든다 |
| `features_v1.npz` | 93 MB | 학습 점수가 빠진다 |
| `stage1_section.npy` | 33 MB | 학습 점수가 빠진다 |
| `moments.parquet` | 3.2 MB | **기동 실패** |
| `stage1_weights.npz` | **352 KB** | **묶음에 반드시 넣는다.** 없으면 `stage1.pt` 를 찾는데 이미지에 torch 가 없어 학습 점수가 통째로 빠진다. 오류는 안 나고 규칙 점수로 조용히 내려간다 |
| `stage1.pt` | 349 KB | torch 판. 개발 환경용이고 **배포 묶음에는 필요 없다** |
| `id_map.parquet` | 작음 | **`/internal/ready` 가 503** — 숫자 ID 를 못 잇는다 |
| `labels_service.parquet` | 가변 | 모든 사용자가 콜드스타트가 된다 (오류는 안 난다) |

> **네 파일이 행 번호로 서로를 가리킨다**(`moments`·`emb`·`features`·`stage1`). 버전이
> 섞이면 엉뚱한 곡을 추천하고 **그 상태로도 정상 기동한다.** 2026-09-09~10 에 두 번
> 겪었다. 그래서 기동 시 무결성 검사가 붙어 있고, 어긋나면 `/internal/ready` 가 실패한다.
> **묶음 단위로 교체하고, 한 파일만 바꾸지 않는다.**

배치 CLI 는 같은 경로에 **쓴다**(`id_map.parquet`, `labels_service.parquet`,
`onboarding.parquet`, `signals_watermark.json`). 서버용 마운트는 읽기 전용, 배치용은
쓰기 가능으로 나누거나, 같은 PVC 를 `ReadWriteMany` 로 쓴다.

---

## 5. 자원 요구량

| | 요청 | 상한 | 근거 |
|---|---|---|---|
| CPU | `500m` | `2` | 추천 1건 계산은 짧지만 카탈로그 전수 비교라 순간 CPU 를 쓴다 |
| 메모리 | **`1.5Gi`** | **`3Gi`** | 아래 |

실측 — 산출물 없이 유휴 상태에서 **80 MB**. 여기에 `moments_emb.npy` 385 MB 가 얹힌다
(`mmap` 으로 읽지만 조회 때 페이지가 올라온다). 곡이 늘면 이 파일이 같이 커지므로
**메모리는 산출물 크기에 비례해 잡는다.**

GPU 는 필요 없다. 서빙 경로에 torch 가 없다.

레플리카는 무상태라 여러 개 띄울 수 있다. 다만 파드마다 385 MB 를 각자 올린다.

---

## 6. 환경변수

### 반드시 필요

| 이름 | 예 | 없으면 |
|---|---|---|
| `MUPID_AI_ARTIFACT_DIR` | `/artifacts` | 이미지 기본값이 있어 생략 가능 |

### Secret 으로 넣을 것

| 이름 | 용도 |
|---|---|
| `MUPID_AI_INTERNAL_TOKEN` | `/internal` 공유 비밀. **Spring 의 `MUPID_RECOMMENDER_INTERNAL_TOKEN` 과 같은 값** |
| `MUPID_CLICKHOUSE_PASSWORD` | 배치가 ClickHouse 를 읽을 때 |
| `MUPID_API_REFRESH_TOKEN` | 배치가 온보딩을 조회할 때 (ROLE_SYSTEM 계정) |

> **토큰은 한 쌍으로 다룬다.** AI 에만 `MUPID_AI_INTERNAL_TOKEN` 을 넣고 Spring 에 짝을
> 주지 않으면 **추천 호출이 전부 401** 이 된다. 배포 설정만으로 조용히 죽는 형태다.
> 기본은 꺼져 있고, 값을 넣는 쪽이 켜는 쪽이다.

### ConfigMap 으로 넣을 것

| 이름 | 기본 | 용도 |
|---|---|---|
| `MUPID_AI_PORT` | `8100` | 수신 포트 |
| `MUPID_POOL` | `flow` | 추천 후보 풀 |
| `MUPID_POOL_EXTRA` | `jamendo` | 추가 풀 (쉼표 구분) |
| `MUPID_COMBINER` | | 점수 결합기 파일명 |
| `MUPID_ID_MAP` | | 매핑표 경로 재정의 |
| `MUPID_AI_NLP_URL` | | 자연어 해석기. 비우면 QueryPlan 경로만 쓴다 |
| `MUPID_CLICKHOUSE_URL` / `_USER` | | 배치용. **읽기 전용 계정** |
| `MUPID_API_BASE_URL` | | 배치용. Spring 주소 |

서버 파드에는 `MUPID_CLICKHOUSE_*` 와 `MUPID_API_*` 가 필요 없다. 배치에만 준다.

---

## 7. Job · CronJob

> **2026-09-16 변경.** `fetch_onboarding` 은 없앴다 — 온보딩이 요청에 실려 온다.
> 그리고 **서버 파드에도 `MUPID_CLICKHOUSE_*` 가 필요해졌다.** 행동 데이터를 요청마다
> 직접 조회하기 때문이다(§6 의 "배치에만 준다" 는 더 이상 맞지 않는다). 읽기 전용
> 계정이면 충분하다.


배치는 전부 CLI 진입점이 있다. 이미지 안에서 도는 것까지 확인했다.

```
python -m ai_server.tools.<이름>
```

| 진입점 | 언제 | 하는 일 |
|---|---|---|
| `build_id_map <표>` | 곡 적재 뒤 | MySQL 내보내기 → `id_map.parquet` |
| `push_sections --full` | 최초 1회 | 구간 전량을 ClickHouse 로 |
| `push_sections` | **신규 곡·재분석 뒤** | 바뀐 구간만 |
| `push_sections --audit` | 하루 1회 | 대사. 차이가 있으면 **종료 코드 3** |
| `pull_signals` | 짧은 주기 | ClickHouse 신호 → 행동 로그 (워터마크 증분) |
| `run_labeler` | 신호 수집 뒤 | 라벨·선호 구간 갱신 |
| `export_stage1_npz` | 모델 교체 시 | `stage1.pt` → `stage1_weights.npz` |

**재생할 때마다 도는 것은 하나도 없다.** `push_sections` 는 곡이 늘거나 경계가 바뀔 때만
의미가 있다. 전처리 완료 이벤트로 Job 을 띄우거나 짧은 주기 CronJob 으로 돌리면 된다 —
증분이라 자주 돌려도 비용이 거의 없다.

종료 코드로 성공을 판단한다. `0` 정상, `1` 실패, `3` 대사 차이 발견.

CronJob 은 `concurrencyPolicy: Forbid` 를 권한다. 같은 워터마크를 두 파드가 동시에
전진시키면 건너뛰는 구간이 생긴다.

---

## 8. 아직 못 정한 것 — 인프라 판단 필요

| # | 항목 |
|---|---|
| 1 | **산출물 전달 방법.** PVC / 오브젝트 스토리지 → initContainer 내려받기 / 이미지 동봉 중 무엇으로 갈지. 513 MB 이고 곡이 늘면 커진다 |
| 2 | 서버와 배치가 같은 PVC 를 쓸지. 서버는 읽기만, 배치는 쓴다 |
| 3 | `/internal` 을 Ingress 에서 막는 방식 (NetworkPolicy / 경로 차단) |
| 4 | 레플리카 수. 파드마다 385 MB 를 각자 올린다 |
| 5 | ClickHouse 읽기 전용 계정 발급 |

---

## 9. 확인한 것

```
이미지 빌드            성공 (827 MB)
컨테이너 기동          성공
GET /health            200 · 없는 산출물을 본문에 열거
GET /internal/ready    503 (산출물 없음 — 설계대로)
CLI 6종                이미지 안에서 전부 기동
torch                  없음
유휴 메모리            80 MB
```

### 랭킹 모델 (2026-09-15 확인)

`stage1_weights.npz` 를 실제 체크포인트에서 만들어 파리티를 확인했다.

```
python -m ai_server.tools.export_stage1_npz --trials 64
  최대 절대 오차 1.192e-07 · 순위 불일치 0/64
  저장 stage1_weights.npz (352KB)   in_dim 285 · hist_max 32 · half_life 14.0
```

torch 를 막은 상태에서 이 파일만으로 사용자 벡터가 나오는 것까지 확인했다(dim 128).
**이 파일이 묶음에 없으면 배포 이미지에서 학습 점수가 조용히 사라진다** — `/health` 의
`model_backend` 가 `numpy` 인지, `model_error` 가 비어 있는지로 확인한다.

### 아직 확인하지 못한 것

산출물을 붙인 상태의 **전체 기동**은 아직이다. MySQL 스키마가 비어 있어
`id_map.parquet` 을 실제 숫자 ID 로 만들지 못했기 때문이다. DDL 적용이 선행이다.
