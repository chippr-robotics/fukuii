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
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.BlockParam
import com.chipprbots.ethereum.jsonrpc.EthProofService
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.jsonrpc.ResolveBlock
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** B-210: a block that is imported and not yet the head, as one a consensus client's `newPayload` was answered VALID
  * for, is above the best block. The methods that read a block's state answer it as geth and core-geth do
  * (`StateAndHeaderByNumberOrHash`):
  *
  *   - '''by its hash''', from its state, which the node holds: on the hash store under its state root, on the unified
  *     layout as the forward layer it is kept as beside the disk;
  *   - '''by its number''', `-32000 header not found`: a number is resolved through the canonical chain, which ends at
  *     the best block.
  *
  * Until B-210 the account readers answered it, by hash, with no account (a zero balance and nonce, no code, empty
  * storage) on the hash store, and with "state not held" on the unified layout; and by number with `-32602 Block n not
  * found`.
  *
  * The chain is hive's rpc-compat chain ([[RpcCompatNode]]). The reference imports all of it, each block adopted; each
  * node under test imports it up to the last block that has transactions, adopting each block but that one.
  */
class RpcReadsAboveBestSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit
  private given IORuntime = IORuntime.global

  private trait Fresh extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = EphemDataSource()

  /** The block above the best block: the last of the chain with transactions, so that it changes its senders' nonces.
    */
  private lazy val above: Block = RpcCompatNode.blocks.filter(_.body.transactionList.nonEmpty).last
  private def number: BigInt = above.header.number.value

  private lazy val reference: RpcCompatNode =
    val node = new RpcCompatNode
    node.importChain()
    node

  /** A node that imports the chain up to [[above]], every block adopted but that one, which is executed and stored
    * without becoming the head.
    */
  private def importedUpTo(guard: Boolean): RpcCompatNode =
    val node =
      if !guard then new RpcCompatNode
      else
        new RpcCompatNode:
          override lazy val storagesInstance
              : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
            new Fresh with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
              override protected def statePathLive: Boolean = true
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
    RpcCompatNode.blocks.takeWhile(_.header.number.value <= number).foreach { block =>
      val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      val adopt = block.header.number.value < number
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = adopt) match
        case (_, Some(error)) => throw new IllegalStateException(s"block ${block.header.number} not imported: $error")
        case _                => ()
    }
    node

  private lazy val onHashes: RpcCompatNode = importedUpTo(guard = false)
  private lazy val onPositions: RpcCompatNode = importedUpTo(guard = true)
  private lazy val layouts: Seq[(String, RpcCompatNode)] =
    Seq("hash store" -> onHashes, "unified layout" -> onPositions)

  private def ask(node: RpcCompatNode, method: String, params: JValue*): JValue =
    val request = JsonRpcRequest("2.0", method, Some(JArray(params.toList)), Some(JInt(1)))
    RpcCompatVectors.wire(node.controller.handleRequest(request).unsafeRunSync())

  private def hex(n: BigInt): JString = JString("0x" + n.toString(16))
  private def byHash: JString = JString("0x" + Hex.toHexString(above.header.hash.value.toArray))

  /** The genesis accounts and the senders of the block's transactions. */
  private lazy val accounts: Seq[String] =
    val genesis = (parse(RpcCompatNode.resource("genesis.json")) \ "alloc")
      .asInstanceOf[JObject]
      .obj
      .map("0x" + _._1.stripPrefix("0x").toLowerCase)
    val senders = SignedTransactionWithSender
      .getSignedTransactionsOfBlock(above.header, above.body.transactionList)(using RpcCompatNode.chainConfig)
      .map(stx => "0x" + Hex.toHexString(stx.senderAddress.bytes.toArray))
    (genesis ++ senders).distinct

  private val methods: Seq[(String, String => Seq[JValue])] = Seq(
    "eth_getBalance" -> (address => Seq(JString(address))),
    "eth_getTransactionCount" -> (address => Seq(JString(address))),
    "eth_getCode" -> (address => Seq(JString(address))),
    "eth_getStorageAt" -> (address => Seq(JString(address), hex(0))),
    "eth_getStorageAt" -> (address => Seq(JString(address), hex(1)))
  )

  "A block imported and not yet the head" should "be above the best block, stored, and not the head, on either layout" taggedAs
    UnitTest in {
      layouts.foreach { case (layout, node) =>
        withClue(s"$layout: ") {
          node.blockchainReader.getBestBlockNumber shouldBe number - 1
          node.blockchainReader.getBlockHeaderByHash(above.header.hash).map(_.hash) shouldBe Some(above.header.hash)
          ask(node, "eth_blockNumber") \ "result" shouldBe hex(number - 1)
        }
      }
      onPositions.storagesInstance.storages.pathStateStorage.get.holdsLayer(
        com.chipprbots.ethereum.db.storage.pathstate
          .BlockRef(above.header.hash.value, number, above.header.stateRoot.value)
      ) shouldBe true
    }

  it should "be answered by its hash from its own state, as the node that adopted it answers by its number" taggedAs
    UnitTest in {
      var differsFromHead = 0
      layouts.foreach { case (layout, node) =>
        accounts.foreach { address =>
          methods.foreach { case (method, params) =>
            withClue(s"$layout, $method $address: ") {
              val answer = ask(node, method, (params(address) :+ byHash)*)
              (answer \ "error") shouldBe JNothing
              answer shouldBe ask(reference, method, (params(address) :+ hex(number))*)
              if answer != ask(node, method, (params(address) :+ JString("latest"))*) then differsFromHead += 1
            }
          }
        }
      }
      // what was compared is the block's own state, not the head's
      differsFromHead should be > 0
    }

  it should "be answered by its number with geth's -32000 header not found" taggedAs UnitTest in {
    val notFound = JObject("code" -> JInt(-32000), "message" -> JString("header not found"))
    layouts.foreach { case (layout, node) =>
      val address = accounts.head
      methods.foreach { case (method, params) =>
        withClue(s"$layout, $method by number: ") {
          ask(node, method, (params(address) :+ hex(number))*) \ "error" shouldBe notFound
        }
      }
      // the controller of this node serves eth_getProof from a stand-in: the service itself is asked
      withClue(s"$layout, eth_getProof by number: ") {
        proof(node, address, BlockParam.WithNumber(number)) shouldBe Left(ResolveBlock.HeaderNotFound)
      }
    }
  }

  private def proof(node: RpcCompatNode, address: String, at: BlockParam) =
    new EthProofService(
      node.blockchain,
      node.blockchainReader,
      node.mining.blockGenerator,
      node.blockchainConfig.ethCompatibleStorage
    ).getProof(
      GetProofRequest(Address(Hex.decode(address.stripPrefix("0x"))), (0 to 2).map(slot => StorageProofKey(slot)), at)
    ).unsafeRunSync()
      .map(_.proofAccount)

  it should "be proved by its hash against its own state root" taggedAs UnitTest in {
    layouts.foreach { case (layout, node) =>
      accounts.foreach { address =>
        withClue(s"$layout, eth_getProof $address: ") {
          val answer = proof(node, address, BlockParam.WithHash(above.header.hash.value))
          answer.isRight shouldBe true
          answer shouldBe proof(reference, address, BlockParam.WithNumber(number))
        }
      }
    }
  }
