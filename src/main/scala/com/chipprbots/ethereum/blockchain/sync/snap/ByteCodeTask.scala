package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

/** ByteCode download task for SNAP sync
  *
  * Represents a batch of contract bytecodes to download. Follows core-geth patterns from eth/protocols/snap/sync.go
  *
  * Unlike AccountTask and StorageTask which represent ranges, ByteCodeTask represents a specific sequence of bytecode
  * hashes to download for contract accounts.
  *
  * @param codeHashes
  *   Sequence of bytecode hashes to download
  * @param accountHashes
  *   Corresponding account hashes (for logging/debugging)
  */
case class ByteCodeTask(
    codeHashes: Seq[ByteString],
    accountHashes: Seq[ByteString] = Seq.empty,
    // Runtime fields
    var pending: Boolean = false,
    var done: Boolean = false,
    var bytecodes: Seq[ByteString] = Seq.empty
) {

  require(codeHashes.nonEmpty, "ByteCodeTask must have at least one code hash")
  require(
    accountHashes.isEmpty || accountHashes.size == codeHashes.size,
    "If accountHashes is provided, it must match codeHashes size"
  )

  /** Check if this task is completed */
  def isComplete: Boolean = done

  /** Check if this task is in progress */
  def isPending: Boolean = pending

  /** Get task identifier for logging */
  def taskString: String = {
    val hashesStr = codeHashes.take(3).map(_.take(4).toArray.map("%02x".format(_)).mkString).mkString(", ")
    val suffix = if (codeHashes.size > 3) s", ... (${codeHashes.size} total)" else ""
    s"[$hashesStr$suffix]"
  }

  /** Calculate progress based on downloaded bytecodes */
  def progress: Double =
    if (done) 1.0
    else if (bytecodes.isEmpty) 0.0
    else bytecodes.size.toDouble / codeHashes.size

  /** Get number of hashes in this task */
  def size: Int = codeHashes.size
}

object ByteCodeTask {

  /** Default batch size for bytecode requests.
    *
    * Core-geth server accepts up to 1,024 hashes but caps responses at 2MB (softResponseLimit). Geth client sends 85
    * hashes per request (512KB / 24KB max contract size * 4 = 85). Besu defaults to 84-168. Sending 1,000 wastes
    * overhead since most go unserved. Aligned with geth client-side norm for complete responses per request.
    */
  val DEFAULT_BATCH_SIZE = 85

  /** Create bytecode tasks from a list of contract accounts
    *
    * Batches contract code hashes into tasks of the specified batch size.
    *
    * @param contractAccounts
    *   Sequence of (accountHash, codeHash) for contract accounts
    * @param batchSize
    *   Number of bytecodes per task (default 16)
    * @return
    *   Sequence of bytecode tasks
    */
  def createBytecodeTasksFromAccounts(
      contractAccounts: Seq[(ByteString, ByteString)],
      batchSize: Int = DEFAULT_BATCH_SIZE
  ): Seq[ByteCodeTask] = {
    require(batchSize > 0, "Batch size must be positive")

    if (contractAccounts.isEmpty) {
      return Seq.empty
    }

    // Group into batches
    contractAccounts
      .grouped(batchSize)
      .map { batch =>
        val accountHashes = batch.map(_._1)
        val codeHashes = batch.map(_._2)
        ByteCodeTask(codeHashes, accountHashes)
      }
      .toSeq
  }

  /** Create a single bytecode task from code hashes
    *
    * @param codeHashes
    *   Sequence of code hashes to download
    * @return
    *   ByteCode task
    */
  def createTask(codeHashes: Seq[ByteString]): ByteCodeTask = {
    require(codeHashes.nonEmpty, "Must provide at least one code hash")
    ByteCodeTask(codeHashes)
  }

  /** Create bytecode tasks by batching code hashes
    *
    * @param codeHashes
    *   Sequence of code hashes to download
    * @param batchSize
    *   Number of bytecodes per task
    * @return
    *   Sequence of bytecode tasks
    */
  def createBatchedTasks(
      codeHashes: Seq[ByteString],
      batchSize: Int = DEFAULT_BATCH_SIZE
  ): Seq[ByteCodeTask] = {
    require(batchSize > 0, "Batch size must be positive")

    if (codeHashes.isEmpty) {
      return Seq.empty
    }

    codeHashes
      .grouped(batchSize)
      .map(batch => ByteCodeTask(batch))
      .toSeq
  }
}
