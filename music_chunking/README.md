# music_chunking

음악 스트리밍을 위한 **Opus 청크 + RTP 헤더** 전처리 · **API 등록** 프로그램

## 처리 파이프라인

```
MP3/WAV/FLAC → FFMPEG(Opus 인코딩+세그먼트 분할) → Ogg 파싱 → RTP 패킷화 → .rtp 파일
                                                                          ↓
                                          Mupid API 등록 (아티스트 → 앨범 → 음악 → 스트림 → 청크)
```

1. **FFMPEG 세그먼트 분할**: 입력 오디오를 N초 단위 Ogg/Opus 세그먼트로 분할
2. **Ogg 컨테이너 파싱**: 각 세그먼트에서 개별 Opus 프레임 추출
3. **RTP 패킷화**: 각 프레임에 12바이트 RTP 헤더(RFC 3550) 부착
4. **바이너리 저장**: 스트리밍 서버가 바로 전송 가능한 `.rtp` 파일로 저장
5. **API 등록**: 사이드카 JSON 을 읽어 곡 메타와 청크를 서버에 올린다
   (`api_base_url` 을 설정했을 때만)

## 빠른 시작

```bash
# 가상환경 세팅
uv sync

# 단일 파일 처리
uv run python -m music_chunking single -i song.mp3 -o ./output

# 배치 처리
uv run python -m music_chunking batch -I ./input -O ./output

# 설정 파일 사용
uv run python -m music_chunking batch --config config.example.json
```

## CLI 옵션

```
music-chunking [-v] {single,batch}

single:
  -i, --input           입력 파일 경로
  -o, --output          출력 디렉토리 (기본: ./output)

batch:
  -I, --input-dir       입력 디렉토리 (기본: ./input)
  -O, --output-dir      출력 디렉토리 (기본: ./output)
  --config              JSON 설정 파일 경로
  --processed-log       처리 완료 로그 (기본: ./processed.json)

공통:
  -b, --bitrate         비트레이트 (기본: 128k)
  --segment-time        세그먼트 길이 초 (기본: 6)
  --frame-duration      Opus 프레임 길이 ms (기본: 20)
  --workers             병렬 워커 수 (기본: CPU 코어 수)
  --ffmpeg-threads      파일당 FFMPEG 스레드 수 (기본: 2)
  -v, --verbose         DEBUG 레벨 로그 출력

API 등록:
  --api-base-url             Mupid API 루트. 비우면 등록하지 않음
  --api-refresh-token-file   리프레시 토큰 파일 (기본: ./refresh_token.txt)
  --api-registry-log         등록 결과 기록 (기본: ./registered.json)
```

## API 등록

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

오디오 파일마다 **같은 이름의 사이드카 JSON** 이 있어야 합니다. 없으면 그
파일은 등록하지 않고 건너뜁니다 (청킹은 그대로 수행).

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
uv run python -m music_chunking batch --config config.json
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
| 사이드카가 없음 | 청킹만 하고 등록은 보류. 나중에 사이드카를 놓으면 **재청킹 없이** 등록된다. 실패로 세지 않으므로 크론 종료 코드는 0 |
| 입력 파일이 삭제됨 | 로컬 출력 디렉터리만 지운다. **서버에 등록된 곡·스트림은 그대로 둔다** |

입력 파일을 지워도 서버 데이터가 지워지지 않는 것은 의도한 동작입니다. 배치
서버에서 파일 하나가 사라졌다고 서비스 중인 곡을 내리면 위험합니다. 서버에서
내리려면 `DELETE /api/v1/music/{musicId}` 를 직접 호출하세요.

곡 번호는 스트림 작업 전에 `registered.json` 에 먼저 기록합니다. 그래야
등록이 중간에 실패해도 다음 배치가 같은 곡을 또 만들지 않습니다.

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
# 매일 새벽 2시에 배치 처리
0 2 * * * cd /opt/music_chunking && uv run python -m music_chunking batch --config config.json >> /var/log/music_chunking/batch.log 2>&1
```

자세한 crontab 예시는 `crontab.example.sh`를 참고하세요.

## 요구사항

- Python ≥ 3.14
- FFMPEG (libopus 포함)
- (등록 기능을 쓸 경우) Mupid API 와 `ROLE_SYSTEM` 계정

외부 패키지 의존성은 없습니다. HTTP 호출도 표준 라이브러리(`urllib`)로 합니다.
