#!/usr/bin/env python3
"""music_preprocessing 을 JupyterHub 배치 서버에 배포하고 crontab 을 다시 건다.

그 서버는 SSH 가 닫혀 있고 80/443 만 열려 있다(확인함). 그래서 다른 배포와
달리 tar 를 밀어 넣지 못하고 JupyterHub HTTP API 로 간다.

  1. /hub/login                     아이디·비밀번호로 세션 쿠키를 받는다
  2. /hub/spawn/<user>              유저 서버를 띄운다 (브라우저와 같은 경로)
  3. /user/<user>/api/contents/...  코드 파일을 올린다
  4. /user/<user>/api/kernels + ws  uv sync 와 crontab 설치를 실행한다

**공용 서버다.** 팀의 다른 작업이 같은 홈을 쓴다. 그래서 이 스크립트는
두 가지를 지킨다.

  - 코드만 올린다. config.json · refresh_token.txt · registered.json ·
    processed.json 은 서버가 들고 있는 상태이고 여기서 절대 건드리지 않는다.
    registered.json 을 날리면 아티스트·앨범·곡이 서버에 전부 다시 만들어진다.
  - crontab 은 BEGIN/END 마커 사이만 갈아 끼운다. 남의 크론 항목은 그대로 둔다.

.python-version 도 올리지 않는다. 3.14 로 적혀 있는데 서버 기본 파이썬은 3.12 라
uv 가 3.14 를 새로 받으려 든다.

**API 토큰을 쓰지 않는다.** 이 허브는 토큰 발급이 불가능해서 사람이 브라우저로
하는 것과 같은 길로 간다 — `/hub/login` 에 아이디·비밀번호를 POST 하고 세션
쿠키를 받는다. 그래서 토큰 인증이면 필요 없었을 것이 둘 붙는다.

  - 로그인 실패를 상태 코드로 판별해야 한다. JupyterHub 는 틀린 비밀번호에
    401 이 아니라 **200 으로 로그인 폼을 다시 준다.** raise_for_status 만
    보면 실패를 성공으로 읽는다.
  - 쿠키 인증은 비-GET 요청마다 XSRF 토큰이 필요하다. 그런데 `_xsrf` 쿠키는
    허브(`/hub/`)와 유저 서버(`/user/<id>/`) 양쪽에서 같은 이름으로 내려온다.
    유저 서버 것을 골라 써야 한다 — 허브 것을 보내면 403 이다.

필요한 환경변수:
  MUPID_JUPYTER_BASE_URL   https://lab.tailf237b8.ts.net
  MUPID_JUPYTER_USER       j15a204
  MUPID_JUPYTER_PASSWORD   그 계정의 비밀번호. **File 형식·보호된** CI 변수로
                           넣는다 — 8자 미만이면 GitLab 이 마스킹을 거부하는데,
                           File 형식은 환경에 경로만 들어가 그 문제가 없다.
                           Variable 형식으로 넣어도 읽는다(read_secret 참고).
  MUPID_JUPYTER_DIR        홈 기준 상대 경로 (기본 music_preprocessing)

  --dry-run 을 주면 무엇을 올릴지만 찍고 아무것도 바꾸지 않는다.
"""
import argparse
import base64
import json
import os
import pathlib
import re
import sys
import time
import uuid

import requests
import websocket

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "music_preprocessing"

# 올리는 것. 여기 없는 것은 올라가지 않는다 — 열거가 아니라 제외 목록으로 하면
# 서버의 상태 파일과 이름이 겹치는 순간 덮어쓰게 된다.
UPLOAD = (
    "pyproject.toml",
    "uv.lock",
    "README.md",
    "crontab.sh",
    "crontab.example.sh",
    "config.example.json",
    "sidecar.example.json",
)
UPLOAD_TREES = ("src",)
# 서버가 들고 있는 상태. 실수로 목록에 들어와도 여기서 한 번 더 막는다.
NEVER = {
    "config.json", "refresh_token.txt", "registered.json", "processed.json",
    ".python-version", ".env",
}

MARKER_BEGIN = "# BEGIN music_preprocessing"
MARKER_END = "# END music_preprocessing"


def die(message):
    raise SystemExit(f"배포 실패: {message}")


def read_secret(value):
    """CI 변수를 읽는다. File 형식이면 값이 아니라 **경로**가 온다.

    GitLab 은 8자 미만인 값을 마스킹해 주지 않는다("The value must have 8
    characters"). 마스킹 안 된 변수는 job 이 한 번이라도 출력하면 그대로 로그에
    남고 그 로그는 프로젝트 구성원이 볼 수 있다. 이 세션에서 실제로 그렇게
    개인키가 샌 적이 있다.

    File 형식이면 그 문제가 사라진다 — 환경에 들어가는 것이 내용이 아니라
    경로라서 `echo $VAR` 가 경로만 찍는다. 길이·문자 제한도 없다. 이 저장소는
    배포 SSH 키를 이미 그렇게 넣고 있고(infra_k8s/ci/deploy-k8s.sh 의
    read_secret), 여기도 같은 처리를 둔다. 둘 다 받아 주므로 어느 형식으로
    등록해도 돈다.
    """
    try:
        path = pathlib.Path(value)
        if path.is_file():
            value = path.read_text(encoding="utf-8")
    except (OSError, ValueError):
        # 경로로 볼 수 없는 문자열이면 값 그대로다.
        pass
    return value.strip("\r\n")


class Hub:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        self.user = user
        self.session = requests.Session()
        self._login(password)

    def _url(self, path):
        return f"{self.base}/user/{self.user}/api/{path}"

    def _login(self, password):
        """브라우저가 하는 것과 같은 폼 로그인. 비밀번호는 어디에도 찍지 않는다."""
        page = self.session.get(f"{self.base}/hub/login", timeout=30)
        page.raise_for_status()
        found = re.search(r'name="_xsrf"\s+value="([^"]+)"', page.text)
        if not found:
            die("로그인 폼에 _xsrf 가 없다. JupyterHub 쪽이 바뀌었는지 본다.")
        done = self.session.post(
            f"{self.base}/hub/login",
            data={"_xsrf": found.group(1), "username": self.user,
                  "password": password},
            allow_redirects=False, timeout=60,
        )
        # 틀린 비밀번호는 401 이 아니라 200 + 로그인 폼이다. 리다이렉트가
        # 왔는지로 본다.
        if done.status_code not in (302, 303):
            die("로그인이 되지 않았다. 아이디·비밀번호를 확인한다 "
                f"(응답 {done.status_code})")

    def _xsrf(self):
        """유저 서버의 _xsrf 를 고른다. 허브 것과 이름이 같아 경로로 가른다."""
        token = self.session.cookies.get("_xsrf", path=f"/user/{self.user}/")
        if not token:
            die("유저 서버의 _xsrf 쿠키가 없다. 서버가 떠 있는지 본다.")
        return token

    def start_server(self, timeout=180):
        """유저 서버가 떠 있어야 contents·kernels 가 응답한다.

        /hub/api 는 토큰을 요구하므로 쓰지 않는다. 브라우저처럼 유저 서버를
        직접 열어 스폰을 유도하고, contents API 가 답할 때까지 기다린다."""
        deadline = time.time() + timeout
        first = None
        while time.time() < deadline:
            got = self.session.get(
                self._url(f"contents/?content=0"), timeout=60)
            if got.status_code == 200:
                return "이미 떠 있음" if first is None else "새로 띄움"
            if first is None:
                first = got.status_code
                # 스폰을 시킨다. 리다이렉트를 따라가면 spawn-pending 을 거친다.
                self.session.get(f"{self.base}/hub/spawn/{self.user}", timeout=120)
            time.sleep(3)
        die(f"{timeout}초 안에 유저 서버가 준비되지 않았다 (첫 응답 {first})")

    def _headers(self):
        # 토큰 인증이면 필요 없다. 쿠키 인증이라 비-GET 마다 붙여야 한다.
        return {"X-XSRFToken": self._xsrf()}

    def ensure_directory(self, path):
        got = self.session.get(self._url(f"contents/{path}"), timeout=30)
        if got.status_code == 200:
            return
        self.session.put(
            self._url(f"contents/{path}"),
            json={"type": "directory"}, headers=self._headers(), timeout=30,
        ).raise_for_status()

    def put_file(self, path, data):
        body = {
            "type": "file",
            "format": "base64",
            "content": base64.b64encode(data).decode(),
        }
        done = self.session.put(self._url(f"contents/{path}"), json=body,
                                headers=self._headers(), timeout=120)
        if done.status_code not in (200, 201):
            die(f"{path} 업로드 실패: {done.status_code} {done.text[:200]}")

    def run(self, code, timeout=900):
        """커널에서 파이썬을 실행하고 stdout 을 모아 돌려준다."""
        made = self.session.post(self._url("kernels"), json={},
                                 headers=self._headers(), timeout=60)
        made.raise_for_status()
        kernel = made.json()["id"]
        ws_base = self.base.replace("https://", "wss://").replace("http://", "ws://")
        # 웹소켓도 같은 세션으로 붙어야 한다. requests 의 쿠키를 그대로 넘긴다.
        cookies = "; ".join(f"{c.name}={c.value}" for c in self.session.cookies)
        sock = websocket.create_connection(
            f"{ws_base}/user/{self.user}/api/kernels/{kernel}/channels",
            cookie=cookies,
            header=[f"X-XSRFToken: {self._xsrf()}"],
            timeout=timeout,
        )
        message_id = uuid.uuid4().hex
        sock.send(json.dumps({
            "header": {"msg_id": message_id, "username": "ci", "session": uuid.uuid4().hex,
                       "msg_type": "execute_request", "version": "5.3"},
            "parent_header": {}, "metadata": {},
            "content": {"code": code, "silent": False, "store_history": False,
                        "user_expressions": {}, "allow_stdin": False},
        }))
        output, status, idle = [], None, False
        deadline = time.time() + timeout
        try:
            while time.time() < deadline:
                message = json.loads(sock.recv())
                if message.get("parent_header", {}).get("msg_id") != message_id:
                    continue
                kind = message["msg_type"]
                content = message["content"]
                if kind == "stream":
                    output.append(content["text"])
                elif kind == "error":
                    output.append("\n".join(content.get("traceback", [])))
                    status = "error"
                elif kind == "execute_reply":
                    status = status or content.get("status")
                elif kind == "status" and content.get("execution_state") == "idle":
                    idle = True
                # execute_reply 와 idle 은 순서가 보장되지 않는다. 둘 중 하나만
                # 보고 끊으면 나머지를 기다리다 타임아웃까지 매달린다.
                if status and idle:
                    break
        finally:
            sock.close()
            self.session.delete(self._url(f"kernels/{kernel}"),
                                headers=self._headers(), timeout=30)
        text = "".join(output)
        if status != "ok":
            die(f"원격 실행이 실패했다 ({status}):\n{text}")
        return text


def files_to_upload():
    found = []
    for name in UPLOAD:
        path = SOURCE / name
        if path.is_file():
            found.append((name, path))
    for tree in UPLOAD_TREES:
        for path in sorted((SOURCE / tree).rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts:
                found.append((path.relative_to(SOURCE).as_posix(), path))
    for name, _ in found:
        if pathlib.PurePosixPath(name).name in NEVER:
            die(f"{name} 은 서버 상태 파일이다. 올리면 안 된다.")
    return found


# 원격에서 도는 코드. crontab 은 마커 사이만 갈아 끼운다.
REMOTE = r'''
import os, shutil, subprocess, pathlib
directory = pathlib.Path.home() / {directory!r}
uv = shutil.which("uv") or str(pathlib.Path.home() / ".local/bin/uv")
if not pathlib.Path(uv).exists():
    raise SystemExit("uv 를 찾지 못했다. 서버에 uv 가 설치되어 있어야 한다.")
print("uv:", uv)

done = subprocess.run([uv, "sync", "--frozen", "--python", "3.12"],
                      cwd=directory, capture_output=True, text=True)
print("uv sync:", done.returncode)
print(done.stdout[-2000:], done.stderr[-2000:])
if done.returncode:
    raise SystemExit("uv sync 실패")

check = subprocess.run([uv, "run", "music-preprocessing", "--help"],
                       cwd=directory, capture_output=True, text=True)
if check.returncode:
    raise SystemExit("CLI 가 뜨지 않는다:\n" + check.stderr[-2000:])
print("CLI ok")

block = (directory / "crontab.sh").read_text()
block = block.split({begin!r}, 1)[1].split({end!r}, 1)[0]
block = {begin!r} + block + {end!r}
block = block.replace("{{{{UV}}}}", uv).replace("{{{{DIR}}}}", str(directory))

current = subprocess.run(["crontab", "-l"], capture_output=True, text=True)
existing = current.stdout if current.returncode == 0 else ""
if {begin!r} in existing and {end!r} in existing:
    head, rest = existing.split({begin!r}, 1)
    tail = rest.split({end!r}, 1)[1]
    updated = head + block + tail
    action = "기존 블록 교체"
else:
    updated = (existing.rstrip("\n") + "\n\n" if existing.strip() else "") + block + "\n"
    action = "블록 추가"
written = subprocess.run(["crontab", "-"], input=updated,
                         capture_output=True, text=True)
if written.returncode:
    raise SystemExit("crontab 설치 실패:\n" + written.stderr[-2000:])
print("crontab:", action)
back = subprocess.run(["crontab", "-l"], capture_output=True, text=True).stdout
assert {begin!r} in back and {end!r} in back, "설치했는데 되읽으면 없다"
print("설치된 항목:")
for line in back.splitlines():
    if line.strip() and not line.lstrip().startswith("#"):
        print("   ", line)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true",
                        help="올릴 목록만 찍고 서버를 건드리지 않는다")
    args = parser.parse_args()

    uploads = files_to_upload()
    directory = os.environ.get("MUPID_JUPYTER_DIR", "music_preprocessing")
    if args.dry_run:
        print(f"{len(uploads)}개 파일을 ~/{directory} 로 올린다:")
        for name, path in uploads:
            print(f"  {name}  ({path.stat().st_size}B)")
        print("\n건드리지 않는 것:", ", ".join(sorted(NEVER)))
        return 0

    base = os.environ.get("MUPID_JUPYTER_BASE_URL") or die("MUPID_JUPYTER_BASE_URL 이 없다")
    user = os.environ.get("MUPID_JUPYTER_USER") or die("MUPID_JUPYTER_USER 가 없다")
    password = read_secret(
        os.environ.get("MUPID_JUPYTER_PASSWORD") or die("MUPID_JUPYTER_PASSWORD 가 없다"))
    if not password:
        die("MUPID_JUPYTER_PASSWORD 가 비어 있다 (File 형식이면 파일이 비었는지 본다)")

    hub = Hub(base, user, password)
    print("유저 서버:", hub.start_server())

    # 부모부터 만든다. src/music_preprocessing 을 먼저 요청하면 404 다.
    hub.ensure_directory(directory)
    trees = {pathlib.PurePosixPath(n).parent.as_posix() for n, _ in uploads}
    for tree in sorted(t for t in trees if t not in (".", "")):
        parts = tree.split("/")
        for depth in range(len(parts)):
            hub.ensure_directory(f"{directory}/{'/'.join(parts[:depth + 1])}")

    for name, path in uploads:
        hub.put_file(f"{directory}/{name}", path.read_bytes())
    print(f"올린 파일 {len(uploads)}개")

    print(hub.run(REMOTE.format(
        directory=directory, begin=MARKER_BEGIN, end=MARKER_END)))
    print("배포 완료")
    return 0


if __name__ == "__main__":
    sys.exit(main())
