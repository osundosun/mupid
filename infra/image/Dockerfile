FROM azul-zulu:21-jre
WORKDIR /app
ENV TZ=Asia/Seoul JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Seoul"
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system spring \
 && adduser --system --ingroup spring --no-create-home spring
COPY --chown=spring:spring app.jar app.jar
USER spring
EXPOSE 8800
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
 CMD curl -fsS "http://127.0.0.1:${MUPID_PORT:-8800}/api/actuator/health" | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
