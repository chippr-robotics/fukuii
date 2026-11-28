package com.chipprbots.ethereum.blockchain.checkpoint

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.*
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Hex

/** Code completeness on import (spec 018 T226, data-model E.1 "Code completeness"): every non-empty code hash an
  * account of the state names has a code entry. With the default threshold of 0 one gap refuses the archive; a non-zero
  * threshold logs the gaps, for the on-demand fetch, and imports.
  */
class CheckpointV2CodeCompletenessSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*
  import CheckpointV2ImportKit.*

  private val c1 = Array[Byte](0x60, 0x01, 0x00)
  private val c2 = Array[Byte](0x60, 0x02, 0x00)
  private val (root, nodes) = Nodes.twoAccounts(
    "a" -> Nodes.account(code = Some(c1)),
    "c" -> Nodes.account(code = Some(c2))
  )
  private def hashOf(code: Array[Byte]): ByteString = ByteString(crypto.kec256(code))

  /** Every message the importer logs at WARN or above while `f` runs. */
  private def warnings[A](f: => A): (A, List[String]) =
    val logger = LoggerFactory.getLogger(classOf[CheckpointImporter]).asInstanceOf[LogbackLogger]
    val previous = logger.getLevel
    val appender = com.chipprbots.ethereum.testing.LogCapture.open(logger.getName)
    logger.setLevel(Level.TRACE)
    val result =
      try f
      finally
        logger.setLevel(previous)
        appender.close()
    (result, appender.list.asScala.toList.filter(_.getLevel.isGreaterOrEqual(Level.WARN)).map(_.getFormattedMessage))

  "The importer" should {

    "import a state whose every code is in the archive, each stored under keccak of it" taggedAs UnitTest in {
      val (bytes, trust) = crafted(root, nodes, CheckpointV2TestKit.sortedCodes(Seq(c1, c2)))
      val target = new Chain
      importInto(target, bytes, trust).value.bytecodesImported shouldBe 2
      Seq(c1, c2).foreach { c =>
        target.storages.storages.evmCodeStorage.get(hashOf(c)).map(_.toSeq) shouldBe Some(c.toSeq)
      }
    }

    "refuse one gap with the default threshold of 0, and say nothing is done" taggedAs UnitTest in {
      val (bytes, trust) = crafted(root, nodes, Seq(c1))
      val target = new Chain
      importInto(target, bytes, trust) shouldBe Left(BadFormat(MissingCode(1, List(hashOf(c2)))))
      target.storages.storages.appStateStorage.isSnapSyncDone() shouldBe false
      target.storages.storages.appStateStorage.getBestBlockNumber() shouldBe 0
    }

    "refuse two gaps with no code at all" taggedAs UnitTest in {
      val (bytes, trust) = crafted(root, nodes, Nil)
      importInto(new Chain, bytes, trust).left.value match
        case BadFormat(MissingCode(count, sample)) =>
          count shouldBe 2
          sample.toSet shouldBe Set(hashOf(c1), hashOf(c2))
        case other => fail(s"not MissingCode: $other")
    }

    "import with gaps at or below a non-zero threshold, logging them for the on-demand fetch" taggedAs UnitTest in {
      val (bytes, trust) = crafted(root, nodes, Seq(c1))
      val target = new Chain
      val (result, warned) = warnings(importInto(target, bytes, trust, codeGapThreshold = 1))
      result.value.bytecodesImported shouldBe 1
      target.storages.storages.appStateStorage.isSnapSyncDone() shouldBe true
      warned.exists(_.contains(Hex.toHexString(hashOf(c2).toArray))) shouldBe true
      withClue("above it, refused: ") {
        importInto(new Chain, crafted(root, nodes, Nil)._1, trust, codeGapThreshold = 1).left.value shouldBe a[
          BadFormat
        ]
      }
    }

    "refuse a code no account names" taggedAs UnitTest in {
      val stray = Array[Byte](0x60, 0x03, 0x00)
      val (bytes, trust) = crafted(root, nodes, CheckpointV2TestKit.sortedCodes(Seq(c1, c2, stray)))
      importInto(new Chain, bytes, trust) shouldBe Left(BadFormat(UnreferencedCode(hashOf(stray))))
    }
  }
