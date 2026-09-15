# AGENTS.md

`api/` — Mupid 백엔드(Spring Boot). 이 디렉터리에서 작업할 때 따를 규칙.

## 지식 저장소

이 파일은 **규칙**만 담는다. 구조·설계·배경과 이미 밟은 함정은 **[knowledge/](knowledge/README.md)** 에 있다.

| | 답하는 질문 | 언제 |
|---|---|---|
| [domain/](knowledge/domain/README.md) | 무엇을 만드는가 | 기능 설계·스키마 변경 전 |
| [architecture/](knowledge/architecture/README.md) | 무엇이 어디에 있는가 | 코드를 찾아 들어가기 전 |
| [decision/](knowledge/decision/README.md) | 왜 그렇게 정했는가 | 구조를 바꾸려 할 때 |
| [convention/](knowledge/convention/README.md) | 어떤 규칙을 지키는가 | 커밋·브랜치·MR·문서 전 |
| [failure/](knowledge/failure/README.md) | 어디서 넘어졌는가 | **막혔을 때 가장 먼저** (159건) |
| [lesson/](knowledge/lesson/README.md) | 해 보니 어떻더라 | 회고·일하는 방식 점검 |

작업 전에 반드시 확인할 것:

- **엔티티를 건드린다면** → [decision/0011](knowledge/decision/0011-jpa-manages-schema.md) — `ddl-auto` 가 `none` 이라 **DDL 을 직접 실행해야 한다.** Flyway 는 쓰지 않는다.
- **테이블·컬럼을 추가하거나 바꾼다면** → [domain/erd.md](knowledge/domain/erd.md) — MySQL 스키마 설계의 기준은 ERD 다. **ERD 수정은 반드시 팀에 의사를 물은 뒤에 한다.**
- **배포에 반영되어야 한다면** → [decision/0008](knowledge/decision/0008-deploy-image-from-infra.md) — `api/Dockerfile` 과 `api/nginx/` 는 실제 배포에 쓰이지 않는다.
- **정해지지 않은 것을 만났다면** → [open-questions.md](knowledge/open-questions.md) — 이미 목록에 있는지 먼저 본다.
- **문서를 근거로 삼기 전에** → [failure/doc-drift.md](knowledge/failure/doc-drift.md) — 문서와 코드가 어긋난 지점 목록.

새로 밟은 함정은 `failure/` 에, 되돌리기 어려운 선택은 `decision/` 에 ADR 로, 지킬 규칙은 `convention/` 에 남긴다. 규약을 바꾸면 해당 문서를 **같은 MR 에서** 고친다.

`진행사항/` 의 개인 스터디 기록은 지식 저장소에 반영하지 않는다.

## 명령

작업 디렉터리는 항상 `api/`.

```bash
./gradlew build              # 빌드 + 테스트
./gradlew test               # 테스트
./gradlew bootRun            # 로컬 실행
./gradlew bootJar -x test    # jar
```

`gradlew.bat` 는 Windows 용이지만 Git Bash 에서는 `./gradlew` 를 쓴다.

## 스택 / 버전

Java 21 · Spring Boot **4.1.2-SNAPSHOT** · Gradle 9.7.1 · MySQL 8.4 · Redis 7.4 · nginx 1.27

- Boot 4 는 아직 SNAPSHOT 이라 `repo.spring.io/snapshot` 저장소가 `build.gradle` 과
  `settings.gradle`(pluginManagement) **양쪽에** 필요하다. 지우면 빌드가 깨진다.
- Boot 4 는 스타터 이름이 바뀌었다. `spring-boot-starter-web` 이 아니라
  **`spring-boot-starter-webmvc`**, 테스트는 `spring-boot-starter-test` 하나가 아니라
  `spring-boot-starter-<모듈>-test` 로 모듈별로 나뉜다. 3.x 문서를 그대로 옮기지 말 것.
- 의존성을 추가하면 `build.gradle` 의 기존 주석 그룹(`// SpringBoot 관련`, `// JWT 관련` …)에 맞춰 넣는다.

## 코드

- 베이스 패키지: `com.osundosun.mupid.api`
- 공통 설정은 `common/config` 에 둔다 (`SecurityConfig` 가 여기 있다).
- Lombok 사용 가능 (`compileOnly` + `annotationProcessor` 등록됨).
- 들여쓰기는 기존 파일을 따른다 — Gradle 스크립트와 부트스트랩 클래스는 탭, 새로 쓴 설정 클래스는 스페이스 4칸.

## 설정 규칙

**모든 환경 변수는 `MUPID_` 접두사를 쓴다.** 이건 이 프로젝트의 강한 규칙이다.

- `application.yaml` 은 값을 직접 적지 않고 `${MUPID_...}` 로만 참조한다.
- 실제 값은 `.env` 에 두고, `spring.config.import: optional:file:.env[.properties]` 로 주입된다.
- 새 설정을 추가하면 **`.env.example` 도 같이 갱신한다** (값은 비우되 키와 주석 섹션은 유지).
- `SPRING_DATA_REDIS_HOST` 같은 스프링 관례 변수로 우회하지 말 것. compose 에서 덮어쓸 때도
  `MUPID_REDIS_HOST` 처럼 프로젝트 변수명을 쓴다.
- 커스텀 프로퍼티(`jwt.secret` 등)를 추가하면
  `src/main/resources/META-INF/additional-spring-configuration-metadata.json` 에 메타데이터를 넣는다.

`.env` 에는 DB 비밀번호와 JWT 시크릿이 있다. **절대 커밋하지 않는다.**
`api/.gitignore` 1행이 `.env` 라 기본적으로는 무시된다. `git add -f` 로 우회하지 말 것.

## Docker / Compose

compose 가 **네 벌**이고 이름이 헷갈리기 쉽다. `api/` 안의 것만 보면 오해한다.

| 파일 | 프로젝트명 | 어디서 도는가 | 스프링 |
|---|---|---|---|
| `compose.yml` | `mupid-dev` | 개발자 로컬 | **호스트에서 직접 실행** |
| `compose.prod.yml` | `mupid` | 최초 서버 부트스트랩, 로컬 운영 스모크 | `api` 컨테이너 (`build:`) |
| `compose.ci.yml` | 없음 | **어디서도 실행되지 않음** | — |
| `../infra/deploy/compose.yml` | `mupid` | **실제 EC2 운영** | `api` 컨테이너 (`image:`) |

- **`compose.prod.yml` 은 실배포 파일이 아니다.** 실제 EC2 가 쓰는 것은 `infra/deploy/compose.yml`,
  nginx 설정도 `nginx/templates/` 가 아니라 `infra/deploy/nginx.conf` 다.
  배포에 반영되어야 하는 변경은 반드시 [decision/0008](knowledge/decision/0008-deploy-image-from-infra.md) 을 먼저 읽는다.
- 배포되는 이미지도 `api/Dockerfile` 이 아니라 `infra/image/Dockerfile` 로 굽는다.
- 개발 스택은 인프라 + nginx 만 띄운다. 여기에 `api` 서비스를 추가하지 말 것.
- 운영 스택에서 MySQL·Redis 는 `expose` 만 하고 호스트로 포트를 열지 않는다. 호스트에 노출되는 건 nginx 뿐이다.
- 네트워크는 `frontend`(nginx↔api) / `backend`(api↔mysql,redis) 로 나눠져 있다. nginx 를 `backend` 에 붙이지 말 것.
- 포트는 전부 `.env` 변수로 뺀다(`MUPID_NGINX_PORT` 등). compose 에 숫자를 직접 박지 않는다.

### Dockerfile

- 베이스는 `azul-zulu:21-jdk` → `azul-zulu:21-jre` 멀티스테이지이고, **Debian 기반**이다.
  Alpine(BusyBox) 문법(`addgroup -S`, `adduser -S`)은 실패한다. `addgroup --system` 을 쓴다.
- 이미지에 `curl`·`wget` 이 없다. 헬스체크용 `curl` 은 런타임 스테이지에서 apt 로 설치한다.
- 의존성 캐싱을 위해 `gradlew`/`gradle`/`*.gradle` 을 먼저 복사하고 `dependencies` 를 돌린 뒤
  `src` 를 복사한다. 이 순서를 바꾸면 매 빌드마다 의존성을 다시 받는다.

### nginx

- 설정은 `nginx/templates/default.conf.template` **하나를 `compose.yml` 과 `compose.prod.yml` 이 공유**한다.
  **실제 EC2 의 nginx 설정은 이 템플릿이 아니라 `infra/deploy/nginx.conf` 다.**
- 환경 차이는 `API_UPSTREAM` 환경 변수로만 준다 (개발 `host.docker.internal:8800`, 운영 `api:8800`).
- 템플릿은 nginx 공식 이미지의 envsubst 로 치환된다. `NGINX_ENVSUBST_FILTER: "^API_UPSTREAM$$"` 로
  범위를 좁혀 뒀으니, nginx 변수는 `$host` 처럼 **중괄호 없이** 쓴다.

## 검증

변경 후에는 헬스체크까지 확인한다.

```bash
docker compose -f compose.prod.yml up -d --build
curl http://localhost:8080/api/actuator/health   # {"status":"UP"}
docker compose -f compose.prod.yml down -v
```

- 컨테이너 healthcheck 가 있으므로 `docker ps` 의 `(healthy)` 표시로도 확인할 수 있다.
- 운영 스택에서 nginx 는 `api` 가 healthy 가 된 뒤에야 뜬다. nginx 가 안 뜨면 먼저 `api` 로그를 본다.
- API 수동 확인은 `docs/` 의 Bruno 컬렉션을 쓴다. 엔드포인트를 추가하면 컬렉션에도 추가한다.

### Windows / Git Bash 주의

- `docker exec` 등에 `/etc/nginx/...` 같은 절대경로를 넘기면 Git Bash 가 경로를 변환한다.
  `MSYS_NO_PATHCONV=1` 을 앞에 붙인다.
- 포트가 이미 점유돼 있을 수 있다. `.env` 의 포트 변수를 바꿔서 피한다.

## Git

- 커밋 메시지는 **한글 Conventional Commits**: `feat: …`, `fix: …`, `docs: …`, `chore: …`
  지라 티켓은 `.husky/commit-msg` 가 제목 끝에 **소괄호**로 자동으로 붙인다 —
  `chore: 셸 스크립트 실행 권한 부여 (S15P21A204-184)`
  (과거 훅이 넣은 대괄호 `[S15P21A204-184]` 는 이력에 남아 있다. 따라 쓰지 않는다.)
- 브랜치: `back/<type>/<티켓>-<요약>` (예: `back/chore/S15P21A204-41-create-spring-project`)
  원격에 `feat` 와 `feature` 가 섞여 있다. 브랜치명으로 필터링하는 CI rule 을 쓸 때 주의한다.
  백엔드 외 작업은 `study/<이름>/…`, `common/docs/…` 를 쓴다.
- master 로 직접 푸시하지 않는다. MR 을 통해 병합한다.
- 커밋·푸시는 요청받았을 때만 한다.
