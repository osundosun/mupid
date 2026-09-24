"""곡 분석 값(보컬·BPM·언어·스타일)과 구간 에너지를 Spring 에 싣는다 (S15P21A204-423).

예전에는 이 값들이 AI 색인 파일에만 있어서 추천 서버가 Spring 카탈로그를 따로 당겨 와
합쳐야 했다. 연동 계약 2026-09-23 절 — MySQL 에 두고 Spring 이 요청 본문으로 보낸다.
"""
from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np

from music_preprocessing.analysis import api as ai
from music_preprocessing.analysis.indexer import NewTrack


def _track(meta: dict, content: dict, rms=(0.031, 0.087)) -> NewTrack:
    secs = [{"section_idx": i, "section_label": lab, "start_sec": a, "end_sec": b,
             "dur_sec": b - a, "rms": r}
            for i, (lab, a, b, r) in enumerate(
                [("intro", 0.0, 15.5, rms[0]), ("chorus", 15.5, 45.0, rms[1])])]
    n = len(secs)
    return NewTrack(track_id="T000501", source="flow", track_dur_sec=150.0, sections=secs,
                    embeddings=np.zeros((n, 1536), np.float32),
                    features=np.zeros((n, 285), np.float32),
                    section_vecs=np.zeros((n, 128), np.float32),
                    content_row=content, meta_row=meta, music_id=501)


class VocalTests(unittest.TestCase):
    """생성 요청의 보컬 종류가 정답이다. 분류기는 요청이 없을 때만 쓴다."""

    def test_the_request_wins_over_an_unsure_classifier(self):
        # 분류기가 unknown(확률 0.60 미만)을 낸 135곡은 전부 연주곡 요청이었고,
        # 들어 보니 연주곡이었다(2026-09-23).
        self.assertEqual(ai.vocal_of({"vocal_type": "instrumental", "voice_label": "unknown"}),
                         (False, None, "requested"))

    def test_the_request_gives_the_gender(self):
        self.assertEqual(ai.vocal_of({"vocal_type": "mixed"})[:2], (True, "MIX"))
        self.assertEqual(ai.vocal_of({"vocal_type": "female"})[:2], (True, "F"))

    def test_the_classifier_is_used_only_without_a_request(self):
        self.assertEqual(ai.vocal_of({"vocal_type": "unknown", "voice_label": "voice",
                                      "gender_label": "male"}),
                         (True, "M", "classifier"))

    def test_every_gender_the_erd_enum_has_can_come_from_the_classifier(self):
        """`ENUM('M','F','MIX')` 셋이 다 나가야 한다. 혼성이 `mixed` 를 못 받아 조용히
        비워졌던 일이 있다(S15P21A204-414). 옛 `ai_server/tools/register_analysis` 의 규칙이다."""
        for label, want in (("female", "F"), ("male", "M"), ("mixed", "MIX"), ("mix", "MIX")):
            self.assertEqual(ai.vocal_of({"voice_label": "voice", "gender_label": label})[:2],
                             (True, want), label)
        # 모르는 성별은 혼성으로 올리지 않는다
        self.assertEqual(ai.vocal_of({"voice_label": "voice", "gender_label": "unknown"})[:2],
                         (True, None))

    def test_an_instrumental_never_stores_a_gender(self):
        self.assertEqual(ai.vocal_of({"voice_label": "instrumental", "gender_label": "female"})[:2],
                         (False, None))

    def test_nothing_known_is_not_guessed(self):
        self.assertEqual(ai.vocal_of({"voice_label": "unknown"})[0], None)
        self.assertIsNone(ai.analysis_payload({"voice_label": "unknown"}, {"bpm": 120}),
                          "has_vocal 을 모르면 행을 만들지 않는다 — ERD 가 NOT NULL 이다")


class PayloadTests(unittest.TestCase):

    def test_values_come_from_the_same_columns_as_the_index(self):
        """BPM 은 content.bpm, 나머지는 flow_meta — `slice_index` 의 track_features 와 같다."""
        body = ai.analysis_payload(
            {"vocal_type": "female", "language": "korean", "category": "female_korean_ballad",
             "bpm_lo": 120.0, "bpm_hi": 130.0},
            {"bpm": 128.4567})
        self.assertEqual(body, {"has_vocal": True, "vocal_gender": "F", "bpm": 128.457,
                                "bpm_requested_min": 120, "bpm_requested_max": 130,
                                "language": "korean", "category": "female_korean_ballad"})

    def test_unknown_text_and_missing_numbers_are_null(self):
        body = ai.analysis_payload(
            {"vocal_type": "instrumental", "language": "unknown", "category": "",
             "bpm_lo": float("nan"), "bpm_hi": 130.0},
            {"bpm": 0})
        self.assertIsNone(body["language"])
        self.assertIsNone(body["category"])
        self.assertIsNone(body["bpm"], "0 은 '모른다' 다")
        self.assertIsNone(body["bpm_requested_min"], "범위는 둘 다 있거나 둘 다 없다")
        self.assertIsNone(body["bpm_requested_max"])

    def test_sections_carry_energy(self):
        lines = ai.sections_payload(_track({}, {}))
        self.assertEqual([x["energy"] for x in lines], [0.031, 0.087])

    def test_unmeasured_energy_is_left_out(self):
        lines = ai.sections_payload(_track({}, {}, rms=(float("nan"), None)))
        self.assertTrue(all("energy" not in x for x in lines))


class _Client:
    base_url = "http://api"

    def __init__(self):
        self.calls = []

    def add_code(self, *a):
        return {"created": False}

    def replace_sections(self, mid, payload, version):
        self.calls.append(("section", mid, payload))
        return {"music_id": mid, "replaced": len(payload),
                "sections": [{"external_id": p["external_id"],
                              "music_section_analysis_id": 9000 + i}
                             for i, p in enumerate(payload)]}

    def replace_analysis(self, mid, body):
        self.calls.append(("analysis", mid, body))
        return body


class RegistrarTests(unittest.TestCase):

    def test_registering_a_track_sends_sections_and_analysis(self):
        import tempfile

        client = _Client()
        reg = ai.HttpRegistrar(client=client)
        with tempfile.TemporaryDirectory() as d:
            out = reg.register([_track({"vocal_type": "male", "language": "english"},
                                       {"bpm": 100.0})], Path(d))
        kinds = [c[0] for c in client.calls]
        self.assertEqual(kinds, ["section", "analysis"])
        self.assertEqual(client.calls[1][2]["vocal_gender"], "M")
        self.assertEqual(out["analysis_skipped"], 0)

    def test_a_track_without_vocal_evidence_still_gets_its_sections(self):
        import tempfile

        client = _Client()
        reg = ai.HttpRegistrar(client=client)
        with tempfile.TemporaryDirectory() as d:
            out = reg.register([_track({}, {"bpm": 100.0})], Path(d))
        self.assertEqual([c[0] for c in client.calls], ["section"])
        self.assertEqual(out["analysis_skipped"], 1)


if __name__ == "__main__":
    unittest.main()
