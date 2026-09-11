#!/usr/bin/env bash
# Root-owned deployment endpoint. Input: SHA checksum, stdin: gzip Docker archive.
set -euo pipefail
umask 077
[[ $EUID == 0 && $# == 2 ]] || exit 64
mode=upload
if [[ $1 == --resume && $2 =~ ^[0-9a-f]{40}$ ]]; then
  mode=resume
  release=$2
elif [[ $1 =~ ^[0-9a-f]{40}$ && $2 =~ ^[0-9a-f]{64}$ ]]; then
  release=$1
  checksum=$2
else
  exit 64
fi
root=/srv/mupid
exec 9>"$root/deploy.lock"
flock -w 300 9 || exit 75
directory="$root/releases/$release"
if [[ $mode == resume ]]; then
  if [[ ! -e "$directory/image.sha256" && ! -e "$directory/image.tar.gz" ]]; then
    echo 'RELEASE_NOT_STORED'
    exit 44
  fi
  [[ -s "$directory/image.sha256" && -s "$directory/image.tar.gz" ]] || {
    echo 'STORED_RELEASE_INCOMPLETE: operator recovery required' >&2; exit 65;
  }
  checksum=$(cat "$directory/image.sha256")
  [[ $checksum =~ ^[0-9a-f]{64}$ ]] || exit 65
fi
mkdir -p "$directory"
if [[ -f "$directory/image.sha256" && $(cat "$directory/image.sha256") != "$checksum" ]]; then
  echo 'An immutable release already exists with a different checksum' >&2
  exit 65
fi
temporary=$(mktemp "$directory/upload.XXXXXX")
trap 'rm -f "$temporary"' EXIT
if [[ $mode == resume ]]; then
  head -c 1073741825 < "$directory/image.tar.gz" > "$temporary"
else
  head -c 1073741825 > "$temporary"
fi
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
if ! MUPID_API_IMAGE="$image" python3 - "$root" <<'PY'
import json, os, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1])
try:
    result = subprocess.run(['docker', 'compose', '--env-file', str(root / '.env'),
        '-f', str(root / 'config/compose.yml'), 'config', '--format', 'json'],
        capture_output=True, text=True, check=True, timeout=30)
    values = json.loads(result.stdout)['services']['api']['environment']
    if values['MUPID_MYSQL_USER'].lower() == 'root':
        print('DEPLOY_DB_ADMIN_ACCOUNT: configure the application DB account', file=sys.stderr)
        sys.exit(1)
    result = subprocess.run(['docker', 'exec', '-e', 'MYSQL_PWD', 'mupid-mysql',
        'mysql', '-hmysql', '-u' + values['MUPID_MYSQL_USER'], 'mupid', '-N', '-e', 'SELECT 1'],
        env=dict(os.environ, MYSQL_PWD=values['MUPID_MYSQL_PASSWORD']),
        capture_output=True, timeout=15)
    if result.returncode:
        sys.exit(1)
except (subprocess.SubprocessError, KeyError, ValueError, OSError):
    sys.exit(1)
PY
then
  echo 'DEPLOY_DB_UNREACHABLE: check application DB credentials/connectivity; running API preserved' >&2
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
  # A recorded version is not proof that the current container is healthy.
  running=$(docker inspect --format '{{.Config.Image}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' mupid-api 2>/dev/null) || running=''
  if [[ $running != "$image running healthy" ]] || ! curl --fail --silent --show-error --max-time 15 --resolve j15a204.p.ssafy.io:443:127.0.0.1 https://j15a204.p.ssafy.io/api/actuator/health | grep -q '"status":"UP"'; then
    echo 'SAME_RELEASE_UNHEALTHY: recorded version is not healthy; operator recovery required' >&2
    exit 1
  fi
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
