# Spec 020 DC-4 ledger: the warning ratchet at `-source:3.9`

**Generated**: 2026-11-05, PR 6, on Scala 3.9.0 (`-source:3.9`), from `clean compile-all` with the five module cleans
(bytes, crypto, rlp, scalanet, scalanetDiscovery) run first so that every module recompiles. Counts are the compiler's
own warning headers (`[warn] -- `). DC-4 sets the bound: the total must not exceed the post-3.4 count (**605**, GT-1),
and every new category is promoted to an error or deferred with a dated `@nowarn`. R-6 is the plan; where the tree
measures differently from R-6, this file records the measurement.

## Steps (DC-2)

| Step | Flags (one-off, reverted after) | Rewrite diff | Errors | Warnings | E205 | `using` |
|---|---|---|---|---|---|---|
| 3.8 | `-rewrite -source:3.8-migration`, `clean compile-all` | **none** (`git status` shows only `build.sbt`; 0 `[patched file` lines). Not elaboration-neutral: see "Better fors" | 0 | 81 | 0 | 0 |
| 3.9 | `-rewrite -source:3.9-migration`, `clean compile-all` | **none** | 0 | 81 | 0 | 0 |
| flag | `-source:3.9`, `clean compile-all` | n/a | 0 | 81 | 0 | 0 |
| ratchet | the commits below, `clean compile-all` after cleaning every module | n/a | 0 | **70** | 0 | 0 |

Two notes on the rewrite steps:

- `compile-all` does not build the scalanet test sources. `scalanet / Test / compile` also ran under the 3.8 rewrite: no
  diff. `scalanetDiscovery / Test / compile` is **red on staging** (12 E198 errors in `scalanet/discovery/ut`, present
  before this PR: the same 12 are in the PR 5b logs) and is not part of `compile-all`, so no step here changes it. A one-off 3.8
  rewrite with E198 demoted to a warning wrote four unused-import edits in that module, which is `-Wunused:all` acting
  through `-rewrite`, not a 3.8 rewrite; they were reverted. Cleaning that module is PR 6b's.
- In Scala 3.9.0 the **last** matching `-Wconf` wins (checked with `id=E198:w` and `id=E198:error` in both orders), so
  ratchets are added below the existing ones.

## R-9 probe: `val Pat(x) = expr: @unchecked`

Probed with a one-file scratch compile (outside the repo, 3.9.0, the exact `scala3Options` of `build.sbt`) holding four
of the repo's shapes (`val JO(a) = j: @unchecked`, `val Right((a, b)) = r: @unchecked`, a `List(x)` pattern, a `Some`
pattern) and one unannotated control. At `-source:3.8-migration -rewrite`, `-source:3.9-migration -rewrite` and
`-source:3.9`:

- the four annotated forms compile with **no warning** and are **not rewritten**;
- the control `val JO(a) = j` warns ("pattern's type JO is more specialized than the right hand side expression's
  type J") and is the only line the 3.8 rewrite changes (to `j.runtimeChecked`).

So 3.9.0 accepts `: @unchecked` without a warning. No action on the 57 test sites. The tree has no unannotated site of
the control's shape (the 81 warnings at `-source:3.9` contain none).

## Deferrals (user-confirmed 2026-10-17; dated 2026-11-05)

| # | Site | Warning | Reason | Date | Owner | Follow-up |
|---|---|---|---|---|---|---|
| 1 | `src/main/.../crypto/EcKeyGen.scala`, `object EcKeyGen extends App` | `trait App is deprecated since 3.8.0` | `App` to `def main` changes initialisation order (delayedInit). Under `crypto/` that is bucket C, so forge decides. `@annotation.nowarn("cat=deprecation")` is on the object, the narrowest site that leaves the template untouched | 2026-11-05 | forge | #1715: replace `App` with `def main`, keeping the key-generation output unchanged |
| 2 | `src/main/.../crypto/SignatureValidator.scala`, `object SignatureValidator extends App` | same | same | 2026-11-05 | forge | #1715 |
| 3 | `src/main/.../blockchain/sync/regular/BlockFetcherState.scala`, `bodiesAreOrderedSubsetOfRequested` | `[E029]` match may not be exhaustive; counter-example `(_, Seq(_, _*))` | The three cases look complete, so it may be a false positive on `Seq()` / `+:`, but adding a case is a behaviour change, which DC-8 forbids here. `@annotation.nowarn("id=E029")` on the method (the match is its whole body) | 2026-11-05 | wraith to analyse; prism to review (non-consensus sync code) | #1716: decide whether the counter-example is reachable; add the case or keep the suppression with a proof |
| 4 | `src/main/.../blockchain/sync/snap/ChainDownloader.scala`, `matchOrderedSubsequence` | same | same | 2026-11-05 | same | #1716 |

Not deferred, still emitted and untouched (pre-existing): the other two E029 (`scalanet/.../V5DemuxResponder.scala:43`,
`regular/BlockFetcher.scala:100`), 2 E194 (`scalanet/.../IOValues.scala`), 1 E121 (`ByteCodeCoordinator.scala:541`), the
Manifest synthesis deprecations, and the one-off deprecations below.

## Final per-category table (measured)

"Step (c)" is `-source:3.9` before any ratchet commit; "Final" is after the ratchet commits. "Ref" is GT-1 / R-6.

| Category | Ref | Step (c) | Final | Disposition | Ratchet |
|---|---|---|---|---|---|
| Implicit params without `using` | 514 | 0 | 0 | cleared by the 3.7 rewrite (PR 5a) | **error**: `msg=Implicit parameters should be provided with a `` `using` `` clause` |
| E205 given prioritisation | 371 | 0 | 0 | cleared by R-5 / DC-3 | **error**: `id=E205` (inert at `-source:3.9`, which never emits it; it guards `-migration` re-runs and lower source levels) |
| E198 unused import / symbol | 13 | 0 | 0 | already an error | unchanged |
| E028 illegal literal | 4 | 0 | 0 | cleared by the 3.4 rewrite | **error**: `id=E028` |
| Alphanumeric infix | 8 | 0 | 0 | cleared by the 3.4 rewrite | **error**: `msg=Alphanumeric method .* is not declared infix` |
| `_` type wildcard | 5 | 0 | 0 | cleared by the 3.4 rewrite | **error**: `` msg=`_` is deprecated for wildcard arguments of types `` |
| `with` type operator (E003) | 2 | 0 | 0 | cleared by the 3.4 rewrite | **error**: `msg=with as a type operator has been deprecated` |
| Inaccessible-companion implicits | 0 | 0 | **0 at 3.9.0** | none exist (`grep -ci inaccessible` of every log = 0) | none needed |
| Ascription after pattern | 4 | **5** | 0 | fixed. 3 test sites (one added after GT-1, by #1697) and 2 in `vm/PrecompiledContracts.scala` (own commit, javap-checked, for forge) | **error**: `msg=Type ascriptions after patterns other than`. At 3.9.0 the construct is still accepted with a warning ("are no longer supported"), so the ratchet is what stops it coming back |
| `trait App` deprecated | 2 | 2 | 0 emitted, 2 suppressed | deferred (rows 1 and 2) | narrow `@nowarn` |
| Unused `@nowarn` | 2 | 2 | 0 | deleted (`CalibratePivotTDSpec`, `CommonFakePeer`) | `-Wunused:all` reports any new one |
| E029, new at 3.9 | 2 | 2 | 0 emitted, 2 suppressed | deferred (rows 3 and 4) | narrow `@nowarn` |
| E029, pre-existing | 2 | 2 | 2 | unchanged | none |
| Manifest synthesis (deprecation) | 69 | 59 | 59 | unchanged (R-6's 69 was measured on an older tree; `cat=deprecation` cannot be promoted while these exist) | none |
| E194 extension never selected | 2 | 2 | 2 | unchanged | none |
| E121 unreachable case | 1 | 1 | 1 | unchanged | none |
| diffx `DiffMatcher` deprecated | 2 | 2 | 2 | unchanged | none |
| One-off deprecations (see below) | 5 | 4 | 4 | unchanged; one fewer than GT-1 (below) | none |
| `-Ykind-projector` option warning | 13 per `compile-all` | 0 | 0 | the flag was dropped in PR 3 (OQ-1: no code needs it); no occurrence in any log of this PR | none |
| **Total emitted** | 605 (post-3.4 ceiling), 365 (3.3.8) | **81** | **70** (+4 suppressed sites) | | |

Checks of the sum: step (c) is 59 Manifest + 5 ascription + 4 E029 + 2 E194 + 2 `trait App` + 2 diffx + 2 unused `@nowarn` +
1 E121 + 4 one-off deprecations = 81. Final is 59 Manifest + 2 E029 + 2 E194 + 2 diffx + 1 E121 + 4 one-off deprecations = 70.
R-6 expected about 85 plus 4 suppressed sites; this tree is lower because Manifest synthesis measures 59, not 69.

### One-off deprecations: the four current sites, and the one that went

GT-1 (§4.1) lists five one-off deprecations, and its "6" at line 174 is those five plus the E121. This tree has four, at

| Site | Warning |
|---|---|
| `scalanet/src/.../peergroup/ExternalAddressResolver.scala:29` | `new URL(url)`: constructor `URL` is deprecated |
| `src/main/.../transactions/PendingTransactionsManager.scala:233` | `CacheBuilder.expireAfterWrite` is deprecated: see corresponding Javadoc |
| `src/test/.../blockchain/sync/snap/SnapResumeBoundarySpec.scala:103` | `Set.--` is deprecated since 2.13.0 |
| `src/rpcTest/.../rpcTest/RpcApiTests.scala:1204` | web3j `Admin` is deprecated |

The fifth, GT-1's `HealResponseProcessing.scala:354` (`copyArrayToImmutableIndexedSeq`: implicit Array to `IndexedSeq`
conversion in `mptStorage.multiGetNodes(toCheck.map(_._2.toArray))`), is the one that went. The logs show it: it is in the PR 3
baseline, `d-source34.log` and the PR 4 logs (`s020-pr4-logs/c-compile.log`, 2026-10-28), and absent from every log from PR 5a on
(`s020-pr5-logs/a-rewrite37.log`, `c-compile37.log`, `f-rebase.log`, `s020-pr5b-logs/base2-compile.log`, and this PR's). Git
history of the file between those logs: `cabda59b5` (spec 022 slice 1, #1682, 2026-10-29) changed the `toCheck` definition the
call uses, and the same call now sits at `HealResponseProcessing.scala:632` without a warning. This ledger does not claim more
than that: it is a spec 022 change that removed the warning, not a PR 6 one.

## Better fors: the silent 3.8 change

The 3.8 and 3.9 rewrite steps printed no diff and no warning, but they are **not elaboration-neutral**. From `-source:3.8` the
compiler enables SIP-62 "better fors" (`SourceVersion.enablesBetterFors = isAtLeast(3.8)`, found in the forge and beacon review
of PR 6; it was off at 3.7 without `-preview`). It changes how a `for` comprehension is desugared, and no rewrite or warning
announces it: a trailing `map(identity)` after the last generator is dropped, and a value definition inside the `for`
(`y = ...`) is desugared without the tuple wrapping.

Evidence:

- **Jar comparison (forge):** 159 jar entries differ between the 3.7 and 3.9 builds.
- **Receiver multisets (forge):** the `map` / `flatMap` / `foreach` / `withFilter` receivers were compared across every changed
  class. The net change is `map` removals on lawful single-valued monads only (Either, Option, Try, EitherT, IO, Resource, scodec
  `Attempt`), where `fa.map(identity)` is `fa`. There is no change on List, Seq, Vector, Iterator, Array, Map, Future, Pekko
  `Source` or fs2.
- **Shared ETC/ETH classes touched:** `BlockExecution`, `StxLedger`, `PrecompiledContracts` (the `Bn128Add` and `Bn128Mul`
  lambdas), `BlockchainReader`, `ForkIdValidator`, `GenesisDataLoader`, `TxAdmission`, and `BlockAccessList` (ETH).
- **Scratch confirmation (this review round):** `for { x <- o; y = x + 1 } yield y` on an `Option` and on a `List`, compiled at
  `-source:3.7` and `-source:3.9` and read with `javap -c`: the `Option.map` call sites go from 3 to 1 and the `List.map` sites
  from 2 to 1, and the `Tuple2` allocations disappear. So the effect is real and also reaches `List`-shaped value definitions;
  forge's receiver comparison found no such site in this tree.
- **Proof of behaviour (DC-5, run by the main session on this branch):** `testEssential` 7,219 / 0; the differential against the
  0.10.0 GT-2 jar, all runs passing (Mordor compute-only within budget, Ephemery faster; PR 6 against its PR 5b staging base equal
  or faster everywhere); ethereum/tests 66 / 0 (2 canceled).

The `-source:3.9` comment in `build.sbt` says so, so nobody reads the "no rewrite diff" steps as a no-op.

## Promotions: the exact filters

```
"-Wconf:id=E205:error"
"-Wconf:id=E028:error"
"-Wconf:msg=Implicit parameters should be provided with a `using` clause:error"
"-Wconf:msg=Alphanumeric method .* is not declared infix:error"
"-Wconf:msg=`_` is deprecated for wildcard arguments of types:error"
"-Wconf:msg=with as a type operator has been deprecated:error"
"-Wconf:msg=Type ascriptions after patterns other than:error"
```

R-6's draft `using` filter dropped the backticks. It does not match: the compiler's text is
``Implicit parameters should be provided with a `using` clause``. Each filter was checked both ways, on a scratch file
at `-source:3.9` (a warning without the flag, an error with it; E205 at `-source:3.7-migration`), and in the real build
with one temporary `src/main` file holding the four 3.9-visible constructs, which failed `sbt compile` with exactly five
errors (E028, E003, `_`, `using`, infix). The file was deleted before the commit.

The ascription filter was added in the review round. The compiler's message is multi-line ("Type ascriptions after patterns
other than:" then two bullets, then "are no longer supported"). The filter uses the first line without its colon, since a
colon would end the filter. A scratch file `val (a, b): (Int, String) = t` at `-source:3.9` warns without the flag and errors
with it (a filter on the last line also matches; the same construct with the ascription on the right-hand side stays clean).
A temporary `src/main` file with that construct failed `sbt compile` with exactly one error; it was deleted before the commit.
