# Sprint retro — 0.12.1

**Range**: `a9896f489` (staging bump to 0.11.0, the version on main) → `21cfa13df` (bump to 0.12.1).
141 PRs: 77 feat, 35 fix, 14 docs, 7 test, 6 chore, 2 build. Of 112 feat/fix PRs, by chain:
50 neither, 38 both, 12 ETC, 12 ETH. Work by spec: 018 (unified state layout, Track C) 71, 027 + 029 (TUI) 21,
028 (Ephemery/Hoodi) 6, 021/022/024 2 each.

Sources: `sprint_prs.py` (routing, lineage), every squash body, the review tables in
`specs/018-*/plan.md` and the 027/028/029 `tasks.md` notes, PR bodies via the REST API, and seven
pre-review PR heads fetched by `pull/N/head`.

## 1. At a glance

Path-routed agent load: eye 122, vault 64, beacon 53, forge 52, prism 51, conduit 33, herald 30,
wraith 2; flow, loom and mithril were never routed. Agents named in PR bodies as authors or reviewers:
018 Track C — beacon 61, forge 40, vault 13, conduit 9, herald 3; TUI — prism 15, herald 6, eye 6.
14 feat/fix PRs touch no path any specialist owns.

## 2. Shipped defects (lineage, triaged)

`sprint_prs.py` found 17 fix PRs that rewrote lines of an earlier sprint PR. All were triaged.
Every candidate except two was an **extension** (a planned next step) or **pre-existing** (blame
credited code the originator only moved or re-indented, or TODO scaladoc it left for the follow-up).
That finding changed the tool: blame now runs `-w -M -C` and skips comments, and pairs are
triaged before use.

| Originator → fix | Defect | Found by | Owner | Rule that would have caught it |
|---|---|---|---|---|
| #1853 → #1876 | Fork recovery's pick is bounded by this node's head (`StrictPickBlocks` to `bestKnownBlockNumber`): a heavier but shorter branch never reaches it, so the pick is never answered. A lighter branch, weighed and not stored, is fetched again for ever. | forge after code, rated a **warning** and deferred (path inert until C5a part 10) | forge (no specialist owns `blockchain/sync/regular/`) | None in forge.md: fork choice by TD not height; every wait names what answers it and what ends it; a never-answered wait on the import path is Critical |
| #1870 → #1875 | The branch guard runs only for targets at or above the branch's first block; a pivot refresh backtracks below it with no floor, and `branchServed` fires for any target, so the requirement ends with a WARN instead of the ERROR | forge's later design read; its after-code review missed it | forge | None: a one-sided guard needs every path that produces a value on the other side enumerated, pre-existing callers included |

## 3. Review corrections (what reviewer agents caught)

| Category | Findings / PRs | Caught by | Rule at authoring time |
|---|---|---|---|
| Thread lifecycle (stop, interrupts, fatal errors, start-up order) | ~35 / 16 | prism, beacon, vault, conduit, forge | **Gap.** Concurrency rules cover Pekko actors only |
| Lock scope and hold time | ~30 / 12 | vault, beacon, prism, eye | Partial (prism asks); **gap** in vault.md: bounded hold under import/Engine API locks |
| Unbounded or untrusted input | ~22 / 10 | prism | prism's trust boundary omits terminal replies, files, downloads, peer text in logs |
| Wall-clock tests | ~14 / 8 | prism, eye, vault, forge | prism bans them; testing-protocol.md bans only `Thread.sleep`; prism's own final review re-added a ceiling |
| Tests that never fail or never run | ~45 / 12 | eye, prism, forge, beacon | **Gap**: a new test must fail on the pre-change code and be selected by the gate's filter |
| Error-path logging | ~30 / 9 | prism, beacon, conduit, vault | Fail-loudly holds; **gap** on the inverse: an interrupt at stop is not ERROR |
| Restart state on existing datadirs | ~25 / 12 | vault, beacon | **Gap** in vault.md: marker + repair pass, same-batch state, reopen test |
| Peer credit and blame | ~15 / 9 | prism, forge, herald | **Gap**: herald.md never mentions blame or scoring; herald authored #1782 and prism found its four bugs |
| Doc / CHANGELOG drift | ~25 / 12 | prism, beacon, vault | **Gap** |
| Reference-client parity | ~40 / 11 | forge, beacon | Protocol working: pre- and after-code reads |

Multi-round reviews (#1783, #1784, #1785, #1769, #1817, #1823): every later round caught a
regression the previous round's fix introduced. prism.md says nothing about re-reviews treating fix
commits as new code. eye ran concurrency suites three times, which caught the #1787 race; eye.md does not
say to.

Geth parity (`#1727` series, #1879): conduit's rule names values, types and codes but not error text or
decode strictness, points at Besu for ETC (the sprint chose go-ethereum texts on both chains), and names no
conformance corpus (Hive rpc-compat, execution-apis fixtures). conduit was named in 2 of the 9
eth_simulateV1 PRs.

## 4. Roster gaps

- **Unified state layout** (`db/storage/pathstate/`, 27 new files): vault reviewed 18 of the 68
  code PRs; storage write-path PRs went to beacon alone. vault's description still says "`db/` (50
  files)"; there are 88.
- **`blockchain/sync/`** (148 files, 58 changed): in no agent's scope. `snap-sync.md` routes its rows to
  vault, forge and beacon, but no agent file links it.
- **TUI** (`console/`, 93 files, ~40k lines): no owner; prism reviewed it in 15 PRs.
- **beacon chain facts**: only chain ids 1 and 11155111; Hoodi, Ephemery, Platåberget and Amsterdam are
  missing.
- **conduit / beacon on Engine API auth**: #1765 (WebSocket origin + JWT) went to beacon alone, #1823 to
  conduit and prism alone.
- **flow** was never used though #1765 and #1847 changed WebSocket stream graphs.
- 018 Track C squash bodies record no reviews; the record is only in `plan.md`.

## 5. Agent-file staleness

Real (from `agent_lint.py`, triaged): forge's `extvm/` (removed); conduit's package tree
(`JsonRpcHttpsServer`, `JsonRpcWebsocketServer`, `GraphQLHttpRoute`, `controllers/…` are now
`server/http/{Secure,Insecure}JsonRpcHttpServer`, `JsonRpcWsServer`, services; `mcp/` missing; 97 files);
vault's package tree (`RocksDbConfig`, `NodeCache`, `BlockCache`, `RocksDbBatch`, `WriteAheadLog`,
`BlockStorage` do not exist; its WAL step names them); loom's `ResourceHealthMonitor`.
Fact drift: eye says testEssential takes "<5 min" (24 min) and omits `pp`, `formatCheck` and six tag
aliases; loom's test count 3,519 (7,219) and SSC 5,178 LOC (1,944); herald's "ETH70 (EIP-7706)" (eth/70 is
EIP-7975, eth/71 EIP-8159). No agent links `proposer-import-parity.md`, `snap-sync.md`,
`consensus-change-protocol.md`, `ancestry-not-index.md`, `evm-hot-path.md` or `genesis-json-import.md`.

## 6. What went right

- The consensus-change protocol worked on 018 Track C: forge and beacon read before and after code on
  ~40 and ~60 PRs, and parity findings were caught in review, not on a node.
- Failing-first tests and mutation checks were practised in most fix PRs, though no protocol asks for them.
- eye's repeated runs of concurrency suites caught a race a single run missed.

## Suite

18 tasks (`tasks/`): review 8 (forge 2, prism 2, herald 2, vault 1, beacon 1), diagnose 3 (conduit 2,
vault 1), route 7. Proposals, A/B results and verdicts follow below.

## Baseline probe (round 1, arm A = agent files at `HEAD`)

18 tasks, 1 rep, each agent on its own `model:`; trials cost ≈ $7 at API prices (a subscription
login: plan usage, not billing). No leaks, no edits, every answer kept the RESULT contract.

| Task | Agent | Rubric | Failure mode |
|---|---|---|---|
| diagnose-1735 | conduit | 8/8 | — |
| diagnose-1753 | vault | 8/10 | fixed only the first defect; no bound asked of the retry loop |
| diagnose-1879 | conduit | 7/10 | stated geth behaviour from memory (reference clones absent), wrongly |
| review-1745 | herald | 0/8 | diff-only skim; never compared ingest with the reference; "unchanged" taken as fine |
| review-1769 | vault | 6/10 | never listed other writers of the key; zero edge untested; whole-repair lock hold rated optional |
| review-1782 | herald | 0/10 | checked wiring, not what each recorded outcome means; no abandon/timeout paths traced |
| review-1783 | prism | 6/10 | test files unread; one shutdown path checked; blocker rated should-fix |
| review-1787 | prism | 4/12 | sanitiser's callers and input origin never traced; peer-reachable DoS rated warning |
| review-1823 | beacon | 4/8 | no start-order analysis; default-on exposure not used to re-rate |
| review-1853 | forge | 4/12 | did not ask whether the awaited reply ever comes; height × TD in one order only |
| review-1870 | forge | 10/10 | — (traced every path into the restart) |
| route ×7 | router | 5/7 | WS + Engine JWT sent to conduit alone; peer blame got vault added |

Review/diagnose total: 57/108. Cross-cutting: the lowest scores are 7–9-turn diff-only reads; five
tasks under-rated the gold's lead issue (liveness, untrusted-input DoS, every-run defects).

## Proposals (round 1)

One candidate per agent; the diffs are below. Each rule is general: none names a task's file, function
or fix. Evidence is the probe above plus the Phase 1 categories.

| Agent | Edit | Evidence | Should flip |
|---|---|---|---|
| forge | Critical includes "stops following the chain"; review beyond the diff; a chain-following checklist (TD vs height in every combination, every bounded wait names what answers and ends it, a rejected branch is not re-requested, one-sided guards list every path to the other side); links snap-sync, consensus-change, proposer-import-parity, ancestry-not-index; drops the removed `extvm/` | #1853, #1870 shipped; probe 1853 | review-1853 |
| prism | reads changed tests; follows entry points and input origin; critical for stalls, super-linear work on untrusted input, lost updates, every-run defects; re-review rules; read-modify-write and thread-ownership checks; wall-clock and test-selection checks; trust boundary widened; TUI named in its scope | LIFE/LOCK/INPUT/TIME/TEST categories; probe 1783, 1787 | review-1783, review-1787, route-1744 |
| herald | scope adds peer credit/blame/blacklisting and review; iron rule: peer/DNS data untrusted, reference checks on the whole ingest path; a "When reviewing" section (outcome paths, eviction order, lock cost, pre-existing gaps reported); ETH70/71 EIPs corrected | PEER category; #1782 authored by herald; probe 1745, 1782 | review-1745, review-1782, route-1757 |
| vault | scope adds the unified state layout and every store write; package tree replaced (six named files did not exist); rule 6 corrected to children-before-parent (spec 022); content-addressed reads verify their key; every writer under the same lock, O(chain) holds must-fix; restart markers and repair passes; bounds, zero edges, a fix per defect | LOCK/PERSIST categories; #1753; probe 1753, 1769 | review-1769, diagnose-1753, route-1806 |
| beacon | scope adds new ETH chains, Engine API semantics and JWT (with conduit), chain-following; Critical includes stalls; start-order, default-on re-rating, secret-file checks; chain ids for Hoodi, Platåberget, Ephemery; Amsterdam | #1823 rounds; roster; probe 1823 | review-1823, route-1765 |
| conduit | parity with go-ethereum on both chains including error text and decode strictness; where texts live; Hive rpc-compat as the oracle; UNVERIFIED label; deviation comments as root-cause candidates; JWT split; stale test baseline and package tree replaced | #1727 series, #1879; probe 1879 | diagnose-1879, route-1765 |

Not proposed (no task tests them this round; recorded for the next retro): eye (testEssential time,
missing aliases, repeated concurrency runs), loom (test count, SSC size, `ResourceHealthMonitor`),
flow (never routed for WebSocket stream graphs), a thread-lifecycle protocol and testing-protocol.md
rules (protocols are not loaded into a trial).

## A/B round 1

A = agent files at `HEAD`, B = the round-1 candidates; 18 tasks × 2 reps per arm, blind pairwise grades
(opus). No leaks, edits or contract failures in 72 trials. Cost at API prices: A $13.56, B $12.37.

Per agent: the agent's own tasks plus the route tasks its description can change (`score.py --only`).

| Agent | Tasks | Wins A / B / tie | Verdict |
|---|---|---|---|
| forge | review-1853, review-1870, route-1733/1757/1806 | 0 / 5 / 0 | **ACCEPT B** |
| prism | review-1783, review-1787, route-1744 | 0 / 2 / 1 | **ACCEPT B** |
| herald | review-1745, review-1782, route-1757 | 0 / 3 / 0 | **ACCEPT B** |
| vault | diagnose-1753, review-1769, route-1806/1757 | 0 / 3 / 1 | **ACCEPT B** (revised in round 2) |
| beacon | review-1823, route-1765/1814/1733/1806 | 0 / 4 / 1 | **ACCEPT B** |
| conduit | diagnose-1735, diagnose-1879, route-1765/1770 | 3 / 1 / 0 | **KEEP A** |

Rubric totals, A → B (sum of both reps): review-1745 0 → 16 of 16; review-1782 0 → 10 of 20;
review-1783 6 → 16 of 20; review-1787 6 → 15 of 24; review-1823 6 → 15 of 16; review-1853 8 → 21 of 24;
review-1769 10 → 10 of 20 (no change); diagnose-1753 17 → 16; diagnose-1735 16 → 15; diagnose-1879 14 → 14.

Why conduit lost: both diagnose tasks sit near ceiling and the margins are one rubric point. The
UNVERIFIED rule fired (B labelled its geth claims) but the claims were as wrong as A's, so the grader
still counted them; routes were identical. The behavioural rules bought nothing here.

Why vault did not move on review-1769: the writer-enumeration rule never fired — no B run opened the
other writer — while the severity rule did (the lock hold became must-fix in both B runs). Diagnosis:
placement. vault has no review procedure; the new rules sat among coding iron rules.

Full table: `runs/0.12.1/r1/score.md` (not tracked).

## A/B round 2

A = round 1's winners (forge, prism, herald, vault, beacon from B; conduit from `HEAD`); B changes two
agents only. vault B adds a "When reviewing a change" procedure (list the stores written, grep and paste
every writer with its lock, worst-case lock hold, zero/empty edges, proof behind each marker, rate by
consequence). conduit B keeps only the factual and scope edits (package tree, file count, the stale test
baseline removed, the JWT split) and drops the behavioural rules. Round-1 trials whose agent file is
unchanged were reused as A; route tasks ran fresh on both arms (the router differs).

| Agent | Wins A / B / tie | Verdict |
|---|---|---|
| vault | 0 / 3 / 1 | **ACCEPT B** — diagnose-1753 reached 10/10 once; review-1769 6 → 6 and 5 → 8 |
| conduit | 3 / 0 / 1 | **KEEP A**, and a new hard failure: with "JWT handling" in conduit's description the router added beacon to an RPC-decoding task (route-1770) |

conduit has two KEEP A in a row, so its edits are dropped. The loop stops here: the other agents won
round 1 outright and a third round was not worth the plan usage. The open items below seed the next retro.

## Verdicts

| Agent | Verdict | Landed |
|---|---|---|
| forge | ACCEPTED (round 1) | chain-following review checklist; Critical includes losing the chain; protocol links; `extvm/` removed |
| prism | ACCEPTED (round 1) | tests read, inputs traced to origin, severity for stalls/DoS/lost updates/every-run defects, re-review rules, thread ownership, wider trust boundary, TUI scope |
| herald | ACCEPTED (round 1) | untrusted ingest rule, "When reviewing" section, peer credit/blame scope, ETH70/71 EIPs corrected |
| vault | ACCEPTED (round 2) | scope incl. unified layout, real package tree, rule 6 corrected (children before parent), key-verified reads, locks, restarts, bounds, review procedure |
| beacon | ACCEPTED (round 1) | scope incl. new ETH chains, Engine API + JWT, chain-following; start-order/default-on/secret-file checks; chain ids and Amsterdam |
| conduit | NO CHANGE | both candidates rejected |
| eye, loom, flow, mithril, wraith | NOT TESTED | no tasks this sprint |

Cost: 96 trials ≈ $30 at API prices, 52 blind grades and the review/checker subagents on top — on a
subscription login, plan usage, not billing.

## Open items (seeds for the next retro)

- **conduit**: still names files that do not exist (`JsonRpcHttpsServer`, `JsonRpcWebsocketServer`,
  `GraphQLHttpRoute`, `controllers/…`), "79 files" (97) and a stale test baseline. The factual fix lost
  only through its description edit; next time, test the package-tree fix without touching the
  description.
- **route-1757**: every arm in both rounds left forge out of a peer-blame fix on a PoW chain's import
  errors, or added vault. Either the gold is too strict or the routing rule for "consensus import errors
  without a validity change" is unclear in CLAUDE.md.
- **Unmoved scores**: review-1782 (herald, 4–6 of 10), review-1787 (prism, 7–8 of 12), review-1769
  (vault, 5–8 of 10).
- **Not tested, recommended**: eye (testEssential "<5 min" is 24 min; missing `pp`, `formatCheck` and six
  tag aliases; repeat concurrency suites); loom (test count 3,519 → 7,219; SSC 5,178 → 1,944 LOC;
  `ResourceHealthMonitor`); flow (routed to WebSocket stream graphs); a thread-lifecycle protocol;
  testing-protocol.md: wall-clock assertions and failing-first.
- **Harness**: `.claude/repo-references/` is absent in a trial snapshot, so every "compare with geth"
  rule ran on memory; 018 Track C squash bodies record no reviews (only `plan.md`). The next retro should
  add `impact` and `implement` tasks (none this sprint).

## Carry-over (Phase 7: the backlog)

Three agents reviewed every item not done (237, by area) against `origin/staging` at 21cfa13df.
Applied on the `tracker` branch (evidence: a merged PR or code in place):

- **Closed done (8):** B-019 (#1873), B-027 (#1777, #1778), B-223 (#1810), B-276 (#1873), B-282 (#1845;
  dev-node's review claim had expired), B-321 (#1874), B-330 (#1882), B-111 (the check was already in
  `ByteCodeResponseHandling`).
- **Pruned:** 103 closed items moved to `archive.yaml` (`backlog prune`); 230 remain in `backlog.yaml`.
  Ids stay taken; the page keeps counting archived items once this PR's `backlog.py` is on `main`.
- **Added:** B-333, this retro (review, #1884).

**After:** 230 items — 220 open, 3 claimed, 3 in progress, 2 in review, 2 blocked; P1 13, P2 104, P3 113.

### Fix first

- **B-332 (P1, snap)** — 0.12.1 shipped with it: valid SNAP bytecode replies complete with 0 items, so
  peers get no credit. A 0.12.2 hotfix.
- **B-274 (P1, node)** — a startup exception leaves a half-started JVM (P2P up, no APIs). Small.

### P1s and gates

- **path-live gates** before `fukuii.state.path-live` defaults on: B-295 (kill during reverse-then-commit),
  B-296 (bound a deep reorg's memory), B-314 (hive re-org suites with the guard on); with the P2 tests
  B-292, B-329, B-255. Then the Track C chain: B-064 closes (record T209 and the Sepolia fresh sync + 24 h)
  → B-065 (C5b) → B-066 (C5c, takes B-243's checkpoint half) → B-067 (C6, ETC; B-326 first) → B-068 →
  B-069/B-070.
- **Hash layout while it stays the default:** B-009 (heal never replaces a junk node); the SYNC-FORK
  cluster B-298 + B-248 (+B-107) + B-012 + B-011, one consult with forge and vault; B-270.
- **Engine API (beacon):** B-005 (+B-271), B-198 (+B-089), B-090; B-245 if newHeads is silent post-merge.
- **Zero-trust TLS:** B-146/B-150 (P1, untouched since 11-08), with B-148, B-147, B-149 (#1756).
- **B-151 (Windows BLS, P1)** — paused after B-153; needs a holder or an accepted "ETH not supported on
  Windows" release note.

### Clusters for the sprint

- eth_simulateV1 / trace parity (conduit + beacon): B-163, B-166, B-180, B-181, B-158, B-161, B-172,
  B-176, B-171 (+B-136); B-072 re-validates on Ephemery at the end.
- RPC on the unified layout, now live: B-228, B-238, B-229, B-240, B-211 (unblocked).
- Consensus divergences (forge, then beacon): B-169, B-177, B-164, B-175, B-324.
- Mining RPC honesty: B-278 + B-279.
- Test hygiene: B-020 (+B-221), B-156, B-187, B-305, B-174, B-205, B-206, B-215, B-218, B-018, B-021, B-022.
- Docs/protocol batch: B-037, B-086, B-118, B-138, B-194, B-147, B-083. TUI polish: B-192, B-259, B-263,
  B-265, B-267, B-287, B-331, B-125, B-264 (P3; spec 027 UAT B-189 waits on the user).
- Blocked on data exports nobody holds: B-046, B-047 (fukuii-fc) gate spec 021's B-049 → B-052.
  B-053 (the C0 oracle jar, a user decision) gates B-203, B-207, B-212 and C7's exit.

### Decisions (ruled by the advisor, applied)

The user delegated the open decisions to an advisor: an independent Opus subagent that re-checked the
evidence against `origin/staging` and the live tracker and ruled on each one. Its rulings are in
`runs/0.12.1/backlog/advisor-rulings.json` (not tracked); all were applied to the `tracker` branch, then
pruned. It overturned three proposals: B-064's claim had not expired (a note, not a release); #1694 is
not fixed (it is B-005's bug), so B-023 no longer closes it; B-171 takes P2 when it absorbs B-136.

- **Dropped (7):** B-097, B-104, B-109, B-110, B-116, B-117, B-225.
- **Merged (7, dropped into the survivor with a note):** B-221 → B-020, B-159 → B-155, B-136 → B-171,
  B-089 → B-198, B-271 → B-005, B-029 → B-281, B-107 → B-248.
- **Done:** B-227 (S1–S4b merged; S5 is B-236).
- **Released (claims older than 24 h):** B-072, B-073, B-151, B-203. B-203 and B-049 set blocked (on
  B-053; on B-046/B-047). Notes asking for a heartbeat on B-064 and B-236 (claims kept).
- **Raised:** B-053, B-073, B-148 → P1; B-149, B-171, B-216, B-240 → P2. B-245 stays P2 with a note
  (raise if a node confirms newHeads is silent post-merge).
- **Lowered:** B-008, B-010 → P2; B-013, B-014, B-046, B-047, B-106, B-115, B-315 → P3.
- **Retitled (partial work merged):** B-020, B-023, B-073, B-092, B-095, B-194, B-219, B-220, B-241, B-328.
- **depends_on:** B-014, B-079 → B-069; B-076 → B-070. (B-050–B-052 already chain through B-049.)

**Final:** 215 items in `backlog.yaml` (208 open, 4 blocked, 3 held), 118 in `archive.yaml`;
P1 14, P2 93, P3 108. P1s: B-005, B-009, B-053, B-073, B-146, B-148, B-150, B-151, B-274, B-295,
B-296, B-298, B-314, B-332.

### Decisions the reviewers proposed (for the record)

| Kind | Items |
|---|---|
| Drop (obsolete) | B-109, B-110, B-116, B-117 (code gone or already done), B-225 (superseded by #1821), B-097 (vague), B-104 (fold into spec 024?) |
| Merge (close the first into the second) | B-221 → B-020, B-159 → B-155, B-136 → B-171, B-089 → B-198, B-271 → B-005, B-029 → B-281, B-107 → B-248 |
| Close over another holder | B-227 (dev-node; S1–S4b merged, S5 is B-236) |
| Stale claims (> 24 h) | B-073 (dev-node, 11-09, likely abandoned; raise to P1 for the Mordor gates), B-072 (dev-node, 11-09), B-151 (fukuii-fc, 11-08), B-203 (dev-node, 11-11; set blocked on B-053), B-064 (cloud-dc960fdc; code done), B-236 (dev-node; likely live until the 11-19 reset) |
| Raise | B-053 → P1, B-073 → P1, B-148 → P1, B-245 → P1 if confirmed, B-149 → P2, B-216 → P2, B-240 → P2 |
| Lower | B-008 → P2, B-010 → P2, B-013/B-014/B-106/B-315 → P3, B-046/B-047 → P3 unless spec 021 S1 is scheduled, B-115 → P3 |
| Retitle (partial) | B-020, B-023, B-092, B-095, B-194, B-219, B-220, B-241, B-328 |
| Set blocked / depends_on | B-049…B-052 on B-046/B-047; B-014, B-079 on B-069; B-076 on B-070 |

The CLI has no retitle, priority or note command (B-123); the rulings were applied through
`backlog.Tracker.mutate`, the CLI's own validated compare-and-swap write.
