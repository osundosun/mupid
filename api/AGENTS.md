# AGENTS.md

`api/` — Mupid 백엔드(Spring Boot). 이 디렉터리에서 작업할 때 따를 규칙.

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
현재 `.gitignore` 에 `.env` 가 없으므로 `git add` 할 때 특히 주의할 것.

## Docker / Compose

파일이 두 벌이고 **이름이 헷갈리기 쉽다.**

| 파일 | 용도 | 스프링 |
|---|---|---|
| `compose.yml` | 개발(기본) | **호스트에서 직접 실행** |
| `compose.prod.yml` | 운영 | `api` 컨테이너 |

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

- 설정은 `nginx/templates/default.conf.template` **하나를 개발·운영이 공유**한다.
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
  지라 티켓이 있으면 뒤에 붙인다 — `chore: 스프링 프로젝트 생성 (S15P21A204-41)`
- 브랜치: `back/<type>/<티켓>-<요약>` (예: `back/chore/S15P21A204-41-create-spring-project`)
  백엔드 외 작업은 `study/<이름>/…`, `common/docs/…` 를 쓴다.
- master 로 직접 푸시하지 않는다. MR 을 통해 병합한다.
- 커밋·푸시는 요청받았을 때만 한다.
