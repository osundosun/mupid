"""MP3 → Opus 청크 + RTP 변환 핵심 로직

처리 파이프라인:
  1. FFMPEG로 입력 오디오 → Ogg/Opus 세그먼트 분할
  2. 각 세그먼트에서 Opus 프레임 추출 (Ogg 컨테이너 파싱)
  3. 각 프레임에 RTP 헤더 부착
  4. 바이너리 .rtp 파일 + 원본 .opus 파일로 저장
"""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
import struct
import subprocess
import tempfile
import time
from pathlib import Path

from .config import ChunkConfig
from .hasher import compute_sha256
from .ogg_parser import extract_opus_frames
from .rtp import RTPPacketizer
from .waveform import WaveformError, compute_waveform

logger = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────
# RTP 바이너리 파일 포맷
# ─────────────────────────────────────────────────────────────
#   Offset  Size  Description
#   0       4     매직 넘버 b'RTPK'
#   4       2     포맷 버전 (uint16, big-endian)
#   6       4     총 패킷 수 (uint32, big-endian)
#   10      ...   패킷 배열: [uint16 길이 | RTP 패킷 데이터] × N
# ─────────────────────────────────────────────────────────────

_RTP_FILE_MAGIC = b"RTPK"
_RTP_FILE_VERSION = 1


def process_file(
    input_path: str,
    output_dir: str,
    config: ChunkConfig,
) -> dict:
    """하나의 오디오 파일을 Opus 청크 + RTP 패킷 파일로 변환합니다.

    Args:
        input_path: 입력 오디오 파일 경로 (mp3, wav, flac 등)
        output_dir: 출력 루트 디렉토리
        config: 변환 설정

    Returns:
        처리 결과 메타데이터 딕셔너리
    """
    input_path = Path(input_path)
    output_dir = Path(output_dir)
    start_time = time.time()

    # 출력 디렉토리: output_dir / {파일명(확장자 제외)}
    file_stem = input_path.stem
    file_output_dir = output_dir / file_stem
    file_output_dir.mkdir(parents=True, exist_ok=True)

    # 임시 디렉토리에서 FFMPEG 세그먼트 생성
    with tempfile.TemporaryDirectory(prefix="music_chunk_") as tmp_dir:
        tmp_path = Path(tmp_dir)

        # ── 1단계: FFMPEG Opus 세그먼트 생성 ──
        segment_pattern = tmp_path / "chunk_%04d.opus"
        _run_ffmpeg_segment(input_path, segment_pattern, config)

        segments = sorted(tmp_path.glob("chunk_*.opus"))
        if not segments:
            raise RuntimeError(
                f"FFMPEG가 세그먼트를 생성하지 못했습니다: {input_path}"
            )

        # ── 2단계: 각 세그먼트 → RTP 패킷화 ──
        ssrc = _generate_ssrc(str(input_path))
        chunk_count = 0
        total_packets = 0
        chunks: list[dict] = []

        for seg_idx, seg_path in enumerate(segments):
            opus_frames = extract_opus_frames(str(seg_path))
            if not opus_frames:
                logger.warning("빈 세그먼트 스킵: %s", seg_path.name)
                continue

            # RTP 패킷화
            packetizer = RTPPacketizer(
                payload_type=config.rtp_payload_type,
                clock_rate=config.rtp_clock_rate,
                frame_duration_ms=config.frame_duration,
                ssrc=ssrc,
                initial_seq=(seg_idx * 10000) & 0xFFFF,
                initial_timestamp=seg_idx * config.segment_time * config.rtp_clock_rate,
            )
            rtp_packets = packetizer.packetize_frames(opus_frames)

            # .rtp 바이너리 파일 저장
            rtp_file = file_output_dir / f"chunk_{seg_idx:04d}.rtp"
            _write_rtp_file(rtp_file, rtp_packets)

            # .opus 원본 세그먼트도 보존 (디버깅/폴백용)
            opus_out = file_output_dir / f"chunk_{seg_idx:04d}.opus"
            shutil.copy2(seg_path, opus_out)

            # 청크 길이는 담긴 프레임 수로 정확히 나온다.
            # 마지막 청크는 segment_time 보다 짧으므로 설정값을 쓰면 안 된다.
            chunk_duration_ms = len(rtp_packets) * config.frame_duration

            chunks.append(
                {
                    "index": seg_idx,
                    "file": rtp_file.name,
                    "duration_ms": chunk_duration_ms,
                    "size_bytes": rtp_file.stat().st_size,
                    "sha256": compute_sha256(rtp_file),
                    "rtp_packets": len(rtp_packets),
                }
            )

            chunk_count += 1
            total_packets += len(rtp_packets)

    # ── 3단계: 파형 ──
    #
    # 인코딩 전 **원본**에서 뽑는다. 파형은 곡의 성질이라 비트레이트별로 달라질
    # 이유가 없고, 청크를 다시 풀어 세는 것보다 싸다.
    #
    # 실패해도 청킹을 되돌리지 않는다. 파형이 없으면 화면이 예전처럼 기기에서
    # 직접 계산한다 — 느릴 뿐 재생에는 지장이 없다.
    try:
        waveform = compute_waveform(input_path, ffmpeg_threads=config.ffmpeg_threads)
    except (WaveformError, subprocess.SubprocessError) as e:
        logger.warning("파형 계산 실패 (등록은 계속한다): %s — %s", input_path.name, e)
        waveform = None

    # ── 4단계: 메타데이터 저장 ──
    elapsed = round(time.time() - start_time, 3)
    source_hash = compute_sha256(input_path)
    metadata = {
        "source_file": str(input_path),
        "file_stem": file_stem,
        "sha256": source_hash,
        "bitrate": config.bitrate,
        "segment_time_sec": config.segment_time,
        "frame_duration_ms": config.frame_duration,
        "chunk_count": chunk_count,
        "duration_ms": sum(c["duration_ms"] for c in chunks),
        "chunks": chunks,
        # 0~1 값 160칸. 계산에 실패했으면 null 이다 — 등록 단계가 그때는 건너뛴다.
        "waveform": waveform,
        "total_rtp_packets": total_packets,
        "ssrc": ssrc,
        "rtp_payload_type": config.rtp_payload_type,
        "rtp_clock_rate": config.rtp_clock_rate,
        "rtp_file_format_version": _RTP_FILE_VERSION,
        "processing_time_sec": elapsed,
    }

    meta_path = file_output_dir / "metadata.json"
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2, ensure_ascii=False)

    logger.info(
        "완료: %s → %d개 청크, %d개 RTP 패킷 (%.2fs)",
        input_path.name,
        chunk_count,
        total_packets,
        elapsed,
    )

    return metadata


# ─────────────────────────────────────────────────────────────
# 내부 함수
# ─────────────────────────────────────────────────────────────


def _run_ffmpeg_segment(
    input_path: Path,
    segment_pattern: Path,
    config: ChunkConfig,
) -> None:
    """FFMPEG를 실행하여 입력 파일을 Ogg/Opus 세그먼트로 분할합니다."""
    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "warning",
        "-threads",
        str(config.ffmpeg_threads),
        "-i",
        str(input_path),
        "-c:a",
        "libopus",
        "-b:a",
        config.bitrate,
        "-vbr",
        config.vbr,
        "-compression_level",
        str(config.compression_level),
        "-application",
        config.application,
        "-frame_duration",
        str(config.frame_duration),
        "-f",
        "segment",
        "-segment_time",
        str(config.segment_time),
        "-reset_timestamps",
        "1",
        str(segment_pattern),
    ]

    logger.debug("FFMPEG 명령어: %s", " ".join(cmd))

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=600,  # 10분 타임아웃
    )

    if result.returncode != 0:
        raise RuntimeError(
            f"FFMPEG 실패 (코드 {result.returncode}): {result.stderr}"
        )


def _write_rtp_file(path: Path, packets: list[bytes]) -> None:
    """RTP 패킷들을 커스텀 바이너리 포맷으로 저장합니다.

    스트리밍 서버는 이 파일을 읽어 각 패킷을
    UDP 소켓으로 그대로 전송할 수 있습니다.
    """
    with open(path, "wb") as f:
        # 파일 헤더
        f.write(_RTP_FILE_MAGIC)
        f.write(struct.pack("!H", _RTP_FILE_VERSION))
        f.write(struct.pack("!I", len(packets)))

        # 패킷 데이터
        for pkt in packets:
            f.write(struct.pack("!H", len(pkt)))
            f.write(pkt)


def _generate_ssrc(identifier: str) -> int:
    """파일 경로에서 결정적 SSRC를 생성합니다.

    동일 파일에 대해 항상 같은 SSRC를 반환하므로
    재처리 시에도 일관성이 유지됩니다.
    """
    h = hashlib.md5(identifier.encode()).digest()
    return struct.unpack("!I", h[:4])[0]
