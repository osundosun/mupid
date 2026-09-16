# ai-server-verify 의 job 이미지. 러너 호스트에서 미리 구워 둔다.
#
# 왜: 이 job 은 20초 중 13.5초가 pip install 이었다(실측). 매번 같은 핀 고정
# 의존성을 다시 받아 설치하는 데 쓰는 시간이다. backend·frontend 는 /cache
# 볼륨으로 이미 해결했지만 pip 는 캐시가 있어도 site-packages 에 다시 풀어야
# 해서 이 방식이 맞다.
#
# 레지스트리에 올리지 않는다. job 컨테이너가 러너와 **같은 도커 데몬**에서
# 뜨므로 그 데몬에 있으면 그만이다. 배포 이미지를 SSH 로 직접 보내는 것과 같은
# 이유로 레지스트리를 두지 않는다.
#
# 굽는 법 (러너 호스트에서):
#   sh infra/runner/build-images.sh
FROM python:3.12-slim

# git 은 테스트가 실제로 부른다. tests/test_contract_offline.py 의
# test_env_files_are_ignored 가 `git check-ignore` 로 .env 가 무시되는지 본다.
# 그 테스트는 "git 체크아웃이 아님"(반환값 128)은 건너뛰지만 **git 이 아예
# 없는 경우**는 처리하지 않아 FileNotFoundError 로 죽었다. 실제로 그렇게
# 실패했다 (job 541874 · 545294).
RUN apt-get update \
 && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/*

# 이미지가 어떤 requirements 로 구워졌는지 남긴다. job 이 이것과 저장소의
# 파일을 대조해서, 의존성이 바뀌었는데 이미지를 다시 굽지 않았으면 **낡은
# 환경으로 조용히 통과하는 대신 크게 실패**한다.
COPY requirements.txt /tmp/requirements.txt
# 줄바꿈을 LF 로 맞춘다. 윈도우 체크아웃에서 구우면 CRLF 가 들어오는데 CI
# 체크아웃은 LF 라, 내용이 같아도 대조가 틀어진다. 실제로 처음 굽고 그렇게
# 실패했다 — 가드가 잡아 준 것이고, 가드는 인코딩이 아니라 내용을 봐야 한다.
RUN mkdir -p /opt/mupid \
 && tr -d '\r' < /tmp/requirements.txt > /opt/mupid/requirements.txt \
 && rm /tmp/requirements.txt \
 && python -m pip install --quiet --upgrade pip \
 && python -m pip install --quiet -r /opt/mupid/requirements.txt pytest httpx \
 && python -m pip check
