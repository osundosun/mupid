#!/usr/bin/env bash
set -euo pipefail
command=${SSH_ORIGINAL_COMMAND:-}
if [[ $command =~ ^deploy-front\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive-front "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi
if [[ $command =~ ^deploy\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  exec sudo /usr/local/sbin/mupid-receive "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi
echo 'Only versioned deployment uploads are accepted' >&2
exit 64
