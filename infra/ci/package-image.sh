#!/bin/sh
set -eu
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'
directory=$(mktemp -d /tmp/mupid-image.XXXXXX)
trap 'rm -rf "$directory"' EXIT
mkdir "$directory/context"
# Explicit inputs exclude .env, previous build output, and CI SSH files.
for input in gradlew gradle build.gradle settings.gradle lombok.config src; do
  cp -R "api/$input" "$directory/context/$input"
done
tr -d '\r' < api/gradlew > "$directory/context/gradlew"
tr -d '\r' < infra/image/build.Dockerfile > "$directory/context/Dockerfile"
# Reuse the production runtime definition, including user and health check.
tr -d '\r' < infra/image/Dockerfile | \
  sed 's/^COPY --chown=1000:1000 app.jar app.jar$/COPY --from=builder --chown=1000:1000 \/release\/app.jar app.jar/' \
  >> "$directory/context/Dockerfile"
grep -q '^COPY --from=builder ' "$directory/context/Dockerfile"
chown -R 1000:1000 "$directory"
export MUPID_IMAGE_NAME="mupid-api:$CI_COMMIT_SHA"
export MUPID_BUILD_DIRECTORY="$directory"
# platform is pinned rather than inherited from the runner: the servers are
# x86_64, and a runner on another architecture would otherwise build an image
# that loads without complaint and then cannot execute. On an amd64 runner this
# resolves to what it already produced, so the output is unchanged. The builder
# stage in build.Dockerfile is $BUILDPLATFORM, so the compile still runs native.
su user -c 'buildctl-daemonless.sh build --frontend dockerfile.v0 --local context="$MUPID_BUILD_DIRECTORY/context" --local dockerfile="$MUPID_BUILD_DIRECTORY/context" --opt platform=linux/amd64 --output type=docker,name="$MUPID_IMAGE_NAME",dest="$MUPID_BUILD_DIRECTORY/image.tar"'
mkdir -p .release
gzip -n -c "$directory/image.tar" > .release/image.tar.gz
size=$(wc -c < .release/image.tar.gz)
[ "$size" -le 1073741824 ] || { echo 'Image exceeds server limit of 1 GiB' >&2; exit 65; }
(cd .release && sha256sum image.tar.gz > image.tar.gz.sha256)
printf 'Built %s (%s bytes); no GitLab package upload\n' "$MUPID_IMAGE_NAME" "$size"
