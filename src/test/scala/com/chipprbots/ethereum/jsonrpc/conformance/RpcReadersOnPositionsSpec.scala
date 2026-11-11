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
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.BlockParam
import com.chipprbots.ethereum.jsonrpc.EthProofService
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b: the readers behind RPC answer from the position store what they answer from the hash store,
  * on a real chain.
  *
  * The chain is the execution-apis test chain that hive's rpc-compat simulator serves its vectors from, imported in
  * process ([[RpcCompatNode]]). Its head's state is then copied into the position store of the same datadir, with the
  * anchor at the head, and a second node is built over that datadir with the in-development guard on. Every request is
  * sent to both through `JsonRpcController.handleRequest` and the two answers are compared as JSON.
  *
  * The position store holds the head's state and no other until the overlay (C4a): a request about an earlier block is
  * answered with the error for a state that is not there.
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

  // Until the overlay (C4a) the position store holds one block's state.
  it should "answer a request about an earlier block with the error for a state that is not there" taggedAs UnitTest in {
    val address = JString(accounts.head)
    val notThere = ask(onPositions, "eth_getBalance", address, JString("earliest"))
    (notThere \ "error" \ "code") shouldBe JInt(3)
    (notThere \ "result") shouldBe JNothing
    // the hash store still answers it
    (ask(onHashes, "eth_getBalance", address, JString("earliest")) \ "error") shouldBe JNothing

    val call = JObject("to" -> address, "data" -> JString("0x"))
    (ask(onPositions, "eth_call", call, JString("earliest")) \ "error" \ "code") shouldBe JInt(3)
    proof(onPositions, accounts.head, Nil, BlockParam.Earliest) shouldBe Left(JsonRpcError.NodeNotFound)
    proof(onHashes, accounts.head, Nil, BlockParam.Earliest).isRight shouldBe true
  }
