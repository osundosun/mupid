# package-web.sh 가 web.Dockerfile 앞에 붙인다.
# 명시한 입력만 컨텍스트에 들어간다 — .env 나 이전 빌드 결과물은 들어가지 않는다.
#
# $BUILDPLATFORM 은 이 단계를 "빌드하는 기계" 에 고정한다. 결과물이 정적 파일이라
# 어느 아키텍처에서 만들든 같은 바이트가 나오므로, 아래 런타임 단계가 linux/amd64
# 여도 이 컴파일은 러너에서 네이티브로 돈다. arm64 러너가 amd64 이미지를 만들 때
# QEMU 에뮬레이션을 타지 않는 이유가 이것이다. amd64 러너에서는 두 플랫폼이 같아
# 아무것도 달라지지 않는다. infra/image/build.Dockerfile 과 같은 방식이다.
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS builder
WORKDIR /source
COPY . .
# 배포된 것이 어느 커밋인지 이미지 안에 남긴다. 예전에는 서버의
# receive-front.py 가 썼고 그 다음에는 CI 가 dist/client 에 써 넣었다. 이제는
# 빌드가 쓴다 — 이미지는 한 번 구워지면 다시 쓰이지 않으므로 여기가 맞는 자리다.
# deploy-k8s.sh 의 /deployment.txt 확인은 그대로 동작한다.
ARG MUPID_COMMIT_SHA
# frontend-verify 와 같은 환경이어야 한다. MUPID_DEPLOY_TARGET 이 없으면
# vinext 가 SSR 로 빌드해 dist/server 를 만들고 dist/client/index.html 이
# 아예 생기지 않는다.
ENV MUPID_DEPLOY_TARGET=ec2 \
    NEXT_TELEMETRY_DISABLED=1
RUN test -n "$MUPID_COMMIT_SHA" \
 && npm ci --prefer-offline --no-audit --no-fund \
 && npm run build \
 && test -f dist/client/index.html \
 && printf '%s\n' "$MUPID_COMMIT_SHA" > dist/client/deployment.txt
