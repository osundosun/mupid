# Docker · compose · nginx

13건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## Docker · compose

### `addgroup -S` 가 실패한다 / 헬스체크에서 `curl: not found`

- **원인**: 베이스 이미지 `azul-zulu:21-jdk`·`21-jre` 는 Alpine 이 아니라 **Debian** 기반이다. BusyBox 축약 문법(`addgroup -S`, `adduser -S`)이 통하지 않고, `curl` 도 `wget` 도 이미지에 없다.
- **해결**: `addgroup --system spring` / `adduser --system --ingroup spring --no-create-home spring` 을 쓰고, 헬스체크용 `curl` 은 런타임 스테이지에서 `apt-get install --no-install-recommends curl` 로 설치한다(현재 Dockerfile 27-31행이 그렇게 한다).
- **근거**: `api/Dockerfile:25-31`, `api/AGENTS.md`

### 소스 한 줄만 고쳤는데 매 빌드가 의존성을 다시 받는다

- **원인**: 레이어 캐싱 순서가 깨졌다. 현재는 `gradlew` → `gradle` → `settings.gradle build.gradle` 을 먼저 복사하고 `./gradlew dependencies` 를 돌린 뒤에야 `COPY src src` 를 한다. `src` 를 먼저 복사하거나 `COPY . .` 로 합치면 의존성 레이어가 매번 무효화된다.
- **해결**: 이 순서를 바꾸지 않는다. Boot 4 SNAPSHOT 을 `repo.spring.io/snapshot` 에서 받으므로 재다운로드 비용이 크다.
- **근거**: `api/Dockerfile:7-16`

### 릴리스 버전으로 바꿨더니 Docker 빌드가 jar 복사에서 깨진다

- **원인**: `cp build/libs/*-SNAPSHOT.jar /workspace/app.jar` 가 `api/build.gradle` 의 `version = '0.0.1-SNAPSHOT'` 에 묶여 있다. 릴리스 버전이면 글롭이 아무것도 잡지 못한다.
- **해결**: 버전 정책을 바꿀 때 두 경로를 함께 고친다 — CI 는 다른 규칙(`find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar'`)을 쓴다.
- **근거**: `api/Dockerfile:15-16`, `infra/ci/backend.yml`

### `api/Dockerfile` 을 고쳤는데 배포 이미지가 그대로다

- **원인**: 저장소에 Dockerfile 이 둘이고 **CI 가 쓰는 것은 `infra/image/Dockerfile`** 이다. `api/Dockerfile` 은 `azul-zulu:21-jdk` → `21-jre` 멀티스테이지로 소스를 직접 빌드하고, `infra/image/Dockerfile` 은 `azul-zulu:21-jre` 단일 스테이지로 이미 만들어진 `app.jar` 만 `COPY` 한다. `infra/ci/package-image.sh` 가 후자를 컨텍스트에 복사한다.
- **해결**: 배포 이미지에 반영하려면 `infra/image/Dockerfile` 을 고친다. 두 파일의 HEALTHCHECK 도 미세하게 다르다(`127.0.0.1` vs `localhost`).
- **근거**: `infra/ci/package-image.sh`, `api/Dockerfile`, `infra/image/Dockerfile`

### 배포 후 nginx 가 안 뜬다

- **원인**: `spring-boot-starter-data-redis` + actuator 조합이라 Redis 가 끊기면 `/api/actuator/health` 가 DOWN(503)이 된다. `api/Dockerfile` 의 HEALTHCHECK 가 응답에서 `"status":"UP"` 을 grep 하므로 api 컨테이너가 unhealthy 로 떨어지고, `compose.prod.yml` 의 nginx 는 `depends_on: api: {condition: service_healthy}` 라 아예 뜨지 않는다.
- **해결**: api 로그와 함께 **redis 컨테이너 상태를 먼저** 본다. ClickHouse 는 이 문제를 피하려고 의도적으로 health 집계에서 뺐지만 Redis 는 그 예외가 아니다.
- **근거**: `api/Dockerfile:38-39`, `api/compose.prod.yml`, `api/docs/Actuator/HealthCheck.yml`

### `docker compose up -d` 직후 `localhost:8080/api/...` 가 502 다

- **원인**: `api/compose.yml`(개발)의 nginx 에는 `depends_on` 이 없다. 업스트림이 호스트 프로세스(`host.docker.internal:8800`)라 기다릴 대상이 없기 때문이다. 스프링을 아직 안 띄웠으면 502 이고, nginx 자체는 `/healthz` 로 healthy 를 유지하므로 컨테이너 상태만 보면 정상으로 보인다.
- **해결**: 개발 스택에서는 호스트에서 스프링을 직접 띄워야 한다. 이 스택에 `api` 서비스를 추가하지 않는다.
- **근거**: `api/compose.yml`, `api/AGENTS.md`

### 새 컨테이너가 `port is already allocated` 로 못 뜬다

- **원인**: compose 는 **현재 파일에 정의된 서비스만** 정리한다. 서비스명이 바뀌면(예: `kafka-1`/`kafka-2` → `kafka`) 예전 컨테이너가 orphan 으로 남아 포트를 계속 잡는다.
- **해결**: 초기화할 때 `docker compose down -v --remove-orphans` 로 `--remove-orphans` 를 함께 붙인다.
- **근거**: `api/clickhouse/README.md`

### `.env` 로 포트를 바꿨는데 SeaweedFS 포트 충돌이 안 풀린다

- **원인**: `api/AGENTS.md` 는 "포트는 전부 `.env` 변수로 뺀다. compose 에 숫자를 직접 박지 않는다" 를 규칙으로 두는데, `storage`(SeaweedFS) 서비스만 `8333:8333`, `9333:9333`, `8888:8888` 로 고정이고 `.env.example` 에 대응 변수가 없다.
- **해결**: 이 세 포트는 compose 파일을 직접 고쳐야 피할 수 있다.
- **근거**: `api/compose.yml:81-83`, `api/AGENTS.md`

### 운영 compose 를 올렸더니 `up` 이 바로 실패한다

- **원인**: `infra/deploy/compose.yml` 의 네트워크 `frontend`/`backend` 와 볼륨 `mysql-data`/`redis-data`/`clickhouse-data`/`ch-keeper-data`/`kafka-data` 가 전부 `{external: true}` 다. 서버에 없으면 즉시 실패한다.
- **해결**: 서버에서 미리 만든다 — `docker volume create mupid-clickhouse-data` 등. 이름은 `api/compose.prod.yml` 이 `name:` 으로 만든 것과 같아야 부트스트랩 스택을 인계받는다.
- **근거**: `infra/deploy/compose.yml:264-274`

### 운영에서 `down -v` 를 치면 데이터가 사라진다

- **원인**: `down -v` 와 `docker volume prune` 은 MySQL/Redis 운영 볼륨을 지운다. 로컬 `api/compose.yml` 에서 `down -v` 는 정상 초기화 절차라 손에 익어 있고, 두 파일의 컨테이너·볼륨 이름이 비슷해(`mupid-*` vs `mupid-dev-*`) 대상을 착각하기 쉽다.
- **해결**: 운영에서는 금지다. 배포 스크립트가 쓰는 명령은 `up -d --no-build` 뿐이고 어디에도 `down` 이 없다.
- **근거**: `infra/OPERATIONS.md`, `infra/deploy/receive.sh`

---

---

## nginx

### 템플릿 주석대로 업스트림을 맞추면 정반대로 설정된다

- **원인**: `api/nginx/templates/default.conf.template` 1-3행 주석이 "운영(compose.yml) / 개발(compose.dev.yml)" 이라고 적고 있으나, 저장소에 `compose.dev.yml` 은 **없고** 실제로는 `api/compose.yml` 이 개발(`host.docker.internal:${MUPID_PORT:-8800}`), `api/compose.prod.yml` 이 운영(`api:8800`)이다. `api/AGENTS.md` 가 "파일이 두 벌이고 이름이 헷갈리기 쉽다"고 경고하는 바로 그 함정을 주석 자체가 밟았다.
- **해결**: `API_UPSTREAM` 은 각 compose 파일에서 직접 확인한다. compose 파일은 `compose.yml`(개발), `compose.prod.yml`(운영), `compose.ci.yml`(미사용) 세 개다.
- **근거**: `api/nginx/templates/default.conf.template:1-3`, `api/compose.yml`, `api/compose.prod.yml`

### nginx 변수가 빈 문자열로 치환된다

- **원인**: `NGINX_ENVSUBST_FILTER: "^API_UPSTREAM$$"` 가 치환 대상을 그 이름 하나로 못박고 있다. 이 필터를 풀거나 nginx 변수를 `${host}` 처럼 중괄호로 쓰면 envsubst 가 그것도 치환 대상으로 보고 지운다.
- **해결**: 템플릿 안 nginx 변수는 `$host`, `$remote_addr`, `$proxy_add_x_forwarded_for` 처럼 **중괄호 없이** 쓴다. compose YAML 안에서는 `$$` 로 써야 compose 보간을 통과해 리터럴 `$` 가 남는다.
- **근거**: `api/nginx/templates/default.conf.template`, `api/compose.yml`, `api/AGENTS.md`

### 업로드가 5MB 에서 막히는데 nginx 를 의심한다

- **원인**: nginx 는 `client_max_body_size 20m` 이라 병목이 아니다. 실제 병목은 Spring 의 멀티파트 기본 상한이다(아래 "외부 연동" 섹션 참고).
- **해결**: `spring.servlet.multipart.max-file-size` 쪽을 본다.
- **근거**: `api/nginx/templates/default.conf.template:13`

---
