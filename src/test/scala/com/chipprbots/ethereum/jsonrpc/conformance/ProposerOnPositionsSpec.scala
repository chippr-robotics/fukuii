package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.implicits.global

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.consensus.engine.BuiltBlock
import com.chipprbots.ethereum.consensus.engine.EngineApiService
import com.chipprbots.ethereum.consensus.engine.ForkChoiceState
import com.chipprbots.ethereum.consensus.engine.PayloadAttributes
import com.chipprbots.ethereum.consensus.engine.PayloadStatus
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.jsonrpc.TestingService
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockResult
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C3c (T102, T106, T111, T114): a block is BUILT on the unified state layout from its parent's state
  * under a layer of the build's own, and nothing of the build reaches the disk.
  *
  * The chain is the execution-apis test chain ([[RpcCompatNode]]), from the merge to Amsterdam: withdrawals, the system
  * calls of each fork, deposit and request contracts, block access lists. Each of its post-merge blocks is built again
  * on the unified layout by the node's one builder (`EngineApiService.buildBlockOnParent`), from the block's own
  * inputs, and must come out as that block: the same hash, so the same state root, receipts root, requests hash and
  * access-list hash. The block is then imported, which executes it once more on a staged layer and commits it; so the
  * builder is held to the fixture and to import at every height.
  *
  * Around each build the whole datadir is compared: no row of the position store, no node of the hash store, no
  * changeset record, no AppState key, no block. The one thing a build writes is contract code, which is kept by its
  * hash on both layouts and is the same bytes import writes.
  */
class ProposerOnPositionsSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit
  private given org.apache.pekko.actor.typed.Scheduler = system.scheduler

  private trait Shared(source: EphemDataSource) extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = source

  /** Block execution that counts what the builder asks of it, and throws what a test queued before it answers. */
  final private class Watched(node: RpcCompatNode)
      extends HeaderTrustingBlockExecution(
        node.blockchain,
        node.blockchainReader,
        node.blockchainWriter,
        node.storagesInstance.storages.evmCodeStorage,
        node.mining.blockPreparator,
        new BlockValidation(node.mining, node.blockchainReader, node.blockQueue)
      ):
    @volatile var proposerCalls = 0
    @volatile var faults: List[Throwable] = Nil

    /** Failures of an execution that no transaction explains, each answered once in place of the execution. */
    @volatile var refusals: List[BlockExecutionError] = Nil
    override def executeForProposer(
        block: Block
    )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
      proposerCalls += 1
      faults match
        case next :: rest =>
          faults = rest
          throw next
        case Nil =>
          refusals match
            case refusal :: rest =>
              refusals = rest
              Left(refusal)
            case Nil => super.executeForProposer(block)

  private val chain: Seq[Block] = RpcCompatNode.blocks
  private def proofOfStake(block: Block): Boolean = block.header.difficulty.value == 0

  /** A node on the unified layout that holds the first `held` blocks of the chain, the last of them as its state. */
  final private class Rig(held: Int):
    val source: EphemDataSource = EphemDataSource()
    val node: RpcCompatNode = new RpcCompatNode:
      override lazy val storagesInstance
          : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
        new Shared(source) with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
          override protected def statePathLive: Boolean = true
    given BlockchainConfig = node.blockchainConfig
    GenesisDataLoader
      .over(node.blockchainReader, node.blockchainWriter, node.storagesInstance.storages)
      .loadGenesisData()
    val execution = new Watched(node)
    def pathState = node.storagesInstance.storages.pathStateStorage.get
    val engine = new EngineApiService(
      node.blockchainReader,
      node.blockchainWriter,
      execution,
      node.forkChoiceManager,
      None,
      stateHeldInPlace = Some(() => pathState.anchor.map(_.hash))
    )

    def importBlock(block: Block): Option[BlockExecutionError] =
      val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true)._2

    chain.take(held).foreach(block => importBlock(block) shouldBe None)

    def head: BlockHeader = node.blockchainReader.getBestBlockHeader.get
    def refOf(header: BlockHeader): BlockRef = BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

    /** Every key of the datadir with its value, but for contract code. */
    def allButCode: Map[ByteString, ByteString] = source.storage.collect {
      case (key, value) if key.array().head != Namespaces.CodeNamespace.head =>
        ByteString(key.array()) -> ByteString(value)
    }
    def code: Map[ByteString, ByteString] = source.storage.collect {
      case (key, value) if key.array().head == Namespaces.CodeNamespace.head =>
        ByteString(key.array().drop(1)) -> ByteString(value)
    }

    /** `block`'s own inputs, as the consensus client would give them for a build on its parent. */
    def attributesOf(block: Block): PayloadAttributes =
      val h = block.header
      PayloadAttributes(
        timestamp = h.unixTimestamp.toLong,
        prevRandao = h.mixHash.value,
        suggestedFeeRecipient = Address(h.beneficiary),
        withdrawals = block.body.withdrawals,
        parentBeaconBlockRoot = h.parentBeaconBlockRoot.map(_.value),
        slotNumber = h.slotNumber
      )

    def build(block: Block, transactions: Seq[SignedTransaction], strict: Boolean): Either[String, BuiltBlock] =
      val parent = node.blockchainReader.getBlockByHash(block.header.parentHash).get
      engine.buildBlockOnParent(
        parent,
        attributesOf(block),
        transactions,
        block.header.extraData,
        block.header.gasLimit,
        strict
      )

  private val zero = ByteString(new Array[Byte](32))

  // ─── T102: the builder is the fixture, and writes nothing ──────────────────────────────────────────────────────────

  "A block built on the unified layout" should "be the block the chain has, at every height, and leave the datadir as it was" taggedAs
    UnitTest in {
      val rig = new Rig(held = 0)
      var built = 0
      var withAccessList = 0
      var withWithdrawals = 0
      var lenientDrops = 0
      chain.zipWithIndex.foreach { (block, index) =>
        withClue(s"block ${block.header.number}: ") {
          if proofOfStake(block) then
            val before = rig.allButCode
            val codeBefore = rig.code
            val epoch = rig.pathState.clock.epoch
            val anchor = rig.pathState.anchor

            // the testing namespace's build: a transaction that cannot be applied is an error
            val strict = rig.build(block, block.body.transactionList, strict = true)
            strict.map(_.block.header.hash) shouldBe Right(block.header.hash)
            strict.toOption.get.block.header.blockAccessListHash shouldBe
              strict.toOption.get.blockAccessList.map(_.hash)

            // the engine's build: a transaction that cannot be applied is left out, and the list executed again.
            // One that an earlier block included is such a transaction, here and in a pool that is behind the head.
            val included = chain.take(index).flatMap(_.body.transactionList).lastOption
            val executions = rig.execution.proposerCalls
            val lenient = rig.build(block, block.body.transactionList ++ included, strict = false)
            lenient.map(_.block.header.hash) shouldBe Right(block.header.hash)
            // the execution that meets the transaction and fails, and the one of the list without it. Where the
            // block has transactions that is what the hash says: an empty payload would be another block.
            rig.execution.proposerCalls - executions shouldBe 1 + included.size
            if block.body.transactionList.nonEmpty then lenientDrops += included.size

            // nothing of either build is on disk, and the state on disk has not moved
            rig.allButCode shouldBe before
            rig.pathState.clock.epoch shouldBe epoch
            rig.pathState.anchor shouldBe anchor
            new ChangesetStorage(rig.source).get(block.header.hash.value) shouldBe None
            PathStateTestKit.hashStoreRows(rig.source) shouldBe empty
            // but for contract code, which is kept by its hash
            (rig.code -- codeBefore.keys).foreach((hash, bytes) => ByteString(kec256(bytes)) shouldBe hash)
            codeBefore.foreach((hash, bytes) => rig.code.get(hash) shouldBe Some(bytes))

            built += 1
            if strict.toOption.get.blockAccessList.isDefined then withAccessList += 1
            if block.body.withdrawals.exists(_.nonEmpty) then withWithdrawals += 1
          // and import executes the block once more, on a staged layer, and commits it
          rig.importBlock(block) shouldBe None
          rig.pathState.anchor shouldBe Some(rig.refOf(block.header))
        }
      }
      // the chain has what the test says it covers
      built should be > 20
      withAccessList should be > 0
      withWithdrawals should be > 0
      lenientDrops should be > 0
    }

  // ─── T106: a view that goes stale, and a state that is not held ────────────────────────────────────────────────────

  private def stale(rig: Rig): StaleStateViewException =
    new StaleStateViewException(rig.refOf(rig.head), 2L, 40L, "the log of state moves no longer reaches back to it")

  "A build whose view went stale" should "be made again on a fresh view, with the list it had" taggedAs UnitTest in {
    val rig = new Rig(held = chain.size - 1)
    val block = chain.last
    rig.execution.faults = List(stale(rig))
    rig.build(block, block.body.transactionList, strict = false).map(_.block.header.hash) shouldBe
      Right(block.header.hash)
    rig.execution.proposerCalls shouldBe 2
  }

  it should "end as a fault of the state, and never as an empty payload, when no fresh view holds" taggedAs UnitTest in {
    val rig = new Rig(held = chain.size - 1)
    val block = chain.last
    val before = rig.allButCode
    rig.execution.faults = List.fill(20)(stale(rig))
    a[StaleStateViewException] should be thrownBy rig.build(block, block.body.transactionList, strict = false)
    // the first attempt, and three on fresh views: not one more, and no attempt at the empty payload
    rig.execution.proposerCalls shouldBe 4
    rig.allButCode shouldBe before
  }

  // A failure of the execution that no transaction explains (a system call, the layout of a deposit log) gives the
  // empty payload, on this layout as on the other: that is a block, built on the parent's state like any other.
  "A lenient build whose execution fails for no transaction's fault" should "be the empty payload, which import takes, and write nothing" taggedAs
    UnitTest in {
      val index = chain.lastIndexWhere(block => proofOfStake(block) && block.body.transactionList.nonEmpty)
      val rig = new Rig(held = index)
      val block = chain(index)
      val before = rig.allButCode
      rig.execution.refusals = List(BlockExecutionError.ValidationAfterExecError("a failure no transaction explains"))
      val built = rig.build(block, block.body.transactionList, strict = false).fold(why => fail(why), identity)
      built.block.body.transactionList shouldBe empty
      built.block.header.parentHash shouldBe block.header.parentHash
      // the list that failed, then the empty one, which was executed: its header is one import computes
      rig.execution.proposerCalls shouldBe 2
      rig.allButCode shouldBe before
      rig.importBlock(built.block) shouldBe None
      rig.pathState.anchor shouldBe Some(rig.refOf(built.block.header))
    }

  "A build on a block whose state is not held" should "be refused as that, strict or lenient" taggedAs UnitTest in {
    val rig = new Rig(held = chain.size - 1)
    val before = rig.allButCode
    // the parent of this block is one behind the block the disk holds
    val earlier = chain(chain.size - 2)
    a[StateUnavailable] should be thrownBy rig.build(earlier, earlier.body.transactionList, strict = false)
    a[StateUnavailable] should be thrownBy rig.build(earlier, earlier.body.transactionList, strict = true)
    a[StateUnavailable] should be thrownBy rig.build(earlier, Nil, strict = false)
    rig.allButCode shouldBe before
  }

  "forkchoiceUpdated with attributes, on the unified layout" should "build a payload on the block the disk holds, which import then takes" taggedAs
    UnitTest in {
      val rig = new Rig(held = chain.size - 1)
      val head = rig.head
      val before = rig.allButCode
      val answer = rig.engine
        .forkchoiceUpdated(ForkChoiceState(head.hash.value, zero, zero), Some(rig.attributesOf(chain.last)))
        .unsafeRunSync()
      answer.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      val id = answer.toOption.flatMap(_.payloadId).getOrElse(fail("no payload id"))
      val payload = rig.engine.getPayload(id).unsafeRunSync().fold(why => fail(why), identity)
      payload.header.parentHash shouldBe head.hash
      payload.header.number.value shouldBe head.number.value + 1
      // nothing was written by the build, and the head is where it was
      rig.allButCode shouldBe before
      rig.head.hash shouldBe head.hash
      // the payload is a block import executes to the header the builder gave it
      rig.importBlock(payload) shouldBe None
      rig.pathState.anchor shouldBe Some(rig.refOf(payload.header))
    }

  it should "answer that no payload was built, with no id, when the state gives none" taggedAs UnitTest in {
    val rig = new Rig(held = chain.size - 1)
    val head = rig.head
    val before = rig.allButCode
    val ask = () =>
      rig.engine
        .forkchoiceUpdated(ForkChoiceState(head.hash.value, zero, zero), Some(rig.attributesOf(chain.last)))
        .unsafeRunSync()

    // the parent's state stopped being held under the build: said once, with no retry
    rig.execution.faults = List(new StateUnavailable("the disk has moved on"))
    val unheld = ask()
    unheld.left.map(_.take(5)) shouldBe Left("ATTR:")
    unheld.left.toOption.get should include("no payload could be built")
    rig.execution.proposerCalls shouldBe 1

    // a view that stays stale: the same answer, after the bound
    rig.execution.faults = List.fill(20)(stale(rig))
    ask().left.map(_.take(5)) shouldBe Left("ATTR:")
    rig.execution.proposerCalls shouldBe 5

    // the head is set and stays set, nothing is on disk, and no sealed-over-its-parent block was kept
    rig.head.hash shouldBe head.hash
    rig.pathState.anchor shouldBe Some(rig.refOf(head))
    rig.allButCode shouldBe before

    // and with the state back, the same attributes get their payload
    rig.execution.faults = Nil
    val built = ask()
    built.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
    val payload = rig.engine.getPayload(built.toOption.flatMap(_.payloadId).get).unsafeRunSync().toOption.get
    payload.header.stateRoot should not be head.stateRoot
    rig.importBlock(payload) shouldBe None
  }

  // ─── T111: the pool is judged against the parent's state ───────────────────────────────────────────────────────────

  "The transactions offered to a build" should "be held to the parent's state as the unified layout holds it" taggedAs
    UnitTest in {
      // the last block of the chain that carries transactions, on a node that holds its parent
      val index = chain.lastIndexWhere(block => proofOfStake(block) && block.body.transactionList.nonEmpty)
      index should be > 0
      val rig = new Rig(held = index)
      val block = chain(index)
      val included = chain.take(index).flatMap(_.body.transactionList).last
      // what the block has is executable at its parent; what an earlier block included no longer is
      rig.engine.executableAtParent(rig.head, block.body.transactionList :+ included) shouldBe
        block.body.transactionList
      rig.engine.executableAtParent(rig.head, Seq(included)) shouldBe empty
      // and a state that is not held is the build's to answer, not a reason to take the pool as it is
      a[StateUnavailable] should be thrownBy
        rig.engine.executableAtParent(chain(index - 2).header, block.body.transactionList)
    }

  // ─── T114: testing_commitBlockV1 commits by importing ──────────────────────────────────────────────────────────────

  private def testing(rig: Rig, importInPlace: Block => Either[String, Unit]): TestingService =
    import rig.given
    new TestingService(
      rig.engine,
      rig.node.blockchainReader,
      rig.node.blockchainWriter,
      rig.node.forkChoiceManager,
      None,
      None,
      BigInt(60000000),
      Some(importInPlace)
    )

  "testing_commitBlockV1 on the unified layout" should "commit the block it built by importing it: one batch, the state with the block" taggedAs
    UnitTest in {
      val rig = new Rig(held = chain.size - 1)
      import rig.given
      val head = rig.head
      val service = testing(rig, TestingService.importInPlace(rig.node.blockchainReader, rig.execution))
      val request = TestingService.CommitBlockRequest(rig.attributesOf(chain.last), Some(Nil), None)
      val committed = service.commitBlock(request).unsafeRunSync().fold(error => fail(error.message), _.blockHash)
      val now = rig.head
      now.hash.value shouldBe committed
      now.parentHash shouldBe head.hash
      rig.pathState.anchor shouldBe Some(rig.refOf(now))
      new ChangesetStorage(rig.source).get(committed).flatMap(_.toOption).map(_.parentHash) shouldBe
        Some(head.hash.value)
      PathStateTestKit.hashStoreRows(rig.source) shouldBe empty
    }

  it should "commit two in a row, the head moved with the state each time: the fork choice after a commit writes the head the state is at" taggedAs
    UnitTest in {
      val rig = new Rig(held = chain.size - 3)
      import rig.given
      val service = testing(rig, TestingService.importInPlace(rig.node.blockchainReader, rig.execution))
      chain.takeRight(2).foreach { block =>
        val head = rig.head
        val request = TestingService.CommitBlockRequest(rig.attributesOf(block), Some(Nil), None)
        val committed = service.commitBlock(request).unsafeRunSync().fold(error => fail(error.message), _.blockHash)
        val now = rig.head
        now.hash.value shouldBe committed
        now.parentHash shouldBe head.hash
        rig.pathState.anchor shouldBe Some(rig.refOf(now))
        rig.node.storagesInstance.storages.appStateStorage.getBestBlockInfo().hash shouldBe committed
        // with no beacon chain behind it, a commit names no finalized block, and so refuses no later rewind (C4d)
        rig.pathState.finalizedBlock shouldBe None
      }
    }

  it should "leave the head and the disk as they were when import refuses the block" taggedAs UnitTest in {
    val rig = new Rig(held = chain.size - 1)
    val head = rig.head
    val before = rig.allButCode
    val service = testing(rig, _ => Left("refused for the test"))
    val request = TestingService.CommitBlockRequest(rig.attributesOf(chain.last), Some(Nil), None)
    val refused = service.commitBlock(request).unsafeRunSync()
    refused.left.map(_.message) shouldBe Left("the block that was built was refused by import: refused for the test")
    rig.head.hash shouldBe head.hash
    rig.pathState.anchor shouldBe Some(rig.refOf(head))
    rig.allButCode shouldBe before
  }
