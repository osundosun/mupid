#!/bin/sh
set -eu
: "${MUPID_DEPLOY_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_KNOWN_HOSTS:?GitLab file variable required}"
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'
mode=${1:?Expected resume or upload}
case "$mode" in resume|upload) ;; *) exit 64 ;; esac
umask 077
key=$(mktemp)
trap 'rm -f "$key"' EXIT
tr -d '\r' < "$MUPID_DEPLOY_SSH_KEY" > "$key"
printf '\n' >> "$key"
chmod 600 "$key"
ssh-keygen -y -P '' -f "$key" >/dev/null
remote() {
  ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
    -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$MUPID_DEPLOY_KNOWN_HOSTS" \
    -i "$key" mupid-deploy@j15a204.p.ssafy.io "$@"
}
if [ "$mode" = resume ]; then
  if remote "resume $CI_COMMIT_SHA" < /dev/null; then
    :
  else
    status=$?
    if [ "$status" -eq 64 ]; then
      echo 'RECEIVER_UPGRADE_REQUIRED: install receive.sh and ssh-entrypoint.sh before this pipeline' >&2
    fi
    exit "$status"
  fi
else
  (cd .release && sha256sum -c image.tar.gz.sha256)
  checksum=$(sha256sum .release/image.tar.gz | cut -d ' ' -f 1)
  remote "deploy $CI_COMMIT_SHA $checksum" < .release/image.tar.gz
fi
curl --fail --silent --show-error --max-time 15 https://j15a204.p.ssafy.io/api/actuator/health | grep -q '"status":"UP"'
