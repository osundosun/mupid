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
| `admin/service/AdminTimelineServiceImpl.java` | 곡의 가사·구간 타임라인 (MySQL 자원) |
| `admin/service/AdminUserTimelineServiceImpl.java` | 회원의 행동 타임라인 (ClickHouse 이벤트) |
| `analytics/repository/UserActivityRepository.java` | 그 타임라인이 읽는 ClickHouse 질의 |
| `admin/controller/AdminClickHouseController.java` | ClickHouse 화면 프록시 (`/api/admin/clickhouse/**`) |
| `admin/service/AdminClickHouseConsoleServiceImpl.java` | 열람권 발급·검사, 프록시 본체 |
| `admin/service/AdminValueCodec.java` | JSON 값 → 엔티티 필드 타입 변환 |
| `admin/dto/`, `admin/response/`, `admin/error/` | 메타데이터 홀더, 응답 record, `AdminErrorCode` |
| `admin/runner/AdminRunner.java` | 관리자 계정이 하나도 없으면 기동 시 하나 만든다 |
| `src/main/resources/templates/admin/index.html` | 콘솔 화면 전체 (CSS·JS 인라인) |
| `src/main/resources/templates/admin/login.html` | 로그인 화면 |
| `docs/Admin/` | Bruno 컬렉션 |

## 두 개의 타임라인은 서로 다른 것이다

이름이 닮아서 같은 것으로 읽기 쉬운데, **원본도 축도 다르다.** 고칠 때 한쪽 규칙을
다른 쪽에 가져오면 조용히 깨진다.

| | 곡 가사·구간 (380) | 회원 행동 (430) |
|---|---|---|
| 원본 | MySQL. **관리 자원**(`lyric`·`musicSectionAnalysis`) | ClickHouse `listen_events`. 자원이 아니다 |
| 가로축 | 곡 안의 ms. **곡마다 다르고 DB 에서 집계한다** | 하루. **언제나 24시간, 고정** |
| 행 | 목록 조회와 같은 모양(`_id`·`_labels`) | 전용 record. 식별자로 고칠 대상이 없다 |
| 편집 | 된다 — 같은 편집기, `PATCH /resource/…`. 드래그로 시각도 옮긴다 | **없다.** 이벤트 원본은 쌓기만 한다 |
| 밴드 선언 | `AdminResourceRegistry.TIMELINES` (자원·시간 컬럼) | `AdminUserEventGroup` (이벤트 종류 묶음) |
| 화면 진입 | 스키마의 `timeline_axes` 가 비어 있지 않은 자원 | 스키마의 `event_timeline` 이 참인 자원 + 왼쪽 목록 항목 |

축을 그날 데이터에서 유도하지 않는 것이 행동 타임라인의 핵심이다. 유도하면 새벽에만
쓴 날과 온종일 쓴 날의 눈금이 달라져 **날짜를 바꿀 때마다 같은 가로 위치가 다른 시각을
뜻하게** 된다 — 그 순간 날짜끼리 비교할 수 없다.

### 하트비트를 점으로 그리지 않는다

`PLAY_PROGRESS` 는 5초 하트비트다. 한 곡을 3분 들으면 서른여섯 건이라, 점으로 늘어놓으면
시간축이 그것으로 덮여 나머지 행동(좋아요·스킵·검색)이 보이지 않는다. 대신 `play_id` 로
묶어 **재생 세션 막대 하나**로 접는다.

버린 것이 아니다. 접은 건수를 `heartbeat_events` 로 함께 보내고 화면이 그 사실을 말한다 —
안 그러면 하루 이벤트 수와 시간축의 항목 수가 크게 어긋나 보여 화면을 의심하게 된다.

> 막대의 **길이**(걸린 시간)와 **실제 들은 시간**(`listened_ms`)은 다르다. 일시정지와
> 앞으로감기가 그 차이라, 둘을 함께 보여 주지 않으면 막대가 청취 시간으로 읽힌다.

### ClickHouse 를 읽을 때 지켜야 하는 세 가지

`analytics/repository/UserActivityRepository` 머리말에 그대로 적어 두었다. **하나라도
빠지면 오류 없이 값만 틀린다.**

1. **Distributed 테이블(`listen_events`)만 읽는다.** Kafka 적재 경로가 샤딩 키를 타지
   않아 한 사용자의 이벤트가 두 샤드에 흩어진다 — 로컬 테이블을 읽으면 하루치가 절반만 온다.
2. **`GROUP BY event_id` 로 감싼다.** 원본이 `ReplicatedReplacingMergeTree` 라 병합 전에는
   같은 이벤트가 여러 행으로 남아 있다.
3. **보낼 때는 문자열, 받을 때는 숫자.** 시각을 `toDateTime64(?, 3, 'Asia/Seoul')` 로 못
   박아 보내고, 돌려받을 때는 `toUnixTimestamp64Milli` 로 받는다. 숫자에는 시간대가 없어
   드라이버가 끼어들 자리가 없다.

`position_ms - from_ms` 를 그냥 빼지 않는 것도 같은 부류다. 뒤집힌 하트비트에서 `UInt32`
언더플로가 나 40억에 가까운 양수가 되고, 그 한 건이 그날 청취 시간을 통째로 망가뜨린다.

## 자원 목록은 손으로 관리하지 않는다

`AdminResourceRegistry` 는 생성자에서 `EntityManagerFactory.getMetamodel()` 을 읽어 **`@Entity` 전부**를 자원으로 만든다. 지금 30종이다. 엔티티를 추가하면 관리자 화면에도 저절로 나타난다.

도메인마다 관리 컨트롤러를 손으로 쓰지 않은 이유가 이것이다. 엔티티가 서른 개이고 앞으로도 늘 텐데, 손으로 쓰면 새 엔티티가 생길 때마다 조용히 빠진다.

손으로 관리하는 것은 **한글 이름(`LABELS`)과 분류(`CATEGORIES`) 표 둘뿐**이다 (자식
링크·타임라인 선언은 "보여 줄 것만" 적는 별개의 표다). 코드에서 유도할 수 없기 때문이다. 표에 없는 엔티티도 동작에는 지장이 없고 클래스 이름과 `"기타"` 로 나온다. **엔티티를 추가하면 이 두 표에도 한 줄씩 넣는다.**

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

## ClickHouse 콘솔은 포트를 열지 않고 넣는다

ClickHouse 는 자기 화면 두 장을 스스로 서빙한다 — `/play`(쿼리 콘솔)와 `/dashboard`(차트).
관리자 화면의 **ClickHouse 콘솔** 항목이 그 둘을 iframe 으로 띄운다.

**8123 을 밖으로 열지 않는다.** 열면 그 화면에는 우리 로그인이 없고, 지키는 것이
ClickHouse 계정 하나뿐인데 그 계정은 분석 DB 를 통째로 읽고 쓴다. 방화벽 규칙과
ClickHouse 계정이라는 두 번째 자격증명 체계를 따로 관리하게 된다. 대신 스프링이 중간에
서서 관리자 로그인 하나로 통과시킨다 — `compose.yml` 의 `ports` 는 로컬 편의를 위한
것이고 운영에서는 필요 없다.

### 자격증명이 브라우저로 내려가지 않는다

두 화면 모두 user·password 칸을 갖고 그 값을 **쿼리 문자열에** 실어 보낸다. 프록시는
`user`·`password`·`quota_key` 를 **버리고** `X-ClickHouse-User`·`X-ClickHouse-Key` 헤더를
서버에서 붙인다. 그래서 ClickHouse 비밀번호가 브라우저 주소창·히스토리·중간 로그에
남지 않는다. 화면의 두 칸은 조각(아래)이 잠가 둔다 — 적어도 무시되기 때문이다.

### 열람권은 쿠키다

iframe 의 문서 요청, 그 안의 `<script src>`, ClickHouse 화면이 스스로 던지는 `fetch` —
셋 다 우리가 `Authorization` 헤더를 붙일 수 없다. 이 프로젝트의 인증은 헤더의 Bearer
뿐이므로(`AuthFilter`) 세 가지를 한 번에 통과시킬 수 있는 자격증명은 쿠키뿐이다.

1. 화면이 `POST /api/v1/admin/clickhouse/console` 을 부른다. 이 엔드포인트는 `@AdminRole` 이다.
2. 서버가 무작위 열람권을 Redis 에 30분 TTL 로 올리고, 같은 값을 `HttpOnly` 쿠키로 내린다.
   `Path` 는 `/api/admin/clickhouse` 라 다른 API 요청에는 실리지 않는다.
3. 화면이 iframe 을 띄운다. 프록시가 매 요청마다 쿠키를 Redis 에서 확인한다.
4. 화면은 만료 1분 전에 열람권만 새로 받는다 — **iframe 은 다시 그리지 않는다.** 쓰던
   쿼리가 사라지면 콘솔을 쓸 수 없다.
5. 로그아웃이 `DELETE /api/v1/admin/clickhouse/console` 로 Redis 에서 지운다. 쿠키만
   비우면(그마저 HttpOnly 라 화면이 못 지운다) 남은 30분 동안 그 값이 그대로 산다.

`Secure` 는 요청이 https 로 들어왔을 때만 붙인다(`X-Forwarded-Proto` 를 먼저 본다).
리프레시 토큰 쿠키처럼 늘 붙이면 http 로 띄우는 로컬에서 콘솔이 아예 열리지 않는다.

> ⚠ `SecurityConfig` 에서 `/api/admin/clickhouse/**` 는 `permitAll` 이다. 관리자 HTML 두
> 장을 연 것과 같아 보이지만 성격이 다르다 — 그쪽은 데이터가 한 줄도 없지만 **여기는
> 분석 DB 그 자체다.** 쿠키 검사가 유일한 방어선이다.

### X-Frame-Options 를 경로별로 준다

스프링 시큐리티는 **모든 응답에 `X-Frame-Options: DENY`** 를 붙인다. 그대로 두면
브라우저가 프레임을 거부해서 콘솔 iframe 이 **아무것도 그리지 않는다** — 화면에는 흰
사각형만 남고, 서버 로그에는 200 이 찍혀서 원인을 찾기 어렵다. (curl 로는 멀쩡히
내려오므로 명령줄 확인만으로는 드러나지 않는다.)

전체를 `SAMEORIGIN` 으로 내리지는 않았다. 운영에서 프런트엔드와 API 는 같은 오리진이라
(nginx 가 둘 다 서빙한다) 프런트에 HTML 주입이 생기면 관리자 로그인 화면이 프레임에
걸릴 수 있다. 기본 라이터를 끄고 `DelegatingRequestMatcherHeaderWriter` 로 **콘솔
경로에만 `SAMEORIGIN`, 나머지에는 `DENY`** 를 붙인다.

컨트롤러에서 헤더를 덮어쓰는 방법은 통하지 않는다. `XFrameOptionsHeaderWriter` 는
DENY·SAMEORIGIN 모드에서 기존 헤더를 확인하지 않고 `setHeader` 로 덮어쓰고, 그 시점이
응답 커밋 직전이라 우리가 먼저 적어 둔 값이 진다.

같은 이유로 `X-Content-Type-Options: nosniff` 도 늘 붙는다. ClickHouse 가 `/js/*` 를
`text/html` 로 내보내므로 프록시가 `.js` 응답의 Content-Type 을 바로잡아 준다 —
안 그러면 차트 대시보드의 라이브러리가 nosniff 에 걸려 실행되지 않는다.

### ClickHouse 가 만든 HTML 을 두 군데만 고친다

`/play` 는 자기가 서빙된 **오리진의 루트**로 쿼리를 던진다 (`location.origin + '?query'`).
주소칸을 고쳐 줘도 소용없다 — `new URL(주소).origin` 으로 경로를 지우고 부르는 자리가
있다. 그래서 내려보내기 전에 HTML 을 손본다.

| 고치는 것 | 왜 |
|---|---|
| `</head>` 앞에 조각 삽입 | `fetch` 를 감싸 같은 오리진 요청의 경로를 `/api/admin/clickhouse/` 로 옮긴다. user·password 칸을 잠근다. `<base target="_blank">` 로 바깥 링크가 iframe 을 삼키지 않게 한다 |
| `src="/js/` → `src="js/` | `/dashboard` 가 차트 라이브러리를 오리진 루트에서 읽는다. 상대 경로로 내려야 프록시 아래로 들어온다 |

남의 코드를 문자열로 고치는 것이라 ClickHouse 버전이 바뀌면 깨질 수 있다. 그래서 손대는
곳을 이 둘로만 묶었다. `</head>` 를 못 찾으면 경고를 남기고 원본을 그대로 내보낸다.

넘겨주는 경로도 **손으로 적은 것만**이다 — 빈 경로(쿼리 엔드포인트)·`play`·`dashboard`·
`js/<파일>`. `**` 를 그대로 이어 붙이면 ClickHouse 의 HTTP 인터페이스 전체가 열린다.

### 주의

- **읽기 전용이 아니다.** 프록시는 `mupid` 계정으로 나가고 그 계정은 `default` 프로파일이라
  `DROP`·`ALTER` 가 된다. 관리자가 데이터를 바로잡는 통로라는 점에서 `AdminResourceService`
  가 도메인 서비스를 건너뛰는 것과 같은 판단이다.
- `LIMIT` 없는 `SELECT` 도 그대로 나간다. 그래서 프록시는 응답을 통째로 들고 있지 않고
  흘려보낸다 — 바이트 배열로 받으면 API 서버의 힙이 먼저 죽는다. 단 `play`·`dashboard`
  두 장은 HTML 을 고쳐야 해서 버퍼링한다 (70KB 남짓).
- `console-timeout` 은 60초다. 운영 nginx 의 `/api/` 가 `proxy_read_timeout 60s` 라
  그보다 크게 잡으면 브라우저는 nginx 의 504 를 먼저 받는다.
- 어느 노드를 볼지는 `MUPID_CLICKHOUSE_HTTP_URL` 로 바꾼다. 비우면 `MUPID_CLICKHOUSE_URL`
  의 JDBC 주소에서 만들어 쓴다 (`ClickHouseProperties.httpEndpoint()`).

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
- **회원 행동 타임라인에 회원을 이름으로 찾는 길이 없다.** 번호를 치거나 회원 목록에서
  **행동** 버튼으로 들어간다. 목록에는 이메일 검색이 있으므로 길이 막히지는 않는다.
- 회원 행동 타임라인의 창은 **92일**까지다. 그보다 긴 기간은 날짜 막대가 1~2px 이 되어
  날을 고를 수 없다 → 주·월 집계로 답할 일이다.
- **하루 24시간 축에서 몇 분짜리 재생 막대는 몇 px 밖에 안 되어 그 안의 곡 이름이 보이지
  않는다.** 축을 그날 데이터에 맞춰 늘이면 날짜끼리 비교할 수 없게 되므로 그대로 두었고,
  마우스를 올리면 곡·진입 경로·실제 청취 시간이 설명에 전부 나온다.
- 하루 이벤트가 800건(재생 세션 300건)을 넘으면 **그날의 뒤쪽이 잘린다.** 잘린 사실은
  밴드에 표시되지만, 뒤쪽을 보려면 ClickHouse 콘솔을 써야 한다.
- 관리자 행동이 감사 로그로 남지 않는다. ClickHouse 콘솔은 **연 사람과 시각만**
  서버 로그에 남는다 — 거기서 무슨 쿼리를 돌렸는지는 ClickHouse 의 `system.query_log`
  를 봐야 한다.
- 콘솔의 쿼리 콘솔/차트 대시보드를 오가면 iframe 이 다시 뜨면서 쓰던 쿼리가 사라진다.

## 검증

로컬 인프라(`compose.yml`, `mupid-dev`)를 띄운 상태에서:

```bash
./gradlew bootRun
# 브라우저로 http://localhost:8800/api/admin
```

관리자 계정이 없으면 기동할 때 `AdminRunner` 가 하나 만들고 비밀번호를 **콘솔에 한 번만** 찍는다. 놓치면 `users` 에서 지우고 다시 띄운다.

ClickHouse 콘솔만 따로 확인하려면 (`$CK` 는 열람권 쿠키 값):

```bash
# 열람권 받기 (Set-Cookie 헤더에 들어온다)
curl -i -X POST localhost:8800/api/v1/admin/clickhouse/console -H "Authorization: Bearer $ACCESS_TOKEN"

# 쿼리가 실제로 도는가 — user/password 는 프록시가 버린다
curl -b "mupid_ch_console=$CK" -X POST --data "SELECT version()"   "localhost:8800/api/admin/clickhouse/?default_format=JSONEachRow&user=default&password=아무거나"

# 쿠키 없이 401 인가
curl -o /dev/null -w "%{http_code}
" localhost:8800/api/admin/clickhouse/play
```

API 만 확인하려면 `docs/Admin/` Bruno 컬렉션을 쓴다. `Auth / 로그인` 을 먼저 돌려 `ACCESS_TOKEN` 을 채운다.
