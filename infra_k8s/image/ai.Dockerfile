# ai_server (FastAPI recommendation service). CPU only — the ranking model is 349KB
# and nothing in this service touches a GPU.
#
# torch MUST come from the CPU index. The default PyPI wheel drags in CUDA
# libraries and the image grows by several GB for no benefit.
#
# Build from the repository root so ai_server/ is in context:
#   docker build -f infra_k8s/image/ai.Dockerfile -t mupid-ai:<sha> .
FROM python:3.12-slim AS build
WORKDIR /build
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
COPY ai_server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
 && pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.6.0

FROM python:3.12-slim
ENV TZ=Asia/Seoul \
    PATH=/opt/venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system mupid \
 && adduser --system --ingroup mupid --no-create-home mupid
COPY --from=build /opt/venv /opt/venv
WORKDIR /app
COPY --chown=mupid:mupid ai_server /app/ai_server
USER mupid
EXPOSE 8100
# /health only proves the process is alive. Readiness (indexes loaded, ID mapping
# consistent) is /internal/ready and is checked by the Kubernetes probe, not here.
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
 CMD curl -fsS "http://127.0.0.1:${MUPID_AI_PORT:-8100}/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec uvicorn ai_server.app.main:app --host 0.0.0.0 --port ${MUPID_AI_PORT:-8100}"]
