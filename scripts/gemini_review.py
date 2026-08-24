#!/usr/bin/env python3
"""Review a GitLab merge request diff with Gemini and post one MR note per commit."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


DEFAULT_MODEL = "gemini-3.7-flash"
DEFAULT_MAX_DIFF_CHARS = 120_000


def required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"필수 CI/CD 변수 {name}이(가) 설정되지 않았습니다.")
    return value


def git_output(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout


def collect_diff(base_sha: str, head_sha: str) -> str:
    diff = git_output(
        "diff",
        "--no-ext-diff",
        "--unified=30",
        base_sha,
        head_sha,
        "--",
        ".",
        ":(exclude)package-lock.json",
        ":(exclude)yarn.lock",
        ":(exclude)pnpm-lock.yaml",
        ":(exclude)dist/**",
        ":(exclude)node_modules/**",
    ).strip()

    max_chars = int(os.environ.get("GEMINI_MAX_DIFF_CHARS", DEFAULT_MAX_DIFF_CHARS))
    if len(diff) > max_chars:
        diff = (
            diff[:max_chars]
            + "\n\n[주의: 변경량이 커서 이후 diff는 자동으로 생략되었습니다.]"
        )
    return diff


def request_json(
    url: str,
    *,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    payload: dict[str, object] | None = None,
) -> object:
    data = None
    request_headers = dict(headers or {})
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json"

    request = urllib.request.Request(
        url,
        data=data,
        headers=request_headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"HTTP {error.code}: {detail}") from error


def build_prompt(diff: str, source_branch: str, target_branch: str) -> str:
    return f"""당신은 React와 TypeScript 프로젝트를 검토하는 시니어 프론트엔드 엔지니어입니다.
다음 GitLab Merge Request의 unified diff만 근거로 한국어 코드 리뷰를 작성하세요.

소스 브랜치: {source_branch}
대상 브랜치: {target_branch}

검토 기준:
- 런타임 오류, 타입 안정성, React 상태 관리와 렌더링 문제
- 접근성, 예외 처리, API 요청 상태, 유지보수성
- 학습용 프로젝트 수준을 고려한 구체적이고 실행 가능한 개선안

규칙:
- diff에서 확인되지 않는 문제를 추측해서 단정하지 마세요.
- 문제를 언급할 때 가능한 경우 `파일경로:라인` 형식을 사용하세요.
- 사소한 취향보다 실제 버그와 학습 가치가 높은 항목을 우선하세요.
- 문제가 없다면 잠재적 문제 섹션에 "중대한 문제를 찾지 못했습니다."라고 작성하세요.
- 아래 Markdown 제목 구조를 정확히 유지하세요.

## 🤖 AI 리뷰 요약

## ⚠️ 잠재적 문제

## 💡 개선 제안

<diff>
{diff}
</diff>
"""


def review_with_gemini(api_key: str, model: str, prompt: str) -> str:
    encoded_model = urllib.parse.quote(model, safe="")
    endpoint = (
        "https://generativelanguage.googleapis.com/v1beta/models/"
        f"{encoded_model}:generateContent"
    )
    response = request_json(
        endpoint,
        method="POST",
        headers={"x-goog-api-key": api_key},
        payload={
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": prompt}],
                }
            ]
        },
    )

    if not isinstance(response, dict):
        raise RuntimeError("Gemini API 응답 형식이 올바르지 않습니다.")

    candidates = response.get("candidates", [])
    if not candidates:
        raise RuntimeError("Gemini API가 리뷰 후보를 반환하지 않았습니다.")

    content = candidates[0].get("content", {})
    parts = content.get("parts", [])
    review = "\n".join(
        part.get("text", "") for part in parts if isinstance(part, dict)
    ).strip()
    if not review:
        raise RuntimeError("Gemini API 리뷰 본문이 비어 있습니다.")
    return review


def gitlab_headers(token: str) -> dict[str, str]:
    return {"PRIVATE-TOKEN": token}


def note_exists(notes_url: str, token: str, marker: str) -> bool:
    notes = request_json(
        f"{notes_url}?per_page=100&sort=desc&order_by=created_at",
        headers=gitlab_headers(token),
    )
    if not isinstance(notes, list):
        return False
    return any(marker in str(note.get("body", "")) for note in notes)


def post_note(notes_url: str, token: str, body: str) -> None:
    request_json(
        notes_url,
        method="POST",
        headers=gitlab_headers(token),
        payload={"body": body},
    )


def main() -> int:
    gemini_api_key = required_env("GEMINI_API_KEY")
    gitlab_token = required_env("GITLAB_REVIEW_TOKEN")
    api_base = required_env("CI_API_V4_URL")
    project_id = required_env("CI_PROJECT_ID")
    mr_iid = required_env("CI_MERGE_REQUEST_IID")
    base_sha = required_env("CI_MERGE_REQUEST_DIFF_BASE_SHA")
    head_sha = required_env("CI_COMMIT_SHA")
    source_branch = required_env("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME")
    target_branch = required_env("CI_MERGE_REQUEST_TARGET_BRANCH_NAME")
    model = os.environ.get("GEMINI_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL

    notes_url = f"{api_base}/projects/{project_id}/merge_requests/{mr_iid}/notes"
    marker = f"<!-- gemini-review:{head_sha} -->"
    if note_exists(notes_url, gitlab_token, marker):
        print(f"동일 커밋({head_sha[:8]})의 AI 리뷰가 이미 존재합니다.")
        return 0

    diff = collect_diff(base_sha, head_sha)
    if not diff:
        print("리뷰할 코드 변경 사항이 없습니다.")
        return 0

    prompt = build_prompt(diff, source_branch, target_branch)
    review = review_with_gemini(gemini_api_key, model, prompt)
    note_body = (
        f"{marker}\n{review}\n\n"
        f"---\n`{model}` · 커밋 `{head_sha[:8]}` 기준 자동 리뷰"
    )
    post_note(notes_url, gitlab_token, note_body)
    print(f"MR !{mr_iid}에 Gemini 리뷰 댓글을 등록했습니다.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # CI 로그에는 비밀값을 출력하지 않는다.
        print(f"AI 리뷰 작업 실패: {error}", file=sys.stderr)
        raise SystemExit(1)
