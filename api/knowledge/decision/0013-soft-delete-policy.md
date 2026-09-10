# 0013. 삭제는 soft delete 로 한다

**상태** 채택 · **회원 삭제 코드가 이를 따르지 않음 (버그)**

## 맥락

회원 탈퇴와 파일 삭제에서 레코드를 실제로 지울지, 상태만 바꿀지 정해야 했다. 청취 이벤트가 `user_id` 를 참조하고 분석 데이터가 18개월 보존되므로, 행을 지우면 과거 분석 결과의 참조가 끊긴다.

## 결정

**두 도메인 모두 soft delete 한다.**

| 대상 | 방식 |
|---|---|
| 파일 | `ObjectStorageFile.isDeleted = true` |
| 회원 | `UserStatus.WITHDRAW` 로 전이 |

조회는 삭제 상태를 제외한다.

## 결과

- 탈퇴 후에도 `user_id` 참조가 유지돼 분석 데이터가 고아가 되지 않는다.
- 모든 조회 경로가 삭제 상태를 걸러야 한다. 빠뜨리면 탈퇴 회원이 노출된다.
- 개인정보 파기 요구가 있으면 별도 처리가 필요하다. soft delete 는 파기가 아니다.

## ⚠ 현재 코드가 결정과 다르다

`UserServiceImpl.deleteUser` 가 **`repository.delete(user)` 로 하드 삭제한다.** 조회 쪽은 `UserStatus.WITHDRAW` 를 제외하도록 이미 되어 있어, 코드 안에서 두 정책이 충돌한다.

**`WITHDRAW` 로 상태를 바꾸도록 고쳐야 한다.** 파일 쪽(`isDeleted`)은 이미 결정대로다.

→ [failure/database-and-jpa.md](../failure/database-and-jpa.md)

## 근거

`api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java`, `api/src/main/java/com/osundosun/mupid/api/user/enumerate/UserStatus.java`, `api/src/main/java/com/osundosun/mupid/api/storage/entity/ObjectStorageFile.java`
