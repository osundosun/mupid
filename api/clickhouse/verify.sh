#!/usr/bin/env bash
# 로컬 ClickHouse 클러스터 + Kafka 파이프라인 스모크 테스트.
#
#   cd api && ./clickhouse/verify.sh
#
# 비밀번호는 MUPID_CLICKHOUSE_PASSWORD 환경변수나 api/.env 에서 읽는다.
# 고정 ID 로만 데이터를 넣으므로 여러 번 실행해도 결과가 같다.
set -uo pipefail

# Git Bash(MSYS)는 '/' 로 시작하는 인자를 Windows 경로로 변환한다. 그대로 두면
# docker exec 에 넘기는 컨테이너 내부 경로 /opt/kafka/bin/... 이
# C:/Program Files/Git/opt/kafka/bin/... 으로 바뀌어 exec 가 실패한다.
# (apache/kafka 이미지는 kafka 툴을 PATH 에 넣어주지 않아 절대경로가 필요하다.)
# 리눅스/맥에서는 쓰이지 않는 환경변수라 무해하다.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

CH1="${CH1:-mupid-dev-ch-01}"
CH2="${CH2:-mupid-dev-ch-02}"
KAFKA="${KAFKA:-mupid-dev-kafka}"
TOPIC="${TOPIC:-mupid.listen.events}"
CH_USER="${MUPID_CLICKHOUSE_USER:-mupid}"
CH_PASSWORD="${MUPID_CLICKHOUSE_PASSWORD:-}"

ENV_FILE="${ENV_FILE:-.env}"
if [ -z "$CH_PASSWORD" ] && [ -f "$ENV_FILE" ]; then
  CH_PASSWORD=$(grep -E '^[[:space:]]*MUPID_CLICKHOUSE_PASSWORD=' "$ENV_FILE" \
    | head -1 | cut -d= -f2- | tr -d '\r' | sed 's/[[:space:]]*$//')
fi
if [ -z "$CH_PASSWORD" ]; then
  echo "MUPID_CLICKHOUSE_PASSWORD 를 환경변수나 $ENV_FILE 로 넘겨야 한다" >&2
  exit 1
fi

PASS=0
FAIL=0

# docker exec 에 -i 를 붙이면 안 된다. clickhouse-client 는 INSERT 일 때 추가 데이터를
# stdin 에서 읽으려 하는데, 이 스크립트를 터미널에서 직접 실행하면 stdin 이 키보드라
# EOF 가 오지 않아 INSERT 에서 영원히 멈춘다. (파이프로 실행하면 우연히 동작해서
# 놓치기 쉽다.) 아래 Kafka producer 는 heredoc 을 넣어야 하므로 거기서만 -i 를 쓴다.
ch() {
  local host="$1"; shift
  docker exec "$host" clickhouse-client --host 127.0.0.1 \
    --user "$CH_USER" --password "$CH_PASSWORD" "$@"
}

q() { ch "$1" --query "$2" 2>&1; }

# check <설명> <실제> <기대>
check() {
  local label="$1" actual="$2" expected="$3"
  if [ "$actual" = "$expected" ]; then
    printf '  \033[32mPASS\033[0m  %-58s %s\n' "$label" "$actual"
    PASS=$((PASS + 1))
  else
    printf '  \033[31mFAIL\033[0m  %-58s got=[%s] want=[%s]\n' "$label" "$actual" "$expected"
    FAIL=$((FAIL + 1))
  fi
}

# docker compose up -d 는 1회성 컨테이너가 "끝나는" 것까지 기다려주지 않는다.
# 게다가 실행 중인 컨테이너의 .State.ExitCode 는 0 이라서, 종료 코드만 보면
# 아직 DDL 을 적용하는 중인데도 통과해버린다. Status 가 exited 가 될 때까지 기다린다.
wait_exited() {
  local container="$1" status
  local i
  for i in $(seq 1 120); do
    status=$(docker inspect "$container" --format '{{.State.Status}}' 2>/dev/null || echo missing)
    case "$status" in
      exited)  return 0 ;;
      missing) return 1 ;;
    esac
    sleep 2
  done
  return 1
}

echo "==> 0. 초기화 컨테이너 (완료까지 대기)"
for c in mupid-dev-kafka-init mupid-dev-ch-init; do
  if wait_exited "$c"; then
    check "$c 정상 종료" "$(docker inspect "$c" --format '{{.State.ExitCode}}')" "0"
  else
    check "$c 정상 종료" "exited 상태가 되지 않음" "0"
  fi
done

echo "==> 1. 클러스터 토폴로지"
check "mupid = 2 샤드" \
  "$(q "$CH1" "SELECT count() FROM system.clusters WHERE cluster='mupid'")" "2"
check "mupid_all = 1 샤드 2 레플리카" \
  "$(q "$CH1" "SELECT concat(toString(uniqExact(shard_num)),'/',toString(count())) FROM system.clusters WHERE cluster='mupid_all'")" "1/2"

echo "==> 2. 스키마 (두 노드가 동일해야 한다)"
objects="SELECT concat(
    toString(countIf(engine LIKE 'Replicated%')), '/',
    toString(countIf(engine = 'Distributed')), '/',
    toString(countIf(engine LIKE '%View')), '/',
    toString(countIf(engine = 'Kafka')), '/',
    toString(countIf(engine = 'Dictionary')))
  FROM system.tables WHERE database='default'"
o1=$(q "$CH1" "$objects")
o2=$(q "$CH2" "$objects")
check "ch-01 replicated/distributed/view/kafka/dict" "$o1" "5/5/4/1/1"
check "ch-02 replicated/distributed/view/kafka/dict" "$o2" "5/5/4/1/1"

echo "==> 3. ON CLUSTER DDL 큐에 실패 없음"
check "DDL 큐 실패 건수" \
  "$(q "$CH1" "SELECT count() FROM system.distributed_ddl_queue WHERE status != 'Finished' OR exception_code != 0")" "0"

echo "==> 4. track_sections (mupid_all 전 노드 복제)"
q "$CH1" "INSERT INTO track_sections
    (track_id, analysis_version, section_index, section_uid, fn, start_ms, end_ms, peak_ms)
  SETTINGS distributed_foreground_insert = 1
  VALUES
    (1001,'v1',1,'s1001-intro','INTRO',0,30000,15000),
    (1001,'v1',2,'s1001-hook','HOOK',30000,60000,45000),
    (2002,'v1',1,'s2002-intro','INTRO',0,25000,10000)" >/dev/null
# 수동 테스트로 다른 트랙 구간을 넣어도 깨지지 않도록 이 스크립트가 넣은 것만 센다.
sections="SELECT uniqExact(section_uid) FROM track_sections_local
          WHERE analysis_version = 'v1' AND track_id IN (1001, 2002)"
check "ch-01 구간 수" "$(q "$CH1" "$sections")" "3"
check "ch-02 구간 수 (복제됨)" "$(q "$CH2" "$sections")" "3"

echo "==> 5. RANGE_HASHED Dictionary"
for node in "$CH1" "$CH2"; do
  q "$node" "SYSTEM RELOAD DICTIONARY default.dict_track_sections" >/dev/null
  check "$node dictGet(1001, 15000ms)" \
    "$(q "$node" "SELECT dictGet('dict_track_sections','section_uid', toUInt64(1001), toUInt32(15000))")" \
    "s1001-intro"
done
check "구간 밖 조회는 빈 문자열 (MV 필터 조건)" \
  "$(q "$CH1" "SELECT dictGet('dict_track_sections','section_uid', toUInt64(1001), toUInt32(999999)) = ''")" "1"

echo "==> 6. Kafka -> mv_kafka_to_events -> listen_events"
docker exec -i "$KAFKA" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic "$TOPIC" >/dev/null <<'EVENTS'
{"event_id":"11111111-1111-4111-8111-111111111111","event_type":"PLAY_START","user_id":42,"track_id":1001,"occurred_at":"2026-01-01 10:00:00.000","play_id":"aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa","entry_path":"RECOMMEND","position_ms":0}
{"event_id":"22222222-2222-4222-8222-222222222222","event_type":"LIKE","user_id":42,"track_id":1001,"occurred_at":"2026-01-01 10:00:15.000","play_id":"aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa","entry_path":"RECOMMEND","position_ms":15000}
{"event_id":"33333333-3333-4333-8333-333333333333","event_type":"SECTION_MARK","user_id":42,"track_id":1001,"occurred_at":"2026-01-01 10:00:45.000","play_id":"aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa","entry_path":"RECOMMEND","position_ms":45000}
{"event_id":"44444444-4444-4444-8444-444444444444","event_type":"PLAYLIST_ADD","user_id":77,"track_id":2002,"occurred_at":"2026-01-01 10:01:10.000","play_id":"bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb","entry_path":"SEARCH","position_ms":10000,"playlist_id":900}
{"event_id":"55555555-5555-4555-8555-555555555555","event_type":"DISLIKE","user_id":77,"track_id":1001,"occurred_at":"2026-01-01 10:01:30.000","play_id":"bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb","entry_path":"SEARCH","position_ms":20000}
{"event_id":"66666666-6666-4666-8666-666666666666","event_type":"TRACK_SKIP","user_id":42,"track_id":1001,"occurred_at":"2026-01-01 10:01:50.000","play_id":"aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa","entry_path":"RECOMMEND","position_ms":5000,"skip_trigger":"NEXT_BUTTON"}
EVENTS
check "Kafka producer 실행" "$?" "0"

# 수동 테스트로 다른 이벤트를 넣어도 깨지지 않도록 이 스크립트가 넣은 것만 센다.
TEST_IDS="'11111111-1111-4111-8111-111111111111',
          '22222222-2222-4222-8222-222222222222',
          '33333333-3333-4333-8333-333333333333',
          '44444444-4444-4444-8444-444444444444',
          '55555555-5555-4555-8555-555555555555',
          '66666666-6666-4666-8666-666666666666'"

# Kafka 엔진은 kafka_flush_interval_ms 주기로 넘기므로 잠깐 기다린다.
events=0
for _ in $(seq 1 30); do
  events=$(q "$CH1" "SELECT uniqExact(event_id) FROM listen_events WHERE event_id IN ($TEST_IDS)")
  [ "$events" = "6" ] && break
  sleep 2
done
check "listen_events 이벤트 수" "$events" "6"
check "occurred_at 이 Asia/Seoul 로 파싱됨" \
  "$(q "$CH1" "SELECT toString(occurred_at) FROM listen_events WHERE event_id='22222222-2222-4222-8222-222222222222' LIMIT 1")" \
  "2026-01-01 10:00:15.000"

echo "==> 7. mv_user_section_signals (dictGet 로 구간 매핑)"
check "시그널 수 (PLAY_START/TRACK_SKIP 은 제외)" \
  "$(q "$CH1" "SELECT uniqExact((user_id, section_uid, signal)) FROM user_section_signals WHERE user_id IN (42, 77)")" "4"
check "LIKE 가중치" \
  "$(q "$CH1" "SELECT any(weight) FROM user_section_signals WHERE user_id=42 AND signal='LIKE'")" "1"
check "SECTION_MARK -> s1001-hook" \
  "$(q "$CH1" "SELECT any(section_uid) FROM user_section_signals WHERE user_id=42 AND signal='SECTION_MARK'")" "s1001-hook"
check "DISLIKE 가중치 (음수)" \
  "$(q "$CH1" "SELECT any(weight) FROM user_section_signals WHERE user_id=77 AND signal='DISLIKE'")" "-1"

echo "==> 8. play_section_coverage / rec_impressions (Array, Map 왕복)"
# 구간은 일부러 s1001-hook 을 쓴다. v_track_skips 의 r 서브쿼리가
# (user_id, section_uid) 로 "이미 충분히 들은 구간" 을 세는데,
# TRACK_SKIP 이벤트가 가리키는 구간(s1001-intro)에 커버리지가 있으면
# skip_class 가 EARLY_SKIP 대신 SKIP_AFTER_HOOK 으로 바뀌어 9번 검증과 충돌한다.
q "$CH1" "INSERT INTO play_section_coverage
    (user_id, play_id, track_id, section_uid, entry_path, listened_ms, section_ms, reached, started_at)
  SETTINGS distributed_foreground_insert = 1
  VALUES (42,'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa',1001,'s1001-hook','RECOMMEND',20000,30000,1,'2026-01-01 10:00:00.000')" >/dev/null
check "play_section_coverage 적재" \
  "$(q "$CH1" "SELECT uniqExact((user_id, play_id, section_uid)) FROM play_section_coverage WHERE user_id = 42")" "1"

q "$CH1" "INSERT INTO rec_impressions
    (rec_id, user_id, rec_type, served_at, section_uids, scores, explore_flags,
     seed_section_uid, pref_section_uids, signal_weights, model_version,
     embedding_version, analysis_version, ext)
  SETTINGS distributed_foreground_insert = 1
  VALUES ('rec-0001',42,'SECTION','2026-01-01 10:02:00.000',
     ['s1001-hook','s2002-intro'],[0.91,0.42],[0,1],
     's1001-intro',['s1001-hook'],{'LIKE':1.0,'DISLIKE':-1.0},'m1','e1','v1',{'ab':'B'})" >/dev/null
check "rec_impressions Array(String) 왕복" \
  "$(q "$CH1" "SELECT section_uids[1] FROM rec_impressions WHERE rec_id='rec-0001' LIMIT 1")" "s1001-hook"
check "rec_impressions Array(Float32) 왕복" \
  "$(q "$CH1" "SELECT round(scores[1], 2) FROM rec_impressions WHERE rec_id='rec-0001' LIMIT 1")" "0.91"
check "rec_impressions Map(String,Float32) 왕복" \
  "$(q "$CH1" "SELECT signal_weights['LIKE'] FROM rec_impressions WHERE rec_id='rec-0001' LIMIT 1")" "1"
check "rec_impressions Map(String,String) 왕복" \
  "$(q "$CH1" "SELECT ext['ab'] FROM rec_impressions WHERE rec_id='rec-0001' LIMIT 1")" "B"

echo "==> 9. v_track_skips (distributed_product_mode=global 필요)"
# event_id 로 못 박는다. 수동 테스트로 TRACK_SKIP 을 더 넣어도 이 건만 본다.
# (컬럼명에 별칭이 남아 s.user_id 는 백틱이 필요하지만 event_id 는 깔끔하다)
skip=$(q "$CH1" "SELECT concat(skip_class,'/',toString(weight)) FROM v_track_skips
                 WHERE event_id = '66666666-6666-4666-8666-666666666666' LIMIT 1")
check "EARLY_SKIP + RECOMMEND + NEXT_BUTTON -> -1" "$skip" "EARLY_SKIP/-1"

echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m%s\033[0m\n' "모두 통과 ($PASS)"
else
  printf '\033[31m%s\033[0m\n' "실패 $FAIL / 통과 $PASS"
fi
exit $(( FAIL > 0 ? 1 : 0 ))
