#!/usr/bin/env python3
"""웹 번들 수신기. /usr/local/sbin/mupid-receive-web 로 설치한다.

ssh-entrypoint-k8s.sh 를 거쳐서만 닿는다. 인자 모양은 거기서 이미 한 번 맞췄지만
여기서 다시 본다 — root 로 도는 코드가 호출자를 믿으면 안 된다.

  argv:  <40-hex 커밋> <64-hex sha256>
  stdin: 코드 자산만 담은 tar.gz (frontend/scripts/web-archive.mjs 의 출력)

**왜 APK 수신기와 합치지 않나.** 하는 일이 다르다. 저쪽이 보는 것은 "이것이
APK 인가"(zip 구조와 AndroidManifest.xml)이고, 여기가 보는 것은 **아카이브 안전**
이다 — 심볼릭 링크·`..`·절대경로·크기 상한. `infra/deploy/receive-front.py` 가 이미
정한 규칙이고, 그 파일도 APK 수신기와 따로 있다.

**매니페스트를 아카이브에서 만든다.** `web.json` 의 필드를 클라이언트가 보내지
않고 여기서 **전개하면서 직접 센다.** 스탬프가 세 곳(아카이브 이름 · `app/build.json`
· `web.json`)에 흩어져 있으면 하나가 어긋나고, 그 증상은 "앱이 자기 번들을 계속
다시 받는다" 로만 보인다. 여기서 만들면 어긋날 수가 없다.

**게시 순서: 아카이브 먼저, 매니페스트 나중.** `web.json` 이 스위치다. 반대로
놓으면 앱이 없는 파일을 가리키는 매니페스트를 읽는다.

  /srv/mupid-k8s/downloads/
    web-<builtAt>.tar.gz     내용 주소(이름에 타임스탬프)
    web.json                 스위치 (no-store 로 나간다)
"""
import hashlib
import json
import os
import pathlib
import re
import shutil
import sys
import tarfile
import tempfile

ROOT = pathlib.Path("/srv/mupid-k8s")
DOWNLOADS = ROOT / "downloads"

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")

# ── 상한은 **앱과 같은 값**이어야 한다 ─────────────────────────────
# `app/src/update.rs` 의 상수와 같다. 여기서만 느슨하면 앱이 거부할 번들을
# 공개 URL 에 올려 두게 되고, 그 증상은 "업데이트가 안 된다" 뿐이다. 반대로
# 여기서 더 좁으면 앱이 받을 수 있는 번들을 못 올린다. 어긋나면 안 된다.
# `infra_k8s/tests/test_manifests.py` 가 두 파일을 맞대어 본다.
MAX_ARCHIVE_BYTES = 4 * 1024 * 1024        # update.rs MAX_ARCHIVE_BYTES
MAX_EXPANDED_BYTES = 24 * 1024 * 1024      # update.rs MAX_EXPANDED_BYTES
MAX_FILES = 2048                           # update.rs MAX_FILES
MAX_MEMBER_BYTES = 8 * 1024 * 1024         # update.rs MAX_MEMBER_BYTES

# 앱이 읽는 이름. 생성기(`web-archive.mjs`)의 BUILD_INFO_PATH 와 같아야 한다.
BUILD_INFO = "app/build.json"
ENTRY = "index.html"

# 예전 번들을 몇 개까지 남길지. 되돌릴 수 있어야 한다.
KEEP = 3

SCHEMA = 1


def fail(message, code=65):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def receive(path):
    """stdin 을 파일로 받는다. 상한을 넘으면 거기서 끊는다."""
    total = 0
    with open(path, "wb") as sink:
        while True:
            block = sys.stdin.buffer.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > MAX_ARCHIVE_BYTES:
                fail(f"아카이브가 앱의 상한을 넘는다: {total} > {MAX_ARCHIVE_BYTES}")
            sink.write(block)
    if total == 0:
        fail("Empty upload")
    return total


def checksum_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_extract(archive, target):
    """항목을 하나씩 검사하며 쓴다. `extractall` 을 부르지 않는다.

    규칙은 `receive-front.py` 가 이미 정한 그대로다 — 새 규칙을 만들지 않는다.
    다만 상한이 그쪽(256 MiB / 20000개)보다 훨씬 좁다. 앱이 `update.rs` 에서
    거부할 것을 여기서 통과시키면 안 된다.
    """
    files = 0
    expanded = 0
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            name = pathlib.PurePosixPath(member.name)
            # 심볼릭 링크·하드 링크·장치·FIFO 를 전부 거부한다. 링크 하나로
            # 번들 밖 파일을 읽게 만들 수 있다.
            if not member.isfile() and not member.isdir():
                fail(f"일반 파일·디렉터리만 받는다: {member.name}")
            if name.is_absolute() or ".." in name.parts:
                fail(f"경로가 밖을 가리킨다: {member.name}")
            if member.isfile():
                if member.size > MAX_MEMBER_BYTES:
                    fail(f"항목이 상한을 넘는다: {member.name} {member.size}")
                files += 1
                expanded += member.size
                if files > MAX_FILES:
                    fail(f"파일이 상한을 넘는다: {files} > {MAX_FILES}")
                if expanded > MAX_EXPANDED_BYTES:
                    fail(f"펼친 총량이 상한을 넘는다: {expanded} > {MAX_EXPANDED_BYTES}")
                destination = target.joinpath(*name.parts)
                destination.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, destination.open("wb") as sink:
                    shutil.copyfileobj(source, sink)
                destination.chmod(0o644)
            else:
                target.joinpath(*name.parts).mkdir(parents=True, exist_ok=True)
    return files, expanded


def read_built_at(tree):
    """번들이 스스로 밝히는 나이. `app/src/update.rs` 의 `read_bundle_build` 와 같다.

    이 값이 곧 버전이다. 없으면 앱이 그 번들을 거부하므로 여기서도 거부한다 —
    공개 URL 에 아무도 못 쓰는 번들을 올려 두면 안 된다.
    """
    path = tree / BUILD_INFO
    if not path.is_file():
        fail(f"번들 안에 {BUILD_INFO} 이 없다 — 앱이 이 번들을 거부한다")
    try:
        built_at = json.loads(path.read_text(encoding="utf-8"))["builtAt"]
    except Exception as error:
        fail(f"{BUILD_INFO} 을 읽지 못했다: {type(error).__name__}: {error}")
    if not isinstance(built_at, int) or built_at <= 0:
        fail(f"{BUILD_INFO} 의 builtAt 이 이상하다: {built_at!r}")
    return built_at


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        raise SystemExit(64)
    release, checksum = sys.argv[1:]
    if not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)

    os.umask(0o022)  # nginx 가 읽어야 한다.
    DOWNLOADS.mkdir(parents=True, exist_ok=True)

    staging = pathlib.Path(tempfile.mkdtemp(dir=DOWNLOADS, prefix=".upload-web."))
    try:
        archive = staging / "bundle.tar.gz"
        size = receive(archive)
        if checksum_of(archive) != checksum:
            fail("Checksum mismatch; nothing published")

        tree = staging / "tree"
        tree.mkdir()
        files, expanded = safe_extract(archive, tree)
        built_at = read_built_at(tree)
        if not (tree / ENTRY).is_file():
            fail(f"진입점이 없다: {ENTRY} — 앱이 뜨지 않는다")

        manifest = {
            "schema": SCHEMA,
            "builtAt": built_at,
            "bundle": f"/download/web-{built_at}.tar.gz",
            "sha256": checksum,
            "bytes": size,
            "files": files,
            "expandedBytes": expanded,
            "entry": ENTRY,
        }

        # ① 아티팩트를 먼저 놓는다. 같은 이름이 이미 있으면 같은 내용이어야
        #    한다 — 다르면 다른 빌드가 같은 타임스탬프를 들고 온 것이고, 그대로
        #    덮으면 그 판을 받은 기기와 새로 받는 기기가 서로 다른 코드를 돈다.
        target = DOWNLOADS / f"web-{built_at}.tar.gz"
        if target.exists():
            if checksum_of(target) != checksum:
                fail("같은 타임스탬프에 다른 내용이 왔다; 아무것도 바꾸지 않았다")
        else:
            archive.chmod(0o644)
            archive.replace(target)

        # ② 매니페스트는 **마지막에, 원자적으로.** 이 파일이 스위치다.
        pending = DOWNLOADS / "web.json.new"
        pending.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
        pending.chmod(0o644)
        pending.replace(DOWNLOADS / "web.json")

        # ③ 예전 번들을 몇 개 남긴다. 새 번들이 잘못됐을 때 되돌릴 수 있다.
        old = sorted(DOWNLOADS.glob("web-*.tar.gz"), key=lambda p: p.stat().st_mtime)
        for stale in old[:-KEEP]:
            stale.unlink()

        print(f"PUBLISHED web {built_at} ({size} bytes, {files} files, "
              f"expanded {expanded})")
    finally:
        shutil.rmtree(staging, ignore_errors=True)


if __name__ == "__main__":
    main()
