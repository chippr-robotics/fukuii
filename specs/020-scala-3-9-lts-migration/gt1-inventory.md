# Spec 020 GT-1: inventory spike (Scala 3.3.8 → 3.9 LTS)

**Date**: 2026-10-17. **Branch**: `spike/020-gt1-inventory` (local only, not pushed), cut from `origin/staging` at
`42b15256f` (v0.9.101). **Host**: plain clone `/home/dontpanic/work/spike-020`, no nodes running, every sbt call under
`flock /tmp/fukuii-sbt.lock`. This report records measurements. It does not plan anything or answer the open
questions; it gives the evidence for them. Raw logs and patches are in `~/.claude/jobs/19f23359/tmp/spike-020/`
(listed in §11).

## 1. Versions tested

| Item | Value |
| --- | --- |
| Scala baseline | 3.3.8 |
| Scala target | **3.9.0**. Maven Central `scala3-compiler_3` metadata (lastUpdated 2026-10-08) lists 3.9.0 as the only 3.9 release. There is no 3.9.1. 3.10.0 is the `latest`/`release` tag, and 3.3.9-RC2 also exists |
| sbt | 1.10.7, unchanged (the DC-1 step-1 bump was not part of this spike) |
| JDK on the spike host | **OpenJDK 21.0.12.1**. The project's CI and Docker images are on 25. 3.9 needs 17 or later, so either works, but these numbers come from 21 |
| scalafmt | 3.8.3 (pinned), then 3.11.5 / 3.11.4 / 3.11.2 / 3.11.0 / 3.10.7 / 3.9.10 / 3.9.5 / 3.9.0 / 3.8.6 / 3.8.5 / 3.8.4 |
| sbt-scalafix | 0.13.0 (pinned), then 0.14.9 |
| scapegoat compiler plugin | 3.3.6 (pinned). **Bumped to 3.4.1 for every 3.9 run**: see §2 |

## 2. Build changes needed before the 3.9 compiler would check any source

These are the smallest changes that got past each blocker, in the order they were hit.

| # | Change | Why it was needed | Evidence |
| --- | --- | --- | --- |
| 1 | `scapegoatVersion` 3.3.6 → **3.4.1** | sbt-scapegoat adds `scalac-scapegoat-plugin_<full Scala version>`, and 3.3.6 has no `_3.9.0` artifact. Dependency resolution failed before any compilation started. `scalac-scapegoat-plugin_3.9.0` is published in 3.4.0 and 3.4.1 | `B0-390.log` (first attempt): `not found: …/scalac-scapegoat-plugin_3.9.0/3.3.6/…pom` |
| 2 | `-source:future` → `-source:3.9` | On 3.9, `-source:future` turns on `-new-syntax`, which makes old-style control syntax a **parser error**. Compilation stopped in `bytes` (the first module) with 23 errors. `-source:future-migration` did not help: it still enforces new syntax (17 errors in `bytes`). See §6 OQ-2 | `B0-390.log`, `B1-390-futmig.log` |
| 3 | 8 hand fixes (2 main, 6 test): explicit argument to a **context-bound or `using` parameter** without `using` | 3.9 reports these as hard type errors (E172/E171). No `-source` flag rewrites them. They block every module downstream, so they were fixed by hand to get the rest of the inventory (`handfix-3.9.patch`) | §4.2 |

The ratchet promotions (`-Wconf:id=E198:error`, `-Wconf:cat=unchecked:error`) were demoted to `:w` only on the
inventory runs, so that no module was skipped. The post-3.4 run (C2) restored them to `:error` and still compiled with
0 errors.

## 3. Flag table

| Flag | Status on 3.9.0 | Evidence |
| --- | --- | --- |
| `-source:future` | **Accepted, but its meaning is broader than on 3.3.** On 3.9, `future` turns on `-new-syntax` (`Scanners.scala`: `newSyntax = settings.newSyntax || sourceVersion.requiresNewSyntax`; on 3.3.8 it was `settings.newSyntax` only). It also turns every `MigrationVersion` row with `errorFrom = future` into an **error**, including `Scala2Implicits` (implicit vals, defs, classes and parameters), `ImplicitParamsWithoutUsing`, `GivenSyntax`, `WithOperator`, `WildcardType`, `AlphanumericInfix`, `ImportWildcard` and `ImportRename`. Result: **the unmodified tree does not compile with it** (§4.2) | `B0-390.log`; compiler sources at tag 3.9.0: `SourceVersion.scala`, `MigrationVersion.scala`, `report.scala` `errorOrMigrationWarning`, `Parsers.scala` (`Scala2Implicits` at the `implicit` parameter, lambda and closure sites) |
| `-Ykind-projector` | **Accepted with a deprecation warning**: `Option -Ykind-projector is deprecated: Use -Xkind-projector instead.` It prints once per compilation unit (13 per `compile-all`). In `ScalaSettings` 3.9.0 it is `Deprecation.renamed("-Xkind-projector")` | 79 occurrences across the 3.9 logs. All five rewrite-chain runs (G3.5 to G3.9) ran with the flag **removed entirely**, and every module compiled with 0 errors. The flag is not needed by any code (OQ-1) |
| `-Wconf:id=E198:error` | **Accepted. E198 is still `UnusedSymbolID`.** On 3.3.8 the enum entry is `UnusedSymbolID // errorNumber: 198` at line 214; on 3.9.0 it is at line 216 with the same number. Every enum entry before it is an in-place rename, and new ids are only appended after it | `B2-390-src39.log` reports `[E198] Unused Symbol Warning` (13 sites). `ErrorMessageID.scala` at both tags (diff in `EMID-*.scala`) |
| `-Wconf:cat=unchecked:error` | Accepted | No unchecked warnings on any 3.9 run |
| `-Wconf:cat=feature:s` | Accepted | none |
| `-Xmax-inlines:64` | Accepted (`AdvancedSetting "Xmax-inlines"`, default 32) | No option warning in any log |
| `-language:adhocExtensions` | Accepted | No option warning |
| `-Wunused:all`, `-unchecked`, `-deprecation`, `-feature`, `-encoding utf-8` | Accepted | none |
| `-source:3.4-migration` on **3.3.8** | **Rejected**: `3.4-migration is not a valid choice for -source`. The 3.3 `SourceVersion` enum stops at 3.3 and `future`, so the rewrites have to be run on the 3.9.0 compiler | `C0-338-src34mig.log` |
| `-new-syntax -rewrite` (with `-source:3.9`) | Works. It rewrites old control syntax (§4.3) | `B3-…log` |

Note that with `-rewrite` and `-Wunused:all`, 3.9 also **removes unused imports**. Each rewrite step therefore also
deleted the 13 E198 imports. This is not a 3.4 migration patch.

## 4. Error and warning tables

Counting method: each `[warn] -- …` or `[error] -- …` header line is one diagnostic. "Unique" removes duplicates on
(file, line, message). The raw header total was cross-checked against the sum of the `N warnings found` lines; any
difference is exactly the per-unit `-Ykind-projector` option warning (13 per run).

### 4.1 A — baseline, 3.3.8 untouched (`sbt clean compile-all`, 331 s wall)

**0 errors, 365 warnings** (361 unique; the 365 matches the per-module summary lines exactly).

| Count | Id / category | What | Examples |
| --- | --- | --- | --- |
| 264 | E165 Type | `pattern selector should be an instance of Matchable` (only appears with `-source:future`) | `scalanet/.../InetMultiAddress.scala:42`, `scalanet/.../DynamicTLSPeerGroupInternals.scala:171` |
| 69 | Deprecation | Compiler synthesis of `Manifest` / `OptManifest` | `GenesisDataLoader.scala:92`, `EngineApiHttpServer.scala:143` |
| 9 | Deprecation | Alphanumeric method used as infix (`div`, `sdiv`, `mod`, `smod`, `slt`, `sgt`, `canEqual`, `remove`, `xor`) | `vm/OpCode.scala:524`, `scalanet/.../DiscoveryService.scala:268` |
| 5 | Deprecation | `_` as a type wildcard | `scalanet/.../InetAddressOps.scala:49` |
| 2 | E003 | `with` as a type operator | `rlp/.../package.scala:134`, `scalanet/.../PeerGroup.scala:165` |
| 2 | E194 | Extension method will never be selected | `scalanet/IOValues.scala:26` |
| 2 | E029 | Match may not be exhaustive | `scalanet/.../V5DemuxResponder.scala:47`, `regular/BlockFetcher.scala:93` |
| 2 | Deprecation | diffx `DiffMatcher` | `EthProofServiceSpec.scala:44` |
| 1 each | Deprecation / E121 | URL constructor, `Array`→`IndexedSeq` conversion, Javadoc-deprecated method, `Set.--`, web3j admin; unreachable case | `HealResponseProcessing.scala:354`, `ByteCodeCoordinator.scala:359` |

### 4.2 B — 3.9.0, only the version bumped (plus scapegoat 3.4.1)

**B0 (flags unchanged)**: compilation stops in `bytes`, so no other module is checked. **23 errors, all in
`bytes/`**:

| Count | Error | Examples |
| --- | --- | --- |
| 17 | `This construct is not allowed under -new-syntax or -language:future` (`if (…)`, `while (…)` without `then`/`do`) | `bytes/.../ByteStringUtils.scala:47`, `:53`, `bytes/.../ByteUtils.scala:25` |
| 5 | `implicit classes are no longer supported` (Scala2Implicits) | `ByteStringUtils.scala:45`, `:61`, `:80` |
| 1 | `implicit vals are no longer supported, use a given clause` | `ByteStringUtils.scala:85` |

**B1 (`-source:future-migration`)**: still stops in `bytes`, with 17 errors (the new-syntax ones). The 6 implicit
errors become migration warnings.

**B2 (`-source:3.9` in place of `future`, ratchets at `:w`)**: the first pass fails with **2 errors in main**, and once
those are fixed by hand, **6 errors in test**. All of them are hard errors that no rewrite clears:

| Count | Error | Cause | Sites (all hand-fixed by adding `using` for the inventory) |
| --- | --- | --- | --- |
| 5 | E172 `No given instance of type Ordering[…] was found` | An explicit `Ordering` is passed positionally to a stdlib **context-bound** parameter. Since 3.8 the standard library is compiled with Scala 3, so context bounds are `using` clauses (3.9 announcement) | `snap/actors/AccountRangeCoordinator.scala:222` (main), `snap/actors/storage/StorageOrderedApply.scala:227` (main), `AccountPeerDispatchSpec.scala:65`, `AccountTaskLifecycleSpec.scala:65`, `AccountTrieAssemblySpec.scala:98` |
| 3 | E171 `missing argument for parameter scheduler of constructor McpService` | A 2-parameter `(implicit ec, scheduler)` list is called with only `(ExecutionContext.global)`. Observed: this compiles on 3.3.8 and gives E171 on 3.9.0. The cause was not checked against a changelog | `jsonrpc/FukuiiJRCSpec.scala:62`, `JsonRpcControllerFixture.scala:239`, `QaJRCSpec.scala:127` |

**B2 after the 8 hand fixes**: all modules compile (325 s wall). **0 errors, 637 unique warnings** (643 raw headers; the summary lines add up to 656, which is the 643 plus 13
option warnings):

| Count | Id / category | Δ vs 3.3.8 | What | Examples |
| --- | --- | --- | --- | --- |
| **514** | Warning (`ImplicitParamsWithoutUsing`, warns from 3.7) | **new** | `Implicit parameters should be provided with a using clause` | `BytecodeRecoveryActor.scala:158`, `CombinedRecoveryScanActor.scala:70`, `rlp/.../RLPSuite.scala:533` |
| 69 | Deprecation | = | Manifest synthesis | as in A |
| **13** | **E198** Unused Symbol | **new** (0 on 3.3.8) | unused import. 3.9's checker finds `import x.*` lines that 3.3 did not flag. These **would be build errors under the ratchet** | `network/p2p/MessageDecoders.scala:602`, `:663`, `ETH70ComplianceSpec.scala:256` (7 in the ETH68/69/70 compliance specs), `SNAPSyncControllerSpec.scala:144`, `BlobTransactionRejectionSpec.scala:17`, `vm/BlockAccessRecordingSpec.scala:3` |
| 8 | Warning | ≈ | Alphanumeric infix | `vm/OpCode.scala:524…547` |
| 5 | Warning | = | `_` type wildcard | `scalanet/.../InetAddressOps.scala:49` |
| **4** | E029 | +2 | Match may not be exhaustive: 2 new sites | **new**: `regular/BlockFetcherState.scala:222`, `snap/ChainDownloader.scala:796` |
| **4** | E028 Syntax | **new** | `Illegal literal` on `-98.toByte` / `-1.toByte` | `network/p2p/messages/LegacyTransactionSpec.scala:49`, `:56`, `:79`; `vm/VMSpec.scala` |
| 4 | Warning | **new** | Type ascription after pattern | `vm/PrecompiledContracts.scala:214`, `:331`, `MerkleProofVerifierPhase3Spec.scala:108` |
| 2 | E003 | = | `with` type operator | as in A |
| 2 | E194 | = | | as in A |
| **2** | Deprecation | **new** | `trait App is deprecated since 3.8.0` | `crypto/EcKeyGen.scala:13`, `crypto/SignatureValidator.scala:15` |
| **2** | Warning | **new** | `@nowarn annotation does not suppress any warnings` | `CalibratePivotTDSpec.scala:375`, `src/it/.../CommonFakePeer.scala:154` |
| 7 | Deprecation / E121 | = | the one-off deprecations from A | |
| 0 | — | −264 | Matchable (E165) disappears: it only fires under `-source:future` | |
| **0** | — | — | **inaccessible-companion implicit deprecation**: no occurrences in any 3.9 log | `grep -ci inaccessible` = 0 |

### 4.3 What `-source:future` costs on 3.9 (B3 + B4)

- **B3, `-source:3.9 -new-syntax -rewrite`**: compiles, and the rewrite is written back. Excluding the 8 hand-fix
  files: **63 files changed**. About **222 control-syntax lines** in 49 files (`if (c) x` → `if c then x`,
  `while (c)` → `while c do`), mostly in the modules scalafmt's `convertToNewSyntax` pass never reached: scalanet 14,
  scalanet/discovery 11, crypto 9, src 7, rlp 6, bytes 2. Plus the 13 unused-import deletions. Diff
  `B3-full.patch`: +348 / −362.
- **B4, `-source:future-migration` on the B3 tree** (no rewrite; future errors are reported as migration warnings so
  that every module compiles): **1,892 unique diagnostics**. These are the diagnostics that would be **errors** under
  `-source:future`:

| Count | Migration diagnostic (an error under `-source:future`) |
| --- | --- |
| 514 | Implicit parameters should be provided with a `using` clause |
| 460 | `implicit` vals are no longer supported, use a `given` clause |
| 365 | `implicit` parameters are no longer supported, use a `using` clause |
| 98 | `implicit` classes are no longer supported |
| 43 | `implicit` defs are no longer supported |
| 16 | `implicit` conversion methods are no longer supported |
| 16 | Old given syntax (`given x: T with` / `given … as …`) is no longer supported |
| 9 | Multiple context bounds must be enclosed in `{}` |
| 8 / 5 / 4 / 2 / 2 / 1 | alphanumeric infix / `_` wildcard / ascription after pattern / given member starting with `with` / `with` type operator / abstract given |
| **= 1,541** | migration errors in total |
| 264 | E165 Matchable (stays a **warning** under `future`) |

Repo grep for comparison: `\bimplicit\b` 986 lines, `(implicit ` parameter clauses 266, `implicit val/def/class/object` 638.

### 4.4 C — `-source:3.4-migration -rewrite` alone (3.9.0, untouched tree + the 8 hand fixes)

`C1-390-src34mig-rewrite.log` (356 s): **16 files, +19 / −33** (`C-rewrite-3.4.patch`):

| Category | Lines | Files |
| --- | --- | --- |
| `with` → `&` in types | 2 | `rlp/.../package.scala` (`type RLPCodec[T] = RLPEncoder[T] & RLPDecoder[T]`), `scalanet/.../PeerGroup.scala` |
| `_` → `?` type wildcard | 5 | `scalanet/.../InetAddressOps.scala`, `NettyFutureUtils.scala`, `PeerGroup.scala`, `vm/OpCode.scala:501` |
| Alphanumeric infix → backticks | 8 | **`vm/OpCode.scala`** (``_ `div` _``, `sdiv`, `mod`, `smod`, `slt`, `sgt`), `scalanet` `canEqual` / `remove` |
| `-98.toByte` → `(-98).toByte` (E028) | 4 | `LegacyTransactionSpec.scala` ×3, `VMSpec.scala` |
| Unused imports deleted (`-Wunused` with `-rewrite`, not a 3.4 patch) | 13 | `network/p2p/MessageDecoders.scala` ×2, ETH68/69/70 compliance specs, 3 other specs |
| `xs: _*`, `private[this]`, `var x = _`, trailing `_` eta-expansion | 0 | the tree has none |

**C2, post-3.4 tree with production flags except `-source:3.9` (ratchets back at `:error`)**: **0 errors, 605 unique
warnings** (607 raw). **This is the DC-4 / FR-005 reference.** The 3.4 step cleared 32 of B2's 637: 13 E198, 8
infix, 5 wildcard, 4 illegal literal, 2 `with`.

**The 605 holds for `-source:3.9` only.** If OQ-2 keeps `-source:future` or `future-migration` (options (a) or
(c)), the post-3.4 reference is a different number, and it was not measured. B4 was run on the B3 tree, before the
3.4 step. A derived estimate, not measured: B4's 1,892 minus the 3.4-cleared categories (5 wildcard, 8 infix, 2
`with`) gives about 1,877 diagnostics, of which about 1,526 are migration errors under `future`.

| Count | Category |
| --- | --- |
| 514 | `using` for implicit arguments (warns from 3.7) |
| 69 | Manifest synthesis |
| 4 | E029 exhaustivity (2 baseline + 2 new) |
| 4 | Ascription after pattern |
| 2 / 2 / 2 / 2 | E194 / `trait App` / diffx / unused `@nowarn` |
| 6 | One-off deprecations and E121 |

### 4.5 G — the rest of the DC-2 chain (3.5 → 3.9), run in order on the post-3.4 tree, `-Ykind-projector` removed

| Step | Rewrite diff | Wall (clean `compile-all`) | Warnings during the step | Basis |
| --- | --- | --- | --- | --- |
| 3.4 | 16 files, +19 / −33 | 356 s | — | measured |
| 3.5 | **none** | 330 s | 91 (no E205) | measured |
| 3.6 | **none** | 329 s | 352 **E205** given-prioritisation sites (371 by file:line:col) + 91 | measured |
| 3.7 | **187 files, +514 / −514** (inserts `using` at every explicit implicit argument) | 612 s (the rewrite triggers a recompile) | 352 E205 + 514 `using` + 91 | measured |
| 3.8 | **none** | 327 s | 91 | measured. **Unexplained**: the repo has 57 `val Pat(x) = expr: @unchecked` pattern definitions, all in `src/test` (e.g. `EestBuilderReplay.scala:71`), which the announcement says the 3.8 rewrite turns into `.runtimeChecked`. None were rewritten and no warning was printed. Worth checking at plan time |
| 3.9 | **none** (8 grep hits for backticked `$` identifiers, all false positives) | 325 s | 91 | measured |

**F, post-chain confirmation**: hand fixes plus the 3.4 and 3.7 rewrites, production flags except `-source:3.9`,
ratchets at `:error`, `-Ykind-projector` still present, clean `compile-all` (334 s). Result: **0 errors, 91
warnings** (91 raw): 69 Manifest, 4 E029, 4 ascription-after-pattern, 2 E194, 2 `trait App`, 2 diffx, 2 unused
`@nowarn`, 6 one-off deprecations and E121.

## 5. scalafmt and scalafix

### 5.1 scalafmt (D)

Run per module (`<m>/scalafmtCheckAll`). Root on the baseline tree under 3.8.3 exited 0. A plain `;` chain stops at the first failing module, so each module was run
separately. The table separates **parse errors** from "not formatted" findings.

| Tree / version | bytes / crypto / rlp / scalanet / discovery | root (Compile / Test / IT) | Parse errors |
| --- | --- | --- | --- |
| Baseline 3.3.8 tree, **scalafmt 3.8.3** | **fail on staging today**: 3 / 11+15 / 7+3 / 25+1 / 28+20 files unformatted | **clean** | 0 |
| Rewritten tree (3.4 + 3.7), **3.8.3** | same as baseline | 14 / 17 / 1 files need formatting (rewrite output) | **0. 3.8.3 parses the 3.9-compatible tree** |
| Rewritten tree, **3.11.5** | same as baseline (+1 in discovery) | Compile **111**, IT 10, Test **aborted** | **1 file** |

**The parse error**: `src/test/.../consensus/ReorgBlockhashParitySpec.scala:81`: `[dialect scala3] template body:
indent expected but \n found`. The construct is `it should "…" taggedAs (\n UnitTest,\n ConsensusTest\n) in new
Fixture:`. The rewrites did not touch this file; it is the same as on staging. Deleting the comment line that follows
the `:` does not fix it.

Bisection on the root Test config: **3.8.3 parses it; 3.8.4, 3.8.5, 3.8.6, 3.9.0, 3.9.5, 3.9.10, 3.10.7, 3.11.0,
3.11.2, 3.11.4 and 3.11.5 all fail.** So **no 3.11.x parses the tree unmodified.** Only this one file was reported
before the Test config stopped. The scalameta parser inside scalafix (§5.2) fails on **34 test files with the same
shape**, so up to 34 sites may need a hand change before any 3.11.x parses. No such change was tried, so which 3.11.x
parses after it is **not measured**. 3.11.5 also reformats about 110 more main files that 3.8.3 accepts: formatting
drift that would need the separate format-only commit described in the spec's edge cases.

### 5.2 scalafix (E)

| Run | Result |
| --- | --- |
| **sbt-scalafix 0.13.0 on 3.9.0** | **Does not run.** Every module fails with `InvalidArgument: The ExplicitResultTypes rule was compiled with a different Scala 3 minor (3.5) than the target sources (3.9)` |
| **sbt-scalafix 0.14.9 on 3.9.0** | **Semantic rules run.** bytes and rlp pass. crypto, scalanet, discovery and root fail with `TestError` / `LinterError` findings |
| **Baseline: 0.13.0 on 3.3.8** (comparison) | **The same failures already exist on staging**: crypto 38 error lines, scalanet 60, discovery 4,074 (3.9: 4,086), root 6,489 (3.9: 6,489). Root has 194 files with diffs in both. There are 78 `DisableSyntax.return` lint errors in both (57 root, 21 discovery) |
| scalameta parse failures inside scalafix | **34 files, identical on both runs** (`template body: indent expected` from `taggedAs (…) in new Fixture:` and the same fewer-braces shape, plus 2 cases of `; expected but : found`). Examples: `ConsensusImplSpec.scala:228`, `ReorgBlockhashParitySpec.scala:81`, `CalibratePivotTDSpec.scala:301`. These already exist; they are not caused by 3.9 |

Conclusion: `formatCheck` / `formatAll` are not green on staging today, under either scalafmt (submodules) or scalafix
(all modules except bytes and rlp). SC-005 therefore depends on clearing work that already exists, not only on
migration fallout.

## 6. Open-question evidence (no decisions)

**OQ-1 `-Ykind-projector`.** 3.9.0 accepts it with a deprecation warning, renamed to `-Xkind-projector`. The tree has
**0 kind-projector sites**: no `λ[` or `Lambda[`, no type-position `*` placeholder (the 48 `*` grep hits are ScalaMock
argument matchers such as `.expects(*, *)`), and no `-Ykind-projector:underscores` use. Every module compiled with
**0 errors with the flag removed** (G3.5 to G3.9). The flag's comment ("Scala 3 replacement for kind-projector
plugin") has no remaining user.

**OQ-2 `-source:future` on 3.9.** Evidence:
- It still enforces `import x.*`: `ImportWildcard` is `(warnFrom = future, errorFrom = future)`. The tree already has
  **0** `import x._` and **0** `import a.{b => c}` renames, so there is nothing to fix today.
- It now also implies `-new-syntax` (a parser error, with about 222 sites) and makes `implicit` in every form an
  error (the 1,541 migration errors in §4.3), plus old-style `given` syntax.
- **No numbered `-source:3.N` value enforces `import x.*`.** On 3.9, `ImportWildcard` warns only from `future`. So
  `-source:3.9` gives up the `import x.*` enforcement DC-6 relies on.
- `-source:future-migration` reports the future errors as migration warnings, but still makes old control syntax a
  hard parser error.
- Options visible from the evidence, not chosen:
  - (a) keep `-source:future` and pay §4.3 (new-syntax rewrite plus the whole `implicit`→`given`/`using`
    conversion, P3a, P3b and more);
  - (b) `-source:3.9` plus a separate guard for `import x._` (scalafix `DisableSyntax` regex, or a CI grep);
  - (c) `-source:future-migration` with a `-Wconf` filter promoting only the import-wildcard and rename migration
    messages to errors (not tried here), after the new-syntax rewrite.
- The 3.9 announcement page does not mention `-source:future`.

**OQ-3 E198.** It keeps its id. `UnusedSymbolID` is errorNumber 198 at both 3.3.8 and 3.9.0 (`ErrorMessageID.scala`),
and 3.9 prints `[E198] Unused Symbol Warning`. 3.9's unused-import checker is stricter: it finds 13 new sites, which
would be **build errors** under the current ratchet. The `-rewrite` pass deletes them.

**OQ-7 published modules and cross-build.** **Five** projects apply `publishSettings` (`publish / skip := false`):
`bytes`, `crypto`, `rlp`, **`scalanet`** and **`scalanetDiscovery`** (build.sbt lines 143, 172, 201, 218, 236).
DC-7 names three. `supportedScalaVersions = List(\`scala-3\`)`, so `crossScalaVersions` has a single element. No
workflow has a Scala-version matrix (the `matrix:` blocks in `nightly`, `hive-full` and `launchpad-ppa` are images
and suites). No workflow runs `sbt publish`. **No cross-build exists today.**

## 7. Size estimate (DC-2)

| Step | Size | Basis | Notes |
| --- | --- | --- | --- |
| Pre-step hand fixes (needed for 3.9 to compile at all) | 8 sites, 8 files (2 main, 6 test) | measured | Explicit args to stdlib context bounds or partial implicit lists. **No rewrite exists**; must precede or accompany the 3.4 commit |
| Build bumps riding with the compiler | scapegoat plugin 3.3.6 → 3.4.x; sbt-scalafix 0.13.0 → 0.14.x | measured | scalafix 0.13 cannot run on 3.9 |
| 3.4 | 16 files, 52 changed lines | measured | 8 of the 16 files are under consensus paths, including `vm/OpCode.scala` (forge review). Also touches `rlp/package.scala` and `network/p2p/MessageDecoders.scala` (wire encoding) |
| 3.5 | 0 | measured | |
| 3.6 | 0 lines; **371 E205 sites (352 distinct lines)** | measured | Review only (DC-3). Per-site list with old and new alternatives: `E205-sites.txt` |
| 3.7 | 187 files, 514 lines (`using` insertion) | measured | **58 of the 187 files** are under the spec's consensus paths (`consensus/` 48, `db/storage/` 3, `domain/` 3, `mpt/` 2, `network/p2p/messages/` 2). Mechanical, but large |
| 3.8 | 0 | measured | |
| 3.9 | 0 | measured | |
| `-source:future` retention (if chosen) | about 63 files of new-syntax rewrite (mechanical) **plus 1,541 `implicit` sites** | measured (diagnostic counts) | The 1,541 overlaps with P3b scope; most has no compiler rewrite except `implicit` param → `using` (`patch` under future-migration) |
| Ratchet: 3.9-new warning categories | 2 E029, 4 ascription-after-pattern, 2 `trait App`, 2 unused `@nowarn` | measured | Small |
| scalafmt | 1 hand construct (or more scalameta-shape cases) plus a format-only commit of about 110 or more root files on 3.11.5, plus submodules that already fail | measured | |
| scalafix | 194 root files and 44 discovery files of existing findings, 78 `return` lints, 34 scalameta parse failures | measured on staging | Not migration fallout |

Cost per verification: one clean `compile-all` takes about 325–356 s on this 4-core host (612 s for the 3.7 rewrite
step). The full chain with one compile per step is about 40 minutes of compile time.

## 8. Risks seen (DC-3 / DC-8)

1. **Given prioritisation (3.7): 371 sites counted by file:line:col (352 distinct lines) in 38 files (177 main, 194
   test).** 364 of the 371 are **RLP** codecs (254 `RLPEncoder`, 110 `RLPDecoder`), across `domain/` (5 files), `network/p2p/messages`,
   `rlpx`, `mpt`, `ledger`, `db/storage`, `forkid`, `consensus/validators`, `crypto` and `rlp`. The pattern in each
   case is that `RLPImplicits` defines both `given xEncDec: RLPEncoder[X] & RLPDecoder[X] = xCodec` and an anonymous
   `given RLPEncoder[X] = xCodec`. The old rules pick `xEncDec`; 3.7 picks the anonymous one. **For the scalar codecs
   both are the same `xCodec` value.** The `Seq` variants both call `seqCodec[T]`. The other 7 sites are
   `JsonMethodDecoder` in `jsonrpc/JsonRpcController.scala:492–504`: `mcpXCodec` vs `mcpXRequestDecoder`, where the
   codec's decode delegates to the decoder. Each site still needs the DC-3 review, and RLP is consensus-critical.
2. **Rewrites inside consensus paths**: at 3.4, 8 files, including `vm/OpCode.scala` (infix to backticks on DIV,
   SDIV, MOD, SMOD, SLT, SGT, and one wildcard). At 3.7, 58 files touched by the `using` insertion. The spec's edge case (forge or beacon review
   plus a differential re-run) applies to both steps.
3. **`-98.toByte` (E028 "Illegal literal")**: 3.9 flags prefix-minus on a literal before a method call, and the
   rewrite parenthesises it to `(-98).toByte`. The sites are tests (`LegacyTransactionSpec` `pointSign`, `VMSpec`).
   The numeric value is the same either way, but these are test-expectation lines that DC-8 says to watch.
4. **Partial implicit argument lists** (E171): these compile on 3.3.8 and fail on 3.9.0 (the cause was not verified). They are compile
   errors, so the change is not silent, but the hand fix (`using ec, summon`) chooses an instance explicitly.
5. **Two new exhaustivity warnings** (`BlockFetcherState.scala:222`, `snap/ChainDownloader.scala:796`): 3.9's
   checker sees gaps 3.3 did not. Worth a look under DC-8, since it could be a missed real case.
6. **Stdlib context bounds are now `using`**: any further explicit `Ordering`, `ClassTag` or similar arguments in
   code added before the migration lands will fail the same way.
7. **Lazy vals** compile to `VarHandle` from 3.8 (announcement). There is no source impact, but it is a codegen change
   for the DC-5 differential and `[IMPORT-TIMING]`.
8. **Tooling gates are already red on staging** (scalafmt in submodules, scalafix nearly everywhere). SC-005 cannot be
   met by the migration alone.
9. The spike host ran JDK 21. CI and the release images run 25. These numbers have not been repeated on 25.

## 9. Commands run (all from `/home/dontpanic/work/spike-020`, prefixed with `flock /tmp/fukuii-sbt.lock sbt -Dsbt.color=false -Dsbt.supershell=false -Dsbt.server.forcestart=false -Dsun.net.client.defaultReadTimeout=60000 -Dsun.net.client.defaultConnectTimeout=30000` through `scripts/run.sh`)

The first B0 attempt hung for more than 10 minutes on a stalled coursier HTTP read (`util-interface-1.11.5.jar`). It
was killed and re-run with the read and connect timeouts above.

| Step | Command / build state | VERIFY |
| --- | --- | --- |
| A | `clean compile-all`, untouched 3.3.8 | ran — PASS (0 errors, 365 warnings) |
| B0 | `clean compile-all`, `scala-3` = 3.9.0 (first try) | ran — FAIL (scapegoat plugin resolution) |
| B0 | same, plus scapegoat 3.4.1 | ran — FAIL (23 errors, `bytes`) |
| B1 | plus `-source:future-migration`, E198 and unchecked at `:w` | ran — FAIL (17 errors, `bytes`) |
| B2 | `-source:3.9` | ran — FAIL (2 main), then FAIL (6 test) after 2 hand fixes, then **PASS** after 8 (0 errors, 637 unique warnings) |
| B3 | `-source:3.9 -new-syntax -rewrite` | ran — PASS (63-file rewrite) |
| B4 | `-source:future-migration` on the B3 tree | ran — PASS (1,892 unique diagnostics, 1,541 migration) |
| C0 | 3.3.8 plus `-source:3.4-migration -rewrite`, `bytes/compile` | ran — FAIL (`3.4-migration is not a valid choice for -source`) |
| C1 | 3.9.0, untouched tree plus hand fixes, `-source:3.4-migration -rewrite` | ran — PASS (16-file rewrite) |
| C2 | post-3.4 tree, `-source:3.9`, ratchets at `:error` | ran — **PASS (0 errors, 605 unique warnings)** |
| G | `-source:3.N-migration -rewrite` for N = 3.5 … 3.9, in order, `-Ykind-projector` removed | ran — PASS ×5 |
| F | post-chain tree (hand fixes + 3.4 + 3.7 rewrites), `-source:3.9`, ratchets at `:error` | ran — **PASS (0 errors, 91 warnings)** |
| D | `<m>/scalafmtCheckAll` per module: 3.8.3 baseline, 3.8.3 rewritten, 3.11.5 rewritten; root `Test/scalafmtCheck` bisection over 11 versions | ran — FAIL (parse error on ≥ 3.8.4; formatting already failing in submodules) |
| E | `<m>/scalafixAll --check` per module: 0.13.0 on 3.9, 0.14.9 on 3.9, 0.13.0 on the 3.3.8 baseline | ran — FAIL / FAIL (findings already on staging) / FAIL (baseline) |
| Tests | `testOnly` / `testEssential` | DID NOT RUN (out of GT-1 scope) |

## 10. Branch state

All build and source changes were made under temporary local commits, then dropped with `git reset --hard 42b15256f`.
The branch carries only the commit that adds this file. Nothing was pushed. Compiled output for 3.9.0 is left under
the git-ignored `*/target/scala-3.9.0/`.

## 11. Artefacts (`~/.claude/jobs/19f23359/tmp/spike-020/`)

- Logs: `A-baseline-338.log`, `B0-390.log`, `B1-390-futmig.log`, `B2-390-src39.log`, `B3-390-newsyntax-rewrite.log`,
  `B4-390-futmig-after-newsyntax.log`, `C0-…`, `C1-…`, `C2-390-post34-src39.log`, `G3.{5..9}-390-rewrite.log`,
  `D*-…`, `E*-…`.
- Inventories: `E205-sites.txt` (371 given-prioritisation sites with both alternatives), `A-inv.txt`, `B2-inv.txt`, `B4-inv.txt`, `C2-inv.txt`.
- Patches: `handfix-3.9.patch`, `B3-full.patch`, `C-rewrite-3.4.patch`, `G3.{5..9}-rewrite.patch`.
- Compiler sources quoted: `EMID-*.scala`, `SS-*.scala`, `SV-*.scala`, `MV-3.9.0.scala`, `src390/`.
- Parser: `scripts/inv.py`.
