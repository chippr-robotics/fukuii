#!/usr/bin/env python3
"""Aggregate trials and grades of an A/B run into a verdict.

  python3 -I .claude/skills/fukuii-sprint-retro/scripts/score.py \
      --run <run dir> --tasks <dir or task.json> [<dir> ...] [--json]

Per (task, rep): a hard failure loses outright (no answer, missing RESULT block,
leak of the gold, an edit by a read-only agent or on a read-only task, a
`must_not` string in the answer, a failed implement check, a wrong route).
Otherwise, in order: the higher rubric total from the blind grader; fewer false
claims plus protocol violations; FILES F1 better by more than 0.1; else a tie.
The grader's bare preference is reported but never decides: between two
equivalent answers it is a coin flip (seen in the first smoke run).
Per task: the majority over reps. Accept B only if all of:
  wins(B) - wins(A) >= max(2, ceil(0.2 * tasks)),
  B has no hard failure on a task where A had none,
  B loses no task in the regression suites (tasks from earlier sprints) that A won
    on a hard failure,
  B's median cost <= 1.25 x A's, unless B wins >= 2/3 of the decided tasks.
"""
import argparse, glob, json, math, os, statistics, sys
from collections import Counter, defaultdict


def load_tasks(paths):
    tasks = {}
    for p in paths:
        for f in ([p] if p.endswith(".json") else glob.glob(os.path.join(p, "**", "*.json"), recursive=True)):
            t = json.load(open(f))
            if "id" in t and "prompt" in t:
                tasks[t["id"]] = t
    return tasks


PROCESS_AGENTS = {"eye", "wraith"}


def f1(got, gold):
    norm = lambda s: {x.strip().lstrip("./") for x in s}
    got, gold = norm(got), norm(gold)
    if not gold:
        return None
    tp = len({g for g in got if any(g.endswith(x) or x.endswith(g) for x in gold)})
    if not tp:
        return 0.0
    p, r = tp / len(got), tp / len(gold)
    return 2 * p * r / (p + r)


def hard(rec, task):
    why = []
    exp = task.get("expected", {})
    if rec["subtype"] != "success":
        why.append(f"run {rec['subtype']}")
    if not rec["contract_ok"]:
        why.append("no RESULT block")
    if rec["leaks"]:
        why.append("leak " + ",".join(rec["leaks"]))
    if rec.get("readonly_violation") or (task.get("read_only") and rec.get("edited")):
        why.append("edited a read-only tree")
    for s in exp.get("must_not", []):
        if s.lower() in (rec["result"] or "").lower():
            why.append(f"must_not '{s}'")
    if task["type"] == "route" and exp.get("route"):
        # eye (validate after code) and wraith (compile fixes) follow every change per CLAUDE.md's
        # protocol; naming them is right, not noise, so compare the owning specialists only.
        gold = set(exp["route"])
        got = {a.lower() for a in rec["route"]} - (PROCESS_AGENTS - gold)
        if got != gold:
            why.append(f"route {sorted(got)} != {sorted(gold)}")
    if rec.get("check") is not None and rec["check"]["rc"] != 0:
        why.append("implement check failed")
    return why


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--tasks", nargs="+", required=True)
    ap.add_argument("--current-sprint", help="tasks of other sprints count as the regression suite")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    tasks = load_tasks(a.tasks)
    trials = defaultdict(dict)
    for f in glob.glob(os.path.join(a.run, "trials", "*.json")):
        r = json.load(open(f))
        trials[(r["task"], r["rep"])][r["arm"]] = r
    grades = {(g["task"], g["rep"]): g for g in
              (json.load(open(f)) for f in glob.glob(os.path.join(a.run, "grades", "*.json")))}

    per_task = defaultdict(list)
    rows, hard_new, reg_loss, cost = [], [], [], {"A": [], "B": []}
    for (tid, rep), arms in sorted(trials.items()):
        if tid not in tasks or set(arms) != {"A", "B"}:
            continue
        t = tasks[tid]
        gold = t.get("expected", {}).get("files", [])
        h = {k: hard(arms[k], t) for k in "AB"}
        fs = {k: f1(arms[k]["files"], gold) for k in "AB"}
        for k in "AB":
            if arms[k]["cost_usd"] is not None:
                cost[k].append(arms[k]["cost_usd"])
        g = grades.get((tid, rep))
        if bool(h["A"]) != bool(h["B"]):
            w = "B" if h["A"] else "A"
        elif h["A"]:
            w = "tie"
        elif g:
            bad = {k: len(g["false_claims"][k]) + len(g["protocol_violations"][k]) for k in "AB"}
            if g["rubric"]["A"] != g["rubric"]["B"]:
                w = "A" if g["rubric"]["A"] > g["rubric"]["B"] else "B"
            elif bad["A"] != bad["B"]:
                w = "A" if bad["A"] < bad["B"] else "B"
            elif None not in fs.values() and abs(fs["A"] - fs["B"]) > 0.1:
                w = "A" if fs["A"] > fs["B"] else "B"
            else:
                w = "tie"
        else:
            w = "ungraded"
        per_task[tid].append((w, h))
        rows.append(dict(task=tid, rep=rep, type=t["type"], agent=arms["A"]["agent"], winner=w,
                         hard_A=h["A"], hard_B=h["B"], f1_A=fs["A"], f1_B=fs["B"],
                         rubric_A=g and g["rubric"]["A"], rubric_B=g and g["rubric"]["B"],
                         rubric_max=g and g["rubric_max"], pref=g and g["preference"], cost_A=arms["A"]["cost_usd"], cost_B=arms["B"]["cost_usd"]))

    verdicts = {}
    for tid, reps in per_task.items():
        c = Counter(w for w, _ in reps)
        verdicts[tid] = "A" if c["A"] > c["B"] else "B" if c["B"] > c["A"] else "tie"
        if any(hh["B"] and not hh["A"] for _, hh in reps):
            hard_new.append(tid)
        sprint = tasks[tid].get("sprint")
        if a.current_sprint and sprint != a.current_sprint and verdicts[tid] == "A" and \
                any(hh["B"] and not hh["A"] for _, hh in reps):
            reg_loss.append(tid)
    W = Counter(verdicts.values())
    n = len(verdicts)
    need = max(2, math.ceil(0.2 * n))
    med = {k: (statistics.median(v) if v else None) for k, v in cost.items()}
    decided = W["A"] + W["B"]
    cost_ok = not (med["A"] and med["B"]) or med["B"] <= 1.25 * med["A"] or (decided and W["B"] / decided >= 2 / 3)
    checks = {f"margin wins(B)-wins(A)={W['B'] - W['A']} >= {need}": W["B"] - W["A"] >= need,
              f"no new hard failures {hard_new or ''}": not hard_new,
              f"no regression-suite losses {reg_loss or ''}": not reg_loss,
              f"cost median A=${med['A'] and round(med['A'], 3)} B=${med['B'] and round(med['B'], 3)}": cost_ok}
    accept = all(checks.values())
    if a.json:
        json.dump(dict(rows=rows, verdicts=verdicts, wins=dict(W), checks=checks, accept=accept), sys.stdout, indent=1)
        return
    print("| task | rep | type | agent | winner | hard A | hard B | F1 A | F1 B | rubric A | rubric B | grader pref | $A | $B |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    fmt = lambda v: "-" if v is None else (f"{v:.2f}" if isinstance(v, float) else str(v))
    for r in rows:
        print(f"| {r['task']} | {r['rep']} | {r['type']} | {r['agent']} | **{r['winner']}** | "
              f"{'; '.join(r['hard_A']) or '-'} | {'; '.join(r['hard_B']) or '-'} | {fmt(r['f1_A'])} | {fmt(r['f1_B'])} | "
              f"{fmt(r['rubric_A'])}/{fmt(r['rubric_max'])} | {fmt(r['rubric_B'])}/{fmt(r['rubric_max'])} | {fmt(r['pref'])} | "
              f"{fmt(r['cost_A'])} | {fmt(r['cost_B'])} |")
    print(f"\nTasks: {n}  wins A={W['A']} B={W['B']} tie={W['tie']}")
    for k, v in checks.items():
        print(f"- [{'x' if v else ' '}] {k}")
    print(f"\nVERDICT: {'ACCEPT B' if accept else 'KEEP A'}")


if __name__ == "__main__":
    main()
