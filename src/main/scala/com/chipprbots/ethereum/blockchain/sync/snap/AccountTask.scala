package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer

/** Account range task for SNAP sync
  *
  * Represents a range of accounts to download from the state trie.
  * Follows core-geth patterns from eth/protocols/snap/sync.go
  *
  * @param next Next account hash to sync in this interval
  * @param last Last account hash to sync in this interval (exclusive upper bound)
  * @param rootHash State root hash for verification
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
  def progress: Double = {
    if (done) 1.0
    else if (accounts.isEmpty) 0.0
    else {
      // Rough estimate based on account count
      math.min(0.9, accounts.size.toDouble / 1000.0)
    }
  }
}

object AccountTask {

  /** Create initial account tasks by dividing the account space
    *
    * Following core-geth pattern, divide into chunks for parallel download.
    * Default is 16 concurrent chunks.
    *
    * @param rootHash State root to sync
    * @param concurrency Number of parallel tasks (default 16)
    * @return List of account tasks covering the full account space
    */
  def createInitialTasks(rootHash: ByteString, concurrency: Int = 16): Seq[AccountTask] = {
    require(concurrency > 0, "Concurrency must be positive")

    if (concurrency == 1) {
      // Single task covers entire range
      return Seq(AccountTask(
        next = ByteString.empty, // 0x00...
        last = ByteString.empty, // 0xFF... (exclusive)
        rootHash = rootHash
      ))
    }

    // Divide 256-bit space into equal chunks
    val chunkSize = BigInt(2).pow(256) / concurrency
    
    (0 until concurrency).map { i =>
      val start = if (i == 0) BigInt(0) else chunkSize * i
      val end = if (i == concurrency - 1) BigInt(2).pow(256) - 1 else chunkSize * (i + 1)
      
      AccountTask(
        next = ByteString(start.toByteArray.takeRight(32).padTo(32, 0.toByte).reverse.take(32).reverse),
        last = ByteString(end.toByteArray.takeRight(32).padTo(32, 0.toByte).reverse.take(32).reverse),
        rootHash = rootHash
      )
    }
  }
}
