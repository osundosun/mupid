"""`models.lock` 과 model-publish CI (S15P21A204-454). 네트워크·JupyterHub·Spring 에 붙지 않는다.

    python -B -m unittest discover -s infra_k8s/tests -v

`infra/ci/model_publish.py` 를 대역(가짜 Spring)으로 지나간다. 보는 것:

  - 저장소의 models.lock 은 지금 "채택 없음" 이고, 그 상태에서 네 명령이 아무것도 하지 않는다
  - lock 형식 검사 (MR 에서 먼저 잡는다)
  - sha256 이 하나라도 다르면 멈춘다
  - 재계산 관문 스위치 — 꺼지면 경고만, 켜면 Spring 에 묻고 미완료면 멈춘다
  - 453 의 3단계 업로드 — version_files 를 싣고, 이미 COMPLETED 인 파일은 건너뛴다
  - 굽기: 서빙 파일만 받아 대조하고 model.json 을 쓴다
"""

import contextlib
import hashlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
CI = ROOT / "infra" / "ci"

spec = importlib.util.spec_from_file_location("model_publish", CI / "model_publish.py")
mp = importlib.util.module_from_spec(spec)
# dataclass 가 모듈을 sys.modules 에서 찾는다. 등록하지 않으면 정의하다 죽는다.
sys.modules["model_publish"] = mp
spec.loader.exec_module(mp)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


FILES = {
    "manifest.json": json.dumps({"version": "v3", "metrics": {"hit@10": 0.41},
                                 "embedding_version": "mert-v1-95m"}).encode(),
    "user_encoder.npz": b"encoder",
    "combiner.json": b'{"coef": [1]}',
    "onboarding_seeds.npz": b"seeds",
    "pca.npz": b"pca",
}
SERVING = ["user_encoder.npz", "combiner.json", "onboarding_seeds.npz"]


def lock_text(**over) -> str:
    entry = {"version": "v3", "jupyter_dir": "mupid_models/stage1/v3",
             "files": {n: sha(b) for n, b in FILES.items()}, "serving": SERVING}
    entry.update(over)
    return yaml.safe_dump({"stage1": entry}, sort_keys=False)


class Quiet:
    """출력을 모은다. 경고가 찍혔는지 본다."""

    def __enter__(self):
        self.buf = io.StringIO()
        self._cm = contextlib.redirect_stdout(self.buf)
        self._cm.__enter__()
        return self

    def __exit__(self, *exc):
        self._cm.__exit__(*exc)

    @property
    def text(self):
        return self.buf.getvalue()


class FakeSpring:
    """453 의 모양을 흉내낸다. 파일은 올라온 바이트로 sha256 을 다시 잰다."""

    def __init__(self, completed=(), gate=None, store=None):
        self.calls = []
        self.completed = set(completed)
        self.gate = gate
        self.store = dict(store or {})
        self.uploaded = {}
        self._objects = {}

    def call(self, method, path, payload=None):
        self.calls.append((method, path, payload))
        if path == "/api/v1/system/objects":
            oid = len(self._objects) + 1
            self._objects[oid] = payload
            done = payload["file_name"] in self.completed
            return {"object_id": oid, "status": "COMPLETED" if done else "UPLOADING",
                    "upload_url": None if done else f"/upload/system/{oid}",
                    "model_status": "UPLOADING"}
        if path.endswith("/complete"):
            oid = int(path.split("/")[-2])
            p = self._objects[oid]
            assert sha(self.uploaded[p["file_name"]]) == p["sha256"]
            self.completed.add(p["file_name"])
            declared = set(p["version_files"])
            return {"model_status": "CANDIDATE" if declared <= self.completed else "UPLOADING"}
        if path.endswith("/recompute"):
            return self.gate
        if path.endswith("/adopt"):
            return {"current_version": path.split("/")[-2]}
        raise AssertionError(path)

    def put_file(self, path, file):
        oid = int(path.rsplit("/", 1)[1])
        self.uploaded[self._objects[oid]["file_name"]] = file.read_bytes()

    def download(self, path, dest):
        name = path.rsplit("/", 1)[1]
        dest.write_bytes(self.store[name])
        return sha(self.store[name])


class Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = pathlib.Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def write_lock(self, text) -> pathlib.Path:
        path = self.tmp / "models.lock"
        path.write_text(text, encoding="utf-8")
        return path

    def bundle(self, files=FILES) -> pathlib.Path:
        d = self.tmp / "bundle"
        d.mkdir(exist_ok=True)
        for name, data in files.items():
            (d / name).write_bytes(data)
        return d


class RepositoryLockTest(Base):
    def test_the_committed_lock_adopts_nothing_yet(self):
        """첫 커밋은 "채택 없음" 이다 — 그 상태에서 이미지·ai_server 가 지금처럼 판으로 돈다."""
        self.assertIsNone(mp.load_lock(ROOT / "models.lock"))

    def test_missing_file_also_means_nothing_adopted(self):
        self.assertIsNone(mp.load_lock(self.tmp / "nope.lock"))

    def test_every_command_skips_without_an_adopted_model(self):
        lock = self.write_lock("stage1: null\n")
        never = lambda: self.fail("채택이 없으면 Spring 을 부르지 않는다")  # noqa: E731
        with Quiet():
            self.assertEqual(mp.main(["--lock", str(lock), "verify",
                                      "--report", str(self.tmp / "r.md")]), 0)
            self.assertEqual(mp.main(["--lock", str(lock), "publish"]), 0)
            self.assertFalse(mp.bake(mp.load_lock(lock), self.tmp / "out", never))
            mp.adopt(mp.load_lock(lock), never)
        # 모델 자리는 비어 있다 — 이미지에 빈 자리만 들어간다
        self.assertEqual(list((self.tmp / "out").iterdir()), [])
        self.assertIn("채택된 모델이 없다", (self.tmp / "r.md").read_text(encoding="utf-8"))


class LockFormatTest(Base):
    def test_a_good_lock(self):
        lock = mp.load_lock(self.write_lock(lock_text()))
        self.assertEqual((lock.version, lock.jupyter_dir), ("v3", "mupid_models/stage1/v3"))
        self.assertEqual(lock.version_files, sorted(FILES))
        self.assertEqual(lock.serving, tuple(SERVING))

    def test_errors_are_reported_together(self):
        bad = lock_text(version="3", jupyter_dir="/abs/v2",
                        files={"user_encoder.npz": "zz", "stage1.pt": sha(b"x")},
                        serving=["user_encoder.npz", "weights.npz"])
        with self.assertRaises(SystemExit) as got:
            mp.load_lock(self.write_lock(bad))
        msg = str(got.exception)
        for expected in ("version", "jupyter_dir", "hex 64자", "stage1.pt", "manifest.json",
                         "weights.npz"):
            self.assertIn(expected, msg)

    def test_the_directory_must_end_with_the_version(self):
        with self.assertRaises(SystemExit) as got:
            mp.load_lock(self.write_lock(lock_text(jupyter_dir="mupid_models/stage1/v2")))
        self.assertIn("버전(v3)", str(got.exception))

    def test_serving_names_are_what_ai_server_reads(self):
        """ai_server/app/model_bundle.py 의 SERVING 과 같은 이름이어야 이미지에서 읽힌다."""
        text = (ROOT / "ai_server" / "app" / "model_bundle.py").read_text(encoding="utf-8")
        for name in mp.SERVING_NAMES:
            self.assertIn(f'"{name}"', text)


class VerifyTest(Base):
    def test_matching_files_pass_and_manifest_metrics_are_listed(self):
        lock = mp.load_lock(self.write_lock(lock_text()))
        d = self.bundle()
        mp.verify_files(lock, d)
        lines = mp.manifest_summary(lock, d)
        self.assertIn("metrics.hit@10 = 0.41", lines)
        self.assertIn("embedding_version = mert-v1-95m", lines)

    def test_a_changed_file_stops_and_names_every_mismatch(self):
        lock = mp.load_lock(self.write_lock(lock_text()))
        d = self.bundle({**FILES, "user_encoder.npz": b"retrained", "pca.npz": b"other"})
        with self.assertRaises(SystemExit) as got:
            mp.verify_files(lock, d)
        self.assertIn("user_encoder.npz", str(got.exception))
        self.assertIn("pca.npz", str(got.exception))

    def test_manifest_version_must_match(self):
        files = {**FILES, "manifest.json": b'{"version": "v2"}'}
        lock = mp.load_lock(self.write_lock(lock_text(
            files={n: sha(b) for n, b in files.items()})))
        with self.assertRaises(SystemExit):
            mp.manifest_summary(lock, self.bundle(files))

    def test_mr_without_protected_credentials_checks_format_only(self):
        """보호 변수는 기능 브랜치 MR 에 없다 — 형식만 보고 경고와 함께 지나간다."""
        lock = self.write_lock(lock_text())
        import os
        saved = os.environ.pop(mp.DATA_ENV, None)
        try:
            with Quiet() as q:
                self.assertEqual(mp.main(["--lock", str(lock), "verify",
                                          "--report", str(self.tmp / "r.md")]), 0)
        finally:
            if saved is not None:
                os.environ[mp.DATA_ENV] = saved
        self.assertIn("경고", q.text)


class GateTest(Base):
    def setUp(self):
        super().setUp()
        self.lock = mp.load_lock(self.write_lock(lock_text()))

    def test_off_by_default_warns_and_passes(self):
        spring = FakeSpring()
        with Quiet() as q:
            mp.check_gate(self.lock, spring, enabled=False)
        self.assertIn("경고", q.text)
        self.assertEqual(spring.calls, [])

    def test_on_and_incomplete_stops(self):
        with self.assertRaises(SystemExit):
            mp.check_gate(self.lock, FakeSpring(gate={"complete": False}), enabled=True)

    def test_on_and_complete_passes(self):
        spring = FakeSpring(gate={"complete": True})
        with Quiet():
            mp.check_gate(self.lock, spring, enabled=True)
        self.assertEqual(spring.calls[0][1], "/api/v1/system/models/stage1/v3/recompute")


class PublishTest(Base):
    def test_three_steps_with_version_files_until_candidate(self):
        lock = mp.load_lock(self.write_lock(lock_text()))
        spring = FakeSpring(completed={"pca.npz"})      # 지난 실행이 하나를 올렸다
        with Quiet():
            status = mp.publish(lock, self.bundle(), spring)
        self.assertEqual(status, "CANDIDATE")
        requests = [p for m, path, p in spring.calls if path == "/api/v1/system/objects"]
        self.assertEqual({r["file_name"] for r in requests}, set(FILES))
        for r in requests:
            self.assertEqual(r["version_files"], sorted(FILES))
            self.assertEqual((r["kind"], r["name"], r["version"]), ("MODEL", "stage1", "v3"))
        # 이미 완료된 파일은 다시 올리지 않는다
        self.assertNotIn("pca.npz", spring.uploaded)
        self.assertEqual(set(spring.uploaded), set(FILES) - {"pca.npz"})

    def test_publish_command_stops_before_uploading_on_mismatch(self):
        """받은 파일이 다르면 관문·업로드까지 가지 않는다."""
        lock = mp.load_lock(self.write_lock(lock_text()))
        d = self.bundle({**FILES, "combiner.json": b"changed after merge"})
        spring = FakeSpring()
        with self.assertRaises(SystemExit):
            mp.verify_files(lock, d)
        self.assertEqual(spring.calls, [])


class BakeTest(Base):
    def test_bakes_only_serving_files_with_a_manifest(self):
        lock = mp.load_lock(self.write_lock(lock_text()))
        spring = FakeSpring(store=FILES)
        out = self.tmp / "models" / "stage1"
        with Quiet():
            self.assertTrue(mp.bake(lock, out, lambda: spring))
        self.assertEqual(sorted(p.name for p in out.iterdir()), sorted(SERVING + ["model.json"]))
        meta = json.loads((out / "model.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["version"], "v3")
        self.assertEqual(meta["files"], {n: sha(FILES[n]) for n in SERVING})

    def test_a_downloaded_file_that_differs_stops(self):
        lock = mp.load_lock(self.write_lock(lock_text()))
        spring = FakeSpring(store={**FILES, "combiner.json": b"tampered"})
        with self.assertRaises(SystemExit), Quiet():
            mp.bake(lock, self.tmp / "out", lambda: spring)

    def test_adopt_after_rollout(self):
        lock = mp.load_lock(self.write_lock(lock_text()))
        spring = FakeSpring()
        with Quiet():
            mp.adopt(lock, lambda: spring)
        self.assertEqual(spring.calls, [("POST", "/api/v1/system/models/stage1/v3/adopt", None)])


class SecretTest(Base):
    def test_refresh_token_is_never_in_the_error(self):
        """401 이면 멈추되 토큰 값을 메시지에 싣지 않는다."""
        import urllib.error

        def opener(req, timeout):
            raise urllib.error.HTTPError(req.full_url, 401, "no", {}, io.BytesIO(b"{}"))

        spring = mp.Spring("https://example.invalid", "SECRET-TOKEN-VALUE", opener=opener)
        with self.assertRaises(SystemExit) as got:
            spring.token()
        self.assertNotIn("SECRET-TOKEN-VALUE", str(got.exception))
        self.assertIn(mp.TOKEN_ENV, str(got.exception))

    def test_file_variable_is_read(self):
        path = self.tmp / "token"
        path.write_text("abc\n", encoding="utf-8")
        self.assertEqual(mp.read_secret(str(path)), "abc")
        self.assertEqual(mp.read_secret("plain-value"), "plain-value")


class PipelineWiringTest(unittest.TestCase):
    """job 이 있어야 할 자리에 있는가. 조용히 안 뜨는 쪽이 제일 알아채기 어렵다."""

    def load(self, rel):
        return yaml.safe_load((ROOT / rel).read_text(encoding="utf-8"))

    def test_model_jobs_are_included_and_run_on_lock_changes(self):
        root = self.load(".gitlab-ci.yml")
        self.assertIn({"local": "infra/ci/model.yml"}, root["include"])
        jobs = self.load("infra/ci/model.yml")
        for name in ("model-verify", "model-publish"):
            with self.subTest(job=name):
                changes = [c for r in jobs[name]["rules"] for c in (r.get("changes") or [])]
                self.assertIn("models.lock", changes)
        # master 게시는 보호 브랜치에서만. 보호 변수(JupyterHub·system-ci)를 쓴다.
        first = jobs["model-publish"]["rules"][0]["if"]
        self.assertIn('$CI_COMMIT_BRANCH == "master"', first)
        self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', first)

    def test_ai_deploy_follows_the_lock_and_waits_for_publish(self):
        job = self.load("infra_k8s/ci/k8s-release.yml")["k8s-deploy-ai"]
        changes = [c for r in job["rules"] for c in (r.get("changes") or [])]
        self.assertIn("models.lock", changes)
        needs = {n["job"]: n for n in job["needs"]}
        self.assertTrue(needs["model-publish"]["optional"])
        script = "\n".join(job["script"])
        # 굽기는 package-ai.sh 안에서, 채택은 롤아웃(deploy-k8s.sh) 뒤에
        self.assertLess(script.index("deploy-k8s.sh ai"), script.index("model_publish.py adopt"))
        self.assertIn("model_publish.py bake", (ROOT / "infra_k8s/ci/package-ai.sh").read_text(
            encoding="utf-8"))

    def test_the_image_carries_the_model_dir(self):
        dockerfile = (ROOT / "ai_server" / "Dockerfile").read_text(encoding="utf-8")
        self.assertIn("ai_server/models", dockerfile)
        self.assertTrue((ROOT / "ai_server" / "models" / "stage1" / ".gitkeep").exists())


if __name__ == "__main__":
    unittest.main()
