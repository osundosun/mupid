# 설정과 환경변수 관리

새 환경변수나 커스텀 프로퍼티를 추가할 때, 값이 왜 컨테이너 안에서만 안 잡히는지 찾을 때, 비밀값을 어디에 둘지 정할 때 읽는다. Mupid 의 설정은 `api/src/main/resources/application.yaml` 한 장 + `.env` 두 벌 + compose 세 벌로 이뤄져 있고, 실행 방식마다 값이 도달하는 경로가 다르다.

## 한눈에

| 항목 | 사실 |
|---|---|
| 스프링 설정 파일 | `api/src/main/resources/application.yaml` **하나뿐**. 프로파일별 yaml(`application-dev.yaml` 등) 없음 |
| yaml 안의 `${MUPID_...}` 플레이스홀더 | 20개. 전부 `MUPID_` 접두사 |
| 기본값이 붙은 플레이스홀더 | `${MUPID_PORT:8800}`, `${MUPID_CLIENT_WEBSITE:}` 2개뿐. 나머지 18개는 기본값 없음 |
| 커스텀 프로퍼티 네임스페이스 | `jwt`, `gmail`, `aws.s3`, `clickhouse`, `redis`, `mupid-client` 6개 |
| `@ConfigurationProperties` 바인딩 | `gmail`, `aws.s3`, `clickhouse` 3개 (전부 `record`) |
| 설정 메타데이터 등록 | 3개(`jwt.secret`, `gmail.client-id`, `gmail.client-secret`)뿐 |
| `.env.example` | `api/.env.example`(33키)와 `infra/deploy/.env.example`(29키) 두 벌, 겹치는 키 10개 |

## 파일 배치

```
api/
  src/main/resources/
    application.yaml                                   # 유일한 스프링 설정 파일
    META-INF/additional-spring-configuration-metadata.json
  .env                                                 # 실제 값. api/.gitignore 1행이 무시한다
  .env.example                                         # 33키 / 9개 주석 섹션
  .dockerignore                                        # .env 와 .env.example 을 둘 다 제외
  compose.yml        (name: mupid-dev)                 # 개발: 인프라만. 스프링은 호스트에서 실행
  compose.prod.yml   (name: mupid)                     # 단일 호스트 운영: env_file: .env
  compose.ci.yml                                       # 저장소 어디에서도 참조되지 않는다
  clickhouse/server/*.xml, clickhouse/users/*.xml      # from_env 로 노드 정체성을 받는다
infra/deploy/
  .env.example                                         # 29키 (MUPID_ + CH_ + COMPOSE_PROFILES)
  compose.yml        (name: mupid)                     # 실제 서버 배포 대상
  receive.sh                                           # docker compose --env-file /srv/mupid/.env
  prepare-env.py                                       # /srv/mupid/.env 최초 생성 (1회성)
infra/bootstrap-backend.sh                             # api/.env 를 5개 키로 즉석 생성
```

`application.yaml` 의 최상위 키는 `server`, `spring`, `management`, `jwt`, `gmail`, `aws`, `clickhouse`, `redis`, `mupid-client`. 뒤 6개가 스프링 표준이 아닌 이 프로젝트의 네임스페이스다.

## 값이 앱까지 도달하는 세 경로

같은 `application.yaml` 을 쓰지만 값 주입 방식이 실행 환경마다 다르다. **컨테이너에는 `.env` 가 존재하지 않는다** — `api/.dockerignore` 가 `.env` 와 `.env.example` 을 둘 다 제외한다.

### 1. 호스트 개발 (`./gradlew bootRun`, IDE)

```yaml
# api/src/main/resources/application.yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]
```

- `[.properties]` 는 확장자 없는 파일을 어떤 형식으로 읽을지 지정하는 표기다. 즉 `.env` 는 셸 문법이 아니라 **자바 `.properties` 문법**으로 파싱된다.
- 경로는 프로세스 작업 디렉터리 기준이다. `api/AGENTS.md` 가 "작업 디렉터리는 항상 `api/`" 를 못박은 이유 중 하나다.
- `optional:` 이라 파일이 없어도 기동은 계속된다. 잘못된 디렉터리에서 실행하면 "`.env` 가 없다" 가 아니라 개별 플레이스홀더 해결 실패로 터진다.

### 2. `api/compose.prod.yml` (단일 호스트 운영)

`env_file: .env` 로 `.env` 전체를 컨테이너 OS 환경변수로 넣고, `environment:` 로 컨테이너 네트워크 값 3개만 덮는다.

```yaml
env_file:
  - .env
environment:
  MUPID_MYSQL_URL: jdbc:mysql://mysql:3306/mupid?useSSL=false&...
  MUPID_REDIS_HOST: redis
  MUPID_REDIS_PORT: 6379
```

### 3. `infra/deploy/compose.yml` (서버 배포)

`infra/deploy/receive.sh` 가 `MUPID_API_IMAGE="$candidate" docker compose --env-file "$root/.env" -f "$root/config/compose.yml" up -d ... api` 로 띄운다.

`--env-file` 은 **compose 파일의 `${...}` 치환에만** 쓰인다. 컨테이너에 실제로 들어가는 것은 `api.environment:` 에 열거된 10개뿐이다.

```
MUPID_MYSQL_USER  MUPID_MYSQL_PASSWORD  MUPID_JWT_SECRET  MUPID_PORT
MUPID_MYSQL_URL   MUPID_REDIS_HOST      MUPID_REDIS_PORT
MUPID_CLICKHOUSE_URL  MUPID_CLICKHOUSE_USER  MUPID_CLICKHOUSE_PASSWORD
```

Gmail·S3 키(`MUPID_GMAIL_*` 4개, `MUPID_S3_*` 4개)는 이 블록에도 `infra/deploy/.env.example` 에도 없다. 이 스택에 메일/업로드 기능을 배포하려면 **양쪽 모두**에 키를 추가해야 한다. 상세는 [함정과 실패 사례](../failure/config-and-env.md).

## `MUPID_` 접두사 규약: 범위와 예외

`api/AGENTS.md` 의 규칙은 "모든 환경 변수는 `MUPID_` 접두사를 쓴다". 실제 적용 범위는 **이 프로젝트가 이름을 정하는 변수**다.

**지켜지는 범위**

- `application.yaml` 의 플레이스홀더 20개 전부.
- compose 가 치환에 쓰는 포트·버전·비밀값 변수 전부.
- CI 변수까지 동일 규약 — `MUPID_DEPLOY_SSH_KEY`, `MUPID_DEPLOY_KNOWN_HOSTS`, `MUPID_DEPLOY_ENABLED`, `MUPID_DEPLOY_TARGET`, `MUPID_IMAGE_NAME`.
- `SPRING_DATA_REDIS_HOST` 같은 스프링 관례 변수로 우회하지 않는다. compose 에서 덮어쓸 때도 `MUPID_REDIS_HOST` 를 쓴다 (`api/AGENTS.md`).

**예외 1 — 서드파티 컨테이너가 이름을 정한 변수**

이름은 그대로 두고 값만 `MUPID_` 변수에서 받아 넘긴다. 규약 위반이 아니라 규약의 다른 쪽 면이다.

| 컨테이너 변수 | 값의 출처 | 위치 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | `${MUPID_MYSQL_PASSWORD}` / `${MUPID_MYSQL_ROOT_PASSWORD}` | `api/compose.yml`, `api/compose.prod.yml` / `infra/deploy/compose.yml` |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `S3_BUCKET` | `${MUPID_S3_ACCESS_KEY}`, `${MUPID_S3_SECRET}`, `${MUPID_S3_BUCKET}` | `api/compose.yml` (SeaweedFS) |
| `CLUSTER_ID` | `${MUPID_KAFKA_CLUSTER_ID}` | `api/compose.yml`, `infra/deploy/compose.yml` |
| `BOOTSTRAP`, `TOPIC`, `PARTITIONS`, `REPLICATION` | `BOOTSTRAP` 은 리터럴 `kafka1:9092` / `${MUPID_KAFKA_HOST:-127.0.0.1}:9092`. `TOPIC` 은 양쪽 다 `${MUPID_KAFKA_TOPIC}`, 나머지 둘은 리터럴 | `api/compose.yml` / `infra/deploy/compose.yml` 의 kafka-init |
| `API_UPSTREAM` | `host.docker.internal:${MUPID_PORT}` (개발) / `api:8800` (운영) | nginx 템플릿 치환 |
| `COMPOSE_PROFILES` | docker compose 자체 변수 | `infra/deploy/.env.example` |

**예외 2 — ClickHouse XML 이 `from_env` 로 읽는 이름**

`api/clickhouse/server/*.xml` 과 `api/clickhouse/users/10-mupid-user.xml` 이 이름을 고정하므로 프로젝트가 정할 수 없다.

| 변수 | 읽는 XML |
|---|---|
| `CH_SHARD`, `CH_REPLICA`, `CH_INTERSERVER_HOST` | `api/clickhouse/server/10-macros.xml` |
| `CH_HOST_1`, `CH_HOST_2` | `api/clickhouse/server/00-cluster.xml` |
| `CH_KEEPER_HOST` | `api/clickhouse/server/01-keeper.xml` |
| `CH_MAX_SERVER_MEMORY` | `api/clickhouse/server/02-server.xml` |
| `MUPID_CLICKHOUSE_PASSWORD` | `api/clickhouse/users/10-mupid-user.xml`, `00-cluster.xml` |

마지막 줄이 유일한 예외의 예외다 — `from_env` 로 읽히면서도 `MUPID_` 이름을 유지한다.

XML 파일 자체는 로컬과 운영이 **같은 파일**이고 노드 정체성만 환경변수로 주입한다(`api/clickhouse/README.md` "로컬과 운영의 차이는 환경변수뿐"). 그래서 개발 compose 는 `CH_SHARD: "01"` 처럼 값을 파일에 하드코딩할 수 있고, 서버 2대에 같은 파일을 올리는 운영 compose 는 `CH_SHARD: ${CH_SHARD:-}` 로 `.env` 에서 받는다.

## 환경변수 전체 목록

### `api/.env.example` — 33키

호스트에서 도는 스프링과 `api/compose*.yml` 이 함께 읽는다. 섹션 주석 9개(`# 서버 포트`, `# MySQL 연결`, `# Redis 연결`, `# JWT secret key`, `# Gmail API`, `# S3 연결`, `# 공식 클라이언트`, `# ClickHouse 연결`, `# Kafka 연결`)를 유지한다.

| 변수 | `.env.example` 값 | 읽는 주체 | 매핑 / 용도 |
|---|---|---|---|
| `MUPID_PORT` | `8800` | Spring, compose, Dockerfile | `server.port` (기본값 8800), nginx `API_UPSTREAM`, HEALTHCHECK URL |
| `MUPID_NGINX_PORT` | `8080` | compose | nginx 호스트 포트 (개발·`compose.prod.yml`) |
| `MUPID_MYSQL_PORT` | `13306` | `compose.yml` | 개발 MySQL 호스트 포트 |
| `MUPID_MYSQL_URL` | (빈 값) | Spring | `spring.datasource.url` |
| `MUPID_MYSQL_USER` | (빈 값) | Spring | `spring.datasource.username` |
| `MUPID_MYSQL_PASSWORD` | (빈 값) | Spring, compose | `spring.datasource.password` + mysql 컨테이너 `MYSQL_ROOT_PASSWORD` |
| `MUPID_REDIS_HOST` | (빈 값) | Spring | `spring.data.redis.host` |
| `MUPID_REDIS_PORT` | `16379` | Spring, compose | `spring.data.redis.port` + redis 호스트 포트 |
| `MUPID_REDIS_KEY_PREFIX` | (빈 값) | Spring | `redis.key-prefix` — **읽는 자바 코드가 없다** |
| `MUPID_JWT_SECRET` | (빈 값) | Spring | `jwt.secret` → `JwtProvider`, `AuthProvider` 의 `@Value` |
| `MUPID_GMAIL_CLIENT_ID` | (빈 값) | Spring | `gmail.client-id` |
| `MUPID_GMAIL_CLIENT_SECRET` | (빈 값) | Spring | `gmail.client-secret` |
| `MUPID_GMAIL_TOKEN` | (빈 값) | Spring | `gmail.token` |
| `MUPID_GMAIL_SEND_ADDRESS` | (빈 값) | Spring | `gmail.send-address` |
| `MUPID_S3_ENDPOINT` | (빈 값) | Spring | `aws.s3.endpoint` — 개발은 `http://localhost:8333`, 운영은 빈 값 |
| `MUPID_S3_ACCESS_KEY` | (빈 값) | Spring, compose | `aws.s3.access-key` + SeaweedFS `AWS_ACCESS_KEY_ID` |
| `MUPID_S3_SECRET` | (빈 값) | Spring, compose | `aws.s3.secret-key` + SeaweedFS `AWS_SECRET_ACCESS_KEY` |
| `MUPID_S3_BUCKET` | (빈 값) | Spring, compose | `aws.s3.bucket-name` + `storage-init` 의 버킷 생성 대상 |
| `MUPID_CLIENT_WEBSITE` | (빈 값) | Spring | `mupid-client.website` → `EmailServiceImpl` 의 `@Value`. 기본값 빈 문자열 |
| `MUPID_CLICKHOUSE_VERSION` | `25.3` | compose | ClickHouse / Keeper 이미지 태그 |
| `MUPID_CLICKHOUSE_MAX_MEMORY` | `4294967296` | compose | `CH_MAX_SERVER_MEMORY` 로 전달. 0 이면 자동 계산 |
| `MUPID_CLICKHOUSE_HTTP_PORT` | `18123` | `compose.yml` | ch-01 HTTP. 스프링이 붙는 포트 |
| `MUPID_CLICKHOUSE_NATIVE_PORT` | `19000` | `compose.yml` | ch-01 native |
| `MUPID_CLICKHOUSE_HTTP_PORT_2` | `18124` | `compose.yml` | ch-02 HTTP |
| `MUPID_CLICKHOUSE_NATIVE_PORT_2` | `19001` | `compose.yml` | ch-02 native |
| `MUPID_CLICKHOUSE_URL` | `jdbc:clickhouse://localhost:18123/default` | Spring | `clickhouse.url` |
| `MUPID_CLICKHOUSE_USER` | `mupid` | Spring, compose | `clickhouse.username` + `clickhouse-init` |
| `MUPID_CLICKHOUSE_PASSWORD` | (빈 값) | Spring, compose, XML | `clickhouse.password` + ClickHouse `from_env`. 없으면 개발 compose 가 즉시 실패 |
| `MUPID_KAFKA_VERSION` | `3.9.1` | compose | Kafka 이미지 태그 |
| `MUPID_KAFKA_PORT` | `19092` | `compose.yml` | 호스트 포트 + `KAFKA_ADVERTISED_LISTENERS` 의 EXTERNAL |
| `MUPID_KAFKA_CLUSTER_ID` | `4L6g3nShT-eMCtK--X86sw` | compose | `CLUSTER_ID` (KRaft, base64 22글자) |
| `MUPID_KAFKA_TOPIC` | `mupid.listen.events` | compose | `kafka-init` 의 `TOPIC` |
| `MUPID_KAFKA_BOOTSTRAP` | `localhost:19092` | — | **참조하는 곳이 없다** (저장소 전체 grep 결과 `.env` / `.env.example` 뿐) |

`api/compose.yml` 이 쓰지만 `.env.example` 에 없는 키: `MUPID_KAFKA_UI_VERSION`(기본 `v1.3.0`), `MUPID_KAFKA_UI_PORT`(기본 `18080`).

### `infra/deploy/.env.example` — 29키

`infra/deploy/compose.yml` 의 `${...}` 치환 전용이다. 실제 값은 서버의 `/srv/mupid/.env` 에만 있다.

| 변수 | `.env.example` 값 | 용도 |
|---|---|---|
| `MUPID_HTTP_PORT` / `MUPID_HTTPS_PORT` | (빈 값) | nginx 호스트 포트 (`prepare-env.py` 가 80 / 443 으로 채운다) |
| `MUPID_MYSQL_ROOT_PASSWORD` | (빈 값) | mysql 컨테이너 `MYSQL_ROOT_PASSWORD`. **API 에 전달하지 않는다** |
| `MUPID_MYSQL_USER` / `MUPID_MYSQL_PASSWORD` | (빈 값) | api 컨테이너로 전달. 앱 계정 `mupid_app` (mupid DB 권한만) |
| `MUPID_JWT_SECRET` | (빈 값) | api 컨테이너로 전달 |
| `MUPID_API_IMAGE` | (빈 값) | `${MUPID_API_IMAGE:?image required}`. `receive.sh` 가 배포 시 주입 |
| `COMPOSE_PROFILES` | (빈 값) | 서버 A `analytics,keeper` / 서버 B `analytics,kafka` |
| `CH_SHARD` / `CH_REPLICA` | (빈 값) | 서버 A `01`/`ch1`, 서버 B `02`/`ch2` |
| `CH_INTERSERVER_HOST` | (빈 값) | 이 서버의 주소 (상대 노드가 파트를 가져올 때 접속) |
| `CH_HOST_1` / `CH_HOST_2` / `CH_KEEPER_HOST` | (빈 값) | 두 서버 모두 같은 값. 컨테이너 이름이 아니라 실제 주소 |
| `CH_MAX_SERVER_MEMORY` | `0` | 0 이면 ClickHouse 자동 계산 |
| `MUPID_CLICKHOUSE_MEM_LIMIT` | `4g` | `mem_limit` |
| `MUPID_CLICKHOUSE_VERSION` | `25.3` | 이미지 태그 |
| `MUPID_CLICKHOUSE_USER` / `MUPID_CLICKHOUSE_PASSWORD` | `mupid` / (빈 값) | api·clickhouse·clickhouse-init 공용 |
| `MUPID_CLICKHOUSE_URL` | `jdbc:clickhouse://clickhouse:8123/default` | api 가 붙는 주소 (컨테이너 네트워크 내부) |
| `MUPID_KAFKA_HOST` | (빈 값) | `extra_hosts` 의 `kafka1`/`kafka2` 해석 대상, `KAFKA_ADVERTISED_LISTENERS`, kafka-init `BOOTSTRAP` |
| `MUPID_KAFKA_VERSION` / `MUPID_KAFKA_MEM_LIMIT` | `3.9.1` / `2g` | 이미지 태그, 메모리 상한 |
| `MUPID_KAFKA_TOPIC` / `MUPID_KAFKA_CLUSTER_ID` | `mupid.listen.events` / (빈 값) | 토픽, KRaft 클러스터 ID |
| `MUPID_CLICKHOUSE_NATIVE_BIND` | `9000` | 분산 쿼리. **상대 서버 IP 에만 열 것** |
| `MUPID_CLICKHOUSE_INTERSERVER_BIND` | `9009` | 파트 복제. 동일 |
| `MUPID_KEEPER_BIND` | `9181` | Keeper. 동일 |
| `MUPID_KAFKA_BIND` | `9092` | Kafka. 동일 |

### 두 `.env.example` 의 관계

겹치는 키는 10개다: `MUPID_MYSQL_USER`, `MUPID_MYSQL_PASSWORD`, `MUPID_JWT_SECRET`, `MUPID_CLICKHOUSE_VERSION`, `MUPID_CLICKHOUSE_USER`, `MUPID_CLICKHOUSE_PASSWORD`, `MUPID_CLICKHOUSE_URL`, `MUPID_KAFKA_VERSION`, `MUPID_KAFKA_TOPIC`, `MUPID_KAFKA_CLUSTER_ID`.

| | `api/.env.example` | `infra/deploy/.env.example` |
|---|---|---|
| 키 개수 | 33 | 29 |
| 읽는 주체 | Spring(호스트) + `api/compose*.yml` | `infra/deploy/compose.yml` 치환 전용 |
| 포트 변수 | 개발용 오프셋 포트 (`13306`, `16379`, `18123` …) | `MUPID_HTTP_PORT`, `MUPID_HTTPS_PORT`, `*_BIND` (상대 서버에만 여는 포트) |
| MySQL 비밀번호 | `MUPID_MYSQL_PASSWORD` 하나 (= 컨테이너 root 비밀번호) | `MUPID_MYSQL_ROOT_PASSWORD` 와 `MUPID_MYSQL_PASSWORD` 분리 |
| 앱 전용 키 | `MUPID_GMAIL_*`, `MUPID_S3_*`, `MUPID_CLIENT_WEBSITE`, `MUPID_REDIS_KEY_PREFIX` | 없음 |
| 비-`MUPID_` 키 | 없음 | `COMPOSE_PROFILES`, `CH_*` 7개 |
| API 이미지 | 없음 | `MUPID_API_IMAGE` — 태그가 아니라 `image:` 에 그대로 들어가는 전체 참조 |

같은 이름이라고 같은 의미가 아니다. `MUPID_MYSQL_PASSWORD` 는 특히 위험하다 — 상세는 [함정과 실패 사례](../failure/config-and-env.md).

### CI(GitLab) 변수

`.env` 가 아니라 GitLab 변수로 관리한다.

| 변수 | 타입 | 쓰는 곳 |
|---|---|---|
| `MUPID_DEPLOY_SSH_KEY` | File, Protected, 환경 `demo` | `infra/ci/deploy.sh`, `infra/ci/frontend-release.sh` |
| `MUPID_DEPLOY_KNOWN_HOSTS` | File, Protected, 환경 `demo` | 위와 동일 |
| `MUPID_DEPLOY_ENABLED` | Variable, 환경 All | `infra/ci/release.yml`, `infra/ci/frontend.yml` 의 `rules` |
| `MUPID_DEPLOY_TARGET` | Variable (`ec2`) | 프런트 빌드 (`frontend/next.config.ts`, `frontend/vite.config.ts`) |
| `MUPID_IMAGE_NAME` | 스크립트가 `export` | `infra/ci/package-image.sh` |

## `application.yaml` 에 값을 직접 적어도 되는 경우

`api/AGENTS.md` 의 "값을 직접 적지 않는다" 규칙은 실제로 **환경마다 달라지는 값과 비밀값**에만 적용된다. 어디서 돌든 같은 튜닝 상수는 리터럴로 적는다.

```yaml
aws:
  s3:
    region: "SeaweedFS"          # 리터럴
clickhouse:
  max-pool-size: 8               # 리터럴 (주석으로 근거를 남긴다)
  connection-timeout: 3s
spring:
  data:
    redis:
      timeout: 3000ms
  jackson:
    property-naming-strategy: SNAKE_CASE
```

기본값도 거의 쓰지 않는다. 기본값이 붙은 플레이스홀더는 `${MUPID_PORT:8800}` 과 `${MUPID_CLIENT_WEBSITE:}` 뿐이고, 나머지는 값이 없으면 기동이 실패하도록 두었다. `infra/deploy/compose.yml` 주석이 이 성질을 전제로 한다 — "application.yaml 이 기본값 없이 참조하므로 없으면 기동 자체가 실패한다".

반대로 compose 쪽은 기본값을 후하게 준다(`${MUPID_CLICKHOUSE_VERSION:-25.3}`). 단 **필수 표시(`${VAR:?...}`) 사용 여부가 두 compose 에서 다르다**:

- `api/compose.yml` — `${MUPID_CLICKHOUSE_PASSWORD:?.env 에 MUPID_CLICKHOUSE_PASSWORD 설정 필요}` 처럼 필수로 건다.
- `infra/deploy/compose.yml` — 분석 스택 변수에 `${VAR:-}` 빈 기본값만 쓴다. compose 가 profile 로 걸러내기 전에 파일 전체를 치환하므로, 필수로 걸면 analytics 를 안 쓰는 배포에서도 `up -d api` 가 interpolation 오류로 실패하기 때문이다.

## `.env` 작성 규칙

`.env` 는 셸 파일이 아니라 `.properties` 파일이다. 한 파일을 Spring 파서와 docker compose 파서가 **둘 다** 읽는데 주석 처리가 서로 다르다.

```properties
# 좋음 — 설명은 위 줄에
# ch-01 HTTP. Spring 이 붙는 포트다.
MUPID_CLICKHOUSE_HTTP_PORT=18123

# 나쁨 — properties 문법에서 값이 "abc  # openssl rand -hex 32" 가 된다
MUPID_S3_SECRET=abc  # openssl rand -hex 32
```

- `#` 는 **줄 맨 앞에서만** 주석이다.
- 따옴표는 벗겨지지 않는다(`KEY="v"` → 값이 `"v"`). 백슬래시는 이스케이프 문자로 해석된다.
- `api/.env.example` 은 ClickHouse/Kafka 섹션에서만 이 규칙을 지키고, 그 앞 8줄(`MUPID_REDIS_KEY_PREFIX`, `MUPID_JWT_SECRET`, `MUPID_GMAIL_*` 4개, `MUPID_S3_ENDPOINT`, `MUPID_S3_SECRET`)은 아직 줄 끝 주석을 쓴다. 복사해 값을 채울 때 반드시 지운다.

## `@ConfigurationProperties` 추가 절차

여러 값이 한 빈을 구성하면 `record` + `@ConfigurationProperties`, 단일 값이면 `@Value` 로 끝낸다.

| 프로퍼티 prefix | 바인딩 방식 | 클래스 |
|---|---|---|
| `clickhouse` | `record` + `@ConfigurationProperties(prefix="clickhouse")` | `api/src/main/java/com/osundosun/mupid/api/analytics/properties/ClickHouseProperties.java` |
| `gmail` | `record` + `@ConfigurationProperties(prefix = "gmail")` | `api/src/main/java/com/osundosun/mupid/api/common/gmail/GmailProperties.java` |
| `aws.s3` | `record` + `@ConfigurationProperties(prefix="aws.s3")` | `api/src/main/java/com/osundosun/mupid/api/storage/properties/StorageProperties.java` |
| `jwt.secret` | 생성자 파라미터 `@Value` | `auth/provider/JwtProvider.java:24`, `auth/provider/AuthProvider.java:39` |
| `mupid-client.website` | 필드 `@Value` | `email/service/EmailServiceImpl.java:44` |

절차:

1. **record 로 작성한다.** 위치는 기능 패키지 아래 `properties/`(`analytics/properties`, `storage/properties`) 또는 기능 패키지 안(`common/gmail`).
2. **`@Component` 나 `@EnableConfigurationProperties` 를 붙이지 않는다.** `ApiApplication` 의 `@ConfigurationPropertiesScan` 하나로 베이스 패키지 전체가 등록된다.
   ```java
   @SpringBootApplication
   @ConfigurationPropertiesScan
   @EnableJpaAuditing
   public class ApiApplication { ... }
   ```
3. **소비처는 `@RequiredArgsConstructor` + `private final XProperties props`** 로 주입받는다 (`ClickHouseConfig`, `StorageConfig`, `GmailConfig`, `GmailProvider`, `StorageServiceImpl`).
4. **`application.yaml` 에 prefix 블록을 추가한다.** 환경마다 달라지는 값은 `${MUPID_...}`, 튜닝 상수는 리터럴.
5. **`api/.env.example` 에 키를 추가한다.** 값은 비우고, 알맞은 섹션 주석 아래에 놓고, 설명은 위 줄에 쓴다.
6. **`additional-spring-configuration-metadata.json` 에 항목을 추가한다** (아래 참조).
7. **컨테이너로 배포되는 값이면** `infra/deploy/compose.yml` 의 `api.environment:` 와 `infra/deploy/.env.example` 에도 추가한다. 5번만 하고 7번을 빠뜨리면 로컬에서는 되고 서버에서만 깨진다.

기본값이 필요하면 `@DefaultValue` 를 쓴다. 다만 `ClickHouseProperties` 는 `@DefaultValue("8")` 등 4개를 선언해 두고 `application.yaml` 에도 같은 값을 적어 **이중 선언 상태**다. 새로 쓸 때는 한쪽만 고르는 편이 낫다.

## 설정 메타데이터 등록

`api/src/main/resources/META-INF/additional-spring-configuration-metadata.json`

```json
{"properties": [
  { "name": "jwt.secret", "type": "java.lang.String", "description": "A description for 'jwt.secret'" },
  ...
]}
```

- 현재 등록된 것은 `jwt.secret`, `gmail.client-id`, `gmail.client-secret` 3개뿐이고 description 도 IDE 생성 기본 문구 그대로다.
- `gmail.token`, `gmail.send-address`, `aws.s3.*`, `clickhouse.*`, `redis.key-prefix`, `mupid-client.website` 는 빠져 있다.
- `api/build.gradle` 에 `spring-boot-configuration-processor` 의존성이 **없다**. `@ConfigurationProperties` record 로부터 메타데이터가 자동 생성되지 않으므로 JSON 을 손으로 채워야 한다.
- 런타임에는 영향이 없고 IDE 경고만 나므로 방치되기 쉽다.

## 비밀정보 취급 규칙

| 대상 | 규칙 | 근거 |
|---|---|---|
| `api/.env` | git 무시됨 (`api/.gitignore` 1행). 커밋하지 않는다 | `git check-ignore -v api/.env` → `api/.gitignore:1:.env` |
| `infra/deploy/.env` | **무시되지 않는다.** 루트 `.gitignore` 에는 `/node_modules`, `/.gradle-ci/` 뿐 | `git check-ignore infra/deploy/.env` → exit 1 |
| 이미지 | `.env`·`.env.example` 을 넣지 않는다 | `api/.dockerignore` |
| 서버 비밀값 | `/srv/mupid/.env`, root 600. 내용을 로그·이슈·저장소에 붙이지 않는다 | `infra/OPERATIONS.md` |
| MySQL root | API 에 전달하지 않는다. 앱은 `mupid_app` 계정으로 `mupid` DB 에만 접근 | `infra/README.md`, `infra/deploy/prepare-env.py` |
| CI 배포 키 | GitLab File 변수 + Protected + 환경 `demo`. 여러 줄이라 masking 불가이므로 Visible + Protected 로 두고 출력하지 않는다. Expand variable reference 는 끈다 | `infra/README.md` |
| ClickHouse 비밀번호 | 현재 평문(`from_env`). 운영은 `password_sha256_hex` 로 가야 한다(미적용) | `api/clickhouse/README.md` |

값 생성 명령 (`api/.env.example`, `infra/bootstrap-backend.sh` 기준):

```bash
openssl rand -base64 32                # MUPID_JWT_SECRET (api/.env.example 줄 끝 주석)
openssl rand -base64 48 | tr -d '\n'   # MUPID_JWT_SECRET (infra/bootstrap-backend.sh:8)
openssl rand -hex 32                   # MUPID_S3_SECRET, DB 비밀번호 (bootstrap-backend.sh:7)
```

JWT 시크릿 길이가 두 출처에서 다르다. 스크립트가 만드는 값이 더 길다.

`infra/deploy/prepare-env.py` 는 비밀값을 절대 출력하지 않고, `/srv/mupid/.env` 가 이미 있으면 `Existing deployment environment preserved` 로 중단한다(1회성 마이그레이션).

## 새 설정을 추가할 때 체크리스트

- [ ] 변수 이름이 `MUPID_` 로 시작하는가? (서드파티 컨테이너 변수라면 이름 유지 + 값만 `MUPID_` 에서 받기)
- [ ] `application.yaml` 에 `${MUPID_...}` 로 참조했는가? 기본값을 붙일 이유가 있는가?
- [ ] `api/.env.example` 에 키를 추가하고 줄 끝 주석을 피했는가?
- [ ] `additional-spring-configuration-metadata.json` 에 넣었는가?
- [ ] compose 가 쓰는 값이면 숫자를 박지 않고 `${MUPID_...:-기본값}` 으로 뺐는가?
- [ ] 서버 배포에도 필요하면 `infra/deploy/compose.yml` 의 `api.environment:` **와** `infra/deploy/.env.example` 양쪽에 추가했는가?
- [ ] 비밀값이면 `.env` 가 실제로 무시되는 위치인지 확인했는가?

## 이 영역의 함정

각 항목의 상세는 [함정과 실패 사례](../failure/config-and-env.md) 참조.

- `api/AGENTS.md` 와 `api/README.md` 의 "`.gitignore` 에 `.env` 가 없다" 서술은 **사실이 아니다**. 이 문장을 믿고 `git add -f` 하거나 우회를 만들지 말 것.
- `.env` 무시 규칙은 `api/` 안에서만 유효하다. `infra/deploy/.env` 는 `git add .` 에 그대로 딸려 들어간다.
- `.env` 의 줄 끝 주석은 Spring 에서 **값의 일부**가 된다. compose 파서는 떼어내므로 둘의 결과가 다르다.
- `infra/deploy/compose.yml` 은 Gmail·S3 변수를 컨테이너에 넘기지 않는다. `api/compose.prod.yml`(`env_file: .env`)과 주입 방식이 다르다.
- `MUPID_MYSQL_PASSWORD` 의 의미가 두 운영 스택에서 다르다(root 비밀번호 vs `mupid_app` 비밀번호). 값을 그대로 복사하면 안 된다.
- 컨테이너 안에서는 `spring.config.import` 가 조용히 아무 일도 하지 않는다. 값이 안 잡히면 `.env` 가 아니라 `env_file`/`environment` 블록을 본다.
- `infra/deploy/compose.yml` 은 필수 표시를 쓸 수 없어 `CH_SHARD`/`CH_REPLICA` 가 비어도 통과하고, 복제가 조용히 깨진다. 검증: `docker exec mupid-clickhouse clickhouse-client -q "SELECT * FROM system.macros"`
- `api/compose.ci.yml` 은 어디에서도 참조되지 않고 환경변수도 낡았다. 실제 CI(`infra/ci/backend.yml`)는 build-only 다.
- 쓰이지 않는 키가 있다: `MUPID_KAFKA_BOOTSTRAP`(참조 0건), `MUPID_REDIS_KEY_PREFIX`(yaml 선언만 있고 읽는 코드 없음). 반대로 `MUPID_KAFKA_UI_VERSION`/`MUPID_KAFKA_UI_PORT` 는 compose 가 쓰는데 `.env.example` 에 없다.
