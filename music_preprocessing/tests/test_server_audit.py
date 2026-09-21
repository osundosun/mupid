"""서버에서 사라진 곡을 배치가 어떻게 다루는가 (ticket S15P21A204-399).

실제 실패는 `T000957` 한 곡이 서버에서 소프트 삭제된 상태에서 났다.

- `catalog` — 지문이 같으면 서버를 아예 안 부르므로 **아무것도 눈치채지 못했다.**
- `lyrics` — `PUT /lyric` 이 404 → 배치 종료 코드 1.
- `classify` — 첫 곡(`T000957`)에서 "서버 곡 누락" 으로 즉시 중단. 나머지 1059곡이
  검증되지 않았다.
- `batch` — 경고만 남기고 통과.

여기서 보는 것은 다섯 가지다.

1. 대조가 **첫 곡에서 멈추지 않고** 없는 곡을 전부 모으는가
2. 없는 곡을 예외로 남기고, **다시 나타나면 표식을 지우는가**
3. `lyrics` 가 404 를 맞지 않고 건너뛰는가 (배치를 실패시키지 않는가)
4. `classify` 가 그 곡만 건너뛰고 **나머지를 계속 쓰는가**. 곡명이 어긋난 곡은
   예외 여부와 무관하게 쓰기 전에 멈추는가
5. `catalog` 이 예외 곡에 `PATCH` 를 보내지 않는가

**원본 오디오·메타데이터 파일은 어느 경로에서도 손대지 않는다.** 서버 곡을
되살릴지 주피터의 원본을 지울지는 사람이 정한다 — 여기서도 그것을 단언한다.
"""
import json
import tempfile
import unittest
from pathlib import Path

import music_preprocessing.catalog as catalog_mod
import music_preprocessing.lyrics as lyrics_mod
import music_preprocessing.orphans as orphans
from music_preprocessing.audit import (
    EXEMPT_KEY,
    apply_exemptions,
    audit_registry,
    exempt_track_ids,
    index_server_music,
    orphan_streams_of_missing,
    repair_one,
)
from music_preprocessing.api_client import MupidApiError
from music_preprocessing.catalog import CatalogError, CatalogRegistrar
from music_preprocessing.genre_mood import apply_plan, inspect
from music_preprocessing.registrar import Registry


# ─────────────────────────────────────────────────────────────
# 서버 대역
# ─────────────────────────────────────────────────────────────


class FakeServer:
    """`GET /api/v1/music` 이 **삭제된 곡을 걸러서** 주는 것을 흉내 낸다.

    내부 행은 자바 엔티티처럼 camelCase 로 두고 밖으로 나갈 때만 snake_case 로
    바꾼다 — 실제 스프링 서버가 그렇다(`test_genre_mood.py` 의 `Server` 와 같은
    규칙). 곡이 `deleted` 면 목록에서 아예 빠진다. 이게 이 티켓의 출발점이다.
    """

    def __init__(self, music, deleted=()):
        # music: {music_id: {"title": ..., "genres": [...], "moods": [...]}}
        self.music = {int(k): dict(v) for k, v in music.items()}
        self.deleted = set(deleted)
        self.patched = []
        self.code_calls = []
        self.lyric_calls = []
        self.stream_queries = []
        #: `{music_id: [{stream_id, codec, bitrate_kbps, chunk_count}, ...]}`
        self.streams = {}
        #: `{stream_id: [{...}, ...]}` — `get_chunks` 가 돌려줄 청크 행
        self.chunks = {}
        #: 청크 조회를 404 로 만들 스트림. 없는 스트림을 조회한 셈 치는 데 쓴다.
        self.short_streams = set()
        #: 단건 곡 조회(`GET /api/v1/music/{id}`)를 받은 곡 번호들.
        #: 목록 훑기와 구분하려고 따로 둔다 — 곡마다 부르면 요청이 1060회다.
        self.music_queries = set()
        #: `POST /api/v1/music` 으로 **새 곡 행을 만든** 호출들. 정상 흐름에서는
        #: 비어 있어야 한다 (jira 완료조건: 기존 정상 곡이 중복 등록되지 않는다).
        self.music_created = []

    # ── 읽기 ──
    def music_rows(self, page_size=100):
        rows = [
            {"music_id": mid, "title": row.get("title", ""),
             "genres": list(row.get("genres") or []),
             "moods": list(row.get("moods") or [])}
            for mid, row in sorted(self.music.items())
            if mid not in self.deleted
        ]
        return rows

    def options(self):
        return {"genres": [{"code_id": 7, "name": "synthpop"}],
                "moods": [{"code_id": 30, "name": "화사한"}]}

    def add_code(self, code_type, name, display_name=None):
        self.code_calls.append((code_type, name))
        return {"code_id": 90, "code_name": name, "display_name": display_name}

    def replace_classification(self, music_id, genre_code_ids, mood_code_ids):
        row = self.music[music_id]
        names = {7: "synthpop", 30: "화사한", 90: "melancholic"}
        row["genres"] = [names[i] for i in genre_code_ids]
        row["moods"] = [names[i] for i in mood_code_ids]
        return {"music_id": music_id,
                "genre_code_ids": sorted(set(genre_code_ids)),
                "mood_code_ids": sorted(set(mood_code_ids))}

    # ── 쓰기 ──
    def update_music(self, music_id, **fields):
        """**삭제된 곡이면 404 다.** 서버의 `getMusic` 이 그렇게 답한다."""
        if music_id in self.deleted:
            raise RuntimeError(f"404 MUPID_MUSIC_NOT_FOUND (music_id={music_id})")
        self.patched.append((music_id, fields))

    def update_artist(self, artist_id, **fields):
        raise AssertionError("아티스트는 이미 등록돼 있다 — 건드릴 이유가 없다")

    def update_album(self, album_id, **fields):
        raise AssertionError("앨범은 이미 등록돼 있다 — 건드릴 이유가 없다")

    def add_music(self, **kwargs):
        """`POST /api/v1/music` — **새 곡 행을 만든다**(서버에 중복 검사가 없다).

        그래서 이 시험들에서는 부르면 안 된다: 이미 `registered.json` 에 있는 곡을
        다시 만들면 옛 행과 DONE 스트림이 고아로 남는다. 부른 횟수를 세어
        `music_created` 로 확인할 수 있게 한다 — 예외만 던지면 "안 불렸다" 는
        알 수 있어도 어느 시험이 몇 번 불렀는지는 볼 수 없다.
        """
        self.music_created.append(kwargs)
        raise AssertionError("서버에 없는 곡을 다시 만들면 안 된다")

    def replace_lyrics(self, music_id, lines):
        """가사도 삭제된 곡이면 404 다 — 실제 실패 경로였다."""
        if music_id in self.deleted:
            raise RuntimeError(f"404 MUPID_MUSIC_NOT_FOUND (music_id={music_id})")
        self.lyric_calls.append(music_id)
        return {"deleted": 0}

    def get_music(self, music_id):
        """`GET /api/v1/music/{id}` — 실제 서버처럼 상태코드로 갈라 준다.

        소프트 삭제된 곡은 **410 Gone**(`MupidClient.get_music` 은 `None` 을 준다),
        그런 번호가 아예 없으면 **404** 다(2026-09-18). 이 갈래가 목록 조회에는
        없어서, 곡별 단건 조회를 켠 뒤로는 여기가 그 둘을 가르는 유일한 자리다.
        """
        self.music_queries.add(music_id)
        if music_id in self.deleted:
            # **예외를 던지지 않고 `None` 을 준다.** 실제 `MupidClient.get_music`
            # 이 410 을 자기 안에서 흡수하기 때문이다. 여기서 예외를 던지면
            # 부르는 쪽의 `None` 처리를 시험이 못 본다 — 실제로 그 때문에
            # 삭제된 곡이 판정 없이 지나가는 버그를 이 더블이 가렸다.
            return None
        if music_id not in self.music:
            raise MupidApiError(
                404, "MUPID_MUSIC_NOT_FOUND", "요청한 음악을 찾을 수 없습니다."
            )
        row = self.music[music_id]
        return {"music_id": music_id, "title": row.get("title", "")}

    def get_streams(self, music_id):
        """`GET /api/v1/music/{id}/stream`. **삭제된 곡이면 410 Gone 이다.**

        그래서 삭제된 곡에 딸린 스트림은 조회로 볼 수 없다 — 이 시험이 그것을
        확인한다. 남은 스트림은 `registered.json` 의 청킹 기록으로만 알 수 있다.

        실제 클라이언트는 `MupidApiError` 를 올린다. 예전에는 맨 `RuntimeError`
        였는데, 그러면 `except MupidApiError` 로 상태코드를 보는 코드가 이 더블
        위에서 <b>조용히 동작하지 않는다</b> — 서버가 404 를 주던 시절의 흔적이다.
        """
        if music_id in self.deleted:
            raise MupidApiError(
                410, "MUPID_MUSIC_GONE", "요청한 음악은 삭제되었습니다."
            )
        self.stream_queries.append(music_id)
        return list(self.streams.get(music_id, []))

    def get_chunks(self, stream_id):
        """`GET /api/v1/chunk/{streamId}`. 청크 행 수를 세는 데 쓴다."""
        if stream_id in self.short_streams:
            raise RuntimeError(f"404 STREAM_NOT_FOUND (stream_id={stream_id})")
        return list(self.chunks.get(stream_id, []))



class GoneServer(FakeServer):
    """삭제된 곡에 **410 Gone** 을 주는 서버 (2026-09-18 이후 실제 동작)."""

    def get_streams(self, music_id):
        if music_id in self.deleted:
            raise MupidApiError(
                410, "MUPID_MUSIC_GONE", "요청한 음악은 삭제되었습니다."
            )
        self.stream_queries.append(music_id)
        return list(self.streams.get(music_id, []))


class StaleServer(FakeServer):
    """모르는 곡 번호에 **404** 를 주는 서버 (등록 기록이 낡은 경우)."""

    def get_streams(self, music_id):
        if music_id not in self.music:
            raise MupidApiError(
                404, "MUPID_MUSIC_NOT_FOUND", "요청한 음악을 찾을 수 없습니다."
            )
        self.stream_queries.append(music_id)
        return list(self.streams.get(music_id, []))



# ─────────────────────────────────────────────────────────────
# 공용 재료
# ─────────────────────────────────────────────────────────────


def registry_with(
    tmp: Path,
    tracks: dict,
    chunk_log: dict | None = None,
    artists: dict | None = None,
    albums: dict | None = None,
) -> Registry:
    """`registered.json` 하나를 만들어 연다.

    아티스트·앨범 항목을 넣지 않으면 `catalog` 이 "아티스트가 먼저 등록되지
    않았습니다" 로 막혀 곡 단계까지 가지 못한다. 곡 단계를 보는 시험은 넘겨야 한다.
    """
    path = tmp / "registered.json"
    path.write_text(
        json.dumps({"artists": {}, "albums": {}, "tracks": chunk_log or {},
                    "catalog": {"artists": artists or {}, "albums": albums or {},
                                "lyrics": {}, "tracks": tracks}}),
        encoding="utf-8",
    )
    return Registry(path)


def seeded_registry(tmp: Path, tracks: dict) -> Registry:
    """아티스트·앨범까지 **지문까지 맞춰** 등록된 상태로 시작한다.

    지문을 안 넣으면 `_sync` 가 "지문 없음(최초 1회)" 로 `PATCH` 를 보내려 한다.
    그러면 이 시험이 보려는 것(곡 단계)이 아티스트 오류에 가려진다. 실제 지문을
    계산해 넣어 아티스트·앨범은 조용히 지나가게 한다.
    """
    return registry_with(
        tmp, tracks,
        artists={"a": {"artist_id": 10, "name": "아티스트",
                       "fields_sha256": catalog_mod._fingerprint("아티스트")}},
        albums={"al": {"album_id": 20, "artist_id": 10, "title": "앨범",
                       "release_date": album_release_date(tmp),
                       "fields_sha256": catalog_mod._fingerprint(
                           "앨범", album_release_date(tmp), 10)}},
    )


def album_release_date(tmp: Path) -> str:
    """`write_track` 이 쓰는 `generated_at` 의 한국 시간 변환값(초 단위)."""
    return "2026-09-08T14:03:31"


def track_entry(music_id: int, title: str) -> dict:
    return {"music_id": music_id, "title": title, "fields_sha256": f"fp-{music_id}"}


def classify_tracks(entries: list[tuple]) -> list[dict]:
    """`validate` 가 돌려주는 모양의 곡 목록. (track_id, music_id, title, genre)"""
    return [
        {"track_id": track_id, "music_id": music_id, "title": title,
         "genre": genre, "moods": ["화사한"]}
        for track_id, music_id, title, genre in entries
    ]


# ─────────────────────────────────────────────────────────────
# 1 · 2 — 대조와 예외 표식
# ─────────────────────────────────────────────────────────────


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def registry(self, tracks, chunk_log=None):
        return registry_with(self.root, tracks, chunk_log)

    def test_collects_every_missing_song_not_just_the_first(self):
        """`classify` 가 첫 곡에서 죽어 나머지를 못 봤던 것을 여기서는 전부 모은다."""
        registry = self.registry({
            "T000001": track_entry(1, "Alive"),
            "T000002": track_entry(2, "Gone"),
            "T000003": track_entry(3, "Also Gone"),
            "T000004": track_entry(4, "Third Gone"),
        })
        server = {1: {"music_id": 1, "title": "Alive"}}

        result = audit_registry(registry, server)

        self.assertEqual([m["track_id"] for m in result.missing],
                         ["T000002", "T000003", "T000004"])
        self.assertEqual(len(result.as_report()["missing"]), 3)
        self.assertTrue(result.ok)

    def test_no_ratio_limit_so_many_deletions_are_still_just_missing(self):
        """곡은 몇 개든 삭제될 수 있다 — 개수·비율로 막지 않는다."""
        tracks = {f"T{i:06d}": track_entry(i, f"Song {i}") for i in range(1, 101)}
        registry = self.registry(tracks)
        server = {i: {"music_id": i, "title": f"Song {i}"} for i in range(1, 3)}

        result = audit_registry(registry, server)

        self.assertEqual(len(result.missing), 98)
        self.assertTrue(result.ok, "삭제가 많다고 실패로 만들지 않는다")

    def test_title_mismatch_is_its_own_finding_and_stops_writing(self):
        """매핑이 다른 곡을 가리키면 건너뛰는 것으로는 못 막는다 — 멈춘다."""
        registry = self.registry({"T000001": track_entry(1, "Expected")})
        server = {1: {"music_id": 1, "title": "Something Else"}}

        result = audit_registry(registry, server)

        self.assertEqual(result.missing, [])
        self.assertEqual(result.title_mismatch[0]["track_id"], "T000001")
        self.assertEqual(result.title_mismatch[0]["server_title"], "Something Else")
        self.assertFalse(result.ok)

    def test_same_title_at_a_different_music_id_is_a_mismatch_not_a_deletion(self):
        """곡명이 같은 다른 번호가 있으면 삭제가 아니라 매핑이 낡은 것이다."""
        registry = self.registry({"T000001": track_entry(1, "Moved")})
        server = {77: {"music_id": 77, "title": "Moved"}}

        result = audit_registry(registry, server)

        self.assertEqual(result.missing, [])
        self.assertEqual(result.title_mismatch[0]["server_music_ids"], [77])

    def test_exemption_is_kept_and_not_restamped(self):
        """표식이 이미 있으면 다시 쓰지 않는다 — 매일 파일이 바뀌지 않게."""
        registry = self.registry({"T000001": track_entry(1, "Gone")})
        server = {}

        first = audit_registry(registry, server)
        self.assertTrue(apply_exemptions(first, registry)[0])
        registry.save()
        stamped = registry.catalog_entry("tracks", "T000001")[EXEMPT_KEY]
        self.assertEqual(stamped["music_id"], 1)

        second = audit_registry(registry, server)
        self.assertEqual(second.exempt, [], "이미 표식이 있으면 새로 붙일 것이 없다")
        self.assertFalse(apply_exemptions(second, registry)[0])
        self.assertEqual(
            registry.catalog_entry("tracks", "T000001")[EXEMPT_KEY], stamped
        )

    def test_reappearing_song_clears_the_exemption(self):
        """서버에 다시 나타나면 표식을 지운다 — 되살린 곡을 영영 방치하지 않는다."""
        registry = self.registry({"T000001": track_entry(1, "Back")})
        apply_exemptions(audit_registry(registry, {}), registry)
        registry.save()
        self.assertEqual(exempt_track_ids(registry), {"T000001"})

        server = {1: {"music_id": 1, "title": "Back"}}
        result = audit_registry(registry, server)
        cleared = apply_exemptions(result, registry)

        self.assertEqual(result.recovered, ["T000001"])
        self.assertTrue(cleared)
        self.assertEqual(exempt_track_ids(registry), set())
        self.assertNotIn(EXEMPT_KEY, registry.catalog_entry("tracks", "T000001"))

    def test_dry_run_lookalike_does_not_write(self):
        """`exempt_now=False` 는 같은 수치를 보여 주되 파일을 고치지 않는다."""
        registry = self.registry({"T000001": track_entry(1, "Gone")})

        result = audit_registry(registry, {}, exempt_now=False)

        self.assertEqual(len(result.missing), 1)
        self.assertEqual(result.exempt, [], "표식 대상으로 잡지 않는다")
        self.assertFalse(apply_exemptions(result, registry)[0])

    def test_server_index_rejects_bad_rows(self):
        class Duplicated:
            def music_rows(self, page_size=100):
                return [{"music_id": 1, "title": "a"}, {"music_id": 1, "title": "b"}]

        with self.assertRaises(Exception):
            index_server_music(Duplicated())

    def test_orphan_streams_are_reported_from_the_registry(self):
        """삭제된 곡의 스트림은 조회로 안 보인다. 기록에 남은 번호로 보고한다."""
        chunk = {str(self.root / "T000002.mp3"):
                 {"music_id": 2, "stream_id": 77, "chunk_count": 3}}
        registry = self.registry({
            "T000001": track_entry(1, "Alive"),
            "T000002": track_entry(2, "Gone"),
        }, chunk_log=chunk)

        result = audit_registry(registry, {1: {"music_id": 1, "title": "Alive"}})
        orphans = orphan_streams_of_missing(registry, result)

        self.assertEqual(len(orphans), 1)
        self.assertEqual(orphans[0]["music_id"], 2)
        self.assertEqual(orphans[0]["track_id"], "T000002")
        self.assertEqual(orphans[0]["stream_ids"], [77])

    def test_orphan_scan_is_empty_when_nothing_is_missing(self):
        registry = self.registry({"T000001": track_entry(1, "Alive")})
        result = audit_registry(registry, {1: {"music_id": 1, "title": "Alive"}})
        self.assertEqual(orphan_streams_of_missing(registry, result), [])


# ─────────────────────────────────────────────────────────────
# 3 — lyrics
# ─────────────────────────────────────────────────────────────


class LyricsSkipTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def sheet(self, track_id):
        return lyrics_mod.LyricSheet(
            track_id=track_id, lines=[(0, "line")],
            timed=1, interpolated=0, dropped=0, sha256="s" + track_id,
        )

    def registrar(self, registry):
        return lyrics_mod.LyricRegistrar(
            client=FakeServer({2: {"title": "Gone"}}, deleted={2}),
            registry=registry, dry_run=False,
        )

    def test_exempt_song_is_skipped_before_the_404(self):
        """`T000957` 이 정확히 여기서 배치를 실패시켰다. 이제 부르지도 않는다."""
        registry = registry_with(self.root, {"T000002": track_entry(2, "Gone")})
        registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {"music_id": 2}
        registrar = self.registrar(registry)

        result = registrar.run({"T000002": self.sheet("T000002")})

        self.assertEqual(result["errors"], [], "404 를 맞지 않는다")
        self.assertEqual(result["songs_exempt"], 1)
        self.assertEqual(result["songs_registered"], 0)
        self.assertEqual(registrar.client.lyric_calls, [])

    def test_normal_song_is_still_registered(self):
        registry = registry_with(self.root, {"T000001": track_entry(1, "Alive")})
        registrar = self.registrar(registry)

        result = registrar.run({"T000001": self.sheet("T000001")})

        self.assertEqual(result["songs_registered"], 1)
        self.assertEqual(result["songs_exempt"], 0)
        self.assertEqual(registrar.client.lyric_calls, [1])

    def test_without_the_mark_the_404_still_fails_loudly(self):
        """표식이 없으면 예전처럼 실패한다 — 조용히 넘어가지 않는다."""
        registry = registry_with(self.root, {"T000002": track_entry(2, "Gone")})
        registrar = self.registrar(registry)

        result = registrar.run({"T000002": self.sheet("T000002")})

        self.assertEqual(len(result["errors"]), 1)
        self.assertIn("404", result["errors"][0]["error"])


# ─────────────────────────────────────────────────────────────
# 4 — classify
# ─────────────────────────────────────────────────────────────


class ClassifySkipTests(unittest.TestCase):
    def setUp(self):
        self.tracks = classify_tracks([
            ("T000001", 1, "Alive", "synthpop"),
            ("T000002", 2, "Gone", "synthpop"),
            ("T000003", 3, "Also Gone", "synthpop"),
        ])

    def test_exempt_songs_are_collected_and_do_not_stop_the_run(self):
        """첫 곡(`T000002`)에서 죽던 것을 전부 모으고 나머지는 계속 쓴다."""
        server = FakeServer({1: {"title": "Alive"}, 3: {"title": "Also Gone"}},
                            deleted={2, 3})

        state = inspect(server, self.tracks, {"T000002", "T000003"})

        self.assertEqual([t["track_id"] for t in state["server_missing"]],
                         ["T000002", "T000003"])
        self.assertEqual(state["empty"], [1], "살아 있는 곡은 등록 대상으로 남는다")

    def test_without_the_exemption_it_still_stops(self):
        server = FakeServer({1: {"title": "Alive"}}, deleted={2})

        with self.assertRaisesRegex(ValueError, "서버 곡 누락/곡명 불일치: T000002"):
            inspect(server, self.tracks)

    def test_title_mismatch_stops_even_when_marked_exempt(self):
        """삭제가 아니라 매핑이 어긋난 것이다. 그대로 두면 남의 곡을 고친다."""
        server = FakeServer({2: {"title": "Different Song"}})
        registry = registry_with(Path(tempfile.mkdtemp()),
                                 {"T000002": track_entry(2, "Gone")})
        registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {"music_id": 2}

        # 다른 곡들은 서버에 있으니 걸리지 않는다. 곡명이 어긋난 T000002 만 문제다.
        server.music[1] = {"title": "Alive"}
        server.music[3] = {"title": "Also Gone"}
        with self.assertRaisesRegex(ValueError, "곡명 불일치: T000002"):
            inspect(server, self.tracks, exempt_track_ids(registry))

    def test_apply_plan_writes_the_living_songs_and_verifies(self):
        """건너뛴 곡이 있어도 나머지는 반영되고 마지막 검증이 통과한다."""
        server = FakeServer({1: {"title": "Alive"}, 3: {"title": "Also Gone"}},
                            deleted={2})
        tracks = self.tracks
        state = inspect(server, tracks, {"T000002"})
        report = {"created": []}

        apply_plan(server, tracks, state, report, lambda: None)

        self.assertEqual(report["status"], "verified")
        written = [r for r in report["created"] if r["resource"] == "classification"]
        self.assertEqual(sorted(r["music_id"] for r in written), [1, 3])
        self.assertNotIn(2, [r["music_id"] for r in written])

    def test_verification_excludes_the_skipped_song(self):
        """검증에서 다시 훑을 때 건너뛴 곡 때문에 터지면 안 된다."""
        server = FakeServer({1: {"title": "Alive"}}, deleted={2})
        tracks = [t for t in self.tracks if t["track_id"] in ("T000001", "T000002")]
        state = inspect(server, tracks, {"T000002"})

        report = {"created": []}
        apply_plan(server, tracks, state, report, lambda: None)

        self.assertEqual(report["status"], "verified")


# ─────────────────────────────────────────────────────────────
# 5 — catalog
# ─────────────────────────────────────────────────────────────


class CatalogSkipTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "metadata").mkdir()
        (self.root / "covers").mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def write_track(self, track_id, title, music_id, artist_key, album_key):
        meta = {
            "track_id": track_id,
            "flow_title": title,
            "generated_at": "2026-09-08T05:03:31.236059+00:00",
            "artist": {"artist_key": artist_key, "name": "아티스트"},
            "album": {"album_key": album_key, "title": "앨범"},
        }
        (self.root / "metadata" / f"{track_id}.json").write_text(
            json.dumps(meta), encoding="utf-8"
        )

    def test_exempt_track_is_not_patched_and_files_are_untouched(self):
        """서버에 없는 곡은 `PATCH` 를 보내지 않는다. 원본 파일도 그대로다."""
        # 아티스트·앨범은 이미 등록된 상태 → 곡 단계까지 간다.
        # 살아 있는 곡(1)은 지문이 없어 한 번 `PATCH` 가 나가고,
        # 없는 곡(2)은 지문이 없어도 나가지 않아야 한다.
        self.write_track("T000001", "Alive", 1, "a", "al")
        self.write_track("T000002", "Gone", 2, "a", "al")

        tracks_meta = {
            "T000001": {"music_id": 1, "title": "Alive",
                        "album_key": "al", "artist_key": "a"},
            "T000002": {"music_id": 2, "title": "Gone",
                        "album_key": "al", "artist_key": "a"},
        }
        registry = seeded_registry(self.root, tracks_meta)
        registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {"music_id": 2}

        server = FakeServer({1: {"title": "Alive"}}, deleted={2})
        registrar = CatalogRegistrar(
            client=server, registry=registry,
            covers_dir=self.root / "covers", dry_run=False,
        )
        meta_path = self.root / "metadata" / "T000002.json"
        before = meta_path.read_bytes()

        tracks = catalog_mod.load_metadata(self.root / "metadata")
        summary = registrar.run(tracks, registrar.audit())

        self.assertEqual([p[0] for p in server.patched], [1],
                         "삭제된 곡(2)에는 PATCH 가 나가지 않는다")
        self.assertEqual(summary["tracks_exempt"], 1)
        self.assertEqual(summary["tracks_updated"], 1)
        self.assertEqual(meta_path.read_bytes(), before, "원본 메타데이터는 그대로")
        self.assertTrue(meta_path.exists(), "원본 파일을 지우지 않는다")

    def test_registered_tracks_are_never_recreated(self):
        """**완료조건**: 이미 등록된 곡은 `POST` 로 다시 만들지 않는다 (S15P21A204-399).

        `POST /api/v1/music` 은 중복 검사가 없어 새 행을 만든다. 옛 행에 DONE
        스트림이 남아 있으면 그대로 고아가 된다. 그래서 등록된 곡은 `PATCH` 로만
        가야 하고, 삭제된 곡은 아예 건너뛴다.

        이 시험은 **재실행**까지 본다 — 첫 실행에서 기록이 채워진 뒤 다시 돌려도
        `POST` 가 0건이어야 한다(배치를 반복해서 돌리는 것이 정상 운영이다).
        """
        self.write_track("T000001", "Alive", 1, "a", "al")
        self.write_track("T000002", "Gone", 2, "a", "al")

        registry = seeded_registry(self.root, {
            "T000001": {"music_id": 1, "title": "Alive",
                        "album_key": "al", "artist_key": "a"},
            "T000002": {"music_id": 2, "title": "Gone",
                        "album_key": "al", "artist_key": "a"},
        })
        server = FakeServer({1: {"title": "Alive"}}, deleted={2})
        registrar = CatalogRegistrar(
            client=server, registry=registry,
            covers_dir=self.root / "covers", dry_run=False,
        )

        tracks = catalog_mod.load_metadata(self.root / "metadata")
        first = registrar.run(tracks, registrar.audit())
        second = registrar.run(tracks, registrar.audit())

        # 새로 만든 곡이 하나도 없어야 한다 (두 번 돌려도).
        # `add_music` 은 부르면 AssertionError 를 던지므로 `_guard` 가 삼켜
        # `errors` 에 남는다 — 그 흔적이 없어야 한다.
        for label, summary in (("1회차", first), ("2회차", second)):
            self.assertEqual(
                [], summary["errors"],
                f"{label}에 등록 실패가 있다 — 이미 등록된 곡을 POST 했을 수 있다",
            )
        self.assertNotIn("POST /api/v1/music", [])
        # 살아 있는 곡은 PATCH 로만 갔고, 삭제된 곡에는 아무것도 안 갔다.
        self.assertEqual([p[0] for p in server.patched], [1],
                         "살아 있는 곡만 PATCH, 삭제된 곡은 건너뛴다")

    def test_catalog_stops_before_writing_on_a_title_mismatch(self):
        """매핑이 어긋나면 한 건도 쓰지 않는다 — PATCH 가 남의 곡을 고친다."""
        self.write_track("T000001", "Expected", 1, "a", "al")
        registry = registry_with(self.root, {"T000001": track_entry(1, "Expected")})
        server = FakeServer({1: {"title": "Something Else"}})
        registrar = CatalogRegistrar(
            client=server, registry=registry,
            covers_dir=self.root / "covers", dry_run=False,
        )

        tracks = catalog_mod.load_metadata(self.root / "metadata")
        with self.assertRaises(CatalogError):
            registrar.audit()
        self.assertEqual(server.patched, [], "한 건도 고치지 않았다")

    def test_exempt_track_is_marked_in_the_registry_after_audit(self):
        """`catalog` 이 돌면 표식이 실제로 남는다 (사람이 손으로 넣지 않는다)."""
        self.write_track("T000002", "Gone", 2, "a", "al")
        registry = seeded_registry(self.root, {
            "T000002": {"music_id": 2, "title": "Gone",
                        "album_key": "al", "artist_key": "a"},
        })
        server = FakeServer({}, deleted={2})
        registrar = CatalogRegistrar(
            client=server, registry=registry,
            covers_dir=self.root / "covers", dry_run=False,
        )

        tracks = catalog_mod.load_metadata(self.root / "metadata")
        registrar.run(tracks, registrar.audit())

        self.assertEqual(exempt_track_ids(registry), {"T000002"})

    def test_dry_run_does_not_stamp(self):
        """`--dry-run` 은 서버에 붙지도, 표식을 남기지도 않는다."""
        self.write_track("T000002", "Gone", 2, "a", "al")
        registry = registry_with(self.root, {"T000002": track_entry(2, "Gone")})
        registrar = CatalogRegistrar(
            client=None, registry=registry,
            covers_dir=self.root / "covers", dry_run=True,
        )

        tracks = catalog_mod.load_metadata(self.root / "metadata")
        self.assertIsNone(registrar.audit())
        registrar.run(tracks)
        self.assertEqual(exempt_track_ids(registry), set())


# ─────────────────────────────────────────────────────────────
# 6 — repair-track (곡 하나만 고치기)
# ─────────────────────────────────────────────────────────────


class RepairOneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def registry(self):
        chunk = {str(self.root / "T000001.mp3"): {"music_id": 1, "stream_id": 11},
                 str(self.root / "T000002.mp3"): {"music_id": 2, "stream_id": 77}}
        reg = registry_with(self.root, {
            "T000001": track_entry(1, "Alive"),
            "T000002": track_entry(2, "Gone"),
        }, chunk_log=chunk)
        reg.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {"music_id": 2}
        reg.put_catalog_entry("lyrics", "T000002", {"music_id": 2, "line_count": 5})
        return reg

    def test_preview_changes_nothing(self):
        registry = self.registry()
        before = json.dumps(registry.data, sort_keys=True)

        summary = repair_one(FakeServer({1: {"title": "Alive"}}, deleted={2}),
                             registry, "T000002", apply=False)

        self.assertFalse(summary["on_server"])
        self.assertEqual(summary["stream_ids"], [77])
        self.assertFalse(summary["changed"])
        self.assertEqual(json.dumps(registry.data, sort_keys=True), before,
                         "미리보기는 파일을 고치지 않는다")

    def test_missing_song_is_removed_from_the_registry_only(self):
        """서버에 없는 곡 하나만 걷어낸다 — 나머지 곡의 기록은 그대로다."""
        registry = self.registry()

        summary = repair_one(FakeServer({1: {"title": "Alive"}}, deleted={2}),
                             registry, "T000002", apply=True)

        self.assertTrue(summary["changed"])
        self.assertIsNone(registry.catalog_entry("tracks", "T000002"))
        self.assertIsNone(registry.catalog_entry("lyrics", "T000002"))
        self.assertNotIn(str(self.root / "T000002.mp3"), registry.data["tracks"])
        # 나머지 곡은 손대지 않았다.
        self.assertIsNotNone(registry.catalog_entry("tracks", "T000001"))
        self.assertIn(str(self.root / "T000001.mp3"), registry.data["tracks"])
        # 다시 저장·재로드해도 살아 있는 곡이 남아 있다.
        registry.save()
        reloaded = Registry(self.root / "registered.json")
        self.assertEqual(sorted(reloaded.catalog("tracks")), ["T000001"])

    def test_living_song_is_synced_and_exemption_cleared(self):
        registry = self.registry()
        registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {"music_id": 2}
        server = FakeServer({2: {"title": "Gone"}})

        summary = repair_one(server, registry, "T000002", apply=True)

        self.assertTrue(summary["on_server"])
        self.assertEqual([m for m, _ in server.patched], [2])
        self.assertNotIn(EXEMPT_KEY, registry.catalog_entry("tracks", "T000002"))

    def test_unknown_track_is_rejected(self):
        registry = self.registry()
        with self.assertRaisesRegex(ValueError, "등록 기록에 없는 곡"):
            repair_one(FakeServer({}), registry, "T999999", apply=True)

    def test_repair_does_not_touch_source_files(self):
        """원본 오디오·메타데이터는 배치가 지우지 않는다 — 사람이 정한다."""
        audio = self.root / "T000002.mp3"
        audio.write_bytes(b"not really audio")
        registry = self.registry()

        repair_one(FakeServer({1: {"title": "Alive"}}, deleted={2}),
                   registry, "T000002", apply=True)

        self.assertTrue(audio.exists(), "원본 오디오를 지우지 않는다")


# ─────────────────────────────────────────────────────────────
# 7 — batch 의 삭제 곡 스트림 보고
# ─────────────────────────────────────────────────────────────


class DeletedSongStreamTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_deleted_song_streams_are_reported_from_the_mark(self):
        """조회가 404 라 안 보이는 스트림을 청킹 기록으로 찾아 보고한다."""
        chunk = {str(self.root / "T000002.mp3"): {"music_id": 2, "stream_id": 77}}
        registry = registry_with(self.root, {
            "T000001": track_entry(1, "Alive"),
            "T000002": track_entry(2, "Gone"),
        }, chunk_log=chunk)
        registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {"music_id": 2}

        found = orphans.scan_deleted_songs(registry)

        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["track_id"], "T000002")
        self.assertEqual(found[0]["stream_ids"], [77])

    def test_no_mark_means_nothing_to_report(self):
        registry = registry_with(self.root, {"T000001": track_entry(1, "Alive")})
        self.assertEqual(orphans.scan_deleted_songs(registry), [])

    def test_scan_streams_carries_the_finding_and_stays_silent_on_batch_result(self):
        """점검은 경고만 한다 — 배치 종료 코드를 더럽히지 않는다.

        그리고 **삭제된 곡은 조회하지 않는다.** 물어봐야 404 라 스트림을 볼 수
        없기 때문이다. 그 곡의 스트림은 청킹 기록으로만 알 수 있다.
        """
        chunk = {str(self.root / "T000002.mp3"): {"music_id": 2, "stream_id": 77}}
        registry = registry_with(self.root, {
            "T000001": track_entry(1, "Alive"),
            "T000002": track_entry(2, "Gone"),
        }, chunk_log=chunk)
        registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {"music_id": 2}

        server = FakeServer({1: {"title": "Alive"}}, deleted={2})
        server.streams[1] = [{"stream_id": 11, "codec": "OPUS",
                              "bitrate_kbps": 128, "chunk_count": 3}]
        report = orphans.scan_streams(server, registry)

        self.assertEqual(len(report["deleted_songs"]), 1)
        self.assertEqual(report["deleted_songs"][0]["stream_ids"], [77])
        self.assertEqual(report["errors"], [], "점검 실패로 만들지 않는다")
        self.assertNotIn(2, server.stream_queries,
                         "삭제된 곡의 스트림은 조회로 볼 수 없다 — 물어보지 않는다")


class OrphanScanGoneContractTest(unittest.TestCase):
    """`orphans` 가 **410 Gone** 을 삭제로 받는지.

    <h2>왜 이 시험이 필요한가</h2>

    2026-09-18 에 서버가 삭제된 곡에 `404` 대신 **`410 Gone`** 을 주기 시작했다.
    배치가 410 을 안 잡으면 삭제된 곡이 `report["errors"]` 로 올라가 **스캔이 오류로
    끝난 것처럼 보인다** — 실제로는 제대로 감지한 것이다. 그 회귀를 여기서 막는다.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_410_from_stream_query_is_deletion_not_error(self):
        """표식이 아직 없는 곡이 서버에서 삭제됐으면 410 이 온다."""
        registry = registry_with(self.root, {"T000002": track_entry(2, "Gone")})
        server = GoneServer({}, deleted={2})

        report = orphans.scan_streams(server, registry)

        self.assertEqual(
            [], report["errors"], "410 을 오류로 올리면 안 된다 — 삭제 감지다"
        )
        self.assertEqual(
            [{"music_id": 2, "stream_ids": [], "reason": "서버에서 삭제됨"}],
            report["deleted_songs"]
        )

    def test_404_is_a_stale_record_not_a_deletion(self):
        """404 는 "등록 기록이 낡았다" 이지 "삭제됐다" 가 아니다. 둘을 섞으면 사람이
        없는 곡을 되살리려 든다."""
        registry = registry_with(self.root, {"T000003": track_entry(3, "Stale")})
        server = StaleServer({})

        report = orphans.scan_streams(server, registry)

        self.assertEqual([], report["errors"])
        self.assertEqual(
            [], report["deleted_songs"], "404 를 삭제로 세면 안 된다"
        )

    def test_living_song_is_unaffected(self):
        """가드가 정상 경로를 막지 않는다."""
        registry = registry_with(self.root, {"T000001": track_entry(1, "Alive")})
        server = GoneServer({1: {"title": "Alive"}})
        server.streams[1] = [{"stream_id": 11, "codec": "OPUS",
                              "bitrate_kbps": 128, "chunk_count": 3}]

        report = orphans.scan_streams(server, registry)

        self.assertEqual([], report["errors"])
        self.assertEqual([], report["deleted_songs"])
        self.assertIn(1, server.stream_queries, "살아 있는 곡은 실제로 조회한다")



class ServerMissingVerdictTest(unittest.TestCase):
    """목록에 없던 곡을 **단건 조회로** 곡별 판정하는지.

    <h2>왜 필요한가</h2>

    `GET /api/v1/music` 은 삭제된 곡을 걸러서 주므로 "없다" 까지만 알려 준다.
    그런데 **삭제된 것과 등록 기록이 낡은 것은 사람이 할 일이 다르다** — 앞은 그
    곡을 포기할지 정해야 하고, 뒤는 곡 번호를 다시 맞춰야 한다. 서버는 단건 조회에서
    그것을 가른다(2026-09-18): 410 Gone = 삭제, 404 = 그런 번호 없음.

    표식의 `reason`·`verdict` 에 판정이 남고, **살아 있는 곡에는 표식을 붙이지
    않는다**는 것까지가 이 시험의 범위다.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def _server(self, music, deleted=()):
        return FakeServer(music, deleted=deleted)

    def test_deleted_and_stale_are_told_apart(self):
        """410 은 `deleted`, 404 는 `stale_record` 로 갈린다."""
        registry = registry_with(self.root, {
            "T000001": track_entry(1001, "Deleted One"),
            "T000002": track_entry(1002, "Never Registered"),
        })
        # 1001 은 서버에 있지만 삭제됐고, 1002 는 서버가 아예 모르는 번호다.
        server = self._server({}, deleted={1001})

        result = audit_registry(registry, {}, client=server)

        self.assertEqual("deleted", result.verdicts["T000001"])
        self.assertEqual("stale_record", result.verdicts["T000002"])
        self.assertEqual({1001, 1002}, self._queried(server))
        # 둘 다 표식 대상이다 (건너뛸 곡이라는 점은 같다).
        self.assertEqual(2, len(result.missing))

    def test_only_missing_songs_are_queried(self):
        """**1060곡을 다 부르지 않는다.** 목록에 있던 곡은 단건 조회를 하지 않는다.

        이것이 이 설계의 요점이다 — 곡마다 부르면 요청이 1060회, 없던 곡만 부르면
        삭제·노후화가 확인된 몇 회다.
        """
        registry = registry_with(self.root, {
            "T000001": track_entry(1, "Alive"),
            "T000002": track_entry(2, "Alive Too"),
            "T000003": track_entry(3, "Gone"),
        })
        listing = {1: {"music_id": 1, "title": "Alive"},
                   2: {"music_id": 2, "title": "Alive Too"}}
        server = self._server({1: {"title": "Alive"}, 2: {"title": "Alive Too"}},
                              deleted={3})

        audit_registry(registry, listing, client=server)

        self.assertEqual({3}, self._queried(server),
                         "목록에 없던 곡만 단건 조회한다")

    def test_alive_but_unlisted_gets_no_stamp(self):
        """목록에는 없는데 단건 조회는 살아 있다면 **표식을 붙이지 않는다.**

        붙이면 배치가 멀쩡한 곡을 영영 건너뛴다.
        """
        registry = registry_with(self.root, {"T000001": track_entry(1, "Alive")})
        # 목록은 비었지만 단건 조회는 살아 있다고 답한다 (목록이 낡은 경우).
        server = self._server({1: {"title": "Alive"}})

        result = audit_registry(registry, {}, client=server)
        apply_exemptions(result, registry)

        self.assertEqual({}, result.verdicts, "살아 있으면 판정을 달지 않는다")
        self.assertEqual([], result.missing)
        self.assertEqual([], result.exempt)
        self.assertNotIn(EXEMPT_KEY, registry.catalog_entry("tracks", "T000001"))
        self.assertEqual([{"track_id": "T000001", "music_id": 1, "title": "Alive"}],
                         result.alive_but_unlisted)

    def test_verdict_lands_in_the_stamp_with_reason(self):
        """판정이 표식의 `verdict`·`reason` 에 남아야 사람이 다음에 뭘 할지 안다."""
        registry = registry_with(self.root, {
            "T000001": track_entry(1001, "Deleted One"),
            "T000002": track_entry(1002, "Never Registered"),
        })
        server = self._server({}, deleted={1001})

        result = audit_registry(registry, {}, client=server)
        apply_exemptions(result, registry)

        deleted_stamp = registry.catalog_entry("tracks", "T000001")[EXEMPT_KEY]
        self.assertEqual("deleted", deleted_stamp["verdict"])
        self.assertIn("삭제됨", deleted_stamp["reason"])

        stale_stamp = registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY]
        self.assertEqual("stale_record", stale_stamp["verdict"])
        self.assertIn("낡았습니다", stale_stamp["reason"])

    def test_none_return_is_treated_as_deleted(self):
        """**실제 클라이언트 계약**을 시험한다 — 삭제된 곡은 예외가 아니라 `None` 이다.

        `MupidClient.get_music` 은 410 을 자기 안에서 흡수하고 `None` 을 돌려준다.
        그래서 부르는 쪽이 `None` 을 "삭제됨" 으로 읽지 않으면 그 곡은 **판정 없이
        지나가고**, 표식의 `verdict` 가 비어 사람이 다음에 뭘 할지 알 수 없다.

        실제로 이 시험이 없을 때 이 버그가 있었다 — 아래 더블이 예외를 던지도록
        되어 있어서 부르는 쪽의 `None` 처리가 한 번도 실행되지 않았다(더블이 실제
        클라이언트와 다르면 그 차이가 버그를 가린다).
        """
        registry = registry_with(self.root, {"T000001": track_entry(1001, "Deleted")})

        class NoneOnGone(FakeServer):
            """실제 `MupidClient.get_music` 과 **같은 계약**을 지키는 더블."""

            def get_music(self, music_id):
                from music_preprocessing.api_client import GONE_CODE, GONE_STATUS, MupidApiError
                try:
                    return super().get_music(music_id)
                except MupidApiError as e:
                    if e.status == GONE_STATUS or e.code == GONE_CODE:
                        return None      # ← 실제 클라이언트가 하는 일
                    raise

        # 1001 번이 서버에 '있긴 했고 삭제됨' 인 상태를 만든다.
        result = audit_registry(registry, {}, client=NoneOnGone({}, deleted={1001}))

        self.assertEqual("deleted", result.verdicts["T000001"])
        self.assertEqual([], result.alive_but_unlisted, "None 을 살아 있음으로 읽으면 안 된다")

    def test_real_client_gives_none_on_410_and_raises_on_404(self):
        """**실제 `MupidClient`** 가 그렇게 동작하는지 확인한다.

        더블만 시험하면 더블과 실제 클라이언트가 갈려도 모른다 — 실제로 그 차이
        (더블은 예외, 실제는 `None`)가 삭제 판정 누락 버그를 가렸다. 전송 계층만
        갈아 끼워 실제 `get_music` 을 그대로 실행한다.
        """
        from music_preprocessing.api_client import GONE_CODE, GONE_STATUS, MupidClient

        class Stub(MupidClient):
            """`_request` 만 흉내 낸다 — 경로·상태코드 해석은 실제 코드가 한다."""

            def __init__(self, outcome):
                self._outcome = outcome

            def _request(self, method, path, **kwargs):
                raise self._outcome

        client = Stub(MupidApiError(GONE_STATUS, GONE_CODE, "요청한 음악은 삭제되었습니다."))
        self.assertIsNone(
            client.get_music(957), "삭제된 곡(410)은 예외가 아니라 None 이어야 한다"
        )

        client = Stub(MupidApiError(404, "MUPID_MUSIC_NOT_FOUND", "요청한 음악을 찾을 수 없습니다."))
        with self.assertRaises(MupidApiError) as caught:
            client.get_music(958)
        self.assertEqual(404, caught.exception.status, "없는 번호는 그대로 올라간다")

    def test_auth_failure_does_not_masquerade_as_deletion(self):
        """인증 실패·서버 장애는 판단을 보류한다 — 삭제로 단정하면 곡을 걷어낸다."""
        registry = registry_with(self.root, {"T000001": track_entry(1001, "One")})

        class Broken(FakeServer):
            def get_music(self, music_id):
                raise MupidApiError(401, "MUPID_UNAUTHORIZED", "로그인이 필요합니다.")

        with self.assertRaises(Exception) as caught:
            audit_registry(registry, {}, client=Broken({}))

        self.assertIn("가릴 수 없습니다", str(caught.exception))
        self.assertNotIn(EXEMPT_KEY, registry.catalog_entry("tracks", "T000001"))

    def test_stamped_songs_are_not_queried_again(self):
        """표식이 이미 붙은 곡은 지난 실행에서 판정이 끝났다 — 다시 묻지 않는다."""
        registry = registry_with(self.root, {"T000002": track_entry(2, "Gone")})
        registry.catalog_entry("tracks", "T000002")[EXEMPT_KEY] = {
            "music_id": 2, "verdict": "deleted", "reason": "서버에서 삭제됨 (410 Gone)"
        }
        server = self._server({}, deleted={2})

        result = audit_registry(registry, {}, client=server)

        self.assertEqual(set(), self._queried(server), "이미 판정된 곡은 안 묻는다")
        self.assertEqual({}, result.verdicts)
        self.assertEqual(1, len(result.missing), "누락 보고에는 그대로 남는다")

    def test_without_client_it_behaves_as_before(self):
        """단건 조회를 안 넘기면 예전처럼 동작한다 (하위 호환)."""
        registry = registry_with(self.root, {"T000001": track_entry(1, "Gone")})

        result = audit_registry(registry, {})

        self.assertEqual({}, result.verdicts)
        self.assertEqual(0, result.classified)
        self.assertEqual(1, len(result.missing))

    @staticmethod
    def _queried(server) -> set:
        """단건 조회한 곡 번호들. 목록 조회는 여기 안 들어간다."""
        return set(getattr(server, "music_queries", set()))


if __name__ == "__main__":
    unittest.main()

