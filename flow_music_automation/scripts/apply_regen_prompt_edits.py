# -*- coding: utf-8 -*-
"""
267/277/287/317/318: female_korean_rock_edge_01("굉음") 소속 5곡을 여성->남성 보컬로 전환
  (나중에 언더독=male_korean_rock_edge_01에 합류시킬 예정, 이 스크립트는 재생성용 프롬프트만 바꿈).
2/14/16/17/18: 인트로 가사 포함 등 퀄리티 문제로, 같은 앨범 내 다른 곡의 프롬프트를 그대로
  가져와서 재생성 대상으로 교체(원래 prompt_id/batch_id/batch_position은 유지).

prompts.jsonl 전체를 백업 후 원자적으로 갱신하고, 즉시 전수 검증까지 수행한다.
"""
import json
import os
import re
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROMPTS_PATH = os.path.join(ROOT, "prompts.jsonl")

GENDER_FLIP = ["P0267", "P0277", "P0287", "P0317", "P0318"]

BORROW = {
    "P0002": "P0101",
    "P0014": "P0001",
    "P0016": "P0026",
    "P0017": "P0027",
    "P0018": "P0028",
}


def main():
    with open(PROMPTS_PATH, encoding="utf-8") as f:
        raw_lines = [l for l in f if l.strip()]
    records = [json.loads(l) for l in raw_lines]
    by_id = {r["prompt_id"]: r for r in records}

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_path = PROMPTS_PATH + f".bak_before_regen_edits_{ts}"
    shutil.copy2(PROMPTS_PATH, backup_path)
    print("backup:", backup_path)

    changes = []

    for pid in GENDER_FLIP:
        r = by_id[pid]
        before = dict(r)
        assert r["vocal_type"] == "female"
        assert r["category"].startswith("female_korean_")
        r["vocal_type"] = "male"
        r["category"] = "male_korean_" + r["category"][len("female_korean_"):]
        new_prompt, n = re.subn(r"\bfemale\b", "male", r["prompt"])
        assert n == 1, f"{pid}: expected exactly 1 'female' occurrence, got {n}"
        r["prompt"] = new_prompt
        changes.append((pid, "gender_flip", before, dict(r)))

    for pid, source_pid in BORROW.items():
        r = by_id[pid]
        src = by_id[source_pid]
        before = dict(r)
        for field in ("category", "vocal_type", "language", "requested_bpm_range", "prompt"):
            r[field] = src[field]
        changes.append((pid, f"borrowed_from_{source_pid}", before, dict(r)))

    # write back atomically, preserving original line order
    tmp_path = PROMPTS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    os.replace(tmp_path, PROMPTS_PATH)

    # verification
    with open(PROMPTS_PATH, encoding="utf-8") as f:
        new_lines = [json.loads(l) for l in f if l.strip()]
    assert len(new_lines) == len(records), "line count changed!"
    new_by_id = {r["prompt_id"]: r for r in new_lines}

    print(f"\n총 {len(records)}줄 유지 확인.")
    print(f"변경된 prompt_id: {len(changes)}개\n")
    for pid, kind, before, after in changes:
        cur = new_by_id[pid]
        assert cur == after, f"{pid} 검증 실패: 파일에 반영된 내용이 기대와 다름"
        print(f"  [{kind}] {pid}")
        print(f"      before: category={before['category']} vocal_type={before['vocal_type']}")
        print(f"      after : category={cur['category']} vocal_type={cur['vocal_type']}")

    changed_ids = set(BORROW) | set(GENDER_FLIP)
    unchanged_ok = 0
    for r_old, r_new_line in zip(records, raw_lines):
        pass
    # 변경 안 된 나머지 줄은 원본 그대로인지(같은 dict) 확인
    orig_by_id = {json.loads(l)["prompt_id"]: json.loads(l) for l in raw_lines}
    for pid, rec in new_by_id.items():
        if pid not in changed_ids:
            if rec != orig_by_id[pid]:
                print(f"  !! 의도치 않게 바뀐 줄 발견: {pid}")
            else:
                unchanged_ok += 1
    print(f"\n변경 안 된 {unchanged_ok}줄은 원본과 동일함 확인 완료.")
    print("\n검증 통과.")


if __name__ == "__main__":
    main()
