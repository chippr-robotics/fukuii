package com.chipprbots.ethereum.blockchain.checkpoint

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.*
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The trust checks on import (spec 018 T290, data-model E.1 "Trust"). Every refusal comes before any write. */
class CheckpointV2TrustSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*
  import CheckpointV2ImportKit.*

  private val (chainA, tipA) = streamAChain()
  private val (headerA, entriesA) = decode(CheckpointV2GoldenStreamSpec.golden("a"))
  private val trustA = trustOf(chainA, 61, tipA)
  private val (chainB, tipB) = streamBChain()
  private val (headerB, entriesB) = decode(CheckpointV2GoldenStreamSpec.golden("b"))

  private def refusedBeforeAnyWrite(bytes: Array[Byte], trust: Trust): Refusal =
    val target = new Chain
    val before = snapshot(target)
    val result = importInto(target, bytes, trust)
    withClue("nothing is written: ")(snapshot(target) shouldBe before)
    result.left.value match
      case BadFormat(reason) => reason
      case other             => fail(s"not BadFormat: $other")

  /** Golden B with this total difficulty, on a node whose genesis has `genesisDifficulty` and whose chain `ttd`. */
  private def streamB(td: BigInt, ttd: BigInt, genesisDifficulty: BigInt): (Array[Byte], Trust) =
    (
      Raw.rebuild(headerB.copy(totalDifficulty = td), entriesB),
      trustOf(chainB, StreamBChainId, tipB, Some(ttd)).copy(genesisDifficulty = genesisDifficulty)
    )

  "The importer" should {

    "accept an estimated total difficulty, with a WARN naming the block and the TD" taggedAs UnitTest in {
      val estimated = Raw.rebuild(headerA.copy(tdExact = false), entriesA)
      val logger = LoggerFactory.getLogger(classOf[CheckpointImporter]).asInstanceOf[LogbackLogger]
      val previous = logger.getLevel
      val appender = com.chipprbots.ethereum.testing.LogCapture.open(logger.getName)
      logger.setLevel(Level.TRACE)
      val target = new Chain
      try importInto(target, estimated, trustA).value
      finally
        logger.setLevel(previous)
        appender.close()
      val warned = appender.list.asScala.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)
      warned.exists(m => m.contains(s"block ${tipA.number}") && m.contains(headerA.totalDifficulty.toString)) shouldBe
        true
      target.reader.getChainWeightByHash(tipA.hash).map(_.totalDifficulty.value) shouldBe Some(headerA.totalDifficulty)
    }

    "on a PoS chain not merged at genesis, refuse a post-merge checkpoint whose TD is below the TTD" taggedAs UnitTest in {
      tipB.difficulty.value shouldBe 0
      val (below, trust) = streamB(td = 99, ttd = 100, genesisDifficulty = 0)
      refusedBeforeAnyWrite(below, trust) shouldBe TotalDifficultyRefused(tipB.number.value, 99, 100, 0)
      val (at, trustAt) = streamB(td = 100, ttd = 100, genesisDifficulty = 0)
      importInto(new Chain, at, trustAt).value
    }

    "on a chain merged at genesis, refuse a TD other than the genesis difficulty" taggedAs UnitTest in {
      val (other, trust) = streamB(td = 1, ttd = 0, genesisDifficulty = 0)
      refusedBeforeAnyWrite(other, trust) shouldBe TotalDifficultyRefused(tipB.number.value, 1, 0, 0)
      withClue("golden B, TD 0 on a genesis of difficulty 0: ") {
        importInto(
          new Chain,
          CheckpointV2GoldenStreamSpec.golden("b"),
          trustOf(chainB, StreamBChainId, tipB, Some(0))
        ).value
      }
    }

    "refuse an ETH archive on an ETC node by its chain id" taggedAs UnitTest in {
      refusedBeforeAnyWrite(
        CheckpointV2GoldenStreamSpec.golden("b"),
        trustA.copy(genesisHash = headerB.genesisHash)
      ) shouldBe
        WrongChainId(61, StreamBChainId)
    }

    "refuse an archive with ETC's chain id and the shared genesis, but another chain's block, by the expected hash" taggedAs UnitTest in {
      val other = new Chain
      val root = other.accountTrie(Seq(Array.fill[Byte](20)(0x44) -> Account(UInt256(9), UInt256(9))))
      val otherTip =
        other.store((0 to 3).map(n => powHeader(n, stateRoot = if n == 3 then root else EmptyTrieRoot)), Map.empty).last
      other.reader.genesisHeader.hash shouldBe chainA.reader.genesisHeader.hash
      val bytes = other.exportBytes(other.exporter(61), 3).value
      refusedBeforeAnyWrite(bytes, trustA) shouldBe UnexpectedBlockHash(tipA.hash.value, otherTip.hash.value)
    }

    "read each public chain's pinned expected hash from its chain config, the operator's winning over it" taggedAs UnitTest in {
      val shipped = ConfigFactory.load()
      Seq("etc", "mordor", "eth", "sepolia", "hoodi").foreach { name =>
        val raw = shipped.getConfig(s"fukuii.blockchains.$name")
        withClue(s"$name carries the key: ")(raw.hasPath("checkpoint-expected-block-hash") shouldBe true)
        val pinned = ByteString(Array.fill[Byte](32)(0x5c))
        val config = BlockchainConfig.fromRawConfig(
          raw.withValue("checkpoint-expected-block-hash", ConfigValueFactory.fromAnyRef("0x" + "5c" * 32))
        )
        config.checkpointExpectedBlockHash shouldBe Some(pinned)
        Trust(config, tipA, None).expectedBlockHash shouldBe Some(pinned)
        Trust(config, tipA, Some(tipA.hash.value)).expectedBlockHash shouldBe Some(tipA.hash.value)
        Trust(config, tipA, None).chainId shouldBe config.chainId.value
        withClue(s"$name pins none yet (no format version 2 archive exists): ") {
          BlockchainConfig.fromRawConfig(raw).checkpointExpectedBlockHash shouldBe None
        }
      }
      an[IllegalArgumentException] should be thrownBy BlockchainConfig.fromRawConfig(
        shipped
          .getConfig("fukuii.blockchains.etc")
          .withValue("checkpoint-expected-block-hash", ConfigValueFactory.fromAnyRef("0x1234"))
      )
    }
  }
