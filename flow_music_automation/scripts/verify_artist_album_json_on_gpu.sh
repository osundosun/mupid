#!/usr/bin/env bash
set -euo pipefail
: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"
KEY="$GPU_SSH_KEY"
ssh -i "$KEY" "$GPU_HOST" "ls -la ~/flow_music_data/artists.json ~/flow_music_data/albums.json && python3 -c \"import json; a=json.load(open('flow_music_data/albums.json')); print(a[0]['album_key'], a[0].get('album_release_date'))\""
