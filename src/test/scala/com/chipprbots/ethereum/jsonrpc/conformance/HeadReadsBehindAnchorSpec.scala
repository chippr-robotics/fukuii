package com.chipprbots.ethereum.jsonrpc.conformance

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import cats.effect.IO
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
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.jsonrpc.EthTxService
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.McpDependencies
import com.chipprbots.ethereum.jsonrpc.PersonalService
import com.chipprbots.ethereum.jsonrpc.PersonalService.FillTransactionRequest
import com.chipprbots.ethereum.jsonrpc.StateRead
import com.chipprbots.ethereum.jsonrpc.TxArgs
import com.chipprbots.ethereum.jsonrpc.TxGasEstimator
import com.chipprbots.ethereum.jsonrpc.mcp.AccountByAddressResource
import com.chipprbots.ethereum.jsonrpc.mcp.GetAccountTool
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.KeyStore.KeyStoreError
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.TxPoolConfig

/** Spec 018, B-238: a method that reads the state of the best block, and of no other, reads it in a scope of reads.
  *
  * On the unified layout the disk can move past the best block between the read of the best block and the read of its
  * state. That block is then '''behind the head''', and its state is handed out only inside a scope; outside one the
  * method was told the state is not held. Here the best block is put one block behind the anchor, which is that moment
  * held still, on the execution-apis test chain imported on the unified layout with its changeset records. Every method
  * that reads the best block's state is asked, and answers from it.
  */
class HeadReadsBehindAnchorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit
  private given IORuntime = IORuntime.global

  private trait Fresh extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = EphemDataSource()

  /** The chain on the unified layout, each block committed in place with its record, and then the best block put one
    * block behind the anchor: the disk has moved past it.
    */
  private def behindTheAnchor(): RpcCompatNode =
    val node = new RpcCompatNode:
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
    RpcCompatNode.blocks.foreach { block =>
      val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true) match
        case (_, Some(error)) => throw new IllegalStateException(s"block ${block.header.number} not imported: $error")
        case _                => ()
    }
    val head = node.blockchainReader.getBestBlockHeader.get
    val behind = node.blockchainReader.getBlockHeaderByHash(head.parentHash).get
    node.storagesInstance.storages.appStateStorage
      .putBestBlockInfo(BlockInfo(behind.hash.value, behind.number.value))
      .commit()
    node

  private lazy val node: RpcCompatNode = behindTheAnchor()
  private def state(of: RpcCompatNode): PathStateStorage = of.storagesInstance.storages.pathStateStorage.get
  private def best(of: RpcCompatNode): BlockHeader = of.blockchainReader.getBestBlockHeader.get

  /** The raw transactions of hive's rpc-compat vectors: signed for this chain, by senders it funds. */
  private def vector(name: String): (String, SignedTransaction, Address) =
    val (request, _) =
      RpcCompatVectors.read(RpcCompatNode.resource(s"eth_sendRawTransaction/$name.io")).exchanges.head
    val raw = (request \ "params")(0).asInstanceOf[JString].s
    val (stx, _) = Hex.decode(raw.stripPrefix("0x")).toSignedTransactionWithSidecar
    (raw, stx, SignedTransaction.getSender(stx)(using RpcCompatNode.chainConfig).get)

  private lazy val (legacyRaw, legacyTx, sender) = vector("send-legacy-transaction")

  private lazy val genesisAccounts: Seq[Address] =
    (parse(RpcCompatNode.resource("genesis.json")) \ "alloc")
      .asInstanceOf[JObject]
      .obj
      .map(account => Address(Hex.decode(account._1.stripPrefix("0x"))))

  private def ask(method: String, params: JValue*): JValue =
    val request = JsonRpcRequest("2.0", method, Some(JArray(params.toList)), Some(JInt(1)))
    RpcCompatVectors.wire(node.controller.handleRequest(request).unsafeRunSync())

  private def nonceAtBest(address: Address): BigInt =
    StateRead
      .scoped(node.blockchainReader.accountAt(best(node), address))
      .map(_.nonce.toBigInt)
      .getOrElse(node.blockchainConfig.accountStartNonce.toBigInt)

  private def pool(of: RpcCompatNode): Seq[PendingTransactionsManager.PendingTransaction] =
    val probe = testKit.createTestProbe[PendingTransactionsManager.PendingTransactionsResponse]()
    of.pendingTransactionsManager ! PendingTransactionsManager.GetPendingTransactionsReq(probe.ref)
    probe.receiveMessage().pendingTransactions

  "The best block, one block behind the anchor" should "have a state that is handed out inside a scope and in no other way" taggedAs
    UnitTest in {
      val head = best(node)
      state(node).anchor.map(_.number) shouldBe Some(head.number.value + 1)
      a[StateUnavailable] should be thrownBy node.blockchainReader.stateAt(head)
      StateRead.scoped(node.blockchainReader.accountAt(head, sender)) should not be empty
      state(node).readsBehindInFlight shouldBe 0
    }

  "eth_sendRawTransaction" should "admit a transaction against the best block's state" taggedAs UnitTest in {
    val answer = ask("eth_sendRawTransaction", JString(legacyRaw))
    withClue(compact(render(answer))) {
      (answer \ "result") shouldBe JString("0x" + Hex.toHexString(legacyTx.hash.value.toArray))
    }
    state(node).readsBehindInFlight shouldBe 0
  }

  "eth_getTransactionCount at pending" should "read the sender's nonce at the best block" taggedAs UnitTest in {
    val pooled = pool(node)
    val answer = ask("eth_getTransactionCount", JString(sender.toString), JString("pending"))
    val expected = EthTxService.nextPoolNonce(pooled, sender, nonceAtBest(sender))
    withClue(compact(render(answer))) {
      (answer \ "result") shouldBe JString("0x" + expected.toString(16))
    }
    state(node).readsBehindInFlight shouldBe 0
  }

  "eth_pendingTransactions" should "cut the node's own transactions at the sender's nonce at the best block" taggedAs
    UnitTest in {
      pool(node).map(_.stx.tx.hash) should contain(legacyTx.hash)
      val own = node.ethTxService.pendingTransactionsFrom(Set(sender)).unsafeRunSync()
      own.isRight shouldBe true
      // the pooled transaction is executable on the best block's state: its nonce is the sender's there
      legacyTx.tx.nonce shouldBe nonceAtBest(sender)
      own.map(_.transactions.map(_.hash)) shouldBe Right(Seq(legacyTx.hash.value))
      state(node).readsBehindInFlight shouldBe 0
    }

  private object NoKeys extends KeyStore:
    private val none = Left(KeyStore.KeyNotFound)
    def newAccount(passphrase: String): Either[KeyStoreError, Address] = none
    def importPrivateKey(key: org.apache.pekko.util.ByteString, passphrase: String): Either[KeyStoreError, Address] =
      none
    def listAccounts: Either[KeyStoreError, List[Address]] = Right(Nil)
    def unlockAccount(address: Address, passphrase: String): Either[KeyStoreError, Wallet] = none

  private object NoEstimates extends TxGasEstimator:
    def estimateGas(args: TxArgs, head: BlockHeader): IO[Either[JsonRpcError, BigInt]] =
      IO.raiseError(new IllegalStateException("the gas is given: nothing is estimated"))

  "eth_fillTransaction" should "fill the nonce from the sender's account at the best block" taggedAs UnitTest in {
    val txPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 1000
      override val pendingTxManagerQueryTimeout: scala.concurrent.duration.FiniteDuration =
        scala.concurrent.duration.FiniteDuration(10, "seconds")
      override val transactionTimeout: scala.concurrent.duration.FiniteDuration =
        scala.concurrent.duration.FiniteDuration(10, "minutes")
      override val getTransactionFromPoolTimeout: scala.concurrent.duration.FiniteDuration =
        scala.concurrent.duration.FiniteDuration(10, "seconds")
    val personal = new PersonalService(
      NoKeys,
      node.blockchainReader,
      node.pendingTransactionsManager,
      txPoolConfig,
      new BlockchainConfigBuilder with TestInstanceConfigProvider:
        override def blockchainConfig: BlockchainConfig = node.blockchainConfig
      ,
      node.ethTxService,
      NoEstimates,
      system.scheduler
    )
    // a genesis account with nothing pooled: its nonce is the one at the best block
    val pooled = pool(node).map(_.stx.senderAddress).toSet
    val from = genesisAccounts.find(account => !pooled.contains(account)).get
    val filled = personal
      .fillTransaction(
        FillTransactionRequest(
          TxArgs(
            from = Some(from),
            to = Some(sender),
            gas = Some(GasAmount(BigInt(21000))),
            gasPrice = Some(GasPrice(BigInt(10).pow(10)))
          )
        )
      )
      .unsafeRunSync()
    filled.map(_.result.stx.tx.nonce) shouldBe Right(nonceAtBest(from))
    state(node).readsBehindInFlight shouldBe 0
  }

  private def mcp: McpDependencies =
    McpDependencies(
      peerManager = null,
      syncController = null,
      blockchainReader = node.blockchainReader,
      blockchainConfig = node.blockchainConfig,
      nodeStatusHolder = new AtomicReference(null),
      transactionMappingStorage = node.storagesInstance.storages.transactionMappingStorage,
      scheduler = system.scheduler
    )

  "The MCP account tool and resource" should "read the account at the best block" taggedAs UnitTest in {
    val number = best(node).number.value
    val nonce = nonceAtBest(sender)
    val tool = GetAccountTool.execute(Some(JObject("address" -> JString(sender.toString))), mcp).unsafeRunSync()
    withClue(tool) {
      tool should include(s"Block: $number")
      tool should include(s"Nonce: $nonce")
    }
    val resource = AccountByAddressResource.read(sender.toString, mcp).unsafeRunSync()
    withClue(resource) {
      resource should include(s""""block": $number""")
      resource should include(s""""nonce": $nonce""")
    }
    state(node).readsBehindInFlight shouldBe 0
  }

  "GraphQL's sendRawTransaction" should "check the nonce against the best block's state" taggedAs UnitTest in {
    val (raw, stx, _) = vector("send-dynamic-fee-transaction")
    val answer =
      node.graphQL.execute(s"""mutation { sendRawTransaction(data: "$raw") }""", None, None).unsafeRunSync()._2
    withClue(answer.noSpaces) {
      answer.hcursor.downField("errors").failed shouldBe true
      answer.hcursor.downField("data").downField("sendRawTransaction").as[String] shouldBe
        Right("0x" + Hex.toHexString(stx.hash.value.toArray))
    }
    state(node).readsBehindInFlight shouldBe 0
  }

  // A pool of its own: the sender must be one the pool has not seen, which it keeps only when it read its account.
  "The pool" should "take a peer's transaction from a funded sender it has not seen, read at the best block" taggedAs
    UnitTest in {
      val fresh = behindTheAnchor()
      best(fresh).number.value shouldBe state(fresh).anchor.get.number - 1
      val (_, stx, from) = vector("send-access-list-transaction")
      pool(fresh) shouldBe empty
      fresh.pendingTransactionsManager ! PendingTransactionsManager.ProperSignedTransactions(
        Set(SignedTransactionWithSender(stx, from)),
        PeerId("a peer")
      )
      pool(fresh).map(_.stx.tx.hash) shouldBe Seq(stx.hash)
      state(fresh).readsBehindInFlight shouldBe 0
    }
