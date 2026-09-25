#!/bin/sh
# 추천 서버 이미지를 굽는다.
#
#   sh infra_k8s/ci/package-ai.sh
#
# infra/ci/package-image.sh 와 같은 모양이다 — 명시한 입력만 담은 컨텍스트,
# rootless buildkit, .release/ 아래 gzip 한 docker 아카이브와 체크섬.
#
# platform=linux/amd64 를 고정한다. 노드가 x86_64 인데 러너는 arm64 라, 고정하지
# 않으면 들여오기는 되는데 실행이 안 되는 이미지가 나온다.
#
# **torch 가 없다.** 서빙에서 torch 가 하던 일은 사용자 벡터 한 번뿐이고 NumPy 로
# 옮겼다(ai_server/Dockerfile 의 설명). 그래서 크로스 빌드가 파일 복사에 가깝고
# QEMU 를 타지 않는다 — 순수 파이썬 휠만 받는다.
set -eu
printf '%s\n' "${CI_COMMIT_SHA:?}" | grep -Eq '^[0-9a-f]{40}$'

directory=$(mktemp -d /tmp/mupid-ai.XXXXXX)
trap 'rm -rf "$directory"' EXIT
mkdir -p "$directory/context/ai_server"

# Dockerfile 이 저장소 루트를 컨텍스트로 보고 ai_server/ 만 읽는다. 그 모양을
# 그대로 만들되 필요한 것만 담는다 — .env 나 이전 산출물이 들어가지 않게.
for input in app tools requirements.txt; do
  cp -R "ai_server/$input" "$directory/context/ai_server/$input"
done
# 모델 가중치는 이미지에 함께 굽는다. 352KB 이고 코드와 수명이 같다 —
# 모델 버전이 곧 이미지 태그가 되어 되돌리기가 rollout undo 한 번이다.
# 색인·임베딩은 여기 넣지 않는다 (Pinecone·ClickHouse 로 간다).
if [ -f ai_server/artifacts/data/proc/mupid/stage1_weights.npz ]; then
  mkdir -p "$directory/context/ai_server/artifacts/data/proc/mupid"
  cp ai_server/artifacts/data/proc/mupid/stage1_weights.npz \
     "$directory/context/ai_server/artifacts/data/proc/mupid/"
  echo '모델 가중치를 이미지에 담는다'
else
  # 없어도 뜬다. 학습 점수가 빠지고 규칙 경로로 내려가며, 그 상태는 /health 의
  # model_backend 로 드러난다. 조용히 나빠지는 것이라 로그에 남긴다.
  echo '경고: stage1_weights.npz 가 없다 — 학습 점수 없이 규칙 경로로 돈다' >&2
fi

# 서빙 가중치(모델 묶음)를 이미지의 모델 자리에 굽는다 (S15P21A204-454, 설계 §6.3).
# models.lock 에 채택된 버전이 있으면 서빙 파일만 Object Storage 에서 받아 sha256 을 대조하고
# model.json 과 함께 둔다. 없으면 빈 자리만 만든다 — ai_server 가 판의 가중치로 돈다.
# 대조가 틀리면 여기서 멈춘다(set -e). 약속한 모델이 아닌 이미지를 굽지 않는다.
mkdir -p "$directory/context/ai_server/models/stage1"
python3 infra/ci/model_publish.py bake --out "$directory/context/ai_server/models/stage1"

tr -d '\r' < ai_server/Dockerfile > "$directory/context/Dockerfile"
chown -R 1000:1000 "$directory"

export MUPID_IMAGE_NAME="mupid-ai:$CI_COMMIT_SHA"
export MUPID_BUILD_DIRECTORY="$directory"
su user -c 'buildctl-daemonless.sh build --frontend dockerfile.v0 --local context="$MUPID_BUILD_DIRECTORY/context" --local dockerfile="$MUPID_BUILD_DIRECTORY/context" --opt platform=linux/amd64 --output type=docker,name="$MUPID_IMAGE_NAME",dest="$MUPID_BUILD_DIRECTORY/image.tar"'

mkdir -p .release/ai
gzip -n -c "$directory/image.tar" > .release/ai/image.tar.gz
size=$(wc -c < .release/ai/image.tar.gz)
[ "$size" -le 1073741824 ] || { echo 'Image exceeds server limit of 1 GiB' >&2; exit 65; }
(cd .release/ai && sha256sum image.tar.gz > image.tar.gz.sha256)
printf 'Built %s (%s bytes)\n' "$MUPID_IMAGE_NAME" "$size"
