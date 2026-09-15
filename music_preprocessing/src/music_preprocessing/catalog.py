"""카탈로그 등록 — 곡별 메타데이터·커버를 Mupid API 에 올린다.

`batch` 명령이 오디오 파일 옆의 사이드카 JSON 을 읽는 것과 달리, 여기서는
**수집 파이프라인이 만든 디렉터리 두 개**를 그대로 읽는다.

```
flow_music_data/
├── metadata/T000001.json   ← 곡 하나당 메타데이터
├── covers/T000001.jpg      ← 곡 하나당 커버
├── audio/T000001.mp3
└── chunking/T000001/       ← `batch` 명령의 산출물
```

파일 이름의 `T000001` 이 곡 식별자(`track_id`)다. 네 디렉터리가 모두 이 이름을
공유하므로, 메타데이터·커버·음원·청크가 서로를 찾는 데 다른 매핑이 필요 없다.

## 등록 순서

메타데이터 1060건이 아티스트 87명 · 앨범 204개로 묶인다. 아티스트·앨범은
`artist_key` · `album_key` 기준으로 **한 번만** 만든다.

1. 아티스트마다 — 대표 커버 업로드(`ARTIST`) → `POST /api/v1/artist`
2. 앨범마다   — 대표 커버 업로드(`ALBUM`)  → `POST /api/v1/album`
3. 곡마다     — 자기 커버 업로드(`TRACK`)  → `POST /api/v1/music`

## 대표 커버

커버는 곡마다 있는데 이미지가 붙는 곳은 아티스트·앨범·곡 셋이다. 아티스트와
앨범은 **소속 곡 중 track_id 가 가장 작은 곡의 커버**를 쓴다. 곡 순서가 고정이라
같은 입력이면 언제나 같은 그림이 뽑힌다.

## 가사

`lyrics_raw` · `lyrics_sections` 는 **읽지 않는다.** 아직 다듬는 중이라 나중에
`POST /api/v1/music/{musicId}/lyric` 으로 따로 넣는다.

## 다시 돌려도 되는가

된다. 등록 결과는 `registered.json` 의 `catalog` 아래에 남고, 이미 있는 것은
건너뛴다. 메타데이터나 커버의 SHA-256 이 바뀐 것만 다시 올린다.
"""

from __future__ import annotations

import json
import logging
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .api_client import MupidApiError, MupidClient
from .hasher import compute_sha256
from .registrar import RegistrationError, Registry

logger = logging.getLogger(__name__)

# 커버 확장자. 수집 파이프라인은 jpg 만 내려받지만, 바뀌어도 찾을 수 있게 둔다.
COVER_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp")

# 서버가 이미지 하나에 허용하는 크기. 넘으면 업로드가 400 으로 막힌다.
IMAGE_MAX_BYTES = 5 * 1024 * 1024

# 발매일을 옮길 시간대. `generated_at` 은 UTC 이고, 서비스는 한국 시간을 쓴다.
_KST = timezone(timedelta(hours=9))


class CatalogError(RegistrationError):
    """카탈로그 등록에서 실패했다."""


# ─────────────────────────────────────────────────────────────
# 메타데이터 읽기
# ─────────────────────────────────────────────────────────────


class TrackMeta:
    """메타데이터 JSON 한 건에서 등록에 쓰는 값만 뽑아 둔 것.

    원본에는 생성 프롬프트·가사·구조 검증 결과까지 들어 있지만, 서버에 올릴 것은
    곡명·발매일·아티스트·앨범뿐이다. 나머지는 읽지 않는다.
    """

    __slots__ = (
        "track_id",
        "title",
        "release_date",
        "artist_key",
        "artist_name",
        "album_key",
        "album_title",
        "meta_sha256",
        "path",
    )

    def __init__(self, path: Path, raw: dict, meta_sha256: str) -> None:
        self.path = path
        self.meta_sha256 = meta_sha256
        self.track_id = _require(raw, "track_id", path)
        self.title = _require(raw, "flow_title", path)
        self.release_date = _to_local_datetime(
            _require(raw, "generated_at", path), path
        )

        artist = raw.get("artist") or {}
        album = raw.get("album") or {}
        self.artist_key = _require(artist, "artist_key", path, prefix="artist.")
        self.artist_name = _require(artist, "name", path, prefix="artist.")
        self.album_key = _require(album, "album_key", path, prefix="album.")
        self.album_title = _require(album, "title", path, prefix="album.")


def _require(raw: dict, field: str, path: Path, prefix: str = "") -> str:
    value = raw.get(field)
    if value is None or (isinstance(value, str) and not value.strip()):
        raise CatalogError(f"{path.name}: {prefix}{field} 가 비어 있습니다.")
    return str(value).strip()


def _to_local_datetime(value: str, path: Path) -> str:
    """`2026-09-08T05:03:31.236059+00:00` → `2026-09-08T14:03:31`.

    API 는 `LocalDateTime` 을 받으므로 시간대 표시를 남기면 파싱이 깨진다.
    한국 시간으로 옮긴 뒤 초까지만 남긴다.
    """
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as e:
        raise CatalogError(f"{path.name}: generated_at 을 읽을 수 없습니다: {value}") from e
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(_KST)
    return parsed.replace(microsecond=0, tzinfo=None).isoformat()


def load_metadata(metadata_dir: Path) -> list[TrackMeta]:
    """메타데이터 디렉터리를 통째로 읽어 track_id 순으로 돌려준다."""
    files = sorted(metadata_dir.glob("*.json"))
    if not files:
        raise CatalogError(f"메타데이터 JSON 이 없습니다: {metadata_dir}")

    tracks: list[TrackMeta] = []
    for path in files:
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        tracks.append(TrackMeta(path, raw, compute_sha256(path)))

    tracks.sort(key=lambda t: t.track_id)
    _check_key_collisions(tracks)
    logger.info("메타데이터 %d건을 읽었습니다: %s", len(tracks), metadata_dir)
    return tracks


def _check_key_collisions(tracks: list[TrackMeta]) -> None:
    """같은 키에 다른 이름이 붙어 있으면 멈춘다.

    키 하나에 아티스트명이 둘이면 어느 쪽으로 만들지 알 수 없고, 조용히 하나를
    고르면 나머지 곡들이 엉뚱한 이름 아래 묶인다.
    """
    artists: dict[str, str] = {}
    albums: dict[str, str] = {}
    for track in tracks:
        for cache, key, name, label in (
            (artists, track.artist_key, track.artist_name, "아티스트"),
            (albums, track.album_key, track.album_title, "앨범"),
        ):
            seen = cache.setdefault(key, name)
            if seen != name:
                raise CatalogError(
                    f"{label} 키 하나에 이름이 둘입니다: {key} → {seen!r} / {name!r} "
                    f"({track.path.name})"
                )


def find_cover(covers_dir: Path, track_id: str) -> Path | None:
    """곡 커버 파일을 찾는다. 없으면 None."""
    for ext in COVER_EXTENSIONS:
        candidate = covers_dir / f"{track_id}{ext}"
        if candidate.exists():
            return candidate
    return None


# ─────────────────────────────────────────────────────────────
# 묶기
# ─────────────────────────────────────────────────────────────


class Grouping:
    """곡 목록을 아티스트·앨범으로 묶은 결과.

    `artist_tracks` · `album_tracks` 의 값은 track_id 오름차순이라, 첫 번째가 곧
    대표곡이다.
    """

    def __init__(self, tracks: list[TrackMeta]) -> None:
        self.tracks = tracks
        self.artist_tracks: dict[str, list[TrackMeta]] = defaultdict(list)
        self.album_tracks: dict[str, list[TrackMeta]] = defaultdict(list)
        for track in tracks:
            self.artist_tracks[track.artist_key].append(track)
            self.album_tracks[track.album_key].append(track)

    def album_artist_key(self, album_key: str) -> str:
        return self.album_tracks[album_key][0].artist_key

    def artist_name(self, artist_key: str) -> str:
        return self.artist_tracks[artist_key][0].artist_name

    def album_title(self, album_key: str) -> str:
        return self.album_tracks[album_key][0].album_title

    def album_release_date(self, album_key: str) -> str:
        """앨범 발매일은 수록곡 중 가장 이른 것으로 잡는다."""
        return min(t.release_date for t in self.album_tracks[album_key])

    def representative(self, section: str, key: str) -> TrackMeta:
        """대표곡 — track_id 가 가장 작은 곡."""
        table = self.artist_tracks if section == "artists" else self.album_tracks
        return table[key][0]


# ─────────────────────────────────────────────────────────────
# 진행 보고
# ─────────────────────────────────────────────────────────────


class ProgressReporter:
    """등록이 어디까지 갔는지 알린다. 기본 구현은 아무것도 하지 않는다.

    등록기가 Rich 를 직접 부르지 않는 이유: 크론으로 돌 때는 그릴 화면이 없고,
    시험은 진행률 없이 돌려야 한다. 화면을 그리는 쪽이 이 자리를 채운다.
    """

    def phase(self, name: str, total: int) -> None:
        """새 단계를 시작한다 (`아티스트` · `앨범` · `곡` · `커버`)."""

    def advance(self, name: str, step: int = 1) -> None:
        """그 단계에서 `step` 만큼 진행했다."""


# ─────────────────────────────────────────────────────────────
# 등록
# ─────────────────────────────────────────────────────────────


class CatalogRegistrar:
    """메타데이터·커버를 API 에 올린다.

    호출 쪽이 `run` 한 번으로 아티스트 → 앨범 → 곡 순서를 모두 돌린다. 단계마다
    `registered.json` 에 바로 기록하므로, 중간에 끊겨도 다음 실행이 남은 것부터
    이어서 한다.
    """

    def __init__(
        self,
        client: MupidClient | None,
        registry: Registry,
        covers_dir: Path,
        dry_run: bool = False,
        progress: ProgressReporter | None = None,
    ) -> None:
        self.client = client
        self.registry = registry
        self.covers_dir = Path(covers_dir)
        self.dry_run = dry_run
        self.progress = progress or ProgressReporter()
        if not dry_run and client is None:
            raise CatalogError("dry-run 이 아니면 API 클라이언트가 있어야 합니다.")

    def _save(self) -> None:
        """등록 결과를 파일에 남긴다.

        dry-run 에서는 쓰지 않는다. 번호가 전부 `null` 인 가짜 항목을 남기면
        다음 실제 실행이 "이미 등록됨" 으로 보고 전부 건너뛴다.
        """
        if self.dry_run:
            return
        self.registry.save()

    # ── 전체 ──

    def run(self, tracks: list[TrackMeta]) -> dict:
        grouping = Grouping(tracks)
        summary = {
            "artists_total": len(grouping.artist_tracks),
            "albums_total": len(grouping.album_tracks),
            "tracks_total": len(tracks),
            "artists_created": 0,
            "albums_created": 0,
            "tracks_created": 0,
            "covers_uploaded": 0,
            "covers_missing": 0,
            "errors": [],
        }

        self.progress.phase("아티스트", len(grouping.artist_tracks))
        for artist_key in sorted(grouping.artist_tracks):
            self._guard(
                summary,
                artist_key,
                lambda key=artist_key: self._ensure_artist(grouping, key, summary),
            )
            self.progress.advance("아티스트")

        self.progress.phase("앨범", len(grouping.album_tracks))
        for album_key in sorted(grouping.album_tracks):
            self._guard(
                summary,
                album_key,
                lambda key=album_key: self._ensure_album(grouping, key, summary),
            )
            self.progress.advance("앨범")

        self.progress.phase("곡", len(tracks))
        for index, track in enumerate(tracks, start=1):
            self._guard(
                summary,
                track.track_id,
                lambda item=track: self._ensure_music(item, summary),
            )
            self.progress.advance("곡")
            if index % 100 == 0:
                logger.info("곡 등록 %d/%d", index, len(tracks))

        self._save()
        return summary

    def _guard(self, summary: dict, key: str, action) -> None:
        """한 건이 실패해도 나머지를 계속 등록한다.

        1060곡 중 하나가 깨졌다고 배치 전체를 버리면, 고친 뒤 처음부터 다시
        돌려야 한다. 실패는 모아서 마지막에 보고한다.
        """
        try:
            action()
        except Exception as e:  # noqa: BLE001 — 사유는 요약에 남긴다
            logger.error("등록 실패: %s - %s", key, e)
            summary["errors"].append({"key": key, "error": str(e)})

    # ── 단계별 ──

    def _ensure_artist(self, grouping: Grouping, artist_key: str, summary: dict) -> None:
        if self.registry.catalog_entry("artists", artist_key):
            return

        name = grouping.artist_name(artist_key)
        representative = grouping.representative("artists", artist_key)
        cover = self._require_cover(representative, f"아티스트 {artist_key}")

        file_id = self._upload_cover("ARTIST", artist_key, cover, summary)
        artist_id = self._create_artist(name, file_id)

        self.registry.put_catalog_entry(
            "artists",
            artist_key,
            {
                "artist_id": artist_id,
                "name": name,
                "image_file_id": file_id,
                "cover_track_id": representative.track_id,
                "cover_sha256": compute_sha256(cover),
            },
        )
        self._save()
        summary["artists_created"] += 1
        logger.info("아티스트 등록: %s (%s) → %s", name, artist_key, artist_id)

    def _ensure_album(self, grouping: Grouping, album_key: str, summary: dict) -> None:
        if self.registry.catalog_entry("albums", album_key):
            return

        artist_key = grouping.album_artist_key(album_key)
        artist = self.registry.catalog_entry("artists", artist_key)
        if not artist:
            raise CatalogError(f"아티스트가 먼저 등록되지 않았습니다: {artist_key}")

        title = grouping.album_title(album_key)
        representative = grouping.representative("albums", album_key)
        cover = self._require_cover(representative, f"앨범 {album_key}")

        file_id = self._upload_cover("ALBUM", album_key, cover, summary)
        album_id = self._create_album(
            artist["artist_id"], title, file_id, grouping.album_release_date(album_key)
        )

        self.registry.put_catalog_entry(
            "albums",
            album_key,
            {
                "album_id": album_id,
                "artist_id": artist["artist_id"],
                "title": title,
                "image_file_id": file_id,
                "cover_track_id": representative.track_id,
                "cover_sha256": compute_sha256(cover),
            },
        )
        self._save()
        summary["albums_created"] += 1
        logger.info("앨범 등록: %s (%s) → %s", title, album_key, album_id)

    def _ensure_music(self, track: TrackMeta, summary: dict) -> None:
        existing = self.registry.catalog_entry("tracks", track.track_id)
        if existing:
            # 곡은 그대로 두고, 메타데이터가 바뀌었으면 그것만 알린다. 제목·발매일
            # 수정은 사람이 판단할 일이라 배치가 말없이 덮어쓰지 않는다.
            if existing.get("meta_sha256") != track.meta_sha256:
                logger.warning(
                    "메타데이터가 바뀌었지만 곡 정보는 그대로 둡니다: %s "
                    "(고치려면 PATCH /api/v1/music/%s)",
                    track.track_id,
                    existing.get("music_id"),
                )
            return

        album = self.registry.catalog_entry("albums", track.album_key)
        if not album:
            raise CatalogError(f"앨범이 먼저 등록되지 않았습니다: {track.album_key}")

        # 곡 커버는 없어도 등록한다. 화면이 앨범 커버로 내려간다.
        cover = find_cover(self.covers_dir, track.track_id)
        file_id = None
        cover_sha256 = None
        if cover is None:
            summary["covers_missing"] += 1
            logger.warning("곡 커버가 없습니다: %s", track.track_id)
        else:
            _check_image_size(cover)
            file_id = self._upload_cover("TRACK", track.track_id, cover, summary)
            cover_sha256 = compute_sha256(cover)

        music_id = self._create_music(track, album["album_id"], file_id)

        self.registry.put_catalog_entry(
            "tracks",
            track.track_id,
            {
                "music_id": music_id,
                "album_id": album["album_id"],
                "artist_id": album["artist_id"],
                "album_key": track.album_key,
                "artist_key": track.artist_key,
                "title": track.title,
                "release_date": track.release_date,
                "image_file_id": file_id,
                "cover_sha256": cover_sha256,
                "meta_sha256": track.meta_sha256,
            },
        )
        self._save()
        summary["tracks_created"] += 1

    # ── 커버만 다시 올리기 ──

    def refresh_covers(self, tracks: list[TrackMeta], force: bool = False) -> dict:
        """이미 등록된 대상의 커버만 다시 올린다.

        저장 키가 `artist_key` · `album_key` · `track_id` 로 고정이라, 같은 키에
        다시 올리면 서버가 **오브젝트 내용만 갈아 끼우고 `file_id` 는 그대로
        둔다.** 그래서 아티스트·앨범은 행을 건드릴 필요가 없다. 곡은 처음에
        커버 없이 등록됐을 수 있어 그때만 `PATCH` 로 번호를 이어 준다.

        Args:
            tracks: 메타데이터 전체 (대표곡을 다시 뽑는 데 쓴다)
            force: SHA-256 이 같아도 다시 올린다
        """
        grouping = Grouping(tracks)
        summary = {
            "artists_refreshed": 0,
            "albums_refreshed": 0,
            "tracks_refreshed": 0,
            "covers_uploaded": 0,
            "covers_missing": 0,
            "skipped_unregistered": 0,
            "errors": [],
        }

        for section, label, keys in (
            ("artists", "아티스트 커버", sorted(grouping.artist_tracks)),
            ("albums", "앨범 커버", sorted(grouping.album_tracks)),
        ):
            category = "ARTIST" if section == "artists" else "ALBUM"
            self.progress.phase(label, len(keys))
            for key in keys:
                self._guard(
                    summary,
                    key,
                    lambda k=key, sec=section, cat=category: self._refresh_group_cover(
                        grouping, sec, cat, k, force, summary
                    ),
                )
                self.progress.advance(label)

        self.progress.phase("곡 커버", len(tracks))
        for track in tracks:
            self._guard(
                summary,
                track.track_id,
                lambda item=track: self._refresh_track_cover(item, force, summary),
            )
            self.progress.advance("곡 커버")

        self._save()
        return summary

    def _refresh_group_cover(
        self,
        grouping: Grouping,
        section: str,
        category: str,
        key: str,
        force: bool,
        summary: dict,
    ) -> None:
        entry = self.registry.catalog_entry(section, key)
        if not entry:
            summary["skipped_unregistered"] += 1
            return

        representative = grouping.representative(section, key)
        cover = find_cover(self.covers_dir, representative.track_id)
        if cover is None:
            summary["covers_missing"] += 1
            logger.warning("%s 대표 커버가 없습니다: %s", category, key)
            return

        digest = compute_sha256(cover)
        if not force and digest == entry.get("cover_sha256"):
            return

        _check_image_size(cover)
        file_id = self._upload_cover(category, key, cover, summary)
        entry["image_file_id"] = file_id
        entry["cover_track_id"] = representative.track_id
        entry["cover_sha256"] = digest
        self.registry.put_catalog_entry(section, key, entry)
        summary["artists_refreshed" if section == "artists" else "albums_refreshed"] += 1
        logger.info("%s 커버 갱신: %s", category, key)

    def _refresh_track_cover(
        self, track: TrackMeta, force: bool, summary: dict
    ) -> None:
        entry = self.registry.catalog_entry("tracks", track.track_id)
        if not entry:
            summary["skipped_unregistered"] += 1
            return

        cover = find_cover(self.covers_dir, track.track_id)
        if cover is None:
            summary["covers_missing"] += 1
            return

        digest = compute_sha256(cover)
        had_cover = entry.get("image_file_id") is not None
        if not force and had_cover and digest == entry.get("cover_sha256"):
            return

        _check_image_size(cover)
        file_id = self._upload_cover("TRACK", track.track_id, cover, summary)

        # 처음 붙이는 커버면 곡 행이 아직 파일을 가리키지 않는다. 그때만 고친다.
        # 이미 가리키고 있으면 오브젝트 내용만 바뀌었으므로 호출이 필요 없다.
        if not had_cover and not self.dry_run:
            self.client.update_music_image(entry["music_id"], file_id)

        entry["image_file_id"] = file_id
        entry["cover_sha256"] = digest
        self.registry.put_catalog_entry("tracks", track.track_id, entry)
        summary["tracks_refreshed"] += 1

    # ── API 호출 (dry-run 이면 부르지 않는다) ──

    def _upload_cover(
        self, category: str, storage_key: str, cover: Path, summary: dict
    ) -> int | None:
        summary["covers_uploaded"] += 1
        if self.dry_run:
            return None
        try:
            return self.client.upload_image(category, storage_key, cover)
        except MupidApiError as e:
            raise CatalogError(
                f"커버 업로드 실패 ({category}/{storage_key}, {cover.name}): {e}"
            ) from e

    def _create_artist(self, name: str, file_id: int | None) -> int | None:
        if self.dry_run:
            return None
        return self.client.create_artist(name, file_id)

    def _create_album(
        self, artist_id: int | None, title: str, file_id: int | None, release_date: str
    ) -> int | None:
        if self.dry_run:
            return None
        return self.client.create_album(artist_id, title, file_id, release_date)

    def _create_music(
        self, track: TrackMeta, album_id: int | None, file_id: int | None
    ) -> int | None:
        if self.dry_run:
            return None
        artist = self.registry.catalog_entry("artists", track.artist_key)
        return self.client.add_music(
            album_id=album_id,
            artist_id=artist["artist_id"],
            title=track.title,
            release_date=track.release_date,
            image_file_id=file_id,
        )

    # ── 보조 ──

    def _require_cover(self, track: TrackMeta, label: str) -> Path:
        """아티스트·앨범 이미지는 서버에서 필수라 없으면 만들 수 없다."""
        cover = find_cover(self.covers_dir, track.track_id)
        if cover is None:
            raise CatalogError(
                f"{label} 의 대표 커버가 없습니다: "
                f"{self.covers_dir / (track.track_id + COVER_EXTENSIONS[0])}"
            )
        _check_image_size(cover)
        return cover


def _check_image_size(cover: Path) -> None:
    """서버가 5MB 를 넘는 이미지를 거절한다. 올리기 전에 알아낸다."""
    size = cover.stat().st_size
    if size > IMAGE_MAX_BYTES:
        raise CatalogError(
            f"커버가 너무 큽니다 ({size / 1024 / 1024:.1f}MB > 5MB): {cover}"
        )
