# GitLab CI/CD 변수

무엇을 등록해야 하고, 그 값이 어느 파일에서 오는지 한곳에 모았다.

변수 이름이 코드 어딘가에만 있고 아무 데도 적혀 있지 않으면, 새 사람이 파이프라인을
고칠 때 "왜 이 job 이 안 뜨지"로 반나절을 쓴다. `infra_k8s/tests` 의
`CiVariableDocTest` 가 이 표와 CI 파일이 어긋나면 실패한다.

## 지금 쓰는 것

| 변수 | Type | 값이 오는 곳 | 쓰는 곳 |
|---|---|---|---|
| `MUPID_DEPLOY_ENABLED` | Variable | `true` | 배포 job 전체의 스위치. 이 값이 없으면 배포가 아예 안 뜬다 |
| `MUPID_K8S_BASE_URL` | Variable | `https://j15a204.p.ssafy.io` | 배포 뒤 health·`/deployment.txt` 확인 |
| `MUPID_DEPLOY_K8S_SSH_KEY` | **File** | `mupid-deploy-k8s` 개인키 | `deploy-k8s.sh` 가 엣지 노드로 이미지를 보낼 때 |
| `MUPID_DEPLOY_K8S_KNOWN_HOSTS` | **File** | 엣지 노드 호스트 키 | 같은 스크립트. TOFU 를 쓰지 않으려고 미리 박아 둔다 |
| `MUPID_DATA_ENV` | **File** | `music_preprocessing/deploy.env.example` 를 채운 것 | `data-deploy` (JupyterHub 배포). 접속 정보·배치 설정·토큰이 **전부 이 파일 하나** 안에 있다 |

## 아직 배선하지 않은 것

값은 뽑아 두었지만 CI 가 아직 읽지 않는다. 배선할 때 이 이름으로 맞춘다.

| 변수 | Type | 값이 오는 곳 | 배선하려면 |
|---|---|---|---|
| `MUPID_API_ENV` | **File** | 운영 노드의 `/srv/mupid-k8s/.env` (13개) | 지금은 사람이 서버에서 `kubectl create secret --from-env-file` 로 만든다. 배포 job 이 Secret 을 갱신하도록 고쳐야 한다 — 운영 Secret 을 건드리는 변경이라 따로 다룬다 |
| `MUPID_FRONTEND_ENV` | **File** | `MUPID_DEPLOY_TARGET`·`MUPID_API_ORIGIN` | 지금은 `frontend.yml` 의 `variables:` 에 `ec2` 가 박혀 있다 |
| `MUPID_APP_ENV` | **File** | `MUPID_API_ORIGIN`·`MUPID_DEPLOY_TARGET` | Tauri 앱 빌드 job 이 아직 없다 |

## 정리해도 되는 것

| 변수 | 왜 |
|---|---|
| `MUPID_JUPYTER_BASE_URL`<br>`MUPID_JUPYTER_USER`<br>`MUPID_JUPYTER_PASSWORD` | `MUPID_DATA_ENV` 하나로 합쳤다. 셋 다 그 파일 안의 `JUPYTER_*` 항목이 되었다 |
| `MUPID_DEPLOY_SSH_KEY`<br>`MUPID_DEPLOY_KNOWN_HOSTS` | Compose 배포 전용. `infra/ci/release.yml` 이 `.gitlab-ci.yml` 의 include 에서 빠지면서 쓰는 job 이 없어졌다. **k8s 용은 이름에 `_K8S_` 가 들어간 쪽**이고, 둘을 섞으면 안 되도록 일부러 이름을 갈라 두었다 |
| `GITLAB_REVIEW_TOKEN` | `gemini-code-review` job 이 부르는 `scripts/gemini_review.py` 가 저장소에 없다. 그 job 은 `study/이규원/` 브랜치 MR 에서만 도는 규칙이라 잠들어 있다 |

## File 형식을 쓰는 이유

`Type: File` 이면 환경변수에 들어가는 것이 **내용이 아니라 경로**다. `echo $VAR` 를
해도 경로만 나오므로 job 로그로 값이 새지 않는다. 마스킹 조건(8자 이상 등)도
따질 필요가 없다 — JupyterHub 비밀번호가 8자 미만이라 마스킹이 거부됐던 것이
이 방식으로 해결됐다.

이 저장소는 이미 배포용 SSH 개인키를 File 로 넣고 있다. 예전에 그것을 Variable 로
넣었다가 스크립트가 키 전체를 job 로그에 찍은 적이 있고, 키를 교체하고 로그를
지워야 했다.

**전부 Protected 로 둔다.** 보호 변수는 보호 브랜치(master) 파이프라인에만
주어지므로, 아무나 올린 기능 브랜치에서 job 을 하나 만들어 값을 빼 가는 길이 막힌다.

## CI 변수가 아닌 `.env.example` 들

이름이 비슷해 헷갈리지만 CI 와 무관하다.

| 파일 | 무엇 |
|---|---|
| `api/.env.example` | 로컬 개발용. 포트가 13306·16379 처럼 개발 포트다 |
| `ai_server/.env.example` | ai_server 로컬 실행용 |
| `infra/deploy/.env.example` | Compose 시절 운영 파일. `mupid-secrets` 의 키 목록이 여기서 온다는 점만 아직 유효하다 |
| `infra_k8s/.env.example` | Ansible 이 노드에 접속할 때 쓰는 주소·키 경로. 워크스테이션의 로컬 파일이다 |
