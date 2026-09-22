"""파형 계산과 채우기 (ticket S15P21A204-420).

파형은 **화면이 그대로 그리는 값**이라, 숫자가 조금 틀려도 예외가 나지 않고
그림만 이상해진다. 그래서 여기서 보는 것은 "돌아가는가" 가 아니라 모양이다.

1. 소리가 큰 구간이 **정확히 그 자리의 칸**에서 커지는가 (시간 축이 뒤집히거나
   밀리지 않는가)
2. 가장 큰 칸이 1.0 이고 전부 0~1 인가 — 서버 검증이 이 범위를 요구한다
3. 칸 경계에 나머지가 몰리지 않는가. 나눗셈으로 칸 크기를 먼저 정하면 마지막
   칸이 유독 두꺼워진다
4. 무음은 **0 배열이 아니라 오류**인가. 0 배열을 올리면 서버가 400 을 주고,
   그것을 배치가 실패로 읽는다 — 그 전에 여기서 걸러야 사유가 남는다
5. 채우기가 이미 파형이 있는 곡을 **서버에 물어서** 건너뛰는가.
   `registered.json` 의 표시를 믿으면 다른 경로로 올라간 것을 모른다

ffmpeg 가 있어야 돈다. 없으면 그 시험만 건너뛴다.
"""
import array
import math
import shutil
import struct
import tempfile
import unittest
import wave
from pathlib import Path

from music_preprocessing.registrar import Registry
from music_preprocessing.waveform import (
    WAVEFORM_SAMPLES,
    WaveformError,
    _bin_rms,
    compute_waveform,
)
from music_preprocessing.waveform_backfill import WaveformBackfiller, collect_audio

HAS_FFMPEG = shutil.which("ffmpeg") is not None

SAMPLE_RATE = 44100


def write_wav(path: Path, blocks: list[tuple[float, float]]) -> None:
    """(초, 진폭 0~1) 목록으로 모노 WAV 를 만든다. 440Hz 사인파다."""
    frames = bytearray()
    phase = 0.0
    step = 2 * math.pi * 440 / SAMPLE_RATE
    for seconds, amplitude in blocks:
        for _ in range(int(SAMPLE_RATE * seconds)):
            value = int(32767 * amplitude * math.sin(phase))
            frames += struct.pack("<h", value)
            phase += step
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SAMPLE_RATE)
        f.writeframes(bytes(frames))


class BinningTest(unittest.TestCase):
    """칸 나누기 — ffmpeg 없이 돈다."""

    def test_칸_경계에_나머지가_몰리지_않는다(self):
        # 10개를 3칸으로. 나눗셈으로 크기를 먼저 정하면 3·3·4 가 되어 마지막이
        # 두꺼워진다. 곱셈으로 경계를 잡으면 3·4·3 처럼 고르게 퍼진다.
        pcm = array.array("h", [1000] * 10)
        values = _bin_rms(pcm, 3)

        self.assertEqual(3, len(values))
        for value in values:
            self.assertAlmostEqual(1000.0, value, places=6)

    def test_칸마다_제곱평균이다(self):
        # 앞 절반은 0, 뒤 절반은 100. 두 칸으로 나누면 0 과 100 이 나와야 한다.
        pcm = array.array("h", [0] * 50 + [100] * 50)
        values = _bin_rms(pcm, 2)

        self.assertAlmostEqual(0.0, values[0], places=6)
        self.assertAlmostEqual(100.0, values[1], places=6)


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg 가 없다")
class ComputeWaveformTest(unittest.TestCase):
    """실제 오디오 파일에서 뽑는다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def test_큰_소리가_그_자리의_칸에서_커진다(self):
        # 앞 1초 조용, 가운데 1초 크게, 뒤 1초 조용.
        path = self.dir / "loud-middle.wav"
        write_wav(path, [(1.0, 0.05), (1.0, 1.0), (1.0, 0.05)])

        values = compute_waveform(path, samples=30)

        self.assertEqual(30, len(values))
        front, middle, back = values[:10], values[10:20], values[20:]
        self.assertGreater(min(middle[2:8]), max(front[2:8]) * 5)
        self.assertGreater(min(middle[2:8]), max(back[2:8]) * 5)

    def test_가장_큰_칸이_1이고_전부_0에서_1_사이다(self):
        path = self.dir / "ramp.wav"
        write_wav(path, [(0.5, 0.1), (0.5, 0.4), (0.5, 1.0)])

        values = compute_waveform(path, samples=WAVEFORM_SAMPLES)

        self.assertEqual(WAVEFORM_SAMPLES, len(values))
        self.assertAlmostEqual(1.0, max(values), places=6)
        for value in values:
            self.assertGreaterEqual(value, 0.0)
            self.assertLessEqual(value, 1.0)

    def test_소수_넷째_자리까지만_남긴다(self):
        path = self.dir / "ramp2.wav"
        write_wav(path, [(0.5, 0.3), (0.5, 1.0)])

        for value in compute_waveform(path, samples=32):
            self.assertEqual(value, round(value, 4))

    def test_무음은_0_배열이_아니라_오류다(self):
        path = self.dir / "silent.wav"
        write_wav(path, [(1.0, 0.0)])

        with self.assertRaises(WaveformError):
            compute_waveform(path, samples=16)

    def test_오디오가_아니면_오류다(self):
        path = self.dir / "not-audio.mp3"
        path.write_bytes(b"this is not audio")

        with self.assertRaises(WaveformError):
            compute_waveform(path, samples=16)


class StubClient:
    """서버 대역. 어떤 곡에 파형이 있는지만 기억한다."""

    def __init__(self, existing: dict[int, list[float]] | None = None):
        self.existing = dict(existing or {})
        self.uploaded: list[tuple[int, list[float]]] = []
        self.asked: list[int] = []

    def get_waveform(self, music_id: int):
        self.asked.append(music_id)
        return self.existing.get(music_id)

    def put_waveform(self, music_id: int, values: list[float]):
        self.uploaded.append((music_id, values))
        self.existing[music_id] = values
        return {"music_id": music_id, "sample_count": len(values), "file_id": 1}


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg 가 없다")
class BackfillTest(unittest.TestCase):
    """이미 등록된 곡 채우기."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

        self.audio = self.dir / "T000567.mp3"
        write_wav(self.dir / "T000567.wav", [(0.5, 0.2), (0.5, 1.0)])
        # 확장자만 mp3 로 바꾼 WAV 다. ffmpeg 는 내용으로 판별하므로 그대로 읽힌다.
        self.audio.write_bytes((self.dir / "T000567.wav").read_bytes())
        (self.dir / "T000567.wav").unlink()

        self.registry = Registry(self.dir / "registered.json")
        self.registry.put_catalog_entry("tracks", "T000567", {"music_id": 42})
        self.registry.save()

    def _run(self, client, force=False, dry_run=False):
        backfiller = WaveformBackfiller(
            client=client,
            registry=self.registry,
            samples=32,
            dry_run=dry_run,
        )
        return backfiller.run([self.audio], force=force)

    def test_파형이_없으면_계산해_올린다(self):
        client = StubClient()

        result = self._run(client)

        self.assertEqual(1, result["uploaded"])
        self.assertEqual(1, len(client.uploaded))
        music_id, values = client.uploaded[0]
        self.assertEqual(42, music_id)
        self.assertEqual(32, len(values))
        self.assertAlmostEqual(1.0, max(values), places=6)

    def test_이미_있으면_서버에_물어보고_건너뛴다(self):
        client = StubClient({42: [0.1] * 32})

        result = self._run(client)

        self.assertEqual(0, result["uploaded"])
        self.assertEqual(1, result["skipped_present"])
        self.assertEqual([42], client.asked)
        self.assertEqual([], client.uploaded)

    def test_force_는_묻지_않고_덮어쓴다(self):
        client = StubClient({42: [0.1] * 32})

        result = self._run(client, force=True)

        self.assertEqual(1, result["uploaded"])
        self.assertEqual([], client.asked)

    def test_등록되지_않은_곡은_건너뛴다(self):
        other = self.dir / "T999999.mp3"
        other.write_bytes(self.audio.read_bytes())
        client = StubClient()

        backfiller = WaveformBackfiller(client=client, registry=self.registry, samples=32)
        result = backfiller.run([other])

        self.assertEqual(1, result["skipped_unregistered"])
        self.assertEqual([], client.uploaded)

    def test_dry_run_은_서버에_붙지_않는다(self):
        result = self._run(client=None, dry_run=True)

        self.assertEqual(1, result["uploaded"])
        self.assertEqual([], result["errors"])

    def test_한_곡이_실패해도_나머지를_계속한다(self):
        broken = self.dir / "broken.mp3"
        broken.write_bytes(b"not audio at all")
        self.registry.put_catalog_entry("tracks", "broken", {"music_id": 43})
        client = StubClient()

        backfiller = WaveformBackfiller(client=client, registry=self.registry, samples=32)
        result = backfiller.run([broken, self.audio])

        self.assertEqual(1, len(result["errors"]))
        self.assertEqual("broken.mp3", result["errors"][0]["key"])
        self.assertEqual(1, result["uploaded"])


class CollectAudioTest(unittest.TestCase):

    def test_음원만_이름순으로_모은다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ("b.mp3", "a.flac", "notes.txt", "c.WAV"):
                (root / name).write_bytes(b"")
            (root / "sub").mkdir()

            names = [path.name for path in collect_audio(root)]

        self.assertEqual(["a.flac", "b.mp3", "c.WAV"], names)


if __name__ == "__main__":
    unittest.main()
