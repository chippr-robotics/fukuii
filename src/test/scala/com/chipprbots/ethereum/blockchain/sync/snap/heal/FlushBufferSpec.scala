package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 review eye F9 (4): the Hash scheme's flush buffer. */
class FlushBufferSpec extends AnyFlatSpec with Matchers:

  private def entry(i: Int): (ByteString, Array[Byte]) = (ByteString(s"hash-$i"), Array.fill[Byte](10)(i.toByte))

  "A flush buffer" should "drop only the very entries a snapshot took, from its head" taggedAs UnitTest in {
    val buffer = new FlushBuffer
    val (a, b, c) = (entry(1), entry(2), entry(3))
    buffer ++= Seq(a, b, c)
    val snapshot = buffer.firstEntries(2)
    snapshot.map(_._1) shouldBe Seq(a._1, b._1)
    buffer.dropWritten(snapshot) shouldBe 2
    buffer.snapshot.map(_._1) shouldBe Seq(c._1)
    buffer.contains(a._1) shouldBe false
    buffer.contains(c._1) shouldBe true
  }

  it should "stop dropping at an entry the snapshot did not take: nothing else leaves on its strength" taggedAs UnitTest in {
    val buffer = new FlushBuffer
    val a = entry(1)
    buffer += a
    val stale = buffer.snapshot
    buffer.clear()
    val other = entry(1) // the same hash, another object
    buffer += other
    buffer.dropWritten(stale) shouldBe 0
    buffer.size shouldBe 1
  }

  it should "count a hash buffered twice until both copies have gone" taggedAs UnitTest in {
    val buffer = new FlushBuffer
    val (first, second) = (entry(1), entry(1))
    buffer ++= Seq(first, second)
    buffer.dropWritten(buffer.firstEntries(1)) shouldBe 1
    buffer.contains(first._1) shouldBe true
    buffer.dropWritten(buffer.firstEntries(1)) shouldBe 1
    buffer.contains(first._1) shouldBe false
  }

  it should "account for its bytes, and give them back" taggedAs UnitTest in {
    val buffer = new FlushBuffer
    buffer.bytes shouldBe 0L
    buffer += entry(1)
    buffer.bytes should be > 0L
    buffer.dropWritten(buffer.snapshot)
    buffer.bytes shouldBe 0L
  }
