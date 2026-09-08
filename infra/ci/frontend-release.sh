#!/bin/sh
set -eu
url="$CI_API_V4_URL/projects/$CI_PROJECT_ID/packages/generic/mupid-web/$CI_COMMIT_SHA"
if [ "$1" = package ]; then
  tar -czf site.tar.gz -C frontend/dist/client .
  sha256sum site.tar.gz > site.tar.gz.sha256
  for file in site.tar.gz site.tar.gz.sha256; do
    curl --fail --silent --show-error --header "JOB-TOKEN: $CI_JOB_TOKEN" --upload-file "$file" "$url/$file"
  done
else
  : "${MUPID_DEPLOY_SSH_KEY:?File variable required}"
  : "${MUPID_DEPLOY_KNOWN_HOSTS:?File variable required}"
  for file in site.tar.gz site.tar.gz.sha256; do
    curl --fail --silent --show-error --header "JOB-TOKEN: $CI_JOB_TOKEN" --output "$file" "$url/$file"
  done
  sha256sum -c site.tar.gz.sha256
  checksum=$(sha256sum site.tar.gz | cut -d ' ' -f 1)
  umask 077
  key=$(mktemp)
  trap 'rm -f "$key"' EXIT
  tr -d '\r' < "$MUPID_DEPLOY_SSH_KEY" > "$key"
  printf '\n' >> "$key"
  ssh-keygen -y -P '' -f "$key" >/dev/null
  ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$MUPID_DEPLOY_KNOWN_HOSTS" -i "$key" mupid-deploy@j15a204.p.ssafy.io "deploy-front $CI_COMMIT_SHA $checksum" < site.tar.gz
  test "$(curl --fail --silent --show-error https://j15a204.p.ssafy.io/deployment.txt)" = "$CI_COMMIT_SHA"
fi
