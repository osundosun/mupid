"""`python -m music_preprocessing` 진입점.

명령은 전부 `cli.py` 에 있다. 여기서는 콘솔 인코딩만 맞추고 넘긴다.
"""

from __future__ import annotations

import sys

from .cli import app


def main() -> None:
    _force_utf8_console()
    app()


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


if __name__ == "__main__":
    main()
