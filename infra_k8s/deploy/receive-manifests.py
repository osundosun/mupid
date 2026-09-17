#!/usr/bin/env python3
"""매니페스트 수신기. /usr/local/sbin/mupid-receive-manifests 로 설치한다.

ssh-entrypoint-k8s.sh 를 거쳐서만 닿는다.

  argv:  <40-hex 커밋> <64-hex sha256>
  stdin: infra_k8s/{base,overlays} 를 담은 tar.gz

**왜 필요한가.** 매니페스트는 사람이 노드에 올려 mupid-apply 를 돌려야 반영된다.
저장소에서 고쳐 머지해도 클러스터는 모른다. 그 탓에 하루에 세 번 같은 사고가 났다 —
Secret 은 갱신됐는데 파드가 그 키를 안 읽고, nginx 수정이 k8s 사본에만 없고,
APK 는 올라갔는데 /download/ 가 404 였다. 셋 다 "머지했으니 됐다" 고 생각한 뒤
한참 뒤에 드러났다.

**이 엔드포인트는 다른 둘보다 힘이 세다.** 이미지 수신기는 태그만 바꾸고 환경변수
수신기는 Secret 하나만 건드리지만, 여기는 오버레이 전체를 적용한다. 그래서 받은
내용을 그대로 믿지 않고 세 가지를 본다.

  1. tar 안의 경로가 infra_k8s/base 또는 infra_k8s/overlays 로 시작하는 일반
     파일인지. 절대경로·..·심볼릭 링크는 거부한다.
  2. 렌더된 결과의 네임스페이스가 전부 mupid 인지. 다른 네임스페이스나
     ClusterRole 류가 섞여 오면 거부한다.
  3. 적용은 반드시 mupid-apply 를 거친다. `kubectl apply -k` 를 직접 쓰면
     이미지 태그가 placeholder 로 덮여, 겉보기엔 멀쩡한데 파드가 죽는 순간
     서비스가 내려간다. 그 함정 때문에 mupid-apply 가 존재한다.
"""
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = pathlib.Path("/srv/mupid-k8s")
MANIFESTS = ROOT / "manifests"
OVERLAY = "infra_k8s/overlays/cutover"
NAMESPACE = "mupid"
KUBECONFIG = "/etc/rancher/k3s/k3s.yaml"
SIZE_LIMIT = 10485760  # 10 MiB. 매니페스트가 이보다 클 이유가 없다.
KEEP = 5

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")
ALLOWED = ("infra_k8s/base/", "infra_k8s/overlays/")
# 네임스페이스가 없는 것 중 받아 줄 종류. 이 밖은 거부한다 — RBAC 이나
# 네임스페이스를 CI 가 바꿀 수 있으면 이 엔드포인트가 클러스터 전체를 쥔다.
CLUSTER_KINDS = {"Namespace"}


def fail(message, code=65):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def receive(path):
    total = 0
    with open(path, "wb") as sink:
        while True:
            block = sys.stdin.buffer.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > SIZE_LIMIT:
                fail("매니페스트 묶음이 너무 크다")
            sink.write(block)
    if total == 0:
        fail("빈 업로드")
    return total


def safe_extract(archive, target):
    """경로를 하나씩 본다. tar 는 `../` 와 절대경로로 밖에 쓸 수 있다."""
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        for member in members:
            name = member.name
            if not member.isfile() and not member.isdir():
                fail(f"일반 파일·디렉터리만 받는다: {name}")
            if name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts:
                fail(f"경로가 밖을 가리킨다: {name}")
            if not name.startswith(ALLOWED) and name.rstrip("/") not in (
                    "infra_k8s", "infra_k8s/base", "infra_k8s/overlays"):
                fail(f"허용하지 않는 경로: {name}")
        tar.extractall(target, members=members)


def render(directory):
    done = subprocess.run(
        ["kubectl", "kustomize", str(pathlib.Path(directory) / OVERLAY)],
        env={**os.environ, "KUBECONFIG": KUBECONFIG},
        capture_output=True, text=True, timeout=120,
    )
    if done.returncode:
        fail(f"RENDER_FAILED: {done.stderr.strip()[:500]}")
    return done.stdout


def assert_only_our_namespace(rendered):
    import yaml
    for doc in yaml.safe_load_all(rendered):
        if not doc:
            continue
        kind = doc.get("kind", "?")
        namespace = (doc.get("metadata") or {}).get("namespace")
        if namespace is None:
            if kind not in CLUSTER_KINDS:
                fail(f"네임스페이스 없는 {kind} 는 받지 않는다")
        elif namespace != NAMESPACE:
            fail(f"{kind} 가 다른 네임스페이스를 가리킨다: {namespace}")


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        raise SystemExit(64)
    release, checksum = sys.argv[1:]
    if not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)

    os.umask(0o022)
    MANIFESTS.mkdir(parents=True, exist_ok=True)
    staging = pathlib.Path(tempfile.mkdtemp(dir=MANIFESTS, prefix=".staging."))
    archive = staging / "manifests.tar.gz"
    try:
        size = receive(archive)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != checksum:
            fail("Checksum mismatch; 아무것도 적용하지 않았다")

        tree = staging / "tree"
        tree.mkdir()
        safe_extract(archive, tree)
        overlay = tree / OVERLAY
        if not overlay.is_dir():
            fail(f"{OVERLAY} 가 묶음에 없다")

        assert_only_our_namespace(render(tree))

        applied = subprocess.run(
            ["/usr/local/sbin/mupid-apply", str(overlay)],
            capture_output=True, text=True, timeout=600,
        )
        print(applied.stdout[-4000:])
        if applied.returncode:
            fail(f"APPLY_FAILED: {applied.stderr.strip()[:500]}")

        # 적용에 성공한 것만 보관한다. 실패한 판이 current 가 되면 다음 사람이
        # 클러스터와 다른 것을 보게 된다.
        kept = MANIFESTS / release
        if kept.exists():
            shutil.rmtree(kept)
        shutil.move(str(tree), str(kept))
        current = MANIFESTS / "current"
        pending = MANIFESTS / ".current.new"
        if pending.is_symlink() or pending.exists():
            pending.unlink()
        pending.symlink_to(release)
        pending.replace(current)

        old = sorted((p for p in MANIFESTS.iterdir()
                      if p.is_dir() and RELEASE.match(p.name)),
                     key=lambda p: p.stat().st_mtime)
        for stale in old[:-KEEP]:
            shutil.rmtree(stale, ignore_errors=True)

        print(f"APPLIED {release} ({size} bytes)")
    finally:
        shutil.rmtree(staging, ignore_errors=True)


if __name__ == "__main__":
    main()
