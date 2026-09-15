# MR 정책

머지 리퀘스트를 열고 병합하기 전에 읽는다.

[← 컨벤션](README.md) · [지식 저장소](../README.md)

---

## MR 정책

| 항목 | 규칙 | 근거 |
|---|---|---|
| 병합 대상 | 기본은 `develop`. `master` 는 릴리스 시점에만 (`Merge branch 'develop' into 'master'`) | 병합 커밋 이력 |
| `master` 직접 push | 하지 않는다. MR 로 병합한다 | `api/AGENTS.md` |
| `develop` 직접 push | 서버 설정으로도 금지 | `infra/OPERATIONS.md` |
| MR 템플릿 / 승인 규칙 | 저장소에 파일이 없다 | `git ls-files` |

`master` 는 서버가 아니라 합의로만 막혀 있다 — `infra/OPERATIONS.md` 가 "GitLab master 기본 규칙은 Maintainer push를 허용하므로 팀 규칙상 직접 push하지 않는다. develop은 서버 설정에서도 직접 push 금지다"라고 적는다.

흐름 (`infra/OPERATIONS.md`):

1. 작업 브랜치에서 변경 후 `develop` 대상 MR 생성
2. CI 통과 및 변경 검토 (문서는 "백엔드는 Gradle build/test"라고 적지만 실제로는 test 를 건너뛴다 — 아래)
3. MR 병합 후 이미지/정적 파일 패키징과 demo 배포 자동 실행
4. pipeline 의 deploy job 성공과 서비스 확인. MR 의 수동 package job 은 일반 MR 병합의 필수 단계가 아니다

병합 커밋 제목은 GitLab 기본 형식 `Merge branch '<source>' into '<target>'` 이다. GitLab 이 서버에서 만들므로 훅을 거치지 않고, 로컬에서 직접 merge 하는 경우에도 훅 3단계(`MERGE_HEAD`)에 걸려 티켓이 붙지 않는다.

### MR 이 초록이어도 테스트는 돌지 않았다

`infra/ci/backend.yml` 첫 줄이 결론이다.

```
# Build-only CI by team decision; application tests are not executed.
```

| job | 파일 | 실제로 하는 것 |
|---|---|---|
| `backend-verify` | `infra/ci/backend.yml` | `bash gradlew --no-daemon --console=plain build -x test` → `build/release/app.jar` |
| `frontend-verify` | `infra/ci/frontend.yml` | `npm ci` → `npm run build` → `test -f dist/client/index.html` |
| `backend-package` | `infra/ci/release.yml` | rootless BuildKit 이미지 빌드. MR 에서는 `when: manual` |
| `frontend-package` | `infra/ci/frontend.yml` | `sh infra/ci/frontend-release.sh package`. MR 에서는 `when: manual` |
| `backend-deploy` / `frontend-deploy` | `release.yml` / `frontend.yml` | `develop` + `CI_COMMIT_REF_PROTECTED == "true"` + `MUPID_DEPLOY_ENABLED == "true"` 일 때만 |
| `gemini-code-review` | `.gitlab-ci.yml` | 소스 브랜치가 `^study\/이규원\/` 인 MR 에만. `allow_failure: true` |

lint(`oxlint`) · `tsc --noEmit` · 프론트 테스트 · 백엔드 테스트는 **어느 job 에도 없다.** 운영 문서 두 곳이 이와 다르게 적혀 있다 → [함정과 실패 사례](../failure/git-and-convention.md).

`gemini-code-review` 의 rule 은 `$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME =~ /^study\/이규원\//` 이고 그 외에는 `when: never` 다. `back/`·`front/`·`infra/` MR 에는 AI 리뷰가 붙지 않는다.

### CI 파일을 고칠 때

- `.gitlab-ci.yml` 은 `stages`, `include`, `gemini-code-review` 만 들고 있다. **job 추가는 `infra/ci/{frontend,backend,release}.yml` 에 한다.**
- verify·package job 의 `rules.changes` 에는 자기 자신을 포함한 CI 파일 경로가 함께 열거돼 있다 (`infra/ci/backend.yml`, `infra/ci/release.yml`, `.gitlab-ci.yml` …). CI 설정만 바꿔도 검증이 돌게 하기 위함이니, job 을 추가하면 이 목록도 같이 갱신한다.
- `backend-deploy` 는 예외다. changes 가 `api/**/*`, `infra/image/**/*`, `infra/ci/deploy.sh`, `infra/deploy/compose.yml`, `infra/deploy/receive.sh` 뿐이라 `infra/ci/release.yml` 이나 `.gitlab-ci.yml` 만 고치면 이 job 은 돌지 않는다. `frontend-deploy` 는 `infra/ci/frontend.yml` 과 `.gitlab-ci.yml` 을 포함한다.
- 실패를 숨기는 `allow_failure` 를 새로 추가하지 않는다 (`infra/OPERATIONS.md`).
