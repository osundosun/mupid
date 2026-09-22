"""CLI — 손으로 배치를 돌릴 때 쓰는 명령들.

```
music-preprocessing catalog --config config.json --dry-run
music-preprocessing catalog --config config.json
music-preprocessing covers  --config config.json
music-preprocessing lyrics  --config config.json
music-preprocessing classify --config config.json
music-preprocessing batch   --config config.json
music-preprocessing status  --config config.json
```

크론도 같은 명령을 쓴다. 다른 점은 둘뿐이다.

- `--no-progress` — 진행률 막대를 끈다 (터미널이 아니면 자동으로 꺼진다)
- `--yes` — 확인 프롬프트를 건너뛴다

## 왜 확인을 묻는가

`catalog` 는 서버에 곡·아티스트·앨범 행을 **만든다.** 실수로 운영 서버를 향해
1060곡을 밀어 넣으면 되돌리는 길이 `DELETE` 를 1060번 부르는 것뿐이다. 그래서
무엇이 어디에 몇 개 만들어지는지 먼저 보여 주고 한 번 묻는다. 크론은 `--yes` 를
붙인다.

## 로그

배치 로그는 화면이 아니라 파일로 간다 (`logs/catalog-20260915-1103.log`).
화면에는 진행률과 마지막 요약, 그리고 경고·오류만 남는다 → `logging_setup.py`.
"""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Annotated

import typer
from rich.console import Console
from rich.progress import (
    BarColumn,
    MofNCompleteColumn,
    Progress,
    SpinnerColumn,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
)
from rich.table import Table

from .api_client import MupidClient
from .audit import ServerAuditError, repair_one
from .batch import build_registrar, run_batch
from .catalog import (
    CatalogError,
    CatalogRegistrar,
    ProgressReporter,
    TrackMeta,
    load_metadata,
)
from .chunker import process_file
from .config import ChunkConfig
from .genre_mood import run as run_classify
from .logging_setup import default_log_path, setup_logging
from .lyrics import LyricRegistrar, LyricSheet, load_lyrics
from .registrar import Registry
from .waveform import WAVEFORM_SAMPLES
from .waveform_backfill import WaveformBackfiller, collect_audio

logger = logging.getLogger(__name__)

app = typer.Typer(
    help="음악 스트리밍 전처리 — 청킹(single·batch)과 카탈로그 등록(catalog·covers·lyrics)",
    no_args_is_help=True,
    add_completion=False,
    rich_markup_mode="rich",
)

# ─────────────────────────────────────────────────────────────
# 공통 옵션
# ─────────────────────────────────────────────────────────────

ConfigOpt = Annotated[
    Path | None,
    typer.Option("--config", "-c", help="JSON 설정 파일 경로", show_default=False),
]
VerboseOpt = Annotated[
    bool, typer.Option("--verbose", "-v", help="DEBUG 까지 남기고 화면에도 전부 찍는다")
]
LogFileOpt = Annotated[
    Path | None,
    typer.Option(
        "--log-file",
        help="로그 파일 경로. 기본은 [cyan]<log_dir>/<명령>-<시각>.log[/]",
        show_default=False,
    ),
]
NoLogOpt = Annotated[
    bool, typer.Option("--no-log-file", help="파일로 남기지 않는다")
]
ProgressOpt = Annotated[
    bool, typer.Option("--progress/--no-progress", help="진행률 막대")
]
YesOpt = Annotated[
    bool, typer.Option("--yes", "-y", help="확인 프롬프트를 건너뛴다 (크론용)")
]
DryRunOpt = Annotated[
    bool,
    typer.Option("--dry-run", help="서버에 붙지 않고 무엇이 만들어질지만 센다"),
]


# ─────────────────────────────────────────────────────────────
# 진행률
# ─────────────────────────────────────────────────────────────


class RichProgress(ProgressReporter):
    """`CatalogRegistrar` 의 단계 보고를 Rich 막대로 그린다.

    단계가 넘어가면 앞 단계 막대는 완료된 채로 남는다. 끝난 뒤 화면에
    "아티스트 87/87 · 앨범 204/204 · 곡 1060/1060" 이 그대로 보이는 편이,
    막대가 사라지고 숫자만 남는 것보다 읽기 좋다.
    """

    def __init__(self, progress: Progress) -> None:
        self._progress = progress
        self._tasks: dict[str, int] = {}

    def phase(self, name: str, total: int) -> None:
        self._tasks[name] = self._progress.add_task(name, total=total)

    def advance(self, name: str, step: int = 1) -> None:
        task = self._tasks.get(name)
        if task is not None:
            self._progress.advance(task, step)


def _progress_bar(console: Console, enabled: bool) -> Progress:
    return Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description:<12}"),
        BarColumn(),
        MofNCompleteColumn(),
        TaskProgressColumn(),
        TimeElapsedColumn(),
        console=console,
        disable=not enabled,
    )


# ─────────────────────────────────────────────────────────────
# 명령 — 카탈로그
# ─────────────────────────────────────────────────────────────


@app.command()
def catalog(
    config: ConfigOpt = None,
    metadata_dir: Annotated[
        Path | None, typer.Option(help="곡별 메타데이터 JSON 디렉터리", show_default=False)
    ] = None,
    covers_dir: Annotated[
        Path | None, typer.Option(help="곡별 커버 이미지 디렉터리", show_default=False)
    ] = None,
    api_base_url: Annotated[
        str | None, typer.Option(help="Mupid API 루트", show_default=False)
    ] = None,
    limit: Annotated[
        int | None,
        typer.Option(
            help="앞에서 N곡만 등록한다. 처음 붙일 때 몇 곡으로 시험하는 용도",
            show_default=False,
        ),
    ] = None,
    dry_run: DryRunOpt = False,
    yes: YesOpt = False,
    show_progress: ProgressOpt = True,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """곡별 메타데이터·커버를 읽어 아티스트·앨범·곡을 등록한다."""
    console = _start("catalog", log_file, no_log_file, config, verbose)
    cfg, tracks = _load(config, metadata_dir, covers_dir, api_base_url, limit)

    _describe(console, cfg, tracks, dry_run)
    _confirm(console, yes, dry_run, f"곡 {len(tracks)}개를 {cfg.api_base_url} 에 등록합니다.")

    with _progress_bar(console, show_progress and not console.is_jupyter) as bar:
        registrar = _registrar(cfg, dry_run, RichProgress(bar))
        # 서버에 없는 곡을 먼저 찾아 예외로 남긴다. 이걸 건너뛰면 지문이 같은 곡은
        # 서버를 아예 안 부르므로 삭제된 곡을 영원히 모른다 (S15P21A204-399).
        try:
            audit = registrar.audit()
        except CatalogError as e:
            Console(stderr=True).print(f"[red]✖[/] {e}")
            raise typer.Exit(1) from None
        result = registrar.run(tracks, audit)
        if audit is not None:
            result["audit"] = audit.as_report()

    _report_audit(console, result.get("audit"))
    table = Table(title="카탈로그 등록" + (" (dry-run)" if dry_run else ""), title_style="bold")
    table.add_column("대상")
    table.add_column("새로", justify="right")
    table.add_column("수정", justify="right")
    table.add_column("전체", justify="right")
    for label, created, updated, total in (
        ("아티스트", "artists_created", "artists_updated", "artists_total"),
        ("앨범", "albums_created", "albums_updated", "albums_total"),
        ("곡", "tracks_created", "tracks_updated", "tracks_total"),
    ):
        changed = result[updated]
        table.add_row(
            label,
            str(result[created]),
            str(changed) if changed else "[dim]0[/]",
            str(result[total]),
        )
    table.add_row("커버 업로드", str(result["covers_uploaded"]), "-", "-")
    if result["tracks_exempt"]:
        table.add_row("서버에 없어 건너뜀", "[yellow]-[/]", "[yellow]-[/]", str(result["tracks_exempt"]))
    console.print(table)
    if result["covers_missing"]:
        console.print(
            f"[yellow]커버 없는 곡 {result['covers_missing']}개[/] — 화면은 앨범 커버를 쓴다"
        )
    _report_errors(console, result)


@app.command()
def covers(
    config: ConfigOpt = None,
    metadata_dir: Annotated[
        Path | None, typer.Option(help="곡별 메타데이터 JSON 디렉터리", show_default=False)
    ] = None,
    covers_dir: Annotated[
        Path | None, typer.Option(help="곡별 커버 이미지 디렉터리", show_default=False)
    ] = None,
    api_base_url: Annotated[
        str | None, typer.Option(help="Mupid API 루트", show_default=False)
    ] = None,
    force: Annotated[
        bool, typer.Option("--force", help="내용이 그대로여도 다시 올린다")
    ] = False,
    dry_run: DryRunOpt = False,
    yes: YesOpt = False,
    show_progress: ProgressOpt = True,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """이미 등록된 아티스트·앨범·곡의 커버만 다시 올린다."""
    console = _start("covers", log_file, no_log_file, config, verbose)
    cfg, tracks = _load(config, metadata_dir, covers_dir, api_base_url, None)

    console.print(f"곡 [bold]{len(tracks)}[/]개 · 커버 [cyan]{cfg.covers_dir}[/]")
    if force:
        console.print("[yellow]--force[/] — 바뀌지 않은 커버도 전부 다시 올린다")
    _confirm(console, yes, dry_run, f"커버를 {cfg.api_base_url} 에 다시 올립니다.")

    with _progress_bar(console, show_progress and not console.is_jupyter) as bar:
        registrar = _registrar(cfg, dry_run, RichProgress(bar))
        result = registrar.refresh_covers(tracks, force=force)

    table = Table(title="커버 갱신" + (" (dry-run)" if dry_run else ""), title_style="bold")
    table.add_column("대상")
    table.add_column("갱신", justify="right")
    table.add_row("아티스트", str(result["artists_refreshed"]))
    table.add_row("앨범", str(result["albums_refreshed"]))
    table.add_row("곡", str(result["tracks_refreshed"]))
    table.add_row("업로드", str(result["covers_uploaded"]))
    console.print(table)
    if result["covers_missing"]:
        console.print(f"[yellow]커버 파일 없음 {result['covers_missing']}개[/]")
    if result["skipped_unregistered"]:
        console.print(
            f"[yellow]미등록이라 건너뜀 {result['skipped_unregistered']}개[/]"
            " — `catalog` 를 먼저 돌리세요"
        )
    _report_errors(console, result)


@app.command()
def lyrics(
    config: ConfigOpt = None,
    metadata_dir: Annotated[
        Path | None,
        typer.Option(help="곡별 메타데이터 JSON 디렉터리 (가사도 여기 있다)", show_default=False),
    ] = None,
    api_base_url: Annotated[
        str | None, typer.Option(help="Mupid API 루트", show_default=False)
    ] = None,
    skip_untimed: Annotated[
        bool,
        typer.Option(
            "--skip-untimed",
            help="시각을 모르는 줄을 채우지 않고 버린다 (기본은 앞뒤 사이를 고르게 메운다)",
        ),
    ] = False,
    limit: Annotated[
        int | None,
        typer.Option(help="앞에서 N곡만 올린다. 시험용", show_default=False),
    ] = None,
    force: Annotated[
        bool, typer.Option("--force", help="내용이 그대로여도 지우고 다시 올린다")
    ] = False,
    dry_run: DryRunOpt = False,
    yes: YesOpt = False,
    show_progress: ProgressOpt = True,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """곡별 가사를 시각과 함께 등록한다. [b]catalog 를 먼저 돌려야 한다.[/]"""
    console = _start("lyrics", log_file, no_log_file, config, verbose)
    cfg, sheets = _load_lyrics(config, metadata_dir, api_base_url, skip_untimed, limit)

    total_lines = sum(len(s) for s in sheets.values())
    interpolated = sum(s.interpolated for s in sheets.values())
    dropped = sum(s.dropped for s in sheets.values())

    info = Table(show_header=False, box=None, padding=(0, 2, 0, 0))
    info.add_row("메타데이터", f"[cyan]{cfg.metadata_dir}[/]")
    info.add_row(
        "등록 대상",
        "[dim]없음 (dry-run)[/]" if dry_run else f"[cyan]{cfg.api_base_url}[/]",
    )
    info.add_row("규모", f"곡 {len(sheets)} · 줄 {total_lines}")
    if skip_untimed:
        info.add_row("시각 없는 줄", f"[yellow]{dropped}줄을 버린다[/] (--skip-untimed)")
    else:
        info.add_row("시각 없는 줄", f"{interpolated}줄을 앞뒤 사이로 메운다")
    console.print(info)
    if dry_run:
        console.print("[yellow]dry-run[/] — 아무것도 올리지 않고 무엇이 올라갈지만 셉니다.")

    _confirm(
        console,
        yes,
        dry_run,
        f"가사 {total_lines}줄을 {cfg.api_base_url} 에 올립니다"
        f" (곡마다 기존 가사를 지우고 다시 씁니다).",
    )

    with _progress_bar(console, show_progress and not console.is_jupyter) as bar:
        registrar = LyricRegistrar(
            client=None if dry_run else _client(cfg),
            registry=Registry(cfg.api_registry_log),
            dry_run=dry_run,
            progress=RichProgress(bar),
        )
        result = registrar.run(sheets, force=force)

    table = Table(title="가사 등록" + (" (dry-run)" if dry_run else ""), title_style="bold")
    table.add_column("항목")
    table.add_column("값", justify="right")
    table.add_row("등록한 곡", str(result["songs_registered"]))
    table.add_row("그대로라 건너뜀", str(result["songs_unchanged"]))
    table.add_row("올린 줄", str(result["lines_written"]))
    table.add_row("  파일에 시각 있음", str(result["lines_timed"]))
    table.add_row("  시각을 메움", str(result["lines_interpolated"]))
    if result["lines_dropped"]:
        table.add_row("  시각 없어 버림", str(result["lines_dropped"]))
    if result["lines_deleted"]:
        table.add_row("지운 줄(재등록)", str(result["lines_deleted"]))
    console.print(table)

    for key, message in (
        ("songs_empty", "가사 줄이 없는 곡"),
        ("songs_untimed", "시각을 아는 줄이 없어 건너뛴 곡"),
        ("songs_unregistered", "곡이 아직 등록되지 않아 건너뜀"),
    ):
        if result[key]:
            hint = " — `catalog` 를 먼저 돌리세요" if key == "songs_unregistered" else ""
            console.print(f"[yellow]{message} {result[key]}개[/]{hint}")

    _report_errors(console, result)


@app.command()
def waveform(
    config: ConfigOpt = None,
    input_dir: Annotated[
        Path | None,
        typer.Option("--input-dir", "-I", help="원본 음원 디렉터리", show_default=False),
    ] = None,
    api_base_url: Annotated[
        str | None, typer.Option(help="Mupid API 루트", show_default=False)
    ] = None,
    samples: Annotated[
        int, typer.Option(help="파형 칸 수. 화면이 그리는 막대는 많아야 160개다")
    ] = WAVEFORM_SAMPLES,
    ffmpeg_threads: Annotated[int, typer.Option(help="파일당 FFMPEG 스레드 수")] = 2,
    limit: Annotated[
        int | None,
        typer.Option(help="앞에서 N곡만 올린다. 시험용", show_default=False),
    ] = None,
    force: Annotated[
        bool, typer.Option("--force", help="이미 파형이 있는 곡도 다시 계산해 올린다")
    ] = False,
    dry_run: DryRunOpt = False,
    yes: YesOpt = False,
    show_progress: ProgressOpt = True,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """이미 등록된 곡의 파형만 채운다. [b]청킹은 하지 않는다.[/]"""
    console = _start("waveform", log_file, no_log_file, config, verbose)

    if config:
        cfg = ChunkConfig.from_file(str(config))
    else:
        cfg = ChunkConfig(ffmpeg_threads=ffmpeg_threads)
    if input_dir:
        cfg.input_dir = str(input_dir)
    if api_base_url:
        cfg.api_base_url = api_base_url

    source = Path(cfg.input_dir)
    if not source.is_dir():
        console.print(f"[red]✖[/] 입력 디렉터리가 없습니다: [cyan]{source}[/]")
        raise typer.Exit(1)

    audio_paths = collect_audio(source)
    if limit:
        audio_paths = audio_paths[:limit]
    if not audio_paths:
        console.print(f"[yellow]음원이 없습니다: {source}[/]")
        return

    info = Table(show_header=False, box=None, padding=(0, 2, 0, 0))
    info.add_row("음원", f"[cyan]{source}[/]")
    info.add_row(
        "등록 대상",
        "[dim]없음 (dry-run)[/]" if dry_run else f"[cyan]{cfg.api_base_url}[/]",
    )
    info.add_row("규모", f"곡 {len(audio_paths)} · 칸 {samples}")
    console.print(info)
    if force:
        console.print("[yellow]--force[/] — 이미 파형이 있는 곡도 전부 다시 올린다")
    if dry_run:
        console.print("[yellow]dry-run[/] — 서버에 붙지 않아 이미 올라간 곡을 가리지 못합니다.")

    _confirm(
        console,
        yes,
        dry_run,
        f"곡 {len(audio_paths)}개의 파형을 {cfg.api_base_url} 에 올립니다.",
    )

    with _progress_bar(console, show_progress and not console.is_jupyter) as bar:
        backfiller = WaveformBackfiller(
            client=None if dry_run else _client(cfg),
            registry=Registry(cfg.api_registry_log),
            samples=samples,
            ffmpeg_threads=cfg.ffmpeg_threads,
            dry_run=dry_run,
            progress=RichProgress(bar),
        )
        result = backfiller.run(audio_paths, force=force)

    table = Table(title="파형 등록" + (" (dry-run)" if dry_run else ""), title_style="bold")
    table.add_column("항목")
    table.add_column("값", justify="right")
    table.add_row("올린 곡", str(result["uploaded"]))
    table.add_row("이미 있어 건너뜀", str(result["skipped_present"]))
    if result["skipped_gone"]:
        table.add_row("서버에 없어 건너뜀", str(result["skipped_gone"]))
    console.print(table)

    if result["skipped_unregistered"]:
        console.print(
            f"[yellow]미등록이라 건너뜀 {result['skipped_unregistered']}개[/]"
            " — `catalog` · `batch` 를 먼저 돌리세요"
        )

    _report_errors(console, result)


@app.command()
def classify(
    config: ConfigOpt = None,
    metadata_dir: Annotated[
        Path | None,
        typer.Option(help="곡별 메타데이터 JSON 디렉터리 (장르·무드도 여기 있다)", show_default=False),
    ] = None,
    taxonomy: Annotated[
        Path | None,
        typer.Option(help="장르·무드 어휘 파일. 비우면 메타데이터 옆에서 찾는다", show_default=False),
    ] = None,
    api_base_url: Annotated[
        str | None, typer.Option(help="Mupid API 루트", show_default=False)
    ] = None,
    report: Annotated[
        Path | None,
        typer.Option(help="실행 보고서 경로. 기본은 [cyan]<log_dir>/classify-<시각>.json[/]", show_default=False),
    ] = None,
    check: Annotated[
        bool,
        typer.Option("--check", help="서버까지 읽어 무엇이 바뀔지만 본다 (쓰지 않는다)"),
    ] = False,
    replace_existing: Annotated[
        bool,
        typer.Option(
            "--replace-existing",
            help="이미 다른 분류가 있는 곡도 덮어쓴다 (기본은 건너뛰고 보고만 한다)",
        ),
    ] = False,
    skip_mood_approximations: Annotated[
        bool,
        typer.Option("--skip-mood-approximations", help="초기 10곡의 무드 근사치를 쓰지 않는다"),
    ] = False,
    dry_run: DryRunOpt = False,
    yes: YesOpt = False,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """곡별 장르·분위기를 서버에 등록한다. [b]catalog 를 먼저 돌려야 한다.[/]

    관리자 API 를 쓰지 않는다 — `POST /api/v1/code` 와
    `PUT /api/v1/music/{id}/classification` 만 부른다. 자세한 것은
    `genre_mood.py` 의 머리말과 `GENRE_MOOD.md`.
    """
    console = _start("classify", log_file, no_log_file, config, verbose)
    try:
        cfg = ChunkConfig.from_file(str(config)) if config else ChunkConfig()
        if metadata_dir:
            cfg.metadata_dir = str(metadata_dir)
        if taxonomy:
            cfg.genre_taxonomy_file = str(taxonomy)
        if api_base_url:
            cfg.api_base_url = api_base_url
    except (OSError, TypeError, ValueError) as e:
        Console(stderr=True).print(f"[red]✖[/] {e}")
        raise typer.Exit(1) from None

    if not dry_run and not cfg.api_base_url:
        Console(stderr=True).print(
            "[red]✖[/] api_base_url 이 비어 있습니다. 설정 파일이나 --api-base-url 로"
            " 등록할 서버를 지정하세요. (원본만 확인하려면 --dry-run)"
        )
        raise typer.Exit(1)

    # 보고서는 실행마다 새 파일이다. 도구가 기존 파일을 덮어쓰지 않는다.
    path = Path(report) if report else default_log_path(cfg.log_dir, "classify").with_suffix(".json")
    path.parent.mkdir(parents=True, exist_ok=True)

    info = Table(show_header=False, box=None, padding=(0, 2, 0, 0))
    info.add_row("메타데이터", f"[cyan]{cfg.metadata_dir}[/]")
    info.add_row("어휘", f"[cyan]{cfg.taxonomy_path}[/]")
    info.add_row("등록 기록", f"[cyan]{cfg.api_registry_log}[/]")
    info.add_row(
        "등록 대상",
        "[dim]없음 (dry-run)[/]" if dry_run else f"[cyan]{cfg.api_base_url}[/]",
    )
    info.add_row("보고서", f"[cyan]{path}[/]")
    console.print(info)
    if dry_run:
        console.print("[yellow]dry-run[/] — 서버에 붙지 않고 원본만 확인합니다.")
    elif check:
        console.print("[yellow]--check[/] — 서버를 읽기만 하고 아무것도 쓰지 않습니다.")

    _confirm(
        console,
        yes,
        dry_run or check,
        f"곡별 장르·분위기를 {cfg.api_base_url} 에 등록합니다"
        + (" (기존 분류가 있는 곡도 덮어씁니다)." if replace_existing else "."),
    )

    # `genre_mood` 는 argparse 로도 돌아간다. 같은 함수에 같은 모양을 넘긴다.
    options = SimpleNamespace(
        snapshot=None,
        source_dir=None,
        metadata_dir=cfg.metadata_dir,
        taxonomy=str(cfg.taxonomy_path),
        registry=cfg.api_registry_log,
        report=str(path),
        api_base_url="" if dry_run else cfg.api_base_url,
        token_file=None,
        refresh_token_file=cfg.api_refresh_token_file,
        apply=not (dry_run or check),
        replace_existing=replace_existing,
        skip_mood_approximations=skip_mood_approximations,
        quiet=True,
    )
    try:
        run_classify(options)
    except (OSError, RuntimeError, TypeError, ValueError) as e:
        # 서버 오류 본문에는 토큰이 섞일 수 있다. 종류와 상태만 남긴다.
        detail = f"HTTP {e.status} ({type(e).__name__})" if hasattr(e, "status") else str(e)
        Console(stderr=True).print(f"[red]✖[/] {detail}  (보고서: {path})")
        raise typer.Exit(1) from None

    result = json.loads(path.read_text(encoding="utf-8"))
    table = Table(title="장르·분위기 등록" + (" (dry-run)" if dry_run else ""), title_style="bold")
    table.add_column("항목")
    table.add_column("값", justify="right")
    table.add_row("원본 곡", str(result["summary"]["tracks"]))
    table.add_row("고유 장르 / 분위기",
                  f"{result['summary']['distinct_genres']} / {result['summary']['distinct_moods']}")
    if "missing_codes" in result:
        table.add_row("추가할 코드", str(len(result["missing_codes"])))
    if "pending" in result:
        table.add_row("분류가 비어 있던 곡", str(result["pending"]["empty"]))
        table.add_row("이미 다른 분류가 있던 곡", str(result["pending"]["differs"]))
    table.add_row("이번에 쓴 건수", str(len(result["created"])))
    table.add_row("상태", result["status"])
    console.print(table)

    held = result.get("held") or {}
    if held and not replace_existing:
        console.print(
            f"[yellow]이미 다른 분류가 있어 건너뛴 곡 {len(held)}개[/]"
            " — 보고서의 `held` 를 확인하세요."
            " 원본으로 덮어쓰려면 --replace-existing."
        )


def _report_audit(console: Console, audit: dict | None) -> None:
    """서버 대조 결과를 화면에 남긴다. **누락 곡은 빠짐없이** 보여 준다.

    첫 곡에서 멈추면 나머지를 알 수 없다는 것이 이 티켓의 요구사항이다
    (`S15P21A204-399`).
    """
    if not audit:
        return
    missing = audit.get("missing") or []
    mismatch = audit.get("title_mismatch") or []
    if not missing and not mismatch:
        return

    if missing:
        console.print(
            f"[yellow]서버에 없는 곡 {len(missing)}개[/] "
            f"(대조 {audit['checked']}곡 · 서버 {audit['server_total']}곡) — 건너뜁니다:"
        )
        verdicts = audit.get("verdicts") or {}
        why = {
            "deleted": "서버에서 삭제됨",
            "stale_record": "서버에 그런 번호 없음 — 기록이 낡음",
        }
        for item in missing:
            verdict = verdicts.get(item["track_id"])
            console.print(
                f"  · [yellow]{item['track_id']}[/] "
                f"(music_id={item['music_id']}, {item['title']!r})"
                + (f" — {why[verdict]}" if verdict in why else "")
            )
        # 왜 없는지에 따라 다음 사람이 할 일이 다르다. 판정별로 안내를 나눈다.
        if any(verdicts.get(i["track_id"]) == "stale_record" for i in missing):
            console.print(
                "[dim]  번호가 어긋난 곡은 등록 기록을 다시 맞추세요: "
                "`music-preprocessing repair-track <track_id>` (미리보기, "
                "`--apply` 로 실제 반영).[/]"
            )
        if any(verdicts.get(i["track_id"]) == "deleted" for i in missing):
            console.print(
                "[dim]  서버에서 삭제된 곡인지 확인하려면: "
                "SELECT music_id, is_deleted FROM music WHERE music_id=<번호>  ·  "
                "원본을 지울지 곡을 되살릴지는 사람이 정합니다.[/]"
            )
        if not verdicts:
            console.print(
                "[dim]  서버에서 지워졌는지 확인하려면: "
                "SELECT music_id, is_deleted FROM music WHERE music_id=<번호>  ·  "
                "원본을 지울지 곡을 되살릴지는 사람이 정합니다.[/]"
            )
        console.print(
            "[dim]  등록 기록에서 그 곡만 걷어내려면 "
            "`music-preprocessing repair-track <track_id>`.[/]"
        )

    if audit.get("alive_but_unlisted"):
        # 목록에 없는데 단건 조회는 살아 있다고 답한 곡. 표식을 붙이지 않았다.
        console.print(
            f"[yellow]곡 목록에는 없는데 살아 있는 곡 "
            f"{len(audit['alive_but_unlisted'])}개[/] — 목록이 낡았을 수 있습니다. "
            "표식을 붙이지 않았으니 다음 실행이 다시 봅니다: "
            + ", ".join(i["track_id"] for i in audit["alive_but_unlisted"])
        )
    if mismatch:
        console.print(
            f"[red]곡명이 어긋난 곡 {len(mismatch)}개[/] — 등록 기록이 다른 곡을 "
            "가리키고 있습니다. 분류 등록(PUT)이 엉뚱한 곡의 값을 지울 수 있어 "
            "한 건도 쓰지 않았습니다:"
        )
        for item in mismatch:
            console.print(
                f"  · [red]{item['track_id']}[/] (기록 {item['title']!r} / "
                f"서버 {item.get('server_title', item.get('server_music_ids'))!r})"
            )
    if audit.get("recovered"):
        console.print(
            f"[green]서버에 다시 나타난 곡 {len(audit['recovered'])}개[/] — "
            "예외 표식을 지웠습니다: " + ", ".join(audit["recovered"])
        )


@app.command("repair-track")
def repair_track(
    track_id: Annotated[
        str,
        typer.Argument(help="고칠 곡. 예: T000957"),
    ],
    config: ConfigOpt = None,
    api_base_url: Annotated[
        str | None, typer.Option(help="Mupid API 루트", show_default=False)
    ] = None,
    apply: Annotated[
        bool,
        typer.Option("--apply", help="실제로 고친다. 없으면 무엇을 할지만 보여 준다"),
    ] = False,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """등록 기록에서 곡 하나만 걷어내거나 다시 맞춘다.

    `registered.json` **전체를 지우지 않는다.** 서버에서 사라진 곡 하나 때문에
    1060곡의 등록 결과를 버리면 전부 다시 만들게 된다.

    서버에 그 곡이 없는 경우:

    - 그 곡의 `catalog.tracks` 항목과 청킹 기록(`tracks`)을 걷어낸다.
      그래야 다음 실행이 그 곡을 "모르는 채" 시작하지 않는다.
    - 예외 표식도 함께 지운다 — 등록 기록 자체가 없으면 표식이 가리킬 것이 없다.
    - 딸린 스트림 번호를 보여 준다. 조회로는 확인할 수 없다(곡이 없으면
      `GET /music/{id}/stream` 이 410 이다) — `registered.json` 에 남은 기록이다.

    서버에 그 곡이 있는 경우:

    - 낡은 매핑만 그 곡 기준으로 다시 맞춘다(`PATCH`).

    **원본 오디오·메타데이터 파일은 건드리지 않는다.** 서버 곡을 되살릴지
    주피터의 원본을 지울지는 사람이 정한다.
    """
    console = _start("repair-track", log_file, no_log_file, config, verbose)
    try:
        cfg = ChunkConfig.from_file(str(config)) if config else ChunkConfig()
        if api_base_url:
            cfg.api_base_url = api_base_url
    except (OSError, TypeError, ValueError) as e:
        Console(stderr=True).print(f"[red]✖[/] {e}")
        raise typer.Exit(1) from None

    if not cfg.api_base_url:
        Console(stderr=True).print(
            "[red]✖[/] api_base_url 이 필요합니다 — 서버 상태를 봐야 고칠 수 있습니다."
        )
        raise typer.Exit(1)

    if not re.fullmatch(r"T\d{6}", track_id):
        # 오타를 그대로 받아 넘기면 엉뚱한 곡을 고친다. 형식을 먼저 막는다.
        Console(stderr=True).print(f"[red]✖[/] 곡 ID 형식이 아닙니다: {track_id!r} (예: T000957)")
        raise typer.Exit(1)

    registry = Registry(cfg.api_registry_log)
    try:
        client = _client(cfg)
        summary = repair_one(client, registry, track_id, apply=apply)
    except (CatalogError, ServerAuditError, OSError, RuntimeError, TypeError, ValueError) as e:
        Console(stderr=True).print(f"[red]✖[/] {e}")
        raise typer.Exit(1) from None

    table = Table(title=f"곡 복구 {track_id}" + ("" if apply else " (미리보기)"), title_style="bold")
    table.add_column("항목")
    table.add_column("값")
    why = {
        "deleted": "서버에서 삭제됨 (410 Gone)",
        "stale_record": "서버에 그런 곡 번호 없음 (404) — 기록이 낡음",
    }
    rows = [
        ("서버에 있는가", "on_server"),
        ("기록된 music_id", "music_id"),
        ("기록된 곡명", "title"),
        ("정리할 스트림", "stream_ids"),
        ("걷어낼 청킹 기록", "chunk_entries"),
        ("예외 표식", "exempt"),
    ]
    if summary.get("verdict") in why:
        # 왜 없는지 — 410(삭제) 과 404(기록이 낡음) 는 다음에 할 일이 다르다.
        rows.insert(1, ("왜 없는가", "verdict"))
    for label, key in rows:
        value = summary.get(key)
        if key == "verdict":
            value = why.get(value, value)
        if isinstance(value, list):
            value = ", ".join(str(v) for v in value) if value else "-"
        table.add_row(label, str(value))
    console.print(table)

    if summary["on_server"]:
        console.print(
            "[green]서버에 있습니다.[/] 낡은 매핑만 다시 맞춥니다."
            + ("" if apply else " [yellow]--apply[/] 를 주면 실제로 고칩니다.")
        )
    elif summary.get("verdict") == "stale_record":
        # 404 — 서버가 이 번호를 모른다. 삭제된 것이 아니라 기록이 낡은 것이라
        # 곡을 걷어내면 멀쩡한 등록 기록을 버린다.
        console.print(
            "[red]서버에 그런 곡 번호가 없습니다(404).[/] 삭제된 것이 아니라 "
            "등록 기록의 곡 번호가 어긋난 것입니다 — 서버 곡 번호를 다시 맞추세요. "
            "`registered.json` 의 `music_id` 를 고친 뒤 다시 돌리는 편이 "
            "걷어내는 것보다 안전합니다."
        )
        if not apply:
            console.print(
                "  그래도 이 곡을 기록에서 걷어내려면 [yellow]--apply[/] 를 주세요."
            )
    else:
        console.print(
            "[yellow]서버에 없습니다.[/] 등록 기록에서 이 곡만 걷어냅니다 "
            "(원본 파일은 그대로 둡니다)."
        )
        if summary["stream_ids"]:
            console.print(
                "[dim]  남은 스트림은 조회로 확인할 수 없습니다(곡이 없으면 "
                "`GET /music/{id}/stream` 이 410). 정말 지우려면: "
                + " ".join(f"DELETE /api/v1/stream/{sid}" for sid in summary["stream_ids"])
                + "[/]"
            )
        if not apply:
            console.print("  [yellow]--apply[/] 를 주면 실제로 고칩니다.")
    if summary["changed"]:
        console.print("[green]등록 기록을 고쳤습니다.[/]")
    elif apply:
        console.print("[dim]고칠 것이 없었습니다.[/]")


@app.command()
def status(
    config: ConfigOpt = None,
    metadata_dir: Annotated[
        Path | None, typer.Option(help="곡별 메타데이터 JSON 디렉터리", show_default=False)
    ] = None,
    covers_dir: Annotated[
        Path | None, typer.Option(help="곡별 커버 이미지 디렉터리", show_default=False)
    ] = None,
) -> None:
    """무엇이 등록됐고 무엇이 남았는지 센다. 서버에 붙지 않는다."""
    console = _start("status", None, True, config, False)
    cfg, tracks = _load(config, metadata_dir, covers_dir, None, None)
    registry = Registry(cfg.api_registry_log)

    artist_keys = {t.artist_key for t in tracks}
    album_keys = {t.album_key for t in tracks}
    done_artists = artist_keys & registry.catalog("artists").keys()
    done_albums = album_keys & registry.catalog("albums").keys()
    done_tracks = {t.track_id for t in tracks} & registry.catalog("tracks").keys()
    no_cover = sum(
        1
        for entry in registry.catalog("tracks").values()
        if entry.get("image_file_id") is None
    )

    # 가사는 곡 1060개 중 있는 것만 센다. 연주곡을 분모에 넣으면 영영 안 찬다.
    with_lyrics = {
        t.track_id
        for t in tracks
        if _has_lyrics(Path(cfg.metadata_dir) / f"{t.track_id}.json")
    }
    done_lyrics = with_lyrics & registry.catalog("lyrics").keys()
    lyric_lines = sum(
        entry.get("line_count") or 0 for entry in registry.catalog("lyrics").values()
    )

    table = Table(title="카탈로그 등록 현황", title_style="bold")
    table.add_column("대상")
    table.add_column("등록됨", justify="right")
    table.add_column("전체", justify="right")
    table.add_column("남음", justify="right")
    for label, done, total in (
        ("아티스트", len(done_artists), len(artist_keys)),
        ("앨범", len(done_albums), len(album_keys)),
        ("곡", len(done_tracks), len(tracks)),
        ("가사", len(done_lyrics), len(with_lyrics)),
    ):
        remaining = total - done
        table.add_row(
            label,
            str(done),
            str(total),
            f"[green]0[/]" if remaining == 0 else f"[yellow]{remaining}[/]",
        )
    console.print(table)
    console.print(f"등록 기록: [cyan]{cfg.api_registry_log}[/]")
    if lyric_lines:
        console.print(f"등록된 가사 줄: [bold]{lyric_lines}[/]")
    if no_cover:
        console.print(
            f"[yellow]커버 없이 등록된 곡 {no_cover}개[/]"
            " — 커버를 놓고 `covers` 를 돌리면 이어 붙는다"
        )


# ─────────────────────────────────────────────────────────────
# 명령 — 청킹
# ─────────────────────────────────────────────────────────────


@app.command()
def single(
    input: Annotated[Path, typer.Option("--input", "-i", help="입력 파일 경로")],
    output: Annotated[
        Path, typer.Option("--output", "-o", help="출력 디렉터리")
    ] = Path("./output"),
    bitrate: Annotated[str, typer.Option("--bitrate", "-b")] = "128k",
    segment_time: Annotated[int, typer.Option(help="세그먼트 길이 초")] = 6,
    frame_duration: Annotated[int, typer.Option(help="Opus 프레임 길이 ms")] = 20,
    workers: Annotated[int, typer.Option(help="병렬 워커 수 (0=CPU 코어 수)")] = 0,
    ffmpeg_threads: Annotated[int, typer.Option(help="파일당 FFMPEG 스레드 수")] = 2,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """파일 하나를 Opus 청크 + RTP 로 쪼갠다."""
    console = _start("single", log_file, no_log_file, None, verbose)
    cfg = ChunkConfig(
        bitrate=bitrate,
        segment_time=segment_time,
        frame_duration=frame_duration,
        max_workers=workers,
        ffmpeg_threads=ffmpeg_threads,
    )
    with console.status(f"{input.name} 처리 중…"):
        result = process_file(str(input), str(output), cfg)

    table = Table(title=f"청킹 완료 — {input.name}", title_style="bold")
    table.add_column("항목")
    table.add_column("값", justify="right")
    table.add_row("청크 수", str(result["chunk_count"]))
    table.add_row("RTP 패킷 수", str(result["total_rtp_packets"]))
    table.add_row("처리 시간", f"{result['processing_time_sec']}초")
    console.print(table)


@app.command()
def analysis(
    config: ConfigOpt = None,
    local: Annotated[
        bool,
        typer.Option(
            "--local",
            help="~/flow_music_data 에서 직접 대상·태그를 모은다 (요구 5 조회 API 를 부르지 않는다)",
        ),
    ] = True,
    flow_dir: Annotated[
        Path | None, typer.Option(help="음원·metadata 루트 (기본: ~/flow_music_data)", show_default=False)
    ] = None,
    all_tracks: Annotated[
        bool,
        typer.Option("--all", help="색인에 있는 곡까지 전부 돌린다 (재분석·태그 backfill)"),
    ] = False,
    allin1_dir: Annotated[
        Path | None,
        typer.Option(
            "--allin1-dir",
            help="① 원본 JSON 디렉터리(All-In-One). 비우면 <artifact>/flow/allin1",
            show_default=False,
        ),
    ] = None,
    demix_dir: Annotated[
        Path | None,
        typer.Option(
            "--demix-dir",
            help="구간별 악기를 읽을 demucs 스템. 비우면 <artifact>/work/demix",
            show_default=False,
        ),
    ] = None,
    sections_dir: Annotated[
        Path | None,
        typer.Option(
            help="변환된 sections.json 디렉터리. 비우면 <work>/sections", show_default=False
        ),
    ] = None,
    segmenter_python: Annotated[
        Path | None,
        typer.Option(
            "--segmenter-python",
            help="① 분할·②③④ 를 돌릴 3.10 인터프리터. 비우면 config 값을 쓴다",
            show_default=False,
        ),
    ] = None,
    limit: Annotated[
        int,
        typer.Option(help="--local 에서 돌릴 곡 수 상한. 0(기본)이면 전부"),
    ] = 0,
    data_dir: Annotated[
        Path | None, typer.Option(help="색인 디렉터리 (기본: $MUPID_AI_ARTIFACT_DIR/...)", show_default=False)
    ] = None,
    device: Annotated[
        str | None, typer.Option(help="cuda:0 · cpu. 비우면 있는 쪽", show_default=False)
    ] = None,
    keep: Annotated[
        int | None, typer.Option(help="남길 이전 색인 판 수", show_default=False)
    ] = None,
    force_unlock: Annotated[
        bool, typer.Option("--force-unlock", help="남아 있는 잠금을 지우고 시작한다")
    ] = False,
    yes: Annotated[
        bool, typer.Option("--yes", "-y", help="확인 프롬프트를 건너뛴다 (크론용)")
    ] = False,
    verbose: VerboseOpt = False,
) -> None:
    """구간 분석을 돌린다 — ① 분할 → ②③④ 특징 → ⑤ 색인 → ⑥ 등록·전환.

    **청킹(`batch`)과 다른 단계다.** ①은 All-In-One(conda `mupid-ai`, 3.10)이 필요하고
    GPU 를 오래 잡는다. 그래서 독립 도구로 두되 `run_batch.sh` 의 **맨 뒤**에 걸어
    스케줄이 "언제"를 정하게 한다.

    `--local` 이 기본이다 — 분석 대상과 숫자 ID 매핑을 `~/flow_music_data` 와
    `registered.json` 에서 얻는다. 요구 5(분석 대상 조회)를 부를 이유가 없다.
    설계는 `ai_server/docs/ANALYSIS-BATCH.md`.

    `--yes` 는 cron·`run_batch.sh` 용이다. **이 명령은 아무것도 묻지 않지만 인자를
    받아 둔다** — `run_batch.sh` 가 `run()` 헬퍼로 `--yes` 를 붙여 부르므로, 없으면
    `No such option` 으로 죽는다(실제로 그렇게 죽었다).
    """
    from .analysis import batch as ab

    argv: list[str] = []
    if config:
        argv += ["--config", str(config)]
    if local:
        argv.append("--local")
    if flow_dir:
        argv += ["--flow-dir", str(flow_dir)]
    if all_tracks:
        argv.append("--all")
    if allin1_dir:
        argv += ["--allin1-dir", str(allin1_dir)]
    if demix_dir:
        argv += ["--demix-dir", str(demix_dir)]
    if sections_dir:
        argv += ["--sections-dir", str(sections_dir)]
    if segmenter_python:
        argv += ["--segmenter-python", str(segmenter_python)]
    if limit:
        argv += ["--limit", str(limit)]
    if data_dir:
        argv += ["--data-dir", str(data_dir)]
    if device:
        argv += ["--device", str(device)]
    if keep is not None:
        argv += ["--keep", str(keep)]
    if force_unlock:
        argv.append("--force-unlock")
    # 분석 배치는 자기 로그·보고를 스스로 남긴다. `run_batch.sh` 가 종료 코드만 본다.
    raise SystemExit(ab.main(argv))


@app.command()
def batch(
    config: ConfigOpt = None,
    input_dir: Annotated[
        Path | None, typer.Option("--input-dir", "-I", show_default=False)
    ] = None,
    output_dir: Annotated[
        Path | None, typer.Option("--output-dir", "-O", show_default=False)
    ] = None,
    api_base_url: Annotated[
        str | None, typer.Option(help="Mupid API 루트", show_default=False)
    ] = None,
    bitrate: Annotated[str, typer.Option("--bitrate", "-b")] = "128k",
    segment_time: Annotated[int, typer.Option(help="세그먼트 길이 초")] = 6,
    frame_duration: Annotated[int, typer.Option(help="Opus 프레임 길이 ms")] = 20,
    workers: Annotated[int, typer.Option(help="병렬 워커 수 (0=CPU 코어 수)")] = 0,
    ffmpeg_threads: Annotated[int, typer.Option(help="파일당 FFMPEG 스레드 수")] = 2,
    processed_log: Annotated[
        Path | None, typer.Option(help="처리 완료 로그", show_default=False)
    ] = None,
    orphan_check: Annotated[
        bool,
        typer.Option(
            "--orphan-check/--no-orphan-check",
            help="끝나고 서버에 쓰이지 않는 스트림이 남았는지 훑는다 (읽기만 한다)",
        ),
    ] = True,
    verify_chunks: Annotated[
        bool,
        typer.Option(
            "--verify-chunks",
            help="성한 스트림까지 청크 행 수를 대조한다. 곡 수만큼 호출이 더 나간다",
        ),
    ] = False,
    log_file: LogFileOpt = None,
    no_log_file: NoLogOpt = False,
    verbose: VerboseOpt = False,
) -> None:
    """입력 디렉터리를 통째로 청킹하고, 스트림·청크를 등록한다."""
    console = _start("batch", log_file, no_log_file, config, verbose)

    if config:
        cfg = ChunkConfig.from_file(str(config))
    else:
        cfg = ChunkConfig(
            bitrate=bitrate,
            segment_time=segment_time,
            frame_duration=frame_duration,
            max_workers=workers,
            ffmpeg_threads=ffmpeg_threads,
        )
    for name, given in (
        ("input_dir", input_dir),
        ("output_dir", output_dir),
        ("processed_log", processed_log),
        ("api_base_url", api_base_url),
    ):
        if given:
            setattr(cfg, name, str(given))

    result = run_batch(cfg, orphan_check=orphan_check, verify_chunks=verify_chunks)

    if result["status"] == "no_input":
        console.print("[yellow]입력 디렉터리가 비어 있습니다.[/]")
        return

    table = Table(title="배치 청킹", title_style="bold")
    table.add_column("항목")
    table.add_column("값", justify="right")
    if result["status"] == "up_to_date":
        table.add_row("처리할 파일", "없음")
        table.add_row("등록 재시도", str(result.get("registration_retried", 0)))
        table.add_row("등록 실패", str(result.get("registration_errors", 0)))
        table.add_row("파형 재등록", str(result.get("waveform_retried", 0)))
        table.add_row("파형 실패", str(result.get("waveform_errors", 0)))
        table.add_row("정리(삭제)", str(result.get("stale_cleaned", 0)))
    else:
        table.add_row("성공", f"{result['processed']}")
        table.add_row("  신규", str(result["new_files"]))
        table.add_row("  변경", str(result["modified_files"]))
        table.add_row("실패", str(result["errors"]))
        table.add_row("등록 완료", str(result.get("registered", 0)))
        table.add_row("등록 실패", str(result.get("registration_errors", 0)))
        table.add_row("파형 재등록", str(result.get("waveform_retried", 0)))
        table.add_row("파형 실패", str(result.get("waveform_errors", 0)))
        table.add_row("정리(삭제)", str(result.get("stale_cleaned", 0)))
        table.add_row("총 소요", f"{result['total_time_sec']}초")
        table.add_row("평균", f"{result['avg_time_per_file_sec']}초/파일")
    console.print(table)
    _report_orphans(console, result.get("orphans"))

    failures = [
        *({"key": e["file"], "error": e["error"]} for e in result.get("error_details", [])),
        *(
            {"key": e["file"], "error": e["error"]}
            for e in result.get("registration_error_details", [])
        ),
        *(
            {"key": e["file"], "error": e["error"]}
            for e in result.get("waveform_error_details", [])
        ),
    ]
    _report_errors(console, {"errors": failures})


# ─────────────────────────────────────────────────────────────
# 뒷일
# ─────────────────────────────────────────────────────────────


def _start(
    command: str,
    log_file: Path | None,
    no_log_file: bool,
    config: Path | None,
    verbose: bool,
) -> Console:
    """로그를 열고 화면 콘솔을 만든다."""
    path: Path | None = None
    if not no_log_file:
        log_dir = "./logs"
        if config and Path(config).exists():
            try:
                log_dir = ChunkConfig.from_file(str(config)).log_dir
            except Exception:  # noqa: BLE001 — 설정 오류는 뒤에서 제대로 알린다
                pass
        path = Path(log_file) if log_file else default_log_path(log_dir, command)

    console = setup_logging(path, verbose=verbose)
    if path is not None:
        console.print(f"[dim]로그: {path}[/]")
    return console


def _load(
    config: Path | None,
    metadata_dir: Path | None,
    covers_dir: Path | None,
    api_base_url: str | None,
    limit: int | None,
) -> tuple[ChunkConfig, list[TrackMeta]]:
    """설정을 읽고 메타데이터를 불러온다. 경로가 틀리면 여기서 끝낸다."""
    try:
        cfg = ChunkConfig.from_file(str(config)) if config else ChunkConfig()
        # 설정 파일보다 명령줄에 직접 준 값이 우선한다.
        for name, given in (
            ("metadata_dir", metadata_dir),
            ("covers_dir", covers_dir),
            ("api_base_url", api_base_url),
        ):
            if given:
                setattr(cfg, name, str(given))
        tracks = load_metadata(Path(cfg.metadata_dir))
    except (CatalogError, OSError, TypeError, ValueError) as e:
        Console(stderr=True).print(f"[red]✖[/] {e}")
        raise typer.Exit(1) from None

    if limit is not None:
        tracks = tracks[:limit]
    return cfg, tracks


def _has_lyrics(metadata_path: Path) -> bool:
    """그 곡에 가사가 있는가. 파일을 통째로 읽되 값 하나만 본다."""
    try:
        with open(metadata_path, encoding="utf-8") as f:
            return bool(json.load(f).get("lyrics_sync"))
    except (OSError, ValueError):
        return False


def _load_lyrics(
    config: Path | None,
    metadata_dir: Path | None,
    api_base_url: str | None,
    skip_untimed: bool,
    limit: int | None,
) -> tuple[ChunkConfig, dict[str, LyricSheet]]:
    """설정을 읽고 가사를 불러온다. 경로가 틀리면 여기서 끝낸다."""
    try:
        cfg = ChunkConfig.from_file(str(config)) if config else ChunkConfig()
        for name, given in (
            ("metadata_dir", metadata_dir),
            ("api_base_url", api_base_url),
        ):
            if given:
                setattr(cfg, name, str(given))
        sheets = load_lyrics(Path(cfg.metadata_dir), skip_untimed=skip_untimed)
    except (CatalogError, OSError, TypeError, ValueError) as e:
        Console(stderr=True).print(f"[red]✖[/] {e}")
        raise typer.Exit(1) from None

    if limit is not None:
        sheets = {key: sheets[key] for key in sorted(sheets)[:limit]}
    return cfg, sheets


def _registrar(
    cfg: ChunkConfig, dry_run: bool, progress: ProgressReporter
) -> CatalogRegistrar:
    client = None if dry_run else _client(cfg)
    return CatalogRegistrar(
        client=client,
        registry=Registry(cfg.api_registry_log),
        covers_dir=Path(cfg.covers_dir),
        dry_run=dry_run,
        progress=progress,
    )


def _client(cfg: ChunkConfig) -> MupidClient:
    """API 클라이언트를 만든다. 등록할 서버가 없으면 여기서 멈춘다."""
    try:
        registrar = build_registrar(cfg)
    except RuntimeError as e:
        Console(stderr=True).print(f"[red]✖[/] {e}")
        raise typer.Exit(1) from None
    if registrar is None:
        Console(stderr=True).print(
            "[red]✖[/] api_base_url 이 비어 있습니다. 설정 파일이나 --api-base-url 로"
            " 등록할 서버를 지정하세요. (무엇이 만들어질지만 보려면 --dry-run)"
        )
        raise typer.Exit(1)
    return registrar.client


def _describe(
    console: Console, cfg: ChunkConfig, tracks: list[TrackMeta], dry_run: bool
) -> None:
    """무엇을 어디에 올릴지 먼저 보여 준다."""
    artists = {t.artist_key for t in tracks}
    albums = {t.album_key for t in tracks}

    table = Table(show_header=False, box=None, padding=(0, 2, 0, 0))
    table.add_row("메타데이터", f"[cyan]{cfg.metadata_dir}[/]")
    table.add_row("커버", f"[cyan]{cfg.covers_dir}[/]")
    table.add_row("등록 대상", "[dim]없음 (dry-run)[/]" if dry_run else f"[cyan]{cfg.api_base_url}[/]")
    table.add_row("등록 기록", f"[cyan]{cfg.api_registry_log}[/]")
    table.add_row(
        "규모", f"아티스트 {len(artists)} · 앨범 {len(albums)} · 곡 {len(tracks)}"
    )
    console.print(table)
    if dry_run:
        console.print("[yellow]dry-run[/] — 아무것도 올리지 않고 무엇이 만들어질지만 셉니다.")


def _confirm(console: Console, yes: bool, dry_run: bool, what: str) -> None:
    """서버를 바꾸는 명령 앞에서 한 번 묻는다. dry-run 과 --yes 는 묻지 않는다."""
    if dry_run or yes:
        return
    console.print()
    if not typer.confirm(f"{what} 계속할까요?", default=False):
        console.print("[dim]취소했습니다.[/]")
        raise typer.Exit(130)


def _report_orphans(console: Console, report: dict | None) -> None:
    """쓰이지 않는 스트림 점검 결과. **경고일 뿐 종료 코드를 바꾸지 않는다.**"""
    if report is None:
        return

    rows = [
        ("배치가 모르는 스트림", report["unknown"]),
        ("겹치는 스트림", report["duplicate"]),
        ("청크 수 불일치", report["count_mismatch"]),
        ("청크 모자람", report["short"]),
    ]
    found = sum(len(items) for _, items in rows)
    if not found:
        console.print(
            f"[dim]스트림 점검: 곡 {report['music_checked']}개 · "
            f"스트림 {report['streams_seen']}개 — 쓰이지 않는 것 없음[/]"
        )
        return

    console.print()
    table = Table(
        title=f"쓰이지 않는 스트림 {found}건 (지우지 않았습니다)",
        title_style="bold yellow",
    )
    table.add_column("종류")
    table.add_column("건수", justify="right")
    table.add_column("예시 stream_id")
    for label, items in rows:
        if not items:
            continue
        sample = ", ".join(
            str(item.get("stream_id") or item.get("stream_ids")) for item in items[:3]
        )
        table.add_row(label, str(len(items)), sample + (" …" if len(items) > 3 else ""))
    console.print(table)
    console.print(
        "[yellow]확인 후[/] [cyan]DELETE /api/v1/stream/{streamId}[/] [yellow]로 치우세요."
        " 자세한 목록은 로그 파일에.[/]"
    )


def _report_errors(console: Console, result: dict) -> None:
    """실패 목록을 찍고 종료 코드를 맞춘다.

    크론이 읽는 것은 종료 코드다. 한 건이라도 실패했으면 0 으로 끝내지 않는다.
    """
    errors = result.get("errors") or []
    if not errors:
        return
    console.print()
    table = Table(title=f"실패 {len(errors)}건", title_style="bold red")
    table.add_column("대상", style="yellow", no_wrap=True)
    table.add_column("사유")
    for err in errors[:20]:
        table.add_row(str(err["key"]), str(err["error"]))
    console.print(table)
    if len(errors) > 20:
        console.print(f"[dim]… 그 밖에 {len(errors) - 20}건. 자세한 사유는 로그 파일에.[/]")
    raise typer.Exit(1)
