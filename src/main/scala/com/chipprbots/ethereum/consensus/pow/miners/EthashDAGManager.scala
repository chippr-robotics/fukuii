package com.chipprbots.ethereum.consensus.pow.miners

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Try

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.miners.EthashMiner.DagFilePrefix
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger

/** Manages Ethash DAG lifecycle: generation, file persistence, and memory-mapped access.
  *
  * DAG files are memory-mapped via MappedByteBuffer (matching go-ethereum's mmap approach)
  * rather than loaded into JVM heap. This eliminates ~40M GC-tracked objects for a 2.4GB DAG,
  * reduces heap pressure by ~2.4GB, and enables instant startup (demand-paged by kernel).
  *
  * Thread safety: JDK 21 absolute-position bulk reads on MappedByteBuffer are thread-safe
  * for concurrent mining threads.
  *
  * ECIP-1099 compatible: DAG files are keyed by seed hash (not epoch number), so the
  * epoch length change (30K→60K) is transparent to this class.
  */
class EthashDAGManager(blockCreator: PoWBlockCreator) extends Logger {
  var currentEpoch: Option[Long] = None
  var currentEpochDagSize: Option[Long] = None
  private var currentDagLookup: Option[Int => Array[Int]] = None
  private var currentDagBuffer: Option[MappedByteBuffer] = None
  private var currentDagChannel: Option[FileChannel] = None

  def calculateDagSize(blockNumber: Long, epoch: Long)(implicit
      blockchainConfig: BlockchainConfig
  ): (Int => Array[Int], Long) =
    (currentEpoch, currentDagLookup, currentEpochDagSize) match {
      case (Some(`epoch`), Some(lookup), Some(dagSize)) => (lookup, dagSize)
      case _ =>
        val seed =
          EthashUtils.seed(blockNumber, blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong)
        val dagSize = EthashUtils.dagSize(epoch)
        val dagNumHashes = (dagSize / EthashUtils.HASH_BYTES).toInt
        val buffer =
          if (!dagFile(seed).exists()) generateDagAndMmap(epoch, dagNumHashes, seed)
          else {
            val res = mmapDagFile(seed, dagNumHashes)
            res.failed.foreach { ex =>
              log.error("Cannot mmap DAG file", ex)
            }
            res.getOrElse(generateDagAndMmap(epoch, dagNumHashes, seed))
          }
        val lookup = dagLookup(buffer)
        closePreviousDag()
        currentEpoch = Some(epoch)
        currentDagLookup = Some(lookup)
        currentEpochDagSize = Some(dagSize)
        currentDagBuffer = Some(buffer)
        (lookup, dagSize)
    }

  private def dagFile(seed: ByteString): File =
    new File(
      s"${blockCreator.miningConfig.ethashDir}/full-R${EthashUtils.Revision}-${Hex
          .toHexString(seed.take(8).toArray[Byte])}"
    )

  /** Generate DAG to file, then memory-map it. The array is only held transiently during generation. */
  private def generateDagAndMmap(epoch: Long, dagNumHashes: Int, seed: ByteString): MappedByteBuffer = {
    val file = dagFile(seed)
    if (file.exists()) file.delete()
    file.getParentFile.mkdirs()
    file.createNewFile()

    val outputStream = new FileOutputStream(file.getAbsolutePath)
    outputStream.write(DagFilePrefix.toArray[Byte])

    val cache = EthashUtils.makeCache(epoch, seed)

    (0 until dagNumHashes).foreach { i =>
      val item = EthashUtils.calcDatasetItem(cache, i)
      outputStream.write(ByteUtils.intsToBytes(item, bigEndian = false))

      if (i % 100000 == 0) log.info(s"Generating DAG ${((i / dagNumHashes.toDouble) * 100).toInt}%")
    }

    Try(outputStream.close())

    // Memory-map the generated file
    mmapDagFile(seed, dagNumHashes).get
  }

  /** Memory-map a DAG file, skipping the 8-byte prefix. */
  private def mmapDagFile(seed: ByteString, dagNumHashes: Int): Try[MappedByteBuffer] = {
    val file = dagFile(seed)
    val expectedDataSize = dagNumHashes.toLong * EthashUtils.HASH_BYTES
    val expectedFileSize = 8L + expectedDataSize // 8-byte prefix + data

    if (file.length() != expectedFileSize) {
      Failure(new RuntimeException(
        s"DAG file size mismatch: expected $expectedFileSize, got ${file.length()}"
      ))
    } else {
      Try {
        // Validate prefix
        val fis = new FileInputStream(file)
        val prefix = new Array[Byte](8)
        fis.read(prefix)
        fis.close()
        if (ByteString(prefix) != DagFilePrefix)
          throw new RuntimeException("Invalid DAG file prefix")

        // Memory-map the data portion (skip 8-byte prefix)
        val raf = new RandomAccessFile(file, "r")
        val channel = raf.getChannel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 8, expectedDataSize)
        currentDagChannel = Some(channel)
        log.info(s"Memory-mapped DAG file: ${file.getName} (${expectedDataSize / (1024 * 1024)} MB)")
        buffer
      }
    }
  }

  /** Create a thread-safe lookup function from a MappedByteBuffer.
    * Each call reads 64 bytes at the given index and converts to Array[Int].
    * Uses thread-local byte arrays to avoid allocation on the hot path.
    */
  private def dagLookup(buffer: MappedByteBuffer): Int => Array[Int] = {
    val threadLocalBuffer = ThreadLocal.withInitial[Array[Byte]](() => new Array[Byte](64))

    (index: Int) => {
      val bytes = threadLocalBuffer.get()
      val offset = index.toLong * 64
      // Absolute bulk get — reads from buffer position without affecting buffer state.
      // Thread-safe: each thread uses its own byte array, and MappedByteBuffer
      // absolute reads don't modify buffer position.
      buffer.slice(offset.toInt, 64).get(bytes)
      ByteUtils.bytesToInts(bytes, bigEndian = false)
    }
  }

  /** Close the previous epoch's file channel when switching epochs. */
  private def closePreviousDag(): Unit = {
    currentDagChannel.foreach { ch =>
      Try(ch.close())
    }
    currentDagChannel = None
    currentDagBuffer = None
    currentDagLookup = None
  }
}
