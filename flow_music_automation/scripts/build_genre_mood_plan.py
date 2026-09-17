# -*- coding: utf-8 -*-
"""
백엔드 DB(music_genres/music_moods 테이블)에 업로드할 genre/mood 값을
downloads/metadata/T*.json에서 파생시키는 계획을 만든다.

- genre: requested.category에서 성별/언어 접두어를 제거한 나머지("장르" 세그먼트).
  instrumental_* 은 "instrumental_" 접두어만 제거. 곡당 1개.
- mood: prompt 원문에서 "primarily X with a Y undertone", "primary mood: X;
  secondary mood: Y" 등 여러 세대의 프롬프트 템플릿 문구를 정규식으로 파싱해
  {primary, secondary, energy} 형태로 뽑는다. 곡당 0~2개 mood 태그
  (primary/secondary). 어떤 프롬프트 템플릿에도 mood 관련 문구가 전혀 없으면
  (극초기 배치 B001 일부) mood는 null로 남긴다 — 추측해서 채우지 않는다.

이 스크립트는 계획(JSON)만 만든다. downloads/metadata에 실제로 반영하는 건
apply_genre_mood_to_metadata.py가 한다.
"""
import json
import os
import re
import glob
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, "downloads", "metadata")
OUT_PLAN_PATH = os.path.join(ROOT, "genre_mood_plan.json")
OUT_TAXONOMY_PATH = os.path.join(ROOT, "genre_mood_taxonomy.json")

VOCAL_TYPES = {"male", "female", "mixed", "instrumental"}
LANGUAGES = {"english", "korean", "japanese"}

# 우선순위 순서대로 시도. 먼저 매치되는 패턴을 채택한다.
# 각 패턴은 (primary, secondary) 2개 그룹을 캡처한다. energy 숫자가 있는
# 패턴만 3개 그룹(energy, primary, secondary 순서는 패턴마다 다름 -> 아래
# extract_mood에서 패턴별로 개별 처리).
MOOD_PATTERNS = [
    ("energy_undertone",
     re.compile(r"Energy\s*(\d)/5;\s*primarily\s+([\w-]+)\s+with\s+an?\s+([\w-]+)\s+undertone", re.I)),
    ("energy_primary_secondary",
     re.compile(r"Energy\s*(\d)/5;\s*primary mood:\s*([\w-]+);\s*secondary mood:\s*([\w-]+)", re.I)),
    ("balance_combine",
     re.compile(r"(?:Balance|Combine) an?\s+([\w-]+)\s+primary mood with an?\s+(?:subtle\s+)?([\w-]+)\s+undertone", re.I)),
    ("vocals_overall_tone",
     re.compile(r"\b([A-Za-z]+)\s+(?:English|Korean|Japanese)\s+(?:male|female)\s+lead vocals and an?\s+([\w-]+)\s+overall tone", re.I)),
    ("emotional_core",
     re.compile(r"[Gg]ive the song an?\s+([\w-]+)\s+emotional core balanced by an?\s+([\w-]+)\s+(?:outward tone|undertone)", re.I)),
    ("overall_mood_and",
     re.compile(r"overall mood\s+([\w-]+)\s+and\s+([\w-]+)", re.I)),
    ("keep_mood_and",
     re.compile(r"[Kk]eep the mood\s+([\w-]+)\s+and\s+([\w-]+)", re.I)),
    ("core_and_in_mood",
     re.compile(r"([\w-]+)\s+and\s+([\w-]+)\s+in mood,", re.I)),
    ("mood_should_feel",
     re.compile(r"mood should feel\s+([\w-]+)(?:\s+and\s+([\w-]+))?", re.I)),
]

ENERGY_METHODS = {"energy_undertone", "energy_primary_secondary"}


def extract_genre(category):
    parts = category.split("_")
    if parts[0] == "instrumental":
        return "_".join(parts[1:])
    if parts[0] in VOCAL_TYPES and len(parts) >= 2 and parts[1] in LANGUAGES:
        return "_".join(parts[2:])
    raise ValueError(f"unrecognized category shape: {category}")


def extract_mood(prompt):
    for name, pat in MOOD_PATTERNS:
        m = pat.search(prompt)
        if not m:
            continue
        groups = m.groups()
        if name in ENERGY_METHODS:
            energy, primary, secondary = groups
            energy = int(energy)
        else:
            primary, secondary = (groups + (None,))[:2]
            energy = None
        return {
            "primary": primary.lower() if primary else None,
            "secondary": secondary.lower() if secondary else None,
            "energy": energy,
            "method": name,
        }
    return None


def main():
    files = sorted(glob.glob(os.path.join(METADATA_DIR, "T*.json")))
    print(f"[INFO] total metadata files: {len(files)}")

    plan = []
    genre_counter = collections.Counter()
    mood_method_counter = collections.Counter()
    mood_word_counter = collections.Counter()
    no_mood = []

    for fp in files:
        with open(fp, encoding="utf-8") as f:
            meta = json.load(f)
        track_id = meta["track_id"]
        category = meta.get("requested", {}).get("category")
        assert category, f"{track_id}: requested.category missing"
        genre = extract_genre(category)
        genre_counter[genre] += 1

        prompt = meta.get("prompt", "")
        mood = extract_mood(prompt)
        if mood is None:
            no_mood.append(track_id)
            mood_method_counter["NONE"] += 1
        else:
            mood_method_counter[mood["method"]] += 1
            if mood["primary"]:
                mood_word_counter[mood["primary"]] += 1
            if mood["secondary"]:
                mood_word_counter[mood["secondary"]] += 1

        plan.append({
            "track_id": track_id,
            "genre": genre,
            "mood": mood,
        })

    with open(OUT_PLAN_PATH, "w", encoding="utf-8") as f:
        json.dump(plan, f, ensure_ascii=False, indent=2)
    print(f"[OK] wrote plan: {OUT_PLAN_PATH} ({len(plan)} entries)")

    taxonomy = {
        "genres": [{"name": g, "track_count": c} for g, c in sorted(genre_counter.items(), key=lambda kv: -kv[1])],
        "moods": [{"name": m, "track_count": c} for m, c in sorted(mood_word_counter.items(), key=lambda kv: -kv[1])],
        "genre_total_distinct": len(genre_counter),
        "mood_total_distinct": len(mood_word_counter),
        "tracks_without_mood": no_mood,
    }
    with open(OUT_TAXONOMY_PATH, "w", encoding="utf-8") as f:
        json.dump(taxonomy, f, ensure_ascii=False, indent=2)
    print(f"[OK] wrote taxonomy: {OUT_TAXONOMY_PATH}")

    print()
    print(f"[INFO] distinct genres: {len(genre_counter)}")
    print(f"[INFO] distinct mood words: {len(mood_word_counter)} -> {dict(mood_word_counter.most_common())}")
    print(f"[INFO] mood extraction method breakdown: {dict(mood_method_counter.most_common())}")
    print(f"[INFO] tracks with no extractable mood: {len(no_mood)} -> {no_mood}")


if __name__ == "__main__":
    main()
