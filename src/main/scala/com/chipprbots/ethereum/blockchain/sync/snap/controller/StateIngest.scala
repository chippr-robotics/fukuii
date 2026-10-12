package com.chipprbots.ethereum.blockchain.sync.snap.controller

import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter

// The call-site map for the snap-split verify capability mode (spec 016 T054b): each env storage handle and the
// member that replaces it.
// capability-map: appStateStorage -> appState
// capability-map: stateStorage -> stateTries
// capability-map: flatSlotStorage -> flatSlots
// capability-map: pathNodeStorageOpt -> pathNodes
// capability-map: evmCodeStorage -> bytecode
// capability-map: blockchainReader -> chainReader
// capability-map: blockchainWriter -> chainWriter
// capability-map: validatorFactory -> validatorFactory

/** SNAP's state-ingest capability (spec 016 T055, FR-040/041/044/045; ADR INF-006).
  *
  * The data plane has two kinds of state writer. **Ingest** is bulk trie-range and trie-node writing with no execution
  * (SNAP download, healing, later snapshot and era import). **Access** is EVM execution (`StateAccess`, spec 017). This
  * trait is the SNAP side of that boundary: everything SNAP reads or writes in storage goes through it. It exposes no
  * EVM execution read or write; SNAP code never holds `StateAccess` (FR-042).
  *
  * '''Members''' (the eight storage handles `SnapControllerEnv` held before T055; one member per handle):
  *   - progress, resume and completion markers, pivot persistence: [[appState]];
  *   - trie stores: [[stateTries]] (the writable per-pivot trie store, `getBackingStorage`), [[flatSlots]] (flat slots,
  *     and the data source the derived SNAP stores share), [[pathNodes]] (Path scheme only), [[bytecode]];
  *   - chain data (headers, pivot block, chain weight, best block): [[chainReader]], [[chainWriter]]. These are not
  *     trie state; ADR INF-006 records why they stay on this trait for now instead of a separate header/chain
  *     capability;
  *   - verification: [[validatorFactory]].
  *
  * '''Verification reads are ingest.''' SNAP reads what it needs to decide what to write, and those reads belong here,
  * not to `StateAccess`: proof checks (the coordinators' `MerkleProofVerifier` over the trie store), healing presence
  * checks ([[bytecode]] for healed-account code, the trie stores for missing nodes), state validation
  * ([[validatorFactory]]) and the publish verification (the pivot root read back from the hash-keyed store after the
  * Path-to-hash publish, and the #1555 pivot-root presence check over [[pathNodes]]).
  *
  * '''The batched storage-trie node-write path (Path scheme, #1548).''' `StorageRangeCoordinator` builds each node's
  * update with `PathNodeStorage.storageNodeUpsert` / `storageNodeRemoval` (the `SnapPathTrie` write callbacks), buffers
  * them in `trieNodeWrites`, and commits one batch per applied response in `flushTrieNodeWrites`. That path's store is
  * [[pathNodes]]. The capability hands out the store itself, once per batch, so the per-node calls stay direct calls on
  * `PathNodeStorage`: no wrapper call and no allocation per node (SC-010). The coordinator's call sites move onto this
  * member in its own narrowing (C-S2, T067). The Hash scheme already batches its trie writes (8 MiB).
  *
  * '''Durability policy (FR-045).''' A policy is per capability; [[durability]] is this one's. The only policy is
  * [[StateIngest.Durability.Default]], today's behaviour, and nothing reads it yet. A relaxed policy (a lighter WAL,
  * T056) may be set here only, never on `StateAccess` and never on the execution store, on either scheme.
  *
  * '''Exporter durability property (FR-044).''' `PathToHashExporter`'s publish is this capability's '''commit to the
  * access store''': on the Path scheme SNAP writes the path-keyed store, and the publish copies the finished trie into
  * the hash-keyed store that block execution reads. Its output must be durable (flushed and synced) before SNAP
  * finalization marks SNAP complete (`snapSyncDone`) and before execution reads from it, whatever policy the ingest
  * writes ran under. Otherwise a crash during finalization leaves execution reading a store that was never durably
  * written. On the Hash scheme (ETC/Mordor) SNAP writes the execution store directly and there is no export step, so
  * the same property needs a durable flush/sync barrier before `completeSnapSync` marks completion.
  *
  * How today's code meets it (T055 finding, ADR INF-006): the node runs one RocksDB instance with the WAL on for every
  * write, and no write disables it. The three completion flags (`snapSyncDone`, `storageRecoveryDone`,
  * `bytecodeRecoveryDone`) are written in one batch with `commitSync()`, the only synced write on the finalization
  * path. Its WAL sync persists every earlier WAL record too, so a recovered `snapSyncDone` implies every earlier write
  * that went to RocksDB. Under archive and basic pruning (the default is basic) the ingest writes and the publish's
  * writes are such writes: plain commits, WAL appended, not fsynced, so the property holds on both schemes. Under
  * `pruning.mode = "inmemory"` it does not: the trie store (`stateTries.getBackingStorage`, a
  * `CachedReferenceCountedStorage`, which is also the Path publish's target) keeps nodes in its LRU cache and change
  * log, and its `persist()` does nothing, so those nodes are not in the WAL when the flags sync, on either scheme.
  * There is no separate flush or sync barrier before `completeSnapSync` on either scheme, and none that would hold if
  * ingest writes skipped the WAL. Adding one changes behaviour on the ETC path, so T055 does not: it is added by T056,
  * as a hard precondition of any relaxed WAL policy (user decision 2026-10-11). `StateIngestDurabilitySpec` pins
  * today's call ordering on both schemes.
  */
private[snap] trait StateIngest:

  /** SNAP progress, resume checkpoints, completion markers and pivot persistence (the app-state column family). */
  def appState: AppStateStorage

  /** The node store; SNAP takes the writable per-pivot trie store from it (`getBackingStorage`). */
  def stateTries: StateStorage

  /** Flat storage slots. Its data source also backs the derived SNAP stores (Path nodes, done markers, frontier). */
  def flatSlots: FlatSlotStorage

  /** The Path-scheme node store (`None` on the Hash scheme, i.e. ETC), and the batched storage-trie write path's store.
    */
  def pathNodes: Option[PathNodeStorage]

  /** Contract bytecode: the bytecode download's writes and the healed-code presence checks. */
  def bytecode: EvmCodeStorage

  /** Read access to the chain: headers, genesis, stored chain weights. */
  def chainReader: BlockchainReader

  /** Write access to the chain: the pivot header and block, its chain weight. */
  def chainWriter: BlockchainWriter

  /** Builds the `StateValidator` for a trie store (test seam; production: `new StateValidator(_)`). */
  def validatorFactory: MptStorage => StateValidator

  /** This capability's durability policy (FR-045). */
  def durability: StateIngest.Durability

private[snap] object StateIngest:

  /** Durability policies for ingest writes (FR-045). `Default` is today's behaviour: the WAL on for every write, plain
    * commits for ingest writes, and the completion flags in one synced commit. A relaxed policy is T056's.
    */
  enum Durability:
    case Default

  /** The production capability over the controller's stores. */
  def apply(
      appState: AppStateStorage,
      stateTries: StateStorage,
      flatSlots: FlatSlotStorage,
      pathNodes: Option[PathNodeStorage],
      bytecode: EvmCodeStorage,
      chainReader: BlockchainReader,
      chainWriter: BlockchainWriter,
      validatorFactory: MptStorage => StateValidator,
      durability: Durability
  ): StateIngest =
    new Stores(
      appState,
      stateTries,
      flatSlots,
      pathNodes,
      bytecode,
      chainReader,
      chainWriter,
      validatorFactory,
      durability
    )

  /** A final class of constructor `val`s and nothing else: the controller makes one at construction, and every member
    * is a field read. It is the only production implementation, so each call through the trait has one receiver class.
    */
  final private class Stores(
      val appState: AppStateStorage,
      val stateTries: StateStorage,
      val flatSlots: FlatSlotStorage,
      val pathNodes: Option[PathNodeStorage],
      val bytecode: EvmCodeStorage,
      val chainReader: BlockchainReader,
      val chainWriter: BlockchainWriter,
      val validatorFactory: MptStorage => StateValidator,
      val durability: Durability
  ) extends StateIngest
