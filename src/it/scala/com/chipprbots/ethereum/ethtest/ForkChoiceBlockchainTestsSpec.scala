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

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.UnknownParent
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
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
  *
  * Each category runs on the hash layout and on the unified state layout (spec 018 Track C, B-289: the gate before the
  * guard is on for a proof-of-work node). On the unified layout the pre-state is loaded as the node loads a genesis, as
  * rows, and the genesis block the loader builds must be the fixture's; a branch is executed on pinned layers and the
  * head is moved to it in place, with no rewind.
  *
  * Each layout runs under both pruning modes (spec 018 T007, B-432): `basic`, the shipped default
  * (`conf/base/pruning.conf`: history 64), and `archive`. The spec runs only in the nightly ethereum/tests job.
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

  /** The node a case runs on: its state layout and its pruning mode. */
  final private case class Variant(layout: String, unified: Boolean, pruningName: String, pruning: PruningMode)

  private val Variants: Seq[Variant] =
    for
      (layout, unified) <- Seq("the hash layout" -> false, "the unified layout" -> true)
      (pruningName, pruning) <- Seq("basic" -> BasicPruning(64), "archive" -> ArchivePruning)
    yield Variant(layout, unified, pruningName, pruning)

  private class FixtureNode(bc: BlockchainConfig, variant: Variant) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = bc

    // mixed in before Storages.DefaultStorages, so the storages are built with this mode
    private trait VariantPruning extends LocalPruningConfigBuilder:
      override val pruningMode: PruningMode = variant.pruning

    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with VariantPruning
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = variant.unified

  private def quantity(value: BigInt): String = "0x" + value.toString(16)

  private def hex(s: String): Array[Byte] =
    val c = if s.startsWith("0x") then s.substring(2) else s
    if c.isEmpty then Array.emptyByteArray else Hex.decode(c)

  private def big(s: String): BigInt = if s.startsWith("0x") then BigInt(1, hex(s)) else BigInt(s)

  private def runOne(name: String, testJson: Json, variant: Variant): Outcome =
    val unified = variant.unified
    val test = testJson.as[BlockchainTest].fold(e => throw new RuntimeException(e.toString), identity)
    val lastBlockHash = testJson.hcursor.downField("lastblockhash").as[String].toOption.map(h => ByteString(hex(h)))
    val bc: BlockchainConfig = TestConverter.networkToConfig(test.network, Config.blockchains.blockchainConfig)
    val node = new FixtureNode(bc, variant)
    import node.*
    val genesisHeader = TestConverter.toBlockHeader(test.genesisBlockHeader.get)
    // the node is on the layout the case is named for, or the two runs would be one
    require(storagesInstance.storages.pathStateStorage.isDefined == unified, s"$name: the node is not on its layout")
    require(storagesInstance.pruningMode == variant.pruning, s"$name: the node is not on its pruning mode")
    if unified then
      // The pre-state as rows, by the loader a node starts with: the block it builds must be the fixture's genesis.
      val alloc = test.pre.map { case (addr, acc) =>
        val code = ByteString(hex(acc.code))
        val storage = acc.storage.map { case (k, v) => UInt256(big(k)) -> UInt256(big(v)) }
        (if addr.startsWith("0x") then addr.substring(2) else addr) -> GenesisAccount(
          precompiled = None,
          balance = UInt256(big(acc.balance)),
          code = Option.when(code.nonEmpty)(code),
          nonce = Some(UInt256(big(acc.nonce))),
          storage = Option.when(storage.nonEmpty)(storage)
        )
      }
      val loaded = GenesisDataLoader
        .over(blockchainReader, blockchainWriter, storagesInstance.storages)
        .loadGenesisData(
          GenesisData(
            nonce = genesisHeader.nonce,
            mixHash = Some(genesisHeader.mixHash.value),
            difficulty = quantity(genesisHeader.difficulty.value),
            extraData = genesisHeader.extraData,
            gasLimit = quantity(genesisHeader.gasLimit.value),
            coinbase = genesisHeader.beneficiary,
            timestamp = quantity(BigInt(genesisHeader.unixTimestamp.toLong)),
            alloc = alloc
          )
        )(using bc)
      val built = blockchainReader.getBlockHeaderByNumber(0)
      val notTheFixtures: Option[String] = loaded.failed.toOption
        .map(refused => s"genesis not loaded: ${refused.getMessage}")
        .orElse(
          Option.when(!built.exists(_.hash == genesisHeader.hash))("the loader's genesis block is not the fixture's")
        )
      notTheFixtures match
        case Some(why) => Outcome(name, Some(why), 0)
        case None      => importAndJudge(name, test, lastBlockHash, bc, node)
    else
      seedOnTheHashLayout(test, bc, node, genesisHeader) match
        case Some(why) => Outcome(name, Some(why), 0)
        case None      => importAndJudge(name, test, lastBlockHash, bc, node)

  /** The hash layout's genesis: the pre-state into the node's own storage, then the fixture's genesis header on it. */
  private def seedOnTheHashLayout(
      test: BlockchainTest,
      bc: BlockchainConfig,
      node: FixtureNode,
      genesisHeader: BlockHeader
  ): Option[String] =
    import node.*

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
    if genesisHeader.stateRoot.value != genesisRoot then Some("genesis pre-state root mismatch")
    else
      blockchainWriter.save(
        Block(genesisHeader, BlockBody(Nil, Nil)),
        Nil,
        ChainWeight.totalDifficultyOnly(genesisHeader.difficulty.value),
        saveAsBestBlock = true
      )
      None

  private def importAndJudge(
      name: String,
      test: BlockchainTest,
      lastBlockHash: Option[ByteString],
      bc: BlockchainConfig,
      node: FixtureNode
  ): Outcome =
    import node.*
    val results = test.blocks.map { tb =>
      val block = Block(
        TestConverter.toBlockHeader(tb.blockHeader),
        BlockBody(tb.transactions.map(TestConverter.toTransaction), tb.uncleHeaders.map(TestConverter.toBlockHeader))
      )
      (
        block,
        consensusAdapter
          .evaluateBranchBlock(block)(using IORuntime.global, bc)
          .unsafeRunSync()(using IORuntime.global)
      )
    }
    val failures = results.collect {
      case (block, BlockImportFailed(err)) => s"block ${block.number} ${block.header.hashAsHexString}: $err"
      // An orphan is not a `BlockImportFailed` any more (B-007), but a vector block whose parent is missing is a failure.
      case (block, UnknownParent) => s"block ${block.number} ${block.header.hashAsHexString}: parent not found"
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
        // the head's state, whichever layout holds it
        blockchainReader.stateAt(h),
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

  private def runCategory(category: String, variant: Variant): Seq[Outcome] =
    val dir = new File(s"$Base/$category")
    assume(dir.isDirectory, s"ethereum/tests fixtures not present at $dir")
    dir.listFiles().filter(_.getName.endsWith(".json")).sortBy(_.getName).toSeq.flatMap { f =>
      val src = Source.fromFile(f)
      val json =
        try parse(src.mkString).fold(e => throw new RuntimeException(e.toString), identity)
        finally src.close()
      json.asObject.get.toList.collect {
        case (name, t) if t.hcursor.downField("network").as[String].toOption.exists(Networks.contains) =>
          runOne(s"${f.getName}/$name", t, variant)
      }
    }

  private def check(category: String, variant: Variant): Unit =
    val outcomes = runCategory(category, variant)
    val failed = outcomes.filter(_.error.isDefined)
    info(
      s"$category: ${outcomes.size - failed.size}/${outcomes.size} passed; " +
        s"${outcomes.map(_.reorgs).sum} reorganisations in ${outcomes.count(_.reorgs > 0)} cases"
    )
    failed.foreach(o => info(s"  FAIL ${o.name}: ${o.error.get}"))
    outcomes should not be empty
    failed.map(o => s"${o.name}: ${o.error.get}") shouldBe empty

  for variant <- Variants do
    s"Fork-choice BlockchainTests on ${variant.layout}, ${variant.pruningName} pruning" should "pass bcMultiChainTest" taggedAs (
      IntegrationTest,
      EthereumTest,
      SlowTest
    ) in {
      check("bcMultiChainTest", variant)
    }

    it should "pass bcTotalDifficultyTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
      check("bcTotalDifficultyTest", variant)
    }

    it should "pass bcForkStressTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
      check("bcForkStressTest", variant)
    }

    it should "pass bcRandomBlockhashTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
      check("bcRandomBlockhashTest", variant)
    }

    // uncles on side branches and uncle races between two chains
    it should "pass bcUncleTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
      check("bcUncleTest", variant)
    }

    it should "pass bcUncleSpecialTests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
      check("bcUncleSpecialTests", variant)
    }
