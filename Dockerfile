# syntax=docker/dockerfile:1.7
# Warp's container image: the shadow jar on a Java 25 runtime, as a non-root user, with Warp's
# management server on :9901 for health checks and the drain on SIGTERM.
#
#   docker build -t warp .                                        builds the jar inside (slow, no
#                                                                 natives for other platforms)
#   docker build --build-arg JAR_SOURCE=prebuilt -t warp .        takes proxy/build/libs/warp-*.jar
#                                                                 (what CI does, with every native)
#   docker build --build-arg BASE_IMAGE=eclipse-temurin:25-jre-alpine ...   the musl variant
#
# See docs/deploy/docker.md.

# Runtime images, pinned by digest (multi-architecture indexes): Ubuntu 24.04 (glibc) by default,
# Alpine (musl) as the -alpine variant. Warp bundles libdeflate for both C libraries.
ARG BASE_IMAGE=eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce
# Where the jar comes from: the `build` stage below, or `prebuilt` (the build context's own jar).
ARG JAR_SOURCE=build

# ---------------------------------------------------------------------------
# build: the shadow jar from the sources, for a local `docker build .`
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS build
# libdeflate for this platform is built from source (jni/native/build-libdeflate.sh).
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates curl git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain :proxy:shadowJar -Pwarp.natives.required=true \
    && mkdir -p /out && cp proxy/build/libs/warp-*.jar /out/warp.jar

# ---------------------------------------------------------------------------
# prebuilt: the one jar of the build context (CI builds it with every platform's native library)
# ---------------------------------------------------------------------------
FROM scratch AS prebuilt
COPY proxy/build/libs/warp-*.jar /out/warp.jar

FROM ${JAR_SOURCE} AS jar

# ---------------------------------------------------------------------------
# runtime
# ---------------------------------------------------------------------------
FROM ${BASE_IMAGE}

LABEL org.opencontainers.image.title="Warp" \
      org.opencontainers.image.description="High-performance Minecraft proxy: blind forwarding, compression passthrough, drain without disconnects" \
      org.opencontainers.image.url="https://github.com/NeosiaNexus/Warp" \
      org.opencontainers.image.source="https://github.com/NeosiaNexus/Warp" \
      org.opencontainers.image.documentation="https://github.com/NeosiaNexus/Warp/blob/main/docs/deploy/docker.md" \
      org.opencontainers.image.vendor="Warp Contributors" \
      org.opencontainers.image.licenses="AGPL-3.0-only"

# The base image's security fixes published since its last rebuild (Trivy fails the build on a
# fixed critical or high vulnerability in it), then one user, uid and gid 1000, on both Ubuntu
# (useradd, after removing the `ubuntu` user Noble ships at 1000) and Alpine (busybox adduser).
# /data holds what Warp writes: warp.conf on first start, forwarding.secret, logs/, anti-bot/,
# plugins/.
RUN set -eu; \
    if command -v apt-get > /dev/null; then \
      apt-get update && apt-get upgrade -y --no-install-recommends \
      && apt-get clean && rm -rf /var/lib/apt/lists/*; \
    else \
      apk upgrade --no-cache; \
    fi; \
    if command -v useradd > /dev/null; then \
      if id ubuntu > /dev/null 2>&1; then userdel --remove ubuntu; fi; \
      groupadd --gid 1000 warp && useradd --uid 1000 --gid warp --home-dir /data --no-create-home --shell /sbin/nologin warp; \
    else \
      addgroup -g 1000 warp && adduser -D -u 1000 -G warp -h /data -H -s /sbin/nologin warp; \
    fi; \
    mkdir -p /data /opt/warp/cache && chown warp:warp /data /opt/warp/cache

COPY --from=jar --chown=root:root --chmod=444 /out/warp.jar /opt/warp/warp.jar
COPY --chown=root:root --chmod=444 docker/warp.conf /opt/warp/warp.conf
COPY --chown=root:root --chmod=555 docker/entrypoint.sh /opt/warp/entrypoint.sh

# The JVM's flags for a container. The heap takes half of the container's memory limit (cgroup
# aware): Netty's direct buffers, the metaspace and the threads take the rest, so leave the limit
# at twice the heap you want. ZGC (generational since JDK 24) keeps pauses under a millisecond,
# which is what a proxy forwarding packets needs; it costs a little CPU and memory headroom over
# G1, hence the 50% rather than 75%. Add flags with JAVA_OPTS, replace them all with WARP_JVM_OPTS.
ENV WARP_HOME=/data \
    XDG_CACHE_HOME=/opt/warp/cache \
    WARP_JVM_OPTS="-XX:MaxRAMPercentage=50.0 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -XX:+DisableExplicitGC -XX:-UsePerfData --sun-misc-unsafe-memory-access=allow -Dio.netty.leakDetection.level=DISABLED -Dio.netty.allocator.maxOrder=12 -Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector -Dlog4j2.enableThreadlocals=true -Dlog4j2.enableDirectEncoders=true" \
    JAVA_OPTS="" \
    WARP_HEALTH_URL="http://127.0.0.1:9901/startupz"

WORKDIR /data
VOLUME ["/data"]
# 25577 the players, 9901 the management server (probes, drain, metrics, remote console).
EXPOSE 25577 9901
USER warp:warp

# Installs this platform's libdeflate from the jar into the image (XDG_CACHE_HOME above), so that
# the proxy starts with it on a read-only root filesystem and a noexec /tmp without extracting
# anything. A jar built without this platform's library (a local build) only warns here: the
# proxy then compresses with Java's zlib, and says so when it starts.
RUN mkdir -p /opt/warp/cache \
    && (java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx64m -cp /opt/warp/warp.jar dev.warp.jni.Natives \
        || echo "warning: no native libdeflate for this platform in the jar; the proxy will use Java's zlib")

# `java -jar warp.jar health` asks the management server (no curl in the image): a small, quick
# JVM. /startupz answers 200 once the proxy listens and its plugins are enabled, and keeps doing
# so during a drain; WARP_HEALTH_URL=http://127.0.0.1:9901/readyz turns unhealthy while draining.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD java -Xshare:auto -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx48m -XX:-UsePerfData \
        -jar /opt/warp/warp.jar health --url "$WARP_HEALTH_URL" --timeout 8

# SIGTERM starts the drain (drain.on-sigterm = true in docker/warp.conf): players are transferred to
# drain.transfer-to or kept until they leave, then the proxy exits. Give it the time:
# `docker stop -t 1830` (drain.timeout + propagation-delay + a margin), or stop_grace_period in
# Compose. A second SIGTERM stops at once.
STOPSIGNAL SIGTERM

ENTRYPOINT ["/opt/warp/entrypoint.sh"]
