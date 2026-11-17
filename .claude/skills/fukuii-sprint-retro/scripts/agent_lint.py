#!/usr/bin/env python3
"""Find stale references in the agent files: paths and Scala files they name
that no longer exist, sbt aliases build.sbt no longer defines, and agents they
hand off to that do not exist. Mechanical input to the retro's proposals.

  python3 -I .claude/skills/fukuii-sprint-retro/scripts/agent_lint.py [.claude/agents/*.md]
"""
import glob, os, re, subprocess, sys

files = sys.argv[1:] or sorted(glob.glob(".claude/agents/*.md"))
tracked = subprocess.run(["git", "ls-files"], capture_output=True, text=True).stdout.split()
names = {os.path.basename(f) for f in tracked}
agents = {os.path.basename(f)[:-3] for f in glob.glob(".claude/agents/*.md")} - {"REFERENCES"}
build = open("build.sbt").read() if os.path.exists("build.sbt") else ""
aliases = set(re.findall(r'addCommandAlias\(\s*"([\w-]+)"', build))
PATH = re.compile(r"`((?:src|docs|scripts|specs|\.claude|\.specify|project|ops|bytes|crypto|rlp|scalanet)/[\w./*{}-]+)`")
SCALA = re.compile(r"\b([A-Z]\w+\.scala)\b")
SBT = re.compile(r"\bsbt\s+\"?([a-z][\w-]+)")
HANDOFF = re.compile(r"`(\w+)`\s+(?:agent|for\b)|\b(?:delegate|hand off|defer)s?\s+(?:to\s+)?`(\w+)`", re.I)
# gitignored, per-machine trees: their absence in a clone is not staleness
LOCAL_ONLY = (".claude/repo-references", ".claude/agent-protocols/working-docs", ".local")
bad = 0
for f in files:
    if os.path.basename(f) == "REFERENCES.md":
        continue
    text = open(f).read()
    issues = []
    for p in sorted(set(PATH.findall(text))):
        if any(c in p for c in "*{}") or p.startswith(LOCAL_ONLY):
            continue
        q = p.rstrip("/.")
        if not os.path.exists(q) and not any(t.startswith(q) for t in tracked):
            issues.append(f"missing path  {p}")
    for s in sorted(set(SCALA.findall(text))):
        if s not in names:
            issues.append(f"missing file  {s}  (or a library type)")
    builtin = {"compile", "test", "testOnly", "testQuick", "clean", "update", "console", "run", "scalafmt",
               "scalafmtAll", "scalafmtCheck", "scalafmtCheckAll", "scalafixAll", "dependencyUpdates", "assembly",
               "dist", "reload", "projects", "evicted", "coverage", "coverageReport"}
    for s in sorted(set(SBT.findall(text))):
        if s not in aliases and s not in builtin:
            issues.append(f"sbt alias?    {s}")
    for m in HANDOFF.finditer(text):
        a = (m.group(1) or m.group(2)).lower()
        if a.isalpha() and a not in agents and a in {"forge", "beacon", "eye", "wraith", "herald", "mithril", "prism",
                                                      "loom", "vault", "conduit", "flow", "sentinel", "keeper"} - agents:
            issues.append(f"unknown agent {a}")
    if issues:
        bad += len(issues)
        print(f"## {f}")
        for i in issues:
            print(f"  - {i}")
print(f"\n{bad} possible stale reference(s) in {len(files)} file(s)")
