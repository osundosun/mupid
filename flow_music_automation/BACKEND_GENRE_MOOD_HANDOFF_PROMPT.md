# [백엔드 이관 요청] music_genres / music_moods 테이블 채우기

너는 음원 스트리밍 서비스 백엔드(관리자 페이지 `/api/admin` 기준 `artists` 87 / `albums` 204 / `music` 1,060 / `streams` 1,060 — 전부 채워져 있음) 프로젝트에서 작업 중이야. 지금 `music_moods`(0건) / `music_genres`(0건) 테이블만 비어 있어. 이 문서는 그 데이터를 채우기 위해 별도의 "flow-music-automation" 데이터 파이프라인 프로젝트에서 준비한 소스 데이터와 요청 내용이야.

**⚠️ 작업 범위 (반드시 지킬 것)**: 이번 작업은 **`music_genres`/`music_moods` 두 테이블에 행을 새로 채우는 것뿐**이야. `music`/`artists`/`albums`/`streams` 등 이미 채워져 있는 기존 테이블의 데이터나 스키마, 기존 임포트 로직/컨트롤러/서비스 코드는 **손대지 마.** 리팩터링이나 "더 나은 구조로 재설계" 같은 것도 하지 마 — 기존 코드는 그대로 두고, 이 두 테이블을 채우는 **새 스크립트/마이그레이션을 추가하는 방식**으로만 진행해줘. 스키마 확인 등 읽기 전용 조사는 당연히 필요하지만, 조사 중에 기존 코드를 고치거나 갈아엎지는 말 것.

## 0. 배경

`music`/`artists`/`albums` 테이블은 이미 다른 프로젝트(flow-music-automation)가 생성한 `flow_music_data/{artists.json,albums.json,metadata/T*.json}` (GPU 서버, 1,060개 트랙)를 임포트해서 채운 것으로 보여(레코드 수가 정확히 일치). **이번에 그 프로젝트가 각 트랙 metadata JSON에 `genre`/`mood` 필드를 새로 추가하고, 같은 GPU 서버에도 이미 동기화해뒀어.** 즉 새로운 오디오 분석 없이, 기존에 임포트할 때 쓴 것과 같은 소스 파일에 필드 2개가 늘어난 것뿐이야.

**접속 정보**:
- 호스트: `70.12.130.102` (SSH 유저 `j-j15a204`)
- 데이터 경로: `~j-j15a204/flow_music_data/`(절대경로 `/home/j-j15a204/flow_music_data/`) — 하위에 `artists.json`, `albums.json`, `genre_mood_taxonomy.json`, `metadata/T000001.json`~`T001060.json`(1,060개), `audio/`, `covers/` 있음.
- `artists`/`albums`/`music`를 최초 임포트할 때 이미 이 경로(같은 호스트)에 접근했을 거야 — **그때 쓴 것과 동일한 접속 방식(SSH 키, rsync, NFS 마운트 등)을 그대로 재사용**해줘. 이 문서를 준 automation 프로젝트 쪽 SSH 키는 로컬 PC(Windows, WSL)에만 있어서 여기엔 못 넘겨줘 — 백엔드 프로젝트가 최초 임포트 때 이미 확보해둔 자기 접근 수단이 있을 거야.
- 만약 백엔드 서버 자체가 이 GPU 서버(`70.12.130.102`) 위에서 돌고 있다면, 그냥 로컬 절대경로 `/home/j-j15a204/flow_music_data/`로 바로 읽으면 돼(SSH 불필요).

## 1. 새로 추가된 필드 (`flow_music_data/metadata/T*.json`, 트랙당 1개 파일)

```json
{
  "track_id": "T000011",
  "genre": "contemporary_pop",
  "mood": {
    "primary": "bright",
    "secondary": "warm",
    "energy": null,
    "method": "core_and_in_mood"
  }
}
```

- **`genre`**(문자열, 트랙당 정확히 1개): 1,060개 전부 값 있음(파싱 실패 0건). 고유값 45개(아래 2절).
- **`mood`**(객체 또는 `null`): 1,050/1,060(99.1%)만 값 있음. 나머지 10개 트랙(`T000001`, `T000003`~`T000010`, `T000014` — 최초 생성 배치)은 원본 프롬프트 자체에 mood 정보가 없어서 추측하지 않고 `null`로 남김 — 오류 아님, mood 관련 행을 만들지 말고 건너뛰면 됨.
  - `primary`/`secondary`: mood 태그(소문자 문자열). `secondary`는 패턴에 따라 `null`일 수 있음. 고유값 9개(아래 2절).
  - `energy`: 1~5 정수 또는 `null`(원본 프롬프트에 `"Energy N/5"` 같은 명시적 수치가 있을 때만 채워짐, 약 89%). DB 스키마에 자리가 없으면 무시해도 됨 — `music_genres`/`music_moods` 채우는 데는 `primary`/`secondary`만 있으면 충분.
  - `method`: 내부 파싱 로직 추적용(정규식 패턴 이름). DB에 넣을 필요 없음.

## 2. 마스터 룩업 데이터 (`music_genres`/`music_moods` 시드용)

전체 목록 + 트랙 수는 GPU 서버의 `flow_music_data/genre_mood_taxonomy.json`에도 올려뒀어(접근 가능하면 그걸 그대로 읽어도 됨). 참고로 여기 인라인으로도 붙여둘게.

**genre (45개, 곡당 1개, 총 1,060개 트랙 전부 커버):**
`contemporary_pop`(62) `dance_pop`(49) `synthpop`(45) `lofi_hiphop`(41) `indie_pop`(39) `alternative_rock`(39) `poprock`(36) `city_pop`(34) `pop_ballad`(34) `rnb`(32) `solo_piano`(31) `singing_rap_melodic_hiphop`(24) `modern_trap_hiphop`(22) `synthwave`(21) `game_battle_bgm`(21) `indie_rock`(20) `hard_rock`(20) `punk_power_pop`(20) `atmospheric_post_rock`(20) `alternative_rnb`(20) `neo_soul`(20) `boom_bap_hiphop`(20) `house`(20) `future_bass`(20) `drum_and_bass`(20) `chillhop`(20) `downtempo`(20) `chillwave_dreamy_electronica`(20) `modern_jazz`(20) `funk`(20) `cinematic_orchestral`(20) `adventure_fantasy`(20) `emotional_drama`(20) `mystery_suspense`(20) `casual_light_game_bgm`(20) `neo_classical`(20) `acoustic_guitar_folk`(20) `ambient`(20) `breakbeat_uk_garage`(10) `ambient_electronic`(10) `acoustic_chill`(10) `jazzhop`(10) `jazz_fusion`(10) `bossa_smooth_jazz`(10) `chamber_classical`(10)

**mood (9개, `primary`/`secondary`에 등장하는 값 전부 이 안에 있음):**
`melancholic`(322) `warm`(314) `dreamy`(296) `bright`(296) `hopeful`(263) `powerful`(262) `dark`(152) `calm`(143) `exciting`(52)
(괄호는 `primary`+`secondary` 통합 등장 횟수)

## 3. 요청 사항

이 백엔드 프로젝트의 실제 스키마/ORM은 내가(automation 프로젝트) 알 수 없으니, 아래 순서로 **네가 직접 스키마를 확인하면서** 진행해줘:

1. `music`/`music_genres`/`music_moods` 테이블 구조와, `music` 87/204/1,060건을 최초 임포트할 때 각 레코드를 `track_id`(예: `T000011`)와 어떻게 연결했는지 확인해줘 (외부 ID 컬럼으로 `track_id`를 그대로 저장해뒀는지, 아니면 다른 방식인지). **이 조인 키를 그대로 재사용**해서 genre/mood를 연결해야 해 — 새로 만들지 말 것.
2. `music_genres`/`music_moods`가 각각 순수 룩업(코드성) 테이블 + N:M 조인 테이블 구조인지, 아니면 `music`에 직접 컬럼/배열로 붙는 구조인지 확인해줘. 테이블명이 복수형인 걸 보면 조인 테이블 구조로 설계된 것 같은데, 그렇다면:
   - `music_genres`: 트랙당 1행(장르는 1개뿐이므로).
   - `music_moods`: 트랙당 `primary` 1행 + `secondary`가 있으면 1행 추가(즉 트랙당 1~2행, mood가 `null`인 10개 트랙은 0행).
3. 2절의 45개 genre / 9개 mood를 각각 `music_genres`/`music_moods` 마스터(룩업) 테이블에 먼저 시드하고, 그 다음 `flow_music_data/metadata/T*.json` 1,060개를 순회하며 조인 테이블에 실제 곡-장르/곡-무드 관계를 채우는 **새 스크립트(또는 새 마이그레이션)**를 작성/실행해줘 — 기존 임포트 스크립트를 고쳐 쓰지 말고 별도로 추가할 것.
4. 완료 후 검증: `music_genres` 조인 행 수(=1,060이어야 함, 트랙당 정확히 1개), `music_moods` 조인 행 수(1,050~2,100 사이여야 함 — 트랙당 1~2개), 그리고 고유 genre/mood 값 수가 각각 45/9와 일치하는지 확인해줘.

## 4. 참고

- 이 필드들은 실제 오디오 분석 결과가 아니라 곡 생성 시 사용한 프롬프트(장르/무드 요청 라벨)에서 파싱한 값이야 — `release_date`(발매일)와 같은 성격의 "가상이지만 근거 있는" 메타데이터로 취급하면 돼.
- 원본 스키마 계약 문서: automation 프로젝트의 `docs/metadata_schema_v2.md` "genre / mood" 절(이 프로젝트에서는 접근 불가할 수 있으니, 필요한 내용은 위 1~2절에 이미 다 옮겨뒀어).
