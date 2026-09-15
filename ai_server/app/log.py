"""진단 로그. **절대 예외를 올리지 않는다.**

평범한 `print()` 가 서비스를 죽인 적이 있다. 기동 로그에 한글과 `—` 가 들어 있는데
stdout 인코딩이 그 글자를 표현하지 못하면 `UnicodeEncodeError` 가 나고, 그 호출이
lifespan 안에 있어 **애플리케이션이 뜨지 못한다.** 무엇이 없는지 알려 주려던 줄이
서비스를 통째로 내린 셈이다.

컨테이너는 `LANG=C.UTF-8` 로 맞추지만 그것만 믿지 않는다. 로그를 어디로 흘려보내느냐는
운영 쪽 사정이고, 거기서 실패해도 추천은 계속돼야 한다.
"""
from __future__ import annotations

import sys

PREFIX = "[ai_server]"


def log(*parts: object) -> None:
    msg = " ".join(str(p) for p in parts)
    try:
        print(f"{PREFIX} {msg}", flush=True)
        return
    except Exception:
        pass
    # 콘솔 인코딩이 못 받는 글자가 있다. 바이트로 직접 내보낸다.
    try:
        buf = getattr(sys.stdout, "buffer", None)
        if buf is not None:
            buf.write(f"{PREFIX} {msg}\n".encode("utf-8", "replace"))
            buf.flush()
            return
    except Exception:
        pass
    # 그것도 막히면 알파벳만 남겨서라도 남긴다. 여기서 포기하되 예외는 올리지 않는다.
    try:
        print(f"{PREFIX} {msg.encode('ascii', 'backslashreplace').decode('ascii')}",
              flush=True)
    except Exception:
        pass
