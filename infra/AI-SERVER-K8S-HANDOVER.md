# AI 추천 서버 — 인프라 인계

Kubernetes 배포에 필요한 것만 적는다. **Docker Compose 에는 넣지 않는다** — 운영은
Kubernetes 로 가기로 했고(Q6), compose 에 두면 두 벌을 맞춰야 한다.

측정일 2026-09-15 · 이미지 `ai_server/Dockerfile` 로 빌드·기동까지 확인함.


> ## 2026-09-16 · 인프라 결론 (2회 갱신)
>
> 이 문서의 §4(산출물 마운트)와 §8(아직 못 정한 것)은 **더 이상 맞지 않는다.**
> 537MB 를 통째로 마운트하는 전제로 쓰여 있다. 아래가 확정된 방침이고 매니페스트
> (`infra_k8s/base/ai.yaml`)가 이 방침대로 되어 있다.
>
> **이 자리에 처음 적었던 결론 중 두 가지는 틀렸다.** 지운 것이 아니라 여기 남긴다 —
> 그 근거를 그대로 믿고 다음 사람이 같은 판단을 반복하지 않게.
>
> | 처음 적은 것 | 무엇이 틀렸나 |
> |---|---|
> | `features_v1.npz` 93MB → **제거**("서빙이 읽지 않는다") | 읽는다. `app/main.py` 가 기동 때 `Catalog()` 를 만들고 그 생성자가 `features.load()` 를 부른다. 지연 import 라 grep 에 걸리지 않았다 |
> | 임베딩·구간 벡터 → **Pinecone** | 규모가 근거를 못 준다. 추천 풀은 10,007구간뿐이라 `Z[pool_rows] @ q` 가 약 15 MFLOP·1ms 미만이다. 외부 저장소를 넣으면 요청마다 네트워크 왕복이 생긴다 |
>
> ### 확정
>
> | 산출물 | 전체 | 풀만 | 어디로 |
> |---|---:|---:|---|
> | `moments_emb.npy` | 403MB | **61MB** | 노드 hostPath |
> | `features_v1.npz` | 75MB | **11MB** | 노드 hostPath |
> | `stage1_section.npy` | 34MB | **5MB** | 노드 hostPath |
> | `moments.parquet` | 3.2MB | 작음 | 노드 hostPath |
> | `content.parquet` 등 | 작음 | 작음 | 노드 hostPath |
> | `labels_service.parquet` | 가변 | — | **ClickHouse `section_labels`** |
> | `id_map.parquet` | 작음 | — | **제거** (계약이 문자열 ID 다) |
> | `stage1_weights.npz` | 352KB | — | **이미지 동봉** (모델 버전 = 이미지 태그) |
>
> **537MB 가 약 80MB 가 된다.** 줄어든 이유는 압축이 아니라 범위다 —
> `moments.parquet` 65,540행 중 서빙이 쓰는 것은 추천 풀 10,007행뿐이고, 나머지는
> PCA·Stage 1 을 학습시킨 FMA 곡이다. 자르기는 행 선택이라 값이 바뀌지 않는다
> (`ai_server/tools/slice_index.py`, 근거는 그 모듈의 설명과 시험).
>
> ### 그 80MB 가 노드에 오는 길
>
> 이미지에 굽지 않는다. 색인 교체가 코드 배포보다 잦아서, 구우면 색인을 바꿀 때마다
> 이미지를 다시 굽고 롤아웃해야 한다(`ai_server/Dockerfile` 의 설명).
>
> **사람이 scp 하지도 않는다.** `upload-index` 동사를 강제 명령에 추가했다 —
> `upload-apk`·`upload-env` 와 같은 구조다.
>
>     python -m ai_server.tools.slice_index --out .release/ai-index
>     sh infra_k8s/ci/deploy-index.sh
>
> 받는 쪽(`infra_k8s/deploy/receive-index.py`)이 체크섬을 맞추고, 파드와 **같은**
> 무결성 검사(행 수 일치·`emb_row` 연속·중복 없음)를 미리 돌리고, 새 판을 옆에 풀어
> `current` 심볼릭 링크를 원자적으로 갈아 끼운다. 한 파일씩 덮으면 그 사이에 기동한
> 파드가 **서로 다른 판의 색인을 섞어 읽고**, 그때 서비스는 정상처럼 뜨면서 엉뚱한
> 곡을 추천한다.
>
> 이건 CI job 이 아니다. 전체 색인은 저장소에도 러너에도 없고 GPU 배치 장비에 있다.
> 그래서 산출물을 들고 있는 쪽에서 사람이 돌린다 — 손으로 scp 하던 것과 달리
> 검증·원자성·되돌리기가 붙어 있다.
>
> §8 의 2·4번(공유 볼륨·레플리카)은 해소됐다. 마운트가 읽기 전용이고 파드가
> `nodeSelector` 로 같은 노드에 묶이므로 여러 파드가 같은 디렉터리를 함께 읽는다 —
> ReadWriteMany 가 필요해지는 것은 쓰기를 열 때다. 3번(`/internal` 차단)은 이미
> 되어 있다(Service 가 ClusterIP, nginx 는 `/api/`·`/storage/` 만 프록시).
>
> §7 이 말한 배치(`pull_signals`·`run_labeler`)는 원천이 클러스터 안
> (MySQL·ClickHouse)에 있으므로 밖에서 가져올 것이 아니다.
>
> ### 코드 쪽에 남은 일
>
> `replicas: 0` 인 이유는 하나만 남았다 — **색인을 아직 한 번도 올리지 않았다.**
>
> 끝난 것
>
> - 라벨 → ClickHouse `section_labels` (`app/labels_store.py`, DDL 은
>   `ai_server/sql/section_labels.sql`, 표는 사람이 만든다 — decision/0011)
> - `id_map` 이 준비 판정에서 빠졌다
> - `app/legacy/` 승격 — `sys.path` 조작과 맨 이름 import 가 사라졌다
> - 색인 자르기 도구와 업로드 경로
>
> 남은 것
>
> 1. 색인을 한 번 올리고 `replicas` 를 1 로
> 2. `build_dna` 의 `OV`(다른 사용자 평균)를 미리 계산 — 지금은 요청마다 계산한다.
>    다만 p50 2.22초의 주된 원인은 행동 로그 전수 스캔이다(`S15P21A204-217`)
> 3. 죽은 코드 정리. **`_pref_sections()` 가 안 불린다는 앞선 판단은 확인이 필요하다**
>    — `legacy` 건과 같은 실수를 반복하지 않으려면 지연 import 까지 보고 판단한다

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
| 총 크기 | **517 MB** (`stage1_weights.npz` 포함, `stage1.pt` 제외) |

| 파일 | 크기 | 없으면 |
|---|---:|---|
| `moments_emb.npy` | 384 MB | **기동 실패** — 카탈로그를 못 만든다 |
| `features_v1.npz` | 93 MB | 학습 점수가 빠진다 (PCA 기저와 285차가 여기 있다) |
| `stage1_section.npy` | 32 MB | 학습 점수가 빠진다 |
| `clap_moments.npz` | 4.2 MB | CLAP 재정렬만 빠진다. 자연어 해석기가 미설정이면 지금은 영향 없다 |
| `moments.parquet` | 3.2 MB | **기동 실패** |
| **`content.parquet`** | **1.8 MB** | **기동 실패.** `recommender.py:155` 가 `exists()` 검사 **없이** 읽는다. `trk_*` 다섯 열의 출처이기도 하다 |
| **`flow_meta.parquet`** | **65 KB** | 오류는 안 난다. 곡이 추천 풀에는 들어가고 **조건 검색(장르·무드·보컬·언어)에서만 조용히 빠진다** |
| `stage1_weights.npz` | **349 KB** | **묶음에 반드시 넣는다.** 없으면 `stage1.pt` 를 찾는데 이미지에 torch 가 없어 학습 점수가 통째로 빠진다. 오류는 안 나고 규칙 점수로 조용히 내려간다 |
| `stage1.pt` | 349 KB | torch 판. 개발 환경용이고 **배포 묶음에는 필요 없다** |
| `labels_service.parquet` | 156 KB | 모든 사용자가 콜드스타트가 된다 (오류는 안 난다) |
| `id_map.parquet` | 작음 | **`/internal/ready` 가 503** — 숫자 ID 를 못 잇는다 |
| **`moment_zstats.npz`** | **12 KB** | 서버는 안 쓴다. **분석 배치가 멈춘다** — 새로 계산하지 않는다(신곡만 다른 공간에 놓인다) |
| `feature_stats_v1.npz` | **5 KB** | 서버는 안 쓴다. **분석 배치가 멈춘다** — 같은 이유다 |
| **`logistic_flow_stage1.json`** | **1.2 KB** | 점수 결합기가 없어 **규칙 점수로 내려앉는다.** 오류는 안 난다. 이름은 `MUPID_COMBINER` 로 바꿀 수 있고, 없으면 `logistic_combiner.json` 을 찾는다 |
| `features_info.json` | 877 B | 특징 판·`features_v1.npz` sha256 을 담는다. 분석 배치가 이걸로 판이 어긋났는지 가린다 |

> 2026-09-16 에 **실제 로딩 경로와 대조**해 채웠다. 앞 판에는 `content.parquet`·
> `flow_meta.parquet`·`moment_zstats.npz`·`logistic_flow_stage1.json` 넷이 빠져 있었다.
> 그중 `content.parquet` 은 **없으면 기동 자체가 안 되는데도** 표에 없었다.

> **네 파일이 행 번호로 서로를 가리킨다**(`moments`·`emb`·`features`·`stage1`). 버전이
> 섞이면 엉뚱한 곡을 추천하고 **그 상태로도 정상 기동한다.** 2026-09-09~10 에 두 번
> 겪었다. 그래서 기동 시 무결성 검사가 붙어 있고, 어긋나면 `/internal/ready` 가 실패한다.
> **묶음 단위로 교체하고, 한 파일만 바꾸지 않는다.**

`feature_stats_v1.npz` 는 `features_v1.npz` 에서 뽑은 z-score 통계다. 신곡을 섞어
다시 적합하면 **기존 구간의 특징이 전부 바뀌고** 체크포인트의 `in_dim 285` 와 어긋나
학습 점수가 조용히 빠진다. 그래서 통계를 굳혀 두고 신곡은 읽어 쓰기만 한다 —
만드는 쪽은 `python -m ai_server.tools.freeze_feature_stats` 다.
**색인을 다시 만들면 이것도 다시 만든다** (sha256 이 어긋나면 배치가 멈춘다).

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
| `MUPID_POOL_EXTRA` | (비움) | 추가 풀 (쉼표 구분). `jamendo` 는 서비스 곡이 아니라 넣지 않는다 |
| `MUPID_COMBINER` | | 점수 결합기 파일명 |
| `MUPID_ID_MAP` | | 매핑표 경로 재정의 |
| `MUPID_CLICKHOUSE_URL` / `_USER` | | 배치용. **읽기 전용 계정** |

서버 파드와 배치(`ai-labeler`) 어느 쪽도 `MUPID_API_*` 를 쓰지 않는다 — 추천 서버는
Spring 을 부르지 않고(연동 계약 2026-09-23 절), 라벨러는 곡 번호·구간 경계를 ClickHouse
`v_track_sections_current` 에서 읽는다. `MUPID_API_*` 는 사람이 돌리는 적재 도구
(`tools/register_sections` 등)에만 쓴다.

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

### 구간 분석 배치는 여기 없다 — 지금은 GitLab 스케줄이 돌린다

신곡을 색인에 얹는 배치(`python -m music_preprocessing.analysis.batch`)는 **위 표에
없다.** 이미지도 다르고(GPU·파이썬 3.10 이 섞인다) 도는 곳도 다르다.

**"언제" 를 정하는 것은 지금 GitLab 파이프라인 스케줄이다.** K8s CronJob 이 아니다 —
JupyterHub 배치 계정은 cron 스풀에 쓸 권한이 없어(`crontab -` 가 실제로
`mkstemp: Permission denied` 로 실패했다) cron 을 쓸 수 없고, 그래서 `run_batch.sh`
가 "무엇을" 만 정하고 "언제" 는 파이프라인 스케줄이 정하는 구조다. 구간 분석도 같은
자리에 붙는다.

K8s 로 옮길 때의 규격은 `ai_server/docs/ANALYSIS-BATCH.md` §8 에 적어 두었다
(`concurrencyPolicy: Forbid` · `backoffLimit: 0` · 교체는 `MUPID_AI_ARTIFACT_DIR`
전환 + rollout restart). **지금 상태에서 그대로 옮겨 붙일 수 있는 매니페스트는 아니다** —
GPU 노드가 없고, ① 분할이 파이썬 3.10 환경을 요구한다.

| | 지금 | K8s 로 갈 때 |
|---|---|---|
| 언제 | GitLab 파이프라인 스케줄 | CronJob `schedule` |
| 겹침 방지 | 파일 잠금(`analysis_batch.lock`) + 스케줄 | + `concurrencyPolicy: Forbid` |
| ①② (GPU) | GPU 박스 `mupid-ai`(3.10) | GPU 노드가 생기면 같은 Job 으로 |
| ③④⑤⑥ | 색인을 마운트한 3.14 프로세스 | 같은 Job 의 다음 단계 |
| 교체 | 디렉터리 rename | `MUPID_AI_ARTIFACT_DIR` 전환 + rollout restart |

공유 CI·K8s 구현 파일은 이 문서에서 건드리지 않았다. 규격만 적어 둔 것이다.

---

## 8. 아직 못 정한 것 — 인프라 판단 필요

| # | 항목 |
|---|---|
| 1 | **산출물 전달 방법.** PVC / 오브젝트 스토리지 → initContainer 내려받기 / 이미지 동봉 중 무엇으로 갈지. 517 MB 이고 곡이 늘면 커진다 |
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
