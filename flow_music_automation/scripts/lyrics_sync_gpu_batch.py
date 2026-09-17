"""가사 싱크 배치 처리 (GPU 서버용).

2026-09-14~16 검증 이력:
- small 모델로 1차 처리(516곡) 완료했으나, medium 모델이 실제로 더 정확함을
  T000298 비교 테스트로 확인(19/31 -> 23/31 정렬). large-v3는 노래(특히 보컬)에서
  환각("한글자막 by 한효정" 등 가짜 자막 크레딧 문구)을 일으켜 오히려 훨씬 나쁨
  (7/31) -- 노래 오디오엔 large 계열을 쓰지 말 것, medium이 최적점.
- 정렬 실패한 줄은 신뢰도(%) 표시 없이 그냥 앞뒤 실측 앵커 사이를 글자수 비율로
  보간해서 채움(사용자 지시: 스트리밍 서비스도 가끔 안 맞으니 오차는 자연스럽게
  받아들임). 첫 실측 앵커 이전 구간(전체 516곡 중 20%가 30초 이상 공백)은 prompt에
  BPM+"Intro N bars" 패턴이 있으면(516곡 중 381곡=74%) 그걸로 계산한 인트로 길이를
  0초 대신 하한선으로 씀 -- 실제 인트로 길이와 정확히 일치하진 않지만 0초보다는 나음.
- 노래 내용 자체가 원문 가사(lyrics_raw)와 다르게 불린 트랙(예: T001028, 실제 가사가
  저장된 원문과 통째로 다름)은 이 보간으로 해결 안 됨 -- 이미 예전에 "보컬 프레이즈
  이탈" 조사(496곡)에서 조치 불필요로 결론난 것과 같은 부류라 그대로 둠.

여러 GPU에 나눠 돌릴 수 있도록 --shard/--num-shards/--gpu-index를 받는다.
이미 결과 파일이 있는 트랙은 건너뛰므로(체크포인트) 중간에 끊겨도 재실행하면 이어서 처리됨.
재처리하려면 downloads/lyrics_sync/{track_id}.json을 먼저 지울 것.

사용 예 (GPU 4장에 나눠 병렬 실행):
    for i in 0 1 2 3; do
        CUDA_VISIBLE_DEVICES=$i nohup python lyrics_sync_gpu_batch.py \
            --shard $i --num-shards 4 --gpu-index 0 \
            > logs_shard$i.txt 2>&1 &
    done
"""
import argparse
import datetime
import json
import re
import time
import unicodedata
from bisect import bisect_right
from difflib import SequenceMatcher
from pathlib import Path

from faster_whisper import WhisperModel

DATA_DIR = Path.home() / "flow_music_data"
LANG_MAP = {"japanese": "ja", "korean": "ko", "english": "en"}

_BPM_RE = re.compile(r"(\d+)\s*BPM")
_INTRO_BARS_RE = re.compile(r"section plan in bars:\s*Intro\s*(\d+)")


def parse_intro_seconds(prompt_text):
    """prompt의 'N BPM' + 'Intro N bars' 패턴으로 인트로 길이(초)를 계산.
    둘 다 못 찾으면 None (호출부에서 0초로 폴백)."""
    bpm_m = _BPM_RE.search(prompt_text or "")
    bars_m = _INTRO_BARS_RE.search(prompt_text or "")
    if not bpm_m or not bars_m:
        return None
    bpm = int(bpm_m.group(1))
    bars = int(bars_m.group(1))
    if bpm <= 0:
        return None
    return bars * 4 * 60 / bpm


def interpolate(lines, duration_sec, intro_floor_sec):
    """실측 앵커(start_sec 있는 줄) 사이를, 없는 줄들을 글자수 비율로 채운다.
    맨 앞(첫 앵커 이전) 구간만 0초 대신 intro_floor_sec를 하한선으로 씀."""
    real = [i for i, ln in enumerate(lines) if not ln["is_empty_section"]]
    out = [dict(ln) for ln in lines]

    i = 0
    while i < len(real):
        idx = real[i]
        if out[idx]["start_sec"] is not None:
            i += 1
            continue
        j = i
        while j < len(real) and out[real[j]]["start_sec"] is None:
            j += 1
        run = real[i:j]

        if i == 0:
            prev_end = intro_floor_sec if intro_floor_sec is not None else 0.0
        else:
            prev_end = out[real[i - 1]]["end_sec"]
        next_start = out[real[j]]["start_sec"] if j < len(real) else duration_sec
        next_start = max(next_start, prev_end + 0.01)

        gap = max(0.01, next_start - prev_end)
        lens = [max(1, len(out[k]["line_text"])) for k in run]
        total_len = sum(lens)
        cursor = prev_end
        for k, ln_len in zip(run, lens):
            dur = gap * (ln_len / total_len)
            out[k]["start_sec"] = round(cursor, 2)
            out[k]["end_sec"] = round(cursor + dur, 2)
            out[k]["interpolated"] = True
            cursor += dur
        i = j
    for ln in out:
        ln.setdefault("interpolated", False)
    return out


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = re.sub(r"[\s　]+", "", text)
    text = re.sub(r"[.,!?、。「」『』()（）\-~〜…♪]", "", text)
    return text.lower()


def list_vocal_tracks():
    tracks = []
    for f in sorted((DATA_DIR / "metadata").glob("*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        if d.get("flow_generated", {}).get("lyrics_present"):
            tracks.append(d["track_id"])
    return tracks


def align_lines(meta, words):
    sections = meta.get("lyrics_sections") or []

    t_concat_parts, t_word_start_offsets = [], []
    offset = 0
    for w in words:
        t_word_start_offsets.append(offset)
        t_concat_parts.append(w["norm"])
        offset += len(w["norm"])
    t_concat = "".join(t_concat_parts)
    t_word_start_offsets.append(offset)

    def char_to_word_idx(char_pos):
        idx = bisect_right(t_word_start_offsets, char_pos) - 1
        return max(0, min(idx, len(words) - 1)) if words else 0

    lines = []
    l_concat_parts = []
    offset = 0
    for sec in sections:
        if not sec["has_text"]:
            lines.append({
                "section_order": sec["order"], "section_id": sec["section_id"], "raw_label": sec["raw_label"],
                "type": sec["type"], "line_text": None, "start_char": None, "end_char": None,
                "is_empty_section": True,
            })
            continue
        for raw_line in sec["text"].split("\n"):
            norm = normalize(raw_line)
            if not norm:
                continue
            start = offset
            l_concat_parts.append(norm)
            offset += len(norm)
            lines.append({
                "section_order": sec["order"], "section_id": sec["section_id"], "raw_label": sec["raw_label"],
                "type": sec["type"], "line_text": raw_line, "start_char": start, "end_char": offset,
                "is_empty_section": False,
            })
    l_concat = "".join(l_concat_parts)

    matcher = SequenceMatcher(None, l_concat, t_concat, autojunk=False)
    blocks = [b for b in matcher.get_matching_blocks() if b.size > 0]

    results = []
    for ln in lines:
        if ln["is_empty_section"] or not words:
            results.append({**ln, "start_sec": None, "end_sec": None, "match_ratio": None})
            continue
        s_start, s_end = ln["start_char"], ln["end_char"]
        t_positions, matched_chars = [], 0
        for b in blocks:
            a_start, b_start, size = b.a, b.b, b.size
            a_end = a_start + size
            ov_start, ov_end = max(a_start, s_start), min(a_end, s_end)
            if ov_start < ov_end:
                matched_chars += ov_end - ov_start
                t_positions.append((b_start + (ov_start - a_start), b_start + (ov_end - a_start)))

        line_len = s_end - s_start
        if not t_positions:
            results.append({**ln, "start_sec": None, "end_sec": None, "match_ratio": 0.0})
            continue

        t_min, t_max = min(p[0] for p in t_positions), max(p[1] for p in t_positions)
        wi_start = char_to_word_idx(t_min)
        wi_end = char_to_word_idx(max(t_min, t_max - 1))
        results.append({
            **ln,
            "start_sec": round(words[wi_start]["start"], 2),
            "end_sec": round(words[wi_end]["end"], 2),
            "match_ratio": round(matched_chars / line_len, 2) if line_len else None,
        })
    return results


def process_track(track_id, model, model_size):
    out_path = DATA_DIR / "lyrics_sync" / f"{track_id}.json"
    if out_path.exists():
        return "skip", 0.0

    meta_path = DATA_DIR / "metadata" / f"{track_id}.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    lang = LANG_MAP.get(meta.get("requested", {}).get("language"))
    audio_path = DATA_DIR / "audio" / f"{track_id}.mp3"

    t0 = time.time()
    try:
        segments, info = model.transcribe(
            str(audio_path), language=lang, word_timestamps=True, vad_filter=False,
        )
        segments = list(segments)
    except Exception as e:
        out_path.write_text(json.dumps({"track_id": track_id, "error": str(e)}, ensure_ascii=False), encoding="utf-8")
        return "error", time.time() - t0

    elapsed = time.time() - t0

    words = []
    transcript_segments = []
    for seg in segments:
        seg_words = []
        if seg.words:
            for w in seg.words:
                norm = normalize(w.word)
                seg_words.append({"word": w.word, "start": round(w.start, 2), "end": round(w.end, 2)})
                if norm:
                    words.append({"norm": norm, "start": w.start, "end": w.end})
        transcript_segments.append({"start": round(seg.start, 2), "end": round(seg.end, 2), "text": seg.text, "words": seg_words})

    lines_raw = align_lines(meta, words)
    intro_floor = parse_intro_seconds(meta.get("prompt", ""))
    lines_final = interpolate(lines_raw, info.duration, intro_floor)

    result = {
        "track_id": track_id,
        "engine": "faster-whisper",
        "model_size": model_size,
        "device": "cuda",
        "detected_language": info.language,
        "language_probability": round(info.language_probability, 3),
        "elapsed_sec": round(elapsed, 1),
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "duration_sec": round(info.duration, 2),
        "intro_floor_sec": round(intro_floor, 2) if intro_floor is not None else None,
        "transcript_segments": transcript_segments,
        "lines": lines_final,
    }
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return "ok", elapsed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="medium")
    ap.add_argument("--shard", type=int, default=0)
    ap.add_argument("--num-shards", type=int, default=1)
    ap.add_argument("--gpu-index", type=int, default=0)
    args = ap.parse_args()

    (DATA_DIR / "lyrics_sync").mkdir(parents=True, exist_ok=True)

    model = WhisperModel(args.model, device="cuda", device_index=args.gpu_index, compute_type="float16")

    tracks = list_vocal_tracks()
    my_tracks = tracks[args.shard::args.num_shards]
    print(f"[shard {args.shard}/{args.num_shards} gpu{args.gpu_index}] {len(my_tracks)} tracks assigned", flush=True)

    done = skipped = errors = 0
    t_start = time.time()
    for tid in my_tracks:
        status, elapsed = process_track(tid, model, args.model)
        if status == "ok":
            done += 1
        elif status == "skip":
            skipped += 1
        else:
            errors += 1
        print(f"[shard {args.shard}] {tid}: {status} ({elapsed:.1f}s) | done={done} skip={skipped} err={errors}", flush=True)

    total = time.time() - t_start
    print(f"[shard {args.shard}] FINISHED in {total:.1f}s | done={done} skip={skipped} err={errors}", flush=True)


if __name__ == "__main__":
    main()
