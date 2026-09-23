"""하루 1회 배치 — 단계가 실제로 이어지는가.

앞의 시험들이 단계 하나씩을 봤다면, 여기는 **연결**을 본다. 제일 중요한 것은 순서다.

> 등록이 끝나기 전에는 색인을 교체하지 않는다.

교체를 먼저 하면 추천기가 색인 풀에서 신곡을 고른 뒤 `im.music_id()` 에서 막혀
**요청 전체가 404** 가 되고, `/internal/ready` 는 503 이 된다. 즉 등록이 끝날 때까지
서비스가 멈춘다. 그 순서를 코드가 지키는지 확인한다.

음원은 없다. 기존 구간을 복제해 새 `track_id` 를 붙이고, 무거운 MERT 대신 저장본에서
읽은 배열을 그대로 돌려주는 `analyze` 를 끼운다 — 이 시험이 보는 것은 계산이 아니라
단계의 연결·순서·격리다.

**진짜 산출물은 읽기만 한다.** 임시 폴더에 사본을 떠서 거기서 교체·롤백한다.
MySQL·ClickHouse 에는 붙지 않는다 — 등록기를 주입하기 때문에 배치가 바깥으로 나가는
길 자체가 없다.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

from ai_server.app import paths                                      # noqa: E402

bt = pytest.importorskip("music_preprocessing.analysis.batch",
                         reason="analysis 선택 의존성이 없다")
ix = pytest.importorskip("music_preprocessing.analysis.indexer")
pl = pytest.importorskip("music_preprocessing.analysis.pipeline")
sg = pytest.importorskip("music_preprocessing.analysis.segmenter")
pd = pytest.importorskip("pandas")

from test_index_update import NEEDED, make_seed                      # noqa: E402

D = paths.data_dir()
MISSING = [n for n in NEEDED if not (D / n).exists()]
pytestmark = pytest.mark.skipif(bool(MISSING), reason=f"산출물이 없다: {MISSING}")

# 시작 시점의 진짜 산출물 해시. 마지막에 다시 재서 **읽기만 했는지** 확인한다.
BASE_SHA = ix.sha256(D / "features_v1.npz") if not MISSING else ""


# ────────────────────────────────────────────────────── 판 한 벌

@pytest.fixture(scope="module")
def sandbox(tmp_path_factory):
    """산출물 사본 + 전량 매핑표.

    `/internal/ready` 는 추천 풀 **전량**이 매핑돼야 200 이다. 실제 산출물에는
    `id_map.parquet` 이 없으므로(그래서 기존 스모크 7건이 실패한다) 여기서 만든다.
    숫자는 시험용이고 실제 MySQL 과는 무관하다 — 사본 안에서만 쓴다.
    """
    root = tmp_path_factory.mktemp("batch")
    data = root / "data" / "proc" / "mupid"
    data.mkdir(parents=True)
    for p in D.iterdir():
        if p.is_file():
            shutil.copy2(p, data / p.name)
    write_id_map(data)
    return root, data


def write_id_map(data: Path, extra: list | None = None,
                 exclude: set | None = None) -> Path:
    """색인에 있는 곡·구간 전부에 숫자를 붙인다. 1:1 이라야 `IdMap` 이 받는다."""
    mom = pd.read_parquet(data / "moments.parquet", columns=["track_id", "moment_id"])
    tids = list(dict.fromkeys(mom.track_id.astype(str)))
    for t in extra or []:
        if str(t.track_id) not in tids:
            tids.append(str(t.track_id))
    num = {t: i + 1 for i, t in enumerate(tids)}

    uids = list(mom.moment_id.astype(str))
    for t in extra or []:
        uids.extend(str(s["moment_id"]) for s in t.sections)
    uids = list(dict.fromkeys(uids))

    df = pd.DataFrame({
        "external_id": uids,
        "music_section_analysis_id": np.arange(1, len(uids) + 1, dtype="int64"),
    })
    df["source_track_id"] = df.external_id.str.split("#").str[0]
    df["music_id"] = df.source_track_id.map(num).astype("int64")
    df = df[["source_track_id", "music_id", "external_id",
             "music_section_analysis_id"]]
    if exclude:
        # 등록되지 않은 곡. 색인에는 있는데 숫자가 없는 상태를 만든다.
        df = df[~df.source_track_id.isin({str(x) for x in exclude})]
    out = data / "id_map.parquet"
    df.to_parquet(out, index=False)
    return out


# ────────────────────────────────────────────────────── 시드 입력

def make_inputs(data: Path, tag: str, n: int = 2, skip: int = 0):
    """`BatchTrack` 목록과, MERT 를 대신할 `analyze` 를 만든다."""
    seeds = make_seed(data, n, prefix=f"BATCH{tag}_", skip=skip)
    work = data.parent / f"work_{tag}"
    work.mkdir(parents=True, exist_ok=True)
    tracks, made = [], {}
    for t in seeds:
        audio = work / f"{t.track_id}.wav"
        # 음원은 해시 대상일 뿐이다. 내용은 곡마다 다르기만 하면 된다.
        audio.write_bytes(t.track_id.encode() + b"\0" * 64)
        sj = work / f"{t.track_id}.sections.json"
        sg.dump_sections(sg.SectionsFile(
            track_id=t.track_id, duration_sec=float(t.track_dur_sec),
            segments=[sg.Segment(label=str(s["section_label"]),
                                 start=float(s["start_sec"]),
                                 end=float(s["end_sec"])) for s in t.sections],
            segmenter={"name": "allin1", "model": "harmonix-all"}), sj)
        tracks.append(bt.BatchTrack(
            track_id=t.track_id, audio_path=audio, sections_json=sj,
            content_row=dict(t.content_row), meta_row=dict(t.meta_row),
            source="flow"))
        made[t.track_id] = t

    def analyze(track, sections, meta):
        """저장본에서 읽은 배열을 그대로 돌려준다 — MERT 를 돌리지 않는다."""
        t = made[track.track_id]
        r = pl.TrackResult(track_id=track.track_id, status="ok", stage="done",
                           n_sections=len(t.sections))
        r.rows = [dict(s) for s in t.sections]
        r.embeddings, r.features, r.section_vecs = (
            t.embeddings, t.features, t.section_vecs)
        return r

    return tracks, analyze, made


class FakeRegistrar:
    """등록이 붙었을 때를 흉내낸다. **숫자는 새 판 안에서만 쓴다.**

    실제 구현은 Spring `요구 1` 을 부르고 받은 숫자로 같은 파일을 쓴다. 여기서 중요한
    것은 **새 판 안의 `id_map.parquet` 을 갱신한다**는 점이다 — 새 판에는 교체 전의
    낡은 매핑표가 복사돼 있어서, 갱신하지 않으면 확인 단계가 반드시 막힌다.
    """

    def __init__(self, ok: bool = True, partial: bool = False):
        self.ok, self.partial, self.calls = ok, partial, 0

    def available(self):
        return (True, "") if self.ok else (False, "시험: 등록기를 막아 두었다")

    def register(self, tracks, new_dir):
        self.calls += 1
        skip = {str(tracks[-1].track_id)} if (self.partial and len(tracks) > 1) else set()
        write_id_map(Path(new_dir), extra=list(tracks), exclude=skip)
        return {"registered": len(tracks) - len(skip)}


def cleanup(data: Path) -> list[str]:
    """시드가 만든 판을 지운다. 남기면 다음 시험이 옛 판을 본다."""
    left = []
    for p in data.parent.glob(f"{data.name}_*"):
        if p.is_dir():
            left.append(p.name)
            shutil.rmtree(p, ignore_errors=True)
    return left


# ────────────────────────────────────────────────────── 1. 끝까지 돈다

def test_summary_is_written(sandbox, tmp_path):
    root, data = sandbox
    tracks, analyze, _ = make_inputs(data, "sum", 1)
    s = bt.run(tracks, data, state_path=tmp_path / "state.json",
               summary_path=tmp_path / "sum.jsonl", analyze=analyze)

    line = (tmp_path / "sum.jsonl").read_text(encoding="utf-8").strip()
    got = json.loads(line)
    print(f"\n요약: {line[:200]}")
    assert got["tracks"] == 1 and got["ok"] == 1
    assert got["added_sections"] > 0
    assert got["exit_code"] == s.exit_code
    assert got["staged_dir"], "새 판 경로가 요약에 없다"
    cleanup(data)


# ────────────────────────────────────────────────────── 2. 0번 규칙

def test_no_swap_when_registration_is_unavailable(sandbox, tmp_path):
    """등록할 수 없으면 새 판만 만들고 **교체하지 않는다.**

    이 순서를 어기면 교체 직후부터 등록이 끝날 때까지 추천이 통째로 멈춘다.
    """
    root, data = sandbox
    before = len(pd.read_parquet(data / "moments.parquet"))
    tracks, analyze, _ = make_inputs(data, "hold", 2, skip=2)

    s = bt.run(tracks, data, state_path=tmp_path / "state.json",
               summary_path=tmp_path / "sum.jsonl",
               registrar=bt.UnavailableRegistrar(), analyze=analyze)

    assert s.exit_code == bt.EXIT_HOLD
    assert not s.swapped
    assert "보류" in s.hold_reason
    after = len(pd.read_parquet(data / "moments.parquet"))
    assert after == before, f"교체하지 않기로 했는데 색인이 {before} → {after}"
    assert Path(s.staged_dir).is_dir(), "새 판을 지워 버리면 등록 뒤 다시 분석해야 한다"
    print(f"\n보류 — {s.hold_reason[:80]}")
    cleanup(data)


def test_no_swap_when_mapping_is_incomplete(sandbox, tmp_path):
    """등록이 절반만 됐으면 그것도 보류다. 매핑 없는 곡이 뽑히면 요청이 404 다."""
    root, data = sandbox
    before = len(pd.read_parquet(data / "moments.parquet"))
    tracks, analyze, _ = make_inputs(data, "half", 2, skip=4)

    s = bt.run(tracks, data, state_path=tmp_path / "state.json",
               summary_path=tmp_path / "sum.jsonl",
               registrar=FakeRegistrar(partial=True), analyze=analyze)

    assert s.exit_code == bt.EXIT_HOLD and not s.swapped
    assert "매핑" in s.hold_reason
    assert len(pd.read_parquet(data / "moments.parquet")) == before
    cleanup(data)


# ────────────────────────────────────────────────────── 3. 등록되면 교체된다

def _probe(root: Path) -> dict:
    """새 프로세스로 앱을 띄워 `/health` 와 `/internal/ready` 를 본다."""
    script = """
import json
from fastapi.testclient import TestClient
from ai_server.app.main import app

out = {}
client = TestClient(app, raise_server_exceptions=False)
with client:
    h = client.get("/health")
    out["health"] = h.status_code
    out["rows"] = (h.json().get("integrity") or {}).get("rows")
    r = client.get("/internal/ready")
    out["ready"] = r.status_code
    out["ready_body"] = str(r.json())[:300]
print("PROBE " + json.dumps(out, ensure_ascii=False))
"""
    import os

    env = dict(os.environ)
    env["MUPID_AI_ARTIFACT_DIR"] = str(root)
    env["PYTHONIOENCODING"] = "utf-8"
    p = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True,
                       cwd=str(REPO_ROOT), env=env, encoding="utf-8")
    for line in (p.stdout or "").splitlines():
        if line.startswith("PROBE "):
            return json.loads(line[6:])
    pytest.skip(f"앱을 띄우지 못했다: {(p.stderr or '')[-400:]}")


def test_swap_happens_after_registration(sandbox, tmp_path):
    root, data = sandbox
    before = _probe(root)
    rows_before = (before.get("rows") or {}).get("moments")
    print(f"\n교체 전  /health {before['health']} moments {rows_before} "
          f"· /internal/ready {before['ready']}")
    if before["ready"] != 200:
        pytest.skip(f"교체 전부터 준비되지 않았다: {before['ready_body'][:200]}")

    tracks, analyze, _ = make_inputs(data, "swap", 2, skip=6)
    reg = FakeRegistrar()
    s = bt.run(tracks, data, state_path=tmp_path / "state.json",
               summary_path=tmp_path / "sum.jsonl", registrar=reg, analyze=analyze)

    assert s.exit_code == bt.EXIT_OK, s.hold_reason
    assert s.swapped and s.restart_required
    assert reg.calls == 1

    after = _probe(root)
    rows_after = (after.get("rows") or {}).get("moments")
    print(f"교체 후  /health {after['health']} moments {rows_after} "
          f"· /internal/ready {after['ready']}")
    assert rows_after == rows_before + s.added_sections
    assert after["ready"] == 200, after["ready_body"]


# ────────────────────────────────────────────────────── 4. 곡 단위 격리

def test_one_broken_track_does_not_stop_the_rest(sandbox, tmp_path):
    root, data = sandbox
    tracks, analyze, _ = make_inputs(data, "iso", 2, skip=8)
    # 첫 곡의 구간 파일을 깨뜨린다 — 구간이 겹치면 같은 프레임이 두 번 들어간다.
    d = json.loads(tracks[0].sections_json.read_text(encoding="utf-8"))
    d["segments"][1]["start"] = d["segments"][0]["start"]
    tracks[0].sections_json.write_text(json.dumps(d), encoding="utf-8")

    s = bt.run(tracks, data, state_path=tmp_path / "state.json",
               summary_path=tmp_path / "sum.jsonl",
               registrar=FakeRegistrar(), analyze=analyze)

    assert s.failed == 1 and s.ok == 1
    assert s.failures[0]["track_id"] == tracks[0].track_id
    assert s.failures[0]["stage"] == "sections"
    assert s.swapped, "나머지 한 곡은 반영돼야 한다"
    assert s.exit_code == bt.EXIT_PARTIAL, "일부 실패는 0 이 아닌 코드로 끝난다"
    print(f"\n실패 {s.failures[0]['track_id']} ({s.failures[0]['stage']}) "
          f"· 반영 {s.added_tracks}곡")


# ────────────────────────────────────────────────────── 5. 두 번 돌려도 같다

def test_second_run_skips_by_sha256(sandbox, tmp_path):
    root, data = sandbox
    state = tmp_path / "state.json"
    tracks, analyze, _ = make_inputs(data, "twice", 1, skip=10)

    first = bt.run(tracks, data, state_path=state,
                   summary_path=tmp_path / "sum.jsonl",
                   registrar=FakeRegistrar(), analyze=analyze)
    assert first.swapped
    rows = len(pd.read_parquet(data / "moments.parquet"))

    second = bt.run(tracks, data, state_path=state,
                    summary_path=tmp_path / "sum.jsonl",
                    registrar=FakeRegistrar(), analyze=analyze)
    assert second.skipped == 1 and second.ok == 0
    assert not second.swapped and second.exit_code == bt.EXIT_OK
    assert len(pd.read_parquet(data / "moments.parquet")) == rows, "두 번째가 또 붙였다"
    print(f"\n두 번째 — 건너뜀 {second.skipped} · 색인 {rows}행 그대로")


def test_changed_audio_is_not_reprocessed(sandbox, tmp_path):
    """음원이 바뀐 곡은 `changed` 로만 남는다. 기존 행 교체는 이 경로가 아니다."""
    root, data = sandbox
    state = tmp_path / "state.json"
    tracks, analyze, _ = make_inputs(data, "chg", 1, skip=11)
    bt.run(tracks, data, state_path=state, summary_path=tmp_path / "s.jsonl",
           registrar=FakeRegistrar(), analyze=analyze)

    tracks[0].audio_path.write_bytes(b"different audio")
    s = bt.run(tracks, data, state_path=state, summary_path=tmp_path / "s.jsonl",
               registrar=FakeRegistrar(), analyze=analyze)
    assert s.changed == 1 and s.ok == 0 and not s.swapped
    assert s.changed_tracks[0]["track_id"] == tracks[0].track_id


# ──────────────────────────────── 5-1. 보류된 곡은 다음 배치가 다시 집는다

def test_a_held_track_is_picked_up_again(sandbox, tmp_path):
    """교체가 보류된 날의 곡을 **다음 배치가 건너뛰면 안 된다.**

    예전에는 분석 직후에 `ok` 를 적었다. 그날 등록이 막혀 색인에 못 들어간 곡이
    다음 날 "이미 했다" 로 건너뛰어지고, 그 뒤로는 영원히 색인 밖에 남는다. 오류는
    나지 않는다 — 그 곡만 추천에 안 나올 뿐이다.
    """
    root, data = sandbox
    state = tmp_path / "state.json"
    tracks, analyze, _ = make_inputs(data, "held", 1, skip=12)
    rows = len(pd.read_parquet(data / "moments.parquet"))

    held = bt.run(tracks, data, state_path=state, summary_path=tmp_path / "s.jsonl",
                  registrar=bt.UnavailableRegistrar(), analyze=analyze)
    assert held.exit_code == bt.EXIT_HOLD and not held.swapped
    rec = json.loads(state.read_text(encoding="utf-8"))["tracks"][tracks[0].track_id]
    assert rec["status"] != "ok", "색인에 없는 곡을 ok 로 적었다"
    assert held.marked_ok == 0

    again = bt.run(tracks, data, state_path=state, summary_path=tmp_path / "s.jsonl",
                   registrar=FakeRegistrar(), analyze=analyze)
    assert again.skipped == 0, "보류된 곡을 건너뛰었다"
    assert again.ok == 1 and again.swapped and again.marked_ok == 1
    assert len(pd.read_parquet(data / "moments.parquet")) > rows

    # 이제야 `ok` 다. 세 번째 실행은 건너뛴다 — 다시 붙이지 않는다.
    rec = json.loads(state.read_text(encoding="utf-8"))["tracks"][tracks[0].track_id]
    assert rec["status"] == "ok"
    third = bt.run(tracks, data, state_path=state, summary_path=tmp_path / "s.jsonl",
                   registrar=FakeRegistrar(), analyze=analyze)
    assert third.skipped == 1 and not third.swapped
    print("\n보류 → 재시도 → 반영 · 세 번째는 건너뜀")
    cleanup(data)


def test_state_that_disagrees_with_the_index_is_reanalyzed(sandbox, tmp_path):
    """기록은 `ok` 인데 색인에 없다. **색인을 믿는다.**

    예전 판이 남긴 기록이거나 교체가 되돌려진 경우다. 기록을 믿고 건너뛰면 그 곡은
    영영 안 들어간다.
    """
    root, data = sandbox
    state = tmp_path / "state.json"
    tracks, analyze, _ = make_inputs(data, "ghost", 1, skip=14)
    tid = tracks[0].track_id
    sha = pl._sha256(tracks[0].audio_path)
    pl.save_state(state, {"version": pl.STATE_VERSION, "tracks": {
        tid: {"track_id": tid, "status": "ok", "audio_sha256": sha}}})

    s = bt.run(tracks, data, state_path=state, summary_path=tmp_path / "s.jsonl",
               registrar=FakeRegistrar(), analyze=analyze)

    assert s.skipped == 0, "색인에 없는데 기록만 믿고 건너뛰었다"
    assert s.ok == 1 and s.swapped
    cleanup(data)


def test_unswapped_plates_do_not_pile_up(sandbox, tmp_path):
    """보류가 이어져도 새 판이 쌓이지 않는다. 한 벌이 색인 전체 크기다."""
    root, data = sandbox
    made = []
    for i in range(3):
        tracks, analyze, _ = make_inputs(data, f"pile{i}", 1, skip=16 + i)
        s = bt.run(tracks, data, state_path=tmp_path / f"st{i}.json",
                   summary_path=tmp_path / "s.jsonl",
                   registrar=bt.UnavailableRegistrar(), analyze=analyze)
        assert s.exit_code == bt.EXIT_HOLD
        made.append(s.staged_dir)
        left = [p.name for p in data.parent.glob(f"{data.name}_v*") if p.is_dir()]
        assert len(left) == 1, f"{i + 1}번째 보류에서 판이 {len(left)}벌 남았다: {left}"

    # 마지막 한 벌은 남는다 — 사람이 들여다볼 수 있어야 한다.
    assert Path(made[-1]).is_dir()
    assert not Path(made[0]).exists() and not Path(made[1]).exists()
    print("\n보류 3회 · 남은 새 판 1벌")
    cleanup(data)


def test_a_failed_swap_leaves_the_index_alone(sandbox, tmp_path, monkeypatch):
    """교체가 깨져도 **기존 색인은 그대로**고, 요약은 남는다.

    예외를 그대로 올리면 요약이 없어 다음 날 무슨 일이었는지 알 수 없고, 종료 코드도
    cron 이 읽을 수 있는 값이 아니다.
    """
    root, data = sandbox
    before = len(pd.read_parquet(data / "moments.parquet"))
    tracks, analyze, _ = make_inputs(data, "swapfail", 1, skip=19)
    summary = tmp_path / "s.jsonl"

    def boom(*a, **k):
        raise OSError("디스크가 찼다")

    monkeypatch.setattr(ix, "swap", boom)
    s = bt.run(tracks, data, state_path=tmp_path / "state.json",
               summary_path=summary, registrar=FakeRegistrar(), analyze=analyze)

    assert s.exit_code == bt.EXIT_FAIL and not s.swapped
    assert "교체하지 못했다" in s.hold_reason
    assert len(pd.read_parquet(data / "moments.parquet")) == before
    assert summary.read_text(encoding="utf-8").strip(), "요약이 남지 않았다"
    assert s.marked_ok == 0, "교체하지 못한 곡을 ok 로 올렸다"
    cleanup(data)


# ────────────────────────────────────────────────────── 6. 동시 실행 금지

def test_second_process_backs_off(tmp_path):
    """잠금이 걸려 있으면 **기다리지 않고** 물러난다."""
    lock = bt.RunLock(tmp_path / bt.LOCK_NAME)
    with lock:
        with pytest.raises(bt.LockBusy):
            with bt.RunLock(tmp_path / bt.LOCK_NAME):
                pass
        assert lock.path.exists()
    assert not lock.path.exists(), "끝나면 잠금을 푼다"

    # 한 번 더 — 남은 잠금이 다음 실행을 영구히 막지 않는다
    with bt.RunLock(tmp_path / bt.LOCK_NAME):
        pass


def test_cli_returns_locked_code(tmp_path, monkeypatch):
    data = tmp_path / "data" / "proc" / "mupid"
    data.mkdir(parents=True)
    (data / "moments.parquet").write_bytes(b"")          # 존재만 본다
    (data / bt.LOCK_NAME).write_text('{"pid": 1}', encoding="utf-8")
    manifest = tmp_path / "m.json"
    manifest.write_text('{"tracks": []}', encoding="utf-8")
    code = bt.main([str(manifest), "--data-dir", str(data)])
    assert code == bt.EXIT_LOCKED


# ────────────────────────────────────────────────────── 7. 정리

def test_nothing_is_left_behind(sandbox):
    """시드가 만든 판이 남지 않는가. 진짜 산출물은 그대로인가."""
    root, data = sandbox
    left = cleanup(data)
    print(f"\n남아 있던 판 {len(left)}개 정리: {left}")
    assert not list(data.parent.glob(f"{data.name}_*")), "판이 남았다"

    # 진짜 산출물은 읽기만 했다.
    assert ix.sha256(D / "features_v1.npz") == BASE_SHA, "산출물이 바뀌었다"
    assert not (D / "analysis_batch.jsonl").exists(), "진짜 산출물에 요약을 썼다"
    assert not (D / bt.LOCK_NAME).exists(), "진짜 산출물에 잠금이 남았다"
    # MySQL·ClickHouse 에는 붙지 않았다 — 등록기를 주입했으므로 나가는 길이 없다.
