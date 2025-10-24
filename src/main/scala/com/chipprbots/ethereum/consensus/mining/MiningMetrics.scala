package com.chipprbots.ethereum.consensus.mining

import io.micrometer.core.instrument.Timer

import com.chipprbots.ethereum.metrics.MetricsContainer

object MiningMetrics extends MetricsContainer {
  final private val blockGenTimer = "mining.blocks.generate.timer"
  final val PoWBlockGeneratorTiming: Timer = metrics.timer(blockGenTimer, "class", "PoWBlockGenerator")
  final val RestrictedPoWBlockGeneratorTiming: Timer =
    metrics.timer(blockGenTimer, "class", "RestrictedPoWBlockGenerator")
  final val NoOmmersBlockGeneratorTiming: Timer = metrics.timer(blockGenTimer, "class", "NoOmmersBlockGenerator")

  final val MinedBlockEvaluationTimer: Timer = metrics.timer("mining.minedblocks.evaluation.timer")
}
