#!/usr/bin/env python3
"""Root-owned k8s deployment endpoint. Installed as /usr/local/sbin/mupid-receive-k8s.

Reached only through ssh-entrypoint-k8s.sh, which has already matched the
argument shapes. They are re-validated here because this runs as root and must
not depend on its caller.

  argv:  <component> <40-hex release> <64-hex sha256>
  stdin: gzip-compressed docker image archive

The order is deliberate and the same as the Compose receiver's: nothing touches
the cluster until the upload is complete, checksummed, and proven to contain
exactly the image tag that was asked for. A failed rollout is rolled back.

Images only. Manifests are applied by an operator from the repository; this
endpoint cannot change what the cluster runs beyond the image tag of one
container, which is what makes the narrow RBAC in rbac.yaml sufficient.
"""
import json
import os
import pathlib
import re
import subprocess
import sys
import tarfile
import tempfile

ROOT = pathlib.Path("/srv/mupid-k8s")
NAMESPACE = "mupid"
# The restricted credential from rbac.yaml. Not /etc/rancher/k3s/k3s.yaml: that
# one is cluster-admin, and this endpoint is reachable from CI.
KUBECONFIG = ROOT / "deploy.kubeconfig"
SIZE_LIMIT = 1073741824  # 1 GiB, matching the Compose endpoint and CI's check.

# component -> (workload, container, image repository)
COMPONENTS = {
    "api": ("deployment/api", "api", "mupid-api"),
    "web": ("deployment/edge", "nginx", "mupid-web"),
    # 추천 서버. replicas 가 0 이어도 태그는 바꿔 둔다 — 올릴 때 이미 최신
    # 이미지를 가리키고 있어야 한다.
    "ai": ("deployment/ai", "ai", "mupid-ai"),
}

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")


def fail(message, code=65):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def kubectl(*arguments, timeout=240):
    return subprocess.run(
        ["kubectl", "--kubeconfig", str(KUBECONFIG), "-n", NAMESPACE, *arguments],
        capture_output=True, text=True, timeout=timeout,
    )


def receive(destination):
    """Copy stdin to destination, refusing anything over the size limit.

    Reads one byte past the limit so an oversized upload is rejected rather
    than silently truncated into a file whose checksum will not match anyway.
    """
    total = 0
    with open(destination, "wb") as out:
        while True:
            chunk = sys.stdin.buffer.read(1048576)
            if not chunk:
                break
            total += len(chunk)
            if total > SIZE_LIMIT:
                fail("Image exceeds 1 GiB")
            out.write(chunk)
    return total


def checksum_of(path):
    import hashlib
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1048576), b""):
            digest.update(block)
    return digest.hexdigest()


def assert_archive_holds_only(path, image):
    """The tag inside the archive is what containerd will register, so it has to
    be the tag that was requested. Without this an archive could be uploaded
    under one release name and import a completely different image."""
    with tarfile.open(path, "r:gz") as archive:
        try:
            member = archive.getmember("manifest.json")
        except KeyError:
            fail("Archive has no manifest.json; not a docker image archive")
        if member.size > 1048576:
            fail("Oversized image manifest")
        manifest = json.load(archive.extractfile(member))
    if len(manifest) != 1 or manifest[0].get("RepoTags") != [image]:
        fail("Archive must contain exactly the requested image tag")


def main():
    if os.geteuid() != 0 or len(sys.argv) != 4:
        raise SystemExit(64)
    component, release, checksum = sys.argv[1:]
    if component not in COMPONENTS or not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)
    workload, container, repository = COMPONENTS[component]
    image = f"{repository}:{release}"

    os.umask(0o077)
    ROOT.mkdir(parents=True, exist_ok=True)
    lock = open(ROOT / "deploy.lock", "w")
    import fcntl
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        # Two deployments at once would race on `kubectl set image` and leave
        # the recorded tag disagreeing with the running one.
        raise SystemExit(75)

    directory = ROOT / "releases" / component / release
    directory.mkdir(parents=True, exist_ok=True)
    stored = directory / "image.sha256"
    if stored.exists() and stored.read_text().strip() != checksum:
        fail("An immutable release already exists with a different checksum")

    handle, temporary = tempfile.mkstemp(dir=directory, prefix="upload.")
    os.close(handle)
    temporary = pathlib.Path(temporary)
    try:
        receive(temporary)
        if checksum_of(temporary) != checksum:
            fail("Checksum mismatch; deployment not started")
        assert_archive_holds_only(temporary, image)

        # Docker's containerd and k3s's are separate stores, so this is an
        # import rather than a `docker load`. -n k8s.io is the namespace the
        # kubelet looks in; an import into the default namespace is invisible
        # to it and the pod fails with ErrImageNeverPull.
        imported = subprocess.run(
            ["k3s", "ctr", "-n", "k8s.io", "images", "import", str(temporary)],
            capture_output=True, text=True, timeout=300,
        )
        if imported.returncode:
            fail(f"IMAGE_IMPORT_FAILED: {imported.stderr.strip()}")
        listed = subprocess.run(
            ["k3s", "ctr", "-n", "k8s.io", "images", "ls", "-q"],
            capture_output=True, text=True, timeout=60,
        )
        if f"docker.io/library/{image}" not in listed.stdout and image not in listed.stdout:
            fail("IMAGE_MISSING_AFTER_IMPORT")

        temporary.replace(directory / "image.tar.gz")
        stored.write_text(checksum + "\n")
        temporary = None

        current = ROOT / f"current-{component}"
        previous = current.read_text().strip() if current.exists() else ""
        if previous == image:
            # A recorded tag is not proof the workload is healthy, so re-check
            # rather than reporting success from a file.
            status = kubectl("rollout", "status", workload, "--timeout=60s")
            if status.returncode:
                fail("SAME_RELEASE_UNHEALTHY: recorded version is not healthy", 1)
            print(f"ALREADY_DEPLOYED {image}")
            return

        applied = kubectl("set", "image", workload, f"{container}={image}")
        if applied.returncode:
            fail(f"SET_IMAGE_FAILED: {applied.stderr.strip()}", 78)

        rollout = kubectl("rollout", "status", workload, "--timeout=180s")
        log = directory / "deploy.log"
        log.write_text(applied.stdout + applied.stderr + rollout.stdout + rollout.stderr)
        if rollout.returncode == 0:
            current.write_text(image + "\n")
            print(f"DEPLOYED {image}")
            return

        print("Rollout did not become ready; rolling back", file=sys.stderr)
        undo = kubectl("rollout", "undo", workload)
        back = kubectl("rollout", "status", workload, "--timeout=180s")
        with log.open("a") as out:
            out.write(undo.stdout + undo.stderr + back.stdout + back.stderr)
        if undo.returncode or back.returncode:
            fail("ROLLBACK_FAILED: operator intervention required", 1)
        print(f"ROLLED_BACK {previous or 'previous revision'}", file=sys.stderr)
        raise SystemExit(1)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
