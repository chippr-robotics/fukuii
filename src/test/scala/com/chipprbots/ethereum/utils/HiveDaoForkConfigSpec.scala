package com.chipprbots.ethereum.utils

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties

import scala.jdk.CollectionConverters.*

import org.apache.pekko.util.ByteString

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.testing.Tags.*

/** The DAO fork under hive and on ETH mainnet.
  *
  * WHY THIS SPEC EXISTS. hive's `ethereum/consensus` bcHomesteadToDao tests (DaoTransactions,
  * ...EmptyTransactionAndForkBlocksAhead, ...UncleExtradata) failed because `hive-chain.conf` has `dao = null`: no
  * extraData check on blocks [fork, fork+10) and no drain of the 116 DAO accounts into the refund contract at the fork
  * block. `eth-chain.conf` carried the fork block but null extraData / refund contract / drain list, so a full ETH sync
  * would have mis-executed block 1,920,000.
  *
  * The hive adapter (`hive/fukuii/fukuii.sh`) passes the fork as `-Dfukuii.blockchains.hive.dao.*` system properties,
  * with the drain list as numerically-indexed keys (`drain-list.0`, `drain-list.1`, ...). This spec builds properties
  * of that exact shape, layers them over the SHIPPED hive config the way system properties layer over application
  * config, and asserts what `BlockchainConfig` ends up with. Expected values are go-ethereum's `params/dao.go`.
  */
class HiveDaoForkConfigSpec extends AnyFlatSpec with Matchers:

  private val RefundContract = "bf4ed7b27f1d666546e30d74d50d173d20bca754"
  private val DaoExtraData = ByteString(Hex.decode("64616f2d686172642d666f726b")) // "dao-hard-fork"

  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$name"))

  /** hive/fukuii/dao-drain-list.txt, exactly what fukuii.sh reads. */
  private lazy val hiveDrainList: Seq[String] =
    Files
      .readAllLines(Paths.get("hive/fukuii/dao-drain-list.txt"), StandardCharsets.UTF_8)
      .asScala
      .filter(_.nonEmpty)
      .toList

  /** The properties fukuii.sh emits when HIVE_FORK_DAO_BLOCK=forkBlock and HIVE_FORK_DAO_VOTE=1. */
  private def hiveDaoProps(forkBlock: Int): Properties =
    val p = new Properties()
    val prefix = "fukuii.blockchains.hive.dao"
    p.setProperty(s"$prefix.fork-block-number", forkBlock.toString)
    p.setProperty(s"$prefix.fork-block-hash", "00" * 32)
    p.setProperty(s"$prefix.block-extra-data", "dao-hard-fork")
    p.setProperty(s"$prefix.block-extra-data-range", "10")
    p.setProperty(s"$prefix.refund-contract-address", RefundContract)
    p.setProperty(s"$prefix.include-on-fork-id-list", "true")
    hiveDrainList.zipWithIndex.foreach { case (a, i) => p.setProperty(s"$prefix.drain-list.$i", a) }
    p

  private def hiveWithDao(forkBlock: Int): BlockchainConfig =
    val layered = ConfigFactory.parseProperties(hiveDaoProps(forkBlock)).withFallback(shippedRoot).resolve()
    BlockchainConfig.fromRawConfig(layered.getConfig("fukuii.blockchains.hive"))

  "hive adapter drain list" should "hold go-ethereum's 116 DAODrainList() addresses" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    hiveDrainList should have size 116
    hiveDrainList.distinct should have size 116
    (all(hiveDrainList) should fullyMatch).regex("[0-9a-f]{40}")
    hiveDrainList.head shouldBe "d4fe7bc31cedb7bfb8a345f31e668033056b2728"
  }

  "hive chain with the adapter's DAO properties" should "configure the DAO fork completely" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val dao = hiveWithDao(5).daoForkConfig.getOrElse(fail("DAO fork silently absent"))
    dao.forkBlockNumber shouldBe BigInt(5)
    dao.forkBlockHash should have size 32
    dao.blockExtraData shouldBe Some(DaoExtraData)
    dao.range shouldBe 10
    dao.refundContract shouldBe Some(Address(RefundContract))
    dao.drainList should have size 116
    dao.drainList shouldBe hiveDrainList.map(Address(_))
    dao.includeOnForkIdList shouldBe true
    dao.isDaoForkBlock(5) shouldBe true
    dao.requiresExtraData(4) shouldBe false
    dao.requiresExtraData(5) shouldBe true
    dao.requiresExtraData(14) shouldBe true
    dao.requiresExtraData(15) shouldBe false
  }

  "hive chain without the adapter's DAO properties" should "keep dao = null" taggedAs (UnitTest, ConsensusTest) in {
    shipped("hive").daoForkConfig shouldBe None
  }

  "eth chain" should "carry the mainnet DAO fork in full, with the fork-id inputs unchanged" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val dao = shipped("eth").daoForkConfig.getOrElse(fail("ETH has no DAO fork"))
    dao.forkBlockNumber shouldBe BigInt(1920000)
    dao.forkBlockHash shouldBe ByteString(
      Hex.decode("94365e3a8c0b35089c1d1195081fe7489b528a84b22199c916180db8b28ade7f")
    )
    dao.blockExtraData shouldBe Some(DaoExtraData)
    dao.range shouldBe 10
    dao.refundContract shouldBe Some(Address(RefundContract))
    dao.drainList should have size 116
    // One list on the wire: hive's copy and mainnet's must be the same 116 addresses in the same order.
    dao.drainList shouldBe hiveDrainList.map(Address(_))
    dao.includeOnForkIdList shouldBe true
  }

  "ETC family chains" should "still not enforce or execute a DAO fork" taggedAs (UnitTest, ConsensusTest) in {
    // etc rejected the DAO bailout; mordor/gorgoroth never had it.
    Seq("mordor", "gorgoroth").foreach(n => withClue(n)(shipped(n).daoForkConfig shouldBe None))
    shipped("etc").daoForkConfig.foreach { d =>
      d.includeOnForkIdList shouldBe false
      d.drainList shouldBe empty
      d.blockExtraData shouldBe None
    }
  }
