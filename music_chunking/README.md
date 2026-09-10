# music_chunking

음악 스트리밍을 위한 **Opus 청크 + RTP 헤더** 전처리 프로그램

## 처리 파이프라인

```
MP3/WAV/FLAC → FFMPEG(Opus 인코딩+세그먼트 분할) → Ogg 파싱 → RTP 패킷화 → .rtp 파일
```

1. **FFMPEG 세그먼트 분할**: 입력 오디오를 N초 단위 Ogg/Opus 세그먼트로 분할
2. **Ogg 컨테이너 파싱**: 각 세그먼트에서 개별 Opus 프레임 추출
3. **RTP 패킷화**: 각 프레임에 12바이트 RTP 헤더(RFC 3550) 부착
4. **바이너리 저장**: 스트리밍 서버가 바로 전송 가능한 `.rtp` 파일로 저장

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
```

## 출력 구조

```
output/
└── song_name/
    ├── metadata.json       # 트랙 메타데이터
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
