#!/usr/bin/env python3
"""Tests of bench-guard.py, which decides whether the allocation guard passes.

    python3 bin/bench-guard-test.py

The results are shaped like JMH's JSON output, the baselines like alloc-baseline.json.
"""

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

# bench-guard.py is a script, not a module: load it by path, without a __pycache__ next to it.
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "bench_guard", pathlib.Path(__file__).with_name("bench-guard.py"))
guard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(guard)

CHUNK = "ForwardingPathBenchmark.relayClientbound[workload=CHUNK]"
MOVE = "ForwardingPathBenchmark.relayClientbound[workload=ENTITY_MOVE]"


def result(workload, *allocation, jdk="25.0.4", score=100.0, error=1.0):
    """One JMH result with the GC profiler: the bytes allocated per packet in each iteration."""
    return {
        "benchmark": "dev.warp.protocol.netty.ForwardingPathBenchmark.relayClientbound",
        "params": {"workload": workload},
        "jdkVersion": jdk,
        "forks": 3,
        "measurementIterations": 10,
        "measurementTime": "2 s",
        "primaryMetric": {"score": score, "scoreError": error, "scoreUnit": "ns/op"},
        "secondaryMetrics": {guard.ALLOCATION: {"rawData": [list(allocation)]}},
    }


class GuardTest(unittest.TestCase):
    """Runs the commands as in GitHub Actions, in a temporary directory."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = pathlib.Path(directory.name)
        self.baseline = self.path / "alloc-baseline.json"
        self.output = self.path / "github-output"
        environment = {"GITHUB_ACTIONS": "true", "GITHUB_OUTPUT": str(self.output)}
        patcher = unittest.mock.patch.dict(os.environ, environment)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_command(self, command, results, target):
        """Returns the exit status, the Markdown report and the annotations."""
        report, annotations = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(report), contextlib.redirect_stderr(annotations):
            status = command(results, str(target))
        return status, report.getvalue(), annotations.getvalue()

    def check(self, benchmarks, *results, jdk="25.0.4"):
        self.baseline.write_text(json.dumps({"jdk": jdk, "benchmarks": benchmarks}))
        return self.run_command(guard.check, list(results), self.baseline)


class CheckTest(GuardTest):

    def test_passes_within_the_larger_of_the_two_tolerances(self):
        # 2 B on 44.5 B; 1% of 8000 B is 80 B.
        status, report, annotations = self.check(
            {CHUNK: 8000.0, MOVE: 44.5}, result("CHUNK", 8080.0), result("ENTITY_MOVE", 46.5))
        self.assertEqual(status, 0)
        self.assertEqual(annotations, "")
        self.assertIn("| 8,000.0 B | 8,080.0 B | +80.0 B | ✅ |", report)

    def test_fails_above_the_tolerance(self):
        status, report, annotations = self.check(
            {CHUNK: 8000.0, MOVE: 44.5}, result("CHUNK", 8080.2), result("ENTITY_MOVE", 46.6))
        self.assertEqual(status, 1)
        self.assertEqual(report.count("❌ regression"), 2)
        self.assertIn(f"::error title=Allocation regression::{MOVE} allocates 46.6 B", annotations)

    def test_keeps_the_lowest_iteration(self):
        status, report, _ = self.check({MOVE: 44.5}, result("ENTITY_MOVE", 60.0, 44.5, 52.0))
        self.assertEqual(status, 0)
        self.assertIn("| 44.5 B | 44.5 B | +0.0 B | ✅ |", report)

    def test_fails_on_a_benchmark_without_a_baseline(self):
        status, report, annotations = self.check(
            {CHUNK: 16.3}, result("CHUNK", 16.3), result("ENTITY_MOVE", 44.5))
        self.assertEqual(status, 1)
        self.assertIn("❌ not in the baseline", report)
        self.assertIn("::error title=Benchmark without a baseline::", annotations)

    def test_fails_on_a_baseline_benchmark_that_was_not_measured(self):
        status, report, annotations = self.check({CHUNK: 16.3, MOVE: 44.5}, result("CHUNK", 16.3))
        self.assertEqual(status, 1)
        self.assertIn("❌ not measured", report)
        self.assertIn("::error title=Benchmark not measured::", annotations)

    def test_warns_when_allocation_drops(self):
        status, report, annotations = self.check({MOVE: 44.5}, result("ENTITY_MOVE", 40.0))
        self.assertEqual(status, 0)
        self.assertIn("📉 lower: update the baseline", report)
        self.assertIn("::warning title=Allocation dropped::", annotations)

    def test_names_the_baseline_jdk_only_when_allocation_moved(self):
        _, steady, _ = self.check({MOVE: 44.5}, result("ENTITY_MOVE", 44.5), jdk="25.0.2")
        _, moved, _ = self.check({MOVE: 44.5}, result("ENTITY_MOVE", 40.0), jdk="25.0.2")
        self.assertNotIn("JDK", steady)
        self.assertIn("measured on JDK 25.0.2, this run on JDK 25.0.4", moved)


class UpdateTest(GuardTest):

    def update(self, *results):
        self.run_command(guard.update, list(results), self.baseline)
        return json.loads(self.baseline.read_text())

    def test_writes_the_lowest_iterations_rounded_with_the_default_tolerance(self):
        baseline = self.update(result("ENTITY_MOVE", 44.54, 44.532), result("CHUNK", 16.28))
        self.assertEqual(baseline, {
            "tolerance": {"absolute": 2, "relative": 0.01},
            "jdk": "25.0.4",
            "benchmarks": {CHUNK: 16.3, MOVE: 44.5},
        })

    def test_keeps_the_tolerance_of_the_baseline(self):
        self.baseline.write_text(json.dumps({"tolerance": {"absolute": 4}, "benchmarks": {}}))
        baseline = self.update(result("CHUNK", 16.3))
        self.assertEqual(baseline["tolerance"], {"absolute": 4, "relative": 0.01})


class TimingsTest(GuardTest):

    def test_writes_the_chart_and_the_cpu_output(self):
        chart_path = self.path / "chart.json"
        # JMH writes the error as the string "NaN" when there are fewer than two iterations.
        status, report, _ = self.run_command(guard.timings, [
            result("ENTITY_MOVE", 44.5, score=142.63, error=0.61),
            result("CHUNK", 16.3, score=2772.31, error="NaN"),
        ], chart_path)
        chart = json.loads(chart_path.read_text())
        self.assertEqual(status, 0)
        self.assertEqual([entry["name"] for entry in chart], [CHUNK, MOVE])
        self.assertEqual(chart[1] | {"extra": ""}, {
            "name": MOVE, "unit": "ns/packet", "value": 142.6, "range": "± 0.6", "extra": ""})
        self.assertNotIn("range", chart[0])
        self.assertIn("JDK 25.0.4, 3 forks × 10 iterations of 2 s", chart[0]["extra"])
        self.assertEqual(self.output.read_text(), f"cpu={guard.cpu()}\n")
        self.assertIn("| 2,772.3 ns |", report)


if __name__ == "__main__":
    unittest.main()
