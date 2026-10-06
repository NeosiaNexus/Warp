#!/usr/bin/env bash
# Warp Proxy: development launch script (leak detection, debug)

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

JVM_FLAGS=(
    -Xms256M
    -Xmx256M
    -XX:MaxDirectMemorySize=128M

    # GC: G1 for dev (faster startup)
    -XX:+UseG1GC

    # Logging: async
    -Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector

    # Netty: keep sun.misc.Unsafe (disabled by default on Java 25+), paranoid leak detection
    --sun-misc-unsafe-memory-access=allow
    -Dio.netty.leakDetection.level=PARANOID

    # Debug
    -XX:+UnlockDiagnosticVMOptions
    -XX:+DebugNonSafepoints
    -ea
)

exec java "${JVM_FLAGS[@]}" -jar "${WARP_JAR}" "$@"
