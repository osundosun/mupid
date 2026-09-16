"""① 분할 — **계약만 고정한다. 실행기는 미구현이다.**

All-In-One(`allin1`)은 GPU 와 파이썬 3.10 을 요구하고, 지금은 분석할 음원 자체가 없다.
그래서 이번 사이클에서도 돌리지 않는다. 대신 **중간 산출물 `sections.json` 의 형식**을
여기서 못 박는다. 형식이 흔들리면 나중에 분할을 붙일 때 ②③④ 를 다시 손봐야 한다.

## 왜 파일로 남기는가

분할과 임베딩을 한 프로세스에서 이어 붙이면 **MERT 판이 바뀔 때마다 분할까지 다시
돈다.** 곡당 GPU 3.1초 · CPU 654초짜리 단계다. 경계는 음원이 안 바뀌면 그대로이므로
JSON 으로 떼어 두면 임베딩만 다시 돌릴 수 있다.

파이썬 판이 갈려 있는 것(`allin1` 은 py3.10, 서빙은 3.12)도 같은 결론으로 간다 —
프로세스를 나눌 수밖에 없고, 나누면 경계를 주고받을 그릇이 필요하다.

## 구간이 `moments.parquet` 의 어느 열로 가는가

| sections.json | moments.parquet | 비고 |
|---|---|---|
| `segments[].start` | `start_sec` | 초 |
| `segments[].end` | `end_sec` | 초 |
| (end - start) | `dur_sec` | |
| `segments[].label` | `section_label` | 소문자. ClickHouse 로 갈 때만 대문자 |
| `duration_sec` | `track_dur_sec` | |
| start/duration | `rel_start` | ③ 의 side 특징이 이 값을 쓴다 |
| end/duration | `rel_end` | |
| `segmenter.model` | `label_source` | `allin1_harmonix` |
| — | `time_source` | `allin1` |
| 순번 | `section_idx` · `moment_id`(`<track_id>#<idx>`) | 거른 **뒤** 다시 매긴다 |

`rms` 는 여기서 오지 않는다. ② 가 파형에서 잰다.

## 거르는 규칙은 여기에 없다

3초 미만과 `start`·`end` 라벨은 `embedder.keep_segments` **한 곳에서만** 버린다.
이 파일은 분할기가 내놓은 것을 **그대로** 담는다 — 두 곳에서 걸러 내면 어느 쪽이
적용됐는지 알 수 없게 되고, 구간 수가 어긋나 색인 정렬이 깨진다.

그래서 `section_idx` 는 원본 순번이 아니라 **거른 뒤의 순번**이다. 색인의 `emb_row` 와
1:1 이어야 하기 때문이다.
"""
from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .embedder import Segment, keep_segments

SCHEMA = "mupid.sections/v1"

# `moments.parquet` 의 flow 구간이 실제로 쓰는 어휘. MySQL `section_label` ENUM 과
# 대문자만 다르다. 새 라벨이 들어오면 ClickHouse 적재에서 조용히 버려진다.
LABELS = ("intro", "verse", "pre_chorus", "chorus", "bridge",
          "break", "inst", "solo", "outro")
# 곡의 양끝 표시. 구간이 아니다 — `keep_segments` 가 버린다.
EDGE_LABELS = ("start", "end")

LABEL_SOURCE = "allin1_harmonix"
TIME_SOURCE = "allin1"

BOUND_TOL_S = 0.5           # 마지막 구간이 길이를 살짝 넘는 것은 분할기의 반올림이다


class SectionsError(RuntimeError):
    """구간 파일이 계약과 다르다. 추측해서 고치지 않는다."""


@dataclass
class SectionsFile:
    """`sections.json` 한 벌. 분할기와 ②③④ 사이의 유일한 그릇."""

    track_id: str
    duration_sec: float
    segments: list[Segment]
    analysis_version: str = "v1"
    audio_sha256: str = ""
    segmenter: dict = field(default_factory=dict)

    def kept(self) -> list[Segment]:
        """색인에 들어갈 구간. 거르는 규칙은 `embedder` 한 곳에 있다."""
        return keep_segments(self.segments)


# 구간 행(`moments.parquet`)을 만드는 곳은 **`pipeline.analyze_track` 한 곳**이다.
# 여기서도 만들 수 있게 해 두면 `rel_*` 와 `rms` 가 두 갈래로 계산돼 미세하게 어긋난다.


def load_sections(path: Path, *, track_id: Optional[str] = None) -> SectionsFile:
    """읽고 **검사한다.** 깨진 경계를 그대로 태우면 색인에 들어간 뒤에야 드러난다."""
    p = Path(path)
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        raise SectionsError(f"{p} 를 읽지 못했다: {type(e).__name__}: {e}") from None
    if not isinstance(d, dict):
        raise SectionsError(f"{p}: 최상위가 객체가 아니다")

    got = str(d.get("schema", ""))
    if got and got != SCHEMA:
        # 판이 다르면 열 뜻이 달라질 수 있다. 조용히 읽지 않는다.
        raise SectionsError(f"{p}: schema 가 {got!r} 이다 (기대 {SCHEMA!r})")

    tid = str(d.get("track_id") or track_id or "").strip()
    if not tid:
        raise SectionsError(f"{p}: track_id 가 없다")

    segs = [Segment(label=str(s.get("label", "unknown")),
                    start=float(s["start"]), end=float(s["end"]))
            for s in d.get("segments", [])]
    dur = float(d.get("duration_sec") or (max((s.end for s in segs), default=0.0)))

    f = SectionsFile(track_id=tid, duration_sec=dur, segments=segs,
                     analysis_version=str(d.get("analysis_version") or "v1"),
                     audio_sha256=str(d.get("audio_sha256") or ""),
                     segmenter=dict(d.get("segmenter") or {}))
    problems = validate(f)
    if problems:
        raise SectionsError(f"{p}: " + " / ".join(problems))
    return f


def validate(f: SectionsFile) -> list[str]:
    """계약 위반을 **모아서** 돌려준다. 첫 건만 알리면 고치고 다시 돌리기를 반복한다."""
    out: list[str] = []
    if f.duration_sec <= 0:
        out.append("duration_sec 가 0 이하다")
    if not f.segments:
        out.append("segments 가 비어 있다")

    prev_end = None
    for i, s in enumerate(f.segments):
        if s.end <= s.start:
            out.append(f"{i}번 구간의 끝이 시작보다 앞이다 ({s.start}~{s.end})")
        if s.start < -1e-6:
            out.append(f"{i}번 구간이 음수에서 시작한다 ({s.start})")
        if f.duration_sec > 0 and s.end > f.duration_sec + BOUND_TOL_S:
            out.append(f"{i}번 구간이 곡 길이를 넘는다 ({s.end} > {f.duration_sec})")
        if prev_end is not None and s.start + 1e-6 < prev_end:
            # 겹치면 같은 프레임이 두 구간에 들어가 임베딩이 중복된다.
            out.append(f"{i}번 구간이 앞 구간과 겹친다 ({prev_end} > {s.start})")
        prev_end = s.end
        lab = str(s.label)
        if lab not in LABELS and lab not in EDGE_LABELS:
            out.append(f"{i}번 구간의 라벨 {lab!r} 이 어휘에 없다")

    if f.segments and not f.kept():
        out.append("거르고 나면 남는 구간이 없다 (3초 미만·양끝뿐)")
    return out


def dump_sections(f: SectionsFile, path: Path) -> Path:
    """중간 산출물로 남긴다. **임베딩을 다시 돌릴 때 이 파일만 있으면 된다.**"""
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({
        "schema": SCHEMA,
        "track_id": f.track_id,
        "analysis_version": f.analysis_version,
        "audio_sha256": f.audio_sha256,
        "duration_sec": f.duration_sec,
        "segmenter": f.segmenter or {"name": "allin1", "model": "harmonix-all"},
        "segments": [{"label": s.label, "start": s.start, "end": s.end}
                     for s in f.segments],
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return p


# ───────────────────────────────────────────────────────── 실행기

# All-In-One 설정. **정본은 GPU 박스의 `~/rec_mupid/src/allin1_flow.py`** 이고, 그 값을
# 그대로 옮겨 적었다. 색인의 flow 6,767구간이 이 설정으로 만들어졌다 — 하나라도 다르면
# 신곡의 경계가 기존 곡과 다른 규칙으로 잘린다.
#
#     allin1.analyze(paths=chunk, out_dir=OUT, model="harmonix-all", device="cuda:0",
#                    demix_dir=..., spec_dir=..., keep_byproducts=True, multiprocess=False)
#     B = 8                                    # 8곡씩 한 번에
ALLIN1_MODEL = "harmonix-all"
ALLIN1_DEVICE = "cuda:0"
ALLIN1_BATCH = 8
ALLIN1_MULTIPROCESS = False       # 스펙트로그램 추출을 멀티프로세스로 돌리지 않는다
ALLIN1_KEEP_BYPRODUCTS = True     # demucs·스펙트로그램 캐시를 남긴다 (재분석이 훨씬 빠르다)
ALLIN1_TIMEOUT_S = 900            # 곡당. 넘으면 그 곡만 실패로 둔다

DEMIX_DIRNAME = "demix"
SPEC_DIRNAME = "spec"

STDERR_TAIL = 20                  # 실패 기록에 남길 stderr 줄 수


class SegmenterUnavailable(SectionsError):
    """분할기를 부를 수 없다. **무엇이 없는지 말하고 멈춘다.**

    조용히 빈 결과를 돌려주면 그 곡이 "구간 0개" 로 색인에 들어가거나, 더 나쁘게는
    실패가 성공으로 보고된다.
    """


def allin1_available() -> tuple[bool, str]:
    """이 인터프리터에서 All-In-One 을 바로 부를 수 있는가."""
    try:
        import allin1                                    # noqa: F401
    except Exception as e:
        return False, f"allin1 을 import 하지 못했다: {type(e).__name__}: {e}"
    try:
        import torch
    except Exception as e:
        return False, f"torch 가 없다: {type(e).__name__}: {e}"
    if not torch.cuda.is_available():
        # CPU 는 곡당 654초다(GPU 3.1초). 돌기는 하지만 하루 배치에 맞지 않는다.
        return True, "GPU 가 없다 — CPU 로도 돌지만 곡당 10분대다"
    return True, ""


# 다른 인터프리터에서 돌릴 때 자식이 실행할 코드. **여기서 하는 일은 모델 실행뿐이다** —
# `mupid.sections/v1` 로 옮기는 일은 부모가 한다. 두 곳에서 변환하면 어긋난다.
_RUNNER = r"""
import json, sys
p = json.loads(sys.stdin.read())
import allin1
allin1.analyze(paths=p["paths"], out_dir=p["out_dir"], model=p["model"],
               device=p["device"], demix_dir=p["demix_dir"], spec_dir=p["spec_dir"],
               keep_byproducts=p["keep_byproducts"], multiprocess=p["multiprocess"],
               overwrite=p["overwrite"])
print("ALLIN1_OK")
"""


@dataclass
class Segmenter:
    """All-In-One 을 감싼다. **모델은 `analyze()` 한 번에 한 번만 올라간다.**

    `allin1` 1.1.0 은 모델을 밖에서 들고 있을 공개 경로를 주지 않는다 — `analyze()`
    안에서 올리고 끝나면 놓는다. 그래서 원본과 같이 **여러 곡을 한 번에 넘긴다**
    (`ALLIN1_BATCH`). 곡마다 부르면 적재가 추론보다 비싸진다.

    ## 어디서 도는가

    `allin1`(natten·demucs)은 파이썬 3.10 짜리 환경이다. GPU 박스의 `mupid-ai` 에는
    **All-In-One 과 MERT(transformers)가 같이 들어 있어** ①②를 한 프로세스에서 돌릴
    수 있다(실측). 그 환경에서 돌면 `python` 을 비워 두고 바로 부른다.

    `music_preprocessing` 본체(3.14)처럼 `allin1` 이 없는 인터프리터에서는 `python` 에
    3.10 환경의 실행 파일을 준다. 그때도 **재구현하지 않는다** — 같은 `analyze()` 를
    자식 프로세스에서 부르고 결과 JSON 만 받아 온다.
    """

    work_dir: Path                       # demix·스펙트로그램 캐시. 곡당 수백 MB 다
    out_dir: Path                        # All-In-One 원본 JSON
    device: str = ALLIN1_DEVICE
    model: str = ALLIN1_MODEL
    batch: int = ALLIN1_BATCH
    python: Optional[str] = None         # 주면 자식 프로세스, 비우면 이 프로세스에서
    timeout_sec: int = ALLIN1_TIMEOUT_S
    overwrite: bool = False
    failures: dict = field(default_factory=dict)

    # ---------------------------------------------------------------- 실행

    def analyze(self, items) -> dict:
        """`[(track_id, audio_path)]` → `{track_id: SectionsFile}`.

        **곡 단위로 격리한다.** 묶음이 통째로 깨지면 한 곡씩 다시 돌려 진짜 원인 곡만
        실패로 남긴다 — 그러지 않으면 한 곡 때문에 그날 들어온 곡이 전부 밀린다.
        실패는 `failures[track_id]` 에 단계·이유로 남고 다음 배치가 그 곡부터 다시 집는다.
        """
        items = [(str(t), Path(p)) for t, p in items]
        self.failures = {}
        Path(self.out_dir).mkdir(parents=True, exist_ok=True)
        Path(self.work_dir).mkdir(parents=True, exist_ok=True)

        todo = []
        for tid, ap in items:
            if not ap.exists():
                self.failures[tid] = {"stage": "segment", "error": f"음원이 없다: {ap}"}
                continue
            todo.append((tid, ap))

        step = max(1, int(self.batch))
        for i in range(0, len(todo), step):
            chunk = todo[i:i + step]
            try:
                self._run([p for _, p in chunk], len(chunk))
            except Exception as e:
                if len(chunk) == 1:
                    self.failures[chunk[0][0]] = _fail(e)
                    continue
                # 묶음이 깨졌다. 어느 곡 때문인지 모르므로 하나씩 다시 돈다.
                for tid, ap in chunk:
                    try:
                        self._run([ap], 1)
                    except Exception as e2:
                        self.failures[tid] = _fail(e2)

        out = {}
        for tid, ap in todo:
            if tid in self.failures:
                continue
            try:
                out[tid] = self.to_sections(tid, ap)
            except Exception as e:
                self.failures[tid] = _fail(e, stage="convert")
        return out

    def _run(self, paths, n: int) -> None:
        kw = dict(out_dir=str(self.out_dir), model=self.model, device=self.device,
                  demix_dir=str(Path(self.work_dir) / DEMIX_DIRNAME),
                  spec_dir=str(Path(self.work_dir) / SPEC_DIRNAME),
                  keep_byproducts=ALLIN1_KEEP_BYPRODUCTS,
                  multiprocess=ALLIN1_MULTIPROCESS, overwrite=bool(self.overwrite))
        if self.python:
            self._run_child(paths, kw, n)
            return
        ok, why = allin1_available()
        if not ok:
            raise SegmenterUnavailable(
                f"{why}. 3.10 환경의 파이썬을 `python=` 으로 주거나 그 환경에서 돌린다.")
        import allin1

        allin1.analyze(paths=[str(p) for p in paths], **kw)

    def _run_child(self, paths, kw: dict, n: int) -> None:
        """다른 인터프리터에서 **같은** `analyze()` 를 부른다."""
        payload = json.dumps({"paths": [str(p) for p in paths], **kw})
        limit = self.timeout_sec * max(1, n)
        try:
            r = subprocess.run([str(self.python), "-c", _RUNNER], input=payload,
                               capture_output=True, text=True, encoding="utf-8",
                               timeout=limit)
        except FileNotFoundError:
            raise SegmenterUnavailable(f"파이썬을 찾지 못했다: {self.python}") from None
        except subprocess.TimeoutExpired:
            raise TimeoutError(f"{limit}초를 넘겼다 ({n}곡)") from None
        if r.returncode != 0 or "ALLIN1_OK" not in (r.stdout or ""):
            tail = "\n".join((r.stderr or "").strip().splitlines()[-STDERR_TAIL:])
            raise RuntimeError(f"분할기 종료 코드 {r.returncode}\n{tail}")

    # ---------------------------------------------------------------- 변환

    def to_sections(self, track_id: str, audio_path: Path) -> SectionsFile:
        """All-In-One 원본 JSON → `mupid.sections/v1`.

        **곡 길이는 All-In-One 이 주지 않는다.** 마지막 구간의 끝은 곡 끝보다 앞일 수
        있다(무음 꼬리). 색인의 `track_dur_sec` 은 `len(y)/24000` 이므로(원본
        `m17_flow_ingest`) 여기서도 ②와 **같은 적재 함수**로 잰다. 이 값이 어긋나면
        `rel_start`·`rel_end` 가 통째로 밀린다.
        """
        p = Path(self.out_dir) / f"{Path(audio_path).stem}.json"
        if not p.exists():
            raise SegmenterUnavailable(f"분할 결과가 없다: {p}")
        d = json.loads(p.read_text(encoding="utf-8"))

        from .embedder import SR, load_audio

        y = load_audio(Path(audio_path))
        dur = len(y) / SR

        f = SectionsFile(
            track_id=str(track_id), duration_sec=float(dur),
            segments=[Segment(label=str(s.get("label", "unknown")),
                              start=float(s["start"]), end=float(s["end"]))
                      for s in d.get("segments", [])],
            segmenter={"name": "allin1", "model": self.model,
                       "version": _allin1_version(), "device": self.device,
                       "bpm": d.get("bpm")})
        problems = validate(f)
        if problems:
            raise SectionsError(f"{track_id}: " + " / ".join(problems))
        return f


def _fail(e: Exception, stage: str = "segment") -> dict:
    return {"stage": stage, "error": f"{type(e).__name__}: {e}"[:600]}


def _allin1_version() -> str:
    try:
        from importlib.metadata import version

        return version("allin1")
    except Exception:
        return ""


def run_segmenter(audio_path: Path, out_path: Path, *, track_id: Optional[str] = None,
                  work_dir: Optional[Path] = None, python: Optional[str] = None,
                  device: str = ALLIN1_DEVICE,
                  timeout_sec: int = ALLIN1_TIMEOUT_S) -> Path:
    """음원 하나를 분할해 `sections.json` 으로 남긴다.

    **곡 하나짜리 편의 함수다.** 배치는 `Segmenter.analyze()` 로 여러 곡을 한 번에
    넘긴다 — 모델 적재가 곡마다 반복되면 그게 추론보다 비싸다.
    """
    ap = Path(audio_path)
    tid = str(track_id or ap.stem)
    out = Path(out_path)
    work = Path(work_dir) if work_dir else out.parent / "allin1_work"
    seg = Segmenter(work_dir=work, out_dir=out.parent / "allin1_raw",
                    device=device, python=python, timeout_sec=timeout_sec)
    got = seg.analyze([(tid, ap)])
    if tid not in got:
        info = seg.failures.get(tid, {"error": "이유를 알 수 없다"})
        raise SegmenterUnavailable(f"{tid} 분할 실패 — {info.get('error')}")
    return dump_sections(got[tid], out)


__all__ = ["SCHEMA", "LABELS", "SectionsError", "SegmenterUnavailable", "SectionsFile",
           "Segmenter", "load_sections", "validate", "dump_sections", "run_segmenter",
           "allin1_available", "ALLIN1_MODEL", "ALLIN1_DEVICE", "ALLIN1_BATCH"]
