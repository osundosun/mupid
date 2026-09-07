#!/bin/sh
set -eu
test -s api/build/release/app.jar
mkdir -p /tmp/mupid-image/context
cp api/build/release/app.jar /tmp/mupid-image/context/app.jar
cp infra/image/Dockerfile /tmp/mupid-image/context/Dockerfile
chown -R 1000:1000 /tmp/mupid-image
export MUPID_IMAGE_NAME="mupid-api:$CI_COMMIT_SHA"
su user -c 'buildctl-daemonless.sh build --frontend dockerfile.v0 --local context=/tmp/mupid-image/context --local dockerfile=/tmp/mupid-image/context --output type=docker,name="$MUPID_IMAGE_NAME",dest=/tmp/mupid-image/image.tar'
gzip /tmp/mupid-image/image.tar
cd /tmp/mupid-image
sha256sum image.tar.gz > image.tar.gz.sha256
package_url="$CI_API_V4_URL/projects/$CI_PROJECT_ID/packages/generic/mupid-api/$CI_COMMIT_SHA"
for file in image.tar.gz image.tar.gz.sha256; do
  curl --fail --silent --show-error --retry 2 --header "JOB-TOKEN: $CI_JOB_TOKEN" --upload-file "$file" "$package_url/$file"
done
printf '\nPublished %s\n' "$MUPID_IMAGE_NAME"
