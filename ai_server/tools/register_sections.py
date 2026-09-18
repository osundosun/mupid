"""AI 구간 분석 결과 → Spring `music_section_analysis`, 그리고 `id_map.parquet`.

**이것이 없으면 추천 결과가 통째로 버려진다.** 추천 서버는 구간을 문자열
`external_id` 로 가리키고, Spring 이 그 값을 `music_section_analysis` 에서 찾아 곡으로
옮긴다. 표가 비어 있으면 찾을 것이 없어 모든 줄이 버려지고 추천이 0건으로 나온다 —
오류가 아니라 빈 목록이라, 화면은 "추천할 곡이 없다" 로 보인다.

ClickHouse `track_sections` 도 이 단계가 받아 오는 숫자 번호를 쓴다. 그래서
`id_map.parquet` 은 서빙에서 빠졌어도 적재 절차에는 그대로 필요하다.

    moments.parquet ──PUT /api/v1/music/{musicId}/section──> music_section_analysis
                                     │
                                     └─(발급된 번호)──> id_map.parquet

숫자 ID 를 지어내지 않는다
    `track_id` → `music_id` 는 **`--track-map` 으로 받은 표에서만** 읽는다. 문자열을
    해시하거나 순번을 붙이면 그럴듯하게 돌다가 실제 DB 와 어긋난 순간 엉뚱한 곡을
    추천한다 — 이 저장소가 색인 불일치로 두 번 겪은 실패다. 표에 없는 곡은 건너뛰고
    **몇 건을 왜 건너뛰었는지 센다.**

    표 형식(CSV/TSV, 컬럼 이름만 맞으면 된다):
        source_track_id,music_id
        T000148,1001

곡 단위로 통째로 바꾼다
    한 곡의 구간은 분석 한 번의 결과로 함께 정해진다. 구간마다 넣으면 재분석 중간에
    옛 구간과 새 구간이 섞이고, 그 상태로도 조회가 정상으로 보인다.

환경변수

    MUPID_API_BASE_URL       Spring 주소 (`/api/v1` 앞까지)
    MUPID_API_REFRESH_TOKEN  ROLE_SYSTEM 계정의 리프레시 토큰
    MUPID_AI_ARTIFACT_DIR    산출물 루트

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.register_sections
        --track-map track_map.csv
"""
from __future__ import annotations

import argparse
import sys
import urllib.error
from pathlib import Path

import pandas as pd

from ai_server.app import ids, onboarding, paths, recommender
from ai_server.app.log import log
from ai_server.tools import _api_auth, build_id_map
from ai_server.tools.push_sections import SECTION_ENUM

ANALYSIS_VERSION = "v1"
TIMEOUT = 30


def _instruments_module():
    """구간별 악기 계산기. **music_preprocessing 안에 있다.**

    여기서 최상위 import 를 하지 않는 이유: 이 도구는 AI 서버 환경에서 도는데,
    `instruments` 는 `numpy`·`librosa` 를 끌어온다. 분위기만 갱신하려는 실행
    (`--no-instruments`)까지 그 의존성을 요구할 이유가 없다.
    """
    try:
        from music_preprocessing.analysis import instruments as mod
    except ImportError as e:      # pragma: no cover - 환경 문제
        raise SystemExit(
            "악기를 재려면 music_preprocessing 이 필요하다 "
            f"(pip install -e music_preprocessing). 원인: {e}") from None
    return mod

# AI 태그 → Spring 코드표 이름. `MOOD_MAP` 을 뒤집은 것이다.
#
# **번호는 보내지 않는다.** 코드 번호는 MySQL 이 발급하고 이 서버는 모른다 — 이름으로
# 보내면 Spring 이 붙인다. 카탈로그 1,063곡 중 918곡(86%)이 이 표로 옮겨진다. 나머지는
# 분위기 없이 들어가고, 그 자체는 오류가 아니다.
MOOD_BY_TAG: dict[str, str] = {}
for _ko, _tags in onboarding.MOOD_MAP.items():
    for _tag in _tags:
        MOOD_BY_TAG.setdefault(_tag, _ko)


def _tag_list(value) -> list[str]:
    """`flow_meta.mood_tags` 는 쉼표로 이은 문자열이거나 목록이다."""
    if isinstance(value, str):
        return [x.strip() for x in value.split(",") if x.strip()]
    if value is None:
        return []
    return [str(x).strip() for x in value if str(x).strip()]


def moods_of(tags) -> list[str]:
    """곡의 AI 무드 태그 → Spring 코드 이름. 순서를 지키고 중복은 뺀다."""
    out: list[str] = []
    for tag in _tag_list(tags):
        name = MOOD_BY_TAG.get(tag)
        if name and name not in out:
            out.append(name)
    return out


def read_track_map(path: Path) -> dict[str, int]:
    """`source_track_id` → `music_id`. 구분자는 추측한다."""
    df = pd.read_csv(path, sep=None, engine="python", dtype=str)
    missing = [c for c in ("source_track_id", "music_id") if c not in df.columns]
    if missing:
        raise SystemExit(f"컬럼이 없다: {missing} (있는 것: {list(df.columns)})")
    out: dict[str, int] = {}
    for r in df.itertuples(index=False):
        tid = str(r.source_track_id).strip()
        if not tid:
            continue
        try:
            mid = int(str(r.music_id).strip())
        except ValueError:
            raise SystemExit(f"music_id 가 정수가 아니다: {tid} -> {r.music_id!r}") from None
        if mid <= 0:
            raise SystemExit(f"music_id 가 0 이하다: {tid} -> {mid}")
        if out.get(tid, mid) != mid:
            raise SystemExit(f"곡 하나에 music_id 가 둘이다: {tid} -> {out[tid]}, {mid}")
        out[tid] = mid
    if not out:
        raise SystemExit(f"표가 비어 있다: {path}")
    return out


def build_payload(group: pd.DataFrame,
                  moods: list[str] | None = None,
                  instruments: list[list[str]] | None = None) -> tuple[list[dict], dict]:
    """한 곡의 구간 목록. 보낼 수 없는 것은 이유별로 센다.

    `moods` 는 곡 단위다. AI 카탈로그가 구간별 무드를 갖고 있지 않아 곡의 것을 그 곡의
    모든 구간에 같이 붙인다 — 구간별로 갈리면 그때 좁히면 된다.

    `instruments` 는 **구간 단위**다(demucs 스템에서 잰 구간별 악기). `group` 의 행
    순서와 같아야 한다. 없으면 악기 없이 보낸다.

    **둘을 함께 보낸다.** `PUT .../section` 이 곡의 구간을 통째로 갈아 끼우면서
    분위기와 악기를 같은 트랜잭션에서 **둘 다 지우고 다시 넣는다.** 악기만 실어
    보내면 그 요청에 분위기가 없어 **기존 분위기가 사라진다.**
    """
    stat = {"unknown_label": 0, "bad_range": 0, "long_uid": 0}
    rows = []
    ordered = group.sort_values("section_idx") if "section_idx" in group.columns else group
    for i, r in enumerate(ordered.itertuples()):
        label = str(r.section_label).upper()
        if label not in SECTION_ENUM:
            # 추천기는 `other` 를 만들 수 있는데 ERD ENUM 에는 없다. 억지로 다른
            # 라벨에 끼워 넣으면 사용자에게 틀린 구간 유형을 말하게 된다.
            stat["unknown_label"] += 1
            continue
        s_ms, e_ms = ids.to_ms(r.start_sec), ids.to_ms(r.end_sec)
        if e_ms <= s_ms:
            stat["bad_range"] += 1
            continue
        uid = str(r.moment_id)
        if len(uid) > build_id_map.UID_MAX:
            # 컬럼이 VARCHAR(64) 다. 넘기면 MySQL 이 조용히 잘라 저장하고, 잘린
            # 값끼리는 1:1 검사까지 통과해 매핑이 어긋난 채 서비스가 올라온다.
            stat["long_uid"] += 1
            continue
        row = {
            "section_index": int(r.section_idx),
            "external_id": uid,
            "section_label": label,
            "start_time_ms": s_ms,
            "end_time_ms": e_ms,
            "moods": list(moods or []),
        }
        if instruments is not None and i < len(instruments):
            names = [str(x) for x in (instruments[i] or [])]
            if names:
                row["instruments"] = names
        rows.append(row)
    rows.sort(key=lambda x: x["section_index"])
    return rows, stat


def put_sections(base: str, token: str, music_id: int, sections: list[dict],
                 version: str) -> list[dict]:
    """한 곡을 적재하고 발급된 번호 짝을 돌려받는다."""
    body = _api_auth.request_json(
        f"{base}/api/v1/music/{int(music_id)}/section",
        method="PUT", token=token, timeout=TIMEOUT,
        body={"analysis_version": version, "sections": sections})
    return (body.get("data") or {}).get("sections") or []


def instruments_of(group: pd.DataFrame, *, audio_dir: Path, demix_root: Path,
                   mix_rel: float | None = None,
                   cache: dict | None = None) -> tuple[list[list[str]] | None, str]:
    """곡 하나의 **구간별 악기**. `(악기목록, 실패이유)`.

    `group` 의 행 순서(`section_idx` 오름차순)와 같은 순서로 돌려준다 —
    `build_payload` 가 같은 정렬을 쓰므로 인덱스가 맞는다.

    스템이 없으면 `(None, 이유)` 다. **빈 목록으로 넘어가지 않는다** — 그러면
    "악기가 없는 곡" 과 "재지 못한 곡" 이 같아 보인다.

    오디오는 `<audio_dir>/<track_id>.mp3` 다. `cache` 를 주면 곡별 결과를 재사용한다
    (키는 `track_id` — 미매핑 곡의 `music_id` 는 0 이라 키로 쓸 수 없다).
    """
    sub = group.sort_values("section_idx") if "section_idx" in group.columns else group
    tid = str(sub.iloc[0].track_id)
    if cache is not None and tid in cache:
        return cache[tid]
    m = _instruments_module()
    audio = Path(audio_dir) / f"{tid}.mp3"
    if not audio.exists():
        out: tuple = (None, f"음원이 없다: {audio.name}")
    else:
        segs = [m.Segment(label=str(r.section_label), start=float(r.start_sec),
                          end=float(r.end_sec))
                for r in sub.itertuples(index=False)]
        kw = {} if mix_rel is None else {"mix_rel": mix_rel}
        try:
            out = (m.for_track(audio, segs, demix_root=Path(demix_root), **kw), "")
        except m.StemsMissing as e:      # 스템을 못 찾았다. 빈 목록으로 넘어가지 않는다
            out = (None, f"{e}"[:200])
        except Exception as e:           # 오디오를 못 읽는 등
            out = (None, f"{type(e).__name__}: {e}"[:200])
    if cache is not None:
        cache[tid] = out
    return out


def add_instrument_codes(names: set[str], *, dry_run: bool = False) -> list[dict]:
    """악기 이름을 코드표에 넣는다. **구간을 올리기 전에** 해야 한다.

    `CodeType.INSTRUMENT` 는 부트스트랩이 심지 않는다. 서버는 코드표에 없는 악기
    이름을 **버리고 세는데**(구간은 살린다), 그 경고는 서버 로그에만 남으므로
    순서가 뒤집히면 오류 없이 악기만 조용히 빈다. `POST /api/v1/code` 는 멱등이라
    매 배치가 다시 불러도 값이 같다(`add_code` 가 이미 있으면 그 행을 돌려준다).
    """
    if dry_run or not names:
        return []
    added = []
    for name in sorted(names):
        row = _api_auth.request_json(f"{_api_auth.base()}/api/v1/code",
                                     method="POST", token=_api_auth.access_token(),
                                     timeout=TIMEOUT,
                                     body={"code_type": "INSTRUMENT",
                                           "code_name": name})
        d = row.get("data") or row
        if d.get("created"):
            added.append({"code_id": d.get("code_id"), "name": d.get("code_name")})
    return added


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--track-map", required=True,
                    help="source_track_id,music_id 표 (CSV/TSV)")
    ap.add_argument("--moments", default=None,
                    help="기본: <ARTIFACT_DIR>/.../moments.parquet")
    ap.add_argument("--out", default=None,
                    help="기본: <ARTIFACT_DIR>/.../id_map.parquet")
    ap.add_argument("--version", default=ANALYSIS_VERSION,
                    help=f"analysis_version (기본 {ANALYSIS_VERSION})")
    ap.add_argument("--all-sources", action="store_true",
                    help="추천 풀 밖(fma 등)까지 적재한다")
    ap.add_argument("--source", default=None,
                    help="이 출처만 적재한다 (예: flow). 비우면 추천 풀 전량")
    ap.add_argument("--limit", type=int, default=0, help="곡 수 상한 (시험용)")
    ap.add_argument("--dry-run", action="store_true",
                    help="보내지 않고 무엇을 보낼지만 센다")
    ap.add_argument("--force", action="store_true",
                    help="실패한 곡이 있어도 id_map 을 쓴다")
    ap.add_argument("--no-instruments", action="store_true",
                    help="악기를 재지 않고 보낸다 (분위기만 갱신할 때)")
    ap.add_argument("--demix-root", default=None,
                    help="기본: <ARTIFACT_DIR>/work/demix")
    ap.add_argument("--audio-dir", default=None,
                    help="기본: <ARTIFACT_DIR>/flow_music_data/audio")
    ap.add_argument("--mix-rel", type=float, default=None,
                    help="스템 판정 문턱(믹스 대비). 기본 0.1 (-20 dB)")
    a = ap.parse_args()

    src = Path(a.moments) if a.moments else paths.moments_path()
    if not src.exists():
        print(f"구간 파일이 없다: {src}", file=sys.stderr)
        return 2
    mom = pd.read_parquet(src)
    if "source" not in mom.columns:
        mom["source"] = "fma"
    if not a.all_sources:
        # `/internal/ready` 는 **추천 풀** 전량이 매핑됐는지 본다. 풀 밖의 곡까지
        # 넣을 이유가 없고, 넣으면 MySQL 만 커진다.
        mom = mom[mom.source.isin(recommender.POOL_SOURCES)]
    if a.source:
        # 한 출처만. `--limit` 과 함께 쓰면 **시험용으로 flow 곡만** 집을 수 있다 —
        # 풀에는 jamendo 가 앞서 있어(`JAMENDO_*` 가 사전순으로 앞) 그냥 자르면
        # 미매핑 곡만 잡힌다.
        mom = mom[mom.source.astype(str) == a.source]
    if mom.empty:
        print(f"적재할 구간이 없다 (풀 출처 {sorted(recommender.POOL_SOURCES)})",
              file=sys.stderr)
        return 1

    tmap = read_track_map(Path(a.track_map))
    # 곡 분위기. 없으면 분위기 없이 넣는다 — 그 자체는 오류가 아니다.
    meta_path = paths.data_dir() / "flow_meta.parquet"
    mood_of: dict[str, list[str]] = {}
    if meta_path.exists():
        meta = pd.read_parquet(meta_path)
        if "mood_tags" in meta.columns:
            mood_of = {str(r.track_id): moods_of(r.mood_tags)
                       for r in meta.itertuples()}
    else:
        log(f"{meta_path.name} 이 없다 — 분위기 없이 적재한다")

    groups = list(mom.groupby("track_id", sort=True))
    if a.limit:
        groups = groups[:a.limit]

    # 악기 자리. `--no-instruments` 면 계산하지 않는다.
    use_ins = not a.no_instruments
    root = paths.artifact_root()
    # 음원은 **홈 직하** `~/flow_music_data/audio` 다. `<artifact>/flow_music_data`
    # 가 아니다 — 실측으로 확인했다(1,060곡). `--audio-dir` 로 덮어쓸 수 있다.
    default_audio = Path.home() / "flow_music_data" / "audio"
    audio_dir = Path(a.audio_dir) if a.audio_dir else (
        default_audio if default_audio.is_dir() else root / "flow_music_data" / "audio")
    demix_root = Path(a.demix_root) if a.demix_root else (root / "work" / "demix")
    ins_cache: dict = {}
    ins_missing: list[dict] = []
    ins_hits = 0

    stat = {"unknown_label": 0, "bad_range": 0, "long_uid": 0}
    unmapped: list[str] = []
    empty: list[str] = []
    failed: list[str] = []
    pairs: list[dict] = []
    sent = 0

    token = "" if a.dry_run else _api_auth.access_token()
    base = _api_auth.base()

    # ── 코드표를 **먼저** 채운다. 서버는 코드표에 없는 악기를 버리고 세므로,
    #    순서가 뒤집히면 오류 없이 악기만 조용히 빈다.
    if use_ins:
        wanted: set[str] = set()
        # 이 단계도 곡당 수 초라 1,059곡이면 30분 넘게 걸린다. 진행률이 없으면
        # 도는지 멈췄는지 알 수 없다(`ps` 의 CPU 시간을 봐야 알았다).
        total = len(groups)
        for i, (tid, group) in enumerate(groups, 1):
            got, why = instruments_of(group, audio_dir=audio_dir, demix_root=demix_root,
                                      mix_rel=a.mix_rel, cache=ins_cache)
            if got:
                for names in got:
                    wanted.update(names)
            if i % 100 == 0 or i == total:
                log(f"  악기 계산 [{i}/{total}] · 코드 {len(wanted)}종")
        added = add_instrument_codes(wanted, dry_run=a.dry_run)
        log(f"악기 코드 {len(wanted)}종 확인 · 새로 넣음 {len(added)}개"
            + (f" (--dry-run 이라 안 보냄)" if a.dry_run else ""))

    for n, (tid, group) in enumerate(groups, 1):
        tid = str(tid)
        music_id = tmap.get(tid)
        if music_id is None:
            unmapped.append(tid)
            continue
        ins_list = None
        if use_ins:
            ins_list, why = instruments_of(group, audio_dir=audio_dir,
                                           demix_root=demix_root,
                                           mix_rel=a.mix_rel, cache=ins_cache)
            if ins_list is None:
                ins_missing.append({"track_id": tid, "why": why})
            else:
                ins_hits += 1
        sections, s = build_payload(group, mood_of.get(tid), ins_list)
        for k in stat:
            stat[k] += s[k]
        if not sections:
            empty.append(tid)
            continue
        if a.dry_run:
            sent += 1
        else:
            try:
                got = put_sections(base, token, music_id, sections, a.version)
            except urllib.error.HTTPError as e:
                detail = e.read().decode(errors="replace")[:300]
                log(f"적재 실패 {tid} (music_id={music_id}) {e.code}: {detail}")
                failed.append(tid)
                continue
            sent += 1
            for row in got:
                pairs.append({
                    "source_track_id": tid,
                    "music_id": int(music_id),
                    "external_id": str(row["external_id"]),
                    "music_section_analysis_id": int(row["music_section_analysis_id"]),
                })
        # 진행률. 악기 계산이 곡당 수 초라 1,060곡이면 몇 시간이다 — 로그가 없으면
        # 도는지 멈췄는지 알 수 없다. 50곡마다 한 줄.
        if n % 50 == 0 or n == len(groups):
            log(f"  [{n}/{len(groups)}] 적재 {sent} · 구간 {len(pairs)} "
                f"· 악기 {ins_hits} · 실패 {len(failed)}")

    with_mood = sum(1 for t, _ in groups if mood_of.get(str(t)))
    log(f"곡 {len(groups)}개 중 {sent}개 적재 · 구간 {len(pairs)}개 "
        f"· 분위기 있는 곡 {with_mood}개")
    if use_ins:
        log(f"  악기를 잰 곡 {ins_hits}개 · 못 잰 곡 {len(ins_missing)}개")
        for x in ins_missing[:5]:
            log(f"    {x['track_id']}: {x['why']}")
    for k in ("unknown_label", "bad_range", "long_uid"):
        if stat[k]:
            log(f"  제외 구간 {k}={stat[k]}")
    if unmapped:
        log(f"  --track-map 에 없는 곡 {len(unmapped)}개 (예: {unmapped[:5]})")
    if empty:
        log(f"  보낼 구간이 하나도 없던 곡 {len(empty)}개 (예: {empty[:5]})")
    if failed:
        log(f"  적재 실패 {len(failed)}개 (예: {failed[:5]})")

    if a.dry_run:
        log("보내지 않았다 (--dry-run).")
        return 0
    if not pairs:
        print("적재된 구간이 없다. id_map 을 쓰지 않는다.", file=sys.stderr)
        return 1

    dst = Path(a.out) if a.out else paths.id_map_path()
    if failed and dst.exists() and not a.force:
        # 부분 매핑으로 멀쩡한 표를 덮으면 그 곡들이 소리 없이 사라진다.
        print(f"실패한 곡이 {len(failed)}개인데 매핑표가 이미 있다. 덮지 않는다. "
              "고쳐서 다시 돌리거나 --force 를 준다.", file=sys.stderr)
        return 1

    out = pd.DataFrame(pairs)[list(ids.COLS)]
    tracks, sections_n = build_id_map.write_map(out, dst, a.force)
    log(f"저장 {dst} · 곡 {tracks} · 구간 {sections_n}")
    # 하나라도 실패했으면 초록불로 끝내지 않는다. cron 이 알아차려야 한다.
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
