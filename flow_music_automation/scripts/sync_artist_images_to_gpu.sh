#!/usr/bin/env bash
# 로컬 artists/(artists_images 87장 + manifest.json + artist_mapping.md)를
# GPU 서버 ~/flow_music_data/artists/로 미러링. 전부 신규 파일이라 --delete 없이 증분.
set -euo pipefail

: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"

RSH="ssh -i $GPU_SSH_KEY"
SRC="/mnt/c/Users/SSAFY/flow-music-automation/artists"
DEST="${GPU_HOST}:~/flow_music_data/artists"

FLAGS="-avh"
if [ "${DRY_RUN:-0}" = "1" ]; then
  FLAGS="$FLAGS --dry-run"
  echo "=== DRY RUN (no changes will be made) ==="
fi

rsync $FLAGS -e "$RSH" "${SRC}/" "${DEST}/"
