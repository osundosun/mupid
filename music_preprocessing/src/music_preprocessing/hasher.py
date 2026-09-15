"""파일 해시 유틸리티 — SHA-256 기반 변경 감지

대용량 오디오 파일을 메모리에 전부 올리지 않고
청크 단위로 읽어 해시를 계산합니다.

멀티프로세싱 환경에서 안전하게 사용할 수 있습니다.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

# 읽기 버퍼 크기 (1MB) — RAM 사용량과 I/O 효율 사이의 균형
_READ_CHUNK_SIZE = 1024 * 1024


def compute_sha256(file_path: str | Path) -> str:
    """파일의 SHA-256 해시를 계산합니다.

    대용량 파일도 일정한 메모리(~1MB)만 사용합니다.

    Args:
        file_path: 해시를 계산할 파일 경로

    Returns:
        SHA-256 해시값 (64자 hex 문자열)
    """
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(_READ_CHUNK_SIZE)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()
