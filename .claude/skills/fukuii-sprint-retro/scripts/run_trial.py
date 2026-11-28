#!/usr/bin/env python3
"""Run one arm of one eval task: an agent definition, headless, in a
history-free snapshot of the repo at the task's base commit.

  python3 -I .claude/skills/fukuii-sprint-retro/scripts/run_trial.py \
      --task .claude/evals/suites/0.12.1/tasks/diagnose-1733.json \
      --agent-file .claude/evals/runs/0.12.1/r1/A/forge.md \
      --arm A --rep 1 --out .claude/evals/runs/0.12.1/r1

Writes <out>/trials/<task-id>.<arm>.<rep>.json (the record score.py reads) and
<out>/transcripts/<task-id>.<arm>.<rep>.jsonl. The snapshot carries no git
history, so the agent cannot read the fix out of a later commit, and
.claude/evals/ is removed from it, so it cannot read a gold answer either.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, time

CONTRACT = """

---
When you are done, end your reply with this block, exactly this shape:

=== RESULT ===
FILES: <comma-separated repo-relative paths: the files at the heart of the answer (the defect's site, the files a change must touch, or the files you changed)>
ROUTE: <comma-separated agent names, only when the task asks which agent(s) should take the work; else "-">
VERDICT: <one line>
"""

REVIEW_PATHS = [".", ":(exclude)specs", ":(exclude)docs", ":(exclude)*.md", ":(exclude).claude",
                ":(exclude).specify", ":(exclude).local"]

ENV = """

# Trial environment
You are running as the `{name}` subagent on a task handed to you by the main
session. The working directory is a snapshot of the fukuii repository; it has
no git history beyond one snapshot commit. Work the task as you would in a real
session, within the tools you were given.
"""


def sh(cmd, cwd=None, inp=None, timeout=None):
    return subprocess.run(cmd, cwd=cwd, input=inp, capture_output=True, timeout=timeout,
                          shell=isinstance(cmd, str))


def frontmatter(text):
    m = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if not m:
        sys.exit("agent file has no frontmatter")
    fm = {}
    for line in m.group(1).splitlines():
        kv = re.match(r"^(\w[\w-]*):\s*(.*)$", line)
        if kv:
            fm[kv.group(1)] = kv.group(2).strip()
    return fm, text[m.end():]


def snapshot(repo, sha, dest):
    os.makedirs(dest)
    arch = sh(["git", "-C", repo, "archive", sha])
    if arch.returncode:
        sys.exit(arch.stderr.decode())
    sh(["tar", "-x", "-C", dest], inp=arch.stdout)
    shutil.rmtree(os.path.join(dest, ".claude", "evals"), ignore_errors=True)
    g = ["git", "-c", "user.name=trial", "-c", "user.email=trial@localhost"]
    sh(g + ["init", "-q"], cwd=dest)
    sh(g + ["add", "-A"], cwd=dest)
    sh(g + ["commit", "-q", "-m", "snapshot"], cwd=dest)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", required=True)
    ap.add_argument("--agent-file", required=True)
    ap.add_argument("--arm", required=True)
    ap.add_argument("--rep", type=int, default=1)
    ap.add_argument("--out", required=True)
    ap.add_argument("--model", help="override the agent file's model")
    ap.add_argument("--keep", action="store_true", help="keep the snapshot")
    a = ap.parse_args()

    repo = sh(["git", "rev-parse", "--show-toplevel"]).stdout.decode().strip()
    task = json.load(open(a.task))
    exp = task.get("expected", {})
    agent_text = open(a.agent_file).read()
    fm, body = frontmatter(agent_text)
    name = fm.get("name", os.path.basename(a.agent_file)[:-3])
    tools = [t.strip() for t in fm.get("tools", "Read, Grep, Glob").split(",") if t.strip()]
    model = a.model or fm.get("model", "sonnet")

    key = f"{task['id']}.{a.arm}.{a.rep}"
    out = os.path.abspath(a.out)
    for d in ("trials", "transcripts", "work"):
        os.makedirs(os.path.join(out, d), exist_ok=True)
    work = os.path.join(out, "work", key)
    shutil.rmtree(work, ignore_errors=True)
    snapshot(repo, task["base_sha"], work)

    if task["type"] == "review":  # the change under review sits uncommitted in the tree
        # review_sha may be a PR's pre-review head, absent from a fresh clone: fetch it by its ref
        if sh(["git", "-C", repo, "cat-file", "-e", exp["review_sha"] + "^{commit}"]).returncode and exp.get("review_ref"):
            sh(["git", "-C", repo, "fetch", "-q", "origin", exp["review_ref"]])
        # Code only by default: a PR's spec/plan/docs edits often state the very findings under test
        # (seen in the 0.12.1 retro). A task can widen this with expected.review_paths.
        paths = exp.get("review_paths", REVIEW_PATHS)
        d = sh(["git", "-C", repo, "diff", "--binary", task["base_sha"], exp["review_sha"], "--", *paths])
        ap_ = sh(["git", "apply", "--whitespace=nowarn"], cwd=work, inp=d.stdout)
        if ap_.returncode:
            sys.exit("review diff did not apply: " + ap_.stderr.decode())

    # A leak marker already in what the trial is given would flag an honest trial: drop it, and say so.
    inert = [m for m in task.get("leak_markers", [])
             if m and sh(["git", "grep", "-q", "-F", "--untracked", m], cwd=work).returncode == 0]
    markers = [m for m in task.get("leak_markers", []) if m not in inert]

    def tree_state():  # what the agent found vs what it left: any difference is an edit
        st = sh(["git", "status", "--porcelain", "--untracked-files=all"], cwd=work).stdout
        df = sh(["git", "diff", "--binary"], cwd=work).stdout
        return hashlib.sha256(st + df).hexdigest()
    before = tree_state()

    sp = os.path.join(out, "work", key + ".system.md")
    open(sp, "w").write(body + ENV.format(name=name))
    cmd = ["claude", "-p", task["prompt"] + CONTRACT, "--system-prompt-file", sp,
           "--tools", *tools, "--allowedTools", *tools, "--model", model,
           "--strict-mcp-config", "--no-session-persistence",
           "--output-format", "stream-json", "--verbose",
           "--max-budget-usd", str(task.get("budget_usd", 3))]
    t0 = time.time()
    tpath = os.path.join(out, "transcripts", key + ".jsonl")
    with open(tpath, "w") as tf:
        rc = subprocess.run(cmd, cwd=work, stdout=tf, stderr=subprocess.DEVNULL,
                            timeout=task.get("timeout_s", 2400)).returncode
    events = [json.loads(l) for l in open(tpath) if l.strip().startswith("{")]
    res = next((e for e in reversed(events) if e.get("type") == "result"), {})
    text = res.get("result") or ""
    blk = text.rsplit("=== RESULT ===", 1)[-1] if "=== RESULT ===" in text else ""
    field = lambda f: [x.strip().strip("`") for x in (re.search(rf"^{f}:\s*(.*)$", blk, re.M) or [None, ""])[1].split(",")
                       if x.strip() and x.strip() != "-"]

    tool_inputs = json.dumps([c.get("input") for e in events if e.get("type") == "assistant"
                              for c in e.get("message", {}).get("content", []) if c.get("type") == "tool_use"])
    leaks = [p for p in markers if p in tool_inputs]  # what it searched or read, not what it proposes
    leaks += re.findall(r"github\.com/[^\"\s]*/pull/\d+", tool_inputs)

    edited = tree_state() != before
    changed = sh(["git", "status", "--porcelain", "--untracked-files=all"], cwd=work).stdout.decode().splitlines()
    readonly = "Edit" not in tools and "Write" not in tools

    check = None
    if task["type"] == "implement" and exp.get("check_cmd"):
        for p in exp.get("test_paths", []):  # the PR's own tests, dropped in after the agent is done
            blob = sh(["git", "-C", repo, "show", f"{exp['tests_from_sha']}:{p}"])
            os.makedirs(os.path.dirname(os.path.join(work, p)), exist_ok=True)
            open(os.path.join(work, p), "wb").write(blob.stdout)
        try:
            c = sh(exp["check_cmd"], cwd=work, timeout=exp.get("check_timeout_s", 3600))
            check = dict(rc=c.returncode, tail=c.stdout.decode(errors="replace")[-3000:])
        except subprocess.TimeoutExpired:
            check = dict(rc=-1, tail="check timed out")

    rec = dict(task=task["id"], type=task["type"], arm=a.arm, rep=a.rep, agent=name, model=model,
               agent_sha256=hashlib.sha256(agent_text.encode()).hexdigest()[:16],
               rc=rc, subtype=res.get("subtype"), cost_usd=res.get("total_cost_usd"),
               turns=res.get("num_turns"), wall_s=round(time.time() - t0),
               result=text, files=field("FILES"), route=field("ROUTE"),
               verdict=(re.search(r"^VERDICT:\s*(.*)$", blk, re.M) or [None, ""])[1],
               contract_ok=bool(blk), leaks=sorted(set(leaks)), inert_markers=inert, worktree_changes=changed[:200],
               edited=edited, readonly_violation=readonly and edited,
               check=check)
    json.dump(rec, open(os.path.join(out, "trials", key + ".json"), "w"), indent=1)
    if not a.keep:
        shutil.rmtree(work, ignore_errors=True)
    print(f"{key}: subtype={rec['subtype']} cost=${rec['cost_usd']} files={rec['files']} leaks={rec['leaks']} "
          f"check={None if check is None else check['rc']}")


if __name__ == "__main__":
    main()
