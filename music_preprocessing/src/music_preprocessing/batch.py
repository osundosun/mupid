"""배치 처리 — ProcessPoolExecutor 기반 멀티프로세싱

고사양 서버의 CPU 코어를 최대한 활용하기 위해
파일 단위로 병렬 처리합니다.

전체 처리량 = max_workers × 파일 + 파일당 ffmpeg_threads
→ 총 스레드 ≈ max_workers × ffmpeg_threads (CPU 코어 수에 맞게 조정)

변경 감지:
  - SHA-256 해시로 파일 내용 변경을 추적
  - 새 파일 / 내용이 바뀐 파일만 재처리
  - 삭제된 파일은 로그에서 정리 (stale entry 제거)

등록:
  - config.api_base_url 이 설정돼 있으면 청킹 직후 Mupid API 에 등록한다
  - 등록만 실패한 항목은 다음 실행에서 **재청킹 없이** 등록만 다시 시도한다
"""

from __future__ import annotations

import json
import logging
import shutil
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

from .api_client import MupidClient
from .chunker import process_file
from .config import ChunkConfig
from .hasher import compute_sha256
from .orphans import scan_streams
from .registrar import Registrar, Registry, SidecarMissingError

logger = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = frozenset(
    {".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".wma"}
)


def run_batch(
    config: ChunkConfig,
    orphan_check: bool = True,
    verify_chunks: bool = False,
) -> dict:
    """배치 처리를 실행합니다.

    1. 입력 디렉토리에서 오디오 파일을 탐색
    2. SHA-256 해시 비교로 새 파일 / 변경 파일 식별
    3. ProcessPoolExecutor로 병렬 변환
    4. 삭제된 파일의 stale 로그 엔트리 정리
    5. 서버에 쓰이지 않는 스트림·청크가 남아 있는지 훑어 **경고만** 남긴다

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

    # 등록기 준비 (설정이 없으면 None → 청킹만 한다)
    registrar = build_registrar(config)

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
        # 청킹할 것은 없어도, 지난번에 등록만 실패한 항목이 남아 있을 수 있다.
        registration_errors: list[dict] = []
        retried = _retry_registrations(
            registrar, processed_log, output_dir, config, registration_errors
        )
        logger.info(
            "처리할 새/변경 파일이 없습니다. (등록 재시도 %d건, 실패 %d건)",
            retried,
            len(registration_errors),
        )
        return {
            "status": "up_to_date",
            "processed": 0,
            "total": len(all_files),
            "stale_cleaned": stale_count,
            "registration_retried": retried,
            "registration_errors": len(registration_errors),
            "registration_error_details": registration_errors,
            "orphans": _scan_orphans(config, registrar, orphan_check, verify_chunks),
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
    registration_errors: list[dict] = []

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
                entry = {
                    "completed_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "sha256": file_hashes[str(file_path)],
                    "file_size_bytes": file_path.stat().st_size,
                    "change_reason": reason,
                    "file_stem": result["file_stem"],
                    "chunks": result["chunk_count"],
                    "rtp_packets": result["total_rtp_packets"],
                    "processing_time_sec": result["processing_time_sec"],
                }
                processed_log[str(file_path)] = entry

                # 등록은 실패해도 청킹 결과를 버리지 않는다. 다음 실행에서 등록만 다시 한다.
                _register_entry(
                    registrar, file_path, result, output_dir, entry, registration_errors
                )
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

    # 이전 실행에서 청킹만 끝나고 등록이 실패한 항목을 다시 시도한다.
    retried = _retry_registrations(
        registrar, processed_log, output_dir, config, registration_errors
    )

    elapsed = time.time() - batch_start

    summary = {
        "status": "completed",
        "processed": len(results),
        "new_files": new_count,
        "modified_files": modified_count,
        "errors": len(errors),
        "registered": sum(
            1 for e in processed_log.values() if e.get("registered")
        ),
        "registration_retried": retried,
        "registration_errors": len(registration_errors),
        "stale_cleaned": stale_count,
        "total_time_sec": round(elapsed, 2),
        "avg_time_per_file_sec": (
            round(elapsed / len(results), 2) if results else 0
        ),
        "error_details": errors,
        "registration_error_details": registration_errors,
        "orphans": _scan_orphans(config, registrar, orphan_check, verify_chunks),
    }

    logger.info(
        "배치 완료: %d개 성공 (신규 %d, 변경 %d), %d개 실패, 등록 실패 %d개 (%.1f초)",
        len(results),
        new_count,
        modified_count,
        len(errors),
        len(registration_errors),
        elapsed,
    )

    return summary


# ─────────────────────────────────────────────────────────────
# 등록
# ─────────────────────────────────────────────────────────────


def _scan_orphans(
    config: ChunkConfig,
    registrar: Registrar | None,
    enabled: bool,
    verify_chunks: bool,
) -> dict | None:
    """서버에 쓰이지 않는 스트림·청크가 남아 있는지 훑는다.

    **읽기만 한다.** 찾은 것은 경고로 남기고 아무것도 지우지 않는다. 점검이
    깨져도 배치 결과를 더럽히지 않는다 — 청킹과 등록은 이미 끝난 뒤다.
    """
    if not enabled or registrar is None:
        return None
    try:
        return scan_streams(
            registrar.client, registrar.registry, verify_chunks=verify_chunks
        )
    except Exception as e:  # noqa: BLE001 — 점검 실패가 배치 실패는 아니다
        logger.warning("스트림 점검을 마치지 못했습니다: %s", e)
        return None


def build_registrar(config: ChunkConfig) -> Registrar | None:
    """설정에 API 정보가 있으면 등록기를 만든다. 없으면 None (청킹만 한다).

    리프레시 토큰 파일이 없으면 바로 실패시킨다. 조용히 등록을 건너뛰면
    크론이 몇 시간을 돌고 나서야 아무것도 등록되지 않았음을 알게 된다.
    """
    if not config.register_enabled:
        logger.info("api_base_url 이 비어 있어 등록 단계를 건너뜁니다.")
        return None

    token_file = Path(config.api_refresh_token_file)
    if not token_file.exists():
        raise RuntimeError(
            f"리프레시 토큰 파일이 없습니다: {token_file}. "
            "관리자 계정으로 POST /api/v1/user/system 을 호출해 ROLE_SYSTEM 계정을 만들고, "
            "응답의 refresh_token 을 이 파일에 저장하세요."
        )

    refresh_token = token_file.read_text(encoding="utf-8").strip()
    if not refresh_token:
        raise RuntimeError(f"리프레시 토큰 파일이 비어 있습니다: {token_file}")

    client = MupidClient(
        base_url=config.api_base_url,
        refresh_token=refresh_token,
        token_file=token_file,
        timeout=config.api_timeout,
        max_retries=config.api_max_retries,
    )
    logger.info("등록 대상 API: %s", config.api_base_url)
    return Registrar(client, Registry(config.api_registry_log))


def _register_entry(
    registrar: Registrar | None,
    audio_path: Path,
    metadata: dict,
    output_dir: Path,
    entry: dict,
    errors: list[dict],
) -> None:
    """청킹 결과 하나를 등록하고 결과를 processed_log 항목에 적는다."""
    if registrar is None:
        return

    try:
        entry["registered"] = registrar.register(
            audio_path, metadata, output_dir / metadata["file_stem"]
        )
        entry.pop("registration_error", None)
        entry.pop("registration_skipped", None)
    except SidecarMissingError as e:
        # 사람이 파일을 놓기 전까지는 재시도해도 같다. 크론 종료 코드를 더럽히지 않는다.
        entry["registered"] = None
        entry["registration_skipped"] = str(e)
        entry.pop("registration_error", None)
        logger.warning("등록 건너뜀: %s - %s", audio_path.name, e)
    except Exception as e:
        entry["registered"] = None
        entry["registration_error"] = str(e)
        entry.pop("registration_skipped", None)
        errors.append({"file": str(audio_path), "error": str(e)})
        logger.error("등록 실패: %s - %s", audio_path.name, e)


def _retry_registrations(
    registrar: Registrar | None,
    processed_log: dict,
    output_dir: Path,
    config: ChunkConfig,
    errors: list[dict],
) -> int:
    """청킹은 끝났는데 등록이 안 된 항목을 재청킹 없이 다시 등록한다.

    Returns:
        다시 시도한 항목 수 (성공·실패 합계)
    """
    if registrar is None:
        return 0

    targets = [
        (Path(key), entry)
        for key, entry in processed_log.items()
        if not entry.get("registered")
    ]
    if not targets:
        return 0

    # 사이드카가 나중에 놓일 수 있으므로 보류된 항목도 매번 다시 본다.
    # 여전히 없으면 실패가 아니라 경고로만 남는다.
    held = sum(1 for _, entry in targets if entry.get("registration_skipped"))
    logger.info(
        "등록 재시도 대상 %d건 (이전에 사이드카가 없어 보류된 %d건 포함)",
        len(targets),
        held,
    )

    for audio_path, entry in targets:
        metadata = _load_chunk_metadata(output_dir, entry, audio_path)
        if metadata is None:
            entry["registration_error"] = (
                "metadata.json 을 찾을 수 없어 등록을 건너뜁니다. 원본을 다시 청킹하세요."
            )
            errors.append({"file": str(audio_path), "error": entry["registration_error"]})
            continue
        _register_entry(registrar, audio_path, metadata, output_dir, entry, errors)

    _save_processed_log(config.processed_log, processed_log)
    return len(targets)


def _load_chunk_metadata(
    output_dir: Path, entry: dict, audio_path: Path
) -> dict | None:
    """출력 디렉터리에 남아 있는 metadata.json 을 읽는다."""
    file_stem = entry.get("file_stem") or audio_path.stem
    meta_path = output_dir / file_stem / "metadata.json"
    if not meta_path.exists():
        return None
    with open(meta_path, encoding="utf-8") as f:
        return json.load(f)


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
