"""T000298 한 곡으로 medium 모델 재시도 + 보간 테스트 (비교용, 프로덕션 메타데이터/GPU 서버는 안 건드림).

1단계: medium 모델로 재transcribe+정렬 -> downloads/lyrics_sync/T000298_medium.json에 저장
       (기존 small 모델 결과 T000298.json은 그대로 둬서 비교 가능).
2단계: 그래도 못 맞춘 줄은 앞뒤 실측 앵커 사이를 글자수 비율로 보간해서 채움
       (신뢰도/추정 표시 없이 그냥 값으로 채움 -- 사용자 지시, 2026-09-15).
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
TRACK_ID = "T000298"
DURATION_SEC = 174.848  # ffprobe


def interpolate(lines, duration_sec):
    """실측 앵커(start_sec/end_sec 있는 줄) 사이를, 없는 줄들을 글자수 비율로 채운다."""
    real = [i for i, ln in enumerate(lines) if not ln["is_empty_section"]]
    out = [dict(ln) for ln in lines]

    i = 0
    while i < len(real):
        idx = real[i]
        if out[idx]["start_sec"] is not None:
            i += 1
            continue
        # find contiguous unaligned run [i, j)
        j = i
        while j < len(real) and out[real[j]]["start_sec"] is None:
            j += 1
        run_indices = real[i:j]

        prev_end = out[real[i - 1]]["end_sec"] if i > 0 else 0.0
        next_start = out[real[j]]["start_sec"] if j < len(real) else duration_sec

        gap = max(0.01, next_start - prev_end)
        lens = [max(1, len(out[k]["line_text"])) for k in run_indices]
        total_len = sum(lens)

        cursor = prev_end
        for k, ln_len in zip(run_indices, lens):
            dur = gap * (ln_len / total_len)
            out[k]["start_sec"] = round(cursor, 2)
            out[k]["end_sec"] = round(cursor + dur, 2)
            cursor += dur

        i = j
    return out


def main():
    meta = json.loads((PROJECT / "downloads" / "metadata" / f"{TRACK_ID}.json").read_text(encoding="utf-8"))
    lang = LANG_MAP.get(meta.get("requested", {}).get("language"))
    audio_path = PROJECT / "downloads" / "audio" / f"{TRACK_ID}.mp3"

    print("[1/2] transcribing with medium model...")
    model = WhisperModel("medium", device="cpu", compute_type="int8")
    t0 = time.time()
    segments, info = model.transcribe(str(audio_path), language=lang, word_timestamps=True, vad_filter=False)
    segments = list(segments)
    elapsed = time.time() - t0
    print(f"  done in {elapsed:.1f}s, detected_language={info.language}")

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

    lines_medium = align_lines(meta, words)

    result = {
        "track_id": TRACK_ID, "engine": "faster-whisper", "model_size": "medium", "device": "cpu",
        "detected_language": info.language, "elapsed_sec": round(elapsed, 1),
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "transcript_segments": transcript_segments, "lines": lines_medium,
    }
    out_path = PROJECT / "downloads" / "lyrics_sync" / f"{TRACK_ID}_medium.json"
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    real_lines = [ln for ln in lines_medium if not ln["is_empty_section"]]
    aligned_medium = sum(1 for ln in real_lines if ln["start_sec"] is not None)
    print(f"  medium model: {aligned_medium}/{len(real_lines)} lines aligned (saved -> {out_path})")

    print("[2/2] interpolating remaining gaps...")
    lines_final = interpolate(lines_medium, DURATION_SEC)
    still_null = sum(1 for ln in lines_final if not ln["is_empty_section"] and ln["start_sec"] is None)
    print(f"  after interpolation: still null = {still_null}")

    final_path = PROJECT / "downloads" / "lyrics_sync" / f"{TRACK_ID}_medium_interpolated.json"
    final_path.write_text(json.dumps({"lines": lines_final}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  saved -> {final_path}")


if __name__ == "__main__":
    main()
