# 데이터베이스 · JPA · 트랜잭션

30건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## 트랜잭션과 JPA

### 수정 API 를 호출했는데 예외 없이 변경만 사라진다

- **원인**: 수정 로직에 `save()` 가 없다. `UserServiceImpl.updateUser` 는 setter 만 호출하고 `repository.save()` 를 부르지 않는다. `UserImageServiceImpl.updateUserImage`(`user.setImageFile`), `EmailServiceImpl.validateToken`(`verification.setVerifiedAt`), `StorageServiceImpl.uploadFile` 의 메타데이터 갱신 경로도 전부 더티 체킹에 의존한다.
- **해결**: 클래스 레벨 `@Transactional` 을 제거하지 않는다. 이 메서드들을 트랜잭션 밖에서 호출하지 않는다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java:67-75`

### `@Transactional(readOnly = false)` 가 붙은 읽기 메서드를 보고 "여기선 쓰기가 된다"고 판단한다

- **원인**: `StorageServiceImpl` 의 `getFileData`(69행), `getFile`(101행), `isFileExists`(116행)에 `@Transactional(readOnly = false)` 가 붙어 있다. 클래스 기본값과 같아서 아무 효과가 없는 중복이다. 게다가 `UserImageServiceImpl.getUserImageData()` 는 `readOnly = true` 로 시작해 `getFileData()` 를 호출하므로, 기존 트랜잭션에 참여하는 이 표기는 무시된다.
- **해결**: 이 파일을 템플릿으로 복사하지 않는다. 새 읽기 메서드에는 `readOnly = true` 를 쓴다(`UserImageServiceImpl.getUserImageData`, `EmailServiceImpl.isValid` 가 올바른 예).
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/service/StorageServiceImpl.java`

### 메일 발송이 실패하면 인증 레코드까지 사라진다

- **원인**: `EmailServiceImpl` 은 클래스 레벨 `@Transactional` 이고 `sendVerification` 은 `repository.save(verification)`(59행) 후에 `gmailProvider.sendEmail(...)`(77행)을 호출한다. `GmailException` 은 RuntimeException 이라 트랜잭션이 롤백되고 방금 만든 `EmailVerification` 이 사라진다.
- **해결**: 발송 실패 이력을 남기려면 별도 전파 설정이나 아웃박스가 필요하다. 반대로 순서를 뒤집어 커밋 후 발송하면 "코드는 남고 메일만 안 가는" 상태가 된다 — 어느 쪽이 나은지 먼저 정한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/service/EmailServiceImpl.java:47-79`

### S3 삭제가 실패하면 소프트 삭제까지 되돌아간다

- **원인**: `deleteFile` 이 `metaData.setIsDeleted(true)` 와 `s3Client.deleteObject(...)` 를 같은 트랜잭션에서 한다.
- **해결**: 의도된 동작인지 확인하고 쓴다. 더 중요한 건 **삭제가 DB 소프트 / S3 하드로 비대칭**이라는 점이다 — 소프트 삭제를 보고 "복구 가능"이라고 가정하면 안 된다. 남는 건 메타데이터뿐이고 실제 바이트는 사라진다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/service/StorageServiceImpl.java:87-98`

### 엔티티를 고쳤는데 실제 컬럼이 따라오지 않는다 / 테이블이 아예 없다

- **원인**: `spring.jpa.hibernate.ddl-auto` 가 로컬·운영 모두 `none` 이다 ([decision/0011](../decision/0011-jpa-manages-schema.md)). **애플리케이션은 테이블을 만들지도 컬럼을 바꾸지도 않는다.** 엔티티만 고치고 DDL 을 실행하지 않으면 기동은 성공하고 해당 컬럼을 실제로 조회하는 순간 깨진다.
- **해결**: 엔티티 변경과 **DDL 직접 실행**을 한 작업으로 묶는다. `ddl-auto` 를 켜거나 Flyway 를 넣는 것은 해결책이 아니다 — 둘 다 의도적으로 배제한 선택이다.
- **근거**: `api/src/main/resources/application.yaml`, `api/build.gradle`

### compose 로 띄운 MySQL 에 스키마를 넣었는데 앱은 테이블이 없다고 한다

- **원인**: 포트가 다르다. `api/.env.example`·`api/README.md`·`api/compose.yml` 은 개발 MySQL 을 **13306**(`"${MUPID_MYSQL_PORT:-13306}:3306"`)으로 안내하지만, 실제 `api/.env` 의 `MUPID_MYSQL_URL` 은 `jdbc:mysql://localhost:3306/mupid` 다. 즉 현재 로컬 실행은 `mupid-dev-mysql` 컨테이너가 아니라 **호스트에서 도는 MySQL** 을 본다.
- **해결**: DB 작업 전에 `api/.env` 의 `MUPID_MYSQL_URL` 포트를 반드시 확인한다.
- **근거**: `api/.env:10`, `api/.env.example:6`, `api/compose.yml`

### 기준 데이터 SQL 을 넣었는데 한글이 깨져 들어간다 (오류 없이)

- **원인**: `docker exec -i ... mysql ... < seed-*.sql` 이 `--default-character-set` 없이 돌면 클라이언트가 파일의 UTF-8 바이트를 **latin1 로 읽어 다시 인코딩**한다. `보컬 소개팅형` 이 `ë³´ì»¬ ì†Œê°œíŒ…í˜•` 로 들어간다. **오류가 나지 않는다** — 행 수도 맞고 SQL 도 성공한다.
- **증상**: `SELECT CHAR_LENGTH(title)` 이 눈에 보이는 글자 수보다 훨씬 크다(7 글자가 19). `HEX(LEFT(title,1))` 가 `C3AB`(= `Ã«`) 처럼 나오면 그렇게 들어간 것이다. 정상이면 한글 한 글자가 `EBB3B4` 같은 3바이트다.
- **해결**: `mysql` 에 `--default-character-set=utf8mb4` 를 붙인다. 이미 들어간 행은 고칠 수 없으므로 지우고 다시 넣는다. `seed-codes.sql` 머리말에 이 플래그가 붙은 명령을 적어 두었다. (`seed-mubti.sql` 은 S15P21A204-344 이후 부트스트랩이 그 표를 채우면서 없앴다.)
- **기동 때 잡는다**: `BootstrapRunner` 가 `codes` 를 읽을 때 이렇게 들어간 행을 찾아
  경고로 남긴다(`recoverDoubleEncoded`). 판정은 되돌려 보는 것이다 — 한 글자씩 바이트
  하나로 되돌릴 수 있고, 그 바이트열이 온전한 UTF-8 이며, 되살린 값에 한글이 있으면
  그렇다. **순수 cp1252 로는 절반만 잡힌다** — `0x81`·`0x8D`·`0x8F`·`0x90`·`0x9D`
  다섯 칸은 cp1252 에 정의가 없는데 MySQL `latin1` 은 그대로 흘려보낸다. `몽환적인` 의
  UTF-8 에 `0x81` 이 들어 있다.
- **배치는 고치지 못한다**: `BootstrapRunner` 는 (용도, 이름) 으로 대조하므로 깨진 행을
  "없는 코드" 가 아니라 **다른 코드**로 본다. 제대로 된 이름이 따로 들어오면 두 벌이
  공존하고 온보딩 화면에 선택지가 두 배로 뜬다. 2026-09-15 운영 DB 가 그 상태였다
  (무드 16개 중 8개가 깨진 중복).
- **지울 때**: `code_id` 를 `onboarding_survey` 가 참조한다. 참조부터 확인한다.

  ```sql
  -- 1) 깨진 행 확인 (한글 한 글자가 EBB3B4 같은 3바이트면 정상)
  SELECT code_id, code_type, code_name, HEX(code_name), CHAR_LENGTH(code_name)
    FROM codes WHERE code_type = 'MOOD';

  -- 2) 그 행을 참조하는 응답이 있는가
  SELECT s.mood_code_id, COUNT(*) FROM onboarding_survey s
    JOIN codes c ON c.code_id = s.mood_code_id
   WHERE c.code_name NOT REGEXP '^[가-힣 ]+$' GROUP BY s.mood_code_id;

  -- 3) 참조가 없을 때만
  DELETE FROM codes WHERE code_type = 'MOOD' AND code_name NOT REGEXP '^[가-힣 ]+$';
  ```
- **근거**: `api/knowledge/domain/seed-codes.sql`,
  `api/src/main/java/com/osundosun/mupid/api/bootstrap/BootstrapRunner.java`

### `DATETIME` 컬럼에 "그날의 마지막 순간" 을 넣으면 다음 날이 된다

- **원인**: `reports.end_date` 같은 `DATETIME`(초 정밀도)에 `23:59:59.999999999` 를 넣으면 **다음 날 00:00:00 으로 반올림**된다. 잘리는 게 아니라 올라간다.
- **해결**: 애초에 반열린 구간 `[start, end)` 로 정하고 다음 날 00:00 을 넣는다. 값은 같아지지만 의도가 코드에 남는다. `ReportSnapshotWriter` 가 그렇게 되어 있다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/report/service/ReportSnapshotWriter.java`

### 인증 메일 발송이 INSERT 에서 곧바로 실패한다

- **원인**: `EmailVerification.verifiedAt` 이 `@Column(nullable = false)`(83행)인데 `EmailServiceImpl.sendVerification` 의 빌더(52-58행)는 이 값을 채우지 않는다. 값은 나중에 `validateToken()` 이 넣고, `validateToken()`·`isValid()` 는 `getVerifiedAt() != null` 로 미인증 상태를 판별한다. 코드는 null 을 정상 상태로 쓰는데 매핑만 NOT NULL 을 선언하고 있다.
- **해결**: 이 컬럼은 NULL 허용이어야 한다. 매핑을 `nullable = true` 로 고치는 편이 맞다. `ddl-auto: none` 이라 매핑만으로는 드러나지 않지만, 이 매핑을 기준으로 테이블을 NOT NULL 로 만들면 발송이 깨진다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/entity/EmailVerification.java:82-83`

### 회원 탈퇴가 상태 전환이 아니라 행 삭제다 (정책 위반)

- **원인**: 삭제 정책은 **soft delete** 다 ([decision/0013](../decision/0013-soft-delete-policy.md)). `UserStatus.WITHDRAW`("삭제된 사용자")가 있고 `UserRepository` 의 두 조회 메서드와 `AuthProvider.auth()` 가 모두 `…AndStatusNot(UserStatus.WITHDRAW)` 로 거르는 것도 그 전제 위에 있다. 그런데 `UserServiceImpl.deleteUser()` 는 `repository.delete(user)` 로 실제 행을 지운다(78-81행).
- **영향**: `email_verifications.user_id` 가 `users` 를 참조하므로 FK 제약을 걸면 인증 이력이 있는 회원 삭제가 실패하고, 성공하면 인증 이력이 함께 사라진다. 청취 이벤트(`listen_events.user_id`)도 참조가 끊겨 분석 데이터가 고아가 된다(ClickHouse 쪽은 TTL 18개월 동안 남는다).
- **해결**: `deleteUser()` 를 `setStatus(WITHDRAW)` 전이로 고친다. 파일 쪽(`ObjectStorageFile.isDeleted`)은 이미 정책대로다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java:77-81`

### 같은 이메일로 가입이 두 번 된다

- **원인**: `User.email` 은 `@Column(nullable = false, updatable = false)` 일 뿐 `unique = true` 도 `@Table(uniqueConstraints = …)` 도 없다(저장소 전체에 `@Index`/`uniqueConstraints` 0건). 중복 방지는 `UserServiceImpl.createUser()` 의 `getUserByEmail(...).isPresent()` 검사뿐인데, 이 조회는 `statusNot(WITHDRAW)` 로 필터되므로 탈퇴 상태 행과는 애초에 충돌을 감지하지 못한다. 동시 요청도 막지 못한다.
- **해결**: 테이블을 만들 때 유니크 인덱스를 넣을지 판단한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/entity/User.java`, `api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java:49-52`

### 2GB 넘는 파일을 올리면 `ArithmeticException` 이 난다

- **원인**: `ObjectStorageFile.fileSizeBytes` 가 `Integer` 이고 업로드 경로가 `int fileSize = Math.toIntExact(file.getSize());` 로 좁힌다.
- **해결**: 지금은 프로필 이미지 경로뿐이고 `USER_IMAGE_FILE_MAX_SIZE`(5MB)가 가려준다. 음원처럼 큰 파일을 이 테이블로 다루려면 컬럼 타입부터 `Long`(BIGINT)으로 바꾼다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/entity/ObjectStorageFile.java:56`, `api/src/main/java/com/osundosun/mupid/api/storage/service/StorageServiceImpl.java:49`

### 프로필 사진을 지웠다 다시 올리면 같은 키의 메타데이터 행이 새로 쌓인다

- **원인**: `deleteFile` 은 `isDeleted = true` 로만 바꾼다. 재업로드 시 `uploadFile` 의 중복 키 갱신 경로가 `getFileByKey` → `findByStorageObjectKeyAndIsDeletedFalse` 를 쓰므로 삭제 표시된 행을 못 찾고 `storageObjectKey` 가 같은 새 행을 insert 한다. `object_storage_files.storage_object_key` 에 유니크 제약도 없다.
- **해결**: 키(`user/image/{userId}`)로 메타데이터를 역추적하는 코드를 새로 쓸 때 이 중복을 전제한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/service/StorageServiceImpl.java:51-65, 87-98`

### MySQL 커넥션 풀이 ClickHouse 처럼 튜닝돼 있을 거라 생각한다

- **원인**: `application.yaml` 에 `spring.datasource.hikari.*` 가 하나도 없다. 같은 파일의 `clickhouse:` 블록은 `max-pool-size: 8`, `min-idle: 1`, `connection-timeout: 3s`, `validation-timeout: 3s` 를 이유(주석)까지 명시하고 있어 착각하기 쉽다.
- **해결**: MySQL 풀 튜닝이 필요하면 처음부터 새로 정한다.
- **근거**: `api/src/main/resources/application.yaml:51-60`

### 로컬 MySQL 이 `erd.sql` 과 전혀 다르다 — 해소됨 (2026-09-17)

- **원인**: 로컬 `mupid-dev-mysql`(13306)의 표는 `erd.sql` 로 만든 것이 아니라 **과거
  `ddl-auto: update` 가 만든 것**이었다. 지금은 `none` 이라 아무도 바꾸지 않으니
  그 모습 그대로 남아 있었다. 표 30개와 이름은 전부 맞아서 겉으로는 멀쩡해 보인다.
  지문은 이렇다 — FK 이름이 `FKncfaarj6gwd37j1fi98bmpmcl` 같은 해시, 모든 문자열이
  `varchar(255)`, `TINYINT(1)` 대신 `bit(1)`, `DATE` 대신 `datetime(6)`,
  `DEFAULT` 와 COMMENT 가 전부 없음.
- **무엇이 깨져 있었나**:
  - `object_storage_files.category` 에 `TRACK`·`MUBTI` 가 없었다. `FileCategory` enum 에는
    있어서 **곡 커버와 MuBTI 캐릭터 이미지는 저장 자체가 불가능**했다. 그래서
    `music.image_file_id` 가 전 행 NULL 이고, 곡 상세의 `LEFT JOIN FETCH m.imageFile` 이
    늘 NULL 만 줬다.
  - UNIQUE 가 거의 다 없었다 — `ix_codes_type_name`, `uq_email_verification_token`,
    `uq_daily_recommendation_user_date`, `uq_playlist_song`, `ux_mubti_title` 등.
  - 복합 PK 컬럼 순서가 알파벳순으로 뒤집혀 있었다. `music_genres`·`music_moods` 는
    선두가 `code_id` 라 `WHERE music_id IN (...)` 이 PK 를 못 탔다.
  - `playlist_tracks.user_id` 가 NOT NULL 이라 시스템 생성 트랙을 넣을 수 없었다.
  - `music_reactions` 에 FK 가 하나도 없었다.
- **해결**: `erd.sql` 기준으로 ALTER 를 만들어 한 번에 맞췄다. 데이터는 그대로 두고
  구조만 바꾼다. **순서가 중요하다** — ① FK 전부 해제 ② Hibernate 인덱스 제거
  ③ 컬럼·PK·주석 교정 ④ ERD 인덱스 생성 ⑤ ERD 이름으로 FK 재생성. FK 를 먼저 풀지
  않으면 컬럼 타입 변경이 막힌다.
- **적용 전에 반드시**: 백업(`mysqldump --single-transaction`)을 뜨고, **백업을 복원한
  사본 DB 에서 먼저 돌린다.** 좁히는 컬럼(`varchar(255)` → `VARCHAR(6)` 등)과 새로
  거는 UNIQUE 는 기존 데이터가 걸리면 그 자리에서 실패한다. 사전 점검 쿼리는
  길이 초과·중복·NULL 잔존·음수(UNSIGNED 전환)·JSON 유효성을 센다.
- **남은 것**: `mubti.image_file_id` 는 ERD 가 NOT NULL 이지만 기존 16행이 전부 NULL 이라
  적용하지 못했다. 가리킬 MUBTI 이미지가 생기면 그때 `NOT NULL` 로 올린다.
- **근거**: `api/knowledge/domain/erd.sql`, [domain/erd.md](../domain/erd.md) 의 "남은 차이"

### 인증 메일 만료 레코드가 계속 쌓인다

- **원인**: 이메일 인증은 Redis TTL 이 아니라 MySQL 테이블(`email_verifications`)이다. 만료는 `expiredAt` 컬럼과 `EMAIL_VERIFICATION_EXPIRED_MINUTES = 15` / `EMAIL_VERIFICATION_VALID_EXPIRATION_HOURS = 1` 상수 비교로 판정하고, 만료 레코드를 지우는 배치가 없다.
- **해결**: 인증코드 관련 버그를 Redis 에서 찾지 않는다. 정리 배치가 필요하면 별도로 만든다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/entity/EmailVerification.java`, `api/src/main/java/com/osundosun/mupid/api/email/service/EmailServiceImpl.java:37-38`

### `chunks` INSERT 가 SQL 문법 오류로 깨진다 — 해소됨

- **원인**: `index` 가 MySQL 예약어다. SELECT 는 `c1_0.index` 처럼 별칭이 붙어 통과하지만 INSERT 컬럼 목록에서는 그대로 쓰면 `You have an error in your SQL syntax ... near 'index,stream_id)'` 가 난다. **조회만 해서는 드러나지 않아** 업로드 API 를 만들고서야 터졌다. [erd.md](../domain/erd.md) 가 이미 경고해 둔 지점이다.
- **해결**: `@Column(name = "``index``")` 로 백틱을 붙인다. `AiChatMessage.index`, `MusicSectionAnalysis.index` 는 **아직 그대로다.** 그 테이블에 INSERT 를 처음 쓰는 사람이 같은 데서 넘어진다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/stream/entity/Chunk.java:94`

### 복합키 엔티티 INSERT 에서 `No value specified for parameter N` 이 난다 — 해소됨

- **원인**: `@EmbeddedId` 안의 FK 컬럼을 `insertable = false, updatable = false` 로 막고 `@ManyToOne @JoinColumn` 을 따로 두면, Hibernate 가 INSERT 문에 같은 컬럼을 **두 번** 넣는다 (`insert into chunks (...,stream_id,index,stream_id) values (?,?,?,?,?,?)`).
- **해결**: 파생 식별자는 `@MapsId("streamId")` 를 연관에 붙이고, 임베디드 id 쪽 컬럼은 평범한 `@Column(name = "stream_id")` 로 둔다.
- **다시 밟았다**: `PlaylistTrack` 을 같은 모양(`insertable = false`)으로 만들어 곡 추가 INSERT 가 똑같이 깨졌다. 복합키 엔티티를 새로 만들면 `Chunk` 를 먼저 베껴 온다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/stream/entity/Chunk.java:47`, `api/src/main/java/com/osundosun/mupid/api/playlist/entity/PlaylistTrack.java:54`

### `object_storage_files` INSERT 가 `Data too long for column 'mime'` 로 깨진다 — 해소됨

- **원인**: `mime` 이 `varchar(15)` 다. 브라우저가 아닌 클라이언트(curl·배치)가 멀티파트를 보내면 Content-Type 이 `application/octet-stream`(24자)으로 와서 그대로 저장하다 잘린다.
- **해결**: 호출부가 mime 을 지정하는 `StorageService.uploadFile(..., String mime)` 오버로드를 쓴다. 청크는 `audio/opus` 로 고정한다. **클라이언트가 보낸 Content-Type 을 그대로 컬럼에 넣지 않는다.**
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/service/StorageServiceImpl.java`, `api/src/main/java/com/osundosun/mupid/api/stream/service/ChunkServiceImpl.java:48`

### 앨범 생성이 `Column 'is_deleted' cannot be null` 로 깨진다 — 해소됨

- **원인**: `Album.isDeleted` 에 `@Builder.Default` 가 없어서 빌더로 만들면 null 이다. 같은 자리에 `Artist` 는 `@Builder.Default private Boolean isDeleted = false;` 로 되어 있어 둘을 나란히 보면 눈치채기 어렵다.
- **해결**: NOT NULL + 기본값 컬럼은 엔티티 쪽에도 `@Builder.Default` 를 붙인다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/entity/Album.java`

### 앨범 조회 SQL 에 `a1_0.createdat` 이라는 없는 컬럼이 나온다 — 해소됨

- **원인**: `Album.createdat` 필드명 오타. Hibernate 암묵 네이밍이 그대로 내려가 `Unknown column 'a1_0.createdat' in 'field list'` 가 난다. 다른 엔티티는 모두 `createdAt` 이라 `created_at` 으로 변환된다.
- **해결**: `createdAt` 으로 고쳤다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/entity/Album.java`

### 삭제된 음악을 수정·삭제하면 500 이 난다 — 해소됨

- **원인**: `MusicServiceImpl.getMusic` 이 `Optional<Music>` 을 반환한다고 선언해 놓고 삭제된 곡에 대해 `return null` 을 했다. 호출부의 `.orElseThrow(...)` 가 NPE 로 터진다.
- **해결**: `Optional.empty()` 를 반환한다. `Optional` 반환 메서드는 절대 null 을 돌려주지 않는다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/service/MusicServiceImpl.java`

### `@Modifying` UPDATE 를 쓰면 같은 트랜잭션의 다른 저장이 사라진다 — 해소됨

- **원인**: `@Modifying(clearAutomatically = true)` 는 쿼리를 **보낸 뒤** 영속성 컨텍스트를 비운다. 그런데 `flushAutomatically` 의 기본값이 `false` 라 **아직 반영되지 않은 변경은 내보내지 않는다.** 그래서 `save()` 로 대기 중이던 INSERT 나 엔티티의 dirty 변경이 그대로 **버려진다.** 좋아요를 켤 때 반응 행 저장과 `music.like_count` UPDATE 가 한 트랜잭션에 있었는데, 같은 요청을 두 번 보내면 카운트가 계속 오르고 좋아요→관심없음 전환에서는 카운트가 줄지 않았다.
- **해결**: `@Modifying(flushAutomatically = true, clearAutomatically = true)`. 그리고 곡 존재 확인을 `findById` 대신 `existsBy...` 로 바꿔 `Music` 엔티티를 아예 컨텍스트에 올리지 않게 했다 — 올려 두면 UPDATE 뒤 낡은 값이 남는다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/repository/MusicRepository.java`, `api/.../music/service/MusicReactionServiceImpl.java`

### 복합키 엔티티를 저장하면 키 컬럼이 NOT NULL 위반으로 터진다 — 해소됨

- **원인**: `MusicReaction.MusicReactionId` 의 `user_id`·`music_id` 가 `@Column(insertable = false, updatable = false)` 였다. 그 값을 채워 줄 `@ManyToOne` 연관도 없어서 **어느 경로로도 INSERT 에 실릴 수 없었다.** `Chunk` 가 밟은 것과 같은 종류다.
- **해결**: 두 컬럼에서 `insertable`·`updatable` 를 뺐다. 연관 매핑이 따로 없는 `@EmbeddedId` 는 키 컬럼이 쓰기 가능해야 한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/entity/MusicReaction.java`

### `PlayHistory` 를 저장하면 `play_history` 테이블이 없다고 한다 — 해소됨

- **원인**: 엔티티에 `@Table` 이 없어 Hibernate 암묵 네이밍이 클래스명 그대로 `play_history` 로 내려갔다. 실제 표는 `play_histories` 다. 같은 패키지의 `SearchHistory` 에는 `@Table(name = "search_histories")` 가 붙어 있어 한쪽만 빠진 것이었다. 덤으로 PK 가 DB 에서는 `bigint` 인데 엔티티는 `Integer` 였다.
- **해결**: `@Table(name = "play_histories")` 를 붙이고 `playHistoryId` 를 `Long` 으로, 리포지토리도 `JpaRepository<PlayHistory, Long>` 으로 바꿨다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/history/entity/PlayHistory.java`

### 프로필 사진을 올려도 계정에 붙지 않는다 — 해소됨

- **원인**: `UserImageServiceImpl.handleUploadImageAfter` 가 `SecurityUtils.getAuthUser()` 로 꺼낸 인증 principal 에 바로 `setImageFile` 을 했다. 이 `User` 는 액세스 토큰으로 만든 것이라 **영속 상태가 아니다.** 파일은 올라가고 `users.image_file_id` 는 NULL 로 남아, 프로필은 계속 기본 이미지였고 삭제는 `MUPID_USER_NOT_FOUND` 가 났다.
- **해결**: `userService.getUser(principal.getUserId())` 로 DB 에서 다시 읽어 고친다. 사진을 바꾸면 이전 파일도 지운다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserImageServiceImpl.java`

### 인증 principal 의 연관을 읽으면 LazyInitializationException 이 난다 — 해소됨

- **원인**: `getProfileImageUrl` 이 principal 의 `user.getImageFile()` 을 읽었다. 그 프록시는 인증 필터가 쓰던 **이미 닫힌 세션**에 묶여 있어, 호출부에 `@Transactional` 을 붙여도 초기화되지 않는다. `GET /api/v1/user` 가 통째로 500 이 됐다.
- **해결**: 지금 세션에서 `userService.getUser(userId)` 로 다시 읽는다. principal 은 식별자만 쓰고 연관은 건드리지 않는다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserImageServiceImpl.java`

### 구간 분석을 넣으면 `index` 근처에서 문법 오류가 난다 — 해소됨

- **원인**: `MusicSectionAnalysis.index` 가 컬럼명 그대로 `index` 로 내려가는데 MySQL 예약어다. `Chunk.index` 가 이미 같은 것을 밟았는데 이쪽은 그대로 남아 있었다.
- **해결**: `@Column(name = "\`index\`")`. 예약어는 엔티티 쪽에서 백틱으로 못 박는다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/music/entity/MusicSectionAnalysis.java`

### 일일 추천을 읽으면 `recommendation_id` 라는 없는 컬럼이 나온다 — 해소됨

- **원인**: `DailyRecommendation` 의 PK 필드가 `recommendationId` 인데 실제 컬럼은 `daily_recommendation_id` 다. `@Column` 이 없어 Hibernate 암묵 네이밍이 `recommendation_id` 로 내려갔다. 덤으로 `recommendation_date` 가 DB 에서는 `DATE` 인데 엔티티는 `LocalDateTime` 이라, 쓸 때 시각이 잘린다.
- **해결**: `@Column(name = "daily_recommendation_id")` 를 붙이고 날짜는 `LocalDate` 로 바꿨다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/recommendation/entity/DailyRecommendation.java`

### `@EmbeddedId` 엔티티를 저장했는데 감사 필드(`createdAt`)가 null 이다

- **원인**: 복합키를 코드에서 직접 채워 넣으므로 `SimpleJpaRepository.save()` 의 "새 엔티티인가" 판단(id 가 null 인가)이 **false** 가 되어 `persist` 가 아니라 `merge` 가 돈다. `merge` 는 관리 상태의 **복사본**을 돌려주고, `@CreatedDate` 는 그 복사본에만 채워진다. 내가 넘긴 인스턴스는 준영속 상태 그대로라 `getCreatedAt()` 이 null 이다. 응답 DTO 를 그 인스턴스로 만들면 `created_at: null` 이 나간다.
- **해결**: `save()` / `saveAndFlush()` 의 **반환값**을 쓴다. 넘긴 인스턴스를 계속 쓰지 않는다. (근본 해결은 `Persistable` 구현이지만, 이 프로젝트의 복합키 엔티티는 아직 그렇게 하지 않는다.)
- **근거**: `api/src/main/java/com/osundosun/mupid/api/playlist/service/PlaylistServiceImpl.java:91`, `api/src/test/java/com/osundosun/mupid/api/playlist/PlaylistTrackAddTest.java`
