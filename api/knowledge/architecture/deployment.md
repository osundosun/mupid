# 컨테이너 구성과 배포

로컬 스택을 띄우거나, compose·Dockerfile·nginx 설정을 고치거나, CI 파이프라인과 서버 배포 경로를 건드릴 때 읽는다. compose 파일이 다섯 벌(앱 스택 4 + `infra/runner/compose.yml`)이고 Dockerfile 이 두 벌이라 "지금 고치는 파일이 어디서 실행되는가"를 먼저 확인하지 않으면 엉뚱한 파일을 고치게 된다. 실제로 배포되는 이미지는 `api/Dockerfile` 이 아니라 `infra/image/Dockerfile` 로 구워지고, 실제 EC2 가 쓰는 nginx 설정은 `api/nginx/templates/default.conf.template` 이 아니라 `infra/deploy/nginx.conf` 다.

## 파일 배치

```
api/
  Dockerfile                              # 소스 → 이미지 (멀티스테이지). 로컬/수동용
  .dockerignore
  compose.yml                             # name: mupid-dev   로컬 개발
  compose.prod.yml                        # name: mupid       단일 호스트 운영/부트스트랩
  compose.ci.yml                          # 프로젝트명 없음    격리 통합테스트 (현재 미사용)
  nginx/templates/default.conf.template   # compose.yml · compose.prod.yml 공용
  clickhouse/                             # server/ users/ keeper/ init/ kafka/ verify.sh README.md
infra/
  image/Dockerfile                        # 이미 만든 app.jar → 이미지 (단일 스테이지). CI 전용
  ci/
    backend.yml frontend.yml release.yml  # GitLab CI job 정의
    package-image.sh deploy.sh frontend-release.sh
    wait-services.sh                      # 현재 참조하는 곳 없음
  deploy/                                 # 기본 EC2 에 설치하는 수신·운영 자산
    compose.yml nginx.conf .env.example
    ssh-entrypoint.sh receive.sh receive-front.py
    prepare-env.py test-rollback.sh
    backup.py backup-sync.py backup-receive.py
    mupid-backup.service mupid-backup.timer
  runner/compose.yml runner/configure.py  # GitLab Runner 자체
  bootstrap-docker.sh bootstrap-backend.sh
  README.md OPERATIONS.md PROGRESS.md
.gitlab-ci.yml                            # stages 정의 + include + gemini-code-review
```

서버 상태 디렉터리는 `/srv/mupid` 다. `receive.sh`·`receive-front.py`·`backup*.py` 가 읽고 쓰는 경로로, `.env`(root 600), `config/`(compose.yml·nginx.conf·clickhouse/), `deploy.lock`, `backup.lock`, `current-image`, `previous-image`, `releases/<SHA>/`, `web/<SHA>` 와 `web/current` 심볼릭 링크, `backups/`, `backup-ssh/`, `acme/` 로 구성된다.

## compose 4벌의 용도 구분

| 파일 | 프로젝트명 | 어디서 도는가 | Spring |
|---|---|---|---|
| `api/compose.yml` | `mupid-dev` | 개발자 로컬 | **호스트에서 직접 실행** (컨테이너 없음) |
| `api/compose.prod.yml` | `mupid` | 최초 서버 부트스트랩, 로컬 운영 스모크 | `api` 컨테이너 (`build:` 로 직접 빌드) |
| `api/compose.ci.yml` | 없음(디렉터리명) | 어디서도 실행되지 않음 | `test` 컨테이너에서 gradle build |
| `infra/deploy/compose.yml` | `mupid` | **실제 EC2 운영** | `api` 컨테이너 (`image:` 만, 빌드 안 함) |

`api/compose.prod.yml` 과 `infra/deploy/compose.yml` 은 `name: mupid` 와 컨테이너 이름(`mupid-nginx`, `mupid-api`, `mupid-mysql`, `mupid-redis`)이 같다. 부트스트랩이 만든 스택을 운영 compose 가 그대로 인계받는 구조다 (`infra/README.md`: "bootstrap 스크립트는 최초 구축용이다. 운영 전환 이후 현재 Compose는 `infra/deploy/compose.yml`이다").

`api/compose.ci.yml` 과 `infra/ci/wait-services.sh` 는 저장소 전체에서 참조가 0건이다. 현재 파이프라인의 일부로 읽으면 안 되고, 테스트를 되살릴 때 재사용할 자산으로만 본다.

## api/ compose 3종 비교

| 항목 | `api/compose.yml` | `api/compose.prod.yml` | `api/compose.ci.yml` |
|---|---|---|---|
| 서비스 | nginx, mysql, redis, storage, clickhouse-keeper, clickhouse-01, clickhouse-02, kafka, kafka-ui(`profiles: [ui]`), storage-init, kafka-init, clickhouse-init | nginx, api, mysql, redis | mysql, redis, test |
| api 빌드 | **없음** | `build: {context: ., dockerfile: Dockerfile}` + `image: mupid-api:latest` | `test` 가 `azul-zulu:21-jdk` 에서 `bash gradlew … build` |
| 네트워크 | `mupid-dev-frontend` / `mupid-dev-backend` (bridge) | `mupid-frontend` / `mupid-backend` (bridge) | 정의 없음 (compose 기본망) |
| nginx 소속망 | `frontend` 만 | `frontend` 만 | — |
| api 소속망 | — | `frontend` + `backend` | — |
| 호스트 포트 | nginx `${MUPID_NGINX_PORT:-8080}:80`, mysql `${MUPID_MYSQL_PORT:-13306}:3306`, redis `${MUPID_REDIS_PORT:-16379}:6379`, ch-01 `${MUPID_CLICKHOUSE_HTTP_PORT:-18123}`/`${MUPID_CLICKHOUSE_NATIVE_PORT:-19000}`, ch-02 `${MUPID_CLICKHOUSE_HTTP_PORT_2:-18124}`/`${MUPID_CLICKHOUSE_NATIVE_PORT_2:-19001}`, kafka `${MUPID_KAFKA_PORT:-19092}`, kafka-ui `${MUPID_KAFKA_UI_PORT:-18080}`, **storage `8333:8333` `9333:9333` `8888:8888` (숫자 고정)** | nginx `${MUPID_NGINX_PORT:-8080}:80` **뿐**. api/mysql/redis 는 `expose` 만 | 없음 |
| 볼륨 | named 6개(`mupid-dev-mysql-data`, `-redis-data`, `-storage-data`, `-ch-keeper-data`, `-ch1-data`, `-ch2-data`) + ro bind(nginx templates, ClickHouse XML **파일 단위** 7종 = server 4 + users 2 + keeper 1, create-topics.sh, init 디렉터리). Kafka 는 **의도적으로 볼륨 없음** | named 2개(`mupid-mysql-data`, `mupid-redis-data`) + `./nginx/templates:…:ro` | named `gradle-cache`, `.:/workspace` bind, mysql 은 `tmpfs: /var/lib/mysql` |
| depends_on | ch-01/02 → keeper `service_healthy`, kafka-ui → kafka, storage-init → storage, kafka-init → kafka, clickhouse-init → ch-01·ch-02 healthy + kafka-init `service_completed_successfully`. **nginx 에는 depends_on 이 없다** | `nginx → api: service_healthy`, `api → mysql·redis: service_healthy` | `test → mysql·redis: service_healthy` |
| api 헬스체크 | — | **compose 에 없다.** Dockerfile 의 `HEALTHCHECK` 를 그대로 쓴다 | — |
| restart | `unless-stopped` (init 3종만 `"no"`) | `unless-stopped` | 지정 없음 |
| 비밀값 | `.env` 보간. 필수 항목은 `${MUPID_CLICKHOUSE_PASSWORD:?…}` 로 표시 | `env_file: .env` + `environment` 로 컨테이너 내부 주소 덮어쓰기 | 파일에 CI 전용 평문 상수 하드코딩(`ci-only-root-password` 등) |

로컬에서 Spring 을 컨테이너에 넣지 않는 것은 규칙이다 (`api/AGENTS.md`: "개발 스택은 인프라 + nginx 만 띄운다. 여기에 `api` 서비스를 추가하지 말 것"). nginx 는 `host.docker.internal:${MUPID_PORT:-8800}` 을 업스트림으로 잡고 `extra_hosts: host.docker.internal:host-gateway` 로 Linux 에서도 성립시킨다. 덕분에 `bootRun` 핫리로드를 유지하면서 nginx 경유 경로(`/api/` 프록시, `/healthz`)를 로컬에서 그대로 재현할 수 있다.

네트워크를 둘로 나눈 이유는 `api/compose.prod.yml` 주석이 직접 말한다 — `frontend` 는 `nginx <-> api`, `backend` 는 `api <-> mysql/redis` 이고 "nginx 는 붙지 않는다". `api` 만 두 망에 동시에 속한다. mysql·redis 가 `ports` 대신 `expose` 만 쓰므로 호스트에 열리는 소켓은 nginx 하나뿐이고, UFW 규칙에 기대지 않고 토폴로지 자체로 데이터스토어를 격리한다.

## 운영 compose (infra/deploy/compose.yml)

`api/compose.prod.yml` 과의 차이만 본다.

| 항목 | `api/compose.prod.yml` | `infra/deploy/compose.yml` |
|---|---|---|
| api 이미지 | `build:` + `image: mupid-api:latest` | `image: ${MUPID_API_IMAGE:?image required}` — 빌드하지 않는다 |
| api 환경변수 | `env_file: .env` 전체 + `environment` | `env_file` **없음**. `environment` 에 나열된 키만 전달 |
| DB 계정 | `MYSQL_ROOT_PASSWORD: ${MUPID_MYSQL_PASSWORD}` — 앱이 root 자격증명을 그대로 쓴다 | root(`MUPID_MYSQL_ROOT_PASSWORD`)와 앱 계정(`MUPID_MYSQL_USER`/`MUPID_MYSQL_PASSWORD`) 분리 |
| nginx 설정 | `./nginx/templates:/etc/nginx/templates:ro` + envsubst | `/srv/mupid/config/nginx.conf:/etc/nginx/conf.d/default.conf:ro` — **템플릿·envsubst 없음** |
| TLS | 없음 (HTTP 80 만) | `/etc/letsencrypt:ro` + `/srv/mupid/acme:ro` 마운트, 443 리스너 |
| 정적 프런트 | 없음 | `/srv/mupid/web:/srv/web:ro`, nginx `root /srv/web/current` |
| 포트 | `${MUPID_NGINX_PORT:-8080}:80` | `${MUPID_HTTP_PORT}:80`, `${MUPID_HTTPS_PORT}:443` (기본값 없음) |
| 네트워크/볼륨 | compose 가 생성 | 전부 `external: true` — 서버에 미리 존재해야 한다 |
| 로그 | 지정 없음 | `x-logging` YAML 앵커로 전 서비스에 `json-file, max-size 10m, max-file 3` |
| 리소스 | 없음 | api `mem_limit: 2g`, clickhouse `${MUPID_CLICKHOUSE_MEM_LIMIT:-4g}`, kafka `${MUPID_KAFKA_MEM_LIMIT:-2g}` |
| 분석 스택 | 없음 (주석으로 "별도 호스트에 둔다" 명시) | `clickhouse`(profile `analytics`), `clickhouse-keeper`(`keeper`), `kafka`(`kafka`), `kafka-init`/`clickhouse-init`(`init`) |

### external 리소스

`networks` 의 `mupid-frontend`/`mupid-backend`, `volumes` 의 `mupid-mysql-data`/`mupid-redis-data`/`mupid-clickhouse-data`/`mupid-ch-keeper-data`/`mupid-kafka-data` 가 모두 `{external: true}` 다. 이 중 `mupid-frontend`·`mupid-backend`·`mupid-mysql-data`·`mupid-redis-data` 4개는 `api/compose.prod.yml` 이 `name:` 으로 만드는 것과 이름이 정확히 같아, 부트스트랩이 만든 리소스를 그대로 인계받는다. 나머지 `mupid-clickhouse-data`·`mupid-ch-keeper-data`·`mupid-kafka-data` 는 어떤 compose 도 만들지 않으므로 직접 만들어야 한다. 서버에 없으면 `up` 이 바로 실패한다.

```bash
docker volume create mupid-clickhouse-data
docker volume create mupid-ch-keeper-data
docker volume create mupid-kafka-data
```

external 로 선언하면 compose 가 볼륨 생명주기를 소유하지 않아 실수로 지워질 여지가 준다. `infra/README.md`·`infra/OPERATIONS.md` 가 반복하는 "`down -v` 금지"와 짝을 이루는 선택이다.

### 분석 스택을 profile 로 감춘 이유

ClickHouse/Kafka 는 같은 파일을 서버 2대에 올리고 환경변수로 역할만 나눈다.

```bash
# 서버 A (Spring):  .env 에 COMPOSE_PROFILES=analytics,keeper  CH_SHARD=01  CH_REPLICA=ch1
docker compose up -d clickhouse clickhouse-keeper
# 서버 B (FastAPI): .env 에 COMPOSE_PROFILES=analytics,kafka   CH_SHARD=02  CH_REPLICA=ch2
docker compose up -d clickhouse kafka

# 1회성 초기화 (자동 실행되지 않는다. 여러 번 실행해도 안전하다)
docker compose --profile init run --rm kafka-init        # 서버 B
docker compose --profile init run --rm clickhouse-init   # 서버 A
```

파일 주석이 호환성 근거를 밝힌다 — "COMPOSE_PROFILES 를 주지 않고 이름도 지정하지 않으면 분석 스택은 하나도 뜨지 않는다. 즉 기존 배포(receive.sh 의 api/nginx 재기동)는 영향을 받지 않는다." 같은 이유로 `api` 에는 분석 스택 `depends_on` 을 걸지 않는다 — "분석 DB 가 죽어도 API 배포와 서비스는 계속되어야 한다".

이 profile 배치의 대가로 `CH_SHARD`, `CH_REPLICA`, `CH_HOST_1` 등에 `${VAR:?...}` 필수 표시를 쓸 수 없다. compose 가 profile 로 걸러내기 전에 파일 전체를 치환하기 때문이다. 값을 빠뜨려도 조용히 통과하므로 올린 뒤 반드시 확인한다.

```bash
docker exec mupid-clickhouse clickhouse-client -q "SELECT * FROM system.macros"
```

`extra_hosts` 도 좌우가 다르다 — `kafka1:${MUPID_KAFKA_HOST:-127.0.0.1}` 에는 기본값이 있고 `kafka2:${MUPID_KAFKA_HOST}` 에는 없다. `MUPID_KAFKA_HOST` 는 `.env` 에 반드시 채운다.

### 노드 간 통신 포트

Docker bridge 네트워크는 호스트를 넘지 못하므로 ClickHouse 9000(native)/9009(interserver), Keeper 9181, Kafka 9092 를 호스트에 게시한다. 파일 주석이 "상대 서버 IP 에만 열 것. 인터넷 노출 금지" 라고 못박고, `infra/OPERATIONS.md` 는 "Docker의 공개 포트는 UFW 규칙만으로 차단된다고 가정하지 않는다" 고 덧붙인다. 보안그룹으로 막는다. ClickHouse 8123(HTTP)은 게시하지 않는다.

## 이미지 빌드 두 갈래

### api/Dockerfile — 소스에서 굽는 멀티스테이지

`# syntax=docker/dockerfile:1` 선언 후 2 스테이지다.

- **build** — `FROM azul-zulu:21-jdk AS build`, `WORKDIR /workspace`.
  캐싱 순서가 핵심이다. `COPY gradlew` → `COPY gradle gradle` → `COPY settings.gradle build.gradle ./` → `chmod +x gradlew && ./gradlew --no-daemon --console=plain dependencies` 를 먼저 돌리고, **그 다음에** `COPY src src` → `./gradlew … bootJar -x test` → `cp build/libs/*-SNAPSHOT.jar /workspace/app.jar`.
- **runtime** — `FROM azul-zulu:21-jre AS runtime`, `WORKDIR /app`.
  `ENV TZ=Asia/Seoul`, `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul"`.
  `apt-get install --no-install-recommends curl` → `addgroup --system spring` → `adduser --system --ingroup spring --no-create-home spring` 을 한 `RUN` 으로 묶고 apt 목록을 지운다.
  `COPY --from=build --chown=spring:spring /workspace/app.jar app.jar`, `USER spring`, `EXPOSE 8800`.
- **HEALTHCHECK** — `--interval=15s --timeout=3s --start-period=40s --retries=5`,
  `curl -fsS "http://localhost:${MUPID_PORT:-8800}/api/actuator/health" | grep -q '"status":"UP"'`.
- **ENTRYPOINT** — `["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]`. `$JAVA_OPTS` 분해를 위해 셸을 거치되 `exec` 로 PID 1 을 넘긴다.

캐싱 순서는 주석이 직접 이유를 적어 뒀다 — "래퍼와 의존성 선언만 먼저 복사해서 의존성 레이어를 캐싱한다". `api/AGENTS.md` 는 "이 순서를 바꾸면 매 빌드마다 의존성을 다시 받는다" 로 규칙화했다. Boot 4 가 아직 SNAPSHOT 이라 `repo.spring.io/snapshot` 에서 받는 구성(`api/build.gradle`: 플러그인 `4.1.2-SNAPSHOT`, `version = '0.0.1-SNAPSHOT'`)이므로 재다운로드 비용이 크다.

`api/.dockerignore` 는 15줄이다 — `.git .gradle .mybatis build bin out .idea .vscode .env .env.example *.log Dockerfile compose.yml compose.prod.yml nginx`. `compose.ci.yml`, `clickhouse/`, `docs/`, `README.md`, `AGENTS.md` 는 제외되지 않아 빌드 컨텍스트로 전송된다(`COPY` 대상이 아니라 이미지에는 들어가지 않는다).

### infra/image/Dockerfile — jar 만 감싸는 단일 스테이지

| | `api/Dockerfile` | `infra/image/Dockerfile` |
|---|---|---|
| 스테이지 | 2 (jdk build → jre runtime) | 1 (`FROM azul-zulu:21-jre`) |
| `# syntax` 지시자 | 있음 | 없음 |
| jar 확보 | 컨테이너 안에서 `./gradlew bootJar -x test` | 컨텍스트에 미리 놓인 `app.jar` 를 `COPY` |
| 컨텍스트 | `api/` 전체 | `/tmp/mupid-image/context` — `app.jar` + `Dockerfile` 둘뿐 |
| 헬스체크 호스트 | `http://localhost:${MUPID_PORT:-8800}` | `http://127.0.0.1:${MUPID_PORT:-8800}` |
| 나머지 | TZ/JAVA_OPTS/curl 설치/spring 유저/EXPOSE 8800/ENTRYPOINT 동일 | 동일 |

분리 이유는 두 가지다. 첫째, CI job 에 Docker 소켓이 없다 — `infra/README.md`: "rootless BuildKit 사용. 전용 Runner job에는 Docker socket/privileged를 제공하지 않지만 seccomp/AppArmor 프로파일을 unconfined로 설정했다." 둘째, 검증한 산출물과 배포 산출물을 같게 만든다 — `infra/ci/release.yml` 첫 줄이 "Build the exact JAR that passed verification; no Docker daemon/socket in jobs." 다. `backend-verify` 가 만든 `api/build/release/app.jar` 를 아티팩트로 넘기고, package job 은 그 jar 만 감싼다.

**`api/Dockerfile` 만 고치면 배포 이미지는 바뀌지 않는다.** 런타임 설정(JAVA_OPTS, 헬스체크, 유저)을 바꿀 때는 두 파일을 함께 고친다.

### 실행 유저와 헬스체크

두 Dockerfile 모두 `addgroup --system spring` / `adduser --system --ingroup spring --no-create-home spring` 로 비특권 유저를 만들고 `COPY --chown=spring:spring` 후 `USER spring` 으로 내려간다. 베이스가 Alpine 이 아니라 Debian 이라 BusyBox 축약형(`-S`)이 아닌 GNU 롱옵션을 쓴다. 같은 경고가 Dockerfile 주석·`api/AGENTS.md`·`api/README.md` 세 곳에 중복으로 남아 있다.

헬스체크는 HTTP 200 만 보지 않고 `grep -q '"status":"UP"'` 까지 확인한다. Actuator 가 `show-details: never` 라 본문이 `{"status":"UP"}` 로 고정되기 때문에 성립한다. 베이스 이미지에 `curl` 이 없어 런타임 스테이지에서 apt 로 설치하는 비용을 감수했다.

이 헬스체크가 배포 안전장치의 축이다. 두 운영 compose 의 `nginx → api: service_healthy` 와 `receive.sh` 의 `up -d --wait --wait-timeout 180 api` 가 모두 여기에 걸린다. 그래서 애플리케이션 쪽도 헬스체크 의미 범위를 좁게 유지한다 — ClickHouse 를 `DataSource` 빈으로 노출하지 않는 이유가 "분석 DB 가 죽으면 API 컨테이너가 `unhealthy` 가 되고 nginx 의 `depends_on: service_healthy` 에 걸려 서비스 전체가 내려간다" 는 것이다 (`api/clickhouse/README.md`).

## nginx 설정 두 벌

### 공용 템플릿 — api/nginx/templates/default.conf.template

`api/compose.yml` 과 `api/compose.prod.yml` 이 한 파일을 공유하고 환경 차이는 `API_UPSTREAM` 하나로만 준다.

| | `API_UPSTREAM` |
|---|---|
| `api/compose.yml` (개발) | `host.docker.internal:${MUPID_PORT:-8800}` |
| `api/compose.prod.yml` (운영 스모크) | `api:8800` |

내용은 `upstream mupid_api { server ${API_UPSTREAM}; keepalive 32; }`, `listen 80`, `client_max_body_size 20m`, `location = /healthz`(백엔드와 무관하게 `return 200 "ok\n"`), `location /api/` 프록시(`proxy_http_version 1.1`, `Connection ""`, `Host`/`X-Real-IP`/`X-Forwarded-For`/`X-Forwarded-Proto`/`X-Forwarded-Host` 전달, `proxy_connect_timeout 5s`, `send/read 60s`, SSE 대비 `proxy_buffering off`) 다. 정적 파일 서빙 규칙은 없어 `/` 는 어떤 location 에도 걸리지 않는다.

치환 범위는 `NGINX_ENVSUBST_FILTER: "^API_UPSTREAM$$"` 로 그 이름 하나에 못박혀 있다(`$$` 는 compose 보간을 피한 리터럴 `$`). 필터를 넓히거나 nginx 변수를 `${host}` 처럼 중괄호로 쓰면 envsubst 가 그것도 치환해 빈 문자열로 지운다. 템플릿 안 nginx 변수는 반드시 `$host`, `$remote_addr`, `$proxy_add_x_forwarded_for` 처럼 중괄호 없이 쓴다.

템플릿 맨 위 주석은 존재하지 않는 `compose.dev.yml` 을 가리키고 개발/운영 매핑도 반대로 적혀 있다. 주석 말고 각 compose 파일의 `API_UPSTREAM` 을 직접 확인한다 → [함정과 실패 사례](../failure/README.md).

### 운영 전용 — infra/deploy/nginx.conf

실제 EC2 는 템플릿을 쓰지 않는다. `/srv/mupid/config/nginx.conf` 를 `conf.d/default.conf` 로 통째로 마운트한다. server 블록이 2개다.

- **80** — `location = /healthz`, ACME webroot `location /.well-known/acme-challenge/ { root /var/www/acme; }`, 나머지 `return 308 https://j15a204.p.ssafy.io$request_uri`.
- **443** — letsencrypt 인증서, `ssl_protocols TLSv1.2 TLSv1.3`, `server_tokens off`, `client_max_body_size 20m`, `/healthz`, `/api/` 프록시(템플릿과 달리 `X-Forwarded-Host` 와 `proxy_send_timeout` 이 없다), 음원 경로 3종(아래), `root /srv/web/current` 와 `location / { try_files $uri $uri.html $uri/index.html =404; }`.

#### 음원 경로 — 바이트는 스프링을 거치지 않는다

개발 템플릿과 운영 설정 **양쪽에** 같은 블록이 있어야 한다. 없으면 업로드·다운로드가 통째로 404 다 ([decision/0016](../decision/0016-audio-upload-three-step.md)).

| location | 성격 | 하는 일 |
|---|---|---|
| `~ ^/upload/chunk/(\d+)/(\d+)$` | 공개 | `auth_request` 로 스프링에 인증만 묻고(본문 없음), 받은 `X-Storage-Path` 로 본문을 스토리지에 흘린다 |
| `= /internal/chunk-upload-auth` | `internal` | 위 서브요청 전용. `proxy_pass_request_body off`, `X-Original-Content-Length` 전달 |
| `/storage/` | `internal` | 다운로드 `X-Accel-Redirect` 대상 |

`upstream mupid_storage { server storage:8888; }` 가 필요하고, 그러려면 **`storage` 컨테이너가 `frontend` 네트워크에도 붙어 있어야 한다.** nginx 는 `frontend` 에만 있고 storage 는 원래 `backend` 에만 있었다. nginx 를 `backend` 에 붙이는 대신 storage 를 `frontend` 에 올리는 쪽을 택했다 — nginx 는 여전히 mysql·redis 에 닿지 못한다.

nginx 는 업스트림 호스트명을 **기동 시점에 한 번만** 해석한다. `storage` 컨테이너를 재생성하면 IP 가 바뀌므로 nginx 도 함께 재생성해야 한다. `receive.sh` 가 api 교체 뒤 nginx 를 `--force-recreate` 하는 것과 같은 이유다.

TLS 종료·ACME 예외·308 리다이렉트·정적 프런트가 들어가면서 "환경변수 하나 차이" 라는 전제가 깨졌다. 동시에 `infra/README.md` 의 원칙("Compose와 nginx 설정은 root가 관리한다. CI는 API 이미지 버전을 배포하며 서버 설정 파일 자체를 자동 교체하지 않는다")과 맞물린다. 설정 교체를 배포 파이프라인에서 떼어내 사람이 승인하도록 남긴 선택이다. 대신 `receive.sh` 가 배포마다 `/srv/mupid/config` 를 `releases/<SHA>/config-snapshot/` 으로 복사해, 그 버전이 어떤 설정으로 떴는지는 남긴다.

## GitLab CI 파이프라인

`.gitlab-ci.yml` 은 stage 4개와 include 3개, 그리고 `gemini-code-review` 만 직접 정의한다.

```
stages: verify → ai-review → package → deploy

include local infra/ci/frontend.yml   frontend-verify / frontend-package / frontend-deploy
include local infra/ci/backend.yml    backend-verify
include local infra/ci/release.yml    backend-package / backend-deploy
gemini-code-review (stage: ai-review) → scripts/gemini_review.py
```

| job | image | tags | needs | timeout | 비고 |
|---|---|---|---|---|---|
| `backend-verify` | `azul-zulu:21-jdk` | `mupid-ci` | — | 15m | `interruptible: true`. `GRADLE_USER_HOME=$CI_PROJECT_DIR/.gradle-ci`, 캐시 키는 `api/gradle/wrapper/gradle-wrapper.properties` + `api/build.gradle` 내용 |
| `frontend-verify` | `node:22-bookworm-slim` | `mupid-ci` | — | 15m | `MUPID_DEPLOY_TARGET=ec2`, `NEXT_TELEMETRY_DISABLED=1`. 캐시 prefix `frontend-npm` |
| `gemini-code-review` | `python:3.12-slim` | **없음** | — | 5m | `allow_failure: true`, `GIT_DEPTH: "0"`, `GEMINI_MODEL: gemini-3.7-flash` |
| `backend-package` | `moby/buildkit@sha256:80b15f…63ef` | `mupid-ci` | `backend-verify` (artifacts) | 20m | `docker.user: '0'`, `BUILDKITD_FLAGS: --oci-worker-no-process-sandbox` |
| `frontend-package` | `alpine:3.22` | `mupid-ci` | `frontend-verify` | 지정 없음 | |
| `backend-deploy` | `alpine:3.22` | `mupid-ci` | `backend-package` | 15m | `resource_group: mupid-demo`, `environment: demo`, `interruptible: false` |
| `frontend-deploy` | `alpine:3.22` | `mupid-ci` | `frontend-package` | 10m | 위와 동일 |

verify job 스크립트는 다음을 한다.

```bash
# backend-verify
cd api
bash gradlew --no-daemon --console=plain build -x test
mkdir -p build/release
find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} build/release/app.jar \;
test -s build/release/app.jar          # artifacts: api/build/release/app.jar (7일)

# frontend-verify
cd frontend
npm ci --cache .npm --prefer-offline --no-audit --no-fund
npm run build
test -f dist/client/index.html         # artifacts: frontend/dist/client/ (7일)
```

`infra/ci/backend.yml` 첫 줄은 "Build-only CI by team decision; application tests are not executed." 다. `-x test` 이므로 파이프라인 초록불을 테스트 통과로 읽으면 안 된다.

### rules

| job | MR | develop | master | web(수동) |
|---|---|---|---|---|
| `backend-verify` | changes 시 | changes 시 | changes 시 | 무조건 |
| `frontend-verify` | changes 시 | changes 시 | changes 시 | 무조건 |
| `gemini-code-review` | 소스 브랜치가 `^study/이규원/` 일 때만 | – | – | – |
| `backend-package` | changes 시 `when: manual` + `allow_failure` | changes 시 | changes 시 | 무조건 |
| `frontend-package` | changes 시 `when: manual` + `allow_failure` | changes 시 | 안 함 | 안 함 |
| `backend-deploy` | – | 아래 3조건 + changes | – | – |
| `frontend-deploy` | – | 아래 3조건 + changes | – | – |

deploy 는 세 조건을 모두 건다.

```
$CI_COMMIT_BRANCH == "develop" && $CI_COMMIT_REF_PROTECTED == "true" && $MUPID_DEPLOY_ENABLED == "true"
```

보호 브랜치 조건은 비보호 참조에서 protected 변수가 노출되지 않게 하는 장치이고, `MUPID_DEPLOY_ENABLED` 는 코드 수정 없이 자동배포를 끄는 스위치다 (`infra/OPERATIONS.md`: "자동배포를 멈출 때 GitLab MUPID_DEPLOY_ENABLED를 false로 변경한다").

`changes` 감시 목록은 두 종류다.

- 백엔드 verify/package: `api/**/*`, `infra/image/**/*`, `infra/ci/backend.yml`, `infra/ci/release.yml`, `infra/ci/package-image.sh`, `infra/ci/deploy.sh`, `infra/deploy/compose.yml`, `infra/deploy/receive.sh`, `.gitlab-ci.yml`
- `backend-deploy` 만 더 좁다: `api/**/*`, `infra/image/**/*`, `infra/ci/deploy.sh`, `infra/deploy/compose.yml`, `infra/deploy/receive.sh`
- 프런트 3개 job 공통: `frontend/**/*`, `infra/ci/frontend.yml`, `infra/ci/frontend-release.sh`, `infra/deploy/receive-front.py`, `.gitlab-ci.yml`

MR 에서 package 를 `manual` + `allow_failure` 로 둔 것은 `infra/README.md` 의 "이미지 패키징은 수동 선택이므로 MR 검증 시간을 늘리지 않는다" 에 대응한다.

### 필요한 GitLab 변수

| Key | Type | Protected | Environment |
|---|---|---|---|
| `MUPID_DEPLOY_SSH_KEY` | File | Yes | demo |
| `MUPID_DEPLOY_KNOWN_HOSTS` | File | Yes | demo |
| `MUPID_DEPLOY_ENABLED` | Variable | Yes | All (*) |

개인키는 여러 줄이라 GitLab masking 조건에 맞지 않는다. Visible + Protected 로 저장하고 Expand variable reference 는 끈다. `MUPID_DEPLOY_ENABLED` 는 rules 에서 평가되므로 환경 범위를 demo 가 아니라 All 로 둬야 한다 → [함정과 실패 사례](../failure/README.md).

### 이미지 패키징 (infra/ci/package-image.sh)

레지스트리를 쓰지 않는다. `infra/README.md`: "Container Registry 대신 GitLab Generic Packages에 커밋 SHA별 Docker 이미지 gzip과 SHA256 저장."

```sh
test -s api/build/release/app.jar
cp api/build/release/app.jar   /tmp/mupid-image/context/app.jar
cp infra/image/Dockerfile      /tmp/mupid-image/context/Dockerfile
chown -R 1000:1000 /tmp/mupid-image
su user -c 'buildctl-daemonless.sh build --frontend dockerfile.v0 \
  --local context=/tmp/mupid-image/context --local dockerfile=/tmp/mupid-image/context \
  --output type=docker,name="$MUPID_IMAGE_NAME",dest=/tmp/mupid-image/image.tar'
gzip /tmp/mupid-image/image.tar
sha256sum image.tar.gz > image.tar.gz.sha256
# → packages/generic/mupid-api/$CI_COMMIT_SHA/{image.tar.gz,image.tar.gz.sha256}
```

`docker save` 대신 buildctl 의 `type=docker` 출력을 쓰고, 태그는 `mupid-api:$CI_COMMIT_SHA` 다. 이 태그 이름이 서버 검증 대상이 된다.

## 배포 경로

### 백엔드

1. `infra/ci/deploy.sh` 가 `MUPID_DEPLOY_SSH_KEY`, `MUPID_DEPLOY_KNOWN_HOSTS` 존재를 `: "${VAR:?}"` 로 강제한다.
2. `JOB-TOKEN` 헤더로 `image.tar.gz`, `image.tar.gz.sha256` 을 내려받고 `sha256sum -c` 로 검증한다.
3. 키를 `tr -d '\r'` 로 정규화하고 개행을 붙인 뒤 `chmod 600`, `ssh-keygen -y -P ''` 로 사전 파싱한다. `trap 'rm -f "$key"' EXIT`.
4. 이미지를 **stdin 으로만** 흘려보낸다.
   ```sh
   ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$MUPID_DEPLOY_KNOWN_HOSTS" \
       -i "$key" mupid-deploy@j15a204.p.ssafy.io "deploy $CI_COMMIT_SHA $checksum" < image.tar.gz
   ```
5. `infra/deploy/ssh-entrypoint.sh` 가 `SSH_ORIGINAL_COMMAND` 를 `^deploy ([0-9a-f]{40}) ([0-9a-f]{64})$` 로만 매칭해 `sudo /usr/local/sbin/mupid-receive` 로 exec 한다. 그 외는 exit 64.
6. `infra/deploy/receive.sh`(root) — `[[ $EUID == 0 && $# == 2 && … ]] || exit 64` 로 호출 경로를 다시 검사 → `flock -w 300`(실패 시 exit 75) → `head -c 1073741825` 로 1 GiB 상한 → SHA256 대조 → `gzip -t` → 인라인 python 으로 `manifest.json` 의 `RepoTags` 가 정확히 `["mupid-api:<SHA>"]` 하나인지 확인 → `docker load` → `docker image inspect` → `releases/<SHA>/image.tar.gz` + `image.sha256` 저장 → `config-snapshot/` 복사.
7. `previous=$(cat current-image)` 가 `mupid-api:(40hex|bootstrap)` 형식이어야 진행한다. 같은 이미지면 `ALREADY_DEPLOYED` 출력 후 exit 0.
8. `apply_image()` 가 교체한다.
   ```bash
   MUPID_API_IMAGE="$candidate" docker compose --env-file /srv/mupid/.env -f /srv/mupid/config/compose.yml \
     up -d --no-build --wait --wait-timeout 180 api
   # API 교체 후 nginx 를 강제 재생성해 새 컨테이너 주소를 다시 해석시킨다
   MUPID_API_IMAGE="$candidate" docker compose --env-file /srv/mupid/.env -f /srv/mupid/config/compose.yml \
     up -d --no-deps --force-recreate --wait --wait-timeout 60 nginx
   curl --fail --silent --show-error --max-time 15 --resolve j15a204.p.ssafy.io:443:127.0.0.1 \
     https://j15a204.p.ssafy.io/api/actuator/health | grep -q '"status":"UP"'
   ```
9. 성공하면 `previous-image` 기록 → `current-image.new` → `mv` 로 원자 교체 → `succeeded-at` → `DEPLOYED` 출력. 실패하면 `apply_image "$previous"` 로 복구하고 `ROLLED_BACK` 또는 `ROLLBACK_FAILED` 를 출력한 뒤 exit 1. 모든 로그는 `releases/<SHA>/deploy.log`.
10. CI 로 돌아와 외부에서 `https://j15a204.p.ssafy.io/api/actuator/health` 를 다시 확인한다.

nginx 강제 재생성은 버그 회피가 아니라 설계다. 주석이 이유를 적어 뒀다 — "Resolve API's new container address after each replacement." `upstream mupid_api { server api:8800; }` 는 기동 시점에 이름을 한 번만 해석하므로, 재생성하지 않으면 사라진 컨테이너 IP 를 계속 붙든다.

배포 판정 기준은 컨테이너 기동이 아니라 **nginx 를 통과한 HTTPS 응답이 UP 인지**다. `--resolve` 로 자기 자신을 가리켜 DNS·외부망을 배제한다.

### 프런트

`deploy-front <SHA> <checksum>` → `infra/deploy/receive-front.py`(root). 같은 `/srv/mupid/deploy.lock` 을 `fcntl.flock(LOCK_EX)` 로 잡고, 256 MiB 상한으로 stdin 을 받아 SHA256 대조 → tar 안전 검사(멤버 20000개·펼친 크기 1 GiB 상한, 절대경로/`..` 금지, 일반 파일과 디렉터리만 허용 = 심볼릭 링크 거부, 파일 권한 0644, `extractall` 미사용) → `index.html` 필수 → `deployment.txt`(릴리스 SHA)와 `.archive-sha256` 기록 → `web/<SHA>` 로 rename → `current.new` 심볼릭 링크를 `Path.replace()` 로 원자 교체 → `--resolve` 로 `deployment.txt` 와 홈 응답 확인 → 실패 시 이전 링크로 복구하고 예외 재발생 → 성공 시 `web/previous` 기록, `FRONTEND_DEPLOYED <SHA>` 출력.

프런트와 API 는 독립 배포다. `changes` 규칙 때문에 한쪽만 바뀐 커밋에서는 다른 쪽 deploy job 이 아예 생성되지 않으므로, 두 버전이 달라도 정상이다.

### 신뢰 경계

같은 SHA 에 다른 내용물이 오면 거부한다 — `receive.sh` 는 `image.sha256` 과 비교해 exit 65, `receive-front.py` 는 `.archive-sha256` 을 비교해 `Immutable release checksum mismatch`, `backup-receive.py` 는 `os.link` 의 `FileExistsError` 를 잡아 체크섬이 같을 때만 통과한다. 일시적 전송 오류는 실패한 deploy job 만 재시도하고 package 를 다시 굽지 않는다.

직렬화는 두 겹이다 — CI 의 `resource_group: mupid-demo`(backend-deploy·frontend-deploy 공유)와 서버의 `/srv/mupid/deploy.lock`. 러너 동시성도 `infra/runner/configure.py` 가 `concurrent = 1`, `limit = 1` 로 못박는다.

## 롤백

### 자동

위 9단계. `apply_image` 를 `$previous` 로 다시 호출한다. 롤백 경로도 같은 함수를 타므로 API 중단이 두 배가 된다.

### 실증 (점검 시간에만)

```bash
# 서버에 복사한 경로에서 root 로 실행한다 (설치 경로는 문서에 정해져 있지 않다)
sudo bash test-rollback.sh --allow-temporary-outage
```

`infra/deploy/test-rollback.sh` 는 `HEALTHCHECK … CMD exit 1` + `ENTRYPOINT ["sleep","infinity"]` 인 가짜 이미지를 만들어 진짜 `mupid-receive` 에 밀어 넣는다. 종료코드 1 · `current-image` 불변 · `docker inspect mupid-api` 의 `Config.Image` 불변 · HTTPS UP 을 모두 확인해야 `ROLLBACK_TEST_PASSED` 를 출력한다. 스크립트 주석대로 "deliberately replaces the API briefly" 이므로 데모·평가 중에 실행하면 안 된다.

### 수동 (infra/OPERATIONS.md)

```bash
previous=$(sudo cat /srv/mupid/previous-image)
sha=${previous#mupid-api:}
# sha 가 실제 40자리 커밋이고 해당 백업 패키지가 존재하는지 먼저 확인한다.
sudo ls -l /srv/mupid/releases/"$sha"/image.tar.gz
checksum=$(sudo cat /srv/mupid/releases/"$sha"/image.sha256)
sudo sh -c 'exec /usr/local/sbin/mupid-receive "$1" "$2" < "/srv/mupid/releases/$1/image.tar.gz"' sh "$sha" "$checksum"
```

이미지 롤백은 DB 스키마와 데이터를 되돌리지 않는다. migration 은 이전 버전과의 호환성을 전제로 작성한다.

## DB 백업

`mupid-backup.timer` → `OnCalendar=*-*-* 03:00:00 Asia/Seoul`, `Persistent=true`, `RandomizedDelaySec=300`.
`mupid-backup.service` → `Type=oneshot`, `Requires/After=docker.service`, `User=root`, `UMask=0077`, `TimeoutStartSec=30min`, `Nice=10`, `ExecStart=/usr/local/sbin/mupid-backup`, `ExecStartPost=/usr/local/sbin/mupid-backup-sync`.

- `infra/deploy/backup.py` `backup()` — `/srv/mupid/.env` 의 `MUPID_MYSQL_ROOT_PASSWORD` 를 `MYSQL_PWD` 로만 전달하고 `docker exec mupid-mysql mysqldump -uroot --single-transaction --quick --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF mupid` 를 gzip 스트림으로 `.partial` 에 쓴 뒤 fsync·rename. `.sql.json` 에 sha256 을 남긴다.
- `verify()` — `mysql:8.4` 를 `--network none --memory 1g --cpus 1 --tmpfs /var/lib/mysql:rw,size=768m` 로 띄워(랜덤 root 비밀번호) 복원한다. 최대 90회×2초 기동 대기, 테이블 수 집계, 모든 BASE TABLE 에 `CHECK TABLE` → 마지막 줄이 `status OK` 여야 한다. 이어서 격리 컨테이너 안에서만 `backup_fixture.probe`(한글 utf8mb4 `0xED959CEAB880`, `VARBINARY 0x0001FF`, NULL)로 mysqldump→gzip→복원 왕복을 검사하고 `.sql.verified.json` 을 남긴다.
- `backup-sync.py` — `mysql-*.sql.verified.json` 중 최신을 골라 로컬 체크섬을 재확인하고 `.sql.gz` / `.sql.json` / `.sql.verified.json` 3개를 `mupid-backup@j15a204a.p.ssafy.io` 에 `put <파일명> <sha256>` 로 하나씩 stdin 전송한다. 응답이 정확히 `STORED <파일명> <sha256>` 이 아니면 실패다. 키·`known_hosts` 는 `/srv/mupid/backup-ssh/`.
- `backup-receive.py`(추가 EC2 forced command) — `put (mysql-\d{8}T\d{6}Z-[0-9a-f]{6}\.sql\.(?:gz|json|verified\.json)) ([0-9a-f]{64})` 만 허용. 1 GiB 상한, 스트리밍 sha256 검증, `os.link` 로 `/srv/mupid-backup-vault/` 에 넣는다. 같은 이름이 있으면 체크섬이 같을 때만 통과한다. 다운로드·삭제·셸은 제공하지 않는다.

"만들었다"가 아니라 "복원된다"까지가 한 단위다. `backup-sync.py` 가 `.verified.json` 만 훑으므로 검증되지 않은 백업은 외부로 나가지 않는다.

```bash
sudo systemctl start mupid-backup.service                                  # 즉시 백업 + 복원 검증
sudo journalctl -u mupid-backup.service -n 30 --no-pager
sudo /usr/local/sbin/mupid-backup verify /srv/mupid/backups/<파일>.sql.gz  # 기존 백업 재검증
sudo /usr/local/sbin/mupid-backup-sync                                     # 전송만 재시도
```

대상은 MySQL 뿐이다. Redis AOF, 서버 설정/비밀값, 인증서, ClickHouse/Kafka 는 백업 범위 밖이다. 보관 기간별 자동 삭제도 없다.

## 서버 부트스트랩과 Runner

### 1. Docker 설치 — infra/bootstrap-docker.sh

`/etc/os-release` 로 Ubuntu 24.04 를 강제한다. docker 가 이미 있으면 버전만 출력하고 종료하는 멱등 스크립트다. keyring(`/etc/apt/keyrings/docker.asc`)과 deb822 `docker.sources`(Suites: noble)를 만들고 `docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin` 설치, `systemctl enable --now docker`, `hello-world` 확인까지 한다. UFW 규칙과 docker 그룹은 건드리지 않는다.

### 2. 최초 스택 — infra/bootstrap-backend.sh

`api/` 에서 실행한다(`compose.prod.yml` 존재 확인). `.env` 가 없으면 `openssl rand` 로 `MUPID_MYSQL_PASSWORD`(hex 32)와 `MUPID_JWT_SECRET`(base64 48)을 만들고 `MUPID_PORT=8800`, `MUPID_NGINX_PORT=8080`, `MUPID_MYSQL_USER=root` 를 기록한다. `compose.bootstrap.yml` 을 생성해 이미지를 `mupid-api:bootstrap` 으로, nginx 포트를 `!override` 로 `127.0.0.1:8080:80` 에 묶은 뒤 `config --quiet` → `up -d --no-build --wait --wait-timeout 240` → `http://127.0.0.1:8080/api/actuator/health` 를 확인한다.

### 3. 운영 전환 — infra/deploy/prepare-env.py

1회성이다. `/srv/mupid/.env` 가 이미 있으면 즉시 종료한다. `/home/ubuntu/mupid-bootstrap/api/.env` 의 `MUPID_MYSQL_PASSWORD` 를 root 비밀번호로 삼고, `secrets.token_hex(32)` 로 새 앱 비밀번호를 만들어 `mupid_app@'%'` 를 생성하고 `GRANT ALL PRIVILEGES ON mupid.*` 만 부여한다. 결과를 `MUPID_HTTP_PORT=80`, `MUPID_HTTPS_PORT=443` 과 함께 `O_EXCL | 0o600` 으로 기록한다. 비밀값은 출력하지 않는다.

이후 `infra/deploy/compose.yml` 의 `api` 에는 `MUPID_MYSQL_ROOT_PASSWORD` 를 주지 않는다 (`infra/README.md`: "애플리케이션 DB 계정은 mupid DB에만 권한을 부여한다. root 비밀번호는 API에 전달하지 않는다").

### 4. Runner — infra/runner/

`compose.yml` 은 프로젝트명 `mupid-runner`, `gitlab/gitlab-runner` 를 다이제스트로 고정, `cpus: 0.5`, `mem_limit: 512m`, `/srv/mupid-runner/config` 와 `/var/run/docker.sock` 마운트(주석: "Only the trusted runner manager receives the socket, never CI jobs"), json-file 10m×3.

`configure.py` 는 `config.toml` 을 tomllib 로 읽어 러너가 1개이고 executor 가 `docker` 인지 assert 한 뒤 `concurrent = 1`, `check_interval = 3` 으로 바꾸고, 없으면 `limit = 1`, `request_concurrency = 2`, `[runners.docker]` 에 `security_opt = ["seccomp=unconfined", "apparmor=unconfined"]` 를 넣는다(rootless BuildKit 이 user namespace/mount 시스템콜을 필요로 해서). 다시 파싱해 `privileged` 아님, volumes 에 `docker.sock` 없음, `memory == '4g'`, `cpus == '2'`, `service_memory == '1g'`, `service_cpus == '1'` 을 assert 하고 저장 후 `chmod 600` 한다. 자원 한도는 **설정하지 않고 검증만** 하므로 등록과 `[runners.docker]` 기입이 선행 수동 작업이다.

seccomp/AppArmor 를 완화했으므로 `infra/README.md` 의 규칙이 따라붙는다 — "프로젝트 외부 작업을 이 Runner에서 실행하지 않는다."

## 로컬 검증 명령

### 개발 스택 (Spring 은 호스트)

```bash
cd api
cp .env.example .env          # MUPID_* 채우기. MUPID_CLICKHOUSE_PASSWORD 는 필수
docker compose up -d          # nginx, mysql, redis, storage, ClickHouse 2노드, Keeper, Kafka, init 3종
./gradlew bootRun

curl http://localhost:8080/api/actuator/health   # nginx 경유
curl http://localhost:8800/api/actuator/health   # 스프링 직접
curl http://localhost:8080/healthz               # nginx 자체 (백엔드 무관하게 ok)

docker compose up -d kafka-ui                    # profile ui. http://localhost:18080
docker compose down -v --remove-orphans          # 초기화 (로컬에서만)
```

nginx 에 `depends_on` 이 없어 `up -d` 직후 `/api/...` 는 스프링을 아직 안 띄웠다면 502 다. nginx 컨테이너 자체는 `/healthz` 로 healthy 를 유지하므로 컨테이너 상태만 보고 판단하면 안 된다.

### 운영 스모크 (전부 컨테이너)

`api/AGENTS.md` 가 요구하는 변경 후 검증 절차다.

```bash
cd api
docker compose -f compose.prod.yml up -d --build
curl http://localhost:8080/api/actuator/health   # {"status":"UP"}
docker ps                                        # (healthy) 표시 확인
docker compose -f compose.prod.yml down -v
```

nginx 는 `api` 가 healthy 가 된 뒤에야 뜬다. nginx 가 안 뜨면 먼저 `docker compose -f compose.prod.yml logs api` 를 본다.

### ClickHouse / Kafka 파이프라인

```bash
cd api && ./clickhouse/verify.sh
```

비밀번호는 `MUPID_CLICKHOUSE_PASSWORD` 환경변수나 `api/.env` 에서 읽는다. 고정 ID 로만 데이터를 넣으므로 여러 번 실행해도 결과가 같다. 상세는 `api/clickhouse/README.md`.

### 서버 정상 상태 점검 (기본 EC2)

```bash
sudo docker ps --format '{{.Names}} {{.Status}}'
curl --fail https://j15a204.p.ssafy.io/api/actuator/health
curl --fail https://j15a204.p.ssafy.io/deployment.txt
sudo cat /srv/mupid/current-image
sudo readlink /srv/mupid/web/current
sudo systemctl list-timers mupid-backup.timer certbot.timer --no-pager
```

## 바꿀 때 지킬 것

**compose**

- 모든 환경 변수는 `MUPID_` 접두사를 쓴다. `SPRING_DATA_REDIS_HOST` 같은 스프링 관례 변수로 우회하지 않는다.
- 게시 포트는 `.env` 변수로 뺀다. compose 에 숫자를 직접 박지 않는다. 현재 유일한 예외는 `api/compose.yml` 의 `storage` 서비스이고, `.env.example` 에 대응 변수도 없다.
- `api/compose.yml` 에 `api` 서비스를 추가하지 않는다.
- 운영 스택에서 MySQL·Redis 는 `expose` 만 쓴다. nginx 는 `frontend` 망에만 붙인다.
- named volume 과 network 에는 `name:` 을 명시해 프로젝트 접두사가 붙지 않게 한다. 운영 compose 가 이 이름을 `external: true` 로 참조한다.
- 컨테이너 이름 접두사는 로컬 `mupid-dev-*`, 운영 `mupid-*` 로 나눈다.
- 1회성 초기화 컨테이너는 `restart: "no"` 로 두고 후속 서비스는 `condition: service_completed_successfully` 로 기다린다.
- healthcheck 는 인증 없이 200 을 주는 경로를 고른다(SeaweedFS 는 `/` 가 아니라 `8333/status`).
- compose 값 안에서 리터럴 `$` 가 필요하면 `$$` 로 이스케이프한다 — `"^API_UPSTREAM$$"`, `-p$$MYSQL_ROOT_PASSWORD`.
- profile 뒤에 숨긴 서비스의 변수에는 `${VAR:?...}` 를 쓰지 않는다.
- ClickHouse 설정은 XML 파일 단위로 마운트한다. 디렉터리째 마운트하지 않는다.
- 운영에서 `down -v` 와 `docker volume prune` 은 금지다.

**이미지**

- Dockerfile 의 레이어 캐싱 순서(`gradlew`/`gradle`/`*.gradle` → `dependencies` → `src`)를 바꾸지 않는다.
- 베이스가 Debian 이므로 Alpine(BusyBox) 문법을 쓰지 않는다.
- 런타임 설정을 바꿀 때는 `api/Dockerfile` 과 `infra/image/Dockerfile` 을 함께 고친다.
- `api/Dockerfile` 은 `cp build/libs/*-SNAPSHOT.jar`, CI 는 `find build/libs … ! -name '*-plain.jar'` 로 규칙이 다르다. 버전 정책을 바꾸면 두 경로를 함께 고친다.
- 외부 이미지는 태그가 아니라 다이제스트로 고정한다(`moby/buildkit`, `gitlab/gitlab-runner`).

**CI / 배포**

- job 정의는 `.gitlab-ci.yml` 에 직접 쓰지 않고 `infra/ci/*.yml` 로 분리해 `include: local` 로 붙인다.
- 여러 줄 셸 로직은 `script` 에 인라인하지 않고 `infra/ci/*.sh` 로 빼서 한 줄로 호출한다.
- 빌드/배포 job 에는 `tags: [mupid-ci]` 를 붙인다.
- verify 는 `interruptible: true`, deploy 는 `interruptible: false` 로 둔다.
- 배포 job 에는 `resource_group: mupid-demo` 와 `environment: {name: demo, url: https://j15a204.p.ssafy.io}` 를 함께 붙인다.
- 모든 job 에 `changes:` 를 붙인다. 목록에는 코드 디렉터리뿐 아니라 그 job 을 정의한 CI 파일과 스크립트 자신도 넣는다.
- 실패를 숨기는 `allow_failure` 를 새로 추가하지 않는다.
- 셸 스크립트는 `set -eu`(sh) / `set -euo pipefail`(bash) 로 시작하고, 필수 변수는 `: "${VAR:?설명}"` 으로 첫머리에서 검사한다.
- 비밀값을 다루면 `umask 077` 을 걸고 임시 키는 `trap … EXIT` 로 지운다. 비밀번호는 명령행 인자가 아니라 `MYSQL_PWD` 로 넘긴다.
- root 수신 스크립트는 호출 경로를 믿지 않고 스스로 `EUID`/`geteuid()` 와 인자 정규식을 다시 검사한다.
- SSH 는 항상 `-o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=<검증된 파일>` 로 호출한다.
- 전송물은 예외 없이 SHA256 을 동반하고 수신 측에서 다시 계산해 대조한다. 같은 이름에 다른 내용이면 거부한다.
- 상태 파일 전환은 원자적으로 한다 — `.new` → `mv`/`Path.replace()`, 백업은 `.partial` → `rename`.
- 서버의 `compose.yml`·`nginx.conf`·`clickhouse/*` 는 root 가 `/srv/mupid/config` 에 두고 CI 가 자동 교체하지 않는다. `infra/deploy/` 를 고쳤다면 서버 파일도 사람이 옮겨야 실제로 반영된다.
- `infra/deploy/compose.yml` 의 `api` 에는 `env_file` 이 없다. `application.yaml` 에 기본값 없는 설정을 추가하면 `environment` 블록에도 같이 넣어야 배포된 컨테이너가 기동한다.

## 함정

이 영역에서 반복해서 밟은 지점은 [함정과 실패 사례](../failure/README.md) 에 모아 두었다. 특히 다음은 문서를 먼저 읽고 손대는 편이 빠르다.

- nginx 템플릿 주석이 존재하지 않는 `compose.dev.yml` 과 반대 매핑을 가리킨다
- envsubst 필터를 넓히면 nginx 변수가 빈 문자열로 치환된다
- 베이스 이미지가 Alpine 이 아니라 Debian 이다
- 배포 SSH 키를 Windows 에서 붙여넣으면 CRLF·개행 누락으로 libcrypto 오류가 난다
- 같은 커밋 SHA 로 다른 아카이브를 다시 올리면 배포가 거부된다
- 운영 compose 의 분석 스택 변수는 비어 있어도 조용히 통과한다
- 서비스 이름을 바꾸면 예전 컨테이너가 orphan 으로 남아 포트를 계속 잡는다
- Git Bash 가 컨테이너 절대경로를 Windows 경로로 바꾼다 (`MSYS_NO_PATHCONV=1`)
- 로컬 `down -v` 는 정상 절차지만 운영에서는 금지다. 컨테이너·볼륨 이름이 `mupid-*` / `mupid-dev-*` 로 비슷해 대상을 착각하기 쉽다
