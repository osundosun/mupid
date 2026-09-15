# music_preprocessing

음악 스트리밍 전처리 프로그램. 하는 일이 둘이다.

| 갈래 | 명령 | 하는 일 |
| --- | --- | --- |
| **청킹** | `single` · `batch` | 오디오를 Opus 청크 + RTP 패킷으로 쪼개고, 스트림·청크를 API 에 올린다 |
| **카탈로그** | `catalog` · `covers` · `lyrics` | 곡별 메타데이터·커버·가사를 읽어 아티스트·앨범·곡을 API 에 등록한다 |

두 갈래는 `registered.json` 하나를 함께 쓴다. `catalog` 가 만든 곡 번호를
`batch` 가 그대로 이어받으므로, 같은 곡이 두 번 만들어지지 않는다.

## 처리 파이프라인

```
① 청킹
MP3/WAV/FLAC → FFMPEG(Opus 인코딩+세그먼트 분할) → Ogg 파싱 → RTP 패킷화 → .rtp 파일
                                                                          ↓
                                                      Mupid API 등록 (스트림 → 청크)

② 카탈로그
metadata/*.json + covers/*.jpg → 커버 업로드 → 아티스트 → 앨범 → 곡
                                            → 가사 (metadata 의 lyrics_sync)
```

1. **FFMPEG 세그먼트 분할**: 입력 오디오를 N초 단위 Ogg/Opus 세그먼트로 분할
2. **Ogg 컨테이너 파싱**: 각 세그먼트에서 개별 Opus 프레임 추출
3. **RTP 패킷화**: 각 프레임에 12바이트 RTP 헤더(RFC 3550) 부착
4. **바이너리 저장**: 스트리밍 서버가 바로 전송 가능한 `.rtp` 파일로 저장
5. **API 등록**: 곡 메타와 청크를 서버에 올린다 (`api_base_url` 을 설정했을 때만)

## 빠른 시작

```bash
# 가상환경 세팅
uv sync

# ── 카탈로그 등록 (곡·아티스트·앨범·커버) ──
uv run music-preprocessing catalog -c config.json --dry-run   # 먼저 세어 본다
uv run music-preprocessing catalog -c config.json             # 확인을 묻고 올린다

# 커버만 다시 올리기
uv run music-preprocessing covers -c config.json

# 가사 (catalog 다음)
uv run music-preprocessing lyrics -c config.json

# 어디까지 됐나
uv run music-preprocessing status -c config.json

# ── 청킹 ──
uv run music-preprocessing single -i song.mp3 -o ./output
uv run music-preprocessing batch -I ./input -O ./output
uv run music-preprocessing batch -c config.json
```

`uv` 없이 돌릴 수도 있습니다. 의존성(`typer`, `rich`)만 있으면 됩니다.

```bash
pip install typer rich
PYTHONPATH=src python -m music_preprocessing --help
```

## CLI

[Typer](https://typer.tiangolo.com) 로 만들었습니다. `--help` 를 붙이면 어느
명령에서나 옵션 표가 나옵니다.

```
music-preprocessing {catalog,covers,lyrics,status,single,batch}
```

| 명령 | 하는 일 |
| --- | --- |
| `catalog` | 곡별 메타데이터·커버를 읽어 아티스트·앨범·곡을 등록한다 |
| `covers` | 이미 등록된 대상의 커버만 다시 올린다 |
| `lyrics` | 곡별 가사를 시각과 함께 등록한다 (곡마다 한 번에 교체) |
| `status` | 무엇이 등록됐고 무엇이 남았는지 센다. **서버에 붙지 않는다** |
| `single` | 파일 하나를 Opus 청크 + RTP 로 쪼갠다 |
| `batch` | 입력 디렉터리를 통째로 청킹하고 스트림·청크를 등록한다 |

### 손으로 돌릴 때

```bash
# 1. 무엇이 만들어질지 본다 (서버에 붙지 않음)
uv run music-preprocessing catalog -c config.json --dry-run

# 2. 몇 곡만 먼저 올려 본다
uv run music-preprocessing catalog -c config.json --limit 5

# 3. 어디까지 됐나 본다
uv run music-preprocessing status -c config.json

# 4. 나머지를 올린다
uv run music-preprocessing catalog -c config.json

# 5. 가사를 올린다 (곡이 있어야 한다)
uv run music-preprocessing lyrics -c config.json
```

`catalog` 와 `covers` 는 서버에 행을 만들거나 파일을 덮어쓰므로, **무엇이 어디에
몇 개 만들어지는지 보여 주고 한 번 묻습니다.** 되돌리는 길이 `DELETE` 를 곡 수만큼
부르는 것뿐이라, 운영 서버를 향해 실수로 미는 것을 막으려는 것입니다.

### 주요 옵션

| 옵션 | 명령 | 설명 |
| --- | --- | --- |
| `-c`, `--config` | 전부 | JSON 설정 파일 |
| `--metadata-dir` · `--covers-dir` | `catalog` `covers` `lyrics` `status` | 설정 파일보다 우선 |
| `--api-base-url` | `catalog` `covers` `lyrics` `batch` | 등록할 서버 |
| `--dry-run` | `catalog` `covers` `lyrics` | 서버에 붙지 않고 세기만 한다 |
| `--limit N` | `catalog` `lyrics` | 앞에서 N곡만. 처음 붙일 때 시험용 |
| `--force` | `covers` `lyrics` | 바뀌지 않은 것도 지우고 다시 올린다 |
| `--skip-untimed` | `lyrics` | 시각을 모르는 줄을 메우지 않고 버린다 |
| `-y`, `--yes` | `catalog` `covers` `lyrics` | 확인 프롬프트를 건너뛴다 (크론용) |
| `--no-progress` | `catalog` `covers` `lyrics` | 진행률 막대를 끈다 |
| `--no-orphan-check` | `batch` | 쓰이지 않는 스트림 점검을 건너뛴다 |
| `--verify-chunks` | `batch` | 성한 스트림까지 청크 행 수를 대조한다 |
| `--log-file` · `--no-log-file` | 전부 | 로그 파일 경로 / 남기지 않기 |
| `-v`, `--verbose` | 전부 | DEBUG 까지 남기고 화면에도 전부 찍는다 |

`single` · `batch` 는 인코딩 옵션(`-b/--bitrate`, `--segment-time`,
`--frame-duration`, `--workers`, `--ffmpeg-threads`)을 그대로 받습니다.

### 로그는 파일로 간다

배치 하나가 HTTP 를 2천 번 넘게 부릅니다. 그걸 전부 터미널에 쏟으면 진행률도
실패 목록도 스크롤 위로 흘러가므로 둘로 나눕니다.

| 어디 | 무엇이 | 서식 |
| --- | --- | --- |
| 터미널 | 진행률 막대 · 결과 표 · 경고/오류만 | Rich (색 있음) |
| 파일 | INFO 전부 (`--verbose` 면 DEBUG) | Rich 열 정렬, **색 없음** |

파일은 실행마다 새로 만듭니다 — `logs/catalog-20260915-115852.log`. 위치는
설정의 `log_dir`, 또는 `--log-file` 로 직접 줍니다.

```
2026-09-15 11:58:58 INFO   메타데이터 1060건을 읽었습니다: …/metadata      catalog.py:148
                    INFO   아티스트 등록: Halcyon (female_english_…) → 1   catalog.py:365
```

색을 끄는 이유는 `grep` · `less` 로 읽을 때 ANSI 이스케이프가 섞이면 지저분해서
입니다. 색만 빼면 Rich 의 열 정렬(시각 │ 레벨 │ 메시지 │ 위치)은 그대로 남습니다.

화면 출력은 **표준 출력**, 로그는 **표준 오류**로 갑니다. 크론에서
`> out.log 2> err.log` 로 갈라도 결과 요약과 로그가 섞이지 않습니다.

## 카탈로그 등록 (`catalog` · `covers`)

수집 파이프라인이 만든 디렉터리 두 개를 그대로 읽어 아티스트 · 앨범 · 곡을
등록합니다. 오디오 파일 옆에 사이드카 JSON 을 놓는 청킹 쪽(`batch`)과 달리,
**입력 파일을 손대지 않습니다.**

### 입력 구성

```
flow_music_data/
├── metadata/T000001.json   ← 곡 하나당 메타데이터
├── covers/T000001.jpg      ← 곡 하나당 커버
├── audio/T000001.mp3       ← `batch` 의 입력
└── chunking/T000001/       ← `batch` 의 출력
```

파일 이름의 `T000001` 이 곡 식별자(`track_id`)입니다. 네 디렉터리가 모두 이
이름을 공유하므로 메타데이터·커버·음원·청크를 잇는 별도 매핑이 필요 없습니다.

### 메타데이터에서 읽는 것

원본 JSON 에는 생성 프롬프트·가사·구조 검증 결과까지 들어 있지만, **서버로
나가는 것은 여섯 필드뿐입니다.**

| 원본 필드 | 쓰임 |
| --- | --- |
| `track_id` | 커버 파일명 · 등록 기록의 키 |
| `flow_title` | 곡명 (`music.title`) |
| `generated_at` | 발매일. UTC → KST 로 옮기고 초까지만 남긴다 |
| `artist.artist_key` | 아티스트를 한 번만 만들기 위한 키 · 이미지 저장 키 |
| `artist.name` | 아티스트명 |
| `album.album_key` · `album.title` | 앨범 키 · 앨범명 |
| `lyrics_sync` | 가사와 시각. **`lyrics` 명령이 따로 읽는다** |

앨범 발매일은 **수록곡 중 가장 이른 발매일**로 잡습니다.

`prompt` · `prompt_sha256` · `source` · `assets` · `requested` ·
`flow_generated` · `structure_validation` · `lyrics_sections` 는 읽지 않습니다.

가사는 `catalog` 가 아니라 `lyrics` 명령이 올립니다 → [가사 등록](#가사-등록-lyrics).

### 커버가 어디에 붙는가

커버는 곡마다 있는데 이미지가 붙는 곳은 셋입니다.

| 카테고리 | 저장 키 | 어떤 그림 |
| --- | --- | --- |
| `TRACK` | `track_id` | 그 곡의 커버 |
| `ALBUM` | `album_key` | 수록곡 중 `track_id` 가 가장 작은 곡의 커버 |
| `ARTIST` | `artist_key` | 그 아티스트 곡 중 `track_id` 가 가장 작은 곡의 커버 |

대표 커버를 `track_id` 최솟값으로 고르는 것은 **같은 입력이면 언제나 같은
그림이 나오게** 하기 위해서입니다.

아티스트·앨범 이미지는 서버에서 **필수**(`image_file_id` NOT NULL)라, 대표
커버가 없으면 그 아티스트·앨범은 만들지 못하고 실패로 남습니다. 반면 곡 커버는
선택이라, 없으면 `image_file_id` 없이 등록하고 화면이 앨범 커버로 내려갑니다.

### 등록 순서

```
아티스트마다   POST /api/v1/storage/image/ARTIST/{artist_key}  → file_id
              POST /api/v1/artist                             → artist_id
앨범마다      POST /api/v1/storage/image/ALBUM/{album_key}    → file_id
              POST /api/v1/album                              → album_id
곡마다        POST /api/v1/storage/image/TRACK/{track_id}     → file_id
              POST /api/v1/music                              → music_id
```

곡 1060개가 아티스트 87명 · 앨범 204개로 묶이면 호출은 이미지 1351번 +
생성 1351번입니다. 병렬로 돌리지 않습니다 — 아티스트 → 앨범 → 곡 순서를 지켜야
하고, 한 건씩 `registered.json` 에 적어야 중간에 끊겨도 이어서 할 수 있습니다.

### 설정

```jsonc
{
  // catalog · covers · lyrics 가 모두 이 디렉터리를 읽는다
  "metadata_dir": "/home/jupyter/flow_music_data/metadata",
  "covers_dir":   "/home/jupyter/flow_music_data/covers",
  "log_dir":      "./logs",
  "api_base_url": "http://localhost:9900",
  "api_refresh_token_file": "./refresh_token.txt",
  "api_registry_log": "./registered.json"
}
```

### 바뀐 것만 보낸다

모든 명령이 **파일 내용의 SHA-256 을 기억했다가, 달라진 것만 API 를 부릅니다.**
아무것도 안 바뀌었으면 전체 실행에서 HTTP 호출이 **0건**입니다.

| 무엇이 바뀌면 | 어느 명령이 | 무엇을 부르는가 |
| --- | --- | --- |
| 음원 `.mp3` | `batch` | 재청킹 → 기존 스트림 삭제 → 새 스트림·청크 |
| 곡 제목 · 발매일 | `catalog` | `PATCH /api/v1/music/{id}` |
| 아티스트명 | `catalog` | `PATCH /api/v1/artist/{id}` |
| 앨범명 · 발매일 | `catalog` | `PATCH /api/v1/album/{id}` |
| 곡이 다른 앨범/아티스트로 옮겨감 | `catalog` | `PATCH /api/v1/music/{id}` |
| 커버 `.jpg` | `covers` | 같은 저장 키에 다시 업로드 |
| 가사 | `lyrics` | `PUT /api/v1/music/{id}/lyric` |

#### 지문은 파일 전체가 아니라 필드 단위다

메타데이터 한 파일에 곡 정보·아티스트·앨범·가사가 **함께** 들어 있습니다.
파일 해시로 재면 가사 한 줄만 고쳐도 곡·아티스트·앨범이 전부 바뀐 것으로 보여
쓸데없는 `PATCH` 가 1,300번 나갑니다.

그래서 대상마다 **그 대상이 서버로 보내는 필드만** 묶어 잽니다.

| 대상 | 지문에 들어가는 것 |
| --- | --- |
| 아티스트 | `artist.name` |
| 앨범 | `album.title` · 수록곡 중 가장 이른 발매일 · `artist_id` |
| 곡 | `flow_title` · `generated_at` · `album_id` · `artist_id` |
| 가사 | `lyrics_sync` 의 `(text, start_sec)` 목록 |
| 커버 | 이미지 파일 바이트 |

실제로 확인한 동작:

```
② 아무것도 안 바꾸고 다시   → 호출 없음
③ 곡 제목 하나만 수정        → PATCH /api/v1/music/4  {title: "… (Remastered)"}   1건
④ 가사 첫 줄만 수정          → 호출 없음   ← 곡·아티스트·앨범은 나가지 않는다
⑤ 아티스트 이름 수정         → PATCH /api/v1/artist/12  {name: "Vellichor!"}      1건
```

#### 지문이 없는 옛 기록

이 기능 이전에 등록된 항목에는 지문이 없습니다. 서버가 파일과 같은지 알 길이
없으므로 **한 번 맞추고 지문을 남깁니다** — 파일이 기준이니 고치는 쪽이 맞습니다.
그 한 번만 `PATCH` 가 몰려 나가고, 다음 실행부터는 조용합니다.

#### 그 밖의 경우

| 상황 | 동작 |
| --- | --- |
| 곡이 늘어남 | 늘어난 곡만 등록한다. 아티스트·앨범은 이미 있으면 재사용 |
| 커버가 뒤늦게 생김 | `covers` 가 올린 뒤 `PATCH /api/v1/music/{id}` 로 곡에 잇는다 |
| 한 곡이 실패함 | 나머지는 계속 등록하고, 실패 목록을 마지막에 찍은 뒤 종료 코드 1 |
| 메타데이터에서 곡이 사라짐 | **서버에서 지우지 않는다.** 배치 서버에서 파일 하나가 사라졌다고 서비스 중인 곡을 내리면 위험하다. 내리려면 `DELETE /api/v1/music/{musicId}` 를 직접 부른다 |
| `registered.json` 을 지움 | **같은 곡이 한 번 더 만들어진다.** 지우지 마세요 |

`covers` 는 이미 등록된 대상만 손댑니다. 저장 키가 고정이라 같은 키에 다시
올리면 서버가 **오브젝트 내용만 갈아 끼우고 `file_id` 는 그대로 둡니다.** 그래서
아티스트·앨범은 행을 건드릴 필요가 없습니다.

### 청킹과의 관계

`catalog` 가 곡을 만들고, `batch` 가 그 곡에 스트림·청크를 붙입니다.

```
catalog  →  music_id 1..1060  (registered.json 의 catalog.tracks)
                  ↓
        ┌─────────┴─────────┐
lyrics  →  가사 줄          batch  →  stream_id + 청크
(track_id 로 위 표를 찾는다)        (오디오 파일명 T000001 로 찾는다)
```

`batch` 는 사이드카 JSON 이 없어도 `registered.json` 에서 `T000001` 을 찾으면
그 곡 번호로 스트림을 만듭니다. 그래서 **카탈로그를 먼저 돌린 뒤 청킹을 등록해도
곡이 두 번 만들어지지 않습니다.**

## 가사 등록 (`lyrics`)

가사는 곡 메타데이터 안의 `lyrics_sync` 에 들어 있습니다 — `catalog` 가 읽는
파일과 **같은 파일**입니다.

```json
"lyrics_sync": [
  { "text": "Sun goes down across the sky",     "start_sec": null, "end_sec": null },
  { "text": "Left my big speaker on the stage", "start_sec": 30.0, "end_sec": 32.1 }
]
```

연주곡은 이 값이 `null` 입니다. 곡 1060개 중 **가사가 있는 505곡**만 올립니다.

서버의 `lyrics` 테이블은 `text` 와 `time_ms` 둘뿐이라 쓰는 것도 `text` 와
`start_sec` 뿐입니다. `end_sec` 은 담을 자리가 없습니다.

### 시각이 비어 있는 줄

**가사 23,675줄 중 3,419줄(14%)에 `start_sec` 이 없습니다.** 정렬이 그 줄을
음원에 못 붙인 것입니다. `lyrics.time_ms` 는 NOT NULL 이라 그대로는 넣을 수
없어서, 둘 중 하나를 골라야 합니다.

| | 동작 | 대가 |
| --- | --- | --- |
| 기본 | 앞뒤로 시각을 아는 줄 **사이를 고르게 나눠 메운다** | 그 줄의 시각은 추정값이다 |
| `--skip-untimed` | 시각이 확실한 줄만 올린다 | 한 절에서 가운데 줄이 사라져 가사가 이가 빠진 채로 보인다 |

기본을 "메운다" 로 둔 이유는, 가사는 **읽는 것**이 먼저고 따라가는 것은
그다음이기 때문입니다. 줄이 통째로 사라지면 노래를 따라 부를 수 없지만, 한두
줄의 시각이 조금 어긋나는 것은 눈으로 보정됩니다.

결측은 대부분 **사이**에 있습니다 (구간 1,500개 중 사이 1,271 · 앞 125 ·
뒤 104, 중앙값 1줄). 사이는 앞뒤 시각을 알아 고르게 나눌 수 있고, 앞뒤 끝은
그 곡의 평균 줄 간격만큼 늘려 잡습니다.

```
원본=  None  결과=  17.28  Heavy black case banging on my knee   ← 메움
원본=  None  결과=  20.46  Walking past the laundromat in the…   ← 메움
원본=  30.0  결과=  30.00  Left my big speaker on the stage      ← 원본 그대로
원본=  32.3  결과=  32.30  Got twelve bucks sitting in my coat   ← 원본 그대로
```

시각이 있던 줄은 **손대지 않습니다.**

### 못 올리는 곡

| 경우 | 곡 수 | 동작 |
| --- | --- | --- |
| `lyrics_present` 인데 `lyrics_sync` 가 비어 있음 | 11 | 올릴 것이 없다. 기록만 남기고 넘어간다 |
| 시각을 아는 줄이 하나도 없음 (`T000407`) | 1 | 기준점이 없어 메울 수 없다. 건너뛴다 |

둘 다 실패로 세지 않습니다 — 사람이 원본을 고치기 전에는 다시 시도해도 같습니다.

### 실행

`catalog` 가 먼저 돌아 있어야 합니다. 곡 번호를 `registered.json` 의
`catalog.tracks` 에서 가져오기 때문입니다.

```bash
uv run music-preprocessing catalog -c config.json   # 곡을 먼저 만든다
uv run music-preprocessing lyrics  -c config.json
```

곡이 아직 없으면 그 곡은 건너뛰고 "`catalog` 를 먼저 돌리세요" 라고 알립니다.

### 곡마다 한 번

`PUT /api/v1/music/{musicId}/lyric` 이 그 곡의 가사를 **통째로 갈아 끼웁니다.**
지우는 일도 서버가 하므로 배치가 기존 줄을 조회해 하나씩 지울 필요가 없습니다.

| | 줄 단위 API 였을 때 | 지금 |
| --- | ---: | ---: |
| 첫 등록 | 24,133 요청 | **502** |
| `--force` 재등록 | 47,761 요청 | **502** |

곡 하나가 서버에서 한 트랜잭션이라, 중간에 끊겨도 반쯤 올라간 가사가 남지
않습니다 → [decision/0020](../api/knowledge/decision/0020-lyric-bulk-replace.md).

| 상황 | 동작 |
| --- | --- |
| 그대로 다시 실행 | 아무것도 호출하지 않는다 (가사 내용의 SHA-256 을 비교) |
| 가사가 바뀐 곡 | 그 곡의 **기존 가사를 지우고** 다시 올린다 |
| 지난번이 중간에 끊김 | 서버가 먼저 비우므로 그냥 다시 올리면 된다 |
| `--force` | 바뀌지 않은 곡도 지우고 다시 올린다 |

지문은 파일 전체가 아니라 `lyrics_sync` 만 가지고 냅니다. 같은 파일에 들어 있는
곡명·아티스트가 바뀌었다고 가사 23,000줄을 다시 올릴 이유가 없습니다.

**덮어씁니다.** 누가 손으로 고쳐 둔 가사가 있으면 배치가 지웁니다.

## API 인증과 청킹 등록 (`batch`)

인증(1번)은 `catalog` · `covers` · `batch` 가 모두 같은 방식입니다.

### 1. ROLE_SYSTEM 계정 발급

관리자 계정으로 한 번만 호출합니다.

```bash
curl -X POST http://localhost:9900/api/v1/user/system   -H "Authorization: Bearer <관리자 액세스 토큰>"   -H "Content-Type: application/json"   -d '{"email":"chunker@ssafy","nickname":"청킹 배치"}'
```

응답의 `refresh_token` 을 `refresh_token.txt` 에 저장합니다. **비밀번호와
리프레시 토큰은 이 응답에서만 평문으로 나옵니다.**

배치는 이 파일 하나만 들고 돕니다. 액세스 토큰이 필요할 때마다
`GET /api/v1/auth` 로 받고, 서버가 함께 내려주는 새 리프레시 토큰으로
파일을 갱신합니다. 그래서 7일 만료에 걸리지 않습니다.

`refresh_token.txt` 는 시스템 권한 그 자체입니다. 커밋하지 마세요
(`.gitignore` 에 등록되어 있습니다).

### 2. 입력 디렉터리 구성

`batch` 가 곡을 특정하는 길은 둘입니다.

1. **카탈로그**: 오디오 파일명이 `catalog` 로 등록한 `track_id` 와 같으면
   (`T000001.mp3` ↔ `T000001.json`) 그 곡 번호를 그대로 씁니다. 사이드카가
   필요 없습니다 — flow_music 데이터는 이 길로 갑니다.
2. **사이드카**: 오디오 파일 옆에 **같은 이름의 JSON** 을 놓습니다. 카탈로그에
   없는 곡을 청킹과 함께 등록할 때 씁니다.

둘 다 없으면 그 파일은 등록하지 않고 건너뜁니다 (청킹은 그대로 수행).

아래는 사이드카 쪽 구성입니다.

```
input/
├── palette-01.mp3
├── palette-01.json       ← 사이드카
├── palette-02.mp3
├── palette-02.json
└── images/
    ├── iu.jpg
    └── palette.jpg
```

```json
{
  "title": "밤편지",
  "release_date": "2017-03-24",
  "artist": { "name": "아이유", "image": "images/iu.jpg" },
  "album": {
    "title": "Palette",
    "release_date": "2017-04-21",
    "image": "images/palette.jpg"
  }
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `title` | ✅ | 곡명 |
| `release_date` | ✅ | 곡 발매일 (`YYYY-MM-DD` 또는 ISO-8601) |
| `artist.name` | ✅ | 아티스트명 |
| `artist.image` | ✅ | 아티스트 이미지 (사이드카 기준 상대경로) |
| `album.title` | ✅ | 앨범명 |
| `album.image` | ✅ | 앨범 커버 (사이드카 기준 상대경로) |
| `album.release_date` | | 없으면 곡 발매일을 쓴다 |
| `artist.id` · `album.id` · `music_id` | | 이미 아는 번호. 주면 그 단계를 건너뛴다 |

이미지는 서버에서 필수(`image_file_id` NOT NULL)라 생략할 수 없습니다.

### 3. 설정 후 실행

```bash
cp config.example.json config.json   # api_base_url 을 채운다
uv run music-preprocessing batch -c config.json
```

### 등록 순서

곡 하나당 아래 순서로 호출합니다.

1. 이미지 업로드 (`POST /api/v1/storage/image/ARTIST|ALBUM/{key}`) → `file_id`
2. `POST /api/v1/artist` → `artist_id`
3. `POST /api/v1/album` → `album_id`
4. `POST /api/v1/music` → `music_id`
5. `POST /api/v1/music/{musicId}/stream` → `stream_id` (`PENDING`)
6. 청크마다 두 번 × N
   - `PUT /upload/chunk/{streamId}/{index}` — 바이트를 스토리지에 올린다
   - `POST /api/v1/chunk/{streamId}/{index}` — 길이·해시·크기를 기록한다
7. `PATCH /api/v1/stream/{streamId}` → `DONE`

### 청크 바이트는 스프링을 거치지 않는다

6번의 `PUT` 은 `/api/` 아래가 **아니다.** nginx 가 인증만 스프링에 묻고
(`auth_request`), 본문은 오브젝트 스토리지로 그대로 흘려보낸다.

```
PUT /upload/chunk/1/0
  ├─ nginx → GET /api/v1/chunk/1/0/upload-auth   (본문 없음)
  │     스프링: 권한·스트림 상태·순서·용량 검사
  │     응답: 200 + X-Storage-Path: /buckets/mupid/music/1/opus128/chunk_0000.rtp
  └─ nginx → 스토리지로 본문 스트리밍 (JVM 을 거치지 않음)
```

고용량 음원이 `MultipartFile` 로 JVM 힙에 올라오는 것을 막으려는 구조다.
다운로드의 `X-Accel-Redirect` 와 짝이고 방향만 반대다.

스프링은 바이트를 보지 못하므로 **해시를 검증하지 않는다.** 이어지는
`POST /api/v1/chunk/…` 에서 오브젝트가 실제로 있는지와 크기만 대조한다
(`HEAD`, 본문은 받지 않음).

업로드가 거절되면 nginx 가 4xx 를 500 으로 접어 버리므로, 사유는 응답의
`X-Mupid-Error` 헤더에서 읽는다. 클라이언트(`api_client.py`)가 이 헤더를
오류 코드로 올려준다.

아티스트·앨범은 **이름 기준으로 한 번만** 만듭니다. 이름 → 번호 매핑은
`registered.json` 에 남습니다 (API 에 이름으로 조회하는 엔드포인트가 없어서
배치가 직접 기억합니다). 이 파일을 지우면 같은 아티스트가 다시 만들어집니다.

### 재처리와 재시도

| 상황 | 동작 |
| --- | --- |
| 원본 내용이 바뀜 | 재청킹 후 **기존 스트림을 지우고** 다시 올린다. `music_id` 는 유지 |
| 청킹은 됐는데 등록 실패 | 다음 실행에서 **재청킹 없이** 등록만 다시 시도 |
| 청크 업로드 중 실패 | 반쯤 올라간 스트림을 지운다 (다음 시도가 409 로 막히지 않게) |
| 같은 청크를 다시 올림 | 덮어쓴다 |
| 카탈로그에도 없고 사이드카도 없음 | 청킹만 하고 등록은 보류. 나중에 `catalog` 를 돌리거나 사이드카를 놓으면 **재청킹 없이** 등록된다. 실패로 세지 않으므로 크론 종료 코드는 0 |
| 카탈로그가 이미 곡을 만들어 둠 | 곡을 새로 만들지 않고 그 번호에 스트림만 붙인다 |
| 입력 파일이 삭제됨 | 로컬 출력 디렉터리만 지운다. **서버에 등록된 곡·스트림은 그대로 둔다** |
| 서버에 쓰이지 않는 스트림이 남음 | 경고만 남긴다. 지우지 않는다 → [쓰이지 않는 스트림·청크](#쓰이지-않는-스트림청크-batch-가-함께-훑는다) |

입력 파일을 지워도 서버 데이터가 지워지지 않는 것은 의도한 동작입니다. 배치
서버에서 파일 하나가 사라졌다고 서비스 중인 곡을 내리면 위험합니다. 서버에서
내리려면 `DELETE /api/v1/music/{musicId}` 를 직접 호출하세요.

곡 번호는 스트림 작업 전에 `registered.json` 에 먼저 기록합니다. 그래야
등록이 중간에 실패해도 다음 배치가 같은 곡을 또 만들지 않습니다.

## 쓰이지 않는 스트림·청크 (`batch` 가 함께 훑는다)

`batch` 가 끝나면 서버에 **아무도 재생하지 않는 청크**가 남아 있는지 훑고
**경고만** 남깁니다. 지우지 않습니다.

```
WARNING 배치가 모르는 스트림: music_id=2 stream_id=901 (OPUS 64kbps, 청크 40개).
        기록에 있는 것은 102 입니다. 지우려면 DELETE /api/v1/stream/901
WARNING 겹치는 스트림: music_id=3 OPUS 128kbps 가 2개 (stream_id=103, 903).
        화면은 하나만 고르므로 나머지 청크는 재생되지 않습니다
WARNING 스트림 점검: 곡 7개 · 스트림 9개를 보고 5건을 찾았습니다
        (모르는 스트림 3 · 겹침 1 · 청크 수 불일치 1 · 모자람 0).
        **지우지 않았습니다** — 확인 후 DELETE /api/v1/stream/{streamId} 로 치우세요
```

### 왜 지우지 않는가

배치가 판단할 수 있는 것은 "**내 기록에 없다**" 까지이고, 그것이 곧 "버려도
된다" 는 아닙니다. 누가 다른 비트레이트로 스트림을 하나 더 만들어 두었을 수도
있습니다. 사람이 보고 지웁니다 — `DELETE /api/v1/stream/{streamId}` 가 그
스트림의 청크까지 함께 치웁니다.

### 무엇을 찾는가

| 종류 | 무슨 뜻인가 |
| --- | --- |
| 배치가 모르는 스트림 | `registered.json` 이 적어 둔 `stream_id` 가 아니다. 크래시 뒤 남았거나 배치 밖에서 만든 것 |
| 겹치는 스트림 | 한 곡에 같은 코덱·비트레이트가 둘 이상. 화면은 비트레이트가 가장 높은 것 하나만 고르므로 나머지 청크는 영영 안 나간다 |
| 청크 수 불일치 | 서버가 선언한 `chunk_count` 가 배치가 만든 청크 수와 다르다 |
| 청크 모자람 | 실제 청크 행 수 < 선언한 수. **재생이 도중에 끊긴다** (`--verify-chunks`) |

### 못 찾는 것 — 알고 계셔야 합니다

**`PENDING` · `IN_PROGRESS` · `FAILED` 스트림은 보이지 않습니다.**
`GET /api/v1/music/{musicId}/stream` 이 `DONE` 만 걸러 주기 때문입니다
(`StreamServiceImpl.getStreamByMusicId:76`).

하필 **가장 흔한 찌꺼기가 이쪽**입니다 — 청크를 올리다 프로세스가 죽으면
스트림이 `PENDING` 인 채로 남습니다. 배치가 정상적으로 실패하면 스스로
치우지만(`Registrar._drop_stream`), 강제 종료·정전에는 손쓸 수 없습니다.

그래서 점검이 끝날 때마다 **찾지 못한다는 사실을 로그에 남깁니다.** 조용히
넘어가면 "경고가 없으니 깨끗하다" 고 잘못 읽게 됩니다.

찾으려면 서버에 상태 필터가 필요합니다 (`?status=PENDING` 같은). 그건 API
변경이라 하지 않았습니다.

곡이 서버에서 지워진 경우(`404`)도 마찬가지입니다 — 딸린 스트림이 남아 있어도
물어볼 길이 없어 그 사실만 경고합니다.

### 비용과 끄는 법

곡 하나당 `GET` 한 번입니다 (1,060곡이면 20초 남짓). 수상한 스트림만 청크
목록까지 더 봅니다.

```bash
uv run music-preprocessing batch -c config.json --no-orphan-check   # 건너뛰기
uv run music-preprocessing batch -c config.json --verify-chunks     # 성한 것까지 청크 대조
```

`--verify-chunks` 는 스트림 수만큼 `GET` 이 더 나갑니다. 평소에는 끄고, 재생이
끊긴다는 말이 나올 때 켜세요.

**종료 코드는 건드리지 않습니다.** 찌꺼기가 있어도 크론은 성공으로 끝납니다 —
청킹과 등록은 이미 제대로 됐고, 이건 치울 거리를 알려 주는 것뿐입니다.

## 출력 구조

```
output/
└── song_name/
    ├── metadata.json       # 트랙 메타데이터 (청크별 길이·SHA-256 포함)
    ├── chunk_0000.opus     # Opus 세그먼트 (디버깅/폴백)
    ├── chunk_0000.rtp      # RTP 패킷 바이너리
    ├── chunk_0001.opus
    ├── chunk_0001.rtp
    └── ...
```

## .rtp 파일 포맷

```
Offset  Size  Description
0       4     매직 넘버 b'RTPK'
4       2     포맷 버전 (uint16 BE)
6       4     총 패킷 수 (uint32 BE)
10      ...   패킷 배열: [uint16 길이 | RTP 패킷 데이터] × N
```

## crontab 설정

```bash
# 매일 새벽 2시 — 새 곡을 카탈로그에 등록하고
0 2 * * * cd /opt/music_preprocessing && uv run music-preprocessing catalog -c config.json --yes --no-progress
# 2시 15분 — 커버 (바뀐 그림만. 바뀐 게 없으면 호출 0건이라 공짜다)
15 2 * * * cd /opt/music_preprocessing && uv run music-preprocessing covers -c config.json --yes --no-progress
# 2시 30분 — 가사 (곡이 있어야 한다)
30 2 * * * cd /opt/music_preprocessing && uv run music-preprocessing lyrics -c config.json --yes --no-progress
# 새벽 3시 — 청킹과 스트림 등록
0 3 * * * cd /opt/music_preprocessing && uv run music-preprocessing batch -c config.json
```

크론에는 `--yes` 를 붙입니다. 붙이지 않으면 확인 프롬프트에서 입력을 받지 못해
종료 코드 130 으로 끝납니다. `--no-progress` 는 터미널이 아니면 자동으로
꺼지지만, 명시해 두면 의도가 드러납니다.

리다이렉션이 없는 것은 **로그가 이미 파일로 가기 때문입니다** (설정의
`log_dir`). 크론 메일로 오는 것은 화면 요약과 실패 표뿐입니다.

`catalog` 를 `batch` 보다 먼저 두는 것이 중요합니다. 곡이 먼저 있어야 `batch`
가 그 곡에 스트림을 붙입니다. 순서가 뒤집히면 `batch` 는 사이드카가 없는 곡을
건너뛰고(종료 코드 0), 다음 날 `catalog` 가 곡을 만든 뒤에야 등록됩니다.

자세한 crontab 예시는 `crontab.example.sh`를 참고하세요.

## 요구사항

- Python ≥ 3.12 (원격 배치 서버의 기본 파이썬에 맞췄습니다. 로컬 개발은 3.14)
- FFMPEG (libopus 포함) — 청킹에만 필요합니다. `catalog` · `covers` 는 없어도 됩니다
- (등록 기능을 쓸 경우) Mupid API 와 `ROLE_SYSTEM` 계정

의존성은 CLI 와 로그 서식용 **`typer` · `rich` 둘뿐**입니다. HTTP 호출은 그대로
표준 라이브러리(`urllib`)로 합니다 — 등록 경로에는 외부 패키지가 없습니다.
