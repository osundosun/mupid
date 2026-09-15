"""MySQL 내보내기(CSV/TSV) → `id_map.parquet`.

문자열 ID(`JAMENDO_1083659#2`)와 MySQL 숫자 ID 를 잇는 유일한 근거는 **MySQL 이 내보낸
표**뿐이다. 문자열을 해시하거나 순번을 붙여 숫자를 만들어내면 그럴듯하게 돌다가 실제 DB 와
어긋난 순간 엉뚱한 곡을 추천한다 — 이 프로젝트가 색인 불일치로 두 번 겪은 실패다.

    mysql ... -e "
      SELECT s.external_id AS source_section_uid,
             s.music_section_analysis_id AS music_section_id,
             m.source_track_id, m.music_id
      FROM music_section_analysis s JOIN music m ON m.music_id = s.music_id
    " --batch > sections.tsv

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.build_id_map sections.tsv

컬럼 이름만 맞으면 CSV·TSV 어느 쪽이든 읽는다. **구분자와 컬럼 순서는 보지 않는다** —
내보내는 쪽이 도구마다 다르기 때문이다.

곡 문자열 ID 컬럼이 아직 DB 에 없으면 `--derive-track-id` 로 구간 UID 의 `#` 앞을 잘라
쓴다. 임시 수단이고, 되짚을 근거가 DB 에 남지 않으므로 권하지 않는다.

쓰기 전에 **1:1 을 검증한다.** 위반이 있으면 파일을 만들지 않는다. 절반만 매핑된 표를
그대로 두면 곡이 소리 없이 사라진다.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

from ai_server.app import ids, paths

REQUIRED = ("source_section_uid", "music_section_id")
UID_MAX = 50        # erd.sql 의 VARCHAR(50). 넘으면 MySQL 이 잘라 저장한다


def read_table(path: Path) -> pd.DataFrame:
    """구분자를 추측해 읽는다. mysql --batch 는 TSV, 대부분의 도구는 CSV 다."""
    return pd.read_csv(path, sep=None, engine="python", dtype=str)


def build(df: pd.DataFrame, derive_track_id: bool = False) -> pd.DataFrame:
    missing = [c for c in REQUIRED if c not in df.columns]
    if missing:
        raise SystemExit(f"컬럼이 없다: {missing} (있는 것: {list(df.columns)})")

    out = df.copy()
    if "source_track_id" not in out.columns:
        if not derive_track_id:
            raise SystemExit(
                "source_track_id 컬럼이 없다. DB 에 곡 문자열 ID 자리를 만들거나, "
                "임시로 --derive-track-id 를 준다(되짚을 근거가 남지 않는다).")
        out["source_track_id"] = out.source_section_uid.str.split("#").str[0]
    if "music_id" not in out.columns:
        raise SystemExit("music_id 컬럼이 없다.")

    out = out[list(ids.COLS)]
    for c in ("music_id", "music_section_id"):
        out[c] = pd.to_numeric(out[c], errors="coerce").astype("Int64")
    return out


def check(out: pd.DataFrame) -> list[str]:
    """쓰기 전에 보는 것들. 여기서 걸러야 기동 뒤에 안 터진다."""
    problems = []
    long_uid = out[out.source_section_uid.str.len() > UID_MAX]
    if len(long_uid):
        # MySQL 이 조용히 잘라 저장했으면 매핑이 어긋난 채 1:1 검사를 통과할 수도 있다.
        problems.append(
            f"구간 UID 가 {UID_MAX}자를 넘는 행 {len(long_uid)}건 — 컬럼 길이를 늘려야 한다. "
            f"최댓값 {int(out.source_section_uid.str.len().max())}자, "
            f"예: {long_uid.source_section_uid.iloc[0]}")
    for c in ("music_id", "music_section_id"):
        n = int(out[c].isna().sum())
        if n:
            problems.append(f"{c} 가 정수가 아닌 행 {n}건")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("table", help="MySQL 에서 내보낸 CSV/TSV")
    ap.add_argument("--out", default=None, help="기본: <ARTIFACT_DIR>/.../id_map.parquet")
    ap.add_argument("--derive-track-id", action="store_true",
                    help="곡 문자열 ID 를 구간 UID 에서 잘라 쓴다 (임시 수단)")
    ap.add_argument("--force", action="store_true", help="경고가 있어도 쓴다")
    a = ap.parse_args()

    src = Path(a.table)
    if not src.exists():
        print(f"파일이 없다: {src}", file=sys.stderr)
        return 2

    out = build(read_table(src), a.derive_track_id)
    problems = check(out)
    for p in problems:
        print(f"경고: {p}", file=sys.stderr)
    if problems and not a.force:
        print("쓰지 않는다. 고친 뒤 다시 돌리거나 --force 를 준다.", file=sys.stderr)
        return 1

    dst = Path(a.out) if a.out else paths.id_map_path()
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(".parquet.tmp")
    out.to_parquet(tmp, index=False)

    # 서비스가 읽는 그대로 한 번 적재해 본다. 1:1 위반이면 여기서 막힌다.
    try:
        im = ids.IdMap(path=tmp, strict=True)
    except ids.MapIntegrityError as e:
        tmp.unlink(missing_ok=True)
        print(f"매핑표가 1:1 이 아니다. 쓰지 않는다.\n{e}", file=sys.stderr)
        return 1
    tmp.replace(dst)
    print(f"저장 {dst} · 곡 {len(im.track_to_num)} · 구간 {len(im.sec_to_num)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
