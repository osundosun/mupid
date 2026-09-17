# 내 취향 리포트 · MuBTI

> **상태**: 구현 완료 · 화면 연결까지. 결론은
> [decision/0019](../decision/0019-report-reads-clickhouse-directly.md) 에 있다.
>
> 설계 단계에서 팀이 정한 세 가지:
> 1. **적재는 자정 배치까지 넣는다** — backfill-on-read + 5분 throttle 스냅샷 + 00:05 배치.
> 2. **V/I 축은 `has_vocal` 만 쓴다** — 구간 편향은 넣지 않는다.
> 3. **일간 전용** — `GET /api/v1/report?date=YYYY-MM-DD`. `period` 는 두지 않는다.

'내 취향' 화면 한 장에 들어가는 세 가지 — **곡별 청취량 트리맵 · 음악 키워드 ·
MuBTI 판정** — 을 **하루 단위로, 조회할 때마다 다시 집계해서** 내려준다.

막고 있던 것은 하나였다. API 서버에 ClickHouse **조회** 계층이 없었다
([api-todo](../api-todo.md) 13번). 설정(`analytics/config/ClickHouseConfig.java`)과
`clickHouseJdbcTemplate` 빈은 있는데 읽는 코드가 한 줄도 없었다. 이 리포트가 그
첫 사용처다.

관련 문서: [listening-events.md](listening-events.md) · [erd.md](erd.md) ·
[decision/0015](../decision/0015-derive-interpretation-at-query-time.md) ·
[architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md)

---

## 1. 무엇으로 계산하는가

### 이미 있는 것

| | 어디 | 상태 |
|---|---|---|
| 곡별·구간별 청취 이벤트 22종 | ClickHouse `listen_events` | ✅ 쌓이고 있다 |
| 곡 메타 (제목·아티스트·앨범·이미지) | MySQL `music` 외 | ✅ `MusicSummaryAssembler` 로 조립된다 |
| 곡 키워드 | MySQL `music_moods` · `music_genres` | ✅ |
| 보컬 유무 | MySQL `music_analysis.has_vocal` | ✅ 스키마·엔티티 있음 |
| 구간 (INTRO/VERSE/CHORUS…) | MySQL `music_section_analysis` | ✅ 스키마·엔티티 있음 |
| 곡 길이 | MySQL `streams.duration_ms` | ✅ |
| 리포트 적재 자리 | MySQL `reports` · `listening` · `mubti` | ✅ 엔티티까지 있음, **채우는 코드 없음** |

### 쓸 수 없는 것

**ClickHouse `play_section_coverage` 를 쓰지 않는다.** 이 테이블은
`v_track_sections_current` → `track_sections` 에 의존하는데, **`track_sections` 를
채우는 파이프라인이 저장소에 없다** ([track-sections.md](track-sections.md)).
비어 있는 딕셔너리로는 `mv_play_section_coverage` 의 JOIN 이 한 행도 만들지 못한다.
같은 이유로 `mv_user_section_signals` · `v_track_skips` 도 지금은 빈 결과다.

그래서 이 리포트는 **`listen_events` 원본만 읽는다.** 구간 단위 신호는 아예 쓰지
않는다 — V/I 축도 곡 속성(`has_vocal`)으로만 판정한다(§3 A축). `track_sections` 가
채워지면 그때 구간 편향을 축에 더한다.

### `track_id` = `music_id` 로 본다

[open-questions C5](../open-questions.md) 가 남아 있지만, 프론트는 실제로 서버
`music_id` 를 실어 보낸다 (`HistoryServiceImpl.recordPlay` 가 그 전제로 동작한다).
리포트도 같은 전제를 쓰고, **MySQL 에 없는 `track_id` 는 조용히 버린다** — 화면에
그릴 제목·앨범아트가 없기 때문이다. 버린 양은 응답의 `unmatched_ms` 로 내보내
전제가 깨지면 드러나게 한다.

---

## 2. 집계 쿼리 (ClickHouse)

하루 = **`Asia/Seoul` 기준 00:00:00.000 ~ 다음날 00:00:00.000 직전.**
`occurred_at` 이 `DateTime64(3, 'Asia/Seoul')` 이라 시간대 변환이 필요 없다.

모든 쿼리는 **Distributed 테이블 `listen_events`** 를 읽는다. Kafka 적재 경로가
샤딩 키를 타지 않아 한 사용자의 이벤트가 두 샤드에 흩어지기 때문이다
([listening-events.md](listening-events.md) 의 경고 참고).

`ORDER BY (user_id, occurred_at, event_id)` 라서 `user_id = ? AND occurred_at` 범위는
정렬 키 접두사다. 한 사용자의 하루치만 읽는다.

### Q1. 곡별 청취량 — 트리맵의 근거

```sql
SELECT track_id, sum(ms) AS listened_ms, count() AS heartbeats
FROM (
    SELECT
        any(track_id)                   AS track_id,
        any(position_ms) - any(from_ms) AS ms
    FROM listen_events
    WHERE user_id = ?
      AND event_type = 'PLAY_PROGRESS'
      AND occurred_at >= ? AND occurred_at < ?
      AND track_id > 0
      AND position_ms > from_ms
    GROUP BY event_id          -- ReplacingMergeTree 병합 전 중복을 여기서 막는다
)
GROUP BY track_id
ORDER BY listened_ms DESC
```

> `GROUP BY event_id` 를 빼면 안 된다. `listen_events_local` 이
> `ReplicatedReplacingMergeTree(ingested_at)` 라 **병합 전에는 같은 `event_id` 가
> 여러 행으로 남아 있고**, 수집기의 Redis 멱등(TTL 10분)을 넘긴 재전송이나 Kafka
> at-least-once 재소비가 그대로 청취 시간 부풀리기가 된다. 하루치 한 사용자
> 범위라 이 GROUP BY 는 싸다.

**PLAY_PROGRESS 하나가 `[from_ms, position_ms)` 를 실제로 들은 구간**이라는 계약이
이 집계의 전부다. seek 하면 프론트가 포인터를 옮기므로 건너뛴 구간은 애초에
들어오지 않고, 일시정지 중에는 하트비트가 없다. 그래서 "재생기가 돈 시간" 이 아니라
**"귀에 닿은 시간"** 이 나온다 — 트리맵이 보여줘야 하는 값이 이쪽이다.

### Q2. 재생 단위 요약 — C축(완주/스킵)

```sql
SELECT
    countIf(event_type = 'PLAY_END')     AS ends,
    countIf(event_type = 'TRACK_SKIP')   AS skips,
    countIf(event_type = 'PLAY_START')   AS starts,
    countIf(event_type = 'SEEK_FORWARD') AS seek_forwards,
    countIf(event_type = 'SEEK_BACK')    AS seek_backs,
    uniqExact(play_id)                   AS plays
FROM (SELECT any(event_type) AS event_type, any(play_id) AS play_id
      FROM listen_events
      WHERE user_id = ? AND occurred_at >= ? AND occurred_at < ?
      GROUP BY event_id)
```

> **위치 히스토그램 쿼리는 두지 않는다.** 하트비트가 곡의 어느 지점을 덮었는지
> 뽑아도 그것을 얹을 구간 기준이 없다(§1). V/I 는 곡 속성으로만 판정한다(§3 A축).

### Q3. 과거에 들어본 곡 — D축(국밥/개척)

```sql
SELECT DISTINCT track_id
FROM listen_events
WHERE user_id = ?
  AND event_type IN ('PLAY_START', 'PLAY_PROGRESS')
  AND occurred_at >= ?   -- 오늘로부터 90일 전
  AND occurred_at <  ?   -- 오늘 00:00 (오늘은 제외)
  AND track_id > 0
```

**이 결과는 오늘 안에 변하지 않는다.** 그래서 캐시 수명이 다른 두 쿼리와 다르다(§5).

---

## 3. MuBTI 판정 규칙

네 축 각각 **−1 ~ +1 점수**를 만들고 부호로 글자를 고른다. 점수를 저장하지 않고
**조회할 때마다 계산한다** — 기준을 고치면 어제 이벤트도 새 기준으로 다시 읽혀야
한다 ([decision/0015](../decision/0015-derive-interpretation-at-query-time.md)).
`v_track_skips` 가 VIEW 인 것과 같은 이유다.

계수는 전부 `MubtiScorer` 의 상수 한 곳에 모은다. 흩어 놓으면 조율이 불가능해진다.

### A축 — V(보컬) / I(멜로디)

**보컬곡 편식도 하나로 판정한다.** 들은 시간 중 `has_vocal = true` 인 곡의 비중을,
**카탈로그 전체의 보컬곡 비중**과 비교한다.

```
score_A = clamp((myVocalMsShare − catalogVocalShare) × 2.5)
score_A ≥ 0 → V,  < 0 → I
```

절대 비중을 쓰면 안 된다. 카탈로그의 90%가 보컬곡이면 모두가 V 가 되어 축이
죽는다. **기준선 대비 편차**여야 취향이 된다.

> **이 축이 가장 약하다.** `has_vocal` 은 곡의 속성이지 듣는 방식이 아니다. "후렴만
> 되감아 듣는가 / 인트로·솔로에 머무는가" 를 보려면 구간별 청취량이 필요한데, 그
> 근거(`track_sections`)가 아직 없다(§1). 구간 분석이 채워지면 여기에
> `sectionBias` 항을 더하는 것이 다음 단계다.

`music_analysis` 행이 하나도 없는 사용자는 이 축을 판정할 근거가 없다. 그때는
**`V` 로 두되 `confidence = 0` 을 실어 보낸다**(§4) — 축을 비우면 화면이 16유형을
그리지 못하기 때문이다.

### B축 — T(트렌드) / M(매니아)

**집중도 대 대중성**이다.

- **집중도**: 아티스트별 청취 시간 점유율의 허핀달 지수(HHI). 한 아티스트만
  들으면 1, 고르게 들으면 1/n 에 수렴한다. 곡 수가 적으면 자동으로 높게 나오므로
  `(HHI − 1/n) / (1 − 1/n)` 로 정규화한다.
- **대중성**: 들은 곡의 `music.play_count` 가 전체 카탈로그에서 차지하는 백분위의
  청취시간 가중 평균. 그리고 `release_date` 가 90일 이내인 곡의 청취 비중.

```
score_B = 0.55 × concentration − 0.30 × popularityPercentile − 0.15 × freshShare
score_B ≥ 0 → M,  < 0 → T
```

> 카탈로그가 작을 때 `play_count` 백분위는 노이즈다. 그래서 가중치를 집중도보다
> 낮게 뒀다. 곡이 충분히 쌓이면 다시 본다.

### C축 — F(완주) / S(스킵)

가장 깨끗하게 이벤트로 떨어지는 축이다.

```
finishRate  = ends / (ends + skips)          -- Q2
seekOutRate = seek_forwards / plays          -- 곡 안에서 건너뛴 빈도
score_C = 0.7 × (finishRate × 2 − 1) − 0.3 × clamp(seekOutRate / 2)
score_C ≥ 0 → F,  < 0 → S
```

`PLAYBACK_ABANDON`(앱이 닫혀 끊김) 과 `PLAY_ERROR` 는 세지 않는다. 의도한 스킵이
아니라는 것이 [listening-events.md](listening-events.md) 의 명시적 규칙이다.

### D축 — C(국밥) / E(개척)

처음 듣는 곡을 **곡 수**와 **청취 시간** 둘로 보되 곡 수에 무게를 싣는다.

```
countShare = (Q3 에 없는 곡 수)     / 오늘 들은 서로 다른 곡 수
msShare    = (Q3 에 없는 곡의 ms)  / 전체 청취 ms
discovery  = 0.7 × countShare + 0.3 × msShare
score_D    = clamp((discovery × 2 − 1) × 1.4)
score_D ≥ 0 → E,  < 0 → C
```

> **시간만 보면 탐색이 곡 길이에 묻힌다.** 실제로 겪은 하루 — 7곡 중 5곡(71%)이 처음
> 듣는 곡이었는데, 익숙한 나머지 2곡이 하필 카탈로그에서 가장 긴 곡이라 청취 시간의
> 65%를 가져갔다. 시간 기준으로는 34%가 되어 국밥형이 나왔다. **새 곡을 찾아 들었다는
> 사실은 그 곡이 길든 짧든 같은 무게여야 한다.**

> **반복 강도(재생 수 / 곡 수)는 뺐다.** 두 가지 이유다. (1) 익숙한 곡만 듣는 사람은
> 이미 두 비중이 0 이라 −1 에 가깝다 — 반복항은 거기에 또 빼는 이중 처벌이었다.
> (2) 이것저것 눌러 보며 고르는 탐색 행동도 재생 수를 올려서, 최애를 다시 듣는 것과
> 구분되지 않았다. 방향이 정반대인데도.

### 표본이 모자랄 때

하루 청취가 **5분 미만이거나 서로 다른 곡이 3곡 미만**이면 판정하지 않는다.
응답은 `mubti: null` + `sample.sufficient: false` 로 내려가고, 화면은 "아직
판정하기 일러요" 를 보여준다. **RDB 에도 쓰지 않는다** — `reports.mubti_id` 가
NOT NULL 이라 애초에 못 쓴다.

### 16유형 마스터 데이터

`mubti` 표 16행은 **기동 때 `bootstrap/BootstrapRunner` 가 채운다.** 예전에는 사람이
`seed-mubti.sql` 로 넣어야 했지만, `image_file_id` 가 NOT NULL 이라 행만 넣을 수 없고
(캐릭터 이미지를 스토리지에 올린 뒤 그 `file_id` 를 물려야 한다) S15P21A204-344 에서
자동으로 바뀌었다. 컬럼 대응은 이렇다.

| 컬럼 | 값 | 예 |
|---|---|---|
| `title` VARCHAR(20) | 유형명 | `보컬 인싸형` |
| `subtitle` VARCHAR(50) | 수식 문구 | `모두의 보컬은 곧 나의 최애` |
| `description` VARCHAR(100) | 한 문단 설명 | 축 정의를 이어 붙여 만든다 |
| `class_a`~`class_d` | `V/I` `T/M` `F/S` `C/E` | |
| `image_file_id` | 유형 일러스트 | `mubti-characters.zip` 의 `{CODE}.webp` |

**표가 비어 있으면 리포트가 503 이다.** 러너가 못 채우는 환경(권한 없음, 스토리지 죽음)에서
그렇게 되므로 기동 로그의 `취향 유형` 항목을 본다.

#### 캐릭터 이미지의 저장 키는 `image/mubti/{CODE}.webp` 다

카테고리 접두사를 **손으로 적으면 안 된다.** 저장 키의 모양을 정하는 곳은
`StorageImageService.storageKey` 하나이고, `imageUrl(ObjectStorageFile)` 이 그 모양을
되짚어 조회 URL 을 만든다(3마디로 쪼개 앞 두 마디를 떼고 카테고리를 붙인다).

부트스트랩이 이 규약을 어기고 `mubti/{CODE}.webp` 로 올려 둔 동안, `imageUrl` 이 3마디를
못 찾아 **예외도 로그도 없이 null** 을 돌려줬다. 화면은 그 null 을 "이미지 없음" 으로
받아들여 조용히 마스코트를 그렸고, 그래서 `/report` 의 MuBTI 캐릭터가 뜨지 않았다.
같은 이유로 `SecurityConfig` 의 공개 경로에 `MUBTI` 가 없어 `<img src>` 가 401 이었다.

기동할 때 `BootstrapRunner.repairMubtiImageKeys` 가 옛 키(`mubti/{CODE}.webp`)로 올라간
이미지를 찾아 `image/mubti/` 로 옮긴다. 바이트를 **스토리지에서 읽어** 옮기므로 zip 판본으로
덮어쓰지 않고, **행 → 오브젝트 → 옛 객체 삭제** 순서다(뒤집으면 행이 중복 생긴다 —
`BootstrapRunner` 의 javadoc 참고).

---

## 4. API

```
GET /api/v1/report?date=2026-09-15      인증 필요
```

`date` 를 생략하면 `Asia/Seoul` 기준 오늘. 미래 날짜는 400.

```json
{
  "message": null,
  "data": {
    "date": "2026-09-15",
    "generated_at": "2026-09-15T21:04:11",
    "source": "LIVE",
    "total_listening_ms": 3540000,
    "unmatched_ms": 0,
    "tracks": [
      { "music_id": 12, "title": "...", "artist_name": "...",
        "album_title": "...", "image_url": "/api/v1/storage/image/12",
        "listening_ms": 420000, "share": 0.118 }
    ],
    "keywords": [ { "name": "몽환적", "kind": "MOOD", "weight": 0.31 } ],
    "mubti": {
      "code": "VTFC",
      "title": "보컬 인싸형",
      "subtitle": "모두의 보컬은 곧 나의 최애",
      "description": "지금 사랑받는 목소리를 끝까지 듣고, 마음에 든 곡은 오래 곁에 둬요.",
      "image_url": "/api/v1/storage/image/12",
      "axes": [
        { "axis": "A", "code": "V", "name": "보컬파",
          "opposite_code": "I", "opposite_name": "멜로디파",
          "score": 0.62, "confidence": 0.80 }
      ]
    },
    "sample": {
      "sufficient": true, "play_count": 24,
      "distinct_music_count": 11, "listening_ms": 3540000
    }
  }
}
```

- `source` 는 `LIVE`(ClickHouse 즉시 집계) / `STORED`(RDB 스냅샷) 다. 화면이
  "실시간" 표시를 켤지 정하는 근거다.
- `confidence` 는 그 축이 몇 개의 신호로 판정됐는지다. 0 이면 화면이 흐리게 그린다.
  **판정을 숨기지 않고 확신도를 같이 내보내는 쪽**을 골랐다 — 축을 통째로 비우면
  화면이 16유형을 못 그린다.
- 키워드는 **분위기·장르를 청취 시간으로 가중**해 상위 6개. 지금 화면이
  `moments` 의 태그 개수로 로컬 계산하던 자리를 그대로 대체한다.

### 오류

| 코드 | 상태 | 언제 |
|---|---|---|
| `MUPID_REPORT_INVALID_DATE` | 400 | 미래 날짜 / 형식 오류 |
| `MUPID_REPORT_MUBTI_NOT_SEEDED` | 503 | `mubti` 16행이 없다 |
| `MUPID_REPORT_ANALYTICS_UNAVAILABLE` | 503 | ClickHouse 조회 실패 |

ClickHouse 가 죽어도 **API 컨테이너가 unhealthy 로 넘어가면 안 된다.**
`ClickHouseConfig` 가 헬스 컨트리뷰터를 일부러 떼어 놓은 이유와 같다. 이 화면
하나가 503 이 될 뿐이다.

---

## 5. 캐시와 적재

### 캐시 — 변하는 속도로 나눈다

| 대상 | TTL | 왜 |
|---|---|---|
| 과거에 들어본 곡 집합 (Q3) | **6시간** (Redis) | 90일을 훑는 가장 무거운 질의인데 그날 안에는 변하지 않는다 |
| 카탈로그 기준선 (보컬 비중·`play_count` 백분위) | **1시간** (프로세스 메모리) | 사용자마다 다르지 않다 |
| 오늘 리포트 전체 | **캐시하지 않는다** | 아래 참고 |
| 지난 날짜 리포트 | 캐시 없음 | RDB 에서 바로 읽는다 |

**리포트 전체를 캐시하지 않는 이유.** 비싼 조각 둘을 긴 TTL 로 빼고 나면 매 조회에
남는 것은 하루치 슬라이스를 읽는 Q1·Q2 뿐이다. 정렬 키 접두사를 타는 쿼리 둘을
직렬화해 Redis 에 넣었다 빼는 비용이 그보다 싸다는 근거가 없다. 동시 사용자가 늘어
실제로 부담이 되면 그때 넣는다 — [decision/0019](../decision/0019-report-reads-clickhouse-directly.md)
의 "다시 볼 것".

기준선을 Redis 가 아니라 프로세스 메모리에 두는 것도 같은 이유다. 재생수 배열은
곡 수만큼 길어서 직렬화 왕복이 MySQL 재조회보다 싸지 않고, 인스턴스마다 최대
1시간 어긋나도 백분위 기준선으로서는 영향이 없다.

이력 현상용 **직전 판정**(네 글자)과 오늘치 **스냅샷 throttle 표시**는 Redis 에
둔다. 둘 다 값이 작고, 인스턴스 사이에서 같아야 의미가 있다.

### 적재 — 읽을 때마다 쓰지 않는다

`reports` 는 `uq_report_period (user_id, start_date)` 로 하루 한 행이다.

1. **지난 날짜**: `reports` 를 읽는다. 없으면 ClickHouse 로 계산해 그때 한 번
   쓰고 돌려준다(backfill-on-read).
2. **오늘**: 항상 ClickHouse 로 계산한다. 다만 **5분에 한 번만** 스냅샷을
   `reports` + `listening` 에 upsert 한다 (Redis 키로 throttle). 조회마다 쓰면
   읽기 경로에 쓰기 폭풍이 붙는다.
3. **자정 배치**: 00:05 KST 에 전날 리포트를 확정 저장한다.

> ⚠ **스케줄러는 이 기능이 저장소에 처음 들여온다.** `@EnableScheduling` 도
> `@Scheduled` 도 지금까지 0건이었다. API 를 여러 벌 띄우면 배치가 인스턴스 수만큼
> 돌기 때문에, `ApiApplication` 에 `@EnableScheduling` 을 붙이면서 배치 진입에
> **Redis 락(`CacheKind.ReportBatchLock`, SETNX + TTL)** 을 같이 건다. 배포가
> 1벌이어도 그렇게 둔다 — 늘어나는 순간 조용히 깨지는 종류의 문제다.

배치가 도는 대상은 **전날 이벤트가 있는 사용자만**이다. 전체 회원을 훑으면 쓰지도
않는 사용자 수만큼 ClickHouse 를 때린다. 대상 목록은 ClickHouse 에 한 번 물어본다.

```sql
SELECT DISTINCT user_id
FROM listen_events
WHERE occurred_at >= ? AND occurred_at < ?     -- 어제 하루
```

### `Listening` 엔티티에 손볼 곳이 있다

```java
@Entity @Table(name = "listening")
public class Listening {
    @EmbeddedId private ListeningId listeningId;
    @ManyToOne @JoinColumn(name = "report_id", nullable = false) private Report report;
    ...
}
```

- **no-arg 생성자가 없다.** `@AllArgsConstructor` 만 있고
  `@NoArgsConstructor(access = PROTECTED)` 가 빠졌다 — 다른 엔티티는 전부 갖고 있다.
  이대로면 Hibernate 가 인스턴스를 못 만든다.
- `@EmbeddedId` 두 컬럼이 `insertable = false, updatable = false` 인데
  `@MapsId` 가 없다. 연관관계가 컬럼을 쓰므로 INSERT 는 되지만 **PK 값을 손으로
  채워야 하고** 안 채우면 조용히 깨진다. `@MapsId("reportId")` / `@MapsId("musicId")`
  를 붙이는 쪽이 맞다.

둘 다 **매핑 수정이라 ERD 변경이 아니다.** 테이블은 그대로다.

---

## 6. "들을 때마다 갱신" 이 가능한가

### 지연의 바닥

| 단계 | 지연 | 어디에 |
|---|---|---|
| 재생 → 다음 하트비트 | 0~5초 | `frontend/lib/analytics/player.ts` `HEARTBEAT_MS = 5000` |
| 큐 → 서버 전송 | 0~5초 | `tracker.ts` `FLUSH_MS = 5000` (20건 차면 즉시) |
| 수집기 → Kafka | ~0 | 응답을 기다리지 않는다 |
| Kafka → ClickHouse | **0~15초** | `kafka_flush_interval_ms = 15000` |
| 화면 폴링 간격 | 0~30초 | `frontend/lib/report.ts` `POLL_MS = 30000` |

서버까지의 지연이 **최악 25초**, 화면에 보이기까지가 **최악 55초 · 평균 20초 안팎**
이다. "지금 이 곡을 듣자마자 트리맵이 커진다" 는 구조적으로
불가능하다. 줄이려면 `kafka_flush_interval_ms` 를 낮춰야 하는데, 그 값은 **작은
파트가 쌓여 병합 압력이 오르는 것을 막으려고 일부러 올려둔 값**이다
(`01-schema.sql` 주석). 화면 한 장 때문에 적재 안정성을 깎을 값이 아니다.

### 그래서 어떻게 하나

**스트리밍(SSE·WebSocket)은 하지 않는다.** 원천이 20초 지연인데 전송만 실시간으로
만들면 비용만 늘고 체감은 그대로다.

- 화면을 **열 때** 한 번 부른다.
- 탭이 다시 보이면(`visibilitychange`) 다시 부른다. 가려진 탭에서는 건너뛴다.
- 리포트 화면에 머무는 동안만 **30초 폴링**. 다른 화면으로 가면 멈춘다.

사용자당 **분당 2회**다. 매번 도는 것은 하루치 슬라이스를 읽는 쿼리 둘뿐이고
(§5), 정렬 키 접두사를 타므로 수 ms~수십 ms 다.

**폴링해도 화면을 비우지 않는다.** 다시 받아 오는 동안 직전 값을 그대로 두고,
실패해도 이미 받아 둔 리포트를 지우지 않는다. 한 번 끊겼다고 화면이 비면 "잠깐
끊긴 것" 과 "데이터가 없는 것" 을 구분할 수 없다.

### UX 쪽이 더 중요한 문제

성능보다 **유형이 흔들리는 것**이 문제다. 한 곡 스킵했다고 `F` 가 `S` 로 뒤집히면
판정을 신뢰할 수 없게 된다. 표본이 작은 아침일수록 심하다.

세 가지를 건다.

1. **표본 하한**(§3). 5분·3곡 미만이면 아예 판정하지 않는다.
2. **이력 현상(hysteresis).** 한 번 정해진 글자는 반대편 점수가 **0.15 를 넘어서야**
   바뀐다. 0 근처에서 깜빡이지 않는다. 직전 판정은 Redis 에 하루치로 들고 있는다.
3. **움직이는 것은 막대, 고정된 것은 이름.** 축 점수(`score`)는 실시간으로 흐르게
   두고 유형 이름은 위 규칙으로만 바꾼다. 사용자는 "내가 지금 V 쪽으로 기울고 있다"
   를 보면서도 이름이 요동치는 걸 보지 않는다.

**결론**: 실시간 갱신은 **20~30초 지연의 준실시간으로 가능하고, 그 정도면 충분하다.**
진짜 실시간을 쫓는 대신 지연을 숨기는 쪽(폴링 + 이력 현상)에 비용을 쓴다.

---

## 7. 만들어진 것

### 백엔드

```
api/report/
  controller/ReportController.java          GET /api/v1/report?date=
  service/ReportService.java · ReportServiceImpl.java
  service/MubtiScorer.java                  판정 규칙 전부. 계수는 여기 상수로만
  service/CatalogBaseline.java              카탈로그 기준선 (보컬 비중 · 재생수 백분위)
  service/ReportSnapshotWriter.java         RDB 적재
  service/ReportDailyBatch.java             00:05 KST · Redis 선점
  dto/ MubtiInput · MubtiVerdict
  response/ReportResponse.java
  enumerate/ ReportSource · KeywordKind · MubtiClass(Axis 인터페이스 추가)
  error/ReportErrorCode.java
  repository/  (있던 3개에 조회 메서드 추가)
  entity/Listening.java                     ← §5 의 매핑 결함 수정

api/analytics/
  repository/ListenAnalyticsRepository.java Q1~Q3 + 배치 대상. clickHouseJdbcTemplate 첫 사용처
  dto/ DailyTrackListening · DailyPlaySummary

api/ApiApplication.java                     ← @EnableScheduling (저장소 최초)
api/cache/enumerate/CacheKind.java          ← 4종 추가
api/music/repository/                       ← 기준선용 조회 2개
```

### 데이터

```
api/src/main/resources/bootstrap/mubti-characters.zip   16유형 캐릭터 (파일 이름이 코드)
api/src/.../bootstrap/BootstrapRunner.java              기동 때 16행을 이미지와 함께 채운다
```

`seed-mubti.sql` 은 없다. `image_file_id` 가 NOT NULL 이라 SQL 만으로는 행을 만들 수 없어서,
S15P21A204-344 에서 부트스트랩이 이미지를 올리고 행을 만드는 방식으로 바뀌었다.

### 프론트

```
frontend/lib/api/endpoints.ts                      report.daily(date) + 응답 타입
frontend/lib/report.ts                             useDailyReport (폴링) · formatListening
frontend/lib/treemap.ts                            squarify — 순수 함수라 테스트한다
frontend/components/mupid/collection.tsx           ReportView 를 서버 데이터로
frontend/components/mupid/daily-album-treemap.tsx  면적 비례 트리맵
frontend/app/readability.css                       MuBTI 축 막대 + 죽은 균일 그리드 제거
```

예전 트리맵은 `seconds: 180 + (index % 5) * 8` 이라는 **가짜 값에 모든 타일이 같은
크기**였고, "면적이 시간을 나타내지 않는다" 고 주석에 적혀 있었다. 이제는 나타낸다.

### 테스트

```
api/src/test/.../report/MubtiScorerTest.java   판정 방향 · 표본 하한 · 이력 현상 11건
frontend/tests/treemap.test.mjs                면적 비례 · 겹침 · 종횡비 7건
```

둘 다 인프라 없이 돈다. 스코어러가 리포지토리를 모르고, squarify 가 컴포넌트에서
떨어져 있기 때문이다.

> `treemap.test.mjs` 의 종횡비 검사가 실제로 버그를 잡았다. `worstRatio` 가 논문의
> `w`(남은 영역의 짧은 변) 대신 줄 두께를 쓰고 있었는데, 그러면 두 항이 개수와
> 무관한 상수가 되어 **어떤 줄도 끊기지 않고 16개가 전부 한 줄에** 들어갔다.
> 눈으로는 "좀 길쭉하네" 로 보였을 것이다.

### 문서

`api/docs/Report/` Bruno 컬렉션 · [api-todo](../api-todo.md) 13번 해제 ·
[decision/0019](../decision/0019-report-reads-clickhouse-directly.md).

## 지금 판정을 막고 있는 것 (데이터 쪽)

코드가 아니라 **들어오는 데이터** 때문에 축이 죽어 있는 자리들이다. 순서대로 걷어야
리포트가 제 값을 한다.

| | 현재 | 영향 |
|---|---|---|
| 재생 가능한 곡이 **2곡** | `streams.status='DONE'` 가 2건 | `MIN_DISTINCT_MUSIC = 3` 이라 **실제 재생으로는 MuBTI 가 영원히 판정되지 않는다** |
| `music.play_count` 가 **누구도 올리지 않는다** | 전부 0 | B축 대중성 백분위가 전 곡 동일 → 상수 편향만 남고 차원이 죽는다 |
| `music_analysis` 가 거의 비어 있다 | 2곡만 | A축 `confidence` 가 분석된 곡의 청취 비중만큼만 올라간다 |
| `track_sections` 를 채우는 파이프라인이 없다 | 실재하지 않는 곡(1001·2002·7777) 앞으로 `verify.sh` 가 남긴 6행뿐 | 실제 카탈로그 곡의 구간 신호(`play_section_coverage` · `v_track_skips` · `user_section_signals`)가 전부 빈 결과 |
| `PLAYBACK_ABANDON` 이 종료를 지배한다 | 28 재생 중 END 2 · SKIP 1 · ABANDON 18 | **C축이 28번 중 3번만 보고 판정한다** |
| 5초 하트비트보다 짧은 재생 | 29 재생 중 17 | 그 재생의 청취 시간은 **0 으로 집계된다** — 스킵이 잦은 사용자일수록 총 청취 시간이 과소 계상된다 |

앞의 둘은 데이터만 채우면 풀린다. 뒤의 둘은 **판정 규칙을 다시 볼 문제**다.

- `PLAYBACK_ABANDON` 을 C축에서 계속 뺄 것인가. 의도한 스킵이 아니라는 이유로 빼는
  것이 맞지만, 그것이 종료의 대부분이면 축이 사실상 눈을 감는다. "곡 길이의 90% 를
  넘겨 듣고 끊긴 재생은 완주로 본다" 같은 보정을 붙이는 선택지가 있다.
- 5초 미만 재생을 청취 0 으로 두는 것은 하트비트 설계의 결과다. 바꾸려면
  `TRACK_SKIP` · `PLAY_END` 의 `position_ms` 를 청취 시간으로 인정해야 한다.

## 남은 것

- **A축이 가장 약하다.** `has_vocal` 은 곡의 속성이지 듣는 방식이 아니다.
  `track_sections` 가 채워지면 §3 에 적어 둔 `sectionBias` 항을 더한다.
- **계수를 실제 데이터로 조율하지 않았다.** 지금 값은 축의 뜻에서 역산한 것이고,
  사용자 분포를 보고 맞춘 것이 아니다. 한쪽 유형으로 쏠리면 `MubtiScorer` 의
  상수만 고치면 된다 — 저장된 점수가 없으므로 과거까지 새 기준으로 다시 읽힌다.
- **주·월 리포트.** 일별 `reports` 가 쌓이면 그 위에서 만들 수 있다.
