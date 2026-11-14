# Data model: spec 018, Track C (the unified path-live layout)

*Phase 1 artifact of [plan.md](./plan.md). This file holds Track C's formats and design record; Track E's engine sub-stores, segment format and `StorageMetrics` gauge list are added when Track E's design starts.*

**Status**: design draft, **revised after S0 review rounds 1 and 2** (2026-11-06 and 2026-11-07: `beacon` for ETH, `forge` for ETC;
the three round-1 REJECTs resolved, round 2 had no REJECT, and every SIGN WITH CHANGES of both rounds is applied; the records are
plan.md Phase 1b "S0 record (round 1)" and "S0 record (round 2)"). There is no round 3: each reviewer verifies the applied
changes at the owning slice's PR (plan.md, "S0 prerequisites per slice"). Author: `vault`. Evidence base: the `wt/plan-018-021` tree at staging
`49dd46d1b` (v0.10.47); file:line references are to that tree. Nothing here is implemented or prototyped.

**Revised 2026-11-06 (second pass) to the user's decisions and standing rule.** Decisions in force: the track is item 8,
then 6, then item 10 as ONE gate for both chains, on RocksDB with today's `DataSource`; Option Y (path nodes in place,
per-block changesets in the same batch, an in-memory overlay of recent blocks, reference counts retired) reconfirmed after
the size estimate; **write-through confirmed** (the disk layer is the canonical head; state, BlockDiff, anchor and best
pointer in one batch; no journal); changeset keyed by block hash with prior and new values, a version byte and a record
kind, local part only; spec 017 parked but a decorator must see every node read; checkpoint import onto the unified layout is
funded and required; **two pruning modes (`basic`, `archive`) on both chains, and `inmemory` is retired** (user decision, later the same day, reversing "keep all three"), which closes its durability gap by removal.

**Standing rule (user, 2026-11-06):** ETH and ETC share one infrastructure; ETH is the build target and ETC comes along; the
end state is one configurable, efficient EVM execution layer; no multi-stage transitions; the client is not in production
and nothing external depends on earlier versions. Consequences used throughout: **ETC moves onto the unified layout in this
track and the Hash scheme is retired in it** (no 10a/10b split); **there is no legacy-datadir machinery** (a datadir without
the layout marker is refused at start with a resync message; no adoption, no migration, no legacy reader); **no flag ships
default-off for a release**: the only flag is a short-lived in-development guard that keeps staging green between PRs and is
removed, with the Hash-scheme code, in the track's last slice. Consensus rules still differ per chain by config
(`forBlock` versus `forTimestamp`); only the storage infrastructure is shared. This reverses the user's earlier
adopt-in-place choice for mid-copy datadirs.

**Write-through** (the reading of "in place in the same batch" the user confirmed): the disk layer is always the state of the
canonical head, and the overlay holds only what is not the head. A disk layer that lags the head by N blocks (geth's pathdb)
would need a journal and re-execution after a crash and would break "a block's state exists iff the block does" (#1465,
`StateStorage.scala:27-38`). That variant is not designed.

---

## 1. The model in ten lines

1. After the track every chain (ETH and ETC alike) keeps its state in two families, `t` (account trie) and `u` (storage
   tries), one node per position `(owner, nibble path)`, rewritten in place. CF `n` is no longer a state family.
2. The **anchor** is the one block whose state the disk layer holds. It is stored in AppState and moves only in the same
   atomic batch as the in-place writes it describes.
3. Every block that is promoted (becomes part of the head's ancestry on disk) writes a **BlockDiff** record to the new CF `v`
   in that same batch: for each position it changed, the prior and new node bytes.
4. A block that is executed but not canonical (an Engine API `newPayload`, a side branch) is a **forward layer** in memory:
   the same `BlockDiff` structure, never written to `t`/`u`, kept by block hash with a parent link.
5. A **view** of any block within reach is: forward layers (newest first), then the reverse index of the blocks above the
   fork point (earliest prior wins), then disk. The head's view is just disk.
6. Moving the head is: reverse the old branch (newest first), then forward the new branch (oldest first), in one batch with
   the best-block pointer and the canonical index. The plan is made **from the anchor's own records**, never from the
   canonical index, and every in-place batch runs under one state lock with an anchor compare-and-set (7.0). It needs no
   re-execution for blocks whose diff is known.
7. Reads are verified: a node read at a position must hash to what its parent says. A mismatch is classified **stale** (the
   head moved under the view) or **corrupt** (it did not); only corrupt feeds the audit's delete. Stale, unavailable,
   too-deep and record-guard conditions are typed faults outside `MPTException`, never a verdict on a block (6.3).
8. Reference counts, snapshots (`sn`), death rows (`dr`) and abandon records (`bd`) are retired with the Hash scheme.
   Pruning deletes BlockDiffs below the retention floor inside the next block's batch (`basic`); `archive` keeps them (4.6).
9. There is no shutdown journal. A crash loses only the forward layers; the disk layer is exact, and a block that was
   executed but not promoted is **re-executed locally** from its nearest state-available ancestor, with no peer needed
   (Part 2, D).
10. The consensus code (hashing, RLP, trie algorithms, rewards, fork rules) is byte-for-byte what it is today on both
    chains. Only where nodes live and how they are addressed changes. The hash-keyed `MptStorage` default survives as the
    store for ephemeral list tries (transactions, receipts, ommers) and tests, over `EphemDataSource`.

---

## 2. Column families and keys (after the track, both chains)

| CF (namespace byte) | Key | Value | Status |
| --- | --- | --- | --- |
| `t` `StateTriePathNamespace` | `HP(path)` | node RLP | Unchanged encoding, now **live state**. Written by SNAP, healing and, after hand-off, by block commits. |
| `u` `StorageTriePathNamespace` | `owner(32) ++ HP(path)` | node RLP | As `t`. `owner` is `keccak256(address)`. |
| `v` **new** `StateChangesetNamespace` (byte `v`; taken today: `r h b n c w s k i f l m d a g q p t u j`) | `blockHash(32) ++ kind(1)` (record) or `0x00 ++ number(8, BE)` (height index) | BlockDiff record (section 4) or concatenated 32-byte hashes | **New.** Created empty on first start (RocksDB opens with `setCreateMissingColumnFamilies`, as for BlockAccessList in 0.9.0: `Namespaces.scala:53-55`). |
| `s` AppState | `state.anchor`, `state.layout` (section 5) | strings | **New keys**, same string-valued convention as `BestBlockHash` (`AppStateStorage.scala:677-723`). |
| `n` `NodeNamespace` | node hash | wrapped node, `sn`/`dr`/`bd` rows | **Retired as a state family.** Nothing writes or reads it (genesis goes to `t`/`u`, 5.4). The namespace stays in `Namespaces.nsSeq` as an empty column family, because RocksDB must be opened with every family a datadir has; no datadir created by this layout has rows in it. |
| `d` `FlatSlotNamespace`, `a` `FlatAccountNamespace` | unchanged | unchanged | Written by SNAP only, frozen at hand-off, never read by execution (research F3). Unchanged by this track. |

`HP(path)` is `HexPrefix.encode(nibbles, isLeaf = false)` (`HexPrefix.scala:13-24`): the empty path is `0x00`, one nibble
`a` is `0x1a`, two nibbles `a,b` are `0x00 0xab`, three `a,b,c` are `0x1a 0xbc`. **The keys are not prefix-ordered**: the
children of path `[a]` are `00 ax`, nowhere near `1a`. So no range scan or `deleteRange` can enumerate "the subtree under a
position", and every batch below uses point deletes (`PathNodeStorage.scala:154` says the same). **One owner's rows are
contiguous**, though: every `u` key starts with `owner(32)`, so a prefix scan of CF `u` lists all rows of one storage trie
in some order. 3.6 uses that, and not a walk of the trie, to find the rows of a trie that is being replaced.

The **empty trie has no rows**: a trie root is stored iff the trie is non-empty, so an absent row at `(owner, [])` means the
empty trie (root `EmptyRootHash`). An account trie with an empty genesis alloc has no `t` row; a storage trie that empties
loses its root row in the same batch (a vanished position). Every check that reads the root row (I4, 7.3) accepts "absent
and root equals `EmptyRootHash`".

A node is *stored* at a position iff its RLP is at least 32 bytes, or the position is a trie root (empty path). Smaller
nodes are embedded in their parent and have no row. This is the rule `StackTrie.finalise` and `NodeCapper.capNode`
(`RlpHashingVisitor.scala:27-33`) already apply. The position of a leaf or an extension is the path *to* the node, not
including its own key, which is what `StackTrie` emits.

---

## 3. The in-memory unit: `PositionKey` and `BlockDiff`

```scala
// mpt/ : defined there so the stateless closure gains no db/ import (spec 018 DC-8)
opaque type NibblePath = Array[Byte]            // one nibble per byte, 0x00..0x0f

// db/storage/pathstate/
final case class PositionKey(owner: Option[ByteString], path: NibblePath)   // owner = keccak(address) for a storage trie
final case class Change(prior: Option[Array[Byte]], next: Option[Array[Byte]])  // RLP bytes; None = no stored node
final class BlockDiff(
    val blockHash: ByteString, val number: BigInt, val parentHash: ByteString,
    val preStateRoot: ByteString, val postStateRoot: ByteString,
    val changes: Map[PositionKey, Change],        // ONE entry per position: prior as of block start, next as of block end
    val byHash: Map[ByteString, Array[Byte]]      // keccak(rlp) -> rlp of the `next` nodes, so get(hash) works on the overlay (section 9)
)
```

*(C2a part 2 as built, 2026-11-11.)* `PositionKey` landed with the read side, ahead of the rest of this unit (C3a), as
`PositionKey(owner: Option[ByteString], hp: ByteString)` in `pathstate/PositionKey.scala`: `hp` is `HP(path)`
(`NibblePath.key`). Held encoded, two keys are equal when they name the same position, which the opaque array could not
give (C1), and `rowKey` is the row's key in its column family with nothing encoded twice (`hp` in `t`, `owner ++ hp` in
`u`). The record's byte layout (section 4) is unchanged: it never depended on the Scala type.

*(C1, 2026-11-11.)* `NibblePath` is an array underneath, so it compares by identity: `PositionKey` as written above would
give two equal positions two different `Map` keys. C3a keys `changes` by the store key instead (`NibblePath.key`, the
`HexPrefix` bytes as a `ByteString`, with the owner in front for a storage trie) or gives `PositionKey` equality by
content. C1's test oracle keys its rows by `NibblePath.key`.

*(C3a as built, 2026-11-11.)* `BlockDiff` is a case class whose `byHash` is derived (the keccak of each `next`), and
`Change` holds `ByteString`s, so two diffs compare by content. `BlockDiff.validate` holds the writer's rules that can be
read off a diff: rules 2 and 4, a tombstone with no entries, and one more that forge asked for, **the root rule**: the entry
at the account trie's root has a prior that hashes to `preStateRoot` and a next that hashes to `postStateRoot` (an empty
trie has no row), and it is there exactly when the two roots differ. Rule 5 cannot be read off a diff.

Rules. A writer MUST enforce them. The codec (4.2) checks **structure only** (version, kind, flags, sort order, uniqueness,
presence flags, CRC); rules 2, 4 and 5 are writer assertions, checked by the writer's own property tests and by a debug
`BlockDiff.validate`, so a golden vector may legitimately hold any bytes the structure allows.

1. **Coalesced, with the prior fixed at the FIRST event.** Execution persists the trie after every transaction
   (`BlockPreparator.scala:689-693` -> `InMemoryWorldStateProxy.persistState` at `InMemoryWorldStateProxy.scala:84-113` ->
   `InMemorySimpleMapProxy.persist` at `InMemorySimpleMapProxy.scala:53-60` -> one `put` or `remove` per dirty key), so a hot
   node is rewritten many times a block, and a node created by transaction 1 is *reported as an old node* when transaction 2
   replaces it. The staged layer therefore decides a position's prior once, at the first event that touches it in the block:
   if that event reports an old stored node at the position, the prior is that node; if the first event is a write with no
   old node at the position, the prior is **None**, and every later old-node report at that position is an intermediate and
   is ignored. The next is the last new stored node there (or None). A position created and then replaced in the same block
   has prior None, so reversing the block removes it instead of writing transaction 1's intermediate node.
2. **No no-ops.** An entry whose prior equals its next (same bytes, or both None) is omitted.
3. **Logical prior.** The prior is what the parent's *trie* held at the position, not a read of the disk row. A stale row left
   in `t`/`u` by an earlier SNAP pivot (`PathToHashExporter.scala:27-29`) is not part of the diff. Reversing a block then
   deletes a node the block created even if stale junk sat there first. That is the logical state, and it is exact for every
   read the trie can make. **One exception, stated so nobody is surprised (beacon S0 item 1 note):** for an owner in the reset
   set, rule 6 takes the prior of each position from the **physical** row the prefix scan returns, stale SNAP junk included. That
   is harmless: the forward move deletes the junk (it is unreachable), and a reverse writes it back, still unreachable.
4. **Inline rule.** A position is in the diff only when the old or the new node is *stored* (section 2). A node that shrinks
   below 32 bytes has `next = None`; one that grows past it has `prior = None`.
5. **Vanished positions are listed, and only replaced or absorbed nodes are reported.** A position that held a stored node in
   the parent and holds none in this block gets `next = None`. The trie reports an old node only when it is *replaced* (it is
   on the mutation path) or *absorbed* by `fix` (`MerklePatriciaTrie.scala:595,597`). `fix` also **resolves** the child of an
   extension when that child is a branch and stays (`:591`, returned unchanged at `:599`); that live branch is not reported
   (section 8). Today's `toDeleteFromStorage` lists never name an absorbed sibling.
6. **A replaced storage trie removes its old rows, and never fails a block.** A trie under `owner` is *replaced* when the
   account is removed (EIP-161 touch-deletion, SELFDESTRUCT, `deleteAccounts`), when it is recreated, or when its storage is
   cleared while the account stays (Amsterdam's `clearSelfDestructedAccounts`, `BlockPreparator.scala:686`). All three go
   through `InMemoryWorldStateProxy.deleteAccount`, which today only drops the in-memory proxy (`:219-224`) and leaves reference
   counting to collect the nodes. On the unified layout:
   - **Per-block reset tracking.** `deleteAccount` records the owner in the staged layer's reset set (`MptStorage.resetOwner`,
     section 9), at any transaction of the block, as go-ethereum's `stateObjectsDestruct` does. The trigger is the parent's
     storage root being non-empty, not the final state: destroy-then-recreate ends with the account present.
   - **Effect at the persist after the deleting transaction** (`BlockPreparator.scala:685-693`): positions written earlier in
     the block for that owner are removed from the staged layer (their prior stays what rule 1 fixed, their next becomes
     None); positions written by a later recreation (CREATE2 at the same address, possible on ETC since Agharta) survive.
   - **Enumerate by prefix scan of CF `u`**, not by walking the parent's storage trie: the scan lists the owner's rows that
     exist, so a hole in a dead trie (lazy hand-off, spec 022) cannot turn a valid block into a missing-node failure. For a
     view that is not the anchor view, the scan merges the forward layers and the reverse index filtered by owner. It runs at
     diff finalisation, outside the EVM path, through `StateView.ownerRows` (7.0), which bypasses the BAL builder,
     `ImportProfile` and the spec 017 recorder.
   - **The prior of every position of a reset owner is the parent view's row (forge S0 item 5, a reverse-exactness rule).** For an
     owner in the reset set, the prior of each position is **the row bytes the scan returned for the parent view at that
     position, or None when the scan returned none**, and this **overrides rule 1's first-event prior** for that owner. Without
     it, destroy-then-recreate in one block (tx2 SELFDESTRUCT A, tx3 CREATE2 A, possible on ETC since Agharta) would give the
     recreated trie an empty start, so its first write at a position the dead trie occupied reports no old node, rule 1 records
     prior None, and a reverse **deletes** that row instead of restoring the dead trie's node: the restored storage root then
     has holes (a missing node on the next execution, not a wrong root, but not an exact reverse). The next is the last new
     stored node at the position, or None if the position is deleted and not rewritten; an entry whose next equals the scanned
     row is omitted (rule 2). Test (C3b/C6): tx1 writes storage, tx2 destroys, tx3 recreates and writes over a dead position,
     then reverse, and every row of `u` is byte-identical to the parent's.
   - **The bound never fails a valid block.** If the dead trie has more rows than `state.changeset.max-entries-per-owner-delete`
     (or the whole diff exceeds `state.changeset.max-record-bytes`), the deletion is **skipped**: the old rows stay on disk,
     unreachable (every read is hash-verified against its parent, so a leftover row can never be served as state), and the
     block's record is written as an **incomplete tombstone** (4.2, flag bit 1: no entries; any reverse across it is refused
     with `ReorgTooDeep(reason = incomplete)`; **that refusal is required, not optional**, see below). This is go-ethereum's rule (`storageDeleteLimit`, "skip large storage deletion",
     pathdb refuses to revert incomplete history; from memory, verify on `upstream`). The leak is counted
     (`app.db.state.dead-trie.skipped`).
   - **Why the reverse refusal across a tombstone stays (forge S0 item 5).** (i) A tombstone has `entryCount = 0` (4.2), so it
     carries no prior for any position the block changed, not only for the dead trie. (ii) Even with entries, a later block that
     writes a recreated trie over leftover dead rows records prior None there, and reversing past that block would leave the old
     trie with holes. Both make an exact reverse impossible, so the refusal is the contract (go-ethereum refuses the same way,
     from memory, verify).
   - **A tombstone near the head is operationally expensive, so the gate counts them (beacon S0 item 5, forge S0 item 5).** On
     ETH every block is a forward layer first and is promoted at `forkchoiceUpdated`; a tombstone at or near the head turns a
     routine depth-1 reorg into `ReorgTooDeep(Incomplete)` and an escalation (Part 2, D), a multi-hour re-SNAP and a missed duty
     window for a validator. On ETC the same holds for a depth-1 reorg across a tombstoned head. So `app.db.state.record.tombstones`
     must be **0** in EG-4 and EG-15 (Sepolia, Platåberget) and is **recorded** over every ETC import and the Mordor runs, where any
     non-zero count is explained before the bound is frozen; M1 and M15 must show the largest diff and
     the largest rows-per-deleted-owner well below `max-record-bytes` and `max-entries-per-owner-delete` (section 13). Recommended
     and not blocking: when a tombstone is caused by record size alone, keep the computed diff resident as a reverse diff until the
     block is below the finalized block, so only a restart inside that window escalates.
7. **The empty trie** (section 2): the root row of a trie that becomes empty is a vanished position; a trie that becomes
   non-empty creates its root row.

---

## 4. The changeset record (CF `v`)

### 4.1 Keys

| Key | Length | Meaning |
| --- | --- | --- |
| `blockHash ++ kind` | 33 | The record. `kind = 0x01` is the local (trie-node undo) part. Kind `0x02` is reserved for the exportable semantic write set of amendment #2 / spec 025: it will be stored under `blockHash ++ 0x02`, so adding it rewrites nothing. "Keyed by block hash" (FR-010) holds: the hash is the 32-byte prefix, and siblings never collide. |
| `0x00 ++ number(8, BE)` | 9 | Height index. The value is the concatenation of the 32-byte hashes that have a record at that height. The length differs from a record key, so the two never collide. Used only to prune (4.5). |

*(C3a as built, 2026-11-11.)* **The two kinds of key are told apart by length on a point read, and not at all on a range
scan.** A record key whose block hash begins with a zero byte sorts among the height keys or before them, and on a
proof-of-work chain that is every block: an ETC mainnet hash begins with about six zero bytes, so every record key of ETC
sorts before the height key of any height above 2^24. A scan "for the lowest height" would walk those records first, values
included (`RocksDbDataSource.scanRange` materialises both). So `ChangesetStorage` has no scan: it prunes by point reads of the
heights it is given, and its caller (the retention policy, C3b) keeps the height it has pruned up to. The keys themselves
are unchanged.

### 4.2 Value layout, kind 1, version 1

All integers are big-endian; `uvarint` is LEB128, at most 9 bytes.

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | `version` = `0x01`. A reader MUST refuse any other version; it never guesses. |
| 1 | 1 | `kind` = `0x01`. Equal to the last key byte; a mismatch is corruption. |
| 2 | 1 | `flags`. Bit 0 reserved (entries compressed). **Bit 1 = incomplete** (a tombstone: `entryCount` MUST be 0; the diff could not be recorded, 3.6, and any reverse or forward across the record is refused). Other bits reserved. A reader MUST refuse a record with an unknown bit set. |
| 3 | 8 | `blockNumber` |
| 11 | 32 | `blockHash` (repeated, so a key and value mix-up is detected) |
| 43 | 32 | `parentHash` (a reverse walk needs nothing but records) |
| 75 | 32 | `preStateRoot`: the parent's state root. After a reverse apply the row at `(None, [])` must hash to this. |
| 107 | 32 | `postStateRoot`. After a forward apply the same row must hash to this. |
| 139 | var | `entryCount` (`uvarint`) |
| ... | var | `entryCount` entries, sorted by `(tag, key)` ascending in unsigned byte order, unique |
| end-4 | 4 | `crc32c` of bytes `[0, end-4)` |

Entry:

| Field | Encoding |
| --- | --- |
| `tag` | `0x01` account trie (CF `t`), `0x02` storage trie (CF `u`) |
| `key` | `uvarint len`, then the **CF key**: `HP(path)` for tag 1, `owner(32) ++ HP(path)` for tag 2. Reusing the CF key makes an apply a direct `put(cf, key, bytes)` or `delete(cf, key)` with no re-encoding. |
| `prior` | `u8 present`; if 1: `uvarint len`, then the RLP bytes |
| `next` | the same |

An entry with `present = 0` on both sides is illegal. Apply is idempotent: a reverse apply puts `prior` or deletes the key; a
forward apply puts `next` or deletes it. Within a record each key is unique, so batch order is irrelevant.

**What the codec rejects** (structure only): an unknown version, kind or flag bit; a kind that differs from the key's last
byte; a `blockHash` that differs from the key; keys unsorted or repeated; an entry with both sides absent; a tombstone with
entries; trailing bytes; a CRC mismatch. It does **not** check the inline rule or `prior != next` (3, rules 2 and 4): those are
writer assertions. A reader that meets a tombstone returns "incomplete" to its caller, who refuses the move (`ReorgTooDeep`,
reason `incomplete`) or the view (`StateUnavailable`); it is never an error in itself.

*(C3a as built, 2026-11-11.)* Four more refusals, each of something no writer produces, so that **a record the reader accepts
re-encodes to the bytes it was read from**: a `uvarint` written in more bytes than its value needs (or in more than nine); a
`present` byte other than 0 or 1; a tag other than 1 or 2; and a key that is no row key of its column family (no path at all,
a storage key no longer than its 32-byte owner, or a path that is not a non-leaf hex-prefix path: a first nibble above 1, or
an even-length flag with a non-zero pad nibble). A record that ends early is refused as truncated. None of them changes a
byte of the three vectors. The order of the checks is: the version, alone (another version may lay out everything after it
differently); the kind and the flags; the CRC; then the fields.

### 4.3 Golden vectors

CRC32C is the Castagnoli polynomial (`java.util.zip.CRC32C`); its check value is `crc32c("123456789") = e3069283`.
Both vectors below were computed with an independent implementation and must be re-derived and pinned by the test suite.

Vector 1: block number 1, hash `11*32`, parent `22*32`, pre `33*32`, post `44*32`; one account-trie entry at the root path
(`key = 00`), prior absent, next `c2 01 02`. 153 bytes:

```
01 01 00 | 00 00 00 00 00 00 00 01 | 11*32 | 22*32 | 33*32 | 44*32 | 01 | 01 01 00 00 01 03 c2 01 02 | 8d0afd3e
 ver kind flags  number              hash    parent  pre     post   count tag klen key  prior next(len,rlp)   crc
```

Vector 2: block number 2, hash `55*32`, parent `11*32` (vector 1's block), pre `44*32` (vector 1's post), post `66*32`; one
storage-trie entry, owner `aa*32`, path `[0xa]` (key `aa*32 ++ 1a`, 33 bytes, so `klen = 0x21`), prior a **37-byte extension
node** `e4 82 00 ab a0 bb*32` (so the position is legitimately stored: at least 32 bytes), next absent (a deletion). 219 bytes:

```
01 01 00 | 00 00 00 00 00 00 00 02 | 55*32 | 11*32 | 44*32 | 66*32 | 01 | 02 21 aa*32 1a | 01 25 e4 82 00 ab a0 bb*32 | 00 | 707894bf
```

Vector 3, an **incomplete tombstone** (3.6, flag bit 1): block number 3, hash `77*32`, parent `55*32`, pre `66*32`, post
`88*32`, `flags = 02`, `entryCount = 0`. 144 bytes:

```
01 01 02 | 00 00 00 00 00 00 00 03 | 77*32 | 55*32 | 66*32 | 88*32 | 00 | ae4e831f
```

The three strings in full, as the test pins them:

```
vector 1 (153 bytes):
0101000000000000000001111111111111111111111111111111111111111111111111111111111111111122222222222222222222222222222222222222222222222222222222222222223333333333333333333333333333333333333333333333333333333333333333444444444444444444444444444444444444444444444444444444444444444401010100000103c201028d0afd3e

vector 2 (219 bytes):
01010000000000000000025555555555555555555555555555555555555555555555555555555555555555111111111111111111111111111111111111111111111111111111111111111144444444444444444444444444444444444444444444444444444444444444446666666666666666666666666666666666666666666666666666666666666666010221aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1a0125e48200aba0bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb00707894bf

vector 3 (144 bytes):
0101020000000000000003777777777777777777777777777777777777777777777777777777777777777755555555555555555555555555555555555555555555555555555555555555556666666666666666666666666666666666666666666666666666666666666666888888888888888888888888888888888888888888888888888888888888888800ae4e831f
```

(Vector 2 changed after S0 review: its earlier 2-byte prior `c1 80` sat at a non-root path and so broke the inline rule. The
codec would have accepted it, since the rule is a writer assertion, but a golden vector should not model a record no writer
may produce.)

Other vectors the golden-bytes suite needs: `HP(path)` keys for `[]`, `[0]`, `[0xa]`, `[0xa,0xb]`, a 63-nibble and a 64-nibble
path; an empty record (`entryCount = 0`, legal for a block that changed no stored node); a 200-entry record spanning both
tags; `uvarint` lengths of 127, 128 and 16384 (the LEB128 width boundaries); the height-index value for two hashes at one
height; the `state.anchor` and `state.layout` strings of section 5.

### 4.4 Evolution rule

New data goes in a new **kind** under a new key (`blockHash ++ kind`), never in new fields of kind 1. A version bump of kind
1 is allowed only to change the layout above and is a layout-marker bump (5.2): there is no legacy reader and no window, a datadir is re-synced. The
exportable part of amendment #2 (kind `0x02`, `chainKey`, schema version, the semantic write set by hashed key with prior
and new) is added when spec 025 Phase A freezes its record; nothing in the local part depends on it.

### 4.5 Retention and pruning

**Retention** is a floor under the canonical head: a record is kept while `number > anchor.number - retained`, where
`retained = clamp(max(pruning.history, anchor.number - finalized + state.retention.finality-margin), pruning.history,
state.retention.max-blocks)`. `pruning.history` is 64 (`pruning.conf:15`); `finalized` is the consensus client's finalized
block when a consensus client drives the chain (`ForkChoiceManager.getFinalizedBlockHash`, `ForkChoiceManager.scala:83`) and
is absent otherwise, so ETC keeps `history` and ETH keeps about the finality lag plus a margin (16), bounded by
`max-blocks` (default 512). ETH's finalized block is routinely 64 to 96 blocks behind the head, so a fixed 64 would refuse
reorgs the consensus rules allow (S0 item 3, 3h). When canonical block `bn` is committed, the same batch deletes every record
below the current floor and every height index at or below it not yet deleted (a catch-up loop of at most 16 heights per
batch, which also absorbs a floor that moved). Because pruning is point deletes inside an atomic batch, **it needs no
`deleteRange` and is idempotent**: a crash before the batch leaves the records, and the next batch removes them. Tombstones
(4.2) follow the same floor. `archive` keeps every record (4.6); `inmemory` is retired.

*(User decision, 2026-11-11.)* **Where there is no finalized block the floor is `state.retention.without-finality`, 512 blocks,
not `pruning.history`.** That is every ETC and Mordor node, and an ETH node whose consensus client has not reported a
finalized block yet. The arithmetic above gave them 64. A proof-of-work chain can be reorganised to any depth its attackers
pay for (ETC by 3,000 to 7,000 blocks in 2020; MESS returns only with Olympia), and a reorganisation deeper than the floor is
refused and the state synced again. So: `retained = clamp(finalized ? anchor - finalized + finality-margin :
without-finality, pruning.history, max-blocks)`. The cost is one record per block kept, 512 against 64.

### 4.6 The two pruning modes on the unified layout (both chains)

`basic` and `archive`. **`pruning.mode = "inmemory"` is retired** (user decision, 2026-11-06, which reverses the earlier "keep all
three"): it is **rejected at start on both chains** with a message naming the mode and the two that remain, with no deprecation
window (standing rule). Its durability gap (spec 018 DC-3, INF-006 section 4: `CachedReferenceCountedStateStorage` keeps trie
nodes in an LRU and its `persist()` is a no-op, so a recovered `snapSyncDone` does not imply durable state,
`StateStorage.scala:412-457`, `StateIngest.scala:84-92`) closes by removal: no cache that defers state remains, and
`forcePersist` is deleted with it. `CachedReferenceCountedStorage`, the LRU and `inmemory-pruning-node-caching.conf` are deleted
in C8b with the rest of the Hash scheme. OQ-5's recommendation ("retire") is the outcome. Nothing that existed only for the mode
is designed here: no in-memory undo ring, no persisted tail, no restart-reach rule.

The two modes are one write path with different changeset retention; neither changes how state rows are written, which is always
in place in the canonical batch.

| Mode | BlockDiffs | Reach | Disk | After a restart |
| --- | --- | --- | --- | --- |
| `basic` (default) | persisted in CF `v`, deleted below the retention floor (4.5) | the retained blocks | `retained` diffs | full reach from CF `v` |
| `archive` | persisted in CF `v`, **never deleted** | **moves are unbounded**; views (RPC, trace, SNAP serving) reach `state.archive.view-reach` (default 1024) | grows by one diff per block, about 5 MB at 10,000 changed nodes: tens of GB per day on a busy chain (M1, M9) | full reach |

Because both persist every record in the canonical batch, reach after any crash is the retained blocks (or all of them under
`archive`): a restart head orphaned by a routine depth-1 fork (ETC at 13-second blocks, or a late block on ETH) is followed, not
refused.

**Archive reach and deep PoW reorgs.** ETC's 2019 and 2020 attacks reorganised thousands of blocks (about 3,700 to 7,000), and an
archive node today can follow such a reorg because it keeps every state. So under `archive` a move is not bounded by a reach:
`moveHead` is executed in chunks of `state.move.max-blocks-per-batch` (default 256), each chunk **one atomic batch that moves
the anchor, the best pointer and the canonical index to an ancestor on the way**. An interrupted chunked move is recovered by
I1 rules 2 and 3 (5.1), because each chunk leaves `anchor == best`. Only views are bounded, since a reverse index over
thousands of diffs is not something an RPC read should build. MESS (ECIP-1100) is inactive until Olympia, so nothing else
bounds such reorgs.

**Escalation, never stranding.** `ReorgTooDeep` and `StateUnavailable` are never silent and never a reason to keep a head that
the fork choice has abandoned. Each logs at ERROR with a tag (`[STATE-REORG-TOO-DEEP]`), increments
`app.db.state.reorg.refused`, and takes the escalation route of Part 2, D.

---

## 5. State anchor and layout marker (AppState `s`)

### 5.1 `state.anchor`

String value `v1:<blockHash hex>:<number decimal>:<stateRoot hex>`. It is written in the in-place batch of every canonical commit
and every head move, and by SNAP finalization (7.4). It is the only statement of *which block the disk layer holds*. It never
moves on its own and never without the rows it describes.

Startup reconciliation, invariant **I1**. Let `best` be `BestBlockHash`.

1. `best == anchor`: nothing to do. This is the normal case, because canonical commits write both in one batch.
2. `best` is an ancestor of the anchor within the retained blocks, and every block above it has a complete record
   (no tombstone): reverse-apply down to `best` in one batch (an interrupted `moveHead`, or a chunk of one, whose pointer
   half committed first).
3. `best` is a descendant of the anchor and every block between has a complete record (applied before, then reversed):
   forward-apply.
4. Otherwise: rewind the best-block pointer and the canonical index to the anchor (`BlockchainWriter.setCanonicalChainHead`,
   `BlockchainWriter.scala:185-190`), log `[STATE-ANCHOR] best pointer rewound` at WARN, and let the consensus client or the
   importer drive the node forward again.

**As built (C4b part 4, 2026-11-13; the user's decision of 2026-11-14): rule 4 is the rule, and rules 2 and 3 are
not built.** A block's commit and every
batch of a head move write the rows, the anchor, the best block and the index in one write batch, under the state lock,
after a check that the best block is the anchor, so the pointer is never committed apart from the state and rules 2 and
3 have no crash to serve. A best block that is not the anchor comes from a defect, a datadir written before that check,
or test mode's two-write reset; the anchor's state is what execution proved, and the blocks above it stay stored by hash
(vault, forge; plan.md "C4b part 4").

**Rule 4 is a startup rule only.** At runtime a refused move never rewinds the pointer to the anchor: on a PoW chain that
would leave the node on the forked chain (forge, S0 item 3b). A runtime refusal returns a typed result and escalates (Part 2,
D). Rule 4 is safe at startup because the node has not yet followed any fork choice since the crash.

**Invariant I6, checked after rule 1 to 4 at every start.** The canonical index from `best` down to `best - retained` equals
`best`'s header ancestry; where it does not, the index is rewritten from the headers (about 64 header reads). The index can
name side blocks while `best == anchor`: side-branch execution writes the number-to-hash entry for every executed block
(`executeAndCommit` -> `saveBatch`, `BlockExecution.scala:686-691` -> `storeBlockHeader`, `BlockchainWriter.scala:96-98`)
before `settleHead` runs, and a crash in that window makes rule 1 report "consistent" while fork choice reads a mixed index
(`BlockchainReader.scala:102-107,149-178`, `BranchResolution.scala:41-47,210-214`).

The pointer has at least ten writers, and more than the first audit found (Part 2, A): each either carries a state move in
its batch under the state lock (7.0), or is a pointer-only hint that cannot move best past the anchor, or falls under the
startup rules.

**I1 is suspended while SNAP is in progress.** `PivotSelector` writes `BestBlockInfo = pivot` when the pivot is chosen, long
before any state is executed (`PivotSelector.scala:427-439`, #1434; `AppStateStorage.isSnapSyncInProgress`, `:239-260`,
documents the same). So before hand-off there is no anchor and best is the pivot. The anchor is created by the genesis commit
on a fresh datadir (5.4), deleted at SNAP start (5.4) or at a checkpoint import start (E.1), deleted by an escalation in the same synced batch as `clearSnapSyncDone()` (Part 2, D), and written again by finalization (7.4). **While there is no anchor, every in-place writer refuses (7.0).** `SnapSyncDone` with no anchor
is not a legal state on this layout and is refused at start as corruption.

### 5.2 `state.layout`: the one layout-version marker

String `path-live-1`. Present means: this datadir's state lives in `t`/`u` with a `state.anchor`, on either chain. It is stamped
with `updateSync` when the datadir is created (in the same batch as the genesis commit, 5.4) and is the only marker of its
kind. There is no second marker for "adopted", "migrated" or "hash layout".

Two more AppState keys exist for the escalation route only (Part 2, D): `state.escalation.last` (time and causing head hash, for the
cool-down and the loop guard) and `state.reset-pending` (present between the synced escalation batch and the end of its `deleteRange`
calls; a start that finds it repeats them). Neither is a layout marker, and neither exists on a datadir that never escalated.

### 5.3 Detection rule (FR-016, one rule for both chains): refuse at start

Evaluated once by `StateLayoutGuard`, in `NodeBuilder` where `storagesInstance` is built, before any actor starts and before
`GenesisDataLoader` runs. The existing scheme guards run later and are narrower (`SyncController.scala:476-480`,
`SnapResumePlanner.scala:105-115`) and are deleted with the Hash scheme.

```
layout = appState.get("state.layout")
if layout == "path-live-1":   proceed
if layout is any other value: REFUSE        # a layout this build does not know
# marker absent
if datadirIsEmpty:            stamp "state.layout" with updateSync (with the genesis commit); proceed
else:                         REFUSE
# datadirIsEmpty = appState.getBestBlockInfo() is empty AND no header is stored at height 0
```

That is the whole rule. A datadir written by any earlier build has genesis stored and no marker, so it is refused whatever its
state: finished SNAP, mid-SNAP, mid-copy, full sync, either chain. There is no adoption, no migration and no legacy reader
(user standing rule, 2026-11-06). It exits non-zero with:

```
This data directory was written by an earlier storage layout and cannot be opened. Stop the node, move or delete the
rocksdb directory under the data directory, and start again: the node will re-sync (SNAP, or a checkpoint archive in the
current format).
```

Consequence for operators of this repository's own nodes (the user's computer and the dev-node): every existing datadir on
either chain is re-synced once, when the track's last slice lands. Until then the in-development guard (section 10) keeps the
old behaviour, and no datadir is stamped by a build that has the guard off.

**The guard is symmetric while it exists (forge S0 item 7, development only).** A build with the in-development guard **off**
also refuses a datadir that **has** `state.layout`, with a message naming the flag: otherwise flipping the flag between runs
would run Hash-scheme code over a path-live datadir (no CF `n` state, the anchor ignored). It is one line in `StateLayoutGuard`
and is removed with the flag in C8b. Test: start a marked datadir with the flag off (refused) and an unmarked non-empty one
with the flag on (refused).

### 5.4 Genesis on a path-live chain (both chains)

`GenesisDataLoader` writes genesis state through `getReadOnlyStorage` into CF `n` today (`GenesisDataLoader.scala:101-122`).
On the unified layout it writes `t`/`u` through a **genesis commit**: the canonical-commit path (7.1) with no parent,
`anchor = genesis` and the `state.layout` stamp in the same batch. Details, each from S0 round 1:
- **Owner-scoped.** Genesis builds every storage trie into one shared storage with no owner
  (`GenesisDataLoader.scala:159-160,172-176`). Each trie is built through `forOwner(Some(keccak(address)))`, in C2a.
- **Insert-only.** The build runs through `AppendOnlyMptStorage`, which drops removals (`:288-294`), so it emits no vanished
  positions; a test pins that.
- **No BlockDiff for genesis**, as for a SNAP pivot or a checkpoint block (there is no prior). Invariant I3's lower bound is
  therefore uniform: genesis, pivot or checkpoint block, whichever the datadir started from.
- **An empty alloc** leaves no `t` row (section 2); the genesis root is `EmptyRootHash`.
- **Test mode** (`Config.testmode` skips `GenesisDataLoader`, `StdNode.scala:138-139`; `TestService.storeGenesisAccountStorageData`
  builds storage tries with no owner through `getBackingStorage(0)`, `TestService.scala:247-256`) uses the same genesis commit
  and stamp, with owner threading.
- **`ethCompatibleStorage = false`.** `computeStorageRootHash` always builds with `EthereumUInt256Mpt`, while execution builds
  with `ArbitraryIntegerMpt` on such a net (`GenesisDataLoader.scala:172-176`, `InMemoryWorldStateProxy.scala:364-365`). No
  shipped chain sets it false (ETC and Mordor are true: `etc-chain.conf:197`, `mordor-chain.conf:185`). Rather than let genesis
  and execution disagree, a genesis with **non-empty storage on such a net is refused** with a message; accounts without
  storage are fine. This is a private-net case, not an ETC case.
- **`HealInvariant.isEmpty`.** `HealInvariant.atSnapStart` stamps the heal-invariant marker only on an *empty* datadir
  (`HealInvariant.scala:76-95`, `isEmpty` at line 90). Genesis rows in `t`/`u` would make every fresh datadir pre-invariant and
  force the full verification walk that spec 022 slice 5 removes. So at SNAP start, when `anchor == genesis`: clear `t` and
  `u` (two `deleteRange` calls, trivial at genesis size), delete the anchor, then evaluate `isEmpty`. The step is idempotent,
  so a crash in the middle repeats it. A **checkpoint import** does the same at its start (E.1; forge S0 item 8b), and an
  **escalation** (Part 2, D) clears CF `v`, `t` and `u` as well as the anchor.

---

## 6. The in-memory overlay

### 6.1 Structures

```scala
final class StateOverlay(config: OverlayConfig):
  // keyed by block hash; each layer links to its parent hash
  private val forward: ConcurrentHashMap[ByteString, ForwardLayer]   // executed, not on disk: forward diffs
  private val byRoot:  ConcurrentHashMap[ByteString, ByteString]     // stateRoot -> block hash (forward layers + canonical window)
  private val reverse: LruMap[ByteString, BlockDiff]                 // resident BlockDiffs of recent canonical blocks (CF v on a miss)
  private val moves:   RingBuffer[StateMove]                         // last max-view-age-moves state moves, for pinned views

final class ForwardLayer(val diff: BlockDiff, val lastUsedNanos: AtomicLong, var pinnedBy: Option[BranchId])

final class StateView(
    val block: BlockRef, val epoch: Long,
    forwardChain: List[BlockDiff],
    reverseIndex: PersistentMap[PositionKey, Option[Array[Byte]]]):
  def read(p: PositionKey): Option[Array[Byte]]                      // layers, then reverse index, then disk (6.3)
  def ownerRows(owner: ByteString): Iterator[PositionKey]            // prefix scan of CF u merged with the layers (3.6)

final class StagedLayer(parent: StateView):                          // one block under execution, or one proposer attempt
  val byHash:  mutable.Map[ByteString, Array[Byte]]                  // EVERY node written in this block, every version, by hash
  val changes: mutable.Map[PositionKey, Change]                      // the block-local diff (3, rule 1)
  val resets:  mutable.Set[ByteString]                               // owners whose storage trie was replaced (3, rule 6)
```

A view for a target block is `(forwardChain, reverseIndex)`:
- `forwardChain`: the diffs from the fork point up to the target, newest first. Empty when the target is the anchor or one of
  its ancestors.
- `reverseIndex`: for the blocks the head must be reversed through (anchor down to the fork point), a persistent map
  `position -> prior`, **earliest block above the fork wins**. The index for fork `F` is built from the index for `F+1` by
  overlaying block `F+1`'s priors: O(entries in one diff) per step, with structural sharing. A tombstone in the range makes the
  view `StateUnavailable`.

### 6.2 Lookup order

**For a view of an existing block** (`view.read(p)`):
1. `forwardChain`, newest first: the first diff that has `p` answers with its `next` (None means absent in this state).
2. `reverseIndex`: if present, answer with the prior.
3. Disk (`t` or `u`): a point read, keccak-verified against the hash its parent holds, with the epoch check of 6.3.1.

**For a block under execution, and for every throwaway layer** (a proposer attempt, `eth_call`, simulate, a lenient re-run),
`getAt(path, hash)` asks in this order, and the first layer is by **hash**, not by position (S0 item 1c):
1. `StagedLayer.byHash.get(hash)`: every node this block has written, in every version it has had. Hit: the node, with no
   verification (its bytes were hashed when written).
2. Otherwise `parent.read(position)` as above, verified.

A "latest node per position" map is **not enough**. Three paths read an older in-block world after a newer one has persisted
into the same layer, and a position map would hand them the newer node under the older hash:
- `BlockAccessListBuilder.addIndex(before, after)` reads `before.getAccount` and `before.getStorage(...).load` after `after`
  was persisted (`BlockAccessListBuilder.scala:54-58,74`), per transaction at `BlockPreparator.scala:968`, after the request
  calls at `BlockExecution.scala:318` and for index 0 at `:423`. Without by-hash reads every Amsterdam block fails to
  execute, and Sepolia has been post-Amsterdam since 2026-10-06.
- The lenient builder re-executes up to 8 times from the same parent (`EngineApiService.scala:1337`): a fresh layer per attempt.
- `executePreparedTransactions` restarts from `StateBeforeFailure` (`BlockPreparator.scala:1028-1040`).

Today's `BufferedNodeStorage` is content-addressed, which is why these work now. The staged layer keeps `byHash` for the whole
execution and drops it at commit or discard; the forward layer that survives keeps only the `next` nodes' bytes (for `get(hash)`,
section 9).

*(C2a part 2 as built, 2026-11-11.)* The by-hash half of the layer exists from C2a, not C3b: `PathMptStorage.throwaway(view)`
keeps what the tries of one execution write in `byHash` and asks it before the view. beacon found, before code, that the
readers C2b converts cannot wait for C3b: every execution on a view persists after each transaction
(`BlockPreparator.scala:656-660`, `StxLedger.scala:725-735`, `EthSimulateService.scala:311`) and reads the new root back.
`PathMptStorage.readOnly(view)` has no layer and refuses every update. C3b adds `changes` and `resets` to the same layer for a
block that commits; nothing here is torn out.

The head's own view has no layers, so a read is one verified point read. A sibling of the head has `forwardChain = [S]` or
empty, plus a one-block reverse index. The view for a root is found through `byRoot`: forward layers first, then the canonical
window built from headers, the same set `NetworkPeerManagerActor.refreshFreshRootCache` builds (lines 1112-1123).

### 6.3 Isolation: epoch-validated reads (the highest-risk piece; S0 item 4)

There is no MVCC on today's `DataSource`, and the existing code lets blocks execute and commit concurrently: `ChainInsertLock`
is held shared by every node path (`BlockExecution.scala:1181-1202`), `BlockImportGate` serialises only two attempts at the
same hash (`:1204-1263`), and a consensus client's `forkchoiceUpdated` takes no lock at all (`:707-711`). So a view can be
created, and the disk moved by another thread, before the view finishes.

#### 6.3.1 The protocol

- **Every in-place batch is a state move.** Not only a head move: the canonical commit (7.1), a head move or each chunk of one
  (7.3), a repair write (7.5), the genesis commit (5.4), the SNAP finalization commit (7.4) and a checkpoint import's final
  batch (E.1) each take the state lock (7.0), publish a `StateMove`, and bump the epoch. If a canonical commit did not, a
  reader on the anchor view would see changed bytes under a stable epoch, the table below would call that corruption, and the
  importer, which deletes with `stillReached = () => true` today (`BlockImporter.scala:684,716,996-998`), would delete a live
  node (S0 item 4a).
- **A move carries pre-batch values.** `StateMove(kind, newAnchor, pre)`, where `pre(p)` is the value of each written position in
  the *pre-move anchor view*, i.e. what a reader based on the old anchor must see. A coalesced reverse-plus-forward batch has no
  single "opposite side" value, so the move records it. It costs no reads: for a position the reversed blocks touched it is the
  `next` of the newest reversed block that touched it; for a position only the forward blocks touched it is the `prior` of the
  earliest forward block that touched it (relative to the fork state, which is what disk holds there).
- **Order inside the lock:** check the anchor compare-and-set (7.0); publish the `StateMove` to the move log; set `epoch` to an
  odd value; commit the batch; set `epoch` to the next even value; release the lock. A view is created at an even epoch `e0`
  (one created while a move runs waits for it, bounded by one batch commit) and remembers it.
- **Disk read for a view:** `v = disk.get(p)`, then `e1 = epoch`. If `e1` is even and equals `e0`, `v` is valid. Otherwise look
  at the moves with epoch in `(e0, e1]`: if one touches `p`, the value as of the view is the earliest such move's `pre(p)`. The
  move was published before the disk changed, so a reader that saw a changed value always finds the move. A view older than
  `max-view-age-moves` (default 8) throws `StaleStateViewException`.
- **"The anchor view" is decided at read time:** `view.block == the current anchor` and the epoch is even and unchanged since
  the view was created. It is not "the view's anchor at creation".
- *(C2a part 2 as built, 2026-11-11.)* The reader's half of this protocol is `StateClock` (`epoch`, `anchor`,
  `movesBetween(from, to)`), and `StateMoveLog` is the clock with the log: `publish(move)` puts the move in the log and then
  makes the epoch odd, `settled()` makes it even and moves the anchor. **A move is logged under the odd epoch it set**, so a
  reader that samples that odd epoch finds the move in flight (beacon; pinned in `StateViewSpec`). A read that finds the epoch
  odd and no logged move touching its position keeps the row it read. Whether a mismatching row is corrupt or stale is decided
  **after** the row is read: decided before, a batch that commits in between has its new bytes called corrupt (pinned by a
  test that fails when the order is swapped). A view is made by `awaitRest()`, which waits on the log's monitor for the one
  batch in flight. Nothing in the node calls `publish` until C3b's commit, under the state lock (7.0).

Classification when the bytes at `(owner, path)` do not match what the parent expects:

| Condition | Result |
| --- | --- |
| Row absent | `MissingNodeException` carrying `location = (owner, path)` (existing type; the on-demand fetch uses it) |
| Mismatch; the epoch changed, or the view is not the anchor view | `StaleStateViewException`, retryable (6.3.2) |
| Mismatch; epoch stable; the anchor view | `CorruptNodeException` (existing, `MerklePatriciaTrie.scala:49-60`) |
| Bytes do not decode | `CorruptNodeException` |

**The importer's and the audit's deletes are position-scoped.** `BlockImporter.deleteIfCorrupt` and `StateStorage.deleteBadNode`
become `deleteBadNodeAt(position, expectedHash, stillReached)` with a **real** `stillReached`: the anchor root reaches
`expectedHash` at that position, evaluated under the state lock, and the bytes are read again after that look. A decodable
node that differs from the expected one is never deleted on this path: it is another view's node. Only a
`CorruptNodeException` classified in the last two rows may reach it.

An execution that meets a stale view discards its staged state and retries against a fresh view of the same parent; a second
failure is a `StaleStateViewException`, never a verdict on the block (6.3.2). RPC and trace views retry transparently while the
block is in reach: during a regular-sync catch-up 8 moves pass in under a second, so erroring would be routine (M5 is measured
under catch-up as well as at one move per 12 s). The BAL prefetch (`PrefetchNodeReader` reads the parent state straight from
the database, `PrefetchNodeReader.scala:9-25`) becomes `PathPrefetchReader` over `view(parent)` (C2a), treats a fault as a
miss and stops, keeps `putIfCurrent` on its cache inserts, and is consensus-neutral only because it never feeds execution a
node that was not verified.

#### 6.3.2 The fault hierarchy, and what each caller does with it (S0 items 2e, 3, 4e, 5a)

A storage condition must never become a verdict on a block. Today every `MPTException` is mapped to `MPTError`
(`BlockExecution.scala:325`) and the Engine API then reports a state hole and fetches a node (`EngineApiService.scala:687-688,703`);
every other `Left` is answered INVALID (`:692-699`), and `InvalidChainReporter` treats `TxsExecutionError` and most
`ValidationAfterExecError`s as proof of invalidity (`InvalidChainReporter.scala:159-187`). So the new conditions are typed
**outside** `MPTException` and are never wrapped into a `Left`:

```scala
sealed abstract class StateFault(message: String) extends RuntimeException(message)   // NOT an MPTException
final class StaleStateViewException(...)  extends StateFault(...)   // a view older than the move log; retryable
final class StateUnavailable(...)         extends StateFault(...)   // no layer, record or anchor path for the state asked for
final class ReorgTooDeep(reason: ReorgRefusal) extends StateFault(...)  // refused move: Deep, Incomplete, Pivot
final class BranchTooLarge(...)           extends StateFault(...)   // a branch above the pinned cap, with no reverse-then-commit fallback (6.4)
```

`CorruptNodeException` keeps its existing type: by spec 022 T062's design it is a `MissingNodeException` (the stored entry is
not the node, so it is treated as missing), handled as a missing node everywhere and never as INVALID. The record-size guard
is **not** a fault: it writes a tombstone (3.6) and the block commits.

| Caller | On a `StateFault` | Never |
| --- | --- | --- |
| Engine API `newPayload` | the existing not-executed arm answers **ACCEPTED** (`EngineApiService.scala:706-708,727-743`); an unavailable parent also schedules local re-execution (D) | INVALID; `markInvalidRecursive`; `reportInvalid`; `stateHoles.report` |
| Engine API `forkchoiceUpdated` | a refused move returns `Left`: **SYNCING**, with `currentState`, safe and finalized state, payload id and `txGossipGate` untouched (7.3) | publish a payload; mark synced |
| PoS and PoW importer (`ConsensusImpl`) | new `ConsensusResult`s, `ConsensusStateFault(fault)`, handled as a local state fault (`Consensus.isLocalStateFault`, `BlockImporter.scala:875,944,1062`), so the serving peer is not blacklisted | `ConsensusErrorDueToMissingNode` (`ConsensusImpl.scala:334-344`, which fetches a node from peers at a root nobody serves); `BranchExecutionFailure` (`:357`, which reaches `reportIfProvenInvalid`) |
| RPC, trace, simulate | a stale view is retried transparently with a fresh view of the same block while it is in reach (up to 3 times); `StateUnavailable` becomes the existing "state unavailable" error | surface a stale read to the client |
| Block builder (Engine API and PoW) | a stale view during a build retries the build; it does not fall to the empty-payload fallback (`EngineApiService.scala:1352-1354`), which would cost the proposer its fees | return an empty block because of a stale view |
| BAL prefetch | treats a fault as a miss and stops | feed execution an unverified node |
| VM | nothing: it has no catch-all that turns a storage exception into a transaction failure (the only catches are local to precompiles, `PrecompiledContracts.scala:674,781`), and a test pins that | |

A fault-injection test injects each of the four (and a `CorruptNodeException`) into `newPayload`, `forkchoiceUpdated`, the
importer, the PoW consensus path and the builder, and asserts: never INVALID, never `markInvalidRecursive`, never
`reportInvalid`, never a delete.

#### 6.3.3 The fallback, and the memory-model note

Epoch validation relies on one ordering: the writer's volatile epoch store precedes the RocksDB write in program order, and
a reader that observed the new bytes then reads the epoch. The JMM gives no happens-before edge across the native write, so the
argument rests on RocksDB's own synchronisation being a full barrier in practice (x86, ARM). That is documented, and tested by a
deterministic interleaving test, not assumed. The **fallback**, if beacon or forge prefer it or the test cannot be made
reliable: every in-place batch and every execution takes one reader-writer lock (executions shared for their whole duration,
state moves exclusive). It is geth's `chainmu` discipline and gives JMM-guaranteed isolation, at the price that a state move
waits for the longest running execution. The epoch protocol is recommended and the lock is a one-class swap.
### 6.4 Budgets, pinning and eviction

| Pool | Holds | Default bound | On overflow |
| --- | --- | --- | --- |
| forward layers | executed, unpromoted blocks | 32 layers and 128 MiB | evict least recently used layers that are not **pinned** and not ancestors of a head candidate; the block's state becomes unavailable but **re-executable** (D, local recovery) |
| **pinned layers** | the layers of one branch under execution by `reorganise` (PoW) or of a side-payload chain a consensus client has built on | exempt from the 32-layer count cap; `state.overlay.pinned-max-bytes` (default 256 MiB, separate from the overlay cap) | the branch is refused with `BranchTooLarge`, a retryable StateFault (6.3), never an invalid block |
| resident reverse diffs | decoded `BlockDiff`s of recent canonical blocks | 16 blocks and 128 MiB | LRU; a miss reads the record from CF `v` |
| move log | the last state moves | 8 moves | oldest dropped; older views throw stale |
| one layer larger than `max-layer-bytes` (64 MiB) | a gigablock-scale diff | n/a | WARN; committed canonically in place if it extends the anchor (with the record written as a tombstone if it exceeds `max-record-bytes`, 3.6), otherwise its state is marked re-executable. Gigablock sizing belongs to Track E (DC-13). |

Total overlay hard cap, excluding pinned layers: **256 MiB** (`state.overlay.max-bytes`). These are first guesses; section 13
lists what to measure.

*(C4a part 4b-1 as built, 2026-11-12.)* What is resident behind the anchor is **a reverse index per (anchor, block)**, not
decoded records: at most four an execution built and four a reader built, in the reverse half of `max-bytes`, least recently
used first to go, and a reader's never in an execution's place (`ReverseIndexCache`). All of them go when the anchor moves.
`resident-reverse-blocks` is not read. A reader (a peer served a state, a JSON-RPC call) builds at most two indexes at once
per kind of reader and is told to ask again beyond that; an execution is not limited; whoever asks for an index that is being
built is given that one. A view reaches `state.retention.max-blocks` behind the anchor in the basic mode and
`state.archive.view-reach` in the archive mode. **Not bounded here**: an index that is no longer kept lives as long as the
views that read through it, so the layer that serves reads behind the head bounds how many run at once (C4a part 4b-2).

*(C4a part 4b-2 as built, 2026-11-12.)* **The reads behind the head that are in flight are bounded too.** A caller that is
not served one request at a time (the JSON-RPC, GraphQL) is handed the state of a block behind the anchor only inside a
`ReadScope`, the calling thread's, which says when the reader is done; a scope is counted once for each block it reads
behind the anchor, four may be counted at once (`maxReadsBehindHead`, a constant until M5), and one more is refused and
told to ask again. So what readers hold is the kept indexes within their pool, two builds for each kind of reader, and four
reads in flight of at most one index each while the caller keeps one world of a block within reach (a replay does; a scope
is counted once for a block however often it takes it). A caller in no scope reads the anchor's state and no other. A peer
is served through a door of its own, one request at a time. Over RPC a state that cannot be read is one of three errors,
none of them an execution error: not held (`-32000`, `missing trie node`), not handed out right now (`-32005`), moved under
every view of it (`-32005`).

**Why branches are pinned (forge, S0 item 3a).** `reorganise` executes the whole new branch with `adoptEachBlock = false`
(`ConsensusImpl.scala:325-326`) and settles only after the last block (`:328`), so every block of the branch is a live forward
layer at once. A batch is up to 50 blocks (`blocks-batch-size = 50`, `sync.conf:351`) and BlockQueue can hold branches up to
`max-queued-block-number-ahead = 1000` blocks (`:371`): both exceed 32, and the eviction rule forbids evicting the ancestors of
a head candidate. Without pinning, the plan's own reorg cases of `history - 1` and `history` blocks could not pass, and a
heavier ETC branch longer than 32 blocks could not be adopted. Pinned layers are released at `settleHead`, or at the first
failure, which drops the executed prefix's layers unless REORG-PARTIAL re-enqueues them (below).

**What is refused up front is the reverse depth, not the branch length (forge S0 item 3b, beacon S0 item 3i).** The reach
limits how far the anchor moves back, which is `best - fork.number`, not how many blocks the new branch has. So a move is
refused before any execution, with `ReorgTooDeep(Deep)`, when `best - fork.number` exceeds the retained depth
(`reorg.max-depth`, which defaults to the retention floor of 4.5, section 10). Branch length is bounded **only** by
`pinned-max-bytes`, in bytes, while the pinned PoW arm executes (a branch of 300 small blocks passes, one of a few gigablocks does
not): above it the move raises the retryable `BranchTooLarge` (never an invalid block), and the recorded reverse-then-commit
fallback below, which is not the default, is what M13 would switch on. A node whose head was orphaned can
meet a heavier branch that forks 1 or 2 blocks below its head and then runs longer than 64 (BlockQueue admits up to 1,000
ahead, `sync.conf:371`), and a PoS node 300 blocks behind can build on a branch that forks 2 blocks below its head: neither is
refused, because refusing a shallow fork on length would escalate to a re-SNAP (Part 2, D) for no reason. Tests: a branch of
300 blocks forking at `best - 2` is executed and settled; a branch of 3 blocks forking at `best - retained - 1` is refused
`Deep`; a reorg at the finalized lag (about 90) passes.

**By arm.**
- *PoW, heavier by weight* (`selectedByWeight`): keeps execute-first, so `REORG-PARTIAL` (an executed prefix that does not
  outweigh the head) leaves the old head untouched. It uses pinned layers. If M10 and M13 show real ETC branches above the
  pinned cap, the fallback is **reverse-then-commit with rollback from records** (the old branch's records make the move back
  cheap), recorded here and not the default. **Decided 2026-11-12 (user): it is the rule when the pinned cap is hit**,
  with the disk moved back by the records if the branch then fails; it lands with the head move (`plan.md`, "C4a part 3").
- *PoS designated-head arm* (`!selectedByWeight`, `ConsensusImpl.scala:453-460`): this arm already moves best to the last
  executed block whatever happens, so it does **no overlay stack at all**: one reverse move to the fork point, then a
  canonical commit block by block. No N-layer pin, no N-block batch (beacon, S0 item 3g).
- *REORG-PARTIAL.* The executed side blocks are re-enqueued (`ConsensusAdapter.scala:171-173`), so their layers are kept for
  re-use and are not the first evicted; `abandonBlockStates(sideBlocks)` at `ConsensusImpl.scala:499` becomes a no-op.
- *Side-payload chains.* A consensus client can send a long chain of `newPayload`s on a side branch before any
  `forkchoiceUpdated`; they are forward layers like any other, evictable until a head candidate descends from them, which
  pins them. A `forkchoiceUpdated` to a head whose chain is partly evicted triggers local re-execution (D), not a refusal.

### 6.5 Not in the overlay

The decoded-node cache stays keyed by node hash (`DecodedNodeCache.scala:19-47`): content is addressed by hash, so a hit is
correct for any view. It keeps today's `putIfCurrent` rule (`:31-41`) and an in-place overwrite or delete **evicts**, so a
cached node cannot outlive its row for the importer's missing-node machinery. One property is lost, and is stated here so
nobody relies on it: today "a cached node never hides a hole" holds by construction, because a hash has one row. On a
position store identical nodes occupy many positions, so a hit by hash can mask a **positional** hole (a row absent at one
position while another position holds the same node). That is not a consensus issue (execution reads content-addressed
nodes) and the audit reads rows directly, bypassing the cache, so it still finds the hole; execution simply does not trip over
it until eviction. There is no hash-to-position index for non-overlay nodes (section 9).
---

## 7. Atomic units (each is ONE `DataSource.update`, i.e. one RocksDB WriteBatch)

### 7.0 The state lock and the anchor compare-and-set (S0 items 3c and 4a)

Today the writers interleave: `forkchoiceUpdated` takes no lock (`BlockExecution.scala:707-711`), the importer's canonical
commits hold `ChainInsertLock` shared only (`:657,1194-1197`), and `BlockImportGate` serialises one hash, not one anchor
(`:1236-1253`). Relative reference counts tolerate that (`ReferenceCountNodeStorage.scala:310-410`); in-place absolute rows do
not. So:

- **One process-wide state lock** (a fair mutex, not `ChainInsertLock` and not `BlockImportGate`). Every in-place batch holds
  it from "check" to "epoch even": the canonical commit (7.1), a head move or each chunk of one (7.3), a repair write (7.5),
  the genesis commit (5.4), SNAP finalization (7.4) and a checkpoint import's final batch (E.1). Execution does not hold it;
  only commits do, for the length of one batch.
- **Anchor compare-and-set under the lock.** The batch reads the anchor and checks it equals what the plan assumed: for 7.1,
  `anchor == block.parentHash`; for 7.3, `anchor == plan.base`; for 7.5, that the anchor's root reaches the expected hash at
  the position. On a mismatch: 7.1 degrades to an overlay commit; 7.3 **re-plans** from the new anchor (at most 3 times) and
  then refuses with `StateUnavailable`; 7.5 declines.
- **An absent anchor refuses every in-place writer (beacon S0 item 3 iii).** During SNAP and after an escalation (Part 2, D)
  there is no anchor (5.1). "No anchor" is a compare-and-set **failure** for 7.1, 7.3 and 7.5, not a pass: 7.1 and 7.3 refuse
  with `StateUnavailable` (there is no base to overlay on, so 7.1 does not degrade to an overlay commit either) and 7.5 declines.
  So `forkchoiceUpdated`, the importer and the repairers cannot write `t` or `u` while SNAP owns them; the Engine API answers
  SYNCING or ACCEPTED (6.3.2). The only writers that run without an anchor are the ones that **create** it: the genesis commit
  (5.4), SNAP finalization (7.4) and a checkpoint import's final batch (E.1). Test: with the anchor deleted, each of FCU, the
  importer and a repair is refused and the rows of `t` and `u` are byte-identical before and after.
- **Every plan is made from the anchor, never from the canonical index.** `promoteToCanonicalHead` finds its fork by walking the
  index up to `oldBest` (`BlockchainWriter.scala:255-273`), and the index is written outside any state lock by `newPayload`
  (`EngineApiService.scala:650`, above best) and by side-branch `saveBatch` during `reorganise` (`ConsensusImpl.scala:295`,
  `BlockchainWriter.scala:96-98`, below best). The reverse set is the chain of the anchor's own records, following `parentHash`;
  the forward set is the target's headers by parent hash; the meeting point is found by hash. Rewriting the index stays
  `promoteToCanonicalHead`'s job; the state plan never reads it.
- `StateView.ownerRows` and every enumeration of 3.6 run **outside** the lock, on a validated view, and the commit's
  compare-and-set catches a move in between.

### 7.1 Canonical commit (a block that extends the anchor and is adopted)

Callers: `BlockExecution.executeAndCommit` with `adoptEachBlock = true` (`BlockExecution.scala:686-699`),
`executeAndAdoptBlockIf` (`:712-722`), the genesis commit (5.4) and the converted importer paths (7.6). The caller no longer
passes a finished `extra` batch: it passes `buildExtra(adopted: Boolean)`, because under the state lock the commit may turn out
not to extend the anchor (7.0) and then the best pointer must not be in the batch. One batch, under the state lock:

| Part | Rows |
| --- | --- |
| Block data (`buildExtra(true)`) | header, body, receipts, chain weight, number-to-hash, block access list, `BestBlockHash` and `BestBlockNumber` (`BlockchainWriter.saveBatch(... saveAsBestBlock = true)`, `BlockchainWriter.scala:31-48`) |
| State | `t`: `HP(path) -> rlp` upserts and deletes; `u`: `owner ++ HP(path) -> rlp` upserts and deletes. Only positions in the BlockDiff |
| Changeset | `v`: the record at `blockHash ++ 0x01` (a tombstone if the diff exceeded `max-record-bytes`, 3.6), the height-index entry, the deletes of records below the retention floor (4.5) |
| Anchor | `s`: `state.anchor` (last in the batch) |

Not written: nothing in `n`; no reference count, no `sn`, `dr` or `bd` row. The anchor and the best-block pointer move
together, so after any crash `anchor == best` for every canonical flow. The commit is also a state move (7.0, 6.3.1).

**The three cases that are not "extends the anchor":**
1. **The block is already applied** (its hash is in the anchor's ancestor window): the state half is dropped and `extra` is
   committed, as `ReferenceCountedStateStorage.commitWith` does today for a duplicate (`StateStorage.scala:325-344`).
2. **The block is a forward layer.** The importer's dedupe path reaches `commitWith(..., Some(extra))` with nothing staged
   (`BlockExecution.scala:661-662`). Dropping the state half here would move the anchor to B while the disk holds B's parent,
   so the layer is **promoted**: applied in place if its parent is the anchor, else through a full head move (7.3), in the same
   batch as `extra` (S0 item 3f).
3. **The parent is not the anchor** (another writer moved it, or the block belongs to a side branch): the commit degrades to an
   overlay commit (7.2) with `buildExtra(false)`, and tells the caller (`NotExtendingAnchor`).

**What the caller does with `NotExtendingAnchor` (forge S0 item 3a).** `ConsensusImpl.saveLastBlock` (`:286-291`, called at
`:248,255,265`) writes the best pointer through `saveBestKnownBlocks` after the batch; if any commit of the batch degraded to
case 3 it would move best past the anchor at runtime and break I1. So, on a path-live store: (a) `saveLastBlock` **writes
nothing**, because every canonical commit already carries the best pointer in its own batch; (b) `executeAndValidateBlocks`
**stops at the first commit that no longer extends the anchor** and returns `ConsensusStateFault` for it and every block after
it, retryable and never a peer penalty (6.3.2). The blocks already committed stay committed. Test: a competing head move between
block 2 and block 3 of a 5-block batch leaves best equal to the anchor, commits blocks 1 and 2, and reports a fault for 3-5.

### 7.2 Overlay commit (executed, not adopted)

Callers: `executeAndValidateBlockFull`, the Engine API `newPayload` path (`BlockExecution.scala:63-86`,
`EngineApiService.scala:604`), and `executeAndCommit` with `adoptEachBlock = false` (a side branch,
`BlockExecution.scala:686-699`: it becomes an overlay commit like `:82`, S0 item 2f). **No state row is written.** The block's
diff is frozen into a `ForwardLayer` keyed by hash (idempotent: a second commit of the same hash is a no-op). The block's own
rows (header, body, receipts) are still written by the caller, as today, and so is the number-to-hash entry of a side block
below best (`BlockchainWriter.scala:96-98`): hence invariant I6 (5.1). Consequence: for a block in this state, "receipts stored"
no longer implies "state present" (Part 2, C and D).

**A post-execution INVALID drops the layer** (S0 item 2d). When a payload executes (`Right`) and is then found invalid, as
`INVALID_REQUESTS` is after execution (`EngineApiService.scala:618-631`, which calls `abandonBlockStates` at `:620` today), the
caller calls `StateStorage.dropLayer(hash)` and `BlockImportGate.forget(hash)`. Without both,
`BlockImportGate.alreadyExecuted` (`BlockExecution.scala:1256-1258`) would see `isBlockApplied = true` (a forward layer) and the
importer would reuse the result of an INVALID block.

### 7.3 Head move (`moveHead(target)`: a forkchoiceUpdated, a reorganisation, a re-adoption)

Callers: `BlockchainWriter.promoteToCanonicalHead` (`BlockchainWriter.scala:251-301`, called from
`ForkChoiceManager.applyForkChoiceState`, `ForkChoiceManager.scala:124`), `ConsensusImpl.settleHead` and its
`rewriteCanonicalIndex` calls (`ConsensusImpl.scala:423-500`), the designated-head arm, and the `SYNC-FORK` rewind
(`BlockImporter.rewindCanonicalChain`).

**A batch-contributing hook, not an after-call.** `StateStorage.prepareHeadMove(target)` returns a `HeadMovePrepared` holding
the state rows to write, the `StateMove` to publish and the new anchor. `promoteToCanonicalHead` and `rewriteCanonicalIndex`
take it as a parameter and `.and` it into their index and best-pointer batch **before** their `.commit()`
(`BlockchainWriter.scala:296-300`), under the state lock; after the commit `HeadMovePrepared.complete()` bumps the epoch and
updates the overlay. The earlier idea of a `headMoved` hook with a default that calls the old `abandonBlockStates` and
`readoptBlockStates` would run after the commit (`ForkChoiceManager.scala:124-128`) and cannot be in the same batch; the two old
calls stay as no-op shims until C8b.

**The plan comes from the anchor** (7.0): the reverse set from the anchor's records, the forward set from the target's
headers, the meeting point by hash. The batch holds: the canonical-index and best-pointer rows (`canonicalIndexUpdate`,
`BlockchainWriter.scala:208-221`); for each block to reverse, newest first, the `prior` of every entry; for each block to
forward, oldest first, the `next` of every entry (from the forward layer, or from the persisted record for a block applied
before and reversed since); a `v` record for every block newly promoted (none for a re-adopted block); the new anchor. Because
reverse and forward touch some of the same positions, the batch is **coalesced first** (reverse the old branch, then apply the
new, keep the final value per position) so there is one write per row, and the `StateMove` carries the pre-values (6.3.1).

**Deep moves are chunked.** A move of more than `state.move.max-blocks-per-batch` (default 256) blocks is executed as a chain
of such batches, each leaving `anchor == best` on an ancestor of the target, so an interrupted move is recovered by I1 rules 2
and 3. Under `basic` a move deeper than the retained blocks is refused anyway; under `archive` it is not (4.6). **Block generation
is paused for the duration of a chunked move** (forge S0 item 3d): best steps down through ancestors of the old head, one chunk at a
time, and a miner or a proposer must not build on an intermediate ancestor. The pause is the state lock's holder flag read by the
MockedMiner, the Ethash miner coordinator and the Engine API builder; a build that starts inside it waits, and does not fail.

**Preconditions and refusals.** Every block to reverse has a complete record (a tombstone refuses with `ReorgTooDeep(Incomplete)`);
every block to forward has a layer or a complete record. Otherwise: the fork point beyond the retained blocks is
`ReorgTooDeep(Deep)`; a fork point below the SNAP pivot, genesis or checkpoint block is `ReorgTooDeep(Pivot)`; a block with
neither layer nor record is `StateUnavailable`, which callers answer with **local re-execution** (D) before refusing. A refusal
never moves the pointer.

**Postcondition, always on:** after the batch, the row at `(None, [])` hashes to the target's `stateRoot`, or is absent and the
target's root is `EmptyRootHash`. A root-row check alone would pass interior corruption, so the batch also re-reads a sample
of up to 64 of the positions it wrote and compares them with the plan; the oracle tests (8) are what actually prove exactness.

**`applyForkChoiceState` ordering (S0 item 3e).** Today `currentState.set` runs before the promotion
(`ForkChoiceManager.scala:118` before `:124`) and `currentState` is read as "validated" (`:33-34,71-73`). The order becomes:
plan and promote first; set `currentState` only after the move succeeded. A refused move returns `Left`, so
`forkchoiceUpdated` answers SYNCING, and it builds no payload, updates no safe or finalized state and does not call
`txGossipGate.markSynced` (`EngineApiService.scala:910-913`).

### 7.4 SNAP finalization

Today: `finalizeSnapSync` publishes, then writes the pivot header, weight and best-block (`SnapFinalization.scala:162-289`),
then the three completion flags in one `commitSync` (`StateIngest.scala:79-82`). After the track there is no publish, on
either chain (ETC's hash-scheme SNAP, which wrote CF `n` directly, is gone with the scheme). The same synced commit also
carries `state.anchor = (pivotHash, pivotNumber, pivotRoot)`; the layout marker was stamped at creation (5.2). The pivot
BlockDiff is **not** written (there is no prior). It is a state move (7.0). WAL sync on that commit persists every earlier WAL
record, so the `t`/`u` writes SNAP and healing made are durable before a recovered `snapSyncDone`: this is the DC-1 / 016
FR-044 barrier, met the way research 17 says it already is. It holds under both pruning modes, because neither keeps state in a
deferred-persistence cache; the one mode that did, `inmemory`, is retired (4.6), so the gap `forcePersist` closed
(`StateIngest.scala:84-92`) cannot arise. **Hand-off also needs the ancestor window** (G): hand-off does not happen until it is present.
### 7.5 Repair and on-demand writes

`repairNodeAt(position, expectedHash, bytes)` is a state move (7.0, 6.3.1): it takes the state lock and writes only if
`keccak(bytes) == expectedHash` **and** the row is absent or fails its hash **and** the anchor's current root still reaches
`expectedHash` at that position, all judged under the lock. One point write. It never lowers anything (there are no counts) and
never overwrites a valid different node: a node that is valid for another view is not this view's repair (Part 2, E). Both of
the importer's on-demand writes (`BlockImporter.scala:485,552`, today `StateStorage.saveNode`, a blind upsert at
`StateStorage.scala:362-363`) become `repairNodeAt` in C5b, before `FastSyncNodeStorage` is deleted.

### 7.6 Writers that bypass the staged commit (S0 item 3a)

A path-live store has exactly one in-place writer, the staged commit. Two things enforce it. `executeBlock` with
`staged = None` and `isProposer = false` **throws** on a path-live store, so an unstaged execution cannot write a row. And the
paths that executed unstaged are converted (Part 2, A): `ChainImporter.importLenient` (the hive startup import) and its
`saveImported`, the test-mode world and genesis, the ethereum/tests and EEST harnesses. Anything else that reaches
`getBackingMptStorage`, `getReadOnlyMptStorage`, `getBackingStorage` or `getReadOnlyStorage` is found by an allow-list grep
ratchet (C2b), not by a hand-kept table.
---

## 8. How the trie emits positions (item 8; the consensus-adjacent part)

Hashing, RLP encoding and the trie algorithms do not change (DC-2). What changes is that positions are carried and reported.

**What the trie does today.** `MerklePatriciaTrie.pathTraverse` tracks `accPath` for reads (`MerklePatriciaTrie.scala:218-279`)
and attaches it to a missing-node exception as `location` (`:261,274`). `put` and `remove` track no path: they resolve a
`HashNode` with `nodeStorage.get(bytes)` (`:360`, `:483`), and `fix` does the same for the node an extension absorbs (`:591`).
They return the replaced nodes as `toDeleteFromStorage` (`NodeInsertResult`, `NodeRemoveResult`, `:104-110`), by node and not
by position, and `fix` does not report the absorbed child at all. `updateNodesInStorage(newRoot, toRemove)`
(`MptStorage.scala:22,90-94`) collapses the new tree with `MptTraversals.collapseTrie` (`MptTraversals.scala:16-20`), whose
`NodeCapper` returns `(hash, rlp)` pairs without positions (`RlpHashingVisitor.scala:20-35`).

**What item 8 adds (additive until the retirement slice; the hash-keyed members stay as the default for the ephemeral
list-trie store):**

1. `NibblePath` threaded through `put`, `remove` and `fix` as the position of the node being processed. A branch child `i` is
   `path :+ i`; an extension's next is `path ++ sharedKey`; a temporary node built when a leaf splits sits at the same `path`
   as the node it replaces. To avoid an array copy per level on the ETC hot path, the path is an immutable cons list that is
   materialised only when a position-store `getAt` needs the bytes (spec 007's allocation budget).
   *(C1 as built, 2026-11-11; forge and beacon signed the deviation.)* `put`, `remove` and `fix` thread the nibble array
   itself: at the depths a trie has, one array is the size of one list cell (24 bytes to depth 8, 32 to depth 16), and
   these methods already copy the remaining key at every level. The lazy list is used where it does pay, in the
   collapse: `TriePosition` gives each of a branch's sixteen children a position and builds the nibbles of the few
   that are new and stored. Measured figures: `baselines/m7-c1.md`. A temporary node of a partial-prefix split sits at
   `path ++ sharedPrefix`, below the extension that takes the old node's place, not at `path`.
2. Every old node that is **replaced or absorbed** is reported as `(path, node)`: the nodes on the mutation path, and the child
   `fix` merges into its parent (the extension-into-extension case at `MerklePatriciaTrie.scala:595` and the extension-into-leaf
   case at `:597`). `fix` also *resolves* the child of an extension at `:591` when that child is a branch and stays: `:599`
   returns `node` with the `HashNode` intact. That live branch is **not** reported; reporting "every node resolved" would list
   it as vanished and the forward apply would delete it (S0 item 1b).
   *(C1 as built, 2026-11-11; found by forge and beacon before code.)* **Absorbed nodes are reported apart from replaced
   ones.** The hash-keyed store counts references by the removals it is told of, and the trie has never told it of an
   absorbed node. Had the absorbed node joined the replaced list, the default of section 9 would have passed it to
   `updateNodesInStorage` and basic pruning would have decremented, and in time deleted, a node it keeps today. So
   `remove` returns two lists and `updateNodesAt` takes two (section 9). A node is reported as absorbed exactly when the
   extension held its **hash**: an embedded child has no row, and a child built by this removal was never stored.
3. `MptTraversals.collapseTrieAt(node, rootPath)`: the same traversal and the same `capNode` calls, so the same bytes and
   hashes, but each stored node is reported as `(path, hash, rlp)`. The root is always reported (as `collapseTrie` does today).
   Unchanged subtrees keep their cached encoding (`parsedRlp`) and are not visited, so they produce no writes.
   *(C1 as built.)* One code path: `NodeCapper.capNodeAt` is `capNode` with the position noted beside a stored node, and
   the visitors of `collapseTrie` carry no position at all (no field, no allocation). A test holds the two collapses to
   the same nodes in the same order.
4. `MptStorage.updateNodesAt(newRoot, replaced, absorbed)` computes the block-local change set into the staged layer (6.1). For each
   position, `stored(rlp, path) = rlp.length >= 32 || path.isEmpty`. The prior is fixed at the **first event** that touches the
   position in the block (3, rule 1): the old stored node that event reports, or **None** if the first event is a write with no
   old node there; later old-node reports at that position are intermediates and are ignored. The next is the last new stored
   node there, or none. Positions old-stored and not new-stored are the vanished ones. Positions whose prior equals their
   next are dropped. Every node written is also kept by hash in `StagedLayer.byHash`, in every version (6.2).
5. A node is read with `getAt(path, hash)` wherever the trie resolves one, and the hash is verified (6.3).
6. `deleteAccount` calls `MptStorage.resetOwner(owner)` so the staged layer records the reset (3, rule 6).

**Why positions and not hashes.** A node can occupy two positions with the same hash (two leaves with equal remaining key and
value). Content addressing hides that; a position store must not.

**Oracles (S0 asks forge and beacon to approve these as the acceptance tests, before code):**

- *Incremental versus full diff.* For random sequences of `put` and `remove` (including deletions that collapse branches into
  leaves and extensions, extension splits, nodes crossing the 32-byte boundary in both directions, empty-trie transitions),
  the positions emitted incrementally must equal the positions from walking the whole old and whole new tries and diffing
  their stored positions.
- *`StackTrie` rebuild.* After a sequence of mutations, the position-to-bytes map reachable from the root must equal the map
  `StackTrie` emits through `onTrieNode` when it rebuilds the same key set (`StackTrie.scala:21-35`, `finalise` at `:267`).
  `StackTrie` is the independent implementation of what a position holds.
- *Reverse exactness, single and multi-block.* Apply a block, reverse it: the reachable position map equals the parent's. And
  apply blocks N to N+k where a **later block writes a position an earlier block vanished**, then reverse them all: a one-block
  reverse is exact even when a vanished position was missed (the row is simply never touched), so only a later write at the
  same position plus a deeper reverse exposes it, and the root-row postcondition (7.3) will not (S0 item 1d).
- *Several persist rounds per block.* Blocks built by one persist per transaction, so rule 1's first-event prior is exercised:
  a position created by transaction 1 and replaced by transaction 2 must reverse to absent.
- *Real fixtures, not only random sequences.* The EEST Amsterdam fixtures (EIP-7928 block access lists, the EIP-8246 clear)
  run through the staged layer, because they read older in-block worlds (6.2); the pre-Byzantium and pre-Atlantis ethereum/tests
  networks (Frontier, Homestead, EIP-150) and the ETC import from genesis (plan EG-1), because before Atlantis each receipt
  carries the post-transaction state root that the per-transaction persist produces (`BlockPreparator.scala:689-693`), which
  makes per-transaction position emission consensus-visible there; Mordor, whose Atlantis is at block 0 (`mordor-chain.conf:72`),
  never exercises it.
- *Genesis emits nothing vanished.* A genesis build (insert-only, through `AppendOnlyMptStorage`, `GenesisDataLoader.scala:288-294`)
  emits no vanished positions.
- *Consensus unchanged, both chains.* The roots and node sets of the same random sequences, and of recorded Mordor and
  Sepolia block ranges, equal the ones the **pinned pre-track build** (the Hash-scheme release immediately before C1, kept as
  a jar outside the tree) produced. That jar is the differential oracle for the whole track: the Hash scheme is deleted in
  the last slice, so no in-tree oracle survives it.

**Owner threading.** The trie never sees the owner. `MptStorage.forOwner(owner)` returns a view scoped to one trie, created at
the construction sites: the account trie (`InMemoryWorldStateProxy.createProxiedAccountsStateTrie`, `:129-140`, owner `None`)
and each storage trie (`InMemoryWorldStateProxy.getStorageForAddress`, `:305-319`, owner `Some(keccak(address))`; a wrapper
there already turns a missing node into `MissingStorageNodeException`), and the genesis loader's storage tries
(`GenesisDataLoader.scala:159-160,172-176`, which today build into one shared storage with no owner). The hash-keyed
(ephemeral) store's `forOwner` returns `this`. Private nets with `ethCompatibleStorage = false` (`ArbitraryIntegerMpt`) need
nothing: a position is the key's nibble path whatever the key is. (No shipped chain sets it false; the plan's test for it is
scoped to private nets, not to ETC.)

**Empty trie.** A trie that becomes empty reports its root position as vanished, and an empty trie has no rows (section 2).

---

## 9. `MptStorage` contract additions (additive; defaults keep every implementor compiling and behaving)

```scala
trait MptStorage:
  // existing members unchanged: get(hash), updateNodesInStorage, persist, multiGetNodes, rawNodes, storeRawNodes
  def getAt(path: NibblePath, hash: Array[Byte]): MptNode = get(hash)                  // default: the hash-keyed store
  def forOwner(owner: Option[ByteString]): MptStorage = this
  def resetOwner(owner: ByteString): Unit = ()                                         // 3, rule 6: the staged layer records it
  def updateNodesAt(newRoot: Option[MptNode],
                    replaced: Seq[(NibblePath, MptNode)],          // the nodes on the path of the change: today's list, same order
                    absorbed: Seq[(NibblePath, MptNode)]           // merged into their parent by `fix`; never passed to a hash-keyed store
                   ): Option[MptNode] =
    updateNodesInStorage(newRoot, replaced.map(_._2))              // C1, 2026-11-11: was one list, `removedOld` (section 8 item 2)
  def rawNodesAt(requests: Seq[(NibblePath, ByteString)]): Seq[RawNodeRead] = rawNodes(requests.map(_._2))
```

- **A decorator sees every read** (user decision 6). Every read, position-addressed or not, goes through an `MptStorage` method
  that carries the hash (`get(hash)` or `getAt(path, hash)`), and a decorator that wraps `forOwner`'s result sees the scoped
  reads too. *(C1 as built, 2026-11-11.)* `MptStorageDecorator` makes `get` and `getAt` final over one hook, `read(hash, path)`,
  and `updateNodesInStorage` and `updateNodesAt` final over another, `update(newRoot, removals)`: a wrapper that overrode `get`
  alone would be bypassed by a trie that reads with `getAt`, and one that withheld removals in `updateNodesInStorage` alone
  would hand them on through `updateNodesAt` (forge and beacon, C1 review). **C2a (T041):** `AppendOnlyMptStorage`,
  `ReplayMptStorage` and `RetainingMptStorage` override `update` with `removals.none`; `CachingMptStorage`, `PrefetchNodeReader`
  and the `contextStorage` wrapper override `read`; each gets a test that a positioned call goes through its override.
  To make a forgotten member impossible, the seven implementors (`SerializingMptStorage`, `PrefetchNodeReader`,
  `CachingMptStorage`, `GenesisDataLoader.AppendOnlyMptStorage` at `:288`, the anonymous `contextStorage` in
  `InMemoryWorldStateProxy.scala:309`, `StxLedger.ReplayMptStorage` at `:703`, `EthSimulateService.RetainingMptStorage` at `:144`)
  that wrap another storage derive from a new `MptStorageDecorator` that forwards every member, including a wrapped `forOwner`
  and `resetOwner`. A forgotten `getAt` would fall back to `get(hash)` and throw on a position store (loud), and the decorator
  base makes it a compile-time fact.
  *(C2a part 1 as built, 2026-11-11.)* Six of the seven derive from the base: `SerializingMptStorage` wraps a
  `NodesKeyValueStorage`, not another `MptStorage`, and stays as it is. The `contextStorage` wrapper is the named class
  `InMemoryWorldStateProxy.AccountStorageNodes`. `PrefetchNodeReader.forOwner` gives a reader on the **same** budget and the
  same counts (a block's prefetch is one budget, however many tries it reads), and its `resetOwner` throws like its other
  writes: it is a read-only view of the parent state (beacon).
- **A batch read is not an execution read** (C2a, T041). `multiGetNodes`, `rawNodes` and `rawNodesAt` pass through a decorator
  as they are and do not meet its `read` hook. Their only callers are SNAP (`ContractWorkCarry`), healing
  (`FrontierRebuildBfs`, `HealResponseProcessing`) and the background audit (`BackgroundAudit`), each on the state storage's
  own `SerializingMptStorage`; no world, replay or simulation reads a node through them. So a cache, a budget or a recorder
  in front of execution misses nothing by not seeing them. The C2b ratchet (T062) lists the three members with these four
  callers, so a new caller is a reviewed decision; the spec 017 recorder (parked) relies on this line.
- **The spec 017 recorder, when it exists, is a decorator with three rules** (S0 item 9). (a) It sits **above**
  `CachingMptStorage` and `DecodedNodeCache`: a cache hit short-circuits `get` and `getAt`, so a recorder below the cache would
  miss reads. (b) It records **parent-state reads only**: nodes the block itself created (served from `StagedLayer.byHash`, 6.2),
  the enumeration of 3.6 (`StateView.ownerRows`, which bypasses decorators, the BAL builder and `ImportProfile`) and the BAL
  prefetch are not witness reads. (c) Every read of a node of the parent state carries its hash, so `hash -> bytes` is
  recorded, verified.
- **`get(hash)` stays available on the position store.** It answers the account-trie root of any view (the row at `(None, [])`,
  verified) and any node held in a forward layer's `byHash`. Any other hash throws `MissingNodeException`: a position store
  cannot find an arbitrary node by hash. The only peers that ask for arbitrary hashes use `GetNodeData`, which eth/67 removed;
  fukuii negotiates eth/68 and eth/69, so `BlockchainHostActor.scala:137` answers the root and overlay nodes and nothing else,
  at no cost to either chain's peers.
- **A batch of hashes is refused, not answered "absent"** (C2a part 2). The trait's defaults for `multiGetNodes` and `rawNodes`
  loop over `get(hash)`, which on a position store would report nearly every node absent, and their callers read "absent" as
  "fetch it" (healing) or "it is gone" (the audit). `PathMptStorage` throws on both. `rawNodesAt` answers by position, one
  view read per request; its batched form without block-cache fill arrives with the audit's conversion (Part 2, E).
- **Every hash-only reader is converted or shown not to read state, and a grep ratchet enforces it** (C2b). Beyond the SNAP serve
  walkers and `BlockImporter.scala:706`, `SyncController.scala:1985`, `SnapServer.scala:68-77`, the ratchet covers:
  `LeafWalkVisitor.scala:19`, `PathTrackingLeafWalkVisitor.scala:42-44`, `ShardEnumerator.scala:46`,
  `MptConstructionVisitor.scala:18`, `StateValidator.scala:138,204`, `NetworkPeerManagerActor.scala:1164` and
  `BlockchainReader.getMptNodeByHash` (`:77`). Each either takes a position or is listed on the allow-list with the reason it
  is not a state reader (an ephemeral list trie, a test).
- **No new import in the stateless closure.** `NibblePath` lives in `mpt/`; the new members have defaults; a witness-backed
  storage (017 FR-005) inherits them unchanged.
- **`db.storage` and `db.storage.pathstate` know each other** (C2a part 2; beacon). `pathstate` is built on `db.storage`
  (`MptStorage`, `PathNodeStorage`, `AppStateStorage`), and two files of `db.storage` name `pathstate` types:
  `AppStateStorage` (`BlockRef`, for the anchor) and `PrefetchNodeReader` (`StateFault`, to stop on one). Neither is in the
  stateless closure. The cycle ends with the Hash scheme (C8b), when the path state storage is the state storage.
- **Whether a state is held is asked of `PathStateStorage.stateAvailable`, never of `get(root)`** (beacon, C2a part 2).
  `get(hash)` answers "missing" for any hash that is not the view's own root, which is right, and which a caller probing with
  a root would take for a state hole and report (6.3.2 forbids that for a storage condition). The ratchet of C2b covers it.

---

## 10. Configuration (HOCON; proposed keys and defaults)

```
fukuii.state {
  # IN-DEVELOPMENT GUARD ONLY. Selects the unified layout while the track's PRs land, so staging stays green between
  # them. Not an operator option: it is never shipped as a transition, and it is deleted, with the Hash-scheme code it
  # guards, in the track's last slice. After that there is no flag and no second layout.
  path-live = false

  changeset {
    # BlockDiffs kept: the retention floor of 4.5 under basic; all under archive.
    max-entries-per-owner-delete = 1000000   # above it the dead trie's rows are NOT deleted; the record becomes a tombstone (3.6)
    max-record-bytes = 268435456             # 256 MiB; above it the record is a tombstone. Neither ever fails a block
  }
  retention {
    finality-margin = 16                     # blocks kept above a consensus client's finalized block (4.5)
    max-blocks = 512                         # bound on the retention floor
    without-finality = 512                   # blocks kept where there is no finalized block: a proof-of-work chain (user, 2026-11-11)
  }
  overlay {
    max-bytes = 268435456                    # forward layers + resident reverse diffs, excluding pinned layers (a guess until M1, M2)
    pinned-max-bytes = 268435456             # layers of a branch under execution (6.4); a guess until M1, M10, M13
    max-forward-layers = 32
    resident-reverse-blocks = 16
    max-layer-bytes = 67108864               # 64 MiB
    max-view-age-moves = 8
  }
  move { max-blocks-per-batch = 256 }        # deep moves are chunked, each chunk atomic (4.6, 7.3)
  archive  { view-reach = 1024 }             # blocks a VIEW reaches under pruning.mode = archive; moves are unbounded
  recovery {
    max-blocks = ${pruning.history}          # blocks a local re-execution may cover (Part 2, D)
    escalation-refusals = 64                 # consecutive forkchoiceUpdated refusals (about 2 epochs at one per 12 s slot) before a PoS escalation (D)
    escalation-cooldown = 6h                 # at most one escalation per cooldown; counted in a metric (D)
  }
  # Reverse depth (best - fork.number), NOT branch length, above which a move is refused up front (ReorgTooDeep(Deep), 6.4).
  # Defaults to the retention floor of 4.5 (history, finalized lag + margin, bounded by retention.max-blocks): the floor is what
  # the records cover, so a reorg at the finalized lag (about 90 behind a consensus client) is NOT refused. A bare
  # ${pruning.history} would refuse it. A fixed value below the floor is honoured.
  reorg { max-depth = floor }
}
```

`pruning.mode` is `basic` or `archive` on both chains (4.6). **`pruning.mode = "inmemory"` is rejected at start**, on both chains,
with a message that names the retired mode and the two that remain. The keys `sync.snap-sync.storage-scheme` and the whole
`inmemory-pruning-node-caching` block are deleted with the Hash scheme, with no deprecation window; a config that still sets
them fails at start with a message naming the deleted key. `pruning.history` keeps its meaning and its default.

---


## 11. Metrics (Micrometer; Prometheus names follow the `app_db_rocksdb_*` style)

| Metric | Kind | Meaning |
| --- | --- | --- |
| `app.db.state.anchor.number` | gauge | block number the disk layer holds |
| `app.db.state.anchor.lag.blocks` | gauge | `best - anchor`: 0 in steady state; non-zero outside a move is a bug |
| `app.db.state.commit.seconds` | timer | the canonical commit batch (the `[IMPORT-COMMIT]` figure) |
| `app.db.state.commit.positions` | summary | positions written per block: measures the "10k nodes per block" assumption (`inmemory-pruning-node-caching.conf`) |
| `app.db.state.commit.bytes` | summary | batch bytes per block, state and changeset |
| `app.db.state.changeset.records` | gauge | records in CF `v` |
| `app.db.state.overlay.forward.layers`, `.bytes` | gauge | forward layers and their size |
| `app.db.state.overlay.reverse.resident`, `.bytes` | gauge | resident reverse diffs |
| `app.db.state.overlay.evictions` | counter | by pool |
| `app.db.state.overlay.reverse.builds`, `.build.bytes`, `.refused` | counter, summary, counter | reverse indexes built, their size, and readers told to ask again because as many as their kind may build were being built (C4a part 4b-1) |
| `app.db.state.view.stale` | counter | `StaleStateViewException` thrown, by caller (execution, rpc, snap-serve, prefetch) |
| `app.db.state.view.retries` | counter | executions retried after a stale view |
| `app.db.state.view.behind.refused` | counter | reads behind the head refused because as many as may be were in flight (C4a part 4b-2) |
| `app.db.state.move.seconds` | timer | `moveHead`, by direction and block count |
| `app.db.state.reorg.refused` | counter | `ReorgTooDeep` and `StateUnavailable`, by reason |
| `app.db.state.verify.mismatch` | counter | position mismatches, by class (stale, corrupt, absent) |
| `app.db.state.prune.records` | counter | records deleted by retention |
| `app.db.state.lock.wait.seconds` | timer | time spent waiting for the state lock (7.0), by holder (commit, move, repair) |
| `app.db.state.commit.degraded` | counter | canonical commits that found the anchor moved and became overlay commits (7.0) |
| `app.db.state.record.tombstones` | counter | records written as incomplete tombstones, by cause (owner-delete bound, record size) |
| `app.db.state.dead-trie.skipped` | counter, summary | replaced storage tries whose rows were left in place (3.6), and their row counts |
| `app.db.state.recovery.reexecuted.blocks` | counter | blocks re-executed locally to rebuild unavailable state (Part 2, D) |
| `app.db.state.recovery.escalations` | counter | escalations to the re-SNAP route, by reason (Part 2, D) |
| `app.db.state.recovery.escalations.suppressed` | counter | escalations not taken, by cause (cool-down, loop guard, below the hysteresis count) (Part 2, D) |
| `app.db.state.overlay.pinned.layers`, `.bytes` | gauge | layers pinned by a branch under execution (6.4) |
| `app.db.state.checkpoint.import.nodes` | counter | nodes written by the position-assigning importer (C5c), by trie (account, storage) |

Log tags: `[STATE-ANCHOR]`, `[STATE-MOVE]`, `[STATE-REORG-TOO-DEEP]`, `[STATE-STALE-VIEW]`, `[STATE-LAYOUT]`.

---

## 12. Disk and write-amplification model

| | Today (ETH, Path scheme, after hand-off; ETC on the Hash scheme has `n` and no `t`/`u`) | After the track (both chains) |
| --- | --- | --- |
| Families holding live state | `t`, `u` (frozen SNAP output) and `n` (the copy plus every later block: multi-version, reference-counted) | `t`, `u` only |
| Disk for a fresh Sepolia sync | about 300-340 GB at hand-off: about 170 GB to the end of state plus about 150 GB for the hash copy (spec 022 tasks, T063) | about 170 GB plus the changesets below: the copy is gone, a saving of about 150 GB |
| Rows per block | node wrapper (rewritten on every count change), snapshot rows, death-row rewrite, abandon record, and **one point `get` on `n` per node** for the count (research 17: the read that made the copy read-bound) | node rows, one BlockDiff (prior and next copies of each changed node), height index, anchor: **no reads for counts** |
| History kept | `history` blocks of snapshots and death rows in `n` | `history` BlockDiffs in `v`: about `history x 2 x bytes changed per block` (64 x about 5 MB, about 320 MB, if a block changes 10,000 nodes of about 250 bytes; to be measured, section 13) |

Bytes written per block are expected to be about equal (about three copies of each changed node either way); the win is the
removed reads and the removed second copy of the state. Both are claims until the section 13 measurements exist (DC-14).

---
---

# Part 2: design record, questions A to I (with evidence)

Each answer says what the tree does today, then what Option Y does. "S0 item" numbers refer to the checklist in the plan draft.

## A. Write path

**Where a block's state reaches the database today.**
- *Staging.* `Blockchain.stageBlockState(bn)` (`Blockchain.scala:125`) calls `StateStorage.stageBlock(bn)`
  (`StateStorage.scala:122`). Under basic pruning, `ReferenceCountedStateStorage.stageBlock` (`:311-357`) returns a
  `StagedBlockState` over a `ReferenceCountNodeStorage` over a `BufferedNodeStorage`: **memory**. Archive and in-memory
  pruning return `StagedBlockState.direct` (`:79-82`, `:185-199`), which writes straight through.
- *Per-transaction persist.* `BlockPreparator.scala:689-693` runs `InMemoryWorldStateProxy.persistState` after every
  transaction (`InMemoryWorldStateProxy.scala:84-113`). Contract code goes to `EvmCodeStorage` and is committed at once
  (`:88`; content-addressed, so a failed block leaves harmless rows). Then each touched storage trie and the account trie are
  persisted through `InMemorySimpleMapProxy.persist` (`InMemorySimpleMapProxy.scala:53-60`): one trie `put` or `remove` per
  dirty key, each calling `MptStorage.updateNodesInStorage`. Under basic pruning those land in the buffer, so nothing reaches
  the database until commit.
- *Commit.* `BlockExecution.executeAndCommit` builds the block's batch (`saveBatch`, `:686-691`, plus the access list,
  `:693-696`) and calls `staged.commitWith(number, hash, Some(batch))` (`:697-699`). `commitWith` runs under
  `ReferenceCountedStateStorage.commitLock` (`StateStorage.scala:232`, `:325-344`): a duplicate check on the `bd<hash>` record,
  then `other.and(state).commit()`, one atomic write of block, receipts, weight, best pointer, trie nodes, counts, snapshots,
  death row and abandon record (#1465). The single-block Engine API path commits at `BlockExecution.scala:82` with
  `extra = None`; it does **not** move the best pointer, `forkchoiceUpdated` does.

**What Option Y writes per block.** Section 7.1 for a canonical commit, 7.2 for an overlay commit. The in-place rows are
`(owner, path) -> rlp` upserts for every stored node the block created or changed, and point deletes for every position that
vanished, including the storage trie of a removed account (3.6). The changeset is the BlockDiff of those same positions with
prior and next. It is atomic with the head and best-block metadata: it is the same `DataSourceBatchUpdate`
(`DataSource.update` applies a `Seq[DataUpdate]` atomically, `DataSource.scala:110-113`; multi-CF atomic batches exist,
research 17).

**How the trie's `put` and `remove` emit positions.** Section 8.

**The best-pointer and state-writer audit (invariant I1, S0 item 3a).** The first audit said every pointer writer "carries a
state move or falls under rule 4". That was not true of the tree. The corrected table, by writer:

| Writer | Site | Under the unified layout |
| --- | --- | --- |
| `saveBatch(saveAsBestBlock = true)` | `BlockchainWriter.scala:31-48`; `BlockExecution.scala:686-691` | carries the canonical commit (7.1), under the state lock (7.0) |
| `promoteToCanonicalHead` | `BlockchainWriter.scala:251-301`; `ForkChoiceManager.scala:124` | takes the `HeadMovePrepared` and `.and`s it before its `.commit()` at `:296-300` (7.3) |
| `rewriteCanonicalIndex` | `BlockchainWriter.scala:199-205`; `ConsensusImpl.scala:466,489` | `:466` carries the head move; the keep-old-head arm `:489` moves nothing |
| `saveBestKnownBlocks` | `BlockchainWriter.scala:170-174`; `ConsensusImpl.scala:287,454` | `:287` (`saveLastBlock`) is a no-op on a path-live store: it rewrote what the adopt batch already wrote, and after a degraded commit it would move best past the anchor (7.1); `:454` (the PoS designated-head arm) becomes reverse-to-fork then canonical commit per block (6.4) |
| `setCanonicalChainHead` | `BlockchainWriter.scala:185-190` (fork recovery) | a rewind: a head move to an ancestor in the same batch (7.3), refused beyond the reach (never I1 rule 4 at runtime) |
| `Blockchain.removeBlock` | `Blockchain.scala:166` | adjusts best after a removal: a head move when the block is at or below the anchor, else a pointer-only hint that cannot pass the anchor |
| `CheckpointImporter` | `CheckpointImporter.scala:165` | position-assigning importer (E.1, C5c): anchor in its final synced batch |
| SNAP finalization, `PivotSelector` | `SnapFinalization.scala:268,360`; `PivotSelector.scala:437` | `:268` carries the anchor (7.4); the others run before hand-off and set no anchor |
| **`ChainImporter.importLenient`** (hive startup import, `import-chain-file`) | `StdNode.scala:141-147` -> `ChainImporter.importChainFile` -> `importLenient`; `ChainImporter.scala:150-159,177-186,197,374` | **an unstaged direct writer today**: it executes extends-head blocks and every post-merge block, side blocks included, through `executeBlockNoValidationWithRequests` -> `executeBlock(staged = None)` -> `buildInitialWorld` -> `getBackingMptStorage` (`BlockExecution.scala:377`), then moves best in a separate `save(saveAsBestBlock = true)`. Converted in C3c: stage, execute, validate, then commit, canonically if the parent is the anchor, else as an overlay commit followed by a head move, with the pointer in the same batch |
| `ChainImporter.importChainFileStrict` | `ChainImporter.scala:341`; `admin_importChain`, `ChainTool` | already `executeAndAdoptBlockIf`; unchanged apart from the commit-mode parameter |
| **Test mode** | `StdNode.scala:138-139` (skips `GenesisDataLoader`); `TestService.scala:247-256`; `TestModeBlockExecution.scala:36-51` (a `buildInitialWorld` override) | converted in C3c: same genesis commit and stamp, owner threading; the world override reads a view |
| `GenesisDataLoader` | `GenesisDataLoader.scala:130` | genesis commit, anchor = genesis (5.4) |
| `StdNode.fixDatabase` | `StdNode.scala:434` | repairs a missing hash only; no state meaning |

The rule that makes the table checkable instead of hand-kept: on a path-live store, `executeBlock` with `staged = None` and
`isProposer = false` **throws** (7.6), so an unlisted unstaged writer fails loudly in its first test. Slice C4b turns the table
into a test: at the end of each flow, `anchor == best`, or the documented exception holds.

## B. The overlay and its depth

Structure, lookup order and eviction are sections 6.1-6.4. **Flush policy: none along the canonical chain.** The disk layer *is*
the canonical head, written at promotion (7.1, 7.3); the overlay never lags the head and has no flush step. That is what
reading decision 2 as write-through means. The overlay holds (a) forward layers of executed blocks that are not the head, and
(b) a cache of the reverse side of recent changesets.

**Reconciling 64 and 128, and the finality lag.** `pruning.history` is 64 (`pruning.conf:15`). SNAP serving advertises a
128-block window (`NetworkPeerManagerActor.scala:1112-1130`). The two already disagree: basic pruning deletes the nodes of block
`min(bn, best + 1) - history` (`StateStorage.scala:269-287`), so a root 64 to 128 blocks back is partly pruned and
`serveAccountRange` answers an empty range through its `catch` (`NetworkPeerManagerActor.scala:1098-1103`). **On ETH there is a
second constraint**: a consensus client's finalized block is routinely 64 to 96 blocks behind the head, and a reorg back to a
block above the finalized one is consensus-legal, so a fixed 64 would refuse a legal reorg (S0 item 3h). Recommendation:
- the **retention floor** (4.5) is `max(pruning.history, head - finalized + 16)`, bounded by `state.retention.max-blocks` (512),
  where a consensus client supplies `finalized`; ETC (PoW, no finalized block) keeps `pruning.history`. Reverse reach, reorg
  depth and changeset retention are all this floor;
- the served-root window is clamped to `min(128, the floor)`, so the code stops advertising what it cannot serve;
- an operator who wants more sets `pruning.history` higher: about 5 MB of changesets per block and no change in memory,
  because resident reverse diffs stay at 16;
- a fork point below the SNAP pivot, genesis or checkpoint block is outside every reach (`ReorgTooDeep(Pivot)`): the pivot is
  head minus 64 (`PivotSelector.scala:620-639`), so only non-finality can reach it, and the recovery is the escalation of Part 2, D.

**Recommended memory bound: 256 MiB total** (128 MiB forward layers, 128 MiB resident reverse diffs), about 1.6% of a 16 GiB
host. Per-block size drives the layer count: at 10,000 changed nodes (the repo's own estimate,
`inmemory-pruning-node-caching.conf`) a both-sided diff is about 5-7 MB resident, so 128 MiB is 18-25 layers; Sepolia's
smaller blocks give more. **Measure first** (section 13): changed positions and diff bytes per block on Sepolia, Mordor and
Platåberget (p50, p99, max); the number of simultaneously unpromoted payloads over 24 hours of head following; JVM overhead
per entry; the stale-view rate under trace load.

## C. Non-canonical execution

| Case | Today | Under the unified layout |
| --- | --- | --- |
| `newPayload` on the head's child | executes, commits state with counts, writes the index entry but not the pointer (`EngineApiService.scala:604-652`) | executes against the anchor view; **overlay commit**; the index entry is still written (`:650`); the state reaches disk at `forkchoiceUpdated` (a head move) |
| `newPayload` on a non-head parent | `executeAndValidateBlockFull`, then `abandonBlockStates(Seq(block))` at once (`:657`) so the side block holds no counts | executes against `view(parent)`; overlay commit; **no abandon exists**: a never-applied block has nothing to undo |
| sibling of the head | same | same: `view(head.parent)` is the head's reverse diff over disk |
| a payload found invalid after it executed (`INVALID_REQUESTS`) | `abandonBlockStates` (`:620`) | the layer is dropped and the `BlockImportGate` memo forgotten (7.2) |
| reorg inside the retained blocks | `settleHead` / `applyForkChoiceState` rewrite the index, then `abandonBlockStates(dropped)` and `readoptBlockStates(adopted)` as separate writes (`ConsensusImpl.scala:459-460,475-476`; `ForkChoiceManager.scala:127-128`) | one head move whose state rows are **in the pointer batch**, planned from the anchor (7.3), under the state lock (7.0). The `HeadMovePrepared` is passed into `promoteToCanonicalHead` and `rewriteCanonicalIndex` and `.and`ed before their `.commit()`; it is not an after-call hook |
| the PoS designated-head arm (`ConsensusImpl.scala:453-460`) | executes the whole branch with `adoptEachBlock = false`, then moves best to the last executed block | **one reverse move to the fork point, then a canonical commit per block**: no N-layer stack, no N-block batch (6.4) |
| deeper than the retained blocks, or a block with no complete record | the new branch executes against a pruned parent and fails with a missing node (`ConsensusImpl.scala:334-344`, `REORG-EXEC-FAIL`) | refused up front as a typed `StateFault` (6.3.2): `ReorgTooDeep` or `StateUnavailable`; the pointer does not move; `forkchoiceUpdated` answers SYNCING; PoW gets new `ConsensusResult`s, never a missing-node fetch and never a branch failure; the escalation of D follows |
| `forkchoiceUpdated` and `currentState` | `currentState.set` before the promotion (`ForkChoiceManager.scala:118`, `:124`) | promote first; set `currentState` only after the move succeeded; a refusal leaves it, the safe and finalized state, the payload id and `txGossipGate` untouched (7.3) |
| a block executed twice (Engine API and importer) | `BlockImportGate` plus `isBlockApplied` on the `bd` record | `isBlockApplied(hash)` = the hash is a forward layer or is in the anchor's ancestor window; an already-applied or forward-layer block reaching `commitWith` is handled by 7.1, cases 1 and 2 |

**Why the old undo cannot be reused.** Today's abandon and readopt are *relative* (`refs -= delta`), so they commute with every
other block's writes (`ReferenceCountNodeStorage.scala:310-410`). An in-place store has absolute rows, and `rollbackReporting`
says of itself "NOT a general undo ... only correct for the tip of a chain whose later blocks have been undone first"
(`:282-284`). Reverse must run newest first, forward oldest first, and every move must be serialised against every other and
against in-place commits (the lock and compare-and-set of 7.0).

**S0 item 2, commit timing in the Engine API path.** A payload's state reaches disk at `forkchoiceUpdated`, not at
`newPayload`. This is the largest behaviour change in the track. It is visible to the Engine API only as latency: a promotion
writes a few MB in one batch, which is the work `newPayload` does today.

### C.1 The PoW arms (ETC) use the same machinery

The paths are shared code, so ETC needs no second design, only its own tests and forge's sign-off (S0 items 3, 5, 11; item 13 is moot, the `inmemory` mode being retired):
- A block extending the head goes `handleBranchImport` -> `importToTop` with `adoptEachBlock = true`
  (`ConsensusImpl.scala:151-169,225-274`): a canonical commit per block (7.1). `followsConsensusLayer` is false on a PoW chain,
  so the `displaced` list stays empty (`:236-241`).
- Any other branch goes `importToNewBranch` (`:171-221`): the heavier-by-weight test (`:204`) selects `reorganise`
  (`:298-362`), which finds the fork (`locateFork`, `:509-525`), executes the new branch with `adoptEachBlock = false`
  (`:326`), an **overlay commit** (7.2) whose layers are **pinned** until `settleHead` (6.4), and then `settleHead`
  (`:423-500`). In the heavier arm (`executedWeight > oldBestWeight`, `:465`) `rewriteCanonicalIndex` (`:466-471`) takes the
  `HeadMovePrepared` (7.3). In the `REORG-PARTIAL` arm (`:477-499`) the executed side blocks stay overlay-only; they are
  re-enqueued (`ConsensusAdapter.scala:171-173`), so their layers are kept for re-use and not the first evicted, and
  `abandonBlockStates(sideBlocks)` at `:499` is a no-op.
- **`fork.parentSideChain`** may name executed non-canonical blocks whose layers were evicted or lost at a restart; they are
  re-executed locally by `StateRecovery` (D) before the move is planned.
- **Canonical index before settle (I6).** Side-branch execution writes the number-to-hash entry for every executed block
  before `settleHead` (`BlockExecution.scala:686-691` -> `BlockchainWriter.scala:96-98`), so a crash in that window leaves the
  index naming side blocks while `best == anchor`; the startup check I6 rewrites it from the headers.
- **The `SYNC-FORK` rewind** (`BlockImporter.rewindCanonicalChain`, `BlockImporter.scala:130-140,1333`, ending in
  `setCanonicalChainHead`, `BlockchainWriter.scala:185-190`) goes back `MaxForkAncestryDepth = 128` blind
  (`BlockImporter.scala:84,1313`), deeper than the 64-block reach, and the resolver's LCA can be deeper still (`:1270-1278`).
  The blind rewind is **clamped to the current reach**; if the LCA is beyond the reach the importer escalates
  (`RegularSyncStuck`, `:1330`) and logs `[STATE-REORG-TOO-DEEP]`. It never applies I1 rule 4 at runtime.
- **Result mapping.** `ReorgTooDeep`, `StateUnavailable` and `BranchTooLarge` are new `ConsensusResult`s
  (`ConsensusStateFault`), never `ConsensusErrorDueToMissingNode` (`:334-344`) and never `BranchExecutionFailure` (`:357`).
- **`importToTop` and `saveLastBlock` (forge S0 item 3a).** On a path-live store `saveLastBlock` (`:286-291`, called at
  `:248,255,265`) writes nothing, and `executeAndValidateBlocks` stops at the first commit that no longer extends the anchor
  and returns `ConsensusStateFault` for the rest (7.1). Otherwise a degraded commit would let the runtime move best past the anchor.
- **A heavier branch is refused on reverse depth, not length (forge S0 item 3b).** A node whose head was orphaned can meet a heavier
  branch that forks 1 or 2 blocks down and runs past 64 blocks; `reorganise` executes it (pinned bytes permitting, else the
  reverse-then-commit fallback), and refuses `ReorgTooDeep(Deep)` only when `best - fork.number` exceeds the retained depth (6.4).
- ETC forks of depth one or two are routine at 13-second blocks; each is a head move of one or two blocks, one batch, and no
  re-execution, because the new branch was executed first. Ommers and uncle rewards depend on side-chain headers, not on side
  chain state.
- ETC mainnet has seen attacks that reorganised about 3,700 to 7,000 blocks. Under `basic` the retained blocks bound a move,
  as today's pruning does; under `archive` moves are unbounded and chunked (4.6, 7.3). MESS (ECIP-1100) is inactive until
  Olympia.
- A mined block is a block like any other: it re-enters through `MinedBlockImport` (`BlockImporter.scala:278,409-410`) and is
  executed and committed by the import path. The proposer's own run (E table, "Block generation") never writes.
- Rewards are unchanged: `payBlockReward` (`BlockPreparator.scala:50,1069`) and the execution path pay through
  `world.saveAccount`, which is storage-layout-agnostic. ECIP-1017 era arithmetic is not touched; the gate (EG-1, EG-6)
  compares every root and every miner and ommer balance across the era boundaries with the pinned pre-track build.

## D. Crash recovery

**What the node recovers to.** The disk layer is exact: every canonical flow commits state, changeset, anchor and best
pointer in one WAL batch, so after any crash `anchor == best` and the rows are the anchor's state. **No re-import is needed for
the canonical chain.** The overlay is lost, which means only the forward layers of blocks that were executed but not
promoted; the reverse side is rebuilt lazily from CF `v`. **No shutdown journal** (geth writes one so it does not re-execute
its 128 diff layers). A journal would cost one extra write per block on Sepolia (every block is a `newPayload` first),
doubling write volume, to save a few seconds of re-execution after a crash.

**What breaks.** Today "receipts stored" implies "state present", because `newPayload` commits state before storing receipts;
`EngineApiService.scala:588-591` uses exactly that as `parentValidated`. Under the unified layout a crash between
`newPayload(B)` and `forkchoiceUpdated(B)` leaves B with header, body, receipts and its canonical-index entry (`:650`) and **no
state**. `parentValidated` therefore becomes "the parent's state is available" (`StateStorage.stateAvailable(hash)`: a forward
layer, the anchor, or an anchor ancestor with a complete record).

**What the Engine API may claim, after a crash and otherwise (S0 items 2a and 2b).**

| Situation | `newPayload` | `forkchoiceUpdated` |
| --- | --- | --- |
| header and receipts already stored (the receipts dedupe, `EngineApiService.scala:407-443`; it needs only header plus receipts and also admits side payloads, so a canonical entry is not required) | VALID, unchanged: the block was validated before the crash, so the claim is truthful | |
| the block executes now and validates | VALID | |
| the parent is stored but its state is unavailable | **ACCEPTED**, the existing not-executed arm (`:706-708,727-743`), and local re-execution is scheduled | |
| the head's state is available and the move succeeded | | VALID |
| the head is stored but its state is unavailable | | SYNCING, and local re-execution is scheduled |
| the move is refused (`ReorgTooDeep`, or `StateUnavailable` that local recovery could not fix) | | SYNCING, and the escalation below |
| execution finds the block invalid, **and** `InvalidChainReporter.provesConsensusInvalid` is true for the error (`InvalidChainReporter.scala:159-187`) | INVALID | |

INVALID is answered for nothing else: never for a `StateFault` (6.3.2), never for the record guard (it cannot fail a block,
3.6), never for a `CorruptNodeException`. The crash test asserts "not VALID and not INVALID, then VALID once re-executed".

**Local recovery (S0 item 2c; go-ethereum's `recoverAncestors`).** After a crash B is stored and stateless, and the first design
left it to "the importer", which fetches from peers; hive and isolated devnets have none, and no peer is needed to
re-execute a block whose body is on disk. `StateRecovery` is triggered by a `newPayload` or `forkchoiceUpdated` that meets a
stored block whose state is unavailable, and by `reorganise` meeting a `fork.parentSideChain` block with neither layer nor
record (a side block whose layer was evicted or lost at a restart: BlockQueue roots a branch at any block with a stored chain
weight, `BlockQueue.scala:66-73`, which includes executed non-canonical blocks, and `locateFork` walks over them,
`ConsensusImpl.scala:509-525`):
1. Walk parents by header from the block to the nearest state-available ancestor (the anchor, a forward layer, an anchor
   ancestor with a record). If the chain is longer than `state.recovery.max-blocks` or a body is missing, escalate.
2. Execute the stored blocks oldest first on a dedicated bounded dispatcher, through the normal validating path, as overlay
   commits (not adopting): the same work `newPayload` does. One task per head.
3. **Never inline.** Side payloads on Platåberget take 6 to 60 s against an 8 s consensus-client timeout
   (`EngineApiService.scala:411-413`). The caller answers per the table (ACCEPTED or SYNCING) and the next
   `forkchoiceUpdated` finds the state and promotes.

**Escalation, never stranding (S0 items 3b, 3h, 13; round 2: beacon ESC (a)-(d), forge R2.3).** `ReorgTooDeep`, a
`StateUnavailable` that local recovery cannot fix and a `BranchTooLarge` that persists have one exit, and it is not "keep the
head" (on PoW that leaves the node on a minority fork, and on PoS it answers SYNCING forever). Each logs at ERROR
(`[STATE-REORG-TOO-DEEP]`), increments `app.db.state.recovery.escalations`, and takes the existing stuck-sync escape valve,
`clearSnapSyncDone` (`AppStateStorage.scala:124-125`), which re-enters SNAP from a recent pivot (PoS and PoW state can both be
fetched). The typed errors are recognised by `Consensus.isLocalStateFault` (`BlockImporter.scala:875,944,1062`), so the serving
peer is not blacklisted. Four parts, all required:

1. **The trigger.**
   - *PoW:* the importer raises `RegularSyncStuck` (`BlockImporter.scala:1330`), handled at `SyncController.scala:904-920`.
   - *PoS: a direct message, because the first design's trigger does not exist (beacon ESC (a)).* `notifyBeaconHead` and
     `handleBeaconHead` only record the beacon head and forward a `CLPivotHint` to a SNAP that is **already running**
     (`SyncController.scala:1256-1274`), and a refusal on the FCU side produces no importer strikes (a sibling at or below best
     is a `DuplicateBlock`, `ConsensusAdapter.scala:203-206`). So the refusal path in `forkchoiceUpdated` and `newPayload` sends
     `StateEscalationRequested(reason)` straight to `SyncController`, which handles it in the same place as `RegularSyncStuck`.
   - *Hysteresis (PoS).* The message is sent only when the same refusal kind has persisted across
     `state.recovery.escalation-refusals` (64, about 2 epochs) **consecutive** `forkchoiceUpdated` calls with no successful move
     in between; any success resets the count. A consensus client that flip-flops between heads therefore costs nothing.
2. **Guards (forge R2.3).**
   - *Cool-down.* At most one escalation per `state.recovery.escalation-cooldown` (6 h). The time of the last one is stored in
     AppState (`state.escalation.last`, written in the batch of part 3) so a restart does not reset it. A refusal inside the
     window logs ERROR, answers as before (SYNCING, or the importer's typed fault) and counts
     `app.db.state.recovery.escalations.suppressed`. On ETC mainnet an escalation is a multi-hour SNAP with mining and state RPC
     stopped; the runbook says so.
   - *Loop guard.* `state.escalation.last` also records the head hash that caused it. After the re-SNAP, if that branch (or a
     descendant) is offered again, it now forks below the pivot (`ReorgTooDeep(Pivot)`); when the pivot was not taken from that
     branch, the node does **not** escalate again: it logs ERROR, drops the branch (PoW: ignore it; PoS: answer SYNCING, never
     INVALID) and counts the drop. Without this, a heavier branch that `PivotSelector` does not choose would loop re-SNAPs.
3. **Order and atomicity (beacon ESC (b), (c)).** Under the state lock (7.0), in this order:
   1. Clear the overlay: forward layers, pinned layers, the move log, the `BlockImportGate` memo and every pending
      `StateRecovery` task.
   2. **One synced batch** (`updateSync`, all keys are in AppState `s`) that does `clearSnapSyncDone()` (which also removes
      `SnapHealInvariant` and `SnapAuditCleanAnchor`), **deletes `state.anchor`**, writes `state.escalation.last` and writes
      `state.reset-pending`. The anchor is never deleted before `SnapSyncDone` is cleared, because a recovered "`SnapSyncDone`
      and no anchor" is refused at start as corruption (5.1) and the opposite order would brick the datadir on a crash.
   3. `deleteRange` CF `v`, then `deleteRange` CF `t` and CF `u`, then delete `state.reset-pending`. Clearing `t` and `u` is
      required: with the old rows left, `HealInvariant.atSnapStart` finds a non-empty, unmarked datadir
      (`HealInvariant.scala:87-94`) and owes a repair pass, the full walk spec 022 removed (about 17 h on Sepolia), and the old
      rows cannot claim "present means complete" anyway (tombstone-skipped dead tries and lazy hand-off holes break it). Cleared,
      `isEmpty` holds and the re-SNAP is a clean SNAP.
   - *Crash safety of step 3 (vault's addition, closing the window between 2 and 3).* Every start that finds
     `state.reset-pending` repeats the three `deleteRange` calls and then removes the key, before SNAP starts. They are
     idempotent, so a crash anywhere in step 3 repeats it; and a plain mid-SNAP restart, which has no such key, keeps its
     healed rows.
4. **While the re-SNAP runs** there is no anchor, so every in-place writer refuses (7.0), `newPayload` answers ACCEPTED and
   `forkchoiceUpdated` SYNCING (6.3.2), and block generation is off. Healing after an escalation can meet a node with the
   right hash over a changed child where a recreated trie overwrote part of a tombstone-skipped dead trie (forge R2.3 note 3);
   that is the class spec 022 accepted for Path re-pegs (an absence, never a wrong root; the audit repairs it), and one test
   pins it. Tests: induce each trigger (EG-14); a flip-flop of 63 refusals does not escalate; a second trigger inside the
   cool-down does not; the loop guard drops a re-offered branch; a kill at every step of part 3 recovers to a clean SNAP.

**Invariants between the disk layer, the changesets and the best pointer.**
- I1 (5.1): `anchor == best` at rest; startup reconciles any other state; rule 4 is a startup rule only.
- I2: a row in `t`/`u` reachable from the anchor root is the state of the anchor; rows not reachable are inert.
- I3: for every block in the anchor's ancestor window there is a record in CF `v` (written in the batch that applied it),
  complete or a tombstone, except blocks at or below the SNAP pivot, genesis or checkpoint block, which have none; the window
  is therefore bounded below by that block.
- I4: after any batch, the row at `(None, [])` hashes to `anchor.stateRoot`, or is absent with an empty root (7.3).
- I5: a record's `preStateRoot` equals its parent's `postStateRoot` where the parent has a record.
- I6 (5.1): the canonical index from `best` down to the retained floor equals `best`'s header ancestry.

## E. Readers

New seam: `StateStorage.viewFor(block: BlockRef)` and `viewForRoot(stateRoot)` return a `StateView`-backed `MptStorage`
(or `StateUnavailable`). `getBackingStorage(bn)` and `getReadOnlyStorage` are replaced: until the retirement slice they
return the anchor view; afterwards they are deleted. Every reader below keeps its algorithm and serves both chains; what changes
is which storage it is handed and that it passes the owner and the position.

| Reader | Today | Under Y |
| --- | --- | --- |
| Block execution | `buildInitialWorld` hands `staged.storage` and `parentHeader.stateRoot` to `InMemoryWorldStateProxy` (`BlockExecution.scala:358-388`); staging is by block *number* (`StateStorage.scala:122`) | `stageBlock(parent: BlockRef, bn)` returns a `PathStagedState` over `view(parent)`; owner from `getStorageForAddress` (`InMemoryWorldStateProxy.scala:305-319`); the account trie from `:129-140` |
| BAL prefetch | `PrefetchNodeReader` reads the parent's committed nodes straight from the database, by hash (`BlockExecution.scala:340-354`; `StateStorage.scala:345-353`) | `PathPrefetchReader` over `view(parent)`: read-only, walks the trie by key so it has positions; treats a stale view as a miss and stops |
| RPC reads at a block (`eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, GraphQL) | account by canonical *number*: `getAccountMpt` -> `getBackingStorage(blockNumber)` (`BlockchainReader.scala:190-194,312-319`); by root: `getAccountAtStateRoot` (`:206-210`) | `viewFor(blockHash)` resolved through the canonical index, or `viewForRoot`; `StateUnavailable` becomes the existing "state unavailable" RPC error |
| storage reads and proofs | `Blockchain.getAccountStorageAt` and `getStorageProofAt` take a **storage root only** and use `getBackingStorage(0)` (`Blockchain.scala:87-117`); callers `EthUserService.scala:72,97`, `EthProofService.scala:181-196`, `GraphQLSchema.scala:437`, `TestService.scala:437` | signatures gain the account and the view: `(view, address, position)`; the owner is `kec256(address)`. New overloads; the old ones stay for the Hash scheme. `eth_getProof` for an account **with no storage** needs no owner. This is the one RPC-visible API change (`conduit` reviews) |
| `StxLedger`, `eth_call`, `eth_estimateGas`, `eth_createAccessList`, simulate, trace | `blockchain.getReadOnlyMptStorage()` plus `blockHeader.stateRoot` (`StxLedger.scala:125-143,161-179,213-249,256-265`); `ReplayMptStorage` (`:703`); `EthSimulateService.RetainingMptStorage` (`:144`) | `readOnlyViewFor(header)`; the world's writes go to a throwaway layer above the view (what `ReadOnlyNodeStorage` does today); both wrappers derive from `MptStorageDecorator` |
| SNAP serving by root | one `getReadOnlyStorage` handed to `NetworkPeerManagerActor` (`NodeBuilder.scala:484`); `isStateRootFresh` keeps a 128-block root set (`NetworkPeerManagerActor.scala:1112-1130`); `SnapServer` resolves by hash (`:68-77`) and says "content-addressed ... the same MptStorage serves both" (`:415-416`) | per request: `viewForRoot(root)`; the walkers already carry `prefix` (`SnapServer.scala:140-159`) and the storage ranges receive the account hash, so they call `getAt(owner, prefix, hash)`. The window is `min(128, history)` (B). A root with no view gets the existing empty answer |
| Background audit | `StorageReader.nodes(hashes)` (`BackgroundAudit.scala:207-214`); the walk is by `AuditPosition(account, nibbles)` (`AuditWalk.scala:30-64`) but the reader is by hash (`:98-103`) | `AuditReader.nodes(requests: (AuditPosition, hash))` -> `rawNodesAt`; reads the anchor view; a position whose bytes are a different valid node is **moved**, not bad (the walk already re-resolves moved positions from the current root, `:105-122`) |
| Repair and on-demand fetch (#1694) | `MissingNodeException.location` is the HP path, set on reads only (`MerklePatriciaTrie.scala:261,274`); the importer fetches by `location` (`BlockImporter.scala:660-733`) and writes with `saveNode(hash, bytes, bn)` (`:485,552`); audit repair and delete are by hash (`BackgroundAudit.scala:694,748`) | `getAt` attaches `(owner, path)` to every missing node, so the **write** paths report a position too (today `put`, `remove` and `fix` do not). Writes become `repairNodeAt` (7.5): conditional, never overwrites a valid different node. `StorageHolePosition.paths(...)` at `BlockImporter.scala:698-707` finds a position by hash walk and is replaced by the exception's position |
| Healing after hand-off | the heal coordinator ends at hand-off; the audit repairs (spec 022 D8) | unchanged in shape; the audit reads `t`/`u` by position instead of CF `n` by hash |

| Reader (continued) | Today | Under Y |
| --- | --- | --- |
| Checkpoint export | walks the hash store from the state root with a `visited` hash set (memory proportional to the node count) and a queue of storage-root **hashes** (`CheckpointExporter.scala:140-259`, `:151,157,159`); the walk is a stack DFS, parent first (`walkTrie`, `:274-306`) | walks `view(header)` by position and emits **format version 2** (E.1): every occurrence, no `visited` set, so memory is O(depth) and the cross-trie dedup that made version 1 unimportable is gone |
| Checkpoint import | `getBackingStorage(blockNum).storeRawNodes(...)`, hash-keyed, parent-first, with the heal marker withdrawn first (`CheckpointImporter.scala:74-170`, `:79-84,90,107`) | **required, funded (C5c).** It is ETC's resync path as much as ETH's. A position-assigning importer replays the version 2 stream (E.1); version 1 archives are refused |
| Block generation and mining | **Every proposer path:** `PoWBlockGenerator`, reached from `PoWBlockCreator` (EthashMiner, MockedMiner) and from `EthMiningService.scala:120` (`eth_getWork`, one generation per call); `RestrictedPoWBlockGeneratorImpl`; `NoOmmersBlockGenerator` (`TestmodeMining.scala:113-114`); `TestService.scala:352` (`test_mineBlocks`); the Engine API builder through `executeForProposer` (`EngineApiService.scala:1337-1346` -> `BlockExecution.scala:241-244`, which runs `executeBlock(block, isProposer = true)` with no staging over the **writable backing** of `getBackingMptStorage`, `:358-388`; the comment at `:366-373` explains why a read-only wrapper was dropped). The PoW paths all build over `getReadOnlyMptStorage()` in `BlockPreparator.prepareBlock` (`:1043-1076`, `:1054`) unless a world is passed in; today that only writes into `ReadOnlyNodeStorage`'s buffer (`ReadOnlyNodeStorage.scala:66-78`) | on an in-place store a proposer write would corrupt the head, so every path above gets a **proposer view**: `view(parent)` plus a throwaway `StagedLayer` (6.1) that absorbs `persist()` and is discarded with the world. It never commits, never writes a row, never registers a forward layer (a built block is registered only when it arrives through import), is **content-addressed** (6.2) and **fresh per lenient attempt** (`EngineApiService.scala:1337`). **MockedMiner chains a proposer world across head moves** (`initialWorldStateBeforeExecution = Some(state)`, `MockedMiner.scala:179,203`): its view would go stale after `max-view-age-moves` and its layers would accumulate, so it **rebuilds from `view(minedBlock)` after each import, and waits for that import to finish first** (forge S0 item 12): `MockedMiner` schedules the next block one second after the last (`MockedMiner.scala:202`), the import of the mined block may not be done, and `view(minedBlock)` would then be `StateUnavailable`; it must wait (or retry) and not fail, because `MiningFailed` drops the run to `waiting()` (`:205-207`). A test mines two blocks one second apart with a deliberately slow import and asserts the second is built. **`PoWBlockGenerator.cache`** keeps `PendingBlockAndState` worlds (`:101-103`): a cached world is never committed (a mined block is re-executed through `MinedBlockImport`, `BlockImporter.scala:278,409-410`) and never read once stale. A stale view during a build **retries the build**; it does not fall to the empty-payload fallback (`EngineApiService.scala:1352-1354`). If `testing_commitBlockV1` commits a built block it goes through the import path. `BlockGeneratorSkeleton.buildMpt` (`:201-207`) keeps its ephemeral hash-keyed store for the transaction and receipt tries |
| Recovery scans | `getBackingStorage(pivotBlockNumber)` and a walk by hash from the root (`CombinedRecoveryScanActor.scala:76`, `StorageRecoveryActor.scala:222,449,471`, `BytecodeRecoveryActor.scala:314`) | walk the anchor view by position; storage tries by `(accountHash, storageRoot)`. Each walker's `descend` already knows its prefix; C5b audits all four |
| Presence checks by root | `SyncController.scala:1389,1985`; `ChainImporter.scala:362` via `getMptNodeByHash` (`BlockchainReader.scala:77`) | keep working: `get(rootHash)` answers the account root (section 9) |
| Hash-only node serving | `BlockchainHostActor.scala:137` (`GetNodeData` by arbitrary hash) | answers the account root and overlay nodes only; any other hash returns empty. eth/67 removed `GetNodeData` and fukuii negotiates eth/68 and eth/69, so this costs neither chain's peers anything |
| Readers the first table omitted | `EthUserService.scala:51` (`eth_getCode`, via `getBackingMptStorage(number)`); `GraphQLSchema.scala:189` (`worldStateAt`); `TestService.scala:250`; `TestModeBlockExecution.scala:45` | each takes `viewFor(block)`; test mode reads a view too (5.4, 7.6) |
| The rest of them | every call of `getBackingMptStorage`, `getReadOnlyMptStorage`, `getBackingStorage`, `getReadOnlyStorage` | an **allow-list grep ratchet** replaces the hand enumeration (C2b): a new caller fails CI unless it is listed with its reason |
| RPC reads under a stale view | | retried transparently with a fresh view of the same block while it is in reach (6.3.1); `eth_getProof` golden bytes are unchanged |
| Pending transactions | `PendingTransactionsManager.scala:498` reads accounts by root from `getReadOnlyStorage` | `getAccountAtStateRoot` over the anchor view |
| Genesis | `GenesisDataLoader.scala:101` through `getReadOnlyStorage` into CF `n` | canonical commit with `anchor = genesis` (5.4) |

**What each `MptStorage` implementor needs** (the seven are the ones research 17 counted):

| Implementor | Needs |
| --- | --- |
| `SerializingMptStorage` (`MptStorage.scala:53-111`) | stays the hash-keyed store for ephemeral list tries and tests (`getAt` default). A new `PathMptStorage` (verifying, position-addressed, owner-scoped) is the store for chain state |
| `PrefetchNodeReader` (`PrefetchNodeReader.scala:26-62`) | a position-store twin over a view; the hash-keyed one is deleted with the Hash scheme |
| `CachingMptStorage` (`DecodedNodeCache.scala:84-108`) | forwards `getAt` and `forOwner`; the cache is keyed by hash, so a hit short-circuits both |
| `GenesisDataLoader.AppendOnlyMptStorage` (`:288`) | derives from `MptStorageDecorator` |
| anonymous `contextStorage` (`InMemoryWorldStateProxy.scala:309-318`) | replaced by `stateStorage.forOwner(Some(accountHash))` plus the existing missing-node translation |
| `StxLedger.ReplayMptStorage` (`:703`) | derives from `MptStorageDecorator` |
| `EthSimulateService.RetainingMptStorage` (`:144`) | derives from `MptStorageDecorator` |

**Owner threading in one line:** `forOwner(None)` for the account trie, `forOwner(Some(kec256(address)))` for a storage trie,
created at `InMemoryWorldStateProxy` (the two construction sites above), at the RPC and proof readers that today build a
storage trie from a root alone, at the SNAP storage-range and trie-node serve paths (which already receive the account hash),
at the audit's `AuditPosition.account`, and at the recovery scans.


### E.1 Checkpoint archive version 2 and the position-assigning importer (C5c, required for both chains)

**Why version 1 cannot be imported onto a position store.** The exporter walks a stack DFS, parent first, with one global
`visited` set of node hashes (`CheckpointExporter.scala:157`, `walkTrie` at `:274-306`). A node reached a second time, from another trie or
another position, is skipped. That is correct for a hash-keyed store and wrong for a position store, which needs a row at
**every** occurrence. Sharing is common across tries, not rare: a leaf holding slot 0 with the same value at the same depth
has the same remaining key and value, so the same hash, in many contracts' storage tries. The v1 stream has neither positions
nor multiplicity, so an importer would need a hash-to-position index over every node, or a scratch hash-keyed copy, which is
the copy this track removes.

**Format version 2** (`CheckpointArchive.Version`, `CheckpointArchive.scala:43`; the reader rejects an unknown version at
`:174`). A one-way door, signed by forge and beacon in S0 (item 8), so every field below is decided now:
1. The version byte is 2. The checksum is **CRC32C**, as for BlockDiff (v1 uses CRC32, `CheckpointArchive.scala:8`; one
   polynomial across the project's formats, hardware-accelerated).
2. The header binds **`chainId` and `genesisHash(32)`** (v1 binds only `chainId`, `:26,54-58`), so an archive cannot be imported
   into a chain with the same id and a different genesis.
3. Node entries are the **pre-order DFS of the whole state with every occurrence emitted**: an account trie in child order
   (branch children 0 to 15 ascending; an extension has one child), and the storage trie of an account **directly after the
   account's leaf**, before the leaf's later siblings. Only hashed children (RLP at least 32 bytes) and the roots are entries;
   inline children are inside their parent. Position is implicit.
4. Bytecode entries stay after the nodes, deduplicated by hash (code has no position).
5. **The ancestor window**, as new entry tags: `0x03` a header and `0x04` a body, for the **256 headers below the checkpoint
   block** (so BLOCKHASH answers in the first 256 blocks after import: `AncestorBlockHashes.scala:29-34,56-62` falls back to an
   index an import never writes) and the **bodies of the 6 blocks below it** (so ommer validation, which reads up to 6
   ancestors, `StdOmmersValidator.scala:19,119-127` through `BlockValidation.scala:35-56`, does not reject a block whose ommer's
   parent is below the checkpoint; on ETH the 6 bodies are inert, because ommers do not exist there). About 150 KB. They are
   written after the bytecodes and before the CRC. **The window is bound by hash (forge S0 item 8a).** The expected-hash check of
   item 6 binds only the checkpoint block, so an unchained window could carry wrong ancestors (a wrong BLOCKHASH, a state-root
   mismatch on canonical blocks, possibly an invalid-chain report). The importer therefore verifies that the `0x03` headers chain
   by `parentHash` from the checkpoint header (header N-1 hashes to N's `parentHash`, and so on down) and that each `0x04` body
   matches its header (`transactionsRoot` and `ommersHash`); a break, a gap or a mismatch is `BadFormat`. When the checkpoint number
   N is below 256 the window holds `min(256, N)` headers and `min(6, N)` bodies, down to genesis.
6. **Total difficulty is trusted from the archive** (`CheckpointImporter.scala:162-170` imports `chainWeight` unverified, and
   PoW fork choice compares it). So an import **requires an operator-supplied expected block hash** (or a pinned list for the
   public chains) and refuses an archive whose header hash differs; the documentation says plainly that the total difficulty
   inside is not re-derived.

**The importer is a stack machine, with no hash lookup and no scratch store.** The stack holds frames
`(owner, path, expectedHash)`, starting with `(None, [], header.stateRoot)`. For each node entry it pops a frame, checks
`frame.expectedHash == hash` and `keccak(rlp) == hash` (a failure is `BadFormat`), decodes the node, and writes the row
`(owner, path)` (stored rule, section 2). For a branch it pushes its hashed children in reverse order, so child 0 is next;
for an extension, its one child at `path ++ sharedKey`. For an account leaf it computes `owner = keccak(address)` from the
leaf's full key and, if the storage root is not empty, pushes `(Some(owner), [], storageRoot)` on top. Memory is O(depth x 16)
for the account trie plus O(depth x 16) for the trie being walked; there is no `visited` set on either side. Four assertions,
each failing `BadFormat`, are what make the machine sound:
- an account leaf is always a stored node (an account's RLP is at least 68 bytes), so deriving the owner from the leaf's full
  key is safe;
- an inline node holds no hash reference (a hash child makes a node at least 33 bytes), so no hashed grandchild hides inside an
  inline child;
- a storage root below 32 bytes is still a root entry (roots are always stored);
- `EmptyStorageRootHash` pushes no frame.

**Code completeness (S0 item 8b).** After the nodes, the importer checks that every non-empty `codeHash` it met has a bytecode
entry, and counts the gaps; it fails the import above a threshold and logs the rest for the on-demand fetch, rather than
finding them as `MissingCodeException` at execution.

**Atomicity and failure.** Unchanged in shape from today (`CheckpointImporter.scala:74-170`): `beginCheckpointImport` is
committed synced before the first node (`:84`), rows go in batches of at most 10,000, the CRC is checked at the end, and one
synced batch then carries the header, the ancestor window, the chain weight, the best pointer, `state.anchor = (hash, number,
stateRoot)`, the done flags and `endCheckpointImport`. It is a state move (7.0). **A genesis anchor is cleared at import start (forge S0 item 8b).** The genesis commit (5.4) leaves `anchor = genesis` on a fresh
datadir, so `beginCheckpointImport` does what SNAP start does there (5.4): when `anchor == genesis`, clear `t` and `u` (two
`deleteRange` calls), delete the anchor, then write the begin marker, idempotently and before the first node. Without the clear, a
crash mid-import would leave `anchor == best == genesis` over the checkpoint's root row; I1 rule 1 would pass and only T042's
fall-through to SNAP would repair it. With it, an import that fails or crashes leaves partial
rows and no anchor; as now it falls through to SNAP on the same datadir with the heal marker withdrawn and a repair pass owed
(spec 022 T042). No BlockDiff is written for the checkpoint block (no prior), as for a SNAP pivot.

**Golden stream (S0 item 8a).** Before C5c merges, a tiny version 2 stream is pinned byte for byte: two accounts sharing one
storage root, one inline storage leaf, one code entry, one header and one body of the ancestor window; and a second pinned stream whose window does not chain (a header with a wrong
`parentHash`) that must be refused `BadFormat`.

**Size of the change.** Exporter walk about 220 lines (it replaces the `visited` DFS), writer and reader about 120 (header
fields, tags 0x03 and 0x04, CRC32C), importer about 360 (the machine, the assertions, code completeness, the trust check):
about 800 main and 600 test lines. Archives are larger than version 1 by the dedup factor, which M9 measures on Mordor.

**Bootstrap and operators.** No version 2 archive exists until a node on this layout has synced and exported one, and version
1 archives are refused with a message naming the format. Nothing external depends on the old archives (user standing rule), so
they are discarded. The first archive for each chain comes from the track's own gate runs: Sepolia and Mordor in EG-9, and ETC
mainnet from the EG-12 node, which has no other source because fukuii has never synced ETC mainnet.

## F. Pruning

**Retention** is 4.5: records below the retention floor (`pruning.history`, widened by the consensus client's finality lag on ETH) are deleted inside the next canonical batch. Today's prune runs
after each save in a separate write (`Blockchain.saveBlockState`, `Blockchain.scala:133-134` -> `onBlockSave` ->
`pruneLocked`, `StateStorage.scala:263-287`); `onBlockSave` becomes a no-op because the batch already pruned.
**Idempotence:** point deletes in an atomic batch, with a catch-up loop of at most 16 heights; no `deleteRange` (which is not
part of the atomic batch, `DataSource.scala:95-102`) is used. A reorg leaves the reversed blocks' records until their height
ages out, because a re-adoption needs them. **Reorg beyond retention** is refused (C). **`archive`** keeps every record, moves are unbounded and chunked (4.6),
and a view deeper than `state.archive.view-reach` is refused with a documented error (NG-6, OQ-3). **Disk model** is section 12.

## G. The hand-off (item 10, both chains)

**ETC SNAP on the path-keyed families is already proven in the dev loop.** The shipped build forces the schemes by chain:
`require(config.storageScheme == expectedScheme ...)` (ETH to Path, ETC to Hash, `SyncController.scala:476-480`). Spec 022's
T057 runs (S14, R8, U7, P3, P4 on `654c1682f`) were Mordor on the Path scheme "on a local jar with the ETC guard removed,
never committed"; each ended in a clean audit pass over the head (5.54M nodes, 2.72M accounts, 0 holes), and P7 handed off and
followed the head for ten minutes (`specs/022-snap-heal-convergence/tasks.md:391,480`). That is Mordor, not ETC mainnet. Spec 022's
reasons for keeping Hash on ETC (an extra export scan, two to three times the disk) disappear with the export. In this track the
guard and the Hash branches go together: `StorageScheme.Hash` appears 64 times in 18 main files (`AccountRangeCoordinator`,
`ContractWorkCarry`, `AccountTrieAssembly`, `HealInvariant`, `StorageRecoveryActor`, `SyncController`, `SNAPSyncController`,
`SnapTrie`'s hash trie and others), all deleted in the retirement slice, with the 016 ratchet (`scripts/snap-split/verify.py`)
checked on every PR that touches `snap/`.

**The ancestor window is a hand-off precondition, on both chains (S0 items 15 and 8; forge section 5c).** `SnapFinalization`
stores only the pivot's body (`SnapFinalization.scala:422`). Two things then read below the pivot and are wrong when it is
absent:
- **BLOCKHASH** in the first 256 blocks after the pivot walks parent hashes through stored headers and, when they are missing,
  falls back to the canonical index, which SNAP never fills (`AncestorBlockHashes.scala:29-34,56-62`): a wrong result, then a
  state-root mismatch. The existing deferred-backfill hold covers it only where it is switched on (`SnapFinalization.scala:214-226`,
  ETH's setting; `deferChainBackfillUntilStateComplete` is off by default).
- **Ommer validation** reads up to 6 ancestor blocks, header and body, and they come back short when missing
  (`BlockValidation.scala:35-56`): a canonical block in the first six after the pivot whose ommer's parent is below the pivot
  then fails `ommersParentsAreAllAncestors` (`StdOmmersValidator.scala:119-127`) and is rejected as invalid. PoW only.
Mordor's low ommer rate and T063's `chain-download-enabled=false` hid both. So before hand-off (C5a) `AncestorWindow.ensure(pivot)`
holds until the **256 headers below the pivot** (their parent-hash chain verified) and the **6 bodies below it** are stored,
fetched through the existing `PeersClient` or satisfied by the chain downloader's coverage. The same window is carried by a
checkpoint archive (E.1, entries 0x03 and 0x04). BLOCKHASH needs the headers on ETH as well, so the code is shared; the
6-body part is gated on PoW.

**ETC mainnet readiness, and the route for EG-12 (forge section 5).** The first successful ETC mainnet sync is a gate of
this track (user decision, 2026-11-06). The route is **SNAP on the unified layout, with public ETC peers**: a checkpoint is
impossible because no archive exists (the gate produces the first), and a sync from genesis takes one to three months
(`specs/022-snap-heal-convergence/research.md:351`). ETC's state is about 90M nodes (`specs/003-*/spec.md:16`, `005-*/spec.md:16`),
roughly a ninth of Sepolia's 787M (`022/spec.md:23`), and with no copy the T063 blocker does not apply. Why every earlier ETC
sync failed (all on the Hash scheme, which the guard at `SyncController.scala:476-480` forced; no attempt reached hand-off, so
the ETC-only post-hand-off hazards were never met):
1. Mantis SNAP had never met a live ETC snap peer; eight bugs were found, including range progress lost at every serve-window
   expiry (`docs/reports/SNAP-Resolution.md:15,44,155-159`), since fixed.
2. Few ETC peers serve snap: the ETC Coop bootnodes first ran without `--snapshot`, and workers were capped to 1 to 4 peers
   (`SNAP-Resolution.md:95,211-224`).
3. Post-SNAP healing at ETC scale: an in-memory frontier ran out of memory, the walk ran at about 1,000 to 1,170 nodes/s, heal
   was pinned to one root against a serve window of about 128 blocks, and a 16 to 20 h verification walk became 30 to 40 h (specs
   001, 002, 004, 005, 006, 007).
4. The root cause was non-convergence on a moving pivot ("present does not mean complete", V1 to V3); ETC is named as affected
   (`022/spec.md:21-35`). Spec 022 fixes it, proven on Mordor with one local peer (`022/tasks.md:476-480`).

What EG-12 therefore needs: spec 022 fully landed (slice 5, no gate walk) in 0.11.0; the C5a hand-off with no publish and the
ancestor window; the PoW arms of C4 (the 24 h follow meets routine forks); C6, which removes the guard and routes ETC through
the branches Sepolia exercises; and a **peer precondition**: the node logs and records the number of snap-capable ETC peers at
start, and if there are fewer than 2 the user decides whether a declared local core-geth peer is allowed in the gate run
(Mordor's evidence used one). Host figures (disk, heap, CPU) are recorded against spec 007's CPU finding. The pass conditions
are in plan EG-12.

**What replaces `startPathPublish`** (`SnapFinalization.scala:441-469`, `onPathPublishDone` `:475-508`, the `pathPublish` var
`SNAPSyncController.scala:304`, the `PathPublish*` messages `:891-906,1317-1320`, and the dispatch-table and idle-totality
specs that list them): nothing is copied. `finalizeSnapSync`'s Path branch (`:188-212`) keeps the #1555 root-presence check
(`FinalizationPolicy.pathPivotRootAbsent`, `:197-202`), runs the hand-off sample (below) on the blocking dispatcher, and falls
through to the existing anchoring code, with the anchor and layout marker added to the synced flag commit (7.4).

**Where the hand-off sample (spec 022 decision 11 / D9) moves.** Today it piggybacks on the publish's full scan:
`PathToHashExporter.publish` samples while it streams every node (`:164-179`), then re-reads the sampled leaves through the
hash store (`:197-220`). Without the scan there is nothing to piggyback on. It becomes `PathHandoffSample` (in `db/storage/`,
called from `SnapFinalization`): the same strata (`AccountSamplePerNibble` 256, `StorageRootSamplePerNibble` 16,
`EmbeddedSamplePerNibble` 16, `LargeStorageTries` 16, `LargeStorageSamplePerTrie` 64, `:92-96`) and the same
`tooManyMisses` rule (`:84`), but **sampled by random seeks** in `t` and `u` (a seek lands on a row; take the next leaf rows)
and **checked by root-to-leaf lookups through the path-aware trie**, which is what execution will do. Cost falls from hours to
seconds. Keys are not prefix-ordered (2), so strata are by first byte of the key, and the sample is documented as a
reachability sample, not a root recompute, exactly as decision 11 says. `PathToHashExportCheckSpec` ports its cases.

**How the audit and finalization become position-aware.**
- The audit already walks by position (`AuditFrame(position, hash)`, `AuditWalk.scala:30-64`) and re-resolves a moved
  position from the current root. Three seams change. `AuditReader.nodes` takes `(position, hash)` and reads `t` or `u`
  (`rawNodesAt`: a `multiGetOptimizedNoFill` over the CF keys, `DataSource.scala:66-67`, then a keccak check that classifies
  Absent, BadKeccak, Undecodable or **moved**). `repairNode` and `deleteBadNode` (`BackgroundAudit.scala:694,748`) become
  `repairNodeAt` and `deleteBadNodeAt` with today's contract: judged at write time, `stillReached` asked from the anchor root
  at the position, bytes read again after the look, a node that passes keccak never deleted. A **stale** read (6.3) is never
  a delete reason.
- The audit's root is the anchor's. A block commit that moves the head under the walk makes some positions "moved", which the
  walk counts and re-resolves, as it does for a re-peg today.
- Finalization has had no verification walk since spec 022 slice 5; what stays position-aware is the #1555 presence check and
  the hand-off sample above.

**How the DC-1 / 016 FR-044 barrier is met.** Today the exporter's output must be durable before `snapSyncDone`
(`StateIngest.scala:71-92`). With no export, the state SNAP wrote *is* the access store, and the barrier is the synced
completion commit: the WAL is on for every write, and `commitSync` of the three flags syncs every earlier WAL record, so the
`t`/`u` writes are durable before a recovered `snapSyncDone` (research 17: "already met"). The track adds the anchor
to that same synced commit, so a recovered `snapSyncDone` implies it (the layout marker was stamped at creation). `StateIngestDurabilitySpec` is updated to pin
the order: ingest writes, then one `commitSync` carrying flags and anchor. `pruning.mode = inmemory`, the one mode where the
barrier needed `forcePersist` (`StateIngest.scala:84-92`), is retired (4.6), so `forcePersist` is deleted and the barrier holds
under `basic` and `archive` with no cache to flush. A durability test pins it: kill -9 immediately after the synced commit,
restart, and the `t`/`u` rows, the anchor and the best pointer are all there.

**The layout marker and its detection rule** are 5.2 and 5.3: one marker, one rule. A datadir without the marker is refused
unless it is empty, on either chain; there is no adoption of a mid-SNAP, mid-copy or finished-copy datadir, and no probe of CF `n`.

## H. Golden bytes and formats

Sections 2 to 5 are the answer: the CF `v` key layouts (33-byte record key, 9-byte height index); the record value (version,
kind, flags, header, sorted entries, CRC32C) with two pinned vectors; the unchanged `t`/`u` keys (`HP(path)`,
`owner ++ HP(path)`); the `state.anchor` string `v1:<hash>:<number>:<root>`; the `state.layout` string `path-live-1`. HP keys
are not prefix-ordered (`PathNodeStorage.scala:154`), so nothing relies on key adjacency: pruning is point deletes through the
height index, subtree deletion is a list taken from the diff, and the sample strata are by first key byte. The existing S0c
vectors for CF `t`/`u` keys are reused unchanged.

## I. Spec 022 invariants, when the path store becomes live state

| Invariant or mechanism | Effect |
| --- | --- |
| "Present means complete" (V1), `HealInvariant`, the marker (D7) | Holds at hand-off structurally on Path (healing deletes dangling positions, D10; `PathNodeStorage.scala:124-164`). After hand-off the commit batch keeps it: a block writes every node it changes together with the vanished positions, atomically, so a parent is never written without the children the block created. Holes left by a lazy hand-off stay holes (unchanged); the audit repairs them. The heal marker and the layout marker are separate keys with separate meanings |
| V3, aliased storage tries | Gone by construction: each owner has its own positions. The Hash-scheme alias handling and the seed mechanism for excluded accounts (D5, Hash) are deleted with the scheme |
| Excluded accounts, Path (D5: delete the account's trie positions at heal start) | Pre-hand-off only, unchanged. An excluded account left at a lazy hand-off is a hole: the first block that touches it meets a missing node, the importer fetches it **by position** (E), and `repairNodeAt` writes it |
| Deepest-first heal, held-memory cap, atomic chain commit (D1, D3) | Pre-hand-off only, unchanged. They write `t`/`u` through `PathNodeStorage.writeNodesWithDeletes`, already position-keyed and atomic |
| On-demand repair (decisions 1 and 15) | Becomes position-conditional (7.5). New rule: a node that is a different *valid* version at that position is not a hole and not corruption; it is another view's node (6.3) |
| The full-trie walk as a background audit of the hash store (D8) | Walks the path store by position, from the anchor's root |
| `HealInvariant.isEmpty` (D7) | Must treat `anchor == genesis` as empty (5.4), or genesis rows in `t`/`u` force the walk on every fresh datadir |
| Stale rows in `t`/`u` from earlier pivots | Inert (reads verify) and not part of any BlockDiff (3.3). They make `PathNodeStorage.isEmpty` false, which 5.4 handles for genesis |
| `PathToHashExporter`'s "stale nodes are harmless" | Still true for reads; the hand-off sample counts them as stale, as now |

## 13. Measurements required before the numbers above are frozen (DC-14: no claim without one)

| ID | Measure | On | Decides |
| --- | --- | --- | --- |
| M1 | changed positions, diff bytes (prior plus next) and vanished positions per block; p50, p99, max | Sepolia, Mordor, Platåberget | overlay budget, `max-layer-bytes`, section 12 |
| M2 | unpromoted payloads at once, and promotion lag (`newPayload` to `forkchoiceUpdated`), over 24 h of head following | Sepolia | forward-layer count and bytes |
| M3 | JVM bytes per diff entry (JOL) | any | converts M1 bytes to heap |
| M4 | keccak-verify cost per block, with and without the decoded-node cache | Sepolia replay | whether verify-on-read needs a cheaper form |
| M5 | stale-view rate and retries under head follow, **under a regular-sync catch-up burst** (8 moves pass in under a second), and with 8 concurrent `debug_traceBlockByNumber` and rpc-compat | Platåberget, Sepolia | `max-view-age-moves`; the lock fallback (6.3.3) |
| M6 | `moveHead` latency for 1, 2, 8, 32 blocks; `forkchoiceUpdated` p99 | Platåberget with forced reorgs | the promotion latency budget |
| M7 | `[IMPORT-TIMING]` and allocation rate (JFR) per block, ETC (Mordor) and ETH, against the pinned pre-track build, after C1 and at the gate | Mordor, Sepolia | that the shared trie change costs no more than the budget (spec 007) |
| M8 | in-place commit versus today's commit, per block, and total bytes written | Platåberget, Sepolia | the write-amplification claim |
| M9 | archive v1 versus v2 size and node counts, and how many hashes occur at more than one position (the sharing the dedup hid), per chain; archive-mode diff growth per day | Mordor, Sepolia | the version 2 size claim (E.1) and the `archive` disk figure (4.6) |
| M10 | ETC: fork rate and depth distribution, `moveHead` cost at depth 1 and 2, proposer-view cost per generated block | Mordor with a local miner | the PoW reorg budget and the mining latency |
| M11 | *(withdrawn: the `inmemory` mode is retired, 4.6)* | | |
| M12 | cost of the ancestor window (256 headers, 6 bodies) at hand-off, with and without the chain downloader's coverage | Mordor, Sepolia, ETC mainnet | the hold time of `AncestorWindow.ensure` (G) |
| M13 | PoW branch lengths adopted by `reorganise` (blocks per `settleHead`) and their layer bytes; the longest BlockQueue branch seen | Mordor with a local miner, ETC mainnet follow | `pinned-max-bytes` and whether the reverse-then-commit fallback is needed (6.4) |
| M14 | cost of importing ETC blocks 0 to 1M, 1M to 2M, from genesis, on the unified layout, and the size of a `chain.rlp` export | the dev host | whether the EG-1 ETC import is feasible, and its source (plan open item) |
| M15 | rows per deleted storage owner (p50, p99, max) and tombstones written, on the ETC 0 to 5.1M import and the Mordor imports; and the largest diff over every chain (forge S0 item 5, beacon S0 item 5) | ETC import, Mordor, Sepolia, Platåberget | `max-entries-per-owner-delete` (must sit well above the observed maximum) and `max-record-bytes`; the zero-tombstone gate of EG-4 and EG-15 |

---

## 14. Decisions in force, and where I still see risk

**Decided by the user, 2026-11-06 (treated as fixed):** Option Y, write-through, the changeset record, spec 017 parked with the
decorator rule, checkpoint import funded, T064's intent run on a fresh datadir, two pruning modes (`basic`, `archive`) on both chains with `inmemory` retired, the
overlay budget left to M1 and M2, the first successful ETC mainnet sync as a gate (EG-12), and the standing rule (one track for
both chains, no legacy-datadir machinery, no release-level transition). Two earlier answers are **superseded by the standing
rule**: adopt-in-place for mid-copy datadirs (reversed: a datadir without the marker is refused, whatever its state) and "ETC
untouched until 10b" (ETC moves in this track).

**Risks that remain, none a blocker:**
1. **ETC mainnet has never been synced by fukuii, so there is no pre-state and no oracle for it.** Every Hash-scheme attempt
   failed, none reached hand-off, and the ETC-only post-hand-off hazards (the ancestor window, ommers) were never met (G). EG-12
   is the first ETC mainnet sync, on the unified layout; ETC's state is about 90M nodes (not hundreds of millions). Until it
   passes, ETC mainnet consensus rests on ethereum/tests, Mordor and an offline import from genesis (plan EG-1), and nothing
   re-executes ETC mainnet from 5.1M to the head. **The source of the ETC blocks 0 to 5.1M for that import is an open item
   (plan, open item 1), decided later**; until it is named, the ETC half of EG-1 cannot run (M14 measures what it would cost).
2. **No in-tree oracle after the last slice.** The Hash scheme is deleted in the same track, so the differential oracle is a
   pinned pre-track jar kept outside the tree, with its recorded roots and the ETH hive and EEST baselines (pass lists per
   suite), until EG-12 passes and the first ETC v2 archive has been re-imported on a second datadir, and at least one release
   past C8b.
3. **Breadth.** One track carries both chains, the readers, the overlay, the hand-off, the checkpoint format and the deletion of
   the Hash scheme: about 21,200 lines added (about 11,050 main, 10,140 tests) and about 6,800 removed. PRs stay one slice each, with the in-development guard
   keeping staging green, but `forge` and `beacon` carry a larger review load than a staged plan would give them.
4. **The shared trie change is on the path of both chains' consensus** with no Hash-scheme fallback to compare against in tree
   (risk 2). M7 and EG-1 are the control.
5. **"Receipts stored implies state present" breaks** for a block executed and not promoted (Part 2, D); repaired there with
   local re-execution.
6. **A replaced storage trie** (3.6). Post-Cancun this is small on ETH. ETC has no EIP-6780 on any fork and deletes a destroyed
   account outright on every fork; EIP-161 touch-deletion applies on ETC only from Atlantis, block 8,772,000 (`EvmConfig.scala:185-189`;
   `eip161-block-number` is disabled at `etc-chain.conf:40`), including an "empty" account with non-empty storage. ETC's deletion
   load is SELFDESTRUCT, always, plus touch-clearing after Atlantis. The bound skips the deletion and writes a tombstone rather
   than failing a valid block, so the cost is a leaked dead trie and a refused deep reorg across it, never a stalled chain. A
   full sync from genesis is not a supported resync path. **A tombstone at or near the head is the expensive case** (a routine
   depth-1 reorg then escalates to a re-SNAP): EG-4 and EG-15 assert zero tombstones, M15 sets `max-entries-per-owner-delete`
   well above the observed maximum, and the recommended resident-reverse-diff mitigation (3.6) is the follow-up if M1 or M15
   ever show a record-size tombstone.
7. **Archive mode on a busy chain** grows by one diff per block (M1, M9).
8. **Mining and block generation** must never write a row; the proposer view is the control (E table; S0 item 12), and a mined
   block's root must equal the root its import computes (gate EG-6).
9. **Pinned layers against a long PoW branch** (6.4): the pinned cap is a guess until M13; the fallback is reverse-then-commit with
   rollback from records.
10. **Concurrent writers** (7.0): the lock and compare-and-set are new on every in-place path; the deterministic concurrency test
    is a gate item.
11. **Escalation is expensive and must not loop** (Part 2, D): a re-SNAP stops mining and state RPC for hours on ETC mainnet and
    costs a validator its duties on ETH. So it needs a persistent trigger (64 consecutive refusals on PoS), a cool-down
    (`state.recovery.escalation-cooldown`, 6 h, counted in a metric), and a loop guard (a branch that caused an escalation and
    is offered again after the re-SNAP, now `ReorgTooDeep(Pivot)`, is dropped at ERROR level and does not escalate again). The
    runbook says so. EG-14 induces one escalation and asserts the unattended recovery.
