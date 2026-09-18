"""MySQL 내보내기(CSV/TSV) → `id_map.parquet`.

문자열 ID(`JAMENDO_1083659#2`)와 MySQL 숫자 ID 를 잇는 유일한 근거는 **MySQL 이 내보낸
표**뿐이다. 문자열을 해시하거나 순번을 붙여 숫자를 만들어내면 그럴듯하게 돌다가 실제 DB 와
어긋난 순간 엉뚱한 곡을 추천한다 — 이 프로젝트가 색인 불일치로 두 번 겪은 실패다.

    mysql ... -e "
      SELECT s.external_id,
             s.music_section_analysis_id,
             m.music_id
      FROM music_section_analysis s JOIN music m ON m.music_id = s.music_id
    " --batch > sections.tsv

순간 PK 는 **`music_section_analysis_id`** 다. 내보낸 표와 매핑 파일도 실제 컬럼명을
그대로 써서 같은 값에 이름을 두 개 만들지 않는다.

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.build_id_map sections.tsv

컬럼 이름만 맞으면 CSV·TSV 어느 쪽이든 읽는다. **구분자와 컬럼 순서는 보지 않는다** —
내보내는 쪽이 도구마다 다르기 때문이다.

**곡 문자열 ID 는 MySQL 에 두지 않는다**(Q3). 숫자 `music_id` 가 공통 키이고, 문자열은 AI
내부에만 남는다. 그래서 `source_track_id` 컬럼이 표에 없으면 구간 UID 의 `#` 앞을 잘라
쓰는 것이 **정식 경로**다. 표에 그 컬럼이 있으면 그쪽을 그대로 믿는다.

`music_id` 는 파생하지 않는다. 숫자 ID 는 Spring 이 발급하고(Q4) AI 는 받기만 한다 — 지어내면
실제 DB 와 어긋난 순간 엉뚱한 곡을 추천한다.

쓰기 전에 **1:1 을 검증한다.** 위반이 있으면 파일을 만들지 않는다. 절반만 매핑된 표를
그대로 두면 곡이 소리 없이 사라진다.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

from ai_server.app import ids, paths

REQUIRED = ("external_id", "music_section_analysis_id")
# Q2 로 `external_id VARCHAR(64)` 를 권고했다. 넘으면 MySQL 이 조용히 잘라 저장하고,
# 잘린 값끼리는 1:1 검사도 통과할 수 있어 매핑이 어긋난 채 서비스가 올라온다.
UID_MAX = 64


def read_table(path: Path) -> pd.DataFrame:
    """구분자를 추측해 읽는다. mysql --batch 는 TSV, 대부분의 도구는 CSV 다."""
    return pd.read_csv(path, sep=None, engine="python", dtype=str)


def build(df: pd.DataFrame, derive_track_id: bool = True) -> pd.DataFrame:
    missing = [c for c in REQUIRED if c not in df.columns]
    if missing:
        raise SystemExit(f"컬럼이 없다: {missing} (있는 것: {list(df.columns)})")

    out = df.copy()
    if "source_track_id" not in out.columns:
        if not derive_track_id:
            raise SystemExit("source_track_id 컬럼이 없고 파생도 막혀 있다.")
        # Q3: MySQL 에 곡 문자열 ID 자리를 두지 않기로 했다. 구간 UID 의 `#` 앞이 곧 곡 ID 다.
        out["source_track_id"] = out.external_id.str.split("#").str[0]
    if "music_id" not in out.columns:
        # 숫자 ID 는 Spring 이 발급한다(Q4). 없으면 만들어내지 않고 멈춘다.
        raise SystemExit("music_id 컬럼이 없다. Spring 이 발급한 값을 함께 내보내야 한다.")

    out = out[list(ids.COLS)]
    for c in ("music_id", "music_section_analysis_id"):
        out[c] = pd.to_numeric(out[c], errors="coerce").astype("Int64")
    return out


def check(out: pd.DataFrame) -> list[str]:
    """쓰기 전에 보는 것들. 여기서 걸러야 기동 뒤에 안 터진다."""
    problems = []
    long_uid = out[out.external_id.str.len() > UID_MAX]
    if len(long_uid):
        # MySQL 이 조용히 잘라 저장했으면 매핑이 어긋난 채 1:1 검사를 통과할 수도 있다.
        problems.append(
            f"구간 UID 가 {UID_MAX}자를 넘는 행 {len(long_uid)}건 — 컬럼 길이를 늘려야 한다. "
            f"최댓값 {int(out.external_id.str.len().max())}자, "
            f"예: {long_uid.external_id.iloc[0]}")
    for c in ("music_id", "music_section_analysis_id"):
        n = int(out[c].isna().sum())
        if n:
            problems.append(f"{c} 가 정수가 아닌 행 {n}건")
    return problems


def write_map(out: pd.DataFrame, dst: Path, force: bool = False) -> tuple[int, int]:
    """검증한 뒤에만 쓴다. 곡 수·구간 수를 돌려준다.

    `register_sections` 가 같은 경로로 쓰기 위해 `main()` 에서 빼냈다. 매핑표를
    만드는 길이 둘인데 검증이 한쪽에만 있으면, 없는 쪽으로 만든 표가 1:1 을 깨뜨린
    채 서비스에 올라간다.
    """
    problems = check(out)
    for problem in problems:
        print(f"경고: {problem}", file=sys.stderr)
    if problems and not force:
        raise SystemExit("쓰지 않는다. 고친 뒤 다시 돌리거나 --force 를 준다.")

    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(".parquet.tmp")
    out.to_parquet(tmp, index=False)

    # 서비스가 읽는 그대로 한 번 적재해 본다. 1:1 위반이면 여기서 막힌다.
    try:
        im = ids.IdMap(path=tmp, strict=True)
    except ids.MapIntegrityError as e:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"매핑표가 1:1 이 아니다. 쓰지 않는다." + chr(10) + f"{e}") from None
    tmp.replace(dst)
    return len(im.track_to_num), len(im.sec_to_num)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("table", help="MySQL 에서 내보낸 CSV/TSV")
    ap.add_argument("--out", default=None, help="기본: <ARTIFACT_DIR>/.../id_map.parquet")
    ap.add_argument("--no-derive-track-id", dest="derive_track_id", action="store_false",
                    help="source_track_id 컬럼이 없을 때 파생하지 않고 멈춘다")
    ap.add_argument("--derive-track-id", dest="derive_track_id", action="store_true",
                    help="(기본값) 호환용으로 남겨 둔다")
    ap.set_defaults(derive_track_id=True)
    ap.add_argument("--force", action="store_true", help="경고가 있어도 쓴다")
    a = ap.parse_args()

    src = Path(a.table)
    if not src.exists():
        print(f"파일이 없다: {src}", file=sys.stderr)
        return 2

    out = build(read_table(src), a.derive_track_id)
    dst = Path(a.out) if a.out else paths.id_map_path()
    tracks, sections = write_map(out, dst, a.force)
    print(f"저장 {dst} · 곡 {tracks} · 구간 {sections}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
