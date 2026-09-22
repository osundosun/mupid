"""음원이 바뀌면 파형도 교체되는가 (ticket S15P21A204-420).

배치는 음원 해시로 재처리를 정한다. 파형이 그 흐름에 제대로 묶여 있지 않으면
**청크는 새것인데 화면에는 옛 그림이 남는다** — 예외도 로그도 없이 조용하다.
여기서 보는 것은 그 조용한 고장이 생기지 않는가다.

1. 파형을 올리고 나면 **그 파형이 나온 원본의 해시**가 기록되는가
   (`registered.waveform_sha256`)
2. 업로드가 실패하면 그 기록이 **남지 않는가.** 남으면 다음 배치가 낡은 파형을
   성한 것으로 보고 넘어간다
3. 음원이 바뀌었는데 파형 교체만 실패한 상태에서, 다음 배치가 **재청킹 없이**
   파형만 다시 올리는가
4. 파형이 생기기 전에 청킹된 곡(= `metadata.json` 에 `waveform` 키가 없다)은
   원본에서 다시 계산해 채우는가
5. 이미 최신인 곡은 **아무것도 하지 않는가.** 매일 도는 배치라 여기서 매번
   올리면 1060곡에 쓸데없는 PUT 이 매일 나간다
6. **서버에서 삭제된 곡이 배치를 실패시키지 않는가.** 2026-09-22 파이프라인이
   여기서 붉어졌다 — 삭제된 곡 22개에 파형을 올리려다 410 Gone 을 맞고 전부
   실패로 세어졌다. 삭제된 곡은 수정도 메타데이터 등록도 받지 않으므로 다시
   시도해도 같다. `lyrics` · `classify` 와 같은 표식으로 미리 거르고, 표식보다
   삭제가 빨랐던 경우는 410 자체를 건너뜀으로 센다
"""
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from music_preprocessing.api_client import MupidApiError
from music_preprocessing.audit import EXEMPT_KEY
from music_preprocessing.batch import _retry_waveforms, _waveform_is_stale
from music_preprocessing.config import ChunkConfig
from music_preprocessing.registrar import MusicGoneError, Registrar, Registry

from test_waveform import HAS_FFMPEG, write_wav

SOURCE_HASH = "a" * 64
OLD_HASH = "b" * 64


def gone_error() -> MupidApiError:
    """서버가 삭제된 곡에 주는 응답 (2026-09-22 배치 실패의 실제 모양)."""
    return MupidApiError(410, "MUPID_MUSIC_GONE", "요청한 음악은 삭제되었습니다.")


class StubClient:
    def __init__(self, fail: bool = False, gone: bool = False):
        self.fail = fail
        self.gone = gone
        self.uploaded: list[tuple[int, list[float]]] = []

    def put_waveform(self, music_id: int, values: list[float]):
        if self.gone:
            raise gone_error()
        if self.fail:
            raise RuntimeError("서버가 받지 않았다")
        self.uploaded.append((music_id, values))
        return {"music_id": music_id, "sample_count": len(values), "file_id": 1}


class StalenessTest(unittest.TestCase):
    """무엇을 낡았다고 보는가."""

    def test_해시가_같으면_성한_것이다(self):
        entry = {"sha256": SOURCE_HASH,
                 "registered": {"music_id": 42, "waveform_sha256": SOURCE_HASH}}
        self.assertFalse(_waveform_is_stale(entry))

    def test_해시가_다르면_낡은_것이다(self):
        entry = {"sha256": SOURCE_HASH,
                 "registered": {"music_id": 42, "waveform_sha256": OLD_HASH}}
        self.assertTrue(_waveform_is_stale(entry))

    def test_기록이_없으면_낡은_것이다(self):
        entry = {"sha256": SOURCE_HASH, "registered": {"music_id": 42}}
        self.assertTrue(_waveform_is_stale(entry))

    def test_등록되지_않은_항목은_파형의_몫이_아니다(self):
        # 이건 `_retry_registrations` 가 볼 일이다. 여기서 손대면 music_id 가
        # 없어 어차피 못 올린다.
        self.assertFalse(_waveform_is_stale({"sha256": SOURCE_HASH, "registered": None}))
        self.assertFalse(_waveform_is_stale({"sha256": SOURCE_HASH}))


class RegistrarWaveformMarkTest(unittest.TestCase):
    """올린 뒤 무엇을 적는가."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.registry = Registry(Path(self.tmp.name) / "registered.json")

    def test_성공하면_원본_해시를_돌려준다(self):
        registrar = Registrar(StubClient(), self.registry)

        marked = registrar.put_waveform(42, [0.5] * 16, SOURCE_HASH, Path("a.mp3"))

        self.assertEqual(SOURCE_HASH, marked)

    def test_실패하면_아무것도_돌려주지_않는다(self):
        # 여기서 해시를 돌려주면 다음 배치가 "이미 올렸다" 고 보고 넘어간다.
        registrar = Registrar(StubClient(fail=True), self.registry)

        marked = registrar.put_waveform(42, [0.5] * 16, SOURCE_HASH, Path("a.mp3"))

        self.assertIsNone(marked)

    def test_삭제된_곡은_실패가_아니라_전용_예외다(self):
        registrar = Registrar(StubClient(gone=True), self.registry)

        with self.assertRaises(MusicGoneError):
            registrar.put_waveform(42, [0.5] * 16, SOURCE_HASH, Path("a.mp3"))

    def test_파형이_없으면_부르지도_않는다(self):
        client = StubClient()
        registrar = Registrar(client, self.registry)

        marked = registrar.put_waveform(42, None, SOURCE_HASH, Path("a.mp3"))

        self.assertIsNone(marked)
        self.assertEqual([], client.uploaded)


class RetryWaveformsTest(unittest.TestCase):
    """다음 배치가 무엇을 다시 올리는가."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.output = self.root / "output"
        (self.output / "T000567").mkdir(parents=True)
        self.audio = self.root / "T000567.mp3"
        # 원본 자리만 채운다. 파형을 metadata 에서 읽는 경우에는 내용을 보지 않는다.
        self.audio.write_bytes(b"placeholder")

        self.config = ChunkConfig(
            output_dir=str(self.output),
            processed_log=str(self.root / "processed.json"),
        )
        self.registry = Registry(self.root / "registered.json")

    def _metadata(self, waveform):
        payload = {"file_stem": "T000567", "sha256": SOURCE_HASH, "chunk_count": 1}
        if waveform is not None:
            payload["waveform"] = waveform
        (self.output / "T000567" / "metadata.json").write_text(
            json.dumps(payload), encoding="utf-8"
        )

    def _log(self, waveform_sha256):
        registered = {"music_id": 42, "stream_id": 7}
        if waveform_sha256:
            registered["waveform_sha256"] = waveform_sha256
        return {
            str(self.audio): {
                "sha256": SOURCE_HASH,
                "file_stem": "T000567",
                "registered": registered,
            }
        }

    def _run(self, client, processed_log):
        errors = []
        count = _retry_waveforms(
            Registrar(client, self.registry), processed_log, self.output,
            self.config, errors,
        )
        return count, errors

    def test_교체가_실패했던_곡을_재청킹_없이_다시_올린다(self):
        # 음원이 바뀌어 재청킹은 끝났고(metadata 는 새 파형) 업로드만 실패한 상태.
        self._metadata([0.25] * 16)
        processed_log = self._log(OLD_HASH)
        client = StubClient()

        count, errors = self._run(client, processed_log)

        self.assertEqual(1, count)
        self.assertEqual([], errors)
        self.assertEqual([(42, [0.25] * 16)], client.uploaded)
        # 이제 성한 것으로 기록되어 다음 배치는 건드리지 않는다.
        self.assertEqual(
            SOURCE_HASH,
            processed_log[str(self.audio)]["registered"]["waveform_sha256"],
        )
        self.assertFalse(_waveform_is_stale(processed_log[str(self.audio)]))

    def test_이미_최신이면_아무것도_올리지_않는다(self):
        self._metadata([0.25] * 16)
        client = StubClient()

        count, _ = self._run(client, self._log(SOURCE_HASH))

        self.assertEqual(0, count)
        self.assertEqual([], client.uploaded)

    def test_다시_실패하면_기록을_남기지_않는다(self):
        # 남기면 낡은 파형이 성한 것으로 굳어 영영 교체되지 않는다.
        self._metadata([0.25] * 16)
        processed_log = self._log(OLD_HASH)

        count, errors = self._run(StubClient(fail=True), processed_log)

        self.assertEqual(1, count)
        self.assertEqual(1, len(errors))
        self.assertEqual(
            OLD_HASH,
            processed_log[str(self.audio)]["registered"]["waveform_sha256"],
        )
        self.assertTrue(_waveform_is_stale(processed_log[str(self.audio)]))

    @unittest.skipUnless(HAS_FFMPEG, "ffmpeg 가 없다")
    def test_파형이_없던_옛_결과물은_원본에서_계산한다(self):
        # 이 기능이 생기기 전에 청킹된 곡 — metadata.json 에 waveform 키가 없다.
        self._metadata(None)
        wav = self.root / "T000567.wav"
        write_wav(wav, [(0.3, 0.2), (0.3, 1.0)])
        shutil.copyfile(wav, self.audio)
        wav.unlink()
        processed_log = self._log(None)
        client = StubClient()

        count, errors = self._run(client, processed_log)

        self.assertEqual(1, count)
        self.assertEqual([], errors)
        self.assertEqual(1, len(client.uploaded))
        music_id, values = client.uploaded[0]
        self.assertEqual(42, music_id)
        self.assertEqual(160, len(values))
        self.assertAlmostEqual(1.0, max(values), places=6)
        self.assertEqual(
            SOURCE_HASH,
            processed_log[str(self.audio)]["registered"]["waveform_sha256"],
        )

    def test_삭제된_곡은_표식을_보고_건드리지_않는다(self):
        # `catalog` 의 서버 대조가 남긴 표식. `lyrics`·`classify` 가 쓰는 것과 같다.
        # 표식을 보고 미리 빼야 ffmpeg 도 안 돌고 410 도 안 맞는다.
        self._metadata(None)
        self.registry.put_catalog_entry("tracks", "T000567", {"music_id": 42, EXEMPT_KEY: True})
        client = StubClient()

        count, errors = self._run(client, self._log(None))

        self.assertEqual(0, count)
        self.assertEqual([], errors)
        self.assertEqual([], client.uploaded)

    def test_표식이_아직_없어_410_을_맞아도_실패가_아니다(self):
        # 2026-09-22 배치가 붉어진 경로다. 곡 22개가 여기서 실패로 세어졌다.
        self._metadata([0.25] * 16)
        processed_log = self._log(None)

        count, errors = self._run(StubClient(gone=True), processed_log)

        self.assertEqual(1, count)
        # 실패가 아니다 — 고칠 수 없는 것이라 배치 종료 코드를 더럽히지 않는다.
        self.assertEqual([], errors)
        # 성공 표시도 남기지 않는다. 곡이 되살아나면 그때 올라가야 한다.
        self.assertNotIn(
            "waveform_sha256", processed_log[str(self.audio)]["registered"]
        )

    def test_원본도_파형도_없으면_조용히_넘어간다(self):
        # 원본이 지워진 곡이다. 다음 실행에서 stale 정리가 걷어 간다.
        self._metadata(None)
        self.audio.unlink()
        processed_log = self._log(None)
        client = StubClient()

        count, errors = self._run(client, processed_log)

        self.assertEqual(1, count)
        self.assertEqual([], errors)
        self.assertEqual([], client.uploaded)


if __name__ == "__main__":
    unittest.main()
