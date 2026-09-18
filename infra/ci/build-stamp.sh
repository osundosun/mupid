#!/bin/sh
# 빌드 스탬프를 하나 찍어 낸다 (epoch **밀리초**). 이것이 곧 버전이다.
#
#   stamp=$(sh infra/ci/build-stamp.sh)
#
# semver 를 쓰지 않는다(`app/tauri.conf.json` 의 version 은 0.1.0 그대로 둔다).
# 웹 번들·APK·매니페스트 셋이 **같은 값**을 들어야 하고, 그래서 계산이
# 한 곳에만 있어야 한다 — 두 곳에 복사해 두면 언젠가 어긋나고, 그 증상은
# "앱이 자기 번들을 계속 다시 받는다" 또는 "업데이트 안내가 안 뜬다" 뿐이다.
#
# ## 왜 커밋 시각인가
#
# `date +%s%3N` 으로 하면 **job 마다, 재시도마다 다른 값**이 나온다. 그러면
#
#   - 웹 아카이브와 APK 가 다른 job 에서 구워지므로 짝을 이룰 수 없고,
#   - 같은 파이프라인을 재시도할 때마다 새 버전이 생겨 사용자 전원에게
#     재설치 안내가 다시 뜬다.
#
# 커밋 시각은 파이프라인 안 어디서 물어도 같은 값이라 두 문제가 함께 사라진다.
# 대신 **같은 커밋을 두 번 배포하면 스탬프가 같다** — 그건 의도한 것이다.
# 앱은 "내 것보다 크지 않으면" 안내하지 않으므로 재촉이 생기지 않는다.
#
# 순서대로 본다.
#
#   1. `MUPID_BUILD_STAMP` — 이미 정해져 있으면 그것을 쓴다(테스트·수동 배포).
#   2. `CI_COMMIT_TIMESTAMP` — GitLab 이 주는 커밋 시각(ISO8601 UTC).
#   3. `git show -s --format=%ct` — CI 밖에서 사람이 돌릴 때.
#   4. 현재 시각. 마지막 수단이고, 이때는 웹과 APK 의 짝이 어긋날 수 있다.
set -eu

# 1. 이미 정해진 값.
if [ -n "${MUPID_BUILD_STAMP:-}" ]; then
  stamp=$MUPID_BUILD_STAMP
  # 초로 들어온 값을 밀리초로 올린다. 단위가 섞이면 앱의 비교식이
  # (초 vs 밀리초) 항상 한쪽으로 치우친다.
  case "$stamp" in
    ''|*[!0-9]*) echo "MUPID_BUILD_STAMP 가 숫자가 아니다: $stamp" >&2; exit 64 ;;
  esac
  [ "$stamp" -lt 100000000000 ] && stamp=$((stamp * 1000))
  printf '%s\n' "$stamp"
  exit 0
fi

# 2. GitLab 이 주는 커밋 시각. ISO8601(UTC)이 표준이지만, 숫자로 들어오면
#    epoch 초로 읽는다 — 두 모양을 다 받는 편이 조용히 현재 시각으로 떨어지는
#    것보다 낫다(그러면 웹과 APK 의 스탬프가 갈린다).
if [ -n "${CI_COMMIT_TIMESTAMP:-}" ]; then
  case "$CI_COMMIT_TIMESTAMP" in
    *[!0-9]*) ;;
    *) printf '%s\n' "$((CI_COMMIT_TIMESTAMP * 1000))"; exit 0 ;;
  esac
  if seconds=$(date -u -d "$CI_COMMIT_TIMESTAMP" +%s 2>/dev/null); then
    printf '%s\n' "$((seconds * 1000))"
    exit 0
  fi
  echo "경고: CI_COMMIT_TIMESTAMP 를 읽지 못했다: $CI_COMMIT_TIMESTAMP" >&2
fi

# 3. 저장소에서 직접.
if seconds=$(git show -s --format=%ct "${CI_COMMIT_SHA:-HEAD}" 2>/dev/null) \
   && [ -n "$seconds" ]; then
  printf '%s\n' "$((seconds * 1000))"
  exit 0
fi

# 4. 마지막 수단. 웹과 APK 가 다른 job 에서 돌면 값이 갈린다 — 그 사실을 알린다.
echo "경고: 커밋 시각을 읽지 못해 현재 시각을 쓴다. 웹 아카이브와 APK 의 스탬프가 어긋날 수 있다" >&2
printf '%s\n' "$(($(date +%s) * 1000))"
