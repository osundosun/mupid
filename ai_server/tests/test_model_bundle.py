"""서빙 가중치는 이미지의 모델 자리가 먼저, 없으면 판 (S15P21A204-454, 설계 §6.3).

`models.lock` 에 채택된 모델이 있으면 CI 가 서빙 파일 셋(`user_encoder.npz`·`combiner.json`·
`onboarding_seeds.npz`)과 `model.json` 을 이미지의 모델 자리에 굽는다. 없으면 자리가 비어 있고
지금처럼 판에 섞인 가중치(`stage1_weights.npz`·`logistic_flow_stage1.json`)를 읽는다 — 지금
운영이 그 상태다. 여기서 보는 것:

  - 자리 판정: 비었으면 판, 표식과 체크섬이 맞으면 이미지, 어긋나면 판으로 물러나고 이유를 남긴다
  - 추천기가 실제로 그 자리에서 읽는다 (`/health` 의 `model`)
  - 온보딩 씨앗 표는 지금 판·μ 와 맞을 때만 쓰고, 쓰면 원본 벡터를 다시 읽지 않는다
"""
from __future__ import annotations

import hashlib
import importlib
import json
import os
import shutil
import sys

import numpy as np
import pytest

from ai_server.app import catalog_body, model_bundle

from test_pipeline_synthetic import GENRES, _build, _catalog_body   # noqa: E402

N_TRACKS, PER_TRACK = 12, 4


def _sha(path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_bundle(model_dir, files: dict, version="v3", name="stage1") -> None:
    """CI(`package-ai.sh`) 가 쓰는 모양 그대로 — 파일들과 `model.json`."""
    model_dir.mkdir(parents=True, exist_ok=True)
    for fname, src in files.items():
        shutil.copyfile(src, model_dir / fname)
    (model_dir / "model.json").write_text(json.dumps({
        "name": name, "version": version,
        "files": {f: _sha(model_dir / f) for f in files}}), encoding="utf-8")


@pytest.fixture
def dirs(tmp_path, monkeypatch):
    art = tmp_path / "art"
    data = art / "data" / "proc" / "mupid"
    data.mkdir(parents=True)
    model = tmp_path / "models" / "stage1"
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(art))
    monkeypatch.setenv("MUPID_AI_MODEL_DIR", str(model))
    monkeypatch.delenv("MUPID_COMBINER", raising=False)
    model_bundle.reset()
    yield data, model, tmp_path
    model_bundle.reset()


# ─────────────────────────────────────────────── 자리 판정

def test_empty_model_dir_reads_the_plate_like_today(dirs):
    data, model, _ = dirs
    (data / "stage1_weights.npz").write_bytes(b"w")
    (data / "logistic_flow_stage1.json").write_text("{}")

    assert model_bundle.resolve("user_encoder.npz").path == data / "stage1_weights.npz"
    assert model_bundle.resolve("combiner.json").path == data / "logistic_flow_stage1.json"
    assert model_bundle.resolve("onboarding_seeds.npz").path is None
    st = model_bundle.status()
    assert st["source"] == "artifact"
    assert st["version"] is None                      # 판의 가중치에는 버전이 없다
    assert st["image_bundle"] == {"present": False, "version": None, "error": None}


def test_repo_default_model_dir_is_empty(monkeypatch):
    """저장소의 기본 자리에는 `.gitkeep` 만 있다 — 개발·시험은 판을 읽는다."""
    monkeypatch.delenv("MUPID_AI_MODEL_DIR", raising=False)
    model_bundle.reset()
    try:
        d = model_bundle.model_dir()
        assert d.parts[-2:] == ("models", "stage1")
        assert not (d / "model.json").exists()
        assert model_bundle.image_bundle()["present"] is False
    finally:
        model_bundle.reset()


def test_image_bundle_wins_over_the_plate(dirs):
    data, model, tmp = dirs
    (data / "stage1_weights.npz").write_bytes(b"plate")
    (data / "logistic_flow_stage1.json").write_text("{}")
    (tmp / "enc.npz").write_bytes(b"image")
    (tmp / "comb.json").write_text('{"image": true}')
    _write_bundle(model, {"user_encoder.npz": tmp / "enc.npz", "combiner.json": tmp / "comb.json"})

    assert model_bundle.resolve("user_encoder.npz").path == model / "user_encoder.npz"
    assert model_bundle.resolve("combiner.json").source == "image"
    st = model_bundle.status()
    assert (st["source"], st["version"]) == ("image", "v3")
    assert st["files"]["user_encoder.npz"]["source"] == "image"
    # 묶음에 없는 파일은 판을 본다 (씨앗 표는 판에 없으니 None)
    assert st["files"]["onboarding_seeds.npz"]["source"] is None


def test_checksum_mismatch_falls_back_to_the_plate(dirs):
    data, model, tmp = dirs
    (data / "stage1_weights.npz").write_bytes(b"plate")
    (tmp / "enc.npz").write_bytes(b"image")
    _write_bundle(model, {"user_encoder.npz": tmp / "enc.npz"})
    (model / "user_encoder.npz").write_bytes(b"tampered")

    r = model_bundle.resolve("user_encoder.npz")
    assert (r.source, r.path) == ("artifact", data / "stage1_weights.npz")
    st = model_bundle.status()
    assert "sha256" in st["image_bundle"]["error"]
    assert st["version"] is None


def test_weights_without_manifest_are_not_trusted(dirs):
    data, model, _ = dirs
    model.mkdir(parents=True)
    (model / "user_encoder.npz").write_bytes(b"loose")

    assert model_bundle.resolve("user_encoder.npz").source is None
    assert "model.json" in model_bundle.status()["image_bundle"]["error"]


def test_other_model_name_is_rejected(dirs):
    _, model, tmp = dirs
    (tmp / "enc.npz").write_bytes(b"x")
    _write_bundle(model, {"user_encoder.npz": tmp / "enc.npz"}, name="stage2")
    assert model_bundle.resolve("user_encoder.npz").source is None


def test_plate_combiner_name_still_follows_env(dirs, monkeypatch):
    data, _, _ = dirs
    (data / "logistic_combiner.json").write_text("{}")
    (data / "custom.json").write_text("{}")
    assert model_bundle.resolve("combiner.json").path == data / "logistic_combiner.json"
    monkeypatch.setenv("MUPID_COMBINER", "custom.json")
    assert model_bundle.resolve("combiner.json").path == data / "custom.json"


# ─────────────────────────────────────────────── 추천기가 그 자리에서 읽는가

def _combiner(path, first_feature: str) -> None:
    path.write_text(json.dumps({
        "features": [first_feature], "scaler_mean": [0.0], "scaler_scale": [1.0],
        "coef": [1.0], "intercept": 0.0}), encoding="utf-8")


@pytest.fixture
def plate(tmp_path, monkeypatch):
    """합성 판 + 비어 있는 모델 자리. 추천기를 새 환경으로 다시 읽는다."""
    root = tmp_path / "art"
    _build(root, n_tracks=N_TRACKS, per_track=PER_TRACK)
    model = tmp_path / "models" / "stage1"
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(root))
    monkeypatch.setenv("MUPID_AI_MODEL_DIR", str(model))
    monkeypatch.delenv("MUPID_PINECONE_ENABLED", raising=False)
    monkeypatch.delenv("MUPID_COMBINER", raising=False)
    model_bundle.reset()

    def _reload():
        for name in ("features", "labeler", "stage1_torch"):
            full = f"ai_server.app.{name}"
            if full in sys.modules:
                importlib.reload(sys.modules[full])
        from ai_server.app import recommender as mod
        return importlib.reload(mod)

    rc = _reload()
    yield rc, root / "data" / "proc" / "mupid", model, tmp_path
    model_bundle.reset()
    rc.CAT = None


def _view(rc):
    cat = rc.Catalog()
    rc.CAT = cat
    return cat.with_catalog(
        catalog_body.from_body(_catalog_body(n_tracks=N_TRACKS, per_track=PER_TRACK)))


def test_recommender_loads_the_plate_when_no_model_is_adopted(plate):
    rc, data, _, _ = plate
    _combiner(data / "logistic_flow_stage1.json", "rule_sim")
    cat = rc.Catalog()
    assert cat.model_backend == "numpy"
    assert cat.model_sources == {"user_encoder.npz": "artifact", "combiner.json": "artifact"}
    assert cat.comb["path"] == "logistic_flow_stage1.json"


def test_recommender_loads_the_image_bundle_first(plate):
    rc, data, model, tmp = plate
    _combiner(data / "logistic_flow_stage1.json", "rule_sim")
    _combiner(tmp / "combiner.json", "log_dur")
    _write_bundle(model, {"user_encoder.npz": data / "stage1_weights.npz",
                          "combiner.json": tmp / "combiner.json"})
    # 판의 옛 가중치를 치워도 뜬다 — 이미지에 든 것을 읽는다.
    (data / "stage1_weights.npz").unlink()

    rc.CAT = rc.Catalog()
    assert rc.CAT.model_backend == "numpy"
    assert rc.CAT.model_sources == {"user_encoder.npz": "image", "combiner.json": "image"}
    assert rc.CAT.comb["features"] == ["log_dur"]

    from ai_server.app import main
    st = main._model_status()
    assert (st["source"], st["version"]) == ("image", "v3")
    assert st["loaded_from"] == {"user_encoder.npz": "image", "combiner.json": "image"}
    # 이미지가 대신하는 판 파일은 "선택 산출물 없음" 이 아니다
    art = main.artifact_status()
    assert "stage1_weights.npz" not in art["missing"]
    assert art["files"]["stage1_weights.npz"]["served_from_image_as"] == "user_encoder.npz"


# ─────────────────────────────────────────────── 온보딩 씨앗 표

def _answers():
    from ai_server.app import onboarding as onb
    return onb.from_request({"moods": [], "genres": [GENRES[0]]}, GENRES)


def _export_table(rc, path) -> None:
    from ai_server.tools import export_onboarding_seeds as ex
    view = _view(rc)
    with rc.using(view):
        np.savez(path, **ex.build_table(rc))


def test_seed_table_is_used_and_matches_what_serving_computes(plate, monkeypatch):
    rc, _, model, tmp = plate
    view = _view(rc)
    with rc.using(view):
        want_C, want_W, _ = rc.onboarding_seeds("internal:direct", _answers())
    _export_table(rc, tmp / "seeds.npz")
    _write_bundle(model, {"onboarding_seeds.npz": tmp / "seeds.npz"})
    model_bundle.reset()

    view = _view(rc)
    fetched: list[int] = []
    real = rc.raw_vectors
    monkeypatch.setattr(rc, "raw_vectors", lambda ids: (fetched.append(len(ids)), real(ids))[1])
    with rc.using(view):
        C, W, _ = rc.onboarding_seeds("internal:table", _answers())
    assert fetched == []                        # 원본 벡터를 다시 읽지 않았다
    assert np.allclose(C, want_C, atol=1e-5) and np.allclose(W, want_W)
    assert rc.seed_table_status() == {"source": "image", "used": True, "reason": None}


def test_seed_table_from_another_mean_is_not_used(plate, monkeypatch):
    rc, _, model, tmp = plate
    _export_table(rc, tmp / "seeds.npz")
    with np.load(tmp / "seeds.npz") as d:
        table = dict(d)
    table["mu"] = table["mu"] + 0.5              # 다른 μ 로 센 합
    np.savez(tmp / "stale.npz", **table)
    _write_bundle(model, {"onboarding_seeds.npz": tmp / "stale.npz"})
    model_bundle.reset()

    view = _view(rc)
    with rc.using(view):
        C, _, _ = rc.onboarding_seeds("internal:stale", _answers())
    st = rc.seed_table_status()
    assert st["used"] is False and "μ" in st["reason"]
    assert len(C)                                # 지금처럼 세어 만든다


def test_seed_table_skips_tracks_whose_sections_differ(plate):
    """요청의 catalog 가 곡의 구간을 일부만 실으면 표의 합은 그 곡의 합이 아니다 — 세어 쓴다."""
    rc, _, _, _ = plate
    view = _view(rc)
    with rc.using(view):
        idx = rc.pool_index()
        t = idx.track_order[0]
        table = {t: {"chorus": (np.zeros(2), 99, "nope")}}
        assert rc._seed_sums_from_table([t], table, idx) == {}
