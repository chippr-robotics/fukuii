# Data Model: Follower Roles — `replica` and `archive`

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Research**: [research.md](research.md) · **Date**:
2026-10-31

Signatures are Scala 3 (3.9 LTS). Package roots follow plan.md "Project Structure": the follower in
`com.chipprbots.ethereum.blockchain.follow`, the capability in `com.chipprbots.ethereum.db.replay`, the shared diff type
in `com.chipprbots.ethereum.domain.statediff`.

---

## 1. Shared value types (`domain/statediff/`)

These are the *decoded* form of the 025 record's state diff (025 DC-2, FR-008). 025 owns the wire encoding (RLP with a
leading schema-version byte) and the golden vectors; 018 F2 stores the same bytes; 026 applies the decoded value. One
type, three users (025 DC-9). Keys are already hashed (research R-2).

```scala
package com.chipprbots.ethereum.domain.statediff

import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.domain.Account

/** keccak(address), 32 bytes. */
opaque type AccountKey = ByteString
/** keccak(address) ++ keccak(pad32(slot)), 64 bytes. */
opaque type SlotKey = ByteString

final case class Change[A](previous: Option[A], next: Option[A])   // None = absent / deleted

final case class StateDiff(
    accounts: Vector[(AccountKey, Change[Account])],              // key-ascending, no duplicates
    slots: Vector[(SlotKey, Change[BigInt])],                      // key-ascending; next = None means value 0
    code: Vector[(ByteString /* codeHash */, ByteString /* bytes */)]  // new code only; content-addressed
):
  def inverse: StateDiff  // swaps previous/next for accounts and slots; code unchanged
```

Invariants (checked by `StateDiff.validate`, tested by property): keys sorted and unique; every slot key's first 32
bytes name an account present in `accounts` or already existing; every account `next` whose `codeHash` is not the empty
code hash has its code in `code` or already in `EvmCodeStorage` (025 FR-008 "code is a dependency of its leaf").

```scala
sealed trait FollowRecord:
  def chainKey: String; def blockNumber: BigInt; def blockHash: ByteString

final case class BlockRecord(chainKey: String, header: BlockHeader, receipts: Seq[Receipt], diff: StateDiff,
    raw: ByteString /* the bytes as received, kept for the window */) extends FollowRecord
final case class Retraction(chainKey: String, blockNumber: BigInt, blockHash: ByteString) extends FollowRecord
final case class Finality(chainKey: String, finalizedNumber: BigInt, finalizedHash: ByteString) extends FollowRecord
// 025 FR-012 `witness` records are ignored by followers in this spec.
```

`parentHash` is `header.parentHash` on a `BlockRecord`. Decoding is 025's codec; 026 depends on its `decode(bytes):
Either[DecodeError, FollowRecord]`, which MUST fail on an unknown schema version (025 FR-011).

---

## 2. `StateReplay` capability (`db/replay/`)

```scala
package com.chipprbots.ethereum.db.replay

import cats.effect.IO
import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.domain.{BlockHeader, Receipt}
import com.chipprbots.ethereum.domain.statediff.StateDiff
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate

/** The follower write path (018 amendment row 7). Not SNAP (`StateIngest`), not EVM (`StateAccess`). */
trait StateReplay:

  /** Apply `diff` on top of the state at `parentRoot`, verify the computed root against `expectedRoot` (the block's
    * header.stateRoot) and the receipts against `expectedReceiptsRoot`, and commit everything atomically with `extra`
    * (the block's header, receipts, mapping, record copy, AppState keys) only if both match.
    *
    * Returns the committed root. Fails with `RootMismatch` / `ReceiptsRootMismatch` (nothing committed) or a storage
    * error. The call returns only after the data source's write has returned (durable per the WAL policy in effect).
    */
  def applyForward(
      blockNumber: BigInt,
      blockHash: ByteString,
      parentRoot: ByteString,
      diff: StateDiff,
      expectedRoot: ByteString,
      receipts: Seq[Receipt],
      expectedReceiptsRoot: ByteString,
      extra: DataSourceBatchUpdate
  ): IO[Root]

  /** Undo one block: apply `diff.inverse` on top of the state at `currentRoot` (the retracted block's root) and verify
    * the result equals `expectedParentRoot` (the parent header's stateRoot). Same atomicity and durability as
    * applyForward. The retracted block's number is used to tag the write (reference counts on the Hash scheme).
    */
  def applyBackward(
      blockNumber: BigInt,
      blockHash: ByteString,
      currentRoot: ByteString,
      diff: StateDiff,
      expectedParentRoot: ByteString,
      extra: DataSourceBatchUpdate
  ): IO[Root]

  /** Read-only root recomputation for tests and the GT-1 bench: no writes, no commit. */
  def dryRunRoot(parentRoot: ByteString, diff: StateDiff): IO[Root]

object StateReplay:
  opaque type Root = ByteString
  object Root:
    def apply(b: ByteString): Root = b
    extension (r: Root) def bytes: ByteString = r

  sealed trait ReplayError extends Exception
  final case class RootMismatch(blockNumber: BigInt, expected: ByteString, computed: ByteString) extends ReplayError
  final case class ReceiptsRootMismatch(blockNumber: BigInt, expected: ByteString, computed: ByteString)
      extends ReplayError
  final case class InvalidDiff(reason: String) extends ReplayError
  final case class MissingPreState(root: ByteString) extends ReplayError   // parentRoot not in the store

  /** v1, today's layout (both schemes): staged trie puts over hashed keys, flat stores, code; see research R-1/R-2. */
  def v1(
      stateStorage: StateStorage,
      flatAccounts: FlatAccountStorage,
      flatSlots: FlatSlotStorage,
      code: EvmCodeStorage,
      receiptsRootOf: Seq[Receipt] => ByteString   // MptListValidator over Receipt.byteArraySerializable
  ): StateReplay
```

Rules:
- `StateReplay` is constructed only when `InstanceConfig.role` is `replica` or `archive`; `ingest` and `sim` never hold
  one (018 row 7 "held only by follower roles"). Enforced by construction in `NodeBuilder`, pinned by a test.
- v1 performs, in order: code puts → for each account with changed slots, open
  `MerklePatriciaTrie[ByteString, BigInt](account.previous.storageRoot, staged.storage)` with the identity key encoder,
  `put`/`remove` the slots, take `getRootHash`, and require it to equal `account.next.storageRoot` when `next` is
  present (an inconsistent record fails as `InvalidDiff` before the account trie is touched) → open
  `MerklePatriciaTrie[ByteString, Account](parentRoot, staged.storage)`, `put`/`remove` the accounts → `getRootHash`
  → compare → `commitWith` or `discard`. Mirrors `InMemoryWorldStateProxy.persistState`
  (`ledger/InMemoryWorldStateProxy.scala:84-113`).
- v2 (018 Track F) implements the same trait over the unified layout; `FollowController` is unchanged.

---

## 3. Follower state and AppState keys (`db/storage/AppStateStorage`, CF `s`)

| Key | Value | Written | Read by |
|---|---|---|---|
| `follow.role` | `replica` \| `archive` | seed import, once | start: a datadir seeded for one role refuses another role or `ingest` |
| `follow.seedBlock` | `<number>:<hash hex>` | seed import | health `seedBlock`; window floor for `archive` |
| `follow.appliedHead` | `<number>:<hash hex>` | every block batch (forward and backward) | resolver `latest`; restart |
| `follow.lastVerifiedRoot` | root hex | every block batch | readiness (equals `header(appliedHead).stateRoot`) |
| `follow.finalized` | `<number>:<hash hex>` | finality record (ETC: genesis once) | resolver `safe`/`finalized`; FinalizedRetraction check |
| `follow.windowLow` | number | every block batch (`replica` only; absent on `archive`) | window check; health `window.low` |
| `follow.sourcePosition` | source-specific string (Kafka `<partition>:<offset>`; file `<fileIndex>:<recordIndex>`) | every block batch | restart resume |
| `follow.halt` | JSON `{fault, blockNumber, blockHash, expected, computed, at}` | on halt, `updateSync` | start (halted), health `fault`, RPC refusal |
| `follow.halt.record` | hash of the offending record (bytes live in the record CF) | on halt | diagnosis |

`BestBlockNumber`/`BestBlockHash` (`AppStateStorage.scala:38-40`) are written in the same batch as `follow.appliedHead`
so every existing `getBestBlock` reader is correct on a follower. `snapSyncDone`, `bytecodeRecoveryDone`,
`storageRecoveryDone` are set by the seed import (as `CheckpointImporter` does) so nothing SNAP-shaped ever considers
the datadir fresh; `SyncMode.Follow` never reads them (research R-10).

### New column family

| Namespace | Byte | Key | Value | Lifetime |
|---|---|---|---|---|
| `FollowRecordNamespace` | `'y'` (unused in `Namespaces.nsSeq`) | `blockNumber` as 8-byte big-endian ++ `blockHash` | the record bytes as received (versioned RLP) | `replica`: deleted when `blockNumber < windowLow` (range delete per block batch); `archive`: kept |

Added at the end of `Namespaces.nsSeq` with the same comment pattern as `BlockAccessListNamespace`
(`db/storage/Namespaces.scala:30-56`); `RocksDbDataSource` creates missing CFs on open.

---

## 4. Ordered queue (`blockchain/follow/OrderedRecordQueue.scala`)

```scala
final case class QueueBounds(maxRecords: Int, maxBytes: Long, gapTimeout: FiniteDuration)

final case class Applied(number: BigInt, hash: ByteString)

enum QueueDecision:
  case Apply(record: FollowRecord)                 // next in order
  case Hold                                        // buffered, waiting for a predecessor
  case Duplicate(record: FollowRecord)             // dropped, counted
  case Fault(fault: FollowFault)                   // halt

/** Pure. `offer` never performs IO; the driver (fs2) owns time and backpressure. */
final class OrderedRecordQueue(bounds: QueueBounds):
  def offer(now: FiniteDuration, applied: Applied, windowLow: Option[BigInt], finalized: Option[BigInt],
            record: FollowRecord): (OrderedRecordQueue, QueueDecision)
  def next(applied: Applied): Option[(OrderedRecordQueue, FollowRecord)]
  def isFull: Boolean                               // records ≥ maxRecords or bytes ≥ maxBytes → stop pulling
  def gapSince: Option[FiniteDuration]              // for PersistentGap after gapTimeout
  def depth: Int
```

Bounds and defaults: `maxRecords` 1024, `maxBytes` 256 MiB, `gapTimeout` 5 minutes (`fukuii.follow.*`, §6).

---

## 5. Faults and follower status

```scala
enum FollowFault(val name: String):
  case RootMismatch          extends FollowFault("root-mismatch")           // DC-1
  case ReceiptsRootMismatch  extends FollowFault("receipts-root-mismatch")  // DC-1
  case InvalidRecord         extends FollowFault("invalid-record")          // decode or StateDiff.validate failure
  case UnknownSchemaVersion  extends FollowFault("unknown-schema-version")  // 025 FR-011
  case UnexpectedFork        extends FollowFault("unexpected-fork")         // replacement without retraction
  case PersistentGap         extends FollowFault("persistent-gap")          // FR-008 (clears when the gap fills)
  case ReseedRequired        extends FollowFault("reseed-required")         // DC-4, FR-006
  case FinalizedRetraction   extends FollowFault("finalized-retraction")    // spec edge case; 025 FR-007
  case ChainKeyMismatch      extends FollowFault("chain-key-mismatch")      // record for another chain
  case StorageError          extends FollowFault("storage-error")

final case class FollowStatus(
    role: Role, chainKey: String, chainId: BigInt,
    head: Applied, finalized: Option[Applied], seedBlock: Applied,
    sourceHead: Option[BigInt], sourceLag: Option[BigInt],
    window: Option[(BigInt, BigInt)],         // replica only
    lastVerifiedRoot: ByteString,
    halted: Option[(FollowFault, BigInt)],    // fault and block
    queueDepth: Int,
    ready: Boolean                            // DC-8
)
```

`PersistentGap` is the one fault that does not halt: it sets `ready = false` and clears itself when the missing record
arrives. All others halt (research R-7).

---

## 6. Configuration (`fukuii.follow`, in `conf/base/follow.conf`, included from `base/fukuii.conf`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `fukuii.follow.source.type` | `file` \| `kafka` | `kafka` | which `RecordSource` to build |
| `fukuii.follow.source.file.dir` | path | — | 025 file-sink directory (tests, offline) |
| `fukuii.follow.source.kafka.bootstrap-servers` | string list | — | broker list |
| `fukuii.follow.source.kafka.topic` | string | `fukuii.records.<chainKey>` (025 FR-003 name) | one topic per `chainKey` |
| `fukuii.follow.source.kafka.group-id` | string | `fukuii-<role>-<datadir hash>` | consumer group; offsets are *also* kept in `follow.sourcePosition` |
| `fukuii.follow.window` | int (blocks) | ChainSpec `profiles.replica.retention-window-blocks` (Mordor/ETC 6650, Sepolia/Platåberget 7200) | `W`; ignored on `archive` |
| `fukuii.follow.lag-bound` | int (blocks) | 8 | readiness bound (DC-8) and pruning margin |
| `fukuii.follow.queue-bound` | int (records) | 1024 | ordered queue size |
| `fukuii.follow.queue-bytes` | bytes | 256 MiB | ordered queue bytes |
| `fukuii.follow.gap-timeout` | duration | 5 minutes | `PersistentGap` after this |
| `fukuii.follow.sync-every` | int (blocks) | 64 | `commitSync` cadence |
| `fukuii.follow.seed.path` | path | — | 013 v2 snapshot directory or manifest |
| `fukuii.follow.seed.trusted-block-hash` | hex | — | ETC anchor / offline anchor (013 §5.4) |
| `fukuii.follow.min-free-bytes-per-block` | bytes | per-chain default from the seed manifest | start-time sizing check for `replica` |
| `fukuii.follow.rpc.pin-header` | boolean | `true` | also emit `Fukuii-Pinned-Block` on HTTP |

Derived, never set by the operator: `pruning.mode = "basic"`, `pruning.history = window + lag-bound` on `replica`;
`pruning.mode = "archive"` on `archive` (until GT-2). A profile that sets `sync.do-snap-sync = true`, `mining`, or a
P2P port for a follower role is a 024 validator error (023 DC-9).

---

## 7. JSON-RPC errors (follower roles only)

| Code | Message | `data` | When |
|---|---|---|---|
| `-39010` | `outside retention window` | `{requested: {number, hash?}, window: {low, high}, head}` | any block or range edge below `windowLow` on a `replica` (DC-3) |
| `-39011` | `not served by this role` | `{role, method}` | 023 DC-9; `pending` on any follower (DC-6); `eth_call`/`eth_simulateV1`/`debug_trace*` on a `replica`; writes without a forwarding endpoint (023 FR-010). **023 T-004 owns the final code**; 026 uses whatever 023 defines and proposes `-39011` |
| `-39012` | `follower halted` | `{fault, blockNumber, blockHash}` | every chain-RPC read while `follow.halt` is set (FR-003) |
| `-39013` | `block not applied` | `{requested, head}` | a number or hash above the applied head (never guess ahead) |
| `-39014` | `executor saturated` | `{method, queue}` | `archive` heavy reads when the 018 FR-027 executor's queue is full (phase D) |

Codes sit outside the `-32000..-32099` server-error range and the `-38010..-38026` `eth_simulateV1` family already
used (`jsonrpc/JsonRpcError.scala:44-70`). Response extension member for pinned scalar results:
`"pinnedBlock": {"number": "0x…", "hash": "0x…"}` (research R-6; contracts/follower-rpc.md).

---

## 8. Health shape additions (023 FR-004, followers)

```json
{
  "role": "replica", "chainKey": "mordor", "chainId": 63,
  "head": {"number": 12345678, "hash": "0x…"},
  "finalized": {"number": 0, "hash": "0x…"},
  "sourceLag": 2, "ready": true, "fenced": false,
  "seedBlock": {"number": 12340000, "hash": "0x…"},
  "window": {"low": 12339028, "high": 12345678},
  "fault": null
}
```

`window` is absent on `archive`; `fault` is the `FollowFault.name` or `null`. Metrics: research R-11.
