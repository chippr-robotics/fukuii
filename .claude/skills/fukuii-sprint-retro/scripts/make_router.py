#!/usr/bin/env python3
"""Build the pseudo-agent that `route` tasks run against: the main session's
routing rules (CLAUDE.md "Specialist subagents" section) plus the
`description:` of every agent file in a directory. Arm B of a description edit
is built from the candidate directory.

  python3 -I .claude/skills/fukuii-sprint-retro/scripts/make_router.py \
      --agents .claude/evals/runs/0.12.1/r1/B > .claude/evals/runs/0.12.1/r1/B/router.md
"""
import argparse, glob, os, re

ap = argparse.ArgumentParser()
ap.add_argument("--agents", required=True, help="directory of agent .md files")
ap.add_argument("--claude-md", default="CLAUDE.md")
a = ap.parse_args()

text = open(a.claude_md).read()
m = re.search(r"^## Specialist subagents\n(.*?)(?=^## )", text, re.S | re.M)
rules = m.group(1).strip() if m else ""
descs = []
for f in sorted(glob.glob(os.path.join(a.agents, "*.md"))):
    if os.path.basename(f) in ("REFERENCES.md", "router.md"):
        continue
    fm = re.match(r"^---\n(.*?)\n---\n", open(f).read(), re.S)
    if not fm:
        continue
    name = re.search(r"^name:\s*(\S+)", fm.group(1), re.M)
    d = re.search(r"^description:\s*>-?\n((?:[ \t]+.*\n?)+)", fm.group(1) + "\n", re.M) or \
        re.search(r"^description:\s*(.+)$", fm.group(1), re.M)
    descs.append(f"### {name.group(1) if name else os.path.basename(f)[:-3]}\n"
                 + " ".join(l.strip() for l in d.group(1).splitlines()))
print(f"""---
name: router
description: The main session's delegation decision, for route tasks.
tools: Read, Grep, Glob
model: sonnet
---

You are the main session of a Claude Code thread on fukuii, deciding which
project subagent(s) to delegate a piece of work to, and in what order. You may
read the repository to understand the work; you do not do the work.

## Routing rules (from CLAUDE.md)

{rules}

## The subagents, as their descriptions present them

{chr(10).join(descs)}

Answer with the agent(s) to use, in order, and one line on why each.
""")
