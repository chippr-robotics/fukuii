# Data Model: Scala 3.9 LTS Toolchain Migration

There is no runtime data model change. The entities here are **build artifacts and records** that the migration
PRs produce and compare. Each one says where it lives and what makes it valid.

## Toolchain pin

| Field | Values | Lives in |
|---|---|---|
| `scala-3` | 3.3.8 → 3.9.x | `build.sbt:50` |
| `-source` level | `future` → `3.4-migration` … (per step) → `3.4`, `3.6`, `3.7` (between PRs) → **`3.9`** | `build.sbt` `scala3Options` |
| sbt | 1.10.7 → 1.13.0 | `project/build.properties` (also `_hive-sim.yml`) |
| plugin set | research R-3 table | `project/plugins.sbt` |
| scapegoat compiler plugin | 3.3.6 → 3.4.1 (with the compiler) | `build.sbt` `scapegoatVersion` |
| scalafmt-core | 3.8.3 (unchanged in 020) | `.scalafmt.conf` |

**Invariant**: `scala-3` and `scapegoatVersion` change in the same commit. `-source:3.N-migration` never stays on
staging between PRs.

## Rewrite step record (one per `-source` step, 3.4 … 3.9)

| Field | Example (GT-1) |
|---|---|
| step | 3.7 |
| commit sha | — |
| rewrite diff | 187 files, +514 / −514 |
| consensus files touched | 58 (`consensus/` 48, `db/storage/` 3, `domain/` 3, `mpt/` 2, `network/p2p/messages/` 2) |
| `compile-all` | 0 errors, warnings N |
| `testEssential` | count / 0 |
| differential run? | yes, if consensus files were touched |
| reviewer | forge / beacon / mithril |

**Invariants**: the steps run in order with none skipped (DC-2). The rewrite commit contains only compiler output. A
step with no diff still gets a recorded commit, or a recorded line in the PR body when git refuses an empty commit
(`--allow-empty` is acceptable).

## Hand-fix record

| Field | Values |
|---|---|
| construct | `E172 Ordering positional → using` / `E171 McpService partial implicit list` / … |
| site | file:line |
| compiles on 3.3.8 | yes / no (if no, the commit lands after the version flip) |

Eight records exist at PR 3 (GT-1 §4.2).

## Prioritisation site (DC-3)

| Field | Values |
|---|---|
| site | file:line:col |
| searched type | e.g. `RLPDecoder[Byte]` |
| old choice / 3.7 choice | `RLPImplicits.byteEncDec` / `RLPImplicits.given_RLPDecoder_Byte` |
| same value? | yes (`byteCodec`) / no |
| action | definition-site collapse / explicit `using` arg |
| reviewer | forge+beacon / mithril+conduit |

Stored in `dc3-sites.md` (371 rows on 3.9.0, GT-1). **Invariant**: 0 rows reproduce on the final tree under
`-source:3.7-migration`.

## Warning ledger (DC-4)

| Field | Values |
|---|---|
| category / id | e.g. `E029`, `ImplicitParamsWithoutUsing` (msg filter) |
| count at 3.3.8 / post-3.4 / final | e.g. 2 / 4 / 4 |
| disposition | cleared-by-rewrite / fixed (bucket) / promoted (`-Wconf` text) / deferred |
| deferral | site, dated reason, owner, follow-up issue |

Stored in `dc4-ledger.md` (tracked), mirrored to CHASE-QUEUE (git-ignored). **Invariant**: final total ≤ 605; no
blanket `-Wconf` silence.

## Baseline record (GT-2)

Rows 1–12 of research §B. Every PR copies the rows that apply to it into its body, with deltas. **Invariants**:
test counts equal (or explained by tests added outside 020, which must not happen inside the series); coverage ≥
baseline; rpc-compat and graphql equal in both directions; differential `failed = 0`; timing within 1.10×.

## Differential run

| Field | Values |
|---|---|
| chain / range | Mordor `H_M+1…H_M+N` / Platåberget `H_P+1…H_P+N` |
| range file SHA-256 | fixed at GT-2 |
| frozen datadir | `<datadir>.spec020-base-H` |
| jar | A (3.3, GT-2 sha) / B (PR sha) |
| result | imported / skipped / failed |
| last-block `stateRoot`, `receiptsRoot` | hex |
| `[IMPORT-TIMING]` p50 / p99 (first 200 blocks dropped) | ms |

**Invariant**: same datadir copy, same JVM flags, same config, alternating A/B/A/B order.
