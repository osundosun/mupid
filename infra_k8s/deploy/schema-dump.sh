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
#
# **두 조회를 한 파일에 담는다.** 컬럼은 4칸(`table, column, type, extra`),
# 인덱스는 첫 칸에 `I` 를 붙여(`I, table, col1|col2, unique`) 구분한다.
#
# `extra` 를 빼면 안 된다. `AUTO_INCREMENT` 는 `column_type` 에 없고 `extra` 에만
# 있다 — 그걸 안 읽던 동안 `music_analysis_instruments` 의 AUTO_INCREMENT 가
# 정반대여도 대조가 "차이 없음" 을 냈다.
set -euo pipefail

export KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}
env_file=${MUPID_K8S_ENV:-/srv/mupid-k8s/.env}

kube() {
  if command -v kubectl >/dev/null 2>&1; then kubectl "$@"; else k3s kubectl "$@"; fi
}

password=$(grep -m1 '^MUPID_MYSQL_ROOT_PASSWORD=' "$env_file" | cut -d= -f2-)
[ -n "$password" ] || { echo "$env_file 에서 MUPID_MYSQL_ROOT_PASSWORD 를 찾지 못했다" >&2; exit 1; }

mysql_query() {
  kube -n mupid exec -i statefulset/mysql -- \
    mysql -uroot -p"$password" -N -B -e "$1" 2>/dev/null
}

mysql_query "
    SELECT table_name, column_name, column_type, extra
    FROM information_schema.columns
    WHERE table_schema='mupid'
    ORDER BY table_name, ordinal_position"

# 인덱스는 이름을 내보내지 않는다. 게이트가 보는 것은 모양뿐이다 — 이름은 ERD
# 도구가 붙이는 것이라 재산출할 때마다 바뀐다(erd.md 의 `uq_*` 주의와 같은 이유).
# PRIMARY 는 모든 표에 있으니 뺀다.
#
# **인덱스 하나가 여러 줄로 나온다** (복합 인덱스는 컬럼마다 한 줄). 뭉치는 일은
# `schema-check.py` 의 `parse_live` 가 한다 — 여기서 awk 로 접으면 컬럼 순서를
# 잃거나 중복 제거가 틀어지기 쉽다.
#
mysql_query "
    SELECT table_name, index_name, column_name, non_unique
    FROM information_schema.statistics
    WHERE table_schema='mupid' AND index_name <> 'PRIMARY'
    ORDER BY table_name, index_name, seq_in_index" \
  | awk -F'\t' 'BEGIN{OFS="\t"}{print "I",$1,$2,$3,$4}'
