# 회원 도메인

MySQL 에 있는 유일한 도메인이다. 엔티티 3개가 전부다.

> 스키마 기준은 엔티티 매핑이지만 `ddl-auto` 는 `none` 이다 — 엔티티를 고쳐도 테이블은 따라오지 않는다. 건드리기 전에 [decision/0011](../decision/0011-jpa-manages-schema.md) 을 읽는다.

## 엔티티

### `users` — `User`

| 필드 | 타입 | 비고 |
|---|---|---|
| `userId` | `Integer` PK | IDENTITY |
| `email` | `String` | `nullable=false`, `updatable=false` — **가입 후 변경 불가** |
| `password` | `String` | 인코딩 저장 |
| `nickname` | `String` | |
| `imageFile` | `ObjectStorageFile` | `@ManyToOne(LAZY)`, nullable — 프로필 사진 |
| `birthYear` | `Integer` | |
| `gender` | `UserGender` | `@Enumerated(STRING)` |
| `role` | `UserRole` | `@Enumerated(STRING)` |
| `status` | `UserStatus` | `@Enumerated(STRING)` |
| `onboardingSubmittedAt` | `LocalDateTime` | nullable |
| `createdAt` / `updatedAt` | | `@CreatedDate` / `@LastModifiedDate` |

### `email_verifications` — `EmailVerification`

| 필드 | 비고 |
|---|---|
| `verificationId` | PK |
| `user` | `@ManyToOne(LAZY)`, nullable — **가입 전 인증은 회원이 없다** |
| `email`, `token` | `updatable=false` |
| `purpose` | `EmailVerificationPurpose` — 가입 인증 / 비밀번호 재설정 구분 |
| `expiredAt` | 만료 시각 |
| `verifiedAt` | `@Column(nullable = false)` — ⚠ 아래 참고 |
| `createdAt` | |

⚠ `verifiedAt` 이 `nullable = false` 인데 **인증 전 레코드에는 값이 있을 수 없다.** 이 매핑을 기준으로 컬럼을 NOT NULL 로 만들면 인증 메일 발송이 INSERT 에서 깨진다 — 매핑을 `nullable = true` 로 고치는 편이 맞다.

### `object_storage_files` — `ObjectStorageFile`

| 필드 | 비고 |
|---|---|
| `fileId` | PK |
| `originalFileName`, `storageObjectKey` | 원본 이름과 실제 저장 키를 분리 |
| `fileSizeBytes` | `Integer` |
| `contentType` | |
| `isDeleted` | 기본 `false` — **soft delete** |
| `createdAt` / `updatedAt` | |

## 상태 흐름

`UserStatus` 로 회원 생애주기를 표현한다.

```
PENDING ──(온보딩 제출)──> ACTIVE ──(탈퇴)──> WITHDRAW
```

**미구현:** `PENDING → ACTIVE` 로 바꾸는 코드가 현재 없다. `onboardingSubmittedAt` 을 기록할 온보딩 API 도 없다.

## 삭제 정책 — 둘 다 soft

| 대상 | 방식 |
|---|---|
| 파일 | `isDeleted = true` |
| 회원 | `UserStatus.WITHDRAW` 로 전이 |

탈퇴 후에도 `user_id` 참조가 유지돼야 청취 이벤트 등 분석 데이터가 고아가 되지 않는다 → [decision/0013](../decision/0013-soft-delete-policy.md)

⚠ **현재 `UserServiceImpl.deleteUser` 는 `repository.delete(user)` 로 행을 지운다.** 정책 위반이며 `WITHDRAW` 전이로 고쳐야 한다. 파일 쪽은 이미 올바르다.

## 공통 상위 엔티티가 없다

`@MappedSuperclass` 를 두지 않아 `createdAt`/`updatedAt`/`@EntityListeners` 를 엔티티마다 반복 선언한다. 새 엔티티도 **기존 형태를 그대로 따른다.**

## 근거

`api/src/main/java/com/osundosun/mupid/api/{user,email,storage}/entity/`, [architecture/persistence.md](../architecture/persistence.md)
