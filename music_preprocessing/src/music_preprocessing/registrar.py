"""등록 단계 — 청킹 결과를 Mupid API 에 올린다.

곡을 특정하는 길이 둘이다.

1. **카탈로그** — `catalog` 명령이 이미 만든 곡이면 그 번호를 그대로 쓴다.
   오디오 파일명(`T000567.mp3`)이 곧 카탈로그의 `track_id` 다. 이 길로 오면
   아티스트·앨범·곡을 만들지 않고 스트림·청크만 붙인다.
2. **사이드카** — 아래 설명. 카탈로그에 없는 곡을 청킹과 함께 등록할 때 쓴다.

배치 서버에는 오디오 파일과 함께 이미지·곡 정보가 놓인다. 오디오 파일 하나당
같은 이름의 사이드카 JSON 을 읽어 아티스트 → 앨범 → 음악 → 스트림 → 청크 순서로
등록한다.

```
input/
├── palette-01.mp3
├── palette-01.json      ← 사이드카
├── images/iu.jpg
└── images/palette.jpg
```

```json
{
  "title": "밤편지",
  "release_date": "2017-03-24",
  "artist": { "name": "아이유", "image": "images/iu.jpg" },
  "album":  { "title": "Palette", "release_date": "2017-04-21",
              "image": "images/palette.jpg" }
}
```

이미 아는 번호가 있으면 `artist.id` · `album.id` · `music_id` 로 직접 지정할 수
있고, 그러면 그 단계는 건너뛴다.

아티스트·앨범은 **이름 기준으로 한 번만** 만든다. 매핑은 `registered.json` 에
남는다. API 에 이름으로 조회하는 엔드포인트가 없어서 배치가 직접 기억한다.
"""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path

from .api_client import MupidApiError, MupidClient, is_music_gone

logger = logging.getLogger(__name__)

# 청킹 파이프라인이 만드는 코덱. ffmpeg 인코더가 libopus 로 고정되어 있다.
CODEC = "OPUS"

_BITRATE_PATTERN = re.compile(r"^\s*(\d+)\s*k?\s*$", re.IGNORECASE)


class RegistrationError(RuntimeError):
    """등록 단계에서 실패했다. 청킹 결과물 자체는 남아 있다."""


class SidecarMissingError(RegistrationError):
    """사이드카 JSON 도 카탈로그 등록도 없어 곡을 특정할 수 없다.

    다시 시도해도 사람이 파일을 놓기 전까지는 결과가 같다. 그래서 배치는 이
    경우를 실패가 아니라 건너뜀으로 처리하고 종료 코드를 더럽히지 않는다.
    """


class MusicGoneError(RegistrationError):
    """서버에서 삭제된 곡이다 (410 Gone).

    **고칠 수 없는 것이라 실패가 아니다.** 삭제된 곡은 수정도 메타데이터 등록도
    받지 않으므로 다시 시도해도 같고, 실패로 세면 배치가 매일 붉어진다 —
    사이드카가 없는 경우와 같은 부류다.

    되살릴지 원본을 지울지는 사람이 정한다. 그 판단을 돕는 표식은 `catalog` 의
    서버 대조가 `registered.json` 에 남긴다 (`audit.EXEMPT_KEY`).
    """


# ─────────────────────────────────────────────────────────────
# 사이드카
# ─────────────────────────────────────────────────────────────


def load_sidecar(audio_path: Path) -> dict | None:
    """오디오 파일 옆의 `<같은 이름>.json` 을 읽는다. 없으면 None."""
    sidecar = audio_path.with_suffix(".json")
    if not sidecar.exists():
        return None
    with open(sidecar, encoding="utf-8") as f:
        return json.load(f)


def _normalize_datetime(value: str, field: str) -> str:
    """`2017-03-24` 같은 날짜를 API 가 받는 LocalDateTime 형식으로 맞춘다."""
    if not value:
        raise RegistrationError(f"{field} 가 비어 있습니다.")
    value = value.strip()
    if len(value) == 10:  # YYYY-MM-DD
        return f"{value}T00:00:00"
    return value


def parse_bitrate_kbps(bitrate: str) -> int:
    """`"128k"` → `128`."""
    matched = _BITRATE_PATTERN.match(str(bitrate))
    if not matched:
        raise RegistrationError(f"비트레이트를 해석할 수 없습니다: {bitrate}")
    return int(matched.group(1))


# ─────────────────────────────────────────────────────────────
# 등록 레지스트리
# ─────────────────────────────────────────────────────────────


class Registry:
    """등록 결과를 기억하는 JSON 파일.

    사이드카 경로 (`batch` 명령이 쓴다)

    - `artists`: 아티스트명 → artist_id
    - `albums` : "artist_id|앨범명" → album_id
    - `tracks` : 오디오 파일 경로 → { music_id, stream_id, ... }

    카탈로그 경로 (`catalog` · `covers` · `lyrics` 명령이 쓴다)

    - `catalog.artists`: artist_key → { artist_id, image_file_id, cover_sha256 }
    - `catalog.albums` : album_key  → { album_id, artist_id, image_file_id, … }
    - `catalog.tracks` : track_id   → { music_id, album_id, artist_id, … }
    - `catalog.lyrics` : track_id   → { music_id, line_count, sha256, … }

    두 경로가 한 파일을 쓰는 이유는 **곡 번호를 나눠 가져야 하기 때문이다.**
    카탈로그가 만든 곡을 청킹 쪽이 모르면 같은 곡을 한 번 더 만들어 고아 행이
    쌓인다. `Registrar` 가 사이드카가 없을 때 `catalog.tracks` 를 먼저 본다.
    """

    _CATALOG_SECTIONS = ("artists", "albums", "tracks", "lyrics")

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.data = {"artists": {}, "albums": {}, "tracks": {}, "catalog": {}}
        if self.path.exists():
            with open(self.path, encoding="utf-8") as f:
                loaded = json.load(f)
            for key in self.data:
                self.data[key] = loaded.get(key, {})
        for section in self._CATALOG_SECTIONS:
            self.data["catalog"].setdefault(section, {})

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2, ensure_ascii=False)

    # ── 조회/기록 ──

    def artist_id(self, name: str) -> int | None:
        return self.data["artists"].get(name)

    def put_artist(self, name: str, artist_id: int) -> None:
        self.data["artists"][name] = artist_id

    def album_id(self, artist_id: int, title: str) -> int | None:
        return self.data["albums"].get(f"{artist_id}|{title}")

    def put_album(self, artist_id: int, title: str, album_id: int) -> None:
        self.data["albums"][f"{artist_id}|{title}"] = album_id

    def track(self, audio_path: Path) -> dict | None:
        return self.data["tracks"].get(str(audio_path))

    def put_track(self, audio_path: Path, entry: dict) -> None:
        self.data["tracks"][str(audio_path)] = entry

    # ── 카탈로그 (`catalog` · `covers` 명령) ──

    def catalog(self, section: str) -> dict:
        """`artists` · `albums` · `tracks` 중 하나를 통째로 돌려준다."""
        if section not in self._CATALOG_SECTIONS:
            raise KeyError(section)
        return self.data["catalog"][section]

    def catalog_entry(self, section: str, key: str) -> dict | None:
        return self.catalog(section).get(key)

    def put_catalog_entry(self, section: str, key: str, entry: dict) -> None:
        self.catalog(section)[key] = entry

    def catalog_music_id(self, track_id: str) -> int | None:
        """카탈로그가 이미 만든 곡 번호. 청킹 등록이 이걸 먼저 본다."""
        entry = self.catalog("tracks").get(track_id)
        return entry.get("music_id") if entry else None


# ─────────────────────────────────────────────────────────────
# 등록
# ─────────────────────────────────────────────────────────────


class Registrar:
    """청킹 결과 하나를 API 에 등록한다."""

    def __init__(self, client: MupidClient, registry: Registry) -> None:
        self.client = client
        self.registry = registry

    def register(self, audio_path: Path, metadata: dict, output_dir: Path) -> dict:
        """곡 하나를 등록한다.

        Args:
            audio_path: 원본 오디오 파일 경로 (사이드카를 찾는 기준)
            metadata: `process_file` 이 만든 metadata.json 내용
            output_dir: 청크 파일이 들어 있는 디렉터리

        Returns:
            { "music_id": …, "stream_id": …, "chunk_count": … }
        """
        chunks = metadata.get("chunks")
        if not chunks:
            raise RegistrationError(
                "metadata.json 에 청크 목록이 없습니다. 이전 버전 결과물이면 다시 청킹해야 합니다."
            )

        previous = self.registry.track(audio_path) or {}
        # `catalog` 명령이 먼저 곡을 만들어 두었으면 그 번호를 그대로 쓴다.
        # 파일명(`T000567.mp3`)이 곧 카탈로그의 track_id 다. 이걸 보지 않으면
        # 같은 곡이 한 번 더 만들어져 서버에 고아 행이 쌓인다.
        catalog = self.registry.catalog_entry("tracks", audio_path.stem) or {}
        sidecar = load_sidecar(audio_path)

        if sidecar is None and not catalog:
            raise SidecarMissingError(
                f"사이드카 JSON 도 카탈로그 등록도 없습니다: "
                f"{audio_path.with_suffix('.json').name}"
            )

        if sidecar is None:
            artist_id = catalog["artist_id"]
            album_id = catalog["album_id"]
            music_id = catalog["music_id"]
        else:
            artist_id = self._ensure_artist(audio_path, sidecar)
            album_id = self._ensure_album(audio_path, sidecar, artist_id)
            music_id = self._ensure_music(
                sidecar, previous, catalog, artist_id, album_id
            )

        # 곡 번호를 먼저 남긴다. 아래에서 실패하고 다음 배치가 다시 시도할 때
        # 이 값이 없으면 같은 곡이 또 만들어져 고아 행이 쌓인다.
        self.registry.put_track(
            audio_path,
            {
                "music_id": music_id,
                "album_id": album_id,
                "artist_id": artist_id,
            },
        )
        self.registry.save()

        # 내용이 바뀐 곡을 다시 올릴 때는 예전 스트림을 먼저 치운다.
        # 같은 코덱·비트레이트가 둘이면 서버가 409 로 막는다.
        if previous.get("stream_id"):
            self._drop_stream(previous["stream_id"])

        bitrate_kbps = parse_bitrate_kbps(metadata["bitrate"])
        stream_id = self.client.create_stream(
            music_id=music_id,
            bitrate_kbps=bitrate_kbps,
            codec=CODEC,
            duration_ms=metadata["duration_ms"],
            chunk_count=metadata["chunk_count"],
        )

        try:
            for chunk in chunks:
                self.client.upload_chunk(
                    stream_id=stream_id,
                    index=chunk["index"],
                    duration_ms=chunk["duration_ms"],
                    file_hash=chunk["sha256"],
                    chunk_path=output_dir / chunk["file"],
                )
            self.client.complete_stream(stream_id)
        except Exception:
            # 반쯤 올라간 스트림을 남기면 다음 시도가 409 로 막힌다.
            self._drop_stream(stream_id)
            raise

        # 파형은 스트림이 완료된 **뒤에** 올린다. 앞에 두면 재생이 안 되는 곡에
        # 파형만 남는다.
        #
        # **실패해도 스트림을 되돌리지 않는다.** 파형이 없으면 화면이 기기에서
        # 직접 계산하므로 재생에는 지장이 없다 — 곡을 통째로 다시 올리게 만드는
        # 쪽이 손해다.
        #
        # 대신 **성공했을 때만** 원본 해시를 적어 둔다. 이 값이 `processed.json` 의
        # `sha256` 과 어긋나 있으면 서버의 파형이 낡은 것이고, 다음 배치가
        # 재청킹 없이 그것만 다시 올린다 (`batch._retry_waveforms`).
        #
        # 삭제된 곡이면 건너뛴다. 여기까지 왔다는 것은 스트림 등록은 통과했다는
        # 뜻이라 흔치 않지만(그 곡은 위에서 이미 410 을 맞는다), 스트림과 곡의
        # 상태가 어긋난 순간에 올 수 있다.
        try:
            waveform_sha256 = self.put_waveform(
                music_id, metadata.get("waveform"), metadata.get("sha256"), audio_path
            )
        except MusicGoneError as e:
            logger.info("파형 건너뜀(서버에서 삭제된 곡): %s — %s", audio_path.name, e)
            waveform_sha256 = None

        entry = {
            "music_id": music_id,
            "album_id": album_id,
            "artist_id": artist_id,
            "stream_id": stream_id,
            "codec": CODEC,
            "bitrate_kbps": bitrate_kbps,
            "chunk_count": metadata["chunk_count"],
        }
        if waveform_sha256:
            entry["waveform_sha256"] = waveform_sha256
        self.registry.put_track(audio_path, entry)
        self.registry.save()

        logger.info(
            "등록 완료: %s → music_id=%d stream_id=%d (청크 %d개)",
            audio_path.name,
            music_id,
            stream_id,
            metadata["chunk_count"],
        )
        return entry

    def put_waveform(
        self,
        music_id: int,
        values: list[float] | None,
        source_sha256: str | None,
        audio_path: Path,
    ) -> str | None:
        """파형을 올리고, 성공하면 그 파형이 나온 **원본 해시**를 돌려준다.

        돌려준 값을 `processed.json` 에 적어 두면 "서버에 올라간 파형이 어느
        음원에서 나왔는지" 를 알 수 있다. 음원이 바뀌면 그 해시가 어긋나므로
        낡은 파형을 찾아낼 수 있다.

        실패는 `None` 이다. 예외를 올리지 않는 것은 파형이 없어도 재생에 지장이
        없기 때문이고, `None` 을 적지 않고 **키를 비워 두면** 다음 배치가 자연히
        다시 시도한다.

        Raises:
            MusicGoneError: 서버에서 삭제된 곡. 이것만 예외로 올린다 — 다시
                시도해도 같고, **실패로 세면 배치가 붉어진다.** 부르는 쪽이
                건너뜀으로 센다.
        """
        if not values:
            logger.info("파형 없이 등록한다: %s", audio_path.name)
            return None
        try:
            self.client.put_waveform(music_id, values)
        except MupidApiError as e:
            if is_music_gone(e):
                raise MusicGoneError(
                    f"서버에서 삭제된 곡입니다 (music_id={music_id})"
                ) from e
            logger.warning(
                "파형 등록 실패 (스트림은 그대로 둔다): %s music_id=%d — %s",
                audio_path.name,
                music_id,
                e,
            )
            return None
        except Exception as e:  # noqa: BLE001 — 파형 실패가 등록 실패는 아니다
            logger.warning(
                "파형 등록 실패 (스트림은 그대로 둔다): %s music_id=%d — %s",
                audio_path.name,
                music_id,
                e,
            )
            return None
        return source_sha256

    # ── 단계별 ──

    def _ensure_artist(self, audio_path: Path, sidecar: dict) -> int:
        artist = sidecar.get("artist") or {}
        if artist.get("id"):
            return int(artist["id"])

        name = artist.get("name")
        if not name:
            raise RegistrationError("사이드카에 artist.name 이 없습니다.")

        cached = self.registry.artist_id(name)
        if cached:
            return cached

        image = self._resolve_image(audio_path, artist.get("image"), "artist.image")
        file_id = self.client.upload_image("ARTIST", _storage_key(name), image)
        artist_id = self.client.create_artist(name, file_id)

        self.registry.put_artist(name, artist_id)
        self.registry.save()
        logger.info("아티스트 등록: %s → artist_id=%d", name, artist_id)
        return artist_id

    def _ensure_album(self, audio_path: Path, sidecar: dict, artist_id: int) -> int:
        album = sidecar.get("album") or {}
        if album.get("id"):
            return int(album["id"])

        title = album.get("title")
        if not title:
            raise RegistrationError("사이드카에 album.title 이 없습니다.")

        cached = self.registry.album_id(artist_id, title)
        if cached:
            return cached

        image = self._resolve_image(audio_path, album.get("image"), "album.image")
        file_id = self.client.upload_image(
            "ALBUM", _storage_key(f"{artist_id}-{title}"), image
        )
        album_id = self.client.create_album(
            artist_id=artist_id,
            title=title,
            image_file_id=file_id,
            release_date=_normalize_datetime(
                album.get("release_date") or sidecar.get("release_date", ""),
                "album.release_date",
            ),
        )

        self.registry.put_album(artist_id, title, album_id)
        self.registry.save()
        logger.info("앨범 등록: %s → album_id=%d", title, album_id)
        return album_id

    def _ensure_music(
        self,
        sidecar: dict,
        previous: dict,
        catalog: dict,
        artist_id: int,
        album_id: int,
    ) -> int:
        # 같은 파일을 다시 처리하는 경우 곡은 그대로 두고 스트림만 갈아끼운다.
        if previous.get("music_id"):
            return previous["music_id"]
        if catalog.get("music_id"):
            return int(catalog["music_id"])
        if sidecar.get("music_id"):
            return int(sidecar["music_id"])

        title = sidecar.get("title")
        if not title:
            raise RegistrationError("사이드카에 title 이 없습니다.")

        music_id = self.client.add_music(
            album_id=album_id,
            artist_id=artist_id,
            title=title,
            release_date=_normalize_datetime(
                sidecar.get("release_date", ""), "release_date"
            ),
        )
        logger.info("음악 등록: %s → music_id=%d", title, music_id)
        return music_id

    def _drop_stream(self, stream_id: int) -> None:
        """스트림을 지운다. 이미 없으면 넘어간다."""
        try:
            self.client.delete_stream(stream_id)
            logger.info("기존 스트림 삭제: stream_id=%d", stream_id)
        except MupidApiError as e:
            if e.status == 404:
                return
            raise

    @staticmethod
    def _resolve_image(audio_path: Path, image: str | None, field: str) -> Path:
        if not image:
            raise RegistrationError(f"사이드카에 {field} 가 없습니다.")
        path = Path(image)
        if not path.is_absolute():
            path = audio_path.parent / path
        if not path.exists():
            raise RegistrationError(f"{field} 파일이 없습니다: {path}")
        return path


def _storage_key(value: str) -> str:
    """스토리지 키로 쓸 수 있게 공백·특수문자를 정리한다."""
    cleaned = re.sub(r"[^0-9A-Za-z가-힣._-]+", "-", value).strip("-")
    return cleaned or "unnamed"
