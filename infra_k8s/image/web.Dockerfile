# 엣지 nginx 이미지에 프론트엔드 정적 파일을 구워 넣는다.
#
# RUN 이 하나도 없다. 빌드는 aarch64 제어 노드에서 돌고 노드는 x86_64 라
# --platform linux/amd64 로 만드는데, RUN 이 있으면 그 단계가 QEMU 에뮬레이션을
# 탄다. COPY 만 있으면 크로스 빌드는 파일 복사에 불과하다. 정적 파일은 애초에
# 아키텍처와 무관하므로 vite 빌드 자체는 제어 노드에서 네이티브로 돌린다.
#
# 설정은 이미지에 넣지 않는다. nginx.conf 는 edge-nginx ConfigMap 에서 온다.
# 그래야 설정 변경과 프론트 배포가 서로를 재배포하지 않는다.
#
# deployment.txt 는 CI 가 dist/client 안에 써 넣는다. 서버에서 만들던 것을
# (infra/deploy/receive-front.py) 빌드로 옮긴 것이고, 배포 검증
# (curl /deployment.txt 가 커밋 SHA 와 같은지)은 그대로 동작한다.
#
#   printf '%s\n' "$CI_COMMIT_SHA" > frontend/dist/client/deployment.txt
#   docker build --platform linux/amd64 \
#     -f infra_k8s/image/web.Dockerfile -t mupid-web:$CI_COMMIT_SHA frontend
FROM nginx:1.27-alpine
COPY dist/client /srv/web/current
