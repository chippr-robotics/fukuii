package com.chipprbots.ethereum.vm

import scala.collection.mutable

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256

/** EIP-7928 (Amsterdam, ETH-family only): the accounts and storage slots one block access index reads — a transaction,
  * or the system calls and withdrawals that share index 0 or `n + 1`.
  *
  * A read is a lookup execution-specs makes: every `get_account` / `get_account_optional` (`account_reads`) and every
  * `get_storage` (`storage_reads`) of `forks/amsterdam/state_tracker.py`. Code-by-hash, the transaction-start storage
  * value and transient storage are not reads.
  *
  * WHY A MUTABLE RECORDER, and not a field threaded through `ProgramState` / `ProgramResult`: execution-specs shares
  * these two sets by reference across its `copy_tx_state` snapshots, so a read made inside a frame that then reverts or
  * halts stays in the block access list. One recorder per index, handed to every frame through `ProgramContext` and
  * `ExecEnv`, is that semantics by construction: no way a frame can end — success, revert, exceptional halt, a failed
  * code deposit, an InvalidCall before it starts — drops what it recorded, so no exit path needs a merge step of its
  * own. It exists only while an Amsterdam block executes; every other execution (ETC, pre-Amsterdam ETH, eth_call,
  * estimation, tracing) carries none and records nothing.
  *
  * WHY EXPLICIT: the calls into this class sit where execution-specs performs its lookup relative to its gas checks,
  * rather than in a hook on world-state reads. fukuii prices an opcode in full — reading state for its dynamic part —
  * before its out-of-gas check, so a read-level hook would record targets EIP-7928 "Gas Validation Before State Access"
  * excludes (`bal_*_and_oog_before_target_access`, `bal_sload_and_oog`, `bal_sstore_and_oog`).
  *
  * Not thread-safe: an index executes on one thread.
  */
final class BlockAccessRecorder:

  private val accountReads = mutable.HashSet.empty[Address]
  private val storageReads = mutable.HashMap.empty[Address, mutable.HashSet[UInt256]]

  def recordAccount(address: Address): Unit =
    accountReads += address

  def recordSlot(address: Address, slot: UInt256): Unit =
    storageReads.getOrElseUpdate(address, mutable.HashSet.empty[UInt256]) += slot

  /** Every account read, plus every account whose storage was read: the addresses this index puts in the list. */
  def addresses: collection.Set[Address] = accountReads ++ storageReads.keySet

  def slots: collection.Map[Address, collection.Set[UInt256]] = storageReads

  def isEmpty: Boolean = accountReads.isEmpty && storageReads.isEmpty

object BlockAccessRecorder:

  /** Records `address` when `recorder` is present: the one-line form every recording site uses. */
  def account(recorder: Option[BlockAccessRecorder], address: Address): Unit =
    recorder match
      case Some(r) => r.recordAccount(address)
      case None    => ()

  /** Records slot `slot` of `address` when `recorder` is present. */
  def slot(recorder: Option[BlockAccessRecorder], address: Address, slot: UInt256): Unit =
    recorder match
      case Some(r) => r.recordSlot(address, slot)
      case None    => ()
