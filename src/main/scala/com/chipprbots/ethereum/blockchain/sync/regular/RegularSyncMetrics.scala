package com.chipprbots.ethereum.blockchain.sync.regular

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration.NANOSECONDS

import com.google.common.util.concurrent.AtomicDouble

import io.micrometer.core.instrument.Timer

import com.chipprbots.ethereum.metrics.MetricsContainer

object RegularSyncMetrics extends MetricsContainer {
  final private val blockPropagationTimer = "regularsync.blocks.propagation.timer"

  final val MinedBlockPropagationTimer: Timer =
    metrics.timer(blockPropagationTimer, "blocktype", "MinedBlockPropagation")
  final val NewBlockPropagationTimer: Timer = metrics.timer(blockPropagationTimer, "blocktype", "NewBlockPropagation")
  final val DefaultBlockPropagationTimer: Timer =
    metrics.timer(blockPropagationTimer, "blocktype", "DefaultBlockPropagation")

  final private val CurrentBlockGauge =
    metrics.registry.gauge("regularsync.block.current.number.gauge", new AtomicDouble(0d))
  final private val BestKnownNetworkBlockGauge =
    metrics.registry.gauge("regularsync.block.bestKnown.number.gauge", new AtomicDouble(0d))
  final private val BlocksImportedCounter =
    metrics.registry.counter("regularsync.blocks.imported.total")

  def recordMinedBlockPropagationTimer(nanos: Long): Unit = MinedBlockPropagationTimer.record(nanos, NANOSECONDS)
  def recordImportNewBlockPropagationTimer(nanos: Long): Unit = NewBlockPropagationTimer.record(nanos, NANOSECONDS)
  def recordDefaultBlockPropagationTimer(nanos: Long): Unit = DefaultBlockPropagationTimer.record(nanos, NANOSECONDS)

  def setCurrentBlock(blockNumber: BigInt): Unit = CurrentBlockGauge.set(blockNumber.toDouble)
  def setBestKnownNetworkBlock(blockNumber: BigInt): Unit = BestKnownNetworkBlockGauge.set(blockNumber.toDouble)
  def incrementBlocksImported(): Unit = BlocksImportedCounter.increment()
}
