#!/usr/bin/env python3
"""안드로이드 APK 수신기. /usr/local/sbin/mupid-receive-apk 로 설치한다.

ssh-entrypoint-k8s.sh 를 거쳐서만 닿는다. 인자 모양은 거기서 이미 한 번
맞췄지만 여기서 다시 본다 — root 로 도는 코드가 호출자를 믿으면 안 된다.

  argv:  <40-hex 커밋> <64-hex sha256>
  stdin: APK 바이트

**이미지 수신기(receive-k8s.py)를 건드리지 않고 따로 둔다.** 그쪽은 컨테이너
이미지를 들여와 kubectl 로 태그를 바꾸는 일을 하고, 여기는 파일 하나를 정해진
자리에 놓는 일만 한다. 하는 일이 다른 것을 한 파일에 합치면 감사해야 할 코드가
넓어진다 — 저 엔드포인트는 CI 키로 닿을 수 있는 곳이라 좁을수록 좋다.

쓰는 자리는 **고정이다.** 클라이언트가 경로를 정하지 못한다. 파일 이름에 들어가는
커밋 해시는 정규식으로 40자 hex 임을 확인한 값이라 경로 조작이 되지 않는다.
"""
import hashlib
import os
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path("/srv/mupid-k8s")
DOWNLOADS = ROOT / "downloads"
# Tauri 유니버설 APK 가 대략 40~80MB 다. 넉넉히 잡되 무한은 아니다 — 디스크를
# 채우는 것만으로 노드를 멈출 수 있다.
SIZE_LIMIT = 314572800  # 300 MiB
KEEP = 5  # 예전 APK 를 몇 개까지 남길지

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")


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
            if total > SIZE_LIMIT:
                fail("APK exceeds the size limit")
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


def is_apk(path):
    """APK 는 zip 이고 첫 항목이 보통 AndroidManifest.xml 이다.

    내용을 깊이 검사하지는 않는다 — 여기서 하려는 것은 '전혀 다른 것이
    올라와 공개 URL 로 배포되는 일' 을 막는 것이지 서명 검증이 아니다.
    서명은 빌드할 때 gradle 이 한다.
    """
    import zipfile
    if not zipfile.is_zipfile(path):
        return False
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
    return "AndroidManifest.xml" in names and any(n.startswith("classes") for n in names)


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        raise SystemExit(64)
    release, checksum = sys.argv[1:]
    if not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)

    os.umask(0o022)  # nginx 가 읽어야 한다.
    DOWNLOADS.mkdir(parents=True, exist_ok=True)

    handle, temporary = tempfile.mkstemp(dir=DOWNLOADS, prefix="upload.")
    os.close(handle)
    temporary = pathlib.Path(temporary)
    try:
        size = receive(temporary)
        if checksum_of(temporary) != checksum:
            fail("Checksum mismatch; nothing published")
        if not is_apk(temporary):
            fail("Upload is not an APK")

        target = DOWNLOADS / f"mupid-{release}.apk"
        temporary.chmod(0o644)
        temporary.replace(target)

        # 심볼릭 링크를 원자적으로 갈아 끼운다. 바로 덮으면 받는 중인 사람이
        # 끊긴다 — 새 이름으로 만들고 rename 으로 바꾼다.
        digest = DOWNLOADS / "mupid.apk.sha256"
        digest.write_text(f"{checksum}  mupid-{release}.apk\n")
        digest.chmod(0o644)
        pending = DOWNLOADS / "mupid.apk.new"
        if pending.is_symlink() or pending.exists():
            pending.unlink()
        pending.symlink_to(target.name)
        pending.replace(DOWNLOADS / "mupid.apk")

        # 예전 것을 몇 개 남겨 둔다. 새 APK 가 잘못됐을 때 되돌릴 수 있다.
        old = sorted(DOWNLOADS.glob("mupid-*.apk"), key=lambda p: p.stat().st_mtime)
        for stale in old[:-KEEP]:
            stale.unlink()

        print(f"PUBLISHED {release} {size}")
    finally:
        if temporary.exists():
            temporary.unlink()


if __name__ == "__main__":
    main()
