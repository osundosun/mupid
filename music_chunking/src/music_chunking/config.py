"""청크 변환 설정 관리"""

from __future__ import annotations

import json
import multiprocessing
from dataclasses import dataclass, field


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

    def __post_init__(self) -> None:
        if self.max_workers <= 0:
            self.max_workers = max(1, multiprocessing.cpu_count())

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
        }
