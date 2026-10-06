#!/usr/bin/env python3
"""Renders PIT mutation reports as Markdown: a score table, then the mutants left alive per class.

Usage: mutation-summary.py [REPORT.xml ...]   (default: */build/reports/pitest/mutations.xml)
Prints the Markdown to stdout, which CI appends to $GITHUB_STEP_SUMMARY. Each module's score is
compared with its threshold in config/pitest/thresholds.properties, and workflow annotations go to
stderr: an error for a score below its threshold (the pitest task fails the build too), a notice
for a score that has outgrown it.
"""

import collections
import glob
import math
import os
import sys
import xml.etree.ElementTree as ET

THRESHOLDS = "config/pitest/thresholds.properties"
ALIVE = ("SURVIVED", "NO_COVERAGE")


def thresholds():
    """Returns {module: (threshold, line number)} from the thresholds file."""
    found = {}
    try:
        with open(THRESHOLDS, encoding="utf-8") as file:
            for number, line in enumerate(file, start=1):
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    module, value = (part.strip() for part in line.split("=", 1))
                    found[module] = (int(value), number)
    except FileNotFoundError:
        pass
    return found


def pit_score(killed, total):
    """The score PIT compares with its threshold: rounded half up, 100 only when all are killed."""
    if total == 0 or killed == total:
        return 100
    return min(99, math.floor(100 * killed / total + 0.5))


def annotate(level, line, title, message):
    """Annotates a line of the thresholds file, through stderr: stdout is the summary."""
    print(f"::{level} file={THRESHOLDS},line={line},title={title}::{message}", file=sys.stderr)


def percent(part, whole):
    return f"{100 * part / whole:.1f}%" if whole else "n/a"


def bar(part, whole):
    filled = round(10 * part / whole) if whole else 10
    return f"`{'█' * filled}{'░' * (10 - filled)}`"


def source_link(module, mutation, text):
    """Links text to the mutated line on GitHub when running in Actions, else returns it as is."""
    names = ("GITHUB_SERVER_URL", "GITHUB_REPOSITORY", "GITHUB_SHA")
    server, repo, sha = (os.environ.get(name) for name in names)
    if not (server and repo and sha):
        return text
    package = mutation["class"].rsplit(".", 1)[0].replace(".", "/")
    path = f"{module}/src/main/java/{package}/{mutation['file']}"
    return f"[{text}]({server}/{repo}/blob/{sha}/{path}#L{mutation['line']})"


def load(path):
    mutations = []
    for node in ET.parse(path).getroot().iter("mutation"):
        mutations.append({
            "detected": node.get("detected") == "true",
            "status": node.get("status"),
            "file": node.findtext("sourceFile"),
            "class": node.findtext("mutatedClass"),
            "method": node.findtext("mutatedMethod"),
            "line": int(node.findtext("lineNumber")),
            # "replaced int return with 0 for dev/warp/...::read": the method is listed apart.
            "description": node.findtext("description").split(" for ")[0],
        })
    return mutations


def main(paths):
    paths = paths or sorted(glob.glob("*/build/reports/pitest/mutations.xml"))
    print("### Mutation testing\n")
    if not paths:
        print("_No PIT report found._")
        return
    floors = thresholds()
    modules = []
    for path in paths:
        module = path.split("/")[0]
        mutations = load(path)
        total = len(mutations)
        killed = sum(m["detected"] for m in mutations)
        uncovered = sum(m["status"] == "NO_COVERAGE" for m in mutations)
        modules.append((module, mutations, total, killed, uncovered))

    print("| Module | Mutation score | Test strength | Threshold"
          " | Mutants | Killed | Survived | No coverage |")
    print("|---|---|---|---|---|---|---|---|")
    for module, mutations, total, killed, uncovered in modules:
        score, scored = pit_score(killed, total), f"{module} scores {percent(killed, total)}"
        threshold, line = floors.get(module, (None, None))
        if threshold is None:
            verdict = "none"
        elif score < threshold:
            verdict = f"❌ {threshold}%"
            annotate("error", line, "Mutation score below threshold",
                     f"{scored}, below its threshold of {threshold}%.")
        else:
            verdict = f"✅ {threshold}%"
            reached = math.floor(100 * killed / total) if total else 100
            if reached > threshold:
                annotate("notice", line, "Mutation score above threshold",
                         f"{scored}: raise its threshold to {reached}.")
        survived = total - killed - uncovered
        print(f"| `{module}` | {bar(killed, total)} {percent(killed, total)} "
              f"| {percent(killed, total - uncovered)} | {verdict} "
              f"| {total} | {killed} | {survived} | {uncovered} |")

    print("\nA mutant is a small bug planted in the code; it is killed when a test fails because of"
          " it. Test strength leaves out the mutants no test covers.\n")
    for module, mutations, total, killed, uncovered in modules:
        alive = collections.defaultdict(list)
        for mutation in mutations:
            if mutation["status"] in ALIVE:
                alive[mutation["class"].split("$")[0]].append(mutation)
        if not alive:
            continue
        root = f"dev.warp.{module}."
        print(f"<details><summary><b><code>{module}</code></b>: {total - killed} mutants alive"
              f" in {len(alive)} classes</summary>\n")
        for name, entries in sorted(alive.items(), key=lambda item: (-len(item[1]), item[0])):
            survived = sum(m["status"] == "SURVIVED" for m in entries)
            print(f"<details><summary><code>{name.removeprefix(root)}</code>: {survived} survived,"
                  f" {len(entries) - survived} without coverage</summary>\n")
            for m in sorted(entries, key=lambda m: (m["line"], m["description"])):
                status = "" if m["status"] == "SURVIVED" else " _(no coverage)_"
                print(f"- {source_link(module, m, 'line ' + str(m['line']))}:"
                      f" {m['description']} in `{m['method']}`{status}")
            print("\n</details>\n")
        print("</details>\n")


if __name__ == "__main__":
    main(sys.argv[1:])
