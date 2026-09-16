"""온보딩 답변을 카탈로그 어휘로 옮긴다. **서빙 경로에서 쓴다.**

온보딩은 이제 요청에 실려 온다(배치로 받아 두지 않는다). 받는 값은 Spring 의 코드표
이름이고, 추천기가 아는 것은 AI 카탈로그의 태그다. **둘은 한 건도 겹치지 않는다** —
2026-09-15 실측으로 mood 0/8 · genre 0/8 이었다.

변환 없이 넘기면 씨앗이 비고, 씨앗이 비면 **연결은 됐는데 개인화가 0** 이 된다. 오류는
나지 않아 눈에 띄지도 않는다. 그래서 변환표가 여기 있다.

원래 `tools/fetch_onboarding.py`(배치)에 있던 것을 옮겨 왔다. 온보딩을 요청으로 받게
되면서 그 배치는 없앴고, 변환표는 여기 하나만 남는다.
"""
from __future__ import annotations

import json

import numpy as np

# Spring 온보딩 코드표(8종) → AI 카탈로그 태그. **여기가 유일한 자리다.**
#
# mood 는 뜻으로 이어야 해서 표로 적는다. genre 는 대분류 → 세분류라 이름 포함으로
# 자동 확장한다(`Rock` → `Alternative Rock`·`Pop Rock`·`Indie Rock` …).
MOOD_MAP = {
    "잔잔한": ["relaxing", "meditative", "slow", "background"],
    "벅찬": ["epic", "uplifting", "inspiring", "motivational"],
    "몽환적인": ["space", "deep", "drama"],
    "신나는": ["energetic", "happy", "party", "positive"],
    "집중되는": ["background", "corporate", "meditative"],
    "감성적인": ["emotional", "melodic", "sad", "ballad"],
    "강렬한": ["heavy", "action", "trailer", "dark"],
    "설레는": ["love", "romantic", "summer"],
}

# 이름 포함으로는 못 잇는 것만 적는다.
GENRE_EXTRA = {
    "Electronic": ["House", "Downtempo", "Synthwave", "Tropical House", "Dubstep",
                   "Drum n Bass", "Electro House", "Chillwave", "Nu-Disco", "Synth-pop"],
    "Hip-hop": ["Cloud Rap", "Trap"],
    "Indie": ["Folk"],
}


def map_mood(names: list[str]) -> list[str]:
    out: list[str] = []
    for n in names:
        for v in MOOD_MAP.get(n, []):
            if v not in out:
                out.append(v)
    return out


def map_genre(names: list[str], catalog: list[str]) -> list[str]:
    """대분류 이름이 세분류 안에 들어 있으면 같은 무리로 본다."""
    out: list[str] = []
    for n in names:
        hit = [c for c in catalog if n.lower() in c.lower()]
        hit += [c for c in GENRE_EXTRA.get(n, []) if c in catalog]
        for v in hit:
            if v not in out:
                out.append(v)
    return out


# 추천기가 읽는 축. 채우지 못하는 축도 **키는 둔다** — 빠뜨리면 옛 행과 합칠 때
# NaN 이 되고, NaN 은 truthy 라 `x not in NaN` 에서 터진다(2026-09-07 에 겪었다).
LIST_FIELDS = ("language", "vocal_type", "style", "genre", "mood", "instrument",
               "tempo", "energy", "section_label", "exclude_style")

# Spring 이 가진 축은 mood·genre 둘뿐이다. 나머지는 `onboarding_survey` 에 컬럼이
# 없어 빈 값으로 둔다 — 빈 축은 필터를 건너뛴다. 넣으려면 ERD 변경이 선행이다.
EMPTY_AXES = tuple(a for a in LIST_FIELDS if a not in ("mood", "genre"))


def as_list(v) -> list:
    """답변을 항상 list 로 정규화한다. parquet 에서 온 문자열 JSON 도 받는다."""
    if isinstance(v, str):
        t = v.strip()
        if t.startswith("["):
            try:
                return [x for x in json.loads(t) if isinstance(x, str)]
            except Exception:
                return []
        return [t] if t else []
    if isinstance(v, (list, tuple, set, np.ndarray)):
        return [x for x in v if isinstance(x, str)]
    return []


def from_request(payload: dict | None, genre_catalog: list[str] | None = None) -> dict | None:
    """요청에 실려 온 온보딩 → 추천기가 읽는 답변 한 벌.

    `genre_catalog` 를 주지 않으면 장르 변환을 건너뛴다. 그 경우 **그 사용자의 장르는
    추천에 반영되지 않으므로**, 부르는 쪽이 카탈로그를 넘겨야 한다.

    원문은 `mood_raw` · `genre_raw` 로 남긴다 — 변환이 어긋났을 때 되짚을 근거다.
    """
    if not payload:
        return None
    raw_mood = as_list(payload.get("moods"))
    raw_genre = as_list(payload.get("genres"))
    if not raw_mood and not raw_genre:
        return None

    ans: dict = {axis: [] for axis in EMPTY_AXES}
    ans["mood"] = map_mood(raw_mood)
    ans["genre"] = map_genre(raw_genre, genre_catalog) if genre_catalog else []
    ans["mood_raw"] = raw_mood
    ans["genre_raw"] = raw_genre
    return ans
