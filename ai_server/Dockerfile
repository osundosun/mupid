# syntax=docker/dockerfile:1
#
# AI 추천 서비스. **CPU 로만 돈다** — 랭킹 모델은 8.8만 파라미터(체크포인트 ~350KB)다.
#
# **torch 를 넣지 않는다.** 서빙에서 torch 가 하던 일은 사용자 벡터 한 번(`enc_user`)뿐이고
# 그것을 NumPy 로 옮겼다(app/stage1_np.py — torch 판과 오차 1e-5 미만·순위 완전 일치).
# CPU 빌드라도 수백 MB 라 이미지의 대부분을 차지하고 있었다.
#
# 대신 산출물에 `stage1_weights.npz` 가 **있어야 한다.** 없으면 학습 점수가 빠진 채 규칙
# 경로로 동작한다 — 그 상태는 /health 의 model_backend·model_error 로 드러난다.
#   생성: python -m ai_server.tools.export_stage1_npz
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
 && pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt

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
      -r /wheels/requirements.txt

COPY --chown=mupid:mupid ai_server/app /app/ai_server/app
# 배치 진입점(run_labeler 등)도 넣는다. 이미지 안에서 cron 이 부를 수 있어야 한다.
COPY --chown=mupid:mupid ai_server/tools /app/ai_server/tools
# 모델 자리(S15P21A204-454). CI 가 models.lock 대로 서빙 가중치와 model.json 을 채운다
# (infra_k8s/ci/package-ai.sh). 채택된 모델이 없으면 비어 있고 판의 가중치를 읽는다 —
# 로컬 빌드도 저장소의 빈 자리(.gitkeep)가 있어 깨지지 않는다. app/model_bundle.py 가 읽는다.
COPY --chown=mupid:mupid ai_server/models /app/ai_server/models
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
