"""lyrics_sections가 null이라 1차 배치에서 빠졌던 소수 트랙을 재수집(lyrics_raw refetch)
후 로컬 CPU로 가사 싱크 재처리할 때 쓰는 스크립트. lyrics_sync_gpu_batch.py와 완전히
동일한 align_lines()를 재사용해서 downloads/lyrics_sync/{track_id}.json에 같은 포맷으로
저장하므로, apply_lyrics_sync_to_metadata.py를 그대로 재사용해 병합 가능.

사용법: python scripts/lyrics_sync_retry_local.py T000075 T000146 ...
"""
import datetime
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lyrics_sync_gpu_batch import align_lines, normalize  # noqa: E402

from faster_whisper import WhisperModel

PROJECT = Path(__file__).resolve().parent.parent
LANG_MAP = {"japanese": "ja", "korean": "ko", "english": "en"}


def process_track(track_id, model, model_size):
    out_path = PROJECT / "downloads" / "lyrics_sync" / f"{track_id}.json"

    meta_path = PROJECT / "downloads" / "metadata" / f"{track_id}.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    lang = LANG_MAP.get(meta.get("requested", {}).get("language"))
    audio_path = PROJECT / "downloads" / "audio" / f"{track_id}.mp3"

    t0 = time.time()
    segments, info = model.transcribe(str(audio_path), language=lang, word_timestamps=True, vad_filter=False)
    segments = list(segments)
    elapsed = time.time() - t0

    words, transcript_segments = [], []
    for seg in segments:
        seg_words = []
        if seg.words:
            for w in seg.words:
                norm = normalize(w.word)
                seg_words.append({"word": w.word, "start": round(w.start, 2), "end": round(w.end, 2)})
                if norm:
                    words.append({"norm": norm, "start": w.start, "end": w.end})
        transcript_segments.append({"start": round(seg.start, 2), "end": round(seg.end, 2), "text": seg.text, "words": seg_words})

    lines = align_lines(meta, words)

    result = {
        "track_id": track_id, "engine": "faster-whisper", "model_size": model_size, "device": "cpu",
        "detected_language": info.language, "language_probability": round(info.language_probability, 3),
        "elapsed_sec": round(elapsed, 1),
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "transcript_segments": transcript_segments, "lines": lines,
    }
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return elapsed, len(lines)


def main():
    track_ids = sys.argv[1:]
    if not track_ids:
        print("usage: lyrics_sync_retry_local.py TRACK_ID [TRACK_ID ...]")
        return
    model = WhisperModel("small", device="cpu", compute_type="int8")
    for tid in track_ids:
        elapsed, n_lines = process_track(tid, model, "small")
        print(f"{tid}: {elapsed:.1f}s, {n_lines} lines")


if __name__ == "__main__":
    main()
