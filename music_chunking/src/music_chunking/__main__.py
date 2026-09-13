"""CLI 진입점 — 단일 파일 처리 & 배치 처리 모드 지원

Usage:
  # 단일 파일 처리
  music-chunking single -i song.mp3 -o ./output

  # 배치 처리 (crontab용)
  music-chunking batch -I ./input -O ./output

  # 설정 파일 사용
  music-chunking batch --config config.json

  # python -m으로 실행
  python -m music_chunking batch -I ./input -O ./output
"""

from __future__ import annotations

import argparse
import logging
import sys

from .batch import run_batch
from .chunker import process_file
from .config import ChunkConfig


def main() -> None:
    parser = argparse.ArgumentParser(
        description="음악 스트리밍을 위한 Opus 청크 + RTP 전처리 프로그램",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
사용 예시:
  music-chunking single -i song.mp3 -o ./output
  music-chunking batch -I ./input -O ./output -b 128k
  music-chunking batch --config config.json
        """,
    )

    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="상세 로그 출력 (DEBUG 레벨)",
    )

    subparsers = parser.add_subparsers(dest="command", help="실행 모드")

    # ── single 모드 ──
    single_p = subparsers.add_parser("single", help="단일 파일 처리")
    single_p.add_argument("-i", "--input", required=True, help="입력 파일 경로")
    single_p.add_argument(
        "-o", "--output", default="./output", help="출력 디렉토리 (기본: ./output)"
    )
    _add_common_args(single_p)

    # ── batch 모드 ──
    batch_p = subparsers.add_parser("batch", help="배치 처리 (crontab용)")
    batch_p.add_argument(
        "-I", "--input-dir", default="./input", help="입력 디렉토리 (기본: ./input)"
    )
    batch_p.add_argument(
        "-O", "--output-dir", default="./output", help="출력 디렉토리 (기본: ./output)"
    )
    batch_p.add_argument("--config", help="JSON 설정 파일 경로")
    batch_p.add_argument(
        "--processed-log",
        default="./processed.json",
        help="처리 완료 로그 (기본: ./processed.json)",
    )
    _add_common_args(batch_p)

    args = parser.parse_args()

    # 로깅 설정
    log_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    if not args.command:
        parser.print_help()
        sys.exit(1)

    if args.command == "single":
        _handle_single(args)
    elif args.command == "batch":
        _handle_batch(args)


# ─────────────────────────────────────────────────────────────
# 서브커맨드 핸들러
# ─────────────────────────────────────────────────────────────


def _handle_single(args: argparse.Namespace) -> None:
    """single 모드: 하나의 파일을 처리합니다."""
    config = ChunkConfig(
        bitrate=args.bitrate,
        segment_time=args.segment_time,
        frame_duration=args.frame_duration,
        max_workers=args.workers,
        ffmpeg_threads=args.ffmpeg_threads,
    )

    result = process_file(args.input, args.output, config)

    print(f"\n✅ 처리 완료: {args.input}")
    print(f"   청크 수      : {result['chunk_count']}")
    print(f"   RTP 패킷 수  : {result['total_rtp_packets']}")
    print(f"   처리 시간    : {result['processing_time_sec']}초")


def _handle_batch(args: argparse.Namespace) -> None:
    """batch 모드: 디렉토리의 모든 파일을 병렬 처리합니다."""
    if args.config:
        config = ChunkConfig.from_file(args.config)
    else:
        config = ChunkConfig(
            bitrate=args.bitrate,
            segment_time=args.segment_time,
            frame_duration=args.frame_duration,
            max_workers=args.workers,
            ffmpeg_threads=args.ffmpeg_threads,
            input_dir=args.input_dir,
            output_dir=args.output_dir,
            processed_log=args.processed_log,
        )

    result = run_batch(config)

    if result["status"] == "completed":
        print(f"\n✅ 배치 처리 완료:")
        print(f"   성공        : {result['processed']}개")
        print(f"     신규      : {result['new_files']}개")
        print(f"     변경      : {result['modified_files']}개")
        print(f"   실패        : {result['errors']}개")
        if result.get("stale_cleaned", 0) > 0:
            print(f"   정리(삭제)  : {result['stale_cleaned']}개")
        print(f"   총 소요 시간: {result['total_time_sec']}초")
        print(f"   평균 처리   : {result['avg_time_per_file_sec']}초/파일")
        if result["errors"] > 0:
            print("\n❌ 실패 목록:")
            for err in result["error_details"]:
                print(f"   - {err['file']}: {err['error']}")
            sys.exit(1)
    elif result["status"] == "up_to_date":
        stale = result.get("stale_cleaned", 0)
        msg = "✅ 처리할 새/변경 파일이 없습니다."
        if stale > 0:
            msg += f" (삭제된 파일 {stale}개 정리)"
        print(msg)
    else:
        print("⚠️  입력 디렉토리가 비어있습니다.")


# ─────────────────────────────────────────────────────────────
# 공통 인자
# ─────────────────────────────────────────────────────────────


def _add_common_args(parser: argparse.ArgumentParser) -> None:
    """single / batch 공통 CLI 인자를 추가합니다."""
    parser.add_argument(
        "-b", "--bitrate", default="128k", help="비트레이트 (기본: 128k)"
    )
    parser.add_argument(
        "--segment-time",
        type=int,
        default=6,
        help="세그먼트 길이 초 (기본: 6)",
    )
    parser.add_argument(
        "--frame-duration",
        type=int,
        default=20,
        help="Opus 프레임 길이 ms (기본: 20)",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=0,
        help="병렬 워커 수 (기본: CPU 코어 수)",
    )
    parser.add_argument(
        "--ffmpeg-threads",
        type=int,
        default=2,
        help="파일당 FFMPEG 스레드 수 (기본: 2)",
    )


if __name__ == "__main__":
    main()
