# 음원 도착 후 실행 절차

SeaweedFS 에 음원을 올린 **다음**부터의 절차다. 곡 1,063 · 구간 10,007 기준이고,
숫자는 전부 `ai_server/tests/test_e2e_seed.py` 를 실제로 돌려 나온 값이다. 추정치는 없다.

전 과정은 음원 파일을 읽지 않는다. 음원은 사용자가 듣기 위한 것이고, 추천은 이미 만들어
둔 색인(`moments_emb.npy` 등)으로 계산한다. 그래서 **음원이 늦어도 이 절차는 먼저 밟을 수
있다** — 실제로 시험이 그렇게 돈다.

## 0. 전제

| 항목 | 값 |
| --- | --- |
| MySQL | `mupid` 스키마, 30개 표 |
| ClickHouse | 2노드(`mupid_all`), `default` DB |
| 색인 산출물 | `moments.parquet` 외 10개, 약 537MB |
| 추천 풀 | `moments.source ∈ {flow, jamendo}` = 곡 1,063 · 구간 10,007 |

`music_section_analysis.external_id` 는 실제 DB 에서 `varchar(64)` + UNIQUE
(`uk_music_section_analysis_external_id`)다. **`api/knowledge/domain/erd.sql` 은 아직
`VARCHAR(50)` 이고 UNIQUE 도 없다** — 문서가 실제 DB 보다 뒤처져 있다. DDL 문서는 DB·BE팀
소관이라 AI 쪽에서 고치지 않았다.

## 1. 곡 적재

참조 데이터를 먼저 넣는다. **이 차례를 건너뛸 수 없다** — `artists.image_file_id` 와
`albums.image_file_id` 가 NOT NULL + FK 다.

    object_storage_files(1행) -> artists -> albums -> music(1,063행)

`music.image_file_id` 는 NULL 을 받는다. 그래서 커버 이미지 없이 곡 행만 넣을 수 있다.
`is_deleted` 는 NOT NULL 인데 기본값이 없다 — JPA 엔티티가 채워 주므로 Spring 경로는
모르고 지나가지만, 원시 SQL 로 넣을 때는 직접 줘야 한다.

| 기대 | 확인 |
| --- | --- |
| `music` 1,063행 | `SELECT COUNT(*) FROM music` |

실패하면: FK 오류면 참조 데이터 차례를, `Field 'is_deleted' doesn't have a default value`
면 INSERT 컬럼 목록을 본다.

## 2. 구간 API

> **이 API 는 아직 없다. BE 대기 사항이다.** 이 저장소 어디에도 구현이 없어서, 지금
> 부르면 404(`MUPID_NOT_FOUND`, "Endpoint가 존재하지 않습니다")가 돌아온다. 원인이
> "아직 만들어지지 않았다" 라는 것이 응답에 드러나지 않으니, 404 를 보면 먼저 이 줄을
> 떠올릴 것. 계약은 `api/docs/AI/요구 1 · 구간 분석 결과 적재` 에 있고 담당은 BE 다.
>
> 그때까지는 **3번의 내보내기 쿼리가 보는 모양대로** MySQL 에 직접 넣는다
> (`music_section_analysis` 의 music_id · section_label · `index` · start_time_ms ·
> end_time_ms · external_id). 임시방편이라는 것을 분명히 해 둔다 — 아래의 upsert 와
> 검증이 전부 빠지므로, 재분석으로 구간이 바뀐 곡에서 `music_section_analysis_id` 가
> 새로 발급되고 `id_map` 과 ClickHouse 딕셔너리가 옛 번호를 든 채 남는다. **그 상태에서도
> 서비스는 정상처럼 뜬다.** 아래 내용은 API 가 생긴 뒤의 절차다.

    PUT /api/v1/internal/music/{musicId}/section
    X-Internal-Token: <MUPID_RECOMMENDER_INTERNAL_TOKEN 과 같은 값>

곡당 한 번, 총 1,063회. 응답의 `sections[]` 가 `external_id` 와 발급된
`music_section_analysis_id` 를 짝지어 준다. **숫자 ID 를 AI 가 만들지 않는다.**

`external_id` 를 키로 한 upsert 다. 같은 본문을 두 번 보내도 번호가 바뀌지 않는다 —
번호가 바뀌면 `id_map` 과 ClickHouse 딕셔너리가 옛 번호를 든 채 남고, **그 상태에서도
서비스는 정상처럼 뜬다.**

재분석으로 구간 수가 바뀐 곡만 `?allow_section_change=true` 로 부른다. 그때 응답의
`removed_section_ids` 가 비어 있지 않으면 4·5번을 다시 밟아야 한다는 신호다.

| 기대 | 확인 |
| --- | --- |
| `music_section_analysis` 10,007행 | `SELECT COUNT(*) FROM music_section_analysis` |
| 두 번 불러도 같은 번호 | 응답 `created=0`, `updated=<구간 수>` |

실패하면: 401 이면 Spring `MUPID_RECOMMENDER_INTERNAL_TOKEN` 과 AI
`MUPID_AI_INTERNAL_TOKEN` 이 같은 값인지 본다 — **한쪽만 채우면 전부 401 이다.**
409 `SECTION_SET_CHANGED` 는 구간 집합이 달라졌다는 뜻이고, 재분석이 맞으면
`allow_section_change=true` 로 다시 부른다.

## 3. id_map

MySQL 에서 내보내 만든다. 문자열을 해시하거나 순번을 붙여 숫자를 지어내지 않는다.

    SELECT s.external_id AS source_section_uid,
           s.music_section_analysis_id AS music_section_id,
           m.music_id
    FROM music_section_analysis s JOIN music m ON m.music_id = s.music_id

    python -m ai_server.tools.build_id_map sections.tsv

`source_track_id` 컬럼이 없으면 구간 UID 의 `#` 앞을 잘라 쓰는 것이 정식 경로다.

| 기대 | 확인 |
| --- | --- |
| 매핑 10,007건 · 곡 1,063 | 도구가 `곡 1063 · 구간 10007` 로 찍는다 |

실패하면: 1:1 검증에 걸리면 파일을 만들지 않는다. 그게 맞는 동작이다 — 절반만 매핑된
표를 두면 곡이 소리 없이 사라진다.

## 4. ClickHouse 구간 적재

    python -m ai_server.tools.push_sections --full

딕셔너리 갱신까지 도구가 한다. 따로 부르지 않는다.

| 기대 | 확인 |
| --- | --- |
| `구간 65540개 중 10007개 적재 대상` | 도구 출력 |
| `track_sections` +10,007 | `SELECT count() FROM track_sections WHERE analysis_version='v1'` |

> **2026-09-16 변경.** 예전에는 이 시점에서 `/internal/ready` 가 503 → 200 으로
> 바뀌었다. 지금은 **매핑이 `ready` 에 영향을 주지 않는다** — 숫자 ID 번역을 Spring 이
> 맡으면서 그 검사를 뺐고, 색인만 올라오면 200 이다
> (`infra/AI-SPRING-CONTRACT.md` 의 "2026-09-16 계약 변경").
>
> **`id_map` 자체는 이 절차에 그대로 필요하다.** ClickHouse `track_sections` 가 숫자
> `track_id` 를 쓰기 때문에 바로 앞 단계(`push_sections`)가 읽는다. 없어진 것은 서빙
> 경로의 의존과 `/internal/id-map` 엔드포인트다.

## 5. 행동 신호 수집

    python -m ai_server.tools.pull_signals

워터마크 증분이다. 두 번 돌려도 로그가 늘지 않는다(실측: 15줄 → 15줄).

**딕셔너리 갱신이 이벤트 발생보다 앞서야 한다.** `mv_user_section_signals` 는 INSERT
시점에 `dictGet` 을 부르므로, 늦게 갱신하면 그 사이 이벤트는 `section_uid` 가 `''` 이라
`WHERE` 에 걸려 통째로 버려진다. **오류가 나지 않는다.**

| 기대 | 확인 |
| --- | --- |
| `user_section_signals` 에 행 | 0이면 딕셔너리 미갱신이거나 `track_sections` 가 비었다 |
| `events_service.jsonl` 에 네 종류 | `SECTION_MARK` · `LIKE` · `SEEK_BACK` · `PLAY_END` |

## 6. 라벨 배치

    python -m ai_server.tools.run_labeler

| 기대 | 확인 |
| --- | --- |
| `labels_service.parquet` 에 그 사용자 행 | 실측 9신호 → 라벨 6행 |

**L1 이 서지 않으면 개인화가 걸리지 않는다.** `user_positives()` 는 L1 만 보는데, L1 은
구간 저장(`SECTION_MARK`)·파형 클릭 되감기·2회 이상 반복 중 하나가 있어야 선다. 곡 단위
좋아요와 되감기만으로는 L2 에 머물고, 그러면 Moment DNA 가 비어 `query_source` 가
`none` 에서 움직이지 않는다. 오류는 나지 않고 결과만 비어서, 증상이 "개인화가 안 된다"
로만 보인다.

## 7. 추천 검증

같은 사용자로 이어 듣기 추천을 전후로 받아 견준다. `query_source` 는 응답 계약에 없고
진단에만 실리므로, HTTP 로는 개인화 여부를 알 수 없다 — 추천기를 직접 태워 본다.

    전: query_source='none'   JAMENDO_1440522, JAMENDO_1291146, ...
    후: query_source='labels' JAMENDO_1027698, JAMENDO_1080806, ...

| 기대 | 확인 |
| --- | --- |
| `query_source` `none` → `labels` | 진단 |
| 결과 곡이 달라진다 | 위 목록 |

`reason_code` 가 `SIMILAR_PREFERRED_MOMENT` 로 바뀌는지는 **보장되지 않는다.** 그 코드는
`matched_dna` 가 붙어야 나오고, 라벨은 그 구간을 들은 전체 사용자 평균 + 위치 편향 prior +
shrinkage(κ=5)로 만들어진다. 한 사용자 몇 건으로 등급이 선다는 보장이 없다.

## 8. 되돌리기

시험용 회차는 `cleanup_seed_run` 으로 지운다.

    python -m ai_server.tools.cleanup_seed_run --manifest <seed_manifest.json>
    python -m ai_server.tools.cleanup_seed_run --run-id E2E_xxxxxxxxxxxx

`TRUNCATE` 와 범위 없는 `DELETE` 를 쓰지 않는다. ClickHouse 는 `*_local` 에
`ALTER TABLE ... ON CLUSTER mupid_all DELETE WHERE` 로 건다 — Distributed 표
(`listen_events`·`user_section_signals`·`track_sections`)는 mutation 을 받지 않는다.
MySQL 은 FK 역순으로 지운다.

`track_sections` 는 `run_id` 로 지울 수 없다. `section_uid` 가 색인의 `moment_id`
그대로여야 추천기가 찾기 때문이다. 대신 그 회차에 발급된 숫자 `track_id` 로 좁힌다.
