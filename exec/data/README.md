# 데이터 파이프라인 포팅 매뉴얼

음원 수집부터 전처리·임베딩, 그리고 각 저장소(MySQL · SeaweedFS · Qdrant · ClickHouse)에 적재하기까지의 절차를 정리한다.
비밀 값은 적지 않았다. 키 이름과 그 키가 들어 있는 파일 경로만 적는다.

## 1. 개요 — 데이터 흐름

```
[flow_music_automation]  Google Flow Music (브라우저 자동화, 로컬 PC)
   → downloads/{audio,covers,metadata}/T000001.*   (+ faster-whisper 가사 싱크)
   → GPU 서버 ~/flow_music_data 로 rsync (sync_*.sh)
        │
[music_preprocessing]  JupyterHub GPU 서버, uv (Python ≥3.12)
   ├ catalog / covers / lyrics / classify ─► Spring API ─► MySQL(곡·아티스트·앨범·가사·장르/무드)
   │                                                    └► SeaweedFS(커버·아티스트·앨범 이미지)
   ├ batch (ffmpeg → Opus 청크 + RTP, 파형) ─► Spring API ─► SeaweedFS(청크, nginx 직행 업로드) + MySQL(streams·chunks)
   ├ analysis (분할 → MERT 임베딩 → 특징 → 색인) ─► PUT /music/{id}/section ─► MySQL + ClickHouse(track_sections)
   └ vectors-send ─► PUT /api/v1/music/{id}/vectors ─► Spring ─► Qdrant (moments__mert-v1-95m)
        │
[앱 사용] 행동 이벤트 → Kafka(mupid.listen.events) → ClickHouse(listen_events → 파생 MV)
```

- 곡 식별자는 파일 이름의 `track_id`(`T000001`)다. metadata·covers·audio·chunking 네 디렉터리가 이 이름을 공유한다 → [music_preprocessing/README.md](../../music_preprocessing/README.md) 「입력 구성」.
- **저장소에 직접 쓰는 것은 Spring API 하나다.** 배치는 MySQL · SeaweedFS · Qdrant 에 직접 붙지 않고 `ROLE_SYSTEM` 토큰으로 API 를 호출한다. Qdrant 쓰기도 Spring 만 한다([infra/VECTOR-STORE-DESIGN.md](../../infra/VECTOR-STORE-DESIGN.md) §6.6).
- 카탈로그 규모는 곡 1,060 · 아티스트 87 · 앨범 204 · 가사 보유 곡 505(README 기준; 가사 싱크 생성은 516곡)다 → [music_preprocessing/README.md](../../music_preprocessing/README.md), [flow_music_automation/scripts/lyrics_sync_gpu_batch.py](../../flow_music_automation/scripts/lyrics_sync_gpu_batch.py).

## 2. 도구 · 버전

| 구분 | 도구 | 버전 | 근거 |
|---|---|---|---|
| 전처리 | Python | ≥3.12 (서버 기본 3.12, 로컬 `.python-version` 3.14) | [pyproject.toml](../../music_preprocessing/pyproject.toml), [.python-version](../../music_preprocessing/.python-version) |
| 전처리 | uv / uv_build | uv_build ≥0.11.28,<0.12 · 잠금 파일 `uv.lock` | [pyproject.toml](../../music_preprocessing/pyproject.toml) |
| 전처리 | typer / rich | ≥0.15 / ≥13.9 | 〃 |
| 전처리(분석 extra) | numpy · scipy · pandas · pyarrow · librosa | ≥1.26 · ≥1.11 · ≥2.2 · ≥16 · ≥0.10 | 〃 (`uv sync --extra analysis`) |
| 전처리 | FFmpeg (libopus) | 버전 미고정. 청킹에만 필요 | [music_preprocessing/README.md](../../music_preprocessing/README.md) 「요구사항」 |
| 분석(분할·임베딩) | conda 환경 `mupid-ai` (Python 3.10) — All-In-One(allin1)·MERT·demucs | 환경 고정, 추가 설치 금지 | [ai_server/docs/ANALYSIS-BATCH.md](../../ai_server/docs/ANALYSIS-BATCH.md) §7.1 |
| 수집 | playwright · librosa · soundfile | 1.62.0 · 1.0.0 · 0.14.0 | [flow_music_automation/requirements.txt](../../flow_music_automation/requirements.txt) |
| 수집 | faster-whisper (가사 싱크, medium 모델) | requirements.txt 에 없음 | [lyrics_sync_gpu_batch.py](../../flow_music_automation/scripts/lyrics_sync_gpu_batch.py) |
| 수집 | Chrome (원격 디버깅 9222 포트, CDP) | — | [flow_music_automation/README.md](../../flow_music_automation/README.md) |
| 저장소 | MySQL | 8.4 | [api/compose.yml](../../api/compose.yml), [erd.sql](../../api/knowledge/domain/erd.sql) |
| 저장소 | ClickHouse server / keeper | 25.3 (`MUPID_CLICKHOUSE_VERSION`) | [api/compose.yml](../../api/compose.yml), `infra_k8s/base/*.yaml` |
| 저장소 | Kafka | apache/kafka 3.9.1 (KRaft) | 〃 |
| 저장소 | SeaweedFS | `chrislusf/seaweedfs` (k8s 는 digest 고정) | 〃 |
| 저장소 | Qdrant | `qdrant/qdrant` (digest 고정) | 〃 |
| 서버 | JupyterHub GPU 서버 (V100) | SSH 없음, HTTP API(80/443)만 | [infra/ci/deploy-jupyter.py](../../infra/ci/deploy-jupyter.py) |

## 3. 파이프라인별 실행 절차

### 3-1. 음원 수집 — `flow_music_automation/` (로컬 PC)

1. Chrome 을 `--remote-debugging-port=9222 --user-data-dir=<프로필 경로>` 로 띄우고 Flow Music 에 **사람이 직접** 로그인한다. Playwright 가 띄운 브라우저는 Google 로그인에서 차단된다.
2. `prompts.jsonl` 큐를 순회하며 생성·다운로드한다. 모든 스크립트는 기본이 dry-run 이고 `--confirm-*` 옵션을 줘야 실제로 동작한다 → [docs/pipeline_overview.md](../../flow_music_automation/docs/pipeline_overview.md).
3. 결과는 `downloads/audio|covers|metadata/T{n}.*` 에 쌓인다. 메타데이터 스키마는 [docs/metadata_schema_v2.md](../../flow_music_automation/docs/metadata_schema_v2.md), 장르·무드 어휘는 [genre_mood_taxonomy.json](../../flow_music_automation/genre_mood_taxonomy.json) 에 있다.
4. 가사 싱크는 GPU 서버에서 [lyrics_sync_gpu_batch.py](../../flow_music_automation/scripts/lyrics_sync_gpu_batch.py) 로 돌린다(`--shard/--num-shards/--gpu-index`). 이어서 `apply_lyrics_sync_to_metadata.py` 가 결과를 metadata 의 `lyrics_sync` 에 반영한다.
5. GPU 서버로 동기화한다: `scripts/sync_to_gpu.sh`(전체 미러는 `sync_to_gpu_full_mirror.sh`), 검증은 `verify_*_on_gpu.sh`.

> 주의: 이 디렉터리는 스냅샷이다. README 가 말하는 `src/`(`flow_connect.py`, `run_queue.py` 등)와 실제 음원 데이터는 저장소에 없다 → [flow_music_automation/README.md](../../flow_music_automation/README.md) 「참고」.
> TODO: 팀 확인 — 수집 원본 저장소(`../flow-music-automation`)의 위치와 보관 방법.

### 3-2. 전처리 배치 — `music_preprocessing/` (JupyterHub GPU 서버)

**배포.** GitLab CI 가 [infra/ci/deploy-jupyter.py](../../infra/ci/deploy-jupyter.py) 로 JupyterHub HTTP API(로그인 → spawn → contents 업로드 → 커널에서 `uv sync`)를 거쳐 코드만 올린다. 잡은 [infra/ci/data.yml](../../infra/ci/data.yml) 의 `data-check`·`data-deploy`(master)·`data-batch`(파이프라인 스케줄)다. 서버 계정은 cron 을 쓸 수 없어서 실행 시각은 GitLab 스케줄이 정한다.

**배치 한 바퀴** — [run_batch.sh](../../music_preprocessing/run_batch.sh) (서버에서 직접 돌릴 때는 `cd ~/music_preprocessing && bash run_batch.sh`):

| 순서 | 명령 | 하는 일 |
|---|---|---|
| 1 | `catalog --yes --no-progress` | 아티스트 → 앨범 → 곡을 등록하고 이미지를 업로드한다 (`registered.json` 에 한 건씩 기록) |
| 2 | `covers --yes` | 바뀐 커버만 다시 올린다 |
| 3 | `lyrics --yes` | `PUT /api/v1/music/{id}/lyric` 로 곡 단위 가사를 통째로 교체한다 |
| 4 | `classify --yes` | 장르·무드를 연결한다 → [GENRE_MOOD.md](../../music_preprocessing/GENRE_MOOD.md) |
| 5 | `batch` | ffmpeg 로 Opus 청크와 RTP 를 만들고, 스트림·청크·파형을 등록한다(해시 기반 증분) |
| 6 | `analysis --yes --local --segmenter-python $MUPID_AI_PY` | 구간 분석(분할 → 임베딩 → 특징 → 색인 → 등록). 분할·임베딩은 conda `mupid-ai`(3.10)에서 돈다 |
| 7 | `daily-recommendation` | 분석이 성공한 날에만 호출한다 |

순서를 지켜야 한다. `catalog` 가 먼저 곡 번호를 만들어야 `lyrics`·`batch` 가 그 번호에 붙는다.

**처음 올릴 때(수동)** — [music_preprocessing/README.md](../../music_preprocessing/README.md) 「손으로 돌릴 때」:

```bash
uv sync                    # 분석까지 돌릴 거면: uv sync --extra analysis
cp config.example.json config.json   # 값을 채운다 (4절)
uv run music-preprocessing catalog -c config.json --dry-run
uv run music-preprocessing catalog -c config.json --limit 5
uv run music-preprocessing status  -c config.json
uv run music-preprocessing catalog -c config.json
uv run music-preprocessing lyrics  -c config.json
uv run music-preprocessing batch   -c config.json
```

**ROLE_SYSTEM 계정.** 관리자 토큰으로 `POST /api/v1/user/system` 을 한 번 호출하고, 응답에 담긴 `refresh_token` 을 `refresh_token.txt` 에 저장한다. 평문은 이 응답에서만 나온다. 배치가 이 파일을 회전시키므로 커밋하지 않는다(`.gitignore` 등록).

최초 관리자 계정은 Spring 이 기동할 때 없으면 `admin@ssafy` 로 만들고 무작위 비밀번호를 기동 로그에 한 번만 찍는다([AdminRunner.java](../../api/src/main/java/com/osundosun/mupid/api/admin/runner/AdminRunner.java)). 운영 덤프([db/](../db/README.md))를 넣으면 기존 관리자 계정이 그대로 들어온다.

### 3-3. 순간 벡터 → Qdrant

[infra_k8s/OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) §7-1, [music_preprocessing/README.md](../../music_preprocessing/README.md) 「순간 벡터 보내기」:

```bash
# (클러스터) 컬렉션 생성 — 멱등. 쓰기 키는 stdin 으로 넘긴다
kubectl -n mupid get secret mupid-secrets -o jsonpath='{.data.MUPID_QDRANT_API_KEY}' | base64 -d \
  | kubectl -n mupid exec -i deploy/ai -- python -m ai_server.tools.qdrant_collection --api-key-stdin

# (JupyterHub, ~/music_preprocessing)
music-preprocessing vectors-freeze-mu --config config.json            # μ 는 한 번만 잰다. 있으면 덮어쓰지 않는다
music-preprocessing vectors-send --config config.json --yes           # 전 곡
music-preprocessing vectors-send --config config.json --pending --yes # 미반영 곡만
```

- 컬렉션 정의의 정본은 [api/src/main/resources/qdrant/qdrant_collection.json](../../api/src/main/resources/qdrant/qdrant_collection.json) 이다(`ai_server/app/qdrant_collection.json` 사본과 같다). 컬렉션 `moments__mert-v1-95m` 에 벡터 `mert`(1536) · `clap`(512) · `feat_v1`(285) · `stage1_v1` 을 담는다.
- 전체 색인(`<artifact>/data/proc/mupid`, `MUPID_AI_ARTIFACT_DIR` 기본값 `$HOME/rec_mupid`)을 읽는다. 곡 번호는 `registered.json` 에서 가져오고, 번호가 없는 곡(Jamendo 등)은 건너뛴다.
- ai 서버는 `MUPID_VECTOR_BACKEND=qdrant` 를 넣어야 Qdrant 를 읽는다(벡터를 다 채운 뒤에 넣는다).
- 백업은 CronJob `qdrant-snapshot` 이 매일 05:30(KST)에 뜨고 3판을 남긴다. 디스크를 잃으면 `vectors-send` 로 다시 채운다.

## 4. 환경 변수 · 설정 파일

### 4-1. `music_preprocessing/config.json` (서버에만 있다, 커밋 금지)

템플릿은 [config.example.json](../../music_preprocessing/config.example.json), 필드 설명은 [config.py](../../music_preprocessing/src/music_preprocessing/config.py) 에 있다.

| 묶음 | 키 |
|---|---|
| 인코딩 | `bitrate` · `segment_time` · `frame_duration` · `compression_level` · `vbr` · `application` · `rtp_payload_type` · `rtp_clock_rate` · `max_workers` · `ffmpeg_threads` |
| 경로 | `input_dir` · `output_dir` · `processed_log` · `metadata_dir` · `covers_dir` · `log_dir` · `genre_taxonomy_file` |
| API | `api_base_url`(비우면 등록 생략) · `api_refresh_token_file` · `api_registry_log` · `api_timeout` · `api_max_retries` |
| 분석 | `analysis_enabled` · `analysis_artifact_dir` · `analysis_work_dir` · `analysis_internal_token_file` · `analysis_version` · `analysis_limit` · `analysis_device` · `analysis_segmenter_python` · `analysis_tag_file` · `analysis_keep_versions` |

### 4-2. 서버가 들고 있는 상태 파일 (배포가 건드리지 않는다)

| 파일 | 내용 | 주의 |
|---|---|---|
| `refresh_token.txt` | ROLE_SYSTEM 리프레시 토큰 | 시스템 권한 자체. 배치가 회전시킨다 |
| `registered.json` | track_id → artist/album/music/stream 번호, 필드 지문 | **지우면 곡·아티스트가 중복 생성된다** |
| `processed.json` | 음원 SHA-256, `waveform_sha256` | 재청킹 여부를 판단하는 기준 |
| `ai_internal_token.txt` | 분석 API 내부 토큰 | 값이 아니라 파일 경로를 설정한다 |
| `~/mupid_models/stage1/v1/mu.npy`, `mu.json` | 동결한 중심화 μ | 바뀌면 Qdrant 를 전부 다시 넣어야 한다 |

### 4-3. 배포 변수 — GitLab CI/CD `MUPID_DATA_ENV` (File · Protected)

템플릿은 [deploy.env.example](../../music_preprocessing/deploy.env.example), 설명은 [infra/ci/VARIABLES.md](../../infra/ci/VARIABLES.md) 에 있다. 키: `JUPYTER_BASE_URL` · `JUPYTER_USER` · `JUPYTER_PASSWORD` · `JUPYTER_DIR` · `API_BASE_URL` · `INPUT_DIR` · `METADATA_DIR` · `COVERS_DIR` · `GENRE_TAXONOMY_FILE` · `REFRESH_TOKEN` · `RESET_TOKEN`, 그리고 config 필드를 대문자로 쓴 이름(`SEGMENT_TIME` 등). 모르는 이름이 들어 있거나 같은 이름이 두 번 나오면 배포가 멈춘다.

### 4-4. 그 밖의 환경 변수

| 키 | 쓰는 곳 |
|---|---|
| `MUPID_AI_ARTIFACT_DIR`, `MUPID_AI_DATA_DIR` | 전처리 분석·벡터 (색인 루트) |
| `MUPID_AI_PY`, `UV` | [run_batch.sh](../../music_preprocessing/run_batch.sh) (3.10 인터프리터, uv 경로) |
| `GPU_SSH_KEY`, `GPU_HOST` | `flow_music_automation/scripts/sync_*.sh`, `verify_*.sh` |
| `FLOW_SESSION_URL` | `flow_music_automation/scripts` 일부 |
| `MUPID_CLICKHOUSE_URL` · `_USER` · `_PASSWORD` · `_HTTP_URL` · `_SERVING_PASSWORD` · `_VERSION` | api [application.yaml](../../api/src/main/resources/application.yaml), [api/clickhouse/README.md](../../api/clickhouse/README.md) |
| `CH_SHARD` · `CH_REPLICA` · `CH_INTERSERVER_HOST` · `CH_HOST_1/2` · `CH_KEEPER_HOST` · `CH_MAX_SERVER_MEMORY` | ClickHouse 노드 설정 (`from_env`) |
| `MUPID_QDRANT_URL` · `MUPID_QDRANT_API_KEY` · `MUPID_QDRANT_COLLECTION` · `MUPID_QDRANT_READ_ONLY_API_KEY` | api(쓰기) / ai(읽기) |
| `MUPID_S3_ACCESS_KEY` · `MUPID_S3_SECRET` · `MUPID_S3_BUCKET` | api → SeaweedFS |
| `MUPID_MYSQL_ROOT_PASSWORD` | [schema-dump.sh](../../infra_k8s/deploy/schema-dump.sh) 가 노드의 `/srv/mupid-k8s/.env` 에서 읽는다 |

### 4-5. 계정 · 권한을 정의하는 파일

- ClickHouse 사용자: [users/10-mupid-user.xml](../../api/clickhouse/users/10-mupid-user.xml)(앱 계정 `mupid`), [users/20-serving-user.xml](../../api/clickhouse/users/20-serving-user.xml)(읽기 전용 `mupid_serving`), [users/00-profile.xml](../../api/clickhouse/users/00-profile.xml)
- SeaweedFS S3 신원: `s3.json`(버킷 단위 권한 `Write:mupid` 등) → [OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) §5
- 배치 API 계정: `ROLE_SYSTEM` (3-2절)

## 5. 빈 DB 에 스키마 · 시드 적재

`ddl-auto: none` 이라 **앱은 테이블을 만들지 않는다**([decision/0011](../../api/knowledge/decision/0011-jpa-manages-schema.md)). 빈 DB 로 API 를 띄우면 첫 쿼리에서 실패한다.

### 5-1. MySQL — [OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) §6

```bash
kubectl -n mupid exec -i statefulset/mysql -- mysql -umupid_app -p"$PASSWORD" mupid < api/knowledge/domain/erd.sql
kubectl -n mupid exec -i statefulset/mysql -- mysql -umupid_app -p"$PASSWORD" mupid < api/knowledge/domain/seed-codes.sql
```

- [erd.sql](../../api/knowledge/domain/erd.sql) 은 테이블 33개의 **최종 모양**이다. 새 DB 는 이 파일 하나로 충분하다. `migrate-*.sql`·`apply-search-indexes.sql` 은 이미 운영 중인 DB 를 따라잡을 때만 쓴다.
- [seed-codes.sql](../../api/knowledge/domain/seed-codes.sql) 을 빠뜨리면 온보딩이 503 으로 막힌다. API 가 기동할 때 `BootstrapRunner` 가 빠진 `codes`·`mubti`(캐릭터 이미지 포함)를 채우고, 무드 코드 32~48 을 규격대로 맞춘다(동결: [decision/0025](../../api/knowledge/decision/0025-mood-codes-32-48-are-frozen.md)).
- 적용한 뒤 대조한다. 차이가 있으면 종료 코드 1 이다.

```bash
sudo infra_k8s/deploy/schema-dump.sh > live.tsv          # 노드에서
python3 infra_k8s/deploy/schema-check.py --schema live.tsv # 저장소에서 (erd.sql ↔ DB ↔ 엔티티)
# 노드에 kubectl 이 없으면: bash infra_k8s/deploy/schema-dump-local.sh prod.tsv
```

### 5-2. SeaweedFS 버킷

빈 스토리지에는 버킷이 없고 앱도 만들지 않는다. 버킷이 없으면 404 가 아니라 403 이 난다.

```bash
echo 's3.bucket.create -name mupid' | kubectl -n mupid exec -i statefulset/storage -- weed shell -master=storage-master:9333
```

버킷 이름은 `MUPID_S3_BUCKET`, `s3.json` 의 이름과 같아야 한다.

### 5-3. Kafka 토픽 → ClickHouse DDL (순서 고정)

```bash
kubectl -n mupid exec statefulset/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic mupid.listen.events --partitions 6 --replication-factor 1
kubectl -n mupid exec -i clickhouse-0 -- clickhouse-client --user mupid --password "$CH_PASSWORD" --multiquery \
  < api/clickhouse/init/01-schema.sql
```

- [01-schema.sql](../../api/clickhouse/init/01-schema.sql) 는 `ON CLUSTER`(클러스터 `mupid` 2샤드, `mupid_all` 2레플리카) DDL 이다. `default` DB 에 만들고, 한 노드에서 한 번만 실행한다. 주요 객체는 `listen_events`, `track_sections`, `track_features`, `user_section_signals`, `play_section_coverage`, `rec_impressions`, `ai_preference_hourly`, `kafka_listen_events`, 사전 `dict_track_sections`·`dict_serving_sections` 이다.
- 토픽이 먼저 있어야 한다. `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` 라 토픽이 없으면 소비가 시작되지 않는다. 로컬에서는 [kafka/create-topics.sh](../../api/clickhouse/kafka/create-topics.sh) 와 [init/apply-ddl.sh](../../api/clickhouse/init/apply-ddl.sh) 가 같은 일을 한다(`docker compose up -d --wait`).
- **이미 떠 있는 클러스터**에는 [migrations/](../../api/clickhouse/migrations/) 의 `04` → `05` → `06` 을 **api 배포 전에** 한 번씩 적용한다(모두 `IF NOT EXISTS` / `OR REPLACE`). 새 클러스터는 01 에 이미 들어 있으니 적용하지 않는다. `02-numeric-ids.sql` 은 실행하지 않는 제안 문서이고, `03` 은 `track_sections` 재구성으로, 적용 시점을 사람이 정한다(파일 주석). 운영 클러스터에는 2026-09-22 에 적용했다.
- 추천 라벨 표 [ai_server/sql/section_labels.sql](../../ai_server/sql/section_labels.sql) 도 사람이 실행한다 → [ai_server/docs/SEED-RUNBOOK.md](../../ai_server/docs/SEED-RUNBOOK.md).
- 동작 확인: [api/clickhouse/verify.sh](../../api/clickhouse/verify.sh) (로컬)

### 5-4. 카탈로그 데이터

DB 덤프로 옮기지 않고 3-2절의 `catalog → covers → lyrics → classify → batch → analysis` 를 다시 돌려 API 로 채운다. 이어서 3-3절의 벡터를 보낸다. MySQL 의 구간이 ClickHouse 로 가지 못한 경우에는 `analytics_sync_pending` 의 재시도(1분)와 대조(매일)가 메운다([api/clickhouse/README.md](../../api/clickhouse/README.md) 「Spring 연동」).

> TODO: 팀 확인 — 서버의 `registered.json` 은 기존 DB 의 번호를 가리킨다. 빈 DB 로 옮길 때는 이 파일을 어떻게 다룰지(백업 후 새로 시작할지) 정해야 한다.

## 6. ERD 위치

- DDL 원문: [api/knowledge/domain/erd.sql](../../api/knowledge/domain/erd.sql) (ERD 도구 산출물, MySQL 8.4)
- 해설 · 규약 · 코드와 어긋난 지점: [api/knowledge/domain/erd.md](../../api/knowledge/domain/erd.md)
- ClickHouse 스키마: [api/clickhouse/init/01-schema.sql](../../api/clickhouse/init/01-schema.sql), 구간 표 설명은 [track-sections.md](../../api/knowledge/domain/track-sections.md)

> TODO: 팀 확인 — ERD 다이어그램 이미지(원본 ERD 도구 링크 또는 PNG)가 저장소에 없다.

## 7. 특이사항

- **JupyterHub 는 공용 서버다.** 배포는 코드만 올리고 `config.json` · `refresh_token.txt` · `registered.json` · `processed.json` 은 건드리지 않는다. 배치를 두 개 겹쳐 돌리지 않는다.
- 서버에는 ffmpeg 가 없다. conda 환경의 ffmpeg 를 `~/music_preprocessing/bin/` 에 링크해 두고, `run_batch.sh` 가 그 경로를 PATH 앞에 붙인다. 링크가 없으면 청킹이 전부 실패한다.
- `.python-version`(3.14)은 서버에 올리지 않는다. 올리면 uv 가 3.14 를 새로 받으려 한다([deploy-jupyter.py](../../infra/ci/deploy-jupyter.py)).
- `mupid-ai`(3.10) conda 환경에는 아무것도 설치하지 않는다. 색인을 만든 환경이기 때문이다.
- `refresh_token.txt` 는 배치가 회전시킨다. 배포 때 `REFRESH_TOKEN` 은 서버에 파일이 없을 때만 쓰인다. `RESET_TOKEN=true` 는 일회성으로만 쓴다.
- 발매일(`generated_at` 사용)과 아티스트 이미지(첫 곡 커버 사용)는 **팀이 합의한 임시값**이다(2026-09-15).
- 메타데이터에서 곡이 사라지거나 입력 파일을 지워도 서버의 곡은 지우지 않는다. 내리려면 `DELETE /api/v1/music/{id}` 를 직접 호출한다.
- 삭제된 곡(410)은 실패로 세지 않는다. `batch` 의 고아 스트림 점검은 경고만 남긴다.
- Jamendo 곡은 번호가 없어서 벡터 전송 대상에서 빠진다.
- 스키마 변경은 팀 합의 후 ERD → `erd.sql` 재산출 → DB 에 직접 ALTER → 엔티티 순서로 한다([erd.md](../../api/knowledge/domain/erd.md) 「ERD 를 고치기 전에」). CI 는 스키마를 검증하지 않는다.
- ClickHouse 운영 Keeper 는 단일 노드용 설정([keeper_config.xml](../../api/clickhouse/keeper/keeper_config.xml))이다. 3노드 이상으로 늘리는 것은 남은 과제다.
- 인수인계 참고: [AI데이터_인수인계_계획.md](../../진행사항/정예준/AI데이터_인수인계_계획.md), [세션_이어가기.md](../../진행사항/정예준/세션_이어가기.md) — 2026-09-17 기준 미적용 DDL 목록(①~⑥)은 [erd.md](../../api/knowledge/domain/erd.md) 「아직 DB 에 적용하지 않은 DDL」에 있다.
