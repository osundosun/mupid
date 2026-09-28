# AI 추천 서버 (`ai_server/`) 포팅 매뉴얼

## 1. 개요

- **역할**: 추천 전용 FastAPI 서비스. Spring API 만 부르는 내부 서버이고, 하는 일은 오늘의 모멘트(`daily`, 4개 고정)·이어 듣기(`next`)·조건 검색(`search`)·유사 구간(`similar`) 추천이다. 자세한 설명은 [ai_server/README.md](../../ai_server/README.md)에 있다.
- **nginx 로 열지 않는다.** `/internal` 에는 인증이 없어서 클러스터 안의 Spring(`MUPID_RECOMMENDER_BASE_URL=http://ai:8100`)만 호출한다. 근거: [infra_k8s/base/ai.yaml](../../infra_k8s/base/ai.yaml), [infra_k8s/base/config.yaml](../../infra_k8s/base/config.yaml)
- **숫자 ID 를 다루지 않는다.** 곡·구간은 문자열 ID(`T000901#2`)로만 주고받고, MySQL 숫자 ID 로 옮기는 일은 Spring 이 `music_section_analysis.external_id` 로 한다.
- **이 서버는 Spring 을 호출하지 않는다.** 필요한 데이터는 세 곳에서 읽는다.
  - 행동 데이터·선호 라벨·곡/구간 값: ClickHouse
  - 순간 벡터: 로컬 색인 또는 Qdrant
  - 랭킹 모델: 색인 판 또는 이미지에 구운 모델 묶음
- **GPU 와 torch 가 필요 없다.** 사용자 벡터 계산은 NumPy 로 옮겼다([app/stage1_np.py](../../ai_server/app/stage1_np.py)).
- **LLM 은 이 서버가 부르지 않는다.** 대화 모델(GMS·OpenAI 호환 API)은 Spring 쪽 설정(`MUPID_AI_BASE_URL` 등)이다.

| 엔드포인트 | 용도 |
|---|---|
| `GET /health` | 프로세스 생존 확인. liveness·startup probe 와 Docker HEALTHCHECK 가 쓴다 |
| `GET /internal/ready` | 트래픽을 받을 수 있는지 확인. **readinessProbe** 가 쓴다. 조건은 모델 로드, 색인 4종 행 수 일치, `emb_row` 연속 |
| `GET /internal/metrics` | 운영 지표 (예: `cold_start_rate`) |
| `POST /internal/recommendations/{daily,next,search}`, `POST /internal/similar` | 추천 API |

## 2. 기술 스택·버전

| 구분 | 이름 | 버전 | 근거 |
|---|---|---|---|
| 언어 | Python | **3.12** (`python:3.12-slim`) | [ai_server/Dockerfile](../../ai_server/Dockerfile) |
| 환경 관리 | uv | 버전 고정 없음. 로컬에 3.12 가 없으면 uv 가 받아 온다 | [ai_server/README.md](../../ai_server/README.md) |
| 웹 | fastapi / uvicorn / pydantic | 0.141.1 / 0.52.4 / 2.13.5 | [requirements.txt](../../ai_server/requirements.txt) |
| 수치 | numpy / pandas / pyarrow / scipy | 2.5.2 / 3.0.5 / 25.0.1 / 1.18.1 | 〃 |
| (잔존) | pinecone | 5.4.2 | 〃. 코드는 이미 제거됐다(S15P21A204-459). 줄을 빼면 CI 이미지를 다시 구워야 한다(§6.3) |
| 벡터 DB | Qdrant | v1.19.1 (digest 로 고정) | [infra_k8s/base/qdrant.yaml](../../infra_k8s/base/qdrant.yaml), [api/compose.yml](../../api/compose.yml) |
| 행동 저장소 | ClickHouse | 로컬 기본 `25.3` (`MUPID_CLICKHOUSE_VERSION`) | [api/compose.yml](../../api/compose.yml) |
| 테스트 | pytest, httpx | 버전 고정 없음. CI 이미지에서 설치한다 | [infra/runner/images/ai.Dockerfile](../../infra/runner/images/ai.Dockerfile) |
| (선택) | torch | 2.6.0, CPU 인덱스 | 이미지에는 넣지 않는다. `export_stage1_npz`, torch 동치 테스트에만 쓴다 |
| IDE | | | > TODO: 팀 확인 |

`pyproject.toml` 은 두지 않는다. 의존성 기준은 `requirements.txt` 하나다.

## 3. 모델·색인 준비

모델과 색인은 **Git 에 없다**([ai_server/.gitignore](../../ai_server/.gitignore)가 `artifacts/`·`models/stage1/*` 를 제외한다). 파일은 두 갈래로 준비한다.

### 3.1 색인 판 (`MUPID_AI_ARTIFACT_DIR`)

- 만드는 곳은 JupyterHub GPU 배치이고, 전체 약 537MB 다. 저장소에도 CI 러너에도 없다. 로컬 개발은 **별도로 전달받아** 아래 구조로 둔다.
  > TODO: 팀 확인 (전달 경로·담당자)
- 파일 구조는 [ai_server/README.md](../../ai_server/README.md)의 "모델·색인" 절을 따른다.
  - 위치: `<ARTIFACT_DIR>/data/proc/mupid/`
  - 색인 4종: `moments.parquet`, `moments_emb.npy`, `features_v1.npz`, `stage1_section.npy`
  - 모델·결합기: `stage1_weights.npz`, `logistic_flow_stage1.json`
  - 그 밖의 파일도 같은 절에 나온다.
- **색인 4종은 같은 행 번호로 서로를 가리킨다.** 하나만 바꾸면 서버는 정상처럼 뜨는데 엉뚱한 곡을 추천한다. 반드시 묶음으로 교체한다.
- `stage1_weights.npz` 가 없으면 학습 점수가 빠지고 규칙 점수만으로 돈다. 오류는 나지 않는다. 확인하려면 `/health` 의 `model_backend` 가 `numpy` 인지 본다. `stage1.pt` 에서 만드는 방법(torch 필요):
  `MUPID_AI_ARTIFACT_DIR=<경로> python -m ai_server.tools.export_stage1_npz`
- 운영에는 추천 풀에 해당하는 행만 잘라 올린다(약 80MB).
  1. `python -m ai_server.tools.slice_index --out <디렉터리>` 로 자른다.
  2. [infra_k8s/ci/deploy-index.sh](../../infra_k8s/ci/deploy-index.sh)로 **사람이** 올린다. CI job 이 아니다.

### 3.2 모델 묶음 ([models.lock](../../models.lock))

- `models.lock` 이 운영 모델의 기준 파일이다. 이 파일을 바꾸는 MR 이 병합되면 모델이 교체된다. 되돌릴 때는 이 파일을 되돌리는 커밋을 올린다.
- CI 흐름([infra/ci/model.yml](../../infra/ci/model.yml), [infra/ci/model_publish.py](../../infra/ci/model_publish.py)):

  | 시점 | job | 하는 일 |
  |---|---|---|
  | MR | `model-verify` | JupyterHub `~/mupid_models/stage1/v{N}/` 에서 받아 sha256 을 대조한다 |
  | master | `model-publish` | Object Storage(SeaweedFS, Spring 경유)에 올린다 |
  | master | `k8s-deploy-ai` | 서빙 파일(`user_encoder.npz`·`combiner.json`·`onboarding_seeds.npz`)을 이미지의 `ai_server/models/stage1/` 에 구운 뒤 채택한다 |

- **현재 `stage1: null`, 즉 채택된 모델이 없다.** 그래서 이미지의 모델 자리는 비어 있고, 서버는 색인 판의 가중치로 돈다. 모델 자리를 바꾸려면 `MUPID_AI_MODEL_DIR` 을 쓴다([app/model_bundle.py](../../ai_server/app/model_bundle.py)).

## 4. 로컬 실행

명령은 **저장소 루트**에서 실행한다.

```bash
cd ai_server
uv venv --python 3.12
uv pip install -r requirements.txt
cp .env.example .env            # 값 채우기 (아래 환경 변수)
cd ..
ai_server/.venv/Scripts/python -m ai_server.tools.serve          # Linux/macOS: ai_server/.venv/bin/python
ai_server/.venv/Scripts/python -m ai_server.tools.serve --check  # 띄우지 않고 준비 상태만 확인
```

- `tools.serve` 는 `.env` 를 읽고, 무엇이 빠졌는지 출력한 뒤 기동한다. 기본 주소는 `127.0.0.1:8100` 이다. **색인이 온전하지 않으면 띄우지 않는다.**
- 옵션은 `--artifacts`, `--port`, `--host`, `--env-file`, `--reload` 가 있다. 셸 환경 변수가 `.env` 보다 우선한다.
- 컨테이너와 같은 방식으로 띄우려면 `uvicorn ai_server.app.main:app --host 0.0.0.0 --port 8100 --workers 1` 을 쓴다. 이때 환경 변수는 직접 넣어야 한다. 카탈로그를 프로세스마다 메모리에 통째로 올리므로 **워커는 1개**로 둔다.
- 로컬 ClickHouse(`localhost:18123`)와 Qdrant(`127.0.0.1:6333`)는 [api/compose.yml](../../api/compose.yml)의 개발 스택으로 띄운다. Qdrant 컬렉션은 `python -m ai_server.tools.qdrant_collection --url http://127.0.0.1:6333` 로 만들고, 쓰기 키는 `MUPID_QDRANT_API_KEY` 다.
- 이미지로 띄우는 방법:
  ```bash
  docker build -f ai_server/Dockerfile -t mupid-ai:<tag> .     # 빌드 컨텍스트는 저장소 루트
  docker run -p 8100:8100 -v <artifacts>:/artifacts:ro mupid-ai:<tag>
  ```

## 5. 환경 변수

실제 값은 `ai_server/.env`(Git 제외) 또는 배포 Secret 에만 둔다. 예시 파일은 [ai_server/.env.example](../../ai_server/.env.example)이다.

| 변수 | 기본값 | 운영 값·출처 | 설명 |
|---|---|---|---|
| `MUPID_AI_ARTIFACT_DIR` | `ai_server/artifacts`, 이미지는 `/artifacts` | `/artifacts/current` (hostPath) | 색인 판 루트. **필수** |
| `MUPID_AI_STATE_DIR` | `ARTIFACT_DIR` 과 같음 | `/state` | 배치가 쓰는 자리(워터마크·라벨·선호 구간). 서빙과 배치가 같은 곳을 봐야 한다 |
| `MUPID_AI_PORT` | `8100` | `8100` | 포트 |
| `MUPID_AI_MODEL_DIR` | `ai_server/models/stage1` | (미설정) | 모델 묶음 위치 |
| `MUPID_CLICKHOUSE_URL` / `_USER` / `_PASSWORD` | 없음 | `http://clickhouse:8123` / `mupid` / Secret `mupid-secrets` | 행동 저장소. 비우면 모든 추천이 콜드스타트가 된다 |
| `MUPID_CLICKHOUSE_SECURE` | 없음 | | jdbc 형식 URL 을 TLS 로 붙일 때 `1` |
| `MUPID_CLICKHOUSE_SERVING_URL` / `_USER` / `_PASSWORD` | 없으면 위 계정을 쓴다 | `http://clickhouse-0.clickhouse:8123` / `mupid_serving` / `mupid-secrets` | 기준 풀 스냅샷용 읽기 전용 계정 |
| `MUPID_POOL_SNAPSHOT_TTL_SEC` | `60` | | 스냅샷 갱신 주기(초) |
| `MUPID_POOL` / `MUPID_POOL_EXTRA` | `flow` / 빈 값 | `flow` / `''` | 후보 풀. **`jamendo` 는 넣지 않는다**(재생 불가) |
| `MUPID_COMBINER` | `logistic_flow_stage1.json` | | 점수 결합기. 바꾸면 순위가 바뀐다 |
| `MUPID_PREFILTER_MAX` | `10000` | | 벡터 검색 사전 필터 상한 |
| `MUPID_AI_INTERNAL_TOKEN` | 없음(검사 안 함) | Secret `mupid-secrets` (optional) | `/internal` 공유 비밀. Spring 의 `MUPID_RECOMMENDER_INTERNAL_TOKEN` 과 **함께** 켠다. 한쪽만 켜면 전부 401 이 난다 |
| `MUPID_VECTOR_BACKEND` | `local` | **`qdrant`** (`MUPID_AI_SERVER_ENV`) | `local`(판의 `moments_emb.npy`) 또는 `qdrant`. `pinecone` 을 넣으면 기동 오류가 난다. 운영 파드에서 `qdrant` 임을 확인했다(2026-09-28). [vector_store.py](../../ai_server/app/vector_store.py) docstring 의 "운영은 local" 은 옛 설명이다 |
| `MUPID_QDRANT_URL` | `http://qdrant:6333` | | Qdrant 주소 (ClusterIP) |
| `MUPID_QDRANT_READ_ONLY_API_KEY` | 없음 | `MUPID_AI_SERVER_ENV` → Secret `mupid-ai-secrets` | **읽기 전용** 키. 서빙은 Qdrant 에 쓰지 않는다 |
| `MUPID_QDRANT_COLLECTION` / `MUPID_QDRANT_TIMEOUT` | `moments__mert-v1-95m` / `5` | | [app/qdrant_collection.json](../../ai_server/app/qdrant_collection.json)이 기준 정의다 |
| `MUPID_ID_MAP` | `<ARTIFACT_DIR>/data/proc/mupid/id_map.parquet` | | 시험용 매핑표. 서빙과 배치는 읽지 않는다 |
| `MUPID_API_BASE_URL` / `MUPID_API_REFRESH_TOKEN` | | **서버 파드에 넣지 않는다** | MySQL 에 쓰는 적재 도구(`tools/register_sections`)만 쓴다 |
| `OMP_NUM_THREADS` 외 2개 | | 서빙 `3`, 배치 `2` | CPU limit 에 맞춘다 |

- `MUPID_PINECONE_*` 는 읽지 않는다. 남아 있으면 기동 로그와 `/health` 에 이름만 표시된다.
- 설정한 값이 적용됐는지는 `/health` 의 `paths`·`artifacts`·`catalog.by_source`·`pool_snapshot` 으로 확인한다. 응답에 값 자체는 싣지 않는다.

## 6. 배포 특이사항

### 6.1 이미지 빌드

CI job `k8s-deploy-ai` 가 [infra_k8s/ci/k8s-release.yml](../../infra_k8s/ci/k8s-release.yml)에서 [infra_k8s/ci/package-ai.sh](../../infra_k8s/ci/package-ai.sh)를 실행해 이미지를 굽는다.

- 사용하는 Dockerfile 은 **`ai_server/Dockerfile`** 이다. [infra_k8s/image/ai.Dockerfile](../../infra_k8s/image/ai.Dockerfile)은 torch 를 넣던 초기 판이고 CI 는 쓰지 않는다.
- rootless buildkit 으로 굽는다. 러너는 arm64 이고 노드는 x86_64 라 **`platform=linux/amd64` 로 고정**한다.
- 이미지는 gzip 아카이브로 1 GiB 를 넘으면 안 된다.
- 레지스트리는 두지 않는다. 아카이브를 SSH 로 노드에 보내 들여온다. 그래서 파드는 `nodeSelector: mupid.io/role=ai`(node-b)에 묶여 있다.
- 태그는 `mupid-ai:<CI_COMMIT_SHA>` 다. 매니페스트의 `mupid-ai:placeholder` 는 `mupid-apply` 가 치환한다. 매니페스트를 직접 apply 하면 ImagePullBackOff 가 난다.

### 6.2 색인·상태 볼륨

- 색인은 hostPath `/srv/mupid-k8s/ai-artifacts` 를 읽기 전용으로 마운트하고, 컨테이너는 `/artifacts/current` 링크를 본다.
- 색인은 `deploy-index.sh` 로 올린다. 이때 `MUPID_AI_NODE_SSH` 로 **node-b 를 직접 지정**한다. 배포 엔드포인트(`j15a204.p.ssafy.io`)는 node-a 라서 거기로 보내면 파드가 빈 `/artifacts` 를 보게 된다.
- 상태는 별도 hostPath `/srv/mupid-k8s/ai-state` 에 둔다. 서빙은 읽기 전용, 배치는 쓰기로 마운트한다.

### 6.3 CI 검증 이미지

`ai-server-verify` job([infra/ci/ai-server.yml](../../infra/ci/ai-server.yml))은 러너 호스트에 미리 구워 둔 `mupid-ci-ai:1` 이미지에서 돈다(`pull_policy: if-not-present`).

- job 은 이미지 안의 `/opt/mupid/requirements.txt` 와 저장소의 `requirements.txt` 를 **`cmp` 로 바이트 단위 대조**한다. CR 만 제거하고 비교하므로 빈 줄 하나만 달라도 실패한다.
- requirements.txt 를 고쳤으면 러너 호스트에서 `sh infra/runner/build-images.sh` 를 실행해 다시 굽는다([infra/runner/images/ai.Dockerfile](../../infra/runner/images/ai.Dockerfile)).
- 실제로 실행된 테스트가 90건 미만이면 실패로 처리한다. 모두 건너뛰고 통과하는 경우를 막기 위해서다.
- MR 에 **`AI` 라벨**이 있어야 이 job 이 돈다.

### 6.4 환경 변수 배포 (`MUPID_AI_SERVER_ENV`)

- GitLab 의 **File 형식 보호 변수**다. 흐름: [infra_k8s/ci/deploy-ai-env.sh](../../infra_k8s/ci/deploy-ai-env.sh) → 노드 수신기 `receive-ai-env.py` → Secret `mupid-ai-secrets` → **ai 파드만** 재시작.
- Spring 용 `MUPID_API_ENV` 와 나눠 둔다. 키 목록은 [infra/ci/VARIABLES.md](../../infra/ci/VARIABLES.md)에 있다.
- ClickHouse 비밀번호와 내부 토큰은 이 변수가 아니라 `mupid-secrets` 에서 온다.

### 6.5 Qdrant

- node-b 에 **자체 호스팅**한다. StatefulSet 이고 ClusterIP 로만 열려 있다. [qdrant-networkpolicy.yaml](../../infra_k8s/base/qdrant-networkpolicy.yaml)이 `ai`·`ai-labeler` 파드의 연결만 허용한다.
- 키는 둘이다.
  - 쓰기 키 `MUPID_QDRANT_API_KEY`: `MUPID_API_ENV` 에 넣는다.
  - 읽기 키 `MUPID_QDRANT_READ_ONLY_API_KEY`: `MUPID_AI_SERVER_ENV` 에 넣는다.
- 키를 바꾼 뒤에는 Qdrant 도 재시작해야 한다([infra_k8s/OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) 7-1).
- 벡터를 쓰는 길은 Spring 벡터 등록 API 하나뿐이다. 자세한 내용은 [ai_server/docs/QDRANT.md](../../ai_server/docs/QDRANT.md)에 있다.

### 6.6 ai-labeler CronJob

[infra_k8s/base/ai-labeler.yaml](../../infra_k8s/base/ai-labeler.yaml)에 정의되어 있다.

- 매일 **04:00 KST** 에 돈다. MySQL 백업(03:00)을 피한 시간이다. `concurrencyPolicy: Forbid`, 제한 시간 2시간.
- 추천 서버와 **같은 이미지**로 두 명령을 차례로 실행한다: `python -m ai_server.tools.pull_signals` → `python -m ai_server.tools.run_labeler --json`
- initContainer 가 root 로 `/state` 소유자를 uid 100 으로 바꾼다. hostPath 는 root 소유로 만들어지기 때문이다.
- 이 배치가 멈춰도 오류는 나지 않는다. 대신 **모든 사용자가 콜드스타트 추천**을 받는다. `/internal/metrics` 의 `cold_start_rate` 로 확인한다.

### 6.7 리소스·프로브

| 항목 | 값 |
|---|---|
| replicas | 1 |
| requests | 500m / 512Mi |
| limits | 3 CPU / 2Gi |
| startup·liveness probe | `/health` |
| readiness probe | `/internal/ready` |

- ClickHouse 가 죽어도 준비 판정은 막지 않는다. 개인화가 빠진 추천을 계속 내보낸다.
- DDL 은 앱이 적용하지 않는다. [ai_server/sql/section_labels.sql](../../ai_server/sql/section_labels.sql)을 사람이 한 번 실행한다.

## 7. 설정 파일 목록

| 파일 | 내용 |
|---|---|
| [ai_server/.env.example](../../ai_server/.env.example) | 환경 변수 키 목록. 실제 값은 `ai_server/.env` (Git 제외) |
| [ai_server/requirements.txt](../../ai_server/requirements.txt) | 의존성 버전 고정. CI 이미지와 바이트 단위로 대조한다 |
| [ai_server/Dockerfile](../../ai_server/Dockerfile) | 운영 이미지 |
| [models.lock](../../models.lock) | 채택할 모델 버전과 sha256 |
| [ai_server/app/qdrant_collection.json](../../ai_server/app/qdrant_collection.json) | Qdrant 컬렉션 기준 정의 |
| [ai_server/sql/section_labels.sql](../../ai_server/sql/section_labels.sql) | ClickHouse `section_labels` DDL |
| [infra_k8s/base/ai.yaml](../../infra_k8s/base/ai.yaml) / [ai-labeler.yaml](../../infra_k8s/base/ai-labeler.yaml) / [qdrant.yaml](../../infra_k8s/base/qdrant.yaml) | k8s 매니페스트. Secret 은 `mupid-secrets`·`mupid-ai-secrets` 를 참조한다 |
| [infra_k8s/ci/k8s-release.yml](../../infra_k8s/ci/k8s-release.yml), [infra/ci/ai-server.yml](../../infra/ci/ai-server.yml), [infra/ci/model.yml](../../infra/ci/model.yml) | CI job 정의 |
| [infra/ci/VARIABLES.md](../../infra/ci/VARIABLES.md) | GitLab CI 변수 설명 (`MUPID_AI_SERVER_ENV`, `MUPID_SYSTEM_CI_REFRESH_TOKEN`, `MUPID_DATA_ENV`, `MUPID_DEPLOY_K8S_SSH_KEY` 등) |

## 8. 테스트

```bash
ai_server/.venv/Scripts/python -m pip install pytest httpx   # 또는 uv pip install pytest httpx
export MUPID_AI_ARTIFACT_DIR=<경로>                          # smoke 테스트에만 필요
ai_server/.venv/Scripts/python -m pytest ai_server/tests -q
```

- 대부분의 테스트는 산출물 없이 돈다(`test_contract_offline.py`, `test_pipeline_synthetic.py` 등). Qdrant 는 [tests/fake_qdrant.py](../../ai_server/tests/fake_qdrant.py)로 대체한다.
- `test_smoke.py` 는 실제 색인이 있어야 돌고, 없으면 건너뛴다.
- `test_contract_offline.py` 의 `.env` 무시 검사는 `git` 을 호출한다. git 이 없는 환경에서는 실패한다.
- torch 동치 테스트는 torch 가 없으면 건너뛴다.
