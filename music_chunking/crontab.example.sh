#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────
# music_chunking crontab 설정 예시
# ─────────────────────────────────────────────────────────
#
# 설치:
#   crontab -e  →  아래 라인 추가
#
# ─────────────────────────────────────────────────────────

# 환경 변수
MUSIC_CHUNKING_DIR="/opt/music_chunking"
UV_BIN="/usr/local/bin/uv"
LOG_DIR="/var/log/music_chunking"

# 매시 정각에 배치 처리 실행
# 0 * * * * cd $MUSIC_CHUNKING_DIR && $UV_BIN run python -m music_chunking batch --config config.json >> $LOG_DIR/batch_$(date +\%Y\%m\%d).log 2>&1

# 매일 새벽 2시에 실행 (권장)
# 0 2 * * * cd $MUSIC_CHUNKING_DIR && $UV_BIN run python -m music_chunking batch --config config.json >> $LOG_DIR/batch_$(date +\%Y\%m\%d).log 2>&1

# 10분마다 실행 (실시간에 가까운 처리가 필요한 경우)
# */10 * * * * cd $MUSIC_CHUNKING_DIR && $UV_BIN run python -m music_chunking batch --config config.json >> $LOG_DIR/batch_$(date +\%Y\%m\%d).log 2>&1

# ─────────────────────────────────────────────────────────
# 로그 로테이션 (30일 이상 된 로그 삭제)
# 0 3 * * * find $LOG_DIR -name "*.log" -mtime +30 -delete
# ─────────────────────────────────────────────────────────
