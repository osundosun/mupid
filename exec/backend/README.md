# 백엔드 (Spring API) 빌드·배포

`api/` 의 Spring Boot 서버를 GitLab 에서 클론한 뒤 빌드·실행·배포하는 방법을 정리한다.
모든 서술은 저장소 파일을 근거로 하며, 근거 경로를 함께 적는다. 비밀값은 적지 않고 키 이름과 값이 있는 위치만 적는다.

- 소스: [../../api/](../../api/)
- 운영 URL: `https://j15a204.p.ssafy.io` ([../../infra_k8s/base/config.yaml](../../infra_k8s/base/config.yaml) 의 `MUPID_CLIENT_WEBSITE`)
- API 경로는 전부 `/api` 로 시작하고, 헬스체크는 `GET /api/actuator/health` 다.

## 1. 기술 스택과 버전

| 구분 | 제품 / 버전 | 근거 |
|---|---|---|
| 언어 / JVM | Java 21 (Gradle toolchain), 컨테이너는 Azul Zulu 21 (`azul-zulu:21-jdk` 빌드 → `azul-zulu:21-jre` 실행) | [build.gradle](../../api/build.gradle), [Dockerfile](../../api/Dockerfile) |
| 프레임워크 | Spring Boot **4.1.2-SNAPSHOT**, `io.spring.dependency-management` 1.1.7 | [build.gradle](../../api/build.gradle) |
| 빌드 도구 | Gradle **9.7.1** (Wrapper) | [gradle-wrapper.properties](../../api/gradle/wrapper/gradle-wrapper.properties) |
| WAS | Spring Boot 내장 서블릿 컨테이너 (`spring-boot-starter-webmvc`), 포트 `8800` | [application.yaml](../../api/src/main/resources/application.yaml) |
| 웹서버 (로컬) | nginx 1.27-alpine, 호스트 `8080` → `host.docker.internal:8800` | [compose.yml](../../api/compose.yml), [nginx/templates/default.conf.template](../../api/nginx/templates/default.conf.template) |
| 웹서버 (운영) | k8s `edge` 파드의 nginx (설정은 `infra_k8s/base/nginx/`) | [../../infra_k8s/base/edge.yaml](../../infra_k8s/base/edge.yaml) |
| RDBMS | MySQL 8.4 (`utf8mb4` / `utf8mb4_unicode_ci`), 드라이버 `mysql-connector-j` | [compose.yml](../../api/compose.yml) |
| 캐시 | Redis 7.4 | [compose.yml](../../api/compose.yml) |
| 분석 DB | ClickHouse 25.3 (Keeper 1 + 서버 2노드), `clickhouse-jdbc` 0.9.0 | [compose.yml](../../api/compose.yml), [clickhouse/README.md](../../api/clickhouse/README.md) |
| 이벤트 스트림 | Apache Kafka 3.9.1 (KRaft 1대), `spring-kafka` | [compose.yml](../../api/compose.yml) |
| 오브젝트 스토리지 | SeaweedFS (S3 호환), AWS SDK v2 `s3` (BOM 2.25.16) | [compose.yml](../../api/compose.yml), [build.gradle](../../api/build.gradle) |
| 벡터 DB | Qdrant (compose `vectors` 프로필) | [compose.yml](../../api/compose.yml) |
| 인증 | Spring Security + JJWT 0.12.5 | [build.gradle](../../api/build.gradle) |
| 메일 | Gmail API (`google-api-services-gmail` v1-rev20240520-2.0.0) + Jakarta Mail | [build.gradle](../../api/build.gradle) |
| 기타 스타터 | actuator, batch, batch-jdbc, data-jpa, data-redis, validation, thymeleaf, devtools, Lombok | [build.gradle](../../api/build.gradle) |
| IDE | VS Code 실행 구성 `ApiApplication` 을 쓴 기록이 있다 ([api/README.md](../../api/README.md)). `.vscode/`·`.idea` 는 `.gitignore` 대상이라 저장소에 없다 | [api/.gitignore](../../api/.gitignore) |

> TODO: 팀 확인 — 팀이 사용한 IDE 종류와 버전 (IntelliJ IDEA / VS Code + Extension Pack for Java 등).

알아 둘 것:

- Spring Boot 4.1.2 가 SNAPSHOT 이라 `https://repo.spring.io/snapshot` 저장소가 [build.gradle](../../api/build.gradle) 과 [settings.gradle](../../api/settings.gradle)(`pluginManagement`) **양쪽에** 필요하다. 빌드 머신이 이 저장소에 접근할 수 있어야 한다.
- Boot 4 는 스타터 이름이 바뀌었다. `spring-boot-starter-web` 이 아니라 `spring-boot-starter-webmvc`, 테스트는 `spring-boot-starter-<모듈>-test` 로 나뉜다 ([api/AGENTS.md](../../api/AGENTS.md)).
- `settings.gradle` 에 toolchain 자동 다운로드 플러그인이 없으므로 **JDK 21 을 직접 설치**해 둔다.
- `lz4-java` 좌표 충돌은 `build.gradle` 의 `capabilitiesResolution` 으로 처리돼 있다. 이 충돌은 `bootJar`(런타임 클래스패스)에서만 드러나므로 검증은 `build -x test` 로 한다.
- 프로파일별 yaml 은 없다. 설정 파일은 [application.yaml](../../api/src/main/resources/application.yaml) 하나이고 환경 차이는 전부 환경변수로 준다.

## 2. 로컬 빌드·실행

### 2-1. 필요한 도구

| 도구 | 용도 |
|---|---|
| Git | 클론 |
| JDK 21 | Gradle toolchain 이 요구한다 (Gradle 자체는 Wrapper 가 받는다) |
| Docker + Docker Compose | 로컬 인프라(MySQL·Redis·ClickHouse·Kafka·SeaweedFS·nginx) |
| MySQL 클라이언트 | 스키마 적용 (컨테이너 안의 `mysql` 로 대신해도 된다) |

### 2-2. 절차

작업 디렉터리는 항상 `api/` 다. `.env` 를 작업 디렉터리 기준으로 읽기 때문이다.

```bash
git clone <GitLab 저장소 URL>
cd <저장소>/api

# 1) 환경변수 파일
cp .env.example .env
#   값 채우기 — 3장의 표 참고. JWT 시크릿은 openssl rand -base64 32

# 2) 로컬 인프라 기동 (스프링은 띄우지 않는다)
docker compose up -d --wait
#   벡터 API 까지 쓰려면: docker compose --profile vectors up -d qdrant
#   Kafka 웹 UI(선택):   docker compose up -d kafka-ui   -> http://localhost:18080

# 3) MySQL 스키마 적용 (앱이 만들지 않는다 — 4장)
docker exec -i mupid-dev-mysql mysql -uroot -p"<MUPID_MYSQL_PASSWORD>" mupid \
  < knowledge/domain/erd.sql

# 4) 빌드 + 테스트
./gradlew build          # 테스트까지. 테스트 없이 jar 만: ./gradlew bootJar -x test

# 5) 실행
./gradlew bootRun        # 또는 java -jar build/libs/api-0.0.1-SNAPSHOT.jar
```

확인:

```bash
curl http://localhost:8800/api/actuator/health   # 스프링 직접
curl http://localhost:8080/api/actuator/health   # 로컬 nginx 경유
```

- `docker compose up` 이 띄우는 1회성 컨테이너 `storage-init`(S3 버킷 생성), `kafka-init`(토픽 6파티션 생성), `clickhouse-init`(ClickHouse DDL 적용)은 `exited 0` 이 정상이다 ([compose.yml](../../api/compose.yml)).
- Windows 에서는 `gradlew.bat` 도 있지만 Git Bash 에서는 `./gradlew` 를 쓴다 ([api/AGENTS.md](../../api/AGENTS.md)).
- 최초 기동 시 관리자 계정이 없으면 `admin@ssafy` 계정을 만들고 **무작위 비밀번호를 표준출력에 한 번만** 찍는다. 기동 로그에서 확인한다 ([AdminRunner.java](../../api/src/main/java/com/osundosun/mupid/api/admin/runner/AdminRunner.java)).
- 기동 시 `BootstrapRunner` 가 공통 코드(`codes`)와 취향 유형(`mubti`, 이미지는 S3 에 업로드)을 없을 때만 채운다 ([BootstrapRunner.java](../../api/src/main/java/com/osundosun/mupid/api/bootstrap/BootstrapRunner.java)).
- API 수동 호출은 Bruno 컬렉션 [docs/](../../api/docs/opencollection.yml) 을 쓴다. 로컬 환경의 `BASE_URL` 은 `http://localhost:8800` 이다 ([docs/environments/Local.yml](../../api/docs/environments/Local.yml)).

### 2-3. 로컬 포트

| 대상 | 호스트 포트 (기본값) | 변수 |
|---|---|---|
| Spring API | 8800 | `MUPID_PORT` |
| nginx | 8080 | `MUPID_NGINX_PORT` |
| MySQL | 13306 | `MUPID_MYSQL_PORT` |
| Redis | 16379 | `MUPID_REDIS_PORT` |
| ClickHouse ch-01 HTTP / native | 18123 / 19000 | `MUPID_CLICKHOUSE_HTTP_PORT` / `_NATIVE_PORT` |
| ClickHouse ch-02 HTTP / native | 18124 / 19001 | `MUPID_CLICKHOUSE_HTTP_PORT_2` / `_NATIVE_PORT_2` |
| Kafka (호스트용 EXTERNAL) | 19092 | `MUPID_KAFKA_PORT` |
| SeaweedFS S3 / master / filer | 8333 / 9333 / 8888 | (고정) |
| Qdrant HTTP / gRPC (127.0.0.1 만) | 6333 / 6334 | `MUPID_QDRANT_PORT` / `MUPID_QDRANT_GRPC_PORT` |
| Kafka UI (선택) | 18080 | `MUPID_KAFKA_UI_PORT` |

## 3. 환경변수

모든 변수는 `MUPID_` 접두사를 쓴다 ([api/AGENTS.md](../../api/AGENTS.md)). `application.yaml` 은 값을 직접 적지 않고 `${MUPID_...}` 로만 참조한다.

**값이 들어가는 경로**

| 환경 | 주입 방식 | 값의 위치 |
|---|---|---|
| 로컬 (`bootRun`, IDE) | `spring.config.import: optional:file:.env[.properties]` 가 `api/.env` 를 직접 읽는다 | `api/.env` (git 무시, 양식은 [api/.env.example](../../api/.env.example)) |
| 로컬 인프라 | `docker compose` 가 같은 `api/.env` 로 `${...}` 를 치환한다 | `api/.env` |
| 운영 (k8s) | 파드 환경변수. 비밀이 아닌 값은 ConfigMap `api-config`(`envFrom`), 비밀은 Secret `mupid-secrets` 에서 **키 이름을 하나씩** `secretKeyRef` 로 받는다 | [../../infra_k8s/base/config.yaml](../../infra_k8s/base/config.yaml), [../../infra_k8s/base/api.yaml](../../infra_k8s/base/api.yaml). Secret 원본은 GitLab File 변수 `MUPID_API_ENV` (노드의 `/srv/mupid-k8s/.env`) |

`.env` 는 셸이 아니라 **Java `.properties` 문법**으로 읽힌다. 줄 끝 주석이 값에 포함되므로 값이 있는 항목은 주석을 위 줄에 둔다 ([api/.env.example](../../api/.env.example) 의 ClickHouse 절 주석).

### 3-1. Spring 이 읽는 변수

필수 = `application.yaml` 에 기본값이 없어 정의돼 있어야 하는 것. 선택 = 기본값이 있거나 비워도 기동되는 것.

| 변수 | 매핑 프로퍼티 / 설명 | 필수 | 로컬 | 운영 (k8s) |
|---|---|---|---|---|
| `MUPID_PORT` | `server.port`. 기본 `8800` | 선택 | `.env` | ConfigMap (`8800`) |
| `MUPID_MYSQL_URL` | `spring.datasource.url` (JDBC URL) | 필수 | `.env` (예: `jdbc:mysql://localhost:13306/mupid`) | ConfigMap (`jdbc:mysql://mysql:3306/mupid?...`) |
| `MUPID_MYSQL_USER` | `spring.datasource.username` | 필수 | `.env` (로컬은 root) | ConfigMap (`mupid_app`) |
| `MUPID_MYSQL_PASSWORD` | `spring.datasource.password`. 로컬 compose 에서는 MySQL root 비밀번호로도 쓰인다 | 필수 | `.env` | Secret |
| `MUPID_REDIS_HOST` | `spring.data.redis.host` | 필수 | `.env` (`localhost`) | ConfigMap (`redis`) |
| `MUPID_REDIS_PORT` | `spring.data.redis.port` | 필수 | `.env` (`16379`) | ConfigMap (`6379`) |
| `MUPID_REDIS_KEY_PREFIX` | `redis.key-prefix`. yaml 에만 있고 읽는 자바 코드는 없다 | 선택(실질) | `.env` | ConfigMap (`mupid:prod`) |
| `MUPID_JWT_SECRET` | `jwt.secret` — JWT 서명 키 (Base64 32바이트) | 필수 | `.env` | Secret |
| `MUPID_GMAIL_CLIENT_ID` | `gmail.client-id` — Google Cloud OAuth 클라이언트 | 필수 | `.env` | Secret |
| `MUPID_GMAIL_CLIENT_SECRET` | `gmail.client-secret` | 필수 | `.env` | Secret |
| `MUPID_GMAIL_TOKEN` | `gmail.token` — OAuth 리프레시 토큰. 만료되면 메일 발송 전체가 실패 | 필수 | `.env` | Secret |
| `MUPID_GMAIL_SEND_ADDRESS` | `gmail.send-address` — 발신 주소, 문의 관리자 알림 수신 주소 | 필수 | `.env` | Secret |
| `MUPID_MATTERMOST_WEBHOOK_URL` | `mupid-mattermost.webhook-url` — 문의 알림. URL 자체가 자격증명. 비우면 알림만 꺼진다 | 선택 | `.env` | Secret (optional) |
| `MUPID_S3_ENDPOINT` | `aws.s3.endpoint`. 비우면 SDK 기본 엔드포인트 (`StorageConfig`) | 필수(빈 값 허용) | `.env` (`http://localhost:8333`) | Secret |
| `MUPID_S3_ACCESS_KEY` | `aws.s3.access-key`. 로컬 SeaweedFS 관리자 키로도 쓰인다 | 필수 | `.env` | Secret |
| `MUPID_S3_SECRET` | `aws.s3.secret-key` | 필수 | `.env` | Secret |
| `MUPID_S3_BUCKET` | `aws.s3.bucket-name`. 로컬은 `storage-init` 이 이 이름으로 버킷을 만든다 | 필수 | `.env` | Secret |
| `MUPID_MAX_UPLOAD_FILE_SIZE` | `spring.servlet.multipart.max-file-size`. 기본 `20MB` | 선택 | `.env` | (미설정 → 기본값) |
| `MUPID_MAX_UPLOAD_REQUEST_SIZE` | `spring.servlet.multipart.max-request-size`. 기본 `20MB` | 선택 | `.env` | (미설정 → 기본값) |
| `MUPID_CLIENT_WEBSITE` | `mupid-client.website` — 메일 본문 링크 등에 쓰는 프론트 주소 | 선택 | `.env` | ConfigMap·Secret 양쪽에 있다 (env 로 준 Secret 값이 이긴다) |
| `MUPID_CLICKHOUSE_URL` | `clickhouse.url` (JDBC) | 필수 | `.env` (`jdbc:clickhouse://localhost:18123/default`) | ConfigMap (`jdbc:clickhouse://clickhouse:8123/default`) |
| `MUPID_CLICKHOUSE_USER` | `clickhouse.username` | 필수 | `.env` (`mupid`) | ConfigMap (`mupid`) |
| `MUPID_CLICKHOUSE_PASSWORD` | `clickhouse.password`. ClickHouse 서버 XML 도 `from_env` 로 읽는다 | 필수 | `.env` | Secret |
| `MUPID_CLICKHOUSE_HTTP_URL` | `clickhouse.http-url` — 관리자 콘솔 프록시 주소. 비우면 JDBC 주소에서 만든다 | 선택 | `.env` | (미설정) |
| `MUPID_KAFKA_BOOTSTRAP` | `event.kafka-bootstrap-servers` — 청취 이벤트 프로듀서 | 필수 | `.env` (`localhost:19092`) | ConfigMap (`kafka:9092`) |
| `MUPID_KAFKA_TOPIC` | `event.kafka-topic`. 기본 `mupid.listen.events` | 선택 | `.env` | ConfigMap |
| `MUPID_AI_GMS_KEY` | `ai.api-key` — LLM 게이트웨이 키. 옛 이름 `MUPID_AI_GMS` 도 받는다. 비면 `/api/v1/ai/chat` 이 503 | 선택 | `.env` | Secret (optional) |
| `MUPID_AI_BASE_URL` | `ai.base-url` — OpenAI 호환 게이트웨이. 기본 `https://gms.ssafy.io/gmsapi/api.openai.com/v1` | 선택 | `.env` | Secret (optional) |
| `MUPID_AI_MODEL` | `ai.model`. 기본 `gpt-5-mini` | 선택 | `.env` | Secret (optional) |
| `MUPID_RECOMMENDER_BASE_URL` | `recommender.base-url` — 추천 서버(ai_server) `/internal` 앞 주소. 비면 곡 추천만 503 | 선택 | `.env` | ConfigMap (`http://ai:8100`) |
| `MUPID_RECOMMENDER_INTERNAL_TOKEN` | `recommender.internal-token` — 추천 서버 `MUPID_AI_INTERNAL_TOKEN` 과 **같은 값**이어야 한다 | 선택 | `.env` | Secret (optional) |
| `MUPID_QDRANT_URL` | `qdrant.url`. 비면 벡터 API 만 503 | 선택 | `.env` | ConfigMap (`http://qdrant:6333`) |
| `MUPID_QDRANT_API_KEY` | `qdrant.api-key` — 쓰기 키 | 선택 | `.env` (compose 기본값 사용 가능) | Secret (optional) |
| `MUPID_QDRANT_COLLECTION` | `qdrant.collection`. 비우면 [qdrant_collection.json](../../api/src/main/resources/qdrant/qdrant_collection.json) 의 이름 | 선택 | `.env` | (미설정) |
| `MUPID_QDRANT_WRITE_CONCURRENCY` / `MUPID_QDRANT_WRITE_QUEUE` | 벡터 쓰기 동시성·대기열. 기본 `2` / `8` | 선택 | — | (미설정) |

`MUPID_AI_BASE_URL`(LLM 게이트웨이)과 `MUPID_RECOMMENDER_BASE_URL`(추천 서버)은 **다른 서버**다. 추천 서버 주소를 `MUPID_AI_BASE_URL` 에 넣으면 대화 요청이 추천 서버로 나간다 ([application.yaml](../../api/src/main/resources/application.yaml), [api/.env.example](../../api/.env.example) 끝 주석).

### 3-2. 로컬 compose 만 읽는 변수

Spring 은 읽지 않고 [compose.yml](../../api/compose.yml) 치환에만 쓴다. 모두 기본값이 있다.

| 변수 | 설명 | 기본값 |
|---|---|---|
| `MUPID_NGINX_PORT`, `MUPID_MYSQL_PORT` 등 포트 변수 | 2-3 표 참고 | — |
| `MUPID_CLICKHOUSE_VERSION` | ClickHouse·Keeper 이미지 태그 | `25.3` |
| `MUPID_CLICKHOUSE_MAX_MEMORY` | 노드별 메모리 상한(바이트), 0 이면 자동 | `4294967296` |
| `MUPID_CLICKHOUSE_SERVING_PASSWORD` | 추천 서빙 읽기 계정 `mupid_serving` 비밀번호 | `MUPID_CLICKHOUSE_PASSWORD` |
| `MUPID_KAFKA_VERSION` | Kafka 이미지 태그 | `3.9.1` |
| `MUPID_KAFKA_CLUSTER_ID` | KRaft 클러스터 ID (base64 22글자) | `.env.example` 에 값 있음 |
| `MUPID_KAFKA_UI_VERSION` | Kafka UI 이미지 태그 | `v1.3.0` |
| `MUPID_QDRANT_READ_ONLY_API_KEY` | Qdrant 읽기 전용 키 | 개발용 기본값 |

`MUPID_CLICKHOUSE_PASSWORD`, `MUPID_S3_BUCKET` 이 비어 있으면 compose 가 즉시 실패한다 (`${...:?}`).

## 4. 배포 시 특이사항

### 스키마는 사람이 넣는다 (`ddl-auto: none`, Flyway 없음)

- `application.yaml` 에 `spring.jpa.hibernate.ddl-auto` 가 없고, MySQL 은 비내장 DB 라 Spring Boot 기본값 `none` 이 적용된다. 팀 결정으로 로컬·운영 모두 `none` 이며 Flyway·Liquibase 의존성도 없다 ([decision/0011](../../api/knowledge/decision/0011-jpa-manages-schema.md)).
- 따라서 빈 MySQL 에는 [erd.sql](../../api/knowledge/domain/erd.sql)(MySQL 8.4, `CREATE TABLE IF NOT EXISTS`)을 먼저 적용한다. 적용하지 않으면 기동 직후 `AdminRunner`/`BootstrapRunner` 의 첫 쿼리에서 깨진다.
- `codes` 는 기동 시 `BootstrapRunner` 가 채운다. 앱을 띄우지 않고 DB 만 채울 때는 [seed-codes.sql](../../api/knowledge/domain/seed-codes.sql) 을 쓴다 ([../../infra_k8s/PLAN.md](../../infra_k8s/PLAN.md) Phase 4).
- `knowledge/domain/migrate-*.sql`, `apply-search-indexes.sql` 은 **이미 떠 있는 DB** 를 고칠 때 쓴 일회성 스크립트다. 새 DB 는 `erd.sql` 로 충분하다.
- 데이터까지 옮기려면 DB 덤프 [../db/mupid_mysql_dump.sql](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A204/-/blob/master/exec/db/mupid_mysql_dump.sql) 을 쓴다.
- 엔티티와 DB 가 어긋나면 배포 직후가 아니라 그 컬럼을 처음 쓰는 요청에서 터진다. 운영은 `infra_k8s/deploy/schema-check.py` 로 대조한다 ([../../infra_k8s/OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) 6절).

### 분석 스택 초기화 순서

- Kafka 토픽을 ClickHouse DDL 보다 먼저 만든다. 브로커가 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` 라 토픽이 없으면 `kafka_listen_events` 가 소비를 시작하지 않는다. 토픽은 6파티션(ClickHouse 2노드 × 컨슈머 3).
- ClickHouse DDL 은 [clickhouse/init/01-schema.sql](../../api/clickhouse/init/01-schema.sql) 을 `mupid` 계정으로 한 노드에서 한 번 적용한다(`ON CLUSTER`). 로컬은 `clickhouse-init` 컨테이너가 [apply-ddl.sh](../../api/clickhouse/init/apply-ddl.sh) 로 자동 적용한다.
- [clickhouse/migrations/](../../api/clickhouse/migrations/) 의 `02`~`06` 은 이미 떠 있는 클러스터용이다. 새 클러스터에는 `01-schema.sql` 에 반영돼 있다 (`02-numeric-ids.sql` 은 파일 머리에 "실행되지 않는다" 고 적혀 있다).
- ClickHouse 커넥션 풀과 Kafka 프로듀서는 지연 생성이라 두 서비스가 없어도 API 는 뜬다. 분석·이벤트 요청만 실패한다 ([../../infra_k8s/base/config.yaml](../../infra_k8s/base/config.yaml) 주석).

### S3 버킷은 앱이 만들지 않는다

SeaweedFS 는 버킷을 자동 생성하지 않는다. 버킷이 없으면 업로드가 `NoSuchBucket` 으로 실패한다. 로컬은 `storage-init` 이, 운영은 `weed shell` 의 `s3.bucket.create` 를 손으로 실행한다 ([../../infra_k8s/OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) 5절).

### 실제로 배포되는 이미지는 `api/Dockerfile` 이 아니다

| 파일 | 용도 |
|---|---|
| [api/Dockerfile](../../api/Dockerfile) | 로컬 검증용. 멀티스테이지(`azul-zulu:21-jdk` 에서 `bootJar -x test` → `azul-zulu:21-jre`), 비루트 `spring` 사용자, curl HEALTHCHECK |
| [../../infra/image/build.Dockerfile](../../infra/image/build.Dockerfile) + [../../infra/image/Dockerfile](../../infra/image/Dockerfile) | **운영 이미지**. 빌더에서 `gradlew build -x test` 로 jar 를 만들고, 런타임은 `azul-zulu:21-jre` 에 `app.jar` 만 복사, UID 1000 으로 실행 |
| [api/compose.yml](../../api/compose.yml), [api/nginx/](../../api/nginx/) | 로컬 개발 전용. 운영 nginx 설정은 `infra_k8s/base/nginx/` |

두 Dockerfile 의 JVM 옵션은 같다: `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul"`, `TZ=Asia/Seoul`, `EXPOSE 8800`, `ENTRYPOINT sh -c "exec java $JAVA_OPTS -jar /app/app.jar"`. k8s 메모리 limit 이 2Gi 라 힙은 limit 의 75% 를 따른다.

[api/README.md](../../api/README.md)·[api/AGENTS.md](../../api/AGENTS.md) 가 언급하는 `compose.prod.yml`·`compose.ci.yml` 은 현재 저장소에 없다. 문서와 코드의 어긋남은 [failure/doc-drift.md](../../api/knowledge/failure/doc-drift.md) 에 모여 있다.

> TODO: 팀 확인 — 베이스 이미지 이름 `azul-zulu:21-jdk`/`21-jre` 의 출처(Docker Hub 공식 이름은 `azul/zulu-openjdk` 계열이다). 저장소에는 이 이름을 그대로 쓰는 기록만 있고, 러너가 받아 두었다는 기록만 있다([../../infra_k8s/ansible/CONTROL-NODE.md](../../infra_k8s/ansible/CONTROL-NODE.md)). 다른 환경에서 빌드가 이미지 pull 단계에서 실패하면 이 이름부터 확인한다.

### 운영(k8s) 요약

k8s 상세는 인프라 문서(`exec/infra/`)가 다룬다. API 관련만 적는다 ([../../infra_k8s/base/api.yaml](../../infra_k8s/base/api.yaml)).

- Service `api` 포트 **8800** → 컨테이너 8800. `replicas: 1`, `mupid.io/role=edge` 노드에 고정.
- 레지스트리가 없다. CI(`k8s-deploy-api`, [../../infra_k8s/ci/k8s-release.yml](../../infra_k8s/ci/k8s-release.yml))가 master 푸시마다 이미지를 굽고 노드 수신기가 k3s containerd 로 가져온다. 태그는 커밋 SHA, `imagePullPolicy: IfNotPresent`.
- 환경변수 교체는 이미지와 별개다. GitLab File 변수 `MUPID_API_ENV` 를 `k8s-deploy-env` job 이 노드로 보내고, 노드가 Secret `mupid-secrets` 를 갈아 끼운 뒤 api 를 재시작한다 ([../../infra_k8s/ci/deploy-env.sh](../../infra_k8s/ci/deploy-env.sh)).
- 새 비밀을 추가할 때는 `.env` 와 `api.yaml` 의 `env:` **둘 다** 고친다. Secret 을 `envFrom` 으로 통째로 넣지 않기 때문이다(`MUPID_MYSQL_ROOT_PASSWORD` 가 api 파드에 들어가지 않게).
- probe 는 startup/readiness/liveness 모두 `GET /api/actuator/health` (8800). Actuator 는 `health` 만 노출, `show-details: never`.
- CI 는 백엔드 테스트를 돌리지 않는다 (`gradlew build -x test`, [../../infra/ci/backend.yml](../../infra/ci/backend.yml)). MR 에 `Backend` 라벨이 있어야 `backend-verify` 가 돈다.
- `@Scheduled` 배치(리포트 00:05, 재생수 00:10, 일일 추천 12:00, 동기화 대조 04:40~04:50 등, `Asia/Seoul`)는 인스턴스마다 돈다. 레플리카를 늘리면 중복 실행된다 ([ApiApplication.java](../../api/src/main/java/com/osundosun/mupid/api/ApiApplication.java)).
- `spring-boot-starter-batch(-jdbc)` 가 의존성에 있지만 Spring Batch 잡은 하나도 없고 `BATCH_*` 메타 테이블도 준비돼 있지 않다 ([failure/build-and-spring.md](../../api/knowledge/failure/build-and-spring.md)).
- 이미지 롤백은 DB 스키마를 되돌리지 않는다 ([decision/0011](../../api/knowledge/decision/0011-jpa-manages-schema.md)).

## 5. 계정·프로퍼티가 정의된 파일 목록

| 파일 | 내용 |
|---|---|
| [api/src/main/resources/application.yaml](../../api/src/main/resources/application.yaml) | 유일한 Spring 설정. DB·Redis·ClickHouse·Kafka·S3·JWT·Gmail·AI·Qdrant 프로퍼티와 환경변수 매핑 |
| [api/.env.example](../../api/.env.example) | 로컬 `.env` 양식 (키만, 값은 비어 있음). 실제 `api/.env` 는 git 무시 |
| [api/src/main/resources/META-INF/additional-spring-configuration-metadata.json](../../api/src/main/resources/META-INF/additional-spring-configuration-metadata.json) | 커스텀 프로퍼티 메타데이터 (`jwt.secret`, `gmail.*`, `ai.*`, `mupid-mattermost.*`) |
| `api/src/main/java/.../**/properties/*Properties.java` | `@ConfigurationProperties` 바인딩: [AiProperties](../../api/src/main/java/com/osundosun/mupid/api/ai/properties/AiProperties.java), [RecommenderProperties](../../api/src/main/java/com/osundosun/mupid/api/ai/properties/RecommenderProperties.java), [ClickHouseProperties](../../api/src/main/java/com/osundosun/mupid/api/analytics/properties/ClickHouseProperties.java), [GmailProperties](../../api/src/main/java/com/osundosun/mupid/api/common/gmail/GmailProperties.java), [MattermostProperties](../../api/src/main/java/com/osundosun/mupid/api/common/mattermost/MattermostProperties.java), [EventProperties](../../api/src/main/java/com/osundosun/mupid/api/event/properties/EventProperties.java), [StorageProperties](../../api/src/main/java/com/osundosun/mupid/api/storage/properties/StorageProperties.java), [QdrantProperties](../../api/src/main/java/com/osundosun/mupid/api/vector/properties/QdrantProperties.java) |
| [api/src/main/resources/qdrant/qdrant_collection.json](../../api/src/main/resources/qdrant/qdrant_collection.json) | Qdrant 컬렉션 정의 (`moments__mert-v1-95m`, 1536차원 Cosine) |
| [api/compose.yml](../../api/compose.yml) | 로컬 인프라. MySQL DB 이름 `mupid`, root 비밀번호 = `MUPID_MYSQL_PASSWORD`, SeaweedFS 관리자 키 = `MUPID_S3_*` |
| [api/clickhouse/users/10-mupid-user.xml](../../api/clickhouse/users/10-mupid-user.xml) | ClickHouse 앱 계정 `mupid` (비밀번호는 `from_env` `MUPID_CLICKHOUSE_PASSWORD`) |
| [api/clickhouse/users/20-serving-user.xml](../../api/clickhouse/users/20-serving-user.xml) | ClickHouse 서빙 읽기 계정 `mupid_serving` |
| [api/clickhouse/users/00-profile.xml](../../api/clickhouse/users/00-profile.xml), [api/clickhouse/server/](../../api/clickhouse/server/) | ClickHouse 프로필·클러스터·Keeper·매크로 설정 (`CH_*` 환경변수) |
| [api/knowledge/domain/erd.sql](../../api/knowledge/domain/erd.sql) | **ERD DDL** (MySQL). 해설은 [erd.md](../../api/knowledge/domain/erd.md) |
| [api/knowledge/domain/seed-codes.sql](../../api/knowledge/domain/seed-codes.sql) | 공통 코드 시드 |
| [api/clickhouse/init/01-schema.sql](../../api/clickhouse/init/01-schema.sql) | ClickHouse 스키마 |
| [api/src/main/java/.../admin/runner/AdminRunner.java](../../api/src/main/java/com/osundosun/mupid/api/admin/runner/AdminRunner.java) | 초기 관리자 계정 `admin@ssafy` 자동 생성 (비밀번호는 기동 로그) |
| [../../infra_k8s/base/config.yaml](../../infra_k8s/base/config.yaml) | 운영 ConfigMap `api-config` (비밀 아닌 값) |
| [../../infra_k8s/base/api.yaml](../../infra_k8s/base/api.yaml) | 운영 Deployment/Service. Secret `mupid-secrets` 에서 받는 키 목록 |

운영 MySQL 앱 계정은 `mupid_app`(ConfigMap `MUPID_MYSQL_USER`)이며 비밀번호는 Secret `mupid-secrets` 의 `MUPID_MYSQL_PASSWORD` 에 있다.

## 6. 테스트

```bash
cd api
./gradlew test     # 테스트만
./gradlew build    # 빌드 + 테스트
```

- JUnit 5 (`useJUnitPlatform()`), `src/test/java` 에 테스트 파일 97개.
- `@SpringBootTest` 테스트는 **로컬 MySQL 이 떠 있고 `erd.sql` 이 적용돼 있어야** 통과한다. `.env` 의 `MUPID_MYSQL_URL` 이 가리키는 DB 를 쓰며, 각 테스트는 `@Transactional` 로 롤백된다. 컨텍스트 기동 시 관리자 계정이 없으면 하나 만든다.
- Redis·Kafka·ClickHouse 는 기동 시 연결하지 않아 없어도 컨텍스트는 뜬다. 순수 JUnit 테스트(`EventCollectBatchTest`, `MubtiScorerTest` 등)는 인프라 없이 돈다.
- CI 는 테스트를 건너뛴다(`-x test`). 파이프라인 통과를 테스트 통과로 읽지 않는다.

근거: [api/knowledge/convention/testing.md](../../api/knowledge/convention/testing.md), [ApiApplicationTests.java](../../api/src/test/java/com/osundosun/mupid/api/ApiApplicationTests.java)

## 참고 문서

| 문서 | 내용 |
|---|---|
| [api/README.md](../../api/README.md), [api/AGENTS.md](../../api/AGENTS.md) | 백엔드 개요, 명령, 규칙 |
| [api/knowledge/README.md](../../api/knowledge/README.md) | 설계·결정·실패 사례 저장소 |
| [api/knowledge/architecture/configuration.md](../../api/knowledge/architecture/configuration.md) | 설정 경로 해설 (일부 옛 내용 포함) |
| [api/clickhouse/README.md](../../api/clickhouse/README.md) | ClickHouse·Kafka 로컬 클러스터 |
| [../../infra_k8s/OPERATIONS-K8S.md](../../infra_k8s/OPERATIONS-K8S.md) | 운영 구축·배포·롤백 절차 |
