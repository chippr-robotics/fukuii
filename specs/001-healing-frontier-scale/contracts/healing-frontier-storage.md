# Contract: HealingFrontierStorage (Layer 2)

Internal storage interface for the crash-durable healing frontier. Models the existing
`FlatSlotStorage` (`db/storage/FlatSlotStorage.scala`). Consumed only by
`TrieNodeHealingCoordinator`; constructed by `SNAPSyncController`. Not a network/RPC surface.

## Type

```scala
class HealingFrontierStorage(val dataSource: DataSource)
    extends TransactionalKeyValueStorage[ByteString, Seq[ByteString]] {
  val namespace: IndexedSeq[Byte] = Namespaces.HealingFrontierNamespace
  // key   = node hash (32 B)
  // value = pathset (Seq[ByteString]), RLP-encoded
}
```

## Operations (contract)

| Operation | Signature | Semantics |
|-----------|-----------|-----------|
| put (queue) | `put(hash, pathset): DataSourceBatchUpdate` | Persist one outstanding frontier entry. Idempotent (same key overwrites identically). MUST be committed. |
| remove (heal) | `remove(hash): DataSourceBatchUpdate` | Drop a healed entry. Removing an absent key is a no-op. MUST be committed. |
| loadAll | `iterate/storageContent → Seq[(hash, pathset)]` | Stream every persisted entry for restart resume. |
| (batch) | compose multiple put/remove in one `update(...)` | Batched commit, consistent with existing write batches. |

## Behavioral contract

- **C1 — write on queue**: every entry added in `queueNodes` is persisted before/at the same
  commit as `pendingHashSet += hash`.
- **C2 — delete on heal-flush only**: an entry is removed only when its node is durably written
  to node storage (`flushRawNodes*`), **never** on dispatch.
- **C3 — superset of in-flight**: at any instant the persisted set ⊇ `pendingTasks`.
- **C4 — fail-safe load**: `loadAll` returning empty, or throwing on read, MUST cause the
  coordinator to fall back to the full DFS (logged loudly); it MUST NOT skip healing.
- **C5 — idempotent resume**: loading an entry whose node is already present is a no-op on the
  heal path (the present-node check short-circuits).
- **C6 — serialization round-trip**: `deserialize(serialize(pathset)) == pathset` for any
  `Seq[ByteString]` (property test).

## Constructor wiring (contract)

`TrieNodeHealingCoordinator.props` and the class constructor gain one parameter
(`healingFrontierStorage: Option[HealingFrontierStorage] = None` — `None` ⇒ persistence
disabled, preserving Layer-1 behaviour and existing tests). `SNAPSyncController` constructs it
from the node's `DataSource` and passes it through, exactly as `flatSlotStorage` is threaded
(`SNAPSyncController.scala:26, 1756, 2806, 4412`).
