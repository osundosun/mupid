#!/bin/sh
# Build the edge image: nginx plus the frontend build.
#
# Mirrors infra/ci/package-image.sh — an explicit context, the rootless buildkit
# worker, a gzipped docker archive under .release/ and a checksum beside it.
#
# platform=linux/amd64 is pinned rather than inherited from the runner. The
# nodes are x86_64; a runner on another architecture would otherwise produce an
# image that imports cleanly and then fails to execute.
set -eu
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'
test -f frontend/dist/client/index.html

directory=$(mktemp -d /tmp/mupid-web.XXXXXX)
trap 'rm -rf "$directory"' EXIT
mkdir -p "$directory/context/dist"
cp -R frontend/dist/client "$directory/context/dist/client"
# infra/deploy/receive-front.py wrote this on the server. The image is built
# once and never rewritten, so it is written here instead. frontend-release.sh
# verifies the served copy against the commit, and that check is unchanged.
printf '%s\n' "$CI_COMMIT_SHA" > "$directory/context/dist/client/deployment.txt"
cp infra_k8s/image/web.Dockerfile "$directory/Dockerfile"
chown -R 1000:1000 "$directory"

export MUPID_IMAGE_NAME="mupid-web:$CI_COMMIT_SHA"
export MUPID_BUILD_DIRECTORY="$directory"
su user -c 'buildctl-daemonless.sh build --frontend dockerfile.v0 --local context="$MUPID_BUILD_DIRECTORY/context" --local dockerfile="$MUPID_BUILD_DIRECTORY" --opt platform=linux/amd64 --output type=docker,name="$MUPID_IMAGE_NAME",dest="$MUPID_BUILD_DIRECTORY/image.tar"'

mkdir -p .release/web
gzip -n -c "$directory/image.tar" > .release/web/image.tar.gz
size=$(wc -c < .release/web/image.tar.gz)
[ "$size" -le 1073741824 ] || { echo 'Image exceeds server limit of 1 GiB' >&2; exit 65; }
(cd .release/web && sha256sum image.tar.gz > image.tar.gz.sha256)
printf 'Built %s (%s bytes)\n' "$MUPID_IMAGE_NAME" "$size"
