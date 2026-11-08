package com.chipprbots.ethereum.blockchain.sync.regular

import scala.concurrent.duration.NANOSECONDS

import com.google.common.util.concurrent.AtomicDouble
import io.micrometer.core.instrument.Timer

import com.chipprbots.ethereum.metrics.MetricsContainer

object RegularSyncMetrics extends MetricsContainer:
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

  final private val BranchReorgTotalCounter =
    metrics.registry.counter("regularsync.reorg.total")
  final private val BranchResolutionRoundsCounter =
    metrics.registry.counter("regularsync.branch.resolution.rounds.total")
  final private val ReorgLastDepth = new AtomicDouble(0d)
  metrics.registry.gauge("regularsync.reorg.last.depth.gauge", ReorgLastDepth)

  // #1705: fetch-and-import pipelines replaced, tagged with the component whose stop caused it.
  final private val PipelineRestartsCounter = "regularsync.pipeline.restarts.total"

  // H3: SNAP re-syncs regular sync asked for in a row with no block imported in between, and re-SNAPs that waited.
  final private val ConsecutiveResnaps = new AtomicDouble(0d)
  metrics.registry.gauge("regularsync.resnap.consecutive.gauge", ConsecutiveResnaps)
  final private val ResnapBackoffsCounter = metrics.registry.counter("regularsync.resnap.backoff.total")
  // B-007 (#1683): blocks, announced or fetched in a batch, whose parent this node lacks, and what came of them.
  final private val OrphanBlocksCounter = "regularsync.orphan.blocks.total"

  // #1705 (beacon): how long a starting importer has waited for the import lock so far; 0 while none waits.
  final private val ImportLockWaitSeconds = new AtomicDouble(0d)
  metrics.registry.gauge("regularsync.import.lock.wait.seconds.gauge", ImportLockWaitSeconds)

  def recordMinedBlockPropagationTimer(nanos: Long): Unit = MinedBlockPropagationTimer.record(nanos, NANOSECONDS)
  def recordImportNewBlockPropagationTimer(nanos: Long): Unit = NewBlockPropagationTimer.record(nanos, NANOSECONDS)
  def recordDefaultBlockPropagationTimer(nanos: Long): Unit = DefaultBlockPropagationTimer.record(nanos, NANOSECONDS)

  def setCurrentBlock(blockNumber: BigInt): Unit = CurrentBlockGauge.set(blockNumber.toDouble)
  def setBestKnownNetworkBlock(blockNumber: BigInt): Unit = BestKnownNetworkBlockGauge.set(blockNumber.toDouble)
  def incrementBlocksImported(): Unit = BlocksImportedCounter.increment()
  def incrementReorgTotal(): Unit = BranchReorgTotalCounter.increment()
  def incrementBranchResolutionRounds(): Unit = BranchResolutionRoundsCounter.increment()
  def setLastReorgDepth(n: Int): Unit = ReorgLastDepth.set(n.toDouble)

  /** `component`: "block-fetcher" or "block-importer". */
  def incrementPipelineRestarts(component: String): Unit =
    metrics.registry.counter(PipelineRestartsCounter, "component", component).increment()

  /** `source`: "announce" (a NewBlock) or "batch" (fetched). `outcome`: "branch-fetch" (the fetcher goes back for the
    * parent, no one blamed) or "blacklisted" (the peer kept announcing blocks whose parent never arrived).
    */
  def incrementOrphanBlocks(source: String, outcome: String): Unit =
    metrics.registry.counter(OrphanBlocksCounter, "source", source, "outcome", outcome).increment()

  def setImportLockWaitSeconds(seconds: Double): Unit = ImportLockWaitSeconds.set(seconds)

  def setConsecutiveResnaps(count: Int): Unit = ConsecutiveResnaps.set(count.toDouble)
  def incrementResnapBackoffs(): Unit = ResnapBackoffsCounter.increment()
  private[sync] def consecutiveResnaps: Double = ConsecutiveResnaps.get
  private[regular] def importLockWaitSeconds: Double = ImportLockWaitSeconds.get
