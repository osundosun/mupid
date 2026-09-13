# 설정 · 환경변수

10건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## 설정 · 환경변수 (.env)

### `.env` 값 끝에 주석 텍스트가 통째로 붙어 들어간다

- **원인**: `spring.config.import: optional:file:.env[.properties]` 의 `[.properties]` 때문에 Spring 은 `.env` 를 **자바 properties 문법**으로 읽는다. properties 에서 `#` 는 줄 맨 앞에서만 주석이다. `MUPID_S3_SECRET=abc  # openssl rand -hex 32` 의 값은 `abc  # openssl rand -hex 32` 가 되고, 값을 비워 둔 `MUPID_REDIS_KEY_PREFIX=  # 캐시 키 접두사 (예: mupid:dev)` 조차 빈 문자열이 아니라 주석 텍스트가 값이 된다. 따옴표도 벗겨지지 않고(`KEY="v"` → 값이 `"v"`) 백슬래시는 이스케이프로 해석된다. 같은 파일을 docker compose 가 읽을 때는 줄 끝 주석을 떼어내므로 **한 파일이 두 파서를 통과하는데 결과가 다르다.**
- **해결**: `.env.example` 을 복사해 값을 채울 때 줄 끝 주석을 지우거나 위 줄로 옮긴다. `.env.example` 의 ClickHouse 섹션(34-49행)이 이 규칙을 지킨 예다. 아직 줄 끝 주석을 쓰는 줄: `MUPID_REDIS_KEY_PREFIX`(14), `MUPID_JWT_SECRET`(17), `MUPID_GMAIL_*`(20-23), `MUPID_S3_ENDPOINT`(26), `MUPID_S3_SECRET`(28).
- **근거**: `api/.env.example`, `api/src/main/resources/application.yaml:7-9`

### 환경변수 하나가 없다고 애플리케이션이 아예 안 뜬다

- **원인**: `application.yaml` 이 기본값 없이 참조하는 키가 많다. 기본값이 있는 건 `MUPID_PORT:8800` 과 `MUPID_CLIENT_WEBSITE:`(빈 문자열) 둘뿐이다. `MUPID_MYSQL_URL/USER/PASSWORD`, `MUPID_REDIS_HOST/PORT`, `MUPID_JWT_SECRET`, `MUPID_GMAIL_*` 4개, `MUPID_S3_*` 4개, `MUPID_CLICKHOUSE_URL/USER/PASSWORD`, `MUPID_REDIS_KEY_PREFIX` 는 없으면 플레이스홀더 해석 실패로 기동이 멈춘다. `GmailProperties`/`StorageProperties` 는 `@ConfigurationPropertiesScan` 으로 기동 시 바인딩된다.
- **해결**: 새 실행 환경(IDE, CI, 임시 컨테이너)을 만들 때 이 목록을 전부 주입한다.
- **근거**: `api/src/main/resources/application.yaml`

### 컨테이너 안에서 `.env` 를 고쳐도 아무 변화가 없다

- **원인**: `api/.dockerignore` 가 `.env` 와 `.env.example` 을 둘 다 제외하므로 이미지에 `.env` 가 없다. `optional:` 접두사 때문에 파일이 없어도 경고 없이 넘어가고, 스프링은 compose 가 넣어준 OS 환경변수만 본다.
- **해결**: 컨테이너 값은 compose 의 `env_file` / `environment` 블록에서 본다. 반대로 호스트 실행에서는 경로가 프로세스 작업 디렉터리 기준이라 `api/` 밖에서 실행하면 `.env` 를 못 찾는데, 이때도 ".env 없음" 이 아니라 개별 플레이스홀더 해결 실패로 터진다(AGENTS.md 가 "작업 디렉터리는 항상 `api/`" 를 못박은 이유).
- **근거**: `api/.dockerignore`, `api/AGENTS.md`

### `infra/deploy` 스택에 배포했더니 메일·업로드가 있는 빌드가 기동에 실패한다

- **원인**: `infra/deploy/receive.sh:49` 는 `docker compose --env-file "$root/.env"` 로 띄우는데, `--env-file` 은 compose 파일의 `${...}` 치환에만 쓰인다. 컨테이너에 실제로 들어가는 건 `infra/deploy/compose.yml` 의 `api.environment:` 에 열거된 10개(`MUPID_MYSQL_USER`, `MUPID_MYSQL_PASSWORD`, `MUPID_JWT_SECRET`, `MUPID_PORT`, `MUPID_MYSQL_URL`, `MUPID_REDIS_HOST`, `MUPID_REDIS_PORT`, `MUPID_CLICKHOUSE_URL/USER/PASSWORD`)뿐이다. `MUPID_GMAIL_*` 4개와 `MUPID_S3_*` 4개, `MUPID_REDIS_KEY_PREFIX` 는 전달되지 않고 `infra/deploy/.env.example` 에도 없다.
- **해결**: 새 설정을 추가하면 `api.environment:` 블록과 `infra/deploy/.env.example` **양쪽에** 키를 넣는다. `api/compose.prod.yml` 은 `env_file: .env` 를 쓰므로 이 함정이 없다 — **두 운영 compose 의 주입 방식이 다르다는 점**이 핵심이다.
- **근거**: `infra/deploy/compose.yml:27-49`, `infra/deploy/receive.sh`

### `MUPID_MYSQL_PASSWORD` 를 그대로 복사했더니 root 비밀번호가 앱 계정에 들어간다

- **원인**: 의미가 스택마다 다르다. `api/compose.prod.yml` 의 mysql 은 `MYSQL_ROOT_PASSWORD: ${MUPID_MYSQL_PASSWORD}` 라 앱 비밀번호 = root 비밀번호다. `infra/deploy/compose.yml` 은 `MYSQL_ROOT_PASSWORD: ${MUPID_MYSQL_ROOT_PASSWORD}` 로 분리하고 앱은 `mupid_app` 계정을 쓴다 — `infra/deploy/prepare-env.py` 가 기존 `MUPID_MYSQL_PASSWORD` 를 root 비밀번호로 옮기고 `mupid_app` 용 새 비밀번호를 만들어 덮어쓴다.
- **해결**: 두 파일 사이에 값을 그대로 복사하지 않는다. `prepare-env.py` 는 `/srv/mupid/.env` 가 이미 있으면 `Existing deployment environment preserved` 로 중단하므로 재실행으로 고쳐지지도 않는다.
- **근거**: `infra/deploy/prepare-env.py`, `api/compose.prod.yml`, `infra/deploy/compose.yml`

### `MUPID_REDIS_PORT` 값을 믿고 운영 컨테이너에서 16379 로 붙으려 하면 실패한다

- **원인**: 개발에서는 이 변수가 호스트에 게시하는 포트(`"${MUPID_REDIS_PORT:-16379}:6379"`)이자 호스트에서 도는 스프링이 붙을 포트라 `.env` 가 `MUPID_REDIS_HOST=localhost`, `MUPID_REDIS_PORT=16379` 다. 운영에서는 redis 가 `expose: "6379"` 만 하므로 `compose.prod.yml`(과 `infra/deploy/compose.yml`)이 api 서비스 `environment` 에서 `MUPID_REDIS_HOST: redis`, `MUPID_REDIS_PORT: 6379` 로 덮어쓴다.
- **해결**: compose 의 environment 덮어쓰기를 지우지 않는다.
- **근거**: `api/compose.prod.yml`, `api/.env:15-16`, `infra/deploy/compose.yml:39-40`

### `redis.key-prefix` 로 환경을 분리했다고 생각했는데 키가 섞인다

- **원인**: `application.yaml:63` 의 `redis.key-prefix: ${MUPID_REDIS_KEY_PREFIX}` 를 읽는 코드가 **없다.** `@Value` 도 `@ConfigurationProperties` 도 없고, 저장소 전체에서 이 키의 참조는 `application.yaml` 과 `.env.example` 두 곳뿐이다. `CacheServiceImpl.buildKey` 는 `"%s:%s".formatted(kind.getRedisKey(), key)` 로 `block:token:<JWT>` 만 만든다.
- **해결**: 접두사로 환경을 분리하지 않는다. 현재는 개발(`mupid-dev-redis`)과 운영(`mupid-redis`)이 인스턴스 자체가 달라서 충돌이 없을 뿐이다. 접두사를 살리려면 `CacheServiceImpl.buildKey` 를 고쳐야 한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/cache/service/CacheServiceImpl.java:54-56`, `api/src/main/resources/application.yaml:62-63`

### 선언만 있고 아무도 읽지 않는 키 / compose 가 쓰는데 `.env.example` 에 없는 키

- **원인**: 규약이 양방향으로 깨진 자리들이다.
  - **읽는 곳 없음**: `MUPID_KAFKA_BOOTSTRAP=localhost:19092`(`api/.env.example:57`) — `application.yaml` 에 대응 항목이 없고, `build.gradle` 에 Kafka 클라이언트 의존성이 없으며(`spring-kafka` 없음), `api/src` 전체에 `kafka` 문자열이 0건이다. **Spring 은 아직 Kafka 프로듀서가 아니다.** `MUPID_REDIS_KEY_PREFIX` 도 같다.
  - **`.env.example` 에 없음**: `MUPID_KAFKA_UI_VERSION`(`api/compose.yml:285`), `MUPID_KAFKA_UI_PORT`(`api/compose.yml:294`). 기본값(`v1.3.0` / `18080`)이 있어 동작은 한다.
- **해결**: 이 키들이 쓰이고 있다고 가정하고 값을 맞추느라 시간 쓰지 않는다. 새 설정을 추가하면 `.env.example` 도 함께 갱신한다.
- **근거**: `api/.env.example`, `api/compose.yml`

### `aws.s3.region` 을 `.env` 로 덮어쓸 수 없다

- **원인**: `application.yaml:46` 에 `region: "SeaweedFS"` 가 하드코딩되어 있다. `StorageConfig` 는 `endpoint` 가 비어 있으면 `endpointOverride` 를 걸지 않으므로, `.env.example` 이 지시한 대로 운영에서 `MUPID_S3_ENDPOINT` 를 비우면 SDK 가 `Region.of("SeaweedFS")` 로 AWS 엔드포인트를 조립한다.
- **해결**: 실제 AWS S3 로 전환할 때 region 도 `${MUPID_S3_REGION}` 으로 빼야 한다.
- **근거**: `api/src/main/resources/application.yaml:43-49`

### 설정 메타데이터 규칙을 지키려 해도 자동 생성이 안 된다

- **원인**: `additional-spring-configuration-metadata.json` 에는 `jwt.secret`, `gmail.client-id`, `gmail.client-secret` 3개뿐이고 description 도 IDE 생성 기본 문구(`"A description for 'jwt.secret'"`) 그대로다. `gmail.token`, `gmail.send-address`, `aws.s3.*`, `clickhouse.*`, `redis.key-prefix`, `mupid-client.website` 는 빠져 있다. 게다가 `api/build.gradle` 에 `spring-boot-configuration-processor` 가 없어 `@ConfigurationProperties` record 로부터 자동 생성되지도 않는다.
- **해결**: 런타임 영향은 없고 IDE 경고만 난다. 규칙을 지키려면 JSON 을 손으로 채운다. 수동 등록이 특히 필요한 건 `@Value` 로만 읽는 키다 — 현재 `EmailServiceImpl` 의 `@Value("${mupid-client.website}")` 가 여기 해당한다.
- **근거**: `api/src/main/resources/META-INF/additional-spring-configuration-metadata.json`, `api/build.gradle`, `api/AGENTS.md`

### 5MB 제한이라고 적힌 이미지 업로드가 1MB 에서 막힌다 — 해소됨

- **원인**: `spring.servlet.multipart.max-file-size` 기본값이 **1MB** 다. `StorageImageServiceImpl.IMAGE_FILE_MAX_SIZE = 5MB` 검사는 파일이 컨트롤러에 도달해야 돌기 때문에, 그 전에 Tomcat 이 잘라낸다. 코드에 적힌 상한과 실제 상한이 다르다.
- **해결**: `application.yaml` 에 `spring.servlet.multipart.max-file-size` / `max-request-size` 를 명시했다 (`MUPID_MAX_UPLOAD_FILE_SIZE`, 기본 20MB). 서비스 레이어 상한(이미지 5MB / 청크 10MB)은 그대로 둔다.
- **근거**: `api/src/main/resources/application.yaml`

---
