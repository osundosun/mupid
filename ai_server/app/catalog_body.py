"""요청 본문의 `catalog` — Spring 이 보내는 후보와 그 값 (연동 계약 2026-09-23 절).

**AI 는 Spring 을 부르지 않는다.** 곡·구간 메타의 정본은 MySQL 이고, Spring 이 추천을
부를 때마다 후보를 걸러 그 값과 함께 본문에 싣는다. 이 모듈은 그 본문을 추천기가 읽는
스냅샷으로 옮길 뿐이다 — 값을 지어내거나 보태지 않는다.

    catalog.tracks    열 단위. track · duration_ms · bpm · bpm_requested_min/max ·
                      language · category · genres · moods · has_vocal · vocal_gender
    catalog.sections  열 단위. external_id · section_label · start_ms · end_ms · energy

**`sections` 에 없는 구간은 후보가 아니다.** Spring 이 삭제된 곡·재생할 수 없는 곡·
검색 조건 밖을 이미 뺐다.

같은 본문이 거듭 오면(`daily`·`next` 는 사용자마다 다르지 않다) 파생 결과를 해시로
재사용한다 — 캐시이지 상태가 아니다. 본문 없이는 추천을 계산하지 않는다.

배치(라벨러)는 요청이 없으므로 같은 스냅샷을 ClickHouse 구간 표에서 만든다
(`app/track_sections.py`).
"""
from __future__ import annotations

import hashlib
import json
import time
from typing import Optional

import pandas as pd

TRACK_COLS = ("track", "duration_ms", "bpm", "bpm_requested_min", "bpm_requested_max",
              "language", "category", "genres", "moods", "has_vocal", "vocal_gender")
SECTION_COLS = ("external_id", "section_label", "start_ms", "end_ms", "energy")
# 없으면 거절하는 열. 나머지는 비어 올 수 있다("모른다").
TRACK_REQUIRED = ("track",)
SECTION_REQUIRED = ("external_id", "section_label", "start_ms", "end_ms")


class CatalogBodyError(ValueError):
    """본문 모양이 계약과 다르다. 400 으로 돌려준다 — 조용히 고쳐 받지 않는다."""


class Snapshot:
    """한 시점의 카탈로그. **만들고 나면 바꾸지 않는다.**

    통째로 새로 만들어 참조 하나를 바꾸는 방식이라, 추천을 계산하는 도중에 값이
    반쯤 바뀌는 일이 없다.
    """

    def __init__(self, tracks: dict, sections: dict, pages: int = 1,
                 generated_first: Optional[str] = None,
                 generated_last: Optional[str] = None, digest: str = "") -> None:
        self.tracks = tracks              # track_id(str) -> dict
        self.sections = sections          # external_id(str) -> dict
        self.pages = pages
        self.generated_first = generated_first
        self.generated_last = generated_last
        self.digest = digest
        self.loaded_at = time.time()

    def music_id(self, track_id: str) -> Optional[int]:
        row = self.tracks.get(str(track_id))
        return None if row is None else row.get("music_id")

    def track_id_of(self, external_id: str) -> Optional[str]:
        row = self.sections.get(str(external_id))
        return None if row is None else row.get("track_id")

    def track_frame(self) -> pd.DataFrame:
        """`track_id` 를 인덱스로 하는 곡 메타. 열 이름은 추천 계산부가 읽는 이름이다."""
        rows = [{"track_id": tid,
                 "music_id": r.get("music_id"),
                 "title": r.get("title") or tid,
                 "genre_code": r.get("genre_code") or "",
                 "mood_codes": r.get("mood_codes") or "",
                 "has_vocal": r.get("has_vocal"),
                 "vocal_gender": r.get("vocal_gender"),
                 "language": r.get("language") or "",
                 "category": r.get("category") or "",
                 "bpm": r.get("bpm"),
                 "bpm_lo": r.get("bpm_lo"),
                 "bpm_hi": r.get("bpm_hi"),
                 "duration_ms": r.get("duration_ms")}
                for tid, r in self.tracks.items()]
        cols = ["track_id", "music_id", "title", "genre_code", "mood_codes", "has_vocal",
                "vocal_gender", "language", "category", "bpm", "bpm_lo", "bpm_hi",
                "duration_ms"]
        return pd.DataFrame(rows, columns=cols).set_index("track_id")

    def section_frame(self) -> pd.DataFrame:
        """`moment_id`(= `external_id`) 를 열로 갖는 구간 메타."""
        rows = [{"moment_id": uid,
                 "track_id": r.get("track_id"),
                 "music_section_analysis_id": r.get("music_section_analysis_id"),
                 "section_idx": r.get("section_idx"),
                 "section_label": r.get("section_label") or "",
                 "start_ms": r.get("start_ms"),
                 "end_ms": r.get("end_ms"),
                 "instruments": r.get("instruments") or "",
                 "moment_moods": r.get("moment_moods") or "",
                 "energy": r.get("energy")}
                for uid, r in self.sections.items()]
        return pd.DataFrame(rows, columns=[
            "moment_id", "track_id", "music_section_analysis_id", "section_idx",
            "section_label", "start_ms", "end_ms", "instruments", "moment_moods", "energy"])

    def stats(self) -> dict:
        return {"tracks": len(self.tracks), "sections": len(self.sections),
                "digest": self.digest[:12]}


def empty() -> Snapshot:
    """후보가 없는 카탈로그. 기동 직후 기준 판이 이것으로 묶인다."""
    return Snapshot({}, {}, digest="empty")


def _columns(block, names: tuple, required: tuple, where: str) -> dict:
    if not isinstance(block, dict):
        raise CatalogBodyError(f"catalog.{where} 는 객체여야 한다")
    unknown = sorted(set(block) - set(names))
    if unknown:
        raise CatalogBodyError(f"catalog.{where} 에 모르는 열이 있다: {unknown}")
    missing = [c for c in required if c not in block]
    if missing:
        raise CatalogBodyError(f"catalog.{where} 에 {missing} 이 없다")
    lengths = {c: len(v) for c, v in block.items() if isinstance(v, list)}
    if len(lengths) != len(block):
        bad = sorted(c for c, v in block.items() if not isinstance(v, list))
        raise CatalogBodyError(f"catalog.{where}.{bad[0]} 는 배열이어야 한다")
    if len(set(lengths.values())) > 1:
        raise CatalogBodyError(f"catalog.{where} 의 열 길이가 다르다: {lengths}")
    n = next(iter(lengths.values()), 0)
    return {c: block.get(c, [None] * n) for c in names}, n


def _text(v) -> str:
    return "" if v is None else str(v).strip()


def _num(v) -> Optional[float]:
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return x if x == x else None


def digest_of(body: dict) -> str:
    """본문 해시. 같은 후보면 같은 값이다 — 파생 결과를 재사용하는 열쇠."""
    raw = json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def from_body(body: dict) -> Snapshot:
    """`catalog` 본문 → 스냅샷. 모양이 계약과 다르면 거절한다."""
    if not isinstance(body, dict):
        raise CatalogBodyError("catalog 는 객체여야 한다")
    extra = sorted(set(body) - {"tracks", "sections"})
    if extra:
        raise CatalogBodyError(f"catalog 에 모르는 키가 있다: {extra}")
    t, nt = _columns(body.get("tracks", {}), TRACK_COLS, TRACK_REQUIRED, "tracks")
    s, ns = _columns(body.get("sections", {}), SECTION_COLS, SECTION_REQUIRED, "sections")

    tracks: dict = {}
    for i in range(nt):
        tid = _text(t["track"][i])
        if not tid:
            raise CatalogBodyError(f"catalog.tracks.track[{i}] 이 비었다")
        if tid in tracks:
            raise CatalogBodyError(f"catalog.tracks 에 {tid} 가 두 번 있다")
        has_vocal = t["has_vocal"][i]
        tracks[tid] = {
            "music_id": None,
            "title": None,
            # 추천 계산부는 쉼표로 이은 문자열을 읽는다(`recommender._tag_list`).
            "genre_code": ",".join(_text(x) for x in (t["genres"][i] or []) if _text(x)),
            "mood_codes": ",".join(_text(x) for x in (t["moods"][i] or []) if _text(x)),
            "has_vocal": has_vocal if isinstance(has_vocal, bool) else None,
            "vocal_gender": _text(t["vocal_gender"][i]) or None,
            "language": _text(t["language"][i]),
            "category": _text(t["category"][i]),
            "bpm": _num(t["bpm"][i]),
            "bpm_lo": _num(t["bpm_requested_min"][i]),
            "bpm_hi": _num(t["bpm_requested_max"][i]),
            "duration_ms": _num(t["duration_ms"][i]),
        }

    sections: dict = {}
    per_track: dict = {}
    for i in range(ns):
        uid = _text(s["external_id"][i])
        if "#" not in uid:
            raise CatalogBodyError(f"catalog.sections.external_id[{i}] 에 '#' 이 없다: {uid!r}")
        tid = uid.split("#", 1)[0]
        if tid not in tracks:
            # 곡 값 없이 구간만 오면 BPM·장르가 빈 채 후보가 된다. 오류 없이 품질만
            # 나빠지는 종류라 거절한다.
            raise CatalogBodyError(f"catalog.sections 의 {uid} 는 tracks 에 없는 곡이다")
        if uid in sections:
            raise CatalogBodyError(f"catalog.sections 에 {uid} 가 두 번 있다")
        a, b = _num(s["start_ms"][i]), _num(s["end_ms"][i])
        if a is None or b is None or b <= a:
            raise CatalogBodyError(f"catalog.sections 의 {uid} 경계가 잘못됐다: {a}..{b}")
        idx = per_track.get(tid, 0)
        per_track[tid] = idx + 1
        suffix = uid.split("#", 1)[1]
        sections[uid] = {
            "track_id": tid,
            "music_section_analysis_id": None,
            "section_idx": int(suffix) if suffix.isdigit() else idx,
            "section_label": _text(s["section_label"][i]),
            "start_ms": int(a),
            "end_ms": int(b),
            "instruments": "",
            "moment_moods": "",
            "energy": _num(s["energy"][i]),
        }
    return Snapshot(tracks, sections, digest=digest_of(body))
