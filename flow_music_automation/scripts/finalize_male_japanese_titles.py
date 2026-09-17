# -*- coding: utf-8 -*-
"""
male_japanese 보강 20곡의 flow_title 정리:
1. "(Take 2)" 태그 제거 (기존 Wave8 관례)
2. 일본어 원제 옆에 한국어 번역 병기: "원제(번역)" (기존 90곡 번역 작업과 동일 형식)
   (원제가 이미 영어인 BREAKTHROUGH는 번역 생략, T000399 Empty Seat 선례와 동일)
"""
import json
import os
import re
import shutil
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")

TRANSLATIONS = {
    "T001041": "サンシャイン・ステップ(선샤인 스텝)",
    "T001042": "未投函の絵はがき(부치지 않은 그림엽서)",
    "T001043": "潮風とシトラス(바닷바람과 시트러스)",
    "T001044": "落ち葉の坂道(낙엽 진 언덕길)",
    "T001045": "青空スニーカー(푸른 하늘의 스니커즈)",
    "T001046": "朝へ走れ(아침을 향해 달려)",
    # T001047 BREAKTHROUGH -- already English, no translation needed
    "T001048": "波を越えて(파도를 넘어서)",
    "T001049": "週末とステップ(주말과 스텝)",
    "T001050": "緑の車窓から(초록빛 차창 밖으로)",
    "T001051": "鎖を砕け(사슬을 부숴라)",  # Take 2 tag stripped
    "T001052": "標識の先(표지판 너머)",     # Take 2 tag stripped
    "T001053": "叩き直せ(두들겨 바로잡아)",
    "T001054": "灼熱の峠(타오르는 고개)",
    "T001055": "シグナル(시그널)",
    "T001056": "冷たい砂(차가운 모래)",
    "T001057": "潮風の歩道(바닷바람 부는 인도)",
    "T001058": "陽だまりの視線(양지의 시선)",
    "T001059": "小さな芽(작은 새싹)",
    "T001060": "フィルムの歩道(필름 속 인도)",  # Take 2 tag stripped
}


def main():
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = os.path.join(ROOT, "downloads", f"metadata.bak_before_male_ja_titles_{ts}")
    os.makedirs(backup_dir, exist_ok=True)
    for tid in TRANSLATIONS:
        shutil.copy2(os.path.join(METADATA_DIR, f"{tid}.json"), os.path.join(backup_dir, f"{tid}.json"))
    print(f"[OK] backed up {len(TRANSLATIONS)} files to {backup_dir}")

    old_titles = {}
    for tid in TRANSLATIONS:
        meta_path = os.path.join(METADATA_DIR, f"{tid}.json")
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        old_titles[tid] = meta["flow_title"]

    applied = {}
    for tid, new_title in TRANSLATIONS.items():
        meta_path = os.path.join(METADATA_DIR, f"{tid}.json")
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)
        meta["flow_title"] = new_title
        tmp = meta_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        os.replace(tmp, meta_path)
        applied[tid] = new_title

    print(f"[OK] applied {len(applied)} title updates")
    for tid in applied:
        print(f"   {tid}: {old_titles[tid]!r} -> {applied[tid]!r}")

    # verify
    fail = []
    for tid, expected in applied.items():
        with open(os.path.join(METADATA_DIR, f"{tid}.json"), encoding="utf-8") as f:
            meta = json.load(f)
        if meta["flow_title"] != expected:
            fail.append(tid)
    if fail:
        print("[FAIL]", fail)
    else:
        print(f"[OK] verified all {len(applied)} titles")


if __name__ == "__main__":
    main()
