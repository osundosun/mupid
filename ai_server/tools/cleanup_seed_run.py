"""시드 회차가 남긴 것을 지운다.

    python -m ai_server.tools.cleanup_seed_run --run-id E2E_ab12cd34ef56
    python -m ai_server.tools.cleanup_seed_run --manifest /tmp/e2e-xxx/.../seed_manifest.json

시험이 중간에 죽어 `finally` 가 돌지 못했을 때 쓰는 손잡이다. 여러 번 실행해도 안전하고,
지울 것이 없으면 아무 일도 하지 않는다. 종료 코드는 잔여가 0 일 때만 0 이다 — 정리가
덜 끝난 것을 성공으로 보고하면 다음 회차가 남의 데이터 위에서 돈다.

실제 일은 `seed_e2e` 가 한다. 이 파일은 이름을 드러내기 위한 얇은 진입점이다.
"""
from __future__ import annotations

import sys

from ai_server.tools.seed_e2e import main

if __name__ == "__main__":
    sys.exit(main())
