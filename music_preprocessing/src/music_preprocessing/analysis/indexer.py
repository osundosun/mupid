"""분석 결과를 색인에 붙인다 — 일 1회 배치의 마지막 칸.

## 갱신 대상은 6개다

색인 4종만 늘리면 신곡이 **오류 없이 반쪽만 들어간다.**

| 파일 | 신곡이 없으면 |
|---|---|
| `moments.parquet` | 구간 자체가 없다 |
| `moments_emb.npy` | 행 수 불일치로 기동 거부 |
| `features_v1.npz` | 같음 |
| `stage1_section.npy` | 같음 |
| `content.parquet` | `trk_*` 다섯 열을 못 만든다 → **285차 생성 자체가 불가** |
| `flow_meta.parquet` | 추천 풀에는 드는데 **조건 검색에서만 조용히 빠진다** |

`clap_moments.npz` 는 대상이 아니다. 없으면 CLAP 재정렬만 빠지는데, 자연어 해석기가
미설정이라 지금은 그 경로가 돌지 않는다.

## 기존 행은 한 줄도 바뀌지 않는다

신곡은 **뒤에만** 붙는다. 기존 행을 밀면 그 번호를 가리키던 `id_map` 과 ClickHouse
딕셔너리가 통째로 어긋나고, 그 상태에서도 서비스는 정상처럼 뜬다.

PCA 기저(`pca_mean`·`pca_comp`·`side_cols`·`evr`)는 **그대로 복사**한다. 다시 계산하지
않는다.

## 파일을 하나씩 바꾸면 원자적이지 않다

`os.replace` 를 6번 부르면 그 사이에 기동한 프로세스가 **섞인 판**을 읽는다. 행 수가
어긋나면 기동이 거부되지만, 더 나쁜 것은 행 수만 맞고 내용이 다른 조합이다.

그래서 **디렉터리 단위로** 바꾼다. 새 판을 옆에 통째로 만들고, 무결성 검사를 통과한
뒤에 이름만 바꾼다.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional, Sequence

import numpy as np
import pandas as pd

# 갱신 대상 6개. 순서는 만드는 차례다.
INDEX_FILES = ("moments.parquet", "moments_emb.npy", "features_v1.npz",
               "stage1_section.npy", "content.parquet", "flow_meta.parquet")
# 새 판으로 그대로 옮겨야 서비스가 뜨는 것들 (갱신하지는 않는다).
CARRY_FILES = ("features_info.json", "feature_stats_v1.npz", "moment_zstats.npz",
               "stage1_weights.npz", "stage1.pt", "logistic_flow_stage1.json",
               "clap_moments.npz", "labels_service.parquet", "id_map.parquet")

EMB_DIM, FEAT_DIM = 1536, 285
MIN_SEC_S = 3.0
BLOCK = 4096              # moments_emb.npy 를 옮길 때의 블록 행 수
KEEP_VERSIONS = 3


class IndexError_(RuntimeError):
    """갱신을 멈춘다. 조용히 덮어쓰지 않는다."""


@dataclass
class NewTrack:
    """색인에 붙일 곡 하나. `pipeline.analyze_track()` 결과 + 메타 두 줄."""

    track_id: str
    source: str                                   # moments.source (flow·jamendo·local)
    track_dur_sec: float
    sections: list[dict]                          # moments 행 (emb_row 는 여기서 채운다)
    embeddings: np.ndarray                        # (n, 1536)
    features: np.ndarray                          # (n, 285)
    section_vecs: np.ndarray                      # (n, 128)
    content_row: dict                             # content.parquet 한 줄
    meta_row: dict                                # flow_meta.parquet 한 줄

    def check(self) -> None:
        n = len(self.sections)
        for name, arr, dim in (("embeddings", self.embeddings, EMB_DIM),
                               ("features", self.features, FEAT_DIM),
                               ("section_vecs", self.section_vecs, None)):
            if len(arr) != n:
                raise IndexError_(f"{self.track_id}: {name} 행 수 {len(arr)} != 구간 {n}")
            if dim and arr.shape[1] != dim:
                raise IndexError_(f"{self.track_id}: {name} 차원 {arr.shape[1]} != {dim}")
        for s in self.sections:
            if float(s["dur_sec"]) < MIN_SEC_S:
                raise IndexError_(
                    f"{self.track_id}: {s['dur_sec']:.2f}초 구간이 있다 "
                    f"— 레시피는 {MIN_SEC_S}초 미만을 버린다")


@dataclass
class StageReport:
    new_dir: Path
    added_tracks: int = 0
    added_sections: int = 0
    rows_before: int = 0
    rows_after: int = 0
    rejected: list[dict] = field(default_factory=list)
    integrity: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return bool(self.integrity.get("ok"))


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


# ───────────────────────────────────────────────────────── 새 판 만들기

def stage(new_tracks: Sequence[NewTrack], data_dir: Path,
          out_dir: Optional[Path] = None) -> StageReport:
    """새 판을 옆에 통째로 만든다. **현재 판은 읽기만 한다.**"""
    src = Path(data_dir)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    dst = Path(out_dir) if out_dir else src.parent / f"{src.name}_v{stamp}"
    if dst.exists():
        raise IndexError_(f"새 판 경로가 이미 있다: {dst}")
    dst.mkdir(parents=True)

    mom = pd.read_parquet(src / "moments.parquet")
    n0 = len(mom)
    rep = StageReport(new_dir=dst, rows_before=n0)

    # 이미 있는 곡은 거부한다. append 가 아니라 기존 행 교체라 성격이 다르다.
    known = set(mom.track_id.astype(str))
    fresh: list[NewTrack] = []
    for t in new_tracks:
        if str(t.track_id) in known:
            rep.rejected.append({
                "track_id": t.track_id, "reason": "이미 색인에 있다 — 교체는 이 경로가 아니다"})
            continue
        try:
            t.check()
        except IndexError_ as e:
            rep.rejected.append({"track_id": t.track_id, "reason": str(e)})
            continue
        fresh.append(t)

    # emb_row 는 기존 N 다음부터 이어 붙인다 (0부터 연속이 무결성 조건이다).
    rows, cursor = [], n0
    for t in fresh:
        for i, s in enumerate(t.sections):
            r = dict(s)
            r.update({"track_id": t.track_id, "emb_row": cursor + i,
                      "source": t.source, "track_dur_sec": t.track_dur_sec})
            rows.append(r)
        cursor += len(t.sections)
    rep.added_tracks, rep.added_sections = len(fresh), cursor - n0
    rep.rows_after = cursor

    _write_moments(src, dst, mom, rows)
    _write_emb(src, dst, fresh, n0, cursor)
    _write_features(src, dst, fresh, n0, cursor)
    _write_section_vecs(src, dst, fresh, n0, cursor)
    _write_meta(src, dst, fresh)
    _carry(src, dst)
    _refresh_info(dst, cursor)

    rep.integrity = check_index(dst, prev=src)
    return rep


def _write_moments(src: Path, dst: Path, mom: pd.DataFrame, rows: list[dict]) -> None:
    if rows:
        add = pd.DataFrame(rows)
        # 열이 빠지면 조회에서 NaN 이 되어 조용히 다르게 동작한다. 순서까지 맞춘다.
        missing = [c for c in mom.columns if c not in add.columns]
        if missing:
            raise IndexError_(f"신곡 구간에 없는 열: {missing}")
        add = add[list(mom.columns)]
        out = pd.concat([mom, add], ignore_index=True)
    else:
        out = mom
    out.to_parquet(dst / "moments.parquet", index=False)


def _write_emb(src: Path, dst: Path, fresh: Sequence[NewTrack],
               n0: int, total: int) -> None:
    """403MB 다. 통째로 올리지 않고 블록 단위로 옮긴다."""
    old = np.load(src / "moments_emb.npy", mmap_mode="r")
    new = np.lib.format.open_memmap(dst / "moments_emb.npy", mode="w+",
                                    dtype=np.float32, shape=(total, old.shape[1]))
    for i in range(0, n0, BLOCK):
        j = min(i + BLOCK, n0)
        new[i:j] = old[i:j]
    cur = n0
    for t in fresh:
        new[cur:cur + len(t.embeddings)] = t.embeddings.astype(np.float32)
        cur += len(t.embeddings)
    new.flush()
    del new, old


def _write_features(src: Path, dst: Path, fresh: Sequence[NewTrack],
                    n0: int, total: int) -> None:
    """PCA 기저는 **그대로 복사**한다. 다시 계산하지 않는다."""
    with np.load(src / "features_v1.npz", allow_pickle=False) as fz:
        X = np.asarray(fz["X"], dtype=np.float32)
        ids = [str(m) for m in fz["moment_id"]]
        carry = {k: np.asarray(fz[k]) for k in fz.files
                 if k not in ("X", "moment_id")}
    blocks = [X] + [t.features.astype(np.float32) for t in fresh]
    for t in fresh:
        ids.extend(str(s["moment_id"]) for s in t.sections)
    Xn = np.concatenate(blocks, axis=0) if len(blocks) > 1 else X
    if len(Xn) != total:
        raise IndexError_(f"features 행 수가 {len(Xn)} 인데 {total} 이어야 한다")
    np.savez(dst / "features_v1.npz", X=Xn,
             moment_id=np.array(ids, dtype="<U80"), **carry)


def _write_section_vecs(src: Path, dst: Path, fresh: Sequence[NewTrack],
                        n0: int, total: int) -> None:
    old = np.load(src / "stage1_section.npy", mmap_mode="r")
    new = np.lib.format.open_memmap(dst / "stage1_section.npy", mode="w+",
                                    dtype=np.float32, shape=(total, old.shape[1]))
    for i in range(0, n0, BLOCK):
        j = min(i + BLOCK, n0)
        new[i:j] = old[i:j]
    cur = n0
    for t in fresh:
        new[cur:cur + len(t.section_vecs)] = t.section_vecs.astype(np.float32)
        cur += len(t.section_vecs)
    new.flush()
    del new, old


def _write_meta(src: Path, dst: Path, fresh: Sequence[NewTrack]) -> None:
    """`content` 는 285차 생성에, `flow_meta` 는 조건 검색에 쓰인다."""
    for name, attr in (("content.parquet", "content_row"),
                       ("flow_meta.parquet", "meta_row")):
        base = pd.read_parquet(src / name)
        add = [getattr(t, attr) for t in fresh if getattr(t, attr)]
        if add:
            df = pd.DataFrame(add)
            missing = [c for c in base.columns if c not in df.columns]
            if missing:
                # 서빙이 쓰는 5열이 빠지면 특징을 못 만든다. 나머지는 비워도 된다.
                for c in missing:
                    df[c] = pd.NA
            df = df[list(base.columns)]
            base = pd.concat([base, df], ignore_index=True)
            base = base.drop_duplicates("track_id", keep="last")
        base.to_parquet(dst / name, index=False)


def _carry(src: Path, dst: Path) -> None:
    """갱신하지 않지만 새 판에 있어야 서비스가 뜨는 것들."""
    for n in CARRY_FILES:
        p = src / n
        if p.exists():
            shutil.copy2(p, dst / n)
    for sub in ("logs", "serve"):
        s = src / sub
        if s.is_dir():
            shutil.copytree(s, dst / sub, dirs_exist_ok=True)


def _refresh_info(dst: Path, total: int) -> None:
    """`features_sha256` 을 새 값으로 고친다.

    동결 통계 로더가 이 해시를 검사한다. 안 고치면 **다음 배치가 오류로 멈춘다.**
    """
    p = dst / "features_info.json"
    info = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    info["features_rows"] = int(total)
    info["features_sha256"] = sha256(dst / "features_v1.npz")
    p.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n",
                 encoding="utf-8")


# ───────────────────────────────────────────────────────── 무결성 게이트

def check_index(new: Path, prev: Optional[Path] = None) -> dict:
    """교체 **전에** 돌린다. 하나라도 실패하면 교체하지 않는다."""
    out: dict = {"ok": False, "checks": {}, "errors": []}
    try:
        mom = pd.read_parquet(new / "moments.parquet")
        rows = {
            "moments": len(mom),
            "moments_emb": int(np.load(new / "moments_emb.npy", mmap_mode="r").shape[0]),
            "features_v1": int(_npz_rows(new / "features_v1.npz")),
            "stage1_section": int(np.load(new / "stage1_section.npy",
                                          mmap_mode="r").shape[0]),
        }
        out["checks"]["rows"] = rows
        if len(set(rows.values())) != 1:
            out["errors"].append(f"색인 행 수가 어긋난다: {rows}")

        if not bool((mom.emb_row.to_numpy() == np.arange(len(mom))).all()):
            out["errors"].append("emb_row 가 0부터 연속이 아니다")
        dup = int(mom.moment_id.duplicated().sum())
        out["checks"]["duplicate_moment_id"] = dup
        if dup:
            out["errors"].append(f"moment_id 가 {dup}건 중복이다")

        short = int((mom.dur_sec.astype(float) < MIN_SEC_S).sum())
        out["checks"]["short_sections"] = short
        if short:
            out["errors"].append(f"{MIN_SEC_S}초 미만 구간이 {short}건 — 레시피와 어긋난다")

        # 신곡이 메타 두 표에 모두 있는가. 없으면 285차를 못 만들거나 검색에서 빠진다.
        content = pd.read_parquet(new / "content.parquet", columns=["track_id"])
        meta = pd.read_parquet(new / "flow_meta.parquet", columns=["track_id"])
        pool = set(mom[mom.source.isin(("flow", "jamendo"))].track_id.astype(str))
        miss_c = sorted(pool - set(content.track_id.astype(str)))
        miss_m = sorted(pool - set(meta.track_id.astype(str)))
        out["checks"]["missing_content"] = len(miss_c)
        out["checks"]["missing_flow_meta"] = len(miss_m)
        if miss_c:
            out["errors"].append(f"content.parquet 에 없는 곡 {len(miss_c)}개: {miss_c[:5]}")
        if miss_m:
            # 조건 검색에서만 빠지는 것이라 오류로 본다 — 조용히 넘기면 못 찾는다.
            out["errors"].append(
                f"flow_meta.parquet 에 없는 곡 {len(miss_m)}개: {miss_m[:5]}")

        if prev is not None:
            out["errors"] += _compare_with_prev(new, Path(prev), out["checks"])
    except Exception as e:
        out["errors"].append(f"{type(e).__name__}: {e}")
    out["ok"] = not out["errors"]
    return out


def _npz_rows(p: Path) -> int:
    with np.load(p, allow_pickle=False) as d:
        return d["X"].shape[0]


def _compare_with_prev(new: Path, prev: Path, checks: dict) -> list[str]:
    """이전 판의 N행이 **한 줄도 바뀌지 않았는지** 전량 비교한다.

    표본이 아니라 전부 본다. 수 초면 끝나고, 어긋나면 그 상태로도 서비스가 정상 기동한다.
    """
    errs: list[str] = []
    with np.load(prev / "features_v1.npz", allow_pickle=False) as a, \
            np.load(new / "features_v1.npz", allow_pickle=False) as b:
        n0 = a["X"].shape[0]
        for k in ("pca_mean", "pca_comp", "side_cols", "evr"):
            if k not in a.files:
                continue
            if k not in b.files or not np.array_equal(a[k], b[k]):
                errs.append(f"PCA 기저 {k} 가 이전 판과 다르다 — 다시 적합한 흔적이다")
        if not np.array_equal(a["X"], b["X"][:n0]):
            errs.append("기존 구간의 285차 특징이 바뀌었다")

    for name in ("moments_emb.npy", "stage1_section.npy"):
        o = np.load(prev / name, mmap_mode="r")
        n = np.load(new / name, mmap_mode="r")
        if not np.array_equal(np.asarray(o), np.asarray(n[:len(o)])):
            errs.append(f"기존 행이 바뀌었다: {name}")

    om = pd.read_parquet(prev / "moments.parquet")
    nm = pd.read_parquet(new / "moments.parquet")
    if not om.equals(nm.iloc[:len(om)][om.columns]):
        errs.append("기존 구간 행이 바뀌었다: moments.parquet")
    checks["prev_rows"] = len(om)
    return errs


# ───────────────────────────────────────────────────────── 교체

def swap(data_dir: Path, new_dir: Path, *, keep: int = KEEP_VERSIONS) -> dict:
    """디렉터리 이름만 바꾼다. 파일을 하나씩 바꾸지 않는다.

    **완전한 원자 교체는 아니다.** rename 이 둘이라 그 사이에 기동한 프로세스는 색인
    디렉터리를 못 찾는다 — 기동 실패다. 섞인 판을 읽는 것보다 낫다(그쪽은 정상처럼
    뜨면서 엉뚱한 곡을 추천한다).

    K8s 라면 `MUPID_AI_ARTIFACT_DIR` 를 새 판으로 바꿔 재기동하는 쪽이 더 단순하다 —
    교체 자체가 없어지고 롤백은 환경변수를 되돌리는 일이 된다.
    """
    src, new = Path(data_dir), Path(new_dir)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = src.parent / f"{src.name}_prev{stamp}"

    src.rename(backup)
    try:
        new.rename(src)
    except Exception:
        backup.rename(src)          # 되돌린다. 중간 상태로 두지 않는다
        raise
    pruned = _prune(src.parent, src.name, keep)
    return {"active": str(src), "backup": str(backup), "pruned": pruned}


def rollback(data_dir: Path, backup_dir: Path) -> dict:
    """이전 판으로 되돌린다. 교체 전과 같은 응답이 나와야 한다."""
    src, back = Path(data_dir), Path(backup_dir)
    if not back.is_dir():
        raise IndexError_(f"되돌릴 판이 없다: {back}")
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    aside = src.parent / f"{src.name}_rolledback{stamp}"
    src.rename(aside)
    back.rename(src)
    return {"active": str(src), "set_aside": str(aside)}


def _prune(parent: Path, name: str, keep: int) -> list[str]:
    """오래된 판을 지운다. 롤백용으로 몇 판은 남긴다."""
    olds = sorted((p for p in parent.glob(f"{name}_prev*") if p.is_dir()),
                  key=lambda p: p.name)
    drop = olds[:-keep] if keep > 0 else olds
    for p in drop:
        shutil.rmtree(p, ignore_errors=True)
    return [p.name for p in drop]


def restart_notice(rep: StageReport) -> str:
    """교체만으로는 끝나지 않는다.

    서버는 기동 때 `Catalog` 으로 색인을 통째로 메모리에 올린다. 파일을 바꿔도 돌고
    있는 프로세스는 옛 색인을 계속 쓴다 — 오류는 나지 않고 신곡만 추천에 안 나온다.
    """
    return (f"색인 교체 완료 — 곡 {rep.added_tracks} · 구간 {rep.added_sections} "
            f"({rep.rows_before} → {rep.rows_after}행)\n"
            f"**서비스를 다시 띄워야 반영된다.** K8s: kubectl rollout restart deploy/ai-server")
