package com.chipprbots.ethereum.ledger

import scala.collection.mutable

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockAccessList.*
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.vm.BlockAccessRecorder

/** Builds a block's EIP-7928 access list while it executes (Amsterdam, ETH-family only): one [[addIndex]] per block
  * access index, in order — `0` for the EIP-4788 / EIP-2935 system calls, `i + 1` for the `i`-th transaction, `n + 1`
  * for the withdrawals and the four request system calls — then [[build]].
  *
  * Each index contributes what it READ, from its [[BlockAccessRecorder]], and what it CHANGED, found by comparing, for
  * every account and slot it read, the state the index started from with the state it ended on. That per-index
  * comparison is execution-specs' own rule (`block_access_lists.update_builder_from_tx`, HEAD `a9792ab`): a write is
  * netted against the value "as of immediately before the current block access index" (EIP-7928), so a value written
  * and restored within one index is no change, and two system calls sharing index 0 or `n + 1` are netted against each
  * other rather than one by one. Comparing the two ends of the index gives exactly that, with no bookkeeping per write.
  *
  * It is complete because execution-specs reads every account before writing it (`modify_state` → `get_account`) and
  * every slot before writing it (SSTORE's current-value read), and the recorder logs those reads, so the read set
  * covers the write set. What execution-specs reports for an account destroyed in the index (EIP-8246
  * `clear_account_preserving_balance`, whose storage writes become reads) falls out of the same comparison: the
  * account's slots end where they started, at zero, and are left as reads.
  *
  * Both worlds must be persisted (`InMemoryWorldStateProxy.persistState`), so that an account's code hash reflects its
  * code. Not thread-safe: a block executes on one thread.
  */
final class BlockAccessListBuilder:

  import BlockAccessListBuilder.AccountData

  private val accounts = mutable.HashMap.empty[Address, AccountData]

  /** Folds in block access index `index`: everything `reads` recorded, and every change between `before` (the state the
    * index started from) and `after` (the state it ended on) to an account or slot among them. Indices must arrive in
    * increasing order.
    */
  def addIndex(
      index: Long,
      reads: BlockAccessRecorder,
      before: InMemoryWorldStateProxy,
      after: InMemoryWorldStateProxy
  ): Unit =
    reads.addresses.foreach { address =>
      val data = accounts.getOrElseUpdate(address, new AccountData)
      addAccountChanges(index, address, data, before, after)
    }
    reads.slots.foreach { case (address, slots) =>
      val data = accounts.getOrElseUpdate(address, new AccountData)
      val storageBefore = before.getStorage(address)
      val storageAfter = after.getStorage(address)
      slots.foreach { slot =>
        data.storageReads += slot
        val pre = storageBefore.load(slot.toBigInt)
        val post = storageAfter.load(slot.toBigInt)
        if pre != post then
          data.storageChanges.getOrElseUpdate(slot, mutable.ArrayBuffer.empty) += StorageChange(index, UInt256(post))
      }
    }

  private def addAccountChanges(
      index: Long,
      address: Address,
      data: AccountData,
      before: InMemoryWorldStateProxy,
      after: InMemoryWorldStateProxy
  ): Unit =
    // A missing account and an empty one compare alike, as execution-specs' `pre_account.balance if pre_account else
    // U256(0)` does: an account created empty, or an empty account removed under EIP-161, changes nothing.
    val pre = before.getAccount(address)
    val post = after.getAccount(address)
    val preBalance = pre.fold(UInt256.Zero)(_.balance)
    val postBalance = post.fold(UInt256.Zero)(_.balance)
    if preBalance != postBalance then data.balanceChanges += BalanceChange(index, postBalance)
    val preNonce = pre.fold(UInt256.Zero)(_.nonce)
    val postNonce = post.fold(UInt256.Zero)(_.nonce)
    if preNonce != postNonce then data.nonceChanges += NonceChange(index, postNonce.toBigInt)
    val preCodeHash = pre.fold(Account.EmptyCodeHash)(_.codeHash)
    val postCodeHash = post.fold(Account.EmptyCodeHash)(_.codeHash)
    if preCodeHash != postCodeHash then data.codeChanges += CodeChange(index, after.getCode(address))

  /** The list in EIP-7928's canonical order: accounts by address, slots numerically, changes by index, and storage
    * reads without the slots that were also written.
    */
  def build: BlockAccessList =
    BlockAccessList(
      accounts.toSeq
        .sortWith((a, b) => java.util.Arrays.compareUnsigned(a._1.toArray, b._1.toArray) < 0)
        .map { case (address, data) =>
          AccountChanges(
            address,
            storageChanges = data.storageChanges.toSeq.sortBy(_._1).map { case (slot, changes) =>
              SlotChanges(slot, changes.toSeq)
            },
            storageReads = data.storageReads.iterator.filterNot(data.storageChanges.contains).toSeq.sorted,
            balanceChanges = data.balanceChanges.toSeq,
            nonceChanges = data.nonceChanges.toSeq,
            codeChanges = data.codeChanges.toSeq
          )
        }
    )

object BlockAccessListBuilder:

  /** One account's entries so far. The change buffers fill in index order, since indices arrive in order. */
  final private class AccountData:
    val storageChanges: mutable.HashMap[UInt256, mutable.ArrayBuffer[StorageChange]] = mutable.HashMap.empty
    val storageReads: mutable.HashSet[UInt256] = mutable.HashSet.empty
    val balanceChanges: mutable.ArrayBuffer[BalanceChange] = mutable.ArrayBuffer.empty
    val nonceChanges: mutable.ArrayBuffer[NonceChange] = mutable.ArrayBuffer.empty
    val codeChanges: mutable.ArrayBuffer[CodeChange] = mutable.ArrayBuffer.empty
