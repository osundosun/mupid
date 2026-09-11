# Prepended to the runtime Dockerfile by package-image.sh.
# Only explicit build inputs enter this context; .env and CI SSH keys stay out.
FROM azul-zulu:21-jdk AS builder
WORKDIR /source
COPY . .
RUN bash gradlew --no-daemon --console=plain build -x test \
 && mkdir /release \
 && find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /release/app.jar \; \
 && test -s /release/app.jar
