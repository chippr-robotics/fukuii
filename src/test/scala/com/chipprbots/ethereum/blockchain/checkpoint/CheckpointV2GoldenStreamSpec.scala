package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.AncestorWindow
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** The golden streams of format version 2 (spec 018 T227, data-model E.1 "Golden streams"), pinned byte for byte in
  * `src/test/resources/checkpoint/v2-golden-{a,b,c}.hex`. The format is a one-way door: a change to any of these bytes
  * is a change to the format, which forge and beacon sign, and not a fixture to regenerate.
  *
  * A file that is missing is written to `target/checkpoint-golden/` and the case fails, so a stream is pinned by hand.
  */
class CheckpointV2GoldenStreamSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*
  import CheckpointV2GoldenStreamSpec.*

  "Golden stream A (ETC-shaped)" should {

    "be what the exporter writes for its state, byte for byte" taggedAs UnitTest in {
      val (chain, tip) = streamAChain()
      val bytes = chain.exportBytes(chain.exporter(61), tip.number.value).value
      bytes shouldBe pinned("a", bytes)
    }

    "decode as the format, with what E.1 says it carries" taggedAs UnitTest in {
      val (chain, tip) = streamAChain()
      val bytes = golden("a")
      val (header, entries) = decode(bytes)
      header.chainId shouldBe 61L
      header.tdExact shouldBe true
      header.genesisHash shouldBe chain.reader.genesisHeader.hash.value
      header.blockHeader shouldBe tip
      header.totalDifficulty shouldBe (0 to 3).map(i => BigInt(131072) + i * 1000).sum

      val window = entries.collect { case w: CheckpointArchive.WindowHeaderEntry => w.header }
      window.map(_.number.value) shouldBe Seq(2, 1, 0)
      withClue("the window chains by parent hash, down to the genesis: ") {
        (tip +: window).sliding(2).foreach { case Seq(child, parent) => child.parentHash shouldBe parent.hash }
        window.last.hash.value shouldBe header.genesisHash
      }
      val bodies = entries.collect { case b: CheckpointArchive.WindowBodyEntry => b }
      withClue("one body, of the one block of C..C-4 whose header names ommers: ") {
        bodies.map(_.blockHash) shouldBe Seq(window.head.hash.value)
        (tip +: window).take(AncestorWindow.OmmerBodies).filter(AncestorWindow.bodyRead) shouldBe Seq(window.head)
        bodies.head.body.uncleNodesList shouldBe Seq(ommerAt(2))
      }

      val nodes = entries.collect { case n: CheckpointArchive.NodeEntry => n.rlp }
      val codes = entries.collect { case c: CheckpointArchive.CodeEntry => ByteString(c.code) }
      codes shouldBe Seq(Code)

      val stateRoot = header.blockHeader.stateRoot.value
      val load: Array[Byte] => Array[Byte] = h => nodes.find(n => crypto.kec256(n).sameElements(h)).get
      val positions = replay(stateRoot, nodes)
      positions shouldBe RawTrieWalk.statePositions(stateRoot.toArray, load)
      val owners = positions.keySet.flatten
      withClue("a contract's storage owner is its account key, keccak of its address, and not keccak of that: ") {
        owners shouldBe Set("11", "22").map(a => ByteString(crypto.kec256(Hex.decode(a * 20))))
      }
      val rootAt = ByteString(HexPrefix.encode(Array.emptyByteArray, isLeaf = false))
      val storageRoots = owners.toSeq.map(o => positions(Some(o))(rootAt))
      withClue("the storage trie both contracts hold is written under each of them: ") {
        storageRoots.distinct.size shouldBe 1
        nodes.count(n => ByteString(n) == storageRoots.head) shouldBe 2
      }
      withClue("the leaves of the two slots that share 8 nibbles are inside their branch, and are no entry: ") {
        nodes.exists(_.length < 32) shouldBe false
        owners.foreach(o => positions(Some(o)).size shouldBe 2) // the extension and the branch
        val branch = positions(Some(owners.head)).values.find(_ != storageRoots.head).get
        rlp.rawDecode(branch.toArray) match
          case RLPList(items*) =>
            items.count { case l: RLPList => rlp.encode(l).length == 31; case _ => false } shouldBe 2
          case other => fail(s"not a branch: $other")
      }
      CheckpointArchive.archiveIdOf(header) shouldBe ByteString(crypto.kec256(bytes.take(headerBlockLength(header))))
    }
  }

  "Golden stream B (ETH-shaped)" should {

    "be what the exporter writes for its state, byte for byte" taggedAs UnitTest in {
      val (chain, tip) = streamBChain()
      val exporter = chain.exporter(StreamBChainId, terminalTotalDifficulty = Some(0), finalized = Some(2))
      val bytes = chain.exportBytes(exporter, tip.number.value).value
      bytes shouldBe pinned("b", bytes)
    }

    "decode as the format: a post-Cancun header, a chain id wider than 32 bits, a window of headers only" taggedAs UnitTest in {
      val (_, tip) = streamBChain()
      val (header, entries) = decode(golden("b"))
      header.chainIdUnsigned shouldBe StreamBChainId
      header.chainIdUnsigned should be > BigInt(0xffffffffL)
      header.blockHeader shouldBe tip
      header.blockHeader.withdrawalsRoot shouldBe defined
      header.blockHeader.blobGasUsed shouldBe defined
      header.blockHeader.excessBlobGas shouldBe defined
      header.blockHeader.parentBeaconBlockRoot shouldBe defined
      header.totalDifficulty shouldBe 0
      header.tdExact shouldBe false
      entries.collect { case w: CheckpointArchive.WindowHeaderEntry => w.header.number.value } shouldBe Seq(1, 0)
      entries.collect { case b: CheckpointArchive.WindowBodyEntry => b } shouldBe empty
      entries.collect { case n: CheckpointArchive.NodeEntry => n }.size shouldBe 1
      entries.collect { case c: CheckpointArchive.CodeEntry => c } shouldBe empty
    }
  }

  "Golden stream C (stream A with one window header's parentHash wrong)" should {

    "be stream A with block 1's parent hash replaced, byte for byte" taggedAs UnitTest in {
      val bytes = streamC(golden("a"))
      bytes shouldBe pinned("c", bytes)
    }

    // The window's chain is the importer's to check (C5c PR2, T224): the codec reads C as the format.
    "decode as the format, with the break in its window" taggedAs UnitTest in {
      val (header, entries) = decode(golden("c"))
      val window = entries.collect { case w: CheckpointArchive.WindowHeaderEntry => w.header }
      window.map(_.number.value) shouldBe Seq(2, 1, 0)
      window(1).parentHash shouldBe WrongParent
      (header.blockHeader +: window).sliding(2).count { case Seq(child, parent) =>
        child.parentHash != parent.hash
      } shouldBe 2
    }
  }

object CheckpointV2GoldenStreamSpec:

  val WrongParent: BlockHash = BlockHash(ByteString(Array.fill[Byte](32)(0x0c)))

  private val Resources = Paths.get("src/test/resources/checkpoint")
  private val Scratch = Paths.get("target/checkpoint-golden")

  def golden(name: String): Array[Byte] =
    val file = Resources.resolve(s"v2-golden-$name.hex")
    Hex.decode(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).filterNot(_.isWhitespace))

  /** The pinned bytes of stream `name`; where none are pinned, `bytes` are written to the scratch directory, and the
    * case fails.
    */
  def pinned(name: String, bytes: Array[Byte]): Array[Byte] =
    val file = Resources.resolve(s"v2-golden-$name.hex")
    if Files.exists(file) then golden(name)
    else
      Files.createDirectories(Scratch)
      val lines = Hex.toHexString(bytes).grouped(64).mkString("", "\n", "\n")
      Files.write(Scratch.resolve(s"v2-golden-$name.hex"), lines.getBytes(StandardCharsets.US_ASCII))
      throw new IllegalStateException(s"stream $name is not pinned: wrote ${bytes.length} bytes to $Scratch")

  /** Stream A with the window header of block 1 given [[WrongParent]], written again. */
  def streamC(a: Array[Byte]): Array[Byte] =
    val (header, entries) = CheckpointV2Fixtures.decode(a)
    val out = new ByteArrayOutputStream()
    val w = new CheckpointArchive.Writer(out)
    w.writeHeader(header)
    entries.foreach {
      case e: CheckpointArchive.WindowHeaderEntry if e.header.number.value == 1 =>
        w.writeWindowHeader(e.header.copy(parentHash = WrongParent))
      case e: CheckpointArchive.WindowHeaderEntry => w.writeWindowHeader(e.header)
      case e: CheckpointArchive.WindowBodyEntry   => w.writeWindowBody(e.blockHash, e.body)
      case e: CheckpointArchive.NodeEntry         => w.writeNode(e.rlp)
      case e: CheckpointArchive.CodeEntry         => w.writeCode(e.code)
      case CheckpointArchive.EndOfStream          => ()
    }
    w.finish()
    out.toByteArray

  def headerBlockLength(h: CheckpointArchive.Header): Int = CheckpointArchive.headerBlockBytes(h).length
