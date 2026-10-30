package com.chipprbots.ethereum.blockchain.sync.snap.controller

import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter

/** The module stubs' `StateIngest` (spec 016 T055): each member re-reads the stub's own store on every call. The
  * arguments are by-name, so a store a stub never provides (one that throws "not reached") is not touched until a
  * module actually reaches it, and a store a test reassigns (a `var`) is seen by the next call. The healing stores
  * (spec 016 C-H1) default to failing loudly; `HealingOrchestratorSpec`'s stub hands in its own.
  */
final private[snap] class ForwardingStateIngest(
    app: => AppStateStorage,
    tries: => StateStorage,
    slots: => FlatSlotStorage,
    nodes: => Option[PathNodeStorage],
    code: => EvmCodeStorage,
    reader: => BlockchainReader,
    writer: => BlockchainWriter,
    validators: => MptStorage => StateValidator,
    frontier: => Option[HealingFrontierStorage] = ForwardingStateIngest.notReached("healingFrontier"),
    bfs: => Option[BfsQueueStorage] = ForwardingStateIngest.notReached("bfsQueue")
) extends StateIngest:
  def appState: AppStateStorage = app
  def stateTries: StateStorage = tries
  def flatSlots: FlatSlotStorage = slots
  def pathNodes: Option[PathNodeStorage] = nodes
  def bytecode: EvmCodeStorage = code
  def chainReader: BlockchainReader = reader
  def chainWriter: BlockchainWriter = writer
  def validatorFactory: MptStorage => StateValidator = validators
  def durability: StateIngest.Durability = StateIngest.Durability.Default
  // The derived stores (spec 016 T060d): `trieStore` forwards as the production capability does; no module reaches the
  // other two through the capability yet, so they fail loudly.
  def trieStore(pivot: BigInt): MptStorage = tries.getBackingStorage(pivot)
  // spec 022 D5: the heal gate's excluded set; one store per capability, empty unless a test records in it.
  lazy val snapProgress: SnapSyncProgressStorage =
    new SnapSyncProgressStorage(new com.chipprbots.ethereum.db.dataSource.EphemDataSource(Map.empty))
  def storageDone: SnapStorageDoneStorage = ForwardingStateIngest.notReached("storageDone")
  // The healing stores (spec 016 C-H1): the healing spawn reads them; by-name like the rest.
  def healingFrontier: Option[HealingFrontierStorage] = frontier
  def bfsQueue: Option[BfsQueueStorage] = bfs

private[snap] object ForwardingStateIngest:
  def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"module stub read StateIngest.$member")

/** A `StateIngest` that hands out the excluded set's store alone; a module stub that reaches anything else fails
  * loudly.
  */
final private[snap] class ForwardingStateIngestForProgress(progress: SnapSyncProgressStorage) extends StateIngest:
  def appState: AppStateStorage = ForwardingStateIngest.notReached("appState")
  def stateTries: StateStorage = ForwardingStateIngest.notReached("stateTries")
  def flatSlots: FlatSlotStorage = ForwardingStateIngest.notReached("flatSlots")
  def pathNodes: Option[PathNodeStorage] = ForwardingStateIngest.notReached("pathNodes")
  def bytecode: EvmCodeStorage = ForwardingStateIngest.notReached("bytecode")
  def chainReader: BlockchainReader = ForwardingStateIngest.notReached("chainReader")
  def chainWriter: BlockchainWriter = ForwardingStateIngest.notReached("chainWriter")
  def validatorFactory: MptStorage => StateValidator = ForwardingStateIngest.notReached("validatorFactory")
  def durability: StateIngest.Durability = StateIngest.Durability.Default
  def trieStore(pivot: BigInt): MptStorage = ForwardingStateIngest.notReached("trieStore")
  def snapProgress: SnapSyncProgressStorage = progress
  def storageDone: SnapStorageDoneStorage = ForwardingStateIngest.notReached("storageDone")
  def healingFrontier: Option[HealingFrontierStorage] = ForwardingStateIngest.notReached("healingFrontier")
  def bfsQueue: Option[BfsQueueStorage] = ForwardingStateIngest.notReached("bfsQueue")
