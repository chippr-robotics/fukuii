package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** spec 022 FR-014 (beacon, 5d): the Engine path's holes. Without this `state_ondemand_fetch_total` reads zero on a
  * post-merge node whose `newPayload` answers SYNCING, and SC-004 passes with nothing looked at. The consensus client
  * retries every slot, so this is what keeps one hole from being a line a slot and thousands a day.
  */
class EngineStateHolesSpec extends AnyFlatSpec with Matchers:

  private val hash = ByteString(Array.fill[Byte](32)(0x11))
  private val other = ByteString(Array.fill[Byte](32)(0x22))
  private val account = ByteString(Array.fill[Byte](32)(0x33))

  private class Rig(remembered: Int = 8):
    var clock: Long = 0L
    val lines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val counted: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val holes = new EngineStateHoles(
      lines += _,
      counted += _,
      () => clock,
      logEvery = 10.minutes,
      remembered = remembered
    )

  "A hole newPayload answered SYNCING over" should "count once and log with its hash, kind and position" taggedAs UnitTest in {
    val rig = new Rig
    val position = ByteString(Array[Byte](1, 2))
    rig.holes.report(BigInt(7), new MissingStorageNodeException(hash, account, Some(position)))
    rig.counted.toSeq shouldBe Seq("storage")
    rig.lines should have size 1
    rig.lines.head should include("[STATE-HOLE] newPayload #7")
    rig.lines.head should include(s"hash=${Hex.toHexString(hash.toArray)}")
    rig.lines.head should include(s"account=${Hex.toHexString(account.toArray)}")
    rig.lines.head should include(s"position=${Hex.toHexString(position.toArray)}")
  }

  it should "not count or log again each slot the consensus client retries it" taggedAs UnitTest in {
    val rig = new Rig
    (1 to 49).foreach { slot =>
      rig.clock = slot * 12_000L // ten minutes is 50 slots
      rig.holes.report(BigInt(slot), new MissingRootNodeException(hash))
    }
    rig.counted.toSeq shouldBe Seq("root")
    rig.lines should have size 1
  }

  it should "log again once a window has passed, saying how often it was hit, and still count once" taggedAs UnitTest in {
    val rig = new Rig
    rig.holes.report(BigInt(1), new MissingRootNodeException(hash))
    rig.clock = 1_000L
    rig.holes.report(BigInt(2), new MissingRootNodeException(hash))
    rig.clock = 10.minutes.toMillis
    rig.holes.report(BigInt(3), new MissingRootNodeException(hash))
    rig.counted should have size 1
    rig.lines should have size 2
    rig.lines(1) should include("still answers SYNCING, hit 2 more time(s)")
    rig.clock = 10.minutes.toMillis + 1_000L
    rig.holes.report(BigInt(4), new MissingRootNodeException(hash))
    withClue("the window starts over: ")(rig.lines should have size 2)
  }

  it should "count another hole at once, with its own kind, and keep a code hole apart" taggedAs UnitTest in {
    val rig = new Rig
    rig.holes.report(BigInt(1), new MissingRootNodeException(hash))
    rig.holes.report(BigInt(1), new MissingCodeException(other, account))
    rig.counted.toSeq shouldBe Seq("root", "code")
    rig.lines should have size 2
  }

  it should "stay bounded: a hole forgotten past the bound counts again, at worst" taggedAs UnitTest in {
    val rig = new Rig(remembered = 2)
    def hole(tag: Int) = new MissingRootNodeException(ByteString(Array.fill[Byte](32)(tag.toByte)))
    Seq(1, 2, 3).foreach(tag => rig.holes.report(BigInt(1), hole(tag)))
    rig.counted should have size 3
    rig.holes.report(BigInt(2), hole(3))
    withClue("the latest is remembered: ")(rig.counted should have size 3)
    rig.holes.report(BigInt(2), hole(1))
    withClue("the first was forgotten: ")(rig.counted should have size 4)
  }

  it should "take an error with no hash, such as a node that does not decode, by what it says" taggedAs UnitTest in {
    val rig = new Rig
    (1 to 3).foreach(_ => rig.holes.report(BigInt(1), new MPTException("Invalid Node")))
    rig.counted.toSeq shouldBe Seq("other")
    rig.lines should have size 1
    rig.lines.head should include("kind=other Invalid Node")
  }

  it should "never throw at the caller: one call site is inside a catch, and the answer is the caller's" taggedAs UnitTest in {
    val lines = mutable.ArrayBuffer.empty[String]
    val failingCount = new EngineStateHoles(lines += _, _ => throw new IllegalStateException("registry closed"))
    noException should be thrownBy failingCount.report(BigInt(1), new MissingRootNodeException(hash))
    withClue("the hole's own line comes first: ")(lines.head should include(s"hash=${Hex.toHexString(hash.toArray)}"))
    withClue("and it says what it could not do: ")(lines.last should include("could not record a hole"))
    val before = lines.size
    failingCount.report(BigInt(2), new MissingRootNodeException(hash))
    withClue("not again each slot: ")(lines.size shouldBe before)
    val failingLog = new EngineStateHoles(_ => throw new IllegalStateException("appender closed"), _ => ())
    noException should be thrownBy failingLog.report(BigInt(1), new MissingRootNodeException(hash))
  }

  "EngineApiMetrics" should "count a hole as engine_state_hole_total{kind}" taggedAs UnitTest in {
    val kind = "engine-state-holes-spec"
    EngineApiMetrics.recordStateHole(kind)
    EngineApiMetrics.recordStateHole(kind)
    Metrics.get().registry.find("engine.state.hole").tag("kind", kind).counter().count() shouldBe 2d
  }
