#!/usr/bin/env bash
# Benchmarks of Warp's hot path, run the same way locally and in CI.
#
#   bin/bench-guard.sh            # allocation guard, then a smoke test of every benchmark (CI)
#   bin/bench-guard.sh --update   # measure allocation and rewrite the baseline
#   bin/bench-guard.sh --timings  # time the guarded benchmarks (the trend tracked on main)
#
# The guarded benchmarks are the hot path: relaying clientbound traffic with compression
# passthrough, Warp's default, and peeking at the packet id of a compressed frame.
#
# Allocation guard: JMH's GC profiler counts the bytes allocated per packet, with escape analysis
# off so that the count covers every allocation the code makes, not those the JIT happened to keep
# in this run. Counted this way it is the same on every run, so it can gate pull requests on shared
# runners where timings cannot. It must stay within the tolerance of
# protocol/src/jmh/alloc-baseline.json; bench-guard.py reports the comparison.
#
# Smoke test: every benchmark runs one short iteration in a fork of its own, with its JVM settings,
# so that a broken benchmark fails here and not on the day someone needs it.
#
# Timings: each benchmark's own forks, warm-up and iterations, as for published results.
set -euo pipefail

case "${1:-}" in
  '') mode=check ;;
  --update) mode=update ;;
  --timings) mode=timings ;;
  *)
    echo "usage: bin/bench-guard.sh [--update | --timings]" >&2
    exit 2
    ;;
esac

cd "$(dirname "${BASH_SOURCE[0]}")/.."
results=protocol/build/results/jmh
baseline=protocol/src/jmh/alloc-baseline.json
guarded=('ForwardingPathBenchmark\.relayClientbound$|PacketIdPeekBenchmark\.peek$' -p mode=PASSTHROUGH)

# Collapsible sections in the Actions log, headings elsewhere.
group() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::group::$1"; else echo "== $1"; fi; }
endgroup() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::endgroup::"; fi; }

# Runs a report command, prints its Markdown and, in Actions, adds it to the job summary.
report() {
  local markdown status=0
  markdown=$("$@") || status=$?
  echo "$markdown"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$markdown" >> "$GITHUB_STEP_SUMMARY"; fi
  return "$status"
}

group "Build the benchmarks"
./gradlew --quiet :protocol:jmhJar
endgroup
# The java of the toolchain that compiled the benchmarks, wherever Gradle found or installed it.
java=$(./gradlew --quiet :protocol:jmhJava)
jar=protocol/build/libs/protocol-$(< version.txt)-jmh.jar
mkdir -p "$results"
# -foe: a benchmark that throws fails the run instead of being skipped.
jmh() { "$java" -jar "$jar" -foe true "$@"; }

if [ "$mode" = timings ]; then
  group "Timings: each benchmark's own forks, warm-up and iterations"
  jmh "${guarded[@]}" -rf json -rff "$results/timings.json"
  endgroup
  report python3 bin/bench-guard.py timings "$results/timings.json" "$results/timings-chart.json"
  exit
fi

group "Allocation per packet: GC profiler, escape analysis off"
# Without escape analysis the count does not depend on what the JIT compiled, so one fork and one
# warm-up iteration measure it exactly; the guard keeps the lowest of three iterations.
jmh "${guarded[@]}" -f 1 -wi 1 -w 1s -i 3 -r 1s -prof gc -jvmArgsPrepend -XX:-DoEscapeAnalysis \
  -rf json -rff "$results/allocation.json"
endgroup

if [ "$mode" = update ]; then
  python3 bin/bench-guard.py update "$results/allocation.json" "$baseline"
  exit
fi

status=0
report python3 bin/bench-guard.py check "$results/allocation.json" "$baseline" || status=$?

group "Smoke test: every benchmark, one short iteration"
jmh -f 1 -wi 0 -i 1 -r 100ms -rf json -rff "$results/smoke.json"
endgroup
exit "$status"
