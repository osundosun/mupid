"""배치 처리 — ProcessPoolExecutor 기반 멀티프로세싱

고사양 서버의 CPU 코어를 최대한 활용하기 위해
파일 단위로 병렬 처리합니다.

전체 처리량 = max_workers × 파일 + 파일당 ffmpeg_threads
→ 총 스레드 ≈ max_workers × ffmpeg_threads (CPU 코어 수에 맞게 조정)

변경 감지:
  - SHA-256 해시로 파일 내용 변경을 추적
  - 새 파일 / 내용이 바뀐 파일만 재처리
  - 삭제된 파일은 로그에서 정리 (stale entry 제거)
"""

from __future__ import annotations

import json
import logging
import shutil
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

from .chunker import process_file
from .config import ChunkConfig
from .hasher import compute_sha256

logger = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = frozenset(
    {".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".wma"}
)


def run_batch(config: ChunkConfig) -> dict:
    """배치 처리를 실행합니다.

    1. 입력 디렉토리에서 오디오 파일을 탐색
    2. SHA-256 해시 비교로 새 파일 / 변경 파일 식별
    3. ProcessPoolExecutor로 병렬 변환
    4. 삭제된 파일의 stale 로그 엔트리 정리

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

    # 처리 대상 파일 필터링 (해시 기반)
    all_files = _find_audio_files(input_dir)
    processed_log = _load_processed_log(config.processed_log)

    pending, change_reasons = _detect_changes(all_files, processed_log)

    # 삭제된 파일의 stale 엔트리 정리
    stale_count = _cleanup_stale_entries(
        processed_log, all_files, output_dir, config.processed_log
    )
    if stale_count > 0:
        logger.info("삭제된 파일 %d개의 로그 엔트리 정리 완료", stale_count)

    if not pending:
        logger.info("처리할 새/변경 파일이 없습니다.")
        return {
            "status": "up_to_date",
            "processed": 0,
            "total": len(all_files),
            "stale_cleaned": stale_count,
        }

    # 변경 사유 로그 출력
    new_count = sum(1 for r in change_reasons.values() if r == "new")
    modified_count = sum(1 for r in change_reasons.values() if r == "modified")
    logger.info(
        "배치 시작: %d개 파일 대기 (신규 %d개, 변경 %d개) | "
        "전체 %d개 | 워커 %d개",
        len(pending),
        new_count,
        modified_count,
        len(all_files),
        config.max_workers,
    )

    batch_start = time.time()
    results: list[dict] = []
    errors: list[dict] = []

    # ── SHA-256 해시 사전 계산 (병렬 처리 전) ──
    # 각 워커 프로세스에서 해시를 다시 계산하지 않도록 미리 준비
    file_hashes: dict[str, str] = {}
    for f in pending:
        file_hashes[str(f)] = compute_sha256(f)

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
            reason = change_reasons.get(str(file_path), "unknown")
            try:
                result = future.result()
                results.append(result)

                # 처리 완료 기록 (SHA-256 포함)
                processed_log[str(file_path)] = {
                    "completed_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "sha256": file_hashes[str(file_path)],
                    "file_size_bytes": file_path.stat().st_size,
                    "change_reason": reason,
                    "chunks": result["chunk_count"],
                    "rtp_packets": result["total_rtp_packets"],
                    "processing_time_sec": result["processing_time_sec"],
                }
                _save_processed_log(config.processed_log, processed_log)

                reason_tag = "신규" if reason == "new" else "변경"
                logger.info(
                    "[%d/%d] %s 완료 [%s] (%.2fs)",
                    len(results),
                    len(pending),
                    file_path.name,
                    reason_tag,
                    result["processing_time_sec"],
                )

            except Exception as e:
                logger.error("실패: %s - %s", file_path.name, e)
                errors.append({"file": str(file_path), "error": str(e)})

    elapsed = time.time() - batch_start

    summary = {
        "status": "completed",
        "processed": len(results),
        "new_files": new_count,
        "modified_files": modified_count,
        "errors": len(errors),
        "stale_cleaned": stale_count,
        "total_time_sec": round(elapsed, 2),
        "avg_time_per_file_sec": (
            round(elapsed / len(results), 2) if results else 0
        ),
        "error_details": errors,
    }

    logger.info(
        "배치 완료: %d개 성공 (신규 %d, 변경 %d), %d개 실패 (%.1f초)",
        len(results),
        new_count,
        modified_count,
        len(errors),
        elapsed,
    )

    return summary


# ─────────────────────────────────────────────────────────────
# 변경 감지
# ─────────────────────────────────────────────────────────────


def _detect_changes(
    all_files: list[Path],
    processed_log: dict,
) -> tuple[list[Path], dict[str, str]]:
    """SHA-256 해시로 새 파일 및 변경된 파일을 감지합니다.

    Args:
        all_files: 입력 디렉토리의 모든 오디오 파일
        processed_log: 이전 처리 기록

    Returns:
        (처리할 파일 리스트, {파일경로: 변경사유} 매핑)
        변경사유: "new" (신규) 또는 "modified" (내용 변경)
    """
    pending: list[Path] = []
    change_reasons: dict[str, str] = {}

    for f in all_files:
        file_key = str(f)
        prev = processed_log.get(file_key)

        if prev is None:
            # 신규 파일
            pending.append(f)
            change_reasons[file_key] = "new"
            continue

        prev_hash = prev.get("sha256")
        if prev_hash is None:
            # 기존 로그에 해시 없음 (마이그레이션) → 재처리
            logger.info("해시 없는 기존 항목, 재처리: %s", f.name)
            pending.append(f)
            change_reasons[file_key] = "modified"
            continue

        # SHA-256 비교
        current_hash = compute_sha256(f)
        if current_hash != prev_hash:
            logger.info(
                "내용 변경 감지: %s (이전: %s… → 현재: %s…)",
                f.name,
                prev_hash[:12],
                current_hash[:12],
            )
            pending.append(f)
            change_reasons[file_key] = "modified"

    return pending, change_reasons


def _cleanup_stale_entries(
    processed_log: dict,
    current_files: list[Path],
    output_dir: Path,
    log_path: str,
) -> int:
    """입력 디렉토리에서 삭제된 파일의 로그 엔트리를 정리합니다.

    삭제된 파일의 출력 디렉토리도 함께 제거합니다.

    Returns:
        정리된 stale 엔트리 수
    """
    current_keys = {str(f) for f in current_files}
    stale_keys = [k for k in processed_log if k not in current_keys]

    for key in stale_keys:
        entry = processed_log.pop(key)
        file_stem = entry.get("file_stem") or Path(key).stem

        # 출력 디렉토리 정리
        stale_output = output_dir / file_stem
        if stale_output.exists():
            shutil.rmtree(stale_output, ignore_errors=True)
            logger.info("삭제된 파일의 출력 정리: %s", stale_output)

    if stale_keys:
        _save_processed_log(log_path, processed_log)

    return len(stale_keys)


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
