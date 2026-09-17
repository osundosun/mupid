#!/usr/bin/env bash
# 완전 미러링(--delete 포함): 로컬 downloads/{audio,covers,metadata}를 GPU 서버에
# 정확히 일치시킴 (신규 20곡 오디오/커버 + 전체 1060개 metadata 재반영을 위해
# 이번엔 증분이 아니라 --delete로 완전 동기화). DRY_RUN=1이면 삭제 없이 미리보기만.
set -euo pipefail

: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"

RSH="ssh -i $GPU_SSH_KEY"
SRC="/mnt/c/Users/SSAFY/flow-music-automation/downloads"
DEST="${GPU_HOST}:~/flow_music_data"

FLAGS="-avh --delete"
if [ "${DRY_RUN:-0}" = "1" ]; then
  FLAGS="$FLAGS --dry-run"
  echo "=== DRY RUN (no changes will be made) ==="
fi

for sub in audio covers metadata; do
  echo "--- syncing ${sub} ---"
  rsync $FLAGS -e "$RSH" "${SRC}/${sub}/" "${DEST}/${sub}/"
done
