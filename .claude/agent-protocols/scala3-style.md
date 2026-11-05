# Scala 3 Style Protocol

Standards for idiomatic Scala 3 code in the fukuii codebase. Agents apply these
when writing new code and opportunistically when touching existing code (see
`inline-cleanup.md` for scope discipline). Each standard has a grep pattern so
regressions are detectable.

Used by: MITHRIL (primary), LOOM, WRAITH, all agents writing Scala
Referenced by: mithril.md, inline-cleanup.md

---

## The ratchet model

Each standard below has a current state (enforced / partially enforced / not yet enforced).
Once a standard is fully enforced via scalafix rule or compiler flag, it becomes a build error.
That is the definition of done for that standard — not "we cleaned it once."

Counts below were run on the tree at spec 020 PR 7 (2026-11-05, Scala 3.9.0, `-source:3.9`). Where the compiler or
scalafix enforces a standard, the enforcing line is named; `sbt formatCheck` (scalafix `--check` on bytes, crypto, rlp
and root; scalafmt on all six modules) runs in CI.

---

## Standards

### S1 — No `return` statements
**Status:** Enforced for the root project, bytes, crypto and rlp: `DisableSyntax.noReturns = true` in `.scalafix.conf`,
checked by `formatCheck` in CI (spec 020 PR 6b-2 removed the findings). Two groups of `return` remain on purpose or by
gap:
- 9 sites in `vm/PrecompiledContracts.scala`, each marked `// scalafix:ok DisableSyntax.return` (consensus and crypto
  code; where a DEFER comment is present it says an expression rewrite is byte-level risky). forge decides, not a style
  sweep.
- 21 sites in `scalanet/discovery` (`Discv4SyncResponder`, `Discv5SyncResponder`, `Packet`). `formatCheck` runs
  scalafix on neither scalanet module, so nothing stops a new one there.

```bash
# every `return` keyword in main sources of every module; comment-only lines dropped
grep -rnw "return" src/main bytes/src/main rlp/src/main crypto/src/main scalanet/src scalanet/discovery/src \
    --include="*.scala" | grep -vE '^[^:]+:[0-9]+:\s*(//|\*|/\*)'
# 2026-11-05: 35 hits = 9 scalafix:ok (PrecompiledContracts) + 21 (scalanet/discovery) + 5 words inside a
# trailing comment or string (StdNode x2, ReferenceCountNodeStorage, CallErrors, BlockHeader)
```

(The older form `grep "\breturn\b" src/main` printed 315 lines, nearly all `@return` in scaladoc, so it was no ratchet.)

Fix: restructure as an expression (`if cond then a else b`, a `match`, an `Either`/`Option` combinator). Where that
cannot be done, `scala.util.boundary` and `break`, under S12: a `break` is not a `return`.

---

### S2 — No `null`
**Status:** Not yet audited. The grep below prints 160 lines (2026-11-05).

```bash
grep -rn "\bnull\b" src/main/ --include="*.scala" | grep -v "//\|test\|@null"
# Target: 0 hits (use Option, empty collections, sentinel values)
```

Fix: `null` → `Option`, `None`, or appropriate empty value.
Ratchet: `DisableSyntax.noNulls = true` in `.scalafix.conf` (after audit).
Exception: JNI/interop boundary code — narrow `@nowarn` + comment.

---

### S3 — `given/using` over `implicit`
**Status:** P3a done (W2-P3a, `7210311bb`, 334 sites), but not "complete": 44 lines with `implicit val/def/lazy val` remain
in `src/main`. The build is `-source:3.9`, which still accepts `implicit`. Spec 020 made the 3.7 prioritisation sites
explicit (DC-3, `specs/020-scala-3-9-lts-migration/dc3-sites.md`) and rewrote every `implicit` parameter list that
needed a `using` (PR 5a); an implicit parameter passed without `using` is now a build error (see the `-source`
mapping). What stays `implicit` stays on purpose or by decision:
- override-chain definitions: `ioRuntime` in `PeerDiscoveryManagerBuilder` and in `PortForwardingBuilder` (both in
  `NodeBuilder.scala`; test fixtures override it) and `JsonMethodsImplicits.formats` (`RateLimit` overrides it). `given`
  is final, so they must stay (see `scala3-given-migration.md` G3);
- `implicit def` conversions (`UInt256` x5, `FunctorOps`) and the codec and serializer instances;
- the Pekko-HTTP `Json4sSupport` and `JsonRpcHttpServer` members, whose signatures follow the library.

```bash
grep -rn "implicit val\|implicit def\|implicit lazy val" src/main/ --include="*.scala" \
  | grep -v "consensus/\|vm/\|crypto/\|//\|@nowarn\|not given.*override"
# 2026-11-05: 38 hits (44 before the filters). The `not given.*override` comments the filter expects are gone from
# this tree: new override-chain sites need their own comment saying why (G3).
```

Ratchet: none. A `GivenUsing` scalafix rule is not in `.scalafix.conf` and was not added by spec 020: the remaining
sites are decided one by one, not rewritten as a sweep.

**Scalafix rule ordering (CRITICAL):** Apply S3 before S4. Extension method bodies
that call `given`-converted implicits need the new import form (`import X.{given, *}`)
already in place. Applying S4 (extension methods) first creates false positives: the
extension body compiles against the old `implicit` import but fails after S3 converts
the implicit to a `given`. Order: **S3 → S4 → S5 → S6**.

**Operational gotchas (discovered P3a):** See `scala3-given-migration.md` for:
- G1: `import X.{given, *}` required at call sites after companion conversion
- G2: anonymous `given` instances need explicit type annotations
- G3: `given` is final — override chains must stay `implicit val/lazy val`

---

### S4 — Extension methods over `implicit class`
**Status:** Done for the extension-type sites (spec 020 PR 5b, 2026-11-04). The conversion wrappers are kept by decision
(note below).

```bash
grep -rnE "implicit +((final|sealed|abstract|private(\[[A-Za-z_]+\])?|protected(\[[A-Za-z_]+\])?) +)*class\b" \
    src/main bytes/src/main rlp/src/main crypto/src/main scalanet/src scalanet/discovery/src --include="*.scala" \
  | grep -vE '^[^:]+:[0-9]+:\s*(//|\*)' \
  | grep -v "Enc(" | grep -vE "ByteString(Self|Array|Byte)Element|BufferConversionOps"
# Target: 0 hits (0 on 2026-11-05, both forms). Main sources of every module, the published ones included (bytes, rlp, crypto, scalanet,
# scalanetDiscovery); modifier forms included; only comment-only lines are dropped, not lines with a trailing `//`.
# `implicit` and `class` on different lines, or an annotation between them:
rg -U --pcre2 -n -g '*.scala' 'implicit\b(?:\s|@\w+(?:\([^)]*\))?)+(?:(?:final|sealed|abstract|private(?:\[\w+\])?|protected(?:\[\w+\])?|case)\s+)*class\b' \
  src/main bytes/src/main rlp/src/main crypto/src/main scalanet/src scalanet/discovery/src \
  | grep -v "Enc(" | grep -vE "ByteString(Self|Array|Byte)Element|BufferConversionOps"
```

Fix: `implicit class Foo(val x: T) { def bar = ... }` → `extension (x: T) def bar = ...`

**Note (2026-11-04, spec 020 PR 5b):** the conversion wrappers stay `implicit class` by user decision: the 43 message
wrappers (`XEnc(val underlyingMsg) extends MessageSerializableImplicit`), the 12 other encoders, consensus ones among them (`BlockHeaderEnc`,
`BlockBodyEnc`, `AccountEnc`, `WithdrawalEnc`, `BlockEnc`, `OmmersSeqEnc`, `ECDSASignatureEnc`, `SignedTransactionEnc`,
the `ReceiptBloom*` ones, `MptNodeEnc`) and bytes' `ByteString{Self,Array,Byte}Element`. Each adds a supertype
(`RLPSerializable`, `MessageSerializableImplicit`, `ByteStringElement`) that an `extension` cannot give, so the grep
above excludes them by name (`Enc(`, and the bytes and scalanet names). The exemption is by name, so a new wrapper
with another name shows up as a hit: decide it explicitly. **They must be converted (for example to `given Conversion`) before any move
to `-source:future`, which rejects `implicit class`.** The first version of the grep (`implicit class\b`) missed the
modifier forms (`implicit final class`, `implicit private class`); the five sites it hid (`RichMining`, `RichResponse`,
`ConnectedPeersOps` in `src/main`; `DiscoveryNetwork.ChannelOps` and `Discv5SyncResponder.IterableHasAsScala` in
scalanetDiscovery) were converted in PR 5b, C6. In the published modules the only `implicit class` left are bytes'
`ByteString{Self,Array,Byte}Element` wrappers and scalanet's dead `BufferConversionOps`.

---

### S5 — Scala 3 enum over sealed trait + case objects
**Status:** Not yet started (Part 3d, opportunistic). The grep below prints 113 lines (2026-11-05).

Target candidates: `SyncPhase`, `BlacklistReason`, `ForkId` codes.
```bash
grep -rn "sealed trait\|sealed abstract class" src/main/ --include="*.scala" | grep -v "consensus/\|vm/"
```

Fix: Replace where all cases are objects with no fields, or simple value enums.
**Skip enums with methods or fields unless clearly cleaner.**

---

### S6 — `A & B` intersection syntax over `A with B` in types
**Status:** Done and enforced. The self-type grep below prints 0 lines (2026-11-05), and the build has
`-Wconf:msg=with as a type operator has been deprecated:error`, so `with` as a type operator in any position is a
compile error (the 3.4 rewrite cleared the 2 sites, spec 020 PR 3). The old "337 occurrences" figure and the
`-source:future` ratchet no longer apply: the build is `-source:3.9` (see the `-source` mapping).

```bash
grep -rn "self:.*with\b" src/main/ --include="*.scala" | grep "=>"
# Target: 0 hits (0 on 2026-11-05)
```

Fix: `self: A with B =>` → `self: A & B =>`. `with` in a class `extends` clause is unchanged and still correct.

---

### S7 — No `asInstanceOf`
**Status:** Not yet audited. The grep below prints 18 lines (2026-11-05).

```bash
grep -rn "asInstanceOf\[" src/main/ --include="*.scala" | grep -v "consensus/\|vm/\|crypto/\|//"
# Target: 0 hits outside consensus paths
```

Fix: Replace with pattern matching or type-safe alternatives.
Each occurrence is bucket C (semantic risk) — prove behavior-preserving before fixing.
Route to FORGE if in consensus paths.

---

### S8 — No `isInstanceOf` outside pattern matching
**Status:** 8 occurrences (2026-11-05; 59 in the June audit). Part 3c backlog.

```bash
grep -rn "isInstanceOf\[" src/main/ --include="*.scala" | grep -v "//\|consensus/\|vm/"
# Target: 0 hits
```

Fix: Replace with `match { case _: T => }` or sealed ADT exhaustiveness.
Bucket B/C depending on context.

---

### S9 — No `println` / `System.out` in main sources
**Status:** 7 occurrences (2026-11-05; 28 in the June audit). C5 chore.

```bash
grep -rn "println\|System\.out\|System\.err" src/main/ --include="*.scala" | grep -v "//\|Benchmark"
# Target: 0 hits
```

Fix: Replace with SLF4J logger (`ctx.log.info(...)` in Typed actors, `logger.info(...)` elsewhere).
Ratchet: DisableSyntax can flag `println` — add after cleanup.

---

### S10 — Braceless syntax for new code
**Status:** The scalafmt config is in place (`rewrite.scala3.removeOptionalBraces = true` in `.scalafmt.conf`). No mass rewrite.

New code: use braceless where scalafmt accepts it.
Do not rewrite existing brace-based code in bulk — creates noise in diffs.
Opportunistic: if you're rewriting a method body anyway, write it braceless.

---

## Writing new Scala 3 code (standards to apply from the start)

```scala
// ✅ Preferred
given config: BlockchainConfig = ...
extension (block: Block) def isValid: Boolean = ...
enum SyncPhase { case Idle, Fetching, Healing }
type BlockHash = opaque type ByteString

// ❌ Avoid
implicit val config: BlockchainConfig = ...
implicit class BlockOps(val block: Block) { def isValid = ... }
sealed trait SyncPhase; case object Idle extends SyncPhase
```

---

### S11 — Opaque type full-layer propagation: no `.value` inside a layer boundary

**Status:** Not yet enforced. ~20 violations identified in sync layer (audit June 2026).
See `.local/best-practices/scala/type-safety.md` for full pattern catalogue.

The core rule: an opaque type (`TrieRoot`, `CodeHash`, `BlockHash`) must flow through
every var, val, Map key, collection element, and Command ADT field within a layer.
`.value` is called **exactly once** — inside the type's `RLPCodec`, `DataSource`
serialiser, or wire-encoding call. Calling `.value` anywhere else is the "half-typed"
anti-pattern: the type exists at the surface but `.value` scatters the underlying raw
type through the internals, making root-vs-hash transposition bugs invisible to the compiler.

**True boundaries where `.value` is correct:**
- `RLPCodec[T]` xmap extractor (inside companion `given`)
- `DataSource` `put` serialiser lambda
- Wire-encoding call (`encode(root.value)` in `MessageCodec`)
- `toHexString` / `toArray` for logging only

**Half-typed anti-pattern:**
```scala
// ❌ Type exists at the entry point, then discarded immediately
class HealingCoordinator(stateRoot: ByteString) {  // param should be TrieRoot
  var currentRoot: ByteString = stateRoot          // var should be TrieRoot
  def refresh(r: TrieRoot): Unit = currentRoot = r.value  // leaks inside the layer
}
```

**Correct:**
```scala
// ✅ TrieRoot flows through; .value only at the RocksDB/RLP boundary
class HealingCoordinator(stateRoot: TrieRoot) {
  var currentRoot: TrieRoot = stateRoot
  def refresh(r: TrieRoot): Unit = currentRoot = r
  def persist(): Unit = db.put(currentRoot.value.toArray)  // .value at boundary
}
```

**Known violations (audit June 2026):**
- `StorageRecoveryActor` — 7 Command ADT fields and actor var using `ByteString` instead of `TrieRoot`
- `CombinedRecoveryScanner` — constructor param and `Vector[(ByteString, ByteString)]` where second element is a storage root
- `SyncStateSchedulerActor` — `private var currentStateRoot: ByteString`
- `BytecodeRecoveryActor`, `BlockFetcher` — mid-layer `.value` into mutable collections

**Greps:**
```bash
# Detect mid-layer .value calls (adjust path)
grep -rn "\.value\b" src/main/scala/com/chipprbots/ethereum/blockchain/sync/ \
  --include="*.scala" | grep -v "//\|rlp\|encode\|RocksDB\|put(\|wire\|toHex\|toArray"

# Detect ByteString vars/vals that should be opaque types
grep -rn ": ByteString = .*\.value\b" src/main/ --include="*.scala"
grep -rn "var.*: ByteString\|val.*: ByteString" src/main/scala/com/chipprbots/ethereum/blockchain/sync/ \
  --include="*.scala" | grep -v "//\|node\|hash\|path\|code\|key\|Builder"

# Detect untyped tuple pairs (both elements same ByteString but semantically distinct)
grep -rn "Seq\[(ByteString, ByteString)\]\|Vector\[(ByteString, ByteString)\]" \
  src/main/ --include="*.scala" | grep -v "//"
```

The four greps are leads, not a violation count. On the 2026-11-05 tree they print 355, 4, 24 and 41 lines (in the order
above); read each hit before acting on it.

Ratchet: none yet — enforce via PRISM review and MITHRIL sweep after LOOM CAPSTONE.

---

### S12 — `boundary`/`break`: no handler between a closure's `break` and its `boundary`

**Status:** Rule, added with spec 020 PR 7. `return` is banned (S1), so `scala.util.boundary` is the replacement where
an `if/else` or `match` restructuring is not enough. A `break` has a trap that `return` did not.

**Mechanism** (`javap` on scala-library 3.9.0):
`scala.util.boundary$Break extends java.lang.RuntimeException`, while `scala.runtime.NonLocalReturnControl`, the
exception a `return` inside a closure throws, `extends scala.util.control.ControlThrowable`. `NonFatal` and `Try` let a
`ControlThrowable` through, so a `return` crossed them. A `RuntimeException` they catch. A `break` in the same method as
its `boundary` is compiled to a jump and throws nothing; a `break` inside a closure (a lambda, the body of `map`,
`foreach` or `collect`, a by-name argument, a `Future` or `IO` block) is a throw.

**Rule:** between a `break` that sits inside a closure and its `boundary` there must be no `Try`, no `NonFatal` handler,
no `catch` on `Exception` or `RuntimeException` (or a wildcard `catch case _`), no `IO` or `Future` combinator that
recovers (`recover`, `handleError`, `attempt`) and no actor behaviour whose supervision sees the exception. Any of them
swallows the `break`: the method carries on, or the actor sees a failed message, where the code reads as an early exit.

**Prefer restructuring** to a `boundary` at all: an `if/else`, a `match`, `Either`/`Option` combinators, a `fold`.
Where a `boundary` stays, keep every `break` in the method body itself and put none in a lambda. `return` remains
banned (scalafix `DisableSyntax.noReturns`).

**Where it applies** (6 files use `boundary` on the 2026-11-05 tree):

```bash
grep -rlE "scala\.util\.(boundary|\{[^}]*boundary)|^\s*boundary(\[[^]]*\])?(:| *\{)" \
    src/main bytes/src/main rlp/src/main crypto/src/main scalanet/src scalanet/discovery/src --include="*.scala"
# 6 files: jsonrpc/EthSimulateService, jsonrpc/EthSimulateJsonMethodsImplicits,
#          blockchain/sync/snap/SNAPSyncController, and snap/controller/{PivotSelector,SnapResumePlanner,SnapFinalization}
```

`EthSimulateService`'s existing boundaries were checked against this rule on 2026-11-05 and are safe. The other five
files were not part of that check: before editing a `break` or adding one there, apply the rule to the whole method.
In `snap/controller` the `boundary` bodies sit in actor behaviours, where the supervision clause above matters.

---

## `-source:3.9`: what the build enforces, and where

The build compiles with `-source:3.9` (`scala3Options` in `build.sbt`). It replaced `-source:future`. INF-009
(decision 2) gives the reason: `future` on 3.9 would have cost about 1,541 implicit-to-given sites after a 63-file
syntax rewrite, and it rejects `implicit class` (the conversion wrappers of S4). The one thing `future` was kept for,
`import x.*`, is enforced elsewhere: scalafix `DisableSyntax.regex` (`.scalafix.conf`, ids `scala2ImportWildcard` and
`scala2ImportRename`) and `scripts/ci/check_scala_imports.sh` in CI. The ledger of every warning category and each
deferral is `specs/020-scala-3-9-lts-migration/dc4-ledger.md`.

| Level | What it changes | State of the tree |
|-------|-----------------|-------------------|
| 3.4 | alphanumeric method in infix position must be declared `infix`; `_` as a type wildcard (write `?`); `with` as a type operator (write `&`); illegal literal `-98.toByte` (E028, write `(-98).toByte`) | cleared by the 3.4 rewrite (PR 3); each is a build error through `-Wconf` (`warning-ratchet.md`) |
| 3.5 | no source change | none |
| 3.6, 3.7 | given prioritisation changes; an implicit parameter list passed without `using` is flagged | 371 prioritisation sites made explicit (DC-3, PR 4); 564 `using` inserted in 197 files (PR 5a); E205 and the `using` message are build errors |
| 3.8 | SIP-62 "better fors" (enabled from `-source:3.8`) | no rewrite diff and no warning, yet **not elaboration-neutral** (below) |
| 3.9 | no rewrite diff; nothing new enforced | `-source:3.9` set (PR 6) |

**Better fors (SIP-62), the silent change.** From `-source:3.8` a `for` comprehension is desugared differently, and no
rewrite or warning announces it: a trailing `map(identity)` after the last generator is dropped, and a value
definition in the `for` (`y = ...`) is desugared without the tuple wrapping. A review of the 3.7 and 3.9 jars
(forge, spec 020 PR 6; `dc4-ledger.md`, "Better fors") found 159 differing entries and a net change of `map` removals
on lawful single-valued monads only (`Either`, `Option`, `Try`, `EitherT`, `IO`, `Resource`, scodec `Attempt`), where
`fa.map(identity)` is `fa`. It found no change on `List`, `Seq`, `Vector`, `Iterator`, `Array`, `Map`, `Future`, Pekko
`Source` or fs2. The effect is real on `List`-shaped value definitions too (checked with `javap` on a scratch file), so a
new `for` over a type whose `map` is observable (a custom `map` with a side effect) needs the same review.

**Writing new code at this level:** `(-98).toByte`; `a.m(b)` or a backticked `` a `m` b `` or an `infix` declaration;
`?` for a wildcard type argument; `A & B`; `val (a, b): (A, B) = e` becomes an ascription on `e`; `using` at a call site
that passes an implicit argument explicitly; `import x.*` or `import x.{given, *}`.

---

## Consensus-critical exception

Standards S3–S11 do NOT apply to `consensus/`, `vm/`, `crypto/`, `domain/` without
specialist review. Those paths require FORGE (ETC) or BEACON (ETH) before any
idiom modernization. See `consensus-change-protocol.md`. S12 is a safety rule and applies wherever `boundary` is used.
