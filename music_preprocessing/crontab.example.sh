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
LOG_DIR="/var/log/music_preprocessing"   # config.json 의 log_dir 과 맞춘다

# 리다이렉션이 없는 것은 로그가 이미 파일로 가기 때문이다 (설정의 log_dir).
# 크론 메일로 오는 것은 화면 요약과 실패 표뿐이다.
# catalog·covers 는 확인을 묻으므로 크론에서는 --yes 가 필요하다.

# 매시 정각에 배치 처리 실행
# 0 * * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run music-preprocessing batch -c config.json

# 매일 새벽 3시에 실행 (권장 — catalog 다음)
# 0 3 * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run music-preprocessing batch -c config.json

# 10분마다 실행 (실시간에 가까운 처리가 필요한 경우)
# */10 * * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run music-preprocessing batch -c config.json

# ─────────────────────────────────────────────────────────
# 카탈로그 등록 (곡별 메타데이터·커버 → 아티스트·앨범·곡)
#
# batch 보다 **먼저** 돌린다. 곡이 있어야 batch 가 스트림을 붙인다.
# 0 2 * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run music-preprocessing catalog -c config.json --yes --no-progress

# 가사 등록 (metadata 의 lyrics_sync → lyrics 테이블)
#
# catalog 다음. 곡 번호가 있어야 가사를 붙일 수 있다.
# 곡마다 한 번씩 부른다 (502곡). 오래 걸리지 않는다.
# 30 2 * * * cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run music-preprocessing lyrics -c config.json --yes --no-progress

# 커버만 다시 올리기 (그림을 갈아 끼웠을 때. 평소에는 catalog 만으로 충분하다)
# 0 4 * * 0 cd $MUSIC_PREPROCESSING_DIR && $UV_BIN run music-preprocessing covers -c config.json --yes --no-progress

# ─────────────────────────────────────────────────────────
# 로그 로테이션 (30일 이상 된 로그 삭제)
# 0 3 * * * find $LOG_DIR -name "*.log" -mtime +30 -delete
# ─────────────────────────────────────────────────────────
