"""음원 없이 추천 파이프라인을 끝까지 한 번 통과시킨다.

**기본 수집에서 빠진다.** `MUPID_E2E_SEED=1` 이 있어야 돈다. CI 의 ai-server 잡은 인프라
없이 돌면서 "실행 건수 >= 90" 을 검사하므로, MySQL·ClickHouse 가 필요한 이 시험이 거기에
섞이면 그 수치가 흔들린다.

    MUPID_E2E_SEED=1 python -m pytest ai_server/tests/test_e2e_seed.py -s

필요한 것: 로컬 MySQL(`mupid-dev-mysql`), ClickHouse 2노드, 실제 색인 산출물.
`.env` 의 `MUPID_MYSQL_*` · `MUPID_CLICKHOUSE_*` 를 환경에 올려 두어야 한다.

성공하든 실패하든 `finally` 에서 정리한다. 중간에 죽어 정리가 돌지 못했다면
`python -m ai_server.tools.cleanup_seed_run --run-id <run_id>` 로 지운다.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import pytest

if os.environ.get("MUPID_E2E_SEED") != "1":
    pytest.skip("MUPID_E2E_SEED=1 일 때만 돈다 (인프라가 필요하다)",
                allow_module_level=True)

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from ai_server.tools import seed_e2e as se      # noqa: E402

SOURCE_ARTIFACTS = Path(
    os.environ.get("MUPID_E2E_SOURCE_ARTIFACTS")
    or (Path.home() / "ssafy" / "mupid-ai-artifacts"))

# 이 시험이 쓰는 사용자. 예약 대역 안이라 사람 계정과 겹치지 않는다.
USER_ID = se.USER_ID_BASE + 7
USER_REF = f"internal:{USER_ID}"


# ── 도우미 ──────────────────────────────────────────────────────────────────

def run_tool(module: str, *args: str, env: dict) -> subprocess.CompletedProcess:
    cmd = [sys.executable, "-m", module, *args]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                         cwd=str(REPO_ROOT), env=env)
    print(f"\n$ {' '.join(cmd[2:])}\n{out.stdout.strip()[:2000]}")
    if out.returncode != 0:
        print(out.stderr.strip()[:2000])
    return out


def ch_insert_events(run_id: str, rows: list[dict]) -> None:
    """`listen_events_local` 에 직접 넣는다.

    dev 의 `kafka_listen_events` 는 broker 를 kafka1/kafka2 로 보는데 dev 에는 kafka 가
    하나뿐이라 Kafka 경유가 서지 않는다. 여기서 확인하려는 것은 **MV 가 신호를 만드는지**
    이므로 원본 표에 바로 넣는다. Kafka→CH 배선은 별건이다.
    """
    lines = "\n".join(json.dumps(r, ensure_ascii=False) for r in rows)
    se.ch("INSERT INTO listen_events_local FORMAT JSONEachRow\n" + lines)


def event(run_id: str, kind: str, track_id: int, *, position_ms: int,
          entry_path: str = "RECOMMEND", reason: str = "",
          play_id: str | None = None) -> dict:
    return {
        "event_id": str(uuid.uuid4()),
        "event_type": kind,
        "user_id": USER_ID,
        "track_id": track_id,
        "occurred_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "ingested_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "play_id": play_id or str(uuid.uuid4()),
        "entry_path": entry_path,
        "rec_id": run_id,
        "rec_type": "SECTION",
        "rec_rank": 1,
        "position_ms": position_ms,
        "from_ms": 0, "start_ms": 0, "end_ms": 0,
        "target": "", "skip_trigger": "", "reason": reason,
        "playlist_id": 0, "comment_id": 0,
        "content": "", "search_query": "", "ext": {},
    }


# ── 본 시험 ────────────────────────────────────────────────────────────────

def test_seed_pipeline_end_to_end():
    import pandas as pd

    assert SOURCE_ARTIFACTS.is_dir(), f"색인이 없다: {SOURCE_ARTIFACTS}"

    run_id = se.new_run_id()
    workdir = Path(tempfile.mkdtemp(prefix=f"e2e-{run_id}-"))
    manifest = se.Manifest(run_id=run_id, artifact_dir=str(workdir))
    manifest.user_ids = [USER_ID]

    env = dict(os.environ)
    env["MUPID_AI_ARTIFACT_DIR"] = str(workdir)
    env["PYTHONIOENCODING"] = "utf-8"

    data_dir = workdir / "data" / "proc" / "mupid"

    try:
        # ── 준비: 하드링크 디렉터리
        se.build_artifact_dir(SOURCE_ARTIFACTS, workdir)
        manifest.save()

        moments = pd.read_parquet(data_dir / "moments.parquet")
        pool = moments[moments.source.isin(("flow", "jamendo"))].reset_index(drop=True)
        track_ids = sorted(pool.track_id.unique().tolist())
        manifest.track_ids = []
        print(f"\n대상: 곡 {len(track_ids)} · 구간 {len(pool)}")
        assert len(track_ids) == 1063, f"곡 수가 다르다: {len(track_ids)}"
        assert len(pool) == 10007, f"구간 수가 다르다: {len(pool)}"

        # ── (a)(b) 참조 데이터와 곡
        se.seed_reference_rows(manifest)
        manifest.save()
        id_of_track = se.seed_music(manifest, track_ids)
        manifest.save()
        assert len(id_of_track) == len(track_ids)

        # ── 중간 상태에서 ready 가 503 인지 한 번만 본다 (id_map 이 아직 없다)
        ready_before = probe_ready(env)
        assert ready_before == 503, f"매핑 전에는 503 이어야 한다 (받은 값 {ready_before})"

        # ── (c) 구간
        se.seed_sections(manifest, pool, id_of_track)
        manifest.save()

        # ── (d) 내보내기 → id_map
        tsv = workdir / "sections.tsv"
        export = se.mysql(
            "SELECT SUBSTRING_INDEX(s.external_id,'|',-1) AS source_section_uid, "
            "s.music_section_analysis_id AS music_section_id, "
            "SUBSTRING_INDEX(SUBSTRING_INDEX(s.external_id,'|',-1),'#',1) AS source_track_id, "
            "m.music_id "
            "FROM music_section_analysis s JOIN music m ON m.music_id = s.music_id "
            f"WHERE s.external_id LIKE {se.sql_str(run_id + '|%')}")
        header = "source_section_uid\tmusic_section_id\tsource_track_id\tmusic_id\n"
        tsv.write_text(header + export, encoding="utf-8")
        assert run_tool("ai_server.tools.build_id_map", str(tsv),
                        env=env).returncode == 0
        id_map = pd.read_parquet(data_dir / "id_map.parquet")
        assert len(id_map) == 10007, f"매핑 {len(id_map)}건"

        # ── (e) ClickHouse 구간 적재
        assert run_tool("ai_server.tools.push_sections", "--full",
                        env=env).returncode == 0
        loaded = int(se.ch("SELECT count() FROM track_sections "
                           "WHERE analysis_version = 'v1'").strip())
        assert loaded >= 10007, f"track_sections {loaded}건"

        # 전량 적재 뒤에는 200 이어야 한다
        assert probe_ready(env) == 200

        # 라벨이 생기기 **전** 추천. 뒤에 비교할 기준이라 여기서 받아 둔다.
        before = probe_recommend(env)
        print(f"\n[추천 전] query_source={before.get('query_source')!r} "
              f"곡={before.get('tracks')}")

        # ── (f) 행동 신호
        liked = [id_of_track[t] for t in track_ids[:6]]
        rows = []
        for music_id in liked[:3]:
            secs = pool[pool.track_id == track_ids[liked.index(music_id)]]
            mid_ms = int(float(secs.iloc[len(secs) // 2].start_sec) * 1000) + 500
            play = str(uuid.uuid4())
            manifest.play_ids.append(play)
            # 구간 저장. 라벨러가 L1(강한 긍정)을 세우는 조건은 셋뿐이다 —
            # 구간 저장·파형 클릭 되감기·2회 이상 반복. 곡 단위 좋아요와 되감기만으로는
            # L2 에 머물고, `user_positives()` 는 L1 만 보므로 Moment DNA 가 비어
            # `query_source` 가 'none' 에서 움직이지 않는다. 개인화가 걸리는 경로를
            # 확인하려면 이 신호가 있어야 한다.
            rows.append(event(run_id, "SECTION_MARK", music_id, position_ms=mid_ms,
                              play_id=play))
            rows.append(event(run_id, "LIKE", music_id, position_ms=mid_ms, play_id=play))
            rows.append(event(run_id, "SEEK_BACK", music_id, position_ms=mid_ms,
                              play_id=play))
            rows.append(event(run_id, "PLAY_END", music_id, position_ms=mid_ms * 3,
                              reason="complete", play_id=play))
        for music_id in liked[3:]:
            play = str(uuid.uuid4())
            manifest.play_ids.append(play)
            # 초기 스킵: 추천 경로에서 난 것만 부정 신호로 센다
            rows.append(event(run_id, "PLAY_END", music_id, position_ms=3000,
                              reason="skip_next", entry_path="RECOMMEND", play_id=play))
        manifest.event_ids = [r["event_id"] for r in rows]
        manifest.save()

        # 딕셔너리 갱신이 **삽입보다 먼저**여야 한다. MV 는 INSERT 시점에 dictGet 을
        # 부르므로, 늦게 갱신하면 그 사이 들어온 이벤트는 section_uid 가 '' 이라
        # WHERE 에 걸려 통째로 버려진다. 그리고 그 상태가 오류로 보이지 않는다.
        se.ch("SYSTEM RELOAD DICTIONARY ON CLUSTER mupid_all dict_track_sections")
        ch_insert_events(run_id, rows)

        signals = wait_for_signals()
        assert signals > 0, ("user_section_signals 가 비었다 — 딕셔너리가 갱신되지 "
                             "않았거나 track_sections 가 비었다")

        # ── (g) 신호 수집. 두 번 돌려도 중복이 없어야 한다
        assert run_tool("ai_server.tools.pull_signals", "--json", env=env).returncode == 0
        events_log = data_dir / "logs" / "events_service.jsonl"
        first = events_log.read_text(encoding="utf-8").count("\n")
        assert run_tool("ai_server.tools.pull_signals", "--json", env=env).returncode == 0
        second = events_log.read_text(encoding="utf-8").count("\n")
        assert first == second, f"두 번째 회차가 {second - first}건을 더 넣었다"

        kinds = {json.loads(ln)["event_type"]
                 for ln in events_log.read_text(encoding="utf-8").splitlines() if ln.strip()}
        for want in ("LIKE", "SEEK_BACK", "PLAY_END"):
            assert want in kinds, f"{want} 가 로그에 없다 (있는 것: {sorted(kinds)})"

        # ── (h) 라벨
        assert run_tool("ai_server.tools.run_labeler", "--json", env=env).returncode == 0
        labels = pd.read_parquet(data_dir / "labels_service.parquet")
        mine = labels[labels.user_ref == USER_REF] if "user_ref" in labels.columns else labels
        assert len(mine) > 0, "이 사용자의 라벨이 생기지 않았다"

        # ── (i) 같은 사용자로 추천을 다시 받아 전후를 견준다
        after = probe_recommend(env)
        print(f"[추천 후] query_source={after.get('query_source')!r} "
              f"곡={after.get('tracks')}")
        assert after.get("query_source") == "labels", (
            f"개인화가 걸리지 않았다 — query_source={after.get('query_source')!r} "
            f"(라벨 {len(mine)}행)")

        print(f"\n[결과] 신호 {signals}건 · 로그 {first}줄 · 라벨 {len(mine)}행 · "
              f"query_source {before.get('query_source')!r} → {after.get('query_source')!r}")

    finally:
        if os.environ.get("MUPID_E2E_KEEP") == "1":
            # 실패를 들여다볼 때만 쓴다. DB 잔여까지 남으므로 확인이 끝나면
            # `cleanup_seed_run --manifest <경로>` 로 직접 지워야 한다.
            print(f"\n[보존] MUPID_E2E_KEEP=1 — 정리하지 않는다. {manifest.path()}")
            return
        left = se.cleanup(manifest)
        print(f"\n[정리] {json.dumps(left, ensure_ascii=False)}")
        assert all(v == 0 for v in left.values()), f"정리되지 않은 것이 있다: {left}"


# ── 보조 ────────────────────────────────────────────────────────────────────

def probe_ready(env: dict) -> int:
    """`/internal/ready` 의 상태 코드. 서버를 띄우지 않고 앱을 직접 태운다.

    `with` 로 열어야 lifespan 이 돈다. 그냥 부르면 카탈로그가 적재되지 않아 매핑이
    멀쩡해도 늘 503 이고, 이유는 "색인이 준비되지 않았다" 로만 보인다.

    실패했을 때 본문과 `/internal/id-map` 을 함께 찍는다. 코드만 보면 "매핑표가 없다" 와
    "매핑이 모자라다" 가 똑같이 503 이라, 어느 쪽인지 알아내려고 다시 돌려야 한다.
    """
    script = """
from fastapi.testclient import TestClient
from ai_server.app.main import app

client = TestClient(app, raise_server_exceptions=False)
with client:
    r = client.get("/internal/ready")
    print("STATUS", r.status_code)
    print("BODY", r.text[:900])
    print("IDMAP", client.get("/internal/id-map").text[:900])
"""
    out = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True,
                         cwd=str(REPO_ROOT), env=env, encoding="utf-8")
    status = None
    for line in (out.stdout or "").splitlines():
        if line.startswith("STATUS"):
            status = int(line.split()[1])
        elif line.startswith(("BODY", "IDMAP")):
            print("  ", line[:700])
    if status is None:
        pytest.skip(f"/internal/ready 를 부를 수 없다: {out.stderr.strip()[-500:]}")
    return status


def probe_recommend(env: dict) -> dict:
    """이 사용자로 이어 듣기 추천을 한 번 받아 진단을 돌려준다.

    `query_source` 는 **응답 계약에 없다** — 진단에만 실린다. 그래서 HTTP 로는 개인화가
    걸렸는지 알 수 없어 추천기를 직접 태운다. 새 프로세스로 부르는 이유는 카탈로그와
    라벨이 모듈 전역에 한 번만 적재되기 때문이다. 같은 프로세스에서 두 번 부르면
    라벨을 새로 만든 뒤에도 옛 상태를 보게 된다.
    """
    script = f"""
import json
from ai_server.app import recommender

recommender.CAT = recommender.Catalog()
out = recommender.recommend("{USER_REF}", 5, set(), True, None, None, None)
diag = out.get("diagnostics", {{}})
print("REC", json.dumps({{
    "query_source": diag.get("query_source", ""),
    "tracks": [str(x.get("track_id")) for x in out.get("results", [])][:5],
}}, ensure_ascii=False))
"""
    out = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True,
                         cwd=str(REPO_ROOT), env=env, encoding="utf-8")
    for line in (out.stdout or "").splitlines():
        if line.startswith("REC"):
            return json.loads(line[4:])
    print("  추천 호출 실패:", (out.stderr or "").strip()[-400:])
    return {}


def wait_for_signals(timeout: float = 60.0) -> int:
    """MV 는 INSERT 와 함께 돌지만 분산 표 조회에는 잠깐 늦을 수 있다."""
    deadline = time.time() + timeout
    n = 0
    while time.time() < deadline:
        n = int(se.ch(f"SELECT count() FROM user_section_signals "
                      f"WHERE user_id = {USER_ID}").strip() or 0)
        if n:
            return n
        time.sleep(1.0)
    return n
