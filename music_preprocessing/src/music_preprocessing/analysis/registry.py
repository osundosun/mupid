"""로컬 수집 — `~/flow_music_data` 만으로 배치 입력을 만든다.

**요구 5(분석 대상 곡 조회)를 부르지 않는다.** 분석 대상은 JupyterHub 가 이미 알고
있고(음원 디렉터리), 숫자 ID 매핑도 `registered.json`(catalog 단계가 남긴 것)에 있다.
그래서 곡마다 서버를 왕복할 이유가 없다 — 1,063곡이면 1,063번이 된다.

    ~/flow_music_data/audio/T*.mp3              음원 (읽기만)
    ~/flow_music_data/metadata/T*.json          requested · flow_generated.sound_raw
    <artifact>/data/proc/mupid/flow/allin1/     All-In-One 구간 (① 의 산출물)
    ~/music_preprocessing/registered.json       source_track_id → music_id

## 색인에 넣을 값의 출처를 섞지 않는다

`meta_row`(=`flow_meta.parquet` 한 줄)의 값은 **metadata 에서만** 온다. 태그 5개
(`genre_top1`·`mood_tags`·`instrument_tags`·`voice_label`·`gender_label`)는 metadata 에
없다 — 별도 태깅 단계(`t01_pipeline` → Essentia/NLP)가 `tracks.parquet` 에 남긴 것이라
거기서 읽는다. metadata 의 `genre`·`mood` 와 색인의 `genre_top1`·`mood_tags` 는
**다른 값이다**(실측: `contemporary_pop` 대 `Electronic---Tropical House`). 섞으면
신곡만 다른 분포에 놓인다.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Sequence

from . import content as ct
from .api import meta_row
from .batch import BatchTrack

# metadata 에 없고 태깅 산출물에만 있는 열. 없으면 빈 값으로 둔다 — 태그는 곁들이는
# 값이고 색인·구간 적재는 그것 없이도 온전하다(`pipeline._instruments` 와 같은 판단).
TAG_COLS = ("genre_top1", "mood_tags", "instrument_tags", "voice_label", "gender_label")


class RegistryError(RuntimeError):
    """수집을 계속할 수 없다. **무엇이 없는지 말하고 멈춘다.**"""


@dataclass
class LocalRegistry:
    """로컬 디렉터리에서 배치 대상을 모은다."""

    flow_dir: Path                          # ~/flow_music_data
    artifact_root: Path                     # MUPID_AI_ARTIFACT_DIR
    registered_json: Optional[Path] = None  # 비우면 artifact_root 밖이다 — 호출자가 준다
    tag_file: Optional[Path] = None         # test_pipeline/tracks.parquet
    source: str = "flow"
    warn: list = field(default_factory=list)

    # ------------------------------------------------------------------ 조회

    def music_ids(self) -> dict[str, int]:
        """`source_track_id` → `music_id`. **숫자를 지어내지 않는다.**

        `music_id` 는 Spring 이 발급한 값이고, 그것을 받아 둔 곳이 `registered.json`
        (`catalog` 단계의 산출물)이다. 표에 없는 곡은 건너뛰고 센다 — 해시나 순번으로
        만들면 그럴듯하게 돌다가 실제 DB 와 어긋난 순간 엉뚱한 곡을 추천한다.
        """
        p = Path(self.registered_json) if self.registered_json else None
        if p is None or not p.exists():
            raise RegistryError(f"registered.json 이 없다: {p}")
        d = json.loads(p.read_text(encoding="utf-8"))
        out: dict[str, int] = {}
        for path, v in (d.get("tracks") or {}).items():
            tid = Path(path).stem
            mid = (v or {}).get("music_id")
            if mid in (None, ""):
                continue
            try:
                out[tid] = int(mid)
            except (TypeError, ValueError):
                self.warn.append(f"music_id 가 정수가 아니다: {tid} -> {mid!r}")
        return out

    def audio_path(self, track_id: str) -> Path:
        return Path(self.flow_dir) / "audio" / f"{track_id}.mp3"

    def metadata(self, track_id: str) -> dict:
        """metadata JSON 한 건. 없으면 **빈 dict** — 호출자가 결측으로 센다."""
        p = Path(self.flow_dir) / "metadata" / f"{track_id}.json"
        if not p.exists():
            return {}
        try:
            return json.loads(p.read_text(encoding="utf-8-sig"))
        except Exception as e:
            self.warn.append(f"metadata 를 읽지 못했다: {track_id} {type(e).__name__}: {e}")
            return {}

    def tags(self) -> dict[str, dict]:
        """`track_id` → 태그 5개. 태깅 산출물이 없으면 **빈 표**를 돌려준다.

        없는 것을 오류로 만들지 않는다. 태그는 곁들이는 값이고, 이것 때문에 배치가
        멈추면 태그가 아직 없는 날의 청킹까지 밀린다. 대신 `warn` 에 남긴다.
        """
        p = Path(self.tag_file) if self.tag_file else None
        if p is None or not p.exists():
            self.warn.append(f"태깅 산출물이 없다: {p} — 태그 없이 넣는다")
            return {}
        import pandas as pd

        df = pd.read_parquet(p)
        if "track_id" not in df.columns:
            self.warn.append(f"태깅 산출물에 track_id 가 없다: {p}")
            return {}
        out: dict[str, dict] = {}
        for r in df.itertuples(index=False):
            d = r._asdict() if hasattr(r, "_asdict") else dict(zip(df.columns, r))
            out[str(d.get("track_id"))] = {
                "genre_top1": str(d.get("genre_top1") or ""),
                # 색인은 **쉼표로 이은 문자열**이다(large_string). 배열로 넣으면 parquet
                # 쓰기가 타입 오류로 멈춘다 — `batch.meta_row` 가 같은 이유로 변환한다.
                "mood_tags": _tags(d.get("mood_json")),
                "instrument_tags": _tags(d.get("instrument_json")),
                "voice_label": str(d.get("voice_label") or ""),
                "gender_label": str(d.get("gender_label") or ""),
            }
        return out

    # ------------------------------------------------------------------ 조립

    def build(self, track_ids: Sequence[str], *, sections_of, demix_root=None,
              measure=None, load=None, limit: int = 0, work_dir: Optional[Path] = None
              ) -> tuple[list[BatchTrack], dict]:
        """대상 목록 → `BatchTrack`. 못 만든 곡은 이유와 함께 돌려준다.

        `sections_of(track_id)` 는 ① 의 산출물(`sections.json`) 경로를 준다. 없으면
        그 곡은 `sections` 로 실패한다 — **여기서 분할하지 않는다.** 분할은 GPU 와
        3.10 환경이 필요해서 `batch._collect_local` 이 `Segmenter` 로 맡는다.

        `demix_root` 를 주면 구간별 악기를 거기서 읽는다(`ALLIN1_KEEP_BYPRODUCTS`).
        """
        tmap = self.music_ids()
        tags = self.tags()
        out, failed, skipped = [], {}, {}
        work = Path(work_dir) if work_dir else Path(self.artifact_root) / "work"
        for tid in track_ids:
            tid = str(tid)
            if limit and len(out) >= limit:
                break
            if tid not in tmap:
                # 등록되지 않은 곡이다. 대개 jamendo(학습용, 서비스 아님)나 음원이
                # 바뀐 곡이다. 조용히 빠지면 "왜 이 곡만 추천에 안 나오나"로 돌아온다.
                skipped[tid] = "registered.json 에 없다 — catalog 가 아직 등록하지 않았다"
                continue
            ap = self.audio_path(tid)
            if not ap.exists():
                skipped[tid] = f"음원이 없다: {ap}"
                continue
            try:
                meta_raw = self.metadata(tid)
                row = measure((load or _load_audio)(ap)) if (measure or load) else None
                if row is None:
                    row = ct.track_content((load or _load_audio)(ap))
                row = dict(row)
                row["track_id"] = tid
                # metadata 의 requested 를 요구 5 응답 모양으로 옮긴다. `meta_row` 가
                # 읽는 이름을 맞춰 주는 것이 전부다 — 계산은 하지 않는다.
                track = _as_api_track(tid, meta_raw, tmap.get(tid))
                track.update(tags.get(tid) or {})
                out.append(BatchTrack(
                    track_id=tid, audio_path=ap, sections_json=Path(sections_of(tid)),
                    content_row=row, meta_row=meta_row(track, tid), source=self.source,
                    demix_root=Path(demix_root) if demix_root else None))
            except Exception as e:
                failed[tid] = f"{type(e).__name__}: {e}"[:400]
        return out, {"failed": failed, "skipped": skipped, "warn": self.warn,
                     "music_ids": {t: tmap[t] for t in map(str, track_ids) if t in tmap}}


def _as_api_track(track_id: str, meta: dict, music_id: Optional[int]) -> dict:
    """metadata JSON → 요구 5 응답 한 건과 같은 모양.

    이름을 바꾸는 것이 전부다. `meta_row` 는 `bpm_requested`(단일 값)와
    `category`·`vocal_type`·`language`·`sound_raw` 를 읽는데, metadata 는 같은 값을
    `requested.bpm_range`(배열)와 `flow_generated.sound_raw` 에 나눠 담고 있다.
    """
    req = meta.get("requested") or {}
    fg = meta.get("flow_generated") or {}
    rng = req.get("bpm_range") or []
    bpm = None
    if isinstance(rng, (list, tuple)) and rng:
        # `bpm_requested` 는 단일 값이다. 범위의 **가운데**를 쓴다 — `flow_meta` 의
        # `bpm_lo`·`bpm_hi` 는 `meta_row` 가 그 값으로 양끝을 채운다.
        nums = [float(x) for x in rng if isinstance(x, (int, float))]
        if nums:
            bpm = sum(nums) / len(nums)
    return {
        "music_id": music_id,
        "title": str(meta.get("flow_title") or track_id),
        "category": req.get("category"),
        "vocal_type": req.get("vocal_type"),
        "language": req.get("language"),
        "bpm_requested": bpm,
        "sound_raw": fg.get("sound_raw"),
    }


def _tags(v) -> str:
    """태깅 산출물의 `*_json` → 쉼표로 이은 문자열.

    `tracks.parquet` 은 `[{"label": "piano", "prob": 0.9, "confident": true}, …]`
    를 JSON 문자열로 담는다. 확률은 색인에 넣지 않는다 — 순위는 `label` 만 본다.
    """
    if v in (None, ""):
        return ""
    try:
        xs = json.loads(v) if isinstance(v, str) else list(v or [])
    except Exception:
        return str(v)
    labels = []
    for x in xs[:4]:
        if not isinstance(x, dict):
            continue
        if not x.get("confident", True):
            continue
        lab = str(x.get("label") or "").strip()
        if lab and lab not in labels:
            labels.append(lab)
    return ", ".join(labels)


def _load_audio(path: Path):
    from .embedder import load_audio

    return load_audio(Path(path))


__all__ = ["LocalRegistry", "RegistryError", "TAG_COLS"]
