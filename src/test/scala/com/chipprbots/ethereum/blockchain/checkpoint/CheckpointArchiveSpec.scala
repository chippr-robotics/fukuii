package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32C

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockBody.BlockBodyEnc
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** The codec of format version 2, field by field as data-model E.1's table (spec 018 T228). What it checks is format;
  * the importer's checks (the window chains, the node is the one named, the chain is this one) are C5c PR2's.
  */
class CheckpointArchiveSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointArchive.*
  import CheckpointArchiveSpec.*

  "The header block" should {

    "be laid out as E.1's fields 1 to 9, fixed-width and big-endian" taggedAs UnitTest in {
      val h = sample(chainId = 7091047534L, td = BigInt("123456789012345678901234567890"))
      val bytes = headerBlockBytes(h)
      val header = h.blockHeader.toBytes: Array[Byte]
      val td = h.totalDifficulty.toByteArray.dropWhile(_ == 0)
      bytes.take(4).toSeq shouldBe Seq(0xc4, 0xc4, 0xc4, 0xc4).map(_.toByte)
      bytes(4) shouldBe 0x02
      hex(bytes.slice(5, 13)) shouldBe "00000001a6a8cc6e" // 7091047534, wider than 32 bits
      bytes.slice(13, 45).toSeq shouldBe h.genesisHash.toSeq
      bytes(45) shouldBe 0x01
      hex(bytes.slice(46, 50)) shouldBe f"${header.length}%08x"
      bytes.slice(50, 50 + header.length).toSeq shouldBe header.toSeq
      hex(bytes.slice(50 + header.length, 54 + header.length)) shouldBe f"${td.length}%08x"
      bytes.drop(54 + header.length).toSeq shouldBe td.toSeq
    }

    "write a total difficulty of 0 as length 0, and read it back" taggedAs UnitTest in {
      val h = sample(td = 0)
      val bytes = headerBlockBytes(h)
      hex(bytes.takeRight(4)) shouldBe "00000000"
      reader(encode(h)).readHeader().value.totalDifficulty shouldBe 0
    }

    "round-trip a chain id above 2^63 as the same 64 bits, read unsigned" taggedAs UnitTest in {
      val top = Header.chainIdBits(BigInt("18446744073709551615")).get
      top shouldBe -1L
      val read = reader(encode(sample(chainId = top))).readHeader().value
      read.chainId shouldBe top
      read.chainIdUnsigned shouldBe BigInt("18446744073709551615")
      Header.chainIdBits(BigInt(2).pow(64)) shouldBe None
      Header.chainIdBits(BigInt(-1)) shouldBe None
    }

    "name the archive by keccak of fields 1 to 9" taggedAs UnitTest in {
      val h = sample()
      val r = reader(encode(h))
      r.readHeader().value
      r.archiveId shouldBe Some(ByteString(crypto.kec256(headerBlockBytes(h))))
      archiveIdOf(h) shouldBe r.archiveId.get
      archiveIdOf(h.copy(totalDifficulty = h.totalDifficulty + 1)) should not be archiveIdOf(h)
    }

    "refuse a version 1 archive with a message that names the format" taggedAs UnitTest in {
      val bytes = encode(sample())
      bytes(4) = 1
      val refused = reader(bytes).readHeader().left.value
      refused shouldBe UnsupportedVersion(1)
      refused.describe should include("format version 1")
      refused.describe should include("version 2 only")
    }

    "refuse any other version, and a bad magic" taggedAs UnitTest in {
      val v3 = encode(sample())
      v3(4) = 3
      reader(v3).readHeader().left.value shouldBe UnsupportedVersion(3)
      val magic = encode(sample())
      magic(0) = 0
      reader(magic).readHeader().left.value shouldBe BadMagic
    }

    "refuse a TD-exact flag other than 0 or 1" taggedAs UnitTest in {
      reader(encode(sample(tdExact = false))).readHeader().value.tdExact shouldBe false
      val bytes = encode(sample())
      bytes(45) = 2
      reader(bytes).readHeader().left.value shouldBe a[Malformed]
    }

    "refuse a TD with a leading zero byte, and one longer than 32 bytes" taggedAs UnitTest in {
      val headerRlp = sample().blockHeader.toBytes: Array[Byte]
      reader(rawHeaderBlock(headerRlp, Array[Byte](0, 1))).readHeader().left.value shouldBe a[Malformed]
      reader(rawHeaderBlock(headerRlp, Array.fill[Byte](33)(1))).readHeader().left.value shouldBe a[Malformed]
      reader(rawHeaderBlock(headerRlp, Array.fill[Byte](32)(-1))).readHeader().value.totalDifficulty shouldBe
        (BigInt(1) << 256) - 1
    }

    "refuse a checkpoint header longer than 64 KiB, and the genesis as the checkpoint" taggedAs UnitTest in {
      val tooLong = rawHeaderBlock(Array.emptyByteArray, Array.emptyByteArray)
      // header length field at bytes 46..49
      tooLong(46) = 0; tooLong(47) = 1; tooLong(48) = 0; tooLong(49) = 1 // 65537
      reader(tooLong).readHeader().left.value shouldBe a[Malformed]
      val genesis = sample().blockHeader.copy(number = BlockNumber(0))
      reader(rawHeaderBlock(genesis.toBytes, Array[Byte](1))).readHeader().left.value shouldBe a[Malformed]
      an[IllegalArgumentException] should be thrownBy headerBlockBytes(sample().copy(blockHeader = genesis))
    }
  }

  "The sections" should {

    "round-trip every entry type, in order, byte for byte" taggedAs UnitTest in {
      val h = sample()
      val (window, bodies) = windowOf(h)
      val nodes = Seq(Array.fill[Byte](40)(0x42), Array.fill[Byte](2048)(0x43))
      val codes = CheckpointV2TestKit.sortedCodes(Seq("code-a".getBytes, "code-b".getBytes, "code-c".getBytes))
      val bytes = encode(h, window, bodies, nodes, codes)
      val r = reader(bytes)
      r.readHeader().value shouldBe h
      window.foreach { w =>
        val e = r.nextEntry().value.asInstanceOf[WindowHeaderEntry]
        e.header shouldBe w
        e.rlp.toSeq shouldBe (w.toBytes: Array[Byte]).toSeq
      }
      bodies.foreach { (hash, body) =>
        val e = r.nextEntry().value.asInstanceOf[WindowBodyEntry]
        e.blockHash shouldBe hash
        e.body shouldBe body
      }
      nodes.foreach(n => r.nextEntry().value.asInstanceOf[NodeEntry].rlp.toSeq shouldBe n.toSeq)
      codes.foreach(c => r.nextEntry().value.asInstanceOf[CodeEntry].code.toSeq shouldBe c.toSeq)
      r.nextEntry().value shouldBe EndOfStream
      r.verifyCrc() shouldBe Right(())
    }

    "end with the end tag and a CRC32C, big-endian, of every byte from the magic to the end tag" taggedAs UnitTest in {
      val bytes = encode(sample(), windowOf(sample())._1, Nil, Seq(Array.fill[Byte](33)(7)), Nil)
      bytes(bytes.length - 5) shouldBe TagEnd
      val crc = new CRC32C
      crc.update(bytes, 0, bytes.length - 4)
      hex(bytes.takeRight(4)) shouldBe f"${crc.getValue}%08x"
    }

    "refuse a corrupt CRC, a corrupt payload, bytes after the CRC, and a stream cut short" taggedAs UnitTest in {
      val good = encode(sample(), windowOf(sample())._1, Nil, Seq(Array.fill[Byte](64)(0xaa.toByte)), Nil)
      val badCrc = good.clone()
      badCrc(badCrc.length - 1) = (badCrc(badCrc.length - 1) ^ 0xff).toByte
      drain(badCrc) shouldBe Left(BadCrc)
      val badNode = good.clone()
      badNode(badNode.length - 16) = (badNode(badNode.length - 16) ^ 0xff).toByte
      drain(badNode) shouldBe Left(BadCrc)
      drain(good :+ 0.toByte).left.value shouldBe a[Malformed]
      (1 until good.length).foreach { cut =>
        withClue(s"cut at $cut: ")(drain(good.take(cut)).left.value shouldBe a[Truncated])
      }
      drain(good) shouldBe Right(())
    }

    "refuse every tag out of section order, and an unknown tag" taggedAs UnitTest in {
      val h = sample()
      val (window, bodies) = windowOf(h)
      val w = window.map(x => header(x))
      val b = bodies.map((hash, body) => bodyEntry(hash, body))
      val node = entry(TagNode, Array.fill[Byte](40)(1))
      val code = entry(TagCode, "c".getBytes)
      val cases: Seq[(String, Seq[Array[Byte]])] = Seq(
        "a header after a body" -> (w.init ++ b ++ Seq(w.last)),
        "a header after a node" -> (w ++ Seq(node) ++ Seq(header(window.head))),
        "a body after a node" -> (w ++ Seq(node) ++ b),
        "a body after a code" -> (w ++ Seq(code) ++ b),
        "a node after a code" -> (w ++ b ++ Seq(code, node)),
        "a node before the window is whole" -> (w.init ++ Seq(node)),
        "the end before the window is whole" -> w.init,
        "one window header too many" -> (w ++ Seq(header(window.head))),
        "an unknown tag" -> (w ++ Seq(Array[Byte](0x05, 0, 0, 0, 0)))
      )
      cases.foreach { (name, entries) =>
        withClue(s"$name: ")(drain(rawStream(h, entries)).left.value shouldBe a[Malformed])
      }
      drain(rawStream(h, w ++ b ++ Seq(node, code))) shouldBe Right(())
    }

    "refuse more window bodies than the window has blocks with ommers can number" taggedAs UnitTest in {
      val h = sample()
      val (window, bodies) = windowOf(h)
      val one = bodyEntry(bodies.head._1, bodies.head._2)
      // N = 3: C down to C-3 is four blocks
      drain(rawStream(h, window.map(header) ++ Seq.fill(4)(one))) shouldBe Right(())
      drain(rawStream(h, window.map(header) ++ Seq.fill(5)(one))).left.value shouldBe a[Malformed]
    }

    "refuse codes out of order or repeated" taggedAs UnitTest in {
      val h = sample()
      val w = windowOf(h)._1.map(header)
      val sorted = CheckpointV2TestKit.sortedCodes(Seq("x".getBytes, "y".getBytes))
      drain(rawStream(h, w ++ sorted.map(entry(TagCode, _)))) shouldBe Right(())
      drain(rawStream(h, w ++ sorted.reverse.map(entry(TagCode, _)))).left.value shouldBe a[Malformed]
      drain(rawStream(h, w ++ Seq(sorted.head, sorted.head).map(entry(TagCode, _)))).left.value shouldBe a[Malformed]
    }

    "refuse an entry longer than 64 MiB without reading it" taggedAs UnitTest in {
      val h = sample()
      val w = windowOf(h)._1.map(header)
      val huge = Array[Byte](TagNode, 0x04, 0, 0, 1) // 64 MiB + 1
      drain(rawStream(h, w ++ Seq(huge))).left.value shouldBe a[Malformed]
    }

    "and the writer refuses to write out of order" taggedAs UnitTest in {
      val h = sample()
      val (window, _) = windowOf(h)
      val w = new Writer(new ByteArrayOutputStream())
      an[IllegalArgumentException] should be thrownBy w.writeNode(Array[Byte](1))
      w.writeHeader(h)
      an[IllegalArgumentException] should be thrownBy w.writeNode(Array[Byte](1)) // the window is not whole
      window.foreach(w.writeWindowHeader)
      w.writeCode("b".getBytes)
      an[IllegalArgumentException] should be thrownBy w.writeNode(Array[Byte](1))
      an[IllegalArgumentException] should be thrownBy w.writeCode("b".getBytes) // repeated
    }
  }

object CheckpointArchiveSpec:
  import CheckpointArchive.*

  def hex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  /** Block 3 of a window built to chain to it: three window headers, down to block 0. */
  def sample(chainId: Long = 61L, td: BigInt = BigInt(987654321), tdExact: Boolean = true): Header =
    val (relinked, window) =
      CheckpointV2TestKit.withWindow(Fixtures.Blocks.Block3125369.header.copy(number = BlockNumber(3)))
    Header(chainId, CheckpointV2TestKit.genesisOf(window), tdExact, relinked, td)

  /** The window of `h`, and one body, of `h`'s own block, with one ommer. */
  def windowOf(h: Header): (Seq[BlockHeader], Seq[(ByteString, BlockBody)]) =
    val (_, window) = CheckpointV2TestKit.withWindow(h.blockHeader)
    (window, Seq(h.blockHeader.hash.value -> BlockBody(Nil, Seq(window.last))))

  def reader(bytes: Array[Byte]): Reader = new Reader(new ByteArrayInputStream(bytes))

  def encode(
      h: Header,
      window: Seq[BlockHeader] = Nil,
      bodies: Seq[(ByteString, BlockBody)] = Nil,
      nodes: Seq[Array[Byte]] = Nil,
      codes: Seq[Array[Byte]] = Nil
  ): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val w = new Writer(out)
    w.writeHeader(h)
    val full = if window.isEmpty then windowOf(h)._1 else window
    full.foreach(w.writeWindowHeader)
    bodies.foreach((hash, body) => w.writeWindowBody(hash, body))
    nodes.foreach(w.writeNode)
    codes.foreach(w.writeCode)
    w.finish()
    out.toByteArray

  /** Reads a whole stream and its CRC; the first refusal. */
  def drain(bytes: Array[Byte]): Either[DecodeError, Unit] =
    val r = reader(bytes)
    r.readHeader().flatMap { _ =>
      var next = r.nextEntry()
      while next.exists(_ != EndOfStream) do next = r.nextEntry()
      next.flatMap(_ => r.verifyCrc())
    }

  private def u32(v: Int): Array[Byte] = Array((v >>> 24).toByte, (v >>> 16).toByte, (v >>> 8).toByte, v.toByte)

  def entry(tag: Byte, payload: Array[Byte]): Array[Byte] = Array(tag) ++ u32(payload.length) ++ payload
  def header(h: BlockHeader): Array[Byte] = entry(TagWindowHeader, h.toBytes)
  def bodyEntry(hash: ByteString, body: BlockBody): Array[Byte] =
    val rlp = body.toBytes: Array[Byte]
    Array(TagWindowBody) ++ hash.toArray ++ u32(rlp.length) ++ rlp

  /** A header block of block 3 with the header RLP and TD bytes given as they are, with no check. */
  def rawHeaderBlock(headerRlp: Array[Byte], td: Array[Byte]): Array[Byte] =
    val h = sample()
    Magic ++ Array(Version) ++ Array.fill[Byte](7)(0) ++ Array[Byte](61) ++ h.genesisHash.toArray ++ Array[Byte](1) ++
      u32(headerRlp.length) ++ headerRlp ++ u32(td.length) ++ td

  /** `h`'s header block, the entries as they are, the end tag and a CRC32C that matches. */
  def rawStream(h: Header, entries: Seq[Array[Byte]]): Array[Byte] =
    val body = headerBlockBytes(h) ++ entries.flatten ++ Array(TagEnd)
    val crc = new CRC32C
    crc.update(body)
    body ++ u32(crc.getValue.toInt)
