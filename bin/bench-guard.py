#!/usr/bin/env python3
"""Reports on the hot-path JMH benchmarks that bin/bench-guard.sh runs.

    bench-guard.py check RESULTS.json BASELINE.json    # allocation against the baseline
    bench-guard.py update RESULTS.json BASELINE.json   # rewrite the baseline from the results
    bench-guard.py timings RESULTS.json CHART.json     # timings for github-action-benchmark

check: allocation is JMH's gc.alloc.rate.norm, the bytes allocated per operation, here per packet.
The minimum over the measured iterations is kept, since transient work (class loading,
deoptimisation) only ever adds to it. A benchmark fails when it allocates more than its baseline
plus the larger of the two tolerances, or when it is in only one of the results and the baseline.
Exits with 1 on failure.

update: the baseline keeps its tolerance, or gets the default one when it has none or does not
exist yet.

timings: writes the scores in github-action-benchmark's customSmallerIsBetter format. Each entry
names the CPU and the JDK of the run, and in GitHub Actions the CPU is also the step's `cpu`
output: timings are only comparable on the same CPU model, so the workflow keeps a series per model.

Prints a Markdown report on stdout and, in GitHub Actions, annotations on stderr.
"""

import json
import math
import os
import platform
import sys

ALLOCATION = "gc.alloc.rate.norm"
DEFAULT_TOLERANCE = {"absolute": 2, "relative": 0.01}  # bytes per packet, fraction of the baseline


# ---------------------------------------------------------------------------
# JMH results
# ---------------------------------------------------------------------------


def key(result):
    """`Class.method[param=value,...]`: the benchmark without its package, then its parameters."""
    method = ".".join(result["benchmark"].split(".")[-2:])
    params = result.get("params") or {}
    if not params:
        return method
    return f"{method}[{','.join(f'{k}={v}' for k, v in sorted(params.items()))}]"


def columns(bench_key):
    """Splits a key into the benchmark and its parameters, for a Markdown table."""
    method, _, params = bench_key.partition("[")
    return f"`{method}`", params.rstrip("]").replace(",", ", ")


def allocation(results):
    measured = {}
    for result in results:
        metric = result["secondaryMetrics"].get(ALLOCATION)
        if metric is None:
            sys.exit(f"{key(result)}: no {ALLOCATION} in the results (run JMH with -prof gc)")
        measured[key(result)] = min(value for fork in metric["rawData"] for value in fork)
    return measured


def jdk(results):
    return results[0]["jdkVersion"] if results else "unknown"


def cpu():
    try:
        with open("/proc/cpuinfo") as f:
            model = next(line.split(":", 1)[1].strip() for line in f if line.startswith("model name"))
    except (OSError, StopIteration):
        model = platform.processor() or platform.machine()
    return f"{model} ({os.cpu_count()} CPUs)"


def annotate(level, title, message):
    if os.environ.get("GITHUB_ACTIONS") == "true":
        message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::{level} title={title}::{message}", file=sys.stderr)


def load(path):
    with open(path) as f:
        return json.load(f)


def load_baseline(path):
    """The baseline, with the default tolerance where it sets none; empty if it does not exist."""
    try:
        baseline = load(path)
    except FileNotFoundError:
        baseline = {}
    return {
        "tolerance": DEFAULT_TOLERANCE | baseline.get("tolerance", {}),
        "jdk": baseline.get("jdk"),
        "benchmarks": baseline.get("benchmarks", {}),
    }


def set_output(name, value):
    """Sets an output of the current GitHub Actions step; does nothing elsewhere."""
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as f:
            f.write(f"{name}={value}\n")


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------


def check(results, baseline_path):
    baseline = load_baseline(baseline_path)
    expected, measured = baseline["benchmarks"], allocation(results)
    absolute, relative = baseline["tolerance"]["absolute"], baseline["tolerance"]["relative"]
    rows, failures, moved = [], 0, 0
    for bench in sorted(expected.keys() | measured.keys()):
        base, actual = expected.get(bench), measured.get(bench)
        if base is None:
            failures += 1
            rows.append((bench, "", f"{actual:,.1f} B", "", "❌ not in the baseline"))
            annotate("error", "Benchmark without a baseline",
                     f"{bench} allocates {actual:,.1f} B per packet and has no baseline: "
                     "run bin/bench-guard.sh --update and commit the baseline.")
            continue
        if actual is None:
            failures += 1
            rows.append((bench, f"{base:,.1f} B", "", "", "❌ not measured"))
            annotate("error", "Benchmark not measured",
                     f"{bench} is in the baseline but was not measured: if it is gone, "
                     "run bin/bench-guard.sh --update and commit the baseline.")
            continue
        delta, allowed = round(actual - base, 1) or 0.0, max(absolute, base * relative)  # no -0.0
        if delta > allowed:
            failures += 1
            moved += 1
            status = "❌ regression"
            annotate("error", "Allocation regression",
                     f"{bench} allocates {actual:,.1f} B per packet, {delta:,.1f} B more than its "
                     f"baseline of {base:,.1f} B (tolerance {allowed:,.1f} B).")
        elif -delta > allowed:
            moved += 1
            status = "📉 lower: update the baseline"
            annotate("warning", "Allocation dropped",
                     f"{bench} allocates {actual:,.1f} B per packet, {-delta:,.1f} B less than its "
                     "baseline: run bin/bench-guard.sh --update to lock in the gain.")
        else:
            status = "✅"
        rows.append((bench, f"{base:,.1f} B", f"{actual:,.1f} B", f"{delta:+,.1f} B", status))

    print("### Allocation per packet\n")
    print(f"Bytes allocated per packet with escape analysis off, against `{baseline_path}`. "
          f"A benchmark fails above its baseline plus the larger of {absolute} B and {relative:.0%}.\n")
    if moved and baseline.get("jdk") != jdk(results):
        print(f"> [!NOTE]\n> The baseline was measured on JDK {baseline.get('jdk')}, this run on "
              f"JDK {jdk(results)}: a JDK update can move allocation.\n")
    print("| Benchmark | Parameters | Baseline | Measured | Δ | |")
    print("|---|---|--:|--:|--:|---|")
    for bench, base, actual, delta, status in rows:
        print("| " + " | ".join((*columns(bench), base, actual, delta, status)) + " |")
    return 1 if failures else 0


def update(results, baseline_path):
    measured = allocation(results)
    baseline = {
        "tolerance": load_baseline(baseline_path)["tolerance"],
        "jdk": jdk(results),
        "benchmarks": {bench: round(value, 1) for bench, value in sorted(measured.items())},
    }
    with open(baseline_path, "w") as f:
        json.dump(baseline, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"Baseline updated: {len(measured)} benchmarks measured on JDK {jdk(results)}, "
          f"written to {baseline_path}.")
    return 0


def timings(results, chart_path):
    machine = cpu()
    chart, rows = [], []
    for result in sorted(results, key=key):
        metric = result["primaryMetric"]
        unit = metric["scoreUnit"].removesuffix("/op")
        error = float(metric["scoreError"])  # NaN (a string in JMH's JSON) below two iterations
        entry = {"name": key(result), "unit": f"{unit}/packet", "value": round(metric["score"], 1)}
        if math.isfinite(error):
            entry["range"] = f"± {error:.1f}"  # parsed back as a number: no thousands separator
        entry["extra"] = (f"{machine}\nJDK {result['jdkVersion']}, {result['forks']} forks × "
                          f"{result['measurementIterations']} iterations of {result['measurementTime']}")
        chart.append(entry)
        interval = f" ± {error:,.1f}" if math.isfinite(error) else ""
        rows.append((*columns(key(result)), f"{metric['score']:,.1f}{interval} {unit}"))
    with open(chart_path, "w") as f:
        json.dump(chart, f, indent=2, ensure_ascii=False)
        f.write("\n")
    set_output("cpu", machine)

    print("### Timings\n")
    print(f"Average time per packet with a 99.9% confidence interval, on {machine} and "
          f"JDK {jdk(results)}. Shared runners are noisy: read the trend, not one run.\n")
    print("| Benchmark | Parameters | Time per packet |")
    print("|---|---|--:|")
    for row in rows:
        print("| " + " | ".join(row) + " |")
    return 0


COMMANDS = {"check": check, "update": update, "timings": timings}


def main(argv):
    if len(argv) != 4 or argv[1] not in COMMANDS:
        sys.exit(__doc__)
    command, results_path, target = argv[1:]
    return COMMANDS[command](load(results_path), target)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
