# 프론트엔드 (웹) 포팅 매뉴얼

## 1. 개요

- `frontend/` 는 뮤피드 웹 화면이다. React 19 + TypeScript 로 짰고, **Next.js 가 아니라 `vinext`**(Vite 위에서 Next.js App Router API 를 구현한 것)로 빌드한다. `next` 패키지는 의존성에 없고, `next/navigation`·`next.config.ts` 등은 vinext 가 제공한다 ([package.json](../../frontend/package.json), [docs/routing.md](../../frontend/docs/routing.md)).
- 운영 배포물은 **정적 파일 한 벌**(`frontend/dist/client/`)이다. 이것을 엣지 nginx 이미지에 구워 `https://j15a204.p.ssafy.io` 로 내보낸다. 같은 번들을 Tauri 안드로이드 앱도 쓴다 ([next.config.ts](../../frontend/next.config.ts), [app/README.md](../../app/README.md), 앱은 [../app/README.md](../app/README.md)).
- 화면 코드에는 웹/앱 분기가 없다. HTTP·저장소·이벤트 전송·앨범 아트 네 곳만 `lib/platform/` 에서 갈린다 ([docs/app-shell.md](../../frontend/docs/app-shell.md)).
- API 는 같은 출처의 `/api/v1/...` 로 부른다. 운영은 nginx 가 화면과 API 를 한 출처로 묶고, 개발은 Vite 프록시가 넘긴다 ([lib/api/base-url.ts](../../frontend/lib/api/base-url.ts), [vite.config.ts](../../frontend/vite.config.ts)).

## 2. 기술 스택 · 버전

| 항목 | 버전 | 근거 |
| --- | --- | --- |
| Node.js | `>=22.13.0` (engines). CI·이미지 빌드는 `node:22-bookworm-slim` | [package.json](../../frontend/package.json), [infra/ci/frontend.yml](../../infra/ci/frontend.yml), [web.build.Dockerfile](../../infra_k8s/image/web.build.Dockerfile) |
| 패키지 매니저 | npm (`package-lock.json`, CI 는 `npm ci`) | [package-lock.json](../../frontend/package-lock.json) |
| 프레임워크 | `vinext 1.0.0-beta.5` (App Router 호환, RSC) | [package.json](../../frontend/package.json) |
| 번들러 | `vite 8.0.13`, `@vitejs/plugin-react 6.0.2`, `@vitejs/plugin-rsc 0.5.26` | 〃 |
| UI | `react`/`react-dom 19.2.6`, `@base-ui/react 1.7.0`, `shadcn 4.18.0`(style `base-nova`), `lucide-react 1.31.0`, `recharts 3.8.0`, `embla-carousel-react 8.5.2` | 〃, [components.json](../../frontend/components.json) |
| 스타일 | `tailwindcss 4.2.1` + `@tailwindcss/postcss 4.2.1`, `tw-animate-css 1.4.0`. 글꼴은 Pretendard·그리운 규원체(`public/fonts/`, 외부 CDN 없음) | [package.json](../../frontend/package.json), [frontend/README.md](../../frontend/README.md) |
| 언어 | `typescript 5.9.3`, `@types/node 22.19.19` | [package.json](../../frontend/package.json) |
| 린트/포맷 | `oxlint 1.76.0`(+`oxlint-tsgolint`), `oxfmt 0.61.0` | [.oxlintrc.json](../../frontend/.oxlintrc.json), [.oxfmtrc.json](../../frontend/.oxfmtrc.json) |
| 앱 연동 | `@tauri-apps/api 2.11.1` (앱일 때만 동적 import) | [docs/app-shell.md](../../frontend/docs/app-shell.md) |
| (기본 대상 전용) | `wrangler 4.92.0`, `@cloudflare/vite-plugin 1.37.1`, `@openai/sites-vite-plugin 0.2.0` — `MUPID_DEPLOY_TARGET` 이 없을 때의 Cloudflare Worker 빌드용. 운영에서는 쓰지 않는다 | [vite.config.ts](../../frontend/vite.config.ts) |
| 운영 웹 서버 | `nginx:1.27-alpine` (엣지 이미지) | [web.Dockerfile](../../infra_k8s/image/web.Dockerfile) |
| IDE | 저장소에 `.vscode`/`.idea` 설정 없음 | > TODO: 팀 확인 (사용 IDE와 버전) |

## 3. 로컬 빌드 · 실행

### 3-1. 설치

```bash
cd frontend
npm ci
```

### 3-2. 개발 서버

| 명령 | 내용 |
| --- | --- |
| `npm run dev` | 기본 대상(Cloudflare 플러그인 포함) 개발 서버. 기본 포트 3000, `npm run dev -- --port 3001` 로 변경 ([TEAM-HANDOFF.md](../../frontend/TEAM-HANDOFF.md)) |
| `npm run dev:app` | 정적 대상(`MUPID_DEPLOY_TARGET=app`)으로 개발 서버. Tauri `cargo tauri dev` 가 이것을 띄운다 ([static-target.mjs](../../frontend/scripts/static-target.mjs)) |

두 경우 모두 `/api`·`/upload` 를 `MUPID_API_ORIGIN`(기본 `http://localhost:9900`)으로 프록시한다. 서버에 CORS 가 없고 리프레시 토큰 쿠키가 `SameSite=Strict; Secure` 라 같은 출처로 보이게 하려는 것이다 ([vite.config.ts](../../frontend/vite.config.ts)).

- 백엔드는 스프링(8800)이 아니라 **nginx 를 가리켜야 한다.** 음원 청크는 스프링이 `X-Accel-Redirect` 만 주고 본문은 nginx 가 채운다 ([app/README.md](../../app/README.md)).
- 개발용 nginx(`api/compose.yml`)의 호스트 포트 기본값은 `MUPID_NGINX_PORT=8080` 이다. 프록시 기본값 9900 과 다르므로 둘 중 하나를 맞춘다 ([api/compose.yml](../../api/compose.yml), [api/.env.example](../../api/.env.example)).

```bash
# 예: 개발 nginx 가 8080 일 때
MUPID_API_ORIGIN=http://localhost:8080 npm run dev
```

- **로컬 데모 모드**(백엔드 없이 화면 확인): `.env.local` 에 `VITE_MUPID_LOCAL_DEMO=true` 를 넣고 개발 서버를 완전히 재시작한다. 음원은 Git 에서 빠진 `frontend/work/local-demo/` 에 둔다. 데모 계정은 [docs/local-demo.md](../../frontend/docs/local-demo.md) 에 적혀 있다(실서비스 계정 아님). 개발 모드·localhost 에서만 켜진다.

### 3-3. 빌드

| 명령 | 산출물 | 용도 |
| --- | --- | --- |
| `MUPID_DEPLOY_TARGET=ec2 npm run build` (= `npm run build:ec2`) | `dist/client/` 정적 파일 | **운영 웹** (CI·이미지 빌드가 쓰는 방식) |
| `npm run build:static` | `dist/client/` (`app` 대상) | Tauri 앱 번들. 빌드 뒤 RSC 호환성 식별자가 번들에 박혔는지 검사 |
| `npm run build:archive` | `dist/update/web-<builtAt>.tar.gz`, `web.json` | 앱 자동 업데이트용 코드 전용 아카이브 (`build:static` 뒤에 실행) ([web-archive.mjs](../../frontend/scripts/web-archive.mjs)) |
| `npm run build` (대상 없음) | `dist/server` + `dist/client` (Cloudflare Worker) | 운영 미사용. 이때는 `dist/client/index.html` 이 생기지 않는다 |
| `npm run start` | — | 기본 대상 빌드 결과를 `wrangler dev` 로 실행 |

Windows `cmd` 에서는 `VAR=값 명령` 형식이 안 되므로 `npm run build:ec2`(Node 스크립트가 환경 변수를 넣음)를 쓴다 ([static-target.mjs](../../frontend/scripts/static-target.mjs)).

## 4. 환경 변수

모든 `.env*` 파일은 Git 에서 무시된다 ([frontend/.gitignore](../../frontend/.gitignore)). 프론트 전용 `.env.example` 은 없다. `NEXT_PUBLIC_*` 변수는 쓰지 않는다. 브라우저로 새는 변수는 `VITE_MUPID_*` 접두어뿐이다.

| 키 | 시점 | 설명 | 설정 위치 |
| --- | --- | --- | --- |
| `MUPID_DEPLOY_TARGET` | 빌드 | `ec2`(운영 웹) / `app`(Tauri) / 비움(Cloudflare Worker). `ec2`·`app` 이면 `output: 'export'` 정적 빌드 | CI [frontend.yml](../../infra/ci/frontend.yml), 이미지 [web.build.Dockerfile](../../infra_k8s/image/web.build.Dockerfile) `ENV`, `scripts/static-target.mjs` |
| `NEXT_TELEMETRY_DISABLED` | 빌드 | `1`. 텔레메트리 끔 | 위 두 파일 |
| `VITE_MUPID_API_BASE` | 빌드(번들에 박힘) | API 원본 주소. **운영·개발 모두 비움**(같은 출처). 화면과 API 출처가 갈리는 배포에서만 채운다. 앱에서는 쓰지 않는다(Rust 가 붙임) | [lib/api/base-url.ts](../../frontend/lib/api/base-url.ts), [lib/platform/image.ts](../../frontend/lib/platform/image.ts) |
| `VITE_MUPID_LOCAL_DEMO` | 개발 | `true` 면 로컬 데모 로그인·음원 제공 | `frontend/.env.local` ([docs/local-demo.md](../../frontend/docs/local-demo.md)) |
| `MUPID_API_ORIGIN` | 개발 서버 | Vite 프록시 대상. 기본 `http://localhost:9900`. (앱 빌드에서는 Rust 가 같은 이름을 컴파일 시점에 읽는다) | 셸 환경 ([vite.config.ts](../../frontend/vite.config.ts)) |
| `TAURI_DEV_HOST` | 개발 서버 | 정적 대상 개발 서버의 바인딩 호스트(실기기 접속용) | Tauri CLI 가 넣음 |
| `MUPID_WEB_BUILD` / `SOURCE_DATE_EPOCH` | `build:archive` | 웹 아카이브의 `builtAt`. CI 가 고정해 재현 가능한 바이트를 만든다 | [infra/ci/app.yml](../../infra/ci/app.yml), [web-archive.mjs](../../frontend/scripts/web-archive.mjs) |
| `MUPID_COMMIT_SHA` | 이미지 빌드(build-arg) | `dist/client/deployment.txt` 에 커밋 SHA 기록 | [package-web.sh](../../infra_k8s/ci/package-web.sh) |
| `WRANGLER_*`, `MINIFLARE_REGISTRY_PATH`, `CODEX_SANDBOX` | 개발(기본 대상) | 도구 설정. 비밀 아님 | [vite.config.ts](../../frontend/vite.config.ts) |

프론트엔드에는 비밀값이 없다. 인증은 서버가 준 JWT(헤더)와 리프레시 토큰 쿠키로 한다.

## 5. 배포 (운영)

```
master 병합 → frontend-verify (npm ci · build · 시험 기준선)
           → k8s-deploy-web: package-web.sh → deploy-k8s.sh web
```

1. **이미지 빌드** ([package-web.sh](../../infra_k8s/ci/package-web.sh)): `frontend/` 전체(단 `node_modules`·`dist`·`.next`·`.env*` 제외)를 컨텍스트로 삼는다. [web.build.Dockerfile](../../infra_k8s/image/web.build.Dockerfile)(builder: `node:22-bookworm-slim`, `$BUILDPLATFORM`)에서 `npm ci && npm run build` 후 `dist/client/index.html` 존재와 `deployment.txt` 를 확인한다. [web.Dockerfile](../../infra_k8s/image/web.Dockerfile)(`nginx:1.27-alpine`)이 `dist/client` 를 `/srv/web/current` 로 복사한다. 플랫폼은 `linux/amd64` 로 고정하고 buildkit(rootless)로 굽는다. 결과는 `.release/web/image.tar.gz`(+sha256, 1 GiB 상한)다.
2. **배포** ([deploy-k8s.sh](../../infra_k8s/ci/deploy-k8s.sh) `web`): 이미지를 노드 containerd 로 들여와 edge 디플로이먼트를 교체하고 `/deployment.txt` 로 커밋을 확인한다. 실행 조건: `master` + 보호 브랜치 + `MUPID_DEPLOY_ENABLED == "true"` ([k8s-release.yml](../../infra_k8s/ci/k8s-release.yml)).
3. **nginx 설정은 이미지에 없다.** `edge-nginx` ConfigMap([infra_k8s/base/nginx/default.conf](../../infra_k8s/base/nginx/default.conf))에서 온다. `root /srv/web/current`, `try_files $uri $uri.html $uri/index.html`, `/api/` 는 스프링으로 넘긴다.

### 배포 특이사항

- **`MUPID_DEPLOY_TARGET=ec2` 필수.** 빠지면 SSR 빌드가 되어 `dist/client/index.html` 이 없고, 이미지 빌드가 실패한다 ([web.build.Dockerfile](../../infra_k8s/image/web.build.Dockerfile)).
- **RSC 호환성 식별자.** `ec2` 빌드에는 빈 값을 박아 라우터의 비교를 끈다. 그렇지 않으면 메뉴를 옮길 때마다 문서 전체가 다시 로딩되어 재생이 끊긴다. `app` 빌드는 `mupid-static-1` 로 고정하고, 이 값은 `app/src/lib.rs` 의 `RSC_COMPATIBILITY_ID` 와 같아야 한다 ([vite.config.ts](../../frontend/vite.config.ts), [docs/routing.md](../../frontend/docs/routing.md)).
- **`.rsc` 협상.** nginx 가 `RSC: 1` 헤더 요청을 같은 경로의 `.rsc` 파일로 돌리고 `text/x-component` 로 내보낸다. 이 규칙이 없으면 역시 화면 이동마다 전체 새로고침이 된다 ([default.conf](../../infra_k8s/base/nginx/default.conf)).
- **MR 라벨.** MR 파이프라인의 `frontend-verify` 는 MR 에 `Frontend` 라벨이 있을 때만 돈다. 브랜치 파이프라인은 `develop`/`master` 에서 경로 변경으로 판단한다 ([frontend.yml](../../infra/ci/frontend.yml)).
- **CI 러너의 `NODE_ENV`.** 시험 전에 `unset NODE_ENV` 한다. `production` 이 걸려 있으면 devDependencies 가 빠진다 ([frontend.yml](../../infra/ci/frontend.yml)).
- **앱 번들 업데이트.** 앱용 `build:static` → `build:archive` 결과는 [deploy-web.sh](../../infra_k8s/ci/deploy-web.sh) 가 `/download/web.json`·`/download/web-*.tar.gz` 로 올린다(APK 파이프라인, [infra/ci/app.yml](../../infra/ci/app.yml)).
- **레거시 설정.** [mupid-proxy.conf](../../frontend/mupid-proxy.conf)(Apache 로 `www.gyuwon.net/mupid` → OpenAI Sites 프록시)와 [.openai/hosting.json](../../frontend/.openai/hosting.json)(`d1`/`r2` 모두 `null`)은 프로토타입 시절 개인 호스팅용이다. 운영(k8s) 배포에는 쓰이지 않는다 ([TEAM-HANDOFF.md](../../frontend/TEAM-HANDOFF.md)). 단, `vite.config.ts` 가 `hosting.json` 을 import 하므로 파일은 지우면 안 된다 ([package-web.sh](../../infra_k8s/ci/package-web.sh) 주석).

## 6. 설정 파일 목록

| 파일 | 역할 |
| --- | --- |
| [frontend/package.json](../../frontend/package.json) | 의존성·스크립트·Node 엔진 |
| [frontend/vite.config.ts](../../frontend/vite.config.ts) | 빌드 대상 분기, 개발 프록시(`/api`, `/upload`), RSC 식별자 고정, 로컬 데모 음원 미들웨어 |
| [frontend/next.config.ts](../../frontend/next.config.ts) | `ec2`/`app` 대상이면 `output: 'export'` |
| [frontend/tsconfig.json](../../frontend/tsconfig.json) | TS 설정, `@/*` 경로 별칭 |
| [frontend/components.json](../../frontend/components.json) | shadcn 설정 |
| [frontend/.oxlintrc.json](../../frontend/.oxlintrc.json), [.oxfmtrc.json](../../frontend/.oxfmtrc.json) | 린트·포맷 |
| [frontend/.openai/hosting.json](../../frontend/.openai/hosting.json) | 기본(Cloudflare) 대상의 D1/R2 바인딩. 현재 둘 다 `null` |
| [frontend/lib/routes.ts](../../frontend/lib/routes.ts) | 화면 키 ↔ URL, 로그인·온보딩 가드 |
| [frontend/lib/api/base-url.ts](../../frontend/lib/api/base-url.ts) | API 원본 주소(`VITE_MUPID_API_BASE`) |
| [frontend/scripts/static-target.mjs](../../frontend/scripts/static-target.mjs), [web-archive.mjs](../../frontend/scripts/web-archive.mjs) | 정적 빌드 래퍼, 앱 업데이트 아카이브 |
| [frontend/mupid-proxy.conf](../../frontend/mupid-proxy.conf) | (레거시) Apache 프록시 |
| [infra_k8s/image/web.build.Dockerfile](../../infra_k8s/image/web.build.Dockerfile), [web.Dockerfile](../../infra_k8s/image/web.Dockerfile) | 엣지 이미지 빌드 |
| [infra_k8s/ci/package-web.sh](../../infra_k8s/ci/package-web.sh) | 이미지 굽기 |
| [infra_k8s/base/nginx/default.conf](../../infra_k8s/base/nginx/default.conf) | 운영 nginx (ConfigMap) |
| [infra/ci/frontend.yml](../../infra/ci/frontend.yml) | `frontend-verify` job |
| `frontend/.env.local` (Git 제외) | 로컬 데모 등 개발용 값 |

계정 정보를 담는 파일은 프론트엔드에 없다.

## 7. 테스트

```bash
cd frontend
npx tsc --noEmit --incremental false          # 타입 검사
npm run lint                                  # oxlint
node --experimental-strip-types --test        # 전체 시험 (tests/*.test.mjs)
node tests/check-test-baseline.mjs            # CI 와 같은 방식: 기준선에 없는 실패만 막음
```

- 시험은 Node 내장 `node:test` 로 돈다. [frontend/tests/](../../frontend/tests/) 에 60여 개 파일이 있다.
- 이미 깨져 있던 시험은 [tests/failures-baseline.txt](../../frontend/tests/failures-baseline.txt) 에 적혀 있고, CI 는 그 목록 밖의 실패만 막는다. 기준선 갱신은 `node tests/check-test-baseline.mjs --write-baseline` 으로 한다(늘리면 사유를 커밋에 남김) ([check-test-baseline.mjs](../../frontend/tests/check-test-baseline.mjs)).
- CI(`frontend-verify`)는 `npm ci` → `npm run build` → `test -f dist/client/index.html` → 기준선 검사 순으로 돈다 ([frontend.yml](../../infra/ci/frontend.yml)).

## 8. 외부 서비스

프론트엔드가 직접 부르는 외부 서비스는 없다(OAuth·애널리틱스 SDK·외부 CDN 없음). 모든 호출은 같은 출처의 `/api/v1/...` 로 간다.

- 청취 이벤트는 자체 API(`/api/v1/events`)로 `fetch(keepalive)` 전송한다 ([lib/analytics/transport.ts](../../frontend/lib/analytics/transport.ts)).
- AI 채팅(`POST /api/v1/ai/chat`)의 LLM, 인증 메일 발송은 백엔드가 맡는다.
- 시작 화면의 QR/링크는 자체 서버의 `https://j15a204.p.ssafy.io/download/mupid.apk` 를 가리킨다 ([collection.tsx](../../frontend/components/mupid/collection.tsx)).
- Firebase Crashlytics 는 안드로이드 앱에만 있다 ([app/deploy.env.example](../../app/deploy.env.example)).
