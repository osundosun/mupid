#!/usr/bin/env bash
# Root-owned deployment endpoint. Input: SHA checksum, stdin: gzip Docker archive.
set -euo pipefail
umask 077
[[ $EUID == 0 && $# == 2 && $1 =~ ^[0-9a-f]{40}$ && $2 =~ ^[0-9a-f]{64}$ ]] || exit 64
release=$1
checksum=$2
root=/srv/mupid
exec 9>"$root/deploy.lock"
flock -w 300 9 || exit 75
directory="$root/releases/$release"
mkdir -p "$directory"
if [[ -f "$directory/image.sha256" && $(cat "$directory/image.sha256") != "$checksum" ]]; then
  echo 'An immutable release already exists with a different checksum' >&2
  exit 65
fi
temporary=$(mktemp "$directory/upload.XXXXXX")
trap 'rm -f "$temporary"' EXIT
head -c 1073741825 > "$temporary"
[[ $(stat -c %s "$temporary") -lt 1073741825 ]] || { echo 'Image exceeds 1 GiB' >&2; exit 65; }
actual=$(sha256sum "$temporary" | cut -d ' ' -f 1)
[[ $actual == "$checksum" ]] || { echo 'Checksum mismatch; deployment not started' >&2; exit 65; }
gzip -t "$temporary"
python3 - "$temporary" "mupid-api:$release" <<'PY'
import json, sys, tarfile
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    member = archive.getmember('manifest.json')
    if member.size > 1048576:
        raise SystemExit('Oversized image manifest')
    manifest = json.load(archive.extractfile(member))
    if len(manifest) != 1 or manifest[0].get('RepoTags') != [sys.argv[2]]:
        raise SystemExit('Archive must contain exactly the requested image tag')
PY
image="mupid-api:$release"
# Validate interpolation without printing resolved secrets or replacing running containers.
# Server configuration must be installed separately; CI only uploads the image.
if ! MUPID_API_IMAGE="$image" docker compose --env-file "$root/.env" -f "$root/config/compose.yml" config --quiet; then
  echo 'DEPLOY_CONFIG_INVALID: fix server configuration before retrying; running API preserved' >&2
  exit 78
fi
docker load -i "$temporary" >/dev/null
docker image inspect "$image" >/dev/null
mv "$temporary" "$directory/image.tar.gz"
printf '%s\n' "$checksum" > "$directory/image.sha256"
mkdir -p "$directory/config-snapshot"
cp -a "$root/config/." "$directory/config-snapshot/"
previous=$(cat "$root/current-image")
[[ $previous =~ ^mupid-api:([0-9a-f]{40}|bootstrap)$ ]] || exit 65
if [[ $previous == "$image" ]]; then
  echo "ALREADY_DEPLOYED $image"
  exit 0
fi
apply_image() {
  local candidate=$1
  MUPID_API_IMAGE="$candidate" docker compose --env-file "$root/.env" -f "$root/config/compose.yml" up -d --no-build --wait --wait-timeout 180 api || return 1
  # Resolve API's new container address after each replacement.
  MUPID_API_IMAGE="$candidate" docker compose --env-file "$root/.env" -f "$root/config/compose.yml" up -d --no-deps --force-recreate --wait --wait-timeout 60 nginx || return 1
  curl --fail --silent --show-error --max-time 15 --resolve j15a204.p.ssafy.io:443:127.0.0.1 https://j15a204.p.ssafy.io/api/actuator/health | grep -q '"status":"UP"' || return 1
}
if apply_image "$image" >"$directory/deploy.log" 2>&1; then
  printf '%s\n' "$previous" > "$root/previous-image"
  printf '%s\n' "$image" > "$root/current-image.new"
  mv "$root/current-image.new" "$root/current-image"
  date -u +%FT%TZ > "$directory/succeeded-at"
  printf 'DEPLOYED %s\n' "$image"
else
  echo 'Deployment health check failed; restoring previous image' >&2
  if apply_image "$previous" >>"$directory/deploy.log" 2>&1; then
    printf 'ROLLED_BACK %s\n' "$previous" >&2
  else
    echo 'ROLLBACK_FAILED: operator intervention required' >&2
  fi
  exit 1
fi
