package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.network.Peer

/** Storage range task for SNAP sync
  *
  * Represents a range of storage slots to download for a specific account.
  * Follows core-geth patterns from eth/protocols/snap/sync.go (storageTask).
  *
  * Unlike AccountTask which covers the full account space, StorageTask is per-account
  * and tracks the storage trie for a single contract account.
  *
  * @param accountHash Hash of the account whose storage is being synced
  * @param storageRoot Storage root hash for this account (for verification)
  * @param next Next storage slot hash to sync in this interval
  * @param last Last storage slot hash to sync in this interval (exclusive upper bound)
  */
case class StorageTask(
    accountHash: ByteString,
    storageRoot: ByteString,
    next: ByteString,
    last: ByteString,
    // Runtime fields
    var pending: Boolean = false,
    var done: Boolean = false,
    var slots: Seq[(ByteString, ByteString)] = Seq.empty, // (slotHash, slotValue)
    var proof: Seq[ByteString] = Seq.empty
) {

  /** Check if this task is completed */
  def isComplete: Boolean = done

  /** Check if this task is in progress */
  def isPending: Boolean = pending

  /** Get the range as a human-readable string */
  def rangeString: String = {
    val nextStr = if (next.isEmpty) "0x00..." else next.take(4).toArray.map("%02x".format(_)).mkString
    val lastStr = if (last.isEmpty) "0xFF..." else last.take(4).toArray.map("%02x".format(_)).mkString
    s"[$nextStr...$lastStr]"
  }

  /** Get account identifier for logging */
  def accountString: String = {
    accountHash.take(4).toArray.map("%02x".format(_)).mkString
  }

  /** Calculate progress based on downloaded storage slots */
  def progress: Double = {
    if (done) 1.0
    else if (slots.isEmpty) 0.0
    else {
      // Rough estimate based on slot count
      // Typical storage ranges can vary widely (from 0 to thousands of slots)
      math.min(0.9, slots.size.toDouble / StorageTask.ESTIMATED_SLOTS_FOR_NEAR_COMPLETE)
    }
  }
}

object StorageTask {

  /** 
    * Estimated number of storage slots that represents "almost complete" (90% progress).
    * This is a rough heuristic. Actual storage ranges vary dramatically:
    * - Most contracts have 0-10 storage slots
    * - Popular contracts can have thousands of slots
    */
  val ESTIMATED_SLOTS_FOR_NEAR_COMPLETE = 100.0

  /** Create initial storage task for an account
    *
    * Creates a task covering the full storage space for a single account.
    * Unlike accounts which are divided into parallel chunks, storage is typically
    * downloaded in a single pass per account (unless very large).
    *
    * @param accountHash Hash of the account
    * @param storageRoot Storage root to verify against
    * @return Storage task covering full storage space
    */
  def createStorageTask(accountHash: ByteString, storageRoot: ByteString): StorageTask = {
    StorageTask(
      accountHash = accountHash,
      storageRoot = storageRoot,
      next = ByteString.empty, // 0x00... (start)
      last = ByteString.empty  // 0xFF... (end, exclusive)
    )
  }

  /** Create storage tasks for multiple accounts
    *
    * @param accounts Sequence of (accountHash, storageRoot) pairs
    * @return Sequence of storage tasks, one per account
    */
  def createStorageTasks(accounts: Seq[(ByteString, ByteString)]): Seq[StorageTask] = {
    accounts.map { case (accountHash, storageRoot) =>
      createStorageTask(accountHash, storageRoot)
    }
  }

  /** Create continuation task for partial storage download
    *
    * When a storage response doesn't cover the full range, create a continuation
    * task starting from the last received slot.
    *
    * @param original Original task
    * @param lastSlotHash Hash of the last slot received
    * @return Continuation task
    */
  def createContinuation(original: StorageTask, lastSlotHash: ByteString): StorageTask = {
    StorageTask(
      accountHash = original.accountHash,
      storageRoot = original.storageRoot,
      next = lastSlotHash,
      last = original.last
    )
  }
}
