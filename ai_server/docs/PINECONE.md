# Pinecone 순간 벡터

순간 임베딩의 **유일한 운영 정본**이다. 후보 검색과 원본 벡터 복원을 둘 다 여기서 한다.

**곡·순간 메타데이터는 맡지 않는다** — 제목·장르·무드·보컬·악기·순간 시각·삭제
여부의 정본은 MySQL 이고 추천 서버는 Spring 내부 카탈로그 API 로 읽는다
(`app/catalog_api.py`).

## 물리 인덱스는 하나다

| namespace | id | vector | metadata |
| --- | --- | --- | --- |
| `<이름>` | `external_id` | `u = (Z - mu) / ‖Z - mu‖` | `{"r": ‖Z - mu‖}` |
| `<이름>__control` | `__center__` | `mu` | `embedding_version` · `snapshot` · `rows` · `dim` |

**원본 `Z` 를 따로 저장하지 않는다.** 같은 레코드의 `r` 과 control 의 `mu` 로 되돌린다.

```
Z = mu + r · u
```

근사가 아니라 정확한 복원이다 — `u` 와 `r` 이 `Z - mu` 를 그대로 쪼갠 것이라
부동소수점 반올림만 남는다. raw 전용 인덱스를 두지 않는 이유가 이 한 줄이다.

`r` 은 **벡터를 쪼갠 값**이지 DB 가 아는 사실이 아니다. 그 구분이 "metadata 에 DB
소유값을 넣지 않는다" 의 경계다.

## 인덱스 설정

**서버도 배치도 인덱스를 만들지 않는다** — 콘솔에서 먼저 만든다.

    dimension  1536        moments_emb.npy 실측 (MERT-v1-95M, concat(mean 768, std 768))
    metric     dotproduct  저장 벡터가 행 단위 L2=1 이라 cosine 과 동치.
                           추천 코드가 `V @ q` 라 dotproduct 가 계산과 1:1 로 맞는다
    type       dense

차원은 만든 뒤 바꿀 수 없다. 틀리면 upsert 가 건건이 거절되고 그 실패가 배치 로그에만
쌓인다.

## 무중단 전환을 두지 않는다

활성 namespace 는 **고정**이다. 분석 배치가 벡터나 추천 풀을 바꾸면 그 자리에서 전체를
갈아 끼우고, 갈아 끼우는 동안 **추천을 멈춘다.**

```
UPDATING → 전체 삭제 → 삭제 확인 → control(μ·snapshot) → 전량 적재
         → 대사 → AI 서버가 새 세대를 읽었는지 확인 → READY
```

**검증이 전부 통과할 때만 `READY` 로 간다.** 어느 한 단계라도 실패하면 `UPDATING`
이 남고, 추천 호출은 `RELOAD_IN_PROGRESS`(503)를 받는다 — 로컬 옛 벡터로 조용히
내려가지 않는다.

`--resume` 은 상태만 바꾸는 것이 아니다. 대사와 서버 반영 확인을 **다시 통과해야**
열린다. 우회 경로는 두지 않는다.

상태를 control 레코드에 두는 이유는 **근거가 하나여야** 하기 때문이다. 파일 표시를
따로 두면 어느 쪽이 참인지가 자리마다 갈린다.

## 서버가 새 μ 를 읽는 방법

추천 요청마다 control 레코드 **하나를** id 로 읽는다(전체 목록을 훑지 않는다).

    UPDATING              → 503. 로컬로 내려가지 않는다
    READY + 같은 snapshot → 들고 있던 μ 를 그대로 쓴다
    READY + 다른 snapshot → 새 μ 를 읽어 캐시를 바꾼 뒤 추천한다

`UPDATING` 일 때도 캐시를 갱신한다. 배치가 `/health` 로 "서버가 새 세대를 읽었나" 를
확인한 뒤에 `READY` 로 넘어가기 때문이다 — 안 갱신하면 서로 기다리다 멈춘다.
**재기동이 필요 없다.**

## 언제·어디서 도는가

**`ai-labeler` 가 아니다.** 그 파드의 색인에는 `moments_emb.npy` 가 없다 — 배송에서
뺐기 때문이다. 거기서 돌리면 적재 원본이 없어 실패한다.

원본을 실제로 만드는 쪽, 곧 **JupyterHub 분석 배치**가 부른다
(`music_preprocessing/run_batch.sh`). `analysis` 가 **성공했을 때만** 돈다.

```
run analysis --yes --local --segmenter-python ...   # moments_emb.npy 를 만든다
  └─ 성공 && MUPID_PINECONE_ENABLED 있음
       └─ (cd $MUPID_AI_SERVER_DIR && python3 -m ai_server.tools.pinecone_sync --reload)
```

일정은 `data-batch` 파이프라인 스케줄 하나가 정한다 — 새 CronJob·스케줄러를 만들지
않는다. 자격은 `MUPID_AI_SERVER_ENV`(File 변수)가 배치 **프로세스 환경으로만**
들어간다. 서버 디스크에 쓰지 않는다.

## 적재본 지문 (snapshot)

데이터 namespace 와 control 이 **한 갱신에서 함께** 쓰이므로 둘이 어긋날 자리가 없다.
그래도 판이 바뀐 것을 잡으려고 control 에 지문을 적는다.

| 항목 | 왜 |
| --- | --- |
| 정렬된 `external_id` | 구성이 같으면 차례가 달라도 같아야 한다 |
| 벡터 값 (float32 바이트) | **재분석으로 값만 바뀐 경우**를 잡는다 — id 만 세면 못 잡는다 |
| 임베딩 버전 | 모델·층·풀링이 바뀌면 같은 값이라도 뜻이 다르다 |
| 추천 풀 설정 | `MUPID_POOL` / `MUPID_POOL_EXTRA`. 대상이 달라진다 |

서빙은 기동에서 control 의 세대를 읽는다. 저장본(`moments_emb.npy`)이 있는 환경에서는
지문을 다시 재어 대조하고, 운영 판에는 그 파일이 없으므로 control 이 적은 값을 쓴다.

## 명령

```
python -m ai_server.tools.pinecone_sync --reload    멈춤 → 전체 갱신 → 검증 → 재개
python -m ai_server.tools.pinecone_sync --audit     카탈로그 ↔ 인덱스 대사
python -m ai_server.tools.pinecone_sync --verify    연결·차원·개수만 본다
python -m ai_server.tools.pinecone_sync --status    지금 멈춰 있는가
python -m ai_server.tools.pinecone_sync --delete ID [ID ...]
python -m ai_server.tools.pinecone_sync --resume    정합성을 다시 통과해야 재개된다
```

`--dry-run` 을 붙이면 무엇을 할지만 말하고 Pinecone 을 바꾸지 않는다.

종료 코드: `0` 정상 · `2` 설정·입력 오류 · `3` 인덱스 없음·차원 불일치 · `4` 대사 불일치

## 환경변수

`MUPID_AI_SERVER_ENV`(GitLab File 변수) → 노드 수신기 → Secret `mupid-ai-secrets`
→ AI 파드와 배치 CronJob. 값은 어디에도 찍지 않는다.

| 키 | 필수 | 뜻 |
| --- | --- | --- |
| `MUPID_PINECONE_API_KEY` | 켤 때 | Pinecone API 키 |
| `MUPID_PINECONE_INDEX` | 켤 때 | 순간 벡터 인덱스 이름. **하나다** |
| `MUPID_PINECONE_NAMESPACE` | 아니오 | 환경 + 임베딩 버전. **고정값**. 평균은 `<이름>__control` |
| `MUPID_PINECONE_ENABLED` | 아니오 | 검색을 Pinecone 으로 돌릴지. 기본 꺼짐 |
| `MUPID_PINECONE_TIMEOUT` | 아니오 | 호출 하나의 제한 시간(초, 기본 10) |

`ENABLED` 를 켰는데 키나 인덱스 이름이 비면 노드 수신기가 업로드를 거부한다.

## CI 이미지를 다시 구워야 한다

`ai_server/requirements.txt` 에 `pinecone` 이 들어갔다. `infra/ci/ai-server.yml` 은
구운 이미지와 이 파일을 `cmp` 로 대조해 다르면 job 을 멈춘다 — **의존성이 바뀌면
반드시 러너 호스트에서 다시 굽는다.**

```
sh infra/runner/build-images.sh
```

## 로컬에 남는 파일과 남지 않는 파일

| 파일 | 운영 서빙 | 왜 |
| --- | --- | --- |
| `moments_emb.npy` | **없다** | 순간 벡터의 정본은 Pinecone 이다. 분석 배치가 갱신할 때만 읽는 입력 산출물이라 배송에 넣지 않는다 |
| `pinecone_center.npz` | **없다** | 평균은 control namespace 에 있다. 파일은 판마다 따로 움직여서 인덱스만 갈아 끼운 상태가 생긴다 |
| `features_v1.npz` | 있다 | Stage 1 사용자 인코더의 입력. 추천 점수 계산에 쓰인다 |
| `stage1_section.npy` | 있다 | 학습 점수 `ZS @ z_u`. 추천 점수 계산에 쓰인다 |
| `moments.parquet` | 있다 | 모델 행 연결(`emb_row`)과 오디오 파생값(`rel_start`·`rel_end`·`rms`)만 |

`features_v1.npz` 와 `stage1_section.npy` 는 Pinecone 으로 옮기지 않는다. 후보 검색이
아니라 **점수 계산**에 쓰는 값이고, ANN 질의로 표현되지 않는다.
