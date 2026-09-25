"""색인 벡터 → Spring 벡터 등록 API (S15P21A204-457, 설계 §6.3·§6.6).

본문의 모양이 Spring `VectorServiceImpl` 이 받는 것과 같고(float32 LE base64, 구간은 순번 순,
`mert` 는 동결 μ 로 중심화한 단위 벡터), 보내기가 429·5xx 만 다시 보내는지 본다.
"""
from __future__ import annotations

import base64
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from music_preprocessing import vectors as vx
from music_preprocessing.api_client import MupidApiError


def unb64(text: str) -> np.ndarray:
    return np.frombuffer(base64.b64decode(text), dtype="<f4")


def build_board(root: Path, tracks=("T000001", "T000002", "OLD_TITLE"), per=3, clap_for=("T000001#0",)):
    rng = np.random.default_rng(0)
    rows = []
    for t in tracks:
        # 순번을 섞어 둔다 — 보낼 때는 MySQL 순서(순번 오름차순)여야 한다.
        for i in reversed(range(per)):
            rows.append({"moment_id": f"{t}#{i}", "track_id": t, "source": "flow"})
    mom = pd.DataFrame(rows)
    mom["emb_row"] = np.arange(len(mom))[::-1]          # emb_row 는 행 순서와 다르다
    n = len(mom)
    Z = rng.normal(size=(n, vx.MERT_DIM)).astype(np.float32) + 3.0
    X = rng.normal(size=(n, vx.FEAT_DIM)).astype(np.float32)
    S1 = rng.normal(size=(n, vx.STAGE1_DIM)).astype(np.float32)
    x_order = sorted(mom.moment_id)                       # features_v1 의 행 순서도 다르다
    root.mkdir(parents=True, exist_ok=True)
    mom.to_parquet(root / "moments.parquet")
    np.save(root / "moments_emb.npy", Z)
    np.savez(root / "features_v1.npz", X=X, moment_id=np.array(x_order))
    np.save(root / "stage1_section.npy", S1)
    np.savez(root / "clap_moments.npz",
             embeddings=rng.normal(size=(len(clap_for), vx.CLAP_DIM)).astype(np.float16),
             moment_ids=np.array(list(clap_for)))
    return mom, Z, X, S1, x_order


MUSIC_IDS = {"T000001": 101, "T000002": 102}


class FakeClient:
    def __init__(self, fail=None):
        self.bodies = {}
        self.stage1 = []
        self.fail = dict(fail or {})

    def put_vectors(self, mid, body):
        err = self.fail.get(mid)
        if err:
            if isinstance(err, list):
                e = err.pop(0)
                if not err:
                    self.fail.pop(mid)
            else:
                e = err
            raise e
        self.bodies[mid] = body
        return {"points": len(body["sections"])}

    def put_stage1_vectors(self, version, lines):
        self.stage1.append((version, lines))
        return {"updated": len(lines)}


class VectorsTest(unittest.TestCase):
    def setUp(self):
        # Windows 는 mmap 으로 연 .npy 를 지우지 못한다 — 판을 닫을 길이 없어 정리 실패는 넘긴다.
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name) / "board"
        self.mom, self.Z, self.X, self.S1, self.x_order = build_board(self.root)
        self.board = vx.Board.open(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def test_freeze_mu_uses_registered_tracks_only_and_refuses_to_overwrite(self):
        out = Path(self.tmp.name) / "bundle" / "mu.npy"
        meta = vx.freeze_mu(self.board, MUSIC_IDS.get, out)
        rows = self.mom[self.mom.track_id.isin(MUSIC_IDS)].emb_row.to_numpy()
        np.testing.assert_allclose(np.load(out), self.Z[rows].mean(0), rtol=1e-5)
        self.assertEqual(meta["tracks"], 2)
        self.assertEqual(meta["sections"], 6)
        self.assertIn("sha256", json.loads(out.with_suffix(".json").read_text(encoding="utf-8")))
        with self.assertRaises(vx.VectorError):
            vx.freeze_mu(self.board, MUSIC_IDS.get, out)

    def test_body_matches_what_spring_accepts(self):
        mu = self.Z.mean(0)
        body = vx.track_body(self.board, self.board.rows_of(["T000001"]), mu, "v1", "v1")
        self.assertEqual(body["emb_version"], "mert-v1-95m")
        self.assertEqual(body["feat_version"], "v1")
        self.assertEqual(body["source"], "flow")
        self.assertEqual([s["external_id"] for s in body["sections"]],
                         ["T000001#0", "T000001#1", "T000001#2"])
        for s in body["sections"]:
            row = int(self.mom.set_index("moment_id").emb_row[s["external_id"]])
            u = unb64(s["mert"])
            self.assertEqual(u.shape, (vx.MERT_DIM,))
            self.assertAlmostEqual(float(np.linalg.norm(u)), 1.0, places=5)
            # 원본 복원 Z = μ + r·u — AI 가 하는 그대로
            np.testing.assert_allclose(mu + s["r"] * u, self.Z[row], atol=1e-4)
            xr = self.x_order.index(s["external_id"])
            np.testing.assert_array_equal(unb64(s["feat_v1"]), self.X[xr])
            np.testing.assert_array_equal(unb64(s["stage1"]["v1"]), self.S1[xr])
        self.assertIn("clap", body["sections"][0])
        self.assertEqual(unb64(body["sections"][0]["clap"]).shape, (vx.CLAP_DIM,))
        self.assertNotIn("clap", body["sections"][1])

    def test_plan_skips_unregistered_and_filters(self):
        p = vx.plan(self.board, MUSIC_IDS.get)
        self.assertEqual(p.tracks, {101: "T000001", 102: "T000002"})
        self.assertEqual(p.unregistered, 1)
        self.assertEqual(vx.plan(self.board, MUSIC_IDS.get, {102}).tracks, {102: "T000002"})

    def test_send_retries_busy_counts_conflicts_and_gone(self):
        client = FakeClient(fail={
            101: [MupidApiError(429, "MUPID_VECTOR_BUSY", "busy"),
                  MupidApiError(503, "MUPID_VECTOR_STORE_UNAVAILABLE", "down")],
            102: MupidApiError(409, "MUPID_VECTOR_SECTIONS_MISMATCH", "mismatch"),
        })
        slept = []
        got = vx.send(client, self.board, vx.plan(self.board, MUSIC_IDS.get), self.Z.mean(0),
                      sleep=slept.append)
        self.assertEqual(got["sent"], 1)
        self.assertIn(101, client.bodies)
        self.assertEqual(len(slept), 2)
        self.assertEqual([f["music_id"] for f in got["failed"]], [102])

        gone = FakeClient(fail={101: MupidApiError(410, "MUPID_MUSIC_GONE", "gone")})
        got = vx.send(gone, self.board, vx.plan(self.board, MUSIC_IDS.get, {101}), self.Z.mean(0),
                      sleep=lambda s: None)
        self.assertEqual(got["gone"], [101])
        self.assertEqual(got["failed"], [])

    def test_stage1_goes_in_batches_of_1000_for_registered_sections(self):
        client = FakeClient()
        old = vx.STAGE1_BATCH
        vx.STAGE1_BATCH = 4
        try:
            got = vx.send_stage1(client, self.board, MUSIC_IDS.get, "v2")
        finally:
            vx.STAGE1_BATCH = old
        self.assertEqual(got, {"sent": 6, "batches": 2})
        ids = [line["external_id"] for _, lines in client.stage1 for line in lines]
        self.assertEqual(sorted(ids), sorted(f"{t}#{i}" for t in MUSIC_IDS for i in range(3)))
        first = client.stage1[0][1][0]
        np.testing.assert_array_equal(unb64(first["vector"]), self.S1[self.x_order.index(first["external_id"])])
        self.assertEqual(vx.STAGE1_BATCH, 1_000)


if __name__ == "__main__":
    unittest.main()
