"""내부 문자열 ID 와 실서비스 숫자 ID 사이의 매핑.

이 저장소의 곡 식별자는 출처마다 생김새가 다르다 — FMA 는 `000614`, Jamendo 는
`JAMENDO_1083659`, 자체 음원은 곡 제목 그 자체다. 구간은 `<track_id>#<section_idx>` 이다.
실서비스(MySQL)는 `music.music_id` 와 `music_section_analysis.music_section_id` 라는
정수를 쓴다.

**둘을 잇는 유일한 근거는 MySQL 에서 내보낸 매핑표뿐이다.** 문자열을 해시하거나 순번을
붙여 숫자를 만들어내면 그럴듯하게 동작하다가 실제 DB 와 어긋난 순간 엉뚱한 곡을 추천한다.
이 프로젝트가 색인 불일치로 두 번 겪은 실패와 같은 형태다. 그래서 여기서는 **매핑이 없으면
만들지 않고 오류를 낸다.**

매핑표 파일: `data/proc/mupid/id_map.parquet`
    source_track_id   문자열 곡 ID (예: JAMENDO_1083659)
    music_id          MySQL music.music_id
    source_section_uid 문자열 구간 ID (예: JAMENDO_1083659#3)
    music_section_id  MySQL music_section_analysis.music_section_id

`music_section_id` 가 비어 있는 행은 "곡은 적재됐지만 구간은 아직" 인 상태로 본다.
"""
from __future__ import annotations

import argparse
from pathlib import Path
from typing import Optional

import pandas as pd

from . import paths

# 경로는 paths 가 유일한 기준이다. 여기서 따로 계산하면 recommender 와 다른 곳을 보게
# 되고, 그 상태에서도 서비스는 정상처럼 뜬다 → app/paths.py

COLS = ("source_track_id", "music_id", "source_section_uid", "music_section_id")


class MapIntegrityError(Exception):
    """매핑표 자체가 깨졌다. 부분 적재로 넘어가면 조용히 틀린 추천이 나간다."""


class MappingError(Exception):
    """숫자 ID 매핑을 찾지 못했다. error_code 를 그대로 응답에 쓴다."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class IdMap:
    """매핑표를 양방향 사전으로 들고 있는다. 파일이 없으면 빈 상태로 시작한다."""

    def __init__(self, path: Optional[Path] = None, strict: bool = True):
        # 경로를 만들 때 읽는다. 기본값으로 굳히면 import 순서에 따라 환경변수 설정 전의
        # 값이 박힌다.
        self.path = Path(path) if path is not None else paths.id_map_path()
        self.strict = strict
        self.track_to_num: dict[str, int] = {}
        self.num_to_track: dict[int, str] = {}
        self.sec_to_num: dict[str, int] = {}
        self.num_to_sec: dict[int, str] = {}
        self.loaded = False
        self.error: Optional[str] = None
        self.reload()

    def reload(self) -> None:
        self.track_to_num, self.num_to_track = {}, {}
        self.sec_to_num, self.num_to_sec = {}, {}
        self.loaded, self.error = False, None
        if not self.path.exists():
            self.error = f"매핑표 없음: {self.path}"
            return
        try:
            df = pd.read_parquet(self.path)
        except Exception as e:                       # 조용히 비우면 전부 매핑 없음으로 보인다
            self.error = f"{type(e).__name__}: {e}"
            return
        missing = [c for c in COLS if c not in df.columns]
        if missing:
            self.error = f"매핑표에 컬럼이 없다: {missing}"
            return
        try:
            self._ingest(df)
        except MapIntegrityError as e:
            self.error = str(e)
            self.track_to_num, self.num_to_track = {}, {}
            self.sec_to_num, self.num_to_sec = {}, {}
            if self.strict:
                raise
            return
        self.loaded = True

    def _ingest(self, df: pd.DataFrame) -> None:
        """1:1 을 검증하며 담는다. 위반은 모아서 한 번에 보고한다 — 첫 건만 알려주면
        고치고 다시 돌리기를 반복하게 된다."""
        bad: list[str] = []

        def note(kind: str, detail: str) -> None:
            if len(bad) < 20:
                bad.append(f"{kind}: {detail}")

        for n, r in enumerate(df.itertuples(index=False)):
            tid, mid = r.source_track_id, r.music_id
            if pd.isna(tid) or str(tid).strip() == "":
                note("빈 source_track_id", f"{n}행")
            elif pd.notna(mid):
                tid = str(tid)
                try:
                    mid = int(mid)
                except (TypeError, ValueError):
                    note("music_id 가 정수가 아님", f"{n}행 {tid} -> {mid!r}")
                    mid = None
                if mid is not None:
                    if mid <= 0:
                        note("music_id 가 0 이하", f"{n}행 {tid} -> {mid}")
                    elif self.track_to_num.get(tid, mid) != mid:
                        note("원본 1개가 music_id 2개", # 1:N
                             f"{tid} -> {self.track_to_num[tid]}, {mid}")
                    elif self.num_to_track.get(mid, tid) != tid:
                        note("music_id 1개가 원본 2개", # N:1 — 곡 중복 노출의 원인
                             f"{mid} <- {self.num_to_track[mid]}, {tid}")
                    else:
                        self.track_to_num[tid] = mid
                        self.num_to_track[mid] = tid

            uid, sid = r.source_section_uid, r.music_section_id
            if pd.isna(uid) or pd.isna(sid):
                continue                       # 구간 미적재는 정상 상태로 본다
            uid = str(uid)
            try:
                sid = int(sid)
            except (TypeError, ValueError):
                note("music_section_id 가 정수가 아님", f"{n}행 {uid} -> {sid!r}")
                continue
            if sid <= 0:
                note("music_section_id 가 0 이하", f"{n}행 {uid} -> {sid}")
            elif self.sec_to_num.get(uid, sid) != sid:
                note("원본 구간 1개가 id 2개", f"{uid} -> {self.sec_to_num[uid]}, {sid}")
            elif self.num_to_sec.get(sid, uid) != uid:
                note("music_section_id 1개가 원본 2개",
                     f"{sid} <- {self.num_to_sec[sid]}, {uid}")
            else:
                self.sec_to_num[uid] = sid
                self.num_to_sec[sid] = uid

        if bad:
            raise MapIntegrityError(
                f"매핑표 {self.path} 가 1:1 이 아니다 ({len(bad)}건 이상):\n  "
                + "\n  ".join(bad))

    # ------------------------------------------------------------ 문자열 → 숫자
    def music_id(self, source_track_id: str) -> int:
        v = self.track_to_num.get(str(source_track_id))
        if v is None:
            raise MappingError("MUSIC_ID_MAPPING_NOT_FOUND",
                               "숫자 music_id 매핑을 찾을 수 없습니다.")
        return v

    def music_section_id(self, source_section_uid: str) -> int:
        v = self.sec_to_num.get(str(source_section_uid))
        if v is None:
            raise MappingError("SECTION_ID_MAPPING_NOT_FOUND",
                               "숫자 music_section_id 매핑을 찾을 수 없습니다.")
        return v

    # ------------------------------------------------------------ 숫자 → 문자열
    def source_track_id(self, music_id: int) -> str:
        v = self.num_to_track.get(int(music_id))
        if v is None:
            raise MappingError("MUSIC_ID_MAPPING_NOT_FOUND",
                               "숫자 music_id 매핑을 찾을 수 없습니다.")
        return v

    def source_section_uid(self, music_section_id: int) -> str:
        v = self.num_to_sec.get(int(music_section_id))
        if v is None:
            raise MappingError("SECTION_ID_MAPPING_NOT_FOUND",
                               "숫자 music_section_id 매핑을 찾을 수 없습니다.")
        return v

    def stats(self) -> dict:
        return {"path": str(self.path), "loaded": self.loaded, "error": self.error,
                "tracks": len(self.track_to_num), "sections": len(self.sec_to_num)}

    def readiness(self, pool_track_ids, pool_section_uids) -> dict:
        """추천 풀 전체가 매핑됐는지 본다. 하나라도 없으면 준비 완료가 아니다 —
        절반만 매핑된 상태로 응답하면 곡이 소리 없이 사라진다."""
        miss_t = [t for t in pool_track_ids if t not in self.track_to_num]
        miss_s = [u for u in pool_section_uids if u not in self.sec_to_num]
        return {"ready": self.loaded and not miss_t and not miss_s,
                "loaded": self.loaded, "error": self.error,
                "tracks_total": len(pool_track_ids), "tracks_unmapped": len(miss_t),
                "sections_total": len(pool_section_uids),
                "sections_unmapped": len(miss_s),
                "sample_unmapped_tracks": miss_t[:5]}


_MAP: Optional[IdMap] = None


def id_map(reload: bool = False) -> IdMap:
    global _MAP
    if _MAP is None or reload:
        _MAP = IdMap()
    return _MAP


# ---------------------------------------------------------------------- 시간
def to_ms(seconds: float) -> int:
    """초 → 밀리초. 0 아래로는 내려가지 않는다."""
    return max(0, round(float(seconds) * 1000))


# ---------------------------------------------------------------------- 보고
def report(pool_only: bool = True) -> dict:
    """아직 숫자 ID 매핑이 없는 데이터를 센다."""
    m = pd.read_parquet(paths.moments_path())
    if pool_only:
        m = m[m.source.isin(["flow", "jamendo", "own"])]
    im = id_map(reload=True)
    tracks = sorted(m.track_id.astype(str).unique())
    uids = sorted(m.moment_id.astype(str).unique())
    miss_t = [t for t in tracks if t not in im.track_to_num]
    miss_s = [u for u in uids if u not in im.sec_to_num]
    by_src = (m[m.track_id.astype(str).isin(miss_t)]
              .groupby("source").track_id.nunique().to_dict())
    return {"map": im.stats(), "tracks_total": len(tracks), "tracks_unmapped": len(miss_t),
            "sections_total": len(uids), "sections_unmapped": len(miss_s),
            "unmapped_by_source": by_src, "sample": miss_t[:5]}


if __name__ == "__main__":
    # 패키지 모듈이라 상대 import 를 쓴다. 실행은 `python -m ai_server.app.ids --report`.
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--all-sources", action="store_true", help="추천 풀 밖(fma 등)까지 센다")
    a = ap.parse_args()
    if a.report:
        r = report(pool_only=not a.all_sources)
        print(f"매핑표 {r['map']['path']}")
        print(f"  상태 {'로드됨' if r['map']['loaded'] else '없음'}"
              + (f" ({r['map']['error']})" if r['map']['error'] else ""))
        print(f"곡    {r['tracks_unmapped']}/{r['tracks_total']} 매핑 없음")
        print(f"구간  {r['sections_unmapped']}/{r['sections_total']} 매핑 없음")
        if r["unmapped_by_source"]:
            print(f"  출처별 {r['unmapped_by_source']}")
        if r["sample"]:
            print(f"  예시 {r['sample']}")
