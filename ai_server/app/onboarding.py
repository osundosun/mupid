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

# taxonomy 무드 원문 → 화면용 한국어. **아래 `MOOD_MAP` 뒷 9줄이 여기서 나온다.**
#
# 이 대응이 코드에 **데이터로** 있어야 하는 이유: `music_preprocessing` 의
# `genre_mood.py` 가 코드표에 심을 때 `code_name`(화면용 한국어)과
# `display_name`(taxonomy 원문)을 갈라야 하는데, 그 패키지는 이 모듈의 의존성
# (numpy·pandas)을 끌고 올 수 없어 표를 한 벌 더 갖는다. 두 벌이 어긋나면
# **화면 언어와 색인 언어가 뒤바뀐 채 조용히 적재된다** — 그래서 양쪽 테스트가
# 이 표를 서로 대조한다 (`test_genre_mood.py` 의
# `test_mood_display_names_match_the_ai_table`).
TAXONOMY_MOOD_NAMES = {
    "melancholic": "애상적인",
    "warm": "따뜻한",
    "dreamy": "몽글한",
    "bright": "화사한",
    "hopeful": "희망찬",
    "powerful": "웅장한",
    "dark": "어두운",
    "calm": "고요한",
    "exciting": "짜릿한",
}

# Spring 온보딩 코드표(17종) → AI 카탈로그 태그. **여기가 유일한 자리다.**
#
# mood 는 뜻으로 이어야 해서 표로 적는다. genre 는 대분류 → 세분류라 이름 포함으로
# 자동 확장한다(`Rock` → `Alternative Rock`·`Pop Rock`·`Indie Rock` …).
# 앞 8종은 온보딩 첫 화면의 대분류, 뒤 9종은 taxonomy 세분류다 (2026-09-17 추가).
MOOD_MAP = {
    "잔잔한": ["relaxing", "meditative", "slow", "background"],
    "벅찬": ["epic", "uplifting", "inspiring", "motivational"],
    "몽환적인": ["space", "deep", "drama"],
    "신나는": ["energetic", "happy", "party", "positive"],
    "집중되는": ["background", "corporate", "meditative"],
    "감성적인": ["emotional", "melodic", "sad", "ballad"],
    "강렬한": ["heavy", "action", "trailer", "dark"],
    "설레는": ["love", "romantic", "summer"],
    # 아래 9종은 taxonomy 무드를 뜻으로 이은 것이다 (2026-09-17). **이름은 위의
    # `TAXONOMY_MOOD_NAMES` 에서 온다** — 여기서 새로 적지 않는다.
    # 색인 축(`mood_tags` 27종)에 그 이름이 하나도 없다 — taxonomy 쪽 어휘가 완전히
    # 달라서 축을 더 넘기는 것으로는 풀리지 않는다. 실측으로 세어 보면 taxonomy
    # 9종은 색인에 1~5곡뿐이고(추천풀 763곡 기준으로는 `dark` 조차 0곡), 위 8종은
    # 뜻으로 이으면 **전부 기존 태그 안으로 떨어진다.** 그래서 이 줄들이 벌어 주는
    # 것은 새 태그가 아니라 **온보딩 선택지의 세분화**다.
    #
    # **새 태그를 하나도 만들지 않는다.** 9종이 추가로 쓰는 태그는 0개라
    # `tools/register_sections.py` 의 역방향 표(`MOOD_BY_TAG`)가 27종 그대로 남고,
    # 거기서 `setdefault` 로 기존 8종이 우선하므로 **이미 라벨이 붙은 곡의 무드는
    # 바뀌지 않는다.** 아래 태그를 고칠 때는 그 성질이 깨지는지 먼저 확인한다.
    #
    # **`dark` 를 `강렬한` 과 같이 쓴다.** 게이트가 OR 이라 중복 반영이 아니라
    # 단순 커버다. `trailer` 를 빼고 `heavy`·`drama` 만 쓰면 5곡이 라벨을 잃는다.
    "애상적인": ["emotional", "ballad", "drama"],          # melancholic
    "따뜻한": ["relaxing", "love", "meditative", "background"],  # warm
    "몽글한": ["space", "deep", "relaxing"],              # dreamy
    "화사한": ["happy", "positive", "uplifting", "summer"],  # bright
    "희망찬": ["inspiring", "motivational", "uplifting"],   # hopeful
    "웅장한": ["epic", "action", "trailer", "heavy"],      # powerful
    "어두운": ["heavy", "drama", "trailer"],               # dark
    "고요한": ["relaxing", "meditative", "background"],     # calm
    "짜릿한": ["energetic", "action", "happy"],             # exciting
}

# 이름 포함으로는 못 잇는 것만 적는다.
GENRE_EXTRA = {
    "Electronic": ["House", "Downtempo", "Synthwave", "Tropical House", "Dubstep",
                   "Drum n Bass", "Electro House", "Chillwave", "Nu-Disco", "Synth-pop"],
    "Hip-hop": ["Cloud Rap", "Trap", "lofi_hiphop", "boom_bap_hiphop", "modern_trap_hiphop", 
                "singing_rap_melodic_hiphop", "chillhop", "jazzhop"],
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
