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

---

## Standards

### S1 — No `return` statements
**Status:** Partially enforced. DisableSyntax.noReturns not yet in `.scalafix.conf` (C2 chore).

```bash
grep -rn "\breturn\b" src/main/ --include="*.scala" | grep -v "//\|\""
# Target: 0 hits
```

Fix: restructure as expression. `if (cond) return` → `if (!cond) { ... }`.
Ratchet: `DisableSyntax.noReturns = true` in `.scalafix.conf`.

---

### S2 — No `null`
**Status:** Not yet audited.

```bash
grep -rn "\bnull\b" src/main/ --include="*.scala" | grep -v "//\|test\|@null"
# Target: 0 hits (use Option, empty collections, sentinel values)
```

Fix: `null` → `Option`, `None`, or appropriate empty value.
Ratchet: `DisableSyntax.noNulls = true` in `.scalafix.conf` (after audit).
Exception: JNI/interop boundary code — narrow `@nowarn` + comment.

---

### S3 — `given/using` over `implicit`
**Status:** COMPLETE — W2-P3a (`7210311bb`, 334 sites). Override-chain sites
intentionally kept as `implicit val/lazy val` (see `scala3-given-migration.md` G3).

```bash
grep -rn "implicit val\|implicit def\|implicit lazy val" src/main/ --include="*.scala" \
  | grep -v "consensus/\|vm/\|crypto/\|//\|@nowarn\|not given.*override"
# Target: only the intentional override-chain sites (annotated with "not given" comment)
```

Ratchet: GivenUsing scalafix rule — add to `.scalafix.conf` after confirming
override-chain `implicit val` sites are excluded from the rule.

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
grep -rnE "implicit +((final|sealed|abstract|private(\[[A-Za-z_]+\])?|protected(\[[A-Za-z_]+\])?) +)*class\b" src/main/ --include="*.scala" | grep -v "//" | grep -v "Enc("
# Target: 0 hits (same-line forms, modifiers included; the old `consensus/` and `vm/` excludes are gone)
# `implicit` and `class` on different lines, or an annotation between them:
rg -U --pcre2 -n -g '*.scala' 'implicit\b(?:\s|@\w+(?:\([^)]*\))?)+(?:(?:final|sealed|abstract|private(?:\[\w+\])?|protected(?:\[\w+\])?|case)\s+)*class\b' src/main | grep -v "Enc("
```

Fix: `implicit class Foo(val x: T) { def bar = ... }` → `extension (x: T) def bar = ...`

**Note (2026-11-04, spec 020 PR 5b):** the conversion wrappers stay `implicit class` by user decision: the 54 message
wrappers (`XEnc(val underlyingMsg) extends MessageSerializableImplicit`), the consensus encoders (`BlockHeaderEnc`,
`BlockBodyEnc`, `AccountEnc`, `WithdrawalEnc`, `BlockEnc`, `OmmersSeqEnc`, `ECDSASignatureEnc`, `SignedTransactionEnc`,
the `ReceiptBloom*` ones, `MptNodeEnc`) and bytes' `ByteString{Self,Array,Byte}Element`. Each adds a supertype
(`RLPSerializable`, `MessageSerializableImplicit`, `ByteStringElement`) that an `extension` cannot give, so the grep
above excludes them with `grep -v "Enc("`. **They must be converted (for example to `given Conversion`) before any move
to `-source:future`, which rejects `implicit class`.** The first version of the grep (`implicit class\b`) missed the
modifier forms (`implicit final class`, `implicit private class`); the five sites it hid (`RichMining`, `RichResponse`,
`ConnectedPeersOps` in `src/main`; `DiscoveryNetwork.ChannelOps` and `Discv5SyncResponder.IterableHasAsScala` in
scalanetDiscovery) were converted in PR 5b, C6. In the published modules the only `implicit class` left are bytes'
`ByteString{Self,Array,Byte}Element` wrappers and scalanet's dead `BufferConversionOps`.

---

### S5 — Scala 3 enum over sealed trait + case objects
**Status:** Not yet started (Part 3d, opportunistic).

Target candidates: `SyncPhase`, `BlacklistReason`, `ForkId` codes.
```bash
grep -rn "sealed trait\|sealed abstract class" src/main/ --include="*.scala" | grep -v "consensus/\|vm/"
```

Fix: Replace where all cases are objects with no fields, or simple value enums.
**Skip enums with methods or fields unless clearly cleaner.**

---

### S6 — `A & B` intersection syntax over `A with B` in self-types
**Status:** 337 occurrences (E003 warning). Part 1 backlog.

```bash
grep -rn "self:.*with\b" src/main/ --include="*.scala" | grep "=>"
# Target: 0 hits
```

Fix: `self: A with B =>` → `self: A & B =>`
Mechanical (bucket A). Safe scalafix or sed pass.
Ratchet: `-source:future` flag already set — `with` in self-types is a warning. Promote to error after cleanup.

---

### S7 — No `asInstanceOf`
**Status:** Not yet audited.

```bash
grep -rn "asInstanceOf\[" src/main/ --include="*.scala" | grep -v "consensus/\|vm/\|crypto/\|//"
# Target: 0 hits outside consensus paths
```

Fix: Replace with pattern matching or type-safe alternatives.
Each occurrence is bucket C (semantic risk) — prove behavior-preserving before fixing.
Route to FORGE if in consensus paths.

---

### S8 — No `isInstanceOf` outside pattern matching
**Status:** 59 occurrences. Part 3c backlog.

```bash
grep -rn "isInstanceOf\[" src/main/ --include="*.scala" | grep -v "//\|consensus/\|vm/"
# Target: 0 hits
```

Fix: Replace with `match { case _: T => }` or sealed ADT exhaustiveness.
Bucket B/C depending on context.

---

### S9 — No `println` / `System.out` in main sources
**Status:** 28 occurrences. C5 chore.

```bash
grep -rn "println\|System\.out\|System\.err" src/main/ --include="*.scala" | grep -v "//\|Benchmark"
# Target: 0 hits
```

Fix: Replace with SLF4J logger (`ctx.log.info(...)` in Typed actors, `logger.info(...)` elsewhere).
Ratchet: DisableSyntax can flag `println` — add after cleanup.

---

### S10 — Braceless syntax for new code
**Status:** Config change pending (C1 chore). No mass rewrite.

New code written after C1 (scalafmt config): use braceless where scalafmt accepts it.
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

Ratchet: none yet — enforce via PRISM review and MITHRIL sweep after LOOM CAPSTONE.

---

## Consensus-critical exception

Standards S3–S11 do NOT apply to `consensus/`, `vm/`, `crypto/`, `domain/` without
specialist review. Those paths require FORGE (ETC) or BEACON (ETH) before any
idiom modernization. See `consensus-change-protocol.md`.
