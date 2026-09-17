"""가사 싱크 실험 1단계: faster-whisper로 오디오를 word-timestamp까지 transcribe.

2026-09-14 세션에서 T001060(일본어)/T000016(한국어)/T000001(영어) 3곡으로 검증 완료.
핵심 교훈: vad_filter=True(기본값)를 켜두면 에너지 낮은 트랙에서 보컬을 무음으로
오인해 세그먼트를 통째로 날려버리는 경우가 있었음(T001060에서 실제로 발생) — 반드시
vad_filter=False로 돌릴 것.

사용법:
    python scripts/lyrics_sync_transcribe.py <track_id> [--model small] [--lang ja] [--out-dir logs/lyrics_sync]

requested.language가 None인 트랙(4곡, 2026-09-14 기준)은 --lang을 생략하면
faster-whisper가 자동 감지한다.
"""
import argparse
import json
import time
from pathlib import Path

from faster_whisper import WhisperModel

PROJECT = Path(__file__).resolve().parent.parent

LANG_MAP = {"japanese": "ja", "korean": "ko", "english": "en"}


def transcribe_track(track_id: str, model: WhisperModel, lang: str | None):
    audio_path = PROJECT / "downloads" / "audio" / f"{track_id}.mp3"
    segments, info = model.transcribe(
        str(audio_path),
        language=lang,
        word_timestamps=True,
        vad_filter=False,
    )
    segments = list(segments)

    out = {"track_id": track_id, "detected_language": info.language, "language_probability": info.language_probability, "segments": []}
    for seg in segments:
        words = []
        if seg.words:
            for w in seg.words:
                words.append({"word": w.word, "start": round(w.start, 2), "end": round(w.end, 2), "prob": round(w.probability, 3)})
        out["segments"].append({"start": round(seg.start, 2), "end": round(seg.end, 2), "text": seg.text, "words": words})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("track_id")
    ap.add_argument("--model", default="small")
    ap.add_argument("--lang", default=None, help="ja/ko/en 등. 생략하면 requested.language에서 유추, 그것도 없으면 자동 감지")
    ap.add_argument("--out-dir", default="logs/lyrics_sync")
    args = ap.parse_args()

    meta_path = PROJECT / "downloads" / "metadata" / f"{args.track_id}.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))

    lang = args.lang
    if lang is None:
        req_lang = meta.get("requested", {}).get("language")
        lang = LANG_MAP.get(req_lang)  # None이면 faster-whisper가 자동 감지

    model = WhisperModel(args.model, device="cpu", compute_type="int8")

    t0 = time.time()
    result = transcribe_track(args.track_id, model, lang)
    result["model_size"] = args.model
    result["elapsed_sec"] = round(time.time() - t0, 1)

    out_dir = PROJECT / args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{args.track_id}_transcript_{args.model}.json"
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{args.track_id}: {result['elapsed_sec']}s, lang={result['detected_language']} -> {out_path}")


if __name__ == "__main__":
    main()
