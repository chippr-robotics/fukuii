package com.chipprbots.ethereum.consensus.pow.miners

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

/** The full DAG of the internal CPU miner's current epoch (B-413).
  *
  * Only `EthashMiner` asks for it, and only while the built-in miner runs (`mining-enabled`). Header validation and
  * `eth_getWork` for external miners use the light cache (`EthashUtils.hashimotoLight`) and never build a DAG.
  *
  * The DAG is a memory-mapped file in `ethashDir` (`EthashDagFile`): it is generated item by item straight to the file,
  * so the heap holds only the light cache and a 1 MiB write buffer while it is built, and nothing of it afterwards. An
  * existing file for the epoch is reused once its size, magic and sampled items check out; otherwise it is regenerated.
  * On an epoch change the last epoch's mapping is dropped and its file deleted, so at most one DAG file is kept.
  *
  * Used from one thread at a time: the coordinator runs one mining round after another.
  */
class EthashDAGManager(ethashDir: Path, segmentShift: Int = EthashDagFile.DefaultSegmentShift) extends Logger:

  def this(blockCreator: PoWBlockCreator) = this(Paths.get(blockCreator.miningConfig.ethashDir))

  private var current: Option[(Long, MappedDag)] = None

  /** The DAG of `epoch` (of the block `blockNumber`), with its size in bytes. */
  def calculateDagSize(blockNumber: Long, epoch: Long)(implicit
      blockchainConfig: BlockchainConfig
  ): (MappedDag, Long) =
    val dagSize = EthashUtils.dagSize(epoch)
    current match
      case Some((`epoch`, dag)) => (dag, dagSize)
      case _ =>
        val seed = EthashUtils.seed(blockNumber, blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong)
        val dag = dagFor(epoch, seed, (dagSize / EthashUtils.HASH_BYTES).toInt, EthashUtils.makeCache(epoch, seed))
        (dag, dagSize)

  /** Makes `epoch`'s DAG of `numItems` items current: the existing file if it checks out against `cache`, a new one
    * otherwise. `cache` is the epoch's light cache, made at most once.
    */
  private[miners] def dagFor(epoch: Long, seed: ByteString, numItems: Int, cache: => Array[Int]): MappedDag =
    PoWMiningMetrics.recordDagStarted(epoch)
    val path = ethashDir.resolve(EthashDagFile.fileName(seed))
    lazy val lightCache = cache

    val reused =
      if !Files.exists(path) then None
      else
        PoWMiningMetrics.recordDagLoading(0)
        EthashDagFile.open(path, numItems, segmentShift) match
          case Failure(ex) =>
            log.warn("DAG file {} is unusable, regenerating it: {}", path, ex.getMessage)
            None
          case Success(dag) =>
            EthashDagFile.sampleMismatch(dag, lightCache, segmentShift) match
              case None =>
                log.info("Reusing DAG file {} for epoch {} ({} bytes)", path, epoch, dag.sizeBytes)
                Some(dag)
              case Some(index) =>
                log.warn("DAG file {} disagrees with the light cache at item {}, regenerating it", path, index)
                None

    val dag = reused.getOrElse {
      log.info("Generating DAG for epoch {} into {} ({} items)", epoch, path, numItems)
      val started = System.nanoTime()
      var loggedDecile = -1
      EthashDagFile.generate(
        path,
        lightCache,
        numItems,
        percent =>
          if percent / 10 > loggedDecile then
            loggedDecile = percent / 10
            log.info("Generating DAG {}%", percent)
          PoWMiningMetrics.recordDagGenerating(percent)
      )
      log.info("Generated DAG for epoch {} in {} s", epoch, (System.nanoTime() - started) / 1_000_000_000L)
      EthashDagFile.open(path, numItems, segmentShift).get
    }

    val previous = current
    current = Some((epoch, dag))
    previous.foreach { case (oldEpoch, oldDag) =>
      if oldDag.path != dag.path then
        // The mapping goes with the last reference to `oldDag`; on Linux the file's blocks are freed when it does.
        if Files.deleteIfExists(oldDag.path) then
          log.info("Deleted the DAG file of epoch {}: {}", oldEpoch, oldDag.path)
    }
    PoWMiningMetrics.recordDagLoaded()
    dag
