# Prepended to the runtime Dockerfile by package-image.sh.
# Only explicit build inputs enter this context; .env and CI SSH keys stay out.
#
# $BUILDPLATFORM pins this stage to the machine doing the building, not to the
# machine the image will run on. The output is a jar, which is the same bytes
# either way, so the compile runs natively even when the runtime stage below is
# a different architecture — an aarch64 runner producing a linux/amd64 image
# compiles at full speed instead of under emulation. On an amd64 runner the two
# platforms are equal and this changes nothing.
FROM --platform=$BUILDPLATFORM azul-zulu:21-jdk AS builder
WORKDIR /source
COPY . .
RUN bash gradlew --no-daemon --console=plain build -x test \
 && mkdir /release \
 && find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /release/app.jar \; \
 && test -s /release/app.jar
