"""배치 처리 — ProcessPoolExecutor 기반 멀티프로세싱

고사양 서버의 CPU 코어를 최대한 활용하기 위해
파일 단위로 병렬 처리합니다.

전체 처리량 = max_workers × 파일 + 파일당 ffmpeg_threads
→ 총 스레드 ≈ max_workers × ffmpeg_threads (CPU 코어 수에 맞게 조정)
"""

from __future__ import annotations

import json
import logging
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

from .chunker import process_file
from .config import ChunkConfig

logger = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = frozenset(
    {".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".wma"}
)


def run_batch(config: ChunkConfig) -> dict:
    """배치 처리를 실행합니다.

    1. 입력 디렉토리에서 오디오 파일을 탐색
    2. 이미 처리된 파일은 건너뜀 (processed.json 기반)
    3. ProcessPoolExecutor로 병렬 변환

    Args:
        config: 변환 설정

    Returns:
        배치 결과 요약 딕셔너리
    """
    input_dir = Path(config.input_dir)
    output_dir = Path(config.output_dir)

    # 디렉토리 확인/생성
    if not input_dir.exists():
        logger.error("입력 디렉토리가 존재하지 않습니다: %s", input_dir)
        input_dir.mkdir(parents=True, exist_ok=True)
        logger.info("입력 디렉토리 생성: %s", input_dir)
        return {"status": "no_input", "processed": 0}

    output_dir.mkdir(parents=True, exist_ok=True)

    # 처리 대상 파일 필터링
    all_files = _find_audio_files(input_dir)
    processed_log = _load_processed_log(config.processed_log)
    pending = [f for f in all_files if str(f) not in processed_log]

    if not pending:
        logger.info("처리할 새 파일이 없습니다.")
        return {
            "status": "up_to_date",
            "processed": 0,
            "total": len(all_files),
        }

    logger.info(
        "배치 시작: %d개 파일 대기 (전체 %d개, 완료 %d개) | 워커 %d개",
        len(pending),
        len(all_files),
        len(processed_log),
        config.max_workers,
    )

    batch_start = time.time()
    results: list[dict] = []
    errors: list[dict] = []

    # ── 멀티프로세싱 병렬 처리 ──
    with ProcessPoolExecutor(max_workers=config.max_workers) as executor:
        future_to_file = {
            executor.submit(
                process_file,
                str(f),
                str(output_dir),
                config,
            ): f
            for f in pending
        }

        for future in as_completed(future_to_file):
            file_path = future_to_file[future]
            try:
                result = future.result()
                results.append(result)

                # 처리 완료 기록 (파일별로 즉시 저장 → 중단 시 재개 가능)
                processed_log[str(file_path)] = {
                    "completed_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "chunks": result["chunk_count"],
                    "rtp_packets": result["total_rtp_packets"],
                    "processing_time_sec": result["processing_time_sec"],
                }
                _save_processed_log(config.processed_log, processed_log)

                logger.info(
                    "[%d/%d] %s 완료 (%.2fs)",
                    len(results),
                    len(pending),
                    file_path.name,
                    result["processing_time_sec"],
                )

            except Exception as e:
                logger.error("실패: %s - %s", file_path.name, e)
                errors.append({"file": str(file_path), "error": str(e)})

    elapsed = time.time() - batch_start

    summary = {
        "status": "completed",
        "processed": len(results),
        "errors": len(errors),
        "total_time_sec": round(elapsed, 2),
        "avg_time_per_file_sec": (
            round(elapsed / len(results), 2) if results else 0
        ),
        "error_details": errors,
    }

    logger.info(
        "배치 완료: %d개 성공, %d개 실패 (%.1f초)",
        len(results),
        len(errors),
        elapsed,
    )

    return summary


# ─────────────────────────────────────────────────────────────
# 유틸리티
# ─────────────────────────────────────────────────────────────


def _find_audio_files(directory: Path) -> list[Path]:
    """지원하는 오디오 파일을 재귀적으로 탐색합니다."""
    files: list[Path] = []
    for ext in SUPPORTED_EXTENSIONS:
        files.extend(directory.rglob(f"*{ext}"))
    return sorted(files)


def _load_processed_log(log_path: str) -> dict:
    """처리 완료 로그를 로드합니다."""
    path = Path(log_path)
    if path.exists():
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def _save_processed_log(log_path: str, data: dict) -> None:
    """처리 완료 로그를 저장합니다.

    각 파일 처리 완료 시마다 호출되어,
    배치 중단 시에도 진행 상태를 유지합니다.
    """
    path = Path(log_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
