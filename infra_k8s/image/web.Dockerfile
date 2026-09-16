# 엣지 nginx 이미지에 프론트엔드 정적 파일을 구워 넣는다.
#
# 이 단계에는 RUN 이 하나도 없다. 노드가 x86_64 라 --platform linux/amd64 로
# 만드는데, 여기에 RUN 이 있으면 그 단계가 QEMU 에뮬레이션을 탄다. COPY 만
# 있으면 크로스 빌드는 파일 복사에 불과하다. vite 빌드는 web.build.Dockerfile
# 의 빌더 단계에서 $BUILDPLATFORM 으로, 즉 러너 네이티브로 돈다.
#
# package-web.sh 가 web.build.Dockerfile 을 앞에 붙이고 아래 COPY 를
# `COPY --from=builder /source/dist/client ...` 로 바꾼다. 백엔드의
# infra/ci/package-image.sh 가 app.jar 에 하는 것과 같다. 그래서 CI 는
# 프론트엔드를 아티팩트로 주고받지 않고 이미지 빌드 안에서 소스부터 굽는다.
#
# 설정은 이미지에 넣지 않는다. nginx.conf 는 edge-nginx ConfigMap 에서 온다.
# 그래야 설정 변경과 프론트 배포가 서로를 재배포하지 않는다.
#
# 이 파일만 따로 쓰려면 dist/client 를 미리 만들어 두고 컨텍스트를 frontend 로
# 준다 (그때는 deployment.txt 도 직접 넣어야 한다):
#
#   MUPID_DEPLOY_TARGET=ec2 npm --prefix frontend run build
#   printf '%s\n' "$SHA" > frontend/dist/client/deployment.txt
#   docker build --platform linux/amd64 \
#     -f infra_k8s/image/web.Dockerfile -t mupid-web:$SHA frontend
FROM nginx:1.27-alpine
COPY dist/client /srv/web/current
