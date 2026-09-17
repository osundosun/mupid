# Metadata Schema v2 렌더링 계약

`downloads/metadata/*.json` (schema_version 2)를 소비할 향후 백엔드/프론트엔드를 위한 계약 문서. 현재 이 프로젝트에는 이 JSON을 읽는 백엔드/프론트엔드 코드가 전혀 없다 — 이 문서는 그 코드를 만들기 전에 지켜야 할 규칙만 정의한다.

## lyrics_sections

- 화면 표시 순서는 `order` (1부터 시작, 원문 등장 순서)를 사용한다. 배열 인덱스나 `type`/`index`로 정렬을 재구성하지 않는다.
- 반복 렌더링(React `key` 등)은 `section_id`를 사용한다. `section_id`는 곡 내부에서 항상 고유하며(`section_01`, `section_02`, ...), 같은 `type`(예: `chorus`)이 여러 번 반복돼도 서로 다른 값을 가진다.
- `type`이 알려진 값이 아니면 `"other"`이고, 이때도 원문 라벨은 `raw_label`에 그대로 보존되어 있다. `raw_label`은 `type`이 알려진 값일 때도 항상 원문 그대로 채워진다.
- `text`는 plain text로 렌더링한다. 줄바꿈은 `white-space: pre-line`(또는 동등한 방식)으로 유지해야 하며, 괄호로 표기된 백킹 보컬(예: `(Running on the edge)`)도 원문 그대로 한 줄로 표시한다.
- `has_text`가 `false`인 섹션(빈 Intro, `[Instrumental]` 등)은 본문이 없는 것이 정상이며 오류가 아니다.
- section label(“Verse”, “Pre-Chorus” 등)의 한글화/현지화는 프론트엔드 locale map에서 처리한다. 이 JSON은 원문 라벨(`raw_label`)과 정규화된 `type`만 제공하며, 번역된 문자열을 담지 않는다.
- `lyrics_raw`는 항상 원문 그대로 보존되며, `lyrics_sections`는 그것을 파싱한 파생 데이터일 뿐이다. 파싱에 실패하면 `lyrics_sections`는 `null`이 될 수 있으므로, 렌더링 코드는 `null`을 항상 처리해야 한다(이 경우 `lyrics_raw`를 그대로 보여주는 것이 안전한 대체 경로다).

## lyrics_sync

- 2026-09-15에 추가된 필드. `lyrics_sections`의 각 섹션 텍스트를 다시 줄 단위로 쪼개서, faster-whisper(GPU, small 모델)로 음성 인식한 결과와 문자 단위로 대조(difflib)해 각 줄의 시작/끝 시각을 추정한 결과다. **근사값이다** — 실측 정렬이 아니라 ASR 텍스트와 원문 가사를 매칭해서 추정한 값이므로, 정밀 자막 용도가 아니라 "대략 몇 분 몇 초에 이 줄이 나오는지" 참고용으로 쓸 것.
- 의도적으로 **가사 텍스트와 타임스탬프만** 담는다(`text`/`start_sec`/`end_sec` 세 필드뿐). 정렬 엔진/모델/신뢰도 같은 부가 정보는 metadata에 넣지 않음 — 필요하면 스테이징 산출물(`downloads/lyrics_sync/{track_id}.json`, raw ASR transcript 포함)을 따로 참고할 것.
- 보컬곡(`flow_generated.lyrics_present == true`)에서 GPU 배치가 정상 처리된 트랙만 값이 채워진다. 인스트루멘탈 트랙과, 보컬곡이지만 처리에 실패한 소수 트랙은 `lyrics_sync: null`이다(실패 목록은 `logs/lyrics_sync_metadata_apply_log.jsonl` 참고).
- 배열의 각 항목은 `lyrics_sections`의 한 섹션을 줄바꿈(`\n`) 기준으로 나눈 개별 줄이다. 순서는 `lyrics_sections`와 동일하게 원문 등장 순서를 따른다(`has_text`가 `false`인 빈 섹션은 나눌 텍스트가 없으므로 애초에 등장하지 않음).
- 정렬에 실패한 줄(스캣/애드립 등 원문과 실제 발음이 크게 다른 경우)은 `start_sec`/`end_sec`가 그냥 `null`이다 — 별도의 "실패" 표시 필드는 없다. 그 줄은 건너뛰고 다음 줄을 쓰면 된다.
- **특히 한국어 트랙에서 정렬 실패 비율이 영어/일본어보다 뚜렷하게 높다** — 이는 정렬 로직의 결함이 아니라, Flow Music이 한국어 가사를 생성할 때 원문 가사가 실제 보컬에서 압축/생략되는(사용자 표현: "짜부라지는") 경향이 있기 때문으로 확인됨(2026-09-14/15 세션에서 실제 청취로 검증). 고칠 수 있는 문제가 아니라서 재시도 로직 없이 그대로 둠.

## assets

- `assets.audio.path`, `assets.cover.path`는 프로젝트 루트 기준 **서버 내부 상대경로**이며, 브라우저에서 바로 열 수 있는 URL이 아니다. 프론트엔드에 직접 노출하지 않는다.
- 실제 서비스 재생/표시에는 향후 만들어질 **백엔드 미디어 API** 또는 `delivery.stream_url` / `delivery.cover_url`(현재는 업로드 전이라 항상 `null`)을 사용한다.

## source

- `source.song_url`, `source.session_url`은 Flow 내부 출처 추적용 URL이며, **로그인해야 접근 가능한 비공개 URL**이다. 서비스의 공개 재생/조회 URL로 취급하거나 그대로 노출하지 않는다.
- `source.model`이 `null`인 것은 알 수 없어서가 아니라, Flow가 사용한 모델명이 코드/공식 설정 어디에도 확인되지 않기 때문이다. 확인되기 전까지 임의로 채우지 않는다.

## requested

- `requested.language`, `requested.vocal_type`, `requested.category`, `requested.bpm_range`는 **prompts.jsonl에 적힌 요청값**이다. 실제 오디오를 분석해서 나온 결과가 아니므로, 실제 언어/보컬/BPM과 다를 수 있다. 신뢰 가능한 실측값이 필요하면 `analyzed`/`resolved` 필드(현재는 항상 `null`, 향후 실제 오디오 분석 파이프라인이 채울 자리)를 사용한다.

## artist / album

- 2026-09-14에 추가된 필드. 가상 아티스트/앨범 프로젝트(`artists.json`/`albums.json` 참고)의 배정 결과를 각 트랙에 반영한 것으로, 매 트랙은 정확히 하나의 아티스트·앨범에 속한다.
- `artist.name`/`album.title`은 **표시용 최종 문자열 단일 필드**다. 일본어 아티스트/앨범은 `flow_title`과 동일한 "원문(로마자)" 합체 형식을 쓴다(예: `"灯火(Touka)"`). 한국어/영어는 로마자 표기가 필요 없으니 원문 그대로 한 값이다(예: `"물빛"`, `"Vellichor"`). **별도의 `name_reading`/`title_reading` 필드는 없다** — 처음엔 분리된 필드로 설계했다가, `flow_title`과 일관성을 맞추기 위해 합체 방식으로 통일함(2026-09-14).
- `artist.artist_key`/`album.album_key`는 `artists.json`/`albums.json`의 동명 필드와 조인 가능한 내부 식별자이며, 프론트엔드에 노출할 필요는 없다(그룹핑 로직 추적용).
- 인스트루멘탈 앨범(`album.title`)은 `"{아티스트명} Pt. N"` 형식이 대부분이다(로파이/앰비언트/게임음악 프로듀서 디스코그래피 관례를 따른 것, 버그 아님).

## release_date / album_release_date

- 2026-09-15에 추가된 필드. 관계형 DB로 서비스를 옮기면서 필요해진 "곡/앨범 발매일"이며, **가상(virtual) 값**이다 — Flow에서 실제로 발매 개념이 있는 게 아니라, 메타데이터 생성 시점(`generated_at`)을 그대로 발매일로 재사용한 것이다.
- `release_date`(트랙, `downloads/metadata/T*.json`): `generated_at`의 날짜 부분(`YYYY-MM-DD`, UTC 기준, 시각 정보 없음)을 그대로 옮긴 값이다. 1060개 전부 값이 채워져 있다(`null` 없음).
- `album_release_date`(앨범, `albums.json`): 그 앨범 `track_ids`에 속한 트랙들의 `release_date` 중 **최댓값(가장 늦은 날짜)**이다 — "마지막 수록곡이 완성된 뒤 앨범이 나온다"는 실제 발매 관례를 흉내낸 규칙이다. 204개 전부 값이 채워져 있다.
- 실제 생성이 2026-09-01~09-16의 좁은 기간에 몰려 있어서, `release_date`/`album_release_date` 모두 소수의 날짜 값에 집중돼 있다(의도된 결과이며 버그가 아니다). 카탈로그를 더 자연스럽게 흩뿌리는 방안은 검토했으나 사용자가 현재 방식(가공 없이 그대로)을 선택함.
- `artists.json`에는 대응하는 파생 필드(예: 데뷔일)가 없다 — 이번 작업 범위 밖.

## genre / mood

- 2026-09-16에 추가된 필드. `release_date`와 마찬가지로 관계형 DB(`music_genres`/`music_moods` 테이블)로 이전하면서 필요해진 필드이며, 새로 오디오를 분석한 게 아니라 **기존 텍스트 필드(`requested.category`, `prompt`)를 파싱한 파생값**이다. 산출 스크립트: `scripts/build_genre_mood_plan.py`(계획 생성) → `scripts/apply_genre_mood_to_metadata.py`(반영, 백업+전수검증).
- `genre`(문자열, 곡당 1개): `requested.category`에서 성별/언어 접두어(`male_english_`, `instrumental_` 등)를 제거한 나머지 세그먼트. 예: `female_english_contemporary_pop` → `contemporary_pop`. 1060개 전부 값이 있다(파싱 실패 0건). 고유값 45개, 분포는 `genre_mood_taxonomy.json`의 `genres` 배열 참고.
- `mood`(객체 또는 `null`): `prompt` 원문에서 `"Energy 4/5; primarily bright with a dreamy undertone..."`, `"Combine a hopeful primary mood with a subtle melancholic undertone..."` 등 프롬프트 템플릿 세대별로 다른 문구 9가지 패턴을 정규식으로 파싱한 결과. 필드:
  - `primary`/`secondary`: mood 태그 문자열(소문자). 전부 아래 9개 값 중 하나다 — `melancholic`, `warm`, `dreamy`, `bright`, `hopeful`, `powerful`, `dark`, `calm`, `exciting`. `secondary`는 대부분 값이 있지만 패턴에 따라 `null`일 수 있다.
  - `energy`: 1~5 정수 또는 `null`. 프롬프트에 `"Energy N/5"` 형태의 명시적 수치가 있을 때만 채워진다(전체의 약 89%). 나머지는 `"energy moderate/high"` 같은 서술형이라 수치화하지 않고 `null`로 둔다.
  - `method`: 어떤 정규식 패턴으로 파싱했는지 기록하는 내부 추적용 값(`energy_undertone`, `keep_mood_and` 등). 프론트엔드/DB 적재 시 노출할 필요 없음.
  - **1050/1060(99.1%)만 값이 있다.** 나머지 10곡(`T000001`, `T000003`~`T000010`, `T000014` — 극초기 배치 B001의 프롬프트)은 프롬프트 자체에 mood/energy 관련 문구가 전혀 없어서 추측해서 채우지 않고 `null`로 남겼다(`analyzed`/`resolved` 필드와 동일한 원칙).
- `music_genres`/`music_moods`처럼 이름이 복수형인 정규화 테이블을 쓴다면: genre는 곡당 1행(1:N), mood는 `primary`(+ 있으면 `secondary`)로 곡당 최대 2행(N:M)이 자연스러운 매핑이다. 다만 실제 조인 키(이 프로젝트의 `track_id`를 백엔드가 무엇으로 참조하는지)는 백엔드 스키마를 봐야 확정할 수 있다 — 이 프로젝트에는 백엔드 코드가 없다.

## generated_card_count / generated_card_count_source

- `generated_card_count_source`가 `"unknown_legacy"`이면 `generated_card_count`는 항상 `null`이다. 이 값을 추정해서 채우지 않는다.
- 그 외 값(`"live_dom_measurement"`, `"scoped_dom_forensic"`, `"scoped_dom_orphan_recovery"`)은 실제 DOM 측정/재검증 결과다.
