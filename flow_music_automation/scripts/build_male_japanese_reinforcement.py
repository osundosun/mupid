# -*- coding: utf-8 -*-
"""
male_japanese 보강: female_japanese 소스 프롬프트를 gender만 치환해서 20개 생성.
(mixed_korean/mixed_english 확장 때와 동일 패턴 -- \bfemale\b -> male 외 전부 그대로)

대상 장르(전부 male_japanese 0곡 갭):
  contemporary_pop x5 (원본 5개, 재사용 없음)
  dance_pop x5 (원본 4개 + 1개 재사용)
  alternative_rock x5 (원본 3개 + 2개 재사용)
  indie_pop x5 (원본 3개 + 2개 재사용)

새 prompt_id: P1041~P1060, batch_id: B105(1041-1050)/B106(1051-1060)
"""
import json
import re

SRC_PATH = "prompts.jsonl"

PLAN = [
    ("female_japanese_contemporary_pop", ["P0019", "P0029", "P0039", "P0049", "P0059"]),
    ("female_japanese_dance_pop", ["P0069", "P0079", "P0089", "P0099", "P0069"]),
    ("female_japanese_alternative_rock", ["P0269", "P0279", "P0289", "P0269", "P0279"]),
    ("female_japanese_indie_pop", ["P0149", "P0159", "P0169", "P0149", "P0159"]),
]


def main():
    by_pid = {}
    with open(SRC_PATH, encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            by_pid[d["prompt_id"]] = d

    new_entries = []
    p_counter = 1041
    for genre_cat, pids in PLAN:
        genre = genre_cat.replace("female_japanese_", "")
        for pid in pids:
            src = by_pid[pid]
            new_prompt_text = re.sub(r"\bfemale\b", "male", src["prompt"])
            assert new_prompt_text != src["prompt"], f"no 'female' found in {pid}"
            new_pid = f"P{p_counter:04d}"
            batch_id = "B105" if p_counter <= 1050 else "B106"
            batch_position = ((p_counter - 1041) % 10) + 1
            new_entries.append({
                "prompt_id": new_pid,
                "batch_id": batch_id,
                "batch_position": batch_position,
                "category": f"male_japanese_{genre}",
                "vocal_type": "male",
                "language": "japanese",
                "requested_bpm_range": src["requested_bpm_range"],
                "prompt": new_prompt_text,
            })
            p_counter += 1

    print(f"built {len(new_entries)} new prompts (P1041~P{p_counter-1:04d})")
    for e in new_entries:
        print(" ", e["prompt_id"], e["batch_id"], e["category"])

    with open(SRC_PATH, "a", encoding="utf-8") as f:
        for e in new_entries:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    print("appended to", SRC_PATH)


if __name__ == "__main__":
    main()
