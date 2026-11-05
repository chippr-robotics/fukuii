# Warning Ratchet Protocol

Systematic approach for clearing compiler warnings category-by-category with a ratchet —
each cleared category becomes a build error so it cannot silently regress.

Applies to any Scala 3 / Pekko codebase where warnings were blanket-suppressed during
a Scala 2→3 migration. Correctness outranks tidiness: a behavior change hidden inside
a "cleanup" is worse than leaving the warning.

Used by: WRAITH (warning-cleanup sessions), MITHRIL (Scala 3 idiom cleanup)
Project reference: `fukuii/.claude/agent-protocols/completed/CHORE-QUEUE.md` (archive, C1-C14 done; local and gitignored, absent from a fresh checkout)

---

## Step 1 — Inventory only. Do NOT edit yet.

Collect every occurrence of the target warning category in scope:

```bash
sbt compile-all 2>&1 | grep -i "warning" | grep "<CATEGORY>" | sort | uniq -c | sort -rn
```

Produce a triage table:

| file:line | warning text | bucket | proposed action | risk-path? |
|-----------|-------------|--------|-----------------|------------|

Classify each into exactly one bucket:

- **A (forced-upgrade)**: deprecated API slated for removal in a future compiler version.
  Mechanical, behavior-preserving by definition.
- **B (idiom)**: compiles; Scala 2 idiom where Scala 3 idiom is cleaner.
  Behavior-preserving but optional — skip unless time permits.
- **C (semantic risk)**: might resolve a different given/implicit, infer a different type,
  or carry Scala 2.13 collection/eta/overload semantics. NOT obviously behavior-preserving.

Mark **risk-path? = YES** for anything in consensus-critical code:
- `consensus/`, `vm/`, `crypto/`, `domain/` — EVM, gas, state trie, block validation
- `network/p2p/messages/` — RLP serialization / wire encoding
- `db/storage/` — state persistence
- Any path touching: block rewards, fork dispatch, signatures, hash computation

**Present the triage table and STOP. Wait for user approval before any edits.**

**Bucket-C consensus gate:** Before Step 2 begins, check whether any bucket-C items
are in consensus-critical paths (`consensus/`, `vm/`, `crypto/`, `domain/`,
`network/p2p/messages/`). If yes:
- Remove those items from Step 2's scope entirely
- Add them to CHASE-QUEUE with a `FORGE-gate` (ETC) or `BEACON-gate` (ETH) note
- Do not include them in the mechanical commit, and do not attempt to prove them
  behavior-preserving without specialist review — the cost of a wrong call is a chain fork

---

## Step 2 — Fix, split strictly by risk (after approval)

Separate commits, never mixed:

1. **ONE mechanical commit** — all bucket-A + provably zero-semantic-change bucket-B
   (pure syntax: `_`→`*` imports, `xs: _*`→`xs*`, unused param with no callers, etc.)

2. **ONE commit PER bucket-C or risk-path item**, each including:
   - Before/after code
   - Argument for why it is semantically identical
   - The test covering this behavior
   - If you cannot prove it behavior-preserving: **do NOT change it — flag for human review**

Never put a behavior-affecting change in the mechanical commit.

---

## Step 3 — Defer visibly, never re-suppress

Anything not fixed gets a narrow site-level suppression only:

```scala
@annotation.nowarn("cat=deprecation") // <one-line reason> — <ledger row or #issue>, <date>
def foo(...) = ...
```

- `@nowarn` at the **exact site** — not file-level, not build-level
- One-line reason required; a dated reference required: the sprint's ledger row (for spec 020,
  `specs/020-scala-3-9-lts-migration/dc4-ledger.md`, "Deferrals": date, owner, follow-up issue) and a CHASE-QUEUE entry
  (`.claude/agent-protocols/working-docs/CHASE-QUEUE.md`, local and gitignored, see `inline-cleanup.md`). There is no
  `DEFERRED-BACKLOG.md`
- **No blanket `-Wconf` silence, ever**
- The four spec 020 deferrals (`EcKeyGen`, `SignatureValidator`, `BlockFetcherState`, `ChainDownloader`) carry the
  annotation alone in source; their reasons are ledger rows 1 to 4 (follow-ups #1715 and #1716). Add the one-line
  comment when you add a site.

---

## Step 4 — Ratchet (definition of done)

Promote the cleared category to a build error:

```scala
// build.sbt scala3Options (the repo writes `:error`; `:e` is the same):
"-Wconf:cat=<CATEGORY>:error"       // a category
"-Wconf:id=<Exxx>:error"            // a message id
"-Wconf:msg=<message text>:error"   // a warning that has no id (see below)
```

**In Scala 3.9.0 the LAST matching `-Wconf` wins** (checked in both orders with `id=E198:w` and `id=E198:error`). Add a
new ratchet below the existing ones. This is measured behaviour: re-check the order after a compiler bump. A `msg=` filter is a regex on the compiler's
own text, so keep its punctuation, backticks included; for a multi-line message use the first line without its colon,
since a colon ends the filter.

The Step-3 `@nowarn` sites carry their narrow exemptions so the build stays green.

**Not done until:**
- (a) Category escalated to error in `build.sbt` (or rule added to `.scalafix.conf` for scalafix categories)
- (b) `sbt compile-all` is green with 0 new errors
- (c) Deferral list written down

Verify precedence works on this Scala version:
```bash
sbt compile-all 2>&1 | grep -c "error:"                          # must be 0
sbt compile-all 2>&1 | grep "warning:" | grep "<CATEGORY>"       # must be 0
```

### The ratchets in `build.sbt` today (spec 020 PR 6, `-source:3.9`)

Nine `-Wconf ... :error` filters in `scala3Options`: `E198` (unused), `cat=unchecked`, then the seven promotions below.
Ledger, measured counts and the exact scratch-file proofs: `specs/020-scala-3-9-lts-migration/dc4-ledger.md`
("Promotions: the exact filters").

| Filter | Catches | Write instead |
|--------|---------|---------------|
| `id=E205` | given prioritisation (inert at `-source:3.9`; guards `-migration` re-runs and lower source levels) | an explicit instance |
| `id=E028` | illegal literal | `(-98).toByte`, not `-98.toByte` |
| ``msg=Implicit parameters should be provided with a `using` clause`` | implicit argument passed without `using` | `using x` |
| `msg=Alphanumeric method .* is not declared infix` | alphanumeric method used infix | `a.m(b)`, ``a `m` b`` or declare it `infix` |
| ``msg=`_` is deprecated for wildcard arguments of types`` | `_` as a type wildcard | `?` |
| `msg=with as a type operator has been deprecated` | `A with B` as a type | `A & B` |
| `msg=Type ascriptions after patterns other than` | `val (a, b): (A, B) = e` | ascribe `e` |

```bash
grep -c '"-Wconf:.*:error"' build.sbt
# 2026-11-05: 9  (E198 + cat=unchecked + the 7 above)
grep -c '"-Wconf:msg=.*:error"' build.sbt
# 2026-11-05: 5  (the message-matched ones: using, infix, `_`, with, ascription)
```

The greps only count filters. That each filter still matches the compiler's text is not greppable: it was proved with a
scratch file at `-source:3.9` (a warning without the flag, an error with it), recorded in the ledger. Re-prove a filter
that way after any compiler bump, since message text can change between minors. `cat=deprecation` cannot be promoted
yet: 59 Manifest-synthesis deprecations remain (ledger, "Final per-category table").

---

## Consensus-safety hard stops

- Do NOT edit `consensus/`, `vm/`, `crypto/` without FORGE review (ETC) or BEACON (ETH)
- Do NOT edit RLP codecs (`network/p2p/messages/`) without verifying wire behavior
- Do NOT edit `db/storage/` without VAULT review
- Bucket-C risk-path items: leave them, add `@nowarn`, document — never guess

---

## Output per session

1. Triage table (buckets + risk-path flags)
2. Commits split as above
3. The `-Wconf` or `.scalafix.conf` change
4. Deferral list: each deferred site + reason + backlog ref

## Commit message convention

```
chore(quality): clear <CATEGORY> warnings in <scope> [ratchet step N/4]
chore(quality): escalate <CATEGORY> to build error (ratchet complete)
```
