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


def result(workload, *allocation, jdk="25.0.4", score=100.0, error=1.0, threads=1,
           benchmark="dev.warp.protocol.netty.ForwardingPathBenchmark.relayClientbound"):
    """One JMH result with the GC profiler: the bytes allocated per packet in each iteration.

    Every iteration lasts 2 s and takes `score` ns per packet: 20 million packets at 100 ns, over
    which JMH's own allocation is nothing.
    """
    return {
        "benchmark": benchmark,
        "mode": "avgt",
        "threads": threads,
        "params": {"workload": workload} if workload else None,
        "jdkVersion": jdk,
        "forks": 3,
        "measurementIterations": 10,
        "measurementTime": "2 s",
        "primaryMetric": {"score": score, "scoreError": error, "scoreUnit": "ns/op",
                          "rawData": [[score] * len(allocation)]},
        "secondaryMetrics": {guard.ALLOCATION: {"rawData": [list(allocation)]}},
    }


def harness(*allocated, score=1000.0):
    """The calibration benchmark: 2 million operations per iteration at 1000 ns, so 0.0035 B per
    operation is 7000 B per iteration."""
    return result(None, *(allocated or (0.0035,)), score=score,
                  benchmark="dev.warp.protocol.bench.HarnessAllocationBenchmark.idle")


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

    def check(self, benchmarks, *results, jdk="25.0.4", calibration=None, recorded=7000):
        """Checks the results, measured next to a calibration of 7000 B per iteration, against a
        baseline that records `recorded` B per iteration."""
        self.baseline.write_text(
            json.dumps({"jdk": jdk, "harness": recorded, "benchmarks": benchmarks}))
        results = [*results, calibration or harness()]
        return self.run_command(guard.check, results, self.baseline)


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

    def test_subtracts_the_share_of_what_jmh_allocates_per_iteration(self):
        # 1 ms per packet is 2000 packets per iteration: 7000 B of JMH's own read as 3.5 B per
        # packet there, and as 7 B on a machine half as fast. Neither is the benchmark's.
        for score, measured in ((1e6, 3.5), (2e6, 7.0)):
            status, report, _ = self.check({CHUNK: 0.0}, result("CHUNK", measured, score=score))
            self.assertEqual(status, 0)
            self.assertIn("| 0.0 B | 0.0 B | +0.0 B | ✅ |", report)
        self.assertIn("JMH itself allocated 7,000 B per iteration (7,000 B in the baseline)", report)

    def test_still_fails_on_what_a_slow_benchmark_allocates_itself(self):
        # 16 B per packet on top of JMH's 3.5 B.
        status, report, _ = self.check({CHUNK: 0.0}, result("CHUNK", 19.5, score=1e6))
        self.assertEqual(status, 1)
        self.assertIn("| 0.0 B | 16.0 B | +16.0 B | ❌ regression |", report)

    def test_corrects_each_iteration_by_its_own_operations(self):
        # The second iteration ran half the packets of the first: its share is twice as large.
        slow = result("CHUNK", 3.5, 7.0, score=1e6)
        slow["primaryMetric"]["rawData"] = [[1e6, 2e6]]
        slow["secondaryMetrics"][guard.ALLOCATION]["rawData"] = [[5.5, 7.0]]
        status, report, _ = self.check({CHUNK: 0.0}, slow)
        self.assertEqual(status, 0)
        self.assertIn("| 0.0 B | 0.0 B | +0.0 B | ✅ |", report)

    def test_counts_the_operations_of_every_thread(self):
        # Two threads at 1 ms per packet run 4000 packets per iteration: 1.75 B each of JMH's.
        status, report, _ = self.check(
            {CHUNK: 0.0}, result("CHUNK", 1.75, score=1e6, threads=2))
        self.assertEqual(status, 0)
        self.assertIn("| 0.0 B | 0.0 B | +0.0 B | ✅ |", report)

    def test_keeps_the_lowest_iteration_of_the_calibration(self):
        # The last iteration of a run carries the end of the run: 160 KB here.
        status, report, _ = self.check(
            {CHUNK: 0.0}, result("CHUNK", 3.5, score=1e6), calibration=harness(0.0035, 0.08))
        self.assertEqual(status, 0)
        self.assertIn("JMH itself allocated 7,000 B per iteration", report)

    def test_fails_when_jmh_allocates_more_than_twice_the_baseline(self):
        # Subtracting 15 KB per iteration would hide 4 B per packet of a benchmark at 1 ms.
        status, report, annotations = self.check(
            {CHUNK: 0.0}, result("CHUNK", 7.5, score=1e6), calibration=harness(0.0075))
        self.assertEqual(status, 1)
        self.assertIn("15,000 B per iteration, against 7,000 B in the baseline: ❌", report)
        self.assertIn("::error title=JMH's own allocation moved::", annotations)

    def test_fails_on_a_baseline_without_the_allocation_of_jmh(self):
        status, report, annotations = self.check({MOVE: 44.5}, result("ENTITY_MOVE", 44.5),
                                                 recorded=None)
        self.assertEqual(status, 1)
        self.assertIn("which the baseline does not record: ❌", report)
        self.assertIn("::error title=Baseline without JMH's own allocation::", annotations)

    def test_refuses_results_without_the_calibration(self):
        self.baseline.write_text(json.dumps({"harness": 7000, "benchmarks": {MOVE: 44.5}}))
        with self.assertRaisesRegex(SystemExit, "HarnessAllocationBenchmark.idle is not in"):
            self.run_command(guard.check, [result("ENTITY_MOVE", 44.5)], self.baseline)

    def test_refuses_a_benchmark_that_does_not_measure_average_time(self):
        throughput = result("ENTITY_MOVE", 44.5) | {"mode": "thrpt"}
        with self.assertRaisesRegex(SystemExit, "average times"):
            self.check({MOVE: 44.5}, throughput)

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
        self.run_command(guard.update, [*results, harness(0.00346)], self.baseline)
        return json.loads(self.baseline.read_text())

    def test_writes_the_lowest_iterations_rounded_with_the_default_tolerance(self):
        baseline = self.update(result("ENTITY_MOVE", 44.54, 44.532), result("CHUNK", 16.28))
        self.assertEqual(baseline, {
            "tolerance": {"absolute": 2, "relative": 0.01},
            "jdk": "25.0.4",
            "harness": 6900,
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
