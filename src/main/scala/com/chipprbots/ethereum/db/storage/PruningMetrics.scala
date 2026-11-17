package com.chipprbots.ethereum.db.storage

import io.micrometer.core.instrument.Counter

import com.chipprbots.ethereum.metrics.MetricsContainer

/** What basic pruning kept instead of deleting (#1692). On a datadir whose reference counts are not proven exact, a
  * prune deletes no trie node, and these count the nodes it would have deleted: the disk that costs, per block, without
  * a look at the database.
  *
  * Bytes are the nodes' RLP; each stored entry adds its 32-byte key and a few bytes of wrapper. Registered as
  * `db.pruning.retained.nodes` and `db.pruning.retained.bytes` under the metrics prefix.
  */
object PruningMetrics extends MetricsContainer:

  final private val RetainedNodesCounter: Counter = metrics.counter("db.pruning.retained.nodes")
  final private val RetainedBytesCounter: Counter = metrics.counter("db.pruning.retained.bytes")

  def recordRetained(nodes: Int, bytes: Long): Unit =
    RetainedNodesCounter.increment(nodes.toDouble)
    RetainedBytesCounter.increment(bytes.toDouble)

  /** Nodes kept so far in this process. */
  def retainedNodes: Double = RetainedNodesCounter.count()

  /** RLP bytes kept so far in this process. */
  def retainedBytes: Double = RetainedBytesCounter.count()
