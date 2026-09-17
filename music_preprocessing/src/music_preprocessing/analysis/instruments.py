"""구간별 악기 — 분할기가 이미 남긴 demucs 스템에서 읽는다.

`music_analysis_instruments` 는 곡이 아니라 **구간**에 매달린다. 같은 곡이라도
인트로에는 건반만, 후렴에는 드럼·베이스가 들어간다. 그 차이를 내려면 구간마다
무엇이 울리는지 재야 한다.

## 왜 새 모델을 붙이지 않는가

All-In-One 이 분할하면서 demucs 로 이미 스템을 나누고, `keep_byproducts=True` 라
그 파일이 작업 디렉터리에 남아 있다(`segmenter.ALLIN1_KEEP_BYPRODUCTS`). 곡당 GPU
시간을 한 번 더 쓰지 않고 그 결과를 읽기만 한다.

대신 **demucs 는 4스템이다.** 피아노와 기타를 따로 가르지 못한다. 그래서 어휘가
넷뿐이고, 셋은 악기 이름이지만 넷째(`other`)는 "나머지 전부" 다 — 이름을
`그 외 악기` 로 둔 이유다. 없는 해상도를 있는 것처럼 쓰지 않는다.

## 판정

구간에서 스템이 **전체 소리에 견줘** 얼마나 큰가로 본다.

    들린다  ⇔  rms(스템, 구간) ≥ MIX_REL × rms(믹스, 구간)  그리고  ≥ ABS_FLOOR

스템 자기 분포와 견주지 않는 이유가 있다. 보컬이 아예 없는 연주곡도 보컬 스템에
누설(bleed)이 남는데, 그 스템 안에서만 비교하면 누설의 최댓값이 기준이 되어
**모든 구간에 보컬이 있다**고 답한다. 믹스와 견주면 누설은 작은 비율로 남는다.

`ABS_FLOOR` 는 수치적 무음을 거르는 바닥이다. 구간이 통째로 조용하면 믹스도
작아서 비율만으로는 아무거나 통과한다.

## 이 문턱값은 아직 실측으로 맞춘 것이 아니다

실제 스템으로 분포를 본 적이 없다(분석 대상 음원이 이 기계에 없다). 그래서

- 값을 상수 하나로 모아 두고 인자로 바꿀 수 있게 했다,
- `section_levels()` 가 판정이 아니라 **비율 자체**를 돌려준다,
- `python -m music_preprocessing.analysis.instruments` 로 곡 하나의 표를 볼 수 있다.

첫 실주행에서 그 표를 보고 고친다. 지금 값은 -20 dB(0.1) 로, "제대로 들어간 파트는
믹스의 -20 dB 안쪽, 누설은 그 아래" 라는 통상적인 관찰에서 잡은 출발점이다.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Callable, Optional, Sequence

import numpy as np

from .embedder import SR, Segment, keep_segments, load_audio

# demucs 스템 이름 → `codes.code_name` (`CodeType.INSTRUMENT`).
#
# 부트스트랩은 INSTRUMENT 를 심지 않는다 — 확정된 목록이 없어서다. 배치가
# `POST /api/v1/code` 로 이 넷을 먼저 넣고 구간을 올린다.
STEMS: tuple[tuple[str, str], ...] = (
    ("vocals", "보컬"),
    ("drums", "드럼"),
    ("bass", "베이스"),
    ("other", "그 외 악기"),
)
CODE_NAMES: tuple[str, ...] = tuple(name for _, name in STEMS)

MIX_REL = 0.1          # 믹스 대비 -20 dB. 위 머리말 참고 — 실측으로 맞춘 값이 아니다
ABS_FLOOR = 1e-3       # -60 dBFS. 수치적 무음

STEM_EXTS = (".wav", ".flac", ".mp3", ".ogg")


class StemsMissing(RuntimeError):
    """스템을 찾지 못했다. **빈 악기 목록으로 넘어가지 않는다.**

    조용히 비워 두면 "이 곡은 악기가 없다" 와 "재지 못했다" 가 같아 보인다. 부르는
    쪽이 잡아서 그 곡만 건너뛰거나 실패로 남긴다.
    """


def find_stem_dir(demix_root: Path, *names: str) -> Optional[Path]:
    """`demix_root` 아래에서 그 곡의 스템 디렉터리를 찾는다.

    All-In-One 은 demucs 모델 이름으로 한 겹 더 판다(`<demix>/htdemucs/<name>/`).
    모델을 바꾸면 그 이름도 바뀌므로 **경로를 박아 두지 않고 찾는다.**

    `names` 를 여럿 받는 이유도 같다. 폴더 이름이 입력 파일의 stem 인지 파일명
    전체인지는 demucs 판에 달렸고(`1234.audio` → `1234` / `1234.audio`), 이 기계에서는
    확인할 길이 없다. 둘 다 본다 — 못 찾으면 그 곡의 악기가 통째로 빈다.
    """
    root = Path(demix_root)
    if not root.is_dir():
        return None
    for name in names:
        if not name:
            continue
        for candidate in [root / name, *sorted(root.glob("*/" + name))]:
            if candidate.is_dir() and _stem_file(candidate, "vocals") is not None:
                return candidate
    return None


def _stem_file(stem_dir: Path, stem: str) -> Optional[Path]:
    for ext in STEM_EXTS:
        p = Path(stem_dir) / (stem + ext)
        if p.exists():
            return p
    return None


def load_stems(stem_dir: Path, *, load: Optional[Callable] = None) -> dict:
    """네 스템을 믹스와 **같은 적재 규약**(24kHz mono)으로 읽는다.

    규약이 어긋나면 비율이 통째로 틀어진다. 하나라도 없으면 멈춘다 — 셋만 있는 채로
    답하면 "베이스가 없는 곡" 이 되어 버린다.
    """
    reader = load or load_audio
    out = {}
    missing = []
    for stem, _ in STEMS:
        p = _stem_file(Path(stem_dir), stem)
        if p is None:
            missing.append(stem)
            continue
        out[stem] = np.asarray(reader(p), dtype=np.float32)
    if missing:
        raise StemsMissing(f"{stem_dir}: 스템이 없다 — {', '.join(missing)}")
    return out


def rms(y: np.ndarray, start: float, end: float, sr: int = SR) -> float:
    """구간의 실효값. 경계는 배열 길이로 자른다.

    demucs 출력은 믹스보다 몇 프레임 길거나 짧다. 길이를 맞추려 들지 않고 있는
    만큼만 본다 — 구간 하나가 수 초라 끝의 몇 샘플은 영향이 없다.
    """
    a = max(0, int(round(float(start) * sr)))
    b = min(len(y), int(round(float(end) * sr)))
    if b <= a:
        return 0.0
    window = y[a:b]
    return float(np.sqrt(np.mean(np.square(window, dtype=np.float64))))


def section_levels(y_mix: np.ndarray, stems: dict,
                   segments: Sequence[Segment]) -> list[dict]:
    """구간마다 `{스템: 믹스 대비 비율}`. **판정하지 않는다.**

    문턱값을 맞추려면 판정이 아니라 분포를 봐야 한다. 그래서 비율을 그대로 낸다.
    """
    out = []
    for s in segments:
        mix = rms(y_mix, s.start, s.end)
        row = {}
        for stem, _ in STEMS:
            level = rms(stems[stem], s.start, s.end)
            row[stem] = {"rms": level, "ratio": (level / mix) if mix > 0 else 0.0}
        row["mix"] = {"rms": mix, "ratio": 1.0}
        out.append(row)
    return out


def section_instruments(y_mix: np.ndarray, stems: dict, segments: Sequence[Segment], *,
                        mix_rel: float = MIX_REL,
                        abs_floor: float = ABS_FLOOR) -> list[list[str]]:
    """구간마다 들리는 악기의 **코드 이름** 목록.

    순서는 `STEMS` 순이다 — 화면에 뜨는 순서가 곡마다 흔들리지 않게.
    """
    out = []
    for row in section_levels(y_mix, stems, segments):
        names = [name for stem, name in STEMS
                 if row[stem]["rms"] >= abs_floor and row[stem]["ratio"] >= mix_rel]
        out.append(names)
    return out


def for_track(audio_path: Path, segments: Sequence[Segment], *,
              demix_root: Optional[Path] = None, stem_dir: Optional[Path] = None,
              y_mix: Optional[np.ndarray] = None,
              load: Optional[Callable] = None,
              mix_rel: float = MIX_REL,
              abs_floor: float = ABS_FLOOR) -> list[list[str]]:
    """곡 하나의 구간별 악기. `stem_dir` 이나 `demix_root` 중 하나가 필요하다.

    `segments` 는 **이미 거른 구간**이어야 한다 (`keep_segments`). 여기서 다시
    거르면 색인의 `emb_row` 와 순번이 어긋난다 — 거르는 규칙은 `embedder` 한 곳이다.
    """
    ap = Path(audio_path)
    d = Path(stem_dir) if stem_dir else find_stem_dir(Path(demix_root or ""),
                                                      ap.stem, ap.name)
    if d is None:
        raise StemsMissing(f"{ap.stem}: demix 결과를 찾지 못했다 ({demix_root})")
    reader = load or load_audio
    mix = np.asarray(y_mix if y_mix is not None else reader(ap), dtype=np.float32)
    stems = load_stems(d, load=reader)
    return section_instruments(mix, stems, segments, mix_rel=mix_rel, abs_floor=abs_floor)


# ───────────────────────────────────────────────── 문턱값을 맞추기 위한 표

def main(argv: Optional[Sequence[str]] = None) -> int:
    """곡 하나의 구간별 비율을 표로 찍는다. **판정 전에 분포를 본다.**

        python -m music_preprocessing.analysis.instruments \\
            --audio work/1234.audio --sections work/1234.sections.json \\
            --demix work/allin1/demix
    """
    import argparse

    from .segmenter import load_sections

    p = argparse.ArgumentParser(description="구간별 스템 비율 (dB)")
    p.add_argument("--audio", required=True)
    p.add_argument("--sections", required=True)
    p.add_argument("--demix", help="allin1 의 demix 루트")
    p.add_argument("--stem-dir", help="스템이 바로 들어 있는 디렉터리")
    p.add_argument("--mix-rel", type=float, default=MIX_REL)
    p.add_argument("--json", action="store_true", help="표 대신 JSON")
    a = p.parse_args(argv)

    audio = Path(a.audio)
    segments = keep_segments(load_sections(Path(a.sections)).segments)
    stem_dir = (Path(a.stem_dir) if a.stem_dir
                else find_stem_dir(Path(a.demix or ""), audio.stem, audio.name))
    if stem_dir is None:
        print(f"스템을 찾지 못했다: {a.demix}")
        return 1

    mix = load_audio(audio)
    stems = load_stems(stem_dir)
    levels = section_levels(mix, stems, segments)
    names = section_instruments(mix, stems, segments, mix_rel=a.mix_rel)

    if a.json:
        print(json.dumps([{"section_idx": i, "label": s.label,
                           "start": s.start, "end": s.end,
                           "levels": {k: v["ratio"] for k, v in row.items()},
                           "instruments": got}
                          for i, (s, row, got) in enumerate(zip(segments, levels, names))],
                         ensure_ascii=False, indent=2))
        return 0

    head = f"{'idx':>3} {'label':<10}" + "".join(f"{stem:>9}" for stem, _ in STEMS) + "   판정"
    print(head)
    print("-" * len(head))
    for i, (s, row, got) in enumerate(zip(segments, levels, names)):
        cells = "".join(f"{_db(row[stem]['ratio']):>9}" for stem, _ in STEMS)
        print(f"{i:>3} {str(s.label):<10}{cells}   {' · '.join(got) or '—'}")
    print(f"\n문턱값 {_db(a.mix_rel)} (믹스 대비) · 무음 바닥 {ABS_FLOOR}")
    return 0


def _db(ratio: float) -> str:
    if ratio <= 0:
        return "-inf"
    return f"{20 * np.log10(ratio):.1f}"


__all__ = ["STEMS", "CODE_NAMES", "MIX_REL", "ABS_FLOOR", "StemsMissing",
           "find_stem_dir", "load_stems", "rms", "section_levels",
           "section_instruments", "for_track", "main"]


if __name__ == "__main__":
    raise SystemExit(main())
