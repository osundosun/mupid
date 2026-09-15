# syntax=docker/dockerfile:1
#
# AI 추천 서비스. **CPU 로만 돈다** — 랭킹 모델은 8.8만 파라미터(체크포인트 ~350KB)다.
#
# torch 를 CPU 인덱스에서 받는 것이 이 파일의 핵심이다. 기본 PyPI 판은 CUDA 라이브러리를
# 함께 끌고 와 이미지가 수 GB 가 된다. 이 서비스는 GPU 를 쓰지 않으므로 전부 낭비다.
#
# 모델·색인은 이미지에 넣지 않는다. 용량도 용량이지만 코드와 수명이 다르다 — 색인만
# 교체하는 일이 코드 배포보다 잦다. 실행할 때 MUPID_AI_ARTIFACT_DIR 로 붙인다.
#
# 빌드 컨텍스트는 **저장소 루트**다 (ai_server/ 가 아니다):
#   docker build -f ai_server/Dockerfile -t mupid-ai:<tag> .

# ---------- build ----------
# 휠을 미리 받아 두는 단계. 런타임 이미지에 빌드 도구를 남기지 않는다.
FROM python:3.12-slim AS build
WORKDIR /wheels

COPY ai_server/requirements.txt ./requirements.txt
RUN python -m pip install --no-cache-dir --upgrade pip \
 && pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt \
 && pip wheel --no-cache-dir --wheel-dir /wheels \
      --index-url https://download.pytorch.org/whl/cpu torch==2.6.0

# ---------- runtime ----------
FROM python:3.12-slim AS runtime
WORKDIR /app

# 로그에 한글이 들어간다. 로케일이 C 면 print 가 UnicodeEncodeError 를 내고, 그 호출이
# 기동 경로에 있으면 **서비스가 뜨지 못한다.** 코드 쪽도 막아 두었지만 여기서 먼저 막는다.
ENV TZ=Asia/Seoul \
    LANG=C.UTF-8 \
    PYTHONIOENCODING=utf-8 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    MUPID_AI_PORT=8100 \
    MUPID_AI_ARTIFACT_DIR=/artifacts

# 헬스체크에 쓸 curl 만 넣는다. slim 이미지에는 없다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system mupid \
 && adduser --system --ingroup mupid --no-create-home mupid

RUN --mount=type=bind,from=build,source=/wheels,target=/wheels \
    pip install --no-cache-dir --no-index --find-links=/wheels \
      -r /wheels/requirements.txt torch==2.6.0

COPY --chown=mupid:mupid ai_server/app /app/ai_server/app
# 배치 진입점(run_labeler 등)도 넣는다. 이미지 안에서 cron 이 부를 수 있어야 한다.
COPY --chown=mupid:mupid ai_server/tools /app/ai_server/tools
# 저장소에는 ai_server/__init__.py 가 없다(네임스페이스 패키지로 동작한다). 이미지에서는
# `python -m ai_server.tools.*` 가 확실히 잡히도록 아래 RUN 에서 만든다.
# 글로브로 COPY 하면 맞는 파일이 없을 때 **빌드가 실패한다.**
RUN touch /app/ai_server/__init__.py \
 && chown mupid:mupid /app/ai_server/__init__.py \
 && mkdir -p /artifacts \
 && chown mupid:mupid /artifacts

USER mupid
EXPOSE 8100

# /health 는 프로세스 생존만 본다. 색인이 실패해도 200 이므로 컨테이너를 죽이지 않는다 —
# 원인을 /health 로 읽을 수 있어야 하고, 트래픽 차단은 오케스트레이터가 /internal/ready
# 로 판단한다. 여기서 ready 를 쓰면 매핑표가 없는 동안 컨테이너가 재시작을 반복한다.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD curl -fsS "http://localhost:${MUPID_AI_PORT}/health" >/dev/null || exit 1

# 워커는 하나다. 카탈로그를 프로세스마다 통째로 올리므로 늘리면 메모리가 배로 든다.
ENTRYPOINT ["sh", "-c", "exec uvicorn ai_server.app.main:app --host 0.0.0.0 --port ${MUPID_AI_PORT} --workers 1"]
