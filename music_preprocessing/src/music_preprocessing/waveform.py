"""원본 오디오 → 파형(진폭 포락선)

곡을 칸으로 나누고 칸마다 RMS 를 재서, 곡 전체의 최댓값으로 나눈 0~1 값을 만든다.
서버에 올리면 화면이 이걸 그대로 그린다 (`PUT /api/v1/music/{id}/waveform`).

## 왜 배치가 계산하는가

그전에는 기기가 곡을 열 때마다 청크를 전부 내려받아 Opus 로 풀고 직접 계산했다
(`frontend/lib/track-waveform.ts`). 파형은 곡이 바뀌지 않는 한 같은 값이라
한 번 만들어 두면 되는데, 사람 수 × 재생 횟수만큼 다시 계산하고 있었다.

## 의존성을 늘리지 않는다

청킹 경로의 의존성은 `typer` · `rich` 뿐이다 (`numpy` 는 `analysis` extra 에만
있다). 그래서 ffmpeg 로 PCM 을 받아 **순수 파이썬으로** 센다. 4분 곡이 약 1초다.

`audioop` 을 쓰지 않는 것은 파이썬 3.13 에서 표준 라이브러리에서 빠졌기 때문이다.
"""

from __future__ import annotations

import array
import logging
import subprocess
import sys
from pathlib import Path

logger = logging.getLogger(__name__)

#: 기본 칸 수. 프런트의 `WAVEFORM_SAMPLES`(`lib/api/endpoints.ts`) 와 같은 값이다.
#: 서버는 16~4096 을 받지만, 화면이 그리는 막대가 많아야 160개라 더 잘게 보낼
#: 이유가 없다. 같은 수로 맞춰 두면 서버 파형과 기기 계산 폴백이 같은 그림이 된다.
WAVEFORM_SAMPLES = 160

#: 디코딩할 표본율. 포락선만 보는 값이라 원본 48kHz 를 그대로 셀 필요가 없다.
#: 순수 파이썬 루프의 비용이 표본 수에 그대로 비례해서, 낮출수록 싸다.
SAMPLE_RATE = 8000

#: 저장할 소수 자릿수. 서버도 같은 자리에서 반올림한다 (`WaveformServiceImpl`).
SCALE = 4

#: ffmpeg 한 번의 상한. 10분짜리도 수 초면 끝나므로 넉넉하다.
_FFMPEG_TIMEOUT_SEC = 600


class WaveformError(RuntimeError):
    """파형을 만들지 못했다. 청킹 결과물 자체는 멀쩡하다."""


def compute_waveform(
    input_path: str | Path,
    samples: int = WAVEFORM_SAMPLES,
    ffmpeg_threads: int = 2,
) -> list[float]:
    """오디오 파일 하나의 파형을 만듭니다.

    Args:
        input_path: 원본 오디오 (mp3, wav, flac 등). **인코딩 전 원본을 쓴다** —
            파형은 곡의 성질이라 비트레이트별로 달라질 이유가 없다.
        samples: 칸 수
        ffmpeg_threads: ffmpeg 스레드 수 (청킹과 같은 설정을 쓴다)

    Returns:
        0~1 사이 실수 `samples` 개. 가장 큰 칸이 정확히 1.0 이다.

    Raises:
        WaveformError: 디코딩이 실패했거나 곡 전체가 무음인 경우
    """
    if samples <= 0:
        raise WaveformError(f"칸 수가 잘못되었습니다: {samples}")

    pcm = _decode_mono_pcm(input_path, ffmpeg_threads)
    if len(pcm) < samples:
        raise WaveformError(
            f"표본이 칸 수보다 적습니다 ({len(pcm)} < {samples}): {input_path}"
        )

    return _normalize(_bin_rms(pcm, samples), input_path)


# ─────────────────────────────────────────────────────────────
# 내부 함수
# ─────────────────────────────────────────────────────────────


def _decode_mono_pcm(input_path: str | Path, ffmpeg_threads: int) -> array.array:
    """ffmpeg 로 16비트 모노 PCM 을 받아 옵니다.

    스테레오를 모노로 접는 것은 ffmpeg 가 한다(`-ac 1`). 좌우를 따로 세서 더해도
    포락선은 거의 같고, 그러면 파이썬이 볼 표본이 두 배가 된다.
    """
    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-threads",
        str(ffmpeg_threads),
        "-i",
        str(input_path),
        # 영상 트랙이 붙어 있는 파일(커버 아트가 그렇다)에서 그림을 빼지 않으면
        # 표본 스트림에 섞인다.
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(SAMPLE_RATE),
        "-f",
        "s16le",
        "-",
    ]

    logger.debug("FFMPEG 명령어: %s", " ".join(cmd))

    result = subprocess.run(
        cmd,
        capture_output=True,
        timeout=_FFMPEG_TIMEOUT_SEC,
    )

    if result.returncode != 0:
        stderr = result.stderr.decode("utf-8", "replace").strip()
        raise WaveformError(f"FFMPEG 실패 (코드 {result.returncode}): {stderr}")

    raw = result.stdout
    # 표본 하나가 2바이트다. 홀수로 끝나면 마지막 반 토막을 버린다.
    if len(raw) % 2:
        raw = raw[:-1]

    pcm = array.array("h")
    pcm.frombytes(raw)
    # `array('h')` 는 **네이티브 바이트 순서**다. ffmpeg 에는 `s16le` 를 달라고
    # 했으므로 빅엔디언 기계에서는 뒤집어야 한다.
    if sys.byteorder == "big":
        pcm.byteswap()

    if not pcm:
        raise WaveformError(f"디코딩 결과가 비었습니다: {input_path}")

    return pcm


def _bin_rms(pcm: array.array, samples: int) -> list[float]:
    """칸마다 제곱평균제곱근을 냅니다.

    칸 경계를 곱셈으로 잡는다 — 나눗셈으로 칸 크기를 먼저 정하면 마지막 칸에
    나머지가 몰려 곡 끝이 유독 두꺼워진다.
    """
    total = len(pcm)
    values: list[float] = []

    for index in range(samples):
        start = total * index // samples
        end = total * (index + 1) // samples
        if end <= start:
            # 표본이 칸 수보다 적을 때만 생긴다. 위에서 막고 있지만 여기서도
            # 0 으로 두어 IndexError 대신 평평한 칸이 되게 한다.
            values.append(0.0)
            continue
        segment = pcm[start:end]
        energy = sum(value * value for value in segment)
        values.append((energy / len(segment)) ** 0.5)

    return values


def _normalize(rms: list[float], input_path: str | Path) -> list[float]:
    """가장 큰 칸이 1.0 이 되도록 나눕니다.

    곡 사이의 음량을 비교하는 값이 아니다. 화면은 한 곡의 파형만 그리므로,
    조용한 곡도 화면을 가득 채우는 편이 보기에 낫다. 기기에서 계산하던 예전
    경로도 같은 규칙이었다 (`track-waveform.ts` 의 `normalize`).
    """
    peak = max(rms)
    if peak <= 0:
        raise WaveformError(f"곡 전체가 무음입니다: {input_path}")
    return [round(value / peak, SCALE) for value in rms]
