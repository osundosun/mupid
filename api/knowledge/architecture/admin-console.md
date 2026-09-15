# 관리자 화면

`admin` 패키지. 서버의 모든 자원(= 모든 `@Entity`)을 브라우저에서 읽고 고치는 화면과, 그 화면이 쓰는 API 다. 여기 손대기 전에 알아야 할 것은 세 가지다 — **자원 목록이 코드에 적혀 있지 않다는 것**, **경로가 `/admin` 이 아니라 `/api/admin` 이라는 것**, **HTML 은 열려 있고 방어선은 API 쪽 하나뿐이라는 것**.

[← 아키텍처](README.md) · [지식 저장소](../README.md)

---

## 파일 위치

| 경로 | 역할 |
|---|---|
| `admin/controller/AdminController.java` | HTML 골격 2장 (`/api/admin`, `/api/admin/login`) |
| `admin/controller/AdminApiController.java` | 데이터 API. 클래스 전체에 `@AdminRole` |
| `admin/service/AdminResourceRegistry.java` | 기동 시 JPA 메타모델을 읽어 자원 목록을 만든다 |
| `admin/service/AdminResourceServiceImpl.java` | 자원 하나를 읽고 쓰는 제네릭 CRUD |
| `admin/service/AdminDashboardServiceImpl.java` | JVM·외부 저장소 상태 집계 |
| `admin/service/AdminValueCodec.java` | JSON 값 → 엔티티 필드 타입 변환 |
| `admin/dto/`, `admin/response/`, `admin/error/` | 메타데이터 홀더, 응답 record, `AdminErrorCode` |
| `admin/runner/AdminRunner.java` | 관리자 계정이 하나도 없으면 기동 시 하나 만든다 |
| `src/main/resources/templates/admin/index.html` | 콘솔 화면 전체 (CSS·JS 인라인) |
| `src/main/resources/templates/admin/login.html` | 로그인 화면 |
| `docs/Admin/` | Bruno 컬렉션 |

## 자원 목록은 손으로 관리하지 않는다

`AdminResourceRegistry` 는 생성자에서 `EntityManagerFactory.getMetamodel()` 을 읽어 **`@Entity` 전부**를 자원으로 만든다. 지금 30종이다. 엔티티를 추가하면 관리자 화면에도 저절로 나타난다.

도메인마다 관리 컨트롤러를 손으로 쓰지 않은 이유가 이것이다. 엔티티가 서른 개이고 앞으로도 늘 텐데, 손으로 쓰면 새 엔티티가 생길 때마다 조용히 빠진다.

손으로 관리하는 것은 **한글 이름(`LABELS`)과 분류(`CATEGORIES`) 표 둘뿐**이다. 코드에서 유도할 수 없기 때문이다. 표에 없는 엔티티도 동작에는 지장이 없고 클래스 이름과 `"기타"` 로 나온다. **엔티티를 추가하면 이 두 표에도 한 줄씩 넣는다.**

자원 이름은 엔티티 이름의 첫 글자를 내린 것이다 — `MusicComment` → `musicComment`.

### 레지스트리가 메타모델에서 뽑아내는 것

| 화면에 나가는 것 | 어디서 나왔나 |
|---|---|
| `type` | 속성의 자바 타입. `@Column(length > 255)` 이나 `columnDefinition` 에 `text`/`json` 이 있으면 `string` 대신 `text` |
| `generated` | `@GeneratedValue` |
| `insertable` / `updatable` | `@Column` · `@JoinColumn` 의 같은 이름 속성 |
| `nullable` | `@Column(nullable)` → `@JoinColumn(nullable)` → `SingularAttribute.isOptional()` 순 |
| `options` | enum 상수 전부 |
| `relation` | `@ManyToOne`·`@OneToOne` 대상 엔티티의 자원 이름 |
| `sensitive` | 이름이 `password` 인 필드 (레지스트리의 `SENSITIVE_FIELDS`) |

두 가지는 메타모델만으로 안 되어서 따로 손을 댔다.

- **컬럼 순서.** 메타모델의 속성 집합은 순서를 보장하지 않아 그대로 쓰면 컬럼이 매번 뒤바뀐다. `declaredFieldsInOrder` 가 리플렉션으로 **엔티티 소스에 적힌 선언 순서**를 따라간다.
- **감사 필드.** `@CreatedDate`·`@LastModifiedDate` 는 `insertable`·`updatable` 을 강제로 `false` 로 내린다. `AuditingEntityListener` 가 저장 직전에 덮어쓰므로, 입력칸을 내주면 적어 넣은 값이 조용히 사라져 화면이 거짓말을 하게 된다.

## 경로가 `/api/admin` 인 이유

운영 nginx(`infra/deploy/nginx.conf`)가 스프링으로 넘기는 경로는 **`/api/` 뿐**이다. 나머지는 정적 프런트엔드 루트(`/srv/web/current`)로 떨어진다. `/admin` 에 두면 로컬에서만 열리고 배포하면 404 가 된다.

액추에이터가 `management.endpoints.web.base-path: /api/actuator` 로 옮겨져 있는 것과 **같은 이유**다. 스프링이 직접 서빙해야 하는 것을 새로 만들면 `/api/` 아래에 둔다.

## HTML 은 열려 있고 방어선은 API 하나다

`SecurityConfig` 에 이렇게 들어 있다.

```java
.requestMatchers(HttpMethod.GET, "/api/admin", "/api/admin/login").permitAll()
```

브라우저 주소창으로 들어오는 요청에는 `Authorization` 헤더를 붙일 수 없고, `AuthFilter.resolveAccessToken` 은 **헤더의 Bearer 토큰만 본다**(쿠키를 보지 않는다). 그래서 이 경로를 닫아 두면 관리자도 화면에 들어올 수 없다 — 늘 401 이다.

대신 그 HTML 두 장에는 데이터가 한 줄도 들어 있지 않다. 화면이 `fetch` 로 부르는 `/api/v1/admin/**` 는 `anyRequest().authenticated()` 에 걸리고, `AdminApiController` 의 `@AdminRole` 이 `ROLE_ADMIN` 을 요구한다. **실제 방어선은 그쪽 하나다.**

> 이 규칙을 깨는 방법: 템플릿에 `th:` 로 서버 데이터를 심는 것. 그 순간 로그인하지 않은 사람이 데이터를 보게 된다. 화면에 필요한 값은 전부 API 로 받는다.

## 화면의 인증

세션이 아니라 JWT 다. `SessionCreationPolicy.STATELESS` 를 그대로 둔다.

1. `/api/admin/login` 이 `POST /api/v1/auth` 를 부른다.
2. `user_role !== 'ROLE_ADMIN'` 이면 화면에서 먼저 거른다. 서버도 막지만, 그래야 "들어갔는데 전부 403" 이 되지 않는다.
3. 액세스 토큰을 `sessionStorage['mupid.admin.token']` 에 둔다. `localStorage` 가 아니다 — 탭을 닫으면 사라지는 편이 낫다.
4. API 가 401 을 주면 `GET /api/v1/auth` 로 한 번 되살려 보고, 실패하면 로그인으로 보낸다.
5. 로그아웃은 `DELETE /api/v1/auth` 로 **서버 차단 목록에도 올린다.** 세션스토리지만 비우면 남은 한 시간 동안 그 토큰이 그대로 산다.

**리프레시 쿠키는 `Secure` 라 https 가 아닌 환경에서는 저장되지 않는다.** 로컬에서는 4번이 실패하고 한 시간마다 다시 로그인하게 된다. 버그가 아니다.

## 도메인 서비스를 거치지 않는다

`AdminResourceServiceImpl` 은 `UserService`·`MusicService` 를 부르지 않고 `EntityManager` 를 직접 쓴다. 도메인 서비스는 사용자가 지켜야 할 규칙(이메일 인증, 본인 확인)을 담고 있어서, 관리자가 데이터를 **바로잡는** 통로로는 오히려 걸림돌이 된다.

대신 되돌릴 수 없는 것만 따로 막는다. `user` 자원에만 걸리고, 값을 덮어쓰기 **전에** 검사한다.

| 막는 것 | 이유 |
|---|---|
| 자기 계정의 권한 변경 · 자기 계정 `WITHDRAW` · 자기 계정 삭제 | 그 순간 화면에 다시 들어올 수 없다. DB 를 직접 고치는 것 말고는 되돌릴 방법이 없다 |
| 마지막 관리자의 권한 강등 · `WITHDRAW` · 삭제 | 관리자가 한 명도 남지 않는다 |

비밀번호는 조회 응답에서 **언제나 `null`** 이고(해시도 내보내지 않는다), 쓸 때는 평문을 받아 `PasswordEncoder` 로 해싱한다. 빈 값은 "안 바꿈" 이다 — 빈 문자열을 해싱해 넣으면 그 계정은 로그인이 막힌다.

## 목록 조회가 N+1 을 내지 않는 방법

행을 엔티티가 아니라 평평한 `Map` 으로 만든다. 값을 꺼낼 때 **게터가 아니라 리플렉션으로 필드를 직접 읽는다.**

게터를 거치면 `@ManyToOne(LAZY)` 프록시가 깨어나 행마다 추가 쿼리가 붙는다. 필드를 그대로 읽으면 프록시 객체를 얻고, `PersistenceUnitUtil.getIdentifier(proxy)` 로 **쿼리 없이** 식별자만 꺼낼 수 있다. 그래서 연관 필드의 값은 대상 엔티티가 아니라 대상의 식별자다.

실측: `music` 3행(연관 2개)을 조회할 때 도는 SQL 은 목록 1 + `COUNT` 1 뿐이다. 확인하려면 `--logging.level.org.hibernate.SQL=DEBUG` 로 띄운다.

엔티티의 기본 생성자가 `protected` 라 생성에도 어차피 리플렉션이 필요하다.

## 자원 하나가 깨져도 나머지는 살아 있어야 한다

관리자 화면은 엔티티 **전부**를 노출한다. 그래서 어느 하나의 매핑이 실제 테이블과 어긋나 있으면 **여기서 처음 드러난다** (실제로 `Mubti` 가 그랬다 — [함정](../failure/auth-and-api.md)). 그때 콘솔 전체가 죽으면 안 된다.

- **행 수는 JPA 가 아니라 테이블 이름으로 센다.** `resources()` 는 서른 번 도는 자리이고 화면 왼쪽 전부와 대시보드가 이 결과에 달려 있다. JPA 로 세다가 한 엔티티에서 예외가 나면 하이버네이트가 세션을 롤백 표시해 **뒤따르는 스물아홉 개까지 함께 실패한다** — 잡아 봐야 소용이 없다. `JdbcTemplate` 으로 `select count(*) from <테이블>` 을 돌리면 매핑과 무관하고, 실패해도 그 자원만 `-1` 이 된다. 화면은 `-1` 을 **물음표**로 그린다 (`0` 으로 그리면 "비었다" 는 거짓말이 된다).
- **목록·단건 조회 실패는 `MUPID_RESOURCE_QUERY_FAILED`(500)** 다. 그냥 두면 `GlobalExceptionHandler` 의 마지막 폴백에 먹혀 "서버 내부 오류" 한 줄이 되어, 보는 사람이 무엇이 잘못됐는지 알 길이 없다. 어느 자원인지와 DB 가 한 말은 서버 로그에 남는다.
- `JdbcTemplate` 은 `@Qualifier("jdbcTemplate")` 로 받는다. ClickHouse 용 빈이 따로 있어서다.

## 복합키

`@EmbeddedId` 구성 요소는 **바깥으로 펼쳐서** 각각 독립된 필드로 보여 준다. 덩어리째 보여 주면 손댈 수가 없다.

URL 의 `{id}` 는 스키마 `ids` 순서대로 `,` 로 이은 것이다 — `MusicReaction` 은 `5,3`.

`Map` 키가 겹치지 않는 이유: 연관 필드는 `musicId` 가 아니라 **속성 이름 그대로**(`music`) 쓴다. `<이름>Id` 로 만들면 펼쳐진 복합키 구성 요소와 충돌한다 (`MusicGenre` 의 `musicId` vs `music`).

## 응답 키의 표기가 섞인다

행(`Map`)의 키는 자바 필드명 그대로 camelCase 이고, 같은 응답의 `content`·`total` 같은 record 필드는 snake_case 다. `spring.jackson.property-naming-strategy: SNAKE_CASE` 가 **`Map` 의 키에는 적용되지 않기** 때문이다. `error_details` 안의 키가 camelCase 로 남는 것과 같은 현상이다 ([함정과 실패 사례](../failure/auth-and-api.md)).

## 대시보드가 액추에이터를 쓰지 않는 이유

`/api/actuator/health` 는 `show-details: never` 라 `{"status":"UP"}` 밖에 주지 않는다. 관리자는 무엇이 죽었는지 알아야 하므로 `AdminDashboardServiceImpl` 이 MySQL·Redis·ClickHouse·오브젝트 스토리지·Kafka 를 3초 타임아웃으로 하나씩 찔러 본다.

**이 검사를 액추에이터 헬스에 붙이면 안 된다.** 운영 스택에서 nginx 는 `api` 컨테이너가 healthy 가 된 뒤에야 뜬다. 헬스가 ClickHouse·Kafka·S3 까지 집계하면 분석 계통 하나가 죽었을 때 서비스 전체가 내려간다 — `analytics/config/ClickHouseConfig` 의 주석이 같은 이유로 `HikariDataSource` 를 빈으로 올리지 않는다. 관리자가 화면을 열 때만 도는 검사라 그 결합이 생기지 않는다.

Kafka 는 프로듀서 빈을 재사용하지 않고 일회용 `AdminClient` 를 만든다. `send` 는 브로커가 죽어 있어도 `max.block.ms` 만큼 기다렸다 실패해서 상태 확인용으로 쓸 수 없다.

ClickHouse `JdbcTemplate` 은 `@Qualifier("clickHouseJdbcTemplate")` 로 받는다. 빼면 `@Primary` 인 MySQL 쪽이 주입되어 **컴파일도 기동도 되면서 다른 DB 를 조회한다.** 루트 `lombok.config` 가 `@Qualifier` 를 생성자 파라미터로 복사하게 해 두었다.

## 화면 쪽 규칙

- `templates/admin/*.html` 은 **Thymeleaf 표현식을 하나도 쓰지 않는다.** 그대로 나가는 정적 HTML 이고, 인라인 `<style>`·`<script>` 로 자기완결이다. 정적 리소스 경로를 새로 열지 않으려는 것이다 (`/admin/app.js` 를 만들면 `SecurityConfig` 에 permitAll 규칙이 하나 더 필요해진다).
- **`confirm()`·`alert()` 를 쓰지 않는다.** 브라우저 기본 대화상자는 페이지 스크립트를 통째로 멈춰 세워서, 화면 자동화나 스크린샷 도구가 걸리면 아무 반응이 없어진다. `modal()` 이 대신한다.
- 표를 다시 그리면 입력 중이던 칸이 통째로 교체된다. `keepFocus()` 가 포커스와 커서 위치를 되돌린다. 없으면 검색어를 한 글자 칠 때마다 포커스가 날아가 검색을 쓸 수 없다.
- 수정은 **바뀐 필드만** `PATCH` 로 보낸다. 손대지 않은 칸이 다른 사람의 수정을 덮어쓰지 않게.

## 알려진 한계

- **`Mubti` 의 컬럼 이름이 ERD 와 실제 DB 에서 다르다.** 엔티티는 실제 DB(`class_a`)에 맞춰 두었고 ERD 는 `classa` 다. 어느 쪽으로 맞출지는 정해지지 않았다 → [open-questions B7](../open-questions.md).
- **`MusicGenre` · `MusicMood` · `MusicAnalysisInstrument` · `MusicSectionAnalysisMood` 는 생성이 실패한다.** 관리자 화면 문제가 아니라 엔티티 매핑 문제다 — [함정과 실패 사례](../failure/auth-and-api.md) 참고. 조회·수정·삭제는 정상이다.
- 오류 응답이 **어느 필드가 잘못됐는지 말하지 않는다.** `ErrorResponse.errorDetails` 를 채우는 곳은 `GlobalExceptionHandler.invalidArgs` 하나뿐이고 거기에 끼려면 `BindException` 이어야 한다. 지금은 서버 로그에만 남는다. 화면이 타입별 입력칸(숫자·선택·날짜)을 그려 애초에 틀린 값이 잘 가지 않게 하는 것으로 대신하고 있다.
- `GET /api/v1/admin/resource` 는 자원 수만큼 `COUNT` 쿼리를 돌린다. 행이 아주 많아지면 느려진다.
- 일괄 삭제가 없다. 화면에서 한 행씩 지운다.
- 관리자 행동이 감사 로그로 남지 않는다.

## 검증

로컬 인프라(`compose.yml`, `mupid-dev`)를 띄운 상태에서:

```bash
./gradlew bootRun
# 브라우저로 http://localhost:8800/api/admin
```

관리자 계정이 없으면 기동할 때 `AdminRunner` 가 하나 만들고 비밀번호를 **콘솔에 한 번만** 찍는다. 놓치면 `users` 에서 지우고 다시 띄운다.

API 만 확인하려면 `docs/Admin/` Bruno 컬렉션을 쓴다. `Auth / 로그인` 을 먼저 돌려 `ACCESS_TOKEN` 을 채운다.
