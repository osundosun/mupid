#!/usr/bin/env bash
# Read-only snapshot. Does not collect environment variables or application logs.
set -uo pipefail
failed=0
report() {
  printf '\n## %s\n' "$1"
  shift
  if ! "$@"; then
    printf 'COLLECTION_FAILED\n' >&2
    failed=1
  fi
}
report 'UTC time' date -u +%FT%TZ
report 'Load' uptime
report 'Memory' free -m
report 'Swap' swapon --show
report 'Filesystem capacity' df -h / /srv
report 'Filesystem inodes' df -i / /srv
report 'Containers' docker ps -a --format '{{.Names}} {{.Status}}'
report 'Container resource usage (one sample)' docker stats --no-stream --format '{{.Name}} CPU={{.CPUPerc}} Memory={{.MemUsage}} Limit={{.MemPerc}} PIDs={{.PIDs}}'
report 'Docker disk usage' docker system df
for name in mupid-api mupid-mysql mupid-redis mupid-nginx mupid-storage; do
  # The Runner host need not have application containers.
  if docker container inspect "$name" >/dev/null 2>&1; then
    report "$name restart/OOM state" docker inspect --format '{{.Name}} Restarts={{.RestartCount}} OOMKilled={{.State.OOMKilled}}' "$name"
  fi
done
for directory in /srv/mupid/releases /srv/mupid/web /srv/mupid/backups /srv/mupid-backup-vault; do
  if [[ -d "$directory" ]]; then
    report "$directory size" du -sh "$directory"
  fi
done
report 'Backup/certificate timers' systemctl list-timers --all mupid-backup.timer certbot.timer --no-pager
exit "$failed"
