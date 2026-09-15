"""RTP 패킷 생성기 — RFC 3550 + RFC 7587 (Opus)

RTP 헤더 구조 (12바이트):
  ┌─────────────────────────────────────────────────────────────────┐
  │ V=2 │P│X│  CC  │M│     PT      │       Sequence Number        │
  ├─────────────────────────────────────────────────────────────────┤
  │                         Timestamp                              │
  ├─────────────────────────────────────────────────────────────────┤
  │                           SSRC                                 │
  └─────────────────────────────────────────────────────────────────┘

Opus RTP Payload (RFC 7587):
  - Payload Type: 동적 할당 (기본 111)
  - Clock Rate: 48000 Hz
  - 프레임 크기: 통상 20ms (960 samples @ 48kHz)
"""

from __future__ import annotations

import random
import struct


class RTPPacketizer:
    """Opus 프레임을 RTP 패킷으로 래핑합니다.

    각 Opus 프레임에 12바이트 RTP 헤더를 붙여
    스트리밍 서버가 바로 전송할 수 있는 형태로 만듭니다.
    """

    HEADER_SIZE = 12  # bytes

    def __init__(
        self,
        payload_type: int = 111,
        clock_rate: int = 48000,
        frame_duration_ms: int = 20,
        ssrc: int | None = None,
        initial_seq: int | None = None,
        initial_timestamp: int = 0,
    ) -> None:
        self.payload_type = payload_type & 0x7F
        self.clock_rate = clock_rate
        self.frame_duration_ms = frame_duration_ms
        self.samples_per_frame = int(clock_rate * frame_duration_ms / 1000)
        self.ssrc = ssrc if ssrc is not None else random.randint(0, 0xFFFFFFFF)
        self.sequence_number = (
            initial_seq if initial_seq is not None else random.randint(0, 0xFFFF)
        )
        self.timestamp = initial_timestamp

    def build_header(self, marker: bool = False) -> bytes:
        """12바이트 RTP 헤더를 생성합니다.

        Args:
            marker: Marker bit (세그먼트 시작 등을 알리는 플래그)
        """
        byte0 = 0x80  # V=2, P=0, X=0, CC=0
        byte1 = (0x80 if marker else 0x00) | self.payload_type

        return struct.pack(
            "!BBHII",
            byte0,
            byte1,
            self.sequence_number & 0xFFFF,
            self.timestamp & 0xFFFFFFFF,
            self.ssrc & 0xFFFFFFFF,
        )

    def packetize_frame(self, opus_frame: bytes, marker: bool = False) -> bytes:
        """하나의 Opus 프레임을 RTP 패킷으로 변환합니다.

        시퀀스 넘버와 타임스탬프를 자동 증가시킵니다.
        """
        header = self.build_header(marker=marker)
        packet = header + opus_frame

        self.sequence_number = (self.sequence_number + 1) & 0xFFFF
        self.timestamp += self.samples_per_frame

        return packet

    def packetize_frames(self, opus_frames: list[bytes]) -> list[bytes]:
        """여러 Opus 프레임을 RTP 패킷 리스트로 변환합니다.

        첫 번째 패킷에 marker bit를 설정하여 세그먼트 시작을 표시합니다.
        """
        return [
            self.packetize_frame(frame, marker=(i == 0))
            for i, frame in enumerate(opus_frames)
        ]
