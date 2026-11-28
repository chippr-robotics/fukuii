#!/usr/bin/env python3
"""Blind pairwise grade of two trials of the same task and rep.

  python3 -I .claude/skills/fukuii-sprint-retro/scripts/grade.py \
      --task <task.json> --run <run dir> [--rep 1] [--model opus]

Reads <run>/trials/<id>.A.<rep>.json and .B.<rep>.json, shows them to a grader
as X and Y in an order fixed by a hash of the task id and rep (so the grader
cannot learn that A is the baseline), and writes <run>/grades/<id>.<rep>.json
with the scores mapped back to A and B. The grader sees the gold answer; the
trials never did.
"""
import argparse, hashlib, json, os, subprocess, sys

SCHEMA = {
    "type": "object",
    "properties": {
        "rubric": {"type": "array", "items": {"type": "object", "properties": {
            "item": {"type": "string"}, "x": {"type": "integer", "minimum": 0, "maximum": 2},
            "y": {"type": "integer", "minimum": 0, "maximum": 2}}, "required": ["item", "x", "y"]}},
        "false_claims_x": {"type": "array", "items": {"type": "string"}},
        "false_claims_y": {"type": "array", "items": {"type": "string"}},
        "protocol_violations_x": {"type": "array", "items": {"type": "string"}},
        "protocol_violations_y": {"type": "array", "items": {"type": "string"}},
        "preference": {"enum": ["X", "Y", "tie"]},
        "reason": {"type": "string"},
    },
    "required": ["rubric", "false_claims_x", "false_claims_y", "protocol_violations_x",
                 "protocol_violations_y", "preference", "reason"],
}

PROMPT = """You grade two answers to the same engineering task on the fukuii codebase
(a Scala 3 Ethereum Classic / Ethereum execution client). You know what the real
fix or change was; the two answers did not. Grade each answer on its own merits.
Shorter is not worse, longer is not better; an answer that claims something false
about the code or the chain is worse than one that says it does not know.

## Task given to both
{prompt}

## What actually happened (gold, unseen by the answers)
{gold}
Gold files: {files}

## Rubric (score each item 0 = missed, 1 = partly, 2 = fully, for X and for Y)
{rubric}

## Protocol the agent must follow (a breach is a protocol violation)
{protocol}

## Answer X
{x}

## Answer Y
{y}

Return the rubric scores, every false claim each answer makes, every protocol
violation, and which answer you would rather have had in the real session
(\"tie\" only if you truly cannot choose)."""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", required=True)
    ap.add_argument("--run", required=True)
    ap.add_argument("--rep", type=int, default=1)
    ap.add_argument("--model", default="opus")
    a = ap.parse_args()
    task = json.load(open(a.task))
    tid, exp = task["id"], task.get("expected", {})
    rec = {arm: json.load(open(os.path.join(a.run, "trials", f"{tid}.{arm}.{a.rep}.json"))) for arm in "AB"}
    swap = int(hashlib.sha256(f"{tid}:{a.rep}".encode()).hexdigest(), 16) % 2 == 1
    x, y = ("B", "A") if swap else ("A", "B")
    body = lambda r: (r["result"] or "(no answer: " + str(r["subtype"]) + ")")[-20000:]
    prompt = PROMPT.format(prompt=task["prompt"], gold=exp.get("gold_summary", "(none)"),
                           files=", ".join(exp.get("files", [])) or "-",
                           rubric="\n".join(f"- {r}" for r in task.get("rubric", [])),
                           protocol="\n".join(f"- {p}" for p in task.get("protocol", [])) or "- none beyond the task",
                           x=body(rec[x]), y=body(rec[y]))
    r = subprocess.run(["claude", "-p", prompt, "--tools", "", "--strict-mcp-config",
                        "--no-session-persistence", "--output-format", "json", "--model", a.model,
                        "--json-schema", json.dumps(SCHEMA)], capture_output=True, text=True, timeout=900)
    try:
        g = json.loads(r.stdout)["structured_output"]
    except Exception:
        sys.exit("grader failed: " + r.stdout[-2000:] + r.stderr[-2000:])
    m = {"X": x, "Y": y, "tie": "tie"}
    out = dict(task=tid, rep=a.rep, x_is=x, grader_model=a.model,
               rubric={x: sum(i["x"] for i in g["rubric"]), y: sum(i["y"] for i in g["rubric"])},
               rubric_max=2 * len(g["rubric"]),
               false_claims={x: g["false_claims_x"], y: g["false_claims_y"]},
               protocol_violations={x: g["protocol_violations_x"], y: g["protocol_violations_y"]},
               preference=m[g["preference"]], reason=g["reason"], raw=g)
    os.makedirs(os.path.join(a.run, "grades"), exist_ok=True)
    json.dump(out, open(os.path.join(a.run, "grades", f"{tid}.{a.rep}.json"), "w"), indent=1)
    print(f"{tid}.{a.rep}: prefers {out['preference']}  rubric A={out['rubric']['A']} B={out['rubric']['B']}/{out['rubric_max']}")


if __name__ == "__main__":
    main()
