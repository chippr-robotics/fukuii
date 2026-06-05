package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.google.common.util.concurrent.AtomicDouble

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.metrics.MetricsContainer

case object BlockMetrics extends MetricsContainer {

  final private[this] val BlockNumberGauge =
    metrics.registry.gauge("sync.block.number.gauge", new AtomicDouble(0d))
  final private[this] val BlockGasLimitGauge =
    metrics.registry.gauge("sync.block.gasLimit.gauge", new AtomicDouble(0d))
  final private[this] val BlockGasUsedGauge =
    metrics.registry.gauge("sync.block.gasUsed.gauge", new AtomicDouble(0d))
  final private[this] val BlockDifficultyGauge =
    metrics.registry.gauge("sync.block.difficulty.gauge", new AtomicDouble(0d))
  final private[this] val BlockTransactionsGauge =
    metrics.registry.gauge("sync.block.transactions.gauge", new AtomicDouble(0d))
  final private[this] val BlockUnclesGauge =
    metrics.registry.gauge("sync.block.uncles.gauge", new AtomicDouble(0d))
  final private[this] val TimeBetweenParentGauge =
    metrics.registry.gauge("sync.block.timeBetweenParent.seconds.gauge", new AtomicDouble(0d))

  // ECBP-1100 MESS decision counters — incremented on each antigravity evaluation
  final private[this] val MessRejectedCounter =
    metrics.registry.counter("chain.mess.rejected.total")
  final private[this] val MessAcceptedCounter =
    metrics.registry.counter("chain.mess.accepted.total")
  // Last tdr/gravity ratio at decision time (< 1.0 = rejected, >= 1.0 = accepted)
  final private[this] val MessGravityGauge =
    metrics.registry.gauge("chain.mess.gravity.gauge", new AtomicDouble(0d))

  def measure(block: Block, getBlockByHashFn: ByteString => Option[Block]): Unit = {
    BlockNumberGauge.set(block.number.toDouble)
    BlockGasLimitGauge.set(block.header.gasLimit.toDouble)
    BlockGasUsedGauge.set(block.header.gasUsed.toDouble)
    BlockDifficultyGauge.set(block.header.difficulty.toDouble)
    BlockTransactionsGauge.set(block.body.numberOfTxs)
    BlockUnclesGauge.set(block.body.numberOfUncles)

    getBlockByHashFn(block.header.parentHash) match {
      case Some(parentBlock) =>
        val timeBetweenBlocksInSeconds: Long =
          block.header.unixTimestamp - parentBlock.header.unixTimestamp
        TimeBetweenParentGauge.set(timeBetweenBlocksInSeconds.toDouble)
      case None => ()
    }
  }

  def incrementMessRejected(): Unit = MessRejectedCounter.increment()
  def incrementMessAccepted(): Unit = MessAcceptedCounter.increment()
  def setMessGravity(ratio: Double): Unit = MessGravityGauge.set(ratio)

}
