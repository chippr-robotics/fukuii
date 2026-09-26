#!/usr/bin/env python3
"""Aggregate a sharded hive full pass (hive-full.yml) into one verdict per suite.

A suite passes only when ALL of the following hold -- a workflow conclusion alone is not a suite
result:
  * every planned shard uploaded its logs,
  * every shard's simulator FINISHED (hive printed `simulation <sim> finished ...`), never
    `simulation timed out`,
  * for consume-*, every shard selected exactly the number of tests the plan assigned it (so the
    shards partition the suite: nothing skipped, nothing run twice), and the shards together
    selected the suite's expected total,
  * no test failed.

Pass/fail is read from each test's `summaryResult.pass` in hive's result JSON. Failures are
listed by name; a failure whose detail says the test was killed by the time limit ("terminated
by host", "timed out waiting for container startup") is labelled as such, but it still fails the
suite -- a pass that did not run a test did not pass it.

Usage: aggregate-full.py <artifacts-dir> [--plan .github/hive-shards.json] [--suites a,b]
Writes a markdown report to stdout (and $GITHUB_STEP_SUMMARY if set) and failing test names to
<artifacts-dir>/failing-<suite>.txt. Exit 1 unless every requested suite passes.
"""

import argparse
import glob
import json
import os
import re
import sys

TAIL_MARKERS = ("terminated by host", "timed out waiting for container startup")
FINISHED = re.compile(r"simulation \S+ finished suites=\d+ tests=(\d+) failed=(\d+)")
# pytest-regex prints exactly what it kept; xdist's "[N items]" is the fallback.
SELECTED = re.compile(r"pytest-regex selected (\d+) tests")
SELECTED_XDIST = re.compile(r"\d+ workers? \[(\d+) items?\]")


def read(path):
    try:
        with open(path, errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def shard_result(shard_dir):
    run_log = read(os.path.join(shard_dir, "hive-run.log"))
    logs = os.path.join(shard_dir, "workspace", "logs")
    tests, failures = 0, []
    for jf in glob.glob(os.path.join(logs, "*.json")):
        try:
            doc = json.load(open(jf))
        except (OSError, ValueError):
            continue
        if not isinstance(doc, dict) or "testCases" not in doc:
            continue
        details_path = os.path.join(logs, doc.get("testDetailsLog") or "")
        for tc in doc["testCases"].values():
            tests += 1
            if tc["summaryResult"]["pass"]:
                continue
            text = tc["summaryResult"].get("details") or ""
            span = tc["summaryResult"].get("log")
            if span and os.path.exists(details_path):
                with open(details_path, "rb") as f:
                    f.seek(span["begin"])
                    text += f.read(span["end"] - span["begin"]).decode(errors="replace")
            tail = any(m in text for m in TAIL_MARKERS)
            failures.append((tc["name"], "time-limit" if tail else "failed"))
    selected = None
    for sim_log in glob.glob(os.path.join(logs, "*simulator*.log")):
        text = read(sim_log)
        m = SELECTED.search(text) or SELECTED_XDIST.search(text)
        if m:
            selected = int(m.group(1))
    return {
        "present": bool(run_log) or os.path.isdir(logs),
        "finished": bool(FINISHED.search(run_log)) and "simulation timed out" not in run_log,
        "timed_out": "simulation timed out" in run_log,
        "tests": tests,
        "failures": failures,
        "selected": selected,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("artifacts")
    ap.add_argument("--plan", default=".github/hive-shards.json")
    ap.add_argument("--suites", default="")
    args = ap.parse_args()
    plan = json.load(open(args.plan))
    wanted = [s for s in args.suites.split(",") if s] or list(plan["suites"])

    out, ok_all = [], True
    out.append("# Hive full pass\n")
    for suite in wanted:
        spec = plan["suites"][suite]
        rows, problems, failing = [], [], []
        total_selected = total_tests = 0
        for shard in spec["shards"]:
            r = shard_result(os.path.join(args.artifacts, f"hive-logs-{shard['name']}"))
            state = "missing" if not r["present"] else ("finished" if r["finished"] else
                                                          ("TIMED OUT" if r["timed_out"] else "incomplete"))
            if state != "finished":
                problems.append(f"{shard['name']}: {state}")
            if suite.startswith("consume-"):
                if r["selected"] is None:
                    problems.append(f"{shard['name']}: selected-count line not found")
                else:
                    total_selected += r["selected"]
                    if r["selected"] != shard["est_tests"]:
                        problems.append(f"{shard['name']}: selected {r['selected']}, plan {shard['est_tests']}")
            total_tests += r["tests"]
            failing += r["failures"]
            rows.append(f"| {shard['name']} | {state} | {r['selected'] if r['selected'] is not None else '-'} "
                        f"| {r['tests']} | {len(r['failures'])} |")
        if suite.startswith("consume-") and total_selected != spec["expected_tests"]:
            problems.append(f"shards selected {total_selected} in total, suite has {spec['expected_tests']}")
        genuine = [n for n, k in failing if k == "failed"]
        tail = [n for n, k in failing if k == "time-limit"]
        passed = not problems and not failing
        ok_all &= passed
        with open(os.path.join(args.artifacts, f"failing-{suite}.txt"), "w") as f:
            f.write("".join(f"{n}\t{k}\n" for n, k in sorted(failing)))
        out.append(f"## {suite}: {'PASS' if passed else 'FAIL'}\n")
        out.append(f"{total_tests} tests ran; {len(genuine)} failed; {len(tail)} killed by the time limit.\n")
        if problems:
            out.append("**Coverage problems:**\n" + "".join(f"- {p}\n" for p in problems))
        out.append("| shard | simulator | selected | ran | failed |\n|---|---|---|---|---|")
        out += rows
        if failing:
            out.append("\n<details><summary>failing tests</summary>\n")
            out += [f"- `{n}` ({k})" for n, k in sorted(failing)[:500]]
            if len(failing) > 500:
                out.append(f"- ... {len(failing) - 500} more in failing-{suite}.txt")
            out.append("\n</details>\n")
        out.append("")
    report = "\n".join(out) + "\n"
    sys.stdout.write(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(report)
    sys.exit(0 if ok_all else 1)


if __name__ == "__main__":
    main()
