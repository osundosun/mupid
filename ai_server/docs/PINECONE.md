# Pinecone 순간 벡터 검색

추천 후보를 찾는 벡터 검색과 DNA·온보딩용 원본 벡터 fetch 를 Pinecone 이 맡는다.
**곡·순간 메타데이터는 맡지 않는다** — 그쪽 정본은 MySQL 이고 추천 서버는 Spring
내부 카탈로그 API 로 읽는다(`app/catalog_api.py`).

## 물리 인덱스가 둘이다

| 역할 | 저장값 | 쓰는 곳 |
| --- | --- | --- |
| `candidate` | `normalize(Z - mu)` | 후보 검색. 질의도 같은 변환을 거친다 |
| `raw` | `Z` (저장본 그대로) | DNA 군집 · 온보딩 조건별 평균 |

역할을 namespace 로 가르지 않는다. namespace 는 이미 "환경 + 승인된 임베딩 버전"
을 표현하고, 거기에 역할까지 얹으면 무엇을 바꾸는지가 흐려진다.

**섞어 쓰면 오류가 나지 않는다.** 원본으로 후보를 찾으면 중심화가 빠져 순위가
달라지고, 변환본으로 평균을 내면 DNA 중심이 어긋난 공간에 놓인다. 둘 다 품질만
나빠진다 — 그래서 시험이 값으로 못박는다(`tests/test_pinecone_verification.py`).

## 인덱스 설정

두 인덱스 모두 같다. **서버도 배치도 인덱스를 만들지 않는다** — 콘솔에서 먼저 만든다.

    dimension  1536        moments_emb.npy 실측 (MERT-v1-95M, concat(mean 768, std 768))
    metric     dotproduct  저장 벡터가 행 단위 L2=1 이라 cosine 과 동치.
                           추천 코드가 `V @ q` 라 dotproduct 가 계산과 1:1 로 맞는다
    type       dense

차원은 만든 뒤 바꿀 수 없다. 틀리면 upsert 가 건건이 거절되고 그 실패가 배치 로그에만
쌓인다.

## 무중단 namespace 전환을 두지 않는다

활성 namespace 는 **고정**이다. 분석 배치가 벡터나 추천 풀을 바꾸면 그 자리에서 전체를
갈아 끼우고, 갈아 끼우는 동안 **추천을 멈춘다.**

```
멈춤 표시 → 잔존 삭제 → candidate·raw 전량 upsert → 검증 → 표시 제거
```

**검증이 전부 통과할 때만 재개한다.** 어느 한 단계라도 실패하면 표시(`/state` 의
`ai_paused.json`)가 남고, 서빙 파드는 `/internal/ready` 가 실패해 준비 완료가 되지
않는다. 그동안 추천 호출은 `RELOAD_IN_PROGRESS`(503)를 받는다 — 로컬 옛 벡터로
조용히 내려가지 않는다.

표시를 파일로 두는 것은 권한 때문이다. 배치는 `/state` 에 쓸 수 있고 서빙은 같은
자리를 읽을 수 있다. 쿠버네티스 API 권한도 새 CronJob 도 필요 없다.

## 언제 도는가

기존 `ai-labeler` CronJob 의 세 번째 단계다(`infra_k8s/base/ai-labeler.yaml`).
일정은 그 CronJob 의 `schedule` 하나가 정한다 — **별도 CronJob 을 만들지 않는다.**

```
python -m ai_server.tools.pull_signals
python -m ai_server.tools.run_labeler --json
python -m ai_server.tools.pinecone_sync --reload
```

## 적재본 지문 (snapshot)

`candidate` 인덱스 · `raw` 인덱스 · `pinecone_center.npz` 는 **한 묶음**이다. 하나만
갈아 끼운 상태를 잡으려고 평균에 지문을 함께 적는다.

담는 것 넷:

| 항목 | 왜 |
| --- | --- |
| 정렬된 `external_id` | 구성이 같으면 차례가 달라도 같아야 한다 |
| 벡터 값 (float32 바이트) | **재분석으로 값만 바뀐 경우**를 잡는다 — id 만 세면 못 잡는다 |
| 임베딩 버전 | 모델·층·풀링이 바뀌면 같은 값이라도 뜻이 다르다 |
| 추천 풀 설정 | `MUPID_POOL` / `MUPID_POOL_EXTRA`. 대상이 달라진다 |

서빙은 기동에서 이 지문을 다시 재어 평균의 것과 대조한다. 어긋나면 준비 완료가 되지
않는다.

## 명령

```
python -m ai_server.tools.pinecone_sync --reload    멈춤 → 전체 갱신 → 검증 → 재개
python -m ai_server.tools.pinecone_sync --audit     카탈로그 ↔ 인덱스 대사
python -m ai_server.tools.pinecone_sync --verify    연결·차원·개수만 본다
python -m ai_server.tools.pinecone_sync --status    지금 멈춰 있는가
python -m ai_server.tools.pinecone_sync --delete ID [ID ...]
python -m ai_server.tools.pinecone_sync --resume    사람이 확인한 뒤 손으로 재개
```

`--dry-run` 을 붙이면 무엇을 할지만 말하고 Pinecone 을 바꾸지 않는다.

종료 코드: `0` 정상 · `2` 설정·입력 오류 · `3` 인덱스 없음·차원 불일치 · `4` 대사 불일치

## 환경변수

`MUPID_AI_SERVER_ENV`(GitLab File 변수) → 노드 수신기 → Secret `mupid-ai-secrets`
→ AI 파드와 배치 CronJob. 값은 어디에도 찍지 않는다.

| 키 | 필수 | 뜻 |
| --- | --- | --- |
| `MUPID_PINECONE_API_KEY` | 켤 때 | Pinecone API 키 |
| `MUPID_PINECONE_CANDIDATE_INDEX` | 켤 때 | 후보 검색 인덱스 이름 |
| `MUPID_PINECONE_RAW_INDEX` | 켤 때 | 원본 벡터 인덱스 이름 |
| `MUPID_PINECONE_NAMESPACE` | 아니오 | 환경 + 임베딩 버전. **고정값** |
| `MUPID_PINECONE_ENABLED` | 아니오 | 검색을 Pinecone 으로 돌릴지. 기본 꺼짐 |
| `MUPID_PINECONE_TIMEOUT` | 아니오 | 호출 하나의 제한 시간(초, 기본 10) |

`ENABLED` 를 켰는데 앞의 셋 중 하나라도 비면 노드 수신기가 업로드를 거부한다.

## CI 이미지를 다시 구워야 한다

`ai_server/requirements.txt` 에 `pinecone` 이 들어갔다. `infra/ci/ai-server.yml` 은
구운 이미지와 이 파일을 `cmp` 로 대조해 다르면 job 을 멈춘다 — **의존성이 바뀌면
반드시 러너 호스트에서 다시 굽는다.**

```
sh infra/runner/build-images.sh
```

## moments_emb.npy 는 어디에 쓰나

**배치의 적재 원본**이다. 추천 서버는 Pinecone 이 켜져 있으면 유사도 검색에 쓰지
않는다(`recommender.vector_backend()` 가 갈래를 고른다). 판에는 그대로 남는데,
`check_integrity` 의 행 수 대조와 Pinecone 을 끈 배포의 로컬 갈래가 읽기 때문이다.
