# 용어 사전

코드와 스키마에서 쓰는 말을 한자리에 모은다. 새 용어를 만들기 전에 여기 있는지 먼저 본다.

## 도메인 용어

| 용어 | 의미 | 어디에 있나 |
|---|---|---|
| **Track** | 곡 하나. 외부 카탈로그(Jamendo) 기준 `track_id` | ClickHouse `track_id UInt32` |
| **Section (구간)** | 곡 안의 한 구간. **추천과 피드백의 기본 단위** | `track_sections`, `section_uid` |
| `section_uid` | 구간의 고유 키. 신호·추천·커버리지가 전부 이 키로 연결된다 | 전 분석 테이블 |
| `analysis_version` | 구간 분석 버전. 버전이 다르면 구간 정의가 다르다 | `track_sections`, 현재 `'v1'` |
| **Play** | 재생 한 번. `play_id`(UUID)로 묶인다 | `listen_events.play_id` |
| `entry_path` | 이 재생에 어떻게 들어왔는가. `RECOMMEND` · `SEARCH` · `PLAYLIST` · `QUEUE` · `RECENT` · `ARTIST`. **`RECOMMEND` 는 기본값이 아니다** — 추천 성과는 `rec_id` 와 함께 센다 | `listen_events`, 재생의 **모든** 이벤트에 기록 |
| **Signal (신호)** | 행동에 가중치를 붙인 선호 표현 | `user_section_signals` |
| **Impression (노출)** | 추천을 사용자에게 보여준 기록 | `rec_impressions` |
| `rec_id` | 추천 한 묶음의 식별자. 노출↔반응을 잇는다 | `listen_events.rec_id`, `rec_impressions.rec_id` |

## 피드백 구분

| 구분 | 뜻 | 예 |
|---|---|---|
| **명시적(explicit)** | 사용자가 의도를 직접 표현 | `LIKE`, `SECTION_MARK`, `PLAYLIST_ADD` |
| **암묵적(implicit)** | 행동에서 선호를 추정 | `SEEK_BACK`, 완주, 구간 반복, 초반 `TRACK_SKIP` |

암묵적 신호는 **한 번으로 결론 내지 않는다.** Seek Back 이 늘 선호를 뜻하지 않고, 재생 중단은 전화 때문일 수도 있다.

## 버전 필드

추천 결과를 나중에 재현·비교하려면 무엇으로 만들었는지가 남아야 한다. `rec_impressions` 에 셋이 함께 기록된다.

| 필드 | 무엇의 버전 |
|---|---|
| `analysis_version` | 구간 분할 |
| `embedding_version` | 임베딩 모델 |
| `model_version` | 추천 모델 |

## 인프라 용어

| 용어 | 의미 |
|---|---|
| `mupid` (클러스터) | 샤딩되는 이벤트 데이터용 ClickHouse 클러스터 |
| `mupid_all` (클러스터) | 전 노드 복제용. `track_sections` 처럼 모든 노드가 같은 사본을 갖는 테이블 |
| `*_local` | 실제 데이터가 있는 로컬 테이블 |
| 접미사 없는 이름 | `Distributed` 엔진. 쿼리는 이쪽에 한다 |
