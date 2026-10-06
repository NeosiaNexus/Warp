#!/usr/bin/env python3
"""Compares JMH allocation (gc.alloc.rate.norm, bytes per packet) against a committed baseline.

    jmh-alloc-guard.py RESULTS.json BASELINE.json          # check; exit 1 on regression
    jmh-alloc-guard.py RESULTS.json BASELINE.json --update # rewrite the baseline

Allocation per operation is deterministic once the JIT has settled, unlike timings, so it can gate
pull requests on shared CI runners. The minimum over measured iterations is used: transient effects
(deoptimisation, class loading) only ever add allocation.

Prints a Markdown report on stdout and GitHub annotations on stderr when running in Actions.
"""

import json
import os
import sys

METRIC = "gc.alloc.rate.norm"


def key(result):
    name = ".".join(result["benchmark"].split(".")[-2:])
    params = ",".join(f"{k}={v}" for k, v in sorted((result.get("params") or {}).items()))
    return f"{name}[{params}]" if params else name


def measured(results):
    out = {}
    for result in results:
        metric = result["secondaryMetrics"].get(METRIC)
        if metric is None:
            sys.exit(f"{key(result)}: no {METRIC} (run JMH with -prof gc)")
        out[key(result)] = min(v for fork in metric["rawData"] for v in fork)
    return out


def limit(baseline, tolerance):
    return max(tolerance["absolute"], baseline * tolerance["relative"])


def annotate(level, title, message):
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::{level} title={title}::{message}", file=sys.stderr)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        sys.exit(__doc__)
    results_path, baseline_path = args
    with open(results_path) as f:
        current = measured(json.load(f))
    with open(baseline_path) as f:
        baseline = json.load(f)

    if "--update" in sys.argv:
        baseline["benchmarks"] = {k: round(v, 1) for k, v in sorted(current.items())}
        with open(baseline_path, "w") as f:
            json.dump(baseline, f, indent=2)
            f.write("\n")
        print(f"Baseline updated: {len(current)} benchmarks → {baseline_path}")
        return 0

    tolerance = baseline["tolerance"]
    rows, regressions = [], 0
    for name in sorted(set(baseline["benchmarks"]) | set(current)):
        expected, actual = baseline["benchmarks"].get(name), current.get(name)
        if expected is None:
            rows.append((name, "", f"{actual:,.1f}", "", "🆕 not in baseline"))
            annotate("warning", "New benchmark", f"{name} has no allocation baseline: run bin/bench-guard.sh --update")
            continue
        if actual is None:
            rows.append((name, f"{expected:,.1f}", "", "", "⚠️ not measured"))
            regressions += 1
            annotate("error", "Benchmark missing", f"{name} is in the baseline but was not measured")
            continue
        delta = actual - expected
        if delta > limit(expected, tolerance):
            status = "❌ regression"
            regressions += 1
            annotate("error", "Allocation regression", f"{name}: {actual:,.1f} B/op, baseline {expected:,.1f} B/op (+{delta:,.1f})")
        elif -delta > limit(expected, tolerance):
            status = "📉 improved, update the baseline"
            annotate("notice", "Allocation improved", f"{name}: {actual:,.1f} B/op, baseline {expected:,.1f} B/op")
        else:
            status = "✅"
        rows.append((name, f"{expected:,.1f}", f"{actual:,.1f}", f"{delta:+,.1f}", status))

    print("### Allocation per packet (`gc.alloc.rate.norm`)\n")
    print(f"Tolerance: the larger of {tolerance['absolute']} B and {tolerance['relative']:.0%} of the baseline.\n")
    print("| Benchmark | Baseline B/op | Measured B/op | Δ | |")
    print("|---|--:|--:|--:|---|")
    for name, expected, actual, delta, status in rows:
        print(f"| `{name}` | {expected} | {actual} | {delta} | {status} |")
    return 1 if regressions else 0


if __name__ == "__main__":
    sys.exit(main())
