# API 문서 (Bruno)

엔드포인트를 추가·변경하면 `api/docs/` 컬렉션도 함께 고친다.

[← 컨벤션](README.md) · [지식 저장소](../README.md)

---

## API 문서 (Bruno)

`api/AGENTS.md` — "API 수동 확인은 `docs/` 의 Bruno 컬렉션을 쓴다. **엔드포인트를 추가하면 컬렉션에도 추가한다.**"

### 포맷과 구조

Bruno 의 `opencollection: 1.0.0` YAML 포맷이다. `.bru` 파일이 아니다.

```
api/docs/
├── opencollection.yml       # info.name: Mupid API, request.variables(BASE_URL), 컬렉션 전체 규약 docs 블록
├── environments/Local.yml   # 환경 변수 5개
├── Actuator/  folder.yml(seq 1) + HealthCheck.yml
├── Auth/      folder.yml(seq 2) + 로그인.yml · 로그아웃.yml · 액세스 토큰 재발급.yml
├── User/      folder.yml(seq 3) + 회원가입.yml · 회원정보 조회.yml · …
├── Email/     folder.yml(seq 4) + 인증 메일 발송.yml · 인증 상태 확인.yml · 인증 코드 제출.yml
├── Error/     folder.yml(seq 5) + NotFound.yml
└── Event/     folder.yml(seq 6) + 청취 이벤트 수집.yml
```

### 새 요청 파일을 만들 때

- 파일명은 **한글 기능명** (`로그인.yml`, `회원가입.yml`). 영문 파일명은 `Actuator/HealthCheck.yml` 과 `Error/NotFound.yml` 둘뿐이다.
- 도메인 폴더마다 `folder.yml` 을 둔다 — `info.{name, type: folder, seq}` + `request.auth: inherit`. 도메인 설명을 담는 `docs.{type: text/markdown, content}` 는 Actuator·Auth·User·Email·Event 5개가 갖고 있고 `Error/folder.yml` 에만 없다.
- 요청 파일 키 순서는 `info`(name / `type: http` / `seq`) → `http` → `runtime` → `settings` → `examples` → `docs`.
- 표시 순서는 `info.seq` 로 지정한다.
- 응답값 자동 저장은 `runtime.actions` 에 쓴다.

  ```yaml
  actions:
    - type: set-variable
      phase: after-response
      selector:
        expression: res.body.data.access_token
        method: jsonq
      variable:
        name: ACCESS_TOKEN
        scope: runtime
      description: 액세스 토큰을 ACCESS_TOKEN 변수에 저장합니다.
  ```

- **`examples` 의 `response.headers` 에 `Content-Type: application/json` 을 반드시 넣는다.** 저장된 Example 은 파일에 적힌 헤더만 보고 표시 형식을 정하므로, 빠뜨리면 Raw 로 보인다 (`opencollection.yml` 의 "응답이 Raw 로 보일 때" 절).
- **인증이 필요한 요청에는 `http.headers` 에 `Authorization: Bearer {{ACCESS_TOKEN}}` 을 직접 적는다.** `auth: inherit` 은 컬렉션·폴더에 정의된 인증이 없어 **아무 헤더도 붙이지 않는다** (`opencollection.yml` 의 `auth:` 는 `config.proxy.auth` 이지 인증 방식이 아니다). `forwardAuthorizationHeader` 는 "Forward Authorization on Redirect" 라 **새 헤더를 만들지 않는다**. `permitAll` 요청(로그인·회원가입·이메일 인증, 온보딩 선택지, 앨범·아티스트·곡 이미지)과 내부 토큰(`X-Internal-Token`) 요청만 헤더가 없다. 배경은 [함정과 실패 사례](../failure/doc-drift.md).
- `api/docs/environments/Local.yml` 의 토큰류 변수는 `value: ""` 로 비우고 `description` 에 채워지는 경로(자동 저장인지 수기 입력인지)를 적는다.
- 계정·비밀번호 같은 값을 요청 파일에 박지 않는다. `api/docs/.gitignore` 가 막는 것은 `.env*`, `node_modules`, `.DS_Store`, `Thumbs.db` 네 항목이라 `.yml` 안의 값은 걸러지지 않는다. 기존 위반 사례는 [함정과 실패 사례](../failure/git-and-convention.md).

### API 규약의 기준 문서

`api/docs/opencollection.yml` 의 `docs:` 블록이 컬렉션 전체 규약을 담는다.

- 요청·응답 필드는 전부 **snake_case** (`spring.jackson.property-naming-strategy: SNAKE_CASE`)
- 성공은 `BaseResponse`(`message`, `data`), 실패는 `ErrorResponse`(`code`, `message`, `error_details`)
- 오류 코드는 `MUPID_<enum 이름>` 규칙
- `permitAll` 엔드포인트 목록 — 6줄 모두 `SecurityConfig` 의 `requestMatchers(...).permitAll()` 과 대응하는 것을 확인했다 (`/api/v1/user/reset`, `/api/v1/mail/verification` 은 코드상 메서드 제한이 없어 문서 쪽이 더 좁게 적혀 있다)
- enum 값 — `gender`, `role`/`user_role`, `status`

API 규약은 `api/README.md` 가 아니라 이 컬렉션을 기준으로 본다. README 의 인증 경로 표는 코드와 다르다 → [함정과 실패 사례](../failure/git-and-convention.md).
