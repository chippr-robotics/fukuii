package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.BlockParam
import com.chipprbots.ethereum.jsonrpc.EthProofService
import com.chipprbots.ethereum.jsonrpc.EthSimulateJsonMethodsImplicits
import com.chipprbots.ethereum.jsonrpc.EthSimulateService
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager

/** Spec 018 Track C, C2b: the readers behind RPC answer from the position store what they answer from the hash store,
  * on a real chain.
  *
  * The chain is the execution-apis test chain that hive's rpc-compat simulator serves its vectors from, imported in
  * process ([[RpcCompatNode]]). Its head's state is then copied into the position store of the same datadir, with the
  * anchor at the head, and a second node is built over that datadir with the in-development guard on. Every request is
  * sent to both through `JsonRpcController.handleRequest` and the two answers are compared as JSON.
  *
  * This position store holds the head's state and no other: the chain was imported on the hash store, so no changeset
  * record leads back from the head. A request about an earlier block is answered with the error for a state that is not
  * held. (The chain imported on the unified layout, and read behind its head, is `RpcReadersBehindHeadSpec`.)
  */
class RpcReadersOnPositionsSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit
  private given IORuntime = IORuntime.global

  private trait Shared(source: EphemDataSource) extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = source

  /** The node of the hash store: the chain imported, every block executed and validated. */
  private lazy val onHashes = new RpcCompatNode
  private lazy val head = onHashes.importChain()

  /** The same datadir read as the unified layout: the head's state as rows of CF `t` and CF `u`, anchored at the head.
    */
  private lazy val onPositions: RpcCompatNode =
    val source = onHashes.storagesInstance.dataSource
    val nodes = onHashes.storagesInstance.storages.nodeStorage
    val positions = RawTrieWalk.statePositions(
      head.header.stateRoot.value.toArray,
      hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the head's state is not whole"))
    )
    PathStateTestKit.seedPositions(
      source,
      positions,
      BlockRef(head.header.hash.value, head.header.number.value, head.header.stateRoot.value)
    )
    new RpcCompatNode:
      override lazy val storagesInstance
          : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
        new Shared(source) with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
          override protected def statePathLive: Boolean = true

  private def ask(node: RpcCompatNode, method: String, params: JValue*): JValue =
    val request = JsonRpcRequest("2.0", method, Some(JArray(params.toList)), Some(JInt(1)))
    RpcCompatVectors.wire(node.controller.handleRequest(request).unsafeRunSync())

  private def hex(n: Int): JString = JString("0x" + n.toHexString)

  /** Every account of the chain's genesis: senders, contracts with code, a contract with storage, system contracts. */
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

  "The readers behind RPC, on the position store" should "be reading the head's state from rows" taggedAs UnitTest in {
    head.header.number.value should be > BigInt(0)
    onPositions.storagesInstance.storages.pathStateStorage.flatMap(_.anchor).map(_.hash) shouldBe
      Some(head.header.hash.value)
    onHashes.storagesInstance.storages.pathStateStorage shouldBe None
    accounts.size should be >= 30
  }

  it should "answer balance, nonce, code and storage at the head as the hash store does" taggedAs UnitTest in {
    val latest = JString("latest")
    var withBalance, withCode, withStorage = 0
    accounts.foreach { address =>
      if bothAnswer("eth_getBalance", JString(address), latest) \ "result" != JString("0x0") then withBalance += 1
      bothAnswer("eth_getTransactionCount", JString(address), latest)
      if bothAnswer("eth_getCode", JString(address), latest) \ "result" != JString("0x") then withCode += 1
      (0 to 3).foreach { slot =>
        val zero = JString("0x" + "0" * 64)
        if bothAnswer("eth_getStorageAt", JString(address), hex(slot), latest) \ "result" != zero then withStorage += 1
      }
    }
    // the comparison was of something: accounts with a balance, with code, and with storage
    withBalance should be > 0
    withCode should be > 0
    withStorage should be > 0
  }

  // The node's controller answers eth_getProof from a stub, so the two proof services are asked directly.
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

  it should "answer eth_getProof at the head, account and storage proofs, as the hash store does" taggedAs UnitTest in {
    var withStorageProof = 0
    Seq(BlockParam.Latest, BlockParam.WithNumber(head.header.number.value), BlockParam.WithHash(head.header.hash.value))
      .foreach { at =>
        accounts.foreach { address =>
          withClue(s"eth_getProof $address at $at: ") {
            val fromPositions = proof(onPositions, address, 0 to 2, at)
            fromPositions shouldBe proof(onHashes, address, 0 to 2, at)
            fromPositions.isRight shouldBe true
            fromPositions.foreach { account =>
              account.accountProof should not be empty
              if account.storageProof.exists(_.proof.nonEmpty) then withStorageProof += 1
            }
          }
        }
      }
    // the comparison was of something: accounts whose storage trie has nodes to prove a slot with
    withStorageProof should be > 0
  }

  it should "execute eth_call and eth_estimateGas at the head as the hash store does" taggedAs UnitTest in {
    val latest = JString("latest")
    val from = accounts.head
    var executed = 0
    accounts.foreach { to =>
      val call = JObject("from" -> JString(from), "to" -> JString(to), "data" -> JString("0x"))
      val answer = bothAnswer("eth_call", call, latest)
      if (answer \ "result") != JNothing then executed += 1
      bothAnswer("eth_estimateGas", call, latest)
    }
    executed should be > 0
  }

  // A trace replays a block's transactions from the state of its PARENT. So a datadir is read with the anchor one block
  // back from a block that has transactions: the replay has to ask for the parent's state. The replay has two arms,
  // before Amsterdam and from it, so there is one traced block of each kind.
  private def withTransactions(amsterdam: Boolean): Option[Block] =
    RpcCompatNode.blocks.filter { block =>
      block.body.transactionList.nonEmpty &&
      RpcCompatNode.chainConfig.isAmsterdamTimestamp(block.header.unixTimestamp) == amsterdam
    }.lastOption

  /** The chain on a hash store, and the same datadir read as the unified layout anchored at `traced`'s parent. */
  private def anchoredAtParentOf(traced: Block): (RpcCompatNode, RpcCompatNode) =
    anchoredAt(_.blockchainReader.getBlockHeaderByHash(traced.header.parentHash).get)

  /** A fresh pair: the chain on a hash store, and the same datadir read as the unified layout anchored at the block
    * `anchor` picks from it.
    */
  private def anchoredAt(anchor: RpcCompatNode => BlockHeader): (RpcCompatNode, RpcCompatNode) =
    val hashes = new RpcCompatNode
    hashes.importChain()
    val parent = anchor(hashes)
    val source = hashes.storagesInstance.dataSource
    val nodes = hashes.storagesInstance.storages.nodeStorage
    PathStateTestKit.seedPositions(
      source,
      RawTrieWalk.statePositions(
        parent.stateRoot.value.toArray,
        hash =>
          nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the anchor's state is not whole"))
      ),
      BlockRef(parent.hash.value, parent.number.value, parent.stateRoot.value)
    )
    val positions = new RpcCompatNode:
      override lazy val storagesInstance
          : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
        new Shared(source) with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
          override protected def statePathLive: Boolean = true
    (hashes, positions)

  Seq("before Amsterdam" -> false, "from Amsterdam" -> true).foreach { case (era, amsterdam) =>
    it should s"replay a block's transactions from its parent's state, as the hash store does ($era)" taggedAs
      UnitTest in {
        val traced = withTransactions(amsterdam).getOrElse(fail(s"the chain has no block with transactions $era"))
        val (replayOnHashes, replayOnPositions) = anchoredAtParentOf(traced)
        val parent = replayOnHashes.blockchainReader.getBlockHeaderByHash(traced.header.parentHash).get
        replayOnPositions.storagesInstance.storages.pathStateStorage.flatMap(_.anchor).map(_.hash) shouldBe
          Some(parent.hash.value)
        val stxs =
          SignedTransactionWithSender.getSignedTransactionsOfBlock(traced.header, traced.body.transactionList)(using
            RpcCompatNode.chainConfig
          )
        stxs should not be empty

        (0 to stxs.size).foreach { upTo =>
          withClue(s"before transaction $upTo of block ${traced.header.number.value}: ") {
            val onPositions = replayOnPositions.stxLedger.advanceWorldToTx(traced.header, stxs, upTo, parent)
            val onHashes = replayOnHashes.stxLedger.advanceWorldToTx(traced.header, stxs, upTo, parent)
            onPositions.stateRootHash shouldBe onHashes.stateRootHash
            stxs.map(_.senderAddress).distinct.foreach { sender =>
              onPositions.getAccount(sender) shouldBe onHashes.getAccount(sender)
            }
          }
        }
        // the traced block's own state is not the one the position store holds: only its parent's is
        a[StateUnavailable] should be thrownBy replayOnPositions.stxLedger.worldAt(traced.header)
      }
  }

  // eth_simulateV1 persists each simulated block and reads the next from the root it left: two blocks, one state.
  private def simulate(node: RpcCompatNode, payload: String): Either[JsonRpcError, JValue] =
    val service = new EthSimulateService(
      node.blockchain,
      node.blockchainReader,
      node.storagesInstance.storages.evmCodeStorage,
      node.mining.blockPreparator,
      node.mining,
      node.blockchainConfig
    )
    for
      request <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
        Some(JArray(List(parse(payload), JString("latest"))))
      )
      response <- service.ethSimulate(request).unsafeRunSync()
    yield EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(response)

  it should "simulate two blocks at the head, the second on what the first left, as the hash store does" taggedAs
    UnitTest in {
      val from = accounts.head
      val to = accounts(1)
      val transfer = s"""{"from":"$from","to":"$to","value":"0x1"}"""
      val payload = s"""{"blockStateCalls":[{"calls":[$transfer]},{"calls":[$transfer,$transfer]}]}"""
      val onPositionsAnswer = simulate(onPositions, payload)
      onPositionsAnswer shouldBe simulate(onHashes, payload)
      // two simulated blocks came back, with a call in the first and two in the second
      val blocks = onPositionsAnswer.fold(error => fail(s"the simulation was refused: $error"), _.children)
      blocks.map(block => (block \ "calls").children.size) shouldBe List(1, 2)
    }

  /** The raw transactions hive's rpc-compat sends: signed for this chain, by senders the chain funds. */
  private lazy val sendRawRequests: Seq[JValue] =
    val directory = new java.io.File(
      getClass.getClassLoader.getResource(RpcCompatNode.Resources + "eth_sendRawTransaction").toURI
    )
    directory.list().filter(_.endsWith(".io")).sorted.toSeq.flatMap { file =>
      RpcCompatVectors.read(RpcCompatNode.resource(s"eth_sendRawTransaction/$file")).exchanges.map(_._1)
    }

  private def pooled(node: RpcCompatNode): Set[JValue] =
    (ask(node, "txpool_content") \ "result" \ "pending").children.flatMap(_.children).map(_ \ "hash").toSet

  // A transaction that arrives from a peer is judged by the pool itself: it reads the sender's account at the best
  // block, and with no state to read it keeps only senders it already knows. So the same transactions are handed to
  // the pool of a node on each store, as a peer's, and the pools are compared.
  it should "take a peer's transactions into the pool as the hash store does" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
    val (peerOnHashes, peerOnPositions) = anchoredAt(_.blockchainReader.getBestBlock.get.header)
    val fromPeer: Seq[SignedTransaction] = sendRawRequests.flatMap { request =>
      val raw = (request \ "params")(0).asInstanceOf[JString].s
      val (stx, sidecar) = Hex.decode(raw.stripPrefix("0x")).toSignedTransactionWithSidecar
      Option.when(sidecar.isEmpty)(stx) // a blob transaction travels with its sidecar, which this path does not carry
    }
    fromPeer.size should be >= 3
    Seq(peerOnHashes, peerOnPositions).foreach { node =>
      node.pendingTransactionsManager ! PendingTransactionsManager.AddUncheckedTransactions(fromPeer)
    }
    eventually {
      pooled(peerOnHashes) should not be empty
      pooled(peerOnPositions) shouldBe pooled(peerOnHashes)
    }
  }

  // Sent over RPC, a transaction is admitted against the head's state by the transaction service, then pooled.
  it should "admit the same transactions over RPC as the hash store does" taggedAs UnitTest in {
    sendRawRequests should not be empty
    var admitted = 0
    sendRawRequests.foreach { request =>
      val params = (request \ "params").asInstanceOf[JArray].arr
      val answer = bothAnswer("eth_sendRawTransaction", params*)
      if (answer \ "result") != JNothing then admitted += 1
    }
    admitted should be > 0

    eventually {
      pooled(onPositions) should have size admitted
      pooled(onPositions) shouldBe pooled(onHashes)
    }
  }

  // No record leads back from this head: the position store holds one block's state.
  it should "answer a request about an earlier block with the error for a state that is not held" taggedAs UnitTest in {
    val address = JString(accounts.head)
    val notThere = ask(onPositions, "eth_getBalance", address, JString("earliest"))
    (notThere \ "error" \ "code") shouldBe JInt(JsonRpcError.StateNotHeld.code)
    (notThere \ "error" \ "message") shouldBe JString(JsonRpcError.StateNotHeld.message)
    (notThere \ "result") shouldBe JNothing
    // the hash store still answers it
    (ask(onHashes, "eth_getBalance", address, JString("earliest")) \ "error") shouldBe JNothing

    val call = JObject("to" -> address, "data" -> JString("0x"))
    (ask(onPositions, "eth_call", call, JString("earliest")) \ "error" \ "code") shouldBe
      JInt(JsonRpcError.StateNotHeld.code)
    proof(onPositions, accounts.head, Nil, BlockParam.Earliest) shouldBe Left(JsonRpcError.StateNotHeld)
    proof(onHashes, accounts.head, Nil, BlockParam.Earliest).isRight shouldBe true
  }
