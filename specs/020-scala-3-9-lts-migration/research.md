# Phase 0 Research: Scala 3.9 LTS Toolchain Migration

**Spec**: [spec.md](./spec.md) · **Evidence**: [gt1-inventory.md](./gt1-inventory.md) · **Date**: 2026-10-17

Each section records a Decision, the Rationale, and the Alternatives considered. Sections R-1 to R-9 settle the
questions `/speckit-plan` was asked to settle. §F lists the places where the spec contradicts the tree or GT-1, plus
side findings. §B is the GT-2 baseline table that every migration PR reports against. It is filled before PR 1 merges.

Facts re-verified on 2026-10-17 for this plan:

| Fact | Source | Result |
|---|---|---|
| Latest 3.9 patch | Maven Central `scala3-compiler_3` (GT-1 §1) | 3.9.0 is the only one |
| `scalac-scapegoat-plugin` | `repo1.maven.org` POM probes | `_3.3.8/3.3.6` 200, `_3.3.8/3.4.1` **404**, `_3.9.0/3.4.1` 200, `_3.9.0/3.3.6` 404 |
| sbt 1.x line | Maven Central `org/scala-sbt/sbt` metadata | 1.12.13, 1.12.14, 1.12.15, **1.13.0** (newest 1.x) |
| scalafmt-core | Maven Central metadata | latest 3.11.5 |
| sbt 1.12.15 notes | github.com/sbt/sbt release v1.12.15 | fixes GHSA-m2pw-22cj-jq4v (RCE via sbt server when `serverConnectionType` = `Tcp`) |
| sbt 1.13.0 notes | release v1.13.0, GHSA-943m-f264-54p4 | **Scala 3.9 REPL support** (#9564); fixes a second Tcp-server RCE that affects 1.4.0 to 2.0.6, so **1.12.15 is affected** and 1.13.0 is the only patched 1.x; unchanged plugin descriptors are no longer rewritten; `IO.jarParallel`. No deprecations listed |
| `serverConnectionType` in this build | grep `build.sbt`, `project/` | not set (default), so neither advisory applies today |

---

## R-1 — scalafmt: stay on 3.8.3 or hand-fix and move to 3.11.x

**Decision**: **Stay on scalafmt 3.8.3 for the whole of spec 020.** Move to 3.11.x in a follow-up PR (**FU-1**) that
lands after 020's last PR and before spec 017's first implementation PR. FU-1 carries the hand change to the
`taggedAs (…) in new Fixture:` construct as its own commit, then a format-only commit. **Accepted by the user 2026-10-17**; G-3, FR-003 and SC-005 amended.

**Rationale**:
- **DC-8 is the deciding constraint.** It lists everything a migration PR may contain: build and tooling bumps, the
  compiler's rewrite output, hand fixes for *compile errors* the rewrites cannot clear, P3b conversions, and dated
  `@nowarn`. A hand edit of up to 96 test files to get past a formatter's *parser* is none of these. A
  re-layout of test code inside the migration is the diff noise DC-2 and DC-8 exist to prevent.
- **3.8.3 parses the migrated tree** (GT-1 §5.1: 0 parse errors on the rewritten 3.4 + 3.7 tree). The spec's Problem
  paragraph says "the 3.8 dialect does not parse 3.9 syntax". That is true of syntax 020 never writes: `-source:3.9`
  (R-4 of the clarifications) keeps the old `given` and control syntax legal, and NG-4 forbids modernisation.
- **DC-1 holds either way.** The tooling step stays third. With 3.8.3, the only work left in step 3 is the plugin
  re-checks and the protocol text, so its risk and size drop.
- **The constitution needs no scalafmt PATCH** (principle IV names 3.8.3), so GT-3's PATCH shrinks to the sbt string
  (R-8).
- **Cost**: spec 017+ cannot use syntax that scalameta 4.9 (inside scalafmt 3.8.3) does not parse. Examples are the
  3.6 `given` syntax (SIP-64) and `{A, B}` context-bound aggregates. The CI `scalafmtCheckAll` gate would fail on such
  a file, so the failure is loud, not silent. FU-1 removes the limit. If 017 needs the new syntax first, FU-1 goes
  ahead of it.
- **Bisection evidence** (GT-1 §5.1): 3.8.3 parses; every version from 3.8.4 to 3.11.5 fails on the construct. A
  whole-tree count also matters. GT-1 counted **34** files, but scalameta stops at the first error in each file, so
  34 is a lower bound. A grep for the multi-line `taggedAs (…)\n) in new X:` shape matches **96** test files. FU-1
  sizes the change by running a 3.11.x check, not from either number.

**Alternatives considered**:

| Alternative | Rejected because |
|---|---|
| Hand-fix the construct inside 020, then 3.11.x in the tooling step (spec's FR-003 as written) | DC-8 (see above). It also adds about 110 more root files of 3.11.5 reformat drift (GT-1 §5.1) to a PR series whose diffs must stay attributable |
| A config-only scalafmt fix (`runner.dialectOverride.*` on 3.11.x) | Not measured. Worth trying first in FU-1, because it would make the hand edit unnecessary. It cannot be the 020 plan without evidence |
| A whitespace-only hand edit, argued to be a "format change" | The rewrite must be proved token-identical (for example, `tr -d ' \n\t'` equality per file), which GT-1 did not test. Also, 3.11.x may reformat long lines back into the failing shape (its idempotence on this construct is unknown). This is FU-1's acceptance test, not 020's |

**FU-1 acceptance** (recorded here so FU-1 does not re-research it): try the config-only route first. If it fails,
apply the hand edit as one commit per construct shape, each file proved token-identical. Run scalafmt 3.11.x twice; the
second run is a no-op. Make a separate format-only commit. `scalafmtCheckAll` stays green on every module. PATCH the
constitution's principle IV string.

---

## R-2 — The `import x.*` guard (DC-6)

**Decision**: one regex, enforced in two places, landing in **PR 2, before the `-source:future` → `-source:3.9` flip**
so there is no gap in enforcement:

1. **The scalafix rule (the user's decision).** Add a `DisableSyntax.regex` entry to `.scalafix.conf` with two ids,
   `scala2ImportWildcard` and `scala2ImportRename`. It runs wherever scalafix runs: `formatCheck`, `formatAll` and
   `scalafixAll`. Agents read `.scalafix.conf` as the ratchet location (`warning-ratchet.md` step 4a).
2. **CI enforcement.** `scripts/ci/check_scala_imports.sh` runs as a new **step in `ci.yml`'s existing "Test and
   Build" job, before "Check formatting"**. It is plain grep, needs no sbt, and takes seconds. A self-test,
   `scripts/ci/test_check_scala_imports.sh`, runs in the same step. Because it is a step and not a new job or
   workflow, it adds no new check context, so `.github/gates.yml` needs no new entry. (If it is ever split into its
   own job, it must be declared in `gates.yml`, or Gate Integrity fails.)

Pattern (POSIX ERE, matched per line over `*.scala` under the module roots; `project/**` and `*.sbt` excluded):

```
^[[:space:]]*import[[:space:]].*(\._[[:space:]]*($|,|//)|[{,][[:space:]]*_[[:space:]]*}|=>)
```

It catches `import a._`, `import a._, b.*`, `import a.{b, _}` and `import a.{b => c}`. It passes `import a.*`,
`import a.{given, *}` and `import a.{b as c}`.

**Proof** (run during planning against a scratch copy of the script, outside the repo):

| Input | Exit |
|---|---|
| `import foo.*`, `import foo.{given, *}`, `import foo.{a as b}`, `import foo.bar` | 0 |
| `import foo._` | **1** |
| `import foo.{a, _}` | **1** |
| `import foo.{a => b}` | **1** |
| `import foo._, bar.*` | **1** |
| today's tree: `src/`, `bytes/`, `crypto/`, `rlp/`, `scalanet/` | 0 (GT-1: 0 wildcards, 0 renames) |

The scalafix half is proved in PR 2: add a scratch `import scala.util._` to a `bytes` file, run
`bytes/scalafixAll --check`, and see the `DisableSyntax.scala2ImportWildcard` error (quickstart Q-2).

**Rationale**:
- CI runs only `scalafmtCheckAll`. `formatCheck` is not in CI, and its `scalafixAll --check` is red on staging today
  (GT-1 §5.2: about 6,489 root lines, 78 `return` lints, 34 scalameta parse failures). A guard that lives only in
  scalafix would be unenforced, and its failures would be lost among the existing ones. The grep step is the gate;
  the scalafix rule is the local, agent-facing ratchet.
- `project/*.scala` and `*.sbt` are compiled by sbt's Scala 2.12 and pinned to the `scala213` dialect in
  `.scalafmt.conf`, where `import x._` is the only legal form. They must stay out of the guard. Scalafix never sees
  them; the grep excludes `/project/`.
- `-source:future` also made renames (`=>`) an error (`ImportRename`), so the guard covers both.

**Alternatives considered**:

| Alternative | Rejected because |
|---|---|
| scalafix only | Not run in CI. `--check` is already red, so a new failure would go unseen |
| `-source:future-migration` plus `-Wconf` promoting only the wildcard and rename messages (GT-1 §6, option c) | It still makes old control syntax a hard parser error (GT-1 B1), so the 63-file new-syntax rewrite would come first |
| A scapegoat inspection | No such inspection exists. Scapegoat is not in CI, and its compiler-plugin version is tied to the compiler (R-3) |
| A separate `.scalafix-imports.conf` run in CI | It needs an sbt boot in CI, and scalameta still fails to parse 34 or more test files (R-1), so the run would fail for unrelated reasons |

---

## R-3 — sbt version and plugin target list

**Decision** (confirmed by the user 2026-10-17): **sbt 1.13.0** (not 1.12.15), with the plugin set below. PR 1 holds the bumps that run unchanged on
Scala 3.3.8. The compiler-tied artifacts ride with PR 3.

**Rationale for 1.13.0**: FR-002 asks for "the latest 1.x patch", which is 1.13.0 (2026-08-21, more than eight weeks
old). Its notes list **Scala 3.9 REPL support**, and the build configures `Compile / console / scalacOptions`. It
patches GHSA-943m, which 1.12.15 does not. It has no listed deprecations and keeps the same `_2.12_1.0` plugin ABI.
Neither advisory affects this build today (`serverConnectionType` is not set). **Fallback**: if any plugin fails to
load on 1.13.0 in PR 1, use 1.12.15 and record why.

| Artifact | Where | Pinned | Target | PR | Note |
|---|---|---|---|---|---|
| sbt | `project/build.properties` | 1.10.7 | **1.13.0** | 1 | also `_hive-sim.yml:201` downloads `sbt-1.10.7.tgz`, so it changes too |
| sbt-scalafmt | plugins.sbt | 2.5.2 | 2.6.2 | 1 | plugin only. scalafmt-core stays **3.8.3** in `.scalafmt.conf` (R-1) |
| sbt-scalafix | plugins.sbt | 0.13.0 | 0.14.9 | 1 **if** it loads on 3.3.8, else 3 | 0.13.0 cannot load rules against 3.9 sources (GT-1 §5.2), so it must be in place by PR 3 at the latest. PR 1 acceptance: `bytes/scalafixAll --check; rlp/scalafixAll --check` pass (both clean on staging), and root's finding count is unchanged from the GT-2 baseline |
| sbt-scoverage | plugins.sbt | 2.2.2 | 2.4.4 | 1 | PR 1 reports the coverage figure. If it moves from GT-2 by more than 0.1 pp with no source change, the bump moves to its own PR |
| sbt-native-packager | plugins.sbt | 1.10.4 | 1.13.0 | 1 | `dist` and `assembly` outputs compared (quickstart Q-1) |
| sbt-assembly | plugins.sbt | 2.3.0 | 2.5.0 | 1 | |
| sbt-buildinfo | plugins.sbt | 0.12.0 | 0.13.2 | 1 | |
| sbt-ci-release | plugins.sbt | 1.9.2 | 1.12.1 | 1 | no workflow runs `publish` (GT-1 §6). Load-time check only |
| sbt-git | plugins.sbt | 2.0.1 | 2.2.0 | 1 | |
| sbt-updates | plugins.sbt | 0.6.4 | 0.7.1 | 1 | |
| sbt-api-mappings | plugins.sbt | 3.0.2 | 3.1.0 | 1 | |
| sbt-kanela-runner | plugins.sbt | 2.1.0 | 2.1.1 | 1 | kanela-agent 1.0.18 itself stays (NG-3) |
| sbt-javaagent | plugins.sbt | `com.lightbend.sbt` 0.1.6 | **`com.github.sbt` 0.3.0** | 1 | organisation move. Check the `JavaAgent` keys and imports in `build.sbt` still resolve |
| sbt-scapegoat (sbt plugin) | plugins.sbt | 1.2.13 | 1.2.13 | — | already the latest |
| scapegoat **compiler plugin** | `build.sbt` `ThisBuild / scapegoatVersion` | 3.3.6 | **3.4.1** | **3** | must ride with the compiler. 3.4.1 has no `_3.3.8` artifact and 3.3.6 has no `_3.9.0` artifact (probed). The build fails at dependency resolution otherwise (GT-1 §2) |
| `scala-3` | `build.sbt:50` | 3.3.8 | **3.9.0** (or the latest 3.9.x at PR 3) | 3 | |

**DC-1 reading**: step 1 is "the sbt-1 plugin bumps". Step 3 is "scalafmt / scalafix / scoverage". This plan reads
step 1 as the plugin *artifacts* that run unchanged on 3.3.8, and step 3 as the tools' *versions and configuration*
that change formatting or lint output. With R-1, step 3 holds no tool-version change, only the R-2 rule's
interaction and the DC-9 text.

**DC-1 step 4 (libraries)**: GT-1 found **no library bump forced by the compiler**. All modules compiled on 3.9.0 with
the current `Dependencies.scala`. Step 4 is empty unless a later 3.9.x patch changes that.

**Alternatives**: 1.12.15, the spec's default. Rejected because it lacks 3.9 REPL support and the GHSA-943m fix.

---

## R-4 — GT-2 baseline method

**Decision**: split by what each host can measure.

| Figure | Where | How | Recorded in |
|---|---|---|---|
| `testEssential` count / failures | CI, JDK 25 | Tier 1 log of the PR's `ci.yml` run (`Tests: succeeded N, failed 0`) | §B row 1 |
| `testStandard` statement coverage | CI Tier 2 runs only on PRs into `main`/`develop` (`ci.yml` `if:`), and `ci.yml` has no `workflow_dispatch`. **Host** run of the exact Tier 2 command: `sbt "clean; coverage; testEssential; testStandard; coverageReport; coverageAggregate"`, with all nodes stopped | the aggregate statement % | §B row 2 |
| ethereum/tests both chains | CI `ethereum-tests-nightly.yml` `workflow_dispatch` on the branch, plus the `ci.yml` Gating Integration step | pass/fail counts per suite | §B row 3 |
| hive rpc-compat, graphql | CI `hive-rpc-compat.yml` and `hive-graphql.yml` (they run on PRs to staging that touch `build.sbt`/`src`, or by dispatch) | pass/total. **Both directions** count as a change (spec 009 rule) | §B rows 4–5 |
| SNAP `SyncTest` | CI `snap-synctest.yml` (PR to staging / dispatch) | pass/fail | §B row 6 |
| Two-tree import differential and `[IMPORT-TIMING]` | **Host** chipprbots-server, JDK 21, nodes stopped | procedure below | §B rows 7–10 |

**Range selection rule** (fixed once at GT-2, then reused unchanged by every PR):
1. **Mordor**: a contiguous range of **N = 5,000** blocks, all after the newest active ETC fork, starting at
   `H_M + 1`, where `H_M` is the head of the stopped `fukuii-mordor` datadir at freeze time. Within that window it
   must contain at least 200 blocks with gasUsed ≥ 1 Mgas; if it does not, widen N in steps of 5,000.
2. **Platåberget**: a contiguous range of **N = 2,000** blocks, all post-Amsterdam (EIP-8037 two-dimensional gas,
   EIP-7928 BAL validation: the newest and most codegen-sensitive paths), starting at `H_P + 1`. It must contain at
   least 200 blocks with gasUsed ≥ 30 Mgas, so that p99 is over heavy blocks.
3. Record in §B: the range bounds, the start block's parent hash and state root, the SHA-256 of the range file, and
   the frozen datadir's path and size.

**Procedure** (quickstart Q-7 has the commands):
1. **Freeze**: stop the unit and copy its datadir to `<datadir>.spec020-base-H` (not `rm`; the classifier blocks
   `rm -rf`). Restart the unit so it advances past `H + N`.
2. **Range file**: from the live node, fetch `debug_getRawBlock` for `H+1 … H+N` and concatenate the decoded bytes.
   That is the geth-export format `ChainImporter.decodeBlocks` reads. **Do not use `admin_exportChain`**: it writes
   a 4-byte length prefix before each block, which the importer cannot decode (F-6).
3. **Jars**: two assembly jars, `A` = 3.3 tree (the GT-2 commit) and `B` = the PR's tree. Build them in CI or on the
   host with nodes stopped (no sbt beside a running node).
4. **Run**: for each of A, B, A, B (alternating, to cancel drift): restore a fresh copy of the frozen datadir, then
   start the jar with the same JVM flags as the unit, `-Dfukuii.state-read-caches.import-timing-log=true`,
   `import-chain-file=<range>`, networking off (no peers, no discovery), the same caches. Wait for
   `Chain import complete: N imported, 0 skipped, 0 failed`, then stop.
5. **Roots**: `ChainImporter.importBlock` executes each block and runs `validateBlockAfterExecution` against the
   header's `stateRoot`, `receiptsRoot` and `gasUsed`, so **the header is the oracle**. Pass = `imported = N,
   skipped = 0, failed = 0` on every run of both jars. In addition, query `eth_getBlockByNumber` for the last block
   on both final datadirs and compare `stateRoot` and `receiptsRoot` byte for byte.
6. **Timing**: parse `total=<ms>` from each `[IMPORT-TIMING]` line. Drop the first 200 blocks (cold caches: the
   first post-SNAP block takes 237 s cold). Compute p50 and p99 per run, then the median of the two runs per jar.
   **Budget**: `p50_B ≤ 1.10 × p50_A` and `p99_B ≤ 1.10 × p99_A`, each chain separately. A regression above 10% is
   investigated (DC-5), not waived. An improvement is recorded.

**Rationale**: the importer already validates roots against the canonical headers, which were produced by core-geth
(Mordor) and by the devnet's reference clients (Platåberget). A differential against those headers is stronger than
3.3 against 3.9 alone, and it needs no new tool. Both sides run on JDK 21, so the relative comparison is valid.
Absolute times are not comparable to CI's JDK 25 and are not reported as such.

**Liveness precondition**: freeze-then-advance needs the live network to produce `H+1 … H+N` after the freeze.
Before freezing Platåberget, confirm glamsterdam-devnet-8 is still producing blocks (plataberget.dev forkmon). The
soak has been stopped since 2026-10-05. If the devnet is retired, the ETH-family range comes from Sepolia
post-Amsterdam (live since 2026-10-06) instead, which deviates from the spec's "Platåberget range" (F-13).

**Host constraints** (memory): fukuii-mordor, fukuii-plataberget and fukuii-sepolia never run together (same ports,
16 GB host). No
sbt while any node runs. 4 cores, so the runs are serial. Disk: each fresh copy is a full datadir. Check free space on
the root SSD before step 1, and if it is short, keep one working copy and re-copy it per run.

**Alternatives**: sync from peers (not deterministic and network-bound); `scripts/bench/blockchain-test-bench.sh`
(an EVM micro-bench, which may be added as a secondary signal but does not satisfy SC-004); the 3.3 jar against the
3.9 jar on one shared datadir (each run mutates it).

---

## R-5 — DC-3 review of the 371 E205 sites (3.6) and the 514 `using` insertions (3.7)

**Decision**:

1. **Listing.** The 3.6 step regenerates the site list. The GT-1 copy lives in an ephemeral job directory, so it is
   not relied on. Generate it from `sbt compile-all` under `-source:3.6-migration` by grepping `[E205]` lines. Commit
   it as `specs/020-scala-3-9-lts-migration/dc3-sites.md`, one row per site:
   `file:line:col | type searched | old choice | 3.7 choice | same value? | action | reviewer`.
2. **Group by definition, not by use.** 364 of the 371 sites come from one pattern in
   `rlp/.../RLPImplicits.scala`. For each of Byte, Short, Int, BigInt, Long, String, `Array[Byte]`, ByteString and
   `Seq[T]` it defines a named intersection `given xEncDec: (RLPEncoder[X] & RLPDecoder[X]) = xCodec` *and*
   anonymous `given RLPEncoder[X] = xCodec` / `given RLPDecoder[X] = xCodec` (lines 25–130). The old rules pick
   `xEncDec`; 3.7 picks the anonymous one. For the scalar codecs both are the **same value** (`xCodec`). For `Seq`,
   both call `seqCodec[T]`.
   - **Action**: in its own commit, collapse to **one given per type** in `RLPImplicits`. Remove the anonymous
     duplicates and keep the named intersection, so old and new rules pick the same symbol and the warning
     disappears at all 364 use sites. Before the edit, run `git log -S'given RLPEncoder[Byte]'` on the file to learn
     why the duplicates exist (Chesterton; `ba85066b9`, the intersection-parens scalafmt fix, is adjacent). Also
     grep for any reference to the synthesised names (`given_RLPEncoder_Byte` and similar).
   - **Review**: `rlp/` is consensus-critical and published, so **forge and beacon** both review. The import
     differential (R-4) is re-run after this commit alone. Removing the anonymous givens changes `rlp`'s public API,
     which already falls under DC-7's breaking release.
3. **The other 7 sites** are `JsonMethodDecoder` choices in `jsonrpc/JsonRpcController.scala:492–504`: `mcpXCodec`
   against `mcpXRequestDecoder`. These are **different values** (the codec delegates to the decoder). **Action**: make
   each explicit at the use site with a named argument (`using mcpXRequestDecoder`, or whichever the old rule chose),
   one commit, `mithril` reviewing and `conduit` checking the RPC side. rpc-compat must not move.
4. **The 514 `using` insertions at 3.7** (187 files, 58 under consensus paths) are compiler rewrite output. They are
   not hand-edited (DC-2). Review is by sampling plus a mechanical check: the diff must contain only `(` → `(using `
   insertions (`git diff --word-diff` shows only the `using` token added). Any other token fails the review. The 58
   consensus files are split **forge** (`consensus/`, `mpt/`, `db/storage/`, ETC `domain/`) / **beacon** (any
   ETH-only path). The differential is re-run after the step.
5. **P3b in the same step** (DC-3, G-4). The scope is `scala3-style.md` S4's grep: `implicit class` in `src/main`,
   **excluding `consensus/` and `vm/`** (S4's own exclusion). This makes US-4's "zero remaining targets"
   well-defined. Today the repo has 95 `implicit class` lines across all modules and B4 counted 98 diagnostics. The
   P3b PR records the in-scope count first. Conversions are bucket B, commits split per `risk-stratified-commit.md`.
   G3's override-chain `implicit val` sites are not touched.
6. **SC-006 check**: on the final tree, a one-off `-source:3.7-migration` compile reports 0 E205. **Then promote
   `-Wconf:id=E205:error`** (R-6), so the property cannot regress.

**Rationale**: fixing the ambiguity at its 9 definitions instead of 364 call sites gives a smaller and more reviewable
consensus diff, and removes the rule dependency for every future use as well. That is what DC-3 asks for ("so the
choice no longer depends on the rule").

**Alternatives**: annotate the 364 call sites with explicit arguments (a large diff in consensus files, and every new
call site would re-open the question); stay on `-source:3.5` (old rules), which defers the review and keeps 3.10's
removal pending; `@nowarn("id=205")` (hides a semantic choice, which DC-3 forbids).

---

## R-6 — DC-4 warning ratchet

**Deferrals confirmed by the user 2026-10-17** (2 E029, 2 `trait App`: dated `@nowarn`, `dc4-ledger.md` + CHASE-QUEUE).

**References** (GT-1, `-source:3.9`): **365** on 3.3.8, **605** after the 3.4 step (**the DC-4 / FR-005 ceiling**),
and **91** after the full chain with the ratchets at error.

**Decision**, per category:

| Category (3.9-new unless marked) | Count | Disposition | Ratchet |
|---|---|---|---|
| Implicit params without `using` (`ImplicitParamsWithoutUsing`) | 514 | cleared by the 3.7 rewrite | **promote**: `-Wconf:msg=Implicit parameters should be provided with a using clause:error` (migration warnings have no id; PR 6 verifies the filter matches) |
| E205 given prioritisation | 371 | cleared by R-5 | **promote** `-Wconf:id=E205:error` |
| E198 unused import | 13 | deleted by the 3.4 rewrite (`-Wunused` + `-rewrite`) | already an error |
| E028 illegal literal (`-98.toByte`) | 4 | cleared by the 3.4 rewrite (test files; value unchanged, but DC-8 watch) | promote E028 |
| Alphanumeric infix, `_` wildcard, `with` type operator | 8 / 5 / 2 | cleared by the 3.4 rewrite (`vm/OpCode.scala`, so forge) | promote by message filter if `-source:3.9` still warns on them |
| Ascription after pattern | 4 | bucket A syntax (`vm/PrecompiledContracts.scala` ×2, so **forge** glance; 1 test) | fix, then promote |
| `trait App` deprecated | 2 | `crypto/EcKeyGen.scala`, `crypto/SignatureValidator.scala`. `App` → `def main` changes initialisation order (delayedInit), so **bucket C** under `crypto/` | **defer**: dated `@nowarn("cat=deprecation")` at the site; forge decides in a follow-up |
| Unused `@nowarn` | 2 | bucket A: delete the annotation (`CalibratePivotTDSpec.scala:375`, `CommonFakePeer.scala:154`) | — |
| E029 non-exhaustive match (+2 new) | 2 | `regular/BlockFetcherState.scala:222`, `snap/ChainDownloader.scala:796`. Adding a case changes behaviour, which is a **DC-8 stop** ("could be a missed real case") | **defer**: dated `@nowarn("id=E029")` with the reason, CHASE-QUEUE entry, and a follow-up issue for analysis |
| Manifest synthesis (pre-existing) | 69 | unchanged | none (`cat=deprecation` cannot be promoted while these exist) |
| Other pre-existing (E194, diffx, one-off deprecations, E121, the 2 old E029) | 12 | unchanged | none |
| Inaccessible-companion implicits | **0** | the spec names this first among the new categories; GT-1 found none (`grep -ci inaccessible` = 0) | none needed. Record "0 at 3.9.0" in the final table |
| `-Ykind-projector` option warning | 13 per run | flag dropped in PR 3 | — |

**Expected final count**: 91 − 4 (ascription) − 2 (unused `@nowarn`) = **85**, plus 4 `@nowarn`-suppressed sites
(2 `App`, 2 E029). That is well under 605. The final PR publishes the per-category table (SC-001).

**Deferral record**: CHASE-QUEUE (`.claude/agent-protocols/working-docs/CHASE-QUEUE.md`) is **git-ignored**
(`.gitignore:77`), so an entry there is not reviewable. Each deferral is therefore also listed in the final PR's
ratchet table and in `dc4-ledger.md` in this spec directory: site, reason, date, owner, follow-up issue. See F-7.

**Alternatives**: promote everything (forces the E029 behaviour change); blanket `-Wconf` silence (forbidden by
`warning-ratchet.md` step 3).

---

## R-7 — DC-7 release

**Decision**:
- **Modules**: `bytes`, `crypto`, `rlp`, `scalanet` and `scalanetDiscovery` (`publishSettings`, `build.sbt` lines
  143, 172, 201, 218, 236) switch with the root in PR 3. No cross-build (`supportedScalaVersions` has one element,
  OQ-7).
- **Version**: MINOR. After the last 020 PR merges, dispatch `gh workflow run auto-version.yml --ref staging -f
  increment_type=minor` (`.github/VERSIONING.md` rule 2). Staging is at 0.9.103 today, so this gives **0.10.0**. The
  promotion PR to `main` carries the breaking-change callout (constitution VII). Merging it is the user's step.
- **Note**: no workflow runs `sbt publish` (GT-1 §6). "Published" means the modules are *publishable*. The break
  matters to anyone who builds and consumes them from source or from a manual publish.
- **Release-note wording** (in the promotion PR and the GitHub release):
  > **Build toolchain: Scala 3.9 LTS.** fukuii now builds with Scala 3.9.0 (the new LTS line) and sbt 1.13.0. Node
  > behaviour, configuration, RPC and the database format are unchanged. **Breaking for library consumers:** the
  > `com.chipprbots` artifacts `fukuii-bytes`, `fukuii-crypto`, `fukuii-rlp`, `fukuii-scalanet` and
  > `fukuii-scalanet-discovery` (now `_3` artifacts built with Scala 3.9) carry Scala 3.9 TASTy. Projects on
  > Scala 3.3 cannot consume them; rebuild on Scala 3.9 or later. `rlp`'s duplicate anonymous `given` codecs were
  > removed (use the named `xEncDec` givens or `RLPImplicits.{given, *}`).

---

## R-8 — GT-3: constitution PATCH and ADR INF-009

**Decision**:
- **Constitution PATCH 1.1.0 → 1.1.1**, via `/speckit-constitution`, in a docs PR that merges **before PR 1**:
  - Technology constraints: "Scala 3.x LTS on JDK 25 (OpenJDK); build with sbt 1.10.7+" → "Scala 3.x LTS (3.9 line
    from 0.10.0) on JDK 25 (OpenJDK); build with sbt 1.13.0+ (1.x line)".
  - Principle IV: **unchanged**. "3.8.3 config" stays true under R-1. FU-1 PATCHes it later.
  - Sync Impact Report comment at the top, as for 1.1.0.
- **ADR INF-009** at `docs/adr/infrastructure/INF-009-scala-3-9-lts.md`, plus a row in
  `docs/adr/infrastructure/README.md` and `docs/adr/README.md`. INF-007 and INF-008 are reserved by spec 018. It
  records: the LTS adoption (3.3 maintenance ends about 2027-09), `-source:3.9` plus the import guard instead of
  `future` (with the 1,541-site cost), the 016 → 020 → 017 sequencing and its reasons (019's TASTy target), staying
  on scalafmt 3.8.3, and the five-module TASTy break.
- **OQ-6 (user decision 2026-10-17): separate amendments, not one; 020 lands 1.1.1 + INF-009 now.** GT-3 asks that the technology section be "edited once
  per release train". That cannot hold. 020's PATCH must merge before 020's toolchain PRs (GT-3 itself), which is
  months before spec 018's GT-2 (INF-007, the storage engine) and spec 019's CG-1 (Scala Native). Each amendment
  edits a different clause (toolchain string, storage engine, native target), so three small PRs conflict only
  textually. **Note for 018/019** (not edited here): rebase onto constitution 1.1.1 and bump from there.

---

## R-9 — GT-1 loose ends carried as verification steps (not open questions)

| Item | Where it is checked |
|---|---|
| The 57 `val Pat(x) = expr: @unchecked` test sites were not rewritten to `.runtimeChecked` at 3.8 and printed no warning | PR 6, 3.8 step: confirm with a one-file probe whether 3.9.0 still accepts `: @unchecked` without warning. If it does, no action. Recorded either way |
| E171 `McpService` partial implicit list: cause not verified | PR 3 hand-fix commit message cites the 3.9 changelog entry, or states "not found" |
| A 3.9.1 appears before PR 3 | re-run GT-1's B2 and C2 counts on it. The plan takes "the latest 3.9.x" (spec Assumptions) |
| `-Xmax-inlines:64` and `-language:adhocExtensions` still needed | accepted on 3.9 (GT-1 §3). No change |

---

## F — Findings: spec against tree and evidence

| # | Spec text | Evidence | Proposed resolution | Status |
|---|---|---|---|---|
| F-1 | G-3, FR-003 and SC-005 require **scalafmt 3.11.x** | No 3.11.x parses the tree unmodified (GT-1 §5.1). The hand fix conflicts with DC-8 (R-1) | Amend G-3, FR-003 and SC-005 to "scalafmt 3.8.3 retained; 3.11.x in FU-1 before spec 017" | Resolved 2026-10-17: accepted, spec amended |
| F-2 | SC-005: "`formatAll` and `formatCheck` green … across all modules" | Both aliases run `scalafixAll`/`--check`, which is red on staging (GT-1 §5.2). The Clarification puts root's about 6,500-line scalafix debt out of scope | Read SC-005 as: `scalafmtCheckAll` green on every module; `scalafixAll --check` green on `bytes` and `rlp`; elsewhere **no new findings** against the GT-2 count | Resolved 2026-10-17: accepted, SC-005 amended |
| F-3 | SC-003: rpc-compat **40/40** | GT-2 and FR-006: 273/273 since #1616. CLAUDE.md's SPECKIT block (from 009) also says 40 | Use the GT-2 re-measured count. Fix SC-003's text | Resolved: SC-003 amended |
| F-4 | G-1 and FR-001: "`-source:future` semantics preserved"; DC-6: keep both properties | Clarification: `-source:3.9`; the guard keeps `import x.*`; future-deprecation surfacing is **not** carried | The Clarification is binding. `scala3-style.md` records the mapping in PR 7. G-1/FR-001 wording is stale | Resolved: G-1/FR-001 amended |
| F-5 | DC-4 names the inaccessible-companion deprecation "first among" the new categories | 0 occurrences at 3.9.0 | Recorded as 0 in the final table | Resolved: DC-4 notes 0 |
| F-6 | (side) `admin_exportChain` writes a 4-byte length per block; `ChainImporter` reads concatenated RLP | `AdminService.scala:334–337` against `ChainImporter.decodeBlocks` | R-4 uses `debug_getRawBlock`. CHASE-QUEUE candidate (`conduit`); not fixed here (DC-8) | No |
| F-7 | DC-4 and FR-005: "CHASE-QUEUE entry" for each deferral | CHASE-QUEUE is git-ignored | Also record in `dc4-ledger.md` (tracked) | No |
| F-8 | GT-3: the technology section edited "once per release train" | 020's PATCH must precede 020's toolchain PRs, months before 018/019 | OQ-6: three amendments (R-8) | Resolved 2026-10-17: separate amendments |
| F-9 | The Problem paragraph: "the 3.8 scalafmt dialect does not parse 3.9 syntax" | 3.8.3 parses the migrated tree | Text only | Resolved: Problem text amended |
| F-10 | Audit and spec: published modules `bytes`, `crypto`, `rlp` | five, with `scalanet` and `scalanetDiscovery` (already corrected in DC-7 by the Clarification) | — | No |
| F-11 | GT-1: "34 files" with the scalameta failure | A grep of the multi-line `taggedAs` shape finds 96 files | Lower bound against upper bound; FU-1 measures | No |
| F-13 | GT-2, DC-5 and SC-004: a fixed **Platåberget** range | glamsterdam-devnet-8 liveness unconfirmed (soak stopped since 2026-10-05) | If retired, use a post-Amsterdam **Sepolia** range with the same rule | Stated contingency |
| F-12 | (side) `ci.yml` job name and artifact names hard-code `Scala 3.3.8` (lines 33, 200, 221, 233, 243). `docs/development/{scala-build,contributing}.md` name sbt 1.10.7 and stale `target/scala-3.3.7` paths | grep | PR 3 updates `ci.yml` names; PR 7 updates the docs | No |

---

## §B — GT-2 baseline (filled before PR 1 merges; every PR reports the same rows)

| # | Figure | Baseline (3.3.8, commit `<sha>`) | Source |
|---|---|---|---|
| 1 | `testEssential` succeeded / failed | [MEASURE] (last known 3,621 / 0) | CI JDK 25 |
| 2 | Statement coverage (aggregate) | [MEASURE] | host, Tier 2 command |
| 3 | ethereum/tests ETC / ETH | [MEASURE] | `ethereum-tests-nightly` dispatch |
| 4 | hive rpc-compat | [MEASURE] (273/273 since #1616) | CI |
| 5 | hive graphql | [MEASURE] (waiver `graphql-gasprice-stale-fixture` noted) | CI |
| 6 | SNAP `SyncTest` | [MEASURE] | `snap-synctest.yml` |
| 7 | Mordor range `H_M+1 … H_M+N`, parent root, range SHA-256 | [MEASURE] | host |
| 8 | Platåberget range `H_P+1 … H_P+N`, parent root, range SHA-256 | [MEASURE] | host |
| 9 | `[IMPORT-TIMING]` p50 / p99 ms, Mordor (jar A, median of 2) | [MEASURE] | host, JDK 21 |
| 10 | `[IMPORT-TIMING]` p50 / p99 ms, Platåberget (jar A, median of 2) | [MEASURE] | host, JDK 21 |
| 11 | `compile-all` warnings (3.3.8) | 365 (GT-1 A) | GT-1 |
| 12 | `scalafixAll --check` finding lines per module (0.13.0) | crypto 38, scalanet 60, discovery 4,074, root 6,489; bytes and rlp 0 (GT-1 E0) | GT-1, re-measure with PR 1's scalafix version |
