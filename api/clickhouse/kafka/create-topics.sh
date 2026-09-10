#!/usr/bin/env bash
# ddl.sql 의 kafka_listen_events 가 소비할 토픽을 미리 만든다.
#
# 파티션 수는 6으로 잡는다. kafka_listen_events 는 ON CLUSTER mupid 로 두 노드에
# 각각 생성되고, 노드마다 kafka_num_consumers = 3 이라 컨슈머 그룹
# 'ch_listen_events' 에는 총 6개의 컨슈머가 붙는다. 파티션이 6보다 적으면
# 남는 컨슈머는 유휴 상태가 된다.
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-kafka1:9092}"
TOPIC="${TOPIC:-mupid.listen.events}"
PARTITIONS="${PARTITIONS:-6}"
REPLICATION="${REPLICATION:-1}"
KAFKA_BIN=/opt/kafka/bin
RETRIES="${RETRIES:-60}"

echo "==> 브로커 대기 ($BOOTSTRAP)"
for i in $(seq 1 "$RETRIES"); do
  if "$KAFKA_BIN/kafka-broker-api-versions.sh" --bootstrap-server "$BOOTSTRAP" >/dev/null 2>&1; then
    echo "  [ok] 브로커 응답"
    break
  fi
  if [ "$i" -eq "$RETRIES" ]; then
    echo "  [FAIL] 브로커가 응답하지 않는다" >&2
    exit 1
  fi
  sleep 2
done

echo "==> 토픽 생성 ($TOPIC, partitions=$PARTITIONS, rf=$REPLICATION)"
"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic "$TOPIC" \
  --partitions "$PARTITIONS" \
  --replication-factor "$REPLICATION"

echo "==> 결과"
"$KAFKA_BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe --topic "$TOPIC"
echo "==> 완료"
