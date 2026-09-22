"""이미 등록된 곡의 파형을 채운다 (`waveform` 명령)

청킹 경로(`batch`)는 곡을 올리면서 파형도 같이 올린다. 이 모듈은 **그 전에
올라간 곡**을 위한 것이다 — 카탈로그 1060곡은 파형이라는 개념이 생기기 전에
등록됐다.

`covers` · `lyrics` 와 같은 자리다: 서버에 이미 있는 곡을 대상으로, 빠진 것만
다시 채운다. 음원 파일은 그대로 읽지만 **청킹은 하지 않는다** — 스트림과 청크는
이미 서버에 있고, 그걸 다시 올리면 재생 중인 기기가 깨진다.
"""

from __future__ import annotations

import logging
from pathlib import Path

from .catalog import ProgressReporter
from .api_client import MupidClient
from .registrar import Registry
from .waveform import WAVEFORM_SAMPLES, compute_waveform

logger = logging.getLogger(__name__)

#: 음원으로 볼 확장자. `batch` 가 훑는 것과 같다.
AUDIO_SUFFIXES = (".mp3", ".wav", ".flac", ".m4a", ".ogg", ".opus")


class WaveformBackfiller:
    """등록된 곡들의 파형을 채운다."""

    def __init__(
        self,
        client: MupidClient | None,
        registry: Registry,
        samples: int = WAVEFORM_SAMPLES,
        ffmpeg_threads: int = 2,
        dry_run: bool = False,
        progress: ProgressReporter | None = None,
    ) -> None:
        self.client = client
        self.registry = registry
        self.samples = samples
        self.ffmpeg_threads = ffmpeg_threads
        self.dry_run = dry_run
        self.progress = progress or ProgressReporter()

    def run(self, audio_paths: list[Path], force: bool = False) -> dict:
        """파형이 없는 곡을 채웁니다.

        Args:
            audio_paths: 원본 음원 파일들
            force: 이미 파형이 있는 곡도 다시 올린다

        Returns:
            `{"uploaded", "skipped_present", "skipped_unregistered", "errors"}`
        """
        result = {
            "uploaded": 0,
            "skipped_present": 0,
            "skipped_unregistered": 0,
            "errors": [],
        }

        self.progress.phase("파형", len(audio_paths))
        for audio_path in audio_paths:
            try:
                self._one(audio_path, force, result)
            except Exception as e:  # noqa: BLE001 — 한 곡의 실패로 배치를 멈추지 않는다
                logger.warning("파형 채우기 실패: %s — %s", audio_path.name, e)
                result["errors"].append({"key": audio_path.name, "error": str(e)})
            finally:
                self.progress.advance("파형")

        if not self.dry_run:
            self.registry.save()
        return result

    # ── 곡 하나 ──

    def _one(self, audio_path: Path, force: bool, result: dict) -> None:
        music_id = self._music_id(audio_path)
        if music_id is None:
            logger.info("등록되지 않은 곡이라 건너뜁니다: %s", audio_path.name)
            result["skipped_unregistered"] += 1
            return

        # 이미 있는 곡을 다시 계산하지 않는다. 한 곡에 ffmpeg 한 번 + 파이썬
        # 루프 1초라, 1060곡을 매번 다시 도는 것은 20분짜리 낭비다.
        #
        # **서버에 묻는다** — `registered.json` 의 표시가 아니라. 그 파일은 배치가
        # 들고 있는 기억일 뿐이고, 다른 사람이 돌린 배치나 손으로 올린 것까지는
        # 모른다 (`audit.py` 가 생긴 이유가 같은 어긋남이다).
        #
        # dry-run 은 서버에 붙지 않는 것이 규약이라 클라이언트가 없을 수 있다
        # (`lyrics` 도 같다). 그때는 "올라갈 것" 으로 센다.
        if not force and self.client is not None:
            if self.client.get_waveform(music_id) is not None:
                result["skipped_present"] += 1
                return

        values = compute_waveform(
            audio_path, samples=self.samples, ffmpeg_threads=self.ffmpeg_threads
        )

        if self.dry_run:
            logger.info("[dry-run] 파형 %d칸 → music_id=%d", len(values), music_id)
            result["uploaded"] += 1
            return

        self.client.put_waveform(music_id, values)
        self._mark(audio_path, music_id)
        result["uploaded"] += 1
        logger.info("파형 등록: %s → music_id=%d (%d칸)", audio_path.name, music_id, len(values))

    def _music_id(self, audio_path: Path) -> int | None:
        """이 음원이 서버의 어느 곡인가.

        청킹이 남긴 기록을 먼저 보고, 없으면 카탈로그 쪽을 본다 — 파일명
        (`T000567.mp3`)이 곧 카탈로그의 `track_id` 다. `Registrar` 가 곡을 찾는
        순서와 같다.
        """
        entry = self.registry.track(audio_path) or {}
        if entry.get("music_id"):
            return entry["music_id"]
        return self.registry.catalog_music_id(audio_path.stem)

    def _mark(self, audio_path: Path, music_id: int) -> None:
        """`registered.json` 에 파형이 올라갔다고 적는다.

        대상을 고르는 근거로 쓰지는 않는다(위 `_one` 참고). 사람이 `status` 로
        훑을 때 어디까지 됐는지 보이라고 남기는 것이다.
        """
        entry = self.registry.track(audio_path)
        if entry is None:
            # 카탈로그로만 알던 곡이다. 청킹 기록이 없으므로 새로 만들지 않고
            # 둔다 — 여기서 반쪽짜리 항목을 만들면 `audit` 이 그걸 청킹 결과로
            # 읽는다.
            return
        entry["waveform"] = True
        self.registry.put_track(audio_path, entry)


def collect_audio(input_dir: Path) -> list[Path]:
    """입력 디렉터리의 음원 파일. 이름 순."""
    return sorted(
        path
        for path in Path(input_dir).iterdir()
        if path.is_file() and path.suffix.lower() in AUDIO_SUFFIXES
    )
