# 런타임 스테이지. **RUN 이 없다.**
#
# 빌더는 $BUILDPLATFORM 이라 네이티브로 돌지만 이 스테이지는 타깃(linux/amd64)
# 이므로, aarch64 러너에서는 여기의 모든 RUN 이 QEMU 에뮬레이션을 탄다. 예전에
# 있던 `apt-get install curl` 한 줄이 라즈베리파이에서 **200초**를 썼다(실측).
# COPY 만 남기면 그 시간이 사라진다.
#
# curl 은 아래 HEALTHCHECK 하나 때문에 있었다. k8s 는 HEALTHCHECK 를 보지 않고
# api.yaml 의 httpGet probe 를 쓰므로, 컨테이너 안에 curl 이 필요 없다. Compose
# 배포는 은퇴했으므로 이 이미지를 도커로 직접 돌리며 헬스체크에 기대는 곳은 없다.
#
# 사용자도 만들지 않는다. addgroup/adduser 역시 RUN 이고, `spring` 이라는 이름을
# 쓰는 곳이 없어 숫자 UID 로 충분하다. 루트로 돌지 않는다는 성질은 그대로다.
FROM azul-zulu:21-jre
WORKDIR /app
ENV TZ=Asia/Seoul JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul"
COPY --chown=1000:1000 app.jar app.jar
USER 1000:1000
EXPOSE 8800
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
