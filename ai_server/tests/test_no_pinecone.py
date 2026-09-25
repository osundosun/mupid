"""Pinecone 을 지웠다 (S15P21A204-459, 설계 `infra/VECTOR-STORE-DESIGN.md` §11 "Pinecone 제거").

`ai_server/requirements.txt` 에는 SDK 줄이 **아직 남아 있다.** CI 이미지(`mupid-ci-ai:1`)가 그
파일을 바이트로 대조하므로 이미지를 다시 굽는 후속에서 지운다. 그 사이에 누가 다시 import 하면
이미지에 SDK 가 있어 조용히 통과한다 — 그래서 모듈을 **막은 채** 앱이 뜨는지 본다.

운영 Secret(`MUPID_AI_SERVER_ENV`)에는 옛 `MUPID_PINECONE_*` 키가 지워질 때까지 남는다. 그 값이
있어도 기동이 막히지 않고, 읽히지도 않고, 이름만 알려야 한다.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from ai_server.app import vector_store as vstore

ROOT = Path(__file__).resolve().parents[2]

# 자식 프로세스에서 돈다 — 이 프로세스에는 SDK 가 이미 올라와 있을 수 있다.
_CHILD = r"""
import importlib, importlib.abc, json, pkgutil, sys
from pathlib import Path

class Block(importlib.abc.MetaPathFinder):
    def find_spec(self, name, path=None, target=None):
        if name == "pinecone" or name.startswith("pinecone."):
            raise ImportError(f"{name} 는 막아 두었다 (459)")
        return None

sys.meta_path.insert(0, Block())
sys.path.insert(0, sys.argv[1])                  # ai_server/tests — 합성 판 도구

import ai_server.app as app_pkg, ai_server.tools as tools_pkg
for pkg in (app_pkg, tools_pkg):
    for m in pkgutil.iter_modules(pkg.__path__):
        importlib.import_module(f"{pkg.__name__}.{m.name}")

from test_pipeline_synthetic import _build
_build(Path(sys.argv[2]))

from fastapi.testclient import TestClient
from ai_server.app import main
with TestClient(main.app) as client:
    health = client.get("/health")
    ready = client.get("/internal/ready")
print(json.dumps({"health": health.status_code, "body": health.json(),
                  "ready": ready.status_code, "ready_body": ready.json(),
                  "pinecone_loaded": any(k == "pinecone" or k.startswith("pinecone.")
                                         for k in sys.modules)}))
"""


def _run(tmp_path, **env) -> dict:
    child_env = {k: v for k, v in os.environ.items()
                 if not k.startswith(("MUPID_PINECONE_", "MUPID_VECTOR_BACKEND",
                                      "MUPID_CLICKHOUSE_", "MUPID_QDRANT_"))}
    child_env.update(MUPID_AI_ARTIFACT_DIR=str(tmp_path / "art"), PYTHONIOENCODING="utf-8", **env)
    got = subprocess.run(
        [sys.executable, "-c", _CHILD, str(ROOT / "ai_server" / "tests"), str(tmp_path / "art")],
        cwd=ROOT, env=child_env, capture_output=True, text=True, encoding="utf-8", timeout=300)
    assert got.returncode == 0, got.stderr[-3000:]
    return json.loads(got.stdout.strip().splitlines()[-1]) | {"log": got.stdout}


def test_the_app_starts_without_the_pinecone_module(tmp_path):
    """모든 app·tools 모듈을 불러오고, 앱이 local 갈래로 뜬다 — `pinecone` 없이."""
    got = _run(tmp_path)
    assert got["pinecone_loaded"] is False
    assert got["health"] == 200
    assert got["body"]["index_loaded"] is True, got["body"]["error"]
    assert got["body"]["vector_search"] == {"backend": "local"}
    assert got["ready"] == 200, got["ready_body"]


def test_leftover_pinecone_settings_are_ignored_with_a_warning(tmp_path):
    """운영 Secret 에 남은 옛 키. 켜 두었어도 local 로 뜨고, 이름만 알린다(값은 싣지 않는다)."""
    got = _run(tmp_path, MUPID_PINECONE_ENABLED="true", MUPID_PINECONE_API_KEY="pcsk_secret_value",
               MUPID_PINECONE_INDEX="mupid-moments")
    assert got["ready"] == 200, got["ready_body"]
    vs = got["body"]["vector_search"]
    assert vs["backend"] == "local"
    assert vs["ignored_env"] == ["MUPID_PINECONE_API_KEY", "MUPID_PINECONE_ENABLED",
                                 "MUPID_PINECONE_INDEX"]
    assert "경고: 쓰지 않는 옛 설정" in got["log"]
    assert "pcsk_secret_value" not in got["log"] + json.dumps(got["body"], ensure_ascii=False)


def test_choosing_pinecone_fails_readiness_with_a_clear_message(tmp_path):
    got = _run(tmp_path, MUPID_VECTOR_BACKEND="pinecone")
    assert got["health"] == 200                      # liveness 는 답한다
    assert got["body"]["index_loaded"] is False
    assert "Pinecone 갈래는 지웠다" in got["body"]["error"]
    assert got["ready"] == 503
    assert got["ready_body"]["error_code"] == "INDEX_NOT_READY"


@pytest.mark.parametrize("raw", ["pinecone", " Pinecone "])
def test_backend_name_rejects_pinecone(monkeypatch, raw):
    monkeypatch.setenv("MUPID_VECTOR_BACKEND", raw)
    with pytest.raises(ValueError, match="지웠다"):
        vstore.backend_name()


def test_no_serving_code_mentions_the_sdk():
    """남은 언급은 지운 이유와 옛 설정을 무시하는 자리뿐이다 — import·호출은 없다."""
    for folder in ("app", "tools"):
        for f in sorted((ROOT / "ai_server" / folder).glob("*.py")):
            src = f.read_text(encoding="utf-8")
            assert "import pinecone" not in src and "from pinecone" not in src, f
            assert "pinecone_store" not in src and "pinecone_sync" not in src, f
