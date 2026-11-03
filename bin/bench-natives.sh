#!/usr/bin/env bash
# Measures Warp's native libdeflate against Velocity's natives and the JDK, as reported in the pull
# request that introduced it, so that anyone can rerun the numbers on their own machine.
#
#   bin/bench-natives.sh           # full methodology (3 forks, 5x2 s warm-up, 10x2 s): about 3 hours
#   bin/bench-natives.sh --quick   # 1 fork, 3x1 s warm-up, 5x1 s: about 30 minutes, indicative
#
# Runs, for JDK vs VELOCITY_NATIVE vs WARP_NATIVE:
#   - CodecCostBenchmark: deflate, inflate, encrypt and decrypt of CHUNK, MIXED and SMALL packets,
#     with JMH's GC profiler for the bytes allocated per packet;
#   - InterleavedCodecProbe: the same operations with the implementations interleaved round by
#     round, packets on one connection, then spread over 1000 (one context each for Velocity and
#     java.util.zip). On a shared or virtualised machine, where sequential JMH trials drift, these
#     ratios are the ones to trust;
#   - NativeMemoryProbe: native memory for 1000 players (Linux with glibc).
# Results go to protocol/build/results/natives/.
set -euo pipefail

case "${1:-}" in
  '') quick=() ;;
  --quick) quick=(-f 1 -wi 3 -w 1s -i 5 -r 1s) ;;
  *)
    echo "usage: bin/bench-natives.sh [--quick]" >&2
    exit 2
    ;;
esac

cd "$(dirname "${BASH_SOURCE[0]}")/.."
results=protocol/build/results/natives
mkdir -p "$results"

./gradlew --quiet :protocol:jmhJar
java=$(./gradlew --quiet :protocol:jmhJava)
jar=protocol/build/libs/protocol-$(< version.txt)-jmh.jar

{
  echo "date: $(date -u +%FT%TZ)"
  echo "commit: $(git rev-parse --short HEAD)"
  echo "cpu: $(grep -m1 'model name' /proc/cpuinfo 2> /dev/null | cut -d: -f2- | xargs || uname -m)"
  echo "cores: $(nproc 2> /dev/null || sysctl -n hw.ncpu)"
  echo "kernel: $(uname -sr)"
  echo "load average before: $(cut -d' ' -f1-3 /proc/loadavg 2> /dev/null || uptime)"
  echo "java: $("$java" -version 2>&1 | head -2 | tail -1)"
  echo "libdeflate: $(grep LIBDEFLATE_VERSION jni/native/libdeflate.env | cut -d= -f2) (Warp)"
  echo "velocity-native: $(grep '^velocity-native' gradle/libs.versions.toml | cut -d'"' -f2)"
} | tee "$results/environment.txt"

"$java" -jar "$jar" 'CodecCostBenchmark' -foe true "${quick[@]}" -prof gc \
  -rf json -rff "$results/codec.json" | tee "$results/codec.txt"

seconds=40
if [ ${#quick[@]} -gt 0 ]; then seconds=15; fi
probe=("$java" -Xms2g -Xmx2g -XX:+AlwaysPreTouch --enable-native-access=ALL-UNNAMED
  --sun-misc-unsafe-memory-access=allow -Dio.netty.leakDetection.level=disabled -cp "$jar")
"${probe[@]}" dev.warp.protocol.compress.InterleavedCodecProbe "$seconds" 1 | grep -v SLF4J |
  tee "$results/interleaved-1.md"
"${probe[@]}" dev.warp.protocol.compress.InterleavedCodecProbe "$seconds" 1000 | grep -v SLF4J |
  tee "$results/interleaved-1000.md"

if [ "$(uname -s)" = Linux ]; then
  "$java" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Xmx2g \
    -cp "$jar" dev.warp.protocol.compress.NativeMemoryProbe 1000 | grep -v SLF4J |
    tee "$results/memory.md"
fi
echo "load average after: $(cut -d' ' -f1-3 /proc/loadavg 2> /dev/null || uptime)" |
  tee -a "$results/environment.txt"
