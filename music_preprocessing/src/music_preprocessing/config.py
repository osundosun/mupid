"""청크 변환 설정 관리"""

from __future__ import annotations

import json
import multiprocessing
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ChunkConfig:
    """음악 청크 변환 설정

    Attributes:
        bitrate: Opus 인코딩 비트레이트 (예: "128k", "64k", "256k")
        segment_time: 세그먼트 길이 (초)
        frame_duration: Opus 프레임 길이 (ms). 2.5, 5, 10, 20, 40, 60 중 선택
        compression_level: Opus 압축 레벨 (0-10, 높을수록 품질↑/속도↓)
        vbr: 가변 비트레이트 ("on", "off", "constrained")
        application: Opus 애플리케이션 모드 ("audio", "voip", "lowdelay")
        rtp_payload_type: RTP 페이로드 타입 (동적 할당, 기본 111)
        rtp_clock_rate: RTP 클럭 레이트 (Opus 표준: 48000Hz)
        max_workers: 병렬 워커 수 (0=CPU 코어 수 자동 감지)
        ffmpeg_threads: 파일당 FFMPEG 스레드 수
        input_dir: 입력 디렉토리 경로
        output_dir: 출력 디렉토리 경로
        processed_log: 처리 완료 로그 파일 경로
        metadata_dir: 곡별 메타데이터 JSON 디렉터리
            (`catalog` · `covers` · `lyrics` 가 모두 이 디렉터리를 읽는다)
        covers_dir: 곡별 커버 이미지 디렉터리 (`catalog` · `covers` 명령)
        log_dir: 실행 로그를 남길 디렉터리. 실행마다 파일 하나가 생긴다
        api_base_url: Mupid API 루트. 비우면 등록 단계를 건너뛴다
        api_refresh_token_file: ROLE_SYSTEM 계정의 리프레시 토큰 파일
        api_registry_log: 등록 결과(아티스트·앨범·곡 번호) 기록 파일
        api_timeout: API 요청 타임아웃 (초)
        api_max_retries: 5xx·네트워크 오류 재시도 횟수
        genre_taxonomy_file: 장르·무드 어휘 파일 (`classify` 명령).
            비우면 `metadata_dir` 의 부모에서 `genre_mood_taxonomy.json` 을 찾는다
    """

    # ── 오디오 인코딩 ──
    bitrate: str = "128k"
    segment_time: int = 6
    frame_duration: int = 20
    compression_level: int = 10
    vbr: str = "on"
    application: str = "audio"

    # ── RTP ──
    rtp_payload_type: int = 111
    rtp_clock_rate: int = 48000

    # ── 병렬 처리 ──
    max_workers: int = 0
    ffmpeg_threads: int = 2

    # ── 경로 ──
    input_dir: str = "./input"
    output_dir: str = "./output"
    processed_log: str = "./processed.json"

    # ── 카탈로그 (`catalog` · `covers` 명령) ──
    # 수집 파이프라인이 만든 디렉터리를 그대로 읽는다. 파일 이름이 곧 track_id 다.
    metadata_dir: str = "./metadata"
    covers_dir: str = "./covers"

    # ── 로그 ──
    log_dir: str = "./logs"

    # ── API 등록 ──
    # api_base_url 이 비어 있으면 청킹만 하고 등록은 하지 않는다.
    api_base_url: str = ""
    api_refresh_token_file: str = "./refresh_token.txt"
    api_registry_log: str = "./registered.json"
    api_timeout: int = 30
    api_max_retries: int = 3

    # ── 장르·무드 (`classify` 명령) ──
    # 수집 파이프라인이 `metadata/` 옆에 같이 두는 파일이다. 비우면 그렇게 찾는다.
    genre_taxonomy_file: str = ""

    # ── 구간 분석 (`analysis/`, `python -m music_preprocessing.analysis.batch`) ──
    #
    # 청킹 배치와 **다른 배치**다. 여기 있는 이유는 배포 규약 때문이다 — 서버 설정은
    # `config.json` 의 필드 이름을 대문자로 쓴 것만 받고, 모르는 이름이면 배포가 멈춘다
    # (`deploy.env.example`). 이름 공간 밖의 환경변수에만 기대면 배포로 값을 넣을 수 없다.
    #
    # 기본값은 **꺼짐**이다. 청킹만 쓰는 쪽의 동작이 달라지지 않는다.
    # 내부 토큰은 값이 아니라 **파일 경로**로 받는다 (`api_refresh_token_file` 과 같은 방식).
    analysis_enabled: bool = False
    analysis_artifact_dir: str = ""          # 비우면 $MUPID_AI_ARTIFACT_DIR
    analysis_work_dir: str = "./analysis_work"
    analysis_internal_token_file: str = "./ai_internal_token.txt"
    analysis_version: str = "v1"
    analysis_limit: int = 200                # 요구 5 한 페이지 곡 수
    analysis_device: str = ""                # cuda:0 · cpu. 비우면 있는 쪽
    analysis_segmenter_python: str = ""      # All-In-One(3.10) 실행 파일. 비우면 같은 프로세스
    analysis_keep_versions: int = 3          # 롤백용으로 남길 이전 색인 판 수

    def __post_init__(self) -> None:
        if self.max_workers <= 0:
            self.max_workers = max(1, multiprocessing.cpu_count())

    @property
    def taxonomy_path(self) -> Path:
        """`classify` 가 읽을 어휘 파일. 설정이 비어 있으면 메타데이터 옆에서 찾는다."""
        if self.genre_taxonomy_file:
            return Path(self.genre_taxonomy_file)
        return Path(self.metadata_dir).parent / "genre_mood_taxonomy.json"

    @property
    def register_enabled(self) -> bool:
        """API 등록 단계를 수행할지 여부."""
        return bool(self.api_base_url)

    @classmethod
    def from_file(cls, path: str) -> ChunkConfig:
        """JSON 설정 파일로부터 ChunkConfig를 생성합니다."""
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return cls(**data)

    def to_dict(self) -> dict:
        """설정을 딕셔너리로 변환합니다."""
        return {
            "bitrate": self.bitrate,
            "segment_time": self.segment_time,
            "frame_duration": self.frame_duration,
            "compression_level": self.compression_level,
            "vbr": self.vbr,
            "application": self.application,
            "rtp_payload_type": self.rtp_payload_type,
            "rtp_clock_rate": self.rtp_clock_rate,
            "max_workers": self.max_workers,
            "ffmpeg_threads": self.ffmpeg_threads,
            "input_dir": self.input_dir,
            "output_dir": self.output_dir,
            "processed_log": self.processed_log,
            "metadata_dir": self.metadata_dir,
            "covers_dir": self.covers_dir,
            "log_dir": self.log_dir,
            "api_base_url": self.api_base_url,
            "api_refresh_token_file": self.api_refresh_token_file,
            "api_registry_log": self.api_registry_log,
            "api_timeout": self.api_timeout,
            "api_max_retries": self.api_max_retries,
            "genre_taxonomy_file": self.genre_taxonomy_file,
            "analysis_enabled": self.analysis_enabled,
            "analysis_artifact_dir": self.analysis_artifact_dir,
            "analysis_work_dir": self.analysis_work_dir,
            "analysis_internal_token_file": self.analysis_internal_token_file,
            "analysis_version": self.analysis_version,
            "analysis_limit": self.analysis_limit,
            "analysis_device": self.analysis_device,
            "analysis_segmenter_python": self.analysis_segmenter_python,
            "analysis_keep_versions": self.analysis_keep_versions,
        }
