"""가사 싱크 실험 2단계: transcript(word-timestamp)를 lyrics_sections 원문과
줄 단위로 대조(difflib)해서 각 줄의 start_sec/end_sec/match_ratio를 추정.

2026-09-14 세션 검증 결과 (small 모델, CPU, vad_filter=False):
- 영어: match_ratio 92~100%, 거의 완벽.
- 일본어: 65~92%. 실제 오디오가 프롬프트의 마디 수 계획과 어긋나는 구간(순수 반주)도
  정확히 잡아냄 — BPM 계산보다 신뢰도 높음.
- 한국어: 대부분 75~88%지만 "(라라라 라라라)" 같은 스캣/애드립 구간은 정렬 실패(0%,
  start_sec=null)가 자주 나옴. 이는 정렬 로직의 한계라기보다 Flow 한국어 생성 음원이
  써준 가사를 실제로 잘 안 부르는 경향(사용자가 2026-09-14에 확인) 쪽에 더 가까움.

사용법:
    python scripts/lyrics_sync_align.py <track_id> [--model small] [--dir logs/lyrics_sync]

전제: 같은 track_id로 lyrics_sync_transcribe.py를 먼저 돌려서
logs/lyrics_sync/<track_id>_transcript_<model>.json이 존재해야 함.
"""
import argparse
import json
import re
import unicodedata
from bisect import bisect_right
from difflib import SequenceMatcher
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    text = re.sub(r"[\s　]+", "", text)
    text = re.sub(r"[.,!?、。「」『』()（）\-~〜…♪]", "", text)
    return text.lower()


def build_transcript_words(transcript_path: Path):
    data = json.loads(transcript_path.read_text(encoding="utf-8"))
    words = []
    for seg in data["segments"]:
        for w in seg["words"]:
            norm = normalize(w["word"])
            if not norm:
                continue
            words.append({"norm": norm, "start": w["start"], "end": w["end"]})
    return words


def align_lines(track_id: str, model_size: str, transcript_dir: Path):
    meta_path = PROJECT / "downloads" / "metadata" / f"{track_id}.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    sections = meta["lyrics_sections"] or []

    transcript_path = transcript_dir / f"{track_id}_transcript_{model_size}.json"
    words = build_transcript_words(transcript_path)

    t_concat_parts = []
    t_word_start_offsets = []
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
        t_positions = []
        matched_chars = 0
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("track_id")
    ap.add_argument("--model", default="small")
    ap.add_argument("--dir", default="logs/lyrics_sync")
    args = ap.parse_args()

    transcript_dir = PROJECT / args.dir
    results = align_lines(args.track_id, args.model, transcript_dir)

    out_path = transcript_dir / f"{args.track_id}_lines_{args.model}.json"
    out_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    aligned = sum(1 for r in results if r.get("start_sec") is not None)
    total = sum(1 for r in results if not r.get("is_empty_section"))
    print(f"{args.track_id}: {aligned}/{total} lines aligned -> {out_path}")


if __name__ == "__main__":
    main()
