#!/usr/bin/env bash
# 운영 DB 의 스키마를 TSV 로 내보낸다. 노드에서 실행한다.
#
#   sudo infra_k8s/deploy/schema-dump.sh > live.tsv
#
# 그 파일을 저장소가 있는 곳으로 가져가 대조한다.
#
#   python3 infra_k8s/deploy/schema-check.py --schema live.tsv
#
# 스키마만 읽는다. 데이터도 비밀번호도 출력하지 않는다 — 비밀번호는 서버의
# .env 에서 읽어 mysql 에 넘기기만 한다.
set -euo pipefail

export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
env_file=${MUPID_K8S_ENV:-/srv/mupid-k8s/.env}

kube() {
  if command -v kubectl >/dev/null 2>&1; then kubectl "$@"; else k3s kubectl "$@"; fi
}

password=$(grep -m1 '^MUPID_MYSQL_ROOT_PASSWORD=' "$env_file" | cut -d= -f2-)
[ -n "$password" ] || { echo "$env_file 에서 MUPID_MYSQL_ROOT_PASSWORD 를 찾지 못했다" >&2; exit 1; }

kube -n mupid exec -i statefulset/mysql -- \
  mysql -uroot -p"$password" -N -B -e "
    SELECT table_name, column_name, column_type
    FROM information_schema.columns
    WHERE table_schema='mupid'
    ORDER BY table_name, ordinal_position" 2>/dev/null \
  | grep -v 'Using a password'
