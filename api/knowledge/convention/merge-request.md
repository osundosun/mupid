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
| **영역 라벨** | **MR 을 만들 때 반드시 붙인다** (`Backend`·`Frontend`·`AI`·`Data`·`Infra`). 없거나 모자라면 CI 가 실패한다 | `infra/ci/labels.yml` |
| MR 템플릿 / 승인 규칙 | 저장소에 파일이 없다 | `git ls-files` |

`master` 는 서버가 아니라 합의로만 막혀 있다 — `infra/OPERATIONS.md` 가 "GitLab master 기본 규칙은 Maintainer push를 허용하므로 팀 규칙상 직접 push하지 않는다. develop은 서버 설정에서도 직접 push 금지다"라고 적는다.

흐름 (`infra/OPERATIONS.md`):

1. 작업 브랜치에서 변경 후 `develop` 대상 MR 생성 — **영역 라벨을 함께 붙인다**
2. CI 통과 및 변경 검토 (문서는 "백엔드는 Gradle build/test"라고 적지만 실제로는 test 를 건너뛴다 — 아래)
3. MR 병합 후 이미지/정적 파일 패키징과 demo 배포 자동 실행
4. pipeline 의 deploy job 성공과 서비스 확인. MR 의 수동 package job 은 일반 MR 병합의 필수 단계가 아니다

### 영역 라벨을 붙여야 검증이 돈다

MR 을 만들 때 `Backend`·`Frontend`·`AI`·`Data`·`Infra` 중 해당하는 라벨을 **같은 명령에서** 붙인다.
라벨이 없거나 모자라면 `area-preflight`(`infra/ci/labels.yml`)가 **실행 시점에** 변경 경로를 계산해
(`git diff`) 실패하고, 영역 verify job 은 `rules` 의 `when: never` 때문에 **하나도 돌지 않는다** —
아무것도 검증하지 않은 MR 이 된다.

```bash
glab mr create --label Backend --label Frontend ... --label Infra
```

**고르는 기준은 바뀐 경로다.** 라벨이 그대로 그 영역의 verify job 을 켜므로, 한 영역이라도 빠뜨리면
그쪽은 아무도 안 본다.

| 라벨 | 이 job 이 돈다 | 이 경로를 건드리면 붙인다 |
|---|---|---|
| `Backend` | `backend-verify` | `api/**`, `infra/image/**` |
| `Frontend` | `frontend-verify` | `frontend/**`, `app/**`(Tauri 앱 셸), `prototype/**` |
| `AI` | `ai-server-verify` | `ai_server/**` |
| `Data` | `data-verify` | `music_preprocessing/**`, `infra/ci/deploy-jupyter.py` |
| `Infra` | `infra-verify` | 나머지 `infra/**`, `infra_k8s/**`, `.gitlab-ci.yml` |

경로→영역 매핑의 단일 출처는 `infra/ci/area_diff.py` 다. 긴 접두사가 먼저 매칭되므로
`infra/ci/deploy-jupyter.py` 는 `Infra` 가 아니라 `Data` 다. 매핑을 고치면 표·job·테스트 세 곳을
같이 고친다(`infra_k8s/tests` 의 `AreaRoutingTest` 가 어긋나면 실패한다).

**여러 영역을 건드리면 라벨도 여러 개 붙인다.** `api/` 코드를 고치면서 `frontend/` 의 화면 코드도
고치는 변경은 흔한데, `Backend` 만 붙이면 `frontend-verify` 가 조용히 빠진다 — 실제로 그렇게 했다
(S15P21A204-351, 그리고 `b0130b2` 는 `data-verify` 를 빠뜨렸다). 라벨 개수와 바뀐 영역 수를 맞춰
보고, **파이프라인의 job 목록에 기대한 verify job 이 전부 있는지** 확인한다.

**나중에 라벨을 붙였으면 새 파이프라인을 만들어야 한다 — job 재시도는 안 된다.**
GitLab 은 `rules` 를 파이프라인 **생성 시점**에 평가하고, job 재시도는 job 의 실행만 다시 할 뿐
job 자체를 다시 만들지 않는다. 라벨 변경으로 MR 파이프라인도 다시 돌지 않는다.

```bash
glab mr update <iid> --label Frontend
glab api --method POST "projects/<프로젝트>/merge_requests/<iid>/pipelines"
```

`area-preflight` 가 실패하면 그 로그가 위 두 줄을 iid·프로젝트까지 채워서 그대로 찍어 준다.

새 파이프라인 결과에서 `area-preflight`·`area-gate` 가 통과하고 영역 verify job 이 나타나면
라벨이 인식된 것이다. `area-gate`(`infra/ci/area-gate.yml`)는 영역 job 이 남긴 marker 를 대조하므로
"job 목록에는 있는데 실제로는 안 돌았다" 도 여기서 잡힌다. 게이트는 별도 stage 에 있고
`when: always` 라서 앞 stage 의 영역 검증이 실패해도 **반드시 돌아** 어느 영역이 검증되지
않았는지, 그리고 그것이 job 실패 때문인지 job 부재 때문인지 갈라 말한다.

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
