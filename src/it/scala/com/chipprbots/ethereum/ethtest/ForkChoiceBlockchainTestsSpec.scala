package com.chipprbots.ethereum.ethtest

import java.io.File

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.io.Source

import io.circe.Json
import io.circe.parser.parse
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** ethereum/tests BlockchainTests whose blocks form MORE THAN ONE CHAIN: side branches, reorganisations, re-reorgs, and
  * BLOCKHASH read across them. The expected outcome is the fixture's `lastblockhash` (the head a reference client
  * picks) and `postState` (that head's state).
  *
  * The sequential runner ([[EthereumTestExecutor]]) cannot judge these: it executes every block in file order against
  * its parent with no fork choice, and reads the post-state from the last block in the file, which in these fixtures is
  * often not the head. This runner instead delivers each block, in file order, through the production import path —
  * `ConsensusAdapter.evaluateBranchBlock`, the NewBlock path: block queue, branch weight, ConsensusImpl
  * importToTop/reorganise, real EVM, real post-execution state-root check. Header seal/PoW checks are mocked (fixtures
  * are NoProof).
  *
  * Networks: Istanbul and Berlin, the ETH forks whose EVM rules ETC's Phoenix and Magneto adopt.
  */
class ForkChoiceBlockchainTestsSpec extends AnyFlatSpec with Matchers:

  // Resolved like TransactionTestsSpec: system property, then environment variable, then the ets/tests submodule of
  // the checkout sbt runs from.
  private val Base = sys.props
    .get("blockchaintests.basePath")
    .orElse(sys.env.get("BLOCKCHAINTESTS_BASEPATH"))
    .getOrElse(new File(System.getProperty("user.dir"), "ets/tests/BlockchainTests").getPath) + "/ValidBlocks"
  private val Networks = Set("Istanbul", "Berlin")

  final private case class Outcome(name: String, error: Option[String], reorgs: Int)

  private class FixtureNode(bc: BlockchainConfig) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = bc

  private def hex(s: String): Array[Byte] =
    val c = if s.startsWith("0x") then s.substring(2) else s
    if c.isEmpty then Array.emptyByteArray else Hex.decode(c)

  private def big(s: String): BigInt = if s.startsWith("0x") then BigInt(1, hex(s)) else BigInt(s)

  private def runOne(name: String, testJson: Json): Outcome =
    val test = testJson.as[BlockchainTest].fold(e => throw new RuntimeException(e.toString), identity)
    val lastBlockHash = testJson.hcursor.downField("lastblockhash").as[String].toOption.map(h => ByteString(hex(h)))
    val bc: BlockchainConfig = TestConverter.networkToConfig(test.network, Config.blockchains.blockchainConfig)
    val node = new FixtureNode(bc)
    import node.*

    // Genesis: pre-state into the node's own storage, then the fixture's genesis header on top of it.
    val world0 = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(0),
      (_: BigInt) => None,
      bc.accountStartNonce,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = bc.ethCompatibleStorage
    )
    val world1 = test.pre.foldLeft(world0) { case (w, (addr, acc)) =>
      val address = Address(ByteString(hex(addr)))
      val code = ByteString(hex(acc.code))
      val withAccount =
        w.saveAccount(address, Account(nonce = UInt256(big(acc.nonce)), balance = UInt256(big(acc.balance))))
      val withCode = if code.nonEmpty then withAccount.saveCode(address, code) else withAccount
      acc.storage.foldLeft(withCode) { case (ww, (k, v)) =>
        ww.saveStorage(address, ww.getStorage(address).store(big(k), big(v)))
      }
    }
    val genesisRoot = InMemoryWorldStateProxy.persistState(world1).stateRootHash
    val genesisHeader = TestConverter.toBlockHeader(test.genesisBlockHeader.get)
    if genesisHeader.stateRoot.value != genesisRoot then
      return Outcome(name, Some("genesis pre-state root mismatch"), 0)
    blockchainWriter.save(
      Block(genesisHeader, BlockBody(Nil, Nil)),
      Nil,
      ChainWeight.totalDifficultyOnly(genesisHeader.difficulty.value),
      saveAsBestBlock = true
    )

    val results = test.blocks.map { tb =>
      val block = Block(
        TestConverter.toBlockHeader(tb.blockHeader),
        BlockBody(tb.transactions.map(TestConverter.toTransaction), tb.uncleHeaders.map(TestConverter.toBlockHeader))
      )
      (block, consensusAdapter.evaluateBranchBlock(block)(using IORuntime.global, bc).unsafeRunSync()(IORuntime.global))
    }
    val failures = results.collect { case (block, BlockImportFailed(err)) =>
      s"block ${block.number} ${block.header.hashAsHexString}: $err"
    }
    val reorgs = results.count { case (_, r) => r.isInstanceOf[ChainReorganised] }

    val head = blockchainReader.getBestBlock.map(_.header)
    val headError = (lastBlockHash, head) match
      case (Some(expected), Some(h)) if h.hash.value != expected =>
        Some(s"head ${h.number} ${h.hashAsHexString} != lastblockhash ${Hex.toHexString(expected.toArray)}")
      case (_, None) => Some("no head")
      case _         => None

    val postError = head.flatMap { h =>
      val world = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getReadOnlyMptStorage(),
        (_: BigInt) => None,
        bc.accountStartNonce,
        h.stateRoot.value,
        noEmptyAccounts = false,
        ethCompatibleStorage = bc.ethCompatibleStorage
      )
      test.postState.iterator
        .flatMap { case (addr, expected) =>
          val address = Address(ByteString(hex(addr)))
          val actual = world.getAccount(address).getOrElse(Account.empty(bc.accountStartNonce))
          val storage = world.getStorage(address)
          val slotErrors = expected.storage.iterator.collect {
            case (k, v) if storage.load(big(k)) != big(v) => s"$addr[$k] = ${storage.load(big(k))} != $v"
          }
          Iterator(
            Option.when(actual.balance.toBigInt != big(expected.balance))(s"$addr balance"),
            Option.when(actual.nonce.toBigInt != big(expected.nonce))(s"$addr nonce")
          ).flatten ++ slotErrors
        }
        .nextOption()
    }

    // ValidBlocks: every block is valid, so any import failure is a failure in its own right.
    val verdict =
      headError.orElse(postError).orElse(failures.headOption).map(e => (e +: failures.take(3)).mkString(" | "))
    Outcome(name, verdict, reorgs)

  private def runCategory(category: String): Seq[Outcome] =
    val dir = new File(s"$Base/$category")
    assume(dir.isDirectory, s"ethereum/tests fixtures not present at $dir")
    dir.listFiles().filter(_.getName.endsWith(".json")).sortBy(_.getName).toSeq.flatMap { f =>
      val src = Source.fromFile(f)
      val json =
        try parse(src.mkString).fold(e => throw new RuntimeException(e.toString), identity)
        finally src.close()
      json.asObject.get.toList.collect {
        case (name, t) if t.hcursor.downField("network").as[String].toOption.exists(Networks.contains) =>
          runOne(s"${f.getName}/$name", t)
      }
    }

  private def check(category: String): Unit =
    val outcomes = runCategory(category)
    val failed = outcomes.filter(_.error.isDefined)
    info(
      s"$category: ${outcomes.size - failed.size}/${outcomes.size} passed; " +
        s"${outcomes.map(_.reorgs).sum} reorganisations in ${outcomes.count(_.reorgs > 0)} cases"
    )
    failed.foreach(o => info(s"  FAIL ${o.name}: ${o.error.get}"))
    outcomes should not be empty
    failed.map(o => s"${o.name}: ${o.error.get}") shouldBe empty

  "Fork-choice BlockchainTests" should "pass bcMultiChainTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    check("bcMultiChainTest")
  }

  it should "pass bcTotalDifficultyTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    check("bcTotalDifficultyTest")
  }

  it should "pass bcForkStressTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    check("bcForkStressTest")
  }

  it should "pass bcRandomBlockhashTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    check("bcRandomBlockhashTest")
  }
