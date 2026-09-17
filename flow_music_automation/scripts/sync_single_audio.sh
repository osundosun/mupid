#!/usr/bin/env bash
# One-off: sync a single audio file to the GPU box (user manually
# re-downloaded a corrected take and dropped it into downloads/audio/).
set -euo pipefail

TRACK_ID="$1"
MODE="${2:-dry-run}"

: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"

RSH="ssh -i $GPU_SSH_KEY"
SRC="/mnt/c/Users/SSAFY/flow-music-automation/downloads/audio/${TRACK_ID}.mp3"
DEST="${GPU_HOST}:~/flow_music_data/audio/${TRACK_ID}.mp3"

if [ "$MODE" = "dry-run" ]; then
  echo "--- DRY RUN (no files transferred) ---"
  rsync -avh --dry-run -e "$RSH" "$SRC" "$DEST"
else
  echo "--- APPLYING ---"
  rsync -avh -e "$RSH" "$SRC" "$DEST"
fi
