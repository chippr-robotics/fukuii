# Agent evals

The project subagents (`.claude/agents/*.md`) are tested against the work the
project actually shipped. Each sprint retro (`/fukuii-sprint-retro <version>`,
run at every minor/patch release) adds a suite of tasks drawn from that
sprint's PRs, and every later agent-file edit is A/B tested against all the
suites, so the files only change in ways that keep earlier sprints' work done
right.

```
.claude/evals/
  README.md          this file: layout and the task schema
  ledger.tsv         one row per retro; to_sha is where the next retro starts
  suites/<version>/
    retro.md         the retro: findings, proposals, A/B tables, verdicts
    tasks/<id>.json  the tasks, gold answers included
  runs/              (gitignored) snapshots, transcripts, trials, grades
```

The procedure, the scripts and the decision rule are in
`.claude/skills/fukuii-sprint-retro/SKILL.md`.

## Gold answers live here — keep them out of the trials

`run_trial.py` removes `.claude/evals/` from every snapshot and gives it no git
history. Do not copy a task's gold (its files, its cause, its fix) into an
agent file, a protocol or CLAUDE.md: that turns the suite into a test of
recall. A retro reviewer rejects such an edit.

## Task schema

```jsonc
{
  "id": "diagnose-1733",              // <type>-<pr>; unique across suites with the sprint dir
  "sprint": "0.12.1",
  "type": "diagnose",                 // diagnose | impact | review | route | implement
  "agent": "forge",                   // the agent under test (route: "router")
  "chain": "both",                    // ETC | ETH | both | n/a
  "source_prs": [1733],
  "base_sha": "<the PR's parent, full sha>",
  "read_only": true,                  // any edit to the tree is a hard failure
  "prompt": "What the user knew at the time, in their words. No PR/issue/backlog ids, no fix identifiers.",
  "expected": {
    "files": ["src/main/scala/.../BlockPreparator.scala"],  // gold FILES, for F1
    "gold_summary": "What the real fix found and changed, for the grader only.",
    "must_not": ["forTimestamp"],     // literal strings that disqualify an answer
    "route": ["forge", "beacon"],     // route tasks: the exact set
    "review_sha": "<sha>",            // review tasks: the change applied uncommitted on base_sha
    "tests_from_sha": "<sha>",        // implement tasks: where the PR's tests come from
    "test_paths": ["src/test/scala/.../FooSpec.scala"],
    "check_cmd": "sbt 'testOnly *FooSpec'",
    "check_timeout_s": 3600
  },
  "rubric": ["3-6 concrete, checkable items, each scored 0/1/2 by the blind grader"],
  "protocol": ["agent-file / CLAUDE.md rules the trial must keep"],
  "leak_markers": ["#1733", "0b1ca103e", "calculateUpfrontCost"],
  "budget_usd": 3,
  "timeout_s": 2400
}
```

Every trial ends its answer with a `=== RESULT ===` block (`FILES:`, `ROUTE:`,
`VERDICT:`); `run_trial.py` appends that contract to the prompt.
