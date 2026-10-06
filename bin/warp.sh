#!/usr/bin/env bash
# Warp Proxy: optimized launch script for Java 25+

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The shadow jar is versioned (proxy/build/libs/warp-<version>.jar): take the most recently built
# one, unless WARP_JAR points to another jar (a downloaded release, for example).
if [[ -z "${WARP_JAR:-}" ]]; then
    shopt -s nullglob
    for candidate in "${SCRIPT_DIR}"/../proxy/build/libs/warp-*.jar; do
        if [[ -z "${WARP_JAR:-}" || "${candidate}" -nt "${WARP_JAR}" ]]; then
            WARP_JAR="${candidate}"
        fi
    done
    shopt -u nullglob
fi
if [[ -z "${WARP_JAR:-}" ]]; then
    echo "error: no proxy/build/libs/warp-*.jar. Build it with ./gradlew build, or set WARP_JAR." >&2
    exit 1
elif [[ ! -f "${WARP_JAR}" ]]; then
    echo "error: WARP_JAR is not a file: ${WARP_JAR}" >&2
    exit 1
fi

# JVM Performance Flags
JVM_FLAGS=(
    # Memory
    -Xms512M
    -Xmx512M
    -XX:MaxDirectMemorySize=256M

    # GC: ZGC (generational-only since JDK 24) for sub-ms pauses
    -XX:+UseZGC
    -XX:+AlwaysPreTouch
    -XX:+DisableExplicitGC

    # Logging: async via LMAX Disruptor
    -Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
    -Dlog4j2.enableThreadlocals=true
    -Dlog4j2.enableDirectEncoders=true

    # Netty: since Java 25, Netty stops using sun.misc.Unsafe unless memory access is allowed
    --sun-misc-unsafe-memory-access=allow
    -Dio.netty.leakDetection.level=DISABLED
    -Dio.netty.allocator.maxOrder=12

    # JFR (optional, comment out in production)
    # -XX:+UnlockDiagnosticVMOptions
    # -XX:+DebugNonSafepoints
    # -XX:StartFlightRecording=duration=0s,filename=warp.jfr,settings=profile
)

exec java "${JVM_FLAGS[@]}" -jar "${WARP_JAR}" "$@"
