# syntax=docker/dockerfile:1

# ---------- build ----------
FROM azul-zulu:21-jdk AS build
WORKDIR /workspace

# 래퍼와 의존성 선언만 먼저 복사해서 의존성 레이어를 캐싱한다
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew \
 && ./gradlew --no-daemon --console=plain dependencies

COPY src src
RUN ./gradlew --no-daemon --console=plain bootJar -x test \
 && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar

# ---------- runtime ----------
FROM azul-zulu:21-jre AS runtime
WORKDIR /app

ENV TZ=Asia/Seoul \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul"

# azul-zulu 는 Debian 기반이라 Alpine(BusyBox) 문법(addgroup -S)이 통하지 않는다.
# 헬스체크에 쓸 curl 도 이미지에 없어서 함께 설치한다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system spring \
 && adduser --system --ingroup spring --no-create-home spring

COPY --from=build --chown=spring:spring /workspace/app.jar app.jar
USER spring

EXPOSE 8800

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS "http://localhost:${MUPID_PORT:-8800}/api/actuator/health" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
