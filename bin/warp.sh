#!/usr/bin/env bash
# Warp Proxy — optimized launch script for Java 21+

set -euo pipefail

# Resolve script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WARP_JAR="${SCRIPT_DIR}/../proxy/build/libs/warp.jar"

# JVM Performance Flags
JVM_FLAGS=(
    # Memory
    -Xms512M
    -Xmx512M
    -XX:MaxDirectMemorySize=256M

    # GC: Generational ZGC for sub-ms pauses
    -XX:+UseZGC
    -XX:+ZGenerational
    -XX:+AlwaysPreTouch
    -XX:+DisableExplicitGC

    # Logging: async via LMAX Disruptor
    -Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
    -Dlog4j2.enableThreadlocals=true
    -Dlog4j2.enableDirectEncoders=true

    # Netty
    -Dio.netty.leakDetection.level=DISABLED
    -Dio.netty.allocator.maxOrder=12

    # JFR (optional, comment out in production)
    # -XX:+UnlockDiagnosticVMOptions
    # -XX:+DebugNonSafepoints
    # -XX:StartFlightRecording=duration=0s,filename=warp.jfr,settings=profile
)

exec java "${JVM_FLAGS[@]}" -jar "${WARP_JAR}" "$@"
