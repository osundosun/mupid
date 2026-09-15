"""CLI — 손으로 배치를 돌릴 때 쓰는 명령들.

```
music-preprocessing catalog --config config.json --dry-run
music-preprocessing catalog --config config.json
music-preprocessing covers  --config config.json
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

import logging
from pathlib import Path
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
from .logging_setup import default_log_path, setup_logging
from .registrar import Registry

logger = logging.getLogger(__name__)

app = typer.Typer(
    help="음악 스트리밍 전처리 — 청킹(single·batch)과 카탈로그 등록(catalog·covers)",
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
    "아티스트 83/83 · 앨범 204/204 · 곡 1060/1060" 이 그대로 보이는 편이,
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
        result = registrar.run(tracks)

    table = Table(title="카탈로그 등록" + (" (dry-run)" if dry_run else ""), title_style="bold")
    table.add_column("대상")
    table.add_column("만든 것", justify="right")
    table.add_column("전체", justify="right")
    table.add_row("아티스트", str(result["artists_created"]), str(result["artists_total"]))
    table.add_row("앨범", str(result["albums_created"]), str(result["albums_total"]))
    table.add_row("곡", str(result["tracks_created"]), str(result["tracks_total"]))
    table.add_row("커버 업로드", str(result["covers_uploaded"]), "-")
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

    table = Table(title="카탈로그 등록 현황", title_style="bold")
    table.add_column("대상")
    table.add_column("등록됨", justify="right")
    table.add_column("전체", justify="right")
    table.add_column("남음", justify="right")
    for label, done, total in (
        ("아티스트", len(done_artists), len(artist_keys)),
        ("앨범", len(done_albums), len(album_keys)),
        ("곡", len(done_tracks), len(tracks)),
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

    result = run_batch(cfg)

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
        table.add_row("정리(삭제)", str(result.get("stale_cleaned", 0)))
    else:
        table.add_row("성공", f"{result['processed']}")
        table.add_row("  신규", str(result["new_files"]))
        table.add_row("  변경", str(result["modified_files"]))
        table.add_row("실패", str(result["errors"]))
        table.add_row("등록 완료", str(result.get("registered", 0)))
        table.add_row("등록 실패", str(result.get("registration_errors", 0)))
        table.add_row("정리(삭제)", str(result.get("stale_cleaned", 0)))
        table.add_row("총 소요", f"{result['total_time_sec']}초")
        table.add_row("평균", f"{result['avg_time_per_file_sec']}초/파일")
    console.print(table)

    failures = [
        *({"key": e["file"], "error": e["error"]} for e in result.get("error_details", [])),
        *(
            {"key": e["file"], "error": e["error"]}
            for e in result.get("registration_error_details", [])
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
