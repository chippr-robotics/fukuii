package com.chipprbots.ethereum.consensus.pow.miners

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.pow.EthashUtils

/** The full Ethash dataset (the DAG) of one epoch, read from a memory-mapped file (B-413).
  *
  * The DAG is off the Java heap: the file's pages live in the OS page cache, so the heap holds the light cache while
  * the DAG is generated and nothing of the DAG afterwards. A DAG of 3.27 GiB (Mordor, ECIP-1099 epoch 290) needs about
  * that much free RAM for the page cache to mine at full speed, and none of it on the heap.
  *
  * A single `MappedByteBuffer` holds at most 2 GiB, so the file is mapped in segments of `2^segmentShift` items (1 GiB
  * at the default shift of 24). A segment starts on an item boundary, so no item straddles two segments.
  *
  * Reads are absolute (`IntBuffer.get(index, dst, ...)`), which leave the buffer's position alone, so any number of
  * threads may read one `MappedDag`. The mapping does not depend on the channel that made it: the channel is closed
  * once the file is mapped, and the mapping is released when the `MappedDag` is no longer referenced and is collected.
  */
final class MappedDag private[miners] (
    val path: Path,
    val numItems: Int,
    segmentShift: Int,
    segments: Array[IntBuffer]
):
  private val segmentMask: Int = (1 << segmentShift) - 1

  /** The dataset item at `index`: its 16 little-endian uint32 words, as `EthashUtils.calcDatasetItem` returns it. */
  def item(index: Int): Array[Int] =
    val out = new Array[Int](EthashDagFile.ItemWords)
    segments(index >>> segmentShift).get(
      (index & segmentMask) * EthashDagFile.ItemWords,
      out,
      0,
      EthashDagFile.ItemWords
    )
    out

  /** The DAG's size in bytes: `EthashUtils.dagSize(epoch)` for a full DAG. */
  def sizeBytes: Long = numItems.toLong * EthashDagFile.ItemBytes

/** The on-disk layout of a DAG file, unchanged from the heap DAG's file and the same as geth's/ethash's `full-R23-*`
  * dump:
  *
  *   - bytes 0..7: the magic `fe ca dd ba ad de e1 fe`, geth's `dumpMagic` (0xbaddcafe, 0xfee1dead) written as
  *     little-endian uint32 words;
  *   - then item 0, item 1, ... item n-1, each 64 bytes: the item's 16 uint32 words, little-endian.
  *
  * So a file is exactly `8 + 64 * n` bytes, and item `i`'s word `w` is the little-endian uint32 at `8 + 64 i + 4 w`.
  * The name is `full-R23-<first 8 bytes of the seed, hex>`, as geth names a little-endian DAG.
  */
object EthashDagFile:

  // scalastyle:off magic.number
  final val HeaderBytes: Int = 8
  final val ItemBytes: Int = EthashUtils.HASH_BYTES
  final val ItemWords: Int = ItemBytes / EthashUtils.WORD_BYTES

  /** 2^24 items of 64 bytes: 1 GiB per mapped segment, under the 2 GiB limit of one `MappedByteBuffer`. */
  final val DefaultSegmentShift: Int = 24

  /** Items per write while generating: 1 MiB of write buffer, the only DAG bytes ever on the heap. */
  final private val WriteBatchItems: Int = 16384

  /** Items checked against the light cache when an existing file is opened: fixed positions plus random ones. */
  final private val FixedSamples: Int = 8
  final private val RandomSamples: Int = 8

  /** How often, in items, generation reports progress. */
  final val ProgressEvery: Int = 100000
  // scalastyle:on magic.number

  final val Magic: ByteString = EthashMiner.DagFilePrefix

  def fileName(seed: ByteString): String =
    s"full-R${EthashUtils.Revision}-${Hex.toHexString(seed.take(8).toArray[Byte])}"

  def expectedFileSize(numItems: Int): Long = HeaderBytes.toLong + numItems.toLong * ItemBytes

  /** Generates the DAG's `numItems` items from `cache`, item by item, into `path`.
    *
    * The items go to a temporary file beside `path`, which is forced to disk and then renamed onto `path` in one atomic
    * move, so a crash mid-generation never leaves a file at `path` that looks complete. On a failure the temporary file
    * is deleted and the failure is thrown. `onProgress` is called with the percent done every `ProgressEvery` items.
    */
  def generate(path: Path, cache: Array[Int], numItems: Int, onProgress: Int => Unit): Unit =
    Files.createDirectories(path.toAbsolutePath.getParent)
    removeDeadTempFiles(path)
    val tmp = path.resolveSibling(s"${path.getFileName}.${ProcessHandle.current().pid()}.tmp")
    try
      Using.resource(
        FileChannel.open(
          tmp,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
      ) { channel =>
        val buf = ByteBuffer.allocate(WriteBatchItems * ItemBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(Magic.toArray[Byte])
        var i = 0
        while i < numItems do
          if i % ProgressEvery == 0 then onProgress(((i / numItems.toDouble) * 100).toInt)
          if buf.remaining() < ItemBytes then drain(channel, buf)
          val item = EthashUtils.calcDatasetItem(cache, i)
          var w = 0
          while w < ItemWords do
            buf.putInt(item(w))
            w += 1
          i += 1
        drain(channel, buf)
        channel.force(true)
      }
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      ()
    catch
      case ex: Throwable =>
        Files.deleteIfExists(tmp)
        throw ex

  private def drain(channel: FileChannel, buf: ByteBuffer): Unit =
    buf.flip()
    while buf.hasRemaining do channel.write(buf)
    buf.clear()

  /** Deletes the temporary files of generations of `path` whose process is gone (a crash or a kill mid-generation). A
    * live process's temporary file is left alone: two nodes may share an ethash directory.
    */
  private def removeDeadTempFiles(path: Path): Unit =
    val dir = path.toAbsolutePath.getParent
    val prefix = s"${path.getFileName}."
    Using.resource(Files.list(dir)) { files =>
      files.iterator.asScala
        .filter { f =>
          val n = f.getFileName.toString
          n.startsWith(prefix) && n.endsWith(".tmp")
        }
        .foreach { f =>
          val pidPart = f.getFileName.toString.stripPrefix(prefix).stripSuffix(".tmp")
          val alive = pidPart.toLongOption.exists(pid => ProcessHandle.of(pid).isPresent)
          if !alive then Files.deleteIfExists(f)
        }
    }

  /** Maps the DAG file at `path`, read-only, after checking its size and its magic. It does not check the items: see
    * `sampleMismatch`.
    */
  def open(path: Path, numItems: Int, segmentShift: Int = DefaultSegmentShift): Try[MappedDag] =
    Try {
      Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { channel =>
        val size = channel.size()
        val expected = expectedFileSize(numItems)
        if size != expected then
          throw new DagFileInvalid(s"DAG file $path is $size bytes, expected $expected for $numItems items")
        val header = ByteBuffer.allocate(HeaderBytes)
        while header.hasRemaining && channel.read(header, header.position().toLong) >= 0 do ()
        if ByteString(header.array()) != Magic then throw new DagFileInvalid(s"DAG file $path has a bad magic")

        val segmentItems = 1 << segmentShift
        val numSegments = ((numItems.toLong + segmentItems - 1) / segmentItems).toInt
        val segments = Array.tabulate(numSegments) { s =>
          val first = s.toLong * segmentItems
          val items = math.min(segmentItems.toLong, numItems - first)
          channel
            .map(FileChannel.MapMode.READ_ONLY, HeaderBytes + first * ItemBytes, items * ItemBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
        }
        new MappedDag(path, numItems, segmentShift, segments)
      }
    }

  /** The indices the sampled check reads: the first and last items, items spread evenly between them, the items on
    * either side of every segment boundary, and `RandomSamples` random ones.
    */
  private[miners] def sampleIndices(numItems: Int, segmentShift: Int, random: java.util.Random): Seq[Int] =
    val spread = (0 to FixedSamples).map(k => ((numItems.toLong - 1) * k / FixedSamples).toInt)
    val segmentItems = 1 << segmentShift
    val boundaries = (segmentItems until numItems by segmentItems).flatMap(b => Seq(b - 1, b))
    val randoms = Seq.fill(RandomSamples)(random.nextInt(numItems))
    (spread ++ boundaries ++ randoms).distinct

  /** Compares sampled items of `dag` with the items `cache` gives for them. `None` when they all match, or the first
    * index that does not. A truncated or corrupt file fails here or in `open`, and is then regenerated.
    */
  def sampleMismatch(
      dag: MappedDag,
      cache: Array[Int],
      segmentShift: Int = DefaultSegmentShift,
      random: java.util.Random = new java.util.Random()
  ): Option[Int] =
    sampleIndices(dag.numItems, segmentShift, random).find { i =>
      !java.util.Arrays.equals(dag.item(i), EthashUtils.calcDatasetItem(cache, i))
    }

  final class DagFileInvalid(msg: String) extends RuntimeException(msg)
