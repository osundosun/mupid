# 구간 분석 배치 — 하루 1회

신곡을 기존 추천 공간에 얹는 배치다.

```
① 분할        All-In-One          analysis/segmenter.py    실행기 · 재현 8/8
──────────────────────────────────────────────  만들어져 있다
② 임베딩      1536   analysis/embedder.py
③ 특징         285   analysis/feature_space.py
④ 구간벡터     128   feature_space.enc_sec
⑤ 색인 갱신          analysis/indexer.py    append · 무결성 게이트 · 교체 · 롤백
⑥ 배치 본체          analysis/batch.py      수집→분석→새 판→등록→확인→교체→알림
   운영 입출력      analysis/api.py        요구 5 · 6 · 1 클라이언트
──────────────────────────────────────────────  남은 것
⑦ 요구 5·6·1 의 **Spring 구현** · 변경 곡 처리 · GPU 박스 원격 배선 · CronJob 배포
```

## 1. 단계별 입출력

| 단계 | 입력 | 출력 | 어디서 |
|---|---|---|---|
| ② | 파형(24kHz mono) + 구간 목록 | `(n, 1536)` | `Embedder.embed` |
| ③ | 구간 메타 + ② + 트랙 5값 | `(n, 285)` | `FeatureSpace.transform` |
| ④ | ③ | `(n, 128)` | `FeatureSpace.enc_sec` |
| (곁) | demucs 스템 + 구간 목록 | 구간별 악기 이름 | `instruments.for_track` |

`analyze_track()` 이 셋을 묶어 `TrackResult` 로 돌려준다. 값의 규약은
`EMBEDDING-RECIPE.md`(②)와 `FEATURE-SPACE.md`(③④)에 있다.

**산출물에 쓰지 않는다.** 계산해서 호출자에게 넘기고 처리 기록만 갱신한다.

## 2. 곡 단위 격리

한 곡이 깨져도 나머지는 계속 간다. 배치가 통째로 멈추면 그날 들어온 곡이 전부 밀리고,
원인 곡 하나를 찾느라 다시 돌려야 한다.

```
status   ok · failed · skipped · changed
stage    sections · embed · features · section_vecs · done
```

`stage` 로 **어디서 끊겼는지** 남긴다. 실패 곡은 목록으로 보고하고 다음 배치가 그 곡부터
다시 집는다.

## 3. 이미 처리한 곡

음원 **SHA-256** 으로 판단한다. 파일명이나 시각이 아니라 내용이다.

기록은 `analysis_processed.json` 이고, **임시 파일에 쓰고 바꿔 끼운다.** 곡마다 저장하므로
중간에 죽을 수 있는데, 그냥 덮어쓰면 반쪽짜리 JSON 이 남아 다음 배치가 처리한 곡을 전부
다시 돈다.

### 음원이 바뀐 곡은 이번 사이클에서 처리하지 않는다

기존 색인 행을 고치는 일이라 교체 설계와 같이 가야 한다. 발견하면 `changed` 목록에만
남긴다.

> **이미 그런 곡이 있다.** 2026-09-16 확인 결과 flow 110곡 중 **5곡**
> (`T000002` · `T000014` · `T000016` · `T000017` · `T000018`)의 음원이 색인을 만든 뒤
> 교체됐다. `T000002` 는 3.7초 짧아졌다. 구간 JSON 은 옛 음원 기준이라 **경계만 보면
> 멀쩡해 보인다** — 임베딩을 다시 계산해야 드러난다.

## 4. 색인 갱신 (`analysis/indexer.py`)

### 갱신 대상은 4개가 아니라 6개다

색인 4종만 늘리면 신곡이 **오류 없이 반쪽만 들어간다.**

| 파일 | 지금 | 신곡이 없으면 |
|---|---:|---|
| `moments.parquet` | 65,540행 | 구간 자체가 없다 |
| `moments_emb.npy` | 65,540 × 1536 | 행 수 불일치로 기동 거부 |
| `features_v1.npz` | 65,540 × 285 | 같음 |
| `stage1_section.npy` | 65,540 × 128 | 같음 |
| **`content.parquet`** | 7,971곡 × 36열 | `trk_*` 다섯 열을 못 만든다 → **285차 생성 자체가 불가** |
| **`flow_meta.parquet`** | 1,063곡 × 14열 | 추천 풀에는 드는데 **조건 검색에서만 조용히 빠진다** |

`clap_moments.npz` 는 대상이 아니다. 없으면 CLAP 재정렬만 빠지는데, 자연어 해석기가
미설정이라 지금 그 경로는 돌지 않는다.

#### `content.parquet` 의 나머지 31열

서빙이 쓰는 것은 `bpm`·`centroid`·`rolloff`·`rms_mean`·`flatness` **다섯 개**다. 나머지
(`mfcc_m0..12`·`mfcc_s0..12`·`zcr`·`rms_std`·`dur_sec`·`error`)는 추천 경로가 읽지 않는다.

**규칙: 다섯 열은 반드시 채우고, 나머지는 비워도 된다.** 갱신기가 빠진 열을 `NA` 로
채운다. 다만 `librosa_content` 로 한 번에 뽑으면 전부 나오므로, 같은 방식으로 뽑는 편이
낫다 — 나중에 이 열들을 쓰게 됐을 때 신곡만 비어 있으면 그때 다시 돌려야 한다.

### append 규칙

- **기존 행은 한 줄도 바뀌지 않는다.** 신곡은 뒤에만 붙는다
- `emb_row` 는 기존 N 다음부터 이어진다 (0부터 연속이 무결성 조건이다)
- `pca_mean`·`pca_comp`·`side_cols`·`evr` 는 **그대로 복사**한다. 다시 계산하지 않는다
- `moments_emb.npy` 는 403MB 라 통째로 올리지 않는다. `np.lib.format.open_memmap` 으로
  새 파일을 만들어 블록(4,096행) 단위로 옮긴다. **교체 중에는 디스크가 두 벌 필요하다**
- `features_info.json` 의 `features_rows`·`features_sha256` 을 새 값으로 고친다.
  안 고치면 동결 통계 로더가 해시 불일치로 **다음 배치를 멈춘다**

### 파일을 하나씩 바꾸면 원자적이지 않다

`os.replace` 를 6번 부르면 그 사이에 기동한 프로세스가 **섞인 판**을 읽는다. 행 수가
어긋나면 기동이 거부되지만, 더 나쁜 것은 행 수만 맞고 내용이 다른 조합이다.

그래서 **디렉터리 단위로** 바꾼다.

```
<ARTIFACT_DIR>/data/proc/mupid               ← 서비스가 보는 곳
<ARTIFACT_DIR>/data/proc/mupid_v<타임스탬프>  ← 새 판을 여기 통째로 만든다
<ARTIFACT_DIR>/data/proc/mupid_prev<…>       ← 바꾸기 전 판 (롤백용, 3판 유지)
```

> **완전한 원자 교체는 아니다.** rename 이 둘(`mupid → prev`, `v… → mupid`)이라 그 사이에
> 기동한 프로세스는 색인 디렉터리를 못 찾아 **기동에 실패한다.** 섞인 판을 읽는 것보다
> 낫다 — 그쪽은 정상처럼 뜨면서 엉뚱한 곡을 추천한다.
>
> **K8s 라면 교체하지 않는 쪽이 더 단순하다.** `MUPID_AI_ARTIFACT_DIR` 를 새 판으로 바꿔
> 재기동하면 rename 이 아예 없어지고, 롤백은 환경변수를 되돌리는 일이 된다. Windows 에서
> symlink 가 권한을 요구하는 문제도 함께 사라진다.

### 무결성 게이트 (교체 **전에** 돌린다)

하나라도 실패하면 교체하지 않는다.

- 4종 행 수 일치 · `emb_row` 0부터 연속 · `moment_id` 중복 0
- PCA 기저가 이전 판과 **바이트 단위로 같은가**
- 기존 N행의 X·임베딩·구간벡터·moments 행이 이전 판과 같은가 — **표본이 아니라 전량**
- 신곡이 `content.parquet`·`flow_meta.parquet` 에 **모두** 있는가
- 신곡 구간이 3초 이상인가 (레시피의 버림 규칙과 일치)

### 변경된 곡은 이 경로로 처리하지 않는다

이미 색인에 있는 `track_id` 가 들어오면 **거부하고 목록에 남긴다.** append 가 아니라 기존
행 교체라 성격이 다르다. 조용히 덮어쓰지 않는다.

앞 사이클에서 찾은 5곡(`T000002`·`T000014`·`T000016`·`T000017`·`T000018`)이 그 대상이다.
처리 방법은 둘인데 이번에 구현하지 않았다.

| 방법 | 장점 | 단점 |
|---|---|---|
| 해당 곡 구간을 빼고 다시 붙인다 | 판 하나로 끝난다 | `emb_row` 가 밀려 **`id_map`·ClickHouse 를 전부 다시 만들어야** 한다 |
| 그 곡만 새 판을 통째로 다시 만든다 | 번호가 안 밀린다 | 65,540행을 다시 쓴다(수 분·디스크 두 벌) |

### 교체 뒤 서비스를 다시 띄워야 한다

서버는 기동할 때 `Catalog` 으로 색인을 **통째로 메모리에 올리고 워커는 하나다.** 파일만
바꾸면 돌고 있는 프로세스가 옛 색인을 계속 쓴다. 오류는 나지 않고 새 곡만 추천에 안 나온다.

갱신기는 교체까지만 하고 `restart_notice()` 로 그 사실을 남긴다. K8s 에서는 CronJob 뒤에
`kubectl rollout restart` 가 붙는다.

## 5. 배치 본체 (`analysis/batch.py`)

단계는 다 있었고 연결이 없었다. 이 파일이 그 연결이다.

### 등록이 교체보다 **먼저**다

목표 서술은 "색인 원자 교체 → 구간·ClickHouse 등록" 순이었는데, **그 순서로 하면 교체
직후 서비스가 통째로 죽는다.**

추천기는 `id_map` 을 보지 않고 색인 풀 전체에서 곡을 고른다. 응답을 만들 때에야
`im.music_id()` 로 숫자 ID 를 찾고, 없으면 `MappingError` 가 올라가 **그 요청 전체가
404**(`MUSIC_ID_MAPPING_NOT_FOUND`)가 된다. 신곡 하나가 후보에 뽑히기만 해도 그 요청이
떨어진다. `/internal/ready` 는 풀 전량 매핑을 요구하므로 **503** 이다. 즉 신곡이 색인에
들어간 순간부터 등록이 끝날 때까지 추천이 멈춘다.

```
분석 → 새 판 만들기 → 무결성 검사
     → 구간 등록(MySQL) → id_map 갱신 → ClickHouse 등록
     → 신곡 전량이 매핑됐는지 확인
     → 그때 색인 교체 → 재기동
```

**등록이 끝나기 전에는 교체하지 않는다.** 등록이 불가능하면(요구 1 미설정 — 주소·토큰이 없다)
새 판을 만들어 두고 교체를 보류한 채 그 사실을 보고한다. **새 판은 지우지 않는다** —
등록이 붙으면 그대로 이어서 쓴다. 지우면 그 곡들을 다시 분석해야 한다.

확인은 `verify_mapping()` 이 한다. 새 판 안의 `id_map.parquet` 에 신곡의 **곡과 구간이
전량** 있어야 한다. 절반만 매핑된 채 교체하면 그 곡이 뽑힐 때마다 404 다.

> 등록기(`Registrar`)는 **주입한다.** 기본값 `UnavailableRegistrar` 는 아무것도 하지 않고
> 보류로 끝낸다 — 설정이 없으면 숫자 ID 를 받을 곳이 없고, 지어내면 실제 DB 와 어긋난
> 순간 엉뚱한 곡을 추천한다. 배치 자체는 MySQL·ClickHouse 에 붙는 길이 없다.

### 종료 코드

cron·CronJob 이 실패를 알아차려야 한다. 0 이 아니면 사람이 본다.

| 코드 | 뜻 |
|---:|---|
| 0 | 전부 성공 (또는 할 일이 없었다) |
| 10 | 일부 곡 실패 — **색인은 교체됐다.** 실패 곡은 다음 배치가 다시 집는다 |
| 20 | **교체 보류** — 새 판은 만들었지만 등록·게이트를 통과하지 못했다 |
| 30 | 다른 실행이 돌고 있다 (잠금). 아무것도 하지 않았다 |
| 1 | 실패 — 산출물이 없거나 되돌릴 수 없는 오류 |

### 동시 실행 금지

`analysis_batch.lock` 파일 잠금이다. 같은 기록·같은 판을 두 프로세스가 만지면 안 된다.
**뺏지 않는다** — 오래된 잠금이라도 스스로 지우지 않는다. 앞 실행이 403MB 짜리 파일을
쓰는 중일 수 있고, 그때 끼어들면 반쪽 판이 남는다. 사람이 `--force-unlock` 으로 푼다.

### 요약 보고

`analysis_batch.jsonl` 에 한 줄씩 쌓고 표준출력에도 그대로 낸다.

```json
{"tracks": 2, "ok": 1, "failed": 1, "skipped": 0, "changed": 0,
 "added_tracks": 1, "added_sections": 6, "rows_before": 65540, "rows_after": 65546,
 "staged_dir": "…/mupid_v20260916-120321", "swapped": true, "restart_required": true,
 "hold_reason": "", "exit_code": 10, "failures": [{"track_id": "…", "stage": "sections"}]}
```

`failures` 와 `changed_tracks` 가 다음 배치의 재시도 대상이다.

## 6. ① 분할 (`analysis/segmenter.py`)

**실행기가 섰다.** 계약(`mupid.sections/v1`)은 그대로 두고 그 아래에 실제 All-In-One
호출을 붙였다.

```json
{ "schema": "mupid.sections/v1", "track_id": "T000001", "analysis_version": "v1",
  "audio_sha256": "…", "duration_sec": 176.576,
  "segmenter": {"name": "allin1", "model": "harmonix-all", "version": "1.1.0",
                "device": "cuda:0", "bpm": 105},
  "segments": [{"label": "start", "start": 0.0, "end": 2.43},
               {"label": "verse", "start": 2.43, "end": 39.0}] }
```

| sections.json | moments.parquet |
|---|---|
| `segments[].start` · `end` | `start_sec` · `end_sec` · `dur_sec` |
| `segments[].label` | `section_label` (소문자. ClickHouse 로 갈 때만 대문자) |
| `duration_sec` | `track_dur_sec`, 그리고 `rel_start`·`rel_end` 의 분모 |
| `segmenter.model` | `label_source` = `allin1_harmonix` (`time_source` = `allin1`) |
| 순번 | `section_idx` · `moment_id` = `<track_id>#<idx>` |

**거르는 규칙은 이 파일에 없다.** 3초 미만과 `start`·`end` 라벨은
`embedder.keep_segments` 한 곳에서만 버린다 — 두 곳에서 거르면 어느 쪽이 적용됐는지 알
수 없고, 구간 수가 어긋나 색인 정렬이 깨진다. 그래서 `section_idx` 는 원본 순번이 아니라
**거른 뒤의 순번**이다. 원본 `m17_flow_ingest.allin1_segments()` 도 같은 규칙이다.

### 옮겨 적은 설정 — 정본은 `~/rec_mupid/src/allin1_flow.py`

색인의 flow 6,767구간이 이 설정으로 만들어졌다. 하나라도 다르면 신곡의 경계가 기존 곡과
**다른 규칙으로** 잘린다. 고르거나 기본값에 기대지 않고 원본에서 값을 옮겼다.

| 값 | | 왜 |
|---|---|---|
| `model` | `harmonix-all` | 라벨 어휘(intro·verse·chorus…)가 여기서 나온다 |
| `device` | `cuda:0` | 박스는 `CUDA_VISIBLE_DEVICES` 로 물리 GPU 를 가린다 |
| `demix_dir` · `spec_dir` | `<work>/demix` · `<work>/spec` | demucs 분리음·스펙트로그램 캐시 |
| `keep_byproducts` | `True` | 속도만의 문제가 아니다 — 아래 **재현** 참고 |
| `multiprocess` | `False` | 원본이 끈 채로 돌렸다 |
| `overwrite` | `False` | 이미 분석한 곡을 다시 돌리지 않는다 |
| 묶음 | **8곡** | 원본 `B = 8` |

`allin1` 1.1.0 은 모델을 밖에서 들고 있을 공개 경로를 주지 않는다 — `analyze()` 안에서
올리고 끝나면 놓는다. 그래서 **곡 하나씩 부르지 않고 8곡을 한 번에 넘긴다.** 적재가
7.35초라 곡마다 부르면 그게 추론(2.3초)보다 비싸다.

### 어디서 도는가 — 같은 환경에서 된다

측정해서 정했다. GPU 박스의 `mupid-ai` 에 **All-In-One 과 MERT 가 이미 같이 들어 있다.**

```
mupid-ai   python 3.10.21   allin1 1.1.0 · torch 2.5.0+cu124 · transformers 4.47.1
                            natten 0.17.4 · demucs 4.1.0 · librosa 0.11.0 · numpy 1.26.4
```

충돌이 없다. ①과 ②를 **한 프로세스에서** 돌릴 수 있고, 실제로 그렇게 돌려 곡당 3.14초를
쟀다. 그래서 기본 경로는 같은 프로세스 호출이다.

`music_preprocessing` 본체는 `.python-version` 이 **3.14** 라 `allin1`(natten 은 토치·파이썬
판에 묶인 휠이다)을 올릴 수 없다. 그쪽에서 부를 때는 `Segmenter(python=...)` 에 3.10
환경의 실행 파일을 준다 — 그때도 **재구현하지 않고** 같은 `analyze()` 를 자식 프로세스에서
부르고 결과 JSON 만 받는다. 변환은 부모 한 곳에서만 한다.

```
분할만                 conda run -n mupid-ai python …          (박스)
3.14 에서 부를 때      Segmenter(python="~/.conda/envs/mupid-ai/bin/python")
timeout                곡당 900초 · 묶음이면 곡 수만큼 곱한다
실패 기록              곡 id · 단계 · 종료 코드 · stderr 마지막 20줄
```

### 실패는 곡 단위로 격리한다

묶음이 통째로 깨지면 **한 곡씩 다시 돌려** 진짜 원인 곡만 실패로 남긴다. 그러지 않으면
한 곡 때문에 그날 들어온 곡이 전부 밀린다. 실패 곡은 `failures[track_id]` 에 단계와 이유로
남고 다음 배치가 그 곡부터 다시 집는다.

분할기를 부를 수 없으면 **무엇이 없는지 말하고 멈춘다**(`SegmenterUnavailable`). 조용히 빈
결과를 돌려주면 그 곡이 "구간 0개" 로 색인에 들어가거나 실패가 성공으로 보고된다.

### 곡 길이는 All-In-One 이 주지 않는다

마지막 구간의 끝은 곡 끝보다 앞일 수 있다(무음 꼬리). 색인의 `track_dur_sec` 은
`len(y)/24000` 이므로(원본 `m17_flow_ingest`) 여기서도 ②와 **같은 적재 함수**로 잰다.
이 값이 어긋나면 `rel_start`·`rel_end` 가 통째로 밀린다. 실측으로 색인과 일치했다
(T000001 176.576 · T000003 172.8).

### 재현 (2026-09-16 실측, GPU 박스)

기존 flow 8곡·67구간을 다시 분할해 `moments.parquet` 의 경계·라벨과 대조했다. **음원이
색인과 다른 5곡(`T000002`·`T000014`·`T000016`·`T000017`·`T000018`)은 제외**했다 — 앞
사이클에서 확인된 데이터 결함이고, 경계만 보면 멀쩡해 보인다.

| 조건 | 결과 |
|---|---|
| 원본 demucs·스펙트로그램 캐시를 그대로 쓸 때 | **경계·라벨 8/8 일치** (최대차 0.00e+00) |
| demucs 부터 다시 돌릴 때 | 7/8 정확 일치, 1곡에서 경계 한 개가 **0.01초**(한 프레임) |
| 라벨 | 67구간 **전부** 일치 (두 조건 모두) |

> **`keep_byproducts=True` 는 속도만의 문제가 아니다.** 차이는 분할기가 아니라 **demucs
> 분리에서 난다.** 같은 음원으로 만든 스펙트로그램의 해시가 달랐고(`T000007.npy`), 그
> 스펙트로그램을 쓰면 분할은 두 번 돌려도 같은 값을 냈다. 즉 분할 단계는 결정적이고,
> 비결정적인 것은 GPU 소스 분리다. 캐시를 지우면 같은 곡을 다시 분석할 때 경계가 한
> 프레임 흔들릴 수 있다.
>
> 신곡에는 맞출 기준이 없으므로 이 차이가 정확성 문제는 아니다. 문제가 되는 것은 **같은
> 곡을 다시 분석할 때**이고, 그때 캐시가 그것을 막아 준다.

### 성능·자원 (V100 32GB · 캐시 있음 기준)

| | 값 | |
|---|---:|---|
| All-In-One 모델 적재 | **7.35초** | 묶음당 1회. 고정비다 |
| 분할 (캐시 있음) | **2.32초/곡** | 8곡 25.9초 − 1곡 9.7초 로 분리 |
| 분할 (캐시 없음) | **13.2초/곡** | 8곡 105.3초. 차이 약 10.9초가 demucs·스펙트로그램 |
| MERT 적재 | 1.04초 | 같은 프로세스에 함께 올렸다 |
| 오디오 적재(24k) + 임베딩 | 0.45 + 0.37초 | 8구간 → (8, 1536) |
| **①+② 곡당 총** | **3.14초** | 고정비 8.4초는 별도 |
| GPU 최고 | 분할 615 MiB · MERT 506 MiB | 순차로 올라가므로 동시 최고는 615 MiB |

디스크는 곡당 demucs 4채널 wav + 스펙트로그램으로 **약 140 MB** 다(8곡 1.1 GB 실측).
현재 캐시는 demix 164 GB · spec 31 GB 로, 1,060곡을 이미 담고 있다.

CPU 경로는 앞서 곡당 654초로 재 두었다. 하루 배치에 맞지 않는다는 판단이 이미 났으므로
다시 재지 않았다.

### 시험

`ai_server/tests/test_segmenter.py`. GPU·음원·All-In-One 이 없으면 **건너뛴다** — CI 에는
셋 다 없다. GPU 없이도 도는 것은 스키마 검증(겹침·범위·어휘·판)과 **실패 경로**다.

```bash
# GPU 박스에서 진짜 분할까지 보려면
MUPID_SEG_TEST_AUDIO=~/flow_music_data/audio/T000004.mp3 \
  PYTHONPATH=<src> python -m pytest ai_server/tests/test_segmenter.py -q
```

`mupid-ai` 에는 pytest 가 없어 박스에서는 같은 내용을 스크립트로 확인했다. 그 환경에
pytest 를 넣지 않았다 — 색인을 만든 환경이라 손대지 않는 편이 낫다.

## 6.5 구간별 악기 (`analysis/instruments.py`)

`music_analysis_instruments` 는 곡이 아니라 **구간**에 매달린다 — 외래키가
`music_section_analysis_id` 다. 같은 곡이라도 인트로에는 건반만, 후렴에는 드럼·베이스가
들어간다.

### 새 모델을 붙이지 않는다

All-In-One 이 분할하면서 demucs 로 이미 스템을 나누고, `keep_byproducts=True` 라
그 파일이 작업 디렉터리에 남는다(`<work>/allin1/demix/<모델>/<곡>/…`). 곡당 GPU 시간을
한 번 더 쓰지 않고 그 결과를 읽기만 한다. 모델 폴더 이름은 박아 두지 않고 찾는다 —
demucs 모델을 바꾸면 그 이름도 바뀐다.

**demucs 는 4스템이다.** 피아노와 기타를 따로 가르지 못한다. 그래서 어휘가 넷뿐이고
넷째는 "나머지 전부" 다.

| 스템 | `codes.code_name` (`INSTRUMENT`) |
|---|---|
| `vocals` | 보컬 |
| `drums` | 드럼 |
| `bass` | 베이스 |
| `other` | 그 외 악기 |

### 판정

    들린다  ⇔  rms(스템, 구간) ≥ MIX_REL × rms(믹스, 구간)  그리고  ≥ ABS_FLOOR

스템 **자기 분포**와 견주지 않는다. 보컬이 없는 연주곡도 보컬 스템에 누설이 남는데,
그 스템 안에서만 비교하면 누설의 최댓값이 기준이 되어 **모든 구간에 보컬이 있다**고
답한다. 믹스와 견주면 누설은 작은 비율로 남는다. `ABS_FLOOR` 는 수치적 무음을 거르는
바닥이다 — 구간이 통째로 조용하면 믹스도 작아서 비율만으로는 아무거나 통과한다.

**이 문턱값은 아직 실측으로 맞춘 것이 아니다.** 실제 스템 분포를 본 적이 없다.
지금 값은 -20 dB(`MIX_REL = 0.1`) 로, "제대로 들어간 파트는 믹스의 -20 dB 안쪽,
누설은 그 아래" 라는 통상적인 관찰에서 잡은 출발점이다. 첫 실주행에서 아래 표를 보고
고친다 — `section_levels()` 가 판정이 아니라 **비율 자체**를 낸다.

```bash
python -m music_preprocessing.analysis.instruments   --audio work/1234.audio --sections work/1234.sections.json   --demix work/allin1/demix
```

### 코드가 구간보다 **먼저**다

`CodeType.INSTRUMENT` 는 부트스트랩이 심지 않는다 — 확정된 목록이 없어서다. 그리고
서버는 코드표에 없는 악기 이름을 **버리고 센다**(구간은 살린다). 순서가 뒤집히면
오류 없이 악기만 조용히 빈다. 그래서 `HttpRegistrar.register()` 가 구간을 올리기 전에
`POST /api/v1/code` 로 필요한 것을 먼저 넣는다. 멱등이라 매 배치가 다시 불러도 같다.

### 못 재도 곡은 산다

악기는 곁들이는 값이고, 색인과 구간 적재는 그것 없이도 온전하다. 스템을 못 찾으면
그 곡의 `instruments` 만 비우고 `TrackResult.instrument_error` 에 이유를 남긴다 —
조용히 빈 목록으로 넘기지는 않는다. 그러면 "악기가 없는 곡" 과 "재지 못했다" 가
같아 보인다.

### 시험

`ai_server/tests/test_section_instruments.py`. 음원도 demucs 도 없이 돈다 — 파형을
numpy 로 만들고 적재 함수를 끼운다. 여기서 보는 것은 신호 처리가 아니라 **연결**이다:
구간마다 답이 다른가, 코드가 구간보다 먼저 나가는가, 못 재도 곡이 사는가. 마지막 둘은
진짜 HTTP 서버를 띄워 확인한다.

## 7. 실행 위치 · 운영 입출력 · 설정

### 7.1 어디서 도는가 — **분업**으로 간다

제약이 둘이다. `allin1`(natten)은 **파이썬 3.10** 에 묶여 있고, GPU 박스의 `mupid-ai`
에는 **pandas 가 없다**(⑤⑥이 쓴다). 그 환경은 색인을 만든 환경이라 함부로 설치하지
않는다.

먼저 3.14 에서 필요한 휠이 실제로 깔리는지 확인했다(2026-09-16, 리눅스 박스 · uv).

```
python 3.14.3   numpy 2.5.3 · scipy 1.18.1 · pandas 3.0.5 · pyarrow 25.0.1 · librosa 1.0.0
```

전부 설치된다. 그래서 **분업**으로 간다.

| 단계 | 어디서 | 왜 |
|---|---|---|
| ① 분할 · ② 임베딩 | GPU 박스 `mupid-ai` (3.10) | allin1·MERT 가 이미 같이 있다. 색인을 만든 환경 그대로다 |
| ③④⑤⑥ | 색인을 마운트한 3.14 프로세스 | pandas·pyarrow 가 필요하고 3.14 에서 다 깔린다 |

**`mupid-ai` 에 아무것도 설치하지 않는다.** 배포 머신에서 GPU 박스로 SSH 가 열려 있어
①②를 원격으로 부르고, 결과(`sections.json` + 배열)만 받아 온다. `Segmenter(python=…)`
가 그 자리다 — 같은 `analyze()` 를 3.10 인터프리터에서 부르고 JSON 만 돌려받는다.

> **한 박스로 몰지 않은 이유.** `mupid-ai` 에 pandas 를 넣으면 단계는 줄지만, 색인
> 6,767구간과 동결 통계를 만든 환경의 의존성 그래프가 바뀐다. 재현이 필요해졌을 때
> "그때와 같은 환경" 이 더는 없다. 얻는 것(단계 하나)보다 잃는 것이 크다.

### 7.2 운영 입력·출력 (`analysis/api.py`)

```
요구 1  PUT   /api/v1/music/{id}/section            구간 적재 + 숫자 ID 회수     [구현됨]
        POST  /api/v1/code                          악기 코드 확보 (멱등)        [구현됨]
요구 5  GET   /internal/music/pending-analysis      무엇을 분석할까 (limit·cursor 전량 순회)
요구 6  PATCH /internal/music/{id}/analysis-status  PROCESSING · FAILED
```

`POST /api/v1/code` 도 `@SystemRole` 이라 같은 계정으로 부른다. 구간을 올리기 **전에**
악기 코드를 확보하는 용도다 — 6.5 참고.

**요구 1 은 2026-09-16 에 구현됐다.** 제안과 달라진 것이 둘이다 — 경로가 `/internal/*`
이 아니고, 인증이 내부 토큰이 아니라 **ROLE_SYSTEM 계정의 `Authorization: Bearer`** 다.
그래서 이 호출만 저장소의 `MupidClient.replace_sections()` 를 쓴다. 리프레시 토큰으로
액세스 토큰을 그때그때 받고 회전까지 그쪽이 맡는다 — 토큰 다루는 곳을 둘로 늘리면
회전된 값이 갈린다.

확정 계약에는 `audio_sha256` 도 `allow_section_change` 도 **없다.** 곡 단위 통째 교체가
기본이고, 같은 음원을 다시 분석하지 않는 일은 배치가 SHA-256 으로 앞단에서 끝낸다.

**요구 5·6 은 아직 Spring 에 없다.** 제안 형태(`/internal/*` + `X-Internal-Token`)로
적어 두었고 `urllib` 만 쓴다. 요구 1 이 일반 경로로 확정된 것을 보면 **이 둘도 같은
형태로 바뀔 가능성이 높다** — BE 확정이 나면 맞춘다. 서버가 없으면 배치는 **교체를
보류**하고 끝난다(`exit 20`). 가짜로 성공 처리하지 않는다.

| | 규칙 |
|---|---|
| 음원 | `audio_download_url` 로 받고 **`audio_sha256` 를 검증**한다. 어긋나면 파일을 지우고 그 곡만 실패 |
| 임시 음원 | 곡 처리 뒤 지운다(`temp_audio`). 1,000곡이면 수 GB 가 남는다 |
| 재시도 | **`500`·`503`·timeout 에만.** `400`·`401`·`403`·`404`·`409` 는 다시 불러도 같다. 요구 5·6 은 1회, 요구 1 은 `MupidClient` 의 정책을 따른다 |
| 상태 | 분석 **전에** `PROCESSING`, 실패 시 `FAILED` + 짧은 코드. **`DONE` 은 보내지 않는다** — 요구 1 의 적재 성공이 그 자리를 맡는다 |
| 비밀 | 토큰과 다운로드 URL 을 로그·예외 메시지에 싣지 않는다. 토큰은 **파일로만** 받는다 — 요구 1 은 `api_refresh_token_file`, 요구 5·6 은 `analysis_internal_token_file` |

**곡 ID 는 `music_id` 문자열이다.** 구간은 `<music_id>#<index>` 이고 `external_id` 와
`moment_id` 가 같은 모양이다. 그래서 색인에는 `JAMENDO_1083659` 같은 기존 ID 와 `1001`
같은 새 ID 가 **섞인다.** 색인은 문자열 키로만 다루고 숫자 환산은 `id_map` 한 곳에서만
일어나므로 동작에는 문제가 없다 — 다만 **ID 만 보고 출처를 짐작할 수 없게 된다.**

`flow_meta` 의 태그는 배열이 아니라 **쉼표로 이은 문자열**이다(`recommender._tag_list`).
요구 5 는 배열로 주므로 배치가 이어서 넣는다. `lyrics_types` 만 리스트다.

### 7.3 `trk_bpm` 은 **측정값**이다 — 배치가 직접 잰다

요구 5 는 `bpm_requested` 만 준다. 그것을 285차의 `trk_bpm` 에 넣으면 **신곡만 다른
분포에 놓인다.**

| | 평균 | 표준편차 |
|---|---:|---:|
| 동결 통계 `trk_bpm` | 109.644 | 43.291 |
| `content.bpm` (측정, 전체) | 108.963 | 43.172 |
| 요청 BPM 중앙값 (flow 763곡) | 111.0 | **25.6** |

동결 통계는 **측정 BPM 의 분포**다. 요청값은 폭이 절반이라 z-score 가 중앙으로 몰린다.
flow 763곡 중 **246곡**은 측정값이 요청 범위 밖이고(평균 절대차 15.4 BPM), `T000002` 는
요청 114~122 인데 측정 57.4 — 옥타브 오류다. 그러나 **그 오류까지 포함한 분포가 색인**
이고, 신곡을 같은 공간에 놓으려면 같은 방법으로 재야 한다.

그래서 배치가 `~/rec_mupid/src/m02_content.py` 를 그대로 옮긴 `analysis/content.py` 로
**직접 잰다**(중앙 90초 · `|stft(2048, 512)|` · 스펙트럼 플럭스 자기상관 50~190).
요구 5 가 주는 `centroid`·`rolloff`·`rms_mean`·`flatness` 도 같은 이유로 직접 잰다 —
BE 가 다른 파라미터로 계산하면 조용히 어긋난다. 곡당 1초 미만이다.

`bpm_requested` 는 **다른 자리**에서 쓴다 — `flow_meta.bpm_lo`·`bpm_hi` 와 추천기의
BPM 필터다. 거기서는 요청값이 맞다. 두 자리를 섞지 않는다.

### 7.4 설정은 `config.json` 필드로 들어간다

배포 규약이 그렇다 — 서버 설정은 `config.json` 필드를 대문자로 쓴 이름만 받고, **모르는
이름이면 배포가 멈춘다**(`deploy.env.example`). 이름 공간 밖의 환경변수에만 기대면
배포로 값을 넣을 수 없다.

| 필드 | 기본값 | |
|---|---|---|
| `analysis_enabled` | `false` | **기본 꺼짐.** 청킹만 쓰는 쪽의 동작이 달라지지 않는다 |
| `analysis_artifact_dir` | `""` | 비우면 `$MUPID_AI_ARTIFACT_DIR` |
| `analysis_work_dir` | `./analysis_work` | 음원·`sections.json`·demucs 캐시 |
| `analysis_internal_token_file` | `./ai_internal_token.txt` | **값이 아니라 파일 경로** |
| `analysis_version` | `v1` | 요구 5·6·1 에 그대로 실린다 |
| `analysis_limit` | `200` | 요구 5 한 페이지 |
| `analysis_device` | `""` | `cuda:0` · `cpu` |
| `analysis_segmenter_python` | `""` | 3.10 실행 파일. 비우면 같은 프로세스 |
| `analysis_keep_versions` | `3` | 롤백용 이전 판 수 |

`api_base_url` 은 청킹 배치와 **함께 쓴다.** 같은 Spring 이다.

### 7.5 "배치" 가 둘이라 이름을 가른다

upstream 에 이미 `music_preprocessing/batch.py`(**청킹**)가 있고 `run_batch.sh` 가 그것을
돌린다. 구간 분석은 **다른 배치**다.

| | 청킹 | 구간 분석 |
|---|---|---|
| 명령 | `music-preprocessing batch` | `python -m music_preprocessing.analysis.batch` |
| 모듈 | `music_preprocessing/batch.py` | `music_preprocessing/analysis/batch.py` |
| 진입점 | `run_batch.sh` 안 | **독립 도구** |
| 장치 | CPU·ffmpeg | GPU(①②) + CPU(③④⑤⑥) |

**`run_batch.sh` 에 끼우지 않았다.** 장치·환경·소요 시간이 다르고, 분석이 실패했다고
청킹까지 멈출 이유가 없다. `genre_mood.py` 가 "기존 흐름을 건드리지 않는 독립 도구" 의
본보기다 — 그리고 upstream 파일을 고치지 않으므로 병합 충돌도 생기지 않는다.

### 7.6 실행

```bash
# 산출물에 feature_stats_v1.npz 가 없으면 먼저 만든다
MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.freeze_feature_stats
```

```bash
# 운영 — 요구 5 에서 대상을 받는다
python -m music_preprocessing.analysis.batch --config config.json

# 로컬 — 배치 서버에서 `~/flow_music_data` 를 직접 훑는다 (요구 5 를 부르지 않는다)
#   `--segmenter-python` 은 **필수다.** ①·②③④ 가 그 3.10 환경에서 돈다.
MUPID_AI_ARTIFACT_DIR=$HOME/rec_mupid \
  uv run music-preprocessing analysis -c config.json \
    --local --segmenter-python "$HOME/.conda/envs/mupid-ai/bin/python"

# 손으로 — 대상 곡 목록을 파일로 준다 (등록기 없이 돌면 교체는 보류된다)
python -m music_preprocessing.analysis.batch manifest.json --data-dir /경로
```

`manifest.json` 은 곡마다 `track_id` · `audio_path` · `sections_json` · `content` ·
`meta` 를 담는다.

#### `--local` 로 돌릴 때 실측한 함정 (2026-09-18)

전부 **오류 없이 지나가거나 단계가 통째로 죽는** 것들이었다. 순서대로 밟는다.

| 함정 | 증상 | 대응 |
|---|---|---|
| `MUPID_AI_ARTIFACT_DIR` 미설정 | `analysis_artifact_dir · MUPID_AI_ARTIFACT_DIR · --data-dir 중 하나가 필요하다` | env 로 준다. `config.json` 의 `analysis_artifact_dir` 이 비어 있을 수 있다. `run_batch.sh` 도 맨 앞에서 export 한다 |
| `--segmenter-python` 없음 | ②③④ 진입에서 부모(3.12)가 torch 를 못 찾아 죽는다 | 3.10 인터프리터를 준다. **비어 있으면 배치가 미리 멈춘다**(조용히 도는 대신) |
| `analysis_limit` 이 `--local` 에 샘 | 299곡이 200곡으로 **조용히 잘린다** | `--local` 은 자르지 않는다. `--limit` 을 줘야 자른다 |
| ① 원본과 변환본을 같은 디렉터리로 | 변환본이 All-In-One 원본을 덮어씀 → 다음 실행 때 전곡 GPU 재분할 | `--allin1-dir`(`<artifact>/flow/allin1`)과 `--sections-dir`(`<work>/sections`)를 나눈다 |
| `analysis` 에 `--yes` 없음 | `No such option: --yes` — **`run_batch.sh` 가 이 단계에서 죽는다** | CLI 가 받는다 |
| 태깅 산출물 경로 오인 | 태그가 조용히 빈다(오류 없음) | `<artifact>/data/test_pipeline/tracks.parquet` — `flow/allin1` 에서 **두 단계** 위다 |

#### 태그를 **먼저** 채운다

`registry.tags()` 가 ⑤ 색인을 만들 때 태그를 읽는다. 태그 없이 색인을 만들면 전곡이
빈 태그로 고정되고 ⑤⑥ 을 한 번 더 돌려야 한다.

```bash
# 색인에 없는 곡의 태그를 t01 로 채운다 (envs/rec · Essentia 워커는 t01 이 띄운다)
~/envs/rec/bin/python -c "import sys;sys.path.insert(0,'$HOME/rec_mupid/src');\
import t01_pipeline as t01;t01.run([...], force=False)"
```

`mood_tags` 는 **확신도(confident) 미달이면 정상적으로 빈다.** 기존 761곡도
534/640(≈83%)만 채워져 있다 — 신곡만 다른 분포에 놓이지 않는다.

### 7.7 필요한 산출물

| 파일 | 없으면 |
|---|---|
| `moment_zstats.npz` | **멈춘다.** 새로 계산하지 않는다 — 신곡만 다른 공간에 놓인다 |
| `feature_stats_v1.npz` | **멈춘다.** 같은 이유 |
| `features_info.json` | **멈춘다.** 판·sha256 을 대조할 근거가 없다 |
| `features_v1.npz` | PCA 기저 |
| `stage1_weights.npz` | ④ 를 못 한다 |

**운영 조치 기록 (2026-09-18).** 운영 색인(`$MUPID_AI_ARTIFACT_DIR/data/proc/mupid`)에
`feature_stats_v1.npz` 가 없어 ⑤⑥ 이 멈췄다. 정본 도구로 만들어 넣었고, 그 값이
마스터본(`~/mupid_port_verify/artifacts/data/proc/mupid/`)과 `max|diff|=0.00e+00` 로
일치하는 것을 확인한 뒤 설치했다.

```bash
# 1. 먼저 빈 곳에 만들어 기존 값과 대조한다 (색인을 건드리지 않는다)
MUPID_AI_ARTIFACT_DIR=$HOME/rec_mupid PYTHONPATH=$HOME/mupid_ai_project_dev \
  $HOME/envs/mupid-ai-project-dev/bin/python -m ai_server.tools.freeze_feature_stats \
    --out /tmp/stats_probe.npz --force
#    → trk_bpm mean 109.644135 · std 43.291216 (문서의 동결 통계와 일치)

# 2. 일치하면 색인에 넣는다. 다른 색인 파일은 건드리지 않는다.
cp /tmp/stats_probe.npz $HOME/rec_mupid/data/proc/mupid/feature_stats_v1.npz
```

이 파일은 `indexer.CARRY_FILES` 에 있어 **색인 교체 시 새 판으로 함께 복사된다**
(`stage()`). 그래서 한 번 넣으면 이후 판에도 따라간다.


## 8. CronJob · 재기동

### 어디서 도는가

지금은 **두 단계로 갈린다.** GPU 가 필요한 ①②(분할·임베딩)는 GPU 박스에서, 나머지는
CPU 에서 돈다. K8s 에 GPU 노드가 생기면 한 Job 으로 합칠 수 있다.

| 단계 | 장치 | 지금 |
|---|---|---|
| ① 분할 | GPU (CPU 654초 vs GPU 3.1초) | GPU 박스. 산출은 `sections.json` |
| ② 임베딩 | GPU 권장 | GPU 박스. 산출물이 `model.half()` 로 만들어졌다 |
| ③④⑤⑥ | CPU | 배치 프로세스 |

### 주기와 동시 실행

```yaml
schedule: "30 4 * * *"        # 새벽 4시 30분 (Asia/Seoul)
concurrencyPolicy: Forbid     # 겹치면 건너뛴다
successfulJobsHistoryLimit: 3
backoffLimit: 0               # 재시도는 다음 날 배치가 한다
```

`Forbid` 와 파일 잠금은 **둘 다** 있어야 한다. `Forbid` 는 같은 CronJob 의 겹침만 막고,
사람이 손으로 돌리는 것은 못 막는다.

### 교체 방식 — 환경변수 전환을 기본으로 한다

`swap()` 은 rename 이 둘(`mupid → prev`, `v… → mupid`)이라 그 사이에 기동한 프로세스는
색인 디렉터리를 못 찾아 **기동에 실패한다.** K8s 에서는 그럴 필요가 없다.

```
1. 배치가 새 판을 만든다            …/data/proc/mupid_v20260916-043012
2. 등록·확인이 끝난다
3. MUPID_AI_ARTIFACT_DIR 를 새 판으로 바꾼다 (ConfigMap)
4. kubectl rollout restart deploy/ai-server
```

rename 이 없어지고 **롤백은 환경변수를 되돌리는 일**이 된다. Windows 에서 symlink 가
권한을 요구하는 문제도 함께 사라진다. 이전 판은 3판 유지한다.

**재기동은 빼놓을 수 없다.** 서버는 기동할 때 `Catalog` 으로 색인을 통째로 메모리에
올리고 워커는 하나다. 파일만 바꾸면 돌고 있는 프로세스가 옛 색인을 계속 쓴다 — 오류는
나지 않고 새 곡만 추천에 안 나온다.

### 실패했을 때 무엇이 남는가

| 상황 | 남는 것 | 지우는 것 | 다음 배치 |
|---|---|---|---|
| 곡 하나 실패 | 요약의 `failures` | — | 그 곡부터 다시 |
| 게이트 실패(20) | 새 판 · 요약 | — | 새 판을 사람이 보고 지운다 |
| 등록 보류(20) | **새 판을 남긴다** | — | 등록이 붙으면 이어서 쓴다 |
| 교체 뒤 죽음 | `_prev` 판 | — | `rollback()` 으로 되돌린다 |
| 잠금 충돌(30) | 아무것도 안 함 | — | 그대로 다시 |

처리 기록(`analysis_processed.json`)은 **곡마다** 저장한다. 중간에 죽어도 거기까지는
남고, 임시 파일에 쓰고 바꿔 끼우므로 반쪽짜리 JSON 이 생기지 않는다.

### 같이 고친 것 — `push_sections` 의 `created_at` 타임존

배치가 이 도구를 부르므로 이번에 고쳤다. 예전에는
`pd.Timestamp.now("UTC").strftime(...)` 로 **타임존 표시 없는 UTC 문자열**을 넣었는데,
ClickHouse 서버 타임존이 `Asia/Seoul` 이라 **9시간 과거로 저장된다.**

`created_at` 은 `ReplacingMergeTree` 의 **버전 컬럼**이다. 이 도구가 쓴 행(9시간 과거)과
`DEFAULT now()` 로 들어간 행이 섞이면 항상 후자가 이겨, 이 도구로 고친 구간이 조용히
옛 값으로 되돌아간다.

```
SELECT timezone(), toString(now()), toString(now('UTC'))
Asia/Seoul   2026-09-16 12:06:49   2026-09-16 03:06:49     ← 9시간
```

이제 `server_now()` 로 **ClickHouse 에 물어서** 얻는다. 로컬 시계를 쓰지 않는다. 값을
비우면 아예 싣지 않아 `DEFAULT now()` 가 채운다 — 어느 쪽이든 서버 시계다.

확인은 두 가지로 한다. 하나는 서버에 물어 두 값의 차이를 보는 것(위), 다른 하나는
오프라인 시험(`test_contract_offline.py`)이다. 시험은 **`build_rows` 의 소스에
`Timestamp.now` 가 없는지**까지 본다 — 로컬 시계로 되돌아가도 값 검사만으로는 다 통과하기
때문이다.

## 9. 재현 확인 (2026-09-16 실측)

기존 flow 5곡 · 43구간을 다시 계산해 산출물과 대조했다.

| 단계 | GPU fp16 (판정) | CPU fp32 (참고) | 기준 |
|---|---|---|---|
| 임베딩 1536 | **0.000e+00** | 1.687e-04 | < 1e-6 |
| 특징 285 | **0.000e+00** | 2.103e-04 | < 1e-5 |
| 구간벡터 128 | **1.043e-07** | 1.241e-04 | < 1e-6 |

**판정은 GPU fp16 으로 한다.** 산출물이 `model.half()` 로 만들어졌기 때문이다. CPU
float32 는 더 정확하지만 **다른 값**이라 저장본 기준으로는 1e-4 대가 정상이다. 처음에
CPU 로 판정하려 했으나 그 전제가 성립하지 않는다는 것이 실측으로 드러났다.

시험은 `ai_server/tests/test_analysis_pipeline.py` 이고, 산출물·음원·torch 가 있을 때만
돈다. GPU 가 없으면 느슨한 기준(1e-3)으로 구조만 본다 — 그 통과는 재현 확인이 아니다.

## 10. 외부 의존 — **Spring 에 남은 요청**

클라이언트는 다 붙였다(`analysis/api.py`, §7.2). **요구 1 은 구현됐고 둘이 남았다.**
그동안 배치는 새 판을 만들어 두고 `exit 20`(교체 보류)으로 끝난다.

| 계약 | 하는 일 | 없으면 |
|---|---|---|
| `요구 1 · 구간 분석 결과 적재` | **구현됨 (2026-09-16)** — 구간 저장 + `music_section_analysis_id` 반환 | — |
| `요구 5 · 분석 대상 곡 조회` | 무엇을 분석할지 · 음원 URL · `audio_sha256` | 배치가 새 곡을 알 수 없다. `manifest.json` 으로만 돈다 |
| `요구 6 · 곡 분석 상태 갱신` | `PROCESSING` · `FAILED` 기록 | 같은 곡을 두 배치가 동시에 집고, 실패 이유가 다음 실행에 남지 않는다 |

**BE 에 물을 것:** 요구 5·6 도 요구 1 처럼 일반 경로 + `Authorization: Bearer` 로 갈
것인가. 그렇다면 `analysis/api.py` 의 `InternalApi` 를 걷어내고 요구 1 과 같은
`MupidClient` 하나로 합친다.

**ERD 쪽에서 필요한 것**(AI 가 고치지 않았다 — `infra/AI-API-BLOCKERS.md` 와 함께 본다):

- `music.analysis_status` (`PENDING`·`PROCESSING`·`DONE`·`FAILED`)
- `music.audio_sha256` — 배치의 중복 판정 근거. **이 값이 없으면 곡당 수 MB 를 매일 다시
  받아야 해시를 알 수 있다**
- `music_section_analysis.external_id` 는 `VARCHAR(64)` 이상. `<music_id>#<index>` 가
  들어간다

라벨 어휘는 이미 맞는다 — 추천 풀 10,007구간 전수에서 ERD ENUM 과 어긋나는 값이 없고,
대문자로 바꾸기만 하면 된다. `start`·`end` 표시와 3초 미만 구간은 버린다.

`요구 5` 가 트랙 특징(`centroid` 등)을 함께 주지만 **배치는 직접 잰다**(§7.3). 서버가
그 값을 못 주더라도 배치는 돈다 — 필수는 `music_id`·`audio_download_url`·
`audio_sha256`·`bpm_requested` 와 조건 검색용 태그다.

**인프라 쪽에 남은 것**: 색인 디렉터리를 배치가 **쓸 수 있게** 마운트해야 한다(서버는
읽기 전용이면 충분하다). 교체 중에는 디스크가 두 벌(약 1 GB) 필요하다.

## 11. 남은 것

| 남은 것 | 막혀 있는 이유 |
|---|---|
| 요구 5·6·1 **구현** | BE 소관. 클라이언트와 가짜 서버 시험은 준비돼 있다 |
| ClickHouse 자동 동기 | 등록기 안에서 `push_sections` 를 부르면 된다. 도구는 준비돼 있다 |
| 음원이 바뀐 곡 | 5곡. append 경로가 아니라 기존 행 교체라 성격이 다르다 (§4) |
| ①②를 원격으로 부르는 배선 | 분업으로 정했다(§7.1). SSH 호출 자리는 `Segmenter(python=…)` 이고, 배포 머신 ↔ GPU 박스 배선은 아직 없다 |
| CronJob 배포 | 지금은 GitLab 스케줄이 "언제" 를 정한다. K8s 규격은 §8 |
