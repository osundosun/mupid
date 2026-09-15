#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────
# music_preprocessing crontab 설정 예시
# ─────────────────────────────────────────────────────────
#
# 설치:
#   crontab -e  →  아래 라인 추가
#
# ─────────────────────────────────────────────────────────

# 환경 변수
MUSIC_PREPROCESSING_DIR="/opt/music_preprocessing"
UV_BIN="/usr/local/bin/uv"
LOG_DIR="/var/log/music_preprocessing"

# 매시 정각에 배치 처리 실행
# 0 * * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run python -m music_preprocessing batch --config config.json >> $LOG_DIR/batch_$(date +\%Y\%m\%d).log 2>&1

# 매일 새벽 3시에 실행 (권장 — catalog 다음)
# 0 3 * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run python -m music_preprocessing batch --config config.json >> $LOG_DIR/batch_$(date +\%Y\%m\%d).log 2>&1

# 10분마다 실행 (실시간에 가까운 처리가 필요한 경우)
# */10 * * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run python -m music_preprocessing batch --config config.json >> $LOG_DIR/batch_$(date +\%Y\%m\%d).log 2>&1

# ─────────────────────────────────────────────────────────
# 카탈로그 등록 (곡별 메타데이터·커버 → 아티스트·앨범·곡)
#
# batch 보다 **먼저** 돌린다. 곡이 있어야 batch 가 스트림을 붙인다.
# 0 2 * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run python -m music_preprocessing catalog --config config.json >> $LOG_DIR/catalog_$(date +\%Y\%m\%d).log 2>&1

# 커버만 다시 올리기 (그림을 갈아 끼웠을 때. 평소에는 catalog 만으로 충분하다)
# 30 2 * * 0 cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run python -m music_preprocessing covers --config config.json >> $LOG_DIR/covers_$(date +\%Y\%m\%d).log 2>&1

# ─────────────────────────────────────────────────────────
# 로그 로테이션 (30일 이상 된 로그 삭제)
# 0 3 * * * find $LOG_DIR -name "*.log" -mtime +30 -delete
# ─────────────────────────────────────────────────────────
