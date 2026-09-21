# AI 입출력·계산·모델 형태 (실측)

**문서에서 베끼지 않았다.** 서버를 띄워 실제로 받은 JSON 과, 산출물 파일을 열어 읽은
배열 모양이다.

| | |
|---|---|
| 채취 | 2026-09-21 |
| 브랜치 | `ai/S15P21A204-213-batch-hardening` = `origin/develop` `cfd5fa7` |
| 서버 | `python -m ai_server.tools.serve --host 127.0.0.1 --port 8123 --artifacts ~/ssafy/mupid-ai-artifacts` |
| 색인 | 65,540행 · 1,063곡 · 10,007순간 · `model_backend: numpy` |

---

## 1. 온라인 API — 실제 주고받은 JSON

### 1.1 `GET /health`

```json
{"status":"ok","uptime_seconds":5.0,
 "artifact_dir":"C:\\Users\\SSAFY\\ssafy\\mupid-ai-artifacts",
 "paths":{"artifact_dir":"…","artifact_dir_from_env":true,
          "data_dir":"…\\data\\proc\\mupid","state_dir":"…\\data\\proc\\mupid",
          "state_dir_split":false,"data_dir_exists":true,
          "moments":{"path":"…\\moments.parquet","exists":true},
          "id_map":{"path":"…\\id_map.parquet","exists":false,"overridden":false}},
 "catalog_loaded":true,"model_backend":"numpy","model_error":null,
 "load_seconds":0.235,"error":null,
 "catalog":{"tracks":1063,"moments":10007,
            "by_source":{"flow":6767,"jamendo":3240},"empty_sources":[]},
 "integrity":{"rows":{"moments":65540,"moments_emb":65540,
                      "features_v1":65540,"stage1_section":65540},
              "emb_row_contiguous":true,"duplicate_moment_id":0},
 "artifacts":{"files":{"logistic_flow_stage1.json":{"exists":true,"why":"…"}, …}}}
```

### 1.2 `GET /internal/ready` → 200

```json
{"ready":true,"tracks":1063,"moments":10007,
 "behavior_store":{"ok":true,"configured":false},
 "paths":{…}}
```

`id_map.parquet` 이 없어도 200 이다. 숫자 번역은 Spring 이 한다.

### 1.3 `POST /internal/recommendations/daily`

요청 — **`limit` 을 보내면 거절당한다.**

```json
{"recommendation_id":9001,"user_id":15}
```

```json
{"recommendation_id":9001,"user_id":15,"limit":3}
→ 400 {"error_code":"INVALID_REQUEST",
       "message":"(본문): Value error, 오늘의 모멘트는 4개로 고정이다 —
                  limit 을 빼고 보내거나 4 로 보낸다 (개수를 바꾸려면 next·search 를 쓴다)"}
```

응답 (실제)

```json
{"recommendation_id":9001,
 "items":[
  {"external_id":"JAMENDO_1440522#5","rank_order":1,"score":1.0,
   "reason_code":"COLD_START","ref_external_id":null},
  {"external_id":"JAMENDO_1291146#5","rank_order":2,"score":0.999899,
   "reason_code":"COLD_START","ref_external_id":null},
  {"external_id":"JAMENDO_1215804#3","rank_order":3,"score":0.999799,
   "reason_code":"COLD_START","ref_external_id":null},
  {"external_id":"JAMENDO_1182292#3","rank_order":4,"score":0.997987,
   "reason_code":"EXPLORE_NEW","ref_external_id":null}],
 "shortfall":0,"model_version":"mupid-stage1-v1"}
```

온보딩을 실으면 — 이력 없는 사용자

```json
요청 {"recommendation_id":9004,"user_id":987654,
      "onboarding":{"moods":["calm"],"genres":["Ballad"]}}

응답 items[0] =
  {"external_id":"T000018#2","rank_order":1,"score":1.0,
   "reason_code":"ONBOARDING_MATCH",
   "ref_external_id":"20260901T044346Z_Quiet_Light_take2#1"}
```

### 1.4 `POST /internal/recommendations/next`

```json
요청 {"recommendation_id":9002,"user_id":15,"limit":3,
      "current_track":"JAMENDO_1440522","current_section":"JAMENDO_1440522#5"}

응답 {"recommendation_id":9002,
      "items":[{"external_id":"JAMENDO_1440522#5","rank_order":1,"score":1.0,
                "reason_code":"COLD_START","ref_external_id":null},
               {"external_id":"JAMENDO_1215804#3","rank_order":2,"score":0.999799, …},
               {"external_id":"JAMENDO_1141571#2","rank_order":3,"score":0.996075,
                "reason_code":"EXPLORE_NEW", …}],
      "shortfall":0,"model_version":"mupid-stage1-v1"}
```

> **지금 듣고 있는 그 순간이 1위로 돌아왔다.** §5-B 참조.

### 1.5 `POST /internal/similar`

```json
요청 {"external_id":"JAMENDO_1440522#5","limit":3}

응답 {"external_id":"JAMENDO_1440522#5",
      "items":[{"external_id":"JAMENDO_1916494#7","rank_order":1,"similarity":0.621845},
               {"external_id":"JAMENDO_2038511#0","rank_order":2,"similarity":0.589674},
               {"external_id":"JAMENDO_1174819#0","rank_order":3,"similarity":0.587072}],
      "shortfall":0,"embedding_version":"mert-v1-95m"}
```

추천과 필드가 다르다 — `score` 가 아니라 `similarity`, `reason_code` 없음,
`model_version` 대신 `embedding_version`.

### 1.6 `POST /internal/recommendations/search`

```json
query_plan  {"recommendation_id":9003,"user_id":15,"limit":3,
             "query_plan":{"filters":{"mood":["calm"],"genre":["Ballad"]}}}
         → 404 {"error_code":"NO_CANDIDATES","message":"해당되는 노래가 없습니다."}

query      {"recommendation_id":1,"user_id":15,"limit":3,"query":"calm piano"}
         → 501 {"error_code":"SEARCH_NOT_CONFIGURED",
                "message":"자연어 해석기가 설정되지 않았습니다. MUPID_AI_NLP_URL 을
                           지정하거나 query_plan 을 보내세요. (S15P21A204-219)"}
```

`filters` 안의 축은 **AND** 다. `mood=calm` 과 `genre=Ballad` 를 함께 걸면 후보가 0 이
된다(위가 그 예다). 성공하면 `reason_code` 가 `QUERY_FILTER_MATCH`,
CLAP 임베딩이 실리면 `QUERY_SEMANTIC_MATCH` 다.

### 1.7 오류 형태 — 세 가지가 전부 같은 모양이다

```json
400 {"error_code":"INVALID_REQUEST","message":"external_id: String should have at least 1 character"}
404 {"error_code":"NO_CANDIDATES","message":"닮은 구간을 찾지 못했습니다."}
501 {"error_code":"SEARCH_NOT_CONFIGURED","message":"…"}
```

`error_code` + `message` 두 필드다. **단, 예상하지 못한 500 은 이 모양이 아니다** —
일반 예외 핸들러가 없다(§5-E).

### 1.8 `GET /internal/facets` · `GET /internal/metrics`

```json
facets  {"catalog":{"tracks":1063,"moments":10007},
         "fields":{"genre":{"enforcement":"gate","excludable":true,
                            "values":[{"value":"K-pop","tracks":87},
                                      {"value":"Ballad","tracks":69}, …]}, …}}

metrics {"started_at":1789608227.837,"uptime_seconds":9084.7,"sample_window":1000,
         "requests":{"total":9,"by_path":{"/internal/recommendations/daily":2, …}},
         "errors":{"total":2,"by_code":{"HTTP_400":1,"HTTP_501":1},"rate":0.2222},
         "latency_seconds":{…}}
```

---

## 2. 서빙 계산 — 입력에서 `score` 까지

```
① 질의 벡터 C        DNA(라벨) → 없으면 온보딩 씨앗 → 없으면 없음
② rule_sim  s_rule   max_j cos(center(Z[i]), C[j])        구간 벡터 128차
③ model_sim s_model  Zs[i] · enc_user(history)            없으면 0
④ prob              로지스틱 (아래 §3.4)
⑤ final             prob                      (SELECT_MODE="logistic")
                    0.5·pct(s_rule) + 0.5·pct(s_model)   (폴백, 모델 있을 때)
                    pct(s_rule)                           (폴백, 모델 없을 때)
⑥ soft_rerank       final + 0.25·CLAP pct + 0.25·mood 일치비율
⑦ + bdelta          행동 가점·감점 (완주·LIKE·스킵·최근재생·이어듣기)
⑧ 게이트            BPM |log2(bpm/pref)| ≤ 0.40 · 온보딩 명시 게이트
                    확률 상위 5% (P_MODE="quantile", P_TOP_FRAC=0.05)
⑨ 다양성            near-dup cos ≥ 0.95 컷 · MMR λ=0.8 · 곡당 1순간
⑩ 탐색 슬롯         마지막 1칸, 시드 = int(time.time())//60
⑪ 응답 score        final_score 를 소수 6자리 반올림
```

`pct(v)` 는 순위 백분위다 — `argsort(argsort(v)) / (n-1)`. 그래서 **`score` 는 확률이
아니라 후보 안에서의 상대 위치**이고, 1위가 항상 1.0 근처다. 실제 응답의
`1.0 / 0.999899 / 0.999799` 가 그 모양이다.

응답에 싣지 않는 내부 값: `moment_similarity` · `model_score` · `clap_score` ·
`prob` · `matched_dna` · `reason`(사람이 읽는 문장). Spring 에 노출되는 것은
`external_id` · `rank_order` · `score` · `reason_code` · `ref_external_id` 뿐이다.

---

## 3. 모델 형태 (실측 shape)

### 3.1 MERT 임베딩

```
입력   24kHz mono, 20초 청크(꼬리는 1초로 패딩)
모델   m-a-p/MERT-v1-95M  rev 12af15fef9d0ac838c3f475bfbbf26d2060dd4f5
계산   13개 hidden_states 평균 → 시간축 concat(mean, std) = 768 + 768
출력   1536차 float16          moments_emb.npy  (65540, 1536)
정규화 moment_zstats.npz  mu (1536,) float32 · sd (1536,) float32
       V' = (V - mu)/sd  후 행 L2
```

### 3.2 특징 공간 285차

```
features_v1.npz
  X          float32 (65540, 285)
  moment_id  <U80    (65540,)
  pca_mean   float32 (1536,)
  pca_comp   float32 (1536, 256)
  side_cols  <U24    (29,)
  evr        float32 0.8469   (설명 분산비)

X = concat([ L2((Z - pca_mean) @ pca_comp) , S ])      256 + 29 = 285
```

side 29 (`features_info.json` 그대로)

```
rel_start rel_end rel_center log_dur rms_pct rms_z
pos0..pos9                                   (10개, 곡 내 위치 구간)
lab_intro lab_verse lab_chorus lab_bridge lab_inst lab_solo lab_outro lab_other
trk_bpm trk_centroid trk_rolloff trk_rms_mean trk_flatness
```

동결 z-score 통계 — `feature_stats_v1.npz`

```
keys (6,) · mean (6,) float64 · std (6,) float64 · side_cols (29,) · n_moments 65540
```

### 3.3 Stage 1 인코더 — `stage1_weights.npz`

```
enc0_w float32 (192, 285)     enc0_b (192,)
ln_w   float32 (192,)         ln_b   (192,)
enc1_w float32 (128, 192)     enc1_b (128,)
att0_w float32 (64, 128)      att0_b (64,)
att1_w float32 (1, 64)        att1_b (1,)
gmean  float32 (128,)
in_dim 285 · hist_max · half_life_days · feature_version
```

계산

```
enc(x)      = enc1( LayerNorm( GELU_erf( enc0(x) ) ) )        285 → 192 → 128
enc_sec(x)  = L2( enc(x) )                → stage1_section.npy (65540, 128)
enc_user(H) = L2( Σ softmax(att1(tanh? att0(enc(H)))) · enc(H) + gmean 보정 )
```

- **GELU 는 erf 판**이다. tanh 근사를 쓰면 4번째 자리부터 갈라져 순위가 바뀐다
- LayerNorm 은 torch 와 같게 **모집단 분산**, `eps=1e-5`
- NumPy 판과 torch 판 일치 1.65e-07 로 검증됨

### 3.4 점수 결합기 — `logistic_flow_stage1.json` **(주의)**

파일을 열어 보면 학습 결과가 이렇다.

```json
{"features":["model_sim"],
 "coef":[0.9634864330291748],
 "intercept":-0.060991570353507996,
 "scaler_mean":[0.253369431038503],
 "scaler_scale":[0.3005205047701005],
 "dropped":["bpm_log2_ratio","lab_bridge","lab_chorus","lab_inst","lab_intro",
            "lab_other","lab_outro","lab_solo","lab_verse","log_dur",
            "rel_center","rms_pct","rule_sim"],
 "trained_on":"flow","n_train":5528,
 "prob_quantiles":{"p50":0.3915,"p75":0.6221,"p90":0.7158,"p95":0.7452,"p99":0.7802},
 "suggested_threshold":0.6221}
```

```
p = sigmoid( 0.96349 · (model_sim − 0.25337) / 0.30052 − 0.06099 )
```

**특징이 14개가 아니라 1개다.** 학습에서 13개가 떨어졌고 `rule_sim` 도 그중 하나다.
§5-A 에 함의를 적었다.

학습 당시 평가 (같은 파일 `eval`, n=170)

| 방법 | hit@1 | hit@3 |
|---|---|---|
| 기존 랭크 가중합 | 0.412 | 0.800 |
| **로지스틱 확률** | **0.571** | **0.894** |
| DNA 평균 코사인 | 0.282 | 0.635 |
| 무작위 | 0.202 | — |

### 3.5 구간 분리 (All-In-One)

```
allin1.analyze(model="harmonix-all", device="cuda:0",
               keep_byproducts=True, multiprocess=False)
```

`keep_byproducts=True` 는 안정성 요건이다 — demucs 가 비결정적이라 부산물을 버리면
경계가 0.01초씩 흔들린다.

---

## 4. 오프라인 입출력 JSON

### 4.1 구간 분리 결과 `sections.json`

```json
{"schema": "...",
 "track_id": "T000501",
 "analysis_version": "v1",
 "audio_sha256": "9f2c…",
 "duration_sec": 176.576,
 "segmenter": {"name": "allin1", "model": "harmonix-all"},
 "segments": [{"label": "intro", "start": 0.0, "end": 15.5},
              {"label": "verse", "start": 15.5, "end": 45.0}]}
```

**이 파일만 있으면 임베딩부터 다시 돌릴 수 있다.** GPU 박스와 배포 머신을 잇는
내구성 있는 중간 산출물이다.

### 4.2 요구 1 — 구간 적재 (구현됨)

```
PUT /api/v1/music/{musicId}/section      Authorization: Bearer (ROLE_SYSTEM)
```

```json
요청 {"analysis_version":"v1",
      "sections":[{"section_index":0,"external_id":"1001#0",
                   "section_label":"INTRO",
                   "start_time_ms":0,"end_time_ms":15500,
                   "instruments":["guitar","drums"]}]}

응답 {"message":null,
      "data":{"music_id":1001,"analysis_version":"v1","replaced":3,
              "sections":[{"music_section_analysis_id":4101,
                           "section_index":0,"external_id":"1001#0"}]}}
```

- `section_label` 은 **대문자**, ERD ENUM 밖이면 클라이언트가 먼저 거절한다
- 시간은 **정수 밀리초**. 경계가 뒤집히면 보내지 않는다
- `instruments` 는 **코드 이름**으로 보낸다. 번호는 서버가 붙인다. 못 잰 곡은 필드를
  아예 뺀다 — 곡 단위 교체라 "빈 목록" 과 "안 보냄" 이 같다
- 계약에 `audio_sha256`·`allow_section_change` 는 **없다**

### 4.3 요구 5·6 (BE 미구현, 제안 형태)

```
GET   /api/v1/internal/music/pending-analysis?limit=&cursor=
PATCH /api/v1/internal/music/{id}/analysis-status
헤더  X-Internal-Token
```

```json
요구5 응답 data.tracks[] =
  {"music_id":1001,"audio_object_key":"music/1001.mp3",
   "audio_download_url":"https://storage.internal/…","audio_sha256":"9f2c…",
   "bpm_requested":128,"centroid":1832.86,"rolloff":3746.21,
   "rms_mean":0.1566,"flatness":0.0137,"genre_top1":"Rock",
   "category":"male_english_rock","mood_tags":["energetic"],
   "instrument_tags":["guitar"],"vocal_type":"male","language":"english",
   "analysis_status":"PENDING"}
   + data.next_cursor

요구6 본문 {"analysis_version":"v1","audio_sha256":"…","status":"PROCESSING"}
           {"…","status":"FAILED","error_code":"INGEST_FAILED","error_message":"…"}
```

`DONE` 은 보내지 않는다 — 요구 1 의 적재 성공이 그 자리를 맡는다.

### 4.4 배치 요약 `analysis_batch.jsonl` (한 줄 = 한 실행)

```json
{"started_at":"2026-09-21T10:46:16.245086+09:00","elapsed_sec":1.68,
 "tracks":1,"ok":1,"failed":0,"skipped":0,"changed":0,"rejected":0,
 "added_tracks":1,"added_sections":9,"rows_before":65540,"rows_after":65549,
 "staged_dir":"…\\data\\proc\\mupid_v20260921-104616",
 "swapped":false,"restart_required":false,
 "hold_reason":"등록 실패로 교체를 보류한다 — ValueError: …",
 "exit_code":20,"failures":[],"changed_tracks":[],"rejections":[]}
```

종료 코드 `0` 성공 · `10` 일부 실패(교체됨) · `20` 교체 보류 · `30` 잠금 · `1` 실패.

---

## 5. 이번 정리에서 드러난 문제

### A. 결합기가 사실상 `model_sim` 한 개다 — 영향이 크다

`dropped` 에 `rule_sim` 이 들어 있다. 그래서 배포된 점수는 `model_sim` 의 **단조 변환**
이고, 로지스틱을 태우든 `model_sim` 으로 바로 정렬하든 **순위가 같다.**

그리고 사용자 벡터가 없으면(`zu is None`) `model_sim` 이 전부 0 이라 확률이 상수가 된다
— 콜드스타트에서는 이 결합기가 순위에 **아무 기여도 하지 않는다.** 실제 daily 응답의
점수가 `1.0 / 0.999899 / 0.999799` 로 거의 붙어 있는 것이 그 모습이다.

`serve_features()` 는 여전히 14개 특징을 계산해서 13개를 버린다.

### B. `next` 가 지금 듣는 순간을 1위로 돌려준다

`current_track=JAMENDO_1440522` · `current_section=JAMENDO_1440522#5` 로 요청했는데
1위가 `JAMENDO_1440522#5` 였다. 현재 곡은 `_flow_penalty` 로 감점만 받고 하드 제외가
아니다. 이어 듣기에서 같은 순간을 다시 트는 것은 계약 위반에 가깝다.

### C. `ref_external_id` 로 내부 파일명이 나간다

온보딩 응답에서 `"ref_external_id":"20260901T044346Z_Quiet_Light_take2#1"` 가 나왔다.
Spring 은 이 값을 `music_section_analysis.external_id` 에서 찾는데 그 판에 없으면
버리고 로그만 남긴다 — 추천 이유 문구가 조용히 사라진다.

### D. `daily` 의 `limit` 계약이 바뀌었다

4개 고정이고 다른 값을 보내면 400 이다. 문서와 기존 호출자를 확인해야 한다.

### E. 예상하지 못한 500 은 이 오류 모양을 따르지 않는다

`ApiError` 계열만 `{"error_code","message"}` 로 나간다. 일반 예외 핸들러가 없어
Spring 이 재시도 여부를 판단할 수 없고, 지표에도 안 잡힌다.

---

## 6. 채취 방법 (재현)

```bash
python -m ai_server.tools.serve --host 127.0.0.1 --port 8123 \
       --artifacts ~/ssafy/mupid-ai-artifacts
curl -s localhost:8123/health
curl -s -X POST localhost:8123/internal/recommendations/daily \
     -H "Content-Type: application/json" -d '{"recommendation_id":1,"user_id":15}'
```

모델 형태는 `numpy.load` 로 npz 를 열어 `dtype`·`shape` 를 그대로 옮겼다.
