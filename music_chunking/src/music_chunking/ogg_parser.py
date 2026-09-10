"""Ogg/Opus 컨테이너 파서 — Opus 오디오 프레임 추출

Ogg 컨테이너 구조 (RFC 3533):
  ┌──────────────────────────────────────────────┐
  │ "OggS" (4B) | version (1B) | header_type (1B) │
  │ granule_position (8B) | serial_number (4B)    │
  │ page_sequence (4B) | CRC (4B) | segments (1B) │
  │ segment_table (N bytes) | segment_data (...)   │
  └──────────────────────────────────────────────┘

Opus over Ogg (RFC 7845):
  - Page 1: OpusHead (식별 헤더)
  - Page 2: OpusTags (코멘트 헤더)
  - Page 3+: 오디오 데이터 (Opus 패킷)
"""

from __future__ import annotations

import struct
from typing import BinaryIO


def extract_opus_frames(ogg_path: str) -> list[bytes]:
    """Ogg/Opus 파일에서 오디오 Opus 프레임만 추출합니다.

    Args:
        ogg_path: Ogg/Opus 파일 경로

    Returns:
        Opus 오디오 프레임(패킷) 리스트
    """
    frames: list[bytes] = []
    partial = bytearray()  # 페이지 경계를 넘는 패킷 버퍼
    page_count = 0

    with open(ogg_path, "rb") as f:
        while True:
            page = _read_ogg_page(f)
            if page is None:
                break

            header_type, data, segment_table = page
            page_count += 1

            # 처음 2개 페이지는 OpusHead / OpusTags → 스킵
            if page_count <= 2:
                partial = bytearray()
                continue

            # 세그먼트 테이블을 순회하며 패킷 경계 복원
            offset = 0
            for seg_size in segment_table:
                partial.extend(data[offset : offset + seg_size])
                offset += seg_size

                # 세그먼트 크기 < 255 → 패킷 종료
                if seg_size < 255:
                    if partial:
                        frames.append(bytes(partial))
                    partial = bytearray()

    # 파일 끝에 미완성 패킷이 남아있으면 플러시
    if partial:
        frames.append(bytes(partial))

    return frames


def _read_ogg_page(f: BinaryIO) -> tuple[int, bytes, bytes] | None:
    """단일 Ogg 페이지를 파싱합니다.

    Returns:
        (header_type, segment_data, segment_table) 또는 EOF 시 None
    """
    # ── 캡처 패턴 ──
    magic = f.read(4)
    if len(magic) < 4:
        return None
    if magic != b"OggS":
        raise ValueError(f"잘못된 Ogg 페이지: 'OggS' 기대, {magic!r} 수신")

    # ── 페이지 헤더 (23바이트) ──
    # version(1) + header_type(1) + granule_pos(8) +
    # serial(4) + page_seq(4) + crc(4) + num_segments(1) = 23
    header = f.read(23)
    if len(header) < 23:
        return None

    header_type = header[1]
    num_segments = header[22]

    # ── 세그먼트 테이블 ──
    segment_table = f.read(num_segments)
    if len(segment_table) < num_segments:
        return None

    # ── 세그먼트 데이터 ──
    total_size = sum(segment_table)
    data = f.read(total_size)
    if len(data) < total_size:
        return None

    return header_type, data, segment_table
