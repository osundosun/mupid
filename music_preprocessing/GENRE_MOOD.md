# 곡별 장르·분위기 등록

`music-preprocessing classify` 는 수집 파이프라인이 곡마다 남긴 장르·무드를 서버의
`music_genres` · `music_moods` 에 연결한다. 오디오를 분석하지 않는다.
배치 한 바퀴(`run_batch.sh`) 안에서 `catalog` · `covers` · `lyrics` 다음에 돈다.

```bash
# JupyterHub 배치 서버에서. 무엇이 바뀔지만 보려면
uv run music-preprocessing classify -c config.json --check
# 실제로 등록
uv run music-preprocessing classify -c config.json --yes
```

손으로 더 깊이 들여다볼 때는 같은 알맹이를 argparse 로도 쓸 수 있다
(`python -m music_preprocessing.genre_mood --help`).

## 관리자 API 를 쓰지 않는다

예전 판은 `/api/v1/admin/resource/**` 로 코드와 연결 행을 하나씩 만들었다.
그러려면 배치가 **관리자 권한**을 들고 있어야 한다 — 그 계정이면 회원·결제까지
닿는다. 반대로 관리자 API 를 배치에 열어 주면 "관리자 전용" 이라는 말이
무의미해진다. 그래서 배치가 실제로 필요한 것만 `@SystemRole` 로 따로 냈다.

| 하는 일 | 엔드포인트 | 쓰나 |
| --- | --- | --- |
| 어휘 확인 | `GET /api/v1/onboarding/option` | 아니요 |
| 곡·현재 분류 확인 | `GET /api/v1/music?page&size=100` | 아니요 |
| 코드 추가 | `POST /api/v1/code` | 예 (멱등) |
| 분류 적재 | `PUT /api/v1/music/{id}/classification` | 예 (**통째로 교체**) |

인증은 배치가 이미 쓰는 **ROLE_SYSTEM** 리프레시 토큰 그대로다
(`api_refresh_token_file`). 별도 관리자 토큰 파일이 필요하지 않다.

## 입력

| 무엇 | 어디서 | 설정 이름 |
| --- | --- | --- |
| 곡별 장르·무드 | `metadata/T*.json` 의 `genre` · `mood` | `metadata_dir` |
| 분류 어휘와 수량 | `genre_mood_taxonomy.json` | `genre_taxonomy_file` (비우면 `metadata_dir` 옆) |
| track_id → music_id | `catalog` 가 남긴 `registered.json` | `api_registry_log` |

중간 스냅샷 파일은 필요 없다. `music_preprocessing` 은 JupyterHub 안에서 돌고
원본이 같은 파일 시스템에 있다. 감사용으로 한 파일에 떠 두고 싶을 때만
`genre_mood snapshot` 을 쓴다.

**분류 추가의 영향:** 부트스트랩 시드는 장르 8개(`Pop`·`Rock`…)·분위기 8개(한국어)다.
이번 원본은 세부 장르 45개·영문 분위기 9개다. 원문을 보존해 `codes` 에 추가하며
기존 이름과 통합하거나 번역하지 않는다. 다만 `codes` 의 `(code_type, code_name)`
UNIQUE 는 MySQL 기본 콜레이션에서 **대소문자를 구분하지 않으므로**, 원본의 `pop` 은
새 행을 만들지 않고 시드의 `Pop` 을 그대로 쓴다. 도구도 그 철자를 기준으로
비교한다(그러지 않으면 반영 뒤에도 "다르다" 가 되어 매 배치가 같은 곡을 다시 쓴다).
`OnboardingServiceImpl.options()` 가 해당 유형의 코드를 전부 조회하므로 추가된 값은
온보딩 선택지에도 나타난다 — 화면은 45개를 받도록 고쳐 두었다(검색·선택 고정).

## 검증

원본은 쓰기 전에 전부 대조한다. 파일명·트랙 ID, 등록 매핑, 곡명, 중복 ID,
분류 어휘와 유형별 수량, 무드 결측 목록. `mood=null` 은 정상 결측으로 처리하고
아래 초기 10곡만 사용자가 제공한 근사치로 보완한다. primary/secondary 가 같으면
관계는 하나만 만들되 taxonomy 등장 횟수는 두 번 센다. energy/method 는 저장하지 않는다.

서버 쪽은 곡 목록을 훑어 **등록 기록의 곡 번호에 그 곡명이 실제로 있는지** 본다.
`registered.json` 이 낡아 다른 곡을 가리키면 `PUT` 이 엉뚱한 곡의 분류를 지우므로,
여기서 어긋나면 한 건도 쓰지 않고 멈춘다.

2026-09-16 GPU 원본 확인 결과:

| 항목 | 결과 |
| --- | ---: |
| 매핑 검증 완료 곡 | 1,060 |
| 장르 관계 | 1,060 |
| 무드가 있는 곡 | 1,050 |
| 중복 제거 후 무드 관계 | 2,100 |
| 무드 결측 곡 | 10 |
| 고유 장르 / 무드 | 45 / 9 |

### 초기 10곡 무드 보완 (기본 적용)

2026-09-16 추가 요청에 따라 아래 값을 기본 적용한다. 생성 요청 라벨을 파싱한
1,050곡과 달리, Flow 의 `flow_generated.sound_raw` 를 사람이 해석한 **근사치**다.
값과 근거는 `src/music_preprocessing/mood_approximations.py` 에 별도로 보관한다.
GPU 원본 JSON 과 taxonomy 는 수정하지 않는다.

| track_id | genre | primary | secondary |
| --- | --- | --- | --- |
| T000001 | contemporary_pop | bright | warm |
| T000014 | contemporary_pop | bright | warm |
| T000003 | rnb | warm | dreamy |
| T000004 | rnb | warm | dreamy |
| T000005 | poprock | powerful | hopeful |
| T000006 | poprock | bright | hopeful |
| T000007 | lofi_hiphop | warm | calm |
| T000008 | synthwave | dreamy | powerful |
| T000009 | solo_piano | calm | melancholic |
| T000010 | game_battle_bgm | powerful | dark |

원본을 taxonomy 와 먼저 대조한 뒤, 이 목록 중 **원본 무드가 없는 곡만** 보완한다.
장르가 표와 다르거나 해당 무드가 원본 어휘에 없으면 중단한다 — 배치가 매일 도는
지금은 이것이 "원본이 우리가 아는 것과 달라졌다" 는 신호다. 이후 원본에 실제 무드가
추가되면 그 값이 우선하며 이 표로 덮어쓰지 않는다. 목록 밖 결측은 유지한다.

보완 후 예상값은 **무드 1,060곡 / 2,120행**, 장르 1,060행, 고유 분류 45/9개다.
원본만 처리하려면 `--skip-mood-approximations` 를 붙인다.

DB 연결 테이블에는 신뢰도 컬럼이 없으며 이번 작업에서 스키마를 추가하지 않는다.
따라서 **DB 행만으로는 근사치를 구분할 수 없다.** 실행 보고서의
`mood_approximations` 에 track_id·music_id·primary·secondary·`confidence: approx`·출처·근거를
남기고, 실제로 쓴 `created` 항목에도 근사치 표식을 남긴다.
`source_summary` 는 원본 수량, `summary` 는 보완 후 등록 예정 수량이다.

## 교체라서 달라지는 것

`PUT` 은 그 곡의 장르·분위기를 **통째로** 갈아 끼운다. 관계마다 `POST` 를 부르던
예전과 달리 중간 상태가 없다 — 재분석 도중에 옛 분류와 새 분류가 섞인 채로도
조회가 정상으로 보이던 문제가 사라진다.

대신 남의 값을 지울 수 있다. 그래서 이 도구는 **분류가 비어 있는 곡에만 쓴다.**
이미 분류가 있는데 원본과 다르면 건드리지 않고 보고서의 `held` 에 남기고 화면에도
몇 곡인지 알린다. 원본으로 덮어쓰려면 `--replace-existing` 을 명시한다. 그때는
무엇을 지웠는지 `created[].replaced` 에 기록한다.

한 곡 때문에 나머지를 멈추지는 않는다. 다만 아래는 **한 건도 쓰기 전에** 멈춘다.

- 등록 기록의 곡 번호에 그 곡명이 없다 (매핑이 낡았다)
- 같은 유형·이름의 코드가 서버에 둘 이상이다
- 원본이 taxonomy 와 어긋난다

## 실행 보고서

실행마다 `<log_dir>/classify-<시각>.json` 에 남는다. 기존 파일은 덮어쓰지 않는다.

| 키 | 뜻 |
| --- | --- |
| `status` | `source_validated` → `server_preflight_passed` → `verified` |
| `source_sha256` | 원본 스냅샷의 해시 |
| `source_summary` / `summary` | 보완 전 / 보완 후 수량 |
| `missing_codes` | 이번에 추가할 `(유형, 이름)` |
| `pending` | `empty` 비어 있던 곡 수 · `differs` 이미 다른 분류가 있던 곡 수 |
| `held` | 건너뛴 곡의 현재 분류 |
| `skipped` | 건너뛴 곡 번호 |
| `created` | 실제로 쓴 것 (코드 / 곡별 분류) |

## 재실행과 실패

- 한 프로세스만 실행한다. 공통 코드의 `(code_type, code_name)` 에 대해 이 도구가
  데이터베이스 수준의 동시 실행 잠금을 보장하지 않는다.
- 요청마다 트랜잭션이므로 전체 배치가 단일 트랜잭션은 아니다. 다만 **한 곡의
  분류는 한 요청**이라 곡 단위로는 쪼개지지 않는다.
- 전송 실패를 자동 재시도하지 않는다. 서버에는 반영되고 응답만 손실됐을 수 있다.
- 실패해도 이미 쓴 것은 남는다. 다시 돌리면 서버 상태를 다시 읽어 이미 맞는 곡은
  건너뛴다 — `POST /api/v1/code` 도 `PUT` 도 멱등이라 두 번 보내도 결과가 같다.
- 정상 종료는 `status=verified`, `rerun_additions=0` 이다. 반영 뒤 서버를 다시 읽어
  대상 곡의 분류가 원본과 정확히 일치하는지 확인한다. 개수만 같고 다른 곡에 붙은
  경우는 통과하지 않는다.

## 테스트

```bash
PYTHONPATH=src uv run python -m unittest discover -s tests -v
```

추가 의존성 없이 표준 라이브러리만 쓴다. 원본 검증, ID 추측 방지, 무드 결측/중복,
사전 검증 무쓰기, 기존 분류 보존, 대소문자만 다른 코드의 수렴, 저장 결과 대조,
페이지 조회, 응답 손실 후 재실행을 본다. `tests/test_genre_mood_http.py` 는
**진짜 HTTP 서버를 띄워** 경로·쿼리·인증 헤더를 그대로 확인한다 — 목 객체만으로는
"부르긴 불렀는데 서버가 못 알아듣는" 오류가 잡히지 않는다.

## 함정: 서버 JSON 은 snake_case 다

**배치가 서버와 주고받는 모든 키는 snake_case 다.** 자바 record·엔티티의 필드명
(`codeId`·`genreCodeIds`)이 아니다 — `api/src/main/resources/application.yaml` 의
`spring.jackson.property-naming-strategy: SNAKE_CASE` 가 요청·응답 전부를 바꾼다.

| 하는 일 | snake_case (서버) | 자바 필드명 |
| --- | --- | --- |
| 코드 추가 | `code_type` · `code_name` · `display_name` | `codeType` … |
| 코드 응답 | `code_id` · `code_name` · `display_name` | `codeId` … |
| 분류 교체 | `genre_code_ids` · `mood_code_ids` | `genreCodeIds` … |
| 온보딩 선택지 | `code_id` · `name` · `display_name` | `codeId` … |
| 곡 목록 | `music_id` · `total_elements` · `total_pages` | `musicId` … |

camelCase 로 보내면 스프링이 **모르는 필드로 버리고**(`FAIL_ON_UNKNOWN_PROPERTIES`
기본 활성), 필수값이 `null` 이 되어 **400** 이 난다. 읽을 때는 `KeyError` 다 —
2026-09-17 `data-batch` 가 `genre_mood.py` 의 `row["codeId"]` 에서
`KeyError: 'codeId'` 로 죽었다. `classify` 가 그날 처음 `run_batch.sh` 에 들어가
드러났을 뿐, 코드는 그 전부터 틀려 있었다.

**테스트 대역이 이 함정을 가린다.** 대역이 내부 엔티티 이름을 그대로 돌려주면
배치가 camelCase 를 읽어도 통과한다. 그래서 대역은 **내부는 camelCase(실제 자바
엔티티와 같게), 밖으로 나가는 것만 snake_case** 로 둔다
(`tests/test_genre_mood.py` 의 `Server._wire_code`). 대역을 고치지 않고 소스만
고치면 아무것도 검증되지 않는다.

**`imageFile` 은 예외다.** 멀티파트 폼 필드라 `@RequestParam` 이름을 그대로 쓰므로
camelCase 가 맞다 — `SNAKE_CASE` 는 JSON 본문에만 걸린다.