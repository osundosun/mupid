#!/bin/sh
set -eu
# Only 44 means the upgraded receiver has no stored release.
# Protocol/config/health failures must never silently trigger a rebuild.
if sh infra/ci/deploy.sh resume; then
  exit 0
else
  status=$?
  [ "$status" -eq 44 ] || exit "$status"
fi
sh infra/ci/package-image.sh
sh infra/ci/deploy.sh upload
