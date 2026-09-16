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

## 2. 구간 적재

    PUT /api/v1/music/{musicId}/section

곡당 한 번, 총 1,063회. **`ROLE_SYSTEM` 계정만 부를 수 있다** — 토큰 헤더가 아니라
평범한 로그인이고, `tools/_api_auth.py` 가 리프레시 토큰으로 액세스 토큰을 받아 온다.

**추가가 아니라 교체다.** 한 곡의 구간은 분석 한 번의 결과로 함께 정해진다. 구간마다
넣으면 재분석 중간에 옛 구간과 새 구간이 섞이고, 그 상태로도 조회가 정상으로 보인다.
같은 본문을 두 번 보내면 결과가 같다 — 다만 **번호는 새로 발급된다.** `id_map` 과
ClickHouse 딕셔너리가 옛 번호를 든 채 남으므로, 다시 부른 곡이 있으면 3·4번을 함께
다시 밟는다.

응답의 `sections[]` 가 `externalId` 와 발급된 `musicSectionAnalysisId` 를 짝지어 준다.
**숫자 ID 를 AI 가 만들지 않는다.**

`moods` 는 **코드 이름**으로 보낸다(`잔잔한`·`신나는`…). 번호는 Spring 이 붙인다 —
AI 는 MySQL 이 발급한 코드 번호를 모른다. 코드표에 없는 이름은 버리고 센다.

| 기대 | 확인 |
| --- | --- |
| `music_section_analysis` 10,007행 | `SELECT COUNT(*) FROM music_section_analysis` |
| 분위기가 붙은 구간 | `SELECT COUNT(*) FROM music_section_analysis_mood` |

실패하면: 401 이면 `MUPID_API_REFRESH_TOKEN` 이 `ROLE_SYSTEM` 계정의 것인지 본다.
400 `구간 external_id 는 64자를 넘을 수 없습니다` 는 컬럼이 `VARCHAR(64)` 라 먼저 막은
것이다 — 넘치면 MySQL 이 조용히 잘라 저장하고, 잘린 값끼리는 1:1 검사까지 통과한다.

## 3. id_map

2번과 **같은 도구가 함께 만든다.** 적재 응답이 번호를 돌려주므로 따로 조회하지 않는다.

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.register_sections         --track-map track_map.csv

`--track-map` 은 필수다. `track_id`(`T000148`) → `music_id` 표이고, **없는 곡은 건너뛰고
센다.** 문자열을 해시하거나 순번을 붙여 숫자를 지어내지 않는다 — 그럴듯하게 돌다가 실제
DB 와 어긋난 순간 엉뚱한 곡을 추천한다.

    source_track_id,music_id
    T000148,1001

MySQL 에서 이미 적재된 것을 내보내 만드는 길도 남아 있다(`build_id_map`). 2번을 다른
경로로 밟았을 때 쓴다.

    SELECT s.external_id AS source_section_uid,
           s.music_section_analysis_id AS music_section_id,
           m.music_id
    FROM music_section_analysis s JOIN music m ON m.music_id = s.music_id

    python -m ai_server.tools.build_id_map sections.tsv

| 기대 | 확인 |
| --- | --- |
| 매핑 10,007건 · 곡 1,063 | 도구가 `곡 1063 · 구간 10007` 로 찍는다 |

실패하면: 1:1 검증에 걸리면 파일을 만들지 않는다. 그게 맞는 동작이다 — 절반만 매핑된
표를 두면 곡이 소리 없이 사라진다.

**`id_map` 은 이제 서빙이 읽지 않는다.** 숫자 ID 번역은 Spring 이 맡는다. 그래도 4번이
읽으므로 이 절차에서는 빠지지 않는다.

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

먼저 표를 한 번 만든다. **앱은 표를 만들지 않는다**(decision/0011) — 이 단계를 건너뛰면
배치가 적재에서 실패하고, 그 오류가 곧 "DDL 을 아직 안 돌렸다" 는 뜻이다.

    clickhouse-client --host <host> --user <user> --password \
        < ai_server/sql/section_labels.sql

    python -m ai_server.tools.run_labeler

| 기대 | 확인 |
| --- | --- |
| `labels_service.parquet` 에 그 사용자 행 | 실측 9신호 → 라벨 6행 |
| `section_labels` 에 같은 행 수 | `SELECT count() FROM section_labels WHERE run_id = (SELECT max(run_id) FROM section_labels)` |

라벨의 **정본은 ClickHouse** 다. parquet 은 배치의 중간 산출물이고, 추천 파드는 파일을
보지 않는다 — 그래야 파드가 상태를 갖지 않고 여러 벌로 늘어날 수 있다.

판은 통째로 교체된다. 어제 있던 라벨이 오늘 없을 수 있어(행동이 바뀌었으므로) 행을
갱신해 맞추지 않고 `run_id` 를 붙여 새로 넣는다. 읽기는 항상 `max(run_id)` 하나만 본다.
오래된 판은 7일 TTL 로 사라진다.

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
