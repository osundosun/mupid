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

## ✅ 코드가 결정을 따른다 (2026-09-18 확인)

`UserServiceImpl.deleteUser` 는 **`setStatus(UserStatus.WITHDRAW)`** 로 상태만 옮긴다.
한때 `repository.delete(user)` 로 행을 지웠고 이 문서가 그 충돌을 경고했는데, 그 뒤
고쳐졌다. 비밀번호는 자리표시자로 무력화한다 — soft delete 는 파기가 아니므로
자격증명만 따로 버린다는 이 문서의 단서를 코드가 그대로 따른다.
`UserSoftDeleteTest` 가 행 잔존·입구 차단·자격증명 무력화를 각각 못박는다.

→ [failure/database-and-jpa.md](../failure/database-and-jpa.md)

## 곡 소프트 삭제 — 쓰기 경로가 빠져 있었다 (해소됨 2026-09-18)

`music.is_deleted` 는 파일과 같은 방식이다. 조회는 전부 걸러 주지만,
**분류·분석을 쓰는 두 서비스가 곡을 `findById` 로 직접 찾아 `isDeleted` 를 보지
않았다** — 서버에서 삭제된 곡의 분류가 배치에 의해 조용히 바뀔 수 있었다.
`getMusic()`(살아 있는 곡만 돌려준다)으로 고쳤다.

이 결정 문서의 "모든 조회 경로가 삭제 상태를 걸러야 한다" 는 문장은 **쓰기 경로의
대상 확인까지 포함한다** — 조회는 잘못된 결과를 보여 주는 데 그치지만, 쓰기는 남의
행을 지운다. → [failure/database-and-jpa.md](../failure/database-and-jpa.md)

## 근거

`api/src/main/java/com/osundosun/mupid/api/user/service/UserServiceImpl.java`, `api/src/main/java/com/osundosun/mupid/api/user/enumerate/UserStatus.java`, `api/src/main/java/com/osundosun/mupid/api/storage/entity/ObjectStorageFile.java`
