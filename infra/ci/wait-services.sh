#!/usr/bin/env bash
set -euo pipefail
for target in mysql:3306 redis:6379; do
  host=${target%:*}
  port=${target##*:}
  ready=false
  for ((attempt=0; attempt<90; attempt++)); do
    if timeout 2 bash -c 'echo > /dev/tcp/$1/$2' _ "$host" "$port" 2>/dev/null; then
      ready=true
      break
    fi
    sleep 2
  done
  "$ready" || { echo "Timed out waiting for $target" >&2; exit 1; }
done
