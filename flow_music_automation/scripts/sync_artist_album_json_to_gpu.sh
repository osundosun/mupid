#!/usr/bin/env bash
# artists.json / albums.json / genre_mood_taxonomy.json을 GPU 서버 루트에 복사.
# downloads/{audio,covers,metadata}와 달리 이들은 프로젝트 루트 파일이라
# 기존 sync_to_gpu_full_mirror.sh 범위 밖 -> 별도 scp.
set -euo pipefail

: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"

KEY="$GPU_SSH_KEY"
SRC_ROOT="/mnt/c/Users/SSAFY/flow-music-automation"
DEST="${GPU_HOST}:~/flow_music_data"

scp -i "$KEY" "${SRC_ROOT}/artists.json" "${SRC_ROOT}/albums.json" "${SRC_ROOT}/genre_mood_taxonomy.json" "${DEST}/"
