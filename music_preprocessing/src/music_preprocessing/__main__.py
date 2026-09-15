"""CLI 진입점 — 청킹(single·batch)과 카탈로그 등록(catalog·covers)

Usage:
  # 단일 파일 청킹
  music-preprocessing single -i song.mp3 -o ./output

  # 배치 청킹 (crontab용)
  music-preprocessing batch -I ./input -O ./output

  # 곡별 메타데이터·커버를 API 에 등록
  music-preprocessing catalog --config config.json

  # 커버만 다시 올리기
  music-preprocessing covers --config config.json

  # 설정 파일 사용
  music-preprocessing batch --config config.json

  # python -m으로 실행
  python -m music_preprocessing batch -I ./input -O ./output
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from .api_client import MupidClient
from .batch import build_registrar, run_batch
from .catalog import CatalogError, CatalogRegistrar, load_metadata
from .chunker import process_file
from .config import ChunkConfig
from .registrar import Registry


def main() -> None:
    parser = argparse.ArgumentParser(
        description="음악 스트리밍을 위한 Opus 청크 + RTP 전처리 프로그램",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
사용 예시:
  music-preprocessing single -i song.mp3 -o ./output
  music-preprocessing batch -I ./input -O ./output -b 128k
  music-preprocessing batch --config config.json
  music-preprocessing catalog --config config.json --dry-run
  music-preprocessing covers --config config.json
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

    # ── catalog 모드 ──
    catalog_p = subparsers.add_parser(
        "catalog", help="곡별 메타데이터·커버를 API 에 등록"
    )
    _add_catalog_args(catalog_p)

    # ── covers 모드 ──
    covers_p = subparsers.add_parser("covers", help="커버 이미지만 다시 업로드")
    _add_catalog_args(covers_p)
    covers_p.add_argument(
        "--force",
        action="store_true",
        help="내용이 그대로여도 다시 올린다",
    )

    args = parser.parse_args()

    _force_utf8_console()

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
    elif args.command == "catalog":
        _handle_catalog(args)
    elif args.command == "covers":
        _handle_covers(args)


def _force_utf8_console() -> None:
    """표준 출력을 UTF-8 로 맞춘다.

    Windows 기본 콘솔은 cp949 라서 이모지나 일부 한글을 찍는 순간
    UnicodeEncodeError 로 프로세스가 죽는다. 크론으로 돌 때도 같은 문제가 난다.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            pass


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
        # 설정 파일보다 명령줄에 직접 준 값이 우선한다.
        if args.api_base_url:
            config.api_base_url = args.api_base_url
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
            api_base_url=args.api_base_url,
            api_refresh_token_file=args.api_refresh_token_file,
            api_registry_log=args.api_registry_log,
        )

    result = run_batch(config)

    if result["status"] == "completed":
        print(f"\n✅ 배치 처리 완료:")
        print(f"   성공        : {result['processed']}개")
        print(f"     신규      : {result['new_files']}개")
        print(f"     변경      : {result['modified_files']}개")
        print(f"   실패        : {result['errors']}개")
        if "registered" in result:
            print(f"   등록 완료   : {result['registered']}개")
            print(f"   등록 실패   : {result['registration_errors']}개")
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
        if result.get("registration_retried", 0) > 0:
            print(f"   등록 재시도 : {result['registration_retried']}개")
            print(f"   등록 실패   : {result['registration_errors']}개")
        if result.get("registration_errors", 0) > 0:
            for err in result["registration_error_details"]:
                print(f"   - {err['file']}: {err['error']}")
            sys.exit(1)
    else:
        print("⚠️  입력 디렉토리가 비어있습니다.")


# ─────────────────────────────────────────────────────────────
# 카탈로그 (`catalog` · `covers`)
# ─────────────────────────────────────────────────────────────


def _handle_catalog(args: argparse.Namespace) -> None:
    """catalog 모드: 곡별 메타데이터·커버를 API 에 등록합니다."""
    config, registrar, tracks = _build_catalog(args)

    print()
    print(
        f"대상: 곡 {len(tracks)}개"
        f" | 메타데이터 {config.metadata_dir}"
        f" | 커버 {config.covers_dir}"
    )
    if args.dry_run:
        print("⚠️  dry-run — 아무것도 올리지 않고 무엇이 만들어질지만 셉니다.")

    result = registrar.run(tracks)

    print()
    print(f"✅ 카탈로그 등록 {'미리보기' if args.dry_run else '완료'}:")
    print(f"   아티스트    : {result['artists_created']} / {result['artists_total']}")
    print(f"   앨범        : {result['albums_created']} / {result['albums_total']}")
    print(f"   곡          : {result['tracks_created']} / {result['tracks_total']}")
    print(f"   커버 업로드 : {result['covers_uploaded']}개")
    if result["covers_missing"]:
        print(f"   커버 없음   : {result['covers_missing']}개 (앨범 커버로 대체됨)")
    _report_errors(result)


def _handle_covers(args: argparse.Namespace) -> None:
    """covers 모드: 이미 등록된 대상의 커버만 다시 올립니다."""
    config, registrar, tracks = _build_catalog(args)

    print()
    print(f"대상: 곡 {len(tracks)}개 | 커버 {config.covers_dir}")
    if args.dry_run:
        print("⚠️  dry-run — 아무것도 올리지 않고 무엇이 바뀔지만 셉니다.")

    result = registrar.refresh_covers(tracks, force=args.force)

    print()
    print(f"✅ 커버 갱신 {'미리보기' if args.dry_run else '완료'}:")
    print(f"   아티스트    : {result['artists_refreshed']}개")
    print(f"   앨범        : {result['albums_refreshed']}개")
    print(f"   곡          : {result['tracks_refreshed']}개")
    print(f"   업로드      : {result['covers_uploaded']}개")
    if result["covers_missing"]:
        print(f"   커버 없음   : {result['covers_missing']}개")
    if result["skipped_unregistered"]:
        print(
            f"   미등록 건너뜀: {result['skipped_unregistered']}개"
            " (`catalog` 를 먼저 돌리세요)"
        )
    _report_errors(result)


def _build_catalog(
    args: argparse.Namespace,
) -> tuple[ChunkConfig, CatalogRegistrar, list]:
    """설정을 읽고 등록기와 메타데이터를 준비합니다.

    여기서 실패하는 것은 경로·설정이 틀린 경우다. 역추적을 통째로 뱉으면 크론
    로그에서 사유를 찾기 어려워, 한 줄로 줄여 내보낸다.
    """
    try:
        return _build_catalog_unsafe(args)
    except (CatalogError, OSError) as e:
        print(f"❌ {e}", file=sys.stderr)
        sys.exit(1)


def _build_catalog_unsafe(
    args: argparse.Namespace,
) -> tuple[ChunkConfig, CatalogRegistrar, list]:
    config = ChunkConfig.from_file(args.config) if args.config else ChunkConfig()

    # 설정 파일보다 명령줄에 직접 준 값이 우선한다.
    for name in ("metadata_dir", "covers_dir", "api_base_url"):
        given = getattr(args, name, None)
        if given:
            setattr(config, name, given)

    # dry-run 은 서버에 붙지 않는다. 토큰이 없어도 무엇이 만들어질지 볼 수 있다.
    client = None if args.dry_run else _require_client(config)
    registrar = CatalogRegistrar(
        client=client,
        registry=Registry(config.api_registry_log),
        covers_dir=Path(config.covers_dir),
        dry_run=args.dry_run,
    )
    return config, registrar, load_metadata(Path(config.metadata_dir))


def _require_client(config: ChunkConfig) -> MupidClient:
    """API 클라이언트를 만든다. 등록할 서버가 없으면 여기서 멈춘다."""
    registrar = build_registrar(config)
    if registrar is None:
        print(
            "❌ api_base_url 이 비어 있습니다. 설정 파일이나 --api-base-url 로"
            " 등록할 서버를 지정하세요."
            " (무엇이 만들어질지만 보려면 --dry-run)",
            file=sys.stderr,
        )
        sys.exit(1)
    return registrar.client


def _report_errors(result: dict) -> None:
    """실패 목록을 찍고 종료 코드를 맞춘다.

    크론이 읽는 것은 종료 코드다. 한 건이라도 실패했으면 0 으로 끝내지 않는다.
    """
    errors = result.get("errors") or []
    if not errors:
        return
    print()
    print(f"❌ 실패 {len(errors)}건:")
    for err in errors[:20]:
        print(f"   - {err['key']}: {err['error']}")
    if len(errors) > 20:
        print(f"   … 그 밖에 {len(errors) - 20}건")
    sys.exit(1)


def _add_catalog_args(parser: argparse.ArgumentParser) -> None:
    """catalog / covers 공통 CLI 인자를 추가합니다."""
    parser.add_argument("--config", help="JSON 설정 파일 경로")
    parser.add_argument(
        "--metadata-dir",
        default="",
        help="곡별 메타데이터 JSON 디렉터리 (기본: 설정의 metadata_dir)",
    )
    parser.add_argument(
        "--covers-dir",
        default="",
        help="곡별 커버 이미지 디렉터리 (기본: 설정의 covers_dir)",
    )
    parser.add_argument(
        "--api-base-url",
        default="",
        help="Mupid API 루트 (예: http://localhost:9900)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="서버에 붙지 않고 무엇이 만들어질지만 센다",
    )


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
    parser.add_argument(
        "--api-base-url",
        default="",
        help="Mupid API 루트 (예: http://localhost:9900). 비우면 등록하지 않음",
    )
    parser.add_argument(
        "--api-refresh-token-file",
        default="./refresh_token.txt",
        help="ROLE_SYSTEM 계정 리프레시 토큰 파일 (기본: ./refresh_token.txt)",
    )
    parser.add_argument(
        "--api-registry-log",
        default="./registered.json",
        help="등록 결과 기록 파일 (기본: ./registered.json)",
    )


if __name__ == "__main__":
    main()
