# flow-music-automation — 세션 인수인계

프로젝트 경로: `c:\Users\SSAFY\flow-music-automation` (git repo 아님)

## 1. 전체 배경

Flow Music(생성 AI 음악 서비스)을 Playwright+CDP로 자동화해서 `prompts.jsonl`에 대해 곡을 생성/다운로드하는 프로젝트. **최초 1000개 프롬프트는 1000/1000 전부 생성 완료됨** (`downloads/audio`, `downloads/covers`, `downloads/metadata`에 각 1000개). 이후 데이터 분석(무드/악기 태깅) 및 **제목 품질 정비 작업**이 진행됐고(현재 알려진 문제 전부 해결, 아래 2-2 참고), 2026-09-11에 **mixed 보컬 언어 비율 보강을 위해 prompts.jsonl에 40개 프롬프트를 추가**해서 현재 `prompts.jsonl`은 **1040줄**(P0001~P1040)입니다 — 이 40개(P1001~P1040)는 같은 날 실제 생성/다운로드까지 진행함(2-3 참고). 또한 같은 날 **박자(3/4 vs 4/4) 불일치 결함**을 발견해 전체 카탈로그를 자동 스캔하는 도구를 구축함(2-4 참고). **✅ GPU 서버 동기화 완료** (2026-09-11): `scripts/sync_to_gpu.sh` 방식으로 `--delete` 포함 완전 미러링 실행, 원격(`j-j15a204@70.12.130.102:~/flow_music_data`) audio/covers/metadata 전부 정확히 1040개로 로컬과 일치 확인함(2-7 참고).

## 0. ✅ genre/mood 필드 추가 + GPU 동기화 + 백엔드 핸드오프 프롬프트 작성 완료 (2026-09-16)

**배경**: 관리자 페이지(`/api/admin`, `j15a204.p.ssafy.io`)에서 `artists`(87)/`albums`(204)/`music`(1060)/`streams`(1060)는 이미 채워져 있는데 `music_genres`/`music_moods`만 0건. 이 프로젝트에는 그 백엔드 코드가 없어서(별도 폴더), 여기서는 소스 데이터(metadata JSON) 준비 + GPU 동기화까지만 하고, 실제 DB 적재는 백엔드 프로젝트의 Claude 세션에 핸드오프 프롬프트로 넘기는 방식 채택(사용자 확정).

**한 일**:
1. `requested.category`(예: `female_english_contemporary_pop`)에서 성별/언어 접두어를 제거해 **genre**(곡당 1개, 고유 45개) 추출 — 파싱 실패 0건.
2. `prompt` 원문 안의 "Energy N/5; primarily bright with a dreamy undertone" 류 mood 서술을, 프롬프트 템플릿 세대별로 다른 **9가지 정규식 패턴**(`Energy N/5;...`, `Balance/Combine a X primary mood with a Y undertone`, `Keep the mood X and Y`, `overall mood X and Y` 등)으로 파싱해 **mood**(`{primary, secondary, energy, method}`, mood 태그 고유 9개) 추출 — 1050/1060(99.1%) 커버. 나머지 10곡(`T000001`,`T000003`~`T000010`,`T000014`, 최초 배치 B001)은 원본 프롬프트에 mood 서술이 아예 없어서 추측하지 않고 `null`로 둠(기존 `mood_extraction_full1000.json`의 988/1000과 같은 원칙).
3. `scripts/build_genre_mood_plan.py`(계획+타xonomy 생성) → `scripts/apply_genre_mood_to_metadata.py`(백업+원자적 적용+전수검증, `release_date` 추가 때와 동일 패턴)로 `downloads/metadata` 1060개 전부에 `genre`/`mood` 필드 반영. 백업: `downloads/metadata.bak_before_genre_mood_20260916T012052Z`.
4. `docs/metadata_schema_v2.md`에 "genre / mood" 계약 절 추가.
5. `genre_mood_plan.json`(트랙별 상세)/`genre_mood_taxonomy.json`(장르 45개+무드 9개 마스터 리스트, 트랙 수 포함) 프로젝트 루트에 생성.
6. GPU 서버(`j-j15a204@70.12.130.102:~/flow_music_data`) 동기화: `scripts/sync_to_gpu_full_mirror.sh`(metadata 1060개 재반영, audio/covers는 변경 없음 확인) + `scripts/sync_artist_album_json_to_gpu.sh`(이번에 `genre_mood_taxonomy.json`도 같이 올리도록 스크립트 수정, artists.json/albums.json과 함께 3개 파일 scp). **SSH로 원격 파일 직접 확인해서 검증 완료**: `genre`는 1060/1060, `mood` non-null은 1050/1060 — 로컬과 일치.
7. `BACKEND_GENRE_MOOD_HANDOFF_PROMPT.md`(프로젝트 루트) 작성 — 백엔드 프로젝트의 Claude 세션에 그대로 붙여넣을 프롬프트. 새 필드 설명 + genre 45개/mood 9개 목록 인라인 포함 + "조인 키는 네가 직접 스키마에서 확인해서 재사용하라"는 요청(이 프로젝트는 백엔드 스키마를 모름) + music_genres/music_moods 조인 테이블 시드 절차 제안. **아직 사용자가 백엔드 세션에 전달은 안 함 — 다음 액션.**

**⚠️ 이번 세션에서 재확인한 함정(31번째 줄 경고와 동일 건)**: `DRY_RUN=1 wsl bash scripts/sync_to_gpu_full_mirror.sh` 형태로 "드라이런 확인 후 실행"을 시도했는데, 위에 이미 기록된 **"환경변수를 wsl 앞에 붙이면 전달 안 됨" 함정에 또 걸림** — 사실상 첫 호출부터 실제 rsync가 돌았고, 두 번째 "진짜 실행" 호출은 이미 반영된 상태라 전송량이 거의 0으로 나와서 뒤늦게 알아챔. 이번엔 로컬이 이미 검증된 최종 상태였고 결과도 SSH로 직접 재확인해서 문제는 없었지만, **다음에 이 프로젝트에서 GPU sync 드라이런이 필요하면 절대 `ENV=1 wsl bash ...` 패턴을 쓰지 말고, 스크립트 안에서 `export`하는 래퍼를 새로 만들 것** (예: `scripts/dryrun_sync_to_gpu.sh`는 아직 존재하지 않음 — 필요해지면 그때 만들면 됨).

**다음 세션/다음 액션**:
1. 사용자가 `BACKEND_GENRE_MOOD_HANDOFF_PROMPT.md` 내용을 백엔드 프로젝트의 Claude 세션에 붙여넣어서 실제 `music_genres`/`music_moods` 테이블 적재를 진행해야 함 — 이 프로젝트 쪽에서 할 일은 끝남.
2. genre/mood 값 자체(45개 장르, 9개 무드)에 대한 사용자 검수는 아직 안 받음 — 필요하면 `genre_mood_taxonomy.json` 확인.

## 0-1. ✅ 보컬 아티스트 재편 + 267~318 성별전환 완료 (2026-09-15)

**요약**: 아래 "0-구버전"에 있던 indie_rock 클러스터 버그(언더락이 폭발적 하드록과 느린 인디록을 한 아티스트로 묶던 문제) 수정을 실제로 `artists.json`/`albums.json`/`downloads/metadata`에 반영 완료. 반영 직전에 사용자가 직접 곡을 들어보고 추가로 2가지를 지시함:
1. **품질 문제 5곡 재생성**: T000002/014/016/017/018 — 인트로에 가사가 섞이거나 전반적 퀄리티가 낮다고 판단, 같은 앨범 내 다른 곡의 프롬프트를 그대로 가져와 재생성(원 prompt_id/batch는 유지). 결과: T002→"Western Coast", T014→"Sunday Morning High", T016→"보랏빛 순간"(인트로에 Ooh/Ah 애드립 남음, 사용자가 "들어줄만함"으로 허용), T017→"초록빛 발견", T018→"두 바퀴의 바람". 5곡 다 4/4 정상, 원곡과 컨셉도 충분히 다름(T016↔소스 T026 Carousel만 다소 근접했으나 사용자가 그대로 두기로 확정).
2. **267/277/287/317/318 여성→남성 보컬 전환**: "언더락"의 후신인 female_korean_rock_edge_01(당시 9곡: alt_rock+hard_rock 5곡 + punk_power_pop 4곡)에서, 사용자가 "여자 락보컬 컨셉에 안 맞는다"고 판단한 alt_rock+hard_rock 5곡(267,277,287,317,318)만 프롬프트의 `female`→`male`, `category`의 `female_korean_`→`male_korean_`을 치환해 재생성. 결과 제목: T267→"계절의 끝", T277→"스트라이크"(사용자가 직접 "타격 (Strike)"에서 개명), T287→"붉은 지평선", T317→"Breakthrough"(사용자가 "파쇄 (Break Through)"→영어 단어만 남기라고 2번 수정 지시), T318→"파쇄의 포효". 남은 punk_power_pop 4곡(337,338,347,348)은 재생성 없이 **별도의 새 여성 아티스트로 독립**.

**최종 구조** (indie_rock 재배정 + 이번 성별전환까지 전부 반영):
- 언더락 계열: female_korean_rock_edge_01 자리에 남은 4곡(337,338,347,348)이 **레드라인**(신규 여성 아티스트, 앨범 "직선 주로")으로 독립. 전환된 5곡은 male_korean_rock_edge_01(**언더독**, 기존 9곡+5곡=**14곡**)에 합류 — 앨범도 5곡짜리 "첫 번째 라운드"(alt_rock 순수) + 9곡짜리 "두 번째 라운드"(alt_rock 4+hard_rock 3+punk_power_pop 2 혼합, 장르 순수 청크 이어붙이기 규칙상 정상적으로 좀 큼)로 재구성.
- 그 외 22명(23명 중 언더락/노브레이크 후신 제외 나머지 전부)은 계획대로 확정: 신규 작명 17명 + 이름 유지 4명(Petrichor/Redwake/손편지/朧月) — 전체 이름/사유는 git 히스토리 없는 프로젝트라 별도 기록 없음, 필요하면 `artists.json`/`albums.json` 직접 확인.
- **최종 결과: 아티스트 87명(인스트루멘탈 38 + 보컬 49), 앨범 204개, 트랙 1060곡.** null/중복 이름·앨범명 0건, 트랙 집합 아티스트/앨범 완전 일치 확인.

**사용한 스크립트** (전부 재사용 가능, 프로젝트 루트 기준 상대경로):
- `scripts/rebuild_vocal_artists_v2.py`: indie_rock을 rock_edge에서 indie_soft로 재배정 + rock_edge↔rnb_soul 강제분리. metadata를 매번 새로 읽으므로 267~318 성별전환 후 재실행하면 자동으로 반영됨(실제로 이 세션에서 그렇게 활용함).
- `scripts/apply_regen_prompt_edits.py`: 267~318 성별전환 + 2/14/16/17/18 프롬프트 교체를 `prompts.jsonl`에 반영(백업+검증 포함). `GENDER_FLIP`/`BORROW` 딕셔너리만 바꾸면 재사용 가능.
- `scripts/quarantine_regen_batch.py`: 재생성 대상 트랙의 기존 audio/cover/metadata를 `quarantine/`로 이동 + `logs/queue_state.json`을 WAITING으로 리셋. `TRACK_REASONS` 딕셔너리만 바꾸면 재사용 가능(기존 `scripts/quarantine_meter_mismatch.py`와 동일 패턴).
- `scripts/finalize_artist_album_rename.py`: `rebuild_vocal_artists_v2.py`가 만든 `scripts/_tmp_new_vocal_artists.json`(임시파일, 실행 후 삭제됨) + 이름 확정 JSON을 합쳐서 `artists.json`/`albums.json`을 실제로 덮어씀(백업 자동 생성). 인스트루멘탈 38명은 절대 안 건드리고 그대로 복사. **다음에 비슷한 보컬 재편 작업하면 이 스크립트의 구조를 참고**.
- `scripts/apply_artist_album_to_metadata.py`: 기존 스크립트 그대로 재사용, 1060개 전체 재적용.
- `scripts/generate_artist_album_roster.py`: `ARTIST_ALBUM_ROSTER.md` 재생성용(기존엔 이 스크립트가 없었음 — 매번 수작업/인라인으로 만들었던 것으로 보임, 이제 재사용 가능한 스크립트로 정식 생성함).
- `scripts/scan_meter_mismatch.py`: 재생성 10곡 전부 4/4 확인 완료(기존 스크립트 그대로).

**✅ GPU 서버 재업로드 완료** (2026-09-15, 같은 세션에서 바로 진행): `scripts/sync_to_gpu_full_mirror.sh`로 audio/covers/metadata 전부 미러링. 원격 1060/1060/1060 확인 + T000317.mp3·T000317.json·T000002.mp3 sha256 체크섬 로컬=원격 일치 확인.

**⚠️ 이번에 발견한 중요한 함정 (다음에 또 당하지 않으려면 반드시 기억할 것)**: `DRY_RUN=1 wsl bash scripts/sync_to_gpu_full_mirror.sh`처럼 **환경변수를 `wsl` 명령 앞에 붙이는 방식은 WSL 내부로 전달이 안 됨**(WSLENV 미설정 시 Windows→WSL 경계를 못 넘음). 실제로 `wsl bash -c 'echo $DRY_RUN'`로 테스트해보면 빈 값이 나옴 — 즉 "DRY_RUN=1로 먼저 확인하고 실행"이라는 안전 수칙 자체가 이 호출 방식에서는 조용히 무력화되고, **첫 실행부터 실제 rsync(--delete 포함)가 돌아간다.** 이번엔 로컬 `downloads/`가 이미 의도한 최종 상태였어서 결과적으로 문제가 없었지만, 만약 로컬이 아직 정리 안 된 중간 상태였다면 원격이 잘못된 채로 덮어써졌을 것. **올바른 드라이런 방법**: 환경변수를 wsl 호출 바깥이 아니라 **스크립트 파일 안에서 직접 `export DRY_RUN=1`** 하거나, 별도의 `scripts/dryrun_sync_to_gpu.sh`(내부에서 `export DRY_RUN=1; bash scripts/sync_to_gpu_full_mirror.sh`) 같은 래퍼를 만들어 그 파일을 실행할 것. 앞으로 이 프로젝트에서 wsl 스크립트에 환경변수로 옵션을 넘겨야 하면 전부 이 방식으로 우회할 것.

**남은 것**:
1. `logs/meter_scan_result.jsonl`은 갱신됐지만 `logs/phrase_mismatch_scan_result.jsonl`(2-9 참고, 보컬 프레이즈 길이 스캔)은 재생성 10곡분이 반영 안 됨 — 재조사 정책상("이탈 1군데는 감수") 급하지 않으면 안 건드려도 됨.
2. `full_dataset_report.json` 등 데이터셋 리포트도 이번 변경분 반영 안 됨(3-11 이하 참고했던 오래된 이슈, 여전히 유효).

<details>
<summary>0-구버전 (2026-09-14 세션 종료 시점 기록, 위 작업의 배경 — 접어둠)</summary>

**핵심 상황**: 가상 아티스트/앨범 시스템(0장 이하 전체 참고) 만드는 중, 사용자가 실제로 곡을 들어보다가 **아티스트 그룹핑 자체의 근본적 결함**을 발견함. 지금 수정 로직은 이미 계산 완료됐고 **검증까지 끝났지만, 아직 `artists.json`/`albums.json`에 반영 안 됨**. 다음 세션은 여기부터 이어가면 됨.

### 무슨 문제였는가
"언더락"(female_korean_rock_edge_01)이라는 아티스트가 alternative_rock+hard_rock+indie_rock+punk_power_pop 4개 장르를 전부 한 명으로 묶고 있었음. 앨범은 장르별로 순수하게 나뉘어 있었지만(폭발=alt/hard rock, 느린 걸음=indie_rock, 내리막=punk), **애초에 "폭발적인 하드록"과 "느릿한 인디록"을 같은 가수의 다른 앨범인 것처럼 취급한 것 자체가 틀렸음** (사용자 표현: 색깔이 전혀 다른데 왜 한 아티스트로 묶냐). 근본 원인은 최초 아티스트 그룹핑 스크립트(`scripts/build_artist_album_grouping.py`)의 장르 클러스터 정의:
```python
VOCAL_GENRE_CLUSTER = {
    ...
    "alternative_rock": "rock_edge",
    "indie_rock": "rock_edge",   # <- 이게 틀림. indie_rock은 하드록/펑크 계열이 아니라 인디/멜로딕 계열.
    "hard_rock": "rock_edge",
    "punk_power_pop": "rock_edge",
    ...
}
```
추가로 감사해보니, 같은 매커니즘(인원 부족시 알파벳 순서로 아무 옆 클러스터에 억지로 합쳐지는 것) 때문에 **rock_edge가 rnb_soul(알앤비/소울)과도 여러 명에서 섞여있었음** (예: 焔/Cinderfall/잔불 — 록이랑 소울을 한 가수로 취급). 이것도 명백히 틀림.

**중요한 교훈 (다음에 또 이런 걸 놓치지 않으려면)**: 앨범 단위 버그를 고칠 때는 "앨범을 나누는 매커니즘"만 검증했지, "이 장르들을 원래 한 아티스트로 묶은 전제 자체가 맞는가"는 재검증하지 않았음. **구조를 고칠 때는 항상 한 단계 위 레벨(이번엔 클러스터 정의 자체)까지 같이 재검증할 것.**

### 무엇까지 끝냈는가 (검증 완료, 반영 전)
- 새 스크립트: `scripts/rebuild_vocal_artists_v2.py`
  - 수정 1: `indie_rock`을 `rock_edge` → `indie_soft` 클러스터로 재배정.
  - 수정 2: `bin_pack()` 함수에 "호환 안 되는 클러스터끼리는 절대 안 합침" 규칙 추가 (`INCOMPATIBLE_MERGE_PAIRS = {frozenset({"rock_edge", "rnb_soul"})}`). MIN(6곡) 미달이어도 억지로 합치지 않고 독립 그룹으로 남김 — **크기 규칙보다 장르 일관성이 우선**이라는 원칙으로 수정.
  - 인스트루멘탈은 안 건드림(보컬 전용 문제).
- 실행 결과 (스크립트 실행하면 재현됨, 아직 파일 반영 안 함):
  - 보컬 트랙 총 516개(1060개 중 인스트 544개 제외).
  - **보컬 아티스트: 45명 → 49명**. 이 중 **26명은 구성(track_ids)이 정확히 그대로라 이름 보존**, **23명은 새로 구성돼서 이름을 다시 지어야 함**.
  - rock_edge ↔ rnb_soul 혼합, indie_rock이 하드록/펑크와 섞이는 경우 전부 해소됨 (직접 재실행해서 확인 가능).
  - 새 그룹핑 결과는 `scripts/_tmp_new_vocal_artists.json`에 저장돼 있음(임시 파일, 검토용, git/백업 대상 아님).

### 이름 다시 지어야 할 23명 목록 (구성 변경됨)
`scripts/_tmp_new_vocal_artists.json`에 전체 데이터 있음. 요약:
- `female_english_indie_soft_02` (indie_rock 6곡, 기존 Afterburn에서 분리됨)
- `female_english_rnb_soul_01` (rnb 5곡)
- `female_english_rock_edge_01` (alternative_rock 9곡)
- `female_english_rock_edge_02` (hard_rock+punk_power_pop 9곡)
- `female_japanese_indie_soft_01` (기존 水彩 + indie_rock 합류, 7곡)
- `female_japanese_pop_mainstream_01` (15곡), `_02` (poprock+synthpop 7곡)
- `female_japanese_rnb_soul_01` (5곡, rock 분리됨)
- `female_japanese_rock_edge_01` (6곡, soul 분리됨)
- `female_korean_indie_soft_01` (기존 아지랑이 + indie_rock 합류, 9곡)
- `female_korean_pop_mainstream_01` (15곡), `_02` (poprock+synthpop 9곡)
- `female_korean_rock_edge_01` (언더락이었던 것, indie_rock 빠지고 9곡 — **이름 "언더락" 폐기, 새로 지어야 함**)
- `male_english_indie_soft_01` (기존 Petrichor + indie_rock 합류, 14곡)
- `male_english_rock_edge_01` (12곡, indie_rock 빠짐)
- `male_japanese_dance_electronic+hiphop_urban+indie_soft_01` (Duskforge의 indie_pop 5곡이 여기 흡수됨, 10곡)
- `male_japanese_rnb_soul_01` (5곡)
- `male_japanese_rock_edge_01` (Duskforge의 alt_rock 5곡 + 灯火의 punk_power_pop 1곡 = 6곡 — **Duskforge라는 이름은 폐기, 새로 지어야 함**)
- `male_korean_indie_soft_01` (기존 손편지 + indie_rock 합류, 12곡)
- `male_korean_rnb_soul_01` (5곡)
- `male_korean_rock_edge_01` (노브레이크였던 것, indie_rock 빠지고 9곡 — **"노브레이크" 폐기, 새로 지어야 함**)
- `mixed_japanese_hiphop_urban+indie_soft_01` (8곡)
- `mixed_japanese_pop_mainstream+rock_edge_01` (11곡)

**⚠️ 특히 주의**: `Citrusline`(male_japanese_pop_mainstream_01)은 이번 재구성에서 안 바뀜(그대로 유지). 하지만 `Duskforge`는 완전히 해체됨 — 그 10곡이 위 두 개(`dance_electronic+hiphop_urban+indie_soft_01`와 `rock_edge_01`)로 갈라져 들어감.

### 다음 세션이 할 일 (순서대로)
1. `scripts/_tmp_new_vocal_artists.json` 다시 한번 육안 검토 (내용 이 문서에 이미 요약돼 있음).
2. `artists.json`에서 **보컬 아티스트 항목만 교체**(인스트루멘탈 38명 + 이번에 손 안 댄 나머지는 절대 건드리지 말 것 — `build_artist_album_grouping.py`를 통째로 재실행하면 지금까지 지은 이름 전부 날아감, 절대 이렇게 하지 말 것). 이름 있는 26명은 그대로, 23명은 `artist_name: null` 상태로 반영.
3. `albums.json`도 이 23명 기준으로 재구성 (기존 `scripts/rebuild_albums_genre_aware.py`의 "장르 블록 순수 유지" 알고리즘 재사용 — 장르 블록이 MAX(7) 넘으면 그 블록 안에서만 분할, 다른 장르 안 섞기).
4. 23명 이름 + 그 앨범명들 새로 짓기. **이번 세션에서 확립된 규칙 전부 다시 적용**:
   - 아티스트명: 흔한 일상어/관용구 기피, 자기 곡 제목과 글자 겹침 금지(기계적 검증 필수), 실존 브랜드/유명 IP 금지, 언어권이라고 그 언어 고유어만 강제 안 함(한국어도 영어음차 섞어도 됨).
   - 앨범명: 아티스트명과 반대로 흔한 단어 괜찮음. 실제 수록곡 내용 기반으로 지을 것(이름 반쪼개기 같은 무맥락 방식 금지 — 사용자가 명확히 지적한 사항). **앨범명이 자기 수록곡과 "글자"만 안 겹치면 끝이 아니라 "의미"도 겹치면 안 됨** (예: 앨범명 "느린 걸음" vs 수록곡 "천천히 걸으며" — 글자는 안 겹치지만 뜻이 똑같아서 걸림, 이번 세션 마지막에 사용자가 지적한 것, 놓치기 쉬우니 특히 주의).
   - 일본어 아티스트/앨범명은 "원문(로마자)" 한 필드로 합쳐서 씀(`name_reading`/`title_reading` 별도 필드 없음 — 3-10 참고).
5. `scripts/apply_artist_album_to_metadata.py` 재실행해서 `downloads/metadata` 전체(1060개)에 반영 + 전수검증.
6. `ARTIST_ALBUM_ROSTER.md` 재생성해서 사용자한테 보여주고 확인받기.
7. GPU 서버에 `metadata` 폴더 재업로드. **반드시 `scripts/sync_to_gpu_full_mirror.sh` 파일을 통해서만 실행** (`MSYS_NO_PATHCONV=1 wsl bash scripts/sync_to_gpu_full_mirror.sh`) — **절대 `wsl bash -c '...'` 인라인 방식 쓰지 말 것**. 이 세션에서 인라인으로 썼다가 경로가 깨져서 C 드라이브 전체를 스캔하는 사고가 날 뻔했음(다행히 실제 전송 전에 잡아서 중단, 원격 손상 없음 확인함). 실행 전 항상 `DRY_RUN=1`로 삭제 대상부터 확인.
8. 이 문서(0장, 3-6/3-9/3-10 관련 섹션)를 최종 결과로 업데이트.

</details>

## 2. 완료된 작업

### 2-1. 데이터셋 생성 (완료)
- `prompts.jsonl`: 1000개 프롬프트. 일본어 mixed→male 15곡 전환(성비 문제 해결) 완료.
- `downloads/metadata/T0000xx.json`: schema v2, 1000개 전부 존재. `flow_title`, `flow_generated.lyrics_raw`, `flow_generated.sound_raw`, `requested.category`/`vocal_type`/`language` 등 포함.
- `full_dataset_report.json`: 언어/보컬/장르/BPM 분포 리포트 (최신 상태로 재생성 완료).
- `mood_extraction_full1000.json`: 프롬프트의 `Energy N/5; primary mood: X; secondary mood: Y` 계열 패턴 파싱, 98.8% 커버리지.
- 악기 분포: `flow_generated.sound_raw` 키워드 스캔으로 추출 완료 (드럼 85%, 베이스 74%, 피아노 50% 등).

### 2-2. 제목(flow_title) 품질 정비 — **핵심 작업, 진행 중**

**배경**: 곡 제목이 AI가 생성한 것이라 "Neon Horizon", "Midnight Drift" 같은 단어가 여러 곡에 반복돼서 사용자 체감상 지루해 보이는 문제 발견. 완전 중복(문자열 동일) + 특정 단어(anchor word) 과다 반복을 단계적으로 줄이는 작업 진행.

**설계 원칙 (중요, 계속 유지할 것)**:
1. `flow_title` 필드를 **직접 덮어쓴다** (display_title 같은 별도 필드 만들지 않음 — 사용자가 명시적으로 이 방식 선택함, "나중에 개발할 때 확인할 게 늘어나서 번거로움"이 이유).
2. 감사 기록은 `logs/title_rework_log.jsonl`에 별도로 남긴다 (metadata 파일 자체는 깨끗하게 유지).
3. **적용 전 반드시**: 계획(JSON) 생성 → 충돌 검증(완전 중복 재검사) → `downloads/metadata` 전체를 타임스탬프 백업(`downloads/metadata.bak_before_..._{timestamp}/`) → 적용(.tmp + os.replace 원자적 쓰기) → 적용 직후 재로드 검증 → **전수 검증**(파일 개수 1000 유지, JSON 파싱 전부 성공, title 외 필드 완전 불변, 계획한 만큼 정확히 바뀌었는지, 나머지는 그대로인지, 새 완전중복 안 생겼는지).
4. **보컬 곡은 함부로 안 건드림**: 대상 단어가 가사에 실제로 등장하는지 확인. 하지만 "코러스에 있으면 무조건 보존"도 틀린 기준이었음(사용자 피드백) — 코러스엔 원래 단어가 많고 그중 하나가 우연히 겹친 거면 바꿔도 됨. **진짜 기준은 "그 단어가 노래의 정체성/펀치라인/언어유희인가"**. 예: "바람을 타"는 바람(wind)=바람(wish) 언어유희라 못 건드림. "노을 국도"는 코러스에 "주황빛 국도"가 3번 나오지만 그건 여러 코러스 단어 중 하나일 뿐이라 원래는 바꿔도 됐음(사용자가 이 판단 실수를 지적함) — 다만 이미 되돌려서 유지 중.
5. **대체어 풀은 실제로 로테이션시켜야 함**: 한번 "무조건 pool[0]만 씀" 버그로 26곡이 전부 "미풍"/"そよ風"가 되는 대참사가 있었음 (사용자가 크게 화냄, 전부 롤백 후 가사 기반으로 재작업). 매번 배정 후 실제 다양하게 쓰였는지 재확인할 것.
6. **2차 파생 클러스터 주의**: 대체어 풀 안의 단어들끼리 공통 단어를 공유하면(예: "After Hours"/"Small Hours"/"Witching Hour"/"Moonlit Hour" 전부 "Hour" 포함) 원래 단어는 줄여도 그 파생 단어가 새로운 클러스터가 됨. **매 웨이브 적용 후 전체 단어 빈도를 처음부터 다시 스캔**해서 이런 게 없는지 반드시 확인.
7. **카테고리 내부 쏠림도 확인**: 전역 비율은 낮아도(예: coffee 전체 1.4%) 특정 카테고리 안에서는 높을 수 있음(lofi_hiphop 안에서 22%). 카테고리별(주로 `instrumental_*`, 5곡 이상인 카테고리) 단어 비중도 15% 이상이면 문제로 간주.
8. **장르 라벨이라고 무조건 봐주지 않는다**: "battle이 배틀 BGM 카테고리 장르니까 반복돼도 된다"는 논리는 틀렸음(사용자 지적) — 카테고리는 이미 메타데이터에 있으니 제목에까지 반복될 필요 없음. **진짜 예외는 "실제 연주/프로덕션 기법명"뿐** (Reese=DnB 베이스 신스 기법, Slap=펑크 베이스 주법, Swing=재즈 리듬, Shuffled=브레이크비트 리듬 패턴 — 이런 건 바꾸면 정보 자체가 사라짐).
9. 기준선: 전역 단어 빈도는 **9 이하가 이상적, 12~13까지는 허용**. 카테고리 내부는 **15% 이하**(또는 소규모 카테고리는 절대 3곡 이하).

**진행 이력 (적용 완료 순서)**:
| 단계 | 내용 | 곡 수 | 상태 |
|---|---|---|---|
| 완전중복 제거 1차 | 정확히 같은 제목 150곡(60개 그룹) 중 268건 처리(anchor pool + 완전중복 의역) | 268 | ✅ 적용됨 |
| 보컬 제외분 재작업 | 고위험 보컬 27곡 중 코러스 실사용 여부 재검토 → 26곡 가사 기반 개별 수정 | 26 | ✅ 적용됨 (버그로 한 번 롤백 후 재작업) |
| Take/Option 태그 제거 | "(Take 2)", "(Option 1)" 등 149곡에서 제거, 충돌 26곡은 sound_raw 기반 재작명 | 149 | ✅ 적용됨 |
| 2차 파생 클러스터 수정 | Hour(17)/Late(11)/Cozy(10) 클러스터를 장르별 새 단어로 재분산 | 23 | ✅ 적용됨 |
| **Wave 4** | 카테고리 내부 쏠림(coffee/neon/battle/hero's/echoes/reflections 등 29개 카테고리-단어 조합) | 228 | ✅ 적용됨 |
| **Wave 5** | 전역 단어 drive(23)/night(16)/groove(15)/pulse(14)/dreamy(14) | 71 | ✅ 적용됨 (2026-09-11) |
| **Wave 6** | 장르 라벨 재검토: funk(11, instrumental_funk 55%)/chamber(5, chamber_classical 50%)/acoustic(3, acoustic_chill 40%)/bgm 접미사(9곡, 제거)/neo_soul 내부 soul(2)·velvet(2) 쏠림(각 20%) | 30 | ✅ 적용됨 (2026-09-11) |
| **Wave 7** | 전체 재스캔 중 새로 발견된 카테고리 내부 쏠림: expanse(instrumental_atmospheric_post_rock, 4/20=20%) | 2 | ✅ 적용됨 (2026-09-11) |

**현재 전역 단어 빈도 상태 (Wave7 적용 후 기준, 2026-09-11)**:
- drive/night/groove/pulse/dreamy/funk/chamber/bgm/velvet/soul/expanse 전부 해결, 대부분 한 자리수
- acoustic: 10→7 (acoustic_chill 카테고리 내부 40%→10%만 해결, 나머지 7곡은 카테고리별로 분산돼 있어 문제 없음)
- **light(12), line(11)**만 두 자리로 남음 — 아래 3-2 참고, 재작업 불필요로 이미 확정됨
- 전체 1000곡 완전 재스캔(단어 빈도 + 카테고리별 내부 비중) 완료. 정확히 15% 경계값인 것들(ripples/cruise/calm/faith/chillhop, 그리고 기존 legend's/odyssey/valiant/kingdom, instrumental_funk의 boogie/soul/backbeat)은 "초과 아님"으로 판정하고 그대로 둠 — 사용자 확인받음
- 스크립트 패턴: `scripts/build_title_plan_waveN.py` (계획 생성+검증), `scripts/apply_title_plan_waveN.py` (백업+적용+로그) — Wave5부터 Wave7까지 동일 패턴 재사용

### 2-3. mixed 보컬 언어 비율 보강 (prompts.jsonl 확장 + 생성, 2026-09-11)

**배경**: 기존 mixed 보컬 곡이 전부 일본어(mixed_japanese_*, 30개)뿐이라 언어 비율이 치우쳐 있었음. 원래는 기존 mixed_japanese 곡들 일부를 mixed_korean/mixed_english로 교체하려 했으나, 기존 곡 퀄리티가 준수해서 **교체 대신 신규 40곡을 추가하는 쪽으로 결정** (mixed_korean 20 + mixed_english 20). 10개는 비율 보강에 부족하고 30개는 과함 → 20+20=40으로 확정.

**한 일**:
- `prompts.jsonl`의 기존 mixed_japanese 30개 프롬프트(10개 카테고리: contemporary_pop/dance_pop/synthpop/indie_pop/city_pop/pop_ballad/poprock/alternative_rock/modern_trap_hiphop/singing_rap_melodic_hiphop)를 소스 풀로 사용.
- 각 카테고리에서 한국어용/영어용으로 대체로 2개씩(공급 많은 contemporary_pop만 3개씩) 선정 → 20+20=40, 30개 풀 전부가 최소 1번씩 쓰이도록 배분(부족한 만큼만 10건 재사용 — 언어가 다르니 재사용해도 결과물은 다른 곡이라 문제 없음).
- 변환 규칙: 프롬프트 본문에서 `\bJapanese\b`를 `Korean`/`English`로 정규식 치환. BPM/악기/구조/무드는 소스 그대로 유지.
- **중요 발견**: mixed_japanese 프롬프트들이 실제로는 3가지 다른 문장 템플릿(A: "Use Japanese lyrics and natural Japanese vocal phrasing.", B: "Generate natural original Japanese lyrics beginning with the first verse.", C: "Use male and female vocals in Japanese. Generate original Japanese lyrics...")을 섞어 쓰고 있었음. 단순 치환이 세 템플릿 전부에서 자연스럽게 동작하는 것을 확인함.
- 영어(English) 타겟에서는 기존 male_english/female_english 컨벤션에 맞춰 "Use English lyrics and natural English vocal phrasing." 문장을 삭제함 (기존 영어 프롬프트 230개 중 이 문장을 쓰는 건 0개, 대신 41개가 "Generate natural original English lyrics..." 스타일을 씀 — 이건 그대로 유지, 삭제 대상 아님).
- 신규 40개: `prompt_id` P1001~P1040(한국어 P1001~1020, 영어 P1021~1040), `track_id`는 생성 시 T001001~T001040이 될 예정, `batch_id` B101~B104(10개씩), `category`는 `mixed_korean_<genre>` / `mixed_english_<genre>` 네이밍.
- 적용 전 백업: `prompts.jsonl.bak_before_mixed_lang_expansion_20260911T014931Z`. 선정 내역 전체는 `mixed_lang_expansion_plan.json`에 저장.
- 전수 검증 통과: 총 1040줄, 파싱 전부 성공, 기존 1000줄 완전 불변(바이트 동일), 신규 40줄이 계획과 정확히 일치, 카테고리/언어/보컬타입 분포 정확(한국어 20/영어 20), "Japanese" 잔존 단어 없음, 전체 1040개 중 완전중복 프롬프트 텍스트 0건.

**⚠️ 참고 사항**: 이번 작업 중 `prompts.jsonl`과 `downloads/metadata`의 `requested` 필드 간 불일치를 하나 발견함 — `P0300`/`P0310`/`P0320`/`P0340`(indie_rock 2, hard_rock 1, punk_power_pop 1)은 예전 "일본어 mixed→male 15곡 전환" 작업 때 prompts.jsonl에서는 male로 바뀌었지만, 그 4곡을 생성한 시점의 metadata 파일에는 옛날 mixed 버전이 그대로 남아있음(즉 실제 생성된 오디오는 mixed, prompts.jsonl 정의는 male로 어긋나 있음). 지금 당장 손볼 필요는 없어 보이지만, 데이터 정합성 이슈로 인지하고 있을 것.

**생성 진행**: 같은 세션에서 바로 실행. Chrome을 `--remote-debugging-port=9222 --user-data-dir=C:\Users\SSAFY\flow-cdp-profile`로 띄우고 `https://www.flowmusic.app/` 새 탭 열어 로그인 상태(테스트 계정) 확인 후 `src/run_queue.py --start-id P1001 --confirm-run`으로 진행. 중간에 `queue_state.json` 저장 시 Windows 파일 잠금으로 한 번(P1026 근처) 크래시났지만 설계된 복구 흐름대로 재실행해서 정상 이어짐(이미 다운로드된 mp3/커버는 안 날아감, GENERATED 상태에서 자동 복구). **세션 종료 시점에 아직 진행 중이었을 수 있음 — 다음 세션에서 `--start-id P1001`로 dry-run 먼저 돌려서 남은 게 있는지 확인할 것.**

### 2-4. 박자(3/4 vs 4/4) 불일치 결함 발견 및 자동 스캔 도구 구축 (2026-09-11) — **핵심 신규 이슈, 진행 중**

**배경**: 모든 프롬프트가 "Strict 4/4 throughout"을 명시하는데도, Flow가 가끔 실제로는 3/4(왈츠) 박자로 곡을 만드는 경우가 있음. 사용자가 T000206, T000493을 직접 듣고 발견 — 가사가 4/4 기준으로 쓰였는데 반주가 3/4라 곡 전체가 어긋나는 심각한 문제(가사 한두 군데 리듬 이탈은 괜찮지만, 이건 곡 전체가 틀어짐). 1000곡+ 전부 수동으로 들을 여유가 없어서 자동 감지 필요.

**한 일**:
1. 자체 휴리스틱(온셋 자기상관 기반)은 신뢰 불가로 판정 — 문제곡도 정상곡도 전부 "ambiguous"로 나와서 폐기.
2. **madmom** (`RNNDownBeatProcessor` + `DBNDownBeatTrackingProcessor(beats_per_bar=[3,4])`)으로 전환 — 내부에 `ballroom_pattern_3_4.pkl`/`ballroom_pattern_4_4.pkl`라는, 정확히 이 문제(3박 vs 4박 다운비트 판별)에 특화된 사전학습 HMM 패턴 모델이 있음.
3. **환경 셋업 (전부 이번에 새로 설치, 향후 세션에서 재설치 불필요)**:
   - Microsoft Visual C++ Build Tools (C++ 워크로드) — `winget install --id Microsoft.VisualStudio.2022.BuildTools` (madmom 컴파일에 필요, 사용자 승인받고 설치)
   - `ffmpeg` — `winget install --id Gyan.FFmpeg` (madmom이 mp3를 직접 읽으려면 필요). PATH가 새 shell에만 반영되므로, 스크립트 자체가 `os.environ["PATH"]`에 ffmpeg bin 경로(`C:\Users\SSAFY\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.1-full_build\bin`)를 직접 추가하도록 만들어둠.
   - Python 패키지: `librosa`, `soundfile`, `madmom`(+`mido`), `setuptools<81`(madmom이 쓰는 `pkg_resources`가 setuptools 81+에서 제거됨), `cython` — `.venv`에 설치, `requirements.txt`에 librosa/soundfile 추가 반영함 (madmom은 구버전 호환성 문제가 많아 requirements.txt엔 일부러 안 넣음, 아래 호환 패치 참고).
4. **madmom 0.16.1(2018년, 방치된 패키지) 호환성 패치 2가지, 둘 다 적용해야 동작함**:
   - `collections.MutableSequence` 등이 Python 3.10+에서 제거됨 → `collections.abc`에서 가져와 `collections` 네임스페이스에 다시 붙이는 몽키패치 필요 (스크립트 상단에서 madmom import 전에 실행).
   - `np.float`/`np.int`/`np.object`/`np.str` 등이 numpy 1.24+에서 제거됨 → 마찬가지로 몽키패치.
   - **`.venv\Lib\site-packages\madmom\features\downbeats.py` 287번째 줄을 직접 수정함** (numpy 2.x에서 `np.asarray(ragged_list)`가 더 이상 암묵적 object 배열을 안 만들어줘서 에러 남 → `best = np.argmax(np.array([r[1] for r in results]))`로 로그확률만 뽑아 비교하도록 변경). 이건 venv 안의 서드파티 코드를 직접 고친 것이라 **venv를 새로 만들면 이 패치도 다시 해야 함** — 패치 내용은 위 그대로, 필요시 diff 재현 가능.
5. **검증**: T000206/T000493(문제곡)이 **100% 3/4**로, 대조군 4곡(같은 pop_ballad 장르 포함)이 **100% 4/4**로 깨끗하게 갈림 — 감지 신뢰도 높음.
6. **성능**: 곡당 직렬 처리 시 약 30~40초. 22코어 중 10개를 병렬로 써서(`multiprocessing.Pool`, `spawn` context) 처리 — 20곡에 약 1.3분, 전체 1000여곡은 약 1시간 예상.
7. **스캐너 스크립트**: `scripts/scan_meter_mismatch.py` — `logs/meter_scan_result.jsonl`에 `track_id`별 1줄씩 결과 저장(`verdict`: `4/4_OK` / `3/4_MISMATCH` / `MIXED/UNSTABLE` / `NO_BARS_DETECTED` / `ANALYSIS_FAILED`, `frac3` = 3박자로 판정된 마디 비율). 이미 스캔된 `track_id`는 자동 스킵하므로 중단해도 재실행하면 이어서 진행됨 (`--redo`로 강제 재스캔 가능, `--workers N`으로 병렬 수 조절, 기본 10).
8. **✅ 스캔 완료** (2026-09-11): 전체 1046곡(기존 1000 + 신규 40 + 중복파일 T001026_1 등) 스캔 완료. 결과: **4/4_OK 1027곡, 3/4_MISMATCH 19곡**, 그 외(MIXED/UNSTABLE 등) 0건 — 전부 `frac3` 0.0 또는 1.0으로 애매한 경우 없이 깨끗하게 갈림.

**3/4_MISMATCH 19곡 목록**:
- 보컬/mixed 5곡 (가사-반주 어긋남이 실제로 들리는 심각한 케이스): `T000206`(male_korean_pop_ballad), `T000223`(female_english_pop_ballad), `T000488`(male_korean_singing_rap_melodic_hiphop), `T000493`(female_english_singing_rap_melodic_hiphop), **`T001012`(mixed_korean_pop_ballad — 오늘 새로 만든 40곡 중 하나, 이 문제가 과거 이슈가 아니라 지금도 발생 중임을 보여줌)**
- instrumental 14곡 (전부 클래식/오케스트라 계열에 몰림 — 왈츠가 그 장르의 흔한 관용구라 "Strict 4/4" 지시를 더 자주 무시하는 것으로 추정): `T000786`, `T000794`(cinematic_orchestral), `T000830`, `T000838`(adventure_fantasy), `T000841`(emotional_drama), `T000911`, `T000924`, `T000926`(solo_piano), `T000933`, `T000940`, `T000947`, `T000948`(neo_classical), `T000962`(acoustic_guitar_folk), `T000993`(chamber_classical)

**✅ 재생성 완료 (2026-09-11, 같은 세션에서 바로 진행)**: 사용자가 "이동 후 성공 확인되면 삭제"로 방침 결정 → 19곡을 `quarantine/{track_id}/{timestamp}/`로 이동 + `queue_state.json`에서 WAITING으로 리셋(`scripts/quarantine_meter_mismatch.py`) → 같은 프롬프트로 `run_queue.py --confirm-run` 재생성 → 박자 스캐너로 재검증 → 4/4 확인된 것만 quarantine 원본 확정 삭제(`scripts/finalize_meter_requeue.py`).
- **1차 재생성**: 19곡 중 **14곡 성공**(4/4), 5곡(`T000206`, `T000794`, `T000911`, `T000924`, `T000947`) 여전히 3/4.
- 재생성 전에 "혹시 특정 프롬프트만 왈츠로 편향되는 특징이 있는지" 확인함 — **없었음**. 같은 카테고리 내 성공한 곡들과 BPM/mood/energy/rhythm/구조 태그가 완전히 동일(예: T000206과 실패 없이 성공한 T000218은 energy 태그까지 정확히 일치). 즉 **프롬프트 문제가 아니라 Flow 생성 자체의 확률적 변동**으로 결론.
- **2차 재생성** (같은 방식으로 5곡 다시 시도): **3곡 추가 성공**(`T000206`, `T000911`, `T000924`), **2곡은 두 번 연속 실패**(`T000794` instrumental_cinematic_orchestral, `T000947` instrumental_neo_classical).
- **프롬프트 강화 후 3차 재생성** (남은 2곡): `prompts.jsonl`의 `P0794`/`P0947`만 "Strict 4/4 throughout;"을 "Strict 4/4 throughout only -- absolutely never 3/4, 6/8, or any waltz/triple-meter feel;"로 강화(`scripts/strengthen_4_4_clause.py`, 백업: `prompts.jsonl.bak_before_strengthen_4_4_20260911T060957Z`) → **T000947 성공**, T000794는 실패.
- **T000794 4차 재생성** (강화된 프롬프트로 2번째 시도): **또 실패**. 총 5번 연속(원본 포함 6개 생성 결과 중 6개 다) 3/4.
- **⚠️ 중요한 재검토 (사용자가 실제로 들어보고 지적)**: T000794의 최신 결과("Echoes of the Crest")를 사용자가 직접 들어보니 **청감상 전혀 문제가 없었음**. 재확인해보니 이유가 명확함 —애초에 이 이슈를 처음 발견한 계기(`T000206`/`T000493`)는 **보컬곡의 가사가 4/4로 쓰였는데 반주가 3박이라 가사-반주가 실제로 어긋나 들리는** 문제였음. `T000794`는 **instrumental**이라 애초에 "박자 안 맞는 가사"가 존재할 수 없고, 프롬프트 자체도 "Driving/Powerful" 무드의 시네마틱 오케스트라라 3박이어도 왈츠처럼 느긋하지 않고 웅장하게 들려서 귀에 거슬리지 않음. **즉 "Strict 4/4 스펙 위반"은 여전히 사실이지만, instrumental에서는 보컬곡만큼 청감상 심각한 문제가 아닐 수 있음.**
- **사용자 최종 결정**: `T000794`는 재시도 중단, **현재(5번째 재생성) 버전 "Echoes of the Crest"를 그대로 최종 채택**. 이미 4/4로 재생성해서 확정한 다른 instrumental 13곡("사후적으로 보면 안 바꿔도 됐을 수도 있는" 케이스들)도 **이미 끝난 일이니 되돌리지 않고 그대로 둠**.
- **최종 결과: 19곡 중 18곡 4/4로 정상화, `T000794`(instrumental) 1곡만 3/4 상태로 최종 채택(청감상 문제 없다고 판단, 의도적 예외).**
- **다음에 비슷한 상황(instrumental 3/4)이 또 나오면 참고할 교훈**: 보컬/mixed 곡의 3/4는 가사-반주 어긋남이 실제로 들리므로 최우선으로 재생성 가치가 있음. instrumental 3/4는 스펙 위반이긴 하나 청감상 문제가 없을 수 있으므로, 무조건 재생성하기보다 **먼저 한 번 들어보고 판단**할 것 (재생성에 드는 시간/재시도 비용 대비 실익이 낮을 수 있음).
- 재생성 스크립트는 재사용 가능: `scripts/quarantine_meter_mismatch.py`(상단 `TRACK_IDS` 리스트만 바꿔서), `scripts/finalize_meter_requeue.py`(마찬가지), `scripts/strengthen_4_4_clause.py`(프롬프트 자체를 더 강하게 고쳐야 할 때 참고용 패턴).

### 2-5. Wave 8 제목 정리 — Take/Option/Alt 태그 및 신규 40곡 내부 반복 (2026-09-11)

사용자가 신규 mixed 40곡 제목을 직접 훑어보다가 발견: 완전중복 1건("한계 돌파"가 `T001004`/`T001018`에 그대로 반복), Take/Option/Alt 접미사 태그 잔존 5건(재생성 과정에서 Flow가 후보 카드에 "(Take 1)"/"(Take 2)"/"(Alt Take)"/"(Option 2)"를 붙인 채로 나온 것들 — 오늘 재생성한 `T000841`도 포함), `mixed_korean_*` 신규 20곡 내부에서 "발걸음"(3회)·"나란한"(2회) 반복.

**적용 (7건)**:
| Track | 변경 전 | 변경 후 |
|---|---|---|
| T000841 | Echoes of Hope (Option 2) | Rising Warmth (태그 제거 시 T000786과 충돌해서 재작명) |
| T001006 | 나란한 시선 (Take 1) | 나란한 시선 |
| T001008 | 나란한 발걸음 (Alt Take) | 같은 보폭 (태그 제거 + 나란한·발걸음 중복 동시 해소) |
| T001012 | 다락방의 음반 (Take 2) | 다락방의 음반 |
| T001040 | Quiet Space (Take 2) | Quiet Space |
| T001001 | 발걸음 맞춰 | 박자를 맞춰 (발걸음 클러스터 해소) |
| T001018 | 한계 돌파 | 전력 질주 (T001004와 완전중복 해소) |

계획: `title_rework_plan_wave8.json`, 백업: `downloads/metadata.bak_before_wave8_20260911T060242Z`, 스크립트: `scripts/build_title_plan_wave8.py`/`scripts/apply_title_plan_wave8.py`. 전수 검증 통과(7건 전부 정확히 반영, 새 완전중복 없음).

### 2-6. `_1` 중복 파일 정리 + metadata 경로 정합성 수정 (2026-09-11) — **GPU 업로드 직전 정리**

**배경**: 사용자가 GPU 서버에 전체를 덮어쓰기 방식으로 재업로드하려는 시점에, `downloads/audio`·`downloads/covers`에 남아있던 `_1` 접미사 중복 파일(예: `T000746_1.mp3`)을 **사용자가 직접 수작업으로 정리**해서 1040개(정본 파일명만)로 맞춤.

**발견된 문제**: `T000350`, `T000746`, `T000847`, `T000987`, `T000992`, `T001026` 6개 track_id는 metadata의 `assets.audio.path`/`assets.cover.path`가 **원래 `_1` 파일 쪽을 정본으로 가리키고 있었음** — 그 `_1`이 삭제되면서 metadata의 참조 경로가 깨짐(파일이 존재하지 않는 경로를 가리킴).

**조치**: `scripts/fix_orphaned_asset_paths.py`로 이 6개의 `assets.audio`/`assets.cover`의 `path`/`size_bytes`/`sha256`을 **지금 실제로 남아있는 파일 기준으로 재계산해서 갱신**. 수정 전 백업: `downloads/metadata.bak_before_orphan_path_fix_20260911T065555Z`. 전수 검증(1040개 metadata 전부 audio/cover 경로가 실제 파일과 연결됨) 통과.

**⚠️ 중요한 정정 (반드시 기억할 것)**: 처음엔 "base 파일과 metadata가 원래 기록했던 해시가 다르니 위험할 수 있다"고 사용자에게 보고했는데, **이건 주제넘은 판단이었음(사용자가 직접 지적함)**:
- `flow_generated`의 title/가사/사운드 설명도 결국 Flow가 특정 후보 카드에 대해 스스로 붙인 약한 라벨일 뿐, 검증된 사실이 아님.
- **사용자가 과거 세션들에서 후보 2개 중 하나를 직접 듣고 골라 파일을 바꿔치기한 이력이 여러 번 있었음(그때그때 나에게 알리지 않은 것도 많음)**. 즉 "해시가 다르다"는 신호는 자동화 버그가 아니라 **사용자의 의도적인 선곡 결과**를 잡아낸 것일 가능성이 높았음.
- Claude(나)는 이 둘을 구분할 방법이 없는데도 "이 4곡은 위험, 2곡은 안전"이라고 단정적으로 말한 것이 잘못. **사용자 확인 결과: 지금 downloads 폴더에 남아있는 파일들이 전부 이미 검토/선별을 마친 정본**이라고 확정함.
- **교훈**: `downloads/audio`·`downloads/covers`에 metadata의 예전 기록과 다른 파일이 있어도, 그 자체로 "버그/위험"이라고 단정하지 말 것. 사용자의 수작업 선곡 이력이 있을 수 있으므로, 먼저 사용자에게 "이 파일이 의도된 최신 버전이 맞는지"부터 확인하고, 파일-메타데이터 경로 동기화(path/size/hash 갱신)는 필요시 진행하되 **title/가사/사운드 설명 텍스트 자체를 임의로 재해석하거나 의심하지 말 것**.

### 2-7. GPU 서버 전체 미러 업로드 (2026-09-11) — ✅ 완료

**배경**: 사용자가 `downloads/{audio,covers,metadata}` 로컬 1040개를 GPU 서버(`j-j15a204@70.12.130.102:~/flow_music_data`)에 **완전히 덮어쓰기**로 재업로드 요청. 기존 `scripts/sync_to_gpu.sh`는 WSL(Ubuntu-22.04)에서 rsync+ssh로 동작하며 기본은 `--delete` 없는 증분 동기화.

**진행 경과**:
- dry-run(삭제 없음) 먼저 확인 → audio 289개 전송(786MB), covers 290개(38MB), metadata 628개(3MB) 예정, 삭제 0건.
- **원격이 이전에 전체 동기화된 게 아니었음을 발견** — 실제로는 760여 개만 있었음 (예전 "GPU 동기화 완료" 기록은 부실했던 것으로 보임).
- 사용자가 "완전히 덮어써서 로컬과 100% 동일하게" 요청 → `--delete` 옵션 필요 확인.
- `--delete` dry-run에서 원격의 `T000847_1.mp3/jpg` 잔재뿐 아니라, 우리 작업과 무관해 보이는 원격 자체의 `.ipynb_checkpoints/`(Jupyter 체크포인트)까지 삭제 대상에 걸리는 것을 발견해 사용자에게 재확인함 → **사용자가 "flow_music_data 내부는 전부 로컬 1040개 기준으로 갈아치워도 된다"고 명확히 확정** (jupyter 체크포인트 포함 전부 삭제 OK).
- `rsync -avh --delete`로 audio/covers/metadata 3개 폴더 전부 실행 완료.
- **최종 검증**: 원격 audio/covers/metadata **전부 정확히 1040개**, 비표준 파일명(구 `_1` 잔재, 체크포인트 등) 0건 확인.

**⚠️ 실행 중 겪은 삽질 (다음에 참고)**: WSL을 Windows Bash(Git Bash) 도구에서 호출할 때 **경로 자동 변환(MSYS path conversion) 때문에 `/mnt/c/...` 같은 유닉스 경로가 깨짐** — 한 번은 이것 때문에 `cd`가 실패해서 rsync가 의도한 폴더 대신 **C 드라이브 전체를 스캔**한 사고가 있었음(다행히 `--dry-run`이라 실제 피해 없음). 해결법: `MSYS_NO_PATHCONV=1 wsl bash <스크립트파일.sh>` 형태로 **반드시 스크립트 파일을 만들어서 실행**할 것 — 인라인으로 `wsl bash -c "..."`에 중첩 따옴표/변수를 넣는 방식은 이스케이프가 깨지기 쉬우므로 피할 것.

**재사용 가능한 스크립트**: 이번에 임시로 쓴 dry-run/실행 스크립트는 스크래치패드에 있었지만, 필요하면 같은 패턴(`RSH`/`SRC`/`DEST` 변수 + `for sub in audio covers metadata` 루프)으로 다시 만들면 됨. 원본 `scripts/sync_to_gpu.sh`는 `--delete` 없이 증분만 하니, "완전 덮어쓰기"가 필요하면 `--delete` 옵션을 추가해서 쓸 것(단, 실행 전 항상 `--dry-run`으로 삭제 대상부터 확인).

### 2-8. 가상 아티스트/앨범 그룹핑(분류) 1차 작업 (2026-09-14) — **신규 착수, 그룹핑만 완료**

**배경**: 서비스에서 ERD 기준으로 아티스트를 표시하기로 결정되어, 곡별로 가상 아티스트를 배정하는 작업 착수. 기준은 "비슷한 카테고리로 묶어서 아티스트 페르소나화"이며, 분류 축은 언어(english/korean/japanese)/보컬-인스트/성별(female/male/mixed). 앨범도 같은 방식으로 분류 예정.

**핵심 발견**: 이 세 분류 축(언어/보컬-인스트/성별)은 이미 각 metadata의 `requested.vocal_type`(성별, instrumental 포함)·`requested.language`·`requested.category`(`{vocal_type}_{language}_{genre}` 또는 `instrumental_{genre}`)에 전부 들어있었음. 즉 "분류"는 이미 되어 있고, 실제 해야 할 일은 **180개의 세분화된 카테고리(1~41곡 편차)를 아티스트 단위(6~15곡)로 재구성**하는 것.

**사용자 결정 사항** (이번 세션에 확정):
1. 저장 위치: metadata 파일을 건드리지 않고 **별도 `artists.json` / `albums.json`** (프로젝트 루트)에 저장.
2. 아티스트당 카탈로그 규모: **6~15곡** 목표.
3. 이번 세션 범위: **그룹핑(분류)까지만**. 아티스트 이름/소개(페르소나 디테일) 생성은 다음 단계로 보류.

**방법론 (3단계)**:
1. 1차 경계: `vocal_type`(성별, instrumental 포함) × `language` — 이미 있는 조합이 아티스트 세계의 최상위 경계.
2. 2차 경계: 21개 보컬 장르 / 33개 인스트 장르를 프로덕션 스타일이 겹치는 슈퍼장르 클러스터로 매핑(예: contemporary_pop/dance_pop/synthpop/city_pop/pop_ballad/poprock → `pop_mainstream`, 인스트 lofi_hiphop/chillhop/jazzhop/downtempo/acoustic_chill → `chill_lofi` 등, 스크립트 상단 `VOCAL_GENRE_CLUSTER`/`INSTRUMENTAL_GENRE_CLUSTER` 참고).
3. (성별, 언어, 클러스터) 버킷 안에서 (클러스터, 세부장르, track_id) 순 정렬 후 그리디 bin-pack: 클러스터 경계에서 우선 끊되 6곡 미만이면 다음 클러스터까지 이어붙이고, 15곡 도달 시 강제로 끊음. 마지막에 6곡 미만 잔여분이 남으면 직전 그룹과 합쳐서 **균등 재분배**(그냥 이어붙이면 20곡짜리 초대형 그룹이 생기는 버그가 있어서 수정함 — `balanced_split` 함수).
4. 보컬 전용 QA(그룹핑을 바꾸진 않고 경고만 남김): `flow_generated.sound_raw`에서 음색 키워드(성부: soprano/alto/tenor/baritone, 텍스처: warm/breathy/powerful/raspy 등) 추출 → 그룹 내 최다 커버리지 단어 비율이 40% 미만이면 `[TONE-SPREAD]` 경고, 그룹 성별과 반대되는 성부 용어가 섞이면 `[REGISTER-MISMATCH]` 경고. **주의**: 첫 버전에서 "alto saxophone"의 "alto"를 여성 성부로 오탐(false positive)한 버그 발견 후 수정함(악기명 뒤에 sax/saxophone이 오면 성부 용어에서 제외) — 향후 음색 키워드 확장 시 이런 악기명 충돌(예: "soprano sax") 계속 주의할 것.
5. 앨범: 각 아티스트의 곡을 발매 단위(4~7곡)로 균등 청크(현재는 track_id 순서 기반 단순 분할, mood/BPM 기반 앨범 테마화는 다음 단계 후보).

**결과** (`logs/artist_album_grouping_report.txt`에 상세 리포트):
- 총 1040곡 → **아티스트 81명**(보컬 43 + 인스트 38), **앨범 202개**. 전 아티스트 6~15곡 목표 범위 충족(범위 밖 0명).
- 보컬 음색 경고 3건(전부 `[TONE-SPREAD]`, 6~8곡 소규모 그룹에서 음색 표현이 흩어진 경우 — 실제 문제인지는 다음 단계에서 가사/사운드 직접 검토 권장): `female_japanese_dance_electronic+hiphop_urban_01`, `male_korean_rock_edge_01`, `mixed_japanese_rock_edge_01`.
- 스크립트: `scripts/build_artist_album_grouping.py` (재실행 가능, 결정론적 — 매번 같은 입력이면 같은 결과).

**다음 단계 (미착수)**: 3-6 참고.

### 2-9. 보컬곡 프레이즈 길이(마디 수) 이탈 조사 — **완료, 조치 불필요로 결론** (2026-09-14)

**배경**: 사용자가 T000195(105초 지점)를 직접 듣고 발견한 문제. 모든 프롬프트가 "Strict 4/4, 모든 섹션은 완전한 4마디 또는 8마디 단위"를 요구하는데도, 가끔 한 섹션 내부에서 4+4+8이 4+5+8이나 3+4+8처럼 마디 수가 어긋나는 경우가 있음(곡 전체는 4/4가 맞지만 국소적으로 리듬이 어긋나는 느낌). 기존 2-4의 3/4 vs 4/4 전역 미터 문제와는 다른, 국소적 프레이즈 이탈 문제.

**방법론**: madmom `DBNDownBeatTrackingProcessor(beats_per_bar=[4])`로 전체 다운비트를 측정해서 `total_bars % 4 != 0`이면 후보로 표시(모든 섹션이 4/8마디 단위라는 프롬프트 규칙상 정상이면 총 마디 수도 4의 배수여야 함). T000195에서 검증: 86마디 검출(86%4=2), 사용자가 지목한 105초 지점과 정확히 연결됨(Bridge→Final Chorus 경계로 계산됨). 단, "마디 하나가 유독 길다/짧다"는 위치 특정 방법은 신뢰도가 낮다는 것도 이때 확인함(T000195에서 진짜 이탈 지점의 마디 길이 편차가 0.5%뿐이었음 — 삽입/누락된 마디 자체가 "정상 길이"라서 지속시간만으로는 못 잡음).

**스크립트**: `scripts/scan_phrase_mismatch.py` (보컬 496곡 전수 스캔, `scan_meter_mismatch.py`와 동일한 venv/병렬처리/resumable JSONL 패턴 재사용). 결과: `logs/phrase_mismatch_scan_result.jsonl`.

**전체 스캔 결과 (496곡)**: `PHRASE_MISMATCH_CANDIDATE` 349곡, `PHRASE_OK` 147곡. **이 349라는 숫자 자체는 오해하기 쉬움** — 대부분 실제로 들었을 때 티가 안 나는 수준이라, 심화 분석으로 더 걸러냄.

**심화 분석** (단일 마디 편차 ≥20%인 65곡을 골라서, 마디 길이 편차 ≥25%인 마디가 몇 개나 있는지 재검사):
- 심각한 마디 0개: 26곡 (노이즈 수준 편차) → 조치 불필요
- 심각한 마디 정확히 1개: 24곡 → 사용자 정책상("이탈 구간 1개면 넘어가는 정도") 조치 불필요
- **연속된 다수 마디가 똑같이 ~50%/~100% 편차인 경우: 10곡** (`T000955`, `T000204`, `T000163`, `T000403`, `T000468`, `T000521`, `T001039`, `T000284`, `T000467`, `T000773`) — 처음엔 "여러 군데 이탈"로 보였으나, 실제로는 **곡의 한 구간이 통째로 하프타임/더블타임 느낌으로 바뀌는 것**(재즈 장르에서 흔한 "슬로우 기법" 등)이라 리듬 이탈이 아님. `T000772`를 사용자가 직접 듣고 "jazz 장르에서 흔한 슬로우 기법"이라고 확인해줌 → 조치 불필요로 확정.
- **서로 멀리 떨어진 별개의 심각한 마디 2개 이상: 3곡** (`T000172` male_english_city_pop, `T000298` female_korean_indie_rock, `T000446` male_korean_boom_bap_hiphop) — 유일하게 진짜 "여러 군데서 따로 튄" 케이스. 사용자가 직접 듣고 "퀄리티는 좋고, 신경 쓰고 들으면 이탈이 느껴지긴 하지만 신경 쓰일 정도로 심각하진 않다"고 판단 → **조치 불필요로 최종 확정**.

**사용자 정책 (이번에 확정, 앞으로도 적용)**: 토큰/재생성 비용 대비 실익이 낮고, 재생성해도 다시 완벽한 마디 구조가 나온다는 보장이 없어서, **한 곡 안에 이탈이 딱 1군데면 그냥 감수하고 넘어간다. "3/4 미스매치급으로 심각하게 여러 군데" 나오는 경우만 걸러낸다.** (참고: 이 정책 기준으로 원래 발견 계기였던 T000195 자체도 "1군데 이탈"이라 조치 불필요 대상에 포함됨.)

**알려진 한계 (다음에 비슷한 조사할 때 참고)**: `total_bars % 4 != 0`이지만 마디 길이 편차가 거의 없는 유형(T000195가 정확히 이 유형, 약 284곡 추정)은 "삽입된 마디 자체가 정상 길이"라서 지속시간 기반으로는 "몇 군데서 튀었는지" 셀 방법이 없음(이번 심화분석 대상 65곡에도 T000195 자체는 포함되지 못함). 이 유형을 더 정밀하게 다루려면 프롬프트의 "base section plan in bars" 템플릿과 실제 보컬 구간 시작/끝 타임스탬프(현재 metadata에 없음, 별도 보컬 활동 검출 필요)를 대조하는 등 더 무거운 접근이 필요하지만, 이번엔 사용자 정책(1군데는 감수)에 따라 더 파고들지 않기로 함.

**최종 결론: 조치 없음. 496곡 전부 현재 상태 유지.**

## 3. 앞으로 할 일 (우선순위 순)

### 3-1. ✅ 완료 — mixed 언어 확장분 40곡 생성
- P1001~P1040 전부 COMPLETED, metadata 1040개 정확히 일치, category/vocal_type/language 계획과 전수 일치, 가사 40곡 전부 정상 추출 확인함 (2026-09-11)

### 3-2. ✅ 완료 — 박자 불일치 재생성 (18/19 정상화, 1곡 의도적 예외)
- `T000794`만 5번 연속 3/4로 나왔으나, 사용자가 직접 청취 후 instrumental이라 청감상 문제없다고 판단 → 현재(5번째) 버전을 그대로 최종 채택, 재시도 중단. quarantine 잔여물 정리 완료.
- 남은 작업 없음. 다음에 비슷한 instrumental 3/4 케이스 나오면 위 2-4 끝의 교훈 참고(무조건 재생성보다 먼저 청취 판단).

### 3-2b. 신규 40곡 제목 재점검 필요할 수도 있음
- Wave8에서 발견 즉시 대응한 것들 외에, 신규 40곡 전체를 2-2의 기준(전역 9~13, 카테고리 내부 15%)으로 재스캔 완료함(2026-09-11) — 새로운 문제 없음 확인. 추가 조치 불필요.

### 3-3. 카테고리/장르 비율 공백 (분석만 완료, 프롬프트 추가는 보류)
사용자가 "mixed 40개 먼저 실행하고, 나머지는 카테고리 비율 분석 후에 몰아서 하자"고 결정해서 **아직 프롬프트 추가 안 함**. 발견된 공백:
- **male_japanese 심각하게 부족**: 전체 15곡뿐 (`female_japanese` 46, `male_korean` 76, `male_english` 93과 비교됨). 21개 대중음악 장르 중 9개(`alternative_rock`, `city_pop`, `contemporary_pop`, `dance_pop`, `indie_pop`, `modern_trap_hiphop`, `pop_ballad`, `poprock`, `singing_rap_melodic_hiphop`)는 male_japanese가 **0곡**. 예전 "mixed→male 15곡 전환" 때 이 9개 장르는 전환 대상에서 빠진 것으로 보임. → 가장 우선순위 높은 보강 후보.
- **instrumental_melodic_electronic**: 34개 instrumental 카테고리 중 유일하게 **1곡**뿐 (나머지는 10~41곡). 초기 파일럿 잔재로 보임 (`P0008`, 배치 첫 줄) → 다른 instrumental 장르처럼 10곡 수준으로 보강 후보.
- (참고, 우선순위 낮음) `male_english_pop`(`P0001`), `female_english_hiphop_rnb`(`P0004`)도 각 1곡뿐인 초기 파일럿 잔재 카테고리. 장르 체계 확정 전 흔적이라 "보강 필요한 장르"라기보다 그냥 낙오 카테고리에 가까움.
- 다음 세션에서 이 공백들에 대한 프롬프트 추가 계획을 세우고, mixed 40개처럼 사용자 승인 받은 뒤 prompts.jsonl에 추가 + 생성까지 진행할 것.

### 3-4. 제목 정비 다음 웨이브 후보
- 없음. Wave5·6·7 완료로 2026-09-11 기준 알려진 문제는 모두 해결됨(기존 1000곡 기준).
- 혹시 새로운 클러스터가 발견되면 Wave5~7과 동일한 절차(계획→검증→백업→적용→전수검증)를 따를 것. 재스캔 시 소규모 vocal 카테고리(6~12곡)에서 %가 높게 나오는 건 대부분 2곡 우연 겹침의 착시이니, "3곡 이상 겹침"인지부터 먼저 걸러서 볼 것 (Wave7에서 이 착시 때문에 오탐 다수 발생했었음).

### 3-10. artist/album 필드 스키마 단순화 — ✅ 완료 (2026-09-14)

`artist.name`+`artist.name_reading`, `album.title`+`album.title_reading`로 분리했던 최초 설계가 사용자 의도(flow_title처럼 "원문(로마자)" 한 필드로 합체)와 안 맞아서 재수정함.
- `artists.json`/`albums.json`에서 `*_reading` 필드를 없애고 일본어 항목만 `name`/`title` 값 자체를 `"원문(Reading)"` 형태로 합침(예: `灯火(Touka)`).
- **부수 발견**: 앨범 제목 재작업 2·3차 때(자유행/橙の頃/疾走/奮起/夜明け前/烈火/羽ばたき/残照/並走/若葉/道連れ 11개) 로마자 발음을 빠뜨렸던 걸 이번에 같이 채움.
- `scripts/apply_artist_album_to_metadata.py` 수정(더 이상 `name_reading`/`title_reading` 필드 안 씀) 후 1060개 전체 재적용+전수검증.
- `docs/metadata_schema_v2.md`도 단일 필드 방식으로 갱신.

### 3-9. male_japanese 보강 20곡 — ✅ 완료 (2026-09-14)

기존 male_japanese가 15곡뿐이고 9개 장르가 0곡이었던 갭(3-3 참고)을 female_japanese 소스 프롬프트 gender 치환으로 보강함(mixed_korean/mixed_english 확장과 동일 패턴, `\bfemale\b`→`male` 정규식 치환 하나로 충분했음 — 다른 성별 지칭 단어 없음을 사전 확인).

- **대상 장르(전부 0→5)**: contemporary_pop, dance_pop, alternative_rock, indie_pop. 공급 부족한 만큼(dance_pop 1건, alt_rock 2건, indie_pop 2건) 소스 프롬프트 재사용.
- **신규 프롬프트**: P1041~P1060(20개), batch B105/B106. 백업: `prompts.jsonl.bak_before_male_japanese_reinforcement_20260914T065225Z`. 스크립트: `scripts/build_male_japanese_reinforcement.py`.
- **생성**: Chrome CDP(이미 로그인된 세션 재사용) + `src/run_queue.py --confirm-run`으로 20곡 전부 1회에 성공 (T001041~T001060).
- **전수 검증**: 기존 1040곡과 완전중복 0건, Take태그 3건(T001051/1052/1060) 제거 후에도 충돌 없음 확인 후 제거. 반복 패턴(潮風 2곡·歩道 2곡)은 카테고리당 5곡 규모에서 2곡 겹침이라 기존 판단 기준(3곡 이상만 문제)상 우연으로 판단, 조치 안 함.
- **일본어 제목 번역**: 기존 90곡과 동일 형식(`원제(번역)`)으로 19곡에 적용(BREAKTHROUGH 1곡은 원제가 영어라 제외). 스크립트: `scripts/finalize_male_japanese_titles.py`.
- **박자 스캔**: 20곡 전부 4/4_OK.
- **아티스트/앨범**: 이번엔 사용자 지시로 **영어 음차 이름** 사용(기존 male_japanese "灯火" 등 한자+로마자 방식과 다름 — 이 보강분만 예외):
  - `male_japanese_pop_mainstream_01` **Citrusline** — 앨범 Postcard Weather(contemporary_pop 5) / Morning Sprint(dance_pop 5)
  - `male_japanese_rock_edge+indie_soft_01` **Duskforge** — 앨범 Forged Signal(alternative_rock 5) / Sunlit Sprout(indie_pop 5)
  - 기존 81명/200개와 이름 중복 0건, 자기 곡 제목과 겹침 0건 확인.
- **metadata 반영**: `apply_artist_album_to_metadata.py`를 1060개 기준으로 재실행, 전수 검증 통과. 최종: **아티스트 83명, 앨범 204개, 트랙 1060곡**.
- 남은 갭: `male_japanese`의 나머지 6개 0곡 장르(city_pop, pop_ballad, poprock, synthpop, modern_trap_hiphop, singing_rap_melodic_hiphop)는 이번엔 다루지 않음. 필요하면 같은 패턴으로 추가 진행 가능.

### 3-6. 가상 아티스트/앨범 — 페르소나 디테일 생성 (진행 중)

**✅ 완료 (2026-09-14): 아티스트 이름 81/81 확정, `artists.json`에 `artist_name`(+일본어는 `artist_name_reading`에 로마자 병기) 필드로 저장 완료.**

**네이밍 원칙 (여러 번의 시행착오 끝에 확정, 다음에 이름 짓는 작업 또 하면 반드시 참고)**:
1. **흔한 일상어/관용구 기피**: 아무리 분위기 있어 보여도 매일 쓰는 말이면(예: "탈주", "동행", "가로등", "손편지"급 흔함, "들불"처럼 관용구에 자주 쓰이는 말) 그냥 평범하게 읽힘. 대신 ①문화적으로 특정 정서가 강하게 박힌 단어(예: "다락방"), ②은어/외래어(예: "아지트"), ③합성어(예: "우산속", "노브레이크"), ④일상에서 잘 안 쓰는 문학적 단어(예: "잔불", "선잠")를 씀. **단, 합성어를 무조건 강제하지 않음** — 상황에 맞게 저 4가지 중 더 나은 걸 골라 씀.
2. **자기 곡 제목과 겹치는 단어 절대 금지**: 그 아티스트의 실제 곡 제목에 나오는 단어를 이름에 쓰면 "OO의 [자기 이름이 들어간 곡 제목]"처럼 어색해짐(예: "아지트"라는 아티스트의 곡이 "비밀 아지트"면 이상함). **이름 후보를 정할 때마다 그 아티스트의 전체 곡 제목과 기계적으로 대조할 것** (사람 눈으로는 반드시 놓침 — 67명분 확인할 때 스크립트로 검증해서야 확실해짐).
3. **실존 브랜드/유명 IP와 겹침 금지**: 예를 들어 "라이엇"은 게임사 Riot Games와 겹쳐서 기각됨, "파이어플라이"는 드라마/게임사와 겹쳐서 기각됨, "문라이트"는 아카데미 수상작 영화 제목이라 기각됨. 68개 이상 지을 때는 이런 것도 사람 판단만으로는 다 못 거르니 주의.
4. **언어권이라고 그 언어 고유어만 강제하지 않음**: 한국어 아티스트도 영어 음차/합성어(예: "언더락", "딥오션", "로드무비")를 자연스럽게 섞어 씀 — 실제 한국 인디/록 씬의 네이밍 관례와도 맞음.
5. 일본어는 원어(한자/가나)를 이름으로 쓰고 로마자 발음을 병기(예: 灯火(Touka)), 영어는 음차하지 않고 그대로.

**최종 확정 81명** (전체 목록은 `artists.json`의 `artist_name` 필드 참고, 요약):
- 한국어 14명(성별×장르): 나이트드라이브/아지랑이/네온사탕/선잠/언더락/딥오션/손편지/물빛/스파클러/로드무비/잔불/노브레이크/우산속(듀오)/달그림자(듀오)
- 영어 20명: Halcyon/Marrow/Fernweh/Marigold/Windward/Linden/Aftershine/Velveteen/Cinderfall/Afterburn/Amberlight/Petrichor/Vellichor/Amberglow/Farlight/Slowlight/Redwake/Backlot/Twin Ember(듀오)/Kindred Frequency(듀오)
- 일본어 9명: 夜光/水彩/蒼炎/焔/灯火/朧月(듀오)/常磐(듀오)/遠雷(듀오)/疾風(듀오)
- 인스트루멘탈 38명: (chill_lofi 7, ambient_atmos 4, electronic_beats 6, game_bgm 6, hiphop_beats 2, jazz_funk 4, orchestral_cinematic 7, acoustic_folk 2 계열)

**✅ 완료 (2026-09-14): 앨범 제목 202/202 확정, `albums.json`에 `album_title` 필드로 저장 완료.**
- 보컬 아티스트 소속 앨범(94개): 그 앨범 안 곡들의 분위기에서 딴 개별 제목(예: "Petal Ink", "낮의 틈", "駆ける音"). 자기 앨범 안 곡 제목과 겹치는 단어 없음(스크립트로 전수 검증).
- 인스트루멘탈 앨범(108개): `{아티스트명} Pt. 1/2/3` 형식. 로파이/앰비언트/게임음악 프로듀서들이 실제로 흔히 쓰는 디스코그래피 관례이고, 이미 충돌 검증된 아티스트명을 재사용하므로 추가 충돌 위험 없음.
- 중복 제목 0건.

**✅ 완료 (2026-09-14): `artists.json`/`albums.json`의 배정을 `downloads/metadata` 각 트랙에 `artist`/`album` 필드로 반영 완료.**
- 필드: `artist: {artist_key, name, name_reading}`, `album: {album_key, title, title_reading}`. 기존 필드는 전혀 안 건드리고 필드만 추가(다른 필드 전부 백업과 byte-identical 검증 완료).
- 절차: 매핑 무결성 사전검증(1040개 전부 아티스트/앨범 정확히 1개씩) → 백업(`downloads/metadata.bak_before_artist_album_fields_20260914T060825Z`) → 원자적 적용 → 전수검증(파일 수/JSON파싱/필드 일치/비관련 필드 불변 4중 검증) 전부 통과.
- 스크립트: `scripts/apply_artist_album_to_metadata.py`. 로그: `logs/artist_album_metadata_apply_log.jsonl`.
- `docs/metadata_schema_v2.md`에 `artist`/`album` 필드 계약 문서 추가함(일본어만 `name_reading`/`title_reading` 채워짐, 나머지는 null이 정상이라는 점 등).

**⚠️ 중요 버그 발견 및 수정 (2026-09-14, 사용자가 직접 청취 후 발견)**: 초기 앨범 분할 로직(`build_artist_album_grouping.py`의 `chunk_album()`)이 그냥 4~7곡씩 균등 분할만 해서, **세부 장르(`requested.category`) 블록 하나가 두 앨범에 걸쳐 반으로 쪼개지는 버그**가 있었음. 예: `female_korean_rock_edge_01`(언더락)의 indie_rock 4곡 중 2곡만 "무게" 앨범(alt_rock/hard_rock과 섞임)에 들어가고 나머지 2곡은 "가속" 앨범에 들어가서, 사용자가 실제로 들어보니 앨범 안에서 무드가 전혀 안 맞았음(사용자 표현: "저어어어어언혀 안맞음"). 전체 검사 결과 81명 중 66명(75건)에서 같은 문제가 있었음.
- **수정**: `scripts/rebuild_albums_genre_aware.py` — 세부 장르 블록을 먼저 순수하게 나누고(블록이 7곡 넘으면 그 블록 "안에서만" 균등분할), 그 순수 청크 단위로만 4~7곡씩 이어붙이는 방식으로 재작성. 트랙을 쪼개서 다른 장르와 섞는 일이 원천적으로 없음.
- **검증**: 200개 앨범 중 197개는 완전히 장르 순수(한 앨범에 여러 장르가 있어도, 그 장르들이 다른 앨범에 흩어져 있지 않으면 문제없음으로 간주). 나머지 3개는 "소수 장르 1곡이 이웃 장르 앨범에 얹혀가는" 경미한 수준만 남음(예: alt_rock 4곡+hard_rock 1곡). 앨범 개수는 202→206→최종 200개로 재조정됨.
- **부수 작업**: 앨범 구성이 바뀌면서 기존 수록곡 기반 제목 중 상당수(최종 128개)가 무효화되어 전부 다시 지음(내용 기반, 자기 곡 제목 겹침 재검증 포함). `downloads/metadata`의 `artist`/`album` 필드도 새 구성 기준으로 재적용함(백업: `downloads/metadata.bak_before_artist_album_fields_20260914T062850Z`).
- **교훈**: 그룹핑/분할 알고리즘을 짤 때 "정렬 순서를 지킨다"와 "그 순서를 균등 분할해도 안전하다"는 서로 다른 가정이다 — 정렬은 순서만 보장하고, 분할 경계가 의미 단위(장르 블록)와 일치하는지는 별도로 검증해야 함. 이번처럼 사람이 직접 들어보고 발견하기 전까지 스크립트 상 "정상 동작"처럼 보였음.

**남은 것**:
- 아티스트 짧은 소개/컨셉 문구는 대화 중에 만들었으나 아직 `artists.json`에 필드로 저장 안 함 — 필요하면 다음에 추가.
- 아티스트/앨범 관련 작업은 이걸로 실질적으로 마무리됨(2-8, 3-6 전체 완료, 앨범 분할 버그 수정 포함).
- `[TONE-SPREAD]` 경고 3건(2-8 참고) — 재검토 결과 그대로 유지하기로 결정됨(2026-09-14, 별다른 문제 없다고 판단).
- 아티스트 프로필 이미지/앨범 커버 아트 필요 여부 결정 안 됨.
- ERD 실제 필드명과 스키마 대조 아직 안 함.

### 3-7. 보컬곡 프레이즈 이탈 재조사 — ✅ 완료, 조치 불필요 (2026-09-14)
2-9 참고. 496곡 전수 스캔 + 심화분석 후 "조치 없음, 496곡 전부 현재 상태 유지"로 최종 결론남. 남은 작업 없음.

### 3-8. 일본어 곡 제목 한국어 번역 — ✅ 완료 (2026-09-14)
일본어 91곡(mixed_japanese/female_japanese/male_japanese) 중 원제가 이미 영어인 T000399("Empty Seat") 제외 90곡의 `flow_title`에 "일본어원제(한국어번역)" 형식으로 최소한도 번역을 괄호 병기함(예: `明日への翼(내일을 향한 날개)`). 아티스트/앨범명은 반대로 번역하지 않고 음차만 함(3-6 참고) — 곡 제목과 아티스트/앨범명의 번역 방침이 다르다는 점 기억할 것.
- 백업: `downloads/metadata.bak_before_ja_translation_20260914T052746Z`
- 스크립트: `scripts/apply_title_translation_japanese.py`, 계획: `title_translation_plan_japanese.json`
- 로그: `logs/title_rework_log.jsonl`에 `wave: "ja_translation"`으로 90건 추가됨
- 전수 검증 통과(1040개 파일 수 유지, JSON 전부 파싱 성공, 계획한 90건 정확히 반영).

### 3-5. 손 안 대기로 확정한 것 (재작업 불필요)
- **light(12), line(11)**: 재조사 결과 상당수가 동음이의어("light"=가벼움 vs 밝기)나 관용구("Half-Light")라 진짜 반복이 아님. 추가 작업 불필요 판단, 사용자도 별 이견 없었음.
- **instrumental_adventure_fantasy의 legend's/odyssey/valiant/kingdom** (각 15%, Wave4에서 새로 생김): 초과가 아니라 정확히 경계값이라 그대로 둠.
- **female_english_dance_pop 3곡** (line 클러스터): 전부 코러스 그 자체("driving to the coast line" 등)라 영구 제외 확정.

### 3-12. 보컬곡 가사 싱크(줄 단위 타임스탬프) 생성 — ✅ 완료 (2026-09-14~15)

**배경**: 사용자가 가사 섹션(Verse/Chorus 등)을 더 세분화해서 몇 분 몇 초에 어떤 가사가 나오는지 확인하고 싶다고 요청. `faster-whisper`(word-timestamp) + 원문 가사를 문자 단위로 대조(difflib)하는 방식으로 검증 후 보컬곡 516곡 전체 적용까지 완료함.

**검증 단계 (2026-09-14)**:
- 일본어(T001060)/한국어(T000016)/영어(T000001) 3곡으로 프로토타입 검증. `vad_filter=True`(기본값)가 에너지 낮은 트랙의 보컬을 무음으로 오인해 세그먼트를 통째로 날리는 버그를 발견 — **반드시 `vad_filter=False`로 돌릴 것** (다음에 또 이런 파이프라인 만들 때 재확인).
- 오디오+가사 동시 재생 확인용 아티팩트([Lyric Sync QA](https://claude.ai/code/artifact/26e7ca3f-b8b6-43cf-84ba-aad4de0fa5d0)) 만들어서 사용자가 직접 청취 검증함. 결과: 영어는 92~100% 수렴, 일본어도 준수, 한국어만 애드립/스캣 구간 정렬 실패가 두드러짐.
- **사용자 결론 (중요, 재작업 불필요로 확정됨)**: 한국어 정렬 실패는 정렬 로직 결함이 아니라 **Flow Music이 한국어 가사를 생성할 때 원문 가사가 실제 보컬에서 압축/생략되는("짜부라지는") 현상** 때문. 고칠 수 있는 문제가 아니므로 재시도 로직 등 추가 조치 없이 그대로 받아들이기로 함.

**GPU 서버 관련 신규 발견 (다음에 무거운 연산 필요하면 참고)**:
- `j-j15a204@70.12.130.102`(기존엔 파일 동기화 용도로만 씀)에 **Tesla V100 32GB × 10장**이 있고, 이번 확인 시점엔 전부 유휴 상태였음. 이미 다른 프로젝트용 venv(`~/envs/allin1` 등)에 PyTorch+CUDA 12.4가 깔려 있어 GPU 연산 자체는 언제든 가능.
- 로컬 CPU(Intel Ultra 9 185H, 22코어) 벤치마크 결과 **멀티프로세싱 병렬화가 사실상 무의미함**(4워커도 순차 처리와 동일한 wall time) — ctranslate2가 프로세스 하나로도 이미 컨텐션을 만들어내는 것으로 보임. 로컬에서 이런 ASR/오디오 배치 작업을 할 땐 병렬 워커보다 **GPU 서버 활용을 먼저 고려할 것**.
- GPU(V100) 1장 기준 곡당 처리시간이 CPU 대비 약 **10~15배** 빠름(2~5초 vs 25~50초, small 모델 기준).
- 전용 venv `~/envs/lyrics_sync` 새로 만듦(기존 allin1 등 다른 프로젝트 venv와 안 섞이게). `faster-whisper` 설치됨. 필요하면 재사용 가능.

**전체 516곡 배치 실행 (2026-09-15)**:
- 대상: `flow_generated.lyrics_present == true`인 516곡(영어 247·일본어 108·한국어 150 — 위 11곡 제외 후 유효 처리분 기준, 원래 카운트는 영어 250/일본어 111/한국어 155).
- 스크립트: `scripts/lyrics_sync_gpu_batch.py`(GPU 서버에서 실행, GPU 2장에 트랙을 절반씩 나눠 병렬 처리, 트랙별 결과 파일 존재 여부로 체크포인트/재개 가능).
- 실행 결과: **516/516곡 전부 성공(에러 0건)**, GPU 2장 병렬로 약 16분 소요.
- 로컬로 결과 회수(scp) → `downloads/lyrics_sync/{track_id}.json`에 스테이징(원본 ASR transcript 포함, 감사용으로 보존).
- 품질 집계(`scripts/lyrics_sync_report.py`): 평균 커버리지 영어 88.0%·일본어 89.0%·한국어 79.9% — 사전 확인된 한국어 특성과 일치, 추가 조치 없음.
- **11곡은 `lyrics_sections`가 애초에 `null`이라(원인: `lyrics_raw`에 줄바꿈이 아예 소실된 상태로 저장된 기존 데이터 품질 문제, 이번 작업과 무관한 선행 이슈) 정렬 대상 자체가 없었음** — `lyrics_sync: null`로 남김. 목록: T000075/T000146/T000150/T000216/T000251/T000337/T000400/T000482/T000527/T001018/T001059.
- T000407 1곡은 ASR이 실제 가사를 거의 인식 못 함(커버리지 0%) — 위 한국어 압축 현상의 극단적 사례로 판단, 별도 조치 안 함(전체 라인은 존재하되 전부 타임스탬프 null).

**메타데이터 반영**:
- 사용자 지시로 **`lyrics_sync` 필드는 가사 텍스트+타임스탬프만** 담도록 최소화함(엔진/모델/신뢰도 등 부가 정보 전부 제외): `[{"text", "start_sec", "end_sec"}, ...]`. 정렬 실패 줄도 별도 표시 없이 `start_sec`/`end_sec`만 `null`.
- 스크립트: `scripts/apply_lyrics_sync_to_metadata.py` (기존 artist/album 필드 추가 때와 동일한 "사전검증→전체백업→원자적적용→전수검증" 패턴).
- 백업: `downloads/metadata.bak_before_lyrics_sync_20260915T022353Z`. 로그: `logs/lyrics_sync_metadata_apply_log.jsonl`.
- 전수 검증 통과(파일 수 1060 유지, 516곡에만 필드 추가, 나머지 544곡 불변 확인).
- **후속 보완**: 1차 적용 직후엔 인스트루멘탈 544곡에 `lyrics_sync` 키 자체가 아예 없는 상태였음(값이 `null`인 게 아니라 필드 누락) — 이 스키마의 다른 필드들(`source.model` 등)은 전부 "해당 없음"을 명시적 `null`로 표기하는 컨벤션이라 일관성이 깨짐을 발견하고, `scripts/fill_lyrics_sync_nulls.py`로 나머지 544곡에 `lyrics_sync: null`을 명시적으로 채움(동일하게 백업→적용→전수검증). 최종적으로 **1060개 전부 `lyrics_sync` 키를 가짐**(516개는 배열, 544개는 `null`).
- `docs/metadata_schema_v2.md`에 `lyrics_sync` 계약 추가함.
- GPU 서버 metadata를 `scripts/sync_to_gpu_full_mirror.sh`로 두 번 재동기화(1차 적용 직후 + null-fill 후속 적용 직후). 둘 다 삭제 없음 확인 후 반영.

**✅ 후속 완료 (2026-09-15, 같은 세션): 11곡 `lyrics_sections: null` 문제까지 완전히 해결함.**
- 원인 확정: Flow 노래 상세 페이지에서 가사를 `element.textContent`로 긁던 기존 추출 방식(`src/download_latest.py`의 `EXTRACT_SECTION_JS`)이, 이 11곡의 특정 렌더링 상태에서는 줄바꿈을 전혀 못 살리고 한 줄로 뭉개버렸었음(원인 자체는 이번 세션 범위 밖의 선행 버그, 고치지 않고 우회함).
- **재수집 방법**: CDP(`localhost:9222`, 기존 로그인 세션 재사용)로 각 트랙의 `source.song_url`에 직접 `page.goto()` 후, `textContent` 대신 **`element.innerText`**(레이아웃 인지형이라 시각적 줄바꿈을 그대로 살림)로 재추출. 다만 Flow 페이지 자체가 섹션 헤더를 화면엔 대괄호 없이 렌더링해서(`Verse 1`이지 `[Verse 1]`이 아님), `innerText`만으로는 `parse_lyrics_sections()`가 요구하는 `[Header]` 형식이 안 나옴.
- **헤더 판별 방법**: 기존 1060곡 전체의 `lyrics_sections[].raw_label`을 스캔해서 Flow가 실제로 쓰는 헤더 어휘 28종(Intro/Verse/Pre-Chorus/Chorus/Bridge/Outro/Hook/Break/Final Chorus/Final Hook/`End - N:NN` 등)을 뽑아냄 → `innerText` 결과에서 이 어휘와 일치하는 줄만 `[..]`로 감싸고 나머지는 그대로 둠 → 기존 `src/metadata_lib.py`의 `parse_lyrics_sections()`/`is_lyrics_present()`를 그대로 재사용해서 다른 곡들과 **완전히 동일한 파서로** 파싱(임의 추측 없이, 실측 어휘집 기반). 11곡 전부 파싱 성공(각 8~11개 섹션).
- 스크립트: `scripts/apply_lyrics_refetch.py`(재수집 결과를 `flow_generated.lyrics_raw`/`lyrics_present`/`lyrics_sections`/`structure_validation.section_order_parsed`에 반영, 백업→적용→검증). 재수집 자체를 수행한 일회성 스크립트는 세션 스크래치패드에만 있고 프로젝트에는 없음(재사용 필요하면 이 로그의 설명 참고해서 재작성).
- 이어서 `scripts/lyrics_sync_retry_local.py`(로컬 CPU, `lyrics_sync_gpu_batch.py`의 `align_lines()` 그대로 재사용)로 이 11곡만 가사 싱크 재처리 → `apply_lyrics_sync_to_metadata.py` 재실행으로 병합.
- **최종: 보컬 516곡 전부 `lyrics_sync`에 실제 타임스탬프 데이터 보유** (이전엔 505곡만 실데이터 + 11곡 빈 배열이었음). GPU 서버 재동기화 완료. [가상 아티스트 카탈로그](https://claude.ai/artifact/SKRwHJi4kWnHxvP7PdREK3) 아티팩트의 싱크 완료 카운트도 516으로 갱신함.
- **다음에 비슷한 상황(Flow 페이지에서 뭔가 재수집해야 할 때) 참고할 점**: `textContent`는 절대 쓰지 말 것 — `innerText`가 훨씬 안전함. 단, `innerText`는 화면에 안 보이는(CSS로 시각적으로만 꾸며진) 문자를 못 가져오므로, 이번처럼 화면엔 없는 구조적 마커(대괄호 등)가 필요하면 별도 어휘집 매칭으로 보완해야 함.

**✅ 후속 개선 (2026-09-15~16, 같은 세션): small→medium 모델 전환 + 보간 로직으로 516곡 전체 재처리 완료.**
- 계기: 사용자가 T000298("늦은 기차")을 실제로 들으면서 QA 아티팩트로 확인하다가 브릿지 전체가 정렬 실패인 걸 발견. 원인 분석 후 large-v3까지 시도.
- **모델 비교 결론(중요, 다음에 또 볼 것)**: `small`(19/31 정렬) → `medium`(23/31, 실제로 더 정확) → `large-v3`(7/31, **환각으로 오히려 훨씬 나쁨** — "한글자막 by 한효정" 같은 가짜 자막 크레딧 문구를 반복 생성). **노래(보컬) 오디오에는 large 계열을 쓰지 말 것, medium이 최적점**이라는 게 이번에 확실히 검증됨.
- **보간(interpolation) 로직 추가**: 정렬 실패한 줄은 앞뒤로 가장 가까운 실측 앵커 사이를 글자수 비율로 나눠 채움. 신뢰도(%)나 "추정" 표시는 일부러 안 넣음(사용자 지시: "스트리밍 사이트도 가끔 안 맞음, 오류는 자연스럽게 받아들여야 함" — 이 판단 기준을 앞으로도 유지할 것).
- **인트로 구간(첫 실측 앵커 이전) 보간 개선**: 516곡 전체 스캔 결과 89%가 10초 이상, 20%가 30초 이상 첫 앵커까지 공백이 있었음(그냥 인트로 길이인 경우도 많지만 일부는 ASR이 초반 보컬을 놓친 것). prompt에 `"N BPM"` + `"Intro N bars"` 패턴이 있으면(516곡 중 381곡=74%) 그걸로 계산한 인트로 길이를 0초 대신 하한선으로 씀 — 실제 생성 결과가 프롬프트 마디수랑 항상 정확히 일치하진 않지만(이 프로젝트에서 반복 확인된 사실) 0초보다는 훨씬 나은 근사치.
- **언어별 정렬 실패 재확인**: 30초 이상 공백 비율이 한국어 29.0%(155곡 중 45) > 영어 20.4%(250곡 중 51) > 일본어 6.3%(111곡 중 7). "영어는 깨끗하다"는 인상과 달리 영어도 5곡 중 1곡꼴로 문제 있음 — **전체 최악 케이스(145초 공백)는 오히려 영어 트랙 T001028**이었음.
- **T001028 사례(중요, 다른 트랙에서 비슷한 패턴 보이면 참고)**: 정렬 실패 원인이 ASR이 아니라 **실제로 부른 가사가 저장된 원문(lyrics_raw)과 내용 자체가 다름**(21초부터 계속 노래하는데 저장된 Verse 1 원문과 전혀 매칭 안 되다가 145초에야 겨우 매칭됨). 이건 이미 예전에 조사했던 "보컬 프레이즈 이탈"(2-9/3-7, 496곡 조치 불필요로 결론)과 같은 범주라 이번에도 그대로 둠 — BPM 보간으로도 해결 불가능한 유형이니 구분해서 봐야 함.
- **재처리 결과**: 이 스크립트를 `scripts/lyrics_sync_gpu_batch.py`에 통합(`--model medium` 기본값, `parse_intro_seconds()`/`interpolate()` 함수 추가). GPU 2장 병렬로 516곡 전부 재처리(에러 0건, 약 27~30분). 전체 24,188줄 중 4,651줄(19.2%)이 보간으로 채워짐 — 나머지 80.8%는 실제 ASR 매칭. 언어별 보간 비율: 한국어 27.0% > 영어 16.0% ≈ 일본어 15.7%(한국어 가사 압축 현상과 일치, 예상대로).
- `scripts/apply_lyrics_sync_to_metadata.py` 재실행으로 516곡 전부 `lyrics_sync` 필드 갱신(백업 `downloads/metadata.bak_before_lyrics_sync_20260915T060820Z`) + GPU 서버 재동기화 완료. 스키마(text+start_sec+end_sec만)는 그대로 유지 — 보간 여부 표시 필드는 스테이징 파일(`downloads/lyrics_sync/*.json`의 `interpolated: true/false`)에만 남기고 metadata에는 안 넣음.

**✅ T001028 새 버전 교체 완료 (2026-09-15, 같은 세션)**: 사용자가 T001028을 Flow에서 다른 버전(같은 제목/가사, "ver 1")으로 교체(로컬 오디오 파일을 수동으로 바꿔치기함, 어떤 경로로 받았는지는 추적 안 됨). 새 오디오는 원문 가사와 실제로 훨씬 잘 맞음(84.2% 실매칭, 예전엔 145초 통째로 실패). `scripts/apply_t001028_new_version.py`로 `assets.audio.sha256`/`size_bytes`(새 파일 기준 재계산) + `lyrics_sync`(medium+보간) 갱신, GPU 서버 동기화까지 완료. **주의**: `source.song_id`/`song_url`/`selected_card_index`/`generated_at`은 새 버전의 정확한 값을 몰라서 옛날 값 그대로 남아있음(확인 전 임의로 안 채운다는 원칙) — 나중에 정확한 출처 정보가 필요해지면 그때 다시 다뤄야 함.

**❌ initial_prompt(가사 힌트) 방식 시도 후 폐기 (2026-09-15~16, 같은 세션) — 다음에 또 시도하지 않도록 꼭 기억할 것.**
- 배경: T000298에서 "기적 소리는 아직 멀어서" 한 줄이 계속 5초 정도 밀려서 나오길래, Whisper `initial_prompt`에 그 곡의 가사 전문을 힌트로 주면 발음을 더 잘 알아듣지 않을까 테스트함.
- 단일 사례(T000298)에서는 효과가 극적이었음(32.3초→27.3초, 사용자가 직접 들은 값과 거의 일치) + 기존에 있던 "매칭 뺏김" 버그(2차 코러스가 3차 코러스 자리 시간을 빌려오던 것)까지 같이 고쳐짐.
- **하지만 15곡 검증(영어3/일본어3/한국어9, 안 좋은 케이스 위주로 선별)에서 다른 종류의 환각을 발견**: T000116에서 0~17.9초 구간(prompt 없는 프로덕션 버전에서는 아예 인식 자체가 안 되던, 사실상 반주/무음 구간)에 Whisper가 **Verse 2 가사와 비슷한 내용을 통째로 지어내서 채워넣음**. 즉 "있는데 못 맞추는" 게 아니라 "없는데 있다고 지어내는" 실패 모드 — 사용자가 우려했던 "매칭 밀어내기"와는 다르지만 데이터 신뢰성 관점에서 더 나쁜 종류의 오류(비어있는 것보다 틀린 확신이 더 위험).
- **최종 결정**: 이 방식은 폐기하고 프로덕션은 그대로 `medium` 모델 + 보간(prompt 없음) 상태 유지. 사용자 판단: "3초짜리 오차 고치려다 곡 전체를 망칠 수도 있다" — 이 판단 기준(국소적 정확도보다 전체 신뢰성 우선)을 앞으로 비슷한 트레이드오프에서 참고할 것.
- 실험에 쓴 파일(`downloads/lyrics_sync_prompt_test/`, GPU 서버의 동일 경로 및 `scripts/test_prompt_batch.py`)은 전부 정리 완료. `scripts/lyrics_sync_gpu_batch.py`에는 prompt 관련 코드를 아예 추가하지 않았음(실험은 별도 스크립트로만 진행) — 프로덕션 파이프라인은 이 실험의 영향을 전혀 안 받음.

**남은 것**: 없음. 이번 요청은 이걸로 완전히 마무리.

### 3-13. release_date / album_release_date 필드 추가 + GPU 동기화 — ✅ 완료 (2026-09-15)

**배경**: 수록곡을 GPU 서버로 옮겨 서비스화하면서 관계형 DB에 "발매일"(곡/앨범)이 필요해짐. 사용자가 명시적으로 가상 수치 사용 OK, 메타데이터 생성 시점을 그대로 써도 된다고 확정.

**규칙 (사용자 확정)**:
- 곡 `release_date` = `generated_at`의 날짜 부분(YYYY-MM-DD, UTC, 가공 없이 그대로).
- 앨범 `album_release_date` = 그 앨범 `track_ids`의 `release_date` 중 **최댓값(max)** — "마지막 곡 완성 후 앨범 발매"를 흉내냄.
- 날짜를 더 자연스럽게 흩뿌리는 대안도 제시했으나 사용자가 "가공 없이 그대로" 선택(간단함 우선).

**한 일**:
- `scripts/add_release_dates.py`: `downloads/metadata` 1060개 전부에 `release_date` 추가. 백업 `downloads/metadata.bak_before_release_dates_20260915T065526Z`, 전수 검증 통과(파일수/파싱/값 일치/타 필드 불변).
- `scripts/add_album_release_dates.py`: `albums.json` 204개 전부에 `album_release_date` 추가. 백업 `albums.json.bak_before_release_dates_20260915T065536Z`, 전수 검증 통과.
- `docs/metadata_schema_v2.md`에 `release_date`/`album_release_date` 계약 문서 추가(가상 값이라는 점, 규칙, 날짜가 소수 값에 몰려있는 건 의도된 결과라는 점 명시).
- GPU 서버(`j-j15a204@70.12.130.102:~/flow_music_data`) 재동기화: `downloads/metadata` 1060개(`sync_to_gpu_full_mirror.sh`) + **이번에 처음으로** `artists.json`/`albums.json`도 루트에 scp 업로드(`scripts/sync_artist_album_json_to_gpu.sh`, `scripts/verify_artist_album_json_on_gpu.sh`) — 이전까지는 이 두 파일이 GPU에 전혀 없었음, DB 이전용 룩업 테이블로 필요해서 추가함.
- 실제 값: 곡 발매일 10개의 서로 다른 날짜(2026-09-01~09-15), 앨범 발매일 8개의 서로 다른 날짜(2026-09-06~09-15) — 생성 자체가 짧은 기간에 몰려 있었기 때문(의도된 결과, 버그 아님).

**남은 것**: 없음. `artists.json`에는 대응 파생 필드(데뷔일 등) 추가 안 함(요청 범위 밖).

### 3-15. 아티스트 이름 재검토 — 쾌속선→샛별, 여린볕→결 — ✅ 완료 (2026-09-16)

**배경**: 87명 프로필 이미지 87장 전부 업로드/검증 완료(아래 3-16 참고) 후, 사용자가 `쾌속선`(female_korean_pop_mainstream_02)과 `여린볕`(female_korean_indie_soft_01) 두 이름이 여성스러움이 부족하다고 재검토 요청. "쾌속선"(빠른 배)은 완전히 기계/사물 명사라 성별감이 없었고, "여린볕"은 이미 부드러운 조합이지만 밋밋하다는 판단.

**과정**: 두 아티스트의 실제 수록곡 제목(쾌속선 9곡, 여린볕 9곡) 전체와 겹치지 않는 후보를 영어 음차/한국어 대안 두 갈래로 제시 → 사용자가 `쾌속선→샛별`(순우리말, 새벽별/금성), `여린볕→결`(한 글자, 결/무늬 뜻하는 우아한 단어) 확정.

**반영**: `scripts/rename_artists_saetbyeol_gyeol.py`(artists.json, 백업+검증)로 이름만 교체 → `scripts/apply_artist_album_to_metadata.py` 재실행으로 `downloads/metadata` 1060개 전체(해당 18개 트랙만 실질 변경) 재반영 → `scripts/generate_artist_album_roster.py`로 `ARTIST_ALBUM_ROSTER.md` 재생성 → GPU 서버(`metadata` + `artists.json`) 재동기화, 원격 값 확인 완료.

**후속 (같은 날, 사용자가 "그것도 바꿔" 요청)**: 프로필 이미지 파일명(`artists/artists_images/여린볕.png`→`결.png`, `쾌속선.png`→`샛별.png`)과 `artists/manifest.json`(`artist_name`/`output_name` 필드), `artists/artist_mapping.md`(표의 이름/파일명 칸)까지 전부 새 이름으로 갱신함. `scripts/rename_artist_image_files.py` 사용 — 파일 rename은 내용 불변(sha256 동일) 확인, manifest의 `source_path`/`source_file`/`sha256`/`file_id`(원본 업로드 출처 기록)는 과거 사실이라 그대로 보존, `artist_name`/`output_name`(현재 식별자)만 갱신. 87건 전수 검증(의도한 2건만 변경, 나머지 85건 완전 불변) 통과.

### 3-16. 아티스트 프로필 이미지 87장 업로드 완료 — ✅ 완료 (2026-09-15~16)

**배경**: 3-14에서 설계한 프롬프트(장르별 화풍 + 언어별 제작기법 스펙 + 체형/얼굴형 배제 + 헤어/표정/나이/배경 균등 로테이션 + 인스트루멘탈 사람:동물 70:30)를 사용자가 별도 이미지 생성 도구(GPT/Astra)에 직접 넣어 87장 전부 생성 완료. `artists/artists_images/`(87개 PNG, 1254x1254)와 `artists/manifest.json`(sha256/승인근거 포함), `artists/artist_mapping.md`로 업로드됨.

**검증 완료**: `artists.json` 87명과 `manifest.json` 87건 — artist_key 완전 일치(누락/중복/이름불일치 0건), 파일 87개 전부 존재+PNG 헤더 정상+sha256 전부 일치(전송 손상 없음), 전부 1254x1254 정사각 통일.

**남은 것**: `artists.json`에 `profile_image_path` 필드 추가해서 정식 연결하는 작업 아직 안 함(다음에 요청 시 진행, `artist_key` 기준 매칭 예정).

### 3-14. 아티스트 프로필 이미지(캐릭터 일러스트) 생성 — 계획 수립 중 (신규 착수, 2026-09-15)

**배경**: 87명(보컬 49 + 인스트루멘탈 38) 가상 아티스트의 프로필 이미지를 만들려는 것. 실사화가 아니라 **캐릭터 일러스트** 스타일로, 장르별로 스타일을 다르게(예: 하드록 남성 아티스트=근육질 캐릭터, J-pop 여성=일본 만화/애니 스타일, 인스트루멘탈=악기+캐릭터 또는 동물 캐릭터 등). 배경은 단색/심플. 사용자가 참고 이미지 2장(근육질 다크판타지풍 남성 캐릭터, 보라색톤 브이에이퍼웨이브풍 여자아이 캐릭터) 첨부함.

**다음 세션(또는 이어서) 할 일**: 생성 전 계획을 먼저 보고하기로 함 — 스타일 매핑 규칙(장르 클러스터×성별×언어 → 아트 스타일), 프롬프트 생성 방식, 실제 이미지 생성 도구(미정, 확인 필요), 저장 위치/metadata 반영 방식을 설계해서 사용자 승인받은 뒤 진행.

## 4. 참고 파일 위치
- `logs/title_rework_log.jsonl`: 지금까지의 모든 제목 변경 감사 로그 (Wave7까지 총 829줄)
- `title_rework_plan.json`, `title_rework_plan_round2.json`, `title_rework_plan_wave4.json`, `title_rework_plan_wave5.json`, `title_rework_plan_wave6.json`, `title_rework_plan_wave7.json`: 과거 웨이브 계획 (전부 이미 적용됨, 참고용)
- `title_rework_candidates.json`, `title_rework_excluded_vocal.json`: 최초 후보 목록과 제외된 고위험 보컬 목록
- `downloads/metadata.bak_before_*`: 각 웨이브 적용 전 전체 백업 (문제 생기면 여기서 복원). 가장 최근: `metadata.bak_before_wave7_20260911T012302Z`
- `all_titles.txt`: 특정 시점의 전체 1000곡 제목 스냅샷 (오래된 버전일 수 있음, 최신 확인은 metadata 직접 스캔 권장)
- `mixed_lang_expansion_plan.json`: mixed_korean/mixed_english 40개 신규 프롬프트 전체 내역 (prompts.jsonl에 이미 반영됨, 참고용)
- `prompts.jsonl.bak_before_mixed_lang_expansion_20260911T014931Z`: 40개 추가 전 prompts.jsonl 백업
- `scripts/build_mixed_lang_expansion.py`, `scripts/apply_mixed_lang_expansion.py`: 이번 확장 작업에 쓴 스크립트 (재사용 가능한 패턴)
- `scripts/scan_meter_mismatch.py`: 박자(3/4 vs 4/4) 불일치 병렬 스캐너. `logs/meter_scan_result.jsonl`에 결과 저장, 재실행해도 이미 스캔된 track_id는 스킵.
- `logs/meter_scan_result.jsonl`: 박자 스캔 결과 (track_id별 1줄, `verdict`/`frac3` 포함)

## 5. 다음 세션 시작 시 첫 행동 제안

**2026-09-15 최신 상태**: 보컬 아티스트 재편(indie_rock 클러스터 버그 수정, 0장) + 267~318 여성→남성 전환 + 저품질 5곡 재생성 + **release_date/album_release_date 필드 추가(3-13)까지 전부 GPU 서버 반영 완료**(87명/204개/1060곡 + artists.json/albums.json 전부 로컬=원격 확인). **다음 세션(또는 바로 이어서) 할 일: 3-14 아티스트 프로필 이미지(캐릭터 일러스트) 생성 — 아직 계획 단계, 스타일 매핑/실제 생성 도구 확정 안 됨.** 그 외 급한 후속 작업 없음 — 0장의 "남은 것"(phrase_mismatch 재스캔, full_dataset_report 갱신) 정도만 참고. **0장의 DRY_RUN/WSLENV 함정 꼭 읽고 시작할 것.**

<details>
<summary>2026-09-11 시점 기록 (과거, 접어둠)</summary>

2026-09-11 세션 종료 시점 상태: mixed 언어 확장 40곡 생성 완료, 박자 불일치 스캔+재생성 완료(18/19 정상화, 1곡 의도적 예외), 제목 Wave8 정리 완료, `_1` 파일 정합성 수정 완료, **GPU 서버 전체 미러 업로드까지 완료**(로컬=원격 1040개 일치 확인). 다음 세션에서:
1. `downloads/metadata` 1040개 기준으로 전체 리포트(`full_dataset_report.json`, mood/악기 분석 등) 재생성 필요 — 아직 신규 40곡 반영 안 됨.
2. **male_japanese 부족(3-3) 등 카테고리 공백**에 대한 프롬프트 추가를 사용자와 계획해서 진행 — 아직 프롬프트도 안 만듦, 순수 분석만 완료된 상태. 추가 생성하게 되면 GPU 재업로드(2-7 패턴)도 다시 필요.
3. 제목 정비(Wave5~8)는 완료된 상태이므로 급한 후속 작업 없음.
4. 박자 스캐너(`scripts/scan_meter_mismatch.py`)는 madmom/ffmpeg/VS Build Tools 환경이 이미 갖춰져 있으니, 위 male_japanese 등으로 새 곡을 더 생성하게 되면 그 곡들도 이 스캐너로 바로 돌려볼 것. instrumental 3/4는 무조건 재생성하지 말고 먼저 청취 판단(2-4 끝 교훈 참고).
5. GPU 쪽 Jupyter `.ipynb_checkpoints`는 이번에 전부 삭제됐음 — 원격에서 다시 노트북을 열면 재생성될 수 있는 정상적인 부산물이니 참고만 할 것.

</details>
