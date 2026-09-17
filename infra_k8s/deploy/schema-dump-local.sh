#!/usr/bin/env bash
# 스키마를 schema-check.py 가 먹는 TSV 로 뜬다 (이 PC 에서 직접).
#
# 노드에는 kubectl 도 /srv/mupid-k8s/.env 도 없을 수 있어 `--live` 를 쓸 수 없다.
# 그때 이 스크립트로 뜬 파일을 `--schema` 로 넘긴다.
#
#   bash infra_k8s/deploy/schema-dump-local.sh prod.tsv            # 운영 (kubectl)
#   MUPID_SCHEMA_SOURCE=dev bash infra_k8s/deploy/schema-dump-local.sh dev.tsv
#
# `dev` 는 로컬 compose 의 `mupid-dev-mysql` 을 본다. **개발 DB 를 운영과 같은
# 눈으로 보려면 이게 필요하다** — 도구가 `extra`·인덱스를 안 보던 시절에는
# 개발 DB 의 차이도 드러나지 않았다.
set -euo pipefail

out=${1:?사용법: $0 <출력.tsv>}
source_kind=${MUPID_SCHEMA_SOURCE:-prod}

if [ "$source_kind" = "dev" ]; then
  run() {
    MSYS_NO_PATHCONV=1 docker exec -i mupid-dev-mysql \
      sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" mupid -N -B -e "'"$1"'"' 2>/dev/null
  }
else
  run() {
    kubectl -n mupid exec mysql-0 -- \
      sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" mupid -N -B -e "'"$1"'"' 2>/dev/null
  }
fi

run "
  SELECT table_name, column_name, column_type, extra
  FROM information_schema.columns
  WHERE table_schema='mupid'
  ORDER BY table_name, ordinal_position" | tr -d '\r' > "$out"

# 인덱스명은 **묶는 데만** 쓴다 (게이트는 모양만 본다). 이름이 없으면 복합
# 인덱스가 컬럼별로 쪼개져 `(a, b)` 를 영영 못 찾는다.
run "
  SELECT table_name, index_name, column_name, non_unique
  FROM information_schema.statistics
  WHERE table_schema='mupid' AND index_name <> 'PRIMARY'
  ORDER BY table_name, index_name, seq_in_index" | tr -d '\r' \
  | awk -F'\t' 'BEGIN{OFS="\t"}{print "I",$1,$2,$3,$4}' >> "$out"
