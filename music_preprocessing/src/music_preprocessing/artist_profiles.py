"""기존 ARTIST 저장 키의 이미지 내용만 교체한다. 기본 실행은 백업/사전 검증.

python -m music_preprocessing.artist_profiles --help
기존 catalog/covers 및 registered.json은 수정하지 않는다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
from urllib.parse import quote, unquote, urlsplit
from urllib.request import Request, urlopen
from zipfile import ZipFile

from .api_client import MupidClient, _encode_multipart

MAX_IMAGE = 5 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, data):
    path = Path(path)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    # Windows 백신/인덱서가 직전 보고서를 잠깐 열어 둔 경우만 짧게 재시도한다.
    for attempt in range(5):
        try:
            os.replace(temp, path)
            break
        except PermissionError:
            if attempt == 4:
                raise
            time.sleep(0.05 * (attempt + 1))


def image_url(key):
    return "/api/v1/storage/image/ARTIST/" + quote(key, safe="")


def validate_png(data, meta):
    if len(data) > MAX_IMAGE or len(data) < 33:
        raise ValueError("이미지 크기 제한 위반")
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("PNG 형식 아님")
    if struct.unpack(">II", data[16:24]) != (1254, 1254):
        raise ValueError("프로필 해상도 불일치")
    if meta.get("width") != 1254 or meta.get("height") != 1254 or meta.get("mime_type") != "image/png":
        raise ValueError("프로필 메타데이터 형식 불일치")
    if len(data) != meta["size_bytes"] or digest(data) != meta["sha256"]:
        raise ValueError("프로필 크기/해시 불일치")


def validate_manifest(manifest):
    if manifest.get("version") != 1 or len(manifest["artists"]) != 87:
        raise ValueError("87명 프로필 manifest 필요")
    keys, ids, files = set(), set(), set()
    for row in manifest["artists"]:
        key, aid = row["artist_key"], row["artist_id"]
        if not isinstance(key, str) or not re.fullmatch(r"[a-z0-9_+]+", key):
            raise ValueError("잘못된 artist_key")
        if type(aid) is not int or aid <= 0 or type(row["image_file_id"]) is not int or row["image_file_id"] <= 0:
            raise ValueError("잘못된 등록 ID")
        if key in keys or aid in ids or row["filename"] in files:
            raise ValueError("중복 아티스트/파일 매핑")
        if not row["name"] or row["filename"] != key + ".png":
            raise ValueError("이름 또는 파일 매핑 불일치")
        if not isinstance(row["original_filename"], str) or not row["original_filename"].endswith(".png") or any(c in row["original_filename"] for c in '\r\n"\\/'):
            raise ValueError("잘못된 원본 파일명")
        if not re.fullmatch(r"[0-9a-f]{64}", row["profile_image"]["sha256"]):
            raise ValueError("잘못된 SHA-256")
        keys.add(key); ids.add(aid); files.add(row["filename"])


# 원격에서는 파일 읽기와 stdout ZIP 전송만 수행한다. 셸에 경로를 보간하지 않는다.
REMOTE_BUNDLE = r'''
import json, pathlib, sys, zipfile
settings = SETTINGS
root = pathlib.Path(settings['source_dir']).expanduser().resolve()
registry = json.loads(pathlib.Path(settings['registry']).expanduser().read_text())['catalog']['artists']
artists = json.loads((root / 'artists.json').read_text())
rows, paths = [], []
for artist in artists:
    key = artist['artist_key']
    entry = registry[key]
    if entry['name'] != artist['artist_name']:
        raise ValueError('artist name mismatch')
    meta = artist['profile_image']
    path = (root / meta['path']).resolve()
    if not path.is_relative_to(root / 'artists' / 'artists_images') or not path.is_file():
        raise ValueError('image outside artist directory')
    rows.append(dict(artist_key=key, artist_id=entry['artist_id'], name=artist['artist_name'],
        image_file_id=entry['image_file_id'], registered_cover_sha256=entry['cover_sha256'],
        filename=key+'.png', original_filename=path.name, profile_image=meta))
    paths.append(path)
with zipfile.ZipFile(sys.stdout.buffer, 'w', compression=zipfile.ZIP_STORED) as z:
    z.writestr('manifest.json', json.dumps(dict(version=1, artists=rows), ensure_ascii=False))
    for row,path in zip(rows,paths):
        z.write(path, row['filename'])
'''


def fetch(args):
    if args.ssh_host.startswith("-") or not re.fullmatch(r"[\w.@:-]+", args.ssh_host):
        raise ValueError("잘못된 SSH 호스트")
    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=False)
    archive = out / "transfer.zip"
    settings = {"source_dir": args.source_dir, "registry": args.registry}
    source = REMOTE_BUNDLE.replace("SETTINGS", repr(settings))
    command = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15"]
    if args.ssh_key:
        command += ["-i", args.ssh_key]
    command += [args.ssh_host, "python3 -"]
    if args.wsl_distro:
        command = ["wsl", "-d", args.wsl_distro, "--"] + command
    with archive.open("xb") as stream:
        subprocess.run(command, input=source.encode(), stdout=stream, stderr=subprocess.PIPE,
                       timeout=600, check=True)
    unpack_bundle(archive, out)
    archive.unlink()  # 이 실행에서 생성한 전송용 파일 하나만 정리
    print(json.dumps({"verified_profiles": 87, "bundle": str(out)}, ensure_ascii=False))


def unpack_bundle(archive, out):
    out = Path(out)
    if (out / "manifest.json").exists():
        raise ValueError("기존 원본 묶음을 덮어쓸 수 없음")
    with ZipFile(archive) as z:
        manifest = json.loads(z.read("manifest.json"))
        validate_manifest(manifest)
        expected = {"manifest.json"} | {r["filename"] for r in manifest["artists"]}
        if set(z.namelist()) != expected or len(z.namelist()) != len(expected):
            raise ValueError("예상하지 않은 ZIP 항목")
        for row in manifest["artists"]:
            info = z.getinfo(row["filename"])
            if info.file_size > MAX_IMAGE:
                raise ValueError("이미지 크기 초과")
            data = z.read(info)
            validate_png(data, row["profile_image"])
            (out / row["filename"]).write_bytes(data)
    write_json(out / "manifest.json", manifest)


def load_bundle(directory):
    directory = Path(directory)
    manifest = read_json(directory / "manifest.json")
    validate_manifest(manifest)
    for row in manifest["artists"]:
        validate_png((directory / row["filename"]).read_bytes(), row["profile_image"])
    return manifest


class ArtistClient(MupidClient):
    def __init__(self, base_url, refresh_token_file):
        parsed = urlsplit(base_url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
            raise ValueError("HTTPS API origin 필요")
        token_file = Path(refresh_token_file)
        super().__init__(base_url.rstrip("/"), token_file.read_text().strip(), token_file=token_file,
                         timeout=60, max_retries=1)

    def artists(self):
        data = self._request("GET", "/api/v1/artist?size=100&page=0")["data"]
        if data["total_elements"] != 87 or len(data["items"]) != 87:
            raise ValueError("운영 아티스트 수가 87명이 아님")
        return sorted(data["items"], key=lambda r: r["artist_id"])

    def download(self, key):
        request = Request(self.base_url + image_url(key), headers={"Cache-Control": "no-cache"})
        with urlopen(request, timeout=60) as response:
            data = response.read(MAX_IMAGE + 1)
            if len(data) > MAX_IMAGE:
                raise ValueError("기존 이미지 크기 제한 초과")
            mime = response.headers.get_content_type()
            filename = unquote(response.headers.get_filename() or (key + ".png"))
        if not data or mime not in ("image/png", "image/jpeg", "image/webp"):
            raise ValueError("지원하지 않는 기존 이미지 형식")
        # 복구 시 Content-Disposition에 원격 입력을 그대로 보간하지 않는다.
        filename = re.sub(r'[\r\n"\\/]', '_', filename)
        return data, mime, filename

    def upload(self, row, data, mime, filename):
        if len(data) > MAX_IMAGE:
            raise ValueError("이미지 크기 제한 초과")
        multipart = _encode_multipart(files=[("imageFile", filename, data, mime)])
        result = self._request("POST", image_url(row["artist_key"]), multipart=multipart)["data"]
        if result["file_id"] != row["image_file_id"]:
            raise ValueError("업로드 후 기존 file_id 변경 감지")


def check_artists(rows, current):
    by_id = {r["artist_id"]: r for r in current}
    if len(by_id) != len(current) or set(by_id) != {r["artist_id"] for r in rows}:
        raise ValueError("아티스트 ID 집합 불일치")
    for row in rows:
        actual = by_id[row["artist_id"]]
        if actual["name"] != row["name"] or actual["image_url"] != image_url(row["artist_key"]):
            raise ValueError(f"아티스트 이름/이미지 URL 불일치: {row['artist_key']}")


def prepare(client, manifest, run_dir):
    """전체 기존 이미지를 백업하고 모든 검증이 끝난 뒤 manifest를 확정한다."""
    current = client.artists()
    check_artists(manifest["artists"], current)
    rows = []
    backup_dir = run_dir / "backups"
    backup_dir.mkdir(exist_ok=True)
    for i, row in enumerate(manifest["artists"], 1):
        data, mime, filename = client.download(row["artist_key"])
        before = digest(data)
        if before not in (row["registered_cover_sha256"], row["profile_image"]["sha256"]):
            raise ValueError(f"등록 커버/새 프로필과 다른 운영 이미지: {row['artist_key']}")
        backup = row["artist_key"] + ".bin"
        (backup_dir / backup).write_bytes(data)
        rows.append({**row, "before_sha256": before, "backup_file": backup,
                     "before_mime": mime, "before_filename": filename})
        if i % 10 == 0:
            print(f"백업 검증 {i}/87", file=sys.stderr, flush=True)
    state = {"version": 1, "api_base_url": client.base_url,
             "source_sha256": digest(json.dumps(manifest, sort_keys=True).encode()),
             "artists_before": current, "artists": rows}
    write_json(run_dir / "backup-manifest.json", state)
    return state


def verify_backups(state, run_dir):
    validate_manifest(state)
    for row in state["artists"]:
        if row["backup_file"] != row["artist_key"] + ".bin":
            raise ValueError("잘못된 백업 경로")
        data = (run_dir / "backups" / row["backup_file"]).read_bytes()
        if digest(data) != row["before_sha256"]:
            raise ValueError("백업 해시 불일치")


def process(client, state, bundle, run_dir, apply=False, rollback=False):
    verify_backups(state, run_dir)
    if state["api_base_url"] != client.base_url:
        raise ValueError("백업 API 대상 불일치")
    current = client.artists()
    if current != state["artists_before"]:
        raise ValueError("백업 이후 아티스트 ID/이름/URL 변경 감지")
    pending = []
    for row in state["artists"]:
        data, _, _ = client.download(row["artist_key"])
        sha = digest(data)
        new = row["profile_image"]["sha256"]
        if sha not in (new, row["before_sha256"]):
            raise ValueError(f"사전 검증: 예상 밖 이미지 변경: {row['artist_key']}")
        desired = row["before_sha256"] if rollback else new
        if sha != desired:
            pending.append(row)
    report = {"status": "preflight_passed", "rollback": rollback,
              "total": len(state["artists"]), "pending": len(pending),
              "already_matches": len(state["artists"]) - len(pending), "completed": []}
    report_path = run_dir / ("rollback-result.json" if rollback else "result.json")
    write_json(report_path, report)
    if not apply:
        return report
    try:
        for row in pending:
            key = row["artist_key"]
            # 사전 검증과 쓰기 사이의 다른 작업을 덮어쓰지 않도록 바로 다시 확인한다.
            now, _, _ = client.download(key)
            desired = row["before_sha256"] if rollback else row["profile_image"]["sha256"]
            allowed = row["profile_image"]["sha256"] if rollback else row["before_sha256"]
            if digest(now) == desired:
                continue
            if digest(now) != allowed:
                raise ValueError(f"업로드 직전 다른 이미지 감지: {key}")
            if rollback:
                data = (run_dir / "backups" / row["backup_file"]).read_bytes()
                mime, filename = row["before_mime"], row["before_filename"]
            else:
                data = (Path(bundle) / row["filename"]).read_bytes()
                validate_png(data, row["profile_image"])
                mime, filename = "image/png", row["original_filename"]
            if digest(data) != desired:
                raise ValueError("전송 직전 파일 해시 불일치")
            # 자동 재전송하지 않는다. 응답 손실 시 다음 실행에서 실제 서버 해시로 재개.
            report["in_flight"] = key
            write_json(report_path, report)
            client.upload(row, data, mime, filename)
            served, _, _ = client.download(key)
            if digest(served) != desired:
                raise ValueError(f"업로드 후 제공 이미지 불일치: {key}")
            report["completed"].append(key)
            report.pop("in_flight", None)
            write_json(report_path, report)
            print(f"{'복구' if rollback else '교체'} 검증 {len(report['completed'])}/{len(pending)}: {key}", flush=True)
        if client.artists() != state["artists_before"]:
            raise ValueError("작업 후 아티스트 ID/이름/URL 변경 감지")
        for row in state["artists"]:
            data, _, _ = client.download(row["artist_key"])
            desired = row["before_sha256"] if rollback else row["profile_image"]["sha256"]
            if digest(data) != desired:
                raise ValueError("최종 전체 이미지 검증 실패")
        report["status"] = "verified"
        report["verified_images"] = len(state["artists"])
        report["artist_records_unchanged"] = True
        write_json(report_path, report)
    except Exception as exc:
        report["status"] = "failed"
        report["error_type"] = type(exc).__name__
        write_json(report_path, report)
        raise
    return report


def run(args):
    run_dir = Path(args.run_dir)
    client = ArtistClient(args.api_base_url, args.refresh_token_file)
    # 두 프로세스가 같은 백업/인증 파일로 교체 작업을 시작하는 것을 막는다.
    run_dir.mkdir(parents=True, exist_ok=True)
    lock = run_dir / ".lock"
    with lock.open("x") as f:
        f.write(str(os.getpid()))
    try:
        manifest_path = run_dir / "backup-manifest.json"
        if args.command == "rollback":
            state = read_json(manifest_path)
        else:
            manifest = load_bundle(args.bundle)
            if manifest_path.exists():
                state = read_json(manifest_path)
                if state["source_sha256"] != digest(json.dumps(manifest, sort_keys=True).encode()):
                    raise ValueError("기존 백업과 다른 원본 묶음")
            else:
                state = prepare(client, manifest, run_dir)
        report = process(client, state, getattr(args, "bundle", None), run_dir,
                         apply=args.apply, rollback=args.command == "rollback")
        print(json.dumps({k: v for k, v in report.items() if k != "completed"}, ensure_ascii=False))
    finally:
        lock.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("fetch", help="GPU 프로필 원본과 기존 ID 매핑 읽기")
    p.add_argument("--ssh-host", required=True)
    p.add_argument("--ssh-key")
    p.add_argument("--wsl-distro")
    p.add_argument("--source-dir", default="/home/j-j15a204/flow_music_data")
    p.add_argument("--registry", default="/home/j-j15a204/music_preprocessing/registered.json")
    p.add_argument("--output-dir", required=True)
    p.set_defaults(func=fetch)
    for command in ("replace", "rollback"):
        p = sub.add_parser(command)
        if command == "replace":
            p.add_argument("--bundle", required=True)
        p.add_argument("--run-dir", required=True)
        p.add_argument("--api-base-url", required=True)
        p.add_argument("--refresh-token-file", required=True)
        p.add_argument("--apply", action="store_true")
        p.set_defaults(func=run)
    args = parser.parse_args()
    try:
        args.func(args)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.SubprocessError) as exc:
        if hasattr(exc, "status"):
            print(f"실패: HTTP {exc.status} ({type(exc).__name__})", file=sys.stderr)
        elif isinstance(exc, subprocess.SubprocessError):
            print(f"실패: SSH {type(exc).__name__}", file=sys.stderr)
        else:
            print(f"실패: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
