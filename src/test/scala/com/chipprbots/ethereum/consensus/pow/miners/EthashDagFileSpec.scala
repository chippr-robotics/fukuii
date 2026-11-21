package com.chipprbots.ethereum.consensus.pow.miners

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.Comparator
import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.ByteUtils

/** B-413: the memory-mapped DAG file.
  *
  * The DAGs here are short (a few thousand items of the epoch 0 dataset), so `fullSize` is the short DAG's size: the
  * hashimoto loop then reads only items the file has, and the heap DAG, the mapped DAG and the light cache must give
  * the same proof of work for it. `DAGGenerationSpec` ties `hashimotoLight` to Mordor's block 100.
  */
class EthashDagFileSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val ecip1099Block: Long = 2_520_000L
  private val seed0: ByteString = EthashUtils.seed(0L, ecip1099Block)
  private lazy val cache0: Array[Int] = EthashUtils.makeCache(0L, seed0)

  // Even, so that fullSize is a whole number of 128-byte pages, and not a multiple of the 16-item test segments.
  private val numItems: Int = 4090
  private val fullSize: Long = numItems.toLong * EthashUtils.HASH_BYTES
  private val smallShift: Int = 4

  private val dirs = scala.collection.mutable.ListBuffer.empty[Path]
  private def tempDir(): Path =
    val d = Files.createTempDirectory("ethash-dag-spec")
    dirs += d
    d

  override def afterAll(): Unit =
    dirs.foreach { d =>
      if Files.exists(d) then
        Files.walk(d).sorted(Comparator.reverseOrder()).iterator.asScala.foreach(Files.deleteIfExists)
    }

  private lazy val heapDag: Array[Array[Int]] = Array.tabulate(numItems)(EthashUtils.calcDatasetItem(cache0, _))

  private lazy val sharedFile: Path =
    val p = tempDir().resolve(EthashDagFile.fileName(seed0))
    EthashDagFile.generate(p, cache0, numItems, _ => ())
    p

  private def headerHash(k: Int): Array[Byte] = kec256(BigInt(k).toByteArray)
  private def nonce(k: Int): Array[Byte] = java.nio.ByteBuffer.allocate(8).putLong(0x9e3779b97f4a7c15L * k).array()

  "EthashDagFile" should "write the magic, then each item's 16 words little-endian" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val bytes = Files.readAllBytes(sharedFile)
    bytes.length.toLong shouldBe EthashDagFile.expectedFileSize(numItems)
    ByteString(bytes.take(8)) shouldBe ByteString(Array(0xfe, 0xca, 0xdd, 0xba, 0xad, 0xde, 0xe1, 0xfe).map(_.toByte))
    val expected = heapDag.flatMap(item => ByteUtils.intsToBytes(item, bigEndian = false))
    ByteString(bytes.drop(8)) shouldBe ByteString(expected)
  }

  it should "map every item to exactly what calcDatasetItem gives, in one segment and in many" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    for shift <- Seq(EthashDagFile.DefaultSegmentShift, smallShift) do
      val dag = EthashDagFile.open(sharedFile, numItems, shift).get
      (0 until numItems).foreach(i => dag.item(i) shouldEqual heapDag(i))
  }

  it should "give hashimoto byte-identical to the heap DAG and to hashimotoLight" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val mapped = EthashDagFile.open(sharedFile, numItems).get
    val segmented = EthashDagFile.open(sharedFile, numItems, smallShift).get
    (1 to 24).foreach { k =>
      val (h, n) = (headerHash(k), nonce(k))
      val heap = EthashUtils.hashimoto(h, n, fullSize, heapDag.apply)
      val light = EthashUtils.hashimotoLight(h, n, fullSize, cache0)
      EthashUtils.hashimoto(h, n, fullSize, mapped.item) shouldBe heap
      EthashUtils.hashimoto(h, n, fullSize, segmented.item) shouldBe heap
      heap shouldBe light
    }
  }

  it should "sample both sides of every segment boundary" taggedAs UnitTest in {
    val idx = EthashDagFile.sampleIndices(numItems, smallShift, new java.util.Random(1))
    (idx should contain).allOf(0, numItems - 1, 15, 16, 4079, 4080)
    all(idx) should ((be >= 0).and(be < numItems))
  }

  it should "leave no file behind when generation fails" taggedAs UnitTest in {
    val dir = tempDir()
    val p = dir.resolve("full-R23-0000000000000000")
    // An empty cache makes calcDatasetItem divide by zero on the first item.
    an[ArithmeticException] should be thrownBy EthashDagFile.generate(p, Array.empty[Int], numItems, _ => ())
    Files.list(dir).iterator.asScala.toList shouldBe empty
  }

  it should "remove the temporary file of a dead generation, not of a live one" taggedAs UnitTest in {
    val dir = tempDir()
    val p = dir.resolve("full-R23-0000000000000000")
    val dead = dir.resolve(s"${p.getFileName}.${Long.MaxValue}.tmp")
    val live = dir.resolve(s"${p.getFileName}.${ProcessHandle.current().parent().get().pid()}.tmp")
    Files.write(dead, Array[Byte](1))
    Files.write(live, Array[Byte](1))
    EthashDagFile.generate(p, cache0, 2, _ => ())
    Files.exists(dead) shouldBe false
    Files.exists(live) shouldBe true
    Files.size(p) shouldBe EthashDagFile.expectedFileSize(2)
  }

  "EthashDAGManager" should "generate a missing DAG file and map it" taggedAs (UnitTest, ConsensusTest) in {
    val dir = tempDir()
    val dag = new EthashDAGManager(dir, smallShift).dagFor(0L, seed0, numItems, cache0)
    Files.size(dir.resolve(EthashDagFile.fileName(seed0))) shouldBe EthashDagFile.expectedFileSize(numItems)
    (0 until numItems by 97).foreach(i => dag.item(i) shouldEqual heapDag(i))
  }

  /** A file stamped with a past time keeps it only if nothing rewrote it: generation renames a new file onto it. */
  private val stamp = FileTime.from(1_000_000L, TimeUnit.SECONDS)
  private def preparedFile(): (Path, Path) =
    val dir = tempDir()
    val p = dir.resolve(EthashDagFile.fileName(seed0))
    Files.copy(sharedFile, p)
    Files.setLastModifiedTime(p, stamp)
    (dir, p)

  it should "reuse a valid DAG file without regenerating it" taggedAs (UnitTest, ConsensusTest) in {
    val (dir, p) = preparedFile()
    val dag = new EthashDAGManager(dir, smallShift).dagFor(0L, seed0, numItems, cache0)
    Files.getLastModifiedTime(p) shouldBe stamp
    dag.item(numItems - 1) shouldEqual heapDag(numItems - 1)
  }

  private def regenerates(corrupt: Path => Unit): Unit =
    val (dir, p) = preparedFile()
    corrupt(p)
    Files.setLastModifiedTime(p, stamp)
    val dag = new EthashDAGManager(dir, smallShift).dagFor(0L, seed0, numItems, cache0)
    Files.getLastModifiedTime(p) should not be stamp
    Files.readAllBytes(p) shouldEqual Files.readAllBytes(sharedFile)
    (0 until numItems).foreach(i => dag.item(i) shouldEqual heapDag(i))

  private def overwrite(p: Path, at: Long, bytes: Array[Byte]): Unit =
    val f = new RandomAccessFile(p.toFile, "rw")
    try
      f.seek(at)
      f.write(bytes)
    finally f.close()

  it should "regenerate a truncated DAG file" taggedAs (UnitTest, ConsensusTest) in {
    regenerates { p =>
      val f = new RandomAccessFile(p.toFile, "rw")
      try f.setLength(Files.size(p) - EthashDagFile.ItemBytes)
      finally f.close()
    }
  }

  it should "regenerate a DAG file with a bad magic" taggedAs (UnitTest, ConsensusTest) in {
    regenerates(p => overwrite(p, 0, Array[Byte](0)))
  }

  it should "regenerate a DAG file whose sampled item is corrupt" taggedAs (UnitTest, ConsensusTest) in {
    // Item 16 sits on a segment boundary, which the sampled check always reads.
    regenerates(p => overwrite(p, EthashDagFile.HeaderBytes + 16L * EthashDagFile.ItemBytes + 5, Array[Byte](0x5a)))
  }

  it should "keep the same DAG for the same epoch and delete the last epoch's file on an epoch change" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val dir = tempDir()
    val manager = new EthashDAGManager(dir, smallShift)
    val first = manager.dagFor(0L, seed0, numItems, cache0)
    // A different seed names a different file. The cache is epoch 0's: this checks the files, not the dataset.
    val seed1 = ByteString(kec256(seed0.toArray[Byte]))
    manager.dagFor(1L, seed1, numItems, cache0)
    Files.exists(first.path) shouldBe false
    Files.list(dir).iterator.asScala.map(_.getFileName.toString).toList shouldBe List(EthashDagFile.fileName(seed1))
  }

  it should "build the DAG within a heap far smaller than the DAG" taggedAs (UnitTest, SlowTest) in {
    // A child JVM capped at 32 MiB generates a 128 MiB DAG, maps it and checks samples. The control holds the same DAG
    // on the heap, as the manager did before B-413, and must run out of memory under the same cap.
    val dir = tempDir()
    def run(mode: String): (Int, String) =
      val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
      val pb = new ProcessBuilder(
        java,
        "-Xmx32m",
        "-cp",
        System.getProperty("java.class.path"),
        "com.chipprbots.ethereum.consensus.pow.miners.DagHeapProbe",
        mode,
        dir.resolve(s"probe-$mode").toString,
        DagHeapProbe.Items.toString
      ).redirectErrorStream(true)
      val proc = pb.start()
      val out = new String(proc.getInputStream.readAllBytes())
      (proc.waitFor(), out)

    val (mappedExit, mappedOut) = run("mapped")
    withClue(mappedOut)(mappedExit shouldBe 0)
    mappedOut should include("OK")
    Files.size(dir.resolve("probe-mapped")) shouldBe EthashDagFile.expectedFileSize(DagHeapProbe.Items)

    val (heapExit, heapOut) = run("heap")
    withClue(heapOut)(heapExit should not be 0)
    heapOut should include("OutOfMemoryError")
  }

/** The child JVM of the heap test: `mapped <file> <items>` generates and maps a DAG, `heap <file> <items>` holds one on
  * the heap. The cache is a synthetic 1024-item one: the heap a DAG item costs does not depend on the cache.
  */
object DagHeapProbe:
  // 2^21 items of 64 bytes: a 128 MiB DAG, four times the child's heap.
  val Items: Int = 1 << 21

  def main(args: Array[String]): Unit =
    val Array(mode, file, items) = args: @unchecked
    val n = items.toInt
    val cache = Array.tabulate(1024 * EthashDagFile.ItemWords)(i => i * 0x01000193)
    mode match
      case "mapped" =>
        val path = Paths.get(file)
        EthashDagFile.generate(path, cache, n, _ => ())
        val dag = EthashDagFile.open(path, n).get
        EthashDagFile.sampleMismatch(dag, cache) match
          case None    => println(s"OK max-heap=${Runtime.getRuntime.maxMemory()}")
          case Some(i) => println(s"MISMATCH $i"); sys.exit(2)
      case _ =>
        val dag = Array.tabulate(n)(EthashUtils.calcDatasetItem(cache, _))
        println(s"held ${dag.length} items")
