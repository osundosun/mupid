"""하루 1회 배치의 ②~④ — 임베딩 · 285차 특징 · 128차 구간 벡터.

    ① 분할(All-In-One)   ← 이번 사이클에서 부르지 않는다. sections.json 으로 받는다
    ② 임베딩  1536       embedder.Embedder
    ③ 특징     285       feature_space.FeatureSpace.transform
    ④ 구간벡터 128       feature_space.FeatureSpace.enc_sec

여기서는 곡 하나를 세 벌의 배열로 바꾸고, 무엇이 끊겼는지 기록하는 데까지만 한다.
색인 갱신은 `indexer`, 단계를 엮어 돌리는 것은 `batch` 가 한다 — **산출물에 쓰지 않는다.**

## 곡 단위로 실패를 격리한다

한 곡이 깨져도 나머지는 계속 간다. 배치가 통째로 멈추면 그날 들어온 곡이 전부 밀리고,
원인 곡 하나를 찾느라 다시 돌려야 한다. 실패는 이유와 함께 목록으로 남기고 다음 배치가
그 곡부터 다시 집는다.

## 이미 처리한 곡은 건너뛴다

음원 SHA-256 으로 판단한다. 파일명이나 시각이 아니라 내용이다.

**해시가 달라진 곡은 이번 사이클에서 처리하지 않는다.** 기존 색인 행을 바꾸는 일이라
교체 설계와 같이 가야 한다. 발견하면 `changed` 목록에만 남긴다.
"""
from __future__ import annotations

import json
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Optional, Sequence

import numpy as np

from . import content
from . import embedder as emb
from . import segmenter as seg
from .feature_space import ArtifactMissing, FeatureSpace, Section, TrackStats

STATE_NAME = "analysis_processed.json"
STATE_VERSION = 1


# ────────────────────────────────────────────────────────────── 결과 담는 것

@dataclass
class TrackResult:
    """곡 하나의 처리 결과. 실패해도 `status` 와 `error` 로 이유가 남는다."""

    track_id: str
    status: str                       # ok · failed · skipped · changed
    stage: str = ""                   # 어디까지 갔는지 (embed·features·section_vecs)
    n_sections: int = 0
    audio_sha256: str = ""
    error: str = ""
    elapsed_sec: float = 0.0
    rows: list = field(default_factory=list)     # moments.parquet 행 (곡 열은 갱신기가 채운다)
    embeddings: Optional[np.ndarray] = None      # (n, 1536)
    features: Optional[np.ndarray] = None        # (n, 285)
    section_vecs: Optional[np.ndarray] = None    # (n, 128)

    def summary(self) -> dict:
        """배열을 뺀 기록용 요약. 상태 파일과 보고에 쓴다."""
        return {"track_id": self.track_id, "status": self.status, "stage": self.stage,
                "n_sections": self.n_sections, "audio_sha256": self.audio_sha256,
                "error": self.error, "elapsed_sec": round(self.elapsed_sec, 2)}


@dataclass
class BatchReport:
    ok: list[dict] = field(default_factory=list)
    failed: list[dict] = field(default_factory=list)
    skipped: list[dict] = field(default_factory=list)
    changed: list[dict] = field(default_factory=list)

    def add(self, r: TrackResult) -> None:
        getattr(self, r.status if r.status in ("ok", "failed", "skipped", "changed")
                else "failed").append(r.summary())

    def as_dict(self) -> dict:
        return {"ok": len(self.ok), "failed": len(self.failed),
                "skipped": len(self.skipped), "changed": len(self.changed),
                "failures": self.failed, "changed_tracks": self.changed}


# ────────────────────────────────────────────────────────────── 곡 하나

def analyze_track(track_id: str, audio_path: Path, sections: Sequence[emb.Segment],
                  meta: TrackStats, *, engine: emb.Embedder,
                  space: FeatureSpace) -> TrackResult:
    """곡 하나를 세 벌의 배열로 바꾼다.

    `sections` 는 분할기 출력이다 — 이 함수는 분할을 하지 않는다. 3초 미만·양끝
    표시를 거르는 규칙은 `embedder.keep_segments` 한 곳에서만 적용한다.
    """
    t0 = time.time()
    r = TrackResult(track_id=track_id, status="failed")
    try:
        segs = emb.keep_segments(sections)
        r.n_sections = len(segs)
        if not segs:
            r.status, r.stage, r.error = "failed", "sections", "쓸 구간이 없다(3초 미만·양끝 제외 후 0개)"
            return r

        r.stage = "embed"
        y = emb.load_audio(Path(audio_path))
        dur = len(y) / emb.SR
        if dur <= 0:
            raise ValueError("오디오 길이가 0 이다")
        r.embeddings = engine.embed(y, segs)

        r.stage = "features"
        rms = _section_rms(y, segs)
        secs = [Section(rel_start=s.start / dur, rel_end=s.end / dur,
                        dur_sec=s.end - s.start, rms=float(v),
                        section_label=str(s.label))
                for s, v in zip(segs, rms)]
        r.features = space.transform(secs, r.embeddings, meta)
        # 색인에 그대로 들어갈 구간 행. 여기서 만들어야 특징과 **같은 값**이 보장된다 —
        # 나중에 다시 계산하면 rel_* 와 rms 가 미세하게 어긋날 수 있다.
        r.rows = [{"moment_id": f"{track_id}#{i}", "section_idx": i,
                   "start_sec": float(s.start), "end_sec": float(s.end),
                   "dur_sec": float(s.end - s.start),
                   "rel_start": c.rel_start, "rel_end": c.rel_end,
                   "section_label": str(s.label),
                   "label_source": seg.LABEL_SOURCE, "time_source": seg.TIME_SOURCE,
                   "rms": c.rms}
                  for i, (s, c) in enumerate(zip(segs, secs))]

        r.stage = "section_vecs"
        r.section_vecs = space.enc_sec(r.features)

        r.status, r.stage = "ok", "done"
    except Exception as e:                       # 곡 하나의 실패가 배치를 멈추지 않는다
        r.error = f"{type(e).__name__}: {e}"
        r.stage = r.stage or "start"
        # 원인을 찾을 수 있게 마지막 프레임만 남긴다. 전체 스택은 로그로 나간다.
        tb = traceback.format_exc().strip().splitlines()
        if tb:
            r.error += f"  ({tb[-2].strip() if len(tb) > 1 else ''})"
    finally:
        r.elapsed_sec = time.time() - t0
    return r


def _section_rms(y: np.ndarray, segs: Sequence[emb.Segment]) -> list[float]:
    """구간 RMS. `m17_flow_ingest` 와 같은 계산이다 — `moments.parquet` 의 `rms` 열."""
    out = []
    n = len(y)
    for s in segs:
        a, b = int(s.start * emb.SR), min(n, int(s.end * emb.SR))
        blk = y[a:b] if b > a else y[a:a + 1]
        out.append(float(np.sqrt(np.mean(np.square(blk)) + 1e-12)))
    return out


# ────────────────────────────────────────────────────────────── 배치

@dataclass
class TrackInput:
    track_id: str
    audio_path: Path
    sections: Sequence[emb.Segment]
    meta: TrackStats


def run_batch(tracks: Iterable[TrackInput], data_dir: Path, state_path: Path, *,
              device: Optional[str] = None,
              on_result=None) -> tuple[BatchReport, dict]:
    """곡들을 돌고 보고서를 돌려준다.

    산출물에 **쓰지 않는다.** 색인 append 는 다음 사이클 몫이라, 여기서는 계산 결과를
    호출자(`on_result`)에게 넘기고 처리 기록만 갱신한다.
    """
    space = FeatureSpace(Path(data_dir))          # 판 검사(sha256·feature_version)
    engine = emb.Embedder(Path(data_dir), device=device)
    state = load_state(state_path)
    done = state.setdefault("tracks", {})
    report = BatchReport()

    for t in tracks:
        sha = _sha256(Path(t.audio_path))
        prev = done.get(t.track_id)
        if prev and prev.get("audio_sha256") == sha and prev.get("status") == "ok":
            report.add(TrackResult(t.track_id, "skipped", audio_sha256=sha,
                                   n_sections=int(prev.get("n_sections", 0))))
            continue
        if prev and prev.get("audio_sha256") not in (None, "", sha):
            # 음원이 바뀌었다. 기존 색인 행을 고쳐야 하므로 이번 사이클에서는 손대지 않는다.
            report.add(TrackResult(t.track_id, "changed", audio_sha256=sha,
                                   error="음원이 바뀌었다 — 색인 교체와 함께 처리한다"))
            continue

        r = analyze_track(t.track_id, Path(t.audio_path), t.sections, t.meta,
                          engine=engine, space=space)
        r.audio_sha256 = sha
        report.add(r)
        if r.status == "ok":
            done[t.track_id] = r.summary()
            # 곡마다 저장한다. 중간에 끊겨도 거기까지는 남는다.
            save_state(state_path, state)
        if on_result is not None:
            on_result(r)

    save_state(state_path, state)
    return report, state


# ────────────────────────────────────────────────────────────── 상태 파일

def load_state(path: Path) -> dict:
    p = Path(path)
    if not p.exists():
        return {"version": STATE_VERSION, "tracks": {}}
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        # 깨진 기록으로 전부 건너뛰는 것보다 처음부터 다시 도는 편이 안전하다.
        return {"version": STATE_VERSION, "tracks": {}}
    d.setdefault("version", STATE_VERSION)
    d.setdefault("tracks", {})
    return d


def save_state(path: Path, data: dict) -> None:
    """임시 파일에 쓰고 바꿔 끼운다.

    곡마다 저장하므로 중간에 죽을 수 있다. 그냥 덮어쓰면 반쪽짜리 JSON 이 남고, 다음
    배치가 그것을 읽지 못해 **처리한 곡을 전부 다시 돈다.**
    """
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(p)


def _sha256(path: Path) -> str:
    from ..hasher import compute_sha256

    return compute_sha256(path)


# ────────────────────────────────────────────────────────────── 입력 읽기

def read_sections(path: Path) -> list[emb.Segment]:
    """분할기 출력 JSON → 구간 목록.

    형식과 검사는 `segmenter` 한 곳에 있다. 여기서 따로 읽으면 검사를 건너뛴 경로가
    하나 더 생긴다.
    """
    return seg.load_sections(Path(path)).segments


def read_track_stats(d: dict) -> TrackStats:
    """트랙 레벨 5값. **BPM 은 측정값을 쓴다.**

    앞서는 `bpm_requested` 를 우선 썼는데, 실측으로 그것이 틀렸다는 것이 드러났다.
    285차의 `trk_bpm` 은 동결 통계(평균 109.644 · 표준편차 43.291)로 z-score 되고,
    그 통계는 **측정 BPM 의 분포**다(`content.bpm` 전체 108.963 / 43.172). 요청값
    분포는 평균 111.0 · 표준편차 25.6 으로 달라서, 그 값을 넣으면 신곡만 중앙으로
    몰린다. flow 763곡 중 246곡은 측정값이 요청 범위 밖이고(평균 절대차 15.4 BPM)
    옥타브 오류도 섞여 있는데, **그 오류까지 포함한 분포가 색인**이다.

    `bpm_requested` 는 다른 자리에서 쓴다 — `flow_meta` 의 `bpm_lo`·`bpm_hi` 와
    추천기의 BPM 필터다. 거기서는 요청값이 맞다. 두 자리를 섞지 않는다
    (`ai_server/docs/FEATURE-SPACE.md`).
    """
    return content.stats_from_content(d)


__all__ = ["TrackResult", "BatchReport", "TrackInput", "analyze_track", "run_batch",
           "read_sections", "read_track_stats", "load_state", "save_state",
           "ArtifactMissing"]
