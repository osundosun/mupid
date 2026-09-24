"""Pinecone 을 켠 채 기동하면 **적재 완료까지 간다.**

2026-09-24 운영 배포에서 Pinecone 검사는 통과했는데 그 뒤 로그 한 줄이 없는 속성
(`Center.namespace`)을 읽어 예외가 났다. 준비 판정은 색인만 보므로 200 이었지만
`/health` 의 `index_loaded` 가 false 로, `error` 에 AttributeError 가 남았다 — 겉으로는
멀쩡한 채 상태 표시만 거짓이 되는 모양이라 여기서 못박는다.
"""
from __future__ import annotations

import types

import numpy as np

from ai_server.app import main, pinecone_store, recommender


def test_startup_with_pinecone_reaches_loaded(monkeypatch):
    monkeypatch.setattr(main, "artifact_status", lambda: {"missing": []})
    monkeypatch.setattr(main.index_contract, "check_plate",
                        lambda: {"ok": True, "warnings": [], "errors": []})
    monkeypatch.setattr(main, "check_integrity", lambda: {"ok": True, "errors": []})
    fake = types.SimpleNamespace(_mom_index={"T1#0": 0}, Z=None)
    monkeypatch.setattr(recommender, "Catalog", lambda: fake)
    monkeypatch.setattr(pinecone_store, "enabled", lambda: True)
    center = pinecone_store.Center(np.zeros(4, dtype=np.float32), "v", "snap", 1)
    monkeypatch.setattr(pinecone_store, "center", lambda reload=False: center)
    monkeypatch.setattr(pinecone_store, "stats", lambda: {})
    monkeypatch.setattr(pinecone_store, "namespace", lambda: "test__v")
    monkeypatch.setattr(main, "_STATE", dict(main._STATE, loaded=False, error=None))

    main._startup()

    assert main._STATE["error"] is None
    assert main._STATE["loaded"] is True
    assert main._STATE["vector_search"]["backend"] == "pinecone"
