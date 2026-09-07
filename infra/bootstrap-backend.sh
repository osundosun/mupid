#!/usr/bin/env bash
# Initial localhost-only smoke deployment. Run from the copied api directory.
set -euo pipefail
[[ -f compose.prod.yml ]] || { echo 'Run from api directory' >&2; exit 1; }
umask 077
if [[ ! -e .env ]]; then
  db_password=$(openssl rand -hex 32)
  jwt_secret=$(openssl rand -base64 48 | tr -d '\n')
  printf 'MUPID_PORT=8800\nMUPID_NGINX_PORT=8080\nMUPID_MYSQL_USER=root\nMUPID_MYSQL_PASSWORD=%s\nMUPID_JWT_SECRET=%s\n' "$db_password" "$jwt_secret" > .env
  unset db_password jwt_secret
fi
cat > compose.bootstrap.yml <<'YAML'
services:
  api:
    image: mupid-api:bootstrap
  nginx:
    ports: !override
      - '127.0.0.1:8080:80'
YAML
sudo docker compose -f compose.prod.yml -f compose.bootstrap.yml config --quiet
sudo docker compose -f compose.prod.yml -f compose.bootstrap.yml up -d --no-build --wait --wait-timeout 240
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/api/actuator/health
