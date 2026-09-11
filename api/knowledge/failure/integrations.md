# 외부 연동 (스토리지 · Gmail · 메일 템플릿)

11건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## 외부 연동 (SeaweedFS · Gmail · 메일 템플릿)

### 업로드가 `NoSuchBucket` 으로 실패한다

- **원인**: SeaweedFS 는 compose 가 넘긴 `S3_BUCKET` 환경변수를 **읽지 않는다.** 버킷이 자동 생성되지 않는다.
- **해결**: 1회성 `storage-init` 컨테이너가 `echo "s3.bucket.create -name ${MUPID_S3_BUCKET}" | weed shell -master=mupid-dev-storage:9333` 로 만든다(별도 컨테이너라 마스터를 `127.0.0.1` 이 아니라 서비스 이름으로 가리킨다. 이미 있는 버킷에도 exit 0 이라 멱등). `docker compose up` 에서 `storage` 만 띄우고 `storage-init` 을 빼면 조용히 깨진다. 이미지 ENTRYPOINT 가 `weed` 라서 셸을 쓰려면 entrypoint 를 덮어써야 한다. `MUPID_S3_BUCKET` 이 비면 `${MUPID_S3_BUCKET:?.env 에 MUPID_S3_BUCKET 설정 필요}` 로 즉시 실패한다.
- **근거**: `api/compose.yml`

### SeaweedFS 컨테이너가 영구히 unhealthy 로 남는다

- **원인**: 헬스체크가 `GET /` 를 때렸다. `/` 는 S3 ListBuckets 라 자격증명이 필요하고, `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` 를 설정하는 순간 SeaweedFS 가 S3 관리자 identity 를 만들어 익명 요청은 전부 403 이 된다.
- **해결**: 인증 없이 200 을 주는 `wget -qO- http://127.0.0.1:8333/status` 를 쓴다. (`8333/healthz`, `9333/cluster/status` 도 200 이지만, 앱이 실제 쓰는 S3 게이트웨이를 검사하려고 `/status` 를 골랐다.)
- **근거**: `api/compose.yml`

### 1MB 를 넘는 프로필 사진이 413 이 아니라 500 을 낸다

- **원인**: `UserImageServiceImpl.USER_IMAGE_FILE_MAX_SIZE` 는 5MB 이고 `USER_IMAGE_FILE_SIZE_EXCEEDED`(413)를 던지도록 돼 있지만, `application.yaml` 에 `spring.servlet.multipart.*` 설정이 하나도 없다. 스프링 기본 `max-file-size` 는 5MB 보다 작으므로 파일이 **컨트롤러에 닿기 전에** 거절되고, `MaxUploadSizeExceededException` 전용 핸들러가 없어 `@ExceptionHandler(Exception.class)` 로 떨어져 500 `MUPID_INTERNAL_SERVER_ERROR` 가 나간다. nginx 의 `client_max_body_size 20m` 은 병목이 아니다.
- **해결**: 5MB 정책을 실제로 적용하려면 `spring.servlet.multipart.max-file-size`(와 `max-request-size`)를 명시하거나 전용 핸들러를 추가한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/user/service/UserImageServiceImpl.java`, `api/src/main/resources/application.yaml`

### Content-Type 헤더 없는 멀티파트 파트가 NOT NULL 제약에 걸린다

- **원인**: `StorageServiceImpl.uploadFile` 이 `file.getContentType()` 을 그대로 `PutObjectRequest.contentType()` 과 `ObjectStorageFile.contentType` 에 넣는데 후자는 `@Column(nullable = false)` 다. 업로드 검사는 `MultipartFileUtils.isImageExt` 로 **확장자만** 보므로 헤더가 없어도 검사를 통과한다.
- **해결**: null 이면 기본값을 넣거나 검사에서 거른다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/storage/service/StorageServiceImpl.java:41-64`

### 어느 날 갑자기 모든 메일 발송이 실패한다

- **원인**: Gmail 은 서비스 계정이 아니라 **리프레시 토큰 하나**에 매달려 있다. `GmailConfig` 는 `UserCredentials`(clientId + clientSecret + refreshToken)로 만들어지고 `.env.example` 은 토큰 발급처를 OAuth Playground 로 안내한다. 토큰이 만료·취소되거나(테스트 게시 상태의 OAuth 동의 화면은 만료가 짧다) 발신 계정 비밀번호가 바뀌면 전부 실패한다.
- **해결**: 기동 시점에는 검증하지 않으므로(빈 생성은 네트워크 호출 없이 성공) 첫 메일 발송에서야 드러난다는 걸 전제하고 확인한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/common/gmail/GmailConfig.java`, `api/.env.example:19-23`

### 메일에 인증 버튼이 안 보인다

- **원인**: 대부분 `MUPID_CLIENT_WEBSITE` 미설정이다. `application.yaml:66` 의 `mupid-client.website: ${MUPID_CLIENT_WEBSITE:}` 는 기본값이 빈 문자열이고, `EmailServiceImpl:66` 은 `!mupidClientWebsite.isBlank()` 일 때만 `verifyUrl` 을 모델에 넣는다. `button.html` 의 `primaryButton` 은 `th:if="${!#strings.isEmpty(#strings.trim(url))}"` 이라 url 이 비면 버튼과 보조 안내문까지 렌더링하지 않는다. 예외도 로그도 없다.
- **해결**: `MUPID_CLIENT_WEBSITE` 를 설정한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/service/EmailServiceImpl.java:66-74`, `api/src/main/resources/templates/mail/fragments/button.html:16-17`

### 사용자가 복사한 인증 코드에 공백이 섞여 인증이 실패한다

- **원인**: 코드 박스의 슬롯 `<span>` 6개를 여러 줄로 포매팅했다. 파일 주석이 명시한다 — "슬롯 span 은 반드시 공백 없이 한 줄로 두어야 합니다." `user-select: all` 폴백으로 클릭 한 번에 전체 선택되는 구조라, 줄바꿈이 들어가면 선택·복사 결과에 공백이 들어간다. 렌더링 결과만 보면 멀쩡해서 발견이 늦다.
- **해결**: HTML 포매터를 이 파일에 돌리지 않는다. 가독성을 이유로 줄바꿈을 넣지 않는다.
- **근거**: `api/src/main/resources/templates/mail/fragments/code-box.html:12,20`

### 메일 본문의 복사 버튼이 동작하지 않는다

- **원인**: Gmail·Outlook·네이버메일 등 대부분의 메일 클라이언트는 `<script>` 를 제거한다(파일 주석 33-35행). 복사 스크립트는 브라우저·웹뷰로 열었을 때만 동작한다. `navigator.clipboard` 도 보안 컨텍스트(https/localhost)에서만 존재한다.
- **해결**: 정상이다. 그래서 슬롯에 `user-select: all` 폴백이 걸려 있고, 스크립트 안에도 `navigator.clipboard` → `execCommand` textarea → 선택 영역 순의 폴백이 있다. **메일 본문에서 JS 동작을 전제한 기능을 새로 넣지 않는다.**
- **근거**: `api/src/main/resources/templates/mail/fragments/code-box.html:33-118`

### 메일의 인증 링크를 눌러도 프론트엔드가 처리하지 못한다

- **원인**: `EmailVerificationPurpose` 는 링크를 `<website>/verify/email` · `<website>/verify/password` 로 만드는데, 프론트엔드 라우트는 `frontend/app/email/verify/page.tsx` 즉 `/email/verify` 하나뿐이다. 게다가 링크 쿼리에는 `action` 과 `code` 만 담기고 `verification_id` 가 없어서, 경로를 맞추더라도 페이지가 `POST /api/v1/mail/verification`(verification_id 필수)을 호출할 수 없다.
- **해결**: 경로와 쿼리 파라미터를 양쪽에서 함께 맞춰야 한다. 현재 `frontend/lib/email-verification.ts` 의 서비스는 항상 `{ status: 'unavailable' }` 을 반환한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/enumerate/EmailVerificationPurpose.java`, `frontend/app/email/verify/page.tsx`, `frontend/lib/email-verification.ts`

### 인증 코드가 URL 쿼리에 실려 서버·프록시 로그에 남는다

- **원인**: 진입 경로가 `/email/verify?action=SIGNUP&code=인증번호` 라서 코드가 **최초 서버 요청 라인**에 포함된다.
- **해결**: 프론트는 `robots: { index: false, follow: false }` 와 `referrer: 'no-referrer'` 를 걸고 코드를 저장·출력하지 않지만 그것만으로는 부족하다. `frontend/docs/email-verification.md` 가 명시한다 — 서버·프록시 로그에서도 `code` 를 마스킹해야 한다. 아직 백엔드에 합의되지 않은 항목: 만료/사용완료/잘못된 코드 오류 구분, 재시도·중복 호출 정책, **이메일 스캐너가 링크를 먼저 열어 코드를 소비하지 않게 하는 정책**, 다른 브라우저·기기에서 열 때 상태 복원. `action` 은 대소문자를 구분하며 누락·중복·미상은 잘못된 링크로 처리한다.
- **근거**: `frontend/docs/email-verification.md`

### 본문 있는 GET 요청 엔드포인트가 둘 있다

- **원인**: `EmailVerificationController.sendAddressVerificationMail`(GET `/api/v1/mail/verification`)과 `UserPasswordResetController.sendReseteMail`(GET `/api/v1/user/reset`)이 `@GetMapping` 에 `@Valid @RequestBody` 를 함께 쓴다. Bruno 컬렉션에도 GET + JSON 바디로 기록돼 있어 의도된 구현이지만, 프록시·브라우저 `fetch`·일부 HTTP 클라이언트가 본문을 버려 `MUPID_NOT_READABLE_MESSAGE` 로 떨어질 수 있다.
- **해결**: 클라이언트를 붙이기 전에 POST 로 바꿀지 결정한다. **새 조회 엔드포인트를 만들 때 이 두 개를 본보기로 삼지 않는다.**
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/controller/EmailVerificationController.java`, `api/docs/Email/folder.yml`

---
