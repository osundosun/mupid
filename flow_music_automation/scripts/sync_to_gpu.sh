#!/usr/bin/env bash
# Sync downloads/{audio,covers,metadata} from this local project to the GPU
# Jupyter box over SSH/rsync. Run from WSL (Ubuntu-22.04): incremental by
# default (rsync only sends new/changed files), so safe to re-run after
# every 100-prompt batch.
set -euo pipefail

: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"

RSH="ssh -i $GPU_SSH_KEY"
SRC="/mnt/c/Users/SSAFY/flow-music-automation/downloads"
DEST="${GPU_HOST}:~/flow_music_data"

for sub in audio covers metadata; do
  echo "--- syncing ${sub} ---"
  rsync -avh --progress -e "$RSH" "${SRC}/${sub}/" "${DEST}/${sub}/"
done
