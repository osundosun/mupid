#!/usr/bin/env bash
# ON CLUSTER DDL 은 한 노드에서 한 번만 실행하면 Keeper 의 DDL 큐를 통해 전 노드로
# 전파된다. 그래서 각 노드의 docker-entrypoint-initdb.d 에 넣지 않고, 두 노드가
# 모두 healthy 해진 뒤 전용 컨테이너에서 한 번만 적용한다.
#
# 스키마는 default 데이터베이스에 만든다. ddl.sql 의 dict_track_sections 는
#   SOURCE(CLICKHOUSE(TABLE 'v_track_sections_current'))
# 처럼 DB 를 명시하지 않아서 사전 로딩 시 default 에서 뷰를 찾는다. 별도 DB 에
# 스키마를 만들려면 DDL 에 DB 절을 추가해야 하므로, 원본 DDL 을 그대로 쓰기 위해
# default 를 사용한다.
#
# DDL 은 IF NOT EXISTS / OR REPLACE 로만 되어 있어서 여러 번 실행해도 안전하다.
set -euo pipefail

NODES=("${CH_NODE_1:-mupid-dev-ch-01}" "${CH_NODE_2:-mupid-dev-ch-02}")
LEADER="${NODES[0]}"
DDL_FILE="${DDL_FILE:-/init/01-schema.sql}"
RETRIES="${RETRIES:-60}"

# default 계정은 공식 이미지가 127.0.0.1 / ::1 로 묶어두므로 컨테이너 밖에서는 못 쓴다.
# 앱 계정(mupid)으로 접속한다. remote_servers 도 같은 계정을 쓴다.
CH_USER="${MUPID_CLICKHOUSE_USER:-mupid}"
CH_PASSWORD="${MUPID_CLICKHOUSE_PASSWORD:?MUPID_CLICKHOUSE_PASSWORD 가 필요하다}"

ch() {
  local host="$1"; shift
  clickhouse-client --host "$host" --port 9000 --user "$CH_USER" --password "$CH_PASSWORD" "$@"
}

wait_for() {
  local host="$1" label="$2" query="$3"
  local i
  for i in $(seq 1 "$RETRIES"); do
    if ch "$host" --query "$query" >/dev/null 2>&1; then
      echo "  [ok] $label"
      return 0
    fi
    sleep 2
  done
  echo "  [FAIL] $label (${RETRIES} 회 재시도 후 포기)" >&2
  ch "$host" --query "$query" >&2 || true
  return 1
}

echo "==> 1/5 노드 응답 확인"
for node in "${NODES[@]}"; do
  wait_for "$node" "$node 쿼리 가능" "SELECT 1"
done

echo "==> 2/5 Keeper 연결 확인"
for node in "${NODES[@]}"; do
  wait_for "$node" "$node -> Keeper" "SELECT count() FROM system.zookeeper WHERE path = '/'"
done

echo "==> 3/5 클러스터 구성 확인"
for cluster in mupid mupid_all; do
  wait_for "$LEADER" "클러스터 $cluster 등록됨" \
    "SELECT throwIf(count() = 0, 'cluster $cluster missing') FROM system.clusters WHERE cluster = '$cluster'"
done
echo "--- system.clusters ---"
ch "$LEADER" --query "
  SELECT cluster, shard_num, replica_num, host_name, port
  FROM system.clusters
  WHERE cluster IN ('mupid', 'mupid_all')
  ORDER BY cluster, shard_num, replica_num
  FORMAT PrettyCompactMonoBlock"

echo "==> 4/5 DDL 적용 ($DDL_FILE, leader=$LEADER)"
ch "$LEADER" --database default --queries-file "$DDL_FILE"
echo "  [ok] DDL 실행 완료"

echo "==> 5/5 검증"
echo "--- ON CLUSTER DDL 큐에서 실패한 항목 (없어야 정상) ---"
ch "$LEADER" --query "
  SELECT host, port, status, exception_code, exception_text, query
  FROM system.distributed_ddl_queue
  WHERE status != 'Finished' OR exception_code != 0
  ORDER BY query_create_time
  FORMAT Vertical"

echo "--- 노드별 생성된 오브젝트 (두 노드가 같아야 한다) ---"
for node in "${NODES[@]}"; do
  printf '%-20s ' "$node"
  ch "$node" --query "
    SELECT concat(
      'tables=',       toString(countIf(engine NOT LIKE '%View' AND engine != 'Distributed')),
      ' distributed=', toString(countIf(engine = 'Distributed')),
      ' views=',       toString(countIf(engine LIKE '%View'))
    )
    FROM system.tables WHERE database = 'default'"
done

echo "--- Dictionary 로딩 상태 ---"
ch "$LEADER" --query "SYSTEM RELOAD DICTIONARY default.dict_track_sections" || true
for node in "${NODES[@]}"; do
  printf '%-20s ' "$node"
  ch "$node" --query "
    SELECT concat(name, ' status=', toString(status), ' err=', ifNull(last_exception, '-'))
    FROM system.dictionaries WHERE name = 'dict_track_sections'"
done

echo "--- Kafka 엔진 테이블 ---"
ch "$LEADER" --query "
  SELECT name, engine FROM system.tables
  WHERE database = 'default' AND engine = 'Kafka' FORMAT PrettyCompactMonoBlock"

echo "==> 완료"
