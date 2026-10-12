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

/** The module stubs' `StateIngest` (spec 016 T055): each member re-reads the stub's own store on every call. The
  * arguments are by-name, so a store a stub never provides (one that throws "not reached") is not touched until a
  * module actually reaches it, and a store a test reassigns (a `var`) is seen by the next call.
  */
final private[snap] class ForwardingStateIngest(
    app: => AppStateStorage,
    tries: => StateStorage,
    slots: => FlatSlotStorage,
    nodes: => Option[PathNodeStorage],
    code: => EvmCodeStorage,
    reader: => BlockchainReader,
    writer: => BlockchainWriter,
    validators: => MptStorage => StateValidator
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
