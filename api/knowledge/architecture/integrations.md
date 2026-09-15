# 외부 연동 (S3 · Gmail · LLM 게이트웨이)

이 저장소가 밖으로 나가는 경로는 셋이다 — S3 호환 오브젝트 스토리지, Gmail API, 그리고 OpenAI 호환 LLM 게이트웨이. 프로필 사진을 다루거나, 메일을 새로 보내거나, 메일 템플릿을 고치거나, AI 대화를 손보거나, `MUPID_S3_*` / `MUPID_GMAIL_*` / `MUPID_AI_*` 값을 세팅하다 막혔을 때 이 문서를 읽는다. 실제로 밟게 되는 함정의 상세는 [함정과 실패 사례](../failure/integrations.md) 에 있다.

## 한눈에 보기

| 연동 | 설정 접두사 | 구성 클래스 | 진입점 | 소비자 |
|---|---|---|---|---|
| S3 오브젝트 스토리지 | `aws.s3` | `storage/config/StorageConfig.java` | `StorageService` | `UserImageServiceImpl` (유일) |
| Gmail API | `gmail` | `common/gmail/GmailConfig.java` | `GmailProvider.sendEmail` | `EmailServiceImpl` (유일) |
| 메일 템플릿 | 없음 (Boot 기본값) | 없음 | `EmailTemplateService.buildHtml` | `EmailServiceImpl` (유일) |
| LLM 게이트웨이 | `ai` | `ai/config/AiConfig.java` | `OpenAiClient.complete` | `AiChatServiceImpl` (유일) |

네 경로 모두 소비자가 하나뿐이다. 새 소비자를 붙이기 전에 아래의 키 규칙·트랜잭션 경계를 먼저 확인할 것.

네 경로 모두 재시도·백오프·`@Async`·아웃박스가 없다. 실패는 예외로 올라간다 (LLM 경로만 트랜잭션 밖이다 — 아래 참고).

---

## S3 · 오브젝트 스토리지

### 파일 배치

```
api/src/main/java/com/osundosun/mupid/api/storage/
├── config/StorageConfig.java              # S3Client 빈
├── properties/StorageProperties.java      # @ConfigurationProperties("aws.s3") record
├── dto/StorageFileData.java               # (filename, MediaType, byte[]) + getEncodedFilename()
├── entity/ObjectStorageFile.java          # @Table("object_storage_files")
├── error/StorageErrorCode.java            # FILE_NOT_FOUND 하나뿐
├── repository/ObjectStorageFileRepository.java
└── service/StorageService.java, StorageServiceImpl.java
```

### 개발 / 운영은 `endpoint` 하나로 갈린다

`api/src/main/java/com/osundosun/mupid/api/storage/config/StorageConfig.java` 는 Spring Cloud AWS 같은 래퍼 없이 `S3Client` 를 직접 만든다. 개발 환경이 AWS 가 아니라 SeaweedFS 이기 때문이다.

```java
S3ClientBuilder builder = S3Client.builder()
    .region(Region.of(props.region()))
    .credentialsProvider(StaticCredentialsProvider.create(credential));
if (props.endpoint() != null &&  !props.endpoint().isBlank()) {
    builder
        .endpointOverride(URI.create(props.endpoint()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
}
```

- `@Profile` 도 조건부 빈도 없다. `MUPID_S3_ENDPOINT` 값이 있냐 없냐로만 갈린다.
- path-style 은 endpoint 오버라이드와 **한 세트로** 켜진다. SeaweedFS 가 virtual-host 스타일 버킷 주소를 받지 못하기 때문이다.
- 자격증명은 `AwsBasicCredentials` 정적 키다. IAM 역할·인스턴스 프로파일·프로파일 체인을 쓰지 않는다.
- `overrideConfiguration` 을 손대지 않으므로 재시도는 AWS SDK 기본 정책이 전부다.

`api/.env.example` 이 이 계약을 주석으로 못박아 뒀다.

```
MUPID_S3_ENDPOINT=  # 개발은 SeaweedFS (http://localhost:8333), 운영은 빈 값
```

주의: `region` 은 `.env` 로 뺄 수 없다. `api/src/main/resources/application.yaml` 에 `region: "SeaweedFS"` 리터럴로 박혀 있다. 실제 AWS 로 붙일 때 반드시 걸린다 — [함정과 실패 사례](../failure/integrations.md) 참고.

### 오브젝트 키 규칙

`<도메인>/<리소스>/<식별자>` 평문. UUID·확장자·타임스탬프를 붙이지 않는다.

```java
String key = "user/image/%d".formatted(userId);   // UserImageServiceImpl
```

즉 **키는 리소스당 고정**이고 같은 사용자의 재업로드는 같은 키를 덮어쓴다. 이 전제가 아래 업로드 동작과 짝을 이룬다.

### 업로드 / 조회 / 삭제 흐름

`api/src/main/java/com/osundosun/mupid/api/storage/service/StorageServiceImpl.java` 는 `S3Client` · `StorageProperties` · `ObjectStorageFileRepository` 세 개만 의존한다. SDK 동기 클라이언트를 그대로 쓰고, 전송 매니저·프리사인 URL·멀티파트 업로드는 쓰지 않는다.

| 메서드 | S3 | DB (`object_storage_files`) |
|---|---|---|
| `uploadFile(key, file)` | `PutObjectRequest` + `RequestBody.fromInputStream(...)` | 같은 키의 살아 있는 행이 있으면 더티 체킹으로 갱신, 없으면 `save` |
| `getFileData(fileId)` | `getObjectAsBytes` | 메타데이터에서 파일명·MIME 타입을 읽음 |
| `deleteFile(fileId)` | `deleteObject` (하드 삭제) | `isDeleted = true` (소프트 삭제) |
| `getFile(fileId)` | — | `findById` 후 자바에서 `isDeleted` 검사 |
| `getFileByKey(key)` / `isFileExists(key)` | — | `findByStorageObjectKeyAndIsDeletedFalse` |

순서가 고정돼 있다. **업로드는 S3 먼저, 메타데이터 나중.** 삭제는 `isDeleted` 먼저, `deleteObject` 나중이며 둘이 같은 트랜잭션 안에 있다.

- 삭제가 비대칭이라는 점을 놓치지 말 것. 소프트 삭제로 남는 건 메타데이터뿐이고 실제 바이트는 사라진다.
- 삭제된 행은 `...AndIsDeletedFalse` 조회에 걸리지 않으므로 같은 키로 메타데이터 행이 여러 개 쌓일 수 있다. `storage_object_key` 에 유니크 제약은 없다.

상세는 [함정과 실패 사례](../failure/integrations.md) 에.

### 메타데이터를 DB 에 남기는 이유

오브젝트 키(`user/image/3`)에는 원본 파일명도 확장자도 없다. 다운로드할 때 되돌릴 정보가 오브젝트 쪽에 없으므로 DB 가 원본을 들고 있어야 한다. `ObjectStorageFile` 의 컬럼은 전부 `nullable = false` 다.

| 필드 | 용도 |
|---|---|
| `fileId` | `IDENTITY` PK |
| `originalFileName` | 다운로드 시 `Content-Disposition` 파일명 |
| `storageObjectKey` | S3 키 |
| `fileSizeBytes` | `Integer` — `Math.toIntExact(file.getSize())` 로 넣는다 |
| `contentType` | 다운로드 시 `Content-Type`. `MultipartFile.getContentType()` 을 그대로 신뢰 |
| `isDeleted` | 소프트 삭제 플래그 (기본 `false`) |
| `createdAt` / `updatedAt` | `@CreatedDate` / `@LastModifiedDate` (`ApiApplication` 의 `@EnableJpaAuditing`) |

`getFileData` 는 S3 의 `GetObjectResponse` 메타데이터를 보지 않고 DB 값으로 `MediaType.parseMediaType(...)` 을 만든다. 따라서 **DB 메타데이터가 진실**이다.

`User` 엔티티가 이 행을 FK 로 참조한다.

```java
@ManyToOne(fetch = FetchType.LAZY, optional = true)
@JoinColumn(name = "image_file_id", nullable = true)
private ObjectStorageFile imageFile;
```

덕분에 프로필 사진 존재 여부를 S3 조회 없이 판정한다.

참고: `object_storage_files` 의 스키마 기준은 엔티티 매핑이다. 별도 DDL 파일은 두지 않으며 `ddl-auto` 도 `none` 이다 ([decision/0011](../decision/0011-jpa-manages-schema.md)).

### 유일한 소비자 — 프로필 사진

`api/src/main/java/com/osundosun/mupid/api/user/service/UserImageServiceImpl.java` 가 스토리지의 단독 소비자다.

- **업로드**: `imageFile.getSize() > USER_IMAGE_FILE_MAX_SIZE`(`1024 * 1024 * 5`) → `USER_IMAGE_FILE_SIZE_EXCEEDED`(413), `MultipartFileUtils.isImageExt` 실패 → `INVALID_USER_IMAGE_FILE_FORMAT`(400). 통과하면 업로드 후 `user.setImageFile(storageFile)`.
- **삭제**: `imageFile` 이 없으면 그냥 반환. 있으면 `user.setImageFile(null)` 후 `storageService.deleteFile(...)`.
- **조회**: `imageFile` 이 `null` 이면 `ClassPathResource("img/default-user-profile.png")` 를 `StorageFileData` 로 감싸 반환한다. 이건 "없음" 폴백이지 "실패" 폴백이 아니다 — S3 가 죽으면 그대로 예외가 올라간다.

`MultipartFileUtils.isImageExt` 는 **확장자만** 본다 (`jpg`, `jpeg`, `png`, `gif`, `webp`, 소문자 비교). 매직 넘버도 Content-Type 도 검사하지 않는다.

엔드포인트는 `api/src/main/java/com/osundosun/mupid/api/user/controller/UserImageController.java` (`/api/v1/user/image`).

| 메서드 | 경로 | 응답 |
|---|---|---|
| `POST` (multipart, `file`) | `/api/v1/user/image` | `BaseResponse` |
| `DELETE` | `/api/v1/user/image` | `BaseResponse` |
| `GET` | `/api/v1/user/image/{userId}` | **`BaseResponse` 로 감싸지 않는다.** 바이너리 + `Content-Disposition: attachment` |

파일명은 `StorageFileData.getEncodedFilename()` 이 `URLEncoder` 로 인코딩하고 `+` 를 `%20` 으로 바꿔 넣는다.

`SecurityConfig` 의 permitAll 목록에 `/api/v1/user/image/**` 가 **없다.** `anyRequest().authenticated()` 가 적용되므로 남의 프로필 사진을 볼 때도 액세스 토큰이 필요하다. 즉 S3 를 직접 노출하지 않고 항상 API 를 경유한다 — 퍼블릭 버킷도 프리사인 URL 도 쓰지 않는다. (`api/docs/User/프로필 사진 조회.yml` 에 같은 내용이 적혀 있다.)

### 개발 스토리지 띄우기 (SeaweedFS)

`api/compose.yml` 의 두 서비스가 담당한다. `api/compose.prod.yml` 에는 스토리지 서비스가 없다.

| 서비스 | 이미지 | 하는 일 |
|---|---|---|
| `storage` | `chrislusf/seaweedfs` | `server -dir=/data -s3 -filer`. 포트 8333 / 9333 / 8888 |
| `storage-init` | `chrislusf/seaweedfs` | 1회성. `weed shell` 로 `s3.bucket.create -name ${MUPID_S3_BUCKET}` |

```bash
cd api
docker compose up -d    # storage 와 storage-init 을 함께 띄운다
```

- 8333 이 앱이 붙는 S3 게이트웨이다. 9333 은 master 로, `storage-init` 이 `weed shell -master=mupid-dev-storage:9333` 으로 붙는다. 8888 은 앱도 `storage-init` 도 쓰지 않는다.
- `storage-init` 은 정상 종료(exited 0)되는 것이 기대 동작이다. `s3.bucket.create` 는 이미 있는 버킷에도 exit 0 이라 반복 실행이 안전하다.
- `storage` 만 띄우고 `storage-init` 을 빼면 버킷이 없어 업로드가 `NoSuchBucket` 으로 깨진다. 헬스체크 경로도 `/` 가 아니라 `/status` 여야 한다. 둘 다 [함정과 실패 사례](../failure/integrations.md) 에.
- 버킷 생성/확인을 손으로 할 때의 명령은 `api/clickhouse/README.md` 의 "S3 버킷이 만들어지지 않았다" 절에 있다.

### 스토리지를 건드릴 때 지킬 것

- 키는 `<도메인>/<리소스>/<식별자>` 평문. 확장자·UUID 를 붙이지 않는다.
- 조회 메서드 이름은 `...AndIsDeletedFalse` 로 짓는다. 삭제된 행을 걸러내는 건 리포지토리 쪽 책임이다.
- 도메인 에러는 `ErrorCode` 구현 enum + `BusinessException`. 응답 코드 문자열은 `getCode()` 기본 구현이 `MUPID_<enum 이름>` 으로 만든다.
- AWS SDK v2 는 BOM 으로 버전을 고정하고 개별 모듈은 버전 없이 선언한다.
- 엔드포인트를 추가하면 `api/docs/` 의 Bruno 컬렉션에도 같은 이름의 yml 을 추가한다.

---

## Gmail

### 파일 배치

```
api/src/main/java/com/osundosun/mupid/api/common/gmail/
├── GmailConfig.java       # Gmail 빈
├── GmailProperties.java   # @ConfigurationProperties("gmail") record
├── GmailProvider.java     # sendEmail(to, subject, htmlBody) -> messageId
└── GmailException.java    # RuntimeException
```

### 인증은 서비스 계정이 아니라 리프레시 토큰이다

`api/src/main/java/com/osundosun/mupid/api/common/gmail/GmailConfig.java`:

```java
UserCredentials credentials = UserCredentials.newBuilder()
    .setClientId(props.clientId())
    .setClientSecret(props.clientSecret())
    .setRefreshToken(props.token())
    .build();
return new Gmail.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            new HttpCredentialsAdapter(credentials)
        )
        .setApplicationName("Email-Service")
        .build();
```

`ServiceAccountCredentials` 가 아니라 `UserCredentials` 다. `api/.env.example` 이 토큰 발급처를 `# https://developers.google.com/oauthplayground` 로 적어 둔 것과 맞물려, **발신 계정 하나를 OAuth2 로 승인해 그 리프레시 토큰으로 보내는 구성**임을 뜻한다. 앱 비밀번호 SMTP 가 아니라 OAuth2 이므로 발신 계정에 2단계 인증이 걸려 있어도 동작한다.

빈 생성 자체는 네트워크 호출을 하지 않는다. 토큰이 죽어 있어도 애플리케이션은 정상 기동하고 **첫 발송에서야 터진다** — [함정과 실패 사례](../failure/integrations.md) 참고.

### jakarta.mail 은 전송이 아니라 직렬화용이다

`api/build.gradle` 에 `spring-boot-starter-mail` 이 없다. `jakarta.mail:jakarta.mail-api`(API) + `org.eclipse.angus:jakarta.mail`(구현) 조합이고, SMTP 로 보내지 않는다.

`GmailProvider.sendEmail` 흐름:

1. `Session.getDefaultInstance(new Properties())` — Properties 가 비어 있다. SMTP 호스트가 없고 `Transport.send` 도 호출하지 않는다. `MimeMessage` 를 만들기 위한 껍데기다.
2. `MimeMessage` 조립 — `setFrom(new InternetAddress(gmailProps.sendAddress(), "뮤피드", "UTF-8"))`, `addRecipient(TO, ...)`, `setSubject(subject, "UTF-8")`, `setContent(htmlBody, "text/html; charset=UTF-8")`, `setSentDate(new Date())`.
3. `writeTo(ByteArrayOutputStream)` → `Base64.getUrlEncoder().encodeToString(bytes)` → `Message.setRaw(...)`.
4. `gmailClient.users().messages().send("me", message).execute()` — 사용자 id 는 리터럴 `"me"`, 즉 리프레시 토큰의 주인이 곧 발신자다.

`MimeMessage` 를 쓰는 이유는 한글 발신자명·UTF-8 제목의 RFC822 헤더 인코딩을 직접 짜지 않기 위해서다. 여기에 SMTP 설정을 추가해도 아무 효과가 없다.

`sendEmail` 은 Gmail 메시지 id 를 반환하지만 `EmailServiceImpl` 은 이 값을 쓰지 않는다. 발송 이력을 남기려면 여기서부터 손대야 한다.

### 메일을 새로 보낼 때

- 발송은 반드시 `GmailProvider` 를 거친다. `Gmail` 빈을 직접 주입해 쓰는 코드는 현재 없다.
- 본문은 항상 HTML 이다. `setContent` 가 `text/html` 고정이라 평문 파트나 첨부는 지금 구조로 못 보낸다.
- 제목은 `EmailServiceImpl` 이 `"[뮤피드] %s".formatted(purpose.getMailTitle())` 로 만든다. 접두사를 빼려면 여기를 고친다.

---

## 메일 템플릿 (Thymeleaf)

### 파일 배치

```
api/src/main/resources/templates/mail/
├── email-verification.html      # SIGNUP
├── password-reset.html          # PW_RESET
└── fragments/
    ├── layout.html              # base(title, content)
    ├── button.html              # primaryButton(url, label)
    └── code-box.html            # codeBox(code) + copyScript
```

`api/src/main/java/com/osundosun/mupid/api/email/service/EmailTemplateServiceImpl.java` 는 `org.thymeleaf.spring6.SpringTemplateEngine` 을 주입받아 `Context` 에 변수를 넣고 `process(templateName, context)` 만 호출한다. **TemplateResolver 를 직접 설정하지 않는다** — `spring-boot-starter-thymeleaf` 의 기본값(`classpath:/templates/`, 접미사 `.html`)에 의존하므로 템플릿 이름은 `mail/email-verification` 처럼 확장자 없는 경로다.

### 템플릿 선택은 enum 이 한다

`api/src/main/java/com/osundosun/mupid/api/email/enumerate/EmailVerificationPurpose.java` 가 `(displayName, mailTitle, mailTemplate, mailUrl)` 을 들고 있다.

| 상수 | `mailTitle` | `mailTemplate` | `mailUrl` |
|---|---|---|---|
| `SIGNUP` | `이메일 인증 번호를 보내드립니다.` | `mail/email-verification` | `/verify/email` |
| `PW_RESET` | `패스워드 초기화 인증 번호를 보내드립니다.` | `mail/password-reset` | `/verify/password` |

`EmailTemplateService` 는 `buildHtml(String templateName, Map<String,Object>)` 한 메서드짜리 인터페이스다. **목적을 추가하면 enum 상수 한 줄로 제목·템플릿·클라이언트 경로가 같이 따라온다.** 서비스에 분기를 넣지 않는다.

### 모델 변수 계약

`EmailServiceImpl.sendVerification` 이 채운다.

| 변수 | 필수 | 채워지는 조건 |
|---|---|---|
| `code` | 필수 | 항상. 6자리 토큰(`0-9A-Z`), 유효 15분 |
| `userName` | 템플릿별 | `user != null` 일 때만. `user.getNickname()` |
| `verifyUrl` | 선택 | `mupid-client.website` 가 비어 있지 않고 `purpose.getMailUrl()` 이 있을 때만 |

`verifyUrl` 은 `UriComponentsBuilder` 로 만든다 — `<website><mailUrl>?action=<enum 이름>&code=<토큰>`.

`PW_RESET` 템플릿은 `userName` 을 필수로 적어 뒀고, 실제로 `UserPasswordResetController` 가 `User` 를 넘긴다. 반면 `EmailVerificationController` 는 `SIGNUP` 에 `null` 을 넘기므로 `userName` 이 없다.

각 템플릿 상단의 HTML 주석이 이 계약을 적어 둔다. **템플릿을 추가하면 같은 형식의 주석을 반드시 남긴다.**

### 메일 HTML 제약

`layout.html` 은 `width="100%"` + `max-width: 600px` 의 table 레이아웃이고 스타일이 전부 인라인이다. 외부 CSS 도 `<style>` 블록도 없다. 새 조각도 같은 방식으로 쓴다.

`button.html` 의 `primaryButton` 은 `th:if="${!#strings.isEmpty(#strings.trim(url))}"` 로 감싸여 있어 url 이 null·빈 문자열·공백이면 버튼과 보조 안내문까지 통째로 렌더링되지 않는다. `mupid-client.website` 의 기본값이 빈 문자열(`${MUPID_CLIENT_WEBSITE:}`)이라, 주소가 없어도 메일이 깨지지 않게 한 설계다. 예외도 로그도 남지 않으므로 "버튼이 안 보인다"의 원인은 대개 이 환경변수다.

`code-box.html` 은 메일 클라이언트 제약을 흡수하는 지점이고, 주석에 근거가 남아 있다.

- Gmail·Outlook·네이버메일 등은 `<script>` 를 제거한다. `copyScript` 는 브라우저/웹뷰로 열었을 때만 동작하고, 제거된 환경에서는 슬롯의 `user-select: all` 이 폴백으로 동작한다. 스크립트 안에도 `navigator.clipboard` → `execCommand` textarea → 선택 영역 순의 3단 폴백이 있다.
- 슬롯 `<span>` 6개는 **공백 없이 한 줄**로 두어야 한다. 줄바꿈이 들어가면 복사 결과에 공백이 섞인다. 렌더링 결과만 보면 멀쩡해서 발견이 늦다. HTML 포매터를 돌릴 때 특히 주의 — [함정과 실패 사례](../failure/integrations.md) 에.

메일 본문에서 JS 동작을 전제한 기능을 새로 넣지 않는다.

### 이미지

`api/src/main/resources/img/default-user-profile.png` 하나뿐이고, 참조도 `UserImageServiceImpl.getUserImageData` 한 곳뿐이다. 메일 템플릿은 이미지를 전혀 쓰지 않는다.

---

## LLM 게이트웨이 (OpenAI 호환)

Mupid AI 대화(`POST /api/v1/ai/chat`)가 쓴다. 기본 대상은 SSAFY GMS 프록시이고, 경로가 OpenAI 와 같기만 하면 `MUPID_AI_BASE_URL` 하나로 다른 게이트웨이나 로컬 모델로 갈아탈 수 있다.

### 파일 배치

```
api/src/main/java/com/osundosun/mupid/api/ai/
├── config/AiConfig.java                # RestClient 빈 (타임아웃만 잡는다)
├── properties/AiProperties.java        # @ConfigurationProperties("ai") record
├── client/AiJson.java                  # 와이어 전용 ObjectMapper (SNAKE_CASE)
├── client/OpenAiClient.java            # chat/completions 한 방 호출
├── client/OpenAi*.java                 # 요청·응답 레코드
├── tool/AiRecommendTool(+Impl).java    # 곡 추천 툴 — 지금은 목업
├── service/AiChatService(+Impl).java   # 대화 루프 (툴 실행·재질의)
└── controller/AiController.java
```

### 대화 한 번의 흐름

```
messages = [developer 프롬프트, (이전 대화), user 메시지]
  → POST {base-url}/chat/completions   (tools: recommend_music)
  → tool_calls 가 오면 서버가 툴을 실행하고 tool 메시지를 붙여 다시 호출
  → content 가 오면 그것이 답변                       최대 ai.max-tool-rounds 회
```

`tool` 메시지는 **자신을 부른 assistant 메시지 바로 뒤**에 와야 한다. 모델이 돌려준 assistant 메시지를 그대로 되붙이지 않으면 게이트웨이가 거부한다.

### 알아 둘 것

- **Authorization 헤더를 `AiConfig` 에서 붙이지 않는다.** 키가 비었을 때 `Bearer ` 만 실린 요청이 나가면 저쪽 401 을 우리 오류로 오해한다. 키 검사는 `OpenAiClient` 가 하고, 없으면 요청을 만들지 않고 503(`AI_NOT_CONFIGURED`)을 낸다.
- **와이어 JSON 은 애플리케이션 매퍼를 쓰지 않는다.** Boot 4 의 Jackson 빈은 Jackson 3(`tools.jackson`)이라 `com.fasterxml` ObjectMapper 를 주입할 수 없고, `tool_calls`·`tool_call_id` 같은 이름은 우리 설정이 아니라 저쪽 API 가 정한 계약이다. `AiJson` 이 전용 매퍼를 들고 있다 — `EventServiceImpl` 과 같은 이유.
- **본문은 바이트로 오간다.** `application/json` 에 charset 을 적지 않아도 한글이 깨지지 않게 하려는 것이다.
- **`AiChatServiceImpl` 에 `@Transactional` 이 없다.** DB 작업은 곡 한 건 조회뿐인데, 감싸면 응답을 기다리는 수십 초 동안 커넥션을 쥐게 된다.
- **곡 선정은 LLM 이 하지 않는다.** 모델은 원하는 느낌을 한 문장으로 정리해 `recommend_music` 툴에 넘기고, 곡은 추천 파이프라인이 고른다. 지금은 그 자리가 목업(`AiRecommendToolImpl`)이라 질의를 보지 않고 카탈로그 앞쪽 곡에 정해진 사유를 붙인다 — **곡 번호만 진짜다.** 없는 번호를 돌려주면 화면이 재생할 수 없는 줄을 그린다. 외부 추천 API(`ai_server/` 의 `POST /internal/recommendations/{daily,next,search}`)를 붙이는 날 바뀌는 것은 이 클래스 하나다. 대화가 쓰는 자연어 경로(`search`)는 해석기가 없으면 501 이므로, 붙일 때 `next` 로 갈아탈지 먼저 정해야 한다 (S15P21A204-219).
- **대화를 저장하지 않는다.** `ai_chat_sessions`·`ai_chat_messages` 엔티티는 있지만 쓰지 않는다. 켜려면 DDL 부터 봐야 한다 — ERD 의 `ai_chat_session_id` 는 `AUTO_INCREMENT` 없는 `INT NOT NULL` 인데 엔티티는 `GenerationType.IDENTITY` 다.
- gpt-5 계열이라 시스템 프롬프트 역할이 `system` 이 아니라 **`developer`** 이고, `temperature`·`max_tokens` 를 보내지 않는다.

## 설정과 자격증명

### 의존성

`api/build.gradle` 의 한글 주석 그룹에 맞춰 넣는다.

```gradle
// Gmail 관련
implementation 'com.google.api-client:google-api-client:2.7.0'
implementation 'com.google.http-client:google-http-client-gson:1.45.0'
implementation 'com.google.apis:google-api-services-gmail:v1-rev20240520-2.0.0'
implementation 'com.google.auth:google-auth-library-oauth2-http:1.24.1'
implementation 'jakarta.mail:jakarta.mail-api:2.1.3'
implementation 'org.eclipse.angus:jakarta.mail:2.0.3'

// S3(오브젝트 스토리지) 관련
implementation platform('software.amazon.awssdk:bom:2.25.16')
implementation 'software.amazon.awssdk:s3'
```

AWS SDK v2 는 모듈이 많고 버전이 어긋나면 런타임에 깨지므로 BOM 으로 고정한다. 개별 모듈은 버전 없이 선언한다. 현재 `s3` 하나뿐이다 — STS·프리사이너·transfer-manager 를 쓰지 않는다는 뜻이다.

### 환경변수

`api/src/main/resources/application.yaml` 은 자격증명과 환경별 값을 직접 적지 않고 `${MUPID_...}` 로만 참조한다. 예외는 `aws.s3.region` 하나다. 풀 크기·타임아웃 같은 환경과 무관한 값은 리터럴로 적혀 있다.

```yaml
gmail:
  client-id: ${MUPID_GMAIL_CLIENT_ID}
  client-secret: ${MUPID_GMAIL_CLIENT_SECRET}
  token: ${MUPID_GMAIL_TOKEN}
  send-address: ${MUPID_GMAIL_SEND_ADDRESS}

aws:
  s3:
    endpoint: ${MUPID_S3_ENDPOINT}
    region: "SeaweedFS"
    access-key: ${MUPID_S3_ACCESS_KEY}
    secret-key: ${MUPID_S3_SECRET}
    bucket-name: ${MUPID_S3_BUCKET}

ai:
  base-url: ${MUPID_AI_BASE_URL:https://gms.ssafy.io/gmsapi/api.openai.com/v1}
  api-key: ${MUPID_AI_GMS_KEY:${MUPID_AI_GMS:}}
  model: ${MUPID_AI_MODEL:gpt-5-mini}
```

`ai` 블록만 `${...:기본값}` 을 쓴다. 다만 **기본값은 변수가 아예 없을 때만 쓰인다** — `.env` 에 `MUPID_AI_BASE_URL=` 처럼 키만 두면 빈 문자열이 그대로 들어간다. `.env.example` 이 키를 비워 두는 규약이라 실제로 밟는 함정이어서, `AiProperties` 의 compact 생성자가 빈 값을 기본값으로 되돌린다.

| 변수 | 쓰이는 곳 | `.env.example` 의 안내 |
|---|---|---|
| `MUPID_GMAIL_CLIENT_ID` | `gmail.client-id` | Google Cloud Dashboard |
| `MUPID_GMAIL_CLIENT_SECRET` | `gmail.client-secret` | Google Cloud Dashboard |
| `MUPID_GMAIL_TOKEN` | `gmail.token` (리프레시 토큰) | https://developers.google.com/oauthplayground |
| `MUPID_GMAIL_SEND_ADDRESS` | `gmail.send-address` | `someone@gmail.com` |
| `MUPID_S3_ENDPOINT` | `aws.s3.endpoint` | 개발은 SeaweedFS (`http://localhost:8333`), 운영은 빈 값 |
| `MUPID_S3_ACCESS_KEY` | `aws.s3.access-key` + compose 의 `AWS_ACCESS_KEY_ID` | — |
| `MUPID_S3_SECRET` | `aws.s3.secret-key` + compose 의 `AWS_SECRET_ACCESS_KEY` | `openssl rand -hex 32` |
| `MUPID_S3_BUCKET` | `aws.s3.bucket-name` + `storage-init` 의 버킷 이름 | — |
| `MUPID_CLIENT_WEBSITE` | `mupid-client.website` → 메일의 `verifyUrl` | — |
| `MUPID_AI_GMS_KEY` | `ai.api-key` (`MUPID_AI_GMS` 도 받는다) | https://gms.ssafy.io 에서 발급 |
| `MUPID_AI_BASE_URL` | `ai.base-url` | 비우면 `https://gms.ssafy.io/gmsapi/api.openai.com/v1` |
| `MUPID_AI_MODEL` | `ai.model` | 비우면 `gpt-5-mini` |

`MUPID_S3_ACCESS_KEY` / `MUPID_S3_SECRET` 은 앱과 SeaweedFS 컨테이너가 **같은 값을 공유**한다. SeaweedFS 는 이 두 값으로 S3 관리자 identity 를 만들므로, 한쪽만 바꾸면 곧바로 403 이 난다.

바인딩은 `record` + `@ConfigurationProperties(prefix=...)` 이고, 별도 `@EnableConfigurationProperties` 없이 `ApiApplication` 의 `@ConfigurationPropertiesScan` 이 등록한다.

### 컨테이너로 값이 들어가는 경로

`api/.dockerignore` 에 `.env` 가 있어 이미지에는 `.env` 가 들어가지 않는다. 컨테이너 안에서는 `spring.config.import: optional:file:.env[.properties]` 가 아무것도 찾지 못하므로, **모든 값이 컨테이너 환경변수로 들어가야 한다.** 스택마다 방식이 다르다.

| 스택 | api 서비스가 값을 받는 방법 | S3 / Gmail 변수 |
|---|---|---|
| `api/compose.yml` (개발) | api 서비스가 없다. 스프링은 호스트에서 실행하고 `api/.env` 를 직접 읽는다 | `api/.env` |
| `api/compose.prod.yml` | `env_file: - .env` | `.env` 의 값이 그대로 전달된다 |
| `infra/deploy/compose.yml` (배포) | `env_file` 없이 `environment:` 로 항목을 하나씩 나열 | **나열돼 있지 않다** |

`infra/deploy/.env.example` 에도 `MUPID_S3_*` · `MUPID_GMAIL_*` 항목이 없고, 첫 줄에 `# Actual secrets exist only in /srv/mupid/.env on the application server.` 라고만 적혀 있다. 배포 스택을 건드릴 일이 있으면 이 두 그룹이 컨테이너까지 도달하는지 먼저 확인할 것 — `application.yaml` 이 기본값 없이 `${MUPID_...}` 로 참조하므로 값이 없으면 바인딩 단계에서 걸린다.

### 새 설정을 추가할 때

1. `application.yaml` 에 `${MUPID_...}` 참조를 넣는다.
2. `api/.env.example` 에 키를 넣는다 — 값은 비우고, 발급처·형식을 줄 끝 주석으로 적는다.
3. `api/src/main/resources/META-INF/additional-spring-configuration-metadata.json` 에 메타데이터를 넣는다. 현재 여기 등록된 건 `jwt.secret`, `gmail.client-id`, `gmail.client-secret`, `ai.base-url`, `ai.api-key`, `ai.model` 여섯이고 `gmail.token`, `gmail.send-address`, `aws.s3.*` 는 빠져 있다.

주의: `.env` 는 Spring 이 `.properties` 로도 읽는다(`spring.config.import: optional:file:.env[.properties]`). properties 문법에서는 줄 끝 주석이 값에 포함되므로, **실제 값을 채우는 항목에는 줄 끝 주석을 남기지 않는다.**

### 자격증명 취급

- 실제 값은 `api/.env` 에만 둔다. `api/.gitignore` 첫 줄이 `.env` 다. 저장소에 추적되는 env 파일은 `api/.env.example` 과 `infra/deploy/.env.example` 둘뿐이고, 자격증명 항목은 모두 값이 비어 있다.
- Gmail 리프레시 토큰(`MUPID_GMAIL_TOKEN`)과 S3 시크릿(`MUPID_S3_SECRET`)은 그 자체로 발신 권한·스토리지 권한이다. 로그·이슈·MR 설명에 붙여넣지 않는다. 코드 어디에서도 이 값을 로깅하지 않는다.
- 자격증명은 정적 키뿐이다. 키 회전 시 앱과 SeaweedFS 컨테이너를 함께 재시작해야 한다.

---

## 실패 처리 — 재시도가 없다

| 경로 | 실패하면 |
|---|---|
| `GmailProvider.sendEmail` | `MessagingException \| IOException` 을 잡아 `log.error("Gmail 발송 실패: ...")` 후 `GmailException`(RuntimeException) 으로 감싸 던진다 |
| `EmailServiceImpl.sendVerification` | 클래스 레벨 `@Transactional`. `repository.save(verification)` 이 먼저라 발송이 실패하면 **인증 레코드까지 롤백된다** |
| `StorageServiceImpl` | SDK 예외를 잡지 않는다. AWS SDK 기본 재시도 정책만 적용된다 |
| `OpenAiClient.complete` | 오류 상태·연결 실패·읽기 타임아웃을 잡아 `BusinessException(AI_REQUEST_FAILED)` 502 로 바꾼다. 재시도는 없다 |
| `AiChatServiceImpl.runTool` | **툴 실패는 예외로 올리지 않는다.** 모델에게 실패를 알려 주고 답을 이어가게 둔다 |

`GmailException` 도 AWS SDK 예외도 전용 `@ExceptionHandler` 가 없다. `GlobalExceptionHandler` 의 `@ExceptionHandler(Exception.class)` 로 떨어져 `MUPID_INTERNAL_SERVER_ERROR` 500 이 나간다. `StorageErrorCode` 에는 `FILE_NOT_FOUND` 하나뿐이고 이건 DB 메타데이터가 없을 때만 쓰이므로, S3 에서 오브젝트만 사라진 경우는 404 가 아니라 500 이다.

Spring Retry·`@Async`·아웃박스 테이블은 저장소 어디에도 없다. 재시도나 발송 이력이 필요해지면 새로 설계해야 한다.

프로필 업로드 경로에는 검사 순서 때문에 도달하지 못하는 검증이 하나 있다. `application.yaml` 에 `spring.servlet.multipart.*` 설정이 없어 Boot 기본값(`max-file-size: 1MB`, `max-request-size: 10MB`)이 적용되고, `UserImageServiceImpl` 의 5MB 검사보다 먼저 걸린다. 상세는 [함정과 실패 사례](../failure/integrations.md) 에.

---

## 검증 수단

S3·Gmail 경로에는 자동화 테스트가 없다. LLM 경로는 와이어 포맷만 `ai/client/AiJsonTest` 가 잡아 준다 (스프링 컨텍스트도 DB 도 쓰지 않는다). 형식이 어긋나면 대화가 실패하는 게 아니라 **툴 호출만 조용히 사라지기** 때문이다. 나머지는 손으로 확인하며, `api/docs/` 의 Bruno 컬렉션을 쓴다.

| 확인할 것 | 컬렉션 |
|---|---|
| 프로필 사진 업로드 / 조회 / 삭제 | `api/docs/User/프로필 사진 수정.yml`, `프로필 사진 조회.yml`, `프로필 사진 삭제.yml` |
| 인증 메일 발송 / 코드 제출 / 상태 확인 | `api/docs/Email/` |
| 비밀번호 재설정 메일 | `api/docs/User/비밀번호 재설정 메일 발송.yml` |
| AI 대화 · 툴 호출 | `api/docs/AI/AI 대화.yml` · 화면은 `frontend/tests/ai-chat.test.mjs` |
