"""행동 로그 → 라벨 → 선호 구간. **배치로 돌리는 진입점.**

`labels_service.parquet` 이 없으면 오류 없이 모든 사용자가 콜드스타트가 된다. 서버는
정상으로 보이고 개인화만 없다. 그래서 이 작업은 반드시 주기적으로 돌아야 한다.

    MUPID_AI_ARTIFACT_DIR=/경로/artifacts python -m ai_server.tools.run_labeler

HTTP 로 열지 배치로 돌릴지는 아직 정해지지 않았다(S15P21A204-217). 어느 쪽으로 가든
**돌리는 방법 하나는 있어야** 해서 CLI 부터 둔다. cron 이 그대로 부를 수 있고, 나중에
내부 API 를 열더라도 같은 함수를 부르면 된다.

라벨은 한 사용자만 보고 만들지 않는다 — 같은 구간을 들은 전체 사용자 평균, 위치 편향,
표본이 적을 때의 보정이 함께 들어간다. 그래서 **요청마다 한 사람씩 계산하는 방식으로
바꾸면 결과가 달라진다.** 전체를 한 번에 돌리는 이 경로가 기준이다.

종료 코드: 0 성공 · 1 실패. cron 이 실패를 알아차릴 수 있어야 한다.
"""
from __future__ import annotations

import argparse
import json
import sys
import time


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--user", default=None,
                    help="한 사용자만. 비우면 로그에 있는 전체 (권장)")
    ap.add_argument("--json", action="store_true", help="결과를 JSON 한 줄로 출력")
    a = ap.parse_args()

    from ai_server.app import main as appmain
    from ai_server.app import recommender

    # 카탈로그가 있어야 구간 메타를 붙일 수 있다. 서비스와 같은 적재 경로를 쓴다.
    integrity = appmain.check_integrity()
    if not integrity["ok"]:
        print("산출물 무결성 실패 — " + "; ".join(integrity["errors"]), file=sys.stderr)
        return 1
    recommender.CAT = recommender.Catalog()

    t0 = time.time()
    try:
        r = recommender.learn(recommender.LearnReq(user_ref=a.user))
    except Exception as e:
        print(f"라벨 생성 실패: {type(e).__name__}: {e}", file=sys.stderr)
        return 1
    r["elapsed_sec"] = round(time.time() - t0, 2)

    if a.json:
        print(json.dumps(r, ensure_ascii=False))
    else:
        print(f"사용자 {len(r['users'])}명 · 라벨 {r['labels']}건 · "
              f"선호 구간 {r['preferences_saved']}건 · {r['elapsed_sec']}초")
        print(f"저장 {r['path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
