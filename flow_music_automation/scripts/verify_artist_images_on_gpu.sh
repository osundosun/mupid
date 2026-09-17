#!/usr/bin/env bash
set -euo pipefail
: "${GPU_SSH_KEY:?Set GPU_SSH_KEY to your GPU server SSH private key path}"
: "${GPU_HOST:?Set GPU_HOST to user@host, e.g. export GPU_HOST=youruser@your.gpu.host}"
KEY="$GPU_SSH_KEY"
ssh -i "$KEY" "$GPU_HOST" "
echo '-- image count --'
ls ~/flow_music_data/artists/artists_images | wc -l
echo '-- artists.json profile_image spot check --'
python3 -c \"
import json
a = json.load(open('flow_music_data/artists.json'))
for x in a:
    if x['artist_key'] in ('female_korean_pop_mainstream_02','female_korean_indie_soft_01'):
        print(x['artist_name'], x['profile_image']['path'], x['profile_image']['sha256'][:16])
\"
echo '-- sample file exists --'
ls -la ~/flow_music_data/artists/artists_images/샛별.png ~/flow_music_data/artists/artists_images/결.png
"
