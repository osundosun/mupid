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
| `MUPID_AI_NODE_SSH` | Variable | `mupid-deploy-k8s@<node-b 주소>` | **필수.** 추천 서버는 `nodeSelector` 로 node-b 에 뜨고, 이미지·색인은 레지스트리 없이 **그 노드**의 디스크로 간다. 기본값을 두지 않는다 — 있으면 잘못된 노드로 조용히 올라간다 |
| `MUPID_DEPLOY_K8S_SSH_HOST` | Variable | (기본값 node-a) | api·web 이 쓰는 배포 주소. 지금까지 쓰던 엔드포인트가 기본값이라 보통 설정하지 않는다 |
| `MUPID_APP_ENV` | **File** | `app/deploy.env.example` 를 채운 것 — 릴리스 키스토어(base64)·비밀번호·별칭·`MUPID_API_ORIGIN`. 선택: `MUPID_CRASHLYTICS_ENDPOINT`·`MUPID_CRASHLYTICS_API_KEY`(구 경로 — SDK 전환 후에는 불필요), **`GOOGLE_SERVICES_JSON_BASE64`**(SDK 필수) | `app-verify`·`app-build` (Tauri APK). 서명 키가 들어 있다 — **원본 .jks 는 따로 백업**한다. Crashlytics 둘은 **컴파일 시점에 박히므로**(`app/src/crash.rs` 의 `option_env!`) 값을 바꾸면 APK 를 다시 구워야 한다 |
| `MUPID_NATIVE_BUILD` | Variable | `sh infra/ci/build-stamp.sh` — **CI job 이 정하고 export 한다** | `build-apk.sh` 가 APK 의 `versionCode`(`/1000`)와 Rust 상수를 같은 값으로 박고, `deploy-apk.sh` 가 그 값을 `upload-apk` 의 셋째 인자로 넘긴다(수신기가 그 값으로 `native.json` 을 만든다 — 앱이 읽는다) |
| `MUPID_WEB_BUILD` | Variable | 같음. 같은 스탬프를 `MUPID_WEB_BUILD` 로 다시 export 한다 | `build:archive` 가 웹 번들의 `builtAt` 을 정하고, APK 빌드가 **같은 값**을 `option_env!` 로 박아 임베드 번들의 나이로 쓴다. 두 값이 다르면 새 APK 를 설치한 직후 앱이 자기 번들을 다시 받는다 |
| `MUPID_BUILD_STAMP` | Variable | `sh infra/ci/build-stamp.sh` 가 값의 출처를 찾을 때 **가장 먼저** 본다 | 위 두 값의 유일한 출처다. 사람이 수동 배포하거나 값을 고정하고 싶을 때 여기에 넣는다. 없으면 `CI_COMMIT_TIMESTAMP` → `git show` → 현재 시각 순으로 내려간다 |
| `MUPID_DATA_ENV` | **File** | `music_preprocessing/deploy.env.example` 를 채운 것 | `data-deploy`·`data-batch`·`data-check` (JupyterHub). 접속 정보·배치 설정·토큰이 **전부 이 파일 하나** 안에 있다 |
| `MUPID_API_ENV` | **File** | **GitLab CI/CD 변수**에 넣은 운영 환경변수 내용. 노드의 `/srv/mupid-k8s/.env` 는 **수신기가 쓰는 결과물**이지 사람이 넣는 입력이 아니다 | `k8s-deploy-env` (`infra_k8s/ci/deploy-env.sh`). 내용을 그대로 노드로 보내면 **노드의 root 수신기(`receive-env.py`)가 Secret `mupid-secrets` 를 갈아 끼우고 api 를 재시작**한다. CI 는 Secret 을 직접 만들지 않는다 — 배포 계정의 RBAC 이 `secrets` 를 막고 있다(`rbac.yaml`). 열면 CI 키를 훔친 쪽이 DB 비밀번호·JWT 서명키·S3 키를 전부 읽게 된다 |
| `MUPID_AI_SERVER_ENV` | **File** | 추천 서버(`ai`) 전용 환경변수 내용. 키 목록은 아래 | `k8s-deploy-ai-env` (`infra_k8s/ci/deploy-ai-env.sh`) → 노드 수신기 `receive-ai-env.py` → Secret **`mupid-ai-secrets`** → **`ai` 만** 롤아웃. `MUPID_API_ENV` 와 나눠 둔 것은 AI 비밀(Pinecone 키)을 Spring 파드가 받지 않고, AI 값 하나 고치자고 api 까지 재시작되지 않게 하려는 것이다 |
| `MUPID_SYSTEM_CI_REFRESH_TOKEN` | Variable (**Protected·Masked**) 또는 File | `system-ci@mupid.local` 의 **장기 리프레시 토큰** — 관리자가 `POST /api/v1/user/system/{userId}/token` 에 `{"refresh_token_validity_days": 180}` 으로 받는다(`api/docs/User/시스템 계정 토큰 발급.yml`). ROLE_SYSTEM 토큰은 **회전하지 않으므로** CI 가 이 변수를 다시 쓸 일이 없다(설계 §11 미결 10 의 결정) | `model-publish`(업로드 ④-d·관문 ④-c)·`k8s-deploy-ai`(굽기 ④-e·채택 ④-g) — `infra/ci/model_publish.py`. `models.lock` 에 채택된 모델이 없으면 읽지 않는다. **만료 전에 갈아 끼운다** — 새 토큰을 받아 이 변수를 바꾸고, 새면 관리자가 `DELETE /api/v1/user/system/{userId}/token` 으로 일괄 폐기한다 |
| `MUPID_MODEL_RECOMPUTE_GATE` | Variable | `true` 또는 비움(기본 꺼짐) | `model-publish` 의 관문 ④-c — 새 모델의 재계산(`stage1_v{N}`)이 전 구간에 채워졌는지 Spring 에 묻는다(`GET /api/v1/system/vectors/stage1/{N}/status` 의 `complete`, S15P21A204-457). 꺼져 있으면 경고만 찍고 올린다. **켜는 법은 아래** |

> **재계산 관문 켜기** (`MUPID_MODEL_RECOMPUTE_GATE`, S15P21A204-457) — 켜는 것 자체는 운영 결정이다.
>
> 1. **먼저 Qdrant 가 채워져 있어야 한다.** 운영 컬렉션을 만들고(`infra_k8s/OPERATIONS-K8S.md` 7-1) 분석
>    배치가 전 곡 벡터를 보낸 뒤(`music-preprocessing vectors-send`), 대조 불일치가 0 인지 본다(관리자 대시보드
>    "Qdrant 벡터 반영"). 비어 있는 채로 켜면 모든 모델 게시가 멈춘다.
> 2. 새 모델 버전 `v{N}` 의 재계산을 보낸다: `music-preprocessing vectors-stage1 v{N} --vectors <구간 벡터 .npy>`.
>    끝에 관문 수치(`filled`/`expected_sections`, Qdrant `mert` 수)를 찍는다 — `complete=True` 여야 한다.
> 3. GitLab → Settings → CI/CD → Variables 에 `MUPID_MODEL_RECOMPUTE_GATE` = `true` (Variable, Protected).
>    다음 `models.lock` 병합부터 `model-publish` 가 `complete` 가 아니면 **올리지 않고 멈춘다**
>    (메시지에 채워진 수·기대 수가 나온다). 끄려면 변수를 지운다 — 경고만 찍고 올리는 지금 동작으로 돌아간다.
>
> `complete` 는 `filled ≥ MySQL 의 살아 있는 곡 구간 수` 이고 `filled ≥ Qdrant mert 포인트 수` 다. 뒤의 조건은
> AI 준비 판정의 분모라, 삭제된 곡의 포인트가 남아 있으면(매일 04:50 대조가 지운다) 관문이 멈춘다.

> **`MUPID_AI_SERVER_ENV` 에 넣는 키** (2026-09-23 갱신, S15P21A204-423)
>
> **추천 서버는 Spring 을 부르지 않는다.** 후보와 곡·구간 값은 추천 요청 본문(`catalog`)으로
> 온다(연동 계약 2026-09-23 절). 그래서 2026-09-22(416)에 넣었던 `MUPID_API_BASE_URL`·
> `MUPID_API_REFRESH_TOKEN`·`MUPID_AI_CATALOG_*` 는 **빼야 한다** — 남겨 둬도 읽지 않지만,
> 쓰지 않는 ROLE_SYSTEM 토큰이 파드에 있을 이유가 없다. 이 파일은 키가 전부 선택이라
> 비어 있어도 배포된다.
>
> | 키 | 필수 | 뜻 |
> |---|---|---|
> | `MUPID_PINECONE_API_KEY` | 켤 때 **예** | Pinecone API 키. 값은 어디에도 찍지 않는다 |
> | `MUPID_PINECONE_INDEX` | 켤 때 **예** | 순간 벡터 인덱스 이름. **하나다** — 평균은 `<namespace>__control` 에 들어간다 |
> | `MUPID_PINECONE_NAMESPACE` | 아니오 | 환경 + 승인된 임베딩 버전. 예: `prod__mert-v1-95m` |
> | `MUPID_PINECONE_ENABLED` | 아니오 | 검색을 Pinecone 으로 돌릴지. 비우면 꺼짐 |
> | `MUPID_PINECONE_TIMEOUT` | 아니오 | 호출 하나의 제한 시간(초, 기본 10) |
> | `MUPID_AI_SERVER_URL` | 갱신 배치에 **예** | 갱신 배치가 `/health` 로 "서버가 새 μ 를 읽었나" 를 확인할 주소. 확인 못 하면 `READY` 로 넘어가지 않는다 |
> | `MUPID_AI_SERVER_DIR` | 아니오 | 분석 배치 호스트의 `ai_server` 체크아웃 경로 (기본 `$HOME/mupid_ai_project_dev`) |
> | `MUPID_VECTOR_BACKEND` | 아니오 | 순간 벡터 갈래 `local`(기본) \| `qdrant` \| `pinecone` (S15P21A204-456). 비우면 예전 `MUPID_PINECONE_ENABLED` 를 따른다. **Qdrant 에 벡터가 채워진 뒤(457)** `qdrant` 로 바꾼다 — 그 전에 바꾸면 준비 판정이 "컬렉션이 비었다" 로 실패해 추천이 멈춘다 |
> | `MUPID_QDRANT_READ_ONLY_API_KEY` | qdrant 에 **예** | Qdrant 읽기 전용 키. ai 가 쓰고, **Qdrant 파드도 같은 Secret 에서 이 키를 받는다**(`infra_k8s/base/qdrant.yaml`). 비면 Qdrant 가 무작위 키로 잠긴 채 뜬다. 값을 바꾸면 ai 는 수신기가 다시 띄우지만 Qdrant 는 사람이 `kubectl -n mupid rollout restart statefulset/qdrant` |
> | `MUPID_QDRANT_URL` | 아니오 | 기본 `http://qdrant:6333` (클러스터 안 ClusterIP) |
> | `MUPID_QDRANT_COLLECTION`·`MUPID_QDRANT_TIMEOUT` | 아니오 | 기본은 `ai_server/app/qdrant_collection.json` 의 이름 · 5초 |
>
> **Qdrant 쓰기 키 `MUPID_QDRANT_API_KEY` 는 여기가 아니라 `MUPID_API_ENV`** 에 넣는다(Secret
> `mupid-secrets`, `infra/deploy/.env.example`). 쓰는 것은 Spring(벡터 등록, 457)과 스냅샷 CronJob
> 뿐이고, ai 는 쓰기 키를 받지 않는다(설계 §6.4). 이 키도 바꾸면 Qdrant 를 다시 띄운다.
>
> **분석 배치에도 같은 파일 변수를 쓴다.** JupyterHub 배치는 `deploy-jupyter.py --batch`
> 가 `MUPID_AI_SERVER_ENV` 를 읽어 **배치 프로세스의 환경으로만** 넘긴다 — 서버
> 디스크에 쓰지 않고 로그에도 찍지 않는다. 키 이름만 출력한다.
>
> `MUPID_PINECONE_ENABLED` 를 켰는데 앞의 셋 중 하나라도 비면 **수신기가 업로드를
> 거부한다.** 켜 놓고 값을 빼면 추천 서버가 뜨기는 하는데 모든 검색이 실패한다.
>
> 필수 둘이 비면 **수신기가 업로드를 거부한다.** 그 값 없이는 추천 서버가 준비 완료로
> 올라오지 않는다 — 없이 뜨면 장르·무드·보컬·악기 조건이 통째로 빈 채 추천이 나가고,
> 그것은 "조건에 맞는 곡이 없다" 로만 보여 정상과 구분되지 않는다.
>
> **값은 어디에도 찍지 않는다.** 수신기는 키 이름만 세어 출력하고, 추천 서버의
> `/health` 는 주소 설정 여부(`base_url_set`)만 싣는다.

> **`GOOGLE_SERVICES_JSON_BASE64`** (2026-09-21 추가, S15P21A204-383 2단계)
>
> Firebase Crashlytics 를 **SDK 정공법**으로 붙이면서 필요해졌다. `google-services` Gradle 플러그인이 `app/gen/android/app/google-services.json` 을 요구하는데, 그 파일은 `app/.gitignore:29` 가 막는다(프로젝트 번호·API 키·앱 ID 가 들어 있다) — **CI 체크아웃에 없다.**
>
> `infra/ci/build-apk.sh` 가 **키스토어와 같은 패턴**으로 복원한다:
>
> ```bash
> base64 -w0 app/gen/android/app/google-services.json   # 이 값을 MUPID_APP_ENV 에 넣는다
> ```
>
> **없으면 `app-build` 가 실패한다** — 플러그인 기본 전략이 `ERROR` 다. build-apk.sh 가 그 경우 경고를 찍는다.
>
> `MUPID_CRASHLYTICS_ENDPOINT`·`_API_KEY` 는 **구 경로**다(비공개 수집 엔드포인트). SDK 전환 후에는 쓰지 않지만, 스크립트가 아직 읽으므로 남겨 둔다 — 값을 넣지 않으면 예외 보고가 꺼진 채로 굽는다.

> **MUPID_API_ENV 는 이미 배선돼 있다.** 2026-09-16 `6363c35`(S15P21A204-341)에서 들어왔다.
> 이 표는 한동안 *"아직 배선하지 않은 것"* 이라고 적고 있었는데 **사실이 아니었다** — 실제로는
> master 파이프라인마다 `k8s-deploy-env` 가 돌고 있었다(207409·207390 success, job 로그에
> `보내는 항목 19개` → `UNCHANGED …`). 문서가 그 커밋 이전 서술을 들고 있었던 것이다.
>
> **표를 먼저 의심하라.** job 이 안 뜨면 코드보다 이 문서를 먼저 의심하라.
>
> **내용이 예전과 같으면 수신기가 `UNCHANGED` 를 돌려주고 아무것도 하지 않는다.** 그래서 master
> 푸시마다 돌려도 api 가 매번 재시작하지 않는다. 대신 **값을 노드에만 수동으로 넣고 CI 변수를
> 갱신하지 않으면**, 이 job 이 옛 내용을 `UNCHANGED` 로 판정해 그 키가 조용히 반영되지 않는다.

#### 키를 더할 때 — **세 곳을 같이 고친다**

| # | 고칠 곳 | 빠뜨리면 |
|---|---|---|
| ① | `MUPID_API_ENV` (GitLab CI/CD **File** 변수, Protected) | 노드에 안 내려간다 |
| ② | `infra_k8s/base/api.yaml` 의 `secretKeyRef` | Secret 에는 들어가는데 **파드가 안 읽어 조용히 빈다**(`SecretKeyTest` 가 잡는다) |
| ③ | `infra/deploy/.env.example` 선언 | `SecretKeyTest` 가 실패한다 |

②가 필요한 이유: Secret 을 `envFrom` 으로 통째로 주입하지 않는다. 그 안에 api 가 쓸 일 없는
`MUPID_MYSQL_ROOT_PASSWORD` 같은 것도 있어서다.

선택 키(`MUPID_MATTERMOST_WEBHOOK_URL` 등)는 `receive-env.py` 의 경고 목록에 넣으면 비어 있을 때
배포 로그에 남는다 — 조용한 비활성은 *"왜 알림이 안 오지"* 로 한참 뒤에 드러난다.

| 변수 | Type | 값이 오는 곳 | 배선하려면 |
|---|---|---|---|
| `MUPID_FRONTEND_ENV` | **File** | `MUPID_DEPLOY_TARGET`·`MUPID_API_ORIGIN` | **아직 이름만 있다.** `frontend.yml` 의 `variables:` 에 `MUPID_DEPLOY_TARGET: ec2` 가 박혀 있고, `MUPID_FRONTEND_ENV` 는 이 문서에만 있으며 코드가 읽지 않는다. 배선할지 폐기할지 정해야 한다 |

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
