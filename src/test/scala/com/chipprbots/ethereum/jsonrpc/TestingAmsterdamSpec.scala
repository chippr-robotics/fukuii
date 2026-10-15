package com.chipprbots.ethereum.jsonrpc

import java.security.MessageDigest

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.io.Source

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.EngineApiService
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.engine.PayloadAttributes
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.jsonrpc.TestingService.*
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp.encode as rlpEncode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig

// scalastyle:off magic.number
/** `testing_buildBlockV1` / `testing_commitBlockV1` at an Amsterdam timestamp — the shape of the execution-apis
  * rpc-compat vectors since the test chain moved to Amsterdam (ba884e7d77 #867).
  *
  * Pinned here:
  *   - The PayloadAttributesV4 fields decode. Before, `slotNumber` was dropped and every call failed with -32000 "no
  *     slot number set post-amsterdam".
  *   - The built header carries the slot; the envelope's ExecutionPayloadV4 carries `slotNumber` and the
  *     `blockAccessList` whose hash the header commits to.
  *   - `targetGasLimit` replaces the configured target from Amsterdam on (go-ethereum's miner). The vectors' blocks
  *     keep the parent's 200,000,000 because the attributes name it; the configured 60,000,000 would move every hash.
  *   - A commit stores the access list, as engine_newPayload does.
  *
  * Genesis: the six system contracts an Amsterdam block calls, verbatim from execution-specs' Amsterdam `pre`
  * (`eest-regression/amsterdam-genesis.json`), as in EngineApiAmsterdamBuildSpec, plus one funded sender.
  */
class TestingAmsterdamSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val ChainIdValue = BigInt(1)
  private val GenesisGasLimit = BigInt(200_000_000) // 0xbebc200, the rpc-compat chain's
  private val ConfiguredTarget = BigInt(60_000_000) // hive's HIVE_TARGET_GAS_LIMIT
  private val Zero32 = ByteString(new Array[Byte](32))
  private val BeaconRoot = ByteString(Hex.decode("cf8e0d4e9587369b2301d0790347320302cc0943d5a1884365149a42212e8822"))
  private val EmptyRequestsHash = ByteString(MessageDigest.getInstance("SHA-256").digest())

  private def hex(bytes: ByteString): String = "0x" + Hex.toHexString(bytes.toArray)
  private def unhex(s: String): ByteString = ByteString(Hex.decode(s.stripPrefix("0x")))

  private lazy val systemContracts: Seq[(Address, UInt256, UInt256, ByteString)] =
    val wanted = Seq(
      BlockExecution.BeaconRootContractAddress,
      BlockExecution.HistoryStorageAddress,
      BlockExecution.WithdrawalQueueAddress,
      BlockExecution.ConsolidationQueueAddress,
      BlockExecution.BuilderDepositQueueAddress,
      BlockExecution.BuilderExitQueueAddress
    )
    val source = Source.fromResource("eest-regression/amsterdam-genesis.json")
    val json =
      try parse(source.mkString)
      finally source.close()
    val pre = json match
      case JObject((_, fixture) :: _) => fixture \ "pre"
      case other                      => throw new IllegalStateException(s"not a fixture file: ${other.getClass}")
    def str(v: JValue): String = v match
      case JString(s) => s
      case other      => throw new IllegalStateException(s"not a string: $other")
    wanted.map { address =>
      val account = pre \ hex(address.bytes)
      (
        address,
        UInt256(BigInt(str(account \ "nonce").stripPrefix("0x"), 16)),
        UInt256(BigInt(str(account \ "balance").stripPrefix("0x"), 16)),
        unhex(str(account \ "code"))
      )
    }

  private trait Setup extends EphemBlockchainTestSetup:

    val config: BlockchainConfig = EestBlockchainReplay
      .configFor(blockchainConfig, "Amsterdam", ChainIdValue)
      .fold(err => throw new IllegalStateException(err), identity)

    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)

    val senderKeys = crypto.keyPairFromPrvKey(Array.fill[Byte](31)(0) :+ 9.toByte)
    val sender: Address = Address(senderKeys)
    val recipient: Address = Address(ByteString(Array.fill(20)(0x7d.toByte)))

    private lazy val pendingTxManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
      classicSystem.spawn(
        Behaviors.receiveMessage[PendingTransactionsManager.Command] {
          case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            replyTo ! PendingTransactionsManager.PendingTransactionsResponse(Nil)
            Behaviors.same
          case _ => Behaviors.same
        },
        s"ptm-testing-amsterdam-${java.util.UUID.randomUUID()}"
      )

    lazy val engineApi = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExecution,
      new ForkChoiceManager(blockchainReader, blockchainWriter),
      Some(pendingTxManager)
    )(config, classicSystem.toTyped.scheduler)

    lazy val service = new TestingService(
      engineApi,
      blockchainReader,
      blockchainWriter,
      new ForkChoiceManager(blockchainReader, blockchainWriter),
      Some(pendingTxManager),
      blockTopic = None,
      gasLimitTarget = ConfiguredTarget
    )(config)

    val genesis: Block =
      val empty = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(0),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        UInt256.Zero,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
      val deployed = systemContracts.foldLeft(empty) { case (w, (address, nonce, balance, code)) =>
        w.saveAccount(address, Account(nonce = nonce, balance = balance, codeHash = CodeHash(kec256(code))))
          .saveCode(address, code)
      }
      val funded = deployed.saveAccount(sender, Account(balance = UInt256(BigInt(10).pow(19))))
      val header = BlockHeader(
        parentHash = BlockHash(Zero32),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(InMemoryWorldStateProxy.persistState(funded).stateRootHash),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(0),
        gasLimit = GasAmount(GenesisGasLimit),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(0),
        extraData = ByteString.empty,
        mixHash = BlockHash(Zero32),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostAmsterdam(
          baseFee = BigInt(7),
          withdrawalsRoot = BlockHeader.EmptyMpt,
          blobGasUsed = BigInt(0),
          excessBlobGas = BigInt(0),
          parentBeaconBlockRoot = Zero32,
          requestsHash = EmptyRequestsHash,
          blockAccessListHash = BlockAccessList.EmptyHash,
          slotNumber = BigInt(0)
        )
      )
      Block(header, BlockBody(Nil, Nil, withdrawals = Some(Nil)))
    blockchainWriter.storeBlock(genesis).commit()
    blockchainWriter.storeReceipts(genesis.header.hash, Nil).commit()
    blockchainWriter.storeChainWeight(genesis.header.hash, ChainWeight.zero).commit()
    blockchainWriter.saveBestKnownBlocks(genesis.header.hash, 0)

    /** The vectors' attributes: zero prevRandao, zero fee recipient, empty withdrawals, their beacon root. */
    def attrs(
        ts: Long = 12,
        slot: Option[BigInt] = Some(BigInt(4)),
        target: Option[BigInt] = Some(GenesisGasLimit)
    ): PayloadAttributes = PayloadAttributes(
      timestamp = ts,
      prevRandao = Zero32,
      suggestedFeeRecipient = Address(ByteString(new Array[Byte](20))),
      withdrawals = Some(Nil),
      parentBeaconBlockRoot = Some(BeaconRoot),
      slotNumber = slot,
      targetGasLimit = target
    )

    def rawTx(nonce: BigInt): ByteString =
      val tx = TransactionWithDynamicFee(
        chainId = ChainIdValue,
        nonce = nonce,
        maxPriorityFeePerGas = BigInt(1),
        maxFeePerGas = BigInt(1_000_000_000),
        gasLimit = GasAmount(21000),
        receivingAddress = Some(recipient),
        value = BigInt(1000),
        payload = ByteString.empty,
        accessList = Nil
      )
      val stx = SignedTransaction.sign(tx, senderKeys, Some(ChainIdValue))
      ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))

  "testing_buildBlockV1 at an Amsterdam timestamp" should {

    "build a 23-field block carrying the slot, and serve the access list its header commits to" taggedAs (
      UnitTest
    ) in new Setup:
      val res = service
        .buildBlock(BuildBlockRequest(genesis.header.hash.value, attrs(), Some(Seq(rawTx(0))), Some(ByteString.empty)))
        .unsafeRunSync()
      val built = res.fold(err => fail(s"build failed: ${err.code} ${err.message}"), identity)

      built.block.header.extraFields shouldBe a[HefPostAmsterdam]
      built.block.header.slotNumber shouldBe Some(BigInt(4))
      built.block.body.transactionList should have size 1
      val list = built.blockAccessList.getOrElse(fail("no access list for an Amsterdam build"))
      built.block.header.blockAccessListHash shouldBe Some(ByteString(kec256(list.toArray)))
      // The list records the transfer: sender and recipient are both in it.
      val touched = BlockAccessList.decode(list).fold(err => fail(err), identity).accounts.map(_.address)
      (touched should contain).allOf(sender, recipient)

      // Read-only: neither the block nor its list is stored.
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)
      blockchainReader.getBlockByHash(built.block.header.hash) shouldBe None

      val envelope = TestingJsonMethodsImplicits.testing_buildBlockV1.encodeJson(built)
      envelope \ "executionPayload" \ "slotNumber" shouldBe JString("0x4")
      envelope \ "executionPayload" \ "blockAccessList" shouldBe JString(hex(list))
      envelope \ "executionPayload" \ "blockHash" shouldBe JString(hex(built.block.header.hash.value))
      envelope \ "executionRequests" shouldBe JArray(Nil)

    "build toward the attributes' targetGasLimit, not the configured target" taggedAs UnitTest in new Setup:
      val kept = service
        .buildBlock(BuildBlockRequest(genesis.header.hash.value, attrs(), Some(Nil), None))
        .unsafeRunSync()
        .fold(err => fail(err.message), identity)
      // The vectors: parent and target both 0xbebc200, so the child keeps it.
      kept.block.header.gasLimit.value shouldBe GenesisGasLimit

      val configured = service
        .buildBlock(BuildBlockRequest(genesis.header.hash.value, attrs(target = None), Some(Nil), None))
        .unsafeRunSync()
        .fold(err => fail(err.message), identity)
      // Without one, the configured target still applies, as before.
      configured.block.header.gasLimit.value shouldBe
        com.chipprbots.ethereum.consensus.blocks.GasLimitCalculator.calcGasLimit(GenesisGasLimit, ConfiguredTarget)

    "still refuse Amsterdam attributes without a slot, with -32000" taggedAs UnitTest in new Setup:
      val res = service
        .buildBlock(BuildBlockRequest(genesis.header.hash.value, attrs(slot = None), Some(Nil), None))
        .unsafeRunSync()
      res.swap.map(e => (e.code, e.message)) shouldBe Right((-32000, EngineApiService.AmsterdamSlotNumberMissing))

    "reject an unapplicable transaction with -32000 and leave the chain untouched" taggedAs UnitTest in new Setup:
      val res = service
        .buildBlock(BuildBlockRequest(genesis.header.hash.value, attrs(), Some(Seq(rawTx(999))), None))
        .unsafeRunSync()
      res.swap.map(_.code) shouldBe Right(-32000)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)
  }

  "testing_commitBlockV1 at an Amsterdam timestamp" should {

    "advance the head to a block carrying the slot, and keep its access list" taggedAs UnitTest in new Setup:
      val committed = service
        .commitBlock(CommitBlockRequest(attrs(), Some(Seq(rawTx(0))), Some(ByteString("execution-apis".getBytes))))
        .unsafeRunSync()
        .fold(err => fail(s"commit failed: ${err.code} ${err.message}"), identity)

      val head = blockchainReader.getBestBlock.getOrElse(fail("no head"))
      head.header.hash.value shouldBe committed.blockHash
      head.header.slotNumber shouldBe Some(BigInt(4))
      val stored = blockchainReader.getBlockAccessListByHash(head.header.hash).getOrElse(fail("no stored list"))
      head.header.blockAccessListHash shouldBe Some(ByteString(kec256(stored.toArray)))

      // eth_getBlockByNumber("latest") now shows the Amsterdam fields.
      val json = EthBlocksJsonMethodsImplicits.blockResponseEncoder.encodeJson(
        BlockResponse(head, emitTotalDifficulty = false)
      )
      json \ "slotNumber" shouldBe JString("0x4")
      json \ "blockAccessListHash" shouldBe JString(hex(head.header.blockAccessListHash.get))

    "not move the head when a transaction cannot be applied" taggedAs UnitTest in new Setup:
      val res = service
        .commitBlock(CommitBlockRequest(attrs(), Some(Seq(rawTx(999))), None))
        .unsafeRunSync()
      res.swap.map(_.code) shouldBe Right(-32000)
      blockchainReader.getBestBlock.map(_.header.hash) shouldBe Some(genesis.header.hash)
  }

  "the testing_* payload attributes decoder" should {
    import TestingJsonMethodsImplicits.given

    // Verbatim from execution-apis tests/testing_buildBlockV1/build-block-empty-transactions.io.
    val vectorParams =
      """["0x52bccfc9c4d4874b1898c0fbd0ae3b598a0edc7b94d9b796857dbbe1093c32e0",{"parentBeaconBlockRoot":"0xcf8e0d4e9587369b2301d0790347320302cc0943d5a1884365149a42212e8822","prevRandao":"0x0000000000000000000000000000000000000000000000000000000000000000","slotNumber":"0x4","suggestedFeeRecipient":"0x0000000000000000000000000000000000000000","targetGasLimit":"0xbebc200","timestamp":"0x264","withdrawals":[]},[],"0x"]"""

    "read slotNumber and targetGasLimit from the vector's request" taggedAs UnitTest in {
      val params = parse(vectorParams).asInstanceOf[JArray]
      val req =
        summon[JsonMethodDecoder[BuildBlockRequest]].decodeJson(Some(params)).fold(e => fail(e.message), identity)
      req.payloadAttributes.slotNumber shouldBe Some(BigInt(4))
      req.payloadAttributes.targetGasLimit shouldBe Some(BigInt(0xbebc200))
      req.payloadAttributes.timestamp shouldBe 0x264L
      req.transactions shouldBe Some(Nil)
    }

    "leave both None when absent, and reject a non-quantity or a value wider than 64 bits with -32602" taggedAs
      UnitTest in {
        def attrsWith(extra: (String, JValue)*): JArray = JArray(
          List(
            JObject(
              List[(String, JValue)](
                "prevRandao" -> JString("0x" + "00" * 32),
                "suggestedFeeRecipient" -> JString("0x" + "00" * 20),
                "timestamp" -> JString("0x264")
              ) ++ extra
            ),
            JArray(Nil)
          )
        )
        val decoder = summon[JsonMethodDecoder[CommitBlockRequest]]
        val plain = decoder.decodeJson(Some(attrsWith())).fold(e => fail(e.message), identity)
        plain.payloadAttributes.slotNumber shouldBe None
        plain.payloadAttributes.targetGasLimit shouldBe None
        decoder.decodeJson(Some(attrsWith("slotNumber" -> JNull))).map(_.payloadAttributes.slotNumber) shouldBe
          Right(None)
        decoder.decodeJson(Some(attrsWith("slotNumber" -> JBool(true)))).swap.map(_.code) shouldBe Right(-32602)
        decoder
          .decodeJson(Some(attrsWith("targetGasLimit" -> JString("0x1" + "0" * 16))))
          .swap
          .map(_.code) shouldBe Right(-32602)
      }
  }
