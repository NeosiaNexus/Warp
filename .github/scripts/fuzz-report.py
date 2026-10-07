#!/usr/bin/env python3
"""Reports a run of the Gradle `fuzz` task (build-logic/src/main/kotlin/warp.fuzz-conventions.gradle.kts).

Usage:
  fuzz-report.py corpus                       JSON: the size of each fuzz test's corpus
  fuzz-report.py summary [BEFORE] [FINDINGS]  Markdown: a row per fuzz test, then what failed
  fuzz-report.py annotations                  a workflow error per fuzz test that did not pass

BEFORE is the output of `corpus` before fuzzing; FINDINGS a directory holding the failing inputs at
their path in the repository. Reads */build/test-results/fuzz/*.xml and */.cifuzz-corpus/.
"""

import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

REPORTS = "*/build/test-results/fuzz/TEST-*.xml"
CORPORA = "*/.cifuzz-corpus/*/*"
FUZZING = "Fuzzing..."  # the test case a fuzzing run reports as

# A JVM that exits mid-run, as libFuzzer makes it on an input running for 10 seconds, leaves the
# fuzzing test case reported as skipped.
STOPPED = "an input ran for 10 s, or crashed the JVM"
RESULTS = {
    "passed": "✅ no finding",
    "failed": "❌ found a failing input",
    "stopped": f"❌ stopped: {STOPPED}",
}


def corpus_sizes():
    """Number of inputs in each corpus, keyed by `<module>/<class>`."""
    sizes = {}
    for path in sorted(glob.glob(CORPORA)):
        module, _, test_class, _ = path.split("/", 3)
        sizes[f"{module}/{test_class}"] = len(os.listdir(path))
    return sizes


def fuzzing_runs():
    """The fuzzing test case of each fuzz test that ran, keyed by `<module>/<class>`."""
    runs = {}
    for path in sorted(glob.glob(REPORTS)):
        module = path.split("/")[0]
        for case in ET.parse(path).getroot().iter("testcase"):
            if case.get("name") == FUZZING:
                runs[f"{module}/{case.get('classname')}"] = case
    return dict(sorted(runs.items(), key=lambda run: simple_name(run[0])))


def simple_name(key):
    return key.rsplit(".", 1)[-1]


def failure(case):
    """The failure of a fuzzing test case, or None."""
    found = case.find("failure")
    return found if found is not None else case.find("error")


def outcome(case):
    if failure(case) is not None:
        return "failed"
    return "stopped" if case.find("skipped") is not None else "passed"


def message(case):
    """The first line of what a failed fuzzing run reported."""
    return (failure(case).get("message") or "A failing input").splitlines()[0]


def summary(before_path=None, findings_dir=None):
    before = {}
    if before_path and os.path.exists(before_path):
        with open(before_path) as file:
            before = json.load(file)
    sizes, runs = corpus_sizes(), fuzzing_runs()

    print("### Fuzzing\n")
    if not runs:
        print("_No fuzz test ran._")
        return
    print("| Fuzz test | Result | Fuzzed | Corpus |")
    print("|---|---|---|---|")
    for key, case in runs.items():
        result = outcome(case)
        fuzzed = "-" if result == "stopped" else f"{float(case.get('time')):.0f} s"
        size = sizes.get(key, 0)
        grown = size - before.get(key, size)
        corpus = f"{size} inputs" + (f" (+{grown} new)" if grown > 0 else "")
        print(f"| `{simple_name(key)}` | {RESULTS[result]} | {fuzzed} | {corpus} |")

    failed = {key: case for key, case in runs.items() if outcome(case) == "failed"}
    for key, case in failed.items():
        print(f"\n`{simple_name(key)}`:\n```\n{message(case)}\n```")

    findings = []
    if findings_dir and os.path.isdir(findings_dir):
        for root, _, files in os.walk(findings_dir):
            findings += [os.path.relpath(os.path.join(root, name), findings_dir) for name in files]
    if findings:
        print("\n#### Failing inputs\n")
        for finding in sorted(findings):
            print(f"- `{finding}`")
        print(
            "\nOnce in place, each is a failing test: download the `fuzz-findings` artifact, unpack"
            " it at the root of the repository and run `./gradlew :protocol:test`. Commit the input"
            " with the fix, renamed after the bug, to keep it as a regression test."
        )


def escape(text, property_value=False):
    """Escapes text for a workflow command."""
    text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return text.replace(":", "%3A").replace(",", "%2C") if property_value else text


def annotations():
    for key, case in fuzzing_runs().items():
        result = outcome(case)
        if result == "passed":
            continue
        module, test_class = key.split("/", 1)
        simple = simple_name(test_class)
        source = f"{module}/src/test/java/{test_class.replace('.', '/')}.java"
        if result == "stopped":
            title, location, text = f"Fuzzing stopped in {simple}", "", f"Stopped: {STOPPED}."
        else:
            # The first frame in the fuzz test itself: the assertion that failed, or the call
            # that threw.
            frames = re.findall(rf"\({re.escape(simple)}\.java:(\d+)\)", failure(case).text or "")
            title = f"Fuzzing found a failing input in {simple}"
            location = f",line={frames[0]}" if frames else ""
            text = message(case)
        print(f"::error file={source}{location},title={escape(title, True)}::{escape(text)}")


if __name__ == "__main__":
    command, *args = sys.argv[1:] or ["summary"]
    if command == "corpus":
        print(json.dumps(corpus_sizes()))
    elif command == "summary":
        summary(*args)
    elif command == "annotations":
        annotations()
    else:
        sys.exit(__doc__)
