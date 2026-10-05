#!/usr/bin/env bash
# Warp Proxy — development launch script (leak detection, debug)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WARP_JAR="${SCRIPT_DIR}/../proxy/build/libs/warp.jar"

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
