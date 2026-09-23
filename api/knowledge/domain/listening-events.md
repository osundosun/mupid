# 청취 이벤트

사용자의 모든 청취 행동은 **이벤트 하나의 스트림**으로 들어온다. 이 문서는 그 이벤트가 어떤 모양이고 어떻게 신호로 바뀌는지 다룬다.

구현 위치는 `api/clickhouse/init/01-schema.sql`. 파이프라인 운영은 [architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md), 왜 이 구조인지는 [decision/0005](../decision/0005-kafka-clickhouse-for-listen-events.md), 누가 이벤트를 발행하는지는 [decision/0014](../decision/0014-event-collector-in-api.md).

표만 빠르게 훑거나 화면을 같이 보며 이야기할 때는 [event-spec.html](event-spec.html) 을 브라우저로 열면 된다 — 이 문서를 렌더한 한 장짜리 사본이다. **둘이 어긋나면 이 마크다운이 기준이고**, 고칠 때도 마크다운을 먼저 고친다.

## 흐름

```
클라이언트 (JWT 필수 — 비로그인 청취는 막는다)
  → POST /api/v1/events              수집기. user_id 확정 · 값 검증 · event_id 멱등
  → Kafka 토픽 mupid.listen.events   (JSONEachRow)
  → kafka_listen_events              (Kafka 엔진, 컨슈머 3)
  → mv_kafka_to_events               (MATERIALIZED VIEW)
  → listen_events_local              (원본, 수정하지 않음)
       ├→ mv_user_section_signals  → user_section_signals    (가중치 부여)
       ├→ mv_play_section_coverage → play_section_coverage   (구간별 청취량)
       └→ v_track_skips                                      (스킵 해석, VIEW)
```

원본은 고치지 않고 쌓기만 한다. 정제 규칙이 바뀌어도 원본부터 다시 계산할 수 있어야 하기 때문이다.

## 이벤트 22종

### 모든 이벤트가 싣는 것

| 필드 | 타입 | 채우는 주체 | |
|---|---|---|---|
| `event_id` | UUID | 클라이언트 | 멱등 키. 수집기가 Redis 로 중복을 걸러낸다 |
| `event_type` | 아래 22종 | 클라이언트 | 목록 밖이면 그 건만 버려진다 |
| `occurred_at` | ISO-8601 | 클라이언트 | **발생 즉시** 각인. 전송 시각이 아니다 |
| `user_id` | UInt32 | **서버** | 액세스 토큰에서 확정한다. 본문으로 받지 않는다 |
| `ext` | Map(≤10) | 클라이언트(선택) | 스키마 변경 없이 덧붙이는 자리 |

### 재생 중에 일어난 이벤트가 더 싣는 것

| 필드 | |
|---|---|
| `track_id` | 대상 곡 |
| `play_id` | 재생 1회를 묶는 키. `PLAY_START` 가 발급한다 |
| `entry_path` | 이 재생의 진입 경로 |

`SEARCH` 는 재생 밖의 행동이라 이 셋이 없다. 라이브러리 목록에서 누른 `LIKE` 처럼 재생 중이 아닌 피드백은 `track_id` 만 따로 받는다 — `play_id`·`entry_path` 는 비고, 그래도 구간 신호는 만들어진다.

### 이벤트별 고유 필드

"소비" 열은 이 이벤트가 파생 테이블로 이어지는지다. **원본만** 은 `listen_events` 에 남기만 하고 아직 아무 것도 읽지 않는다는 뜻이다.

| 이벤트 | 등급 | 고유 필드 | 뜻 | 소비 |
|---|---|---|---|---|
| `PLAY_START` | 필수 | `position_ms` · `rec_id` `rec_type` `rec_rank` | 재생 시작. 여기서 `play_id` 를 발급한다. 추천에서 들어왔으면 추천 3종을 함께 싣는다 | 원본만 |
| `PLAY_PROGRESS` | 필수 | `from_ms` `position_ms` | 5초 하트비트. **`[from_ms, position_ms)` 를 연속으로 들었다**는 뜻 | `mv_play_section_coverage` |
| `PLAY_PAUSE` | 권장 | `position_ms` | 일시정지 | 원본만 |
| `PLAY_RESUME` | 권장 | `position_ms` | 재개. 재생 포인터를 현재 위치로 다시 맞춘다 | 원본만 |
| `PLAY_END` | 필수 | `position_ms` | 자연 종료 — 스킵과 구분한다 | 원본만 |
| `PLAY_STALL` | 나중 | `position_ms` | 버퍼링. "안 들린 것"을 "안 좋아한 것"으로 세지 않으려고 남긴다 | 원본만 |
| `PLAY_ERROR` | 권장 | `position_ms` `reason` | 재생 실패. `reason` 은 `MEDIA_ERR_NETWORK` 같은 코드 이름 | 원본만 |
| `PLAYBACK_ABANDON` | 권장 | `position_ms` | 앱이 닫혀 끊김 — 의도한 스킵이 아니다 | 원본만 |
| `SEEK_BACK` | 필수 | `from_ms` `position_ms` | 되감아 다시 들음 — **구간 선호의 핵심 신호** | 신호 **+0.6** |
| `SEEK_FORWARD` | 필수 | `from_ms` `position_ms` | 앞으로 감기 = 구간 건너뜀 | 원본만 |
| `TRACK_SKIP` | 필수 | `position_ms` `skip_trigger` | 곡 넘김. **분류는 하지 않는다** | `v_track_skips` |
| `LIKE` | 필수 | `position_ms` `target` | 좋아요 | 신호 **+1.0** |
| `UNLIKE` | 필수 | `position_ms` `target` | 좋아요 취소 | 신호 **−0.5** |
| `DISLIKE` | 필수 | `position_ms` `target` | 관심 없음. 보통 `target=TRACK` | 신호 **−1.0** |
| `UNDISLIKE` | 권장 | `target` | 관심 없음 취소 | 원본만 |
| `SECTION_MARK` | 필수 | `start_ms` `end_ms` `position_ms` | 구간 직접 지정(온보딩용) | 신호 **+1.5** |
| `SECTION_UNMARK` | 권장 | `start_ms` `end_ms` `position_ms` | 지정 해제 | 신호 **−1.5** |
| `PLAYLIST_ADD` | 필수 | `position_ms` `playlist_id` | 플레이리스트에 담기 | 신호 **+0.8** |
| `PLAYLIST_REMOVE` | 권장 | `playlist_id` | 플레이리스트에서 빼기 | ⚠ 아래 참고 |
| `COMMENT_ADD` | 필수 | `position_ms` `content` `comment_id`(선택) | 댓글 작성. `comment_id` 는 서버가 정해주므로 없을 수 있다 | 신호 **+0.7** |
| `COMMENT_DELETE` | 필수 | `comment_id` | 댓글 삭제 | 원본만 |
| `SEARCH` | 권장 | `search_query` | 검색. 재생 맥락이 붙지 않는다 | 원본만 |

**구간 지정 이벤트가 `position_ms` 를 함께 싣는 이유.** `mv_user_section_signals` 는 `dictGet(..., position_ms)` 한 점으로 구간을 찾고 `position_ms > 0` 을 요구한다. `start_ms`/`end_ms` 만 보내면 조건에 걸려 **가장 강한 긍정 신호(+1.5)가 조용히 사라진다.** 그래서 라이브러리가 `position_ms = start_ms` 를 함께 넣는다. 지정 범위가 여러 구간에 걸치면 시작점이 속한 구간 하나만 잡힌다 → [open-questions B5](../open-questions.md).

> ⚠ **`playlist_id` 는 서버의 `playlist_id` 여야 한다.** 한때 프론트가 `Date.now()` 로 만든 화면 전용 번호를 실어 보냈고, 그 값(≈1.7조)이 `UInt32` 를 넘어 **그 이벤트가 실린 배치 전체가 400 으로 거부됐다** — 같이 실려 가던 재생 기록까지 사라졌다. 수집기가 숫자 필드를 `Integer` 로 받아 Jackson 이 역직렬화에서 터진 것이다.
>
> 지금은 양쪽에서 막는다. 수집기는 숫자를 `Long` 으로 받아 건별로 좁히고(값 집합 필드를 `String` 으로 받는 것과 같은 이유), 프론트는 `UInt32` 범위가 아닌 번호를 0("모름")으로 바꿔 보낸다. 플레이리스트가 서버에 저장되면서(`frontend/lib/playlists.ts`) 실제로는 진짜 번호가 실린다.

> ⚠ **`PLAYLIST_REMOVE` 의 −0.8 은 지금 발동하지 않는다.** `mv_user_section_signals` 목록에는 들어 있지만 이 이벤트에는 재생 위치라는 것이 없어 `position_ms` 가 0 이고, MV 의 `position_ms > 0` 조건에 걸린다. 플레이리스트에서 빼는 행동은 애초에 *구간* 신호가 아니라 *곡* 신호라 이 자리가 맞지 않는다 → [open-questions B5](../open-questions.md).

**22종 중 파생까지 이어지는 것은 11종이다.** 구간 신호 9종(`mv_user_section_signals`, 그중 `PLAYLIST_REMOVE` 는 위 이유로 실질 8종) + `TRACK_SKIP`(`v_track_skips`) + `PLAY_PROGRESS`(`mv_play_section_coverage`). 나머지 11종은 원본에만 쌓인다 — 지금 읽지 않을 뿐 나중에 해석을 붙일 수 있도록 남긴다.

### 값 집합

| 필드 | 값 |
|---|---|
| `entry_path` | `RECOMMEND` · `SEARCH` · `PLAYLIST` · `QUEUE` · `RECENT` · `ARTIST` |
| `skip_trigger` | `NEXT_BUTTON` · `OTHER_TRACK` |
| `target` | `TRACK` · `SECTION` |
| `rec_type` | `HOME` · `SECTION` · `CUSTOM` |

`AUTOPLAY` 는 `entry_path` 에서 뺐다. **검색 결과에서 이어 재생되는 것은 `SEARCH` 로 남긴다** — 그래야 나중에 "검색 유입이었는데 추천이 정확했다" 를 구분해 검색 노출 순서를 조정할 수 있다. 큐가 알아서 넘긴 재생은 `AUTOPLAY` 가 아니라 `QUEUE` 다. 그 순서를 만든 것도 사용자이므로 추천이 한 선택으로 셀 수 없다.

**목록이 끝나 추천이 이어 붙인 곡은 `RECOMMEND` 다** (S15P21A204-439). 사용자가 누르지는 않았지만 곡을 고른 것은 추천이다. 값을 따로 파지 않는 이유와 그 대가는 `decision/0014` 에 있다.

**`RECOMMEND` 는 화면의 기본값이 아니다.** `QUEUE`(재생 목록에서 다시 꺼냄)·`RECENT`('최근 들은 음악' 에서 다시 틀기)·`ARTIST`(아티스트 패널에서 그 아티스트의 다른 곡 찾아 듣기)는 **어느 화면에서 눌러도 뜻이 같아** 화면으로 갈음하지 않는다. 셋 모두 사용자가 스스로 꺼낸 재생이라, `RECOMMEND` 에 섞이면 추천이 한 일과 사용자가 원래 알던 곡이 한 통에 들어가 추천 성과가 부풀려진다. 추천 지표를 볼 때는 `entry_path = 'RECOMMEND'` 에 더해 `rec_id` 가 실렸는지까지 본다.

> ⚠ **값을 늘릴 때는 API 의 `EntryPath` enum 을 먼저 늘린다.** `normalize()` 는 모르는 값을 400 으로 거절하지 않고 **빈 문자열로 떨어뜨린다.** 프론트가 먼저 나가면 그 사이 재생은 경로 없이 원본 테이블에 박히고 되돌릴 수 없다. ClickHouse 쪽은 `LowCardinality(String)` 이라 스키마 변경이 필요 없다.

값 검증은 수집기가 한다. ClickHouse `CONSTRAINT` 로 막지 않는다 — 한 건이 어긋나면 블록 전체가 거부돼 Kafka 소비가 멈춘다.

## 프론트가 지켜야 할 것

- **매번 재생기에서 위치를 실제로 읽어서 `position_ms` 를 만든다.** 화면이 들고 있는 값을 쓰면 백그라운드 탭에서 타이머가 스로틀링될 때 뒤처진다. 실측하면 타이머가 늘어져도 시각이 정확하다.
- **구간 번호를 보내지 않는다.** 밀리초만 보내고 구간 매핑은 백엔드가 한다.
- **`occurred_at` 은 이벤트가 발생한 그 순간 각인한다.** 전송 시각이 아니다. 배치로 묶여 나가도 각 이벤트는 자기 시각을 들고 간다.
- `entry_path` 는 `PLAY_START` 뿐 아니라 **그 재생에 속한 모든 이벤트**에 싣는다.
- **가중치도 분류도 계산하지 않는다.** 원시 신호만 남기고 해석은 분석 시점에 한다 → [decision/0015](../decision/0015-derive-interpretation-at-query-time.md).

이 규칙은 `frontend/lib/analytics/` 가 지킨다. 화면에서 `fetch` 를 직접 부르지 말고 이 모듈을 쓴다.

## `listen_events` 주요 필드

| 필드 | 의미 |
|---|---|
| `event_id` UUID | 중복 적재 방지. 클라이언트가 발급하고 수집기가 Redis 로 걸러낸다 |
| `event_type` | 행동 종류 |
| `user_id` UInt32 / `track_id` UInt32 | 대상 |
| `occurred_at` / `ingested_at` | **발생 시각과 적재 시각을 분리한다.** 오프라인 후 일괄 전송 대비 |
| `play_id` UUID | 재생 한 번을 묶는 키. `PLAY_START` 에서 발급 |
| `entry_path` | 이 재생에 어떻게 진입했는가. 재생의 모든 이벤트에 실린다 |
| `rec_id`, `rec_type`, `rec_rank` | 어느 추천에서 나온 반응인지 추적 (`PLAY_START` 에만) |
| `position_ms` | **구간 판정의 근거.** 없거나 0 이면 신호가 만들어지지 않는다 |
| `from_ms` | **두 가지 의미로 쓴다.** `SEEK_*` 에서는 seek 직전 위치, `PLAY_PROGRESS` 에서는 직전 하트비트의 위치 |
| `start_ms` / `end_ms` | 구간 지정형 이벤트의 범위 |
| `skip_trigger` | 스킵을 무엇으로 했는가 |
| `ext` Map | 스키마 변경 없이 추가 정보를 넣는 자리 |

**저장 설계**: `ReplicatedReplacingMergeTree`(`ingested_at` 기준 중복 제거) · `PARTITION BY toYYYYMM(occurred_at)` · `ORDER BY (user_id, occurred_at, event_id)` · **TTL 18개월** · `cityHash64(user_id)` 로 샤딩.

> ⚠ 다만 **Kafka 적재 경로는 이 샤딩 키를 타지 않는다.** 이벤트는 그것을 소비한 노드의 로컬 테이블에 들어가므로, 한 사용자의 이벤트가 두 샤드에 흩어질 수 있다. 사용자 단위 집계는 반드시 Distributed 테이블에서 한다.

### ID 타입은 MySQL 이 기준이다

JPA 엔티티의 PK 는 전부 `Integer`(INT AUTO_INCREMENT) 다. ClickHouse 는 여기에 맞춰 **`UInt32`** 를 쓴다 — `user_id` · `track_id` · `playlist_id` · `comment_id`.

`tracks` · `playlists` · `comments` 엔티티는 **아직 MySQL 에 없다.** 만들 때 `Integer` PK 를 쓰지 않으면 이 스키마와 어긋난다. 과거에 `user_id` 가 `UInt64` 로 잘못 선언돼 있었고, 고쳐도 **이미 띄운 클러스터에는 반영되지 않아** 재생성이 필요했다 → [failure/clickhouse-and-kafka.md](../failure/clickhouse-and-kafka.md)

## 신호 가중치

`mv_user_section_signals` 가 이벤트를 구간 신호로 바꾼다. **가중치가 스키마에 하드코딩돼 있다.**

| 이벤트 | 가중치 | |
|---|---:|---|
| `SECTION_MARK` | **+1.5** | 구간을 직접 찍음 — 가장 강한 긍정 |
| `LIKE` | +1.0 | |
| `PLAYLIST_ADD` | +0.8 | |
| `COMMENT_ADD` | +0.7 | |
| `SEEK_BACK` | +0.6 | 되감아 다시 들음 — 암묵적 긍정 |
| `UNLIKE` | −0.5 | |
| `PLAYLIST_REMOVE` | −0.8 | |
| `DISLIKE` | −1.0 | |
| `SECTION_UNMARK` | **−1.5** | |

구간 판정은 `dictGet('dict_track_sections', 'section_uid', track_id, position_ms)` — RANGE_HASHED 딕셔너리로 재생 위치를 구간에 매핑한다.

**신호가 만들어지지 않는 조건**: `track_id = 0`, `position_ms = 0`, 또는 구간 매핑 실패(빈 문자열). 이벤트는 남지만 신호는 안 생긴다.

> ⚠ **가중치를 바꾸면 과거 신호와 새 신호가 섞인다.** `user_section_signals` 는 계산된 값을 저장하므로 소급 적용되지 않는다.

> ⚠ **이 테이블은 [decision/0015](../decision/0015-derive-interpretation-at-query-time.md) 를 어기는 유일한 자리다.** 해석은 쿼리 시점에 계산해야 소급되는데, 여기만 MV 가 값을 저장한다. 덧붙여 이 MV 는 `target` 을 보지 않아 명세의 `LIKE(target=TRACK)` = 전 구간 긍정과도 어긋난다 → [open-questions B5](../open-questions.md).

수집 계층에는 가중치가 없다. `listen_events` 에 `weight` 컬럼은 없고 프론트도 보내지 않는다 — 어떤 행동이 얼마나 긍정인지는 분석·모델의 판단이다.

## 스킵 해석

**수집과 해석이 나뉜다.** 프론트는 무엇으로 넘겼는지(`skip_trigger`)와 언제 넘겼는지(`position_ms`)만 보낸다. 프론트는 "이 사용자가 이 구간을 전에 들었는지" 도, 구간 경계가 어디인지도 모르기 때문에 분류를 할 수 없다.

분류는 `v_track_skips` 가 **쿼리 시점에** 만든다. 일반 VIEW 라 저장되지 않으므로, 기준(5초 / 20% / 80%)을 고치면 과거 이벤트까지 새 기준으로 다시 해석된다 → [decision/0015](../decision/0015-derive-interpretation-at-query-time.md).

| `skip_type` | 조건 | 가중치 |
|---|---|---:|
| `SKIP_AFTER_HOOK` | 이 구간을 전에 절반 넘게 들은 재생이 있음 | 0.0 |
| `EARLY_SKIP` | 5초 이내 또는 구간의 20% 이내 | 추천 유입 + `NEXT_BUTTON` 이면 **−1.0**, 그 외 −0.4 |
| `LATE_SKIP` | 구간의 80% 이상 들음 | **+0.3** (긍정) |
| `MID_SKIP` | 나머지 | 0.0 |

명세가 정의한 것은 앞의 세 가지다. `MID_SKIP` 은 "처음 듣는 곡을 중간쯤에서 넘겼다" 는 나머지 경우를 담는 자리로 저장소가 덧붙였다. 가중치 0 이라 판단을 보류한다는 뜻이다.

읽어 둘 점:

- **끝까지 듣고 넘기는 것은 긍정 신호다** (`LATE_SKIP` +0.3).
- 이미 익숙한 구간을 넘기는 것은 취향과 무관하다고 본다 (`SKIP_AFTER_HOOK` 0.0).
- 부정 판정을 **추천으로 들어온 경우에만** 강하게 준다. 사용자가 직접 검색해 튼 곡을 넘긴 것에 같은 점수를 주면 추천 평가가 왜곡되기 때문이다.

## 커버리지

`play_section_coverage` 는 재생마다 구간을 얼마나 들었는지 기록한다. `mv_play_section_coverage` 가 `PLAY_PROGRESS` 하트비트에서 만든다.

하트비트 하나는 **`[from_ms, position_ms)` 오디오 구간을 연속으로 들었다**는 뜻이다. 프론트가 재생 포인터를 들고 있다가 하트비트마다 "직전 포인터 → 현재 위치" 를 싣고 포인터를 갱신한다. seek 하면 포인터를 새 위치로 옮기므로 건너뛴 범위는 들어오지 않는다.

MV 는 이 구간을 **곡 구간 경계로 쪼갠다.** 하트비트가 두 구간에 걸치면 두 행이 나온다. `dictGet` 대신 `v_track_sections_current` 와 JOIN 하는 이유가 이것이다 — 딕셔너리는 한 지점이 속한 구간만 돌려주므로 걸친 재생을 나눌 수 없고, 끝점 기준으로 몰아주면 경계 뒤 구간이 과대 집계된다. 하필 그 편향이 INTRO 노출 보정과 정면으로 부딪힌다.

**엔진이 `SummingMergeTree(listened_ms)` 다.** 하트비트마다 부분합 행이 쌓이고 병합될 때 합쳐진다. 병합 시점을 보장할 수 없고 부분합이 두 샤드에 흩어질 수도 있으므로, **읽을 때 Distributed 테이블에서 `(user_id, play_id, section_uid)` 로 GROUP BY 해서 합산한다.** `v_track_skips` 의 `SKIP_AFTER_HOOK` 판정이 그렇게 되어 있다 — 부분합에 바로 조건을 걸면 60% 들은 재생이 30%+30% 로 남아 있는 동안 판정을 통과하지 못한다.

`reached` 컬럼은 두지 않는다. 행이 존재한다는 것 자체가 "재생 헤드가 이 구간에 닿았다" 는 뜻이다.

## 현재 상태

| | 상태 |
|---|---|
| 스키마·클러스터·Kafka 연동 | ✅ 구현 |
| 신호 가중치 · 스킵 해석 · 커버리지 MV | ✅ 구현 |
| 수집 엔드포인트 `POST /api/v1/events` | ✅ 구현 (`event` 도메인) |
| 프론트 추적 SDK (`frontend/lib/analytics/`) | ✅ 구현 — HTTP 전송 |
| 화면 → SDK 호출 (22종 발화) | ✅ 연결 (`hooks/use-mupid.ts` · `lib/track-comments.ts`) |
| `rec_impressions` 적재 | ❌ 없음 |

수집 경로는 끝까지 이어졌다. 남은 것은 추천을 서빙할 때 `rec_impressions` 를 남기는 일이다.

`rec_id` · `rec_type` · `rec_rank` 는 **추천 자리마다 다르다.** 추천 응답이 상관 키를 주는 곳에서만 실린다.

| 추천 자리 | `rec_id` | `rec_type` |
|---|---|---|
| 홈 "취향에 이어지는 음악" (`/recommendation/next`) | ✅ | `SECTION` |
| 오늘의 Moment (`/recommendation/daily`) | ✅ | `HOME` |
| └ 배치 미실행 시 폴백 묶음 (`첫 모먼트`) | ❌ 저장하지 않아 `recommendation_id` 가 `0` 이다 — `0` 은 가리킬 대상이 없어 싣지 않는다 | — |
| 챗봇 추천 곡 | 서버가 `recommendation_id` 를 준 턴만 | `SECTION` |
| 닮은 구간 (`/music/{id}/similar-section`) | ❌ 개인화가 없어 서버가 발급하지 않는다 | — |
| 추천 서버 무응답 시 홈 대체 목록 | ❌ 추천이 아니다 (`pickForYou` 가 카탈로그로 채운다) | — |

> **추천 성과를 볼 때는 `entry_path = 'RECOMMEND'` 만으로 세지 않는다.** 그 값은 화면으로 갈음한 추정이라 위 표의 아래 두 줄이 섞인다. `rec_id != ''` 를 함께 걸면 추천 응답이 실제로 만든 재생만 남는다 — 대신 닮은 구간은 빠지므로, 그쪽을 세려면 `rec_impressions` 가 필요하다.

## 전송이 실패했을 때

프론트 큐가 배치를 되돌릴지 버릴지는 응답으로 정한다.

| 응답 | 처리 |
|---|---|
| 2xx | 큐에서 지운다 |
| 401 · 408 · 429 | 되돌리고 다시 보낸다 |
| 그 밖의 4xx | **버린다** |
| 5xx · 네트워크 오류 | 되돌리고 다시 보낸다 |

**4xx 를 되돌리지 않는다.** 규약에 어긋난 배치는 다시 보내도 같은 답이 오는데, 되돌려 두면 큐 앞을 영원히 막아 뒤따르는 멀쩡한 이벤트까지 함께 잃는다(큐 상한에 닿으면 오래된 것부터 밀려난다). 한 배치를 포기하는 쪽이 싸다.

401 만 예외다. "아직 로그인하지 않았다" 는 고쳐질 수 있는 상태라, 이벤트는 큐에 남았다가 로그인 뒤 함께 나간다.

재시도 간격은 연속 실패마다 두 배가 되고 5분에서 멈춘다. 탭이 가려질 때의 강제 전송은 이 간격을 기다리지 않는다.

수집기가 `rejected` 로 세는 건은 응답이 202 라 버려지지 않는다 — 배치의 나머지는 정상 처리됐고, 어긋난 한 건은 서버가 이미 버렸다.

## 이벤트를 추가하려면

1. `listen_events_local` 과 `kafka_listen_events` **양쪽에** 필드를 추가한다 (또는 `ext` Map 을 쓴다).
2. `mv_kafka_to_events` 의 SELECT 에도 넣는다. **MV 는 만들어질 때의 쿼리로 고정된다** — 테이블만 고치면 새 필드가 안 들어온다.
3. 신호로 쓸 이벤트면 `mv_user_section_signals` 의 `multiIf` 와 `WHERE ... IN (...)` 에 추가한다.
4. 값 집합이 있는 필드면 수집기의 검증에도 추가한다.
5. `api/clickhouse/verify.sh` 로 검증한다. 오브젝트를 늘렸으면 2단계의 기대값도 함께 고친다. **CI 는 이 영역을 검사하지 않는다.**
