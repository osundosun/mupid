#!/bin/sh
set -eu
: "${MUPID_DEPLOY_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_KNOWN_HOSTS:?GitLab file variable required}"
package_url="$CI_API_V4_URL/projects/$CI_PROJECT_ID/packages/generic/mupid-api/$CI_COMMIT_SHA"
mkdir -p /tmp/mupid-deploy
cd /tmp/mupid-deploy
for file in image.tar.gz image.tar.gz.sha256; do
  curl --fail --silent --show-error --retry 2 --header "JOB-TOKEN: $CI_JOB_TOKEN" --output "$file" "$package_url/$file"
done
sha256sum -c image.tar.gz.sha256
checksum=$(sha256sum image.tar.gz | cut -d ' ' -f 1)
chmod 600 "$MUPID_DEPLOY_SSH_KEY"
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$MUPID_DEPLOY_KNOWN_HOSTS" -i "$MUPID_DEPLOY_SSH_KEY" mupid-deploy@j15a204.p.ssafy.io "deploy $CI_COMMIT_SHA $checksum" < image.tar.gz
curl --fail --silent --show-error --max-time 15 https://j15a204.p.ssafy.io/api/actuator/health | grep -q '"status":"UP"'
