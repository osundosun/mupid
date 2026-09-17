#!/usr/bin/env bash
# One-off: sync ONLY downloads/covers to the GPU box (the 31 covers just
# fixed locally by scripts/refetch_covers.py). No --delete: this only
# overwrites files that differ, never removes anything remote.
set -euo pipefail

: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"

RSH="ssh -i $GPU_SSH_KEY"
SRC="/mnt/c/Users/SSAFY/flow-music-automation/downloads/covers/"
DEST="${GPU_HOST}:~/flow_music_data/covers/"

MODE="${1:-dry-run}"

if [ "$MODE" = "dry-run" ]; then
  echo "--- DRY RUN (no files transferred) ---"
  rsync -avh --dry-run -e "$RSH" "$SRC" "$DEST"
else
  echo "--- APPLYING ---"
  rsync -avh -e "$RSH" "$SRC" "$DEST"
fi
