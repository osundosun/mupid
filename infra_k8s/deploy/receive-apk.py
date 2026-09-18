#!/usr/bin/env python3
"""안드로이드 APK 수신기. /usr/local/sbin/mupid-receive-apk 로 설치한다.

ssh-entrypoint-k8s.sh 를 거쳐서만 닿는다. 인자 모양은 거기서 이미 한 번
맞췄지만 여기서 다시 본다 — root 로 도는 코드가 호출자를 믿으면 안 된다.

  argv:  <40-hex 커밋> <64-hex sha256> <versionCode>
  stdin: APK 바이트

**이미지 수신기(receive-k8s.py)를 건드리지 않고 따로 둔다.** 그쪽은 컨테이너
이미지를 들여와 kubectl 로 태그를 바꾸는 일을 하고, 여기는 파일 하나를 정해진
자리에 놓는 일만 한다. 하는 일이 다른 것을 한 파일에 합치면 감사해야 할 코드가
넓어진다 — 저 엔드포인트는 CI 키로 닿을 수 있는 곳이라 좁을수록 좋다.

쓰는 자리는 **고정이다.** 클라이언트가 경로를 정하지 못한다. 파일 이름에 들어가는
커밋 해시는 정규식으로 40자 hex 임을 확인한 값이라 경로 조작이 되지 않는다.

**여기서 APK 와 함께 `native.json` 을 쓴다.** 앱은 "새 APK 가 있나" 를 그 파일로만
판단한다 — `mupid.apk.sha256` 에는 sha256 밖에 없어서 내 것보다 새것인지를 거기서
알 수 없다(`versionCode` 가 없다). 두 파일을 다른 통로로 올리면 그 사이에 앱이
한쪽만 보고 판단하는 창이 생긴다. 그래서 APK 와 **같은 자리에서 이어서** 쓴다.

`versionCode` 는 CI 가 argv 로 준다. 노드에는 `aapt2` 가 없고 APK 안
`assets/tauri.conf.json` 의 version 은 `0.1.0` 그대로라 **바이트에서 읽어 낼 방법이
없다**(실측). 그래서 호출자를 믿되, 믿을 수 있는 것으로 좁힌다.

  1. 범위 — 1 이상 2,100,000,000 이하(안드로이드 상한, gradle 과 같은 값).
  2. `builtAt == versionCode * 1000` — 앱의 비교 규칙(`update.rs` 의
     `local_version_code`)과 같은 식이다. 어긋난 쌍은 받지 않는다.
  3. **뒤로 가지 않을 것** — 직전에 게시한 것보다 작으면 거부한다. 뒤로 가는
     versionCode 는 안드로이드가 설치를 거부하므로, 한 번 게시되면 그 APK 를
     받은 **모든 기기에서 안내만 뜨고 설치가 실패한다.** 캐시가 남은 러너에서
     옛 산출물을 다시 올리는 사고가 실제로 가능한 자리다.

     **같으면 받는다.** 같은 파이프라인을 재시도하면 같은 값이 나오고(스탬프가
     커밋 시각에서 나온다), 그때 막으면 실패한 job 을 다시 돌릴 수 없다.
     앱은 "내 것보다 크지 않으면" 안내하지 않으므로 같은 값을 다시 올려도
     사용자에게 재설치 안내가 뜨지 않는다 — 조용히 최신 바이트로 바뀐다.
     다만 그 사실은 로그에 남긴다.

`minSdk` 는 APK 바이트 안에서 읽는다 — 그 값이 설치 가능성을 정하는 값이고,
호출자가 말하는 값이 아니다.
"""
import hashlib
import json
import os
import pathlib
import re
import sys
import tempfile
import zipfile

ROOT = pathlib.Path("/srv/mupid-k8s")
DOWNLOADS = ROOT / "downloads"
# Tauri 유니버설 APK 가 대략 40~80MB 다. 넉넉히 잡되 무한은 아니다 — 디스크를
# 채우는 것만으로 노드를 멈출 수 있다.
SIZE_LIMIT = 314572800  # 300 MiB
KEEP = 5  # 예전 APK 를 몇 개까지 남길지

# 앱이 아는 스키마. `app/src/update.rs` 의 SCHEMA 와 같아야 한다 —
# 모르는 값이면 앱이 손대지 않고 조용히 아무 일도 일어나지 않는다.
SCHEMA = 1
# 안드로이드 `versionCode` 상한. `build.gradle.kts` 의 require 와 같은 값이다.
VERSION_CODE_MAX = 2_100_000_000

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")
VERSION_CODE = re.compile(r"^[0-9]{1,10}$")


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
    if not zipfile.is_zipfile(path):
        return False
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
    return "AndroidManifest.xml" in names and any(n.startswith("classes") for n in names)


def min_sdk_of(path):
    """APK 안에 든 `tauri.conf.json` 에서 minSdkVersion 을 읽는다.

    **호출자가 말한 값이 아니라 바이트에서 읽은 값이다.** 설치는 이 값으로
    판정되므로, 여기 적히는 값이 곧 "이 APK 가 무엇을 요구하는가" 여야 한다.
    못 읽으면 `None` — 모르는 값을 지어내지 않는다.
    """
    try:
        with zipfile.ZipFile(path) as archive:
            config = json.loads(archive.read("assets/tauri.conf.json").decode("utf-8"))
        value = config.get("bundle", {}).get("android", {}).get("minSdkVersion")
        return int(value) if value is not None else None
    except Exception:
        return None


def published_version_code():
    """직전에 게시한 `native.json` 의 versionCode. 없거나 깨져 있으면 `None`.

    깨진 것을 "없음" 으로 보는 이유: 단조 증가 검사가 막으려는 것은 **뒤로
    가는 게시**지, 손상된 파일이 배포를 영영 막는 것이 아니다.
    """
    path = DOWNLOADS / "native.json"
    try:
        return int(json.loads(path.read_text(encoding="utf-8"))["versionCode"])
    except Exception:
        return None


def main():
    if os.geteuid() != 0 or len(sys.argv) != 4:
        raise SystemExit(64)
    release, checksum, version_code = sys.argv[1:]
    if not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)
    if not VERSION_CODE.match(version_code):
        raise SystemExit(64)
    version_code = int(version_code)
    if not 1 <= version_code <= VERSION_CODE_MAX:
        fail(f"versionCode 가 안드로이드 상한을 벗어난다: {version_code}")

    published = published_version_code()
    if published is not None and version_code < published:
        fail(f"versionCode 가 뒤로 간다: {version_code} < 게시본 {published}. "
             "안드로이드는 다운그레이드 설치를 거부하므로, 이대로 올리면 "
             "받은 기기 전부에서 안내만 뜨고 설치가 실패한다.")
    if published == version_code:
        # 재시도다. 막지는 않는다 — 같은 파이프라인을 다시 돌릴 수 있어야 한다.
        # 앱은 "내 것보다 크지 않으면" 안내하지 않으므로 재촉도 생기지 않는다.
        print(f"알림: versionCode {version_code} 는 게시본과 같다 (재시도로 본다)",
              file=sys.stderr)

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
        pending = DOWNLOADS / "mupid.apk.new"
        if pending.is_symlink() or pending.exists():
            pending.unlink()
        pending.symlink_to(target.name)
        pending.replace(DOWNLOADS / "mupid.apk")

        digest = DOWNLOADS / "mupid.apk.sha256"
        digest.write_text(f"{checksum}  mupid-{release}.apk\n")
        digest.chmod(0o644)

        # 앱이 읽는 매니페스트. **APK 를 다 놓은 뒤에 쓴다** — 순서가 뒤집히면
        # 앱이 아직 없는 파일을 가리키는 매니페스트를 읽는다.
        min_sdk = min_sdk_of(target)
        if min_sdk is None:
            fail(f"APK 에서 minSdk 를 읽지 못했다: {target.name}")
        manifest = {
            "schema": SCHEMA,
            # 앱의 `local_version_code` 와 같은 식이다(ms / 1000). 앱은
            # `versionCode` 를 보고 판단하지만 `builtAt` 도 함께 본다 —
            # 한 값을 두 곳이 **같은 식으로** 접근해야 어긋나지 않는다.
            "builtAt": version_code * 1000,
            "versionCode": version_code,
            "commit": release,
            "apk": "/download/mupid.apk",
            "sha256": checksum,
            "bytes": size,
            "minSdk": min_sdk,
        }
        pending_manifest = DOWNLOADS / "native.json.new"
        pending_manifest.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        pending_manifest.chmod(0o644)
        pending_manifest.replace(DOWNLOADS / "native.json")

        # 예전 것을 몇 개 남겨 둔다. 새 APK 가 잘못됐을 때 되돌릴 수 있다.
        old = sorted(DOWNLOADS.glob("mupid-*.apk"), key=lambda p: p.stat().st_mtime)
        for stale in old[:-KEEP]:
            stale.unlink()

        print(f"PUBLISHED {release} {size} versionCode={version_code} minSdk={min_sdk}")
    finally:
        if temporary.exists():
            temporary.unlink()


if __name__ == "__main__":
    main()
