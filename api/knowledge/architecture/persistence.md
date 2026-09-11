# 데이터 영속성 (MySQL · JPA)

엔티티를 추가·수정하거나, 리포지토리 메서드를 새로 만들거나, 테이블 스키마를 건드릴 때 읽는다. 이 저장소에는 MySQL DDL 이 한 줄도 없고 `spring.jpa.*` 설정도 하나도 없다. 그 상태를 전제로 지금까지의 규약이 굴러가고 있으므로, 무엇을 바꾸든 "이 테이블은 누가 만드는가"를 먼저 확인하고 시작해야 한다.

## 한눈에 보기

| 항목 | 값 | 근거 |
|---|---|---|
| ORM | `spring-boot-starter-data-jpa` (Hibernate) | `api/build.gradle:26` |
| 드라이버 | `runtimeOnly 'com.mysql:mysql-connector-j'` | `api/build.gradle:39` |
| DB | MySQL 8.4, `utf8mb4` / `utf8mb4_unicode_ci`, `TZ: Asia/Seoul` | `api/compose.yml:27`, `api/compose.prod.yml:61`, `infra/deploy/compose.yml:52` |
| Boot | 4.1.2-SNAPSHOT · Java 21 | `api/build.gradle:3,12` |
| 엔티티 | 4개 | `@Entity` grep 4건 |
| 리포지토리 | 4개, 전부 `JpaRepository<T, Integer>` | 아래 표 |
| 스키마 관리 | 설계 기준은 ERD, `ddl-auto` 는 `none`. 마이그레이션 도구 미도입 | [domain/erd.md](../domain/erd.md), [decision/0011](../decision/0011-jpa-manages-schema.md) |
| `spring.jpa.*` | 키 자체가 없음 | `api/src/main/resources/application.yaml` |

## 패키지 배치

도메인별 수직 분할이다. 영속성 타입은 도메인 패키지 안의 `entity/` · `repository/` 에 두고, 공용 `domain` 이나 `persistence` 패키지는 만들지 않는다.

```
api/src/main/java/com/osundosun/mupid/api/
├── ApiApplication.java                    # @SpringBootApplication @ConfigurationPropertiesScan @EnableJpaAuditing
├── user/
│   ├── entity/User.java                   # @Table(name = "users")
│   ├── repository/UserRepository.java
│   └── enumerate/{UserGender,UserRole,UserStatus}.java
├── email/
│   ├── entity/EmailVerification.java      # @Table(name = "email_verifications")
│   ├── repository/EmailVerificationRepository.java
│   └── enumerate/EmailVerificationPurpose.java
├── storage/
│   ├── entity/ObjectStorageFile.java      # @Table(name = "object_storage_files")
│   ├── repository/ObjectStorageFileRepository.java
│   └── enumerate/{StorageFileType,StorageFileCategory}.java
├── playlist/
│   ├── entity/Playlist.java                # @Table(name = "playlists")
│   └── repository/PlaylistRepository.java
├── common/config/JdbcTemplateConfig.java  # MySQL 용 @Primary JdbcTemplate
└── analytics/config/ClickHouseConfig.java # ClickHouse 전용 JdbcTemplate (JPA 아님)
```

## 엔티티와 관계

네 엔티티뿐이고, 연관관계는 단방향 `@ManyToOne(LAZY)` 세 개뿐이다.

```
EmailVerification ──@ManyToOne(LAZY, optional=true)──▶ User
                     FK: email_verifications.user_id (updatable = false)

User ──────────────@ManyToOne(LAZY, optional=true)──▶ ObjectStorageFile
                     FK: users.image_file_id (nullable = true)

Playlist ──────────@ManyToOne(LAZY, optional=true)──▶ User
                     FK: playlists.user_id (nullable = true, updatable = false)
```

`@OneToMany` · `@OneToOne` · `@ManyToMany` · `cascade` · `orphanRemoval` · `@Embedded` 는 저장소 전체에 0건이다. 양방향 매핑이 없으므로 `User` 에서 인증 이력을 따라갈 수단도 없다.

### `users` — `user/entity/User.java`

| 필드 | 타입 | 매핑 |
|---|---|---|
| `userId` | `Integer` | `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` |
| `email` | `String` | `@Column(nullable = false, updatable = false)` — **unique 제약 없음** |
| `password` | `String` | `@Column(nullable = false)` |
| `nickname` | `String` | `@Column(nullable = false)` |
| `imageFile` | `ObjectStorageFile` | `@ManyToOne(fetch = LAZY, optional = true)` + `@JoinColumn(name = "image_file_id", nullable = true)` |
| `birthYear` | `Integer` | `@Column(nullable = false)` |
| `gender` | `UserGender` | `@Enumerated(EnumType.STRING) @Column(nullable = false)` |
| `role` | `UserRole` | `@Enumerated(EnumType.STRING) @Column(nullable = false)` |
| `status` | `UserStatus` | `@Enumerated(EnumType.STRING) @Column(nullable = false)` |
| `createdAt` | `LocalDateTime` | `@CreatedDate @Column(nullable = false, updatable = false)` |
| `updatedAt` | `LocalDateTime` | `@LastModifiedDate @Column(nullable = false)` |

### `email_verifications` — `email/entity/EmailVerification.java`

| 필드 | 타입 | 매핑 |
|---|---|---|
| `verificationId` | `Integer` | `@Id @GeneratedValue(IDENTITY)` |
| `user` | `User` | `@ManyToOne(fetch = LAZY, optional = true)` + `@JoinColumn(name = "user_id", updatable = false)` |
| `email` | `String` | `@Column(nullable = false, updatable = false)` |
| `token` | `String` | `@Column(nullable = false, updatable = false)` — 6자리 랜덤 문자열 |
| `purpose` | `EmailVerificationPurpose` | `@Enumerated(EnumType.STRING) @Column(nullable = false)` |
| `expiredAt` | `LocalDateTime` | `@Column(nullable = false)` |
| `verifiedAt` | `LocalDateTime` | `@Column(nullable = true)` — INSERT 시 항상 null 이고, null 이 "미인증" 을 뜻한다 |
| `createdAt` | `LocalDateTime` | `@CreatedDate @Column(nullable = false, updatable = false)` |

세 엔티티 중 유일하게 `@LastModifiedDate` / `updatedAt` 이 없다.

### `object_storage_files` — `storage/entity/ObjectStorageFile.java`

| 필드 | 타입 | 매핑 |
|---|---|---|
| `fileId` | `Integer` | `@Id @GeneratedValue(IDENTITY)` |
| `fileType` | `StorageFileType` | `@Enumerated(STRING) @Column(nullable = false)` — `IMG` / `MUSIC` |
| `category` | `StorageFileCategory` | `@Enumerated(STRING) @Column(nullable = false)` — `PROFILE` / `ARTIST` / `ALBUM` / `STREAMING` |
| `originalFileName` | `String` | `@Column(nullable = false)` |
| `storageObjectKey` | `String` | `@Column(nullable = false)` — S3 키. 현재 이 키를 만드는 곳은 프로필 이미지 경로 하나뿐이고 `user/image/<userId>` 형식을 쓴다 |
| `fileSizeBytes` | `Integer` | `@Column(nullable = false)` — `Long` 이 아니다 |
| `mime` | `String` | `@Column(nullable = false)` — ERD 컬럼명이 `mime` 이다 (`contentType` 이 아니다) |
| `isDeleted` | `Boolean` | `@Column(nullable = false) @Builder.Default = false` |
| `createdAt` | `LocalDateTime` | `@CreatedDate @Column(nullable = false, updatable = false)` |
| `updatedAt` | `LocalDateTime` | `@LastModifiedDate @Column(nullable = false)` |

### `playlists` — `playlist/entity/Playlist.java`

| 필드 | 타입 | 매핑 |
|---|---|---|
| `playlistId` | `Integer` | `@Id @GeneratedValue(IDENTITY)` |
| `user` | `User` | `@ManyToOne(fetch = LAZY, optional = true)` + `@JoinColumn(name = "user_id", nullable = true, updatable = false)` — **NULL 은 시스템/추천이 만든 플레이리스트** |
| `title` | `String` | `@Column(nullable = false)` — ERD 는 `VARCHAR(100)`. 컬럼명이 `name` 이 아니다 |
| `musicCount` | `Integer` | `@Column(nullable = false) @Builder.Default = 0` — 수록곡 수의 역정규화. ERD 가 `SMALLINT UNSIGNED` 라 `Short` 로는 범위가 부족하다 |
| `deletedAt` | `LocalDateTime` | `@Column(nullable = true)` — 소프트 삭제. 조회는 `…AndDeletedAtIsNull` 로 거른다 |
| `createdAt` | `LocalDateTime` | `@CreatedDate @Column(nullable = false, updatable = false)` |
| `updatedAt` | `LocalDateTime` | `@LastModifiedDate @Column(nullable = false)` |

`musicCount` 의 `@Builder.Default` 는 장식이 아니다. JPA 는 INSERT 문에 모든 컬럼을 실으므로 DB 의 `DEFAULT 0` 이 적용되지 않고, 빼면 `null` 이 들어가 NOT NULL 위반이 된다.


## 엔티티 작성 규약

새 엔티티를 만들 때 세 엔티티가 공유하는 형태를 그대로 따른다.

```java
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "복수형_snake_case")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Something { … }
```

**공통 상위 엔티티는 없다.** `@MappedSuperclass` 도 `BaseEntity` 도 존재하지 않는다(grep 0건). 감사 필드(`createdAt`/`updatedAt`)와 `@EntityListeners` 를 엔티티마다 직접 반복 선언하는 것이 현재 규약이다. 상위 클래스를 도입하려면 세 엔티티를 동시에 고쳐야 하고, 컬럼 순서가 바뀌므로 스키마와의 정합도 함께 봐야 한다.

| 규칙 | 내용 |
|---|---|
| PK | `Integer` + `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`. `Long` 이나 UUID 를 쓴 엔티티는 없다 |
| 테이블명 | `@Table(name = …)` 로 **반드시 명시**하고 복수형 snake_case (`users`, `email_verifications`, `object_storage_files`). `user` 는 예약어라 복수형이 안전하다 |
| 컬럼명 | `@Column(name = …)` 을 **쓰지 않는다**. `userId → user_id` 변환을 Hibernate 암묵 네이밍에 맡긴다. 이름을 지정하는 건 FK 두 개(`@JoinColumn`)뿐이다 |
| Lombok | 위 5종 세트 고정. `@NoArgsConstructor(PROTECTED)` 를 빼면 `@Builder` 가 만든 전체 필드 생성자 때문에 기본 생성자가 사라져 Hibernate 가 엔티티를 인스턴스화하지 못한다 |
| Enum | `@Enumerated(EnumType.STRING)`. `AttributeConverter` 나 별도 `code` 필드를 만들지 않는다 |
| 시간 | `java.time.LocalDateTime`. `@Temporal` · `columnDefinition` 을 붙이지 않는다 |
| 감사 | Spring Data 감사만 쓴다. Hibernate 의 `@CreationTimestamp` / `@UpdateTimestamp` 는 0건 |
| 인덱스 | `@Index` · `@Table(uniqueConstraints = …)` 사용 0건. 제약은 전부 애플리케이션 쪽에만 있다 |

`@NoArgsConstructor(PROTECTED)` 는 커밋 `941a4ba` 에서 세 엔티티에 한꺼번에 추가됐다.

### Enum: 상수명이 곧 저장값

커밋 `941a4ba` ("refactor: enum 상수명 통일 및 AttributeConverter 제거")에서 `UserGenderConverter` · `UserRoleConverter` · `UserStatusConverter` · `EmailVerificationPurposeConverter` 4종을 삭제하고, enum 의 `code` 필드와 `fromCode()` 를 없앤 뒤 **상수명 자체를 DB 저장값과 일치**시켰다.

| enum | DB 에 저장되는 값 | 영속화되지 않는 부가 필드 |
|---|---|---|
| `UserGender` | `M`, `F`, `ETC` | `displayName` |
| `UserRole` | `ROLE_USER`, `ROLE_SYSTEM`, `ROLE_ADMIN` | `level`, `displayName` |
| `UserStatus` | `PENDING`, `ACTIVE`, `WITHDRAW` | `displayName` |
| `EmailVerificationPurpose` | `SIGNUP`, `PW_RESET` | `displayName`, `mailTitle`, `mailTemplate`, `mailUrl` |

**상수명을 바꾸면 저장된 데이터가 깨진다.** 이름 변경은 데이터 마이그레이션을 동반한다. 부가 필드(표시명, 메일 템플릿 경로 등)는 자유롭게 바꿔도 된다.

### 감사 컬럼

`ApiApplication` 에 `@EnableJpaAuditing` 이 붙어 있고 엔티티마다 `@EntityListeners(AuditingEntityListener.class)` 를 단다. `@CreatedBy` / `@LastModifiedBy` 와 `AuditorAware` 는 없으므로 "누가 바꿨는가"는 기록되지 않는다.

타임존은 애플리케이션이 아니라 DB·컨테이너 레벨에서 맞춘다. compose 세 벌 모두 MySQL 에 `TZ: Asia/Seoul` 을 주고, 운영 JDBC URL 에만 `serverTimezone=Asia/Seoul` 이 붙는다(`api/compose.prod.yml:41`, `infra/deploy/compose.yml:38`). 개발용 `.env` 의 URL 에는 쿼리 파라미터가 없다.

## 리포지토리 규약

세 개 모두 인터페이스 하나뿐이고 커스텀 구현체가 없다.

| 리포지토리 | 선언 | 메서드 |
|---|---|---|
| `UserRepository` | `JpaRepository<User, Integer>` | `findByUserIdAndStatusNot(int, UserStatus)`, `findByEmailAndStatusNot(String, UserStatus)` |
| `EmailVerificationRepository` | `JpaRepository<EmailVerification, Integer>` | 없음 (`findById` / `save` 등 상속 메서드만 사용) |
| `ObjectStorageFileRepository` | `JpaRepository<ObjectStorageFile, Integer>` | `findByStorageObjectKeyAndIsDeletedFalse(String)` |
| `PlaylistRepository` | `JpaRepository<Playlist, Integer>` | `findByPlaylistIdAndDeletedAtIsNull(int)`, `findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(User)` |

- **파생 쿼리 메서드만 쓴다.** `@Query` · `EntityManager` · `Specification` · `Pageable` 사용이 저장소 전체에 0건이다. 이 관례를 깨야 한다면(정렬·페이징·조인 페치 등) 왜 필요한지부터 정리하고 시작한다.
- **삭제/숨김 조건을 메서드 이름에 박는다.** `…AndStatusNot`, `…AndIsDeletedFalse`. Hibernate 의 `@Where` / `@SQLDelete` 같은 전역 필터는 쓰지 않는다(0건).
- 호출부는 `UserServiceImpl`, `EmailServiceImpl`, `StorageServiceImpl`, `auth/provider/AuthProvider` 네 곳이다. `AuthProvider.auth()` 가 **인증이 필요한 모든 요청마다** `findByUserIdAndStatusNot` 을 호출하므로, `users` 조회 비용은 곧 전체 API 지연이 된다.

## 트랜잭션과 더티체킹

경계는 `*ServiceImpl` 클래스에 `@Transactional` 을 붙여 잡는다. `org.springframework.transaction.annotation.Transactional` 을 쓴다.

| 클래스 | 클래스 레벨 | 메서드 레벨 |
|---|---|---|
| `user/service/UserServiceImpl` | `@Transactional` | 없음 |
| `user/service/UserImageServiceImpl` | `@Transactional` | `getUserImageData()` 에 `readOnly = true` |
| `email/service/EmailServiceImpl` | `@Transactional` | `isValid()` 에 `readOnly = true` |
| `storage/service/StorageServiceImpl` | `@Transactional` | `getFileData()`·`getFile()`·`isFileExists()` 에 `readOnly = false` (무의미한 중복, 아래 함정 참고) |

**엔티티 수정은 setter 후 더티체킹에 맡기고 `save()` 를 다시 부르지 않는다.** 실제 사례:

- `UserServiceImpl.updateUser()` — setter 만 호출하고 수정된 엔티티를 그대로 반환한다
- `UserImageServiceImpl.updateUserImage()` — `user.setImageFile(storageFile)` 만 한다
- `StorageServiceImpl.uploadFile()` — 같은 키의 메타데이터가 이미 있으면 setter 로 갱신하고, 없을 때만 `repository.save(...)` 를 부른다

`save()` 를 명시적으로 부르는 곳은 신규 INSERT 세 군데뿐이다(`UserServiceImpl.createUser`, `EmailServiceImpl.sendVerification`, `StorageServiceImpl.uploadFile` 의 신규 분기).

## 삭제 정책 — 둘 다 soft

일관돼 있지 않다. 삭제를 건드리기 전에 어느 쪽이 의도인지 확인한다.

| 대상 | 방식 | 구현 |
|---|---|---|
| `object_storage_files` | soft delete | `deleteFile()` 이 `setIsDeleted(true)` 로 더티체킹 UPDATE. S3 객체는 실제로 지운다. 조회는 `findByStorageObjectKeyAndIsDeletedFalse(...)` 와 `getFile()` 안의 `getIsDeleted()` 수동 검사 |
| `playlists` | soft delete (`deleted_at` 에 시각 기록) | 조회를 `…AndDeletedAtIsNull` 로 거른다. 삭제 API 는 아직 없다 |
| `users` | soft delete (`UserStatus.WITHDRAW`) | ⚠ **현재 코드는 하드 삭제** — `UserServiceImpl.deleteUser()` 가 `repository.delete(user)` (`UserServiceImpl.java:80`). 정책 위반이며 고쳐야 한다 |

정책은 **soft delete** 다 ([decision/0013](../decision/0013-soft-delete-policy.md)). 모든 조회가 `…AndStatusNot(WITHDRAW)` 로 거르는 것도 이 전제 위에 있다. 그런데 실제 삭제 경로는 행을 지운다 — 고쳐야 할 결함이다. 또한 `email_verifications.user_id` 가 `users` 를 참조하므로 DB 에 FK 제약을 걸면 인증 이력이 있는 회원의 하드 삭제는 실패한다.

## 스키마 관리 — 기준은 ERD

**설계의 기준은 ERD** 이고([domain/erd.md](../domain/erd.md)) 엔티티는 그것을 따라간다. `ddl-auto` 는 `none` 이라 실제 반영은 앱 밖에서 사람이 한다. 마이그레이션 도구는 도입하지 않는다 → [decision/0011](../decision/0011-jpa-manages-schema.md)

DDL 원문은 ERD 산출물인 `api/knowledge/domain/erd.sql` 에 있다. 앱이 이 파일을 읽지 않으므로 테이블 생성·변경은 사람이 직접 실행한다.

| 확인 | 결과 |
|---|---|
| Flyway / Liquibase 의존성 | 없음 — **의도된 것** |
| `schema.sql`, `data.sql` | 없음 — 의도된 것 |
| 저장소 전체 `*.sql` | `api/clickhouse/init/01-schema.sql` 하나 — ClickHouse 전용 |
| `spring.jpa.hibernate.ddl-auto` | `none` (로컬·운영 공통). 미기재이나 Boot 기본값이 `none` 이라 동작 동일 |

### `ddl-auto` 는 `none` 이다

로컬·운영 모두 `none` 으로 정해져 있다. **애플리케이션은 테이블을 만들지도 바꾸지도 않는다.** 엔티티를 고쳐도 실제 컬럼은 따라오지 않으므로 **DDL 을 직접 실행해야 한다.**

`application.yaml` 에 `spring.jpa` 키 자체가 없는데, MySQL 은 비내장 DB 라 Spring Boot 기본값이 이미 `none` 이어서 동작은 동일하다.

`application.yaml` 의 datasource 는 세 줄이 전부다.

```yaml
  datasource:
    url: ${MUPID_MYSQL_URL}
    username: ${MUPID_MYSQL_USER}
    password: ${MUPID_MYSQL_PASSWORD}
```

`spring.datasource.hikari.*` 도 없다. 같은 파일의 `clickhouse:` 블록은 풀 설정을 이유 주석까지 붙여 명시한다 — "풀 설정을 안 쓰는 팀"이 아니라 MySQL 쪽만 손대지 않은 상태다.

### 스키마를 바꿔야 할 때

1. **엔티티를 고친다.** 스키마의 출처는 엔티티다.
2. **대응 DDL 을 직접 실행한다.** `ddl-auto: none` 이라 자동 반영 경로가 없다.
3. 배포 스택의 앱 계정은 DDL 을 실행할 수 있다 — `infra/deploy/prepare-env.py:16` 이 `GRANT ALL PRIVILEGES ON mupid.*` 를 준다.
4. `infra/OPERATIONS.md` 는 이미지 롤백이 DB 스키마를 되돌리지 않는다고 경고한다. 이전 버전과의 호환성을 고려한다.
5. MySQL 백업이 매일 한국시간 03:00~03:05 에 돈다(`infra/OPERATIONS.md`). `infra/README.md` 는 스키마 변경 작업과 백업 시간을 겹치지 않게 하라고 적는다.
6. CI 는 잡아주지 않는다. `infra/ci/backend.yml` 첫 줄이 "Build-only CI by team decision; application tests are not executed." 이고 `gradlew build -x test` 만 돈다. 유일한 테스트도 `ApiApplicationTests.contextLoads()` 하나라 리포지토리 계약을 검증하지 않는다.

## Spring Batch — 스타터만 있고 아무것도 없다

`api/build.gradle:24-25` 에 `spring-boot-starter-batch` 와 `spring-boot-starter-batch-jdbc` 가, `:67-68` 에 대응 테스트 스타터가 들어 있다. 그런데 사용 흔적은 0이다.

| 확인 | 결과 |
|---|---|
| `api/src` 전체 `org.springframework.batch` import | 0건 |
| `Job` / `Step` / `Tasklet` / `ItemReader` / `ItemWriter` 선언 | 0건 |
| `@EnableBatchProcessing`, `JobRepository`, `JobLauncher` | 0건 |
| `application.yaml` 의 `spring.batch.*` | 없음 (`initialize-schema` 미지정) |
| 저장소 전체 `BATCH_JOB_INSTANCE` 등 메타 테이블 DDL | 없음 |

**JobRepository 메타 테이블은 코드로도 설정으로도 준비돼 있지 않다.** 실제 배치 잡을 만들 때 `BATCH_*` 테이블 생성 방법(`spring.batch.jdbc.initialize-schema` 설정이든 수동 DDL 이든)을 별도로 정해야 한다. 스타터가 걸려 있으니 준비돼 있다고 가정하지 말 것.

## 경계 — JPA 로 다루지 않는 것

| 저장소 | 접근 방식 | 위치 |
|---|---|---|
| ClickHouse (분석) | `clickHouseJdbcTemplate`. 엔티티 없음 | `analytics/config/ClickHouseConfig.java` |
| Redis (캐시) | `StringRedisTemplate` 만 사용. `@RedisHash` · Redis 리포지토리 0건 | `cache/service/CacheServiceImpl.java` |
| S3 (파일 본문) | AWS SDK `S3Client`. MySQL 에는 메타데이터만 | `storage/service/StorageServiceImpl.java` |

`ClickHouseConfig` 의 클래스 주석이 근거를 명시한다 — 엔티티/트랜잭션 모델이 맞지 않고 쓰는 쿼리도 집계와 대량 INSERT 뿐이라 JdbcTemplate 으로 충분하다는 것. 같은 주석이 ClickHouse `HikariDataSource` 를 빈으로 등록하지 않는 이유도 적어 뒀다: `DataSource` 타입 빈이 둘이 되면 `DataSourceAutoConfiguration` 이 물러나 JPA 가 쓰는 MySQL DataSource 를 직접 재현해야 하고, `DataSource` 마다 헬스 컨트리뷰터가 붙어 `/api/actuator/health` 가 ClickHouse 상태까지 집계하게 된다.

그 대가로 `common/config/JdbcTemplateConfig` 가 MySQL `JdbcTemplate` 을 `@Primary` 로 되살린다. `clickHouseJdbcTemplate` 빈이 생기는 순간 `JdbcTemplateAutoConfiguration` 의 `@ConditionalOnMissingBean(JdbcOperations.class)` 가 깨지기 때문이다. **이 클래스를 지우면 MySQL JdbcTemplate 이 조용히 사라진다.**

## 접속 설정

DB 접속값은 `MUPID_` 접두사 변수 세 개로만 주입한다. `api/AGENTS.md` 는 "모든 환경 변수는 `MUPID_` 접두사를 쓴다"를 강한 규칙으로 못 박고, `SPRING_DATA_REDIS_HOST` 같은 스프링 관례 변수로 우회하지 말라고 적는다.

| 변수 | 용도 |
|---|---|
| `MUPID_MYSQL_URL` | JDBC URL |
| `MUPID_MYSQL_USER` | 계정 |
| `MUPID_MYSQL_PASSWORD` | 비밀번호 |

세 변수 모두 `application.yaml` 에서 **기본값 없이** 참조되므로 없으면 기동이 실패한다. 주입 경로는 실행 방식마다 다르다.

| 실행 | 주입 |
|---|---|
| 호스트 개발 | `spring.config.import: optional:file:.env[.properties]` 가 작업 디렉터리의 `api/.env` 를 읽는다 |
| `api/compose.prod.yml` | `env_file: .env` 로 전체를 넣고 `environment:` 가 `MUPID_MYSQL_URL` 을 컨테이너 네트워크 주소로 덮는다 |
| `infra/deploy/compose.yml` | `api.environment:` 에 열거된 변수만 들어간다 (`MUPID_MYSQL_USER`, `MUPID_MYSQL_PASSWORD`, `MUPID_MYSQL_URL` 포함) |

운영 두 경로 모두 `MUPID_MYSQL_URL` 을 `jdbc:mysql://mysql:3306/mupid?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul` 로 고정한다.

## 이 영역의 함정

상세는 [함정과 실패 사례](../failure/database-and-jpa.md) 참고. 요약만 적는다.

- **`ddl-auto: none` 이라 스키마가 자동 반영되지 않는다.** 엔티티 변경과 DDL 실행은 별개 작업이다.
- **`EmailVerification.verifiedAt` 은 `nullable = true` 다.** null 이 "미인증" 을 뜻한다. ERD 도 이 컬럼을 NULL 허용으로 두므로 NOT NULL 로 바꾸면 인증 메일 발송이 곧바로 실패한다. 한동안 엔티티가 `nullable = false` 로 잘못 선언돼 있었다.
- **회원 탈퇴가 soft delete 가 아니라 행 삭제다.** FK 제약을 걸면 깨진다.
- **이메일 유일성이 DB 제약이 아니라 애플리케이션 검사에만 있다.** 그 검사조차 `statusNot(WITHDRAW)` 로 필터되고 동시 요청을 막지 못한다.
- **`ObjectStorageFile.fileSizeBytes` 가 `Integer` 다.** 업로드 경로가 `Math.toIntExact(...)` 로 좁히므로 2GB 이상에서 `ArithmeticException` 이 난다.
- **`.env` 의 MySQL 포트가 문서·compose(13306)와 다를 수 있다.** DB 작업 전에 `MUPID_MYSQL_URL` 의 포트를 반드시 확인한다.
- **`StorageServiceImpl` 의 읽기 메서드에 붙은 `@Transactional(readOnly = false)` 는 아무 효과가 없다.** 이 표기를 보고 "여기서는 쓰기가 된다"고 판단하지 말 것.
- **CI 가 테스트를 돌리지 않는다.** 영속성 변경은 로컬에서 직접 확인해야 한다.
