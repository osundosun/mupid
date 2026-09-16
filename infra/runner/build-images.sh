#!/bin/sh
# CI job 이미지를 러너 호스트의 도커 데몬에 굽는다. 저장소 루트에서 실행한다.
#
#   sh infra/runner/build-images.sh
#
# 레지스트리에 올리지 않는다. job 컨테이너가 러너와 같은 데몬에서 뜨므로
# 로컬에 있으면 된다. 대신 러너 설정에 allowed_pull_policies 가 있어야 하고
# job 이 pull_policy: if-not-present 로 요청해야 한다 — 기본값 always 로는
# 도커 허브에서 찾다가 실패한다. infra/runner/configure.py 가 그 설정을 넣는다.
#
# 의존성 파일을 고쳤으면 여기를 다시 돌린다. 안 돌리면 job 이 이미지 안의
# requirements 와 저장소의 것을 대조해서 실패한다 — 낡은 환경으로 통과하는
# 것보다 낫다.
set -eu
cd "$(dirname "$0")/../.."

docker build \
  -f infra/runner/images/ai.Dockerfile \
  -t mupid-ci-ai:1 \
  ai_server

echo
docker image ls mupid-ci-ai --format '  {{.Repository}}:{{.Tag}}  {{.Size}}  {{.CreatedSince}}'
