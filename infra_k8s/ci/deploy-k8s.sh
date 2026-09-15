#!/bin/sh
# Transmit one image to the k8s deployment endpoint.
#
#   sh infra_k8s/ci/deploy-k8s.sh api
#   sh infra_k8s/ci/deploy-k8s.sh web
#
# Separate key and separate account from infra/ci/deploy.sh: the Compose
# endpoint keeps its own credential until it is retired, so neither key can
# drive the other path.
#
# There is no `resume` mode. The Compose receiver has one because a re-run must
# not rebuild an image that is already stored; here a repeat of the same commit
# is answered by the receiver with ALREADY_DEPLOYED after it re-checks the
# rollout, which costs one upload and keeps this script to one shape.
set -eu
: "${MUPID_DEPLOY_K8S_SSH_KEY:?GitLab file variable required}"
: "${MUPID_DEPLOY_K8S_KNOWN_HOSTS:?GitLab file variable required}"
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

component=${1:?Expected api or web}
case "$component" in
  api) archive=.release/image.tar.gz ;;
  web) archive=.release/web/image.tar.gz ;;
  *) echo 'Unknown component' >&2; exit 64 ;;
esac
[ -s "$archive" ] || { echo "No archive at $archive; run the package job first" >&2; exit 64; }

# The edge answers on 8443 until it takes 80/443, so the verification target is
# a variable rather than the production URL.
base=${MUPID_K8S_BASE_URL:-https://j15a204.p.ssafy.io}

umask 077
key=$(mktemp)
trap 'rm -f "$key"' EXIT
tr -d '\r' < "$MUPID_DEPLOY_K8S_SSH_KEY" > "$key"
printf '\n' >> "$key"
chmod 600 "$key"
ssh-keygen -y -P '' -f "$key" >/dev/null

(cd "$(dirname "$archive")" && sha256sum -c image.tar.gz.sha256)
checksum=$(sha256sum "$archive" | cut -d ' ' -f 1)

ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$MUPID_DEPLOY_K8S_KNOWN_HOSTS" \
  -i "$key" mupid-deploy-k8s@j15a204.p.ssafy.io \
  "deploy-k8s $component $CI_COMMIT_SHA $checksum" < "$archive"

# The receiver already waited for the rollout; this confirms the change is
# visible through the edge rather than only inside the cluster.
case "$component" in
  api)
    curl --fail --silent --show-error --max-time 15 "$base/api/actuator/health" | grep -q '"status":"UP"'
    ;;
  web)
    test "$(curl --fail --silent --show-error --max-time 15 "$base/deployment.txt")" = "$CI_COMMIT_SHA"
    ;;
esac
