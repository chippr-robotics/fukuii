package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

/** Represents a state healing task for requesting missing trie nodes.
  *
  * State healing is the process of detecting and downloading missing trie nodes
  * that were not included in account/storage range downloads. This is necessary
  * because SNAP sync downloads account and storage data without intermediate
  * trie nodes to reduce bandwidth.
  *
  * @param path Trie path to the missing node (sequence of node hashes from root to target)
  * @param hash Expected hash of the missing node
  * @param rootHash Root hash of the trie this node belongs to (state root or storage root)
  * @param pending Whether this task is pending (true) or in progress (false)
  * @param done Whether this task has completed successfully
  * @param nodeData The RLP-encoded trie node data once retrieved
  */
case class HealingTask(
    path: Seq[ByteString],
    hash: ByteString,
    rootHash: ByteString,
    var pending: Boolean = true,
    var done: Boolean = false,
    var nodeData: Option[ByteString] = None
) {

  /** Returns a short string representation for debugging */
  def toShortString: String = {
    val pathStr = if (path.isEmpty) "root" else s"depth=${path.length}"
    val hashStr = hash.take(4).map(b => f"$b%02x").mkString
    val status = if (done) "done" else if (pending) "pending" else "active"
    s"HealingTask($pathStr, hash=$hashStr..., $status)"
  }

  /** Returns the progress of this task (0.0 to 1.0) */
  def progress: Double = {
    if (done) 1.0
    else if (nodeData.isDefined) 0.9
    else if (!pending) 0.5
    else 0.0
  }
}

object HealingTask {

  /** Creates a healing task for a missing node at the given path and hash.
    *
    * @param path Trie path to the missing node
    * @param hash Expected hash of the missing node
    * @param rootHash Root hash of the trie this node belongs to
    * @return A new healing task
    */
  def apply(path: Seq[ByteString], hash: ByteString, rootHash: ByteString): HealingTask = {
    new HealingTask(path, hash, rootHash)
  }

  /** Creates healing tasks from a list of missing node paths and hashes.
    *
    * @param missingNodes List of (path, hash) pairs for missing nodes
    * @param rootHash Root hash of the trie these nodes belong to
    * @return Sequence of healing tasks
    */
  def createTasksFromMissingNodes(
      missingNodes: Seq[(Seq[ByteString], ByteString)],
      rootHash: ByteString
  ): Seq[HealingTask] = {
    missingNodes.map { case (path, hash) =>
      HealingTask(path, hash, rootHash)
    }
  }

  /** Default maximum number of healing requests to batch together.
    * Following core-geth patterns for efficient healing.
    */
  val DEFAULT_BATCH_SIZE: Int = 16

  /** Maximum iterations for iterative healing process.
    * Prevents infinite loops in case of persistent missing nodes.
    */
  val MAX_HEALING_ITERATIONS: Int = 10
}
