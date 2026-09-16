"""음원 없이 추천 파이프라인 전체를 한 번 통과시키는 시드 도구.

    곡 적재 → 구간 적재 → id_map → push_sections → 행동 신호 → pull_signals
    → run_labeler → 추천 전후 비교

SeaweedFS 도 음원 파일도 건드리지 않는다. **행만 넣는다.** 추천기는 `id_map` 을 보지
않고 색인 풀 전체에서 곡을 고르고, 응답을 만들 때에야 `im.music_id()` 로 숫자 ID 를
찾는다. 그래서 일부만 매핑해 두면 고른 곡이 매핑에 없어서 404
(`MUSIC_ID_MAPPING_NOT_FOUND`)로 떨어지고 추천 비교 자체가 불가능하다 — 그래서 전량
(곡 1,063 · 구간 10,007)을 넣는다.

## 격리

회차마다 `run_id = E2E_<UUID>` 를 만든다. 이 값은 두 곳에 **문자열 그대로** 들어간다.

  · MySQL `music_section_analysis.external_id` 의 접두사
  · ClickHouse `listen_events.rec_id` (String)

`event_id` 와 `play_id` 는 UUID 타입이라 문자열 run_id 를 담지 못한다. 그래서 생성한
UUID 와 발급된 숫자 ID 를 전부 manifest 에 적어 두고, 정리는 그 목록으로 한다.
`user_section_signals` 에는 run 표식 칸 자체가 없어서(user_id · section_uid · track_id ·
signal · entry_path · occurred_at · play_id · weight) **예약 user_id 대역과 manifest 의
play_id 목록** 으로만 지운다.

## 쓰기 산출물을 공용 디렉터리에 두지 않는 이유

`run_labeler` 는 `labels_service.parquet` 을 통째로 다시 쓴다. 공용 디렉터리를 그대로
쓰면 팀의 산출물이 이 시험의 몇 건짜리 라벨로 덮인다. 그렇다고 515MB 를 회차마다
복사하면 시험이 비현실적으로 무거워진다. 그래서 읽기 전용 색인은 **하드링크**로 걸고
(같은 볼륨이면 관리자 권한이 필요 없다) 쓰기 대상만 실파일로 둔다.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

# ── 예약 대역 ────────────────────────────────────────────────────────────────
# ClickHouse `user_id` 는 UInt32 다. 실제 사용자 번호가 닿지 않을 위쪽 끝을 쓴다.
# 정리할 때 이 대역 전체를 지우므로, 사람 계정이 섞이면 안 된다.
USER_ID_BASE = 4_200_000_000
USER_ID_SPAN = 1_000

RUN_PREFIX = "E2E_"

# 읽기 전용 색인. 하드링크로 건다.
LINK_FILES = (
    "moments.parquet",
    "moments_emb.npy",
    "features_v1.npz",
    "features_info.json",
    "stage1_section.npy",
    "stage1_weights.npz",
    "stage1.pt",
    "content.parquet",
    "flow_meta.parquet",
    "clap_moments.npz",
    "logistic_flow_stage1.json",
)

# 이 회차가 새로 쓰는 것들. 링크하지 않고 빈 채로 둔다.
WRITE_FILES = (
    "labels_service.parquet",
    "id_map.parquet",
    "signals_watermark.json",
    "logs/events_service.jsonl",
)

MYSQL_CONTAINER = os.environ.get("MUPID_MYSQL_CONTAINER", "mupid-dev-mysql")


# ── 작은 유틸 ────────────────────────────────────────────────────────────────

def log(msg: str) -> None:
    print(f"[seed] {msg}", flush=True)


def new_run_id() -> str:
    return RUN_PREFIX + uuid.uuid4().hex[:12]


def mysql(sql: str, *, database: str = "mupid") -> str:
    """`docker exec` 로 MySQL 에 질의한다.

    파이썬용 MySQL 드라이버를 새로 넣지 않으려고 CLI 를 쓴다. 비밀번호는 `MYSQL_PWD`
    환경변수로만 건네고 명령줄에 싣지 않는다 — 명령줄은 컨테이너 안 프로세스 목록에
    그대로 보인다.

    질의는 **표준입력으로 보낸다.** `-e` 인자로 넘기면 Windows 의 명령줄 길이 상한
    (약 32KB)에 걸린다. 구간 10,007건을 덩어리로 넣는 이 도구는 그 한계를 쉽게 넘고,
    그때 나는 오류(WinError 206)는 SQL 과 아무 상관없어 보여서 원인을 찾기 어렵다.
    """
    password = os.environ.get("MUPID_MYSQL_PASSWORD", "")
    user = os.environ.get("MUPID_MYSQL_USER", "root")
    cmd = ["docker", "exec", "-i", "-e", f"MYSQL_PWD={password}", MYSQL_CONTAINER,
           "mysql", "-u", user, "-N", "-B", database]
    out = subprocess.run(cmd, input=sql, capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise RuntimeError(f"MySQL 실패: {out.stderr.strip()[:500]}")
    return out.stdout


def ch(sql: str) -> str:
    """ClickHouse HTTP. 읽기·쓰기 모두 이 하나로 한다."""
    import httpx

    url = os.environ.get("MUPID_CLICKHOUSE_HTTP", "http://localhost:18123")
    headers = {
        "X-ClickHouse-User": os.environ.get("MUPID_CLICKHOUSE_USER", "mupid"),
        "X-ClickHouse-Key": os.environ.get("MUPID_CLICKHOUSE_PASSWORD", ""),
    }
    r = httpx.post(url, content=sql.encode("utf-8"), headers=headers, timeout=120.0)
    if r.status_code != 200 or r.text.startswith("Code:"):
        raise RuntimeError(f"ClickHouse 실패: {r.text.strip()[:500]}")
    return r.text


def sql_str(value: str) -> str:
    """작은따옴표 문자열 리터럴. 백슬래시와 따옴표를 막는다."""
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


# ── manifest ────────────────────────────────────────────────────────────────

@dataclass
class Manifest:
    """이 회차가 만든 것 전부. 정리는 오직 이 기록으로 한다."""

    run_id: str
    user_ids: list[int] = field(default_factory=list)
    music_ids: list[int] = field(default_factory=list)
    artist_ids: list[int] = field(default_factory=list)
    album_ids: list[int] = field(default_factory=list)
    file_ids: list[int] = field(default_factory=list)
    play_ids: list[str] = field(default_factory=list)
    event_ids: list[str] = field(default_factory=list)
    track_ids: list[int] = field(default_factory=list)
    artifact_dir: str = ""

    def path(self) -> Path:
        return Path(self.artifact_dir) / "seed_manifest.json"

    def save(self) -> None:
        p = self.path()
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.__dict__, ensure_ascii=False, indent=1),
                       encoding="utf-8")
        tmp.replace(p)

    @staticmethod
    def load(path: Path) -> "Manifest":
        return Manifest(**json.loads(path.read_text(encoding="utf-8")))


# ── 1. 임시 산출물 디렉터리 ──────────────────────────────────────────────────

def build_artifact_dir(source: Path, target: Path) -> None:
    """읽기 전용은 하드링크, 쓰기 대상은 빈 실파일."""
    src = source / "data" / "proc" / "mupid"
    dst = target / "data" / "proc" / "mupid"
    (dst / "logs").mkdir(parents=True, exist_ok=True)

    linked = 0
    for name in LINK_FILES:
        s = src / name
        if not s.exists():
            continue
        d = dst / name
        if d.exists():
            d.unlink()
        try:
            os.link(s, d)
        except OSError:
            # 볼륨이 다르면 링크가 안 된다. 그때는 읽기 전용이니 복사해도 맞지만,
            # 조용히 넘어가면 회차마다 515MB 가 복사되는 것을 아무도 모른다.
            log(f"하드링크 실패 — 복사로 대신한다: {name}")
            d.write_bytes(s.read_bytes())
        linked += 1

    for name in WRITE_FILES:
        d = dst / name
        d.parent.mkdir(parents=True, exist_ok=True)
        if not d.exists():
            d.touch()
    # 빈 parquet 파일은 읽을 때 터진다. 아직 없다는 뜻이므로 지워 둔다.
    for name in ("labels_service.parquet", "id_map.parquet"):
        p = dst / name
        if p.exists() and p.stat().st_size == 0:
            p.unlink()
    for name in ("signals_watermark.json",):
        p = dst / name
        if p.exists() and p.stat().st_size == 0:
            p.unlink()

    log(f"산출물 디렉터리 준비 — 링크 {linked}개 → {dst}")


# ── 2. MySQL 참조 데이터 ────────────────────────────────────────────────────

def seed_reference_rows(m: Manifest) -> None:
    """`object_storage_files` -> `artists` -> `albums`.

    이 차례를 건너뛸 수 없다. `artists.image_file_id` 와 `albums.image_file_id` 가
    NOT NULL + FK 다. `music.image_file_id` 는 NULL 을 받는다 — 그래서 음원도 커버도
    없이 곡 행만 넣을 수 있다.

    `is_deleted` 는 NOT NULL 인데 기본값이 없다. JPA 엔티티가 값을 채워 주므로 Spring 은
    모르고 지나가지만, 원시 SQL 로 넣을 때는 직접 줘야 한다.
    """
    key = f"e2e/{m.run_id}.jpg"
    file_id = insert_returning_id(
        "INSERT INTO object_storage_files "
        "(file_type, category, original_file_name, storage_object_key, file_size_bytes, mime) "
        f"VALUES ('IMG','ALBUM',{sql_str(m.run_id + '.jpg')},{sql_str(key)},1024,'image/jpeg')")
    m.file_ids.append(file_id)

    artist_id = insert_returning_id(
        "INSERT INTO artists (name, image_file_id, is_deleted) "
        f"VALUES ({sql_str(m.run_id)},{file_id},0)")
    m.artist_ids.append(artist_id)

    album_id = insert_returning_id(
        "INSERT INTO albums (artist_id, title, image_file_id, release_date, is_deleted) "
        f"VALUES ({artist_id},{sql_str(m.run_id)},{file_id},NOW(),0)")
    m.album_ids.append(album_id)

    log(f"참조 데이터 — file={file_id} artist={artist_id} album={album_id}")


def insert_returning_id(sql: str) -> int:
    """INSERT 하고 발급된 번호를 받는다.

    `LAST_INSERT_ID()` 는 **연결마다** 따로 기억된다. `docker exec` 는 부를 때마다 새
    연결이라 따로 부르면 늘 0 이 돌아온다 — 그러면 FK 가 0 을 가리키며 실패하는데, 원인이
    번호 발급이라는 것이 오류 문구에 드러나지 않는다. 한 번의 호출에 둘을 같이 보낸다.
    """
    out = mysql(sql.rstrip(";") + "; SELECT LAST_INSERT_ID();")
    value = out.strip().splitlines()[-1].strip()
    if not value or value == "0":
        raise RuntimeError(f"번호를 받지 못했다: {sql[:80]}")
    return int(value)


def seed_music(m: Manifest, track_ids: list[str]) -> dict[str, int]:
    """곡 행을 넣고 `track_id(문자열) -> music_id(숫자)` 를 돌려준다."""
    artist_id, album_id = m.artist_ids[0], m.album_ids[0]
    rows = []
    for track_id in track_ids:
        title = f"{m.run_id}:{track_id}"
        rows.append(f"({album_id},{artist_id},{sql_str(title)},NOW(),0)")

    # 한 번에 다 넣으면 패킷 상한에 걸린다. 덩어리로 나눈다.
    for i in range(0, len(rows), 500):
        chunk = ",".join(rows[i:i + 500])
        mysql("INSERT INTO music (album_id, artist_id, title, release_date, is_deleted) "
              "VALUES " + chunk)

    out = mysql(
        f"SELECT title, music_id FROM music WHERE title LIKE {sql_str(m.run_id + ':%')}")
    mapping: dict[str, int] = {}
    for line in out.splitlines():
        if not line.strip():
            continue
        title, music_id = line.rsplit("\t", 1)
        mapping[title.split(":", 1)[1]] = int(music_id)

    m.music_ids = sorted(mapping.values())
    log(f"곡 {len(mapping)}건 적재")
    return mapping


def seed_sections(m: Manifest, sections, id_of_track: dict[str, int]) -> int:
    """구간 행을 넣는다.

    Spring API 를 거치지 않는 경로다(0-(a) 의 (b)). 같은 값을 같은 규칙으로 넣는다 —
    `external_id` 는 `<run_id>|<moment_id>` 이고, 이 접두사가 정리의 근거다.
    """
    rows = []
    for row in sections.itertuples():
        external_id = f"{m.run_id}|{row.moment_id}"
        if len(external_id) > 64:
            raise ValueError(f"external_id 가 64자를 넘는다: {external_id}")
        start_ms = int(round(float(row.start_sec) * 1000))
        end_ms = int(round(float(row.end_sec) * 1000))
        if end_ms <= start_ms:
            end_ms = start_ms + 1
        rows.append(
            f"({id_of_track[row.track_id]},'{label_of(row.section_label)}',"
            f"{int(row.section_idx)},{start_ms},{end_ms},{sql_str(external_id)})")

    for i in range(0, len(rows), 1000):
        chunk = ",".join(rows[i:i + 1000])
        mysql("INSERT INTO music_section_analysis "
              "(music_id, section_label, `index`, start_time_ms, end_time_ms, external_id) "
              "VALUES " + chunk)

    log(f"구간 {len(rows)}건 적재")
    return len(rows)


# 색인의 라벨 어휘는 ERD ENUM 과 다르다. 없는 값을 그대로 넣으면 MySQL 이 거절한다.
LABEL_MAP = {
    "intro": "INTRO", "verse": "VERSE", "pre-chorus": "PRE_CHORUS",
    "prechorus": "PRE_CHORUS", "chorus": "CHORUS", "bridge": "BRIDGE",
    "break": "BREAK", "inst": "INST", "solo": "SOLO", "outro": "OUTRO",
    # 위치 기반 라벨(`position_v1`)은 구조 이름이 아니다. 가장 가까운 자리로 옮긴다.
    "early": "VERSE", "mid": "CHORUS", "late": "OUTRO",
}


def label_of(raw: str) -> str:
    return LABEL_MAP.get(str(raw).strip().lower(), "VERSE")


# ── 3. 정리 ────────────────────────────────────────────────────────────────

def cleanup(m: Manifest, *, wait_mutations: bool = True) -> dict:
    """성공·실패와 무관하게 부른다. 여러 번 불러도 안전하다.

    범위 없는 DELETE 와 TRUNCATE 를 쓰지 않는다. 정확한 ID 나 run_id, 예약 대역으로만
    지운다 — 이 시험이 남의 데이터를 지우는 일은 어떤 경우에도 없어야 한다.
    """
    left: dict[str, int] = {}

    # ── ClickHouse. Distributed 테이블은 mutation 을 받지 않으므로 *_local 에 건다.
    users = ",".join(str(u) for u in m.user_ids) or "0"
    plays = ",".join(sql_str(p) for p in m.play_ids)
    conds = [
        ("listen_events_local", f"user_id IN ({users})"),
        ("listen_events_local", f"rec_id LIKE {sql_str(m.run_id + '%')}"),
        ("user_section_signals_local", f"user_id IN ({users})"),
    ]
    # `track_sections` 는 run_id 로 지울 수 없다. `section_uid` 가 색인의 moment_id
    # 그대로이고(그래야 추천기가 찾는다) 접두사가 붙지 않기 때문이다. 대신 `track_id` 가
    # 이 회차에 발급된 숫자 music_id 라 그것으로 좁힌다 — 이 조건을 빠뜨리면 회차마다
    # 10,007행이 쌓이고, 다음 회차가 같은 번호를 다시 발급받으면 딕셔너리가 **옛 구간을**
    # 돌려준다. 그 상태에서도 서비스는 정상처럼 뜬다.
    for i in range(0, len(m.music_ids), 2000):
        chunk = ",".join(str(x) for x in m.music_ids[i:i + 2000])
        conds.append(("track_sections_local", f"track_id IN ({chunk})"))
    if plays:
        conds.append(("user_section_signals_local", f"play_id IN ({plays})"))

    for table, where in conds:
        try:
            ch(f"ALTER TABLE {table} ON CLUSTER mupid_all DELETE WHERE {where}")
        except Exception as e:      # 정리는 한 건 실패해도 나머지를 계속한다
            log(f"삭제 실패({table}): {e}")

    if wait_mutations:
        wait_for_mutations()

    try:
        ch("SYSTEM RELOAD DICTIONARY ON CLUSTER mupid_all dict_track_sections")
    except Exception as e:
        log(f"딕셔너리 갱신 실패: {e}")

    # ── MySQL. FK 역순으로 지운다.
    if m.music_ids:
        ids = ",".join(str(i) for i in m.music_ids)
        mysql(f"DELETE FROM music_section_analysis WHERE music_id IN ({ids})")
        mysql(f"DELETE FROM music WHERE music_id IN ({ids})")
    if m.album_ids:
        mysql(f"DELETE FROM albums WHERE album_id IN ({','.join(map(str, m.album_ids))})")
    if m.artist_ids:
        mysql(f"DELETE FROM artists WHERE artist_id IN ({','.join(map(str, m.artist_ids))})")
    if m.file_ids:
        mysql(f"DELETE FROM object_storage_files "
              f"WHERE file_id IN ({','.join(map(str, m.file_ids))})")

    # ── 파일
    art = Path(m.artifact_dir)
    if art.exists() and art.name.startswith("e2e-"):
        import shutil
        shutil.rmtree(art, ignore_errors=True)

    left.update(verify_clean(m))
    return left


def wait_for_mutations(timeout: float = 120.0) -> None:
    """두 노드의 mutation 이 끝날 때까지 기다린다.

    끝나기 전에 조회하면 지운 행이 아직 보인다. 그 상태로 "잔여 0" 을 확인하면
    확인이 아니라 우연이다.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = ch("SELECT count() FROM clusterAllReplicas('mupid_all', system.mutations) "
                 "WHERE is_done = 0").strip()
        if out in ("0", ""):
            return
        time.sleep(1.0)
    log("mutation 이 시간 안에 끝나지 않았다")


def verify_clean(m: Manifest) -> dict[str, int]:
    """run_id 로 조회했을 때 전부 0 인지."""
    users = ",".join(str(u) for u in m.user_ids) or "0"
    left = {}
    try:
        left["ch_listen_events"] = int(ch(
            f"SELECT count() FROM listen_events WHERE user_id IN ({users}) "
            f"OR rec_id LIKE {sql_str(m.run_id + '%')}").strip() or 0)
        left["ch_user_section_signals"] = int(ch(
            f"SELECT count() FROM user_section_signals WHERE user_id IN ({users})").strip() or 0)
        if m.music_ids:
            ids = ",".join(str(x) for x in m.music_ids)
            left["ch_track_sections"] = int(ch(
                f"SELECT count() FROM track_sections WHERE track_id IN ({ids})").strip() or 0)
        else:
            left["ch_track_sections"] = 0
    except Exception as e:
        log(f"ClickHouse 잔여 확인 실패: {e}")
    try:
        left["mysql_sections"] = int(mysql(
            "SELECT COUNT(*) FROM music_section_analysis "
            f"WHERE external_id LIKE {sql_str(m.run_id + '%')}").strip() or 0)
        left["mysql_music"] = int(mysql(
            f"SELECT COUNT(*) FROM music WHERE title LIKE {sql_str(m.run_id + ':%')}").strip() or 0)
    except Exception as e:
        log(f"MySQL 잔여 확인 실패: {e}")
    left["files"] = int(Path(m.artifact_dir).exists()) if m.artifact_dir else 0
    return left


# ── CLI ─────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--manifest", default=None, help="정리할 회차의 manifest 경로")
    ap.add_argument("--run-id", default=None,
                    help="manifest 없이 run_id 만으로 정리한다(파일은 남는다)")
    ap.add_argument("--artifact-dir", default=None, help="--run-id 와 함께 쓸 임시 디렉터리")
    a = ap.parse_args()

    if a.manifest:
        m = Manifest.load(Path(a.manifest))
    elif a.run_id:
        m = Manifest(run_id=a.run_id, artifact_dir=a.artifact_dir or "")
        # manifest 가 없으면 예약 대역 전체를 대상으로 삼는다. 이 대역은 시험 전용이다.
        m.user_ids = list(range(USER_ID_BASE, USER_ID_BASE + USER_ID_SPAN))
        rows = mysql("SELECT music_id FROM music WHERE title LIKE "
                     + sql_str(a.run_id + ":%"))
        m.music_ids = [int(x) for x in rows.split() if x.strip()]
        rows = mysql("SELECT artist_id FROM artists WHERE name = " + sql_str(a.run_id))
        m.artist_ids = [int(x) for x in rows.split() if x.strip()]
        rows = mysql("SELECT album_id FROM albums WHERE title = " + sql_str(a.run_id))
        m.album_ids = [int(x) for x in rows.split() if x.strip()]
        rows = mysql("SELECT file_id FROM object_storage_files WHERE storage_object_key = "
                     + sql_str(f"e2e/{a.run_id}.jpg"))
        m.file_ids = [int(x) for x in rows.split() if x.strip()]
    else:
        ap.error("--manifest 또는 --run-id 가 필요하다")

    left = cleanup(m)
    print(json.dumps({"run_id": m.run_id, "left": left}, ensure_ascii=False))
    return 0 if all(v == 0 for v in left.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
