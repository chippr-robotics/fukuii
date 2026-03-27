package com.chipprbots.ethereum.consensus.mining

import java.util.concurrent.atomic.AtomicLong

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer

import com.chipprbots.ethereum.metrics.MetricsContainer

object MiningMetrics extends MetricsContainer {
  final private val blockGenTimer = "mining.blocks.generate.timer"
  final val PoWBlockGeneratorTiming: Timer = metrics.timer(blockGenTimer, "class", "PoWBlockGenerator")
  final val RestrictedPoWBlockGeneratorTiming: Timer =
    metrics.timer(blockGenTimer, "class", "RestrictedPoWBlockGenerator")
  final val NoOmmersBlockGeneratorTiming: Timer = metrics.timer(blockGenTimer, "class", "NoOmmersBlockGenerator")

  final val MinedBlockEvaluationTimer: Timer = metrics.timer("mining.minedblocks.evaluation.timer")

  // Hashrate metrics (L-011)
  final private val HashRateGauge: AtomicLong =
    metrics.registry.gauge("mining.hashrate.hps.gauge", new AtomicLong(0L))
  final private val TriedHashesCounter: Counter =
    metrics.counter("mining.hashes.tried.total")
  final private val BlocksMinedCounter: Counter =
    metrics.counter("mining.blocks.mined.success.counter")
  final private val BlocksFailedCounter: Counter =
    metrics.counter("mining.blocks.mined.failure.counter")

  def setHashRate(hashesPerSecond: Long): Unit = HashRateGauge.set(hashesPerSecond)
  def addTriedHashes(count: Long): Unit = TriedHashesCounter.increment(count.toDouble)
  def incrementBlocksMined(): Unit = BlocksMinedCounter.increment()
  def incrementBlocksFailed(): Unit = BlocksFailedCounter.increment()
}
