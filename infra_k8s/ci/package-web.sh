#!/bin/sh
# Build the edge image: nginx plus the frontend, compiled from source here.
#
# Mirrors infra/ci/package-image.sh — a prepared context, a $BUILDPLATFORM
# builder stage, the rootless buildkit worker, a gzipped docker archive under
# .release/ and a checksum beside it.
#
# 프론트엔드는 GitLab 아티팩트로 오가지 않는다. 백엔드가 jar 를 아티팩트로
# 만들지 않고 이미지 빌드 안에서 컴파일하는 것과 같은 이유다 — 배포되는 바이트가
# 코디네이터를 왕복하지 않고, 이 job 안에서만 만들어진다. frontend-verify 는
# MR 게이트로 남는다.
#
# platform=linux/amd64 is pinned rather than inherited from the runner. The
# nodes are x86_64; a runner on another architecture would otherwise produce an
# image that imports cleanly and then fails to execute. 빌더 단계는
# $BUILDPLATFORM 이라 vite 빌드 자체는 러너에서 네이티브로 돈다.
set -eu
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

directory=$(mktemp -d /tmp/mupid-web.XXXXXX)
trap 'rm -rf "$directory"' EXIT
mkdir "$directory/context"
# package-image.sh 는 입력을 하나씩 열거한다. 여기서는 반대로 frontend/ 전체를
# 넣고 몇 가지만 뺀다. 열거하면 조용히 틀리기 때문이다 — vite.config.ts 는
# .openai/hosting.json 을 import 하고 그런 파일은 앞으로도 늘어난다. 하나
# 빠뜨리면 frontend-verify 는 체크아웃 전체로 빌드하니 통과하고 이 job 만
# 실패한다. 두 job 이 같은 트리를 빌드하게 두는 편이 낫다.
#
# 빼는 것은 비밀값과 빌드 산출물, 의존성 디렉터리다. GitLab 은 프로젝트
# 디렉터리를 job 사이에 재사용하므로 dist 나 node_modules 가 남아 있을 수 있고,
# 그대로 넣으면 이전 빌드 결과가 이미지에 섞인다.
tar -cf - -C frontend \
  --exclude=./node_modules --exclude=./dist --exclude=./.next \
  --exclude=./.git --exclude=./.env --exclude='./.env.*' . \
  | tar -xf - -C "$directory/context"
for forbidden in node_modules dist .next .env; do
  [ ! -e "$directory/context/$forbidden" ] || {
    echo "context still has $forbidden" >&2; exit 65; }
done
test -f "$directory/context/package-lock.json"

tr -d '\r' < infra_k8s/image/web.build.Dockerfile > "$directory/context/Dockerfile"
# Reuse the production runtime definition instead of restating it here.
tr -d '\r' < infra_k8s/image/web.Dockerfile | \
  sed 's|^COPY dist/client /srv/web/current$|COPY --from=builder /source/dist/client /srv/web/current|' \
  >> "$directory/context/Dockerfile"
grep -q '^COPY --from=builder ' "$directory/context/Dockerfile"
chown -R 1000:1000 "$directory"

export MUPID_IMAGE_NAME="mupid-web:$CI_COMMIT_SHA"
export MUPID_BUILD_DIRECTORY="$directory"
export MUPID_COMMIT_SHA="$CI_COMMIT_SHA"
su user -c 'buildctl-daemonless.sh build --frontend dockerfile.v0 --local context="$MUPID_BUILD_DIRECTORY/context" --local dockerfile="$MUPID_BUILD_DIRECTORY/context" --opt platform=linux/amd64 --opt build-arg:MUPID_COMMIT_SHA="$MUPID_COMMIT_SHA" --output type=docker,name="$MUPID_IMAGE_NAME",dest="$MUPID_BUILD_DIRECTORY/image.tar"'

mkdir -p .release/web
gzip -n -c "$directory/image.tar" > .release/web/image.tar.gz
size=$(wc -c < .release/web/image.tar.gz)
[ "$size" -le 1073741824 ] || { echo 'Image exceeds server limit of 1 GiB' >&2; exit 65; }
(cd .release/web && sha256sum image.tar.gz > image.tar.gz.sha256)
printf 'Built %s (%s bytes)\n' "$MUPID_IMAGE_NAME" "$size"
