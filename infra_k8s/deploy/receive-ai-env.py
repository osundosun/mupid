#!/usr/bin/env python3
"""추천 서버 환경변수 수신기. /usr/local/sbin/mupid-receive-ai-env 로 설치한다.

ssh-entrypoint-k8s.sh 를 거쳐서만 닿는다. 인자 모양은 거기서 이미 한 번 맞췄지만
여기서 다시 본다 — root 로 도는 코드가 호출자를 믿으면 안 된다.

  argv:  <40-hex 커밋> <64-hex sha256>
  stdin: `KEY=VALUE` 줄들 (GitLab File 변수 MUPID_AI_ENV 의 내용)

**왜 `mupid-secrets` 와 나누나.** 추천 서버가 Spring 내부 카탈로그 API 를 부르게
되면서 `ROLE_SYSTEM` 리프레시 토큰을 들고 있어야 한다. 그 값을 공용 Secret 에 섞으면
Spring 파드도 함께 받는다 — 쓰지 않는 자격을 쥐여 주는 것이고, 한 곳이 새면 둘 다
새는 모양이 된다. 재시작 범위도 나뉜다: 여기서는 **ai 만** 롤아웃한다.

`receive-env.py` 와 같은 구조다. CI 에 secrets 권한을 주지 않으려고 노드의 root 가
대신 쓴다 — 열면 CI 키를 훔친 쪽이 DB 비밀번호·JWT 서명키를 전부 읽게 된다.
"""
import hashlib
import os
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path("/srv/mupid-k8s")
ENV = ROOT / "ai.env"
NAMESPACE = "mupid"
SECRET = "mupid-ai-secrets"
KUBECONFIG = "/etc/rancher/k3s/k3s.yaml"
SIZE_LIMIT = 65536  # 64 KiB. 항목이 열 개 남짓이라 이보다 클 이유가 없다.

RELEASE = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")
KEY = re.compile(r"^MUPID_[A-Z0-9_]+$")

# 이 둘이 없으면 추천 서버가 **뜨지 못한다.** 카탈로그를 못 읽으면 장르·무드·보컬
# 조건이 통째로 빈 채 추천이 나가므로, 그 상태로 뜨지 않게 기동에서 막아 두었다
# (`ai_server/app/main.py`). 그러니 잘린 업로드는 여기서 거부한다.
REQUIRED = {"MUPID_API_BASE_URL", "MUPID_API_REFRESH_TOKEN"}


def fail(message, code=65):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def kubectl(*args, timeout=120, stdin=None):
    return subprocess.run(
        ["kubectl", "-n", NAMESPACE, *args],
        env={**os.environ, "KUBECONFIG": KUBECONFIG},
        input=stdin, capture_output=True, text=True, timeout=timeout,
    )


def receive():
    body = sys.stdin.buffer.read(SIZE_LIMIT + 1)
    if not body:
        fail("빈 업로드")
    if len(body) > SIZE_LIMIT:
        fail("내용이 너무 크다")
    return body


def parse(body):
    """`KEY=VALUE` 만 받는다. **값은 검사하지 않고 출력하지도 않는다.**

    키 이름만 본다 — 값을 한 글자라도 로그에 실으면 그것이 곧 유출이다.
    """
    pairs = {}
    for raw in body.decode("utf-8").replace("\r", "").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail("KEY=VALUE 가 아닌 줄이 있다")
        key, _, value = line.partition("=")
        key = key.strip()
        if not KEY.match(key):
            fail(f"허용하지 않는 키 이름: {key[:40]}")
        pairs[key] = value.strip()
    missing = sorted(REQUIRED - set(pairs))
    if missing:
        fail(f"필수 항목이 없다: {', '.join(missing)}")
    empty = sorted(k for k in REQUIRED if not pairs[k])
    if empty:
        # 빈 값으로 덮으면 파드가 뜨다가 죽는다. 키가 있는 것과 값이 있는 것은 다르다.
        fail(f"필수 항목이 비어 있다: {', '.join(empty)}")
    return pairs


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        raise SystemExit(64)
    release, checksum = sys.argv[1:]
    if not RELEASE.match(release) or not CHECKSUM.match(checksum):
        raise SystemExit(64)

    body = receive()
    if hashlib.sha256(body).hexdigest() != checksum:
        fail("체크섬이 맞지 않는다")
    pairs = parse(body)

    ROOT.mkdir(parents=True, exist_ok=True)
    before = ENV.read_bytes() if ENV.exists() else b""
    if before == body:
        # 같은 내용이면 아무것도 하지 않는다. master 푸시마다 돌려도 ai 가 매번
        # 재시작하지 않게 하려는 것이다.
        print(f"UNCHANGED {release}")
        return 0

    os.umask(0o077)                       # root 만 읽는다. 토큰이 들어 있다.
    with tempfile.NamedTemporaryFile(dir=ROOT, delete=False) as staged:
        staged.write(body)
        temp = pathlib.Path(staged.name)
    temp.chmod(0o600)
    temp.replace(ENV)                     # 원자적 교체

    made = kubectl("create", "secret", "generic", SECRET,
                   f"--from-env-file={ENV}", "--dry-run=client", "-o", "yaml")
    if made.returncode:
        fail(f"SECRET_RENDER_FAILED: {made.stderr.strip()[:400]}")
    applied = kubectl("apply", "-f", "-", stdin=made.stdout)
    if applied.returncode:
        fail(f"SECRET_APPLY_FAILED: {applied.stderr.strip()[:400]}")

    # 파드는 Secret 이 바뀌어도 스스로 다시 읽지 않는다. env 로 주입된 값은 프로세스
    # 시작 때 한 번 읽히고 끝이다. **ai 만** 재시작한다 — 이 Secret 을 읽는 것은
    # 그쪽뿐이고, 공용 Secret 과 달리 api 를 함께 흔들 이유가 없다.
    present = kubectl("get", "deployment/ai", "-o", "name")
    if present.returncode:
        print("건너뜀: deployment/ai 가 없다", file=sys.stderr)
    else:
        restarted = kubectl("rollout", "restart", "deployment/ai")
        if restarted.returncode:
            fail(f"RESTART_FAILED: {restarted.stderr.strip()[:400]}")
        waited = kubectl("rollout", "status", "deployment/ai",
                         "--timeout=300s", timeout=330)
        if waited.returncode:
            fail(f"ROLLOUT_FAILED: {waited.stderr.strip()[:400]}")

    # **키 이름만 센다.** 값은 어디에도 찍지 않는다.
    print(f"APPLIED {release}")
    print(f"  항목 {len(pairs)}개: {', '.join(sorted(pairs))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
