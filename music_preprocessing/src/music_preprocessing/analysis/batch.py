"""하루 1회 배치 — 단계를 하나로 엮는다.

단계는 다 있었고 연결이 없었다. 이 파일이 그 연결이다.

```
수집   분석 대상 곡 + 음원 + sections.json
분석   analyze_track()          ② 1536 → ③ 285 → ④ 128
새 판  indexer.stage()          6개 파일 append + 무결성 게이트
등록   구간 등록(MySQL) → id_map → ClickHouse
확인   신곡 전량이 매핑됐는가
교체   indexer.swap()
알림   재기동이 필요하다
```

## 등록이 교체보다 **먼저**다

"색인을 바꾸고 나서 등록한다" 는 순서로 하면 **교체 직후 서비스가 통째로 죽는다.**

추천기는 `id_map` 을 보지 않고 색인 풀 전체에서 곡을 고른다. 응답을 만들 때에야
`im.music_id()` 로 숫자 ID 를 찾고, 없으면 `MappingError` 가 올라가 **그 요청 전체가
404**(`MUSIC_ID_MAPPING_NOT_FOUND`)가 된다. 신곡 하나가 후보에 뽑히기만 해도 그 요청이
통째로 떨어진다. `/internal/ready` 는 풀 전량 매핑을 요구하므로 **503** 이 된다.

즉 신곡이 색인에 들어간 순간부터 등록이 끝날 때까지 추천이 멈춘다. 그래서

> **등록이 끝나고, 신곡 전량이 매핑된 것을 확인한 뒤에만 교체한다.**

등록이 불가능하면(요구 1 미설정 — 주소·토큰이 없다) 새 판을 만들어 두고 **교체를 보류한 채**
그 사실을 보고한다. 새 판은 지우지 않는다 — 등록이 붙으면 그대로 이어서 쓴다.

## 종료 코드

cron·CronJob 이 실패를 알아차려야 한다. 0 이 아니면 사람이 본다.

| 코드 | 뜻 |
|---:|---|
| 0 | 전부 성공 (또는 할 일이 없었다) |
| 10 | 일부 곡 실패 — **색인은 교체됐다.** 실패 곡은 다음 배치가 다시 집는다 |
| 20 | **교체 보류** — 새 판은 만들었지만 등록·게이트를 통과하지 못했다 |
| 30 | 다른 실행이 돌고 있다 (잠금). 아무것도 하지 않았다 |
| 1 | 실패 — 산출물이 없거나 되돌릴 수 없는 오류 |
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterable, Optional, Protocol, Sequence

import numpy as np

from . import embedder as emb
from . import indexer as ix
from . import pipeline as pl
from . import segmenter as seg
from .feature_space import ArtifactMissing, FeatureSpace

LOCK_NAME = "analysis_batch.lock"
SUMMARY_NAME = "analysis_batch.jsonl"

EXIT_OK = 0
EXIT_PARTIAL = 10
EXIT_HOLD = 20
EXIT_LOCKED = 30
EXIT_FAIL = 1


def log(msg: str) -> None:
    print(f"[batch] {msg}", flush=True)


# ───────────────────────────────────────────────────────── 동시 실행 금지

class LockBusy(RuntimeError):
    """다른 실행이 돌고 있다. 기다리지 않고 즉시 물러난다."""


class RunLock:
    """파일 잠금. 같은 워터마크·같은 판을 두 프로세스가 만지면 안 된다.

    **뺏지 않는다.** 오래된 잠금이라도 스스로 지우지 않는다 — 앞 실행이 403MB 짜리
    파일을 쓰는 중일 수 있고, 그때 두 번째가 끼어들면 반쪽 판이 남는다. 사람이
    `--force-unlock` 으로 푼다.
    """

    def __init__(self, path: Path):
        self.path = Path(path)
        self.fd: Optional[int] = None

    def __enter__(self) -> "RunLock":
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            self.fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            raise LockBusy(f"이미 실행 중이다: {self.path} ({self.holder()})") from None
        os.write(self.fd, json.dumps(
            {"pid": os.getpid(), "host": socket.gethostname(),
             "started_at": datetime.now().astimezone().isoformat()},
            ensure_ascii=False).encode())
        return self

    def __exit__(self, *exc) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
        self.path.unlink(missing_ok=True)

    def holder(self) -> str:
        try:
            d = json.loads(self.path.read_text(encoding="utf-8"))
            age = time.time() - self.path.stat().st_mtime
            return f"pid {d.get('pid')} @ {d.get('host')} · {age / 60:.0f}분째"
        except Exception:
            return "내용을 읽지 못했다"


# ───────────────────────────────────────────────────────── 등록 단계

class Registrar(Protocol):
    """MySQL 구간 등록 → `id_map` → ClickHouse. **AI 바깥에 의존한다.**

    구현체는 Spring `요구 1 · 구간 분석 결과 적재` 를 부르고, 받은 숫자 ID 로
    `id_map.parquet` 을 **새 판 안에** 다시 쓴다. 새 판에는 교체 전 시점의 낡은
    매핑표가 복사돼 있으므로, 여기서 갱신하지 않으면 확인 단계가 반드시 실패한다.
    """

    def available(self) -> tuple[bool, str]: ...

    def register(self, tracks: Sequence[ix.NewTrack], new_dir: Path) -> dict: ...


class UnavailableRegistrar:
    """기본값 — **등록할 곳이 없다.**

    요구 1 자체는 있다(`PUT /api/v1/music/{id}/section`). 이 자리에 오는 것은 배치가
    그걸 부를 설정을 못 받았을 때다 — manifest 로 돌리거나 `api_base_url`·토큰이
    비어 있는 경우. 숫자 ID 를 지어내면 그럴듯하게 돌다가 실제 DB 와 어긋난 순간
    엉뚱한 곡을 추천한다. 그래서 등록을 흉내내지 않고 **보류로 끝낸다.**
    """

    def __init__(self, reason: str = ""):
        self.reason = reason or (
            "구간 등록(요구 1)을 부를 설정이 없다 — 숫자 ID 를 받을 곳이 없다")

    def available(self) -> tuple[bool, str]:
        return False, self.reason

    def register(self, tracks, new_dir):        # pragma: no cover - 부르지 않는다
        raise RuntimeError(self.reason)


def verify_mapping(new_dir: Path, tracks: Sequence[ix.NewTrack]) -> tuple[bool, dict]:
    """신곡의 곡·구간이 **전량** 매핑됐는가.

    하나라도 빠지면 교체하지 않는다. 절반만 매핑된 채 교체하면 그 곡이 후보에 뽑힐
    때마다 요청이 404 로 떨어지고, `/internal/ready` 는 503 이 된다.
    """
    import pandas as pd

    p = Path(new_dir) / "id_map.parquet"
    det: dict = {"id_map": str(p), "exists": p.exists()}
    if not p.exists():
        det["error"] = "새 판에 id_map.parquet 이 없다"
        return False, det
    df = pd.read_parquet(p)
    have_t = set(df.loc[df.music_id.notna(), "source_track_id"].astype(str))
    have_s = set(df.loc[df.music_section_id.notna(), "source_section_uid"].astype(str))

    want_t = {str(t.track_id) for t in tracks}
    want_s = {str(s["moment_id"]) for t in tracks for s in t.sections}
    miss_t = sorted(want_t - have_t)
    miss_s = sorted(want_s - have_s)
    det.update({"tracks": len(want_t), "tracks_unmapped": len(miss_t),
                "sections": len(want_s), "sections_unmapped": len(miss_s),
                "sample": (miss_t + miss_s)[:5]})
    return (not miss_t and not miss_s), det


# ───────────────────────────────────────────────────────── 입력

@dataclass
class BatchTrack:
    """배치가 받는 곡 하나.

    `content_row` 는 285차 생성에, `meta_row` 는 조건 검색에 쓰인다. 둘 중 하나라도
    빠지면 무결성 게이트가 교체를 막는다.
    """

    track_id: str
    audio_path: Path
    sections_json: Path
    content_row: dict
    meta_row: dict
    source: str = "flow"
    temp_audio: bool = False             # 내려받은 사본이면 처리 뒤 지운다
    demix_root: Optional[Path] = None    # 분할기가 남긴 demucs 스템. 구간별 악기를 여기서 읽는다

    def load(self) -> tuple[seg.SectionsFile, pl.TrackStats]:
        f = seg.load_sections(Path(self.sections_json), track_id=self.track_id)
        return f, pl.read_track_stats(self.content_row)


@dataclass
class BatchSummary:
    """한 줄 JSON 으로 남는 기록. 다음 배치가 이걸 읽어 재시도 대상을 만든다."""

    started_at: str = ""
    elapsed_sec: float = 0.0
    tracks: int = 0
    ok: int = 0
    failed: int = 0
    skipped: int = 0
    changed: int = 0
    rejected: int = 0
    added_tracks: int = 0
    added_sections: int = 0
    rows_before: int = 0
    rows_after: int = 0
    staged_dir: str = ""
    swapped: bool = False
    restart_required: bool = False
    hold_reason: str = ""
    exit_code: int = EXIT_OK
    failures: list = field(default_factory=list)
    changed_tracks: list = field(default_factory=list)
    rejections: list = field(default_factory=list)

    def as_line(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)


# ───────────────────────────────────────────────────────── 본체

def run(tracks: Iterable[BatchTrack], data_dir: Path, *,
        state_path: Optional[Path] = None,
        registrar: Optional[Registrar] = None,
        device: Optional[str] = None,
        keep_versions: int = ix.KEEP_VERSIONS,
        summary_path: Optional[Path] = None,
        status=None,
        analyze: Optional[Callable] = None,
        analyzer=None) -> BatchSummary:
    """한 사이클. **교체는 등록 확인을 통과한 뒤에만 한다.**

    `analyze` 는 시험에서 무거운 MERT 를 빼기 위한 자리다. 비우면 실제 ②③④ 를 돈다.
    `analyzer` 를 주면 그쪽이 ②③④ 를 맡는다 — 부모 인터프리터에 torch 가 없을 때
    (배포 서버가 3.12 이고 MERT 가 3.10 에 있을 때) 쓰는 길이다.
    """
    t0 = time.time()
    data = Path(data_dir)
    state = Path(state_path) if state_path else data / pl.STATE_NAME
    summary_file = Path(summary_path) if summary_path else data / SUMMARY_NAME
    reg = registrar or UnavailableRegistrar()
    s = BatchSummary(started_at=datetime.now().astimezone().isoformat())

    items = list(tracks)
    s.tracks = len(items)
    log(f"대상 {s.tracks}곡")

    new_tracks = _analyze_all(items, data, state, s,
                              device=device, analyze=analyze, status=status,
                              analyzer=analyzer)

    if not new_tracks:
        s.hold_reason = "붙일 신곡이 없다"
        if s.changed:
            # 조용히 넘어가면 이 곡들이 영영 옛 임베딩으로 남는다.
            s.hold_reason += (f" (음원이 바뀐 곡 {s.changed}건 — append 경로로는 "
                              "처리하지 않는다. 요약의 changed_tracks 를 본다)")
        s.exit_code = EXIT_PARTIAL if s.failed else EXIT_OK
        return _finish(s, t0, summary_file)

    # ── 새 판. 현재 판은 읽기만 한다.
    log(f"새 판을 만든다 — 신곡 {len(new_tracks)}곡")
    rep = ix.stage(new_tracks, data)
    s.staged_dir = str(rep.new_dir)
    s.added_tracks, s.added_sections = rep.added_tracks, rep.added_sections
    s.rows_before, s.rows_after = rep.rows_before, rep.rows_after
    s.rejections = list(rep.rejected)
    s.rejected = len(rep.rejected)
    log(f"  {rep.rows_before} → {rep.rows_after}행 (거부 {s.rejected})")

    if not rep.ok:
        s.hold_reason = "무결성 게이트 실패: " + "; ".join(rep.integrity.get("errors", []))
        s.exit_code = EXIT_HOLD
        return _finish(s, t0, summary_file)

    staged = [t for t in new_tracks
              if str(t.track_id) not in {r["track_id"] for r in rep.rejected}]

    # ── 등록. 여기가 교체 앞에 오는 이유는 이 파일 맨 위에 있다.
    ok, why = reg.available()
    if not ok:
        s.hold_reason = f"등록할 수 없어 교체를 보류한다 — {why}"
        s.exit_code = EXIT_HOLD
        log(s.hold_reason)
        log(f"  새 판은 남겨 둔다: {rep.new_dir}")
        return _finish(s, t0, summary_file)

    try:
        det = reg.register(staged, rep.new_dir)
        log(f"등록 {det}")
    except Exception as e:
        s.hold_reason = f"등록 실패로 교체를 보류한다 — {type(e).__name__}: {e}"
        s.exit_code = EXIT_HOLD
        return _finish(s, t0, summary_file)

    mapped, det = verify_mapping(rep.new_dir, staged)
    if not mapped:
        s.hold_reason = f"신곡이 전량 매핑되지 않아 교체를 보류한다 — {det}"
        s.exit_code = EXIT_HOLD
        return _finish(s, t0, summary_file)
    log(f"매핑 확인 — 곡 {det['tracks']} · 구간 {det['sections']} 전량")

    # ── 그때 교체한다.
    res = ix.swap(data, rep.new_dir, keep=keep_versions)
    s.swapped, s.restart_required = True, True
    log(ix.restart_notice(rep))
    log(f"  이전 판: {res['backup']}")
    s.exit_code = EXIT_PARTIAL if s.failed else EXIT_OK
    return _finish(s, t0, summary_file)


def _analyze_all(items: Sequence[BatchTrack], data: Path, state_path: Path,
                 s: BatchSummary, *, device, analyze, status=None,
                 analyzer=None) -> list:
    """곡 단위 격리. 한 곡이 깨져도 나머지는 간다."""
    space = FeatureSpace(data)               # 판 검사(sha256·feature_version)
    engine = None
    state = pl.load_state(state_path)
    done = state.setdefault("tracks", {})
    new_tracks = []

    for t in items:
        tid = str(t.track_id)
        try:
            sha = pl._sha256(Path(t.audio_path))
        except Exception as e:
            s.failed += 1
            s.failures.append({"track_id": tid, "stage": "collect",
                               "error": f"{type(e).__name__}: {e}"})
            continue

        prev = done.get(tid)
        if prev and prev.get("audio_sha256") == sha and prev.get("status") == "ok":
            s.skipped += 1
            continue
        if prev and prev.get("audio_sha256") not in (None, "", sha):
            # 기존 색인 행을 고치는 일이다. append 경로가 아니다.
            s.changed += 1
            s.changed_tracks.append({"track_id": tid, "audio_sha256": sha})
            continue

        # 분석을 **시작한다는 것**을 먼저 알린다 (요구 6 PROCESSING). 상태가 없으면
        # 같은 곡을 두 배치가 동시에 집는다.
        _say(status, "started", tid)

        try:
            sections, meta = t.load()
        except Exception as e:
            s.failed += 1
            s.failures.append({"track_id": tid, "stage": "sections",
                               "error": f"{type(e).__name__}: {e}"})
            _say(status, "failed", tid, "SECTIONS_INVALID", f"{type(e).__name__}: {e}")
            _discard(t)
            continue

        if analyze is not None:
            r = analyze(t, sections, meta)
        elif analyzer is not None:
            # ②③④ 를 다른 인터프리터에서. 부모(3.12)에 torch 가 없을 때의 길이다.
            got = analyzer.run([(t, sections)])
            r = got.get(tid)
            if r is None:
                why = analyzer.failures.get(tid, {}).get("error", "분석 결과가 없다")
                s.failed += 1
                s.failures.append({"track_id": tid, "stage": "analyze", "error": why})
                _say(status, "failed", tid, "MERT_INFERENCE_FAILED", why)
                _discard(t)
                continue
        else:
            if engine is None:
                engine = emb.Embedder(data, device=device)   # 모델은 배치당 한 번
            r = pl.analyze_track(tid, Path(t.audio_path), sections.segments, meta,
                                 engine=engine, space=space,
                                 demix_root=getattr(t, "demix_root", None))
        r.audio_sha256 = sha
        if r.instrument_error:
            # 곡은 살린다. 악기가 왜 비었는지는 남는다.
            log(f"  {tid} 악기 측정 실패 — {r.instrument_error}")

        if r.status != "ok":
            s.failed += 1
            s.failures.append({"track_id": tid, "stage": r.stage, "error": r.error})
            # 실패 이유를 남겨야 다음 배치가 같은 곡을 맹목적으로 다시 돌지 않는다.
            _say(status, "failed", tid, _code(r.stage), r.error)
            _discard(t)
            continue

        s.ok += 1
        done[tid] = r.summary()
        _discard(t)
        pl.save_state(state_path, state)     # 곡마다 저장한다. 중간에 죽어도 남는다
        new_tracks.append(ix.NewTrack(
            track_id=tid, source=t.source,
            track_dur_sec=float(sections.duration_sec),
            sections=[dict(x) for x in r.rows],
            embeddings=np.asarray(r.embeddings, dtype=np.float32),
            features=np.asarray(r.features, dtype=np.float32),
            section_vecs=np.asarray(r.section_vecs, dtype=np.float32),
            content_row={**t.content_row, "track_id": tid},
            meta_row={**t.meta_row, "track_id": tid}))

    pl.save_state(state_path, state)
    log(f"분석 — 성공 {s.ok} · 실패 {s.failed} · 건너뜀 {s.skipped} · 변경 {s.changed}")
    return new_tracks


def _discard(t: BatchTrack) -> None:
    """내려받은 음원은 곡 처리 뒤 지운다.

    곡당 수 MB 다. 1,000곡이면 수 GB 가 작업 디렉터리에 남고, 다음 배치가 그것을 다시
    받는다. **원본 음원 경로(`temp_audio=False`)는 건드리지 않는다.**
    """
    if not getattr(t, "temp_audio", False):
        return
    try:
        Path(t.audio_path).unlink(missing_ok=True)
    except Exception as e:
        log(f"임시 음원을 지우지 못했다 {t.track_id}: {type(e).__name__}: {e}")


def _say(status, what: str, track_id: str, code: str = "", message: str = "") -> None:
    """상태 알림. **없으면 그냥 넘어간다** — 알림 실패가 분석을 멈추지 않는다."""
    if status is None:
        return
    try:
        if what == "started":
            status.started(track_id)
        else:
            status.failed(track_id, code, message)
    except Exception as e:                    # 알림은 곁가지다
        log(f"상태 알림 실패 {track_id} {what}: {type(e).__name__}: {e}")


def _code(stage: str) -> str:
    """단계 → 기계가 읽는 짧은 코드 (요구 6 `error_code`)."""
    return {"sections": "SECTIONS_INVALID", "embed": "MERT_INFERENCE_FAILED",
            "features": "FEATURE_BUILD_FAILED", "section_vecs": "SECTION_VECTOR_FAILED",
            "collect": "AUDIO_UNREADABLE"}.get(stage, "ANALYSIS_FAILED")


def _finish(s: BatchSummary, t0: float, summary_file: Path) -> BatchSummary:
    s.elapsed_sec = round(time.time() - t0, 2)
    try:
        summary_file.parent.mkdir(parents=True, exist_ok=True)
        with summary_file.open("a", encoding="utf-8") as f:
            f.write(s.as_line() + "\n")
    except Exception as e:                    # 기록 실패가 배치를 실패로 만들지 않는다
        log(f"요약을 남기지 못했다: {type(e).__name__}: {e}")
    if s.hold_reason:
        log(s.hold_reason)
    print(s.as_line(), flush=True)
    return s


# ───────────────────────────────────────────────────────── CLI

def read_manifest(path: Path) -> list[BatchTrack]:
    """대상 곡 목록.

    운영에서는 `요구 5 · 분석 대상 곡 조회` 가 이 자리를 대신한다 — 배치는 자기
    기록만으로는 새 곡을 알 수 없다. 그때까지는 파일로 받는다.
    """
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    base = Path(path).parent
    out = []
    for t in d.get("tracks", []):
        out.append(BatchTrack(
            track_id=str(t["track_id"]),
            audio_path=(base / t["audio_path"]).resolve() if not Path(
                t["audio_path"]).is_absolute() else Path(t["audio_path"]),
            sections_json=(base / t["sections_json"]).resolve() if not Path(
                t["sections_json"]).is_absolute() else Path(t["sections_json"]),
            content_row=dict(t.get("content") or {}),
            meta_row=dict(t.get("meta") or {}),
            source=str(t.get("source", "flow")),
            demix_root=_rel(base, t.get("demix_root"))))
    return out


def _rel(base: Path, value) -> Optional[Path]:
    """manifest 의 상대 경로는 manifest 가 있는 곳 기준이다."""
    if not value:
        return None
    p = Path(value)
    return p if p.is_absolute() else (base / p).resolve()


def main(argv: Optional[Sequence[str]] = None) -> int:
    """청킹 배치(`music-preprocessing batch`)와 **다른 명령**이다.

        python -m music_preprocessing.analysis.batch --config config.json   # 요구 5 에서 받는다
        python -m music_preprocessing.analysis.batch manifest.json          # 파일로 받는다

    `run_batch.sh` 에 끼우지 않았다. 장치·환경·소요 시간이 다르고, 실패해도 청킹까지
    멈출 이유가 없다 — `genre_mood.py` 와 같은 **독립 도구** 자리다.
    """
    ap = argparse.ArgumentParser(description="구간 분석 배치 (청킹 배치와 별개다)")
    ap.add_argument("manifest", nargs="?", help="대상 곡 목록 JSON. 비우면 요구 5 로 받는다")
    ap.add_argument("--config", default=None,
                    help="config.json. 분석 설정은 analysis_* 필드로 들어간다")
    ap.add_argument("--data-dir", default=os.environ.get("MUPID_AI_DATA_DIR"),
                    help="색인 디렉터리 (기본: $MUPID_AI_ARTIFACT_DIR/data/proc/mupid)")
    ap.add_argument("--device", default=None, help="cuda:0 · cpu. 비우면 있는 쪽")
    ap.add_argument("--local", action="store_true",
                    help="~/flow_music_data 에서 직접 모은다 (요구 5 를 부르지 않는다)")
    ap.add_argument("--flow-dir", default=None, help="음원·metadata 루트 (기본: ~/flow_music_data)")
    ap.add_argument("--all", dest="all_tracks", action="store_true",
                    help="--local 에서 색인에 있는 곡까지 전부 돌린다 (재분석·태그 backfill)")
    ap.add_argument("--allin1-dir", default=None,
                    help="① 원본 JSON 디렉터리(All-In-One). 비우면 <artifact>/flow/allin1")
    ap.add_argument("--demix-dir", default=None,
                    help="구간별 악기를 읽을 demucs 스템. 비우면 <artifact>/work/demix")
    ap.add_argument("--sections-dir", default=None,
                    help="변환된 sections.json 디렉터리. 비우면 analysis_work_dir/allin1_raw")
    ap.add_argument("--keep", type=int, default=None, help="남길 이전 판 수")
    ap.add_argument("--force-unlock", action="store_true",
                    help="남아 있는 잠금을 지우고 시작한다. 앞 실행이 살아 있으면 안 된다")
    a = ap.parse_args(argv)

    cfg = _load_config(a.config) if a.config else None
    data = _pick_data_dir(a.data_dir, cfg)
    if not (data / "moments.parquet").exists():
        print(f"색인이 없다: {data}", file=sys.stderr)
        return EXIT_FAIL
    keep = a.keep if a.keep is not None else (
        int(getattr(cfg, "analysis_keep_versions", ix.KEEP_VERSIONS)) if cfg
        else ix.KEEP_VERSIONS)
    device = a.device or (getattr(cfg, "analysis_device", "") or None if cfg else None)

    lock = RunLock(data / LOCK_NAME)
    if a.force_unlock:
        lock.path.unlink(missing_ok=True)
    try:
        with lock:
            try:
                tracks, reg, status, analyzer = _collect(a, cfg)
            except Exception as e:
                print(f"대상을 모으지 못했다: {type(e).__name__}: {e}", file=sys.stderr)
                return EXIT_FAIL
            try:
                s = run(tracks, data, device=device, keep_versions=keep,
                        registrar=reg, status=status, analyzer=analyzer)
            except (ArtifactMissing, seg.SectionsError) as e:
                print(f"{type(e).__name__}: {e}", file=sys.stderr)
                return EXIT_FAIL
            return s.exit_code
    except LockBusy as e:
        print(str(e), file=sys.stderr)
        return EXIT_LOCKED


def _load_config(path):
    from ..config import ChunkConfig

    return ChunkConfig.from_file(str(path))


def _pick_data_dir(cli, cfg) -> Path:
    if cli:
        return Path(cli)
    root = (getattr(cfg, "analysis_artifact_dir", "") if cfg else "") \
        or os.environ.get("MUPID_AI_ARTIFACT_DIR", "")
    if not root:
        raise SystemExit("analysis_artifact_dir · MUPID_AI_ARTIFACT_DIR · --data-dir 중 "
                         "하나가 필요하다")
    return Path(root) / "data" / "proc" / "mupid"


def _collect(a, cfg):
    """대상 곡 · 등록기 · 상태 알림 · 분석기를 만든다.

    세 갈래다.

    `manifest`   파일로 받는다. 등록기는 기본값(보류)이다.
    `--local`    **`~/flow_music_data` 에서 직접 모은다.** 요구 5를 부르지 않는다 —
                 분석 대상은 JupyterHub 가 이미 알고 있고, 숫자 ID 매핑도
                 `registered.json`(catalog 산출물)에 있다.
    `--config`   요구 5 에서 받는다. 요구 1·6 클라이언트가 붙는다.

    `--local` 이 등록기를 못 받으면 `UnavailableRegistrar` 로 끝난다 — 교체하지 않는다.
    """
    if a.manifest:
        return read_manifest(Path(a.manifest)), None, None, None
    if getattr(a, "local", None):
        if not a.config:
            raise SystemExit("--local 은 --config 와 함께 쓴다 (경로·토큰이 거기 있다)")
        return _collect_local(a, cfg)
    if cfg is None:
        raise SystemExit("manifest 를 주거나 --config 로 요구 5 설정을 준다")
    if not getattr(cfg, "analysis_enabled", False):
        raise SystemExit("config 의 analysis_enabled 가 꺼져 있다")
    from . import api as ai

    token = _read_token(getattr(cfg, "analysis_internal_token_file", ""))
    client = ai.InternalApi(base_url=cfg.api_base_url, token=token,
                            analysis_version=getattr(cfg, "analysis_version", "v1"))
    work = Path(getattr(cfg, "analysis_work_dir", "./analysis_work"))
    py = getattr(cfg, "analysis_segmenter_python", "") or None
    segmenter = seg.Segmenter(work_dir=work / "allin1", out_dir=work / "allin1_raw",
                              python=py,
                              device=getattr(cfg, "analysis_device", "") or seg.ALLIN1_DEVICE)
    tracks, info = ai.collect(client, work, limit=int(getattr(cfg, "analysis_limit", 200)),
                              segmenter=segmenter)
    sha = info["sha"]
    status = ai.StatusReporter(api=client, sha=sha)
    for tid, why in info["failed"].items():
        # 모으다 깨진 곡도 이유를 남긴다. 다음 배치가 맹목적으로 다시 돌지 않게.
        status.failed(tid, "INGEST_FAILED", why)
    log(f"요구 5 — 대상 {len(tracks)}곡 · 수집 실패 {len(info['failed'])}곡")
    return (tracks, ai.HttpRegistrar(client=_mupid_client(cfg),
                                     analysis_version=getattr(cfg, "analysis_version", "v1")),
            status, None)


def _collect_local(a, cfg):
    """`~/flow_music_data` 만으로 대상과 등록기를 만든다.

    **요구 5·6 을 부르지 않는다.** 다만 ①(분할)은 GPU·3.10 이 필요하므로 여기서 하지
    않는다 — `run()` 이 `Segmenter` 로 맡는다.

    ## ① 원본과 변환본은 **다른 디렉터리**다

    All-In-One 이 남기는 JSON(`flow/allin1/T000001.json`)은 시간·라벨만 있고, 배치가
    읽는 `sections.json`(`mupid.sections/v1`)은 여기에 `duration_sec`·`audio_sha256`
    이 붙은 **다른 형식**이다(`segmenter.to_sections`). 둘을 같은 디렉터리로 잡으면
    변환본이 원본을 덮어써서, 다시 돌릴 때 원본이 없어 전곡을 GPU 로 재분할한다.

    그래서 자리를 셋으로 나눈다.

        allin1_dir    <artifact>/flow/allin1        원본. 있으면 **재사용**한다 (GPU 0회)
        sections_dir  <artifact>/work/sections      변환본. 배치가 읽는 것
        demix_dir     <artifact>/work/demix         구간별 악기(스템). 원본과 같은 실행

    원본 JSON 이 이미 있는 곡은 ① 을 건너뛴다 — B1 이 299곡을 만들어 둔 자리다.
    """
    from . import registry as rg

    root = Path(_pick_data_dir(a.data_dir, cfg))          # <artifact>/data/proc/mupid
    artifact = root.parents[2]                           # <artifact>
    home = Path.home()
    flow = Path(a.flow_dir) if getattr(a, "flow_dir", None) else home / "flow_music_data"
    work = Path(getattr(cfg, "analysis_work_dir", "./analysis_work"))
    if not work.is_absolute():
        work = Path(a.config).parent / work
    # `registered.json` 은 artifact 밖(`~/music_preprocessing`)에 있다 — 청킹 배치가
    # 거기서 돌기 때문이다. config 가 가리키는 값을 그대로 쓴다.
    reg_json = getattr(cfg, "api_registry_log", "") or ""
    reg_path = Path(a.config).parent / (reg_json or "registered.json")

    lib = rg.LocalRegistry(
        flow_dir=flow, artifact_root=artifact,
        registered_json=reg_path,
        tag_file=_tag_file(cfg, a, root),
        source="flow")
    ids = _local_track_ids(a, lib, root, flow)
    log(f"로컬 수집 — 음원 {len(ids)}곡 (요구 5 를 부르지 않는다)")

    # ① 원본(All-In-One) · 변환본 · 스템, 셋을 각각 잡는다.
    allin1_dir = Path(a.allin1_dir) if getattr(a, "allin1_dir", None) \
        else root / "flow" / "allin1"
    out_dir = Path(a.sections_dir) if getattr(a, "sections_dir", None) \
        else work / "sections"
    demix = Path(a.demix_dir) if getattr(a, "demix_dir", None) \
        else artifact / "work" / seg.DEMIX_DIRNAME
    segmenter = seg.Segmenter(
        work_dir=work / "allin1", out_dir=allin1_dir,
        python=getattr(cfg, "analysis_segmenter_python", "") or None,
        device=getattr(cfg, "analysis_device", "") or seg.ALLIN1_DEVICE)
    log(f"  ① 원본 {allin1_dir} · 변환본 {out_dir} · 스템 {demix}")
    tracks, info = lib.build(
        ids,
        sections_of=lambda tid: Path(out_dir) / f"{tid}.json",
        demix_root=demix,
        limit=int(getattr(cfg, "analysis_limit", 0) or 0))
    for tid, why in list((info.get("skipped") or {}).items())[:20]:
        log(f"  건너뜀 {tid}: {why}")
    for tid, why in (info.get("failed") or {}).items():
        log(f"  실패 {tid}: {why}")
    log(f"대상 {len(tracks)}곡 · 건너뜀 {len(info.get('skipped') or {})}곡")

    # ① 분할 — **원본 JSON 이 없는 곡만.** GPU·3.10 이 필요하므로 여기서 맡는다.
    # 있는 곡은 원본을 그대로 읽어 변환만 한다(`to_sections`) — 분할은 이미 끝났다.
    raw = {t.track_id for t in tracks
           if (Path(allin1_dir) / f"{t.track_id}.json").exists()}
    need = [t for t in tracks if t.track_id not in raw]
    if need:
        log(f"① 분할 — {len(need)}곡 (All-In-One) · 원본 재사용 {len(raw)}곡")
        segmenter.analyze([(t.track_id, t.audio_path) for t in need])
        for t in need:
            why = segmenter.failures.get(str(t.track_id), {}).get("error")
            if why:
                log(f"  분할 실패 {t.track_id}: {why}")
    else:
        log(f"① 분할 — 원본 재사용 {len(raw)}곡 (GPU 0회)")

    # 변환 — 배치가 읽는 `sections.json` 을 여기서 만든다. `to_sections` 가 곡 길이를
    # ②와 같은 적재 함수(`embedder.load_audio`, 24kHz)로 재므로 `rel_*` 가 어긋나지
    # 않는다. 이미 변환본이 있으면 만들지 않는다(재실행이 읽기만 하도록).
    from .segmenter import dump_sections

    made = 0
    for t in tracks:
        dest = Path(out_dir) / f"{t.track_id}.json"
        if dest.exists():
            continue
        try:
            f = segmenter.to_sections(str(t.track_id), Path(t.audio_path))
        except Exception as e:
            log(f"  변환 실패 {t.track_id}: {type(e).__name__}: {e}")
            continue
        dump_sections(f, dest)
        made += 1
    log(f"② 입력 sections.json {made}개 생성 (기존 {len(tracks) - made}개 재사용)")

    analyzer = _child_analyzer(cfg, root)
    registrar = ai_registrar(cfg)
    return tracks, registrar, None, analyzer


def _local_track_ids(a, lib, root: Path, flow: Path) -> list:
    """분석할 곡 — 음원이 있는데 **색인에 없는** 곡이다.

    `--all` 을 주면 등록된 전곡을 돌린다(재분석·태그 backfill 용). 기본은 신곡만이라
    매일 돌아도 첫날 이후로는 읽기만 한다(`classify` 와 같은 규칙).
    """
    audio = sorted(p.stem for p in (flow / "audio").glob("*.mp3"))
    if getattr(a, "all_tracks", False):
        return audio
    import pandas as pd

    p = Path(root) / "moments.parquet"
    if not p.exists():
        return audio
    df = pd.read_parquet(p, columns=["track_id", "source"])
    done = set(df.loc[df.source == lib.source, "track_id"].astype(str))
    return [t for t in audio if t not in done]


def _tag_file(cfg, a, root: Path) -> Optional[Path]:
    """태깅 산출물(`tracks.parquet`). config 가 가리키면 그 값을 쓴다.

    없으면 태그 없이 넣는다 — 태그는 곁들이는 값이고, 이것 때문에 배치가 멈추면
    태그가 아직 없는 날의 분석까지 밀린다.
    """
    v = getattr(cfg, "analysis_tag_file", "") or ""
    if v:
        p = Path(v)
        return p if p.is_absolute() else Path(a.config).parent / p
    for cand in (Path(root).parent / "test_pipeline" / "tracks.parquet",):
        if cand.exists():
            return cand
    return None


def _child_analyzer(cfg, root: Path):
    """②③④ 를 돌릴 인터프리터. **없으면 None** — 부모가 직접 돌린다.

    배포 서버(3.12)에는 torch 가 없다. `analysis_segmenter_python`(All-In-One 용
    3.10)에 torch·transformers·MERT 가 함께 있으므로 그쪽에 맡긴다.
    """
    py = getattr(cfg, "analysis_segmenter_python", "") or ""
    if not py:
        return None
    src = Path(__file__).resolve().parents[2]
    return pl.ChildAnalyzer(python=py, data_dir=Path(root), src_root=src,
                            device=getattr(cfg, "analysis_device", "") or "")


def ai_registrar(cfg):
    """요구 1 적재기. 주소·토큰이 없으면 `UnavailableRegistrar`(교체 보류)."""
    from . import api as ai

    try:
        return ai.HttpRegistrar(client=_mupid_client(cfg),
                                analysis_version=getattr(cfg, "analysis_version", "v1"))
    except Exception as e:
        log(f"등록기를 만들지 못했다 — 교체를 보류한다: {type(e).__name__}: {e}")
        return UnavailableRegistrar(f"{type(e).__name__}: {e}")


def _mupid_client(cfg):
    """요구 1 을 부를 클라이언트.

    요구 1 은 확정된 계약이라 `/internal/*` 이 아니라 일반 경로 + `Authorization:
    Bearer` 다. 토큰 발급·회전은 저장소가 이미 쓰고 있는 `MupidClient` 에 맡긴다 —
    리프레시 토큰을 여기서 또 다루면 회전된 값이 두 곳으로 갈린다.
    """
    from ..api_client import MupidClient

    f = Path(getattr(cfg, "api_refresh_token_file", "") or "")
    if not f.exists():
        raise SystemExit(
            f"리프레시 토큰 파일이 없다: {f}. 관리자 계정으로 POST /api/v1/user/system 을 "
            "불러 ROLE_SYSTEM 계정을 만들고 응답의 refresh_token 을 이 파일에 둔다.")
    token = f.read_text(encoding="utf-8").strip()
    if not token:
        raise SystemExit(f"리프레시 토큰 파일이 비어 있다: {f}")
    return MupidClient(base_url=cfg.api_base_url, refresh_token=token, token_file=f)


def _read_token(path: str) -> str:
    """토큰은 **파일로만** 받는다. 설정 파일이나 명령줄에 값을 싣지 않는다."""
    p = Path(path or "")
    if not p.exists():
        raise SystemExit(f"내부 토큰 파일이 없다: {p}")
    return p.read_text(encoding="utf-8").strip()


def _default_data_dir() -> Path:
    root = os.environ.get("MUPID_AI_ARTIFACT_DIR")
    if not root:
        raise SystemExit("MUPID_AI_ARTIFACT_DIR 또는 --data-dir 가 필요하다")
    return Path(root) / "data" / "proc" / "mupid"


__all__ = ["BatchTrack", "BatchSummary", "Registrar", "UnavailableRegistrar",
           "RunLock", "LockBusy", "run", "verify_mapping", "read_manifest", "main",
           "EXIT_OK", "EXIT_PARTIAL", "EXIT_HOLD", "EXIT_LOCKED", "EXIT_FAIL"]


if __name__ == "__main__":
    raise SystemExit(main())
