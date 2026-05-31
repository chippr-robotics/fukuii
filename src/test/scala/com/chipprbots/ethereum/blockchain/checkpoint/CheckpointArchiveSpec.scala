package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.testing.Tags.UnitTest

class CheckpointArchiveSpec extends AnyWordSpec with Matchers with EitherValues {

  private def sample: CheckpointArchive.Header =
    CheckpointArchive.Header(
      chainId = 61L,
      blockHeader = Fixtures.Blocks.Block3125369.header,
      chainWeight = ChainWeight(BigInt("123456789012345678901234567890"))
    )

  private def hex(s: String): ByteString = ByteString(s.getBytes("UTF-8"))

  private def encodeFull(
      header: CheckpointArchive.Header,
      nodes: Seq[(ByteString, Array[Byte])],
      bytecodes: Seq[(ByteString, Array[Byte])]
  ): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val w = new CheckpointArchive.Writer(baos)
    w.writeHeader(header)
    nodes.foreach { case (h, n) => w.writeNode(h, n) }
    bytecodes.foreach { case (h, b) => w.writeBytecode(h, b) }
    w.finish()
    baos.toByteArray
  }

  "CheckpointArchive" should {

    "round-trip a header with no entries" taggedAs UnitTest in {
      val bytes = encodeFull(sample, Nil, Nil)
      val r = new CheckpointArchive.Reader(new ByteArrayInputStream(bytes))
      val decoded = r.readHeader().value
      decoded.chainId shouldBe sample.chainId
      decoded.blockHeader shouldBe sample.blockHeader
      decoded.chainWeight shouldBe sample.chainWeight
      r.nextEntry().value shouldBe CheckpointArchive.EndOfStream
      r.verifyCrc() shouldBe Right(())
    }

    "round-trip a mix of nodes and bytecodes preserving order and bytes" taggedAs UnitTest in {
      val n1 = (hex("nodeHash1____________________________"), "nodeA".getBytes("UTF-8"))
      val n2 = (hex("nodeHash2____________________________"), Array.fill[Byte](2048)(0x42))
      val b1 = (hex("codeHash1____________________________"), "bytecodeBlob".getBytes("UTF-8"))
      val bytes = encodeFull(sample, Seq(n1, n2), Seq(b1))
      val r = new CheckpointArchive.Reader(new ByteArrayInputStream(bytes))
      r.readHeader().value

      r.nextEntry().value match {
        case CheckpointArchive.NodeEntry(h, b) =>
          h shouldBe n1._1
          b.toSeq shouldBe n1._2.toSeq
        case other => fail(s"expected NodeEntry, got $other")
      }
      r.nextEntry().value match {
        case CheckpointArchive.NodeEntry(h, b) =>
          h shouldBe n2._1
          b.toSeq shouldBe n2._2.toSeq
        case other => fail(s"expected NodeEntry, got $other")
      }
      r.nextEntry().value match {
        case CheckpointArchive.BytecodeEntry(h, b) =>
          h shouldBe b1._1
          b.toSeq shouldBe b1._2.toSeq
        case other => fail(s"expected BytecodeEntry, got $other")
      }
      r.nextEntry().value shouldBe CheckpointArchive.EndOfStream
      r.verifyCrc() shouldBe Right(())
    }

    "reject a bad magic value" taggedAs UnitTest in {
      val bytes = encodeFull(sample, Nil, Nil)
      bytes(0) = 0x00 // corrupt magic
      val r = new CheckpointArchive.Reader(new ByteArrayInputStream(bytes))
      r.readHeader() shouldBe Left(CheckpointArchive.BadMagic)
    }

    "reject an unsupported version" taggedAs UnitTest in {
      val bytes = encodeFull(sample, Nil, Nil)
      bytes(4) = 99 // version byte right after 4-byte magic
      val r = new CheckpointArchive.Reader(new ByteArrayInputStream(bytes))
      r.readHeader() shouldBe Left(CheckpointArchive.UnsupportedVersion(99.toByte))
    }

    "reject a corrupted entry payload (CRC mismatch)" taggedAs UnitTest in {
      val n1 = (hex("nodeHash1____________________________"), Array.fill[Byte](64)(0xaa.toByte))
      val bytes = encodeFull(sample, Seq(n1), Nil)
      // Flip a byte in the node payload, after the header. The header section ends at a
      // non-trivial offset; just find a byte well past the prefix and toggle it.
      bytes(bytes.length - 16) = (bytes(bytes.length - 16) ^ 0xff).toByte
      val r = new CheckpointArchive.Reader(new ByteArrayInputStream(bytes))
      r.readHeader().value
      // Drain entries — corruption may surface as a decode error or pass through to CRC check
      var sawEnd = false
      var decodeErr: Option[CheckpointArchive.DecodeError] = None
      while (!sawEnd && decodeErr.isEmpty)
        r.nextEntry() match {
          case Left(e)                              => decodeErr = Some(e)
          case Right(CheckpointArchive.EndOfStream) => sawEnd = true
          case Right(_)                             => ()
        }
      if (sawEnd) {
        r.verifyCrc() shouldBe Left(CheckpointArchive.BadCrc)
      } else {
        decodeErr should not be empty
      }
    }

    "reject a corrupted CRC trailer" taggedAs UnitTest in {
      val bytes = encodeFull(sample, Nil, Nil)
      bytes(bytes.length - 1) = (bytes(bytes.length - 1) ^ 0xff).toByte
      val r = new CheckpointArchive.Reader(new ByteArrayInputStream(bytes))
      r.readHeader().value
      r.nextEntry().value shouldBe CheckpointArchive.EndOfStream
      r.verifyCrc() shouldBe Left(CheckpointArchive.BadCrc)
    }

    "reject a truncated stream" taggedAs UnitTest in {
      val bytes = encodeFull(sample, Nil, Nil)
      val short = bytes.take(bytes.length / 2)
      val r = new CheckpointArchive.Reader(new ByteArrayInputStream(short))
      // Header may decode if first half is enough, else fails. Either way no successful CRC.
      r.readHeader() match {
        case Right(_) =>
          // Header was short enough to fit; nextEntry should fail
          r.nextEntry().isLeft shouldBe true
        case Left(_) => succeed
      }
    }
  }

}
