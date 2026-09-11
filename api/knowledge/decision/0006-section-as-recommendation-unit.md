# 0006. 추천 단위를 곡이 아니라 구간(section)으로 잡는다

**상태** 채택 · 도메인 핵심 결정

## 맥락

첫 회고(2026-08-28)에서 나온 문제의식이 출발점이다 — *"추천은 너무 보여지지 않는 부분에서 작동하는 시스템적인 성향이 크기에 설득력이 강하지 않다. 사용자의 능동적인 행동을 기준으로 조정해보면 좋을 것 같다."*

장르가 비슷한 곡을 추천하는 것은 기존 서비스와 차별점이 없다는 판단도 함께 있었다. 곡 전체를 하나의 값으로 요약해서는 "이 곡의 이 부분이 좋았다" 를 표현할 수 없다.

## 결정

추천과 피드백의 단위를 **곡 전체가 아니라 곡 안의 구간**으로 잡는다.

- 곡을 구간으로 나누고 각 구간에 `section_uid` 를 부여한다 (`track_sections`).
- 사용자 행동을 구간에 귀속시킨다. 이벤트의 `position_ms` 를 딕셔너리(`dict_track_sections`)로 조회해 어느 구간에서 일어난 행동인지 판정한다.
- 선호도 신호를 구간 단위로 축적한다 (`user_section_signals`).
- 추천 노출도 구간 배열로 기록한다 (`rec_impressions.section_uids`).

## 결과

- "곡을 좋아함" 보다 훨씬 촘촘한 신호를 얻는다. 같은 곡이라도 어느 구간에서 반복 재생했는지가 남는다.
- 대신 **구간 정의가 바뀌면 과거 신호의 의미가 달라진다.** 그래서 `analysis_version` 을 두고 현재 버전만 보는 뷰(`v_track_sections_current`, 현재 `'v1'` 하드코딩)를 통해 조회한다.
- 구간 분석이 없는 곡은 추천 대상이 될 수 없다. 카탈로그와 분석 파이프라인이 추천의 전제가 된다.
- 이벤트에 `position_ms` 가 없거나 0 이면 신호가 만들어지지 않는다 (MV 의 `WHERE` 조건).

## 근거

`api/clickhouse/init/01-schema.sql` (`track_sections`, `user_section_signals`, `mv_user_section_signals`), `회고/2026-08-28.md`, [domain/track-sections.md](../domain/track-sections.md)
