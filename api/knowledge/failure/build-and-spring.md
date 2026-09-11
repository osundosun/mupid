# 빌드 · Gradle · Spring Boot 4 · Lombok

12건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## 빌드 / Gradle / Spring Boot 4

### 호스트에서 `./gradlew` 가 조용히 실패한다

- **원인**: 이 개발 머신에 JDK 21 이 없다. Git Bash 에서 `java: command not found` 이고 `JAVA_HOME` 도 비어 있다. `api/AGENTS.md` 는 `./gradlew build` 를 기본 명령으로 안내하지만 그대로 되지 않는다.
- **해결**: 컨테이너에서 빌드한다. `docker compose -f compose.prod.yml up -d --build` (멀티스테이지 `api/Dockerfile`) 후 `curl http://localhost:8080/api/actuator/health` 로 `{"status":"UP"}` 확인.
- **근거**: `api/AGENTS.md`, `api/Dockerfile`

### `spring-boot-starter-web` 을 추가했는데 해석되지 않는다

- **원인**: Boot 4 는 스타터 이름이 바뀌었다. 웹은 **`spring-boot-starter-webmvc`** 이고, 테스트는 `spring-boot-starter-test` 하나가 아니라 `spring-boot-starter-validation-test`, `spring-boot-starter-actuator-test` 처럼 모듈별로 나뉜다. 3.x 문서의 의존성 블록을 그대로 옮기면 깨진다.
- **해결**: `api/build.gradle` 의 기존 항목을 보고 이름을 맞춘다. 의존성은 기존 주석 그룹(`// SpringBoot 관련`, `// JWT 관련` …)에 넣는다.
- **근거**: `api/build.gradle`, `api/AGENTS.md`

### `compileJava` 는 통과하는데 CI 가 `bootJar` 에서 의존성 충돌로 죽는다

- **원인**: `compileJava` 는 `:compileClasspath` 만 해석한다. `clickhouse-jdbc` 처럼 `runtimeOnly` 로 붙은 것은 거기 없으므로, 런타임 의존성끼리의 충돌은 로컬에서 `compileJava` 만 돌리면 절대 보이지 않는다. CI 는 `bash gradlew --no-daemon --console=plain build -x test` 를 돌고 `bootJar` 가 `:runtimeClasspath` 를 해석하면서 그때 터진다.
- **실제 사례**: `spring-kafka` 를 추가했더니 `kafka-clients` 가 `at.yawk.lz4:lz4-java:1.10.1` 을, `clickhouse-jdbc` 가 `org.lz4:lz4-java:1.8.0` 을 끌어왔다. at.yawk 쪽은 원본이 관리되지 않아 나온 재배포판이라 `org.lz4:lz4-java` capability 를 스스로 선언하는데, Gradle 은 같은 capability 를 두 모듈이 제공하면 **둘 다 거부한다**. 에러는 `Cannot select module with conflict on capability` 다.
- **해결**: 어느 한쪽을 exclude 하면 남은 쪽이 lz4 압축을 잃으므로, capability 를 해석해 높은 버전을 고른다.

  ```gradle
  configurations.all {
  	resolutionStrategy.capabilitiesResolution.withCapability('org.lz4:lz4-java') {
  		selectHighestVersion()
  	}
  }
  ```

- **의존성을 추가했으면 `compileJava` 가 아니라 `build -x test` 로 확인한다.** CI 와 같은 명령이어야 CI 가 보는 것을 본다.
- **근거**: `api/build.gradle`, `infra/ci/backend.yml`

### SNAPSHOT 저장소를 지우면 빌드가 깨진다

- **원인**: Boot **4.1.2-SNAPSHOT** 을 쓴다. `repo.spring.io/snapshot` 저장소가 `api/build.gradle` 과 `api/settings.gradle`(pluginManagement) **양쪽에** 필요하다. 한쪽만 남기면 플러그인 또는 의존성 해석이 실패한다.
- **해결**: 두 파일 모두 유지한다.
- **근거**: `api/build.gradle`, `api/settings.gradle`

### `SecurityFilterChain` 에 `throws Exception` 이 없어서 이상해 보인다

- **원인**: 실수가 아니다. Spring Security 7 에서 `HttpSecurity.build()` 는 checked exception 을 던지지 않는다.
- **해결**: 그대로 둔다. 3.x 관례대로 `throws Exception` 을 붙일 필요 없다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`

### 배치 스타터는 있는데 배치가 동작하지 않는다

- **원인**: `spring-boot-starter-batch` / `spring-boot-starter-batch-jdbc` 가 `build.gradle` 에 있고 README 도 소개하지만, `src/` 전체에 `org.springframework.batch` import 가 0건이고 `Job`/`Step`/`Tasklet`/`ItemReader`/`ItemWriter` 선언도 0건이다. `application.yaml` 에 `spring.batch.*` 도 없고 `BATCH_*` 메타데이터 테이블 DDL 도 저장소에 없다.
- **해결**: 실제 잡을 만들 때 JobRepository 메타데이터 테이블 생성 방식(`spring.batch.jdbc.initialize-schema` 또는 수동 DDL)을 먼저 정한다. 스타터가 걸려 있다고 준비된 것이 아니다.
- **근거**: `api/build.gradle`, `api/src/main/resources/application.yaml`

### 자바 파일을 저장했더니 파일 전체가 diff 로 뜬다

- **원인**: 자바 소스 74개가 전부 CRLF 이고 `.editorconfig` 가 없다. 에디터가 LF 로 저장하면 전 줄이 바뀐다.
- **해결**: CRLF 로 저장한다. 자바 소스의 들여쓰기는 **스페이스 4칸**이 정답이다 — AGENTS.md:34 도 "Gradle 스크립트와 부트스트랩 클래스는 탭, 새로 쓴 설정 클래스는 스페이스 4칸" 으로 구분해 적었고 실제 파일도 그렇다. 선행 탭이 있는 자바 파일은 생성물인 `ApiApplication.java` 와 `ApiApplicationTests.java` 둘뿐이고 나머지 72개는 스페이스다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/ApiApplication.java`, `api/AGENTS.md`

---

---

## Lombok · 빈 주입

### `@Qualifier` 를 붙였는데 엉뚱한 DB 를 조회한다

- **원인**: `@RequiredArgsConstructor` 가 만드는 생성자 파라미터에는 필드의 `@Qualifier` 가 복사되지 않는다. 그러면 Spring 이 `@Primary` 인 MySQL `JdbcTemplate` 을 주입한다. 컴파일도 되고 기동도 되므로 결과가 이상할 때까지 모른다.
- **해결**: `api/lombok.config` 의 `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` 가 이걸 막고 있다. **이 파일을 지우거나 옮기지 말 것.** 같은 파일의 `config.stopBubbling = true`(상위 디렉터리 탐색 중단)도 함께 필요하다. 코드를 다른 모듈로 옮기면 재발한다.
- **근거**: `api/lombok.config`

### `@Qualifier` 없이 `JdbcTemplate` 을 주입하면 항상 MySQL 이 들어온다

- **원인**: MySQL `JdbcTemplate` 이 `@Primary` 다.
- **해결**: ClickHouse 를 쓰는 주입 지점에는 반드시 `@Qualifier("clickHouseJdbcTemplate")` 를 명시한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/config/JdbcTemplateConfig.java`

### `JdbcTemplateConfig` 를 지웠더니 MySQL `JdbcTemplate` 주입이 실패한다

- **원인**: `ClickHouseConfig` 가 `clickHouseJdbcTemplate` 빈을 등록하는 순간 `JdbcTemplateAutoConfiguration` 의 `@ConditionalOnMissingBean(JdbcOperations.class)` 조건이 깨져 물러나면서, 자동 설정되던 MySQL `JdbcTemplate` 이 사라진다.
- **해결**: `JdbcTemplateConfig` 가 `@Bean @Primary` 로 되살리고 있다. 이 클래스와 `@Primary` 를 그대로 둔다. `JdbcTemplate` 빈을 하나 더 추가할 때는 이 `@Primary` 관계를 깨지 않는지 확인한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/config/JdbcTemplateConfig.java` (클래스 주석에 그대로 적혀 있다)

### ClickHouse `HikariDataSource` 를 `@Bean` 으로 꺼내면 분석 DB 장애가 서비스 전체 장애가 된다

- **원인**: `DataSource` 타입 빈이 둘이 되면 (1) `DataSourceAutoConfiguration` 이 `@ConditionalOnMissingBean(DataSource.class)` 로 물러나 JPA/Batch 용 MySQL DataSource 를 직접 재현해야 하고, (2) `DataSource` 빈마다 Actuator 헬스 컨트리뷰터가 붙어 `/api/actuator/health` 가 ClickHouse 까지 집계한다 → 분석 DB 가 죽으면 api 컨테이너가 unhealthy → nginx 의 `depends_on: service_healthy` 로 서비스 전체가 내려간다.
- **해결**: 풀은 `ClickHouseConfig` 가 필드로 직접 들고 `DisposableBean.destroy()` 로 닫는다. 빈으로는 `clickHouseJdbcTemplate` 만 노출한다. ClickHouse 를 관측하려면 별도 헬스 그룹으로 분리하거나 Grafana 쪽에서 감시한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/analytics/config/ClickHouseConfig.java` (클래스 주석)

### `new HikariDataSource(config)` 로 바꿨더니 ClickHouse 없이 앱이 기동하지 않는다

- **원인**: `HikariConfig` 를 인자로 받는 생성자는 풀을 **즉시** 띄운다. ClickHouse 가 없으면 기동 자체가 실패한다.
- **해결**: 기본 생성자 + setter 로 만든다(현재 `createDataSource()` 방식). 첫 `getConnection()` 때 초기화되므로 분석 DB 가 죽어도 API 는 뜬다. 같은 전제로 운영 compose 의 `api` 에도 분석 스택 `depends_on` 을 걸지 않았다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/analytics/config/ClickHouseConfig.java:53-70`

### Redis 에 객체를 넣으려는데 문자열만 들어간다

- **원인**: `CacheServiceImpl` 이 `StringRedisTemplate` 을 주입받고 `CacheService` 시그니처도 값이 `String` 이다. 커스텀 `RedisTemplate` 이나 Jackson serializer 를 등록한 설정 클래스가 없다(`common/config` 에는 `SecurityConfig`, `JdbcTemplateConfig` 뿐).
- **해결**: 호출부에서 직접 직렬화하거나 설정 클래스를 새로 만든다. 설정 클래스를 추가할 때는 위 `JdbcTemplateConfig` 사례처럼 자동설정을 밀어내는 부작용을 반드시 확인한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/cache/service/CacheServiceImpl.java`

---
