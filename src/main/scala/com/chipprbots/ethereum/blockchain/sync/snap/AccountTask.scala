package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account

/** Account range task for SNAP sync
  *
  * Represents a range of accounts to download from the state trie. Follows core-geth patterns from
  * eth/protocols/snap/sync.go
  *
  * @param next
  *   Next account hash to sync in this interval
  * @param last
  *   Last account hash to sync in this interval (exclusive upper bound)
  * @param rootHash
  *   State root hash for verification
  */
case class AccountTask(
    next: ByteString,
    last: ByteString,
    rootHash: ByteString,
    // Runtime fields
    var pending: Boolean = false,
    var done: Boolean = false,
    var accounts: Seq[(ByteString, Account)] = Seq.empty,
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

  /** Calculate progress based on downloaded accounts */
  def progress: Double =
    if (done) 1.0
    else if (accounts.isEmpty) 0.0
    else {
      // Rough estimate based on account count
      // Typical ranges contain hundreds to thousands of accounts
      math.min(0.9, accounts.size.toDouble / AccountTask.ESTIMATED_ACCOUNTS_FOR_NEAR_COMPLETE)
    }
}

object AccountTask {

  /** Estimated number of accounts that represents "almost complete" (90% progress). This is a rough heuristic based on
    * typical account range sizes in SNAP sync. Actual ranges can vary significantly based on state distribution.
    */
  val ESTIMATED_ACCOUNTS_FOR_NEAR_COMPLETE = 1000.0

  /** Create initial account tasks by dividing the account space
    *
    * Following core-geth pattern, divide into chunks for parallel download. Default is 16 concurrent chunks.
    *
    * @param rootHash
    *   State root to sync
    * @param concurrency
    *   Number of parallel tasks (default 16)
    * @return
    *   List of account tasks covering the full account space
    */
  def createInitialTasks(rootHash: ByteString, concurrency: Int = 16): Seq[AccountTask] = {
    require(concurrency > 0, "Concurrency must be positive")

    if (concurrency == 1) {
      // Single task covers entire range
      return Seq(
        AccountTask(
          next = ByteString.empty, // 0x00...
          last = ByteString.empty, // 0xFF... (exclusive)
          rootHash = rootHash
        )
      )
    }

    // Divide 256-bit space into equal chunks
    val chunkSize = BigInt(2).pow(256) / concurrency

    (0 until concurrency).map { i =>
      val start = if (i == 0) BigInt(0) else chunkSize * i
      val end = if (i == concurrency - 1) BigInt(2).pow(256) - 1 else chunkSize * (i + 1)

      AccountTask(
        next = bigIntTo32ByteString(start),
        last = bigIntTo32ByteString(end),
        rootHash = rootHash
      )
    }
  }

  /** Convert BigInt to 32-byte big-endian ByteString
    *
    * Handles sign bit properly and ensures correct padding for hash values.
    *
    * @param bi
    *   BigInt to convert
    * @return
    *   32-byte ByteString in big-endian format
    */
  private def bigIntTo32ByteString(bi: BigInt): ByteString = {
    val bytes = bi.toByteArray
    // BigInt.toByteArray includes a sign bit, so remove it if present
    val unsigned = if (bytes.length > 0 && bytes(0) == 0) bytes.drop(1) else bytes
    // Pad to 32 bytes on the left (big-endian) and take right 32 bytes if too long
    ByteString(Array.fill(32 - unsigned.length.min(32))(0.toByte) ++ unsigned.takeRight(32))
  }
}
