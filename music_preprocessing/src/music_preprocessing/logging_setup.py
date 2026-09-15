"""로그 설정 — 터미널은 조용하게, 파일에는 전부 남긴다.

배치 하나가 2천 번 넘게 HTTP 를 부른다. 그걸 전부 터미널에 쏟으면 진행 상황도
실패 목록도 스크롤 위로 흘러가 버린다. 그래서 둘로 나눈다.

| 어디 | 무엇을 | 왜 |
| --- | --- | --- |
| 터미널 | 경고·오류만 (`--verbose` 면 전부) | 진행률 막대와 마지막 요약을 가린다 |
| 파일   | INFO 전부 (`--verbose` 면 DEBUG) | 끝난 뒤에 "무엇이 왜 실패했나" 를 읽는 곳 |

파일 쪽도 `RichHandler` 를 쓴다. 다만 **색은 끈다.** ANSI 이스케이프가 섞인
로그는 `less` 나 `grep` 으로 읽을 때 지저분하다. 색만 빼면 Rich 의 열 정렬
(시각 │ 레벨 │ 메시지 │ 위치)은 그대로 남아 오히려 읽기 좋다.
"""

from __future__ import annotations

import logging
from datetime import datetime
from pathlib import Path

from rich.console import Console
from rich.logging import RichHandler

# 파일 로그의 가로폭. 터미널 크기에 따라 줄바꿈 위치가 달라지면 같은 배치의
# 로그가 실행할 때마다 다르게 보인다. 고정해 둔다.
_FILE_WIDTH = 160


def default_log_path(log_dir: str | Path, command: str) -> Path:
    """`logs/catalog-20260915-1103.log` 같은 경로를 만든다.

    실행마다 파일을 새로 만든다. 한 파일에 계속 덧붙이면 어제 실패와 오늘 실패가
    섞여서, 크론이 남긴 로그에서 이번 회차만 떼어 보기 어렵다.
    """
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return Path(log_dir) / f"{command}-{stamp}.log"


def setup_logging(
    log_file: str | Path | None,
    verbose: bool = False,
    quiet_terminal: bool = True,
) -> Console:
    """로깅을 설정하고 화면에 쓸 콘솔을 돌려준다.

    Args:
        log_file: 로그를 남길 파일. None 이면 파일에 남기지 않는다
        verbose: 켜면 양쪽 모두 DEBUG 까지 남긴다
        quiet_terminal: 켜면 터미널에는 WARNING 이상만 보여 준다.
            크론처럼 화면을 볼 사람이 없을 때는 꺼서 전부 내보낸다

    Returns:
        진행률·표를 그릴 때 쓸 `Console`. **표준 출력**을 쓴다 — 로그는
        표준 오류로 가므로, 크론이 `> out.log 2> err.log` 로 갈라도
        결과 요약과 로그가 섞이지 않는다.
    """
    root = logging.getLogger()
    root.setLevel(logging.DEBUG if verbose else logging.INFO)

    # 같은 프로세스에서 두 번 부를 수 있다(테스트). 남은 핸들러를 치운다.
    for handler in list(root.handlers):
        root.removeHandler(handler)
        handler.close()

    terminal_level = logging.DEBUG if verbose else (
        logging.WARNING if quiet_terminal else logging.INFO
    )
    root.addHandler(
        RichHandler(
            console=Console(stderr=True),
            level=terminal_level,
            show_path=False,
            rich_tracebacks=True,
            log_time_format="%H:%M:%S",
        )
    )

    if log_file is not None:
        path = Path(log_file)
        path.parent.mkdir(parents=True, exist_ok=True)
        # 프로세스가 끝나면 파이썬이 닫는다. 배치는 한 번 돌고 끝나므로
        # 여기서 따로 붙들고 닫지 않는다.
        stream = open(path, "w", encoding="utf-8")
        root.addHandler(
            RichHandler(
                console=Console(
                    file=stream,
                    width=_FILE_WIDTH,
                    no_color=True,
                    force_terminal=False,
                    highlight=False,
                ),
                level=logging.DEBUG if verbose else logging.INFO,
                show_path=True,
                rich_tracebacks=True,
                log_time_format="%Y-%m-%d %H:%M:%S",
            )
        )

    return Console()
