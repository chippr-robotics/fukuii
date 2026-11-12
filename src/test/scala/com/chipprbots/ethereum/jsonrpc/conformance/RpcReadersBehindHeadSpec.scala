package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.BlockParam
import com.chipprbots.ethereum.jsonrpc.DebugTracingService
import com.chipprbots.ethereum.jsonrpc.DebugTracingService.TraceBlockByNumberRequest
import com.chipprbots.ethereum.jsonrpc.DebugTracingService.TraceConfig
import com.chipprbots.ethereum.jsonrpc.DebugTracingService.TraceTransactionRequest
import com.chipprbots.ethereum.jsonrpc.EthProofService
import com.chipprbots.ethereum.jsonrpc.EthSimulateJsonMethodsImplicits
import com.chipprbots.ethereum.jsonrpc.EthSimulateService
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.jsonrpc.StateRead
import com.chipprbots.ethereum.jsonrpc.TraceService
import com.chipprbots.ethereum.jsonrpc.TraceService.TraceBlockRequest
import com.chipprbots.ethereum.jsonrpc.TraceService.TraceFilterRequest
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C4a part 4: the readers behind RPC answer about a block '''behind the head''' from the unified
  * layout what they answer from the hash store, on a real chain.
  *
  * The chain is the execution-apis test chain of hive's rpc-compat simulator ([[RpcCompatNode]]), imported twice from
  * its genesis: on a hash store, and on the unified layout, where every block is committed in place with its changeset
  * record. The disk of the second holds the head's state and no other; the state of a block behind it is made from the
  * records. Every request is sent to both through `JsonRpcController.handleRequest`, and the answers are compared as
  * JSON.
  */
class RpcReadersBehindHeadSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit
  private given IORuntime = IORuntime.global

  private trait Fresh extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = EphemDataSource()

  private lazy val onHashes: RpcCompatNode =
    val node = new RpcCompatNode
    node.importChain()
    node
  private lazy val head: Block = onHashes.blockchainReader.getBestBlock.get

  private lazy val onPositions: RpcCompatNode =
    val node = new RpcCompatNode:
      override lazy val storagesInstance
          : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
        new Fresh with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
          override protected def statePathLive: Boolean = true
    // as the node imports on the unified layout: the genesis committed as rows, then every block executed on a
    // staged layer and committed in place with its record
    given BlockchainConfig = node.blockchainConfig
    GenesisDataLoader
      .over(node.blockchainReader, node.blockchainWriter, node.storagesInstance.storages)
      .loadGenesisData()
    val execution = new HeaderTrustingBlockExecution(
      node.blockchain,
      node.blockchainReader,
      node.blockchainWriter,
      node.storagesInstance.storages.evmCodeStorage,
      node.mining.blockPreparator,
      new BlockValidation(node.mining, node.blockchainReader, node.blockQueue)
    )
    RpcCompatNode.blocks.foreach { block =>
      val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true) match
        case (_, Some(error)) => throw new IllegalStateException(s"block ${block.header.number} not imported: $error")
        case _                => ()
    }
    node

  private def state: PathStateStorage = onPositions.storagesInstance.storages.pathStateStorage.get

  private def ask(node: RpcCompatNode, method: String, params: JValue*): JValue =
    val request = JsonRpcRequest("2.0", method, Some(JArray(params.toList)), Some(JInt(1)))
    RpcCompatVectors.wire(node.controller.handleRequest(request).unsafeRunSync())

  private def hex(n: BigInt): JString = JString("0x" + n.toString(16))

  private lazy val accounts: Seq[String] =
    (parse(RpcCompatNode.resource("genesis.json")) \ "alloc")
      .asInstanceOf[JObject]
      .obj
      .map("0x" + _._1.stripPrefix("0x"))

  private def bothAnswer(method: String, params: JValue*): JValue =
    val fromPositions = ask(onPositions, method, params*)
    withClue(s"$method ${compact(render(JArray(params.toList)))}: ") {
      fromPositions shouldBe ask(onHashes, method, params*)
    }
    fromPositions

  /** The blocks behind the head that are asked about: one back, two, eight, and deeper. */
  private lazy val behind: Seq[BigInt] =
    Seq(1, 2, 8, 20, 40).map(head.header.number.value - _).filter(_ > 0)

  "The chain imported on the unified layout" should "hold its head's state on disk, the same head as the hash store's" taggedAs
    UnitTest in {
      head.header.number.value should be > BigInt(40)
      onPositions.blockchainReader.getBestBlock.map(_.header.hash) shouldBe Some(head.header.hash)
      state.anchor.map(_.hash) shouldBe Some(head.header.hash.value)
      onHashes.storagesInstance.storages.pathStateStorage shouldBe None
      behind should have size 5
    }

  "The readers behind RPC, about a block behind the head" should "answer balance, nonce, code and storage as the hash store does" taggedAs
    UnitTest in {
      var differsFromHead = 0
      behind.foreach { number =>
        val at = hex(number)
        accounts.foreach { address =>
          val balance = bothAnswer("eth_getBalance", JString(address), at)
          (balance \ "error") shouldBe JNothing
          if balance != ask(onPositions, "eth_getBalance", JString(address), JString("latest")) then
            differsFromHead += 1
          bothAnswer("eth_getTransactionCount", JString(address), at)
          bothAnswer("eth_getCode", JString(address), at)
          (0 to 3).foreach(slot => bothAnswer("eth_getStorageAt", JString(address), hex(slot), at))
        }
      }
      // the comparison was of something: states that are not the head's
      differsFromHead should be > 0
      // and nothing is held of them once they are answered
      state.readsBehindInFlight shouldBe 0
    }

  private def proofService(node: RpcCompatNode): EthProofService =
    new EthProofService(
      node.blockchain,
      node.blockchainReader,
      node.mining.blockGenerator,
      node.blockchainConfig.ethCompatibleStorage
    )

  private def proof(node: RpcCompatNode, address: String, slots: Seq[Int], at: BlockParam) =
    proofService(node)
      .getProof(
        GetProofRequest(
          Address(Hex.decode(address.stripPrefix("0x"))),
          slots.map(slot => StorageProofKey(BigInt(slot))),
          at
        )
      )
      .unsafeRunSync()
      .map(_.proofAccount)

  it should "answer eth_getProof, account and storage proofs, as the hash store does" taggedAs UnitTest in {
    behind.foreach { number =>
      val hash = onHashes.blockchainReader.getBlockHeaderByNumber(number).get.hash.value
      Seq(BlockParam.WithNumber(number), BlockParam.WithHash(hash)).foreach { at =>
        accounts.foreach { address =>
          withClue(s"eth_getProof $address at $at: ") {
            val fromPositions = proof(onPositions, address, 0 to 2, at)
            fromPositions shouldBe proof(onHashes, address, 0 to 2, at)
            fromPositions.isRight shouldBe true
          }
        }
      }
    }
    state.readsBehindInFlight shouldBe 0
  }

  it should "execute eth_call, eth_estimateGas and eth_createAccessList as the hash store does" taggedAs UnitTest in {
    val from = accounts.head
    var executed = 0
    behind.foreach { number =>
      val at = hex(number)
      accounts.foreach { to =>
        val call = JObject("from" -> JString(from), "to" -> JString(to), "data" -> JString("0x"))
        val answer = bothAnswer("eth_call", call, at)
        if (answer \ "result") != JNothing then executed += 1
        bothAnswer("eth_estimateGas", call, at)
        bothAnswer("eth_createAccessList", call, at)
      }
    }
    executed should be > 0
    state.readsBehindInFlight shouldBe 0
  }

  // The node's controller has no tracing service, so the services are built over each node and asked directly. A block
  // is traced on its PARENT's state: tracing the head itself is a read behind the head.
  private def debug(node: RpcCompatNode): DebugTracingService =
    new DebugTracingService(
      node.blockchain,
      node.blockchainReader,
      node.mining,
      node.stxLedger,
      node.storagesInstance.storages.transactionMappingStorage
    )

  private def traces(node: RpcCompatNode): TraceService =
    new TraceService(
      node.blockchain,
      node.blockchainReader,
      node.mining,
      node.stxLedger,
      node.storagesInstance.storages.transactionMappingStorage
    )

  private lazy val traced: Seq[Block] = RpcCompatNode.blocks.filter(_.body.transactionList.nonEmpty).takeRight(6)

  it should "replay a block's transactions on its parent's state as the hash store does, the head's too" taggedAs
    UnitTest in {
      traced should not be empty
      traced.foreach { block =>
        val parent = onHashes.blockchainReader.getBlockHeaderByHash(block.header.parentHash).get
        val stxs =
          SignedTransactionWithSender.getSignedTransactionsOfBlock(block.header, block.body.transactionList)(using
            RpcCompatNode.chainConfig
          )
        (0 to stxs.size).foreach { upTo =>
          withClue(s"before transaction $upTo of block ${block.header.number.value}: ") {
            val onHashesWorld = onHashes.stxLedger.advanceWorldToTx(block.header, stxs, upTo, parent)
            // everything that reads through the state is done inside the scope
            StateRead.scoped {
              val world = onPositions.stxLedger.advanceWorldToTx(block.header, stxs, upTo, parent)
              world.stateRootHash shouldBe onHashesWorld.stateRootHash
              stxs.map(_.senderAddress).distinct.foreach { sender =>
                world.getAccount(sender) shouldBe onHashesWorld.getAccount(sender)
              }
            }
          }
        }
      }
      state.readsBehindInFlight shouldBe 0
    }

  it should "replay a range of more blocks than may be read behind the head at once, each in a scope of its own" taggedAs
    UnitTest in {
      val to = head.header.number.value
      val range = RpcCompatNode.blocks.filter(block => block.header.number.value > to - 12)
      range should have size 12
      var replayed = 0
      range.foreach { block =>
        val parent = onHashes.blockchainReader.getBlockHeaderByHash(block.header.parentHash).get
        val stxs =
          SignedTransactionWithSender.getSignedTransactionsOfBlock(block.header, block.body.transactionList)(using
            RpcCompatNode.chainConfig
          )
        // as debug_traceChain and trace_filter read: the block's state is held for that block alone
        StateRead.scoped {
          val world = onPositions.stxLedger.advanceWorldToTx(block.header, stxs, stxs.size, parent)
          world.stateRootHash shouldBe
            onHashes.stxLedger.advanceWorldToTx(block.header, stxs, stxs.size, parent).stateRootHash
          state.readsBehindInFlight should be <= 1
          replayed += 1
        }
        state.readsBehindInFlight shouldBe 0
      }
      replayed shouldBe 12
    }

  // The tracing services read the chain rules of the node's own configuration and not this test chain's, so on this
  // chain they recover no sender and trace no transaction: what holds a replay to the hash store is the two cases
  // above, at the ledger. What is held here is that the services answer on the unified layout what they answer on the
  // hash store, about blocks behind the head and about a range longer than the reads that may be in flight, and hold
  // nothing when they have answered.
  it should "answer the tracing services' requests about blocks behind the head as the hash store does" taggedAs
    UnitTest in {
      traced.foreach { block =>
        val at = BlockParam.WithNumber(block.header.number.value)
        withClue(s"block ${block.header.number.value}: ") {
          val ofBlock = debug(onPositions).traceBlockByNumber(TraceBlockByNumberRequest(at)).unsafeRunSync()
          ofBlock shouldBe debug(onHashes).traceBlockByNumber(TraceBlockByNumberRequest(at)).unsafeRunSync()
          ofBlock.isRight shouldBe true
          val hash = block.body.transactionList.head.hash.value
          debug(onPositions).traceTransaction(TraceTransactionRequest(hash)).unsafeRunSync() shouldBe
            debug(onHashes).traceTransaction(TraceTransactionRequest(hash)).unsafeRunSync()
          traces(onPositions).traceBlock(TraceBlockRequest(at)).unsafeRunSync() shouldBe
            traces(onHashes).traceBlock(TraceBlockRequest(at)).unsafeRunSync()
        }
      }
      val to = head.header.number.value
      val (from, until) = (BlockParam.WithNumber(to - 12), BlockParam.WithNumber(to))
      val range = debug(onPositions).traceChainBlockRange(from, until, TraceConfig()).unsafeRunSync()
      range shouldBe debug(onHashes).traceChainBlockRange(from, until, TraceConfig()).unsafeRunSync()
      range.map(_.size) shouldBe Right(12)
      val filtered = traces(onPositions).traceFilter(TraceFilterRequest(from, until)).unsafeRunSync()
      filtered shouldBe traces(onHashes).traceFilter(TraceFilterRequest(from, until)).unsafeRunSync()
      filtered.isRight shouldBe true
      state.readsBehindInFlight shouldBe 0
    }

  it should "be refused the fifth block of one scope, and told to ask again: a range reads its blocks one at a time" taggedAs
    UnitTest in {
      val headers =
        (1 to 5).map(back => onHashes.blockchainReader.getBlockHeaderByNumber(head.header.number.value - back).get)
      StateRead.scoped {
        headers.take(4).foreach(onPositions.blockchainReader.stateAt)
        state.readsBehindInFlight shouldBe 4
        (the[StateUnavailable] thrownBy onPositions.blockchainReader.stateAt(headers(4))).askAgain shouldBe true
      }
      state.readsBehindInFlight shouldBe 0
      // over RPC that is an error that says to ask again, and no execution error
      StateRead.at(onPositions.blockchainReader, headers(4))(_ => ()) shouldBe Right(())
      // and a caller in no scope reads the head and no other block
      a[StateUnavailable] should be thrownBy onPositions.blockchainReader.stateAt(headers.head)
      a[StateUnavailable] should be thrownBy onPositions.blockchainReader.scratchStateAt(headers.head)
      onPositions.blockchainReader.stateAt(head.header)
    }

  private def simulate(node: RpcCompatNode, payload: String, at: JValue): Either[JsonRpcError, JValue] =
    val service = new EthSimulateService(
      node.blockchain,
      node.blockchainReader,
      node.storagesInstance.storages.evmCodeStorage,
      node.mining.blockPreparator,
      node.mining,
      node.blockchainConfig
    )
    for
      request <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(Some(JArray(List(parse(payload), at))))
      response <- service.ethSimulate(request).unsafeRunSync()
    yield EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(response)

  it should "simulate two blocks on a block behind the head, the second on what the first left, as the hash store does" taggedAs
    UnitTest in {
      val transfer = s"""{"from":"${accounts.head}","to":"${accounts(1)}","value":"0x1"}"""
      val payload = s"""{"blockStateCalls":[{"calls":[$transfer]},{"calls":[$transfer,$transfer]}]}"""
      behind.take(3).foreach { number =>
        withClue(s"on block $number: ") {
          val answer = simulate(onPositions, payload, hex(number))
          answer shouldBe simulate(onHashes, payload, hex(number))
          answer.fold(error => fail(s"the simulation was refused: $error"), _.children).map { block =>
            (block \ "calls").children.size
          } shouldBe List(1, 2)
        }
      }
      state.readsBehindInFlight shouldBe 0
    }

  it should "say that the state is not held for an executed block that is no block of the chain" taggedAs UnitTest in {
    // a block number past the head is no block
    val past = ask(onPositions, "eth_getBalance", JString(accounts.head), hex(head.header.number.value + 5))
    past shouldBe ask(onHashes, "eth_getBalance", JString(accounts.head), hex(head.header.number.value + 5))
    (past \ "error" \ "code") should not be JInt(JsonRpcError.StateBusy.code)
  }
