#!/usr/bin/env python3
"""운영 환경변수 수신기. /usr/local/sbin/mupid-receive-env 로 설치한다.

ssh-entrypoint-k8s.sh 를 거쳐서만 닿는다.

  argv:  <40-hex 커밋> <64-hex sha256>
  stdin: KEY=VALUE 줄로 된 .env 내용

**CI 에 secrets 권한을 주지 않으려고 여기서 쓴다.** 배포 계정의 RBAC 은
secrets 를 명시적으로 막고 있고(rbac.yaml), 그것을 열면 CI 키를 훔친 쪽이 DB
비밀번호·JWT 서명키·S3 키를 전부 읽을 수 있다. CI 는 내용을 보내기만 하고
Secret 을 만드는 것은 노드의 root 인 이쪽이다.

내용이 예전과 같으면 **아무것도 하지 않는다.** 그래야 master 푸시마다 이
job 을 돌려도 api 가 매번 재시작하지 않는다.
"""
import hashlib
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path("/srv/mupid-k8s")
ENV = ROOT / ".env"
NAMESPACE = "mupid"
SECRET = "mupid-secrets"
# root 로 돌므로 관리자 kubeconfig 를 쓴다. 배포 계정의 좁은 것(deploy.kubeconfig)
# 으로는 Secret 을 만들 수 없고, 만들 수 있게 넓히지 않으려고 이 구조를 택했다.
KUBECONFIG = "/etc/rancher/k3s/k3s.yaml"
SIZE_LIMIT = 262144  # 256 KiB. .env 가 이보다 클 이유가 없다.

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")
KEY = re.compile(r"^MUPID_[A-Z0-9_]+$")

# 매니페스트가 참조하는 키. 하나라도 빠진 내용을 받으면 거부한다 — 잘린 업로드가
# Secret 을 갈아엎으면 파드가 CreateContainerConfigError 로 멈춘다.
REQUIRED = {
    "MUPID_MYSQL_ROOT_PASSWORD", "MUPID_MYSQL_PASSWORD", "MUPID_JWT_SECRET",
    "MUPID_GMAIL_CLIENT_ID", "MUPID_GMAIL_CLIENT_SECRET", "MUPID_GMAIL_TOKEN",
    "MUPID_GMAIL_SEND_ADDRESS", "MUPID_S3_ENDPOINT", "MUPID_S3_ACCESS_KEY",
    "MUPID_S3_SECRET", "MUPID_S3_BUCKET", "MUPID_CLICKHOUSE_PASSWORD",
    "MUPID_KAFKA_CLUSTER_ID",
}


def fail(message, code=65):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def kubectl(*args, timeout=120, stdin=None):
    return subprocess.run(
        ["kubectl", "-n", NAMESPACE, *args],
        env={**os.environ, "KUBECONFIG": KUBECONFIG},
        input=stdin, capture_output=True, text=True, timeout=timeout,
    )


def parse(text):
    """.env 를 읽는다. 값은 어디에도 찍지 않는다 — 줄 번호만 말한다."""
    values = {}
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{number}번 줄에 '=' 가 없다 (내용은 찍지 않는다)", 64)
        name, value = line.split("=", 1)
        name = name.strip()
        if not KEY.match(name):
            # 이름은 찍어도 된다. 값이 아니다.
            fail(f"{number}번 줄의 이름이 MUPID_* 가 아니다: {name[:40]}", 64)
        if name in values:
            fail(f"{name} 이 두 번 있다", 64)
        values[name] = value.strip()
    return values


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        raise SystemExit(64)
    release, checksum = sys.argv[1:]
    if not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)

    body = sys.stdin.buffer.read(SIZE_LIMIT + 1)
    if len(body) > SIZE_LIMIT:
        fail(".env 가 너무 크다", 64)
    if hashlib.sha256(body).hexdigest() != checksum:
        fail("Checksum mismatch; 아무것도 바꾸지 않았다")

    text = body.decode("utf-8").replace("\r\n", "\n")
    values = parse(text)
    missing = sorted(REQUIRED - set(values))
    if missing:
        fail("필수 키가 빠졌다: " + ", ".join(missing), 64)
    # 기능 하나가 죽을 뿐 서비스는 뜨는 것들. 막지 않고 알리기만 한다 —
    # 여기서 실패시키면 키를 넣기 전까지 master 푸시가 계속 빨개진다.
    for name, effect in (("MUPID_AI_GMS_KEY", "/api/v1/ai/chat 이 503"),):
        if not values.get(name):
            print(f"경고: {name} 이 비어 있다 — {effect}")

    # 정규화한 내용으로 비교한다. 줄 순서나 주석만 달라진 것으로 api 를
    # 재시작하지 않는다.
    canonical = "".join(f"{k}={values[k]}\n" for k in sorted(values))
    old = parse(ENV.read_text(encoding="utf-8")) if ENV.exists() else {}
    previous = "".join(f"{k}={old[k]}\n" for k in sorted(old))
    if canonical == previous:
        print("UNCHANGED 환경변수가 예전과 같다. 아무것도 하지 않는다.")
        return 0

    added = sorted(set(values) - set(old))
    removed = sorted(set(old) - set(values))
    changed = sorted(k for k in set(values) & set(old) if values[k] != old[k])

    os.umask(0o077)
    ROOT.mkdir(parents=True, exist_ok=True)
    if ENV.exists():
        ENV.replace(ROOT / f".env.{time.strftime('%Y%m%d-%H%M%S')}.bak")

    handle, temporary = tempfile.mkstemp(dir=ROOT, prefix=".env.")
    os.close(handle)
    temporary = pathlib.Path(temporary)
    temporary.write_text(canonical, encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(ENV)

    # Secret 은 교체한다. kubectl create --dry-run 으로 만들어 apply 하면
    # 없어진 키까지 정확히 반영된다 — patch 로는 지워지지 않는다.
    made = kubectl("create", "secret", "generic", SECRET,
                   f"--from-env-file={ENV}", "--dry-run=client", "-o", "yaml")
    if made.returncode:
        fail(f"SECRET_RENDER_FAILED: {made.stderr.strip()[:400]}")
    applied = kubectl("apply", "-f", "-", stdin=made.stdout)
    if applied.returncode:
        fail(f"SECRET_APPLY_FAILED: {applied.stderr.strip()[:400]}")

    # 파드는 Secret 이 바뀌어도 스스로 다시 읽지 않는다. env 로 주입된 값은
    # 프로세스 시작 때 한 번 읽히고 끝이다.
    #
    # **순서가 중요하다 — api 를 먼저, ai 를 나중에.** 둘은 `/internal` 공유 비밀을
    # 같은 Secret 에서 읽는다(Spring 은 MUPID_RECOMMENDER_INTERNAL_TOKEN, 추천
    # 서버는 MUPID_AI_INTERNAL_TOKEN). 추천 서버를 먼저 올리면 새 토큰을 요구하는데
    # Spring 은 아직 옛 토큰을 보내 **그 사이 추천이 전부 401** 이다.
    #
    # 반대 순서에는 그 구간이 없다. 토큰이 비어 있으면 추천 서버가 인증을 아예
    # 건너뛰므로, 먼저 올라온 Spring 이 새 토큰을 보내도 옛 추천 서버는 그냥 받는다.
    for workload in ("deployment/api", "deployment/ai"):
        present = kubectl("get", workload, "-o", "name")
        if present.returncode:
            # 아직 없는 워크로드는 건너뛴다. 다만 조용히 넘기지 않는다 —
            # 재시작한 줄 알고 있으면 옛 값으로 도는 파드를 못 찾는다.
            print(f"건너뜀: {workload} 가 없다", file=sys.stderr)
            continue
        restarted = kubectl("rollout", "restart", workload)
        if restarted.returncode:
            fail(f"RESTART_FAILED({workload}): {restarted.stderr.strip()[:400]}")
        waited = kubectl("rollout", "status", workload, "--timeout=300s", timeout=330)
        if waited.returncode:
            fail(f"ROLLOUT_FAILED({workload}): {waited.stderr.strip()[:400]}")

    print(f"APPLIED {release}")
    print(f"  추가 {len(added)}개: {', '.join(added) or '-'}")
    print(f"  변경 {len(changed)}개: {', '.join(changed) or '-'}")
    print(f"  삭제 {len(removed)}개: {', '.join(removed) or '-'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
