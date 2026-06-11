package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

/** Storage range task for SNAP sync
  *
  * Represents a range of storage slots to download for a specific account. Follows core-geth patterns from
  * eth/protocols/snap/sync.go (storageTask). Serves double duty as the subtask unit for large-storage contracts: when a
  * contract has too many slots to fit in one SNAP response, StorageRangeCoordinator creates N StorageTask objects
  * covering disjoint slot ranges and dispatches them in parallel (spec 005). See `StorageTask.createSubTasks()`.
  *
  * Unlike AccountTask which covers the full account space, StorageTask is per-account and tracks the storage trie for a
  * single contract account.
  *
  * @param accountHash
  *   Hash of the account whose storage is being synced
  * @param storageRoot
  *   Storage root hash for this account (for verification)
  * @param next
  *   Next storage slot hash to sync in this interval
  * @param last
  *   Last storage slot hash to sync in this interval (exclusive upper bound)
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
  def accountString: String =
    accountHash.take(4).toArray.map("%02x".format(_)).mkString

  /** Calculate progress based on downloaded storage slots */
  def progress: Double =
    if (done) 1.0
    else if (slots.isEmpty) 0.0
    else {
      // Rough estimate based on slot count
      // Typical storage ranges can vary widely (from 0 to thousands of slots)
      math.min(0.9, slots.size.toDouble / StorageTask.ESTIMATED_SLOTS_FOR_NEAR_COMPLETE)
    }
}

object StorageTask {

  /** Estimated number of storage slots that represents "almost complete" (90% progress). This is a rough heuristic.
    * Actual storage ranges vary dramatically:
    *   - Most contracts have 0-10 storage slots
    *   - Popular contracts can have thousands of slots
    */
  val ESTIMATED_SLOTS_FOR_NEAR_COMPLETE = 100.0

  /** Create initial storage task for an account
    *
    * Creates a task covering the full storage space for a single account. Unlike accounts which are divided into
    * parallel chunks, storage is typically downloaded in a single pass per account (unless very large).
    *
    * @param accountHash
    *   Hash of the account
    * @param storageRoot
    *   Storage root to verify against
    * @return
    *   Storage task covering full storage space
    */
  def createStorageTask(accountHash: ByteString, storageRoot: ByteString): StorageTask = {
    val min = ByteString(Array.fill(32)(0.toByte))
    val max = ByteString(Array.fill(32)(0xff.toByte))
    StorageTask(
      accountHash = accountHash,
      storageRoot = storageRoot,
      next = min, // 0x00... (start)
      last = max // 0xFF... (end, exclusive)
    )
  }

  /** Create storage tasks for multiple accounts
    *
    * @param accounts
    *   Sequence of (accountHash, storageRoot) pairs
    * @return
    *   Sequence of storage tasks, one per account
    */
  def createStorageTasks(accounts: Seq[(ByteString, ByteString)]): Seq[StorageTask] =
    accounts.map { case (accountHash, storageRoot) =>
      createStorageTask(accountHash, storageRoot)
    }

  /** Create continuation task for partial storage download
    *
    * When a storage response doesn't cover the full range, create a continuation task starting from the last received
    * slot.
    *
    * @param original
    *   Original task
    * @param lastSlotHash
    *   Hash of the last slot received
    * @return
    *   Continuation task
    */
  def createContinuation(original: StorageTask, lastSlotHash: ByteString): StorageTask =
    StorageTask(
      accountHash = original.accountHash,
      storageRoot = original.storageRoot,
      next = incrementHash32(lastSlotHash),
      last = original.last
    )

  /** Divide the slot range [from, to] into numChunks equal subtasks for parallel download.
    *
    * Mirrors go-ethereum's `newHashRange` + subtask-creation loop (sync.go:2144-2193). Used by StorageRangeCoordinator
    * when the first SNAP response for an account reveals more slots exist than fit in one packet, indicating a
    * large-storage contract that benefits from parallel download.
    *
    * @param accountHash
    *   Hash of the contract account whose storage is being split
    * @param storageRoot
    *   Storage root for trie verification
    * @param from
    *   First slot hash of the remaining range (typically `incrementHash32(lastSlotReceived)`)
    * @param to
    *   Last slot hash of the range (typically `task.last`)
    * @param numChunks
    *   Number of parallel subtasks to create (typically 16, matches go-ethereum `storageConcurrency`)
    * @return
    *   Sequence of `numChunks` StorageTask objects covering consecutive disjoint slot ranges
    */
  def createSubTasks(
      accountHash: ByteString,
      storageRoot: ByteString,
      from: ByteString,
      to: ByteString,
      numChunks: Int
  ): Seq[StorageTask] = {
    require(numChunks > 0, s"numChunks must be positive, got $numChunks")
    if (numChunks == 1) return Seq(StorageTask(accountHash, storageRoot, from, to))
    val fromBig = BigInt(1, from.toArray.padTo(32, 0.toByte))
    val toBig = BigInt(1, to.toArray.padTo(32, 0.toByte))
    val step = (toBig - fromBig) / numChunks
    (0 until numChunks).map { i =>
      val start = if (i == 0) from else bigIntTo32(fromBig + step * i)
      val end = if (i == numChunks - 1) to else bigIntTo32(fromBig + step * (i + 1) - 1)
      StorageTask(accountHash, storageRoot, start, end)
    }
  }

  private[snap] def incrementHash32(hash: ByteString): ByteString = {
    require(hash.length == 32, s"Expected 32-byte hash, got ${hash.length}")
    val bytes = hash.toArray
    var i = bytes.length - 1
    var carry = 1
    while (i >= 0 && carry != 0) {
      val sum = (bytes(i) & 0xff) + carry
      bytes(i) = (sum & 0xff).toByte
      carry = if (sum > 0xff) 1 else 0
      i -= 1
    }
    ByteString(bytes)
  }

  private def bigIntTo32(bi: BigInt): ByteString = {
    val raw = bi.toByteArray
    val unsigned = if (raw.nonEmpty && raw(0) == 0) raw.drop(1) else raw
    ByteString(Array.fill((32 - unsigned.length).max(0))(0.toByte) ++ unsigned.takeRight(32))
  }
}
