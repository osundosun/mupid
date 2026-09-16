"""GPU 메타데이터의 장르·무드만 기존 곡에 연결하는 독립 실행 도구.

python -m music_preprocessing.genre_mood --help
기존 catalog/batch 및 registered.json은 수정하지 않는다.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlencode, urlsplit

from .api_client import MupidClient
from .mood_approximations import MOOD_APPROXIMATIONS, SOURCE as APPROXIMATION_SOURCE


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


# SSH stdin으로만 전달한다. 경로는 JSON 입력이며 셸 명령에 보간하지 않는다.
REMOTE_READER = r'''
import json, pathlib, sys
settings = json.loads(sys.stdin.readline())
root = pathlib.Path(settings['source_dir']).expanduser()
registry = json.loads(pathlib.Path(settings['registry']).expanduser().read_text())
tracks = []
for p in sorted((root / 'metadata').glob('T*.json')):
    raw = json.loads(p.read_text())
    tracks.append({'filename': p.name, 'track_id': raw.get('track_id'),
                   'title': raw.get('flow_title'), 'genre': raw.get('genre'),
                   'mood': raw.get('mood')})
print(json.dumps({'version': 1, 'metadata': tracks,
 'taxonomy': json.loads((root / 'genre_mood_taxonomy.json').read_text()),
 'registry': registry.get('catalog', {}).get('tracks', {})}, ensure_ascii=False))
'''


def fetch(args):
    if args.ssh_host.startswith("-") or not re.fullmatch(r"[\w.@:-]+", args.ssh_host):
        raise ValueError("잘못된 SSH 호스트")
    command = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15"]
    if args.ssh_key:
        command += ["-i", args.ssh_key]
    command += [args.ssh_host, "python3 -"]
    if args.wsl_distro:
        command = ["wsl", "-d", args.wsl_distro, "--"] + command
    # python - 는 stdin 전체를 코드로 읽으므로 설정도 코드 안의 JSON 리터럴로 전달.
    settings = json.dumps({"source_dir": args.source_dir, "registry": args.registry})
    source = REMOTE_READER.replace(
        "settings = json.loads(sys.stdin.readline())", f"settings = json.loads({settings!r})"
    )
    result = subprocess.run(command, input=source, text=True, encoding="utf-8",
                            capture_output=True, timeout=120, check=True)
    snapshot = json.loads(result.stdout)
    save_snapshot(snapshot, args.output)


def local_snapshot(args):
    root = Path(args.source_dir).expanduser()
    registry = read_json(Path(args.registry).expanduser())
    metadata = []
    for path in sorted((root / "metadata").glob("T*.json")):
        raw = read_json(path)
        metadata.append({"filename": path.name, "track_id": raw.get("track_id"),
                         "title": raw.get("flow_title"), "genre": raw.get("genre"),
                         "mood": raw.get("mood")})
    save_snapshot({"version": 1, "metadata": metadata,
                   "taxonomy": read_json(root / "genre_mood_taxonomy.json"),
                   "registry": registry.get("catalog", {}).get("tracks", {})}, args.output)


def save_snapshot(snapshot, output):
    plan = validate(snapshot)
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # 기존 스냅샷을 실수로 덮어쓰지 않는다.
    with destination.open("x", encoding="utf-8") as stream:
        json.dump(snapshot, stream, ensure_ascii=False, indent=2)
    print(json.dumps({"snapshot": str(destination), **summary(plan)}, ensure_ascii=False))


def positive_id(value):
    if type(value) is not int or value <= 0:
        raise ValueError(f"잘못된 ID: {value!r}")
    return value


def validate(snapshot):
    if snapshot.get("version") != 1:
        raise ValueError("지원하지 않는 스냅샷 버전")
    taxonomy = snapshot["taxonomy"]
    vocab = {}
    for kind in ("genres", "moods"):
        entries = taxonomy[kind]
        names = [entry["name"] for entry in entries]
        if not names or any(not isinstance(n, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", n) for n in names):
            raise ValueError(f"잘못된 {kind} 어휘")
        if len(set(names)) != len(names):
            raise ValueError(f"중복 {kind} 어휘")
        if any(type(e["track_count"]) is not int or e["track_count"] < 0 for e in entries):
            raise ValueError(f"잘못된 {kind} 개수")
        vocab[kind] = {e["name"]: e["track_count"] for e in entries}
    tracks, seen_ids, seen_music = [], set(), set()
    counts = {"genres": Counter(), "moods": Counter()}
    null_moods = []
    for raw in snapshot["metadata"]:
        track_id = raw["track_id"]
        if not isinstance(track_id, str) or not re.fullmatch(r"T\d{6}", track_id):
            raise ValueError(f"잘못된 track_id: {track_id!r}")
        if raw["filename"] != track_id + ".json" or track_id in seen_ids:
            raise ValueError(f"파일명 불일치 또는 중복: {track_id}")
        seen_ids.add(track_id)
        registered = snapshot["registry"].get(track_id)
        if not registered:
            raise ValueError(f"기존 등록 기록 없음: {track_id}")
        music_id = positive_id(registered["music_id"])
        if music_id in seen_music:
            raise ValueError(f"다른 트랙이 같은 music_id에 연결됨: {music_id}")
        seen_music.add(music_id)
        if not raw.get("title") or raw["title"] != registered.get("title"):
            raise ValueError(f"원본/등록 기록 곡명 불일치: {track_id}")
        genre = raw["genre"]
        if genre not in vocab["genres"]:
            raise ValueError(f"알 수 없는 장르: {track_id}: {genre}")
        counts["genres"][genre] += 1
        moods = []
        mood = raw["mood"]
        if mood is None:
            null_moods.append(track_id)
        else:
            if not isinstance(mood, dict) or not mood.get("primary"):
                raise ValueError(f"잘못된 mood 객체: {track_id}")
            for name in [mood["primary"], mood.get("secondary")]:
                if name is None:
                    continue
                if name not in vocab["moods"]:
                    raise ValueError(f"알 수 없는 무드: {track_id}: {name}")
                counts["moods"][name] += 1  # taxonomy는 primary+secondary 등장 횟수
                if name not in moods:
                    moods.append(name)
        tracks.append({"track_id": track_id, "music_id": music_id, "title": raw["title"],
                       "genre": genre, "moods": moods})
    if not tracks:
        raise ValueError("메타데이터가 비어 있음")
    for kind in vocab:
        if dict(counts[kind]) != vocab[kind]:
            raise ValueError(f"원본과 taxonomy {kind}별 개수 불일치")
        singular = kind[:-1]
        if taxonomy.get(singular + "_total_distinct") != len(vocab[kind]):
            raise ValueError(f"taxonomy {kind} 고유값 개수 불일치")
    if sorted(null_moods) != sorted(taxonomy["tracks_without_mood"]):
        raise ValueError("taxonomy의 무드 결측 트랙 목록 불일치")
    return tracks


def summary(tracks):
    return {"tracks": len(tracks), "genre_relations": len(tracks),
            "mood_tracks": sum(bool(t["moods"]) for t in tracks),
            "mood_relations": sum(len(t["moods"]) for t in tracks),
            "distinct_genres": len({t["genre"] for t in tracks}),
            "distinct_moods": len({m for t in tracks for m in t["moods"]})}


def supplement_moods(tracks, snapshot):
    """검증된 원본의 결측만 보완한다. 원본 트랙·taxonomy는 변경하지 않는다."""
    allowed = {entry["name"] for entry in snapshot["taxonomy"]["moods"]}
    result, provenance = [], []
    for track in tracks:
        entry = MOOD_APPROXIMATIONS.get(track["track_id"])
        if entry is None or track["moods"]:
            result.append(dict(track))
            continue
        genre, primary, secondary, evidence = entry
        if track["genre"] != genre:
            raise ValueError(f"근사치 대상의 장르 불일치: {track['track_id']}")
        if primary not in allowed or secondary not in allowed:
            raise ValueError(f"근사치 무드가 원본 어휘에 없음: {track['track_id']}")
        moods = list(dict.fromkeys([primary, secondary]))
        result.append({**track, "moods": moods, "mood_confidence": "approx"})
        provenance.append({"track_id": track["track_id"], "music_id": track["music_id"],
                           "genre": genre, "primary": primary, "secondary": secondary,
                           "confidence": "approx", "source": APPROXIMATION_SOURCE,
                           "evidence": evidence})
    return result, provenance


class AdminClient(MupidClient):
    """기존 인증 구현을 재사용하되 POST 자동 재시도는 하지 않는다."""

    def __init__(self, base_url, token_file=None, refresh_token_file=None):
        parsed = urlsplit(base_url)
        if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
            raise ValueError("잘못된 API 주소")
        if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("관리자 인증은 HTTPS 또는 로컬 주소에서만 사용 가능")
        self.bearer = Path(token_file).read_text().strip() if token_file else None
        refresh = Path(refresh_token_file).read_text().strip() if refresh_token_file else ""
        if not self.bearer and not refresh:
            raise ValueError("관리자 토큰 파일 필요")
        super().__init__(base_url, refresh, token_file=refresh_token_file, max_retries=1)

    def _ensure_access_token(self, force=False):
        return self.bearer or super()._ensure_access_token(force=force)

    def rows(self, resource):
        result, page, total = [], 0, None
        while True:
            params = urlencode({"page": page, "size": 200, "direction": "asc"})
            data = self._request("GET", f"/api/v1/admin/resource/{resource}?{params}")["data"]
            if total is not None and total != data["total"]:
                raise ValueError(f"조회 중 행 수 변경: {resource}; 재실행 필요")
            total = data["total"]
            result.extend(data["content"])
            page += 1
            if page >= data["pages"]:
                break
            if not data["content"]:
                raise ValueError(f"빈 페이지: {resource}")
        if len(result) != total:
            raise ValueError(f"조회 개수 불일치: {resource}")
        return result

    def create(self, resource, body):
        if resource not in ("code", "musicGenre", "musicMood"):
            raise ValueError("허용되지 않은 쓰기 대상")
        return self._request("POST", f"/api/v1/admin/resource/{resource}", json_body=body)["data"]


def inspect(client, tracks):
    codes = {}
    code_rows = client.rows("code")
    for row in code_rows:
        key = (row["codeType"], row["codeName"])
        if key[0] not in ("GENRE", "MOOD"):
            continue
        if key in codes:
            raise ValueError(f"서버에 중복 코드 존재: {key}")
        codes[key] = positive_id(row["codeId"])
    music = {positive_id(row["musicId"]): row for row in client.rows("music")}
    for track in tracks:
        row = music.get(track["music_id"])
        if not row or row.get("title") != track["title"]:
            raise ValueError(f"서버 곡 누락/곡명 불일치: {track['track_id']}")
    expected = {"musicGenre": set(), "musicMood": set()}
    for t in tracks:
        expected["musicGenre"].add((t["music_id"], "GENRE", t["genre"]))
        expected["musicMood"].update((t["music_id"], "MOOD", mood) for mood in t["moods"])
    reverse = {value: key for key, value in codes.items()}
    target_ids = {t["music_id"] for t in tracks}
    missing = {}
    existing = {}
    for resource, field in (("musicGenre", "genreCode"), ("musicMood", "moodCode")):
        relations = set()
        for row in client.rows(resource):
            music_id = positive_id(row["music"])
            code_id = positive_id(row[field])
            if (music_id, code_id) in relations:
                raise ValueError(f"중복 관계: {resource}")
            relations.add((music_id, code_id))
            if music_id in target_ids:
                key = reverse.get(code_id)
                if key is None or (music_id, *key) not in expected[resource]:
                    raise ValueError(f"원본에 없는 기존 관계: {resource} music_id={music_id}; 자동 삭제하지 않음")
        existing[resource] = relations
        missing[resource] = sorted(item for item in expected[resource]
                                   if (item[0], codes.get(item[1:])) not in relations)
    needed_codes = {(kind, name) for relations in expected.values() for _, kind, name in relations}
    return {"codes": codes, "missing_codes": sorted(needed_codes - codes.keys()),
            "missing": missing, "existing": existing}


def apply_plan(client, tracks, state, report, save):
    codes = dict(state["codes"])
    by_music = {track["music_id"]: track for track in tracks}
    for kind, name in state["missing_codes"]:
        row = client.create("code", {"codeType": kind, "codeName": name})
        codes[(kind, name)] = positive_id(row["codeId"])
        report["created"].append({"resource": "code", "id": row["codeId"], "type": kind, "name": name})
        save()
    for resource, field in (("musicGenre", "genreCode"), ("musicMood", "moodCode")):
        for music_id, kind, name in state["missing"][resource]:
            code_id = codes[(kind, name)]
            client.create(resource, {"music": music_id, field: code_id})
            created = {"resource": resource, "music_id": music_id, "code_id": code_id}
            if resource == "musicMood" and by_music[music_id].get("mood_confidence") == "approx":
                created.update(track_id=by_music[music_id]["track_id"], confidence="approx",
                               source=APPROXIMATION_SOURCE)
            report["created"].append(created)
            save()
            if len(report["created"]) % 100 == 0:
                print(f"등록 완료 {len(report['created'])}건", file=sys.stderr, flush=True)
    after = inspect(client, tracks)
    if after["missing_codes"] or any(after["missing"].values()):
        raise ValueError("반영 후 누락 관계가 남아 있음")
    for resource, before in state["existing"].items():
        if not before <= after["existing"][resource]:
            raise ValueError(f"검증 중 기존 관계 소실 감지: {resource}")
    report["status"] = "verified"
    report["rerun_additions"] = 0
    save()


def run(args):
    snapshot = read_json(args.snapshot)
    tracks = validate(snapshot)
    source_summary = summary(tracks)
    approximations = []
    if not args.skip_mood_approximations:
        tracks, approximations = supplement_moods(tracks, snapshot)
    report = {"status": "source_validated", "source_sha256": hashlib.sha256(Path(args.snapshot).read_bytes()).hexdigest(),
              "source_summary": source_summary, "summary": summary(tracks),
              "mood_approximations": approximations,
              "created": [], "api_base_url": args.api_base_url}
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    # 인증 파일이나 원본을 보고서로 덮어쓰지 않는다. 실행마다 새 경로를 요구한다.
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)

    def save():
        temp = output.with_suffix(output.suffix + ".tmp")
        temp.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, output)

    try:
        if args.apply and not args.api_base_url:
            raise ValueError("--apply에는 --api-base-url 필요")
        if args.api_base_url:
            client = AdminClient(args.api_base_url, args.token_file, args.refresh_token_file)
            state = inspect(client, tracks)
            report["missing_codes"] = state["missing_codes"]
            report["missing_relations"] = {k: len(v) for k, v in state["missing"].items()}
            report["status"] = "server_preflight_passed"
            save()
            if args.apply:
                apply_plan(client, tracks, state, report, save)
        print(json.dumps({k: v for k, v in report.items() if k != "created"}, ensure_ascii=False, indent=2))
    except Exception as exc:
        report["status"] = "failed"
        # 서버 오류 응답에 들어갈 수 있는 인증정보/본문은 보고서에 복사하지 않는다.
        report["error_type"] = type(exc).__name__
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("fetch", help="SSH로 JSON 원본과 기존 ID 매핑만 읽기")
    p.add_argument("--ssh-host", required=True)
    p.add_argument("--ssh-key")
    p.add_argument("--wsl-distro")
    p.add_argument("--source-dir", default="/home/j-j15a204/flow_music_data")
    p.add_argument("--registry", default="/home/j-j15a204/music_preprocessing/registered.json")
    p.add_argument("--output", required=True)
    p.set_defaults(func=fetch)
    p = sub.add_parser("snapshot", help="GPU 서버 또는 마운트된 로컬 원본 읽기")
    p.add_argument("--source-dir", required=True)
    p.add_argument("--registry", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=local_snapshot)
    p = sub.add_parser("import", help="기본: 사전 검증만, --apply: 누락 코드·관계 추가")
    p.add_argument("--snapshot", required=True)
    p.add_argument("--report", required=True)
    p.add_argument("--api-base-url")
    auth = p.add_mutually_exclusive_group()
    auth.add_argument("--token-file", help="ROLE_ADMIN 액세스 토큰 파일")
    auth.add_argument("--refresh-token-file", help="ROLE_ADMIN 리프레시 토큰 파일 (회전 시 갱신)")
    p.add_argument("--apply", action="store_true")
    p.add_argument("--skip-mood-approximations", action="store_true",
                   help="초기 10곡의 사용자 제공 근사치를 제외하고 원본 무드만 사용")
    p.set_defaults(func=run)
    args = parser.parse_args()
    try:
        args.func(args)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError) as exc:
        # API 오류는 기존 클라이언트가 서버 메시지를 포함하므로 타입/상태만 출력한다.
        if hasattr(exc, "status"):
            print(f"실패: HTTP {exc.status} ({type(exc).__name__}); 권한/서버 상태 확인", file=sys.stderr)
        elif isinstance(exc, subprocess.SubprocessError):
            print(f"실패: SSH {type(exc).__name__}; 호스트·키·경로 확인", file=sys.stderr)
        else:
            print(f"실패: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
