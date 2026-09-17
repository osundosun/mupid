"""영역 판정 규칙을 고정한다. 클러스터·도커·네트워크에 붙지 않는다.

    python -B -m unittest infra_k8s.tests.test_area_diff -v

여기서 잡으려는 실패는 하나다 — **경로 매핑과 라벨 목록이 조용히 어긋나는 것.**
어긋나면 초록 파이프라인이 나오는데 아무것도 검증되지 않았다. 실제로 있었다:
b0130b2 는 infra/ci/data.yml 을 건드렸는데 라벨은 Backend,Frontend 뿐이라
data-verify 가 돌지 않았고, 빠진 job 은 흔적을 남기지 않았다.
"""

import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("area_diff", ROOT / "infra/ci/area_diff.py")
area_diff = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(area_diff)

# 저장소 이력을 읽는 검사가 둘 있다. git 이 없는 이미지(예: python:3.12-slim)에서
# FileNotFoundError 로 죽는 것과, 조용히 건너뛰는 것 사이에서 후자를 고른다 —
# 죽으면 원인이 메시지에 안 드러나고, 건너뛰면 로그에 이유가 남는다. CI job 은
# git 을 설치하므로 실제로는 돌아간다(infra/ci/infra.yml).
GIT = shutil.which("git")


class PathMappingTest(unittest.TestCase):
    def test_each_area_has_a_path(self):
        for path, expected in [
            ("frontend/app/page.tsx", "Frontend"),
            ("prototype/app/page.tsx", "Frontend"),
            ("api/src/main/java/Foo.java", "Backend"),
            ("infra/image/Dockerfile", "Backend"),
            ("ai_server/app/main.py", "AI"),
            ("music_preprocessing/src/x.py", "Data"),
            ("flow_music_automation/scripts/x.py", "Data"),
            ("infra/ci/deploy-jupyter.py", "Data"),
            ("infra/ci/labels.yml", "Infra"),
            ("infra_k8s/base/edge.yaml", "Infra"),
            (".gitlab-ci.yml", "Infra"),
        ]:
            with self.subTest(path=path):
                self.assertEqual(area_diff.area_for_path(path), expected)

    def test_the_longest_prefix_wins(self):
        """infra/ 안에 있으면서 Data 인 파일이 있다. 긴 접두사가 이기지 않으면
        deploy-jupyter.py 가 Infra 로 분류돼 Data 라벨 없이 통과한다."""
        self.assertEqual(
            area_diff.area_for_path("infra/ci/deploy-jupyter.py"), "Data")
        self.assertEqual(area_diff.area_for_path("infra/ci/deploy-jupyter.py".replace(
            "deploy-jupyter.py", "other.py")), "Infra")

    def test_ignored_and_unknown_paths(self):
        for path in ["진행사항/2026-09-17.md", "회고/a.md", ".husky/commit-msg",
                     ".hermes/plans/x.md", ".gitignore", "README.md", "package.json"]:
            with self.subTest(path=path):
                self.assertIsNone(area_diff.area_for_path(path))
                self.assertEqual(area_diff.unclassified([path]), [])

    def test_an_unclassified_path_is_reported(self):
        self.assertEqual(area_diff.unclassified(["brand_new_dir/x.py"]),
                         ["brand_new_dir/x.py"])

    def test_changed_areas_keeps_the_canonical_order(self):
        self.assertEqual(
            area_diff.changed_areas([".gitlab-ci.yml", "api/x", "frontend/y"]),
            ["Frontend", "Backend", "Infra"])
        self.assertEqual(area_diff.changed_areas(["README.md"]), [])


class LabelTest(unittest.TestCase):
    def test_gitlab_comma_format(self):
        self.assertEqual(area_diff.labels("Backend,Data"), ["Backend", "Data"])
        self.assertEqual(area_diff.labels(" Backend , Data "), ["Backend", "Data"])
        self.assertEqual(area_diff.labels("Backend,,Data"), ["Backend", "Data"])

    def test_absent_or_empty(self):
        for value in (None, "", "  ", ","):
            with self.subTest(value=value):
                self.assertEqual(area_diff.labels(value), [])

    def test_a_lookalike_label_is_not_a_match(self):
        """Backend-hotfix 는 Backend 가 아니다. 경계를 보지 않으면 같이 걸린다."""
        self.assertNotIn("Backend", area_diff.labels("Backend-hotfix"))
        self.assertEqual(area_diff.labels("Backend-hotfix"), ["Backend-hotfix"])

    def test_missing_and_extra(self):
        self.assertEqual(area_diff.missing(["Frontend", "Backend"], ["Backend"]),
                         ["Frontend"])
        self.assertEqual(area_diff.extra(["Backend"], ["Backend", "AI"]), ["AI"])
        # 영역이 아닌 라벨은 판정에 끼어들지 않는다.
        self.assertEqual(area_diff.extra(["Backend"], ["Backend", "hotfix"]), [])


class EveryTopLevelPathIsClassifiedTest(unittest.TestCase):
    """새 디렉터리가 들어왔는데 표에 없으면 알려준다.

    조용히 지나가면 그 영역은 아무도 검증하지 않는다. 표를 처음 만들 때 이
    테스트가 실제로 app/ 을 잡아냈다 — app/ 은 develop 에도 있는데(Tauri 앱 셸)
    develop 의 어느 CI 파일도 그 경로를 언급하지 않고 있었다.
    """

    def test_every_tracked_top_level_entry_is_known(self):
        if not GIT:
            self.skipTest("git 이 없다 — 최상위 경로를 읽을 수 없다")
        out = subprocess.run(
            [GIT, "-c", "core.quotePath=false", "ls-tree", "--name-only", "HEAD"],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(out.returncode, 0, out.stderr)
        unknown = area_diff.unclassified(
            [name + "/" for name in out.stdout.splitlines()])
        self.assertEqual(
            unknown, [],
            "infra/ci/area_diff.py 의 PREFIXES 에 추가하거나 IGNORED 에 넣는다: "
            + ", ".join(unknown))

    def test_non_ascii_paths_are_not_escaped(self):
        """git 은 기본값(core.quotePath=true)에서 한글 경로를
        `"\\354\\247\\204..."` 로 이스케이프해 내보낸다 — 그러면 `진행사항/` 같은
        무시 목록이 매칭되지 않아 그 경로가 '표에 없는 경로' 로 판정되고 MR 이
        이유 없이 막힌다. 사람 머신에는 이 설정이 false 로 들어가 있는 경우가 많아
        **로컬에서는 통과하고 CI 에서만** 깨졌다(실측: infra-verify 파이프라인 201240).
        area_diff.git 이 설정에 기대지 않는지 여기서 고정한다."""
        if not GIT:
            self.skipTest("git 이 없다 — 저장소 이력을 읽을 수 없다")
        out = area_diff.git("ls-tree", "--name-only", "HEAD")
        self.assertIn("진행사항", out,
                      "한글 경로가 이스케이프됐다 — area_diff.git 이 core.quotePath 를 "
                      "고정하지 않는다")
        self.assertNotIn("\\354", out, "이스케이프된 경로가 그대로 올라왔다")
        self.assertEqual(area_diff.unclassified(["진행사항/2026-09-17.md"]), [])


class PreflightCliTest(unittest.TestCase):
    """실제 저장소 이력으로 CLI 를 돌린다. 경계는 인자를 주입해 본다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.areas = pathlib.Path(self.tmp.name) / ".areas"

    def run_cli(self, *args, env=None):
        base = dict(os.environ)
        base["CI_MERGE_REQUEST_IID"] = "103"
        base["CI_PROJECT_ID"] = "1440925"
        base.update(env or {})
        return subprocess.run(
            [sys.executable, "-B", str(ROOT / "infra/ci/area_diff.py"),
             *args, "--areas-dir", str(self.areas)],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", env=base)

    def test_it_writes_the_expected_file_when_labels_are_complete(self):
        result = self.run_cli("preflight", "--paths", "api/x", "frontend/y",
                              "--labels", "Backend,Frontend")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.areas / "expected.txt").read_text().split(),
                         ["Frontend", "Backend"])

    def test_a_lacking_label_fails_with_a_usable_recovery(self):
        result = self.run_cli("preflight", "--paths", "api/x", "frontend/y",
                              "--labels", "Backend")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Frontend", result.stdout)
        # 로그에서 그대로 복사해 쓸 수 있어야 한다 — iid 와 프로젝트가 채워져 있다.
        self.assertIn("glab mr update 103 --label Frontend", result.stdout)
        self.assertIn("projects/1440925/merge_requests/103/pipelines", result.stdout)
        self.assertIn("retry", result.stdout)
        self.assertIn("frontend/y", result.stdout, "바뀐 경로를 보여주지 않는다")

    def test_an_unknown_path_stops_the_pipeline(self):
        result = self.run_cli("preflight", "--paths", "brand_new/x.py",
                              "--labels", "Backend")
        self.assertEqual(result.returncode, 1)
        self.assertIn("brand_new/x.py", result.stdout)

    def test_a_docs_only_change_needs_no_label(self):
        result = self.run_cli("preflight", "--paths", "진행사항/a.md",
                              "--labels", "")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_a_real_merge_commit_is_judged_without_network(self):
        """18b464c9 는 develop 에 병합된 실제 커밋이다. 그 부모 대비 변경은
        frontend/ 뿐이므로 Infra 라벨 없이도 통과해야 한다."""
        if not GIT:
            self.skipTest("git 이 없다 — 저장소 이력을 읽을 수 없다")
        head = subprocess.run([GIT, "rev-parse", "18b464c9"], cwd=ROOT,
                              capture_output=True, text=True).stdout.strip()
        if not head:
            self.skipTest("18b464c9 가 없다")
        result = self.run_cli("preflight", "--base", head + "^1", "--head", head,
                              "--labels", "Frontend")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.areas / "expected.txt").read_text().split(),
                         ["Frontend"])


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.areas = pathlib.Path(self.tmp.name) / ".areas"
        self.areas.mkdir()

    def write(self, name, text=""):
        (self.areas / name).write_text(text, encoding="utf-8")

    def test_every_expected_area_has_a_marker(self):
        self.write("expected.txt", "Frontend\nBackend\n")
        self.write("Frontend")
        self.write("Backend")
        self.assertEqual(area_diff.gate(self.areas), 0)

    def test_a_missing_marker_fails(self):
        """라벨 없이 만들어진 파이프라인을 재시도한 경우가 여기로 온다 —
        job 목록에는 그 영역 job 이 아예 없고 marker 도 없다."""
        self.write("expected.txt", "Frontend\nBackend\nData\n")
        self.write("Frontend")
        self.write("Backend")
        self.assertEqual(area_diff.gate(self.areas), 1)

    def test_the_message_names_both_causes(self):
        """게이트의 값어치는 '무엇이 빠졌는지' 뿐 아니라 '왜' 를 구분해 말하는
        데 있다. 영역 job 이 실패한 경우와 job 자체가 없는 경우는 대응이
        전혀 다르다 — 전자는 그 job 의 로그를 봐야 하고, 후자는 라벨을 붙이고
        새 파이프라인을 만들어야 한다. 메시지가 둘을 뭉뚱그리면 사람이 엉뚱한
        쪽을 고친다."""
        self.write("expected.txt", "Backend\nInfra\n")
        self.write("Infra")
        import contextlib
        import io
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = area_diff.gate(self.areas)
        printed = buffer.getvalue()
        self.assertEqual(code, 1)
        self.assertIn("Backend", printed, "빠진 영역을 이름으로 말하지 않는다")
        self.assertIn("실패", printed, "job 실패 가능성을 말하지 않는다")
        self.assertIn("아예 없다", printed, "job 부재 가능성을 말하지 않는다")
        self.assertIn("새 파이프라인", printed, "복구 방법을 말하지 않는다")
        self.assertIn("merge_requests/<iid>/pipelines", printed,
                      "붙여넣을 수 있는 복구 명령이 없다")

    def test_an_extra_marker_is_fine(self):
        self.write("expected.txt", "Frontend\n")
        self.write("Frontend")
        self.write("Data")
        self.assertEqual(area_diff.gate(self.areas), 0)

    def test_no_expected_file_means_preflight_did_not_run(self):
        self.assertEqual(area_diff.gate(self.areas), 1)

    def test_a_docs_only_pipeline_passes(self):
        self.write("expected.txt", "")
        self.assertEqual(area_diff.gate(self.areas), 0)


if __name__ == "__main__":
    unittest.main()
