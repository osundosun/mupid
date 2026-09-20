# 0018. 재생·검색 기록은 청취 이벤트에서 갈라 쓴다

**상태** 채택 · 구현 완료

## 맥락

화면의 "최근 감상" 은 MySQL `play_histories` 를 읽어야 빠르다. 그런데 같은 사실
(이 사람이 이 곡을 재생했다)은 이미 청취 이벤트 `PLAY_START` 로 Kafka → ClickHouse
에 쌓이게 되어 있었다([0005](0005-kafka-clickhouse-for-listen-events.md) ·
[0014](0014-event-collector-in-api.md)). 검색어도 마찬가지로 `SEARCH` 이벤트와
`search_histories` 표가 겹친다.

**같은 사실이 두 곳에 쌓인다**는 것이 [api-todo](../api-todo.md) P1-5 가 "정할 것"
으로 남겨 둔 문제였다.

## 후보

| | 방법 | 문제 |
|---|---|---|
| A | 화면이 `POST /api/v1/events` 와 `POST /api/v1/user/history` 를 **둘 다** 부른다 | 같은 사실을 두 번 보낸다. 한쪽만 실패하면 두 저장소가 어긋나고, 호출을 빠뜨리기 쉽다 |
| B | "최근 감상" 을 **ClickHouse 에서 읽는다** | 저장소가 하나로 줄지만 API 서버에 ClickHouse 조회 계층이 없다. 화면 한 줄 그리자고 분석 클러스터를 서비스 경로에 끌어들이는 것도 과하다 |
| C | 수집기가 **한 요청에서 갈라 쓴다** | 수집기가 MySQL 을 건드리게 된다 |

## 결정

**C — `POST /api/v1/events` 가 `PLAY_START` · `SEARCH` 두 종류만 MySQL 사본으로
남긴다.**

- 화면은 **한 번만** 보낸다. 호출부가 하나라 어긋날 여지가 없다.
- 두 저장소의 역할이 갈린다. **MySQL = 서비스용**(지금 이 사람의 최근 감상),
  **ClickHouse = 분석용**(집계·추천). 중복이 아니라 용도가 다른 사본이다.
- 22종 중 둘뿐이다. 특히 `PLAY_PROGRESS` 는 5초 하트비트라 여기 들어오면 안 된다.

### 지키는 규칙

1. **Kafka 로 보낸 뒤에 남긴다.** 분석 적재가 먼저다.
2. **실패해도 던지지 않는다.** 로그만 남긴다. "최근 감상" 때문에 배치 전체가
   실패로 응답되면 클라이언트가 같은 이벤트를 통째로 재전송한다.
3. **멱등 처리 뒤에 남긴다.** 같은 `event_id` 가 다시 오면 이미 걸러진 뒤라
   기록이 두 번 쌓이지 않는다.
4. **없는 곡이면 조용히 넘긴다.** 이벤트의 `track_id` 가 MySQL `music_id` 라는
   보장이 아직 없다([open-questions C5](../open-questions.md)).

`entry_path` 가 `RECOMMEND` 면 `selector = AI`, 아니면 `USER` 다. 추천 사유는
`reason` 을 100자로 잘라 넣는다.

### 추천 사유는 이벤트에 없어서 따로 들고 있다 (2026-09-20 보강)

위 문장은 "추천 사유는 `reason` 을 100자로 잘라 넣는다" 고 적었지만, **그 `reason` 은
재생 이벤트에 실려 오지 않는다.** `PLAY_START` 의 고유 필드는
`position_ms` · `rec_id` · `rec_type` · `rec_rank` 뿐이고, `reason` 은 `PLAY_ERROR`
(`MEDIA_ERR_NETWORK` 같은 코드 이름) 전용이다(`domain/listening-events.md`). 그래서
수집기가 넘기던 `item.reason()` 은 **PLAY_START 에서 구조적으로 항상 null** 이었고,
`play_histories.recommendation_reason` 이 `selector = AI` 인 행에서도 늘 비어 있었다
(실측: AI 38행 / 38행 NULL). 저장 코드는 처음부터 있었으므로 **저장 로직의 문제가
아니었다.**

사유는 사용자가 곡을 누르기 **전에 본 추천 응답**에만 있고, 그 응답을 만드는 곳은
`api/` 다. 그래서 클라이언트 계약을 바꾸지 않고 서버가 잇는다 — 사유 문장이 나가는
자리(`NextTrackService.next` · `AiRecommendToolImpl.recommend`)에서
`RecommendationReasonCache` 에 적어 두고, 재생이 들어올 때 화면이 되돌려주는
`rec_id`(+`track_id`)로 되짚는다.

- 키는 `recommendation:reason:{rec_id}:{music_id}` 다. 추천 하나가 같은 곡을 두 줄로
  줄 수 있어(순위가 다르면 다른 구간) 곡까지 키에 넣는다.
- **해석이 아니라 그때 화면에 보여 준 문장**이라 재현할 수 없다. 그래서 MySQL 이 아니라
  캐시에 둔다 — `daily_recommendation_tracks` 는 하루 한 번 도는 배치 경로에만 있고,
  이어 듣기는 저장되지 않으며, 그 배치 사유는 화면이 상관 키를 되돌려주지 않아 되짚을
  수조차 없다.
- **부가 정보로 다룬다.** 조회가 실패해도 재생 기록은 남는다. 선곡 주체(`selector`)는
  진입 경로만으로 정해지므로 사유와 무관하다.
- TTL 7일. Redis 에 `maxmemory-policy` 가 없어 무기한 키는 컨테이너를 죽인다
  ([0022](0022-play-queue-in-redis.md) 와 같은 지적). 수집기가 받아 주는 과거 한도
  (기본 72시간)보다 길어야 늦게 도착한 재생도 사유를 찾는다.

**남은 한계** — 「오늘의 모먼트」는 저장 경로라 상관 키를 되돌려받지 못한다. 그 곡을
재생하면 진입 경로가 `RECOMMEND` 인데 `rec_id` 가 비어 사유가 붙지 않는다. 그쪽까지
채우려면 화면이 `daily_recommendation_tracks` 의 사유에 상관 키를 싣거나, 배치 사유를
`music_id` 로도 되짚을 수 있어야 한다. 티켓 [S15P21A204-386] 은 이어 듣기 경로를 고쳤고,
그 한계는 [failure/frontend.md](../failure/frontend.md) 에 적어 두었다.


## 따라온 것

- **적재 API 가 없다.** `GET /api/v1/user/history` 만 있다. 기록을 남기려면
  청취 이벤트를 보내야 한다.
- 그래서 프론트의 청취 이벤트 SDK 를 실제로 붙였다. 지금까지 `lib/analytics` 는
  만들어져만 있고 **어디서도 부르지 않았다.** 전송을 콘솔 싱크에서
  `createHttpTransport` 로 바꾸고, `PLAY_START`(재생 시작)와 `SEARCH`(검색어
  입력이 멎었을 때)를 연결했다. 나머지 20종은 아직 호출부가 없다.
- 데모 곡(1~8)은 서버에 없으므로 이벤트를 보내지 않는다.
- 읽을 때 **같은 곡은 한 번만** 나온다. 기록은 들을 때마다 쌓이지만 "최근 감상"
  에 같은 곡이 줄줄이 나오면 쓸모가 없다.

## 다시 볼 것

`PLAY_START` 는 곡을 튼 순간이지 들은 것이 아니다. 3초 만에 넘긴 곡도 "최근
감상" 에 남는다. 더 정확히 하려면 `PLAY_END` 나 일정 재생 시간을 넘긴
`PLAY_PROGRESS` 를 기준으로 삼아야 하는데, 그러면 방금 튼 곡이 목록에 바로
나타나지 않는다. 지금은 즉시성을 택했다.
