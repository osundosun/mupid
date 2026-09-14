# 문서가 코드와 어긋난 곳

9건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

문서를 계약으로 믿지 말 것. 아래는 실제 파일과 대조해 확인한 어긋남이다.

### `.gitignore` 에 `.env` 가 없다는 경고는 이미 틀렸다 — 해소됨

- **원인**: `api/README.md`(170행)가 "현재 `.gitignore` 에 등록되어 있지 않다" 라고 적는다. (`api/AGENTS.md` 에도 같은 문장이 있었으나 **수정 완료.**) 실제로는 `api/.gitignore` **1행이 `.env`** 이고 `git check-ignore -v api/.env` 는 `api/.gitignore:1:.env` 를 돌려준다. `git log --all -- api/.env` 는 0건이다. `.env` 줄은 커밋 `1c497c9`(2026-09-04 16:34)에서 추가됐고 두 문서는 3분 뒤 커밋에서 작성됐다 — **작성 시점부터 이미 사실이 아니었다.**
- **해결**: 이 문장을 근거로 `git add -f` 하거나 별도 우회를 만들지 않는다. `api/README.md` 의 해당 문장도 지우는 게 올바른 대처다. 주의 자체(커밋 금지)는 여전히 유효하다.
- **근거**: `api/.gitignore:1`, `api/README.md:170`

### `api/README.md` 의 인증 경로 표가 `SecurityConfig` 와 다르다

- **원인**: README 154행은 permitAll 을 `/api/auth/**` 로 적지만, 실제 permitAll 은 `POST /api/v1/auth`, `POST /api/v1/user`, `/api/v1/user/reset`, `/api/v1/mail/verification`, `GET /api/v1/mail/verification/**`, `GET /api/actuator/**` 이고 나머지는 `anyRequest().authenticated()` 다.
- **해결**: 정확한 목록은 `SecurityConfig` 와 `api/docs/opencollection.yml` 의 "인증 없이 호출 가능한 엔드포인트" 절에서 본다(이쪽이 코드와 일치한다).
- **근거**: `api/README.md:154` vs `api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java`

### `api/README.md` 의 디렉터리 구조가 초기 상태 그대로다

- **원인**: '디렉터리 구조' 절이 `ApiApplication.java` 와 `common/config/SecurityConfig.java` 두 파일만 나열한다. auth/user/email/storage/cache/analytics 어느 것도 없다. 같은 README 의 트리는 `docs/Actuator/헬스체크.yml` 로 적혀 있으나 실제 파일은 `api/docs/Actuator/HealthCheck.yml` 이다.
- **해결**: 구조 파악은 README 가 아니라 소스 트리로 한다.
- **근거**: `api/README.md`

### `infra/README.md` · `infra/OPERATIONS.md` 가 "백엔드 테스트 자동 실행" 이라고 적는다

- **원인**: `infra/README.md` 는 "MR: 백엔드 빌드·테스트 자동 실행", `infra/OPERATIONS.md` 개발자 작업 흐름 2번은 "백엔드는 Gradle build/test" 라고 적는다. 실제 `infra/ci/backend.yml` 은 build-only 다(커밋 `cbf0050`). `infra/PROGRESS.md` 만 "API 테스트 1개" 로 현실을 반영한다.
- **해결**: 파이프라인 초록불을 테스트 통과로 읽지 않는다.
- **근거**: `infra/ci/backend.yml` vs `infra/README.md`, `infra/OPERATIONS.md`

### Bruno 문서가 존재하지 않는 `UserGender.fromCode` 를 언급한다

- **원인**: `api/docs/User/회원가입.yml` 설명에 "`gender` 에 `M`, `F` 같은 값을 넣으면 `UserGender.fromCode` 에서 실패합니다" 라고 적혀 있으나, `fromCode` 는 커밋 `941a4ba`(enum 상수명 통일 및 AttributeConverter 제거)에서 사라졌고 현재는 `UserGender.valueOf` 다.
- **해결**: 문서 문구로 코드를 찾지 않는다.
- **근거**: `api/docs/User/회원가입.yml` vs `api/src/main/java/com/osundosun/mupid/api/user/enumerate/UserGender.java`

### docs 의 오류 표·예시가 코드와 어긋난 곳들

- **원인**: (1) `api/docs/Auth/로그인.yml` 오류 표에 `MUPID_USER_NOT_FOUND | 404` 가 있으나 `AuthServiceImpl.login` 은 `WRONG_EMAIL_OR_PASSWORD`(401)만 던진다. (2) `api/docs/User/프로필 사진 수정.yml` 의 200 성공 예시 바디가 `{"message": …, "error_details": null}` 인데 `BaseResponse` 의 필드는 message/data 이므로 실제로는 `"data": null` 이다. (3) `api/docs/Auth/로그인.yml` 의 쿠키 예시 `Max-Age=1800` 은 코드의 3600(=`ACCESS_TOKEN_VALIDITY_MS`)과도, 의도했을 604800(7일)과도 다르다.
- **해결**: API 규약은 `SecurityConfig` 와 enum, 응답 record 로 확인한다.
- **근거**: `api/docs/Auth/로그인.yml`, `api/docs/User/프로필 사진 수정.yml`

### 응답 record 이름 규칙에서 빠진 클래스가 있다

- **원인**: `email/response/EmailCheckAddressVerification` 만 `Response` 접미사가 없다. 나머지 응답 record 는 전부 `*Response` 다.
- **해결**: grep 으로 응답 클래스를 찾을 때 누락되기 쉽다는 걸 기억한다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/email/response/EmailCheckAddressVerification.java`

### `api/AGENTS.md` 의 compose·nginx 서술 — 해소됨

- **원인**: "파일이 두 벌" 이라며 `compose.yml`/`compose.prod.yml` 만 적고 `compose.prod.yml` 을 "운영" 으로 표기했으며, nginx 템플릿을 "개발·운영이 공유" 한다고 적었다. 실제 EC2 운영은 `infra/deploy/compose.yml` + `infra/deploy/nginx.conf` 이고 compose 는 네 벌이다.
- **해결**: `api/AGENTS.md` 의 Docker/Compose 표와 nginx 절을 **수정 완료.** 상세 구분은 [architecture/deployment.md](../architecture/deployment.md), 배경은 [decision/0008](../decision/0008-deploy-image-from-infra.md).
- **근거**: `api/AGENTS.md`, `infra/deploy/compose.yml`, `infra/deploy/nginx.conf`

### `erd.sql` 이 엔티티보다 뒤처져 있다 — 플레이리스트 수록곡은 해소됨

- **원인**: `knowledge/domain/erd.sql` 은 ERD 도구 산출물이라 엔티티가 먼저 바뀌면 그대로 뒤처진다. 수록곡 테이블이 그랬다 — SQL 은 `playlist_songs`(단일 PK `playlist_song_id`, `user_id` NULL 허용)인데 `PlaylistTrack` 엔티티는 `playlist_tracks`(`playlist_id` + `track_order` 복합키, `user_id` NOT NULL)다. `@Table` 이름을 SQL 과 대조하면 더 있다: 엔티티에만 있는 `daily_recommendation_tracks` · `listening` · `mubti` · `music_comments` · `music_moods` · `reports`, SQL 에만 있는 `comments` · `daily_recommendation_songs` · `mbti` · `play_histories` · `analysis_reports` 등.
- **해결**: **둘이 어긋나면 엔티티가 기준이다.** `playlist_tracks` 는 엔티티에 맞춰 `erd.sql` 을 고쳤다(복합키 PK + `uq_playlist_track_music` 유니크). 나머지 테이블은 각 기능 티켓에서 같은 방식으로 맞춘다. `ddl-auto` 가 `none` 이라([decision/0011](../decision/0011-jpa-manages-schema.md)) 실제 DB 에 DDL 이 적용됐는지는 별도로 확인해야 한다.
- **근거**: `api/knowledge/domain/erd.sql` vs `api/src/main/java/com/osundosun/mupid/api/playlist/entity/PlaylistTrack.java`

### Bruno 문서의 `permitAll` 목록이 코드와 어긋난다

- **원인**: 인증 규칙을 바꾸면 `SecurityConfig` 와 **Bruno 컬렉션 세 군데**가 함께 틀어진다. 한 곳만 고치면 나머지가 조용히 거짓말을 한다.
  1. `docs/opencollection.yml` 의 "인증 없이 호출 가능한 엔드포인트" 목록
  2. 도메인 `folder.yml` 의 인증 표 (`Music/folder.yml` 은 8줄짜리 표를 갖고 있다)
  3. 요청 파일마다 첫 줄의 `· **인증 불필요**` 표기
- **해결**: 다음 한 줄로 대조한다. 출력이 1:1 로 맞아야 한다.

  ```bash
  grep -oE '\.requestMatchers\((HttpMethod\.[A-Z]+, )?"[^)]+\)\.permitAll' \
    api/src/main/java/com/osundosun/mupid/api/common/config/SecurityConfig.java
  grep -n '^    - `' api/docs/opencollection.yml | head -12
  ```

- **확인**: 컬렉션 `.yml` 83개를 `yaml.safe_load` 로 통과시키면 여러 줄 `docs` 블록의 들여쓰기 실수를 잡을 수 있다. 문서를 고친 뒤 반드시 돌린다.
- **곁들여**: 엔드포인트 누락은 컨트롤러의 `@*Mapping` 과 요청 파일의 `http.url` 을 뽑아 대조하면 드러난다. 2026-09-14 기준 누락 없음.
- **근거**: `api/docs/opencollection.yml`, `api/docs/Music/folder.yml`, `api/knowledge/convention/api-docs.md`
