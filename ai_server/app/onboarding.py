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

# Spring 코드표(`codes`, MOOD) 의 **영문 내부 코드 → 화면용 한국어.** 17종.
#
# 2026-09-21 정리: `code_name` 은 영문 내부 코드, `display_name` 은 한국어다 — 장르
# 45종과 같은 방향이다. 그전에는 무드만 방향이 반대(`code_name` 이 한국어)였고,
# 그 탓에 운영 DB 에 같은 무드가 `melancholic` / `애상적인` 두 행으로 들어가
# 매핑 테이블마다 다른 번호를 썼다 (`api/knowledge/domain/migrate-mood-codes-2026-09-21.sql`).
#
# 앞 8종은 화면 디자인의 대분류(운영 code_id 32~39), 뒤 9종은 taxonomy
# (`genre_mood_taxonomy.json`) 세분류(40~48)다. **차례가 곧 `code_id` 차례다.**
#
# 이 대응이 코드에 **데이터로** 있어야 하는 이유: `music_preprocessing` 의
# `genre_mood.py` 가 코드표에 심을 때 같은 표가 필요한데, 그 패키지는 이 모듈의
# 의존성(numpy·pandas)을 끌고 올 수 없어 표를 한 벌 더 갖는다. 두 벌이 어긋나면
# **화면에 다른 한국어가 뜬 채 조용히 적재된다** — 그래서 양쪽 테스트가 이 표를
# 서로 대조한다 (`test_genre_mood.py` 의 `test_mood_display_names_match_the_ai_table`).
BASE_MOOD_NAMES = {
    "serene": "잔잔한",
    "uplifting": "벅찬",
    "ethereal": "몽환적인",
    "energetic": "신나는",
    "focused": "집중되는",
    "emotional": "감성적인",
    "intense": "강렬한",
    "romantic": "설레는",
}

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

MOOD_NAMES = {**BASE_MOOD_NAMES, **TAXONOMY_MOOD_NAMES}

# Spring 온보딩 코드표(17종, 영문 내부 코드) → AI 카탈로그 태그. **여기가 유일한 자리다.**
#
# mood 는 뜻으로 이어야 해서 표로 적는다. genre 는 대분류 → 세분류라 이름 포함으로
# 자동 확장한다(`Rock` → `Alternative Rock`·`Pop Rock`·`Indie Rock` …).
# 앞 8종은 온보딩 첫 화면의 대분류, 뒤 9종은 taxonomy 세분류다 (2026-09-17 추가).
#
# **키는 Spring `code_name` 이고 값은 색인 태그다 — 둘은 다른 어휘다.** `energetic`·
# `romantic`·`uplifting`·`emotional` 은 우연히 색인 태그와 철자가 같지만, 키는
# 코드표 이름이고 값이 태그라는 역할은 그대로다.
MOOD_MAP = {
    "serene": ["relaxing", "meditative", "slow", "background"],          # 잔잔한
    "uplifting": ["epic", "uplifting", "inspiring", "motivational"],     # 벅찬
    "ethereal": ["space", "deep", "drama"],                              # 몽환적인
    "energetic": ["energetic", "happy", "party", "positive"],            # 신나는
    "focused": ["background", "corporate", "meditative"],                # 집중되는
    "emotional": ["emotional", "melodic", "sad", "ballad"],              # 감성적인
    "intense": ["heavy", "action", "trailer", "dark"],                   # 강렬한
    "romantic": ["love", "romantic", "summer"],                          # 설레는
    # 아래 9종은 taxonomy 무드를 뜻으로 이은 것이다 (2026-09-17). **이름은 위의
    # `TAXONOMY_MOOD_NAMES` 와 같은 차례다** — 여기서 새로 적지 않는다.
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
    # **`dark` 를 `intense`(강렬한) 와 같이 쓴다.** 게이트가 OR 이라 중복 반영이
    # 아니라 단순 커버다. `trailer` 를 빼고 `heavy`·`drama` 만 쓰면 5곡이 라벨을 잃는다.
    "melancholic": ["emotional", "ballad", "drama"],                     # 애상적인
    "warm": ["relaxing", "love", "meditative", "background"],            # 따뜻한
    "dreamy": ["space", "deep", "relaxing"],                             # 몽글한
    "bright": ["happy", "positive", "uplifting", "summer"],              # 화사한
    "hopeful": ["inspiring", "motivational", "uplifting"],               # 희망찬
    "powerful": ["epic", "action", "trailer", "heavy"],                  # 웅장한
    "dark": ["heavy", "drama", "trailer"],                               # 어두운
    "calm": ["relaxing", "meditative", "background"],                    # 고요한
    "exciting": ["energetic", "action", "happy"],                        # 짜릿한
}

# 한국어 표시명 → 영문 코드. 코드표 정리 전에 저장된 요청·기록에는 한국어가 남아
# 있을 수 있어(`mood_raw` 등) 둘 다 받는다. 정리 전 운영 표시명이던
# `몽글몽글한`·`차분한` 도 같은 무드로 읽는다.
MOOD_ALIASES = {ko: en for en, ko in MOOD_NAMES.items()}
MOOD_ALIASES.update({"몽글몽글한": "dreamy", "차분한": "calm"})


def mood_code(name) -> str | None:
    """받은 이름을 Spring 영문 코드로. 한국어·대소문자 차이를 받아 준다."""
    if not isinstance(name, str):
        return None
    key = name.strip()
    if key in MOOD_MAP:
        return key
    if key.casefold() in MOOD_MAP:
        return key.casefold()
    return MOOD_ALIASES.get(key)


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
        code = mood_code(n)
        for v in (MOOD_MAP.get(code, []) if code else []):
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
