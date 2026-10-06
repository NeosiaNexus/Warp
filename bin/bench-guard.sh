#!/usr/bin/env bash
# JMH smoke test and allocation guard: the same command locally and in CI.
#
#   bin/bench-guard.sh            # build the benchmarks, run them, compare with the baseline
#   bin/bench-guard.sh --update   # same, then rewrite protocol/src/jmh/alloc-baseline.json
#
# 1. Allocation guard: the hot-path benchmarks (relaying with compression passthrough, packet-id
#    peek) run with the GC profiler; bytes allocated per packet must stay within the baseline's
#    tolerance. Unlike timings, this is deterministic enough to gate pull requests on CI runners.
# 2. Smoke: every benchmark runs one short iteration, so a broken benchmark fails here and not on
#    the day someone needs it.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
results="$root/protocol/build/results/jmh"
baseline="$root/protocol/src/jmh/alloc-baseline.json"
guarded='ForwardingPathBenchmark\.relayClientbound$|PacketIdPeekBenchmark\.peek$'

# The benchmarks are compiled for Java 25: CI's JAVA_HOME_25_X64, else `java` if recent enough,
# else the JDK Gradle provisioned for the toolchain.
find_java() {
  if [ -n "${JAVA_HOME_25_X64:-}" ]; then echo "$JAVA_HOME_25_X64/bin/java"; return; fi
  if command -v java > /dev/null && [ "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')" -ge 25 ]; then
    echo java; return
  fi
  for home in "$HOME"/.gradle/jdks/*25*/; do
    if [ -x "$home/bin/java" ]; then echo "$home/bin/java"; return; fi
  done
  echo "error: no Java 25 found (set JAVA_HOME_25_X64)" >&2
  exit 2
}
java=$(find_java)

"$root/gradlew" --quiet -p "$root" :protocol:jmhJar
jar="$root/protocol/build/libs/protocol-$(cat "$root/version.txt")-jmh.jar"
mkdir -p "$results"

# Collapsible sections in the Actions log; plain headings elsewhere.
group() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::group::$1"; else echo "== $1"; fi; }
endgroup() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::endgroup::"; fi; }

group "Allocation guard (JMH, GC profiler)"
"$java" -jar "$jar" "$guarded" -p mode=PASSTHROUGH -f 1 -wi 3 -w 1s -i 3 -r 1s -prof gc \
  -rf json -rff "$results/alloc-guard.json"
endgroup

group "Smoke (every benchmark, one short iteration)"
"$java" -jar "$jar" -f 0 -wi 0 -i 1 -r 100ms -rf json -rff "$results/smoke.json"
endgroup

report=$(python3 "$root/bin/jmh-alloc-guard.py" "$results/alloc-guard.json" "$baseline" "$@") && status=0 || status=$?
echo "$report"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$report" >> "$GITHUB_STEP_SUMMARY"; fi
exit "$status"
