---
name: fukuii-sprint-retro
description: >-
  Sprint retrospective that improves the project subagents (.claude/agents/*.md)
  from the work a release actually shipped. Reviews every PR merged since the
  last retro, turns the sprint's real problems into an eval suite of agent tasks
  (diagnose, impact, review, route, implement) with gold answers taken from what
  merged, proposes evidence-backed edits to the agent files, and A/B tests each
  candidate against the baseline on the new suite plus every earlier sprint's
  suite, keeping only the edits that win. Use at every minor/patch release
  (e.g. when 0.12.1 is promoted to main), or when asked for a sprint retro,
  an agent-file review, or agent evals.
disable-model-invocation: true
user-invokable: true
argument-hint: "<release version, e.g. 0.12.1> [--from REF] [--to REF] [--budget USD]"
---

# fukuii-sprint-retro

Run after a release is cut. Output: a tracked eval suite and retro for the
sprint, and a PR to `staging` carrying only the agent-file edits that won a
blind A/B test.

```
1 REVIEW ──▶ 2 GENERALIZE ──▶ 3 PROBE + PROPOSE ──▶ 4 A/B ──▶ 5 ITERATE ──▶ 6 LAND
  PRs,         tasks + gold      baseline on the        A=HEAD    winner is the    retro, suite,
  lineage,     answers           new suite; edits       B=cand.   new baseline;    ledger, agent
  lint                           with evidence          blind     ≤3 rounds        edits, PR
```

Everything runs from the repo root. Scripts are in
`.claude/skills/fukuii-sprint-retro/scripts/` (call them with `python3 -I`).
Layout and the suite format: `.claude/evals/README.md`.

| Path | Tracked | Holds |
|---|---|---|
| `.claude/evals/suites/<ver>/tasks/*.json` | yes | the sprint's tasks, with gold answers |
| `.claude/evals/suites/<ver>/retro.md` | yes | the retro: findings, proposals, A/B tables, verdicts |
| `.claude/evals/ledger.tsv` | yes | one row per retro; its `to_sha` is the next retro's `--from` |
| `.claude/evals/runs/<ver>/` | no | snapshots, transcripts, trials, grades, candidate files |

## Phase 0 — scope and budget (ask before spending)

1. `VER=<version>`. Range: `--from` defaults to the last ledger row's `to_sha`,
   else to the staging commit that bumped `version.sbt` to the version on
   `origin/main`; `--to` defaults to `origin/staging`. `git fetch origin main staging` first.
2. Default caps, overridable by the user: ≤ 15 new tasks (≤ 3 `implement`),
   ≤ 10 regression tasks per agent from earlier suites, 2 reps per arm,
   ≤ 3 improvement rounds, `budget_usd` 3 per trial, grader `opus`.
3. Estimate before Phase 3: `tasks × 2 arms × reps × (median trial ≈ $1–3)
   + grades (≈ $0.3 each)`, per round. State the figure and get the user's
   go-ahead. Trials are headless `claude -p` runs under whatever auth the
   session has (`claude auth status`). On a subscription (`oauth_token`),
   `total_cost_usd` and `--max-budget-usd` are the CLI's API-price *estimate*
   of the tokens used: nothing is billed, but the runs draw down the plan's
   usage limits (the 5-hour window and the weekly cap), which a large round
   can exhaust for the user's other sessions. On an API key they are real
   charges. Either way the dollar figure is the unit the caps are set in.

## Phase 1 — review the sprint's PRs

```bash
S=.claude/skills/fukuii-sprint-retro/scripts
python3 -I $S/sprint_prs.py --json > .claude/evals/runs/$VER/prs.json   # mkdir -p first
python3 -I $S/sprint_prs.py | less                                       # the table
python3 -I $S/agent_lint.py                                              # stale refs in agent files
```

`sprint_prs.py` gives, per PR: kind, chain (ETC/ETH/both), the agents that own
the paths it touched, size, tests added, and **`fixes_lines_of`** — for a fix
PR, the earlier sprint PRs whose `src/main` code lines it deleted or rewrote
(`git blame -w -M -C` at its parent, comments and blank lines skipped). A pair
is a **candidate**, not a defect. Triage each one before using it: **DEFECT**
(the originator shipped a bug the fix repairs), **EXTENSION** (a planned next
step — "PR 2 of N", a deferred item the originator's body names), or
**PRE-EXISTING** (the fix repairs behaviour older than the sprint; the
originator only moved or touched the code). In the 0.12.1 retro, every pair
checked first was EXTENSION or PRE-EXISTING. Only DEFECT pairs seed `review`
tasks; the fix PRs of the others still make good `diagnose` tasks.

Read the work, don't skim titles:
- The squash bodies are full PR descriptions: `git log -1 --format=%B <sha>`.
- For lineage originators, fix PRs, reverts, and the 10 largest PRs, read the
  review threads with `mcp__github__pull_request_read` (`get_reviews`,
  `get_review_comments`) — a reviewer's correction is a rule an agent missed.
- With > 40 PRs, split them into clusters by spec/area and hand each cluster to
  an `Explore` agent (≤ 4 at once). Give each the PR shas and ask for the
  findings below as a table, not prose.

Write the first half of `suites/$VER/retro.md`:

1. **At a glance** — range, PR count by kind and chain, agent load.
2. **Shipped defects** — each lineage pair: what the originating PR got wrong,
   how it was found (test, review, live node, follow-up), which agent owned the
   area, and whether that agent's file has a rule that should have caught it.
3. **Review corrections** — recurring reviewer asks, grouped (≥ 2 = a pattern).
4. **Roster gaps** — feat/fix PRs whose paths no specialist owns (the script
   counts them); new subsystems the sprint added (e.g. a new package or spec)
   that no agent file mentions.
5. **Agent-file staleness** — `agent_lint.py` output, triaged (a library type
   is not stale; a renamed project file is).
6. **What went right** — practices visible in the clean PRs worth writing down
   for the agent that did the work.

## Phase 2 — generalize the sprint into tasks

A task is a JSON file in `suites/$VER/tasks/`. Schema and an example:
`.claude/evals/README.md`. One task tests one agent on one capability, at the
commit **before** the work (`base_sha` = the PR's parent), with a gold answer
from what merged.

Pick tasks stratified, not by convenience:

| Type | Source | The agent is asked to | Gold |
|---|---|---|---|
| `review` | DEFECT lineage originators; PRs whose reviewers found blockers | review the uncommitted change: the originator's diff, or a PR's **pre-review head** (`git fetch origin pull/N/head`; base = its merge-base), since the squash already holds the review fixes | the defect the later fix or the reviewer found |
| `diagnose` | fix PRs | find the root cause of a symptom, read-only | the fix's files and cause |
| `impact` | feat PRs, consensus first | produce the impact analysis before implementing | the files the PR touched; the invariants it had to keep |
| `route` | a sample of PRs, all areas | name the agent(s) to take the work, given only the agent descriptions | the owning agents |
| `implement` | ≤ 3 small PRs whose new tests stand alone | make the change | the PR's own tests pass (`check_cmd`) |

Cover every agent that owned ≥ 3 sprint PRs, and both chains where the sprint
touched both. `route` tasks are cheap and the only test of a `description:`
edit; include at least 5.

**Generalizing** — the prompt states what the user knew at the time, in their
words: the symptom, the log line, the goal, the spec section. It never contains
the PR or issue number, a backlog id, the fix's new identifiers, or the answer's
file list unless the user would have had it. `leak_markers` lists the strings a
trial must not produce or search for (PR number as `#NNNN`, the fix sha's first
9 chars, identifiers the fix introduced). `rubric` has 3–6 concrete, checkable
items ("names the mod-2^256 reduction of the upfront cost before the balance
compare", not "good analysis"). `protocol` lists the agent-file and CLAUDE.md
rules the trial must keep (e.g. `forge`: impact analysis before any edit;
`eye`: no source edits; consensus: `forBlock` on ETC paths). `must_not` lists
literal strings whose presence in the answer is disqualifying.

`run_trial.py` applies a review diff **code only** (`specs/`, `docs/`, `*.md`, `.claude/` excluded,
widen with `expected.review_paths`): a PR's plan or spec edits often record the very review findings
under test. It also drops any leak marker already present in what the trial is given (reported as
`inert_markers`), but pick markers that only the fix introduced.

**Check each task with a second agent** (not the author): give it the task file
and the base snapshot path and ask (a) is it answerable from the snapshot
alone, (b) does the prompt give the answer away, (c) is the gold actually what
merged. Fix or drop failures. A task that cannot be checked is dropped.

## Phase 3 — probe the baseline, then propose edits

Probe: run arm A only (current `HEAD` agent files) on the new tasks, 1 rep.

```bash
R=.claude/evals/runs/$VER/r1; mkdir -p $R/A $R/B
for f in .claude/agents/*.md; do git show HEAD:$f > $R/A/$(basename $f); done
# one trial; run several in parallel with xargs -P 4
python3 -I $S/run_trial.py --task <task.json> --agent-file $R/A/<agent>.md --arm A --rep 1 --out $R
```

For `route` tasks the agent file is irrelevant: run them with the router
(`--agent-file` pointing at a file whose body is the main session's routing
rules — the CLAUDE.md "Specialist subagents" table plus every agent's
`description`), and regenerate it for arm B from the candidate descriptions.

**Keep the suite discriminating.** A task the baseline aces on the probe
(full rubric, FILES F1 ≥ 0.8) cannot show an improvement; keep at most a third
of the suite as such anchors (they still guard against regressions) and spend
the rest on tasks the baseline misses or half-solves. If the probe aces nearly
everything, the tasks are too easy or leak: harden the prompts (less
direction, the symptom only) before proposing any edit. `--model sonnet` is fine
for a cheap probe; the A/B runs each agent on its own `model:`.

Read the failing transcripts (`$R/transcripts/`), then write proposals in
`retro.md` § Proposals. Each proposal:

- **Agent / section** and the **minimal diff** (unified diff in the retro).
- **Evidence**: the PRs, lineage pairs, review comments, or probe failures it
  answers — at least one, two for a new standing rule.
- **Hypothesis**: the behaviour it should change, and the task ids it should
  flip.

Rules for an edit:
- Generalize. A rule, a check, a grep, a pointer to a file — never a fact
  about one PR, and **never the gold answer of a task** (that tests recall, not
  skill; reviewers reject it).
- Remove as well as add: stale references from the lint, rules the sprint
  shows nobody needs. An edit that adds > 40 lines must delete something.
- A `description:` edit changes routing for every session: it needs `route`
  tasks in its A/B.
- Consensus agents (`forge`, `beacon`) keep their hard-stop protocol text
  verbatim; propose additions around it.

Bundle a round's edits per agent: one candidate file per agent in `$R/B/`.
Agents without a candidate are not tested this round.

## Phase 4 — A/B test

For each agent with a candidate: the tasks are its tasks in this suite plus up
to 10 of its tasks from earlier suites (`.claude/evals/suites/*/tasks/`, newest
first) — the regression suite, so an edit that fixes this sprint cannot quietly
break the last one.

```bash
# trials: every task × arm × rep. Reuse a probe trial as A rep 1 when its
# agent_sha256 matches the A file (same file, same task).
python3 -I $S/run_trial.py --task T --agent-file $R/A/<agent>.md --arm A --rep N --out $R
python3 -I $S/run_trial.py --task T --agent-file $R/B/<agent>.md --arm B --rep N --out $R
# blind grade every (task, rep) once both arms exist
python3 -I $S/grade.py --task T --run $R --rep N
# verdict
python3 -I $S/score.py --run $R --tasks .claude/evals/suites --current-sprint $VER
```

`score.py` prints the table and `VERDICT: ACCEPT B | KEEP A` with the checks it
applied (margin, no new hard failures, no regression-suite loss, cost). A
(task, rep) is decided by hard failures, then rubric total, then false claims
and protocol violations, then FILES F1; the grader's bare preference is shown
but never decides (between equivalent answers it is a coin flip). The
grader never learns which arm is the baseline; the trials never see gold, git
history, or `.claude/evals/`. A trial that leaks (searches the PR, cites the fix
sha) loses outright — check why before trusting the rest of that run.

Do not overrule the verdict by reading transcripts. If you believe it is wrong,
say why in the retro, add or fix tasks, and rerun.

## Phase 5 — iterate (recursive improvement)

- **ACCEPT B** → B is round N+1's A. Look at the tasks B still loses or fails,
  propose the next edits from those transcripts, and A/B again.
- **KEEP A** → read B's losses: if the rule never fired (the agent ignored it),
  revise its placement or wording once and rerun; if it fired and made things
  worse, drop it. Record dropped proposals under § Rejected with the scores, so
  a later retro does not re-propose them blind.
- Stop at 3 rounds, two KEEP A in a row, or the budget.

## Phase 6 — land

1. Copy each agent's final accepted file from the last round's A/B into
   `.claude/agents/`. `git diff .claude/agents/` must equal the accepted
   proposals and nothing else.
2. Finish `retro.md`: per agent, the rounds, the score tables, accepted and
   rejected proposals, total cost, and open items (tasks no candidate fixed —
   seeds for the next retro or for `CHASE-QUEUE.md`).
3. Append the ledger row (`.claude/evals/ledger.tsv`): version, date, from_sha,
   to_sha, PRs, tasks, accepted agents, rejected count, cost.
4. Commit on a branch, files added individually: the suite, the retro, the
   ledger, the agent files. PR to `staging`, titled
   `retro(<ver>): agent updates from the sprint`, with the verdict table in the
   body. Agent-file edits are not consensus code; the user reviews them.

## Output contract

```
VERDICT     per agent: ACCEPTED (round N) | NO CHANGE | NOT TESTED
EVIDENCE    score tables (wins A/B/tie, hard failures, cost) per agent
TASKS       new tasks by type and agent; regression tasks run
ACTIONS     files written; PR link
NEXT        unfixed failures, roster gaps, stale refs left
VERIFY      ran score.py — result: ACCEPT B | KEEP A, per agent
```

## Limits (say them in the retro)

- A trial approximates a subagent: the agent body is the whole system prompt,
  its `tools:` and `model:` apply, and the snapshot's CLAUDE.md loads as usual.
  The main session's orchestration is not in the loop except in `route` tasks.
- Two reps is a small sample. The margin rule is conservative on purpose; a
  narrow win is KEEP A.
- Gold is what merged, and what merged can be wrong. A task whose gold a later
  sprint overturns is fixed in that sprint's retro, with a note in both.
