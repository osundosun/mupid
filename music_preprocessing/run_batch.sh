#!/usr/bin/env bash
# 배치 한 바퀴. JupyterHub 배치 서버에서 이 스크립트가 통째로 돈다.
#
# **cron 이 아니다.** 그 계정은 cron 스풀에 쓸 권한이 없다 — 실제로
# `crontab -`  가 `/var/spool/cron/: mkstemp: Permission denied` 로 실패했다.
# 그래서 "언제" 는 GitLab 파이프라인 스케줄이 정하고, "무엇을" 은 이 파일이
# 정한다. 순서와 명령을 저장소에 두어야 리뷰와 이력이 남는다.
#
#   infra/ci/deploy-jupyter.py --batch   가 이 스크립트를 서버에서 띄운다.
#
# 직접 돌릴 때 (서버에서):
#   cd ~/music_preprocessing && bash run_batch.sh
set -uo pipefail

directory=$(cd "$(dirname "$0")" && pwd)
cd "$directory"

UV=${UV:-$(command -v uv || echo /opt/tljh/user/bin/uv)}
[ -x "$UV" ] || { echo "uv 를 찾지 못했다: $UV" >&2; exit 1; }

# 서버에 ffmpeg 이 없다. conda 환경의 것을 bin/ 에 링크해 두고 PATH 앞에 붙인다.
# 이게 빠지면 청킹이 전부 실패한다.
export PATH="$directory/bin:$PATH"

# 색인 루트. 분석 단계(⑤⑥)가 `moments.parquet` 을 여기서 찾는다.
# **없으면 배치가 즉시 멈춘다** — `analysis` 는 "analysis_artifact_dir ·
# MUPID_AI_ARTIFACT_DIR · --data-dir 중 하나가 필요하다" 로 끝난다.
# 청킹 단계는 이 값을 쓰지 않아서, 여기서 주지 않으면 분석 단계만 죽는다.
# AI 서버(`mupid_ai_project_dev/run.sh`)도 같은 경로를 쓴다.
export MUPID_AI_ARTIFACT_DIR="${MUPID_AI_ARTIFACT_DIR:-$HOME/rec_mupid}"

log_dir=$(python3 -c "import json;print(json.load(open('config.json'))['log_dir'])" 2>/dev/null || echo ./logs)
mkdir -p "$log_dir"

run() {
  echo
  echo "=== $1 $(date '+%F %T') ==="
  # shellcheck disable=SC2086
  "$UV" run music-preprocessing "$@" -c config.json
  status=$?
  echo "=== $1 끝 (종료 $status) ==="
  return $status
}

failed=0

# 카탈로그가 먼저다 — 곡이 있어야 batch 가 스트림을 붙인다.
run catalog --yes --no-progress || failed=1
# 커버는 바뀐 그림만 올린다. 변경이 없으면 해시만 비교하고 끝난다.
run covers --yes --no-progress || failed=1
# 가사는 곡 번호가 있어야 붙일 수 있다 — catalog 다음.
run lyrics --yes --no-progress || failed=1
# 장르·분위기. catalog 가 남긴 music_id 에 붙이므로 catalog 다음이다.
# 이미 맞는 곡은 아무것도 쓰지 않는다 — 매일 돌아도 첫날 이후로는 읽기만 한다.
run classify --yes || failed=1
# 청킹. 해시 기반이라 이미 처리한 곡은 건너뛴다.
run batch || failed=1
# 구간 분석(① 분할 → ②③④ 특징 → ⑤ 색인 → ⑥ 등록·전환).
# **맨 뒤다.** GPU 를 오래 잡고, 실패해도 위 단계의 결과는 이미 서비스에 반영돼 있다.
#   · ①·②③④ 는 conda mupid-ai(3.10) 에서 돈다 — 이 3.12 에는 torch 가 없다.
#     그래서 `--segmenter-python` 을 반드시 준다(비면 배치가 ②③④ 진입에서 멈춘다).
#   · ⑤⑥ 은 이 3.12 — 색인 쓰기에 pandas 가 필요하다(`uv sync --extra analysis`).
#   · 대상은 `~/flow_music_data` 에서 직접 고른다. 요구 5 조회 API 를 부르지 않는다.
#   · `--limit` 을 주지 않는다 — 주면 남은 곡이 조용히 빠진다.
MUPID_AI_PY="${MUPID_AI_PY:-$HOME/.conda/envs/mupid-ai/bin/python}"
if [ -x "$MUPID_AI_PY" ]; then
  run analysis --yes --local --segmenter-python "$MUPID_AI_PY" || failed=1
else
  echo "3.10 인터프리터가 없다: $MUPID_AI_PY — 분석 단계를 건너뛴다" >&2
  failed=1
fi

# 앞 단계가 실패해도 나머지는 돌린다. catalog 가 실패했다고 이미 등록된 곡의
# 청킹까지 멈출 이유가 없고, 무엇이 실패했는지는 종료 코드로 알린다.
echo
echo "=== 로그 정리 (30일 지난 것) ==="
# classify 는 같은 자리에 실행 보고서(.json)도 남긴다. 같이 치운다.
find "$log_dir" \( -name '*.log' -o -name 'classify-*.json' \) -mtime +30 -delete 2>/dev/null || true

echo
if [ "$failed" -ne 0 ]; then
  echo "한 단계 이상 실패했다. 위 종료 코드를 본다."
  exit 1
fi
echo "배치 한 바퀴 정상 종료"
