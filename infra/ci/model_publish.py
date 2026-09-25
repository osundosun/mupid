#!/usr/bin/env python3
"""`models.lock` 대로 모델 묶음을 대조·게시·굽기·채택한다 (S15P21A204-454, 설계 §6.3).

`models.lock` 이 운영에 쓸 모델의 정본이다. 학습은 JupyterHub 의 `~/mupid_models/stage1/v{N}/`
에 묶음을 만들기만 하고, 운영 반영의 방아쇠는 이 파일을 바꾸는 MR 의 병합 하나다.

    verify   MR 파이프라인. JupyterHub 에서 받아 sha256 을 대조하고 manifest 의 지표를 남긴다.
             **올리지 않는다.**
    publish  master 파이프라인(models.lock 이 바뀐 커밋). 받기 → 대조(다르면 멈춘다) →
             관문(재계산이 전 구간에 채워졌나) → 배치용 Object Storage API 3단계 → CANDIDATE
    bake     AI 이미지 빌드. 서빙 파일만 Object Storage 에서 받아(X-Accel-Redirect) 대조하고
             이미지의 모델 자리에 `model.json` 과 함께 둔다. **JupyterHub 에 기대지 않는다** —
             모델이 바뀌지 않는 평소 배포가 JupyterHub 장애에 끌려가지 않게.
    adopt    AI 롤아웃 성공 뒤. `POST /api/v1/system/models/{name}/{version}/adopt`

`models.lock` 에 채택된 버전이 없으면(`stage1: null`, 또는 파일이 없으면) 넷 다 아무것도 하지
않고 성공한다 — 이미지는 지금처럼 판의 가중치로 돈다. 그 상태가 이 스크립트의 첫 커밋이다.

**JupyterHub 는 공용 서버이고 여기서는 읽기만 한다.** `deploy-jupyter.py` 의 `Hub` 를 그대로
쓴다(폼 로그인·유저 서버 띄우기·contents API). 상태 파일도 학습 산출물도 쓰지 않는다 — 부르는
것은 `GET contents/…` 뿐이다.

자격 (값은 어디에도 찍지 않는다)
    MUPID_DATA_ENV                  File. JupyterHub 접속 (deploy-jupyter.py 와 같은 변수)
    MUPID_SYSTEM_CI_REFRESH_TOKEN   `system-ci` 의 장기 리프레시 토큰 (ROLE_SYSTEM 은 회전하지
                                    않는다 — 설계 §11 미결 10 의 결정). Variable·File 둘 다 받는다
    MUPID_K8S_BASE_URL              Spring 앞단 주소 (배포 확인과 같은 값)
    MUPID_MODEL_RECOMPUTE_GATE      `true` 면 publish 가 재계산 완료를 Spring 에 묻는다. 그 API 는
                                    457(벡터 등록)이 만든다 — 그때까지 기본은 꺼짐이고 경고만 찍는다

표준 라이브러리 + PyYAML 만 쓴다(Spring 쪽). `bake`·`adopt` 는 buildkit(alpine) 이미지에서 도는데
거기엔 requests 가 없다. JupyterHub 를 부르는 `verify`·`publish` 만 `deploy-jupyter.py` 를
불러오고, 그쪽이 requests·websocket-client 를 요구한다.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable, Optional

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK_PATH = ROOT / "models.lock"
MODEL_NAME = "stage1"
MANIFEST = "manifest.json"
# 이미지의 모델 자리에 함께 두는 표식. ai_server/app/model_bundle.py 가 읽는다.
BAKED_MANIFEST = "model.json"
# 서빙 파일 셋. ai_server 가 이 이름으로 찾는다(model_bundle.SERVING). 다른 이름을 서빙에 올리면
# 이미지에 들어가도 아무도 읽지 않는다 — 조용히 무시되는 것보다 lock 검사에서 멈추는 편이 낫다.
SERVING_NAMES = {"user_encoder.npz", "combiner.json", "onboarding_seeds.npz"}
# Spring 의 규칙과 같다 (api/docs/System/folder.yml). 여기서 먼저 보면 MR 에서 잡힌다.
VERSION_RE = re.compile(r"^v[0-9]{1,6}$")
FILE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
FILE_EXT = (".npz", ".npy", ".json", ".onnx")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")

TOKEN_ENV = "MUPID_SYSTEM_CI_REFRESH_TOKEN"
BASE_ENV = "MUPID_K8S_BASE_URL"
GATE_ENV = "MUPID_MODEL_RECOMPUTE_GATE"
DATA_ENV = "MUPID_DATA_ENV"


class Stop(SystemExit):
    """멈춘다. 메시지에 비밀값을 싣지 않는다."""

    def __init__(self, message: str):
        super().__init__(f"모델 게시 중단: {message}")


def log(message: str = "") -> None:
    print(message, flush=True)


# ───────────────────────────────────────────── models.lock

@dataclass(frozen=True)
class Lock:
    name: str
    version: str
    jupyter_dir: str
    files: dict           # 파일명 → sha256 (소문자 hex)
    serving: tuple

    @property
    def version_files(self) -> list:
        """453 이 ① 요청마다 요구하는 목록 = `files` 의 키 (순서는 상관없다)."""
        return sorted(self.files)


def load_lock(path: pathlib.Path = LOCK_PATH) -> Optional[Lock]:
    """채택된 버전이 없으면 None. 형식이 틀렸으면 멈춘다 — 틀린 lock 으로 굽지 않는다."""
    if not path.exists():
        return None
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise Stop("models.lock 의 최상위가 사전이 아니다")
    unknown = sorted(set(data) - {MODEL_NAME})
    if unknown:
        raise Stop(f"models.lock 에 모르는 모델이 있다: {unknown} (지금은 {MODEL_NAME} 하나)")
    entry = data.get(MODEL_NAME)
    if entry is None:
        return None
    if not isinstance(entry, dict):
        raise Stop(f"{MODEL_NAME} 은 null 이거나 사전이어야 한다")
    return validate(entry)


def validate(entry: dict) -> Lock:
    errors = []
    version = str(entry.get("version") or "")
    if not VERSION_RE.match(version):
        errors.append(f"version 이 v{{N}} 이 아니다: {version!r}")
    jdir = str(entry.get("jupyter_dir") or "")
    if not jdir or jdir.startswith("/") or ".." in jdir.split("/"):
        errors.append(f"jupyter_dir 은 홈 기준 상대 경로여야 한다: {jdir!r}")
    elif version and not jdir.rstrip("/").endswith(f"/{version}"):
        # 버전과 자리가 어긋나면 v3 라고 적고 v2 의 파일을 올리게 된다.
        errors.append(f"jupyter_dir 이 버전({version})으로 끝나지 않는다: {jdir!r}")
    raw_files = entry.get("files") or {}
    if not isinstance(raw_files, dict) or not raw_files:
        errors.append("files 가 비었다")
        raw_files = {}
    files = {}
    for name, sha in raw_files.items():
        name, sha = str(name), str(sha or "").lower()
        if not FILE_RE.match(name) or not name.endswith(FILE_EXT):
            errors.append(f"파일명이 규칙에 맞지 않는다(확장자 npz·npy·json·onnx): {name!r}")
        if not SHA_RE.match(sha):
            errors.append(f"{name} 의 sha256 이 hex 64자가 아니다")
        files[name] = sha
    if files and MANIFEST not in files:
        errors.append(f"files 에 {MANIFEST} 가 없다")
    if len(files) > 32:
        errors.append("files 는 32개까지다")
    serving = entry.get("serving") or []
    if not isinstance(serving, list) or not serving:
        errors.append("serving 이 비었다")
        serving = []
    for name in serving:
        if name not in files:
            errors.append(f"serving 의 {name} 이 files 에 없다")
        if name not in SERVING_NAMES:
            errors.append(f"serving 의 {name} 은 ai_server 가 읽는 이름이 아니다 "
                          f"({', '.join(sorted(SERVING_NAMES))})")
    if errors:
        raise Stop("models.lock 이 틀렸다\n  - " + "\n  - ".join(errors))
    return Lock(MODEL_NAME, version, jdir.rstrip("/"), files, tuple(serving))


# ───────────────────────────────────────────── 대조

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def verify_files(lock: Lock, directory: pathlib.Path, names=None) -> None:
    """받은 파일이 lock 의 sha256 과 같은가. **하나라도 다르면 멈춘다.**

    병합 뒤 JupyterHub 의 파일이 바뀌면 여기서 걸린다 — 리뷰한 것과 다른 바이트를 운영에
    올리지 않는다. 다른 파일을 모두 찍고 멈춘다(첫 하나만 찍으면 고치고 다시 돌리기를 반복한다).
    """
    wrong = []
    for name in (names if names is not None else lock.files):
        path = directory / name
        if not path.exists():
            wrong.append(f"{name}: 없다")
            continue
        got = sha256_file(path)
        if got != lock.files[name]:
            wrong.append(f"{name}: models.lock {lock.files[name][:12]}… ≠ 받은 파일 {got[:12]}…")
    if wrong:
        raise Stop("sha256 이 models.lock 과 다르다\n  - " + "\n  - ".join(wrong))


def manifest_summary(lock: Lock, directory: pathlib.Path) -> list:
    """manifest.json 의 지표를 줄로. 버전이 lock 과 다르면 멈춘다."""
    meta = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
    if not isinstance(meta, dict):
        raise Stop(f"{MANIFEST} 의 최상위가 사전이 아니다")
    if meta.get("version") not in (None, lock.version):
        raise Stop(f"{MANIFEST} 의 version({meta.get('version')!r}) 이 models.lock({lock.version}) 과 다르다")
    lines = []
    for key in sorted(meta):
        value = meta[key]
        if isinstance(value, dict):
            for sub in sorted(value):
                if not isinstance(value[sub], (dict, list)):
                    lines.append(f"{key}.{sub} = {value[sub]}")
        elif not isinstance(value, list):
            lines.append(f"{key} = {value}")
    return lines


def write_report(lock: Optional[Lock], lines: list, path: pathlib.Path) -> None:
    """MR 화면(artifacts:expose_as)에서 열리는 요약."""
    path.parent.mkdir(parents=True, exist_ok=True)
    if lock is None:
        body = ["# models.lock", "", "채택된 모델이 없다 (`stage1: null`) — 이미지는 판의 가중치로 돈다.", ""]
    else:
        body = [f"# models.lock — {lock.name} {lock.version}", "",
                f"JupyterHub `~/{lock.jupyter_dir}` · 서빙 {', '.join(lock.serving)}", "",
                "| 파일 | sha256 |", "|---|---|"]
        body += [f"| `{n}` | `{s[:16]}…` |" for n, s in sorted(lock.files.items())]
        body += ["", "## manifest.json", ""] + [f"- {line}" for line in lines] + [""]
    path.write_text("\n".join(body), encoding="utf-8", newline="\n")


# ───────────────────────────────────────────── 비밀

def read_secret(value: str) -> str:
    """File 형식이면 경로가 온다. 둘 다 받는다(deploy-jupyter.py 의 read_secret 과 같다)."""
    try:
        path = pathlib.Path(value)
        if path.is_file():
            value = path.read_text(encoding="utf-8")
    except (OSError, ValueError):
        pass
    return value.strip()


def require_env(name: str) -> str:
    raw = os.environ.get(name)
    if not raw:
        raise Stop(f"{name} 가 없다 — infra/ci/VARIABLES.md 를 본다")
    return read_secret(raw)


# ───────────────────────────────────────────── Spring (배치용 Object Storage API, 453)

class HttpError(Exception):
    def __init__(self, status: int, code: Optional[str], detail: str = ""):
        super().__init__(f"HTTP {status} {code or ''} {detail}".strip())
        self.status, self.code = status, code


class Spring:
    """`system-ci` 로 부른다. 리프레시 토큰으로 액세스 토큰을 받는다 — ROLE_SYSTEM 은 회전하지
    않으므로 받은 쿠키를 어디에 다시 쓸 필요가 없다. 토큰 값은 예외 메시지에도 싣지 않는다."""

    def __init__(self, base: str, refresh_token: str, opener=None):
        self.base = base.rstrip("/")
        self._refresh = refresh_token
        self._access: Optional[str] = None
        self._open = opener or urllib.request.urlopen

    @classmethod
    def from_env(cls) -> "Spring":
        return cls(os.environ.get(BASE_ENV) or "https://j15a204.p.ssafy.io", require_env(TOKEN_ENV))

    def _send(self, method, path, body=None, headers=None, stream_to=None):
        req = urllib.request.Request(self.base + path, data=body, method=method,
                                     headers=dict(headers or {}))
        try:
            with self._open(req, timeout=300) as res:
                if stream_to is not None:
                    h = hashlib.sha256()
                    with open(stream_to, "wb") as out:
                        for block in iter(lambda: res.read(1 << 20), b""):
                            out.write(block)
                            h.update(block)
                    return res.status, dict(res.headers), h.hexdigest()
                return res.status, dict(res.headers), res.read()
        except urllib.error.HTTPError as e:
            raw = e.read() or b""
            code = e.headers.get("X-Mupid-Error") if e.headers else None
            try:
                code = code or json.loads(raw).get("code")
            except Exception:
                pass
            raise HttpError(e.code, code, f"{method} {path}") from None

    def token(self) -> str:
        if self._access is None:
            try:
                _, _, raw = self._send("GET", "/api/v1/auth",
                                       headers={"Cookie": f"refresh_token={self._refresh}"})
            except HttpError as e:
                if e.status == 401:
                    raise Stop(f"{TOKEN_ENV} 로 액세스 토큰을 받지 못했다(401) — 만료됐거나 폐기됐다. "
                               "관리자가 system-ci 토큰을 새로 발급한다(api/docs/User)") from None
                raise
            self._access = json.loads(raw)["data"]["access_token"]
        return self._access

    def call(self, method, path, payload=None):
        headers = {"Authorization": f"Bearer {self.token()}"}
        body = None
        if payload is not None:
            body = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        _, _, raw = self._send(method, path, body, headers)
        return json.loads(raw).get("data") if raw else None

    def put_file(self, path, file: pathlib.Path):
        size = file.stat().st_size
        with file.open("rb") as f:
            # Content-Length 를 준다 — Spring 이 chunked 전송을 거절한다(UPLOAD_LENGTH_REQUIRED).
            self._send("PUT", path, f, {"Authorization": f"Bearer {self.token()}",
                                        "Content-Type": "application/octet-stream",
                                        "Content-Length": str(size)})

    def download(self, path, dest: pathlib.Path) -> str:
        _, _, digest = self._send("GET", path, headers={"Authorization": f"Bearer {self.token()}"},
                                  stream_to=dest)
        return digest


def publish(lock: Lock, directory: pathlib.Path, spring) -> str:
    """453 의 요청 → 업로드 → 완료. 이미 COMPLETED 인 파일은 건너뛴다(재실행이 멱등이다)."""
    status = None
    for name in lock.version_files:
        file = directory / name
        got = spring.call("POST", "/api/v1/system/objects", {
            "kind": "MODEL", "name": lock.name, "version": lock.version, "file_name": name,
            "size_bytes": file.stat().st_size, "sha256": lock.files[name],
            "version_files": lock.version_files})
        status = got.get("model_status")
        if got.get("status") == "COMPLETED":
            log(f"  {name}: 이미 올라가 확인됐다 — 건너뛴다")
            continue
        spring.put_file(got["upload_url"], file)
        done = spring.call("POST", f"/api/v1/system/objects/{got['object_id']}/complete")
        status = done.get("model_status")
        log(f"  {name}: 완료 ({file.stat().st_size}B)")
    if status not in ("CANDIDATE", "ADOPTED"):
        raise Stop(f"모든 파일을 올렸는데 버전 상태가 {status} 다 — Spring 의 version_files 를 본다")
    return status


def check_gate(lock: Lock, spring, enabled: bool) -> None:
    """관문 ④-c: 재계산(`stage1_v{N}`)이 전 구간에 채워졌는가.

    그 API 는 457(벡터 등록)이 만든다. 그때까지는 CI 변수 스위치로 끄고 켜며 기본은 꺼짐 —
    꺼져 있으면 **경고를 찍고 지나간다.** 켜면 아래 경로를 부르고 `complete` 가 참이 아니면
    멈춘다. 457 이 경로·응답을 다르게 정하면 여기를 함께 고친다.
    """
    if not enabled:
        log(f"경고: 재계산 관문이 꺼져 있다({GATE_ENV} != true). {lock.name}_{lock.version} 이 "
            "전 구간에 채워졌는지 확인하지 않고 올린다 — 457 이 확인 API 를 만든 뒤 켠다")
        return
    got = spring.call("GET", f"/api/v1/system/models/{lock.name}/{lock.version}/recompute")
    if not (got or {}).get("complete"):
        raise Stop(f"{lock.name}_{lock.version} 재계산이 끝나지 않았다: {got}")
    log(f"관문 통과 — {lock.name}_{lock.version} 재계산 완료")


def bake(lock: Optional[Lock], out: pathlib.Path, spring_factory: Callable) -> bool:
    """서빙 파일을 이미지의 모델 자리에 둔다. 채택된 버전이 없으면 아무것도 하지 않는다."""
    out.mkdir(parents=True, exist_ok=True)
    if lock is None:
        log("models.lock 에 채택된 모델이 없다 — 이미지는 판의 가중치로 돈다 (건너뜀)")
        return False
    spring = spring_factory()
    for name in lock.serving:
        digest = spring.download(
            f"/api/v1/system/models/{lock.name}/{lock.version}/files/{urllib.parse.quote(name)}",
            out / name)
        if digest != lock.files[name]:
            raise Stop(f"받은 {name} 의 sha256 이 models.lock 과 다르다")
        log(f"  {name}: 받음·대조 ({(out / name).stat().st_size}B)")
    (out / BAKED_MANIFEST).write_text(json.dumps({
        "name": lock.name, "version": lock.version,
        "files": {n: lock.files[n] for n in lock.serving}}, indent=2) + "\n",
        encoding="utf-8", newline="\n")
    log(f"이미지에 {lock.name} {lock.version} 서빙 파일 {len(lock.serving)}개를 굽는다")
    return True


def adopt(lock: Optional[Lock], spring_factory: Callable) -> None:
    if lock is None:
        log("models.lock 에 채택된 모델이 없다 — 채택 알림을 건너뛴다")
        return
    got = spring_factory().call("POST", f"/api/v1/system/models/{lock.name}/{lock.version}/adopt")
    log(f"채택 — {lock.name} {lock.version} (지금 쓰는 버전 {got.get('current_version')})")


# ───────────────────────────────────────────── JupyterHub (읽기만)

def _deploy_jupyter():
    spec = importlib.util.spec_from_file_location("deploy_jupyter", ROOT / "infra/ci/deploy-jupyter.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fetch_from_jupyter(lock: Lock, dest: pathlib.Path) -> None:
    """`~/{jupyter_dir}` 의 files 를 받는다. contents API 의 GET 만 쓴다."""
    dj = _deploy_jupyter()
    values = dj.load_env(required=True)
    missing = [k for k in ("JUPYTER_BASE_URL", "JUPYTER_USER", "JUPYTER_PASSWORD") if not values.get(k)]
    if missing:
        raise Stop(f"{DATA_ENV} 에 {', '.join(missing)} 가 없다")
    hub = dj.Hub(values["JUPYTER_BASE_URL"], values["JUPYTER_USER"], values["JUPYTER_PASSWORD"])
    log(f"유저 서버: {hub.start_server()}")
    dest.mkdir(parents=True, exist_ok=True)
    for name in lock.files:
        path = f"{lock.jupyter_dir}/{name}"
        got = hub.session.get(hub._url(f"contents/{urllib.parse.quote(path)}?content=1&format=base64"),
                              timeout=300)
        if got.status_code == 404:
            raise Stop(f"JupyterHub 에 ~/{path} 가 없다")
        got.raise_for_status()
        (dest / name).write_bytes(base64.b64decode(got.json()["content"]))
        log(f"  받음 ~/{path}")


# ───────────────────────────────────────────── 명령

def cmd_verify(args) -> int:
    lock = load_lock(args.lock)
    report = pathlib.Path(args.report)
    if lock is None:
        log("models.lock: 채택된 모델이 없다 (stage1: null) — 대조할 것이 없다")
        write_report(None, [], report)
        return 0
    log(f"models.lock: {lock.name} {lock.version} · 파일 {len(lock.files)}개 · 서빙 {', '.join(lock.serving)}")
    if not os.environ.get(DATA_ENV):
        # 보호 변수는 보호 브랜치 파이프라인에만 주어진다 — 기능 브랜치 MR 에서는 비어 있다.
        # 형식만 보고 지나간다. 같은 대조를 병합 뒤 model-publish 가 **멈추는 쪽으로** 다시 한다.
        log(f"경고: {DATA_ENV} 가 이 파이프라인에 없다(보호 변수). 형식만 확인했다 — "
            "sha256 대조는 병합 뒤 model-publish 가 한다")
        write_report(lock, ["(JupyterHub 자격이 없어 대조하지 않았다 — 병합 뒤 model-publish 가 한다)"],
                     report)
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        fetch_from_jupyter(lock, work)
        verify_files(lock, work)
        lines = manifest_summary(lock, work)
    log("sha256 이 models.lock 과 모두 같다")
    log("manifest.json:")
    for line in lines:
        log(f"  {line}")
    write_report(lock, lines, report)
    return 0


def cmd_publish(args) -> int:
    lock = load_lock(args.lock)
    if lock is None:
        log("models.lock: 채택된 모델이 없다 — 올릴 것이 없다")
        return 0
    gate = (os.environ.get(GATE_ENV) or "").lower() == "true"
    spring = Spring.from_env()
    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        fetch_from_jupyter(lock, work)
        verify_files(lock, work)
        for line in manifest_summary(lock, work):
            log(f"  {line}")
        check_gate(lock, spring, gate)
        status = publish(lock, work, spring)
    log(f"{lock.name} {lock.version}: {status}")
    return 0


def cmd_bake(args) -> int:
    bake(load_lock(args.lock), pathlib.Path(args.out), Spring.from_env)
    return 0


def cmd_adopt(args) -> int:
    adopt(load_lock(args.lock), Spring.from_env)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--lock", type=pathlib.Path, default=LOCK_PATH)
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify", help="MR: JupyterHub 에서 받아 대조한다. 올리지 않는다")
    v.add_argument("--report", default=".model/models-lock.md")
    sub.add_parser("publish", help="master: 대조 → 관문 → 업로드 → CANDIDATE")
    b = sub.add_parser("bake", help="AI 이미지: 서빙 파일을 받아 모델 자리에 둔다")
    b.add_argument("--out", required=True)
    sub.add_parser("adopt", help="롤아웃 뒤: 채택 알림")
    args = ap.parse_args(argv)
    return {"verify": cmd_verify, "publish": cmd_publish,
            "bake": cmd_bake, "adopt": cmd_adopt}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
