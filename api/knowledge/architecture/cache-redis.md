# Redis 캐시 계층

Redis 를 건드리는 작업 — 새 캐시 키를 추가하거나, 로그아웃/토큰 차단 동작을 바꾸거나, Redis 연결 설정과 compose 를 손볼 때 먼저 읽는다. 이 계층은 범용 캐시가 아니라 JWT 차단 목록(deny list) 하나를 위해 존재한다. "캐시니까 이럴 것이다" 라는 가정 — 기본 TTL 이 있다, 무효화 API 가 있다, 조회 결과가 캐싱된다, 인증코드가 여기 들어 있다 — 는 전부 어긋난다.

## 한눈에

| 질문 | 답 |
|---|---|
| 무엇이 들어 있나 | JWT 차단 목록 하나뿐. 조회 결과 캐싱은 없다 |
| Spring Cache 추상화 | 안 쓴다. `api/src/` 전체에 `@EnableCaching`·`@Cacheable` 0건 |
| 접근 경로 | `CacheService` 인터페이스 하나 |
| 내부 구현 | Boot 자동설정이 준 `StringRedisTemplate` 을 감싼 래퍼 |
| Redis 전용 `@Configuration` | 없다. `api/src/main/java/com/osundosun/mupid/api/common/config/` 에는 `SecurityConfig.java`, `JdbcTemplateConfig.java` 뿐 |
| TTL | 캐시 계층에 상수가 없다. 호출부가 `Duration` 으로 넘긴다 |
| 삭제 API | 없다. `delete`/`evict` 를 제공하지 않는다 |
| 의존성 | `api/build.gradle` 27행 `spring-boot-starter-data-redis` |

## 파일 배치

```
api/src/main/java/com/osundosun/mupid/api/cache/
├── enumerate/CacheKind.java      # 캐시 종류 = 키 접두사 enum
└── service/
    ├── CacheService.java         # 인터페이스 (set x4 / get / exists)
    └── CacheServiceImpl.java     # StringRedisTemplate 래퍼
```

`CacheService` 의 메서드는 6개다.

| 시그니처 | 동작 |
|---|---|
| `set(String key, CacheKind kind)` | 값 `""`, TTL 없음 |
| `set(String key, CacheKind kind, Duration ttl)` | 값 `""`, TTL 있음 |
| `set(String key, String value, CacheKind kind)` | TTL 없음 |
| `set(String key, String value, CacheKind kind, Duration ttl)` | TTL 있음 |
| `String get(String key, CacheKind kind)` | 값 조회 |
| `boolean exists(String key, CacheKind kind)` | `hasKey` 를 `Boolean.TRUE.equals(...)` 로 감싼 존재 확인 |

값 없는 `set` 오버로드가 두 개나 있고 `exists` 가 `get` 과 따로 있는 건 "값은 필요 없고 존재 여부만 본다"는 유일한 용도에 맞춘 결과다.

## 키 네이밍 규약

최종 키는 항상 `{CacheKind.redisKey}:{key}` 다. 접두사를 붙이는 지점은 `CacheServiceImpl` 의 private `buildKey` 하나뿐이다.

```java
// api/src/main/java/com/osundosun/mupid/api/cache/service/CacheServiceImpl.java
private String buildKey(String key, CacheKind kind) {
    return "%s:%s".formatted(kind.getRedisKey(), key);
}
```

`CacheKind` 는 `@Getter @RequiredArgsConstructor` enum 이고 필드는 `private final String redisKey` 하나다.

| 상수 | `redisKey` | 실제 Redis 키 | 값 |
|---|---|---|---|
| `BlockToken` | `block:token` | `block:token:<JWT 원문>` | 빈 문자열 |

지켜야 할 것.

- Redis 키 문자열을 코드에 직접 쓰지 않는다. `CacheKind` 상수로 추가한다. 접두사를 바꿔야 하면 enum 한 줄만 고치면 되는 구조를 유지하기 위한 것이다.
- 호출부에서 접두사를 또 붙이지 않는다. `buildKey` 가 이미 붙인다.
- `StringRedisTemplate` 을 도메인 코드에 직접 주입하지 않는다. 현재 주입처는 `CacheServiceImpl` 하나다.
- `CacheKind` 상수 이름은 이 파일의 기존 스타일인 PascalCase(`BlockToken`)를 따른다. `UserRole`·`UserStatus`·`EmailVerificationPurpose` 등 다른 enum 은 SCREAMING_SNAKE 라 파일 간 스타일이 다르다.

`application.yaml` 63행의 `redis.key-prefix` 는 **키에 붙지 않는다**. 어디에도 바인딩돼 있지 않은 죽은 설정이다 — [함정과 실패 사례](../failure/auth-and-api.md) 참고.

## TTL 정책

`CacheService`·`CacheServiceImpl` 어디에도 기본 TTL 상수가 없다. TTL 은 호출부가 정한다.

```java
// api/src/main/java/com/osundosun/mupid/api/auth/service/AuthServiceImpl.java
public void logout(List<String> tokens) {
    Instant now = Instant.now();
    for (String token : tokens) {
        Instant expiration = authProvider.parseExpiration(token).toInstant();
        cacheService.set(token, CacheKind.BlockToken, Duration.between(now, expiration));
    }
}
```

즉 **차단 엔트리의 수명 = 그 토큰의 남은 수명**이다.

| 대상 | TTL 계산 | 상한 |
|---|---|---|
| 액세스 토큰 | `Duration.between(now, 토큰 exp)` | `JwtProvider.ACCESS_TOKEN_VALIDITY_MS` = 1시간 |
| 리프레시 토큰 | 같음 | `JwtProvider.REFRESH_TOKEN_VALIDITY_MS` = 7일 |

이렇게 잡아 둔 덕분에 토큰이 자연 만료되면 차단 엔트리도 같이 사라진다. 목록이 무한히 자라지 않고 청소 배치도 필요 없으며, 그래서 `delete`/`evict` 가 없어도 성립한다. 뒤집으면 **한 번 차단한 토큰은 애플리케이션 코드로 되돌릴 수 없다**. 잘못 넣었으면 `redis-cli` 로 직접 지워야 한다.

## 사용처

`CacheService` 를 주입받는 곳은 저장소 전체에서 두 군데다.

| 파일 | 호출 | 동작 |
|---|---|---|
| `api/src/main/java/com/osundosun/mupid/api/auth/filter/AuthFilter.java` | `exists(accessToken, BlockToken)` | 매 요청. 차단됐으면 `SecurityContext` 를 채우지 않고 체인을 그대로 넘긴다 → `SecurityConfig` 의 `anyRequest().authenticated()` 에서 401 |
| `api/src/main/java/com/osundosun/mupid/api/auth/service/AuthServiceImpl.java` | `exists(refreshToken, BlockToken)` | `reissueAccessToken` 에서 차단이면 `AuthErrorCode.UNAUTHORIZED` |
| 〃 | `set(token, BlockToken, ttl)` | `logout` 에서 토큰별로 등록 |

진입점은 `api/src/main/java/com/osundosun/mupid/api/auth/controller/AuthController.java` 의 `logout` 이다. `@DeleteMapping` 이고 액세스 토큰은 `auth.getCredentials()`, 리프레시 토큰은 `@CookieValue(name = "refresh_token")` 에서 꺼내 `List.of(accessToken, refreshToken)` 로 함께 차단한다.

**왜 이런 게 필요한가**: `SecurityConfig` 가 `SessionCreationPolicy.STATELESS` 로 세션을 버리고 JWT 만 쓰기 때문에, 서버가 발급한 토큰을 만료 전에 무효화할 다른 수단이 없다. Redis 는 그 무효화 목록을 담으려고 들어와 있다.

**이메일 인증코드는 Redis 에 없다.** `api/src/main/java/com/osundosun/mupid/api/email/entity/EmailVerification.java` 는 `@Entity @Table(name = "email_verifications")` 로 MySQL 테이블이고, 만료는 TTL 이 아니라 `expiredAt` 컬럼과 `EmailServiceImpl` 의 `EMAIL_VERIFICATION_EXPIRED_MINUTES = 15` / `EMAIL_VERIFICATION_VALID_EXPIRATION_HOURS = 1` 비교로 판정한다. 인증코드 버그를 Redis 에서 찾지 말 것.

## 직렬화와 연결 설정

커스텀 `RedisTemplate` 빈도, Jackson serializer 등록도, `RedisConnectionFactory`/`LettuceConnectionFactory` 를 직접 만드는 코드도 없다. `spring-boot-starter-data-redis` 자동설정이 만든 `StringRedisTemplate` 을 그대로 쓰므로 키·값 직렬화는 문자열(`StringRedisSerializer`)이다.

그래서 `redis-cli` 로 키를 바로 읽고 지울 수 있고, 저장하는 값이 JWT 문자열과 빈 문자열뿐이라 JSON serializer 를 붙일 이유가 없었다. 대신 **문자열만 넣을 수 있다** — 객체를 캐싱하려면 호출부가 직접 직렬화하거나 설정 클래스를 새로 만들어야 한다.

설정 값은 `api/src/main/resources/application.yaml` 에만 있다. 프로파일별 yaml(`application-*.yaml`)은 없다.

```yaml
  data:
    redis:
      host: ${MUPID_REDIS_HOST}     # 16행
      port: ${MUPID_REDIS_PORT}     # 17행
      timeout: 3000ms               # 18행
```

password·ssl·pool 설정은 없다. 호스트·포트는 **기본값 없이** 참조하므로(`MUPID_PORT` 가 `:8800` 기본값을 가진 것과 대조적이다) 두 값이 비면 애플리케이션이 뜨지 않는다. IDE·CI·임시 컨테이너 등 새 실행 환경을 만들 때 반드시 주입한다.

| yaml | 환경변수 | `api/.env.example` | 운영 override |
|---|---|---|---|
| `spring.data.redis.host` | `MUPID_REDIS_HOST` | 빈 값 (개발은 `localhost`) | `redis` |
| `spring.data.redis.port` | `MUPID_REDIS_PORT` | `16379` | `6379` |
| `redis.key-prefix` | `MUPID_REDIS_KEY_PREFIX` | 빈 값 (개발은 `mupid:dev`) | 설정 안 함 |

운영 override 는 `api/compose.prod.yml` 의 api 서비스 `environment`(42-43행)와 `infra/deploy/compose.yml` 이 준다. **이 덮어쓰기를 지우면 안 된다** — 운영에서 redis 는 호스트 포트를 열지 않아 16379 로는 붙을 수 없다.

## compose 의 redis 서비스

이미지는 네 파일 모두 `redis:7.4-alpine`, healthcheck 도 모두 `["CMD", "redis-cli", "ping"]` 이다. 차이는 노출 방식·볼륨·AOF 다.

| 파일 | 컨테이너명 | 노출 | 볼륨 | `--appendonly yes` |
|---|---|---|---|---|
| `api/compose.yml` (개발, 50-65행) | `mupid-dev-redis` | `"${MUPID_REDIS_PORT:-16379}:6379"` | `mupid-dev-redis-data` | 있음 |
| `api/compose.prod.yml` (운영, 84-99행) | `mupid-redis` | `expose: "6379"` | `mupid-redis-data` | 있음 |
| `infra/deploy/compose.yml` (배포, 68-80행) | `mupid-redis` | 없음(`backend` 내부만) | `external: true, name: mupid-redis-data` | 있음 |
| `api/compose.ci.yml` (17-23행) | — | 없음 | 없음 | **없음** (`command` 자체가 없다) |

- 개발은 스프링을 호스트에서 직접 돌리므로 포트를 게시해야 한다. 운영은 `expose` 만 하고 `backend` 네트워크에만 붙인다(`api/AGENTS.md` 의 규칙과 같다. nginx 는 `frontend`).
- compose 에 포트 숫자를 박지 않고 `${MUPID_REDIS_PORT:-16379}` 로 쓴다.
- `--appendonly yes` 로 AOF 를 켜 둔 것은 재시작 때 차단 목록이 날아가면 로그아웃한 토큰이 되살아나기 때문이다. 다만 `infra/OPERATIONS.md` 는 현재 MySQL 만 백업하고 Redis AOF 는 백업 범위 밖이라고 명시한다.
- `maxmemory`·`maxmemory-policy`·`requirepass` 는 어느 파일에도 없다.
- `infra/README.md` 24행과 `infra/OPERATIONS.md` 60행은 **서버에서** DB/Redis 볼륨을 지우는 `down -v` 와 `docker volume prune` 을 금지한다. `api/AGENTS.md` 87행의 `down -v` 는 로컬 검증 스택 정리용이니 대상을 혼동하지 말 것.

## 새 캐시 항목 추가 절차

1. `api/src/main/java/com/osundosun/mupid/api/cache/enumerate/CacheKind.java` 에 상수를 추가한다. PascalCase 이름 + 콜론으로 계층을 나눈 접두사(`block:token` 형태). 접두사 문자열은 이 파일 밖으로 내보내지 않는다.
2. 저장 방식을 고른다.
   - 존재 여부만 필요 → `set(key, kind, ttl)` + `exists`
   - 값이 필요 → `set(key, value, kind, ttl)` + `get`
3. **TTL 을 반드시 넘긴다.** TTL 없는 오버로드를 쓰면 키가 영구히 남고, 지울 API 가 없어 `redis-cli` 로 손봐야 한다.
4. 문자열이 아닌 값을 넣어야 하면 호출부에서 직렬화하거나 `CacheService` 시그니처부터 바꾼다. 설정 클래스를 새로 만든다면 자동설정을 밀어내는 부작용을 확인한다(`JdbcTemplateConfig` 사례).
5. 삭제가 필요한 용도라면 `CacheService` 인터페이스에 `delete` 를 먼저 추가한다. 지금은 없다.
6. 설정 키를 새로 추가한다면 `api/.env.example` 의 `# Redis 연결` 섹션도 같이 갱신하고, 커스텀 프로퍼티는 `api/src/main/resources/META-INF/additional-spring-configuration-metadata.json` 에 메타데이터를 넣는다(`api/AGENTS.md` 규칙). 참고로 `redis.key-prefix` 는 이 메타데이터에 없다 — 현재 등록된 건 `jwt.secret`, `gmail.client-id`, `gmail.client-secret` 셋뿐이다.
7. 환경변수는 `MUPID_` 접두사를 쓴다. `SPRING_DATA_REDIS_HOST` 같은 스프링 관례 변수로 우회하지 않는다.

### 검증

CI 초록불은 근거가 되지 않는다. `infra/ci/backend.yml` 은 첫 줄에 build-only 라고 적혀 있고 실제 script 도 `bash gradlew --no-daemon --console=plain build -x test` 다. redis 를 띄우는 `api/compose.ci.yml` 과 `infra/ci/wait-services.sh`(`for target in mysql:3306 redis:6379`)는 어떤 CI yml 도 참조하지 않으며, 테스트도 `ApiApplicationTests` 하나뿐이다. 로컬에서 직접 확인한다.

```bash
# 작업 디렉터리는 api/
docker compose up -d redis                     # 개발 스택
docker compose exec redis redis-cli keys 'block:token:*'
docker compose exec redis redis-cli ttl 'block:token:<JWT>'

# 운영 스택 (호스트 포트가 없으므로 exec 로 들어간다)
docker compose -f compose.prod.yml exec redis redis-cli
```

## 이 영역의 함정

상세는 [함정과 실패 사례](../failure/auth-and-api.md) 에 있다. 제목만 적어 둔다.

- `redis.key-prefix` 는 아무도 읽지 않는다. 접두사로 환경을 분리하려 하면 안 된다.
- `api/.env.example` 의 줄 끝 `#` 주석은 properties 문법에서 값에 섞인다. Redis 섹션도 아직 그 형식이다.
- `MUPID_REDIS_PORT` 의 의미가 개발(호스트 게시 포트)과 운영(컨테이너 내부 포트)에서 다르다.
- `MUPID_REDIS_HOST`/`MUPID_REDIS_PORT` 는 기본값이 없어 없으면 기동 자체가 실패한다.
- Redis 가 끊기면 `/api/actuator/health` 가 503 → api 컨테이너 unhealthy → nginx 가 아예 안 뜬다.
- 차단 목록은 애플리케이션 코드로 되돌릴 수 없다(최대 7일).
- 만료된 리프레시 토큰으로 로그아웃하면 `ExpiredJwtException` 이 `@ExceptionHandler(Exception.class)` 로 떨어져 500 이 난다.
- 문자열만 캐싱할 수 있다.
- Redis 연동을 검증하는 테스트가 파이프라인에서 돌지 않는다.
- 이메일 인증코드는 Redis 가 아니라 MySQL 이다.
