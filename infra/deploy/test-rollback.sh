#!/usr/bin/env bash
# Run as root during a maintenance window: deliberately replaces the API briefly.
set -euo pipefail
[[ $EUID == 0 && ${1:-} == --allow-temporary-outage ]] || exit 64
umask 077
root=/srv/mupid
before=$(cat "$root/current-image")
context=$(mktemp -d /tmp/mupid-rollback-test.XXXXXX)
trap 'rm -rf -- "$context"' EXIT
cat > "$context/Dockerfile" <<'EOF'
FROM nginx:1.27-alpine
HEALTHCHECK --interval=1s --timeout=1s --start-period=0s --retries=2 CMD exit 1
ENTRYPOINT ["sleep", "infinity"]
EOF
# Synthetic test ID, deliberately not advertised as a source commit.
release=$(sha1sum "$context/Dockerfile" | cut -d ' ' -f 1)
docker build -q -t "mupid-api:$release" "$context" >/dev/null
docker save "mupid-api:$release" | gzip > "$context/image.tar.gz"
checksum=$(sha256sum "$context/image.tar.gz" | cut -d ' ' -f 1)
set +e
/usr/local/sbin/mupid-receive "$release" "$checksum" < "$context/image.tar.gz"
result=$?
set -e
[[ $result == 1 && $(cat "$root/current-image") == "$before" ]]
[[ $(docker inspect --format '{{.Config.Image}}' mupid-api) == "$before" ]]
curl --fail --silent --show-error https://j15a204.p.ssafy.io/api/actuator/health | grep -q '"status":"UP"'
echo "ROLLBACK_TEST_PASSED $before"
