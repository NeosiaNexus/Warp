#!/usr/bin/env python3
"""Renders JaCoCo XML reports as a Markdown coverage table (one row per module).

Usage: coverage-summary.py [REPORT.xml ...]   (default: */build/reports/jacoco/test/*.xml)
Prints to stdout; CI appends it to $GITHUB_STEP_SUMMARY.
"""

import glob
import sys
import xml.etree.ElementTree as ET

COUNTERS = ("LINE", "BRANCH", "INSTRUCTION")


def counters(path):
    root = ET.parse(path).getroot()
    values = {}
    for counter in root.findall("counter"):
        kind = counter.get("type")
        if kind in COUNTERS:
            values[kind] = (int(counter.get("covered")), int(counter.get("missed")))
    return values


def cell(covered, missed):
    total = covered + missed
    if total == 0:
        return "—"
    ratio = covered / total
    filled = round(ratio * 10)
    return f"`{'█' * filled}{'░' * (10 - filled)}` {ratio:.1%}"


def main(paths):
    paths = paths or sorted(glob.glob("*/build/reports/jacoco/test/jacocoTestReport.xml"))
    if not paths:
        print("_No JaCoCo report found._")
        return
    rows, totals = [], {kind: [0, 0] for kind in COUNTERS}
    for path in paths:
        module = path.split("/")[0]
        values = counters(path)
        for kind, (covered, missed) in values.items():
            totals[kind][0] += covered
            totals[kind][1] += missed
        rows.append((module, values))

    print("### Coverage\n")
    print("| Module | Lines | Branches | Instructions |")
    print("|---|---|---|---|")
    for module, values in rows:
        print(f"| `{module}` | " + " | ".join(cell(*values.get(k, (0, 0))) for k in COUNTERS) + " |")
    print("| **Total** | " + " | ".join(f"**{cell(*totals[k])}**" for k in COUNTERS) + " |")


if __name__ == "__main__":
    main(sys.argv[1:])
