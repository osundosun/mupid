"""곡별 장르·분위기를 서버에 등록한다.

`music-preprocessing classify` 가 배치 안에서 이것을 부른다. 손으로 돌릴 때는

    python -m music_preprocessing.genre_mood --help

오디오를 분석하지 않는다. 수집 파이프라인이 곡마다 남긴 메타데이터
(`metadata/T*.json` 의 `genre` · `mood`)와 분류 어휘(`genre_mood_taxonomy.json`)를
읽어, `catalog` 가 남긴 `registered.json` 의 `music_id` 에 연결한다.
기존 catalog/batch 및 `registered.json` 은 수정하지 않는다.

## 관리자 API 를 쓰지 않는다

예전에는 `/api/v1/admin/resource/**` 로 코드와 연결 행을 하나씩 만들었다. 그러려면
배치가 관리자 권한을 들고 있어야 한다 — 그 계정이면 회원·결제까지 닿는다.
지금은 배치가 실제로 필요한 것만 열어 둔 두 엔드포인트를 쓴다.

| 하는 일 | 엔드포인트 | 비고 |
| --- | --- | --- |
| 어휘 확인 | `GET /api/v1/onboarding/option` | 코드 번호까지 온다. 쓰기 없음 |
| 곡 확인 | `GET /api/v1/music?page&size` | 곡명·현재 분류를 함께 준다 |
| 코드 추가 | `POST /api/v1/code` | `@SystemRole`. 멱등 |
| 분류 적재 | `PUT /api/v1/music/{id}/classification` | `@SystemRole`. **통째로 교체** |

## 교체라서 달라지는 것

`PUT` 은 그 곡의 장르·분위기를 통째로 갈아 끼운다. 그래서 이 도구는 **비어 있는
곡에만 마음대로 쓴다.** 이미 분류가 있는데 원본과 다르면 건드리지 않고 보고서에
남긴다 — 사람이 손으로 고친 값을 배치가 조용히 지우면 안 된다.
덮어쓰려면 `--replace-existing` 을 명시한다.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlencode, urlsplit

from .api_client import MupidClient
from .mood_approximations import MOOD_APPROXIMATIONS, SOURCE as APPROXIMATION_SOURCE

TAXONOMY_NAME = "genre_mood_taxonomy.json"

# 한 번에 읽는 곡 수. 서버가 100 을 넘기면 100 으로 깎는다(`MusicServiceImpl`).
PAGE_SIZE = 100


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def build_snapshot(metadata_dir, registry, taxonomy):
    """원본 세 곳을 읽어 검증 가능한 한 덩어리로 만든다. 아무것도 쓰지 않는다."""
    metadata = []
    for path in sorted(Path(metadata_dir).expanduser().glob("T*.json")):
        raw = read_json(path)
        metadata.append({"filename": path.name, "track_id": raw.get("track_id"),
                         "title": raw.get("flow_title"), "genre": raw.get("genre"),
                         "mood": raw.get("mood")})
    registered = read_json(Path(registry).expanduser())
    return {"version": 1, "metadata": metadata,
            "taxonomy": read_json(Path(taxonomy).expanduser()),
            "registry": registered.get("catalog", {}).get("tracks", {})}


def local_snapshot(args):
    root = Path(args.source_dir).expanduser()
    snapshot = build_snapshot(root / "metadata", args.registry, root / TAXONOMY_NAME)
    plan = validate(snapshot)
    destination = Path(args.output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # 기존 스냅샷을 실수로 덮어쓰지 않는다.
    with destination.open("x", encoding="utf-8") as stream:
        json.dump(snapshot, stream, ensure_ascii=False, indent=2)
    print(json.dumps({"snapshot": str(destination), **summary(plan)}, ensure_ascii=False))


def positive_id(value):
    if type(value) is not int or value <= 0:
        raise ValueError(f"잘못된 ID: {value!r}")
    return value


def validate(snapshot):
    if snapshot.get("version") != 1:
        raise ValueError("지원하지 않는 스냅샷 버전")
    taxonomy = snapshot["taxonomy"]
    vocab = {}
    for kind in ("genres", "moods"):
        entries = taxonomy[kind]
        names = [entry["name"] for entry in entries]
        if not names or any(not isinstance(n, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", n) for n in names):
            raise ValueError(f"잘못된 {kind} 어휘")
        if len(set(names)) != len(names):
            raise ValueError(f"중복 {kind} 어휘")
        if any(type(e["track_count"]) is not int or e["track_count"] < 0 for e in entries):
            raise ValueError(f"잘못된 {kind} 개수")
        vocab[kind] = {e["name"]: e["track_count"] for e in entries}
    tracks, seen_ids, seen_music = [], set(), set()
    counts = {"genres": Counter(), "moods": Counter()}
    null_moods = []
    for raw in snapshot["metadata"]:
        track_id = raw["track_id"]
        if not isinstance(track_id, str) or not re.fullmatch(r"T\d{6}", track_id):
            raise ValueError(f"잘못된 track_id: {track_id!r}")
        if raw["filename"] != track_id + ".json" or track_id in seen_ids:
            raise ValueError(f"파일명 불일치 또는 중복: {track_id}")
        seen_ids.add(track_id)
        registered = snapshot["registry"].get(track_id)
        if not registered:
            raise ValueError(f"기존 등록 기록 없음: {track_id}")
        music_id = positive_id(registered["music_id"])
        if music_id in seen_music:
            raise ValueError(f"다른 트랙이 같은 music_id에 연결됨: {music_id}")
        seen_music.add(music_id)
        if not raw.get("title") or raw["title"] != registered.get("title"):
            raise ValueError(f"원본/등록 기록 곡명 불일치: {track_id}")
        genre = raw["genre"]
        if genre not in vocab["genres"]:
            raise ValueError(f"알 수 없는 장르: {track_id}: {genre}")
        counts["genres"][genre] += 1
        moods = []
        mood = raw["mood"]
        if mood is None:
            null_moods.append(track_id)
        else:
            if not isinstance(mood, dict) or not mood.get("primary"):
                raise ValueError(f"잘못된 mood 객체: {track_id}")
            for name in [mood["primary"], mood.get("secondary")]:
                if name is None:
                    continue
                if name not in vocab["moods"]:
                    raise ValueError(f"알 수 없는 무드: {track_id}: {name}")
                counts["moods"][name] += 1  # taxonomy는 primary+secondary 등장 횟수
                if name not in moods:
                    moods.append(name)
        tracks.append({"track_id": track_id, "music_id": music_id, "title": raw["title"],
                       "genre": genre, "moods": moods})
    if not tracks:
        raise ValueError("메타데이터가 비어 있음")
    for kind in vocab:
        if dict(counts[kind]) != vocab[kind]:
            raise ValueError(f"원본과 taxonomy {kind}별 개수 불일치")
        singular = kind[:-1]
        if taxonomy.get(singular + "_total_distinct") != len(vocab[kind]):
            raise ValueError(f"taxonomy {kind} 고유값 개수 불일치")
    if sorted(null_moods) != sorted(taxonomy["tracks_without_mood"]):
        raise ValueError("taxonomy의 무드 결측 트랙 목록 불일치")
    return tracks


def summary(tracks):
    return {"tracks": len(tracks), "genre_relations": len(tracks),
            "mood_tracks": sum(bool(t["moods"]) for t in tracks),
            "mood_relations": sum(len(t["moods"]) for t in tracks),
            "distinct_genres": len({t["genre"] for t in tracks}),
            "distinct_moods": len({m for t in tracks for m in t["moods"]})}


def supplement_moods(tracks, snapshot):
    """검증된 원본의 결측만 보완한다. 원본 트랙·taxonomy는 변경하지 않는다."""
    allowed = {entry["name"] for entry in snapshot["taxonomy"]["moods"]}
    result, provenance = [], []
    for track in tracks:
        entry = MOOD_APPROXIMATIONS.get(track["track_id"])
        if entry is None or track["moods"]:
            result.append(dict(track))
            continue
        genre, primary, secondary, evidence = entry
        if track["genre"] != genre:
            raise ValueError(f"근사치 대상의 장르 불일치: {track['track_id']}")
        if primary not in allowed or secondary not in allowed:
            raise ValueError(f"근사치 무드가 원본 어휘에 없음: {track['track_id']}")
        moods = list(dict.fromkeys([primary, secondary]))
        result.append({**track, "moods": moods, "mood_confidence": "approx"})
        provenance.append({"track_id": track["track_id"], "music_id": track["music_id"],
                           "genre": genre, "primary": primary, "secondary": secondary,
                           "confidence": "approx", "source": APPROXIMATION_SOURCE,
                           "evidence": evidence})
    return result, provenance


class BatchClient(MupidClient):
    """전처리 배치용 클라이언트.

    기존 인증 구현(ROLE_SYSTEM 리프레시 토큰)을 그대로 쓰되 쓰기 요청은 자동으로
    재시도하지 않는다. `PUT` 은 멱등이지만 `POST /api/v1/code` 도 멱등이라 재시도
    자체가 해로운 것은 아니다 — 다만 서버가 5xx 를 주는 상황에서 같은 요청을 계속
    밀어 넣기보다 멈추고 보고서를 남기는 편이 낫다.
    """

    def __init__(self, base_url, token_file=None, refresh_token_file=None, timeout=30):
        parsed = urlsplit(base_url)
        if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
            raise ValueError("잘못된 API 주소")
        if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("토큰을 실어 보내므로 HTTPS 또는 로컬 주소만 허용")
        self.bearer = Path(token_file).read_text().strip() if token_file else None
        refresh = Path(refresh_token_file).read_text().strip() if refresh_token_file else ""
        if not self.bearer and not refresh:
            raise ValueError("토큰 파일 필요")
        super().__init__(base_url, refresh, token_file=refresh_token_file,
                         timeout=timeout, max_retries=1)

    def _ensure_access_token(self, force=False):
        return self.bearer or super()._ensure_access_token(force=force)

    def options(self):
        """온보딩 선택지 = 지금 서버에 있는 GENRE·MOOD 코드 전부. 번호까지 온다."""
        return self._request("GET", "/api/v1/onboarding/option")["data"]

    def music_rows(self):
        """곡 전체를 훑는다. 곡명과 **현재 분류**가 같이 오므로 한 번에 끝난다."""
        rows, page, total = [], 0, None
        while True:
            params = urlencode({"page": page, "size": PAGE_SIZE})
            data = self._request("GET", f"/api/v1/music?{params}")["data"]
            if total is not None and total != data["totalElements"]:
                raise ValueError("조회 중 곡 수가 바뀜; 재실행 필요")
            total = data["totalElements"]
            rows.extend(data["items"])
            page += 1
            if page >= data["totalPages"]:
                break
            if not data["items"]:
                raise ValueError("빈 페이지")
        if len(rows) != total:
            raise ValueError("조회 개수 불일치")
        return rows

    # `add_code` 는 `MupidClient` 에 있다. 구간 분석 배치도 악기 코드를 넣느라
    # 같은 것을 부르므로, 두 벌을 두면 한쪽만 고쳐졌을 때 조용히 갈린다.

    def replace_classification(self, music_id, genre_code_ids, mood_code_ids):
        """그 곡의 장르·분위기를 **통째로** 갈아 끼운다. 저장된 결과가 돌아온다."""
        return self._request(
            "PUT", f"/api/v1/music/{positive_id(music_id)}/classification",
            json_body={"genreCodeIds": genre_code_ids, "moodCodeIds": mood_code_ids},
        )["data"]


def fold(codes):
    """서버가 쓰는 철자를 찾기 위한 대소문자 무시 색인.

    `codes` 의 `(code_type, code_name)` 은 MySQL 기본 콜레이션에서 대소문자를
    구분하지 않는다. 부트스트랩이 심어 둔 `Pop` 이 있으면 원본의 `pop` 은 새로
    만들어지지 않고 그 행이 돌아온다. 그걸 모르고 이름을 그대로 비교하면
    반영한 뒤에도 영원히 "다르다" 가 되어 같은 곡을 매 배치마다 다시 쓴다.
    """
    return {(kind, name.casefold()): name for kind, name in codes}


# 이 저장소가 곧 분류 어휘다. taxonomy 무드 9종은 색인(`mood_tags`)에 그 이름이
# 하나도 없어 뜻으로 이어야 했다 — 그 대응이 여기 있다 (`ai_server/app/onboarding.py`
# 의 `MOOD_MAP` 과 **같은 출처**다). 서버에 심을 때 `code_name` 은 화면용 한국어,
# `display_name` 은 taxonomy 원문이 된다.
#
# `genre_mood` 가 직접 계산하지 않고 `ai_server` 를 부르지 않는 이유: 이 패키지는
# 배치 서버에서 단독으로 도는 도구라 `ai_server` 의 의존성(numpy·pandas·torch)을
# 끌고 올 수 없다. 그래서 표를 여기서 한 번 더 적는다 — **한쪽만 고치면 조용히
# 어긋난다.** `test_genre_mood.py` 의 `test_mood_display_names_match_the_ai_table`
# 이 둘을 대조한다.

GENRE_DISPLAY_NAMES = {
    "contemporary_pop": "컨템포러리 팝",
    "dance_pop": "댄스 팝",
    "synthpop": "신스팝",
    "lofi_hiphop": "로파이 힙합",
    "indie_pop": "인디 팝",
    "alternative_rock": "얼터너티브 록",
    "poprock": "팝 록",
    "city_pop": "시티 팝",
    "pop_ballad": "팝 발라드",
    "rnb": "R&B",
    "solo_piano": "피아노 솔로",
    "singing_rap_melodic_hiphop": "싱잉 랩/멜로딕 힙합",
    "modern_trap_hiphop": "모던 트랩 힙합",
    "synthwave": "신스웨이브",
    "game_battle_bgm": "게임 배틀 BGM",
    "indie_rock": "인디 록",
    "hard_rock": "하드 록",
    "punk_power_pop": "펑크/파워 팝",
    "atmospheric_post_rock": "포스트 록",
    "alternative_rnb": "얼터너티브 R&B",
    "neo_soul": "네오 소울",
    "boom_bap_hiphop": "붐뱁 힙합",
    "house": "하우스",
    "future_bass": "퓨처 베이스",
    "drum_and_bass": "드럼 앤 베이스",
    "chillhop": "칠합",
    "downtempo": "다운템포",
    "chillwave_dreamy_electronica": "칠웨이브",
    "modern_jazz": "모던 재즈",
    "funk": "펑크",
    "cinematic_orchestral": "시네마틱 오케스트라",
    "adventure_fantasy": "어드벤처/판타지",
    "emotional_drama": "감성 드라마",
    "mystery_suspense": "미스터리/서스펜스",
    "casual_light_game_bgm": "캐주얼 게임 BGM",
    "neo_classical": "네오 클래식",
    "acoustic_guitar_folk": "어쿠스틱 기타/포크",
    "ambient": "앰비언트",
    "breakbeat_uk_garage": "브레이크비트/UK 개러지",
    "ambient_electronic": "앰비언트 일렉트로닉",
    "acoustic_chill": "어쿠스틱 칠",
    "jazzhop": "재즈합",
    "jazz_fusion": "재즈 퓨전",
    "bossa_smooth_jazz": "보사/스무스 재즈",
    "chamber_classical": "체임버 클래식"
}

MISMATCHED_MOOD_DISPLAY_NAMES = {
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


def display_name_for(kind: str, name: str, folded: dict) -> str:
    """서버에 보낼 표시명. **화면용 한국어가 색인 원문과 다를 때만** 값이 다르다.

    `name` 은 taxonomy 원문(`melancholic`)이고, 화면에 그릴 말은 따로 있다
    (`애상적인`). 대응이 없으면 빈 문자열을 돌려주고, 그러면 서버가 코드 이름을
    그대로 표시명으로 쓴다 — 기존 동작과 같다.

    **서버 철자를 먼저 본다.** 이미 `애상적인` 이라는 코드가 손으로 들어가 있어도
    (`folded` 는 대소문자만 무시하고 한국어↔영문은 구분한다) 여기서는 걸리지
    않으므로, 없는 코드에만 표시명이 붙는다.
    """
    if kind != "MOOD":
        return ""
    korean = MISMATCHED_MOOD_DISPLAY_NAMES.get(name.casefold())
    if korean and (kind, korean.casefold()) not in folded:
        return korean
    return ""


def validate_taxonomy_moods(tracks) -> None:
    """원본이 아는 taxonomy 무드가 **전부** 뜻 표에 있어야 한다.

    원본에 새 무드가 생기면 그 곡들은 코드 이름(영문)이 곧 표시명이 되어,
    화면에 영문이 뜨고 분류도 조용히 어긋난다. 여기서 멈춘다.
    """
    unknown = sorted({name for t in tracks for name in t["moods"]
                      if name.casefold() not in MISMATCHED_MOOD_DISPLAY_NAMES})
    if unknown:
        raise ValueError(f"뜻 표에 없는 taxonomy 무드: {unknown}"
                         " — `MISMATCHED_MOOD_DISPLAY_NAMES` 를 채워야 한다")


def wanted(track, folded):
    """이 곡에 있어야 할 분류를 **서버 철자로** 돌려준다."""
    genres = [folded.get(("GENRE", track["genre"].casefold()), track["genre"])]
    moods = []
    for name in track["moods"]:
        korean = MISMATCHED_MOOD_DISPLAY_NAMES.get(name.casefold(), name)
        moods.append(folded.get(("MOOD", korean.casefold()), korean))
    return sorted(set(genres)), sorted(set(moods))


def inspect(client, tracks):
    """쓰기 전에 서버 상태를 읽어 계획을 세운다. 아무것도 쓰지 않는다."""
    options = client.options()
    codes = {}
    for kind, key in (("GENRE", "genres"), ("MOOD", "moods")):
        for row in options[key]:
            entry = (kind, row["name"])
            if entry in codes:
                raise ValueError(f"서버에 중복 코드 존재: {entry}")
            codes[entry] = positive_id(row["codeId"])
    folded = fold(codes)

    server = {}
    for row in client.music_rows():
        music_id = positive_id(row["musicId"])
        if music_id in server:
            raise ValueError(f"곡 목록에 같은 번호가 두 번: {music_id}")
        server[music_id] = row

    empty, differs, held = [], [], {}
    for track in tracks:
        row = server.get(track["music_id"])
        # 곡명을 맞춰 본다. `registered.json` 이 낡아 다른 곡을 가리키고 있으면
        # `PUT` 이 엉뚱한 곡의 분류를 지운다.
        if not row or row.get("title") != track["title"]:
            raise ValueError(f"서버 곡 누락/곡명 불일치: {track['track_id']}")
        have = (sorted(set(row.get("genres") or [])), sorted(set(row.get("moods") or [])))
        if have == wanted(track, folded):
            continue
        (empty if not (have[0] or have[1]) else differs).append(track["music_id"])
        held[track["music_id"]] = {"genres": have[0], "moods": have[1]}

    needed = {("GENRE", t["genre"]) for t in tracks}
    for t in tracks:
        for name in t["moods"]:
            korean = MISMATCHED_MOOD_DISPLAY_NAMES.get(name.casefold(), name)
            needed.add(("MOOD", korean))
    return {"codes": codes, "folded": folded,
            "missing_codes": sorted(key for key in needed
                                    if (key[0], key[1].casefold()) not in folded),
            "empty": empty, "differs": differs, "held": held}


def apply_plan(client, tracks, state, report, save, replace_existing=False):
    codes, folded = dict(state["codes"]), dict(state["folded"])
    by_music = {track["music_id"]: track for track in tracks}

    # 이미 다른 분류가 있는 곡은 건드리지 않고 넘어간다. 사람이 손으로 고친 값을
    # 배치가 조용히 지우면 안 된다. 한 곡 때문에 나머지를 멈추지도 않는다 —
    # 무엇을 건너뛰었는지는 보고서의 `held` 와 화면 경고에 남는다.
    report["skipped"] = [] if replace_existing else sorted(state["differs"])

    for kind, name in state["missing_codes"]:
        if kind == "MOOD":
            korean = MISMATCHED_MOOD_DISPLAY_NAMES.get(name.casefold(), name)
            row = client.add_code(kind, korean, name)
        else:
            korean = GENRE_DISPLAY_NAMES.get(name.casefold(), name)
            row = client.add_code(kind, name, korean)
        code_id = positive_id(row["codeId"])
        # 서버가 돌려준 철자를 쓴다. 대소문자만 다른 코드가 이미 있었다면 그 이름이다.
        codes[(kind, row["codeName"])] = code_id
        folded[(kind, row["codeName"].casefold())] = row["codeName"]
        report["created"].append({"resource": "code", "id": code_id, "type": kind,
                                  "name": row["codeName"],
                                  "display_name": row.get("displayName"),
                                  "new": bool(row.get("created"))})
        save()

    for music_id in state["empty"] + (state["differs"] if replace_existing else []):
        track = by_music[music_id]
        genre_names, mood_names = wanted(track, folded)
        genre_ids = [codes[("GENRE", name)] for name in genre_names]
        mood_ids = [codes[("MOOD", name)] for name in mood_names]
        stored = client.replace_classification(music_id, genre_ids, mood_ids)
        # 보낸 것이 아니라 저장된 것이 돌아온다. 다르면 여기서 멈춘다.
        if sorted(stored["genreCodeIds"]) != sorted(genre_ids) or sorted(stored["moodCodeIds"]) != sorted(mood_ids):
            raise ValueError(f"저장 결과가 요청과 다름: music_id={music_id}")
        written = {"resource": "classification", "music_id": music_id,
                   "genre_code_ids": genre_ids, "mood_code_ids": mood_ids}
        if track.get("mood_confidence") == "approx":
            written.update(track_id=track["track_id"], confidence="approx",
                           source=APPROXIMATION_SOURCE)
        if music_id in state["differs"]:
            written["replaced"] = state["held"][music_id]
        report["created"].append(written)
        save()
        if len(report["created"]) % 100 == 0:
            print(f"등록 완료 {len(report['created'])}건", file=sys.stderr, flush=True)

    after = inspect(client, tracks)
    if after["missing_codes"] or after["empty"] or (after["differs"] and replace_existing):
        raise ValueError("반영 후에도 원본과 다른 곡이 남아 있음")
    report["status"] = "verified"
    report["rerun_additions"] = 0
    save()


def load_source(args):
    """`--snapshot` 이든 `--source-dir` 이든 같은 모양으로 돌려준다.

    스냅샷 파일은 감사용이다. 배치는 JupyterHub 안에서 원본 디렉터리를 직접 읽는다.
    """
    if args.snapshot:
        raw = Path(args.snapshot).read_bytes()
        return json.loads(raw.decode("utf-8-sig")), hashlib.sha256(raw).hexdigest()
    root = Path(args.source_dir).expanduser() if args.source_dir else None
    metadata = Path(args.metadata_dir).expanduser() if args.metadata_dir else None
    taxonomy = Path(args.taxonomy).expanduser() if args.taxonomy else None
    if metadata is None or taxonomy is None:
        if root is None:
            raise ValueError("--source-dir 없이는 --metadata-dir 와 --taxonomy 를 모두 줘야 한다")
        metadata = metadata or root / "metadata"
        taxonomy = taxonomy or root / TAXONOMY_NAME
    snapshot = build_snapshot(metadata, args.registry, taxonomy)
    canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return snapshot, hashlib.sha256(canonical).hexdigest()


def run(args):
    if not args.snapshot and not (args.source_dir or args.metadata_dir):
        raise ValueError("--snapshot 또는 --source-dir 가 필요하다")
    if not args.snapshot and not args.registry:
        raise ValueError("--source-dir 에는 --registry 가 필요하다")
    snapshot, digest = load_source(args)
    tracks = validate(snapshot)
    source_summary = summary(tracks)
    approximations = []
    if not args.skip_mood_approximations:
        tracks, approximations = supplement_moods(tracks, snapshot)
    report = {"status": "source_validated", "source_sha256": digest,
              "source_summary": source_summary, "summary": summary(tracks),
              "mood_approximations": approximations,
              "created": [], "api_base_url": args.api_base_url}
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    # 인증 파일이나 원본을 보고서로 덮어쓰지 않는다. 실행마다 새 경로를 요구한다.
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)

    def save():
        temp = output.with_suffix(output.suffix + ".tmp")
        temp.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, output)

    try:
        if args.apply and not args.api_base_url:
            raise ValueError("--apply에는 --api-base-url 필요")
        if args.api_base_url:
            client = BatchClient(args.api_base_url, args.token_file, args.refresh_token_file)
            state = inspect(client, tracks)
            report["missing_codes"] = state["missing_codes"]
            report["pending"] = {"empty": len(state["empty"]), "differs": len(state["differs"])}
            report["held"] = {str(k): v for k, v in state["held"].items()
                              if k in set(state["differs"])}
            report["status"] = "server_preflight_passed"
            save()
            if args.apply:
                apply_plan(client, tracks, state, report, save, args.replace_existing)
        # 표로 다시 그리는 쪽(`music-preprocessing classify`)에서는 끈다.
        if not getattr(args, "quiet", False):
            print(json.dumps({k: v for k, v in report.items() if k != "created"},
                             ensure_ascii=False, indent=2))
    except Exception as exc:
        report["status"] = "failed"
        # 서버 오류 응답에 들어갈 수 있는 인증정보/본문은 보고서에 복사하지 않는다.
        report["error_type"] = type(exc).__name__
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("snapshot", help="원본 디렉터리를 한 파일로 떠 둔다 (감사용)")
    p.add_argument("--source-dir", required=True)
    p.add_argument("--registry", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=local_snapshot)
    p = sub.add_parser("import", help="기본: 사전 검증만, --apply: 코드 추가 및 분류 적재")
    source = p.add_argument_group("원본 (둘 중 하나)")
    source.add_argument("--snapshot", help="snapshot 명령이 떠 둔 파일")
    source.add_argument("--source-dir", help="flow_music_data 루트")
    source.add_argument("--metadata-dir", help="비우면 <source-dir>/metadata")
    source.add_argument("--taxonomy", help=f"비우면 <source-dir>/{TAXONOMY_NAME}")
    source.add_argument("--registry", help="catalog 가 남긴 registered.json")
    p.add_argument("--report", required=True)
    p.add_argument("--api-base-url")
    auth = p.add_mutually_exclusive_group()
    auth.add_argument("--token-file", help="ROLE_SYSTEM 액세스 토큰 파일")
    auth.add_argument("--refresh-token-file", help="ROLE_SYSTEM 리프레시 토큰 파일 (회전 시 갱신)")
    p.add_argument("--apply", action="store_true")
    p.add_argument("--replace-existing", action="store_true",
                   help="이미 다른 분류가 있는 곡도 덮어쓴다 (기본은 건너뛰고 보고만)")
    p.add_argument("--skip-mood-approximations", action="store_true",
                   help="초기 10곡의 사용자 제공 근사치를 제외하고 원본 무드만 사용")
    p.set_defaults(func=run)
    args = parser.parse_args()
    try:
        args.func(args)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError) as exc:
        # API 오류는 기존 클라이언트가 서버 메시지를 포함하므로 타입/상태만 출력한다.
        if hasattr(exc, "status"):
            print(f"실패: HTTP {exc.status} ({type(exc).__name__}); 권한/서버 상태 확인", file=sys.stderr)
        else:
            print(f"실패: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
