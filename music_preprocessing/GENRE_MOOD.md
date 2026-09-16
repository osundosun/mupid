# GPU 장르·무드 등록

`python -m music_preprocessing.genre_mood`는 기존 곡에 생성 프롬프트 기반
장르·무드를 연결하는 독립 명령이다. 오디오 분석을 수행하지 않는다.
`catalog`, `batch`, 기존 설정 및 `registered.json`을 변경하지 않는다.
실행 위치는 `music_preprocessing/`이고 기존 `uv` 환경을 사용할 수 있다.

## 입력과 저장 범위

- GPU: `j-j15a204@70.12.130.102`
- 원본: `/home/j-j15a204/flow_music_data/metadata/T*.json`
- 분류/수량 기준: `/home/j-j15a204/flow_music_data/genre_mood_taxonomy.json`
- 기존 곡 매핑: `/home/j-j15a204/music_preprocessing/registered.json`의
  `catalog.tracks[track_id].music_id`. 트랙 번호를 정수로 변환하지 않는다.
- 읽기: `music`, `codes`, `music_genres`, `music_moods`.
- 쓰기: 누락된 `codes`의 `GENRE`/`MOOD` 값과 두 연결 테이블의 누락 행만 POST.
  곡·아티스트·앨범·스트림, 기존 코드·연결 관계를 수정하거나 삭제하지 않는다.

**분류 추가의 영향:** 기존 시드는 장르 8개·무드 8개다. 이번 원본은 세부 장르
45개·영문 무드 9개다. 원문을 보존해 `codes`에 추가하며, 기존 이름과 통합하거나
번역하지 않는다. `OnboardingServiceImpl.options()`가 해당 유형의 코드를 전부
조회하므로 추가된 값은 온보딩 선택지에도 나타난다. 취향 리포트도 연결된 코드
이름을 사용한다. 기존 데이터 유지 + 원본 코드 추가 범위로 승인된 작업이다.
공유 코드 테이블 전체 행 수가 45/9가 되는 것은 아니다.

## 1. 원본 스냅샷

SSH 키를 복사하지 않고 기존 SSH 인증을 사용한다. 원격 명령은 JSON 읽기만 한다.
오디오·커버·인증 파일은 가져오지 않는다. 스냅샷은 커밋 제외 경로에 저장한다.
이미 있는 출력 파일은 덮어쓰지 않으므로 다시 받을 때 새 파일명을 사용한다.

```bash
uv run python -m music_preprocessing.genre_mood fetch \
  --ssh-host j-j15a204@70.12.130.102 \
  --ssh-key ~/.ssh/ssafy-gpu_ed25519 \
  --output .genre-mood/gpu-snapshot.json
```

Windows에서 WSL SSH를 재사용하려면 `--wsl-distro Ubuntu-22.04`를 추가하고
`--ssh-key`에는 WSL 안의 키 절대경로를 지정한다.

GPU 서버 위에서 실행하거나 원본이 마운트되어 있다면 SSH 없이 읽는다.

```bash
uv run python -m music_preprocessing.genre_mood snapshot \
  --source-dir /home/j-j15a204/flow_music_data \
  --registry /home/j-j15a204/music_preprocessing/registered.json \
  --output .genre-mood/gpu-snapshot.json
```

## 2. 원본만 검증

```bash
uv run python -m music_preprocessing.genre_mood import \
  --snapshot .genre-mood/gpu-snapshot.json \
  --report .genre-mood/source-check.json
```

이 단계는 네트워크에 연결하지 않는다. 파일명·트랙 ID, 등록 매핑, 곡명,
중복 ID, 분류 어휘와 유형별 수량, 무드 결측 목록을 모두 확인한다.
`mood=null`은 원본 검증에서 정상 결측으로 처리한다. 이후 아래의 초기 10곡만
사용자가 제공한 근사치로 보완한다. primary/secondary가 같으면 관계는 하나만
만들되 taxonomy 등장 횟수는 두 번 센다. energy/method는 저장하지 않는다.

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
1,050곡과 달리, Flow의 `flow_generated.sound_raw`를 사람이 해석한 **근사치**다.
값과 근거는 `src/music_preprocessing/mood_approximations.py`에 별도로 보관한다.
GPU 원본 JSON, taxonomy 및 원본 스냅샷은 수정하지 않는다.

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

원본을 taxonomy와 먼저 대조한 뒤, 이 목록 중 **원본 무드가 없는 곡만** 보완한다.
장르가 표와 다르거나 해당 무드가 원본 어휘에 없으면 중단한다. 이후 원본에 실제
무드가 추가되면 그 값이 우선하며, 이 표로 덮어쓰지 않는다. 목록 밖 결측은 유지한다.

보완 후 예상값은 **무드 1,060곡 / 2,120행**, 장르 1,060행, 고유 분류 45/9개다.
원본만 처리하려면 `import`에 `--skip-mood-approximations`를 추가한다.

DB 연결 테이블에는 신뢰도 컬럼이 없으며 이번 작업에서 스키마를 추가하지 않는다.
따라서 **DB 행만으로는 근사치를 구분할 수 없다.** 실행 보고서의
`mood_approximations`에 track_id·music_id·primary·secondary·`confidence: approx`·출처·근거를
남기고, 실제 추가된 무드 행의 `created` 항목에도 근사치 표식을 남긴다.
`source_summary`는 원본 수량, `summary`는 보완 후 등록 예정 수량이다.
이미 등록된 관계를 재실행에서 건너뛰어도 보완 출처 목록은 보고서에 포함한다.

## 3. 서버 사전 검증

**ROLE_ADMIN 인증이 필요하다.** 기존 배치의 ROLE_SYSTEM 토큰은 관리자 API에서
403을 받는다. 권한을 변경하거나 우회하지 않는다.
토큰은 `.genre-mood/admin-refresh-token.txt` 파일에 보관한다. 파일은 커밋에서
제외된다. 명령 인자나 보고서에 토큰을 직접 넣지 않는다.
리프레시 토큰은 재발급 시 회전하므로 도구가 같은 파일에 새 값을 저장한다.
서버 사전 검증에서도 인증을 위한 이 파일 갱신은 발생할 수 있다.

```bash
uv run python -m music_preprocessing.genre_mood import \
  --snapshot .genre-mood/gpu-snapshot.json \
  --report .genre-mood/server-check.json \
  --api-base-url https://j15a204.p.ssafy.io \
  --refresh-token-file .genre-mood/admin-refresh-token.txt
```

액세스 토큰 파일을 쓰려면 대신 `--token-file`을 사용한다. 두 옵션은 동시에
사용할 수 없다. 도구 실행 중 계정 권한을 변경하지 않는다.

서버에서 전체 페이지를 읽어 등록 기록의 곡 ID와 곡명을 확인한다. 같은 유형/이름의
코드가 중복되거나 대상 곡에 원본과 다른 기존 연결이 있으면 쓰기 전에 중단한다.
원본/보완 결과에 없는 서버 무드가 있는 경우도 자동 삭제하지 않고 중단한다.
사전 검증 결과에는 추가할 코드와 관계 수가 나온다.

## 4. 실제 반영 및 검증

```bash
uv run python -m music_preprocessing.genre_mood import \
  --snapshot .genre-mood/gpu-snapshot.json \
  --report .genre-mood/apply-result.json \
  --api-base-url https://j15a204.p.ssafy.io \
  --refresh-token-file .genre-mood/admin-refresh-token.txt \
  --apply
```

기본은 사전 검증이며 `--apply`가 있어야 데이터가 추가된다. 적용 직전에 전체
사전 검증을 다시 수행한다. 마스터 코드 이후 장르·무드 연결을 순차 등록한다.
100건마다 진행 수를 출력하고 성공한 각 행의 ID를 보고서에 남긴다.

완료 후 서버를 다시 조회해 대상 곡별 관계가 원본과 보완 결과에 정확히 일치하는지 확인하고,
기존 관계가 유지되는지 확인한다. 성공 보고서는 `status=verified`,
`rerun_additions=0`이다. 개수만 같고 다른 곡에 붙은 경우는 통과하지 않는다.

### 재실행과 실패

- 한 프로세스만 실행한다. 공통 코드의 `(code_type, code_name)`은 이 도구가
  데이터베이스 수준의 동시 실행 잠금을 보장하지 않는다.
- 기존 관리자 API는 요청마다 트랜잭션이므로 전체 배치가 단일 트랜잭션은 아니다.
- POST 전송 실패를 자동 재시도하지 않는다. 서버에는 반영되고 응답만 손실됐을 수 있다.
- 실패 시 성공한 행은 유지된다. 새 보고서 경로로 다시 실행하면 서버 상태를
  다시 읽어 이미 만들어진 코드·관계를 건너뛴다.
- 보고서에는 원본 SHA-256과 성공한 추가 행 목록을 기록한다. 응답 손실 직전의
  행은 목록에 없을 수 있으므로 재실행 사전 검증이 실제 상태의 기준이다.
- 기존 스냅샷·보고서 경로를 재사용하면 실패한다. 기존 결과 덮어쓰기를 막기 위한 동작이다.
- 별도 관리자 작업으로 곡명이나 관계가 달라졌다면 먼저 원인을 확인한다.

## 테스트

```bash
PYTHONPATH=src uv run python -m unittest discover -s tests -v
```

추가 의존성 없이 기존 표준 라이브러리 HTTP/인증 클라이언트를 재사용한다.
원본 검증, ID 추측 방지, 무드 결측/중복, 사전 검증 무쓰기, 서버 충돌,
페이지 조회, 기존 데이터 보존, 응답 손실 후 재실행 및 중복 방지를 검증한다.
