# 시스템 아키텍처

Mupid 가 어떤 조각으로 이루어져 있고, 요청과 데이터가 그 사이를 어떻게 흐르는지 정리한 문서다. 새 구성요소를 붙이거나 기존 것을 옮길 때, 어느 compose 파일을 고쳐야 할지 헷갈릴 때, "이건 왜 이렇게 되어 있나"를 확인해야 할 때 먼저 읽는다. 개별 파일의 코드 규약이 아니라 **경계와 그 근거**를 다룬다.

## 구성요소

| 구성요소 | 기술 / 버전 | 저장소 위치 | 역할 |
|---|---|---|---|
| 프런트 | vinext 1.0.0-beta.5 + React 19.2.6, Tailwind 4 | `frontend/` | 정적 export(`dist/client`). 서버에서 nginx 가 직접 서빙 |
| 프런트(구) | 같음 | `App/` | 분리 이전 프로토타입 사본. CI 대상이 아니다 |
| 리버스 프록시 | nginx 1.27-alpine | `api/nginx/templates/default.conf.template`, `infra/deploy/nginx.conf` | 유일한 외부 노출 지점. TLS 종료 · `/api/` 프록시 · 정적 서빙 |
| API | Java 21(Azul Zulu) · Spring Boot 4.1.2-SNAPSHOT · Gradle 9.7.1 | `api/` | 인증 · 회원 · 메일 · 파일. 포트 8800 |
| RDBMS | MySQL 8.4 | compose 4벌 | 회원 · 이메일 인증 · 파일 메타데이터 |
| 캐시 | Redis 7.4-alpine | compose 4벌 | 차단(로그아웃)된 액세스 토큰 |
| 오브젝트 스토리지 | SeaweedFS(`chrislusf/seaweedfs`) / S3 호환 | `api/compose.yml` | 파일 바이트. 개발은 SeaweedFS, 운영은 실제 S3 엔드포인트 |
| 분석 DB | ClickHouse 25.3, 서버 2노드 + Keeper 1 | `api/clickhouse/` | 청취 이벤트 적재 · 구간 시그널 파생 |
| 메시지 큐 | Kafka 3.9.1 (KRaft, 브로커 1대) | `api/compose.yml`, `infra/deploy/compose.yml` | 토픽 `mupid.listen.events` |
| 메일 | Gmail API (`google-api-services-gmail`) | `api/src/main/java/com/osundosun/mupid/api/common/gmail/` | 회원가입 · 비밀번호 초기화 인증 메일 |

버전 기본값은 `.env` 변수로 바꿀 수 있다 — `MUPID_CLICKHOUSE_VERSION`(기본 `25.3`), `MUPID_KAFKA_VERSION`(기본 `3.9.1`).

## 요청 흐름

운영(`infra/deploy/nginx.conf`) 기준. 프런트와 API 가 같은 도메인을 공유하고 경로로 갈린다.

```
브라우저
  │
  ├─ http://j15a204.p.ssafy.io  ──► nginx :80
  │      /healthz                      200 "ok"
  │      /.well-known/acme-challenge/  root /var/www/acme   (certbot webroot)
  │      /                             308 → https
  │
  └─ https://j15a204.p.ssafy.io ──► nginx :443  (letsencrypt, TLSv1.2/1.3)
         /healthz     200 "ok"                       ← 백엔드와 무관
         /api/    ──► upstream mupid_api = api:8800  ← keepalive 32, proxy_buffering off
         /        ──► root /srv/web/current          ← try_files, 정적 프런트
```

`/api/` 로 들어온 요청이 컨테이너 안에서 거치는 경로:

```
api:8800  (server.port = ${MUPID_PORT:8800})
  │
  └─ SecurityFilterChain            STATELESS · csrf/formLogin/httpBasic 전부 disable
       │
       ├─ AuthFilter                UsernamePasswordAuthenticationFilter 앞에 삽입
       │    ① Authorization: Bearer <token> 파싱
       │    ② AuthProvider.isValidToken(token)          — JWT 서명 검증
       │    ③ CacheService.exists(token, BlockToken)    ──► Redis  block:token:<토큰>
       │    ④ AuthProvider.auth(token)                  ──► MySQL  findByUserIdAndStatusNot
       │       → SecurityContext 에 User 엔티티를 principal 로 저장
       │       ①~③ 실패는 인증 없이 통과시키고 인가 단계에 맡긴다.
       │       ④ 에서 AuthenticationException 이 나면 필터가 곧바로 401 을 쓴다.
       │
       ├─ 인가        permitAll: POST /api/v1/auth, POST /api/v1/user,
       │              /api/v1/user/reset, /api/v1/mail/verification,
       │              GET /api/v1/mail/verification/**, GET /api/actuator/**
       │              나머지 anyRequest().authenticated()
       │
       ├─ 실패 처리   401 AuthUnauthorizedHandler / 403 AuthForbiddenHandler
       │              (@RestControllerAdvice 밖이라 ObjectMapper 로 직접 직렬화)
       │
       └─ Controller ─► Service(interface + Impl) ─► Repository | S3Client | GmailProvider
```

컨트롤러 진입점은 5개다.

| 클래스 | 경로 |
|---|---|
| `AuthController` | `/api/v1/auth` (POST 로그인 / GET 재발급 / DELETE 로그아웃) |
| `UserController` | `/api/v1/user` (POST · GET · PATCH · DELETE) |
| `UserImageController` | `/api/v1/user/image` (POST multipart · DELETE · GET `/{userId}`) |
| `UserPasswordResetController` | `/api/v1/user/reset` (GET 메일 발송 · POST 코드 제출) |
| `EmailVerificationController` | `/api/v1/mail/verification` (GET 메일 발송 · POST 코드 제출 · GET `/{verificationId}` 상태 확인) |

응답은 전부 `BaseResponse<T>` 로 감싸고 JSON 은 snake_case 다(`spring.jackson.property-naming-strategy: SNAKE_CASE`). 파일 바이트를 내려주는 `UserImageController` 만 `ResponseEntity<byte[]>` 로 예외를 둔다.

## 애플리케이션 내부 구조

패키지는 레이어가 아니라 **도메인**으로 먼저 나눈다 — `auth`, `user`, `email`, `storage`, `cache`, `analytics`, `common`, `utils`. 도메인을 넘을 때는 서비스 인터페이스만 호출한다.

```
auth ──► user (UserService)          로그인 시 회원 조회
     ──► cache (CacheService)        로그아웃 토큰 차단
user ──► email (EmailService)        가입/초기화 인증 메일
     ──► storage (StorageService)    프로필 이미지
email ──► common/gmail (GmailProvider)
storage ──► S3Client (storage/config/StorageConfig)
analytics ──► clickHouseJdbcTemplate 빈만 등록. 사용하는 코드는 아직 없다
```

예외는 `auth/provider/AuthProvider` 하나로, 인증 요청마다 `UserRepository` 를 직접 쓴다.

부트스트랩은 `api/src/main/java/com/osundosun/mupid/api/ApiApplication.java` 세 줄이 전부다.

```java
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
```

`@ConfigurationPropertiesScan` 이 `StorageProperties` · `ClickHouseProperties` · `GmailProperties` 를 자동 등록하므로 프로퍼티 record 에 `@Component` 를 붙이지 않는다. `@EnableJpaAuditing` 은 엔티티의 `@CreatedDate` / `@LastModifiedDate` 를 위한 것이다. 설정 클래스는 "그 기술을 쓰는 도메인 아래"에 두고(`storage/config`, `analytics/config`), 여러 도메인이 공유하는 것만 `common/config` 로 올린다(`SecurityConfig`, `JdbcTemplateConfig`).

### JdbcTemplate 이 두 개인 이유

`analytics/config/ClickHouseConfig` 가 `clickHouseJdbcTemplate` 빈을 등록하는 순간, `JdbcTemplateAutoConfiguration` 의 `@ConditionalOnMissingBean(JdbcOperations.class)` 가 깨지면서 자동 설정되던 MySQL `JdbcTemplate` 이 사라진다. `common/config/JdbcTemplateConfig` 가 이를 `@Primary` 로 다시 정의해 원래 동작을 복구한다. 주입 지점에는 `@Qualifier("clickHouseJdbcTemplate")` 를 명시해야 하고, 그 애너테이션이 Lombok 생성자로 전달되도록 `api/lombok.config` 가 `lombok.copyableAnnotations` 를 설정한다.

## 데이터가 어디에 있는가

| 데이터 | 저장소 | 접근 경로 | 비고 |
|---|---|---|---|
| 회원 | MySQL `users` | `UserRepository` (JPA) | `@Enumerated(STRING)`, PK `Integer` |
| 이메일 인증 | MySQL `email_verifications` | `EmailVerificationRepository` | 토큰 6자리, 만료 15분 |
| 파일 메타데이터 | MySQL `object_storage_files` | `ObjectStorageFileRepository` | soft delete(`isDeleted`) |
| 파일 바이트 | S3 (`aws.s3.bucket-name`) | `StorageServiceImpl` + `S3Client` | 메타는 MySQL, 실물은 S3 |
| 차단 토큰 | Redis | `CacheServiceImpl` | 키 `block:token:<토큰>` |
| 인증 메일 | Gmail API | `GmailProvider` | OAuth refresh token 방식 |
| 청취 이벤트 · 구간 시그널 | ClickHouse | (미구현) | 적재는 Kafka 경로로만 |

리포지토리는 세 개 모두 `JpaRepository<T, Integer>` 이고 파생 쿼리 메서드만 쓴다. 트랜잭션 경계는 `*ServiceImpl` 클래스 레벨 `@Transactional` 이며, **수정은 `save()` 없이 더티 체킹에 의존**한다.

MySQL 스키마의 기준은 JPA 엔티티 매핑이고, `ddl-auto` 는 로컬·운영 모두 `none` 이라 **앱이 테이블을 만들거나 바꾸지 않는다.** Flyway·Liquibase 도 의도적으로 도입하지 않았다 ([decision/0011](../decision/0011-jpa-manages-schema.md)). 엔티티를 고치면 DDL 은 직접 실행해야 한다. 버전 관리되는 DDL 은 `api/clickhouse/init/01-schema.sql` 하나뿐이다 — [persistence.md](persistence.md) 참고.

## 분석 파이프라인

Spring 은 이 경로에 참여하지 않는다. `api/src` 전체에 `kafka` 문자열이 0건이고 Kafka 클라이언트 의존성도 없다. 현재 이벤트를 넣는 경로는 `api/clickhouse/verify.sh` 와 수동 `kafka-console-producer.sh` 뿐이다.

```
프로듀서 (미구현)
  │ JSONEachRow
  ▼
Kafka  mupid.listen.events        파티션 6, 복제 1
  ▼
kafka_listen_events               Kafka 엔진, ON CLUSTER mupid
                                  노드당 kafka_num_consumers=3 × 2노드 = 컨슈머 6
  ▼  mv_kafka_to_events           toUUIDOrZero / parseDateTime64BestEffort
listen_events_local               ReplicatedReplacingMergeTree, 경로에 {shard}
  ▼  mv_user_section_signals      dictGet(dict_track_sections, ...)
user_section_signals_local        ReplicatedMergeTree

play_section_coverage_local / rec_impressions_local 은 이 연쇄 밖. 직접 INSERT 를 받는다.
```

같은 두 노드를 두 벌의 클러스터로 정의한다(`api/clickhouse/server/00-cluster.xml`).

| 클러스터 | 토폴로지 | Keeper 경로 | 대상 |
|---|---|---|---|
| `mupid` | 2 샤드 × 1 레플리카, `cityHash64(user_id)` 분산 | `/clickhouse/tables/{shard}/...` | `listen_events`, `user_section_signals`, `play_section_coverage`, `rec_impressions` |
| `mupid_all` | 1 샤드 × 2 레플리카 (전 노드 복제) | `/clickhouse/tables/track_sections` (`{shard}` 없음) | `track_sections`, `dict_track_sections` |

`mv_user_section_signals` 가 **각 노드에서** `dictGet` 을 호출하므로 구간 데이터만 전 노드에 있어야 한다. 클러스터를 둘로 나눈 이유가 이것 하나다.

Spring 쪽 연결은 `clickHouseJdbcTemplate` 빈 하나뿐이다. JDBC 드라이버는 문자열로만 참조하므로 `runtimeOnly 'com.clickhouse:clickhouse-jdbc:0.9.0'` 로 선언한다.

## 네트워크 토폴로지

망은 어디서나 두 개다. **nginx 는 `backend` 에 붙지 않고, `api` 만 두 망에 동시에 속한다.**

### 운영 (`infra/deploy/compose.yml`, `name: mupid`)

```
     인터넷
       │ 80 / 443                          (호스트에 열리는 유일한 포트)
       ▼
 ┌──────────────┐
 │ mupid-nginx  │  network: frontend  (mupid-frontend)
 └──────┬───────┘
        │ api:8800
 ┌──────▼───────┐
 │  mupid-api   │  network: frontend + backend      mem_limit 2g
 └──────┬───────┘
        │ network: backend  (mupid-backend)
   ┌────┴─────┬──────────────┬─────────────────────┐
   ▼          ▼              ▼                     ▼
 mysql      redis      clickhouse           clickhouse-keeper / kafka
(포트 없음) (포트 없음)  profile: analytics    profile: keeper / kafka
                        9000·9009 호스트 게시   9181 · 9092 호스트 게시
```

MySQL·Redis 는 `ports` 를 쓰지 않는다(`api/compose.prod.yml` 은 `expose` 만). 분석 스택만 호스트에 포트를 게시하는데, Docker bridge 네트워크가 호스트를 넘지 못해 서버 2대가 서로를 찾을 수 없기 때문이다. 이 포트들은 보안그룹에서 상대 서버 IP 에만 열어야 한다.

서버 역할은 환경변수로만 나눈다.

| | 서버 A | 서버 B |
|---|---|---|
| `COMPOSE_PROFILES` | `analytics,keeper` | `analytics,kafka` |
| ClickHouse | `CH_SHARD=01` / `CH_REPLICA=ch1` | `CH_SHARD=02` / `CH_REPLICA=ch2` |
| 추가 | Keeper | Kafka |

### 개발 (`api/compose.yml`, `name: mupid-dev`)

Spring 은 컨테이너에 넣지 않는다. 호스트에서 `./gradlew bootRun` 으로 띄우고 nginx 가 게이트웨이를 통해 그쪽을 본다.

```
  호스트 프로세스: Spring  :8800   (bootRun / IDE)
        ▲
        │ API_UPSTREAM = host.docker.internal:${MUPID_PORT:-8800}
        │ extra_hosts: host.docker.internal:host-gateway
  ┌─────┴──────────────┐
  │ mupid-dev-nginx    │  :8080   network: frontend (mupid-dev-frontend)
  └────────────────────┘

  network: backend (mupid-dev-backend)   — 호스트 포트를 전부 게시한다
    mysql   13306→3306      redis  16379→6379
    storage 8333 / 9333 / 8888          (SeaweedFS, 포트 하드코딩)
    ch-keeper                            ch-01 18123·19000    ch-02 18124·19001
    kafka   19092   (alias kafka1, kafka2)
    kafka-ui 18080  (profile: ui)
    1회성: storage-init · kafka-init · clickhouse-init  →  Exited (0) 이 정상
```

개발 nginx 에는 `depends_on` 이 없다. 기다릴 컨테이너가 없기 때문이다. 그래서 `docker compose up -d` 직후 nginx 자체는 healthy 인데 `/api/...` 는 502 일 수 있다.

## 개발 / 운영 환경 차이

compose 파일이 네 벌이고 이름이 헷갈리기 쉽다.

| 항목 | `api/compose.yml` | `api/compose.prod.yml` | `api/compose.ci.yml` | `infra/deploy/compose.yml` |
|---|---|---|---|---|
| 프로젝트명 | `mupid-dev` | `mupid` | (디렉터리명) | `mupid` |
| 용도 | 로컬 개발 | 단일 호스트 운영 · 최초 구축 | 격리 통합테스트 | **실제 EC2 배포** |
| Spring | 호스트에서 직접 실행 | `build:` + `mupid-api:latest` | 없음(gradle 컨테이너) | `image: ${MUPID_API_IMAGE:?}` |
| nginx 설정 | 템플릿 + envsubst | 템플릿 + envsubst | — | `/srv/mupid/config/nginx.conf` 통째 마운트 |
| TLS | 없음 | 없음 | — | 443 + letsencrypt |
| 정적 프런트 | 없음 | 없음 | — | `/srv/web/current` |
| 분석 스택 | 전부 포함 | 없음(주석으로 별도 호스트 명시) | 없음 | profile 뒤(`analytics`/`keeper`/`kafka`/`init`) |
| 호스트 포트 | 다수 | nginx 만 | 없음 | 80·443 + 분석 포트 |
| 볼륨 / 네트워크 | compose 생성 | compose 생성 | tmpfs + gradle 캐시 | 전부 `external: true` |
| 로그 회전 | 지정 없음 | 지정 없음 | 없음 | `x-logging` 앵커로 10m × 3 |
| DB 계정 | root 하나 | root 하나 | CI 전용 평문 상수 | root 와 앱 계정 분리 |

세 가지가 특히 중요하다.

- **`compose.prod.yml` 은 실제 배포 파일이 아니다.** 두 파일 다 `name: mupid` 이고 컨테이너 이름도 같아 부트스트랩 스택을 그대로 인계받지만, 배포 엔드포인트(`infra/deploy/receive.sh`)가 쓰는 것은 `/srv/mupid/config/compose.yml`(= `infra/deploy/compose.yml`)이다.
- **nginx 설정 "한 벌 공유" 는 운영까지 가지 않는다.** `api/nginx/templates/default.conf.template` 은 `API_UPSTREAM` 하나만 바꾸는 구조지만, 실제 EC2 는 TLS·308 리다이렉트·ACME·정적 `try_files` 가 들어간 `infra/deploy/nginx.conf` 를 통째로 마운트한다. 서버 설정 파일은 root 가 관리하고 CI 가 교체하지 않는다.
- **`api/compose.ci.yml` 은 어디서도 참조되지 않는다.** `infra/ci/backend.yml` 은 `bash gradlew build -x test` 만 돌린다(팀 결정으로 테스트 미실행). `infra/ci/wait-services.sh` 도 같은 상태다.

## 배포 경로

레지스트리를 쓰지 않는다. 검증한 JAR 을 그대로 이미지로 굽고, SSH stdin 으로 서버에 밀어 넣는다.

```
GitLab CI  (.gitlab-ci.yml → infra/ci/{backend,frontend,release}.yml)

 verify   backend-verify   gradlew build -x test  →  api/build/release/app.jar (artifact)
          frontend-verify  npm ci + npm run build →  frontend/dist/client
             │
 package  backend-package  rootless BuildKit + infra/image/Dockerfile
          frontend-package tar.gz
             │  GitLab Generic Packages  (커밋 SHA 별, SHA256 동봉)
             ▼
 deploy   ssh mupid-deploy@… "deploy <40 SHA> <64 체크섬>" < image.tar.gz
             │  forced command → /usr/local/sbin/mupid-receive (root)
             ▼
   flock → 크기·SHA256·gzip·manifest RepoTags 검증 → docker load
        → up -d --wait api
        → up -d --no-deps --force-recreate nginx     ← 새 api 컨테이너 주소 재해석
        → curl --resolve …:443:127.0.0.1 /api/actuator/health 가 "status":"UP"
        → 실패하면 previous-image 로 같은 절차 재실행 (자동 롤백)
```

Dockerfile 이 두 개인 것은 여기서 나온다.

| | `api/Dockerfile` | `infra/image/Dockerfile` |
|---|---|---|
| 스테이지 | 2단계 (jdk 빌드 → jre 런타임) | 1단계 (jre) |
| jar | 컨테이너 안에서 `./gradlew bootJar -x test` | 컨텍스트의 `app.jar` 를 `COPY` |
| 쓰는 곳 | 로컬 · `compose.prod.yml` | **CI** (`infra/ci/package-image.sh`) |

`api/Dockerfile` 만 고치면 배포 이미지는 바뀌지 않는다.

nginx 를 매번 `--force-recreate` 하는 것은 버그 회피가 아니라 설계다. `upstream mupid_api { server api:8800; }` 는 기동 시점에 이름을 한 번만 해석하므로, API 컨테이너를 교체하면 nginx 가 사라진 IP 를 계속 붙든다.

## 설계 근거

- **nginx 를 유일한 노출 지점으로 둔다.** MySQL·Redis 는 `ports` 자체를 쓰지 않는다. 방화벽 규칙이 아니라 토폴로지로 격리한다 — Docker 가 게시한 포트는 UFW 를 우회하기 때문이다.
- **개발에서 Spring 을 컨테이너에 넣지 않는다.** `bootRun` 핫리로드와 IDE 디버깅을 유지하면서 nginx 경유 경로(`/api/` 프록시, `/healthz`)를 그대로 재현한다. 개발 스택에 `api` 서비스를 추가하지 않는 것이 규칙이다(`api/AGENTS.md`).
- **`/api/actuator/health` 의 의미 범위를 좁게 유지한다.** 이 헬스체크가 컨테이너 `HEALTHCHECK` → nginx `depends_on: service_healthy` → 배포 성공 판정까지 세 곳에 걸려 있다. 그래서 `ClickHouseConfig` 는 `HikariDataSource` 를 빈으로 노출하지 않는다. `DataSource` 빈마다 헬스 컨트리뷰터가 붙어 분석 DB 장애가 서비스 전체 다운으로 번지기 때문이다. 같은 이유로 `infra/deploy/compose.yml` 의 `api` 에 분석 스택 `depends_on` 을 걸지 않는다.
- **ClickHouse 풀은 지연 초기화한다.** `new HikariDataSource(HikariConfig)` 는 풀을 즉시 띄워 ClickHouse 가 없으면 기동이 실패한다. 기본 생성자 + setter 로 만들어 첫 `getConnection()` 때 초기화되게 했다.
- **ClickHouse 를 JPA 밖에 둔다.** 엔티티/트랜잭션 모델이 맞지 않고 쓰는 쿼리도 집계와 대량 INSERT 뿐이라 `JdbcTemplate` 으로 충분하다는 판단이 `ClickHouseConfig` 주석에 있다.
- **Kafka 는 1대다.** 2대는 KRaft 컨트롤러 쿼럼이 2가 되어 장애를 한 대도 못 견딘다. HA 는 3대부터라 2대는 리소스만 두 배 쓴다.
- **DDL 원본을 환경별로 갈라놓지 않는다.** `kafka_broker_list = 'kafka1:9092,kafka2:9092'` 를 고치지 않으려고 로컬은 컨테이너 하나에 alias 두 개를 붙이고, 운영은 `extra_hosts` 로 두 이름을 같은 주소에 매핑한다.
- **ClickHouse 설정 파일은 로컬과 운영이 동일하다.** 차이는 `from_env` 환경변수(`CH_SHARD`, `CH_REPLICA`, `CH_HOST_*`, `CH_KEEPER_HOST` …)로만 낸다. 예외는 Keeper 노드 수가 가변이라 환경변수 하나로 표현할 수 없는 두 파일이다 — `keeper/keeper_config.xml`, 그리고 `server/01-keeper.xml`. 후자는 운영에서 `<zookeeper>` 블록의 `<node>` 를 Keeper 노드 수(3 이상 홀수)만큼 늘려 써야 한다. 현재는 `CH_KEEPER_HOST` 하나뿐이다.
- **S3 인터페이스를 유지한다.** 개발은 SeaweedFS 지만 AWS SDK v2 `S3Client` 로만 다루고, `endpoint` 가 비어 있으면 `endpointOverride` 를 걸지 않는다. 개발/운영 교체가 `MUPID_S3_ENDPOINT` 한 줄이다.
- **모든 환경변수는 `MUPID_` 접두사를 쓴다.** `api/AGENTS.md` 의 규칙은 "`application.yaml` 은 값을 직접 적지 않고 `${MUPID_...}` 로만 참조한다" 이다. 예외는 ClickHouse 노드 정체성 변수(`CH_*`)뿐인데, XML 이 `from_env` 로 직접 읽는 값이라 계열을 나눴다. 실제 `application.yaml` 에는 아직 리터럴이 남아 있다 — `aws.s3.region: "SeaweedFS"`, `spring.data.redis.timeout`, `clickhouse.max-pool-size`·`min-idle`·`connection-timeout`·`validation-timeout`, `management.*`. 환경별로 갈릴 값은 `aws.s3.region` 뿐이다.
- **검증한 산출물과 배포 산출물을 같게 만든다.** CI 에는 Docker 소켓이 없어 rootless BuildKit 을 쓰고, verify 가 만든 `app.jar` 를 다시 빌드하지 않고 그대로 감싼다.

## 바꿀 때 지켜야 할 것

- nginx 를 `backend` 망에 붙이지 않는다. 새 서비스는 `api` 만 접근하면 되는지 먼저 판단한다.
- 개발 스택(`api/compose.yml`)에 `api` 서비스를 추가하지 않는다.
- 호스트 게시 포트는 `.env` 변수로 뺀다. 현재 예외는 `storage`(SeaweedFS) 뿐이다.
- 새 설정을 추가하면 `application.yaml`(`${MUPID_...}`) · `api/.env.example` · `infra/deploy/compose.yml` 의 `api.environment` 를 함께 갱신한다. 운영 compose 의 `api` 에는 `env_file` 이 없어 **열거된 키만** 컨테이너로 들어간다.
- `/api/actuator/health` 에 새 의존성을 얹지 않는다. `DataSource` 타입 빈을 추가하면 자동으로 얹힌다.
- `JdbcTemplate` 빈을 늘릴 때 `common/config/JdbcTemplateConfig` 의 `@Primary` 관계를 깨지 않고, 주입 지점에 `@Qualifier` 를 명시한다. `api/lombok.config` 를 지우거나 옮기지 않는다.
- ClickHouse XML 은 파일 단위로 마운트한다. 파일이 늘면 compose 의 `volumes` 에 한 줄씩 추가한다.
- 서버 레벨 설정은 `config.d`, 쿼리(user) 레벨 설정은 `users.d` 의 `<profiles><default>` 에 넣는다. 잘못 두면 서버가 기동하지 않는다.
- 운영 볼륨은 `external: true` 다. `down -v` 와 `docker volume prune` 은 금지다.
- 이미지 롤백은 DB 스키마를 되돌리지 않는다. 스키마 변경은 앞뒤 버전 모두에서 동작하도록 나눠 배포한다.
- 분석 스택을 고쳤으면 `docker compose up -d --wait` 후 `cd api && ./clickhouse/verify.sh` 로 25개 항목을 직접 확인한다. CI 는 이 영역을 검증하지 않는다.

## 이 영역의 함정

상세는 [함정과 실패 사례](../failure/README.md) 에 있다. 아키텍처 경계와 직접 맞물린 것만 적는다.

- `api/nginx/templates/default.conf.template` 상단 주석이 존재하지 않는 `compose.dev.yml` 을 가리키며 개발/운영 매핑을 **반대로** 적고 있다. 업스트림은 각 compose 파일에서 직접 확인한다.
- `api/README.md` 의 디렉터리 구조는 도메인 패키지가 하나도 없는 초기 상태 그대로다. 구조 파악은 소스 트리로 한다.
- `api/clickhouse/README.md` 는 "운영 배포 파일에는 ClickHouse / Kafka 서비스가 아직 없다"고 단언하지만 `infra/deploy/compose.yml` 에는 profile 뒤에 모두 정의돼 있다. 다만 "기본 배포로는 안 뜬다"는 취지는 유효하다.
- `infra/deploy/compose.yml` 의 분석 변수는 빈 기본값(`${CH_SHARD:-}`)이라 값을 빠뜨려도 compose 가 통과한다. 올린 뒤 `system.macros` 를 반드시 확인한다.
- `MUPID_KAFKA_BOOTSTRAP` 과 `redis.key-prefix` 는 선언만 있고 읽는 코드가 없다. 이 값들을 근거로 동작을 가정하지 않는다.
- `api/clickhouse/{server,users,keeper,init,kafka}` 를 서버 `/srv/mupid/config/clickhouse/` 로 복사하는 자동화가 없다. 저장소를 고쳐도 서버에 반영되지 않는다.
- CI 초록불은 기능 검증을 뜻하지 않는다. 백엔드 테스트는 실행되지 않고, 유일한 테스트도 `contextLoads` 하나다.
