package com.chipprbots.ethereum.consensus.engine

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.io.Source

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionStateGasExceedsBlockCapacity
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.AmsterdamGas

// scalastyle:off magic.number
/** The Amsterdam payload builder end to end, as a CL drives it (#1427): engine_forkchoiceUpdatedV4 with
  * PayloadAttributesV4, engine_getPayloadV6, then engine_newPayloadV5 on a SECOND node that shares nothing with the
  * builder but the genesis. VALID there means another node re-executed the block from the ExecutionPayloadV4 alone and
  * reproduced every header field: the 23-field shape and the slot, the state and receipts roots, gasUsed as EIP-8037's
  * maximum of the two dimensions, the EIP-7685 requests (EIP-8282's builder requests included), and the EIP-7928 access
  * list, which the validating node rebuilds from its own execution and checks against the header.
  *
  * VALID cannot show that both nodes are right, since they run the same execution code; that is
  * EngineApiAmsterdamBuilderEestSpec's job (the builder reproduces execution-specs' own block hashes).
  *
  * Genesis: the six system contracts an Amsterdam block calls — EIP-4788, EIP-2935, EIP-7002, EIP-7251 and the two
  * EIP-8282 builder queues — verbatim from execution-specs' tests@v21.0.0 `pre`
  * (`eest-regression/amsterdam-genesis.json`), a SLOTNUM probe and senders with fixed keys.
  */
class EngineApiAmsterdamBuildSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val ChainIdValue = BigInt(1)
  private val GenesisGasLimit = BigInt(45_000_000)
  private val GenesisBaseFee = BigInt(7)
  private val TenEther = UInt256(BigInt(10).pow(19))

  private val Zero32 = ByteString(new Array[Byte](32))
  private val BeaconRoot = ByteString(Array.fill(32)(0xbe.toByte))
  private val PrevRandao = ByteString(Array.fill(32)(0x42.toByte))
  private val FeeRecipient = Address(ByteString(Array.fill(20)(0x0f.toByte)))
  private val EmptyRequestsHash = ByteString(MessageDigest.getInstance("SHA-256").digest())

  private def hex(bytes: ByteString): String = "0x" + Hex.toHexString(bytes.toArray)
  private def quantity(n: BigInt): String = "0x" + n.toString(16)
  private def unhex(s: String): ByteString = ByteString(Hex.decode(s.stripPrefix("0x")))

  /** A sender with a fixed private key (`seed` as a 32-byte big-endian integer). */
  private def key(seed: Int): AsymmetricCipherKeyPair =
    crypto.keyPairFromPrvKey(Array.fill[Byte](31)(0) :+ seed.toByte)

  private val alice = key(1)
  private val bob = key(2)
  private val carol = key(3)
  private val dave = key(4)
  private val erin = key(5)
  private val frank = key(6)
  private val sam = key(7)
  private val senders = Seq(alice, bob, carol, dave, erin, frank, sam)

  /** An existing account without code, the recipient of plain calls. */
  private val Sink = Address(ByteString(Array.fill(20)(0x51.toByte)))

  /** `SLOTNUM PUSH0 SSTORE STOP`: stores the executing block's EIP-7843 slot at storage slot 0. */
  private val SlotProbe = Address(0x5107)
  private val SlotProbeCode = ByteString(Hex.decode("4b5f5500"))

  /** The system contracts, verbatim (nonce, balance, code) from execution-specs' Amsterdam genesis `pre`. */
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
      val code = unhex(str(account \ "code"))
      require(code.nonEmpty, s"no code for system contract $address in the fixture")
      (
        address,
        UInt256(BigInt(str(account \ "nonce").stripPrefix("0x"), 16)),
        UInt256(BigInt(str(account \ "balance").stripPrefix("0x"), 16)),
        code
      )
    }

  private def amsterdamGenesisFields: HeaderExtraFields = HefPostAmsterdam(
    baseFee = GenesisBaseFee,
    withdrawalsRoot = BlockHeader.EmptyMpt,
    blobGasUsed = BigInt(0),
    excessBlobGas = BigInt(0),
    parentBeaconBlockRoot = Zero32,
    requestsHash = EmptyRequestsHash,
    blockAccessListHash = BlockAccessList.EmptyHash,
    slotNumber = BigInt(0)
  )

  private def pragueGenesisFields: HeaderExtraFields =
    HefPostPrague(GenesisBaseFee, BlockHeader.EmptyMpt, BigInt(0), BigInt(0), Zero32, EmptyRequestsHash)

  /** One node: its own ephemeral storage, the real VM and the engine validators, on the genesis above. `withPool` gives
    * it a pool (the builder); without one it only validates. `gasCeil` is the node's builder gas ceiling, what it is
    * handed from `mining.gas-limit-target`.
    */
  private class Node(
      network: String,
      genesisFields: HeaderExtraFields,
      withPool: Boolean,
      gasCeil: BigInt = EngineApiService.DefaultBuilderGasCeil
  ) extends EphemBlockchainTestSetup:

    val config: BlockchainConfig = EestBlockchainReplay
      .configFor(blockchainConfig, network, ChainIdValue)
      .fold(err => throw new IllegalStateException(err), identity)

    // The real validators, not ScenarioSetup's always-succeed mock: VALID must mean the post-execution checks ran.
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)

    // The cake's `blockExecution`: `mining` (with the validators above) and `blockValidation`, as the node wires it.

    /** (transaction, arrival millis): what the stub pool answers. */
    val pool: AtomicReference[Seq[(SignedTransaction, Long)]] = new AtomicReference(Nil)

    private lazy val pendingTxManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
      classicSystem.spawn(
        Behaviors.receiveMessage[PendingTransactionsManager.Command] {
          case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            val entries = pool.get().flatMap { case (stx, arrival) =>
              // Under the node's schedule, not the cake's default one: the stateless pre-filter drops a typed
              // transaction whose chain id is not the config's. The head is the genesis, at timestamp 0, so admission
              // follows the fork active there (Amsterdam on the Amsterdam schedule).
              val admitted = SignedTransactionWithSender.getSignedTransactions(Seq(stx), Timestamp.Zero)(using config)
              admitted.map(withSender => PendingTransactionsManager.PendingTransaction(withSender, arrival))
            }
            replyTo ! PendingTransactionsManager.PendingTransactionsResponse(entries)
            Behaviors.same
          case _ => Behaviors.same
        },
        s"ptm-amsterdam-build-${java.util.UUID.randomUUID()}"
      )

    lazy val service: EngineApiService =
      if withPool then
        new EngineApiService(
          blockchainReader,
          blockchainWriter,
          blockExecution,
          new ForkChoiceManager(blockchainReader, blockchainWriter),
          Some(pendingTxManager),
          builderGasCeil = gasCeil
        )(config, classicSystem.toTyped.scheduler)
      else
        new EngineApiService(
          blockchainReader,
          blockchainWriter,
          blockExecution,
          new ForkChoiceManager(blockchainReader, blockchainWriter),
          None
        )(config, null)

    def call(method: String, params: List[JValue]): JsonRpcResponse =
      new EngineApiController(service, None, config)
        .handleRequest(JsonRpcRequest("2.0", method, Some(JArray(params)), Some(JInt(1))))
        .unsafeRunSync()

    /** The world at `stateRoot`, read from this node's own storage. */
    def worldAt(stateRoot: ByteString): InMemoryWorldStateProxy =
      InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(0),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        UInt256.Zero,
        stateRoot,
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )

    val genesis: Block =
      val empty = worldAt(ByteString(MerklePatriciaTrie.EmptyRootHash))
      val contracts = systemContracts :+ ((SlotProbe, UInt256(1), UInt256.Zero, SlotProbeCode))
      val deployed = contracts.foldLeft(empty) { case (w, (address, nonce, balance, code)) =>
        w.saveAccount(address, Account(nonce = nonce, balance = balance, codeHash = CodeHash(kec256(code))))
          .saveCode(address, code)
      }
      val funded = (senders.map(Address(_)) :+ Sink).foldLeft(deployed) { (w, address) =>
        w.saveAccount(address, Account(balance = TenEther))
      }
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
        extraFields = genesisFields
      )
      Block(header, BlockBody(Nil, Nil, withdrawals = Some(Nil)))
    blockchainWriter.storeBlock(genesis).commit()
    blockchainWriter.storeReceipts(genesis.header.hash, Nil).commit()
    blockchainWriter.storeChainWeight(genesis.header.hash, ChainWeight.zero).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

  private def builderNode(
      network: String = "Amsterdam",
      genesis: HeaderExtraFields = amsterdamGenesisFields,
      gasCeil: BigInt = EngineApiService.DefaultBuilderGasCeil
  ) = new Node(network, genesis, withPool = true, gasCeil)
  private def validatorNode(network: String = "Amsterdam", genesis: HeaderExtraFields = amsterdamGenesisFields) =
    new Node(network, genesis, withPool = false)

  // ── Transactions ─────────────────────────────────────────────────────────────────────────────────────────────────

  private def tx(
      from: AsymmetricCipherKeyPair,
      to: Option[Address],
      gas: BigInt,
      payload: ByteString = ByteString.empty,
      value: BigInt = 0
  ): SignedTransaction =
    SignedTransaction.sign(
      TransactionWithDynamicFee(
        ChainIdValue,
        nonce = 0,
        maxPriorityFeePerGas = 1,
        maxFeePerGas = 1_000_000_000,
        gasLimit = GasAmount(gas),
        receivingAddress = to,
        value = value,
        payload = payload,
        accessList = Nil
      ),
      from,
      Some(ChainIdValue)
    )

  // ── The engine calls ─────────────────────────────────────────────────────────────────────────────────────────────

  private def forkchoiceState(head: Block): JObject = JObject(
    "headBlockHash" -> JString(hex(head.header.hash.value)),
    "safeBlockHash" -> JString(hex(Zero32)),
    "finalizedBlockHash" -> JString(hex(Zero32))
  )

  private def withdrawalJson(w: Withdrawal): JObject = JObject(
    "index" -> JString(quantity(w.index)),
    "validatorIndex" -> JString(quantity(w.validatorIndex)),
    "address" -> JString(hex(w.address.bytes)),
    "amount" -> JString(quantity(w.amount))
  )

  /** PayloadAttributesV3, and V4 when `slot` is given. */
  private def attributes(
      timestamp: Long,
      slot: Option[BigInt],
      withdrawals: Seq[Withdrawal] = Nil,
      targetGasLimit: Option[BigInt] = None
  ): JObject =
    JObject(
      List[(String, JValue)](
        "timestamp" -> JString(quantity(timestamp)),
        "prevRandao" -> JString(hex(PrevRandao)),
        "suggestedFeeRecipient" -> JString(hex(FeeRecipient.bytes)),
        "withdrawals" -> JArray(withdrawals.map(withdrawalJson).toList),
        "parentBeaconBlockRoot" -> JString(hex(BeaconRoot))
      ) ++ slot.map(s => "slotNumber" -> JString(quantity(s)))
        ++ targetGasLimit.map(t => "targetGasLimit" -> JString(quantity(t)))
    )

  /** engine_forkchoiceUpdatedV{version} with attributes on `builder`: the payload ID. */
  private def requestPayload(builder: Node, version: Int, attrs: JObject): String =
    val response = builder.call(s"engine_forkchoiceUpdatedV$version", List(forkchoiceState(builder.genesis), attrs))
    withClue(s"forkchoiceUpdatedV$version: ${response.error}: ") {
      response.error shouldBe None
      response.result.map(_ \ "payloadStatus" \ "status") shouldBe Some(JString("VALID"))
    }
    response.result.map(_ \ "payloadId") match
      case Some(JString(id)) => id
      case other             => fail(s"no payloadId: $other")

  private def getPayload(builder: Node, version: Int, payloadId: String): JsonRpcResponse =
    builder.call(s"engine_getPayloadV$version", List(JString(payloadId)))

  private def envelopeOf(response: JsonRpcResponse): JValue =
    withClue(s"getPayload error ${response.error}: ")(response.error shouldBe None)
    response.result.getOrElse(fail("getPayload returned no result"))

  /** The payload `builder` holds for `payloadId`, as a block. */
  private def builtBlock(builder: Node, payloadId: String): Block =
    builder.service.getPayload(unhex(payloadId)).unsafeRunSync().fold(err => fail(err), identity)

  /** engine_newPayloadV{version} of an engine_getPayload envelope on `validator`: the payload status. */
  private def validate(validator: Node, version: Int, envelope: JValue): (String, JValue) =
    val params: List[JValue] =
      List(envelope \ "executionPayload", JArray(Nil), JString(hex(BeaconRoot)), envelope \ "executionRequests")
    val response = validator.call(s"engine_newPayloadV$version", params)
    withClue(s"newPayloadV$version error ${response.error}: ")(response.error shouldBe None)
    val result = response.result.getOrElse(fail("newPayload returned no result"))
    result \ "status" match
      case JString(status) => (status, result \ "validationError")
      case other           => fail(s"no status: $other")

  private def expectValid(validator: Node, version: Int, envelope: JValue): Unit =
    val (status, validationError) = validate(validator, version, envelope)
    withClue(s"validationError=$validationError: ")(status shouldBe "VALID")

  private def servedAccessList(envelope: JValue): ByteString =
    envelope \ "executionPayload" \ "blockAccessList" match
      case JString(list) => unhex(list)
      case other         => fail(s"the ExecutionPayloadV4 carries no blockAccessList: $other")

  private def servedRequests(envelope: JValue): List[ByteString] =
    envelope \ "executionRequests" match
      case JArray(items) => items.map { case JString(r) => unhex(r); case other => fail(s"not DATA: $other") }
      case other         => fail(s"no executionRequests: $other")

  // ── Specs ────────────────────────────────────────────────────────────────────────────────────────────────────────

  "engine_forkchoiceUpdatedV4 + engine_getPayloadV6" should {

    "build an Amsterdam payload, withdrawals included, that an independent node validates through newPayloadV5" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val builder = builderNode()
      val validator = validatorNode()
      val fresh = Address(ByteString(Array.fill(20)(0x77.toByte)))
      val withdrawals = Seq(Withdrawal(0, 10, Sink, 1_000_000_000), Withdrawal(1, 11, fresh, 2_000_000_000))
      val payloadId = requestPayload(builder, 4, attributes(12, Some(BigInt(1)), withdrawals))
      val envelope = envelopeOf(getPayload(builder, 6, payloadId))
      val block = builtBlock(builder, payloadId)

      expectValid(validator, 5, envelope)

      block.header.extraFields shouldBe a[HefPostAmsterdam]
      block.header.slotNumber shouldBe Some(BigInt(1))
      block.header.gasUsed shouldBe GasAmount.Zero
      block.header.requestsHash shouldBe Some(EmptyRequestsHash)
      envelope \ "executionPayload" \ "slotNumber" shouldBe JString("0x1")
      envelope \ "executionPayload" \ "blockHash" shouldBe JString(hex(block.header.hash.value))
      envelope \ "executionRequests" shouldBe JArray(Nil)

      // The list served is the one the header commits to, the one the validating node built and kept, and it holds
      // what the block touched: the preamble system calls (index 0), the withdrawal recipients and the request
      // system calls (index n + 1).
      val accessList = servedAccessList(envelope)
      block.header.blockAccessListHash shouldBe Some(ByteString(kec256(accessList.toArray)))
      validator.blockchainReader.getBlockAccessListByHash(block.header.hash) shouldBe Some(accessList)
      val touched = BlockAccessList.decode(accessList).fold(err => fail(err), identity).accounts.map(_.address)
      (touched should contain).allOf(
        BlockExecution.BeaconRootContractAddress,
        BlockExecution.HistoryStorageAddress,
        Sink,
        fresh,
        BlockExecution.WithdrawalQueueAddress,
        BlockExecution.ConsolidationQueueAddress,
        BlockExecution.BuilderDepositQueueAddress,
        BlockExecution.BuilderExitQueueAddress
      )

      // Served whole and the same on every call.
      envelopeOf(getPayload(builder, 6, payloadId)) shouldBe envelope
    }

    "execute the payload with the attributes' slot: a SLOTNUM transaction stores it" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val builder = builderNode()
      val validator = validatorNode()
      val probe = tx(sam, Some(SlotProbe), gas = 200_000)
      builder.pool.set(Seq(probe -> 1L))
      val slot = BigInt(424242)
      val payloadId = requestPayload(builder, 4, attributes(12, Some(slot)))
      val envelope = envelopeOf(getPayload(builder, 6, payloadId))
      val block = builtBlock(builder, payloadId)

      // A builder that sealed the slot into the header only after executing would have run SLOTNUM against another
      // value, and the validating node's state root would differ.
      expectValid(validator, 5, envelope)
      block.body.transactionList shouldBe Seq(probe)
      block.header.slotNumber shouldBe Some(slot)
      validator.worldAt(block.header.stateRoot.value).getStorage(SlotProbe).load(BigInt(0)) shouldBe slot
    }

    "fill the block by EIP-8037's per-dimension rule: include what the receipt sum refuses, skip a state overflow" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val builder = builderNode()
      val validator = validatorNode()
      // EXECUTION-heavy: 125,000 calldata bytes cost EIP-7976's 64 gas each as a floor, 8,015,000 of execution gas
      // (12,000 + 3,000 + 125,000 x 64) against no state.
      val executionHeavy = tx(alice, Some(Sink), gas = 8_100_000, payload = ByteString(new Array[Byte](125_000)))
      // STATE-heavy, and only possible with the reservoir: 12,000 bytes of code are 18,360,000 of state gas
      // (12,000 x 1,530) plus 183,600 for the new account — more than the 2^24 execution gas a transaction may have.
      // Its 20,000,000 gas limit seeds a reservoir with the rest (EIP-8037), and the deployment succeeds only if that
      // reservoir pays for the state. Initcode: PUSH2 12000, PUSH0, RETURN.
      val codeSize = 12_000
      val stateHeavy = tx(bob, None, gas = 20_000_000, payload = ByteString(Hex.decode("612ee05ff3")))
      // Fits both dimensions, yet `tx.gas + receipt sum <= gasLimit` — the pre-Amsterdam rule — refuses it.
      val probe = tx(carol, Some(Sink), gas = 22_000_000)
      // Fits the execution dimension (min(2^24, tx.gas) of it) but not the state one: skipped, not included.
      val stateOverflow = tx(dave, Some(Sink), gas = 28_000_000)
      builder.pool.set(Seq(executionHeavy -> 1L, stateHeavy -> 2L, probe -> 3L, stateOverflow -> 4L))
      val timestamp = 12L
      val payloadId = requestPayload(builder, 4, attributes(timestamp, Some(BigInt(1))))
      val envelope = envelopeOf(getPayload(builder, 6, payloadId))
      val block = builtBlock(builder, payloadId)

      expectValid(validator, 5, envelope)
      block.body.transactionList shouldBe Seq(executionHeavy, stateHeavy, probe)

      val receipts = validator.blockchainReader.getReceiptsByHash(block.header.hash).getOrElse(fail("no receipts"))
      val gasLimit = block.header.gasLimit.value
      withClue("the reservoir precondition: the code deposit alone exceeds the execution budget: ") {
        BigInt(codeSize) * AmsterdamGas.Cpsb should be > AmsterdamGas.TxMaxGasLimit
        stateHeavy.tx.gasLimit.value should be > AmsterdamGas.TxMaxGasLimit
      }
      val world = validator.worldAt(block.header.stateRoot.value)
      world.getCode(world.createAddress(Address(bob))).size shouldBe codeSize

      // The header takes the larger dimension — here the state one, all of it the deployment's — while receipts sum
      // both. So the pre-Amsterdam rule, read against the receipts, would have refused `probe`.
      val stateGas = AmsterdamGas.GasNewAccount + BigInt(codeSize) * AmsterdamGas.Cpsb
      block.header.gasUsed.value shouldBe stateGas
      val receiptSum = receipts.last.cumulativeGasUsed
      receiptSum should be > block.header.gasUsed.value
      withClue("the receipt-sum rule refuses the probe: ")(
        probe.tx.gasLimit.value + receipts(1).cumulativeGasUsed should be > gasLimit
      )

      // No transaction here earns a refund or sits under its calldata floor after execution, so each receipt is its
      // execution plus its state gas and the execution dimension is the rest of the receipt sum. Against those
      // counters it is the STATE dimension that refuses `stateOverflow` (the execution check comes first and passes).
      StdSignedTransactionValidator.blockGasCapacityError(
        stateOverflow.tx.gasLimit.value,
        gasLimit,
        Timestamp(timestamp),
        receiptSum,
        receiptSum - stateGas,
        stateGas
      )(using builder.config) shouldBe Some(
        TransactionStateGasExceedsBlockCapacity(stateOverflow.tx.gasLimit.value, stateGas, gasLimit)
      )
    }

    "commit EIP-8282 builder deposit and exit requests to requestsHash and serve them in executionRequests" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val builder = builderNode()
      val validator = validatorNode()
      // Builder deposit (EIP-8282): pubkey48 || withdrawal_credentials32 || amount8 (big-endian gwei) || signature96,
      // and 1 ETH of amount plus the 1-wei queue fee as value. The layout of devp2p fixture block 36
      // (AmsterdamBuilderRequestsSpec).
      val pubkey = ByteString(Array.fill(48)(0x42.toByte))
      val credentials = ByteString(new Array[Byte](12)) ++ Address(erin).bytes
      val signature = ByteString(Array.fill(96)(0x24.toByte))
      val depositCalldata = pubkey ++ credentials ++ ByteString(Hex.decode("000000003b9aca00")) ++ signature
      val deposit = tx(
        erin,
        Some(BlockExecution.BuilderDepositQueueAddress),
        gas = 1_000_000,
        payload = depositCalldata,
        value = BigInt(10).pow(18) + 1
      )
      // Builder exit: pubkey48, and the 1-wei fee.
      val exit = tx(frank, Some(BlockExecution.BuilderExitQueueAddress), gas = 1_000_000, payload = pubkey, value = 1)
      builder.pool.set(Seq(deposit -> 1L, exit -> 2L))
      val payloadId = requestPayload(builder, 4, attributes(12, Some(BigInt(1))))
      val envelope = envelopeOf(getPayload(builder, 6, payloadId))
      val block = builtBlock(builder, payloadId)

      // newPayload compares the requests the CL hands back with those its own execution derives: VALID means these
      // are exactly the block's requests.
      expectValid(validator, 5, envelope)
      block.body.transactionList shouldBe Seq(deposit, exit)
      val requests = servedRequests(envelope)
      requests shouldBe List(
        // The predeploy stores the amount little-endian.
        ByteString(0x03.toByte) ++ pubkey ++ credentials ++ ByteString(Hex.decode("00ca9a3b00000000")) ++ signature,
        ByteString(0x04.toByte) ++ Address(frank).bytes ++ pubkey
      )
      block.header.requestsHash shouldBe Some(BlockExecution.computeRequestsHash(requests))
    }

    "move the gas limit toward targetGasLimit at go-ethereum's CalcGasLimit rate" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val builder = builderNode()
      val validator = validatorNode()
      val payloadId =
        requestPayload(builder, 4, attributes(12, Some(BigInt(1)), targetGasLimit = Some(BigInt(60_000_000))))
      val envelope = envelopeOf(getPayload(builder, 6, payloadId))
      val block = builtBlock(builder, payloadId)

      // The validating node checks the step against the parent (less than parent / 1024): VALID.
      expectValid(validator, 5, envelope)
      // CalcGasLimit(45,000,000, 60,000,000): one step of 45,000,000 / 1024 - 1 = 43,944.
      block.header.gasLimit.value shouldBe BigInt(45_043_944)
      envelope \ "executionPayload" \ "gasLimit" shouldBe JString(quantity(45_043_944))
      // Without a target, the node's gas ceiling: go-ethereum's default, 60,000,000, the same step here.
      val untargeted = requestPayload(builder, 4, attributes(12, Some(BigInt(2))))
      builtBlock(builder, untargeted).header.gasLimit.value shouldBe BigInt(45_043_944)
    }

    "without targetGasLimit, move toward the node's configured gas ceiling, as go-ethereum's GasCeil" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // mining.gas-limit-target = 36,000,000: CalcGasLimit(45,000,000, 36,000,000), one step down of 43,944.
      val builder = builderNode(gasCeil = 36_000_000)
      val validator = validatorNode()
      val payloadId = requestPayload(builder, 4, attributes(12, Some(BigInt(1))))
      val envelope = envelopeOf(getPayload(builder, 6, payloadId))

      expectValid(validator, 5, envelope)
      builtBlock(builder, payloadId).header.gasLimit.value shouldBe BigInt(44_956_056)
      envelope \ "executionPayload" \ "gasLimit" shouldBe JString(quantity(44_956_056))
    }
  }

  /** execution-specs' `BPO2ToAmsterdamAtTime15k` schedule: every fork through BPO2 at genesis, Amsterdam at 15,000 —
    * Sepolia's situation at 1791294816, when the first Amsterdam block's parent is a Prague-shaped (21-field) header.
    */
  "The builder across the Amsterdam boundary" should {

    "build the first Amsterdam block on a Prague-shaped parent" taggedAs (UnitTest, ConsensusTest) in {
      val builder = builderNode("BPO2ToAmsterdamAtTime15k", pragueGenesisFields)
      val validator = validatorNode("BPO2ToAmsterdamAtTime15k", pragueGenesisFields)
      builder.pool.set(Seq(tx(sam, Some(SlotProbe), gas = 200_000) -> 1L))
      val payloadId = requestPayload(builder, 4, attributes(15_000, Some(BigInt(1250))))
      val envelope = envelopeOf(getPayload(builder, 6, payloadId))
      val block = builtBlock(builder, payloadId)

      expectValid(validator, 5, envelope)
      builder.genesis.header.extraFields shouldBe a[HefPostPrague]
      block.header.extraFields shouldBe a[HefPostAmsterdam]
      block.header.slotNumber shouldBe Some(BigInt(1250))
      validator.worldAt(block.header.stateRoot.value).getStorage(SlotProbe).load(BigInt(0)) shouldBe BigInt(1250)
    }

    "build a pre-Amsterdam payload on the same schedule exactly as before: Prague-shaped, V5 only" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val builder = builderNode("BPO2ToAmsterdamAtTime15k", pragueGenesisFields)
      val validator = validatorNode("BPO2ToAmsterdamAtTime15k", pragueGenesisFields)
      builder.pool.set(Seq(tx(carol, Some(Sink), gas = 21_000) -> 1L))
      val payloadId = requestPayload(builder, 3, attributes(14_999, slot = None))
      val block = builtBlock(builder, payloadId)

      block.header.extraFields shouldBe a[HefPostPrague]
      getPayload(builder, 6, payloadId).error.map(_.code) shouldBe Some(-38005)
      val envelope = envelopeOf(getPayload(builder, 5, payloadId))
      envelope \ "executionPayload" \ "blockAccessList" shouldBe org.json4s.JNothing
      envelope \ "executionPayload" \ "slotNumber" shouldBe org.json4s.JNothing
      expectValid(validator, 4, envelope)
    }
  }
