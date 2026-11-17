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
