#!/usr/bin/env bash
set -euo pipefail
: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"
KEY="$GPU_SSH_KEY"
ssh -i "$KEY" "$GPU_HOST" "python3 -c \"import json; a=json.load(open('flow_music_data/artists.json')); names={x['artist_key']:x['artist_name'] for x in a}; print(names['female_korean_pop_mainstream_02'], names['female_korean_indie_soft_01'])\""
