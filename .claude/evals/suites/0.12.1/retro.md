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
