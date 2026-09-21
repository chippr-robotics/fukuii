package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.TxLogEntry

/** Represenation of the result of execution of a contract
  *
  * @param returnData
  *   bytes returned by the executed contract (set by [[RETURN]] opcode)
  * @param gasRemaining
  *   amount of gas remaining after execution
  * @param world
  *   represents changes to the world state
  * @param addressesToDelete
  *   list of addresses of accounts scheduled to be deleted
  * @param internalTxs
  *   list of internal transactions (for debugging/tracing) if enabled in config
  * @param error
  *   defined when the program terminated abnormally
  * @param createdAddresses
  *   addresses of accounts created by CREATE/CREATE2 during this transaction (EIP-6780). Propagated upwards only from
  *   frames that completed without error, so a reverted CREATE does not leak its address.
  */
case class ProgramResult[W <: WorldStateProxy[W, S], S <: Storage[S]](
    returnData: ByteString,
    gasRemaining: BigInt,
    world: W,
    addressesToDelete: Set[Address],
    logs: Seq[TxLogEntry],
    internalTxs: Seq[InternalTransaction],
    gasRefund: BigInt,
    error: Option[ProgramError],
    accessedAddresses: Set[Address],
    accessedStorageKeys: Set[(Address, StorageKey)],
    transientStorage: Map[(Address, StorageKey), BigInt] = Map.empty,
    createdAddresses: Set[Address] = Set.empty
)
