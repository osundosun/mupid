# Qdrant — 순간 벡터 저장소 (S15P21A204-456)

설계 `infra/VECTOR-STORE-DESIGN.md` §6.1(컬렉션)·§6.4(보안)·§10.1(장애)·§10.2(모델–데이터 짝).
이 문서는 **쓰는 쪽(Spring 벡터 등록, 457)과 읽는 쪽(ai_server)이 맞춰야 하는 것**을 적는다.

| 무엇 | 어디 |
|---|---|
| 컬렉션 정의 (정본, 한 곳) | `ai_server/app/qdrant_collection.json` |
| 정의를 읽는 코드 | `ai_server/app/qdrant_schema.py` |
| 읽기 (서빙) | `ai_server/app/vector_store.py` 의 `QdrantVectorStore`, REST 는 `app/qdrant_store.py` |
| 컬렉션 만들기 (멱등, 운영은 배포 뒤 사람이) | `python -m ai_server.tools.qdrant_collection` |
| 개발·시험용 적재 (**운영 경로 아님**, localhost 만) | `python -m ai_server.tools.qdrant_dev_load` |
| 부하 측정 | `python -m ai_server.tools.bench_request_path --backend qdrant` |
| 매니페스트 | `infra_k8s/base/qdrant.yaml` · `qdrant-networkpolicy.yaml` |
| 운영 절차 | `infra_k8s/OPERATIONS-K8S.md` 7-1 |

## 1. 컬렉션

`moments__mert-v1-95m`. 임베딩 모델이 바뀌면 새 컬렉션을 만들고 바꿔 끼운다(이름에 임베딩 버전).
본문은 `python -m ai_server.tools.qdrant_collection --print` 로 볼 수 있다 — Spring 은 이 JSON 을
그대로 쓰거나(리소스로 복사) 같은 값으로 만든다. **값을 두 곳에 적지 않는다.**

| named vector | 차원 | 거리 | 저장 | 담는 것 |
|---|---|---|---|---|
| `mert` | 1536 | Cosine | 원본 `on_disk`, int8 스칼라 양자화본만 메모리(`always_ram`) | `u = (Z-μ)/‖Z-μ‖` — **ANN 대상** |
| `stage1_v{N}` | 128 | Cosine | 메모리 | 모델 버전 N 의 구간 벡터. 버전마다 하나 |
| `clap` | 512 | Cosine | 메모리 | 있는 구간만 (선택 벡터) |
| `feat_v1` | 285 | Dot | `on_disk`, HNSW 끔(`m: 0`) | 점수 계산용 fetch 전용 |

새 모델 버전의 벡터는 컬렉션을 다시 만들지 않고 **덧붙인다** — Qdrant v1.19 는
`PUT /collections/{c}/vectors/stage1_v{N}` 본문 `{"dense": {"size":128,"distance":"Cosine"}}` 를 받는다
(도구가 이렇게 한다). 서빙 중인 옛 버전은 그대로 남는다(설계 §6.3 반영 흐름 ②).

**payload** — AI 가 만든 값과 구간 지문만. MySQL 값(장르·무드·삭제 여부…)은 넣지 않는다(§5.1).

| 필드 | 형 | 색인 | 뜻 |
|---|---|---|---|
| `external_id` | keyword | 예 | `music_section_analysis.section_uid` |
| `track_id` | integer | 예 | `music.music_id` (ClickHouse `track_features.track_id` 와 같다) |
| `r` | float | — | `‖Z-μ‖`. 원본 복원 `Z = μ + r·u` |
| `source` | keyword | 예 | 추천 풀 구분(flow 등). 서빙은 늘 `source ∈ POOL` 로 건다 |
| `emb_version`·`feat_version` | keyword | 예·— | 임베딩·특징 버전 |
| `analysis_version` | keyword | 예 | 구간 지문. ClickHouse 값과 다르면 AI 가 버린다(§9.3-3, 458) |
| `seg_start_ms`·`seg_end_ms` | integer | — | 구간 지문 |

## 2. 포인트 id

`UUIDv5(7722576a-c58b-5149-9965-e75645c70abd, UTF-8 external_id)`. 숫자 id(`music_section_analysis_id`)는
구간 교체 때 새로 발급되므로 쓰지 않는다. Java 에는 v5 가 없어(`nameUUIDFromBytes` 는 v3) 직접 만든다 —
SHA-1(namespace 16바이트 ‖ 이름) 앞 16바이트, version 5·variant RFC 4122.
**대조값:** `T000001#3` → `08155de7-3e4e-58ff-8625-f9a049bc76d0` (`ai_server/tests/test_qdrant_store.py`).

## 3. μ — 쓰는 쪽과 읽는 쪽이 같아야 한다

`mert` 는 μ 로 중심화한 방향이라, 다른 μ 로 읽으면 **오류 없이** 유사도만 틀어진다.

| 서빙이 쓰는 μ | 언제 |
|---|---|
| 이미지의 `mu.npy` (모델 묶음, `models.lock` 의 `serving` 에 넣었을 때) | 채택된 모델이 있을 때 — 설계 §6.3 동결 μ |
| 판의 색인 풀 평균 (`moments_emb.npy`) | 지금 (`models.lock` 이 null). local 갈래와 같은 값 |

어느 쪽인지 `/health` 의 `vector_generation.status.mu_source` 에 실린다. 벡터를 쓰는 배치(457)는
같은 μ 로 `u`·`r` 을 계산해야 한다.

## 4. 서빙 — 갈래와 준비 판정

`MUPID_VECTOR_BACKEND=local|qdrant|pinecone`, **기본 local**. 비어 있으면 예전 `MUPID_PINECONE_ENABLED`
를 따른다. 모르는 값은 기동 오류다(조용히 local 로 내려가지 않는다).

qdrant 를 고르면 `/internal/ready` 와 추천 요청 입구가 컬렉션을 본다(10초 캐시):

| 상태 | 결과 |
|---|---|
| Qdrant 에 닿지 못함 | 준비 판정 실패 (`INDEX_NOT_READY`) — 설계 §10.1 "Qdrant 가 죽으면 멈춘다" |
| 컬렉션 없음 · 비었음 · `mert` 차원이 μ 와 다름 | 준비 판정 실패 |
| 이미지 모델 버전 `vN` 이 있는데 `stage1_vN` 이 없거나 일부 구간뿐 | 준비 판정 실패 (§10.2-1·5) |
| 이미지에 모델 버전이 없음 (`models.lock` null) | stage1 검사를 건너뛴다 |

Qdrant 가 살아나면 재기동 없이 준비 완료로 돌아온다(기동에서 멈추지 않는다).

검색의 허용 목록(§9.3-1): 컬렉션의 절반 이상이면 필터 없이 넉넉히 뽑아 거르고, 1만 이하면 `HasId`
사전 필터, 그 사이는 넉넉히 뽑아 거른 뒤 모자라면 한 번 더 넓힌다.

## 5. 키

| 키 | 권한 | 비밀 | 받는 곳 |
|---|---|---|---|
| `MUPID_QDRANT_API_KEY` | 전체(쓰기·스냅샷) | `MUPID_API_ENV` → `mupid-secrets` | Qdrant · 스냅샷 CronJob · Spring(457) |
| `MUPID_QDRANT_READ_ONLY_API_KEY` | 읽기 | `MUPID_AI_SERVER_ENV` → `mupid-ai-secrets` | Qdrant · ai |

읽기 전용 키로는 검색·조회·개수만 되고 쓰기·스냅샷은 403 이다(v1.19.1 에서 확인). 키가 비면 Qdrant 는
무작위 키로 잠긴 채 뜬다.

## 6. 알려진 비용 — 후보 원본 fetch

추천 점수는 후보의 원본 벡터를 읽어 다시 센다(`recommender.raw_vectors`). DNA 경로는 씨앗당 200개라
요청마다 약 1,600개(× 1536차)를 읽고, REST 는 JSON 이라 그 파싱이 요청 시간의 대부분이다(256개당 약
55ms, Qdrant 쪽은 약 20ms). 부하 측정 결과는 S15P21A204-456 MR 에 있다. 줄이는 길은 둘이다 — 점수에
필요한 것이 후보와 씨앗의 내적뿐이라 Qdrant 가 점수를 돌려주게 하거나(요청 경로 연결, 458), gRPC(바이너리).
