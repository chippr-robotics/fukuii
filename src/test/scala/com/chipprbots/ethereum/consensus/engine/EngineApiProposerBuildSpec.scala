package com.chipprbots.ethereum.consensus.engine

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The engine producer path end to end — forkchoiceUpdated(head, attrs) -> getPayload -> newPayload — for the pool
  * transactions go-ethereum's miner leaves out, and for a transaction the execution itself rejects.
  *
  * The invariant under test: every payload we hand the CL is one we (and every other client) validate. Before, a
  * transaction the child block could not pay for went into the payload anyway, the lenient build's execution failed on
  * it, and the payload came back sealed over the PARENT's state root with every transaction still in its body — hive
  * `Blob Transactions On Block 1, * (Cancun)` saw its 6 blobs where it expected none.
  */
// scalastyle:off magic.number
class EngineApiProposerBuildSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val CancunTs: Long = 9999999995L // test application.conf: Cancun, not Prague

  abstract private class Setup(genesisTimestamp: Long, genesisExtraFields: HeaderExtraFields)
      extends EphemBlockchainTestSetup:

    implicit override def blockchainConfig: BlockchainConfig =
      initBlockchainConfig.withUpdatedForkBlocks(
        _.copy(
          olympiaBlockNumber = BigInt(1),
          olympiaGasLimitElasticity = Some(BaseFeeCalculator.ElasticityMultiplier)
        )
      )

    override lazy val vm: VMImpl = new VMImpl
    // The REAL transaction validator (fee caps, balance, nonce, blob fee), not ScenarioSetup's always-succeed mock:
    // the point is that what the selection keeps is what execution accepts, and that what execution rejects is left
    // out of the payload.
    override lazy val validators: com.chipprbots.ethereum.consensus.validators.Validators = powValidators
    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)
    override lazy val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)

    lazy val blockExec = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )
    lazy val forkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)

    /** (transaction, arrival millis) — what the stub pool answers. */
    val poolContents: AtomicReference[Seq[(SignedTransaction, Long)]] = new AtomicReference(Nil)

    /** The EIP-4844 network form the pool captured for its blob transactions (tx hash -> raw bytes). */
    val poolBlobBytes: AtomicReference[Map[ByteString, ByteString]] = new AtomicReference(Map.empty)

    lazy val pendingTxManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
      classicSystem.spawn(
        Behaviors.receiveMessage[PendingTransactionsManager.Command] {
          case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            val entries = poolContents.get().flatMap { case (stx, arrival) =>
              SignedTransactionWithSender.getSignedTransactions(Seq(stx)).map { withSender =>
                PendingTransactionsManager.PendingTransaction(withSender, arrival)
              }
            }
            replyTo ! PendingTransactionsManager.PendingTransactionsResponse(entries, poolBlobBytes.get())
            Behaviors.same
          case _ => Behaviors.same
        },
        s"ptm-proposer-build-${java.util.UUID.randomUUID()}"
      )

    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExec,
      forkChoiceManager,
      Some(pendingTxManager)
    )(blockchainConfig, typedScheduler)

    private val random = new SecureRandom()
    private val to = Address(ByteString(Array.fill(20)(0x16.toByte)))
    private val chainId: BigInt = blockchainConfig.chainId.value

    val alice: AsymmetricCipherKeyPair = crypto.generateKeyPair(random)
    val bob: AsymmetricCipherKeyPair = crypto.generateKeyPair(random)
    val broke: AsymmetricCipherKeyPair = crypto.generateKeyPair(random) // never funded

    private def address(key: AsymmetricCipherKeyPair): Address =
      Address(crypto.kec256(crypto.pubKeyFromKeyPair(key)))

    // Unprotected (pre-EIP-155) signature: the test config is ETC-shaped and activates EIP-155 far above block 1,
    // where the real validator rejects a chain-id-protected legacy signature.
    def legacy(key: AsymmetricCipherKeyPair, nonce: BigInt): SignedTransaction =
      SignedTransaction.sign(
        LegacyTransaction(nonce, GasPrice(BigInt("30000000000")), GasAmount(21000), Some(to), 1, ByteString.empty),
        key,
        None
      )

    def dynamic(key: AsymmetricCipherKeyPair, nonce: BigInt, maxFeePerGas: BigInt): SignedTransaction =
      SignedTransaction.sign(
        TransactionWithDynamicFee(
          chainId,
          nonce,
          BigInt(1),
          maxFeePerGas,
          GasAmount(21000),
          Some(to),
          1,
          ByteString.empty,
          Nil
        ),
        key,
        Some(chainId)
      )

    def blob(key: AsymmetricCipherKeyPair, nonce: BigInt, blobs: Int, maxFeePerBlobGas: BigInt): SignedTransaction =
      SignedTransaction.sign(
        BlobTransaction(
          chainId,
          nonce,
          BigInt(1000000000),
          BigInt("30000000000"),
          GasAmount(100000),
          Some(to),
          0,
          ByteString.empty,
          Nil,
          maxFeePerBlobGas,
          List.tabulate(blobs)(i => BlobVersionedHash(ByteString(Array(0x01.toByte) ++ Array.fill(31)(i.toByte))))
        ),
        key,
        Some(chainId)
      )

    private val genesisStateRoot =
      val world = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(0),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        UInt256.Zero,
        ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
      val funded = Seq(alice, bob).foldLeft(world) { (w, key) =>
        w.saveAccount(address(key), Account(balance = UInt256(BigInt("1000000000000000000"))))
      }
      InMemoryWorldStateProxy.persistState(funded).stateRootHash

    val genesisHeader: BlockHeader = BlockHeader(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(genesisStateRoot),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(0),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(genesisTimestamp),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = genesisExtraFields
    )

    blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil))).commit()
    blockchainWriter.storeReceipts(genesisHeader.hash, Nil).commit()
    blockchainWriter.storeChainWeight(genesisHeader.hash, ChainWeight.zero).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

    private val zero32 = ByteString(new Array[Byte](32))

    def attrsFor(head: BlockHeader): PayloadAttributes =
      val ts = head.unixTimestamp.toLong + 1
      val cancun = blockchainConfig.isCancunTimestamp(Timestamp(ts))
      PayloadAttributes(
        timestamp = ts,
        prevRandao = ByteString(Array.fill(32)(0x42.toByte)),
        suggestedFeeRecipient = Address(ByteString(new Array[Byte](20))),
        withdrawals = Option.when(blockchainConfig.isShanghaiTimestamp(Timestamp(ts)))(Nil),
        parentBeaconBlockRoot = Option.when(cancun)(ByteString(Array.fill(32)(0x07.toByte)))
      )

    /** forkchoiceUpdated(head, attrs) -> (payloadId, getPayload). */
    def buildOnWithId(head: BlockHeader): (ByteString, Block) =
      val response =
        engineApi
          .forkchoiceUpdated(ForkChoiceState(head.hash.value, zero32, zero32), Some(attrsFor(head)))
          .unsafeRunSync()
      val payloadId = response
        .getOrElse(fail(s"forkchoiceUpdated failed: $response"))
        .payloadId
        .getOrElse(fail("forkchoiceUpdated returned no payloadId"))
      (payloadId, engineApi.getPayload(payloadId).unsafeRunSync().getOrElse(fail("getPayload failed")))

    def buildOn(head: BlockHeader): Block = buildOnWithId(head)._2

    def newPayloadStatus(block: Block): PayloadStatusV1 =
      import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
      import com.chipprbots.ethereum.rlp.encode as rlpEncode
      val payload = ExecutionPayload(
        parentHash = block.header.parentHash.value,
        feeRecipient = Address(block.header.beneficiary),
        stateRoot = block.header.stateRoot.value,
        receiptsRoot = block.header.receiptsRoot.value,
        logsBloom = block.header.logsBloom.value,
        prevRandao = block.header.mixHash.value,
        blockNumber = block.header.number.value,
        gasLimit = block.header.gasLimit.value,
        gasUsed = block.header.gasUsed.value,
        timestamp = block.header.unixTimestamp.toLong,
        extraData = block.header.extraData,
        baseFeePerGas = block.header.baseFee.getOrElse(BigInt(0)),
        blockHash = block.header.hash.value,
        transactions =
          block.body.transactionList.map(stx => ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))),
        withdrawals = block.header.withdrawalsRoot.flatMap(_ => block.body.withdrawals),
        blobGasUsed = block.header.blobGasUsed,
        excessBlobGas = block.header.excessBlobGas,
        parentBeaconBlockRoot = block.header.parentBeaconBlockRoot.map(_.value),
        executionRequests = block.header.requestsHash.map(_ => Nil)
      )
      engineApi.newPayload(payload).unsafeRunSync()

    def expectValid(block: Block): Unit =
      val status = newPayloadStatus(block)
      withClue(s"validationError=${status.validationError}: ")(status.status shouldBe Valid)

    lazy val controller = new EngineApiController(engineApi)

    /** forkchoiceUpdated(head, attrs) — the payloadId only; nothing is fetched. */
    def requestPayload(head: BlockHeader): ByteString =
      engineApi
        .forkchoiceUpdated(ForkChoiceState(head.hash.value, zero32, zero32), Some(attrsFor(head)))
        .unsafeRunSync()
        .getOrElse(fail("forkchoiceUpdated failed"))
        .payloadId
        .getOrElse(fail("forkchoiceUpdated returned no payloadId"))

    /** engine_getPayloadV{version} exactly as the CL sends it, through the controller. */
    def getPayloadCall(version: Int, payloadId: ByteString): com.chipprbots.ethereum.jsonrpc.JsonRpcResponse =
      import org.json4s.JsonAST.{JArray, JInt, JString}
      val idHex = "0x" + payloadId.toArray.map("%02x".format(_)).mkString
      controller
        .handleRequest(
          com.chipprbots.ethereum.jsonrpc
            .JsonRpcRequest("2.0", s"engine_getPayloadV$version", Some(JArray(List(JString(idHex)))), Some(JInt(1)))
        )
        .unsafeRunSync()

    /** (blockHash, number of transactions) of the payload an engine_getPayloadV1 answer carries. */
    def servedV1(response: com.chipprbots.ethereum.jsonrpc.JsonRpcResponse): (String, Int) =
      import org.json4s.JsonAST.{JArray, JObject, JString}
      response.result match
        case Some(JObject(fields)) =>
          val payload = fields.toMap
          val hash = payload.get("blockHash").collect { case JString(h) => h }.getOrElse(fail("no blockHash"))
          val txs = payload.get("transactions").collect { case JArray(items) => items.size }.getOrElse(fail("no txs"))
          (hash, txs)
        case other => fail(s"getPayloadV1 answered $other, error ${response.error}")

    /** What the service currently holds for `payloadId`. */
    def stored(payloadId: ByteString): Block =
      engineApi.getPayload(payloadId).unsafeRunSync().getOrElse(fail("payload not available"))

    def hexOf(block: Block): String = "0x" + block.hash.value.toArray.map("%02x".format(_)).mkString

  /** Paris-era genesis (the test config activates Shanghai and later far in the future). */
  private class ParisSetup extends Setup(1000L, HefPostOlympia(BaseFeeCalculator.InitialBaseFee))

  /** Cancun genesis whose blob gas makes block 1's blob base fee exactly 2 wei: excess 15 blobs + used 6 blobs - target
    * 3 blobs = 18 blobs of excess, and fake_exponential(1, 18 * 131072, 3338477) = 2.
    */
  private class CancunSetup
      extends Setup(
        CancunTs,
        HefPostCancun(
          baseFee = BaseFeeCalculator.InitialBaseFee,
          withdrawalsRoot = BlockHeader.EmptyMpt,
          blobGasUsed = BlobGasUtils.GAS_PER_BLOB * 6,
          excessBlobGas = BlobGasUtils.GAS_PER_BLOB * 15,
          parentBeaconBlockRoot = ByteString(new Array[Byte](32))
        )
      )

  "The engine payload builder" should {

    "leave out a transaction whose fee cap is below the child's base fee, and keep the rest" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new ParisSetup:
      val childBaseFee = BaseFeeCalculator.calcBaseFee(genesisHeader, blockchainConfig)
      val underpriced = dynamic(alice, 0, maxFeePerGas = childBaseFee - 1)
      val transfer = legacy(bob, 0)
      poolContents.set(Seq(underpriced -> 1L, transfer -> 2L))

      val block1 = buildOn(genesisHeader)
      block1.body.transactionList shouldBe Seq(transfer)
      expectValid(block1)

    "leave out a transaction whose execution fails, and still hand the CL a VALID payload" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new ParisSetup:
      // Unfunded sender: nothing in selection can see it (no balance check there), execution rejects it.
      val unpayable = legacy(broke, 0)
      val transfer = legacy(bob, 0)
      poolContents.set(Seq(unpayable -> 1L, transfer -> 2L))

      val block1 = buildOn(genesisHeader)
      block1.body.transactionList shouldBe Seq(transfer)
      expectValid(block1)

    "leave out a blob transaction the child block's blob base fee has priced out" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new CancunSetup:
      // hive `Blob Transactions On Block 1, * (Cancun)` step 6: blob base fee 2, maxFeePerBlobGas 1.
      val pricedOut = blob(alice, 0, blobs = 6, maxFeePerBlobGas = 1)
      val transfer = legacy(bob, 0)
      poolContents.set(Seq(pricedOut -> 1L, transfer -> 2L))

      val block1 = buildOn(genesisHeader)
      block1.body.transactionList shouldBe Seq(transfer)
      block1.header.blobGasUsed shouldBe Some(BigInt(0))
      expectValid(block1)

    "carry a Cancun payload's blob sidecars in its bundle, without EIP-7594 cell proofs" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new CancunSetup:
      // Cell proofs are for BlobsBundleV2 (engine_getPayloadV5, Osaka onwards) only. Computing them here, one c-kzg call
      // per blob, made each 6-blob Cancun build take ~4.3 s (hive `In-Order Consecutive Payload Execution (Cancun)`).
      val payable = blob(alice, 0, blobs = 6, maxFeePerBlobGas = 2)
      val zeroBlob = Array.fill[Byte](ethereum.ckzg4844.CKZG4844JNI.BYTES_PER_BLOB)(0)
      val networkForm = com.chipprbots.ethereum.rlp.encode(
        com.chipprbots.ethereum.rlp.RLPList(
          com.chipprbots.ethereum.rlp.RLPValue(Array.empty[Byte]),
          com.chipprbots.ethereum.rlp.RLPList(Seq.fill(6)(com.chipprbots.ethereum.rlp.RLPValue(zeroBlob))*),
          com.chipprbots.ethereum.rlp.RLPList(Seq.fill(6)(com.chipprbots.ethereum.rlp.RLPValue(new Array[Byte](48)))*),
          com.chipprbots.ethereum.rlp.RLPList(Seq.fill(6)(com.chipprbots.ethereum.rlp.RLPValue(new Array[Byte](48)))*)
        )
      )
      poolContents.set(Seq(payable -> 1L))
      poolBlobBytes.set(Map(payable.hash.value -> ByteString(0x03.toByte +: networkForm)))

      val (payloadId, block1) = buildOnWithId(genesisHeader)
      block1.body.transactionList shouldBe Seq(payable)
      val bundle = engineApi.getPayloadBlobsBundle(payloadId)
      bundle.blobs.size shouldBe 6
      bundle.commitments.size shouldBe 6
      bundle.proofs.size shouldBe 6
      bundle.cellProofsPerBlob shouldBe empty

    "include the same blob transaction once it can pay the blob base fee" taggedAs (UnitTest, ConsensusTest) in
      new CancunSetup:
        val payable = blob(alice, 0, blobs = 6, maxFeePerBlobGas = 2)
        poolContents.set(Seq(payable -> 1L))

        val block1 = buildOn(genesisHeader)
        block1.body.transactionList shouldBe Seq(payable)
        block1.header.blobGasUsed shouldBe Some(BlobGasUtils.GAS_PER_BLOB * 6)
        expectValid(block1)
  }

  /** execution-apis paris.md, engine_getPayloadV1: "MUST return the most recent version of the payload that is
    * available in the corresponding build process at the time of receiving the call", and "Payload building" point 3:
    * the default strategy keeps the transaction set up to date with the local mempool until getPayload.
    *
    * The build ran once, inside forkchoiceUpdated, so a transaction that reached the pool between forkchoiceUpdated and
    * getPayload was left out: hive `Blob Transaction Ordering, Multiple Clients (Cancun)` failed with "expected 6 blob,
    * got 5" when client B's gossiped 1-blob transaction arrived after client A's forkchoiceUpdated.
    *
    * These drive getPayload through the controller, as the CL does. The pool is the stub's; nothing sleeps.
    */
  "engine_getPayload" should {

    "include a transaction that reached the pool after forkchoiceUpdated" taggedAs (UnitTest, ConsensusTest) in
      new ParisSetup:
        val payloadId = requestPayload(genesisHeader)
        stored(payloadId).body.transactionList shouldBe empty

        val transfer = legacy(bob, 0)
        poolContents.set(Seq(transfer -> 1L))
        val (servedHash, servedTxs) = servedV1(getPayloadCall(1, payloadId))

        servedTxs shouldBe 1
        val served = stored(payloadId)
        servedHash shouldBe hexOf(served)
        served.body.transactionList shouldBe Seq(transfer)
        expectValid(served)

    "answer exactly the payload it built when the pool has not changed" taggedAs (UnitTest, ConsensusTest) in
      new ParisSetup:
        poolContents.set(Seq(legacy(bob, 0) -> 1L))
        val payloadId = requestPayload(genesisHeader)
        val built = stored(payloadId)

        servedV1(getPayloadCall(1, payloadId)) shouldBe ((hexOf(built), 1))

    "keep answering the payload it served, even after the pool changes" taggedAs (UnitTest, ConsensusTest) in
      new ParisSetup:
        // "Client software MAY stop the corresponding build process after serving this call"; go-ethereum does
        // (Payload.Resolve), and hive's withdrawals tests ask V1 then V2 for one id and expect the same payload.
        val payloadId = requestPayload(genesisHeader)
        val first = servedV1(getPayloadCall(1, payloadId))

        poolContents.set(Seq(legacy(bob, 0) -> 1L))
        servedV1(getPayloadCall(1, payloadId)) shouldBe first
        first._2 shouldBe 0

    "not restart a build for attributes it is already building" taggedAs (UnitTest, ConsensusTest) in new ParisSetup:
      // "Payload building" point 6: "If a build process with given payloadAttributes already exists, client software
      // SHOULD NOT restart it" (go-ethereum: `localBlocks.has(id)`). Restarting replaced a payload already served.
      val payloadId = requestPayload(genesisHeader)
      val served = servedV1(getPayloadCall(1, payloadId))

      poolContents.set(Seq(legacy(bob, 0) -> 1L))
      requestPayload(genesisHeader) shouldBe payloadId
      servedV1(getPayloadCall(1, payloadId)) shouldBe served

    "bring the payload up to date on the call that returns it, not on one refused for its version" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new ParisSetup:
      val payloadId = requestPayload(genesisHeader)
      val transfer = legacy(bob, 0)
      poolContents.set(Seq(transfer -> 1L))

      getPayloadCall(3, payloadId).error.map(_.code) shouldBe Some(-38005) // a Paris payload asked for as V3
      servedV1(getPayloadCall(1, payloadId))._2 shouldBe 1
      stored(payloadId).body.transactionList shouldBe Seq(transfer)
  }
