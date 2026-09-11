# 도메인 (domain)

**무엇을 만들고 있는가.** 코드가 아니라 문제를 다룬다. 기능을 설계하거나 스키마를 바꾸기 전에 읽는다.

한 줄 요약: **사용자의 청취 행동을 모아, 좋아한 "구간"과 비슷한 다른 곡의 구간을 추천하는 서비스.**

| 문서 | 다루는 것 |
|---|---|
| [glossary.md](glossary.md) | 용어 사전 |
| [user-account.md](user-account.md) | 회원·이메일 인증·파일 — MySQL 에 있는 유일한 도메인 |
| [track-sections.md](track-sections.md) | 곡과 구간, `analysis_version`, 재생 위치 → 구간 판정 |
| [listening-events.md](listening-events.md) | 청취 이벤트 22종, 이벤트별 필드, 신호 가중치, 스킵 해석 |
| [event-spec.html](event-spec.html) | 위 문서를 렌더한 한 장짜리 페이지 (브라우저로 열어 본다) |
| [recommendation.md](recommendation.md) | 선호 신호 축적, 노출 기록, 평가 |

## 핵심 개념 하나

**추천과 피드백의 단위가 곡이 아니라 곡 안의 구간(`section_uid`)이다.**

이것이 도메인 설계 전부를 좌우한다. 이벤트에 `position_ms` 를 싣는 것도, `track_sections` 를 모든 노드에 복제하는 것도, `analysis_version` 을 두는 것도 전부 여기서 나온다. 배경은 [decision/0006](../decision/0006-section-as-recommendation-unit.md).

## 구현 상태를 먼저 볼 것

스키마는 상당히 갖춰져 있지만 **이를 채우는 코드가 대부분 없다.** 문서를 읽고 "이미 동작하는 기능" 으로 오해하지 않도록 정리한다.

| 영역 | 상태 |
|---|---|
| 회원·인증·프로필 (MySQL) | ✅ 구현 |
| 청취 이벤트 스키마·Kafka·ClickHouse 파이프라인 | ✅ 구현 |
| 신호 가중치·스킵 해석·구간 커버리지 (MV / VIEW) | ✅ 구현 |
| 프론트 추적 SDK (`frontend/lib/analytics/`) | ✅ 구현 (전송은 아직 콘솔 싱크) |
| 이벤트 수집기 (`POST /api/v1/events`) | ✅ 구현 → [decision/0014](../decision/0014-event-collector-in-api.md) |
| `track_sections` 를 채우는 분석 파이프라인 | ❌ 없음 |
| `play_section_coverage` 적재 | ✅ `mv_play_section_coverage` 구현 (채울 `PLAY_PROGRESS` 가 아직 안 들어온다) |
| 추천 계산 · `rec_impressions` 기록 | ❌ 없음 (스키마만 있음) |
| 온보딩 (`PENDING → ACTIVE`) | ❌ 없음 |

## 알려진 결함

| | |
|---|---|
| 회원 탈퇴가 하드 삭제 | 정책은 soft delete → [decision/0013](../decision/0013-soft-delete-policy.md) |

## 출처

전부 `api/clickhouse/init/01-schema.sql` 과 JPA 엔티티에서 왔다. **이 저장소의 코드·스키마에 있는 것만 기록한다.**
