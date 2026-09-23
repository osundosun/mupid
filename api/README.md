# Mupid API Server

특화프로젝트 백엔드 API 서버.

## 기술 스택

| 구분 | 사용 |
|---|---|
| 언어 / 런타임 | Java 21 (Azul Zulu) |
| 프레임워크 | Spring Boot 4.1.2-SNAPSHOT |
| 빌드 | Gradle 9.7.1 (Wrapper) |
| RDBMS | MySQL 8.4 |
| 캐시 | Redis 7.4 |
| 리버스 프록시 | nginx 1.27 |
| 인증 | Spring Security + JJWT 0.12.5 |

주요 스타터: `actuator`, `batch`, `batch-jdbc`, `data-jpa`, `data-redis`, `security`, `validation`, `webmvc`

> Spring Boot 4.1.2 는 아직 SNAPSHOT 이라 `repo.spring.io/snapshot` 저장소를 사용한다.
> `build.gradle` · `settings.gradle` 양쪽에 등록되어 있으므로 지우지 말 것.

## 디렉터리 구조

```
api/
├── src/main/java/com/osundosun/mupid/api/
│   ├── ApiApplication.java              # 진입점
│   └── common/config/SecurityConfig.java # 시큐리티 필터 체인
├── src/main/resources/
│   ├── application.yaml                 # 서버/DB/Redis/Actuator/JWT 설정
│   └── META-INF/
│       └── additional-spring-configuration-metadata.json
├── src/test/java/…                      # JUnit 5
├── docs/                                # Bruno(opencollection) API 컬렉션
│   ├── opencollection.yml
│   └── Actuator/헬스체크.yml
├── nginx/templates/default.conf.template # 개발·운영 공용 nginx 설정
├── compose.yml                          # 개발용 (스프링은 호스트에서 실행)
├── compose.prod.yml                     # 운영용 (스프링까지 컨테이너)
├── Dockerfile                           # 멀티스테이지 (zulu jdk → jre)
├── .env / .env.example
└── build.gradle · settings.gradle · gradlew
```

## 시작하기

### 0. 환경 변수

```bash
cp .env.example .env
# MUPID_MYSQL_* / MUPID_REDIS_* / MUPID_JWT_SECRET 채우기
# JWT 시크릿 생성: openssl rand -base64 32
```

`application.yaml` 이 `spring.config.import: optional:file:.env[.properties]` 로 `.env` 를 직접 읽으므로,
IDE 에서 실행할 때 별도 플러그인 없이 값이 주입된다.

### 1. 개발 — 스프링은 호스트에서 직접 실행

인프라(MySQL·Redis)와 nginx 만 컨테이너로 띄우고, 스프링은 IDE 나 Gradle 로 돌린다.

```bash
docker compose up -d      # mysql, redis, nginx
./gradlew bootRun         # 또는 VS Code "ApiApplication" 실행 구성
```

nginx 가 `host.docker.internal:8800` 으로 프록시하므로, 운영과 **동일한 경로**로 확인할 수 있다.

```bash
curl http://localhost:8080/api/actuator/health   # nginx 경유
curl http://localhost:8800/api/actuator/health   # 스프링 직접
```

### 2. 운영 — 전부 컨테이너

```bash
docker compose -f compose.prod.yml up -d --build
curl http://localhost:8080/api/actuator/health
```

MySQL·Redis 는 호스트로 포트를 열지 않는다. 직접 붙어야 할 때는 `exec` 로 들어간다.

```bash
docker compose -f compose.prod.yml exec mysql mysql -uroot -p mupid
docker compose -f compose.prod.yml exec redis redis-cli
```

### 정리

```bash
docker compose down                        # 개발
docker compose -f compose.prod.yml down    # 운영
# 데이터까지 지우려면 -v
```

## 구성 비교

| | `compose.yml` (개발) | `compose.prod.yml` (운영) |
|---|---|---|
| Compose 프로젝트명 | `mupid-dev` | `mupid` |
| 스프링 | **호스트에서 직접 실행** | `api` 컨테이너 |
| nginx 업스트림 | `host.docker.internal:8800` | `api:8800` |
| MySQL / Redis | 호스트에 포트 노출 | 내부 전용 (`expose`) |
| 호스트에 열리는 포트 | nginx, MySQL, Redis | **nginx 뿐** |

프로젝트명이 달라 두 스택을 동시에 띄워도 컨테이너 이름은 충돌하지 않는다(포트는 충돌).

## 네트워크

두 구성 모두 네트워크를 둘로 나눈다.

- `frontend` — nginx ↔ api
- `backend` — api ↔ mysql / redis (**nginx 는 붙지 않는다**)

## 환경 변수

| 변수 | 설명 | 예시 |
|---|---|---|
| `MUPID_PORT` | 스프링 서버 포트 | `8800` |
| `MUPID_NGINX_PORT` | nginx 를 호스트에 노출할 포트 | `8080` |
| `MUPID_MYSQL_PORT` | 개발용 MySQL 호스트 포트 | `13306` |
| `MUPID_MYSQL_URL` | JDBC URL | `jdbc:mysql://localhost:13306/mupid` |
| `MUPID_MYSQL_USER` | DB 계정 | `root` |
| `MUPID_MYSQL_PASSWORD` | DB 비밀번호 · MySQL 컨테이너 root 비밀번호 | |
| `MUPID_REDIS_HOST` | Redis 호스트 | `localhost` |
| `MUPID_REDIS_PORT` | Redis 포트 | `16379` |
| `MUPID_JWT_SECRET` | JWT 서명 키 (Base64 32바이트) | `openssl rand -base64 32` |

운영 compose 는 `.env` 를 읽은 뒤 컨테이너 네트워크 기준으로 `MUPID_MYSQL_URL`,
`MUPID_REDIS_HOST`, `MUPID_REDIS_PORT` 를 덮어쓴다.

## 포트

| 대상 | 개발 | 운영 |
|---|---|---|
| nginx | 8080 | 8080 |
| API | 8800 (호스트 프로세스) | 내부 8800 |
| MySQL | 13306 → 3306 | 내부 3306 |
| Redis | 16379 → 6379 | 내부 6379 |

## API

- 진입 경로는 전부 `/api` 로 시작한다.
- Actuator 베이스 경로는 `/api/actuator`, 노출 엔드포인트는 `health` 뿐이다 (`show-details: never`, liveness/readiness probe 활성).
- nginx 자체 헬스체크는 백엔드와 무관하게 200 을 주는 `/healthz`.
- `docs/` 는 Bruno 로 열 수 있는 API 컬렉션이다. `BASE_URL` 변수 기본값은 `http://localhost:8800`.

### 인증

`SecurityConfig` 기준 — 세션은 STATELESS, CSRF·폼로그인·HTTP Basic 비활성.

| 경로 | 접근 |
|---|---|
| `/api/auth/**` | 허용 (로그인·회원가입) |
| `/api/actuator/**` | 허용 |
| 그 외 | 인증 필요 |

## 자주 쓰는 명령

```bash
./gradlew bootRun            # 실행
./gradlew build              # 빌드 + 테스트
./gradlew test               # 테스트만
./gradlew bootJar -x test    # jar 만
docker compose logs -f api   # 운영 스택 로그
```

## 주의

- `.env` 에는 DB 비밀번호와 JWT 시크릿이 들어간다. **커밋하지 말 것.** `api/.gitignore` 에 등록되어 있다.
- `Dockerfile` 의 베이스 이미지 `azul-zulu` 는 **Debian 기반**이다. `addgroup -S` 같은 Alpine(BusyBox) 문법은 쓸 수 없다.
