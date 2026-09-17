# AI · 데이터 — 남은 일과 순서

작성 2026-09-17 · 기준 커밋 `develop c546b78`

곡 분석 결과를 서비스에 붙이는 일의 **남은 절반**을 적는다. 앞 절반(적재 경로)은
develop 에 들어갔고, 남은 것은 **어휘를 맞추는 일**과 **운영에 반영하는 일**이다.

읽는 사람이 다시 캐지 않도록, 판단의 근거가 된 실측값을 같이 적는다.

---

## 0. 지금 어디까지 왔나

develop 에 들어간 것 (2026-09-17, MR !103 · !104 · !105 · !107).

| | 무엇 |
|---|---|
| `api` | `PUT /api/v1/music/{id}/section` 에 `instruments` 추가. `MusicAnalysisInstrument` 의 `@MapsId` 수정 |
| `music_preprocessing` | `classify` 명령 — 배치가 곡별 장르·분위기를 직접 등록. `run_batch.sh` 에 편입 |
| `music_preprocessing` | demucs 스템에서 구간별 악기 측정 (`analysis/instruments.py`) |

**아직 운영에 없다.** 배포는 master 기준인데 위는 전부 develop 이다.
master 에 없는 develop 커밋이 13개다.

### 운영 데이터 현황 (2026-09-17 직접 조회)

```
codes                       GENRE 8 (id 9~16) · MOOD 8 (id 32~39) · INSTRUMENT 0
music_genres                0행
music_moods                 0행
music_section_analysis      0행
music_analysis_instruments  0행
daily_recommendations       0행
onboarding_survey          35행   ← codes 를 참조하는 유일한 곳
```

곡에 붙은 분류가 한 건도 없다. **지금이 스키마를 고치기에 제일 싼 시점이다.**

---

## 1. 어휘가 세 벌이다 — 이 문서의 핵심

이걸 모르면 아래 작업이 전부 "왜 하는지 모르는 일" 이 된다.

| | 사는 곳 | 표기 | 예 | 몇 개 |
|---|---|---|---|---|
| ① Spring `codes` | MySQL. 온보딩 화면이 보여준다 | Title Case | `Rock` · `R&B` · `Hip-hop` | 8 |
| ② 색인 `genre` 축 | `flow_meta.genre_top1` | Title Case + 공백 | `Alternative Rock` · `Contemporary R&B` | 57 |
| ③ 색인 `style` 축 | `flow_meta.category` 에서 보컬·언어 접두어를 뗀 값 | snake_case | `poprock` · `lofi_hiphop` | 37 |

그리고 JupyterHub 의 `~/flow_music_data/genre_mood_taxonomy.json` 이 **45종**(snake_case)인데,
이것이 곧 ③ 이다 — 색인이 763곡이라 37종만 나와 있을 뿐, 나머지 곡이 분석되면 45종이 된다.

**추천기는 ②③ 을 이미 둘 다 본다.** `recommender.py:1552`

```python
gate("genre", lambda t: [_m(t).get("genre", ""), _m(t).get("style", "")])
```

`internal_api.py:485` 주석도 명시한다 — "장르 게이트는 genre 와 style 양쪽에 맞춰 본다."

### 그런데 온보딩 변환이 ② 만 넘긴다

```python
def _genre_catalog(svc) -> list[str]:                      # internal_api.py:354
    return [str(x["value"]) for x in svc.catalog_facets().get("genre", [])]
    #                                                        ^^^^^^^ style 축이 빠졌다
```

`onboarding.map_genre` 는 **이름 포함**으로 잇는다(`'jazz' in 'contemporary jazz'`).
③ 의 값을 받지 못하니 `poprock` 은 "카탈로그에 없는 값" 으로 버려진다.

### "씨앗이 0" 이 무슨 뜻인가

`recommender.onboarding_seeds()` 가 하는 일이다.

```
온보딩 답('Jazz')
  → map_genre 로 색인 어휘 변환 → ['Jazzy Hip-Hop', 'Smooth Jazz']
  → 그 태그를 가진 곡의 구간만 추린다 (track_ok)
  → 구간 라벨별로 묶어 임베딩 평균 → 씨앗 벡터 2~4개
  → 이 벡터와 가까운 구간을 찾아 추천한다
```

변환이 비면 씨앗 배열이 비고, 부르는 쪽은 `if not len(C): return []` 로 넘어간다
(`recommender.py:917`). 그 사용자는 **온보딩을 안 한 사람과 똑같이** 처리된다.

**오류가 나지 않는다.** API 200, 화면에 추천 목록 정상. 취향만 반영되지 않는다.
후보가 적을 때 조건을 푸는 완화 단계(`relax`)가 있지만, `want_genre` 자체가 비면
풀 것이 없어 복구되지 않는다.

### 실측 — 지금 45종을 등록하면 어떻게 되나

운영 색인 어휘를 `/internal/facets` 로 받아 `map_genre` 를 실제로 돌린 결과다.

```
지금 코드 그대로                     45종 중 39종이 씨앗 0
_genre_catalog 에 style 축 추가      45종 중  9종이 씨앗 0
```

남는 9종(`modern_jazz` · `neo_classical` · `jazz_fusion` …)은 **버그가 아니다.**
색인이 763곡이라 그 style 이 아직 없을 뿐이다. 곡이 색인에 들어오면 정확히 일치로 풀린다.

시드 8개는 style 축을 더해도 줄지 않고 **늘어난다.**

| 시드 | 지금 | style 추가 후 |
|---|---:|---:|
| Rock | 5 | 10 |
| Pop | 8 | 17 |
| Electronic | 10 | 12 |
| Indie | 3 | 5 |
| R&B · Jazz · Classical · Hip-hop | 1·2·1·3 | 변화 없음 |

`Hip-hop` 만 하이픈 때문에 `'hip-hop' in 'lofi_hiphop'` 이 False 라 따로 적어야 한다.

### 기존 사용자 35명은 괜찮다

설문이 쓰는 장르 6개를 운영 색인 어휘로 돌려 본 결과다.

| 코드 | 사용자 | 씨앗 |
|---|---:|---:|
| Electronic | 12 | 10 |
| Indie | 5 | 3 |
| R&B | 6 | 1 |
| Pop | 5 | 8 |
| Jazz | 5 | 2 |
| Classical | 2 | 1 |

전부 씨앗이 나온다. `display_name` 방식은 `code_name` 을 건드리지 않으므로
이 사람들은 앞으로도 영향받지 않는다.

### 무드는 장르보다 나쁘다

```
기존 한국어 8개      씨앗 비는 것 없음
taxonomy 9종(영어)   9/9 전부 씨앗 0
색인 mood 축에 그 이름이 있나:  하나도 없음
```

색인 mood 축은 `energetic` · `love` · `melodic` · `epic` · `relaxing` … 로 **완전히 다른 어휘**다.
장르처럼 축을 하나 더 넘겨서 되는 것이 아니라, `onboarding.MOOD_MAP` 에 **9줄을 뜻으로
이어 적어야 한다**. 기계가 정할 수 없고 사람의 판단이 들어간다. 여기가 제일 큰 구멍이다.

---

## 2. 결정된 것

`codes` 에 `display_name` 컬럼을 둔다 (2026-09-17, ERD 수정됨).

- `code_name` — JupyterHub 메타데이터 표기 그대로 (`poprock`). **색인과 맞추는 값**
- `display_name` — 화면에 보여줄 이름 (`팝 록`)

이 방식이면 이름을 번역·정규화하지 않아도 되고, ③ 과 정확히 1:1 로 맞는다.

> **주의.** Spring 이 AI 서버로 보내는 값은 `CodeOption::name` → `code.getCodeName()` 이다
> (`AiRecommendToolImpl:234`). 여기를 `display_name` 으로 바꾸면 `팝 록` 같은 한국어가
> 색인으로 가서 씨앗이 0이 된다. **온보딩 응답에 두 값을 같이 실어, 화면은
> `displayName`, AI 전달은 `name`(=code_name) 으로 갈라 두어야 한다.**

기존 시드 8개는 `code_name` 을 그대로 둔다. 대분류라 메타데이터 어휘에 대응하는 값이 없고,
②와 포함 매칭으로 이미 잘 이어진다.

---

## 3. 운영 DDL 세 가지 — **먼저 해야 한다**

`decision/0011` 에 따라 앱이 스키마를 만들지 않는다. 손으로 친다.
세 테이블 모두 0행이라 지금이 제일 안전하다.

### 3-1. `codes.display_name`

```sql
ALTER TABLE codes ADD COLUMN display_name VARCHAR(200) NOT NULL AFTER code_name;
UPDATE codes SET display_name = code_name WHERE display_name = '';
```

기존 16행은 일단 `code_name` 과 같게 채운다. 한국어 표시명은 나중에 따로 정한다.

### 3-2. `music_analysis_instruments` 의 AUTO_INCREMENT 제거

운영이 지금 이렇다.

```
`music_section_analysis_id` int NOT NULL AUTO_INCREMENT
```

**그 컬럼은 값을 발급받는 자리가 아니라 남의 PK 를 가리키는 자리다.**
AUTO_INCREMENT 가 붙어 있으면 `0` 이 들어왔을 때 MySQL 이 **번호를 새로 발급한다.**
로컬에서 재현한 결과다.

```sql
INSERT INTO links (section_id, code_id) VALUES (1, 100);   -- 1번 구간에 기타
INSERT INTO links (section_id, code_id) VALUES (0, 200);   -- 값이 0으로 들어온 경우

section_id  code_id
    1         100
    2         200     ← 0을 넣었는데 2가 됐다. 외래키 검사도 통과했다
```

엉뚱한 구간에 악기가 붙고 **아무 오류도 나지 않는다.** 떼면 같은 INSERT 가
`ERROR 1452 ... foreign key constraint fails` 로 시끄럽게 실패한다 — 그것이 맞는 동작이다.

지금 코드 경로(`@MapsId`)는 항상 실제 id 를 채우므로 당장 터지지는 않는다.
손으로 쓴 SQL 이나 다른 경로가 위험하다.

```sql
ALTER TABLE music_analysis_instruments
  DROP FOREIGN KEY fk_music_analysis_instruments_music_section_analysis_id;
ALTER TABLE music_analysis_instruments
  MODIFY music_section_analysis_id INT NOT NULL;
ALTER TABLE music_analysis_instruments
  ADD CONSTRAINT fk_music_analysis_instruments_music_section_analysis_id
  FOREIGN KEY (music_section_analysis_id)
  REFERENCES music_section_analysis (music_section_analysis_id);
```

외래키를 먼저 떼야 한다. 붙어 있는 채로 `MODIFY` 하면 `ERROR 1832` 로 거부된다.

### 3-3. `music_section_analysis.external_id` UNIQUE 복구

커밋 `b6d5c7e` 가 길이(64)와 UNIQUE 를 같이 넣었는데 **운영에는 길이만 반영됐다.**

`external_id` 는 추천 결과를 곡으로 되돌리는 **번역 키**다. UNIQUE 가 없으면 같은 값이
두 구간에 존재할 수 있고, 그러면 `id_map` 이 어느 쪽을 가리키는지 알 수 없어진다.
애플리케이션(`MusicSectionServiceImpl.rejectForeignExternalIds`)이 막고 있지만
두 배치가 동시에 돌면 뚫린다.

```sql
ALTER TABLE music_section_analysis
  ADD UNIQUE KEY uk_music_section_analysis_external_id (external_id);
```

> **2026-09-17 실측 정정 — 앞선 판단은 틀렸다.**
> "운영이 문서를 덮어쓴 상태" 가 아니라 **반대**다. 운영 MySQL 을 덤프해
> `infra_k8s/deploy/schema-check.py --schema <dump>` 로 대조하면 **커밋된 `erd.sql`
> (=`HEAD`)이 운영과 차이 0건**이다 (`DB 테이블 30개 / erd.sql 30개 / 엔티티 30개 ·
> 차이 없음`). 어긋나 있던 것은 **작업 트리 쪽**이었고, 그것도 무관한 두 변경이 섞인
> 손편집이었다 — `display_name` 추가(전진)와 커밋 `b6d5c7e`(S15P21A204-344)의 되돌림
> 2건(`AUTO_INCREMENT` 부활 · `VARCHAR(50)`+UNIQUE 삭제)이다.
>
> **되돌림 쪽은 버린다.** 커밋 이력과 `erd.md` 의 "재산출이 풀어 준 것" 이 전부
> `VARCHAR(64)` + UNIQUE + AUTO_INCREMENT 없음 쪽을 가리키므로, 그게 운영이 수렴할
> 대상이다. 운영의 현재 상태(실측)는 다음과 같고 **3-2 · 3-3 만 아직 남았다.**
>
> | | 운영 현재 | 3절 DDL 이후 |
> |---|---|---|
> | `music_analysis_instruments.music_section_analysis_id` | `int NOT NULL AUTO_INCREMENT` + FK | `int NOT NULL` + FK |
> | `music_section_analysis.external_id` | `varchar(64)`, PRIMARY·FK 인덱스만 | + UNIQUE |
> | `codes.display_name` | 컬럼 없음 (16행) | 추가 |
>
> 따라서 **3-2 · 3-3 을 실행한 뒤에는 `erd.sql` 을 고칠 필요가 없다** — 이미 그 모양이고
> 이미 커밋돼 있다. ERD 반영 대상은 **`display_name`(3-1) 하나**이고, 그때는 `erd.md`
> 의 규칙대로 **ERD 도구에서 산출한 DDL 로 통째로 교체**한다 (지금처럼 손으로 한 줄
> 넣지 않는다). `erd.sql` 변경은 3-1 DDL·엔티티 매핑과 **같은 티켓**에서 다룬다.
>
> 3절의 세 DDL 을 운영에 적용한 뒤 위 대조를 다시 돌려 **`codes.display_name` 1건만
> 남는지** 확인한다. 조회·대조 명령은 `세션_이어가기.md` 의 "운영 조회" 에 있다.

---

## 4. 코드 작업 — 컴포넌트별

순서대로 하지 않아도 되지만, **6(실주행)은 1~5가 전부 끝난 뒤**다.

### 4-1. `ai_server` — 변환표 (제일 먼저)

이게 없으면 나머지를 해도 추천에 반영되지 않는다.

- `internal_api._genre_catalog()` 가 `style` 축도 넘기게 한다
- `onboarding.GENRE_EXTRA["Hip-hop"]` 에 hiphop 계열 style 추가
  (`lofi_hiphop` · `boom_bap_hiphop` · `modern_trap_hiphop` ·
  `singing_rap_melodic_hiphop` · `chillhop` · `jazzhop`)
- `onboarding.MOOD_MAP` 에 taxonomy 9종을 뜻으로 잇는 줄 추가.
  **사람이 판단해야 한다** — 색인 mood 축 27개 태그를 놓고 정한다
- `test_contract_offline.test_every_spring_code_reaches_the_catalog` 를
  **45+9종으로 확장**한다. 변환이 비는 값이 하나라도 있으면 실패하게

### 4-2. `api` — display_name

- `Code` 엔티티에 `displayName` 매핑
- `CodeOption` 에 `displayName` 추가. **`name` 은 `code_name` 유지** (2절 주의 참고)
- `POST /api/v1/code` 가 `displayName` 도 받게. 없으면 `codeName` 으로 채운다
- `BootstrapRunner.CODE_SEEDS` 에 표시명 추가 (시드 8+8 의 `code_name` 은 그대로)

### 4-3. `music_preprocessing` — classify 가 표시명도 보내게

- `genre_mood.py` 가 `code_name` = taxonomy 원문, `display_name` = 표시명으로 등록
- 표시명 매핑표(45+9종)를 어디에 둘지 정한다. taxonomy 에 없는 값이라
  저장소에 두는 편이 리뷰가 된다

### 4-4. `frontend` — 온보딩

- 선택지를 `displayName` 으로 보여준다
- **층위 문제가 남는다.** 대분류 8개와 세분류 45개가 한 줄에 섞인다
  (`R&B` 와 `rnb` 가 나란히). 두 단계로 나누거나, 세분류는 온보딩에서 빼고
  필터·리포트에만 쓰는 쪽을 검토한다. 데이터 문제가 아니라 화면 설계 문제다

---

## 5. 릴리스

`develop → master` MR. 위 4-1~4-4 가 들어간 뒤에 한 번에 올린다.

master 푸시가 `data-deploy`(JupyterHub)와 k8s 배포를 같이 태운다.
배포 전까지 운영에는 `POST /api/v1/code` 도 `PUT .../classification` 도 없다 —
지금 `classify --check` 를 운영에 쏘면 404 다.

---

## 6. 실주행 — 순서가 있다

### 6-1. 장르·분위기 등록

JupyterHub 에서. `music_preprocessing` 은 그 안에서 돈다.

```bash
uv run music-preprocessing classify -c config.json --check   # 무엇이 바뀔지만
uv run music-preprocessing classify -c config.json --yes     # 실제 등록
```

`--check` 결과의 `missing_codes` 와 `pending` 을 먼저 본다.
이미 분류가 있는 곡은 건드리지 않고 보고서의 `held` 에 남는다.

### 6-2. 구간 분석 + 악기

`analysis/batch.py`. 이때 처음으로 `music_section_analysis` 와
`music_analysis_instruments` 에 행이 생긴다. **3-2 · 3-3 DDL 이 먼저다.**

### 6-3. 악기 문턱값 맞추기

`instruments.py` 의 `MIX_REL = 0.1`(-20 dB)은 **실측으로 맞춘 값이 아니다.**
이 기계에 음원도 demucs 도 없어 실제 스템 분포를 본 적이 없다.

첫 실주행 뒤 곡 몇 개의 표를 보고 고친다.

```bash
python -m music_preprocessing.analysis.instruments \
  --audio work/1234.audio --sections work/1234.sections.json \
  --demix work/allin1/demix
```

`section_levels()` 가 판정이 아니라 **비율(dB)** 을 내므로 분포를 보고 정할 수 있다.

같이 확인할 것: 스템 폴더 이름이 `1234` 인지 `1234.audio` 인지 demucs 판에 따라 갈린다.
확인할 길이 없어 **둘 다 찾게** 해 뒀다. 그래도 못 찾으면 그 곡의 악기만 비고
`TrackResult.instrument_error` 에 이유가 남는다 — 곡이 죽지는 않는다.

### 6-4. 워터마크 리셋

`track_sections` 가 채워진 뒤 node-b 의
`/srv/mupid-k8s/ai-state/signals_watermark.json` 을 리셋한다.
지금 워터마크가 전부 제외된 신호 375건을 이미 지나쳐 있다.

---

## 7. 아직 손대지 않은 것

**노드 설정을 저장소로 옮기기.** 수신기(`receive-k8s.py` · `receive-index.py` ·
`receive-env.py`), forced command(`ssh-entrypoint-k8s.sh`), 배포 계정이 지금
**사람이 손으로 설치**돼 있다. 이것 때문에 배포가 네 번 깨졌다 —
노드가 `(api|web)` 만 알아서 `k8s-deploy-ai` 가 실패한 건이 대표적이다.

새 노드를 붙이거나 노드를 다시 만들면 같은 일을 또 겪는다.
설치 스크립트를 `infra_k8s/` 에 두고 멱등으로 만드는 일이 남았다.

---

## 참고 — 이번에 쓴 조회 방법

문서의 수치를 다시 확인해야 할 때 쓴다.

```bash
# 운영 DB (읽기만)
export KUBECONFIG=<cluster-admin kubeconfig>
kubectl -n mupid exec mysql-0 -- sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -t -e "..."'

# 운영 색인의 실제 어휘
kubectl -n mupid exec <ai-pod> -- sh -c \
  'curl -s -H "X-Internal-Token: $MUPID_AI_INTERNAL_TOKEN" http://127.0.0.1:8100/internal/facets'

# JupyterHub 파일 읽기 — 폼 로그인 + contents API (infra/ci/deploy-jupyter.py 의 Hub 참고)
#   GET {base}/user/{user}/api/contents/flow_music_data/genre_mood_taxonomy.json
```

kubeconfig 는 cluster-admin 이다. 저장소에 넣지 않는다.

---

## 부록 — taxonomy 45종 / 9종 (2026-09-17)

곡 수 순. `genre_mood_taxonomy.json` 기준이며 1,060곡 전체를 센 값이다.

```
contemporary_pop 62   dance_pop 49   synthpop 45   lofi_hiphop 41
indie_pop 39   alternative_rock 39   poprock 36   city_pop 34
pop_ballad 34   rnb 32   solo_piano 31   singing_rap_melodic_hiphop 24
modern_trap_hiphop 22   synthwave 21   game_battle_bgm 21   indie_rock 20
hard_rock 20   punk_power_pop 20   atmospheric_post_rock 20   alternative_rnb 20
neo_soul 20   boom_bap_hiphop 20   house 20   future_bass 20
drum_and_bass 20   chillhop 20   downtempo 20   chillwave_dreamy_electronica 20
modern_jazz 20   funk 20   cinematic_orchestral 20   adventure_fantasy 20
emotional_drama 20   mystery_suspense 20   casual_light_game_bgm 20   neo_classical 20
acoustic_guitar_folk 20   ambient 20   breakbeat_uk_garage 10   ambient_electronic 10
acoustic_chill 10   jazzhop 10   jazz_fusion 10   bossa_smooth_jazz 10
chamber_classical 10
```

무드 9종 — `melancholic 322` · `warm 314` · `dreamy 296` · `bright 296` ·
`hopeful 263` · `powerful 262` · `dark 152` · `calm 143` · `exciting 52`

색인 mood 축(변환 대상) 27개 — `energetic` · `love` · `melodic` · `epic` · `relaxing` ·
`film` · `happy` · `action` · `christmas` · `background` · `deep` · `space` ·
`motivational` · `emotional` · `ballad` · `inspiring` · `summer` · `corporate` ·
`romantic` · `positive` · `heavy` · `drama` · `meditative` · `trailer` ·
`adventure` · `uplifting` · `advertising`
