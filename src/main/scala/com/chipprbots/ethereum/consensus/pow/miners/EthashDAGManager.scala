package com.chipprbots.ethereum.consensus.pow.miners

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.consensus.pow.miners.EthashMiner.DagFilePrefix
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger

class EthashDAGManager(blockCreator: PoWBlockCreator) extends Logger:
  var currentEpoch: Option[Long] = None
  var currentEpochDagSize: Option[Long] = None
  var currentEpochDag: Option[Array[Array[Int]]] = None

  def calculateDagSize(blockNumber: Long, epoch: Long)(implicit
      blockchainConfig: BlockchainConfig
  ): (Array[Array[Int]], Long) =
    (currentEpoch, currentEpochDag, currentEpochDagSize) match
      case (Some(`epoch`), Some(dag), Some(dagSize)) => (dag, dagSize)
      case _ =>
        PoWMiningMetrics.recordDagStarted(epoch)
        val seed =
          EthashUtils.seed(blockNumber, blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong)
        val dagSize = EthashUtils.dagSize(epoch)
        val dagNumHashes = (dagSize / EthashUtils.HASH_BYTES).toInt
        val dag =
          if !dagFile(seed).exists() then generateDagAndSaveToFile(epoch, dagNumHashes, seed)
          else
            val res = loadDagFromFile(seed, dagNumHashes)
            res.failed.foreach { ex =>
              log.error("Cannot read DAG from file", ex)
            }
            res.getOrElse(generateDagAndSaveToFile(epoch, dagNumHashes, seed))
        currentEpoch = Some(epoch)
        currentEpochDag = Some(dag)
        currentEpochDagSize = Some(dagSize)
        PoWMiningMetrics.recordDagLoaded()
        (dag, dagSize)

  private def dagFile(seed: ByteString): File =
    new File(
      s"${blockCreator.miningConfig.ethashDir}/full-R${EthashUtils.Revision}-${Hex
          .toHexString(seed.take(8).toArray[Byte])}"
    )

  private def generateDagAndSaveToFile(epoch: Long, dagNumHashes: Int, seed: ByteString): Array[Array[Int]] =
    val file = dagFile(seed)
    if file.exists() then file.delete()
    file.getParentFile.mkdirs()
    file.createNewFile()

    val res = new Array[Array[Int]](dagNumHashes)

    val written = Using(new FileOutputStream(dagFile(seed).getAbsolutePath)) { outputStream =>
      outputStream.write(DagFilePrefix.toArray[Byte])

      val cache = EthashUtils.makeCache(epoch, seed)

      (0 until dagNumHashes).foreach { i =>
        val item = EthashUtils.calcDatasetItem(cache, i)
        outputStream.write(ByteUtils.intsToBytes(item, bigEndian = false))
        res(i) = item

        if i % 100000 == 0 then
          log.info(s"Generating DAG ${((i / dagNumHashes.toDouble) * 100).toInt}%")
          PoWMiningMetrics.recordDagGenerating(((i / dagNumHashes.toDouble) * 100).toInt)
      }
    }

    written match
      case Success(_)  => res
      case Failure(ex) =>
        // Delete the partial/corrupt DAG file so the next run regenerates from scratch.
        log.error("Failed to generate DAG file, removing partial output", ex)
        if file.exists() then file.delete()
        throw ex

  private def loadDagFromFile(seed: ByteString, dagNumHashes: Int): Try[Array[Array[Int]]] =
    Using(new FileInputStream(dagFile(seed).getAbsolutePath)) { inputStream =>
      val prefix = new Array[Byte](8)
      if inputStream.read(prefix) != 8 || ByteString(prefix) != DagFilePrefix then
        Failure(new RuntimeException("Invalid DAG file prefix"))
      else
        val buffer = new Array[Byte](64) // scalastyle:ignore magic.number
        val res = new Array[Array[Int]](dagNumHashes)
        var index = 0

        while inputStream.read(buffer) > 0 do
          if index % 100000 == 0 then
            log.info(s"Loading DAG from file ${((index / res.length.toDouble) * 100).toInt}%")
            PoWMiningMetrics.recordDagLoading(((index / res.length.toDouble) * 100).toInt)
          res(index) = ByteUtils.bytesToInts(buffer, bigEndian = false)
          index += 1

        if index == dagNumHashes then Success(res)
        else Failure(new RuntimeException("DAG file ended unexpectedly"))
    }.flatten
