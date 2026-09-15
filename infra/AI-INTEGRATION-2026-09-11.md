# AI 통합 — 현황과 정할 것

작성일: 2026-09-11. AI 담당자(정예준)가 작성한다.
[DEPLOYMENT-CONTRACT.md](DEPLOYMENT-CONTRACT.md) 의 "AI 담당자와 확정할 내용" 을 채우는 근거 문서다.

수치는 GPU 서버(`~/rec_mupid`)에서 2026-09-10 에 실측한 값이다. 추정값은 그렇다고 적었다.

---

## 0. 세 줄 요약

- **추천 서빙에 GPU 가 필요 없다.** CPU 전용, RAM 1GB 로 실측됐다. 응답은 이력이 없는
  사용자 0.30초, 이력이 있는 사용자 2.22초다(2026-09-14 재측정). GPU 는 곡을 처음
  분석할 때만 필요하고 그건 오프라인 작업이라 운영에 올리지 않는다.
- **ClickHouse 스키마가 AI 쪽 설계와 거의 그대로 맞는다.** 이벤트 어휘(`SECTION_MARK`,
  `EARLY_SKIP` …)까지 일치한다. 통합 범위가 예상보다 훨씬 작다.
- 남은 문제는 **식별자 3종이 서로 다르다**는 것과, MySQL `daily_recommendation_songs` 에
  구간 컬럼이 없다는 것이다.

---

## 1. AI 쪽이 지금 무엇인가

GPU 서버 `~/rec_mupid` 에서 도는 파이썬(FastAPI) 서비스다. 곡을 구간(intro/verse/chorus…)으로
쪼개고, 구간마다 벡터와 태그를 만들어, 사용자 취향에 맞는 **(곡, 구간) 쌍**을 추천한다.
곡 단위가 아니라 구간 단위인 것이 핵심이다.

### 두 부분으로 나뉜다

| | 하는 일 | 도구 | GPU | 언제 |
|---|---|---|---|---|
| **분석** | 곡 → 구간 분해 · 벡터 · 태그 | All-In-One · MERT · Essentia | 필요 | 곡 추가 시 1회, 곡당 약 45초 |
| **서빙** | 저장된 벡터로 추천 | numpy · 작은 분류기 | **불필요** | 0.30초 ~ 2.22초 |

분석 결과는 파일로 남고, 서빙은 그 파일만 읽는다. **운영에 올릴 것은 서빙뿐이다.**

### 실측치 (2026-09-10)

```
프로세스 메모리   1,036 MB
추천 응답        콜드스타트 0.30초 · 이력 보유 사용자 2.22초 (2026-09-14, 12회 측정)
                 이전 '0.26초' 는 이력이 없는 사용자만 잰 값이었다.
                 이력 사용자가 느린 이유는 요청마다 이벤트 로그(22,084건)를 네 번
                 전수 스캔하기 때문이며, 로그가 커질수록 선형으로 늘어난다.
기동 시간        약 2 초 (카탈로그 로딩 1.7초)
학습된 모델      349 KB
서빙용 데이터    약 515 MB  (2026-09-14 실측)
                 구간벡터 385MB · 특징 93MB · 랭킹입력 33MB · 메타 3.2MB · 모델 357KB
                 ※ 이전 '약 120MB' 는 잘못된 값이었다. 색인은 65,540행 전체를 담고
                   있고 추천 풀은 10,007구간뿐이라 풀만 추출하면 줄어들 여지가 있다.
GPU 점유         없음 — nvidia-smi 프로세스 목록에 없다
카탈로그         963곡 / 약 8,900 구간 (계속 증가 중)
```

---

## 2. ClickHouse 스키마와의 대응

`api/clickhouse/init/01-schema.sql` 을 읽고 확인했다. **거의 그대로 맞는다.**

| ClickHouse | AI 쪽 대응물 | 상태 |
|---|---|---|
| `track_sections` (`section_uid`, `fn`, `start_ms`, `end_ms`, `peak_ms`) | `moments.parquet` | 구조 일치 |
| `listen_events` (`event_type`, `play_id`, `position_ms`, `skip_trigger`) | `events_service.jsonl` | **어휘까지 일치** |
| `user_section_signals` + `mv_user_section_signals` | 행동 가중치 집계 | 가중치 표만 맞추면 됨 |
| `v_track_skips` 의 `EARLY_SKIP` (≤5초 또는 구간의 20% 이내) | Early Skip 감점 판정 | 기준 동일 |
| `rec_impressions` (`section_uids`, `scores`, `explore_flags`, `seed_section_uid`, `signal_weights`) | `/next` 진단 출력 | 필드 대응됨 |
| `analysis_version` / `model_version` / `embedding_version` | (아직 없음) | **AI 가 만들어야 함** |

`mv_user_section_signals` 의 이벤트 목록 —
`SECTION_MARK` `SECTION_UNMARK` `LIKE` `UNLIKE` `DISLIKE` `PLAYLIST_ADD` `PLAYLIST_REMOVE`
`COMMENT_ADD` `SEEK_BACK` — 은 AI 서비스가 이미 처리하는 이벤트와 같은 이름이다.

> [open-questions.md](../api/knowledge/open-questions.md) C6 "청취 이벤트 프로듀서는 누가
> 만드는가" 가 언급한 **"서버 B(FastAPI)" 가 이 AI 서비스다.** 다만 지금은 Kafka 로 발행하지
> 않고 파일에 쓴다. 발행 주체를 AI 로 할지 백엔드로 할지는 아래 3-C 참조.

## 3. MySQL 스키마와의 대응

| MySQL | AI 쪽 대응물 | 상태 |
|---|---|---|
| `music_section_analysis` | `moments.parquet` | 컬럼 대응됨 |
| `music_analysis` (`has_vocal`, `vocal_gender`) | Essentia voice/gender 헤드 | 값 매핑 필요 |
| `music_analysis_instruments` → `instruments` | Essentia instrument 태그 | 어휘 정리 필요 |
| `music_moods` → `moods` | Essentia moodtheme 태그 (17종) | 어휘 정리 필요 |
| `music_genres` → `genres` | Essentia genre400 태그 (41종) | 어휘 정리 필요 |
| `music_reactions` | 곡 LIKE/DISLIKE | 그대로 |
| `daily_recommendations` / `_songs` | `/next` 결과 | **구간 컬럼 없음 → 3-A** |
| `onboarding_survey` (mood + genre) | 조사 9항목 | 범위가 좁음 → 3-D |
| `streams` / `chunks` | — | **음원 서빙은 백엔드가 한다.** AI 의 `/audio` 불필요 |

---

## 4. 정할 것

### A. `daily_recommendation_songs` 에 구간 컬럼이 없다

```sql
daily_recommendation_songs
  recommendation_id, music_id, rank_order, reason
                     ^^^^^^^^ 곡만 있고 어느 구간인지 없음
```

`daily_recommendations` 의 주석은 "오늘의 **모먼트** 추천" 인데 모먼트를 저장할 자리가 없다.
ClickHouse `rec_impressions.section_uids` 에는 구간이 남지만, 그건 분석용이고 사용자에게
보여줄 영속 데이터는 MySQL 이다.

제안 — 컬럼 하나면 된다. `NULL` 을 허용해 기존 행과 곡 단위 추천을 그대로 둔다.

```sql
ALTER TABLE daily_recommendation_songs
  ADD COLUMN music_section_id INT NULL COMMENT '추천 구간 (NULL이면 곡 전체)',
  ADD CONSTRAINT fk_daily_rec_song_section
      FOREIGN KEY (music_section_id) REFERENCES music_section_analysis(music_section_id);
```

`ddl-auto: none` 이므로 JPA 엔티티 수정과 DDL 실행이 함께 필요하다
([decision/0011](../api/knowledge/decision/0011-jpa-manages-schema.md)).

**정할 것**: 구간 단위 추천을 이번 범위에 넣을지. 빼면 서비스가 곡 추천기가 된다.

    > **2026-09-11 결정: 컬럼을 추가하지 않는다.** "모먼트" 는 이름일 뿐 오늘의 추천은 곡 목록이다.
> 구간은 재생 시점에 추천 서비스가 돌려주고, 노출 이력은 `rec_impressions.section_uids` 에 남는다.

### B. 식별자가 세 가지다

| 곳 | 곡 | 구간 |
|---|---|---|
| MySQL | `music.music_id` INT | `music_section_analysis.music_section_id` INT · `embedding_id` VARCHAR(50) |
| ClickHouse | `track_id` UInt32 | `section_uid` String |
| AI | `T000001` · `JAMENDO_1280` (문자열) | `T000001#3` (문자열) |

AI 쪽은 문자열이고 DB 는 정수다. 그리고 구간 식별자가 MySQL `embedding_id` 와 ClickHouse
`section_uid` 로 이원화돼 있다.

**정할 것**
1. AI 카탈로그를 `music` 에 적재하면서 `music_id` 를 부여받을지, 아니면 AI 문자열을 그대로
   보관하는 컬럼을 둘지.
2. `embedding_id` 와 `section_uid` 를 같은 값으로 통일할지. 통일하면 벡터DB 키 하나로
   MySQL·ClickHouse·벡터스토어가 연결된다. **통일을 권한다.**

### C. 청취 이벤트를 누가 Kafka 에 넣는가 (C6 의 답)

AI 서비스는 지금 이벤트를 **파일**(`events_service.jsonl`)에 쓴다. 컨테이너에서는 재배포 시
사라지고, 인스턴스를 2개 띄우면 같은 파일에 동시에 써서 깨진다.

선택지
1. **프런트 → 백엔드 → Kafka.** AI 는 이벤트를 만들지 않고 ClickHouse 를 읽기만 한다.
   AI 가 상태를 갖지 않아 인스턴스를 늘릴 수 있다. 백엔드 작업이 늘어난다.
2. **AI 가 Kafka 로 발행.** 지금 구조에 가깝지만 AI 에 Kafka 의존성이 생긴다.

**1번을 권한다.** AI 를 무상태로 만드는 게 배포·복구 모두 단순해진다.

### D. 온보딩 조사 범위

```
MySQL   mood_id, genre_id                                        (2종)
AI      언어·보컬·스타일·장르·무드·악기·템포·구간유형·제외스타일   (9종)
```

**정할 것**: 조사를 2종으로 줄일지, `onboarding_survey` 를 확장할지.

### E. 구간 라벨 어휘가 세 곳에서 다르다

```
MySQL ENUM   INTRO VERSE PRE_CHORUS CHORUS BRIDGE BREAK INST SOLO OUTRO
ClickHouse   fn LowCardinality(String) — 값 제약 없음
AI           All-In-One(harmonix) 출력 — 실제 사용 값 확인 필요
```

AI 자연어 스키마에 임의로 넣은 `drop`·`build` 는 MySQL ENUM 에 없다. **MySQL ENUM 을
기준으로 맞춘다.** AI 카탈로그의 실제 라벨 분포는 GPU 서버 복구 후 확인해 이 문서에 추가한다.

### F. `music` 적재에 앨범·아티스트가 필수다

```sql
music.album_id  INT NOT NULL
music.artist_id INT NOT NULL
```

자체 생성 음원에는 앨범도 아티스트도 없다. Jamendo 곡은 아티스트만 있다.

**정할 것**: 더미 아티스트·앨범을 만들지, 두 컬럼을 NULL 허용으로 바꿀지.

### G. Jamendo 곡의 음원 보관

현재 정책은 "분석 후 즉시 삭제, 원본 URL 만 보관" 이다. 그런데 `streams`·`chunks` 구조는
파일을 실제로 보관해야 동작한다.

**정할 것**: Jamendo 를 팀 스트리밍에 태울지(보관 + CC 저작자 표시 필요), 원본 URL 로
넘길지(별도 재생 경로 필요), 아예 제외할지.

### H. 버전 문자열을 AI 가 만들어야 한다

ClickHouse `rec_impressions` 가 `analysis_version`·`model_version`·`embedding_version` 을
요구한다. AI 산출물에는 아직 이 값이 없다.

이건 단순한 표기 문제가 아니다. AI 산출물 네 파일은 **행 번호로 서로를 가리킨다.**

```
moments.parquet ↔ moments_emb.npy ↔ features_v1.npz ↔ stage1_section.npy
```

하나라도 버전이 어긋나면 엉뚱한 곡을 추천하고, **서비스는 그 상태로도 정상 기동해서 즉시
드러나지 않는다.** 2026-09-09~10 에 실제로 두 번 발생했다(원인: 재색인 누락 1회,
재색인 프로세스가 GPU 를 못 잡아 절반만 성공 1회).

**규약으로 넣을 것**
- 네 파일은 한 묶음으로만 배포한다.
- 묶음에 `manifest.json`(버전 + 각 파일 행 수)을 포함한다.
- 서비스는 기동 시 행 수가 전부 같은지 확인하고, 다르면 **기동을 거부한다.**

---

## 5. 이번 범위에서 빼는 것

**자연어 해석(Qwen 3-4B).** 별도 데모(포트 8200)로 만들어 두었고 동작한다
(52케이스 통과 — 기본 35 + "○○ 빼고" 같은 제외 표현 17). 응답은 p95 1.7초이나
이는 35케이스 기준 실측이고, 제외 표현이 늘어난 뒤로는 다시 재지 않았다.
다만 **GPU 가 필요하다.** CPU 로는 한 요청에 수십 초가 걸려
운영에 올릴 수 없다. `ai_chat_sessions`·`ai_chat_messages` 테이블이 이미 있으므로
GPU 자원이 확보되면 그때 붙인다.

---

## 6. 다음 행동

| 순서 | 할 일 | 담당 |
|---|---|---|
| 1 | 위 A~H 결정 | AI · 백엔드 · 인프라 |
| 2 | 산출물 묶음 규약 확정 (4-H) | AI |
| 3 | 서빙 컨테이너 Dockerfile · 헬스체크 | AI |
| 4 | 산출물 전달 경로 확정 (이미지 포함 / SeaweedFS) | 인프라 |
| 5 | 백엔드 → AI 호출 규약 | 백엔드 |

4번은 [DEPLOYMENT-CONTRACT.md](DEPLOYMENT-CONTRACT.md) 의 "스토리지" 항목이고,
SeaweedFS 가 운영에서 실제로 도는지 확인이 선행된다(같은 문서 §통합 전 운영 설정 4).
