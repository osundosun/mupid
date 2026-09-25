#!/usr/bin/env python3
"""변경 경로와 MR 라벨로 '검증해야 할 영역'과 '실제로 검증된 영역'을 판정한다.

왜 순수 함수로 모으는가
-----------------------
판정이 YAML 의 rules 안에 흩어져 있으면 틀렸을 때 **조용히** 틀린다. 이 저장소는
라벨과 job 목록이 어긋난 채로 초록 파이프라인을 만든 적이 여러 번 있다 —
b0130b2 는 infra/ci/data.yml 을 건드렸는데 라벨은 Backend,Frontend 뿐이라
data-verify 가 돌지 않았고, 빠진 job 은 흔적을 남기지 않았다. 경로 매핑·라벨
파싱·비교를 여기 한곳에 모으고 infra_k8s/tests/test_area_diff.py 가 표를 고정한다.

두 번 돈다
----------
preflight  파이프라인 앞단. 변경 경로로 기대 영역을 계산해 .areas/expected.txt 로
           남기고, 라벨이 모자라면 그 자리에서 실패한다. 복구 명령까지 찍는다.
gate       파이프라인 뒷단. 영역 job 이 남긴 .areas/<영역> marker 와 기대 목록을
           대조한다.

**왜 gate 가 필요한가.** GitLab 은 job 의 rules 를 파이프라인을 만들 때 한 번
평가하고, job 을 다시 돌려도(retry) 그 결과를 바꾸지 않는다(공식 문서:
"The configuration for a pipeline is only fetched when the pipeline is created.
When you rerun a job, uses the same configuration each time."). 실측으로도
확인했다 — 라벨이 없는 MR 의 파이프라인에서 require-area-label 을 재시도하면,
스크립트는 최신 라벨을 보고도 job 은 여전히 실패한다(파이프라인 200778 의
job 556620 → 556721). 즉 재시도로는 영역 verify 가 생기지 않고, job 목록만
보면 '아무것도 검증하지 않은 초록 파이프라인' 이 된다. marker 는 실행 시점의
사실이므로 gate 가 그 거짓을 잡는다.

사용법 (저장소 루트에서)
-----------------------
    python3 infra/ci/area_diff.py preflight
    python3 infra/ci/area_diff.py gate

CI 변수를 읽는다. 로컬 확인은 --base/--head/--labels/--paths 로 값을 주입한다.
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys

# 경로 접두사 -> 영역. **긴 접두사가 먼저 매칭된다**(area_for_path 참고).
# 표를 고치면 세 곳을 같이 고친다: infra/ci/labels.yml 의 라벨 목록, 영역 job 의
# rules 와 marker, infra_k8s/tests/test_area_diff.py 의 기대값.
PREFIXES: tuple[tuple[str, str], ...] = (
    # music_preprocessing 이 아니라 이 스크립트가 Data 다 — infra/ 안에 있지만
    # 검증하는 것도 배포하는 것도 JupyterHub 배치다(infra/ci/data.yml).
    ("infra/ci/deploy-jupyter.py", "Data"),
    # 배포 이미지를 굽는 컨텍스트는 backend-verify 가 검사한다.
    ("infra/image/", "Backend"),
    ("frontend/", "Frontend"),
    # Tauri 앱 셸. **develop 에는 이 경로를 검사하는 job 이 없다** — app.yml
    # (app-verify·app-build)은 아직 master 에만 있고, develop 의 어느 CI 파일도
    # app/ 을 언급하지 않는다(실측). 그래서 지금 app/ 변경은 조용히 미검증이다.
    # Frontend 로 묶는 이유는 ① 앱이 frontend/dist/client 를 그대로 담아
    # (tauri.conf.json 의 frontendDist) 프런트 빌드가 실제로 관련이 있고
    # ② master 의 app-verify 도 Frontend 라벨이 고르기 때문이다.
    # 앱 전용 검사(Rust 단위 시험)가 필요해지면 app/ 을 따로 떼어 App 영역을
    # 만들고 app-verify 를 develop 으로 가져온다.
    ("app/", "Frontend"),
    # 옛 넥스트 프로토타입. 화면 코드라 Frontend 다.
    ("prototype/", "Frontend"),
    ("api/", "Backend"),
    ("ai_server/", "AI"),
    # 운영에 쓸 모델의 정본(S15P21A204-454). 바꾸면 AI 이미지가 새 가중치로 구워진다 —
    # 모델 담당(AI)이 보는 변경이다. 대조·게시 job(model.yml)은 경로로 따로 뜬다.
    ("models.lock", "AI"),
    ("music_preprocessing/", "Data"),
    # GPU 서버로 곡을 생성·다운로드하는 Playwright 자동화. music_preprocessing
    # (다운스트림 가공)과는 별도 코드지만 같은 Data 영역이다.
    ("flow_music_automation/", "Data"),
    # 나머지 infra/ 와 infra_k8s/ 는 매니페스트·배포 스크립트·CI 자신이다.
    ("infra/", "Infra"),
    ("infra_k8s/", "Infra"),
    (".gitlab-ci.yml", "Infra"),
)

# 로그·비교의 기준 순서. 집합이 아니라 이 순서로 항상 돌려준다 — 같은 입력에
# 같은 출력이 나와야 테스트가 흔들리지 않는다.
AREAS: tuple[str, ...] = ("Frontend", "Backend", "AI", "Data", "Infra")

# 어느 영역도 아닌 경로. 검증할 코드가 아니라 검증을 돌리는 사람의 물건이다.
# (개인 기록·훅·도구 설정·저장소 메타)
IGNORED: tuple[str, ...] = (
    "진행사항/", "회고/",
    ".husky/", ".hermes/", ".gitlab/",
    ".gitignore", ".gitattributes", ".dockerignore",
    "README.md", "package.json", "package-lock.json",
)

# preflight 가 남기고 gate 가 읽는 자리.
AREAS_DIR = pathlib.Path(".areas")
EXPECTED = AREAS_DIR / "expected.txt"


def area_for_path(path: str) -> str | None:
    """경로 하나가 속한 영역. 접두사가 여럿 맞으면 **가장 긴 것**이 이긴다.

    이 규칙이 없으면 infra/ci/deploy-jupyter.py 가 infra/ 때문에 Infra 로
    분류돼, Data 라벨 없이도 Data 변경이 통과한다.
    """
    hits = [(prefix, area) for prefix, area in PREFIXES if path.startswith(prefix)]
    if not hits:
        return None
    return max(hits, key=lambda hit: len(hit[0]))[1]


def labels(value: str | None) -> list[str]:
    """CI_MERGE_REQUEST_LABELS 를 라벨 목록으로.

    GitLab 은 "Backend,Data" 로 붙여서 준다. 사람이 쉼표 뒤에 공백을 넣는
    경우가 있어 걷어낸다. 빈 항목("Backend,,")도 버린다.
    """
    return [part.strip() for part in (value or "").split(",") if part.strip()]


def changed_areas(paths) -> list[str]:
    """변경 경로들이 건드린 영역. AREAS 순서로 돌려준다."""
    found = {area_for_path(path) for path in paths}
    return [area for area in AREAS if area in found]


def missing(changed, labelled) -> list[str]:
    """변경이 건드렸는데 라벨이 없는 영역. 이게 있으면 파이프라인은 빨강이다."""
    return [area for area in changed if area not in labelled]


def extra(changed, labelled) -> list[str]:
    """변경에 없는데 붙은 라벨. 막지 않는다 — 그 영역 verify 가 한 번 더 돌 뿐이다."""
    return [area for area in labelled if area in AREAS and area not in changed]


def unclassified(paths) -> list[str]:
    """영역도 무시 목록도 아닌 경로. 표에 구멍이 났다는 뜻이다.

    조용히 지나가면 그 경로의 변경은 아무도 검증하지 않는다.
    """
    return sorted(
        path for path in paths
        if area_for_path(path) is None and not path.startswith(IGNORED)
    )


def git(*args: str) -> str:
    """실패하면 SystemExit. 얕은 체크아웃이면 여기서 멈춘다 — 조용히 빈 diff 를
    돌려주면 '검증 대상 없음' 이 되어 게이트가 통째로 무력해진다.

    `-c core.quotePath=false` 를 항상 준다. git 은 기본값(true)에서 비ASCII
    경로를 `"\\354\\247\\204\\355\\226\\211\\354\\202\\254\\355\\225\\255/"` 처럼
    이스케이프해 내보내는데, 그러면 `진행사항/` 같은 무시 목록이 매칭되지 않고
    그 경로가 "표에 없는 경로" 로 판정돼 MR 이 이유 없이 막힌다. 사람 머신에는
    이 설정이 false 로 들어가 있는 경우가 많아 **로컬에서는 통과하고 CI 에서만**
    깨졌다(실측: infra-verify 파이프라인 201240). 사용자·러너 설정에 기대지 않도록
    여기서 고정한다.
    """
    result = subprocess.run(
        ("git", "-c", "core.quotePath=false", *args),
        capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        sys.exit(f"git {' '.join(args)} 실패:\n{result.stderr.strip()}\n"
                 "GIT_DEPTH 0 으로 체크아웃했는지 확인한다.")
    return result.stdout


def resolve_refs(base: str | None, head: str | None, env) -> tuple[str, str, str]:
    """diff 의 양 끝과, 그 값을 어디서 얻었는지.

    MR 파이프라인에는 CI_MERGE_REQUEST_DIFF_BASE_SHA 가 있다 — GitLab 이
    diff 기준점으로 주는 값이라 우리가 다시 계산할 필요가 없다. 없으면
    타깃 브랜치와의 merge-base 로 대신한다.

    돌려주는 세 번째 값은 로그에 찍어 근거를 남기기 위한 것이다.
    """
    head = head or env.get("CI_COMMIT_SHA")
    if not head:
        sys.exit("head 커밋을 알 수 없다. --head 또는 CI_COMMIT_SHA 가 필요하다.")
    if base:
        return base, head, "지정값"
    base = env.get("CI_MERGE_REQUEST_DIFF_BASE_SHA")
    if base:
        return base, head, "CI_MERGE_REQUEST_DIFF_BASE_SHA"
    target = env.get("CI_MERGE_REQUEST_TARGET_BRANCH_NAME") or "develop"
    try:
        base = git("merge-base", f"origin/{target}", head).strip()
    except SystemExit:
        sys.exit(f"diff 기준점을 찾지 못했다. CI_MERGE_REQUEST_DIFF_BASE_SHA 가 "
                 f"없고 origin/{target} 와의 merge-base 도 계산할 수 없다.")
    return base, head, f"merge-base(origin/{target})"


def changed_paths(base: str, head: str) -> list[str]:
    return [line for line in git("diff", "--name-only", base, head).splitlines() if line]


def recovery_hint(env, areas: list[str]) -> str:
    """모자란 라벨을 붙이고 **새 파이프라인**을 만드는 두 줄.

    로그에서 그대로 복사해 붙일 수 있어야 한다. iid·프로젝트를 <...> 로 두면
    사람이 매번 채워야 하고, 그러다 틀린다.
    """
    iid = env.get("CI_MERGE_REQUEST_IID", "<iid>")
    project = env.get("CI_PROJECT_ID", "<project_id>")
    flags = " ".join(f"--label {area}" for area in areas)
    return (
        f"  1) 모자란 라벨을 붙인다\n"
        f"     glab mr update {iid} {flags}\n"
        f"     (GitLab UI: MR 오른쪽 Labels 에서 추가)\n"
        f"\n"
        f"  2) **새 파이프라인을 만든다** — 라벨은 여기서 처음 반영된다\n"
        f"     glab api --method POST "
        f"\"projects/{project}/merge_requests/{iid}/pipelines\"\n"
        f"     (GitLab UI: MR 의 Pipelines 탭 → Run pipeline)\n"
        f"\n"
        f"     job 을 다시 누르는 것(retry)으로는 안 됩니다. rules 가 고른 job\n"
        f"     목록은 파이프라인을 만들 때 확정되고, 재시도는 그 목록을 다시\n"
        f"     만들지 않습니다 — 검증 job 이 없는 파이프라인은 영원히 그대로입니다."
    )


def preflight(paths, labelled, env, areas_dir: pathlib.Path = AREAS_DIR) -> int:
    """기대 영역을 남기고, 라벨이 모자라면 실패한다."""
    changed = changed_areas(paths)
    holes = unclassified(paths)
    if holes:
        print("영역 표에 없는 경로가 있습니다 — infra/ci/area_diff.py 의 PREFIXES 에 "
              "추가하거나 IGNORED 에 넣으세요.")
        for path in holes:
            print(f"  {path}")
        print("\n표에 구멍이 있으면 그 경로의 변경은 아무도 검증하지 않습니다.")
        return 1

    areas_dir.mkdir(parents=True, exist_ok=True)
    (areas_dir / EXPECTED.name).write_text(
        "".join(f"{area}\n" for area in changed), encoding="utf-8")

    print(f"변경 파일 {len(paths)}개 / 검증해야 할 영역: "
          f"{', '.join(changed) if changed else '(없음)'}")
    print(f"붙어 있는 라벨: {', '.join(labelled) if labelled else '(없음)'}")

    lack = missing(changed, labelled)
    surplus = extra(changed, labelled)
    if surplus:
        print(f"참고: 변경에 없는 영역 라벨도 있습니다({', '.join(surplus)}) — "
              "그 영역 검증이 한 번 더 돌 뿐입니다.")

    if not lack:
        if not changed:
            print("\n검증 대상 영역이 없습니다(문서·설정만 바뀐 MR).")
        return 0

    print()
    print(f"이 MR 은 {'·'.join(lack)} 영역을 건드렸는데 "
          f"{'·'.join(lack)} 라벨이 없습니다.")
    print()
    print("바뀐 경로:")
    for path in sorted(paths):
        if area_for_path(path) in lack:
            print(f"  {path}")
    print()
    print(recovery_hint(env, lack))
    return 1


def verified_areas(areas_dir: pathlib.Path = AREAS_DIR) -> list[str]:
    """영역 job 이 남긴 marker. **실행 시점의 사실**이라 retry 로 바뀌지 않는다."""
    return [area for area in AREAS if (areas_dir / area).exists()]


def gate(areas_dir: pathlib.Path = AREAS_DIR) -> int:
    """기대 영역이 전부 검증됐는지. 누락을 여기서 잡는다.

    이 job 은 stage 를 나누고 needs 를 쓰지 않는다. 그래서 영역 job 이 실패해도
    돌고, 앞 stage 의 아티팩트(preflight 의 기대 목록, 영역 job 의 marker)를
    기본으로 받는다 — infra/ci/area-gate.yml 머리말 참고.
    """
    expected_file = areas_dir / EXPECTED.name
    if not expected_file.exists():
        print("area-preflight 의 결과(.areas/expected.txt)가 없습니다.")
        print()
        print("이 게이트는 needs 없이 앞 stage 의 아티팩트를 받는다. 그래서 이건")
        print("설정 문제가 아니라 preflight 가 **아티팩트를 올리지 못했다**는 뜻이다.")
        print("그 job 의 로그에서 업로드 단계를 보고, infra/ci/labels.yml 의")
        print("artifacts.paths 가 .areas/expected.txt 를 가리키는지 확인한다.")
        return 1

    expected = [line.strip() for line in
                expected_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    ran = verified_areas(areas_dir)
    lack = [area for area in expected if area not in ran]

    print(f"기대 영역: {', '.join(expected) if expected else '(없음)'}")
    print(f"검증한 영역: {', '.join(ran) if ran else '(없음)'}")

    if lack:
        print()
        for area in lack:
            print(f"  ✗ {area} — 기대에 있는데 검증되지 않았습니다.")
        print()
        print("원인은 둘 중 하나이고, 이 게이트는 **둘을 구분해서 말한다.**")
        print()
        print("  1) 그 영역 job 이 실패했다")
        print("     → marker 를 남기지 않는다(artifacts: on_success). 위 stage 의")
        print("       실패한 job 로그가 진짜 원인이고, 여기서는 그 결과만 본다.")
        print()
        print("  2) 그 영역 job 이 이 파이프라인에 아예 없다")
        print("     → 라벨 없이 파이프라인이 만들어졌다는 뜻이다. 라벨을 붙이고")
        print("       **새 파이프라인**을 만들어야 한다 — job 재시도로는 job 목록이")
        print("       바뀌지 않는다(infra/ci/labels.yml 머리말 참고).")
        print()
        print("구분 방법: 이 파이프라인의 job 목록에서 그 이름을 찾아본다.")
        print("  있으면 → 1) 이고 그 job 의 로그를 본다")
        print("  없으면 → 2) 이고 아래 두 줄을 실행한다")
        print()
        print("     glab mr update <iid> --label <영역>")
        print("     glab api --method POST \"projects/<project_id>/merge_requests/<iid>/pipelines\"")
        return 1

    if not expected:
        print("\n검증 대상 영역이 없습니다(문서·설정만 바뀐 MR).")
    else:
        print("\n모든 영역이 검증됐습니다.")
    return 0


def main(argv=None, env=None) -> int:
    env = env if env is not None else __import__("os").environ
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mode", choices=("preflight", "gate"))
    parser.add_argument("--base", help="diff 기준 커밋 (기본: MR 의 diff base)")
    parser.add_argument("--head", help="diff 끝 커밋 (기본: CI_COMMIT_SHA)")
    parser.add_argument("--labels", help="쉼표로 이은 라벨 (기본: CI_MERGE_REQUEST_LABELS)")
    parser.add_argument("--paths", nargs="+", help="경로를 직접 준다 (테스트용)")
    parser.add_argument("--areas-dir", default=str(AREAS_DIR))
    args = parser.parse_args(argv)
    areas_dir = pathlib.Path(args.areas_dir)

    if args.mode == "gate":
        return gate(areas_dir)

    labelled = labels(args.labels if args.labels is not None
                      else env.get("CI_MERGE_REQUEST_LABELS"))
    if args.paths:
        return preflight(args.paths, labelled, env, areas_dir)
    base, head, source = resolve_refs(args.base, args.head, env)
    paths = changed_paths(base, head)
    print(f"판정 기준: {source} {base[:8]}..{head[:8]}")
    return preflight(paths, labelled, env, areas_dir)


if __name__ == "__main__":
    sys.exit(main())
