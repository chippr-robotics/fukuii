package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.GasLimitCalculator
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.engine.EngineApiService
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.engine.PayloadAttributes
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.TestingService.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp.encode as rlpEncode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** execution-apis `testing_*` namespace.
  *
  * What is actually being pinned here, in order of how badly a regression would hurt:
  *   1. `testing_buildBlockV1` MUST NOT move the head. It is a read-only payload generator; the hive fixture
  *      `build-block-invalid-transaction.io` asserts the chain is untouched after a failed call, and `build-block-*.io`
  *      all run before the commit fixtures which expect the head still at the imported tip. 2. `extraData` is used
  *      verbatim. The engine path hardcodes `"fukuii"`; a payload built here with that in it hashes differently from
  *      every other client's and every fixture fails. 3. The gas limit converges toward the configured target with
  *      go-ethereum's `CalcGasLimit`, NOT the parent's gas limit the engine path keeps. This single field decides the
  *      block hash. 4. An unapplicable transaction is an error with code -32000 and the head does not move.
  */
// scalastyle:off magic.number
class TestingServiceSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val GenesisGasLimit: BigInt = 3_000_000
  private val GasLimitTarget: BigInt = 60_000_000

  "GasLimitCalculator.calcGasLimit" should {

    // The three values below are read straight off the execution-apis fixtures for
    // testing_commitBlockV1: the imported chain tip carries gasLimit 0xbebc200 and geth's built
    // children carry 0xbe8c711 then 0xbe5cce1. Any drift here changes the block hash.
    "reproduce go-ethereum's descent from the rpc-compat fixture chain" taggedAs UnitTest in {
      val target = BigInt(60_000_000)
      val tip = BigInt(0xbebc200) // 200,000,000
      val child = GasLimitCalculator.calcGasLimit(tip, target)
      val grandchild = GasLimitCalculator.calcGasLimit(child, target)
      child shouldBe BigInt(0xbe8c711)
      grandchild shouldBe BigInt(0xbe5cce1)
    }

    "step up toward a higher target by parent/1024 - 1" taggedAs UnitTest in {
      GasLimitCalculator.calcGasLimit(BigInt(1_000_000), BigInt(60_000_000)) shouldBe
        BigInt(1_000_000) + (BigInt(1_000_000) / 1024 - 1)
    }

    "clamp to the target rather than overshoot" taggedAs UnitTest in {
      GasLimitCalculator.calcGasLimit(BigInt(1_000_000), BigInt(1_000_100)) shouldBe BigInt(1_000_100)
      GasLimitCalculator.calcGasLimit(BigInt(1_000_100), BigInt(1_000_000)) shouldBe BigInt(1_000_000)
    }

    "hold a parent already at target" taggedAs UnitTest in {
      GasLimitCalculator.calcGasLimit(BigInt(60_000_000), BigInt(60_000_000)) shouldBe BigInt(60_000_000)
    }
  }

  "testing_buildBlockV1" should {

    "build an empty block on the given parent without touching the canonical head" taggedAs UnitTest in new Setup:
      val before = blockchainReader.getBestBlockNumber

      val res = service
        .buildBlock(BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Nil), Some(ByteString.empty)))
        .unsafeRunSync()

      val built = res.getOrElse(fail(s"build failed: $res"))
      built.block.header.parentHash shouldBe genesisHeader.hash
      built.block.header.number shouldBe BlockNumber(1)
      built.block.body.transactionList shouldBe empty

      // read-only: no head move, and the block was not stored
      blockchainReader.getBestBlockNumber shouldBe before
      blockchainReader.getBlockByHash(built.block.header.hash) shouldBe None

    "use the supplied extraData verbatim, not the miner's configured header-extra-data" taggedAs UnitTest in
      new Setup:
        val marker = ByteString("test_name".getBytes)
        val built = service
          .buildBlock(BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Nil), Some(marker)))
          .unsafeRunSync()
          .getOrElse(fail("build failed"))

        built.block.header.extraData shouldBe marker

    "default extraData to empty when the parameter is absent" taggedAs UnitTest in new Setup:
      val built = service
        .buildBlock(BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Nil), None))
        .unsafeRunSync()
        .getOrElse(fail("build failed"))

      built.block.header.extraData shouldBe ByteString.empty

    "converge the gas limit toward the target instead of inheriting the parent's" taggedAs UnitTest in new Setup:
      val built = service
        .buildBlock(BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Nil), Some(ByteString.empty)))
        .unsafeRunSync()
        .getOrElse(fail("build failed"))

      built.block.header.gasLimit.value shouldBe GasLimitCalculator.calcGasLimit(GenesisGasLimit, GasLimitTarget)
      built.block.header.gasLimit.value should not be GenesisGasLimit

    "include the supplied transactions, in order" taggedAs UnitTest in new Setup:
      val tx0 = rawTx(nonce = 0)
      val tx1 = rawTx(nonce = 1)
      val res = service
        .buildBlock(
          BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Seq(tx0, tx1)), Some(ByteString.empty))
        )
        .unsafeRunSync()
      val built = res.getOrElse(fail(s"build failed: ${res.swap.map(_.message)}"))

      built.block.body.transactionList.map(_.tx.nonce) shouldBe Seq(BigInt(0), BigInt(1))

    "reject an unapplicable transaction with -32000 and leave the chain untouched" taggedAs UnitTest in new Setup:
      val before = blockchainReader.getBestBlockNumber

      val res = service
        .buildBlock(
          BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Seq(rawTx(nonce = 999))), Some(ByteString.empty))
        )
        .unsafeRunSync()

      res.isLeft shouldBe true
      res.swap.getOrElse(fail("expected an error")).code shouldBe -32000
      // `data` must stay absent: hive's rpc-compat redacts only `message` before diffing, so an
      // extra key in the error object is a mismatch.
      res.swap.getOrElse(fail("expected an error")).data shouldBe None
      blockchainReader.getBestBlockNumber shouldBe before

    "reject an unknown parent hash with -32602" taggedAs UnitTest in new Setup:
      val res = service
        .buildBlock(
          BuildBlockRequest(ByteString(Array.fill(32)(0x99.toByte)), attrs(), Some(Nil), Some(ByteString.empty))
        )
        .unsafeRunSync()

      res.swap.getOrElse(fail("expected an error")).code shouldBe -32602

    "build from the mempool when transactions is null" taggedAs UnitTest in new Setup:
      // The stub pool is empty, so "from the mempool" is an empty block here — which is exactly
      // what the spec permits ("MAY build a block from its local transaction pool").
      val built = service
        .buildBlock(BuildBlockRequest(genesisHeader.hash.value, attrs(), None, Some(ByteString.empty)))
        .unsafeRunSync()
        .getOrElse(fail("build failed"))

      built.block.body.transactionList shouldBe empty
  }

  "testing_commitBlockV1" should {

    "insert the block and advance the canonical head" taggedAs UnitTest in new Setup:
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

      val res = service
        .commitBlock(CommitBlockRequest(attrs(), Some(Nil), Some(ByteString.empty)))
        .unsafeRunSync()
        .getOrElse(fail("commit failed"))

      blockchainReader.getBestBlockNumber shouldBe BigInt(1)
      val head = blockchainReader.getBestBlock.getOrElse(fail("no head"))
      head.header.hash.value shouldBe res.blockHash
      head.header.parentHash shouldBe genesisHeader.hash
      blockchainReader.getBlockByHash(BlockHash(res.blockHash)).isDefined shouldBe true
      blockchainReader.getReceiptsByHash(BlockHash(res.blockHash)).isDefined shouldBe true

    "build on the CURRENT head, so two commits chain" taggedAs UnitTest in new Setup:
      val first = service
        .commitBlock(CommitBlockRequest(attrs(ts = 1001), Some(Nil), Some(ByteString.empty)))
        .unsafeRunSync()
        .getOrElse(fail("first commit failed"))
      val second = service
        .commitBlock(CommitBlockRequest(attrs(ts = 1002), Some(Nil), Some(ByteString.empty)))
        .unsafeRunSync()
        .getOrElse(fail("second commit failed"))

      blockchainReader.getBestBlockNumber shouldBe BigInt(2)
      val head = blockchainReader.getBestBlock.getOrElse(fail("no head"))
      head.header.hash.value shouldBe second.blockHash
      head.header.parentHash.value shouldBe first.blockHash

    "NOT move the head when a transaction cannot be applied" taggedAs UnitTest in new Setup:
      val res = service
        .commitBlock(CommitBlockRequest(attrs(), Some(Seq(rawTx(nonce = 999))), Some(ByteString.empty)))
        .unsafeRunSync()

      res.isLeft shouldBe true
      res.swap.getOrElse(fail("expected an error")).code shouldBe -32000
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)
      blockchainReader.getBestBlock.map(_.header.hash) shouldBe Some(genesisHeader.hash)
  }

  "the shared proposer builder" should {

    "keep engine_forkchoiceUpdated's lenient behaviour: a bad transaction does NOT abort the build" taggedAs
      UnitTest in new Setup:
        // Regression guard on the extraction. The engine path always builds a payload, even when
        // a transaction fails to apply: it leaves that transaction out and hands the CL a payload
        // that executes. Only the testing_* namespace is strict. If `strict = false` ever starts
        // returning Left, forkchoiceUpdated throws instead of answering.
        val bad = rawTx(nonce = 999).toArray.toSignedTransaction

        val lenient = engineApi.buildBlockOnParent(
          Block(genesisHeader, BlockBody(Nil, Nil)),
          attrs(),
          Seq(bad),
          ByteString.empty,
          GasAmount(GenesisGasLimit),
          strict = false
        )
        val strict = engineApi.buildBlockOnParent(
          Block(genesisHeader, BlockBody(Nil, Nil)),
          attrs(),
          Seq(bad),
          ByteString.empty,
          GasAmount(GenesisGasLimit),
          strict = true
        )

        lenient.isRight shouldBe true
        // ...and it leaves the unapplicable transaction OUT rather than sealing it into a block no
        // client can execute.
        lenient.map(_.block.body.transactionList) shouldBe Right(Nil)
        strict.isLeft shouldBe true

    "produce the identical block for the engine and testing paths given identical inputs" taggedAs UnitTest in
      new Setup:
        // The point of routing both through buildBlockOnParent: with the same parent, attributes,
        // transactions, extraData and gasLimit the two surfaces cannot disagree on a single header
        // field. Everything they DO differ on (extraData default, gas-limit policy) is a caller
        // argument, visible at the call site, not a hidden fork in the builder.
        val parent = Block(genesisHeader, BlockBody(Nil, Nil))
        val gasLimit = engineApi.proposerGasLimit(genesisHeader, 1, Some(GasLimitTarget))

        val viaBuilder = engineApi
          .buildBlockOnParent(parent, attrs(), Nil, ByteString.empty, gasLimit, strict = true)
          .getOrElse(fail("builder failed"))
        val viaService = service
          .buildBlock(BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Nil), Some(ByteString.empty)))
          .unsafeRunSync()
          .getOrElse(fail("service failed"))

        viaService.block.header.hash shouldBe viaBuilder.block.header.hash

    "keep the parent gas limit when no target is given (engine policy) and converge when one is" taggedAs
      UnitTest in new Setup:
        engineApi.proposerGasLimit(genesisHeader, 1, None).value shouldBe GenesisGasLimit
        engineApi.proposerGasLimit(genesisHeader, 1, Some(GasLimitTarget)).value shouldBe
          GasLimitCalculator.calcGasLimit(GenesisGasLimit, GasLimitTarget)
  }

  "the testing_* codecs" should {
    import TestingJsonMethodsImplicits.given
    import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
    import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder

    val decodeBuild = summon[JsonMethodDecoder[BuildBlockRequest]]
    val encodeBuild = summon[JsonEncoder[BuildBlockResponse]]
    val decodeCommit = summon[JsonMethodDecoder[CommitBlockRequest]]
    val encodeCommit = summon[JsonEncoder[CommitBlockResponse]]

    val attrsJson = JObject(
      "parentBeaconBlockRoot" -> JString("0x" + "cf" * 32),
      "prevRandao" -> JString("0x" + "00" * 32),
      "suggestedFeeRecipient" -> JString("0x" + "00" * 20),
      "timestamp" -> JString("0x228"),
      "withdrawals" -> JArray(Nil)
    )

    "distinguish an empty transactions array from null" taggedAs UnitTest in {
      val empty = decodeBuild
        .decodeJson(Some(JArray(List(JString("0x" + "11" * 32), attrsJson, JArray(Nil), JString("0x")))))
        .getOrElse(fail("decode failed"))
      val fromPool = decodeBuild
        .decodeJson(Some(JArray(List(JString("0x" + "11" * 32), attrsJson, JNull, JString("0x")))))
        .getOrElse(fail("decode failed"))

      empty.transactions shouldBe Some(Nil)
      fromPool.transactions shouldBe None
    }

    "distinguish an explicit empty extraData from an absent one" taggedAs UnitTest in {
      val explicit = decodeBuild
        .decodeJson(Some(JArray(List(JString("0x" + "11" * 32), attrsJson, JArray(Nil), JString("0x")))))
        .getOrElse(fail("decode failed"))
      val absent = decodeBuild
        .decodeJson(Some(JArray(List(JString("0x" + "11" * 32), attrsJson, JArray(Nil)))))
        .getOrElse(fail("decode failed"))

      explicit.extraData shouldBe Some(ByteString.empty)
      absent.extraData shouldBe None
    }

    "decode payload attributes including withdrawals and the beacon root" taggedAs UnitTest in {
      val withWithdrawal = attrsJson.copy(obj = attrsJson.obj.map {
        case ("withdrawals", _) =>
          "withdrawals" -> JArray(
            List(
              JObject(
                "index" -> JString("0x1"),
                "validatorIndex" -> JString("0x2"),
                "address" -> JString("0x" + "ab" * 20),
                "amount" -> JString("0x3")
              )
            )
          )
        case other => other
      })
      val req = decodeCommit
        .decodeJson(Some(JArray(List(withWithdrawal, JArray(Nil), JString("0x")))))
        .getOrElse(fail("decode failed"))

      req.payloadAttributes.timestamp shouldBe 0x228L
      req.payloadAttributes.parentBeaconBlockRoot shouldBe Some(ByteString(Array.fill(32)(0xcf.toByte)))
      req.payloadAttributes.withdrawals.map(_.map(_.amount)) shouldBe Some(Seq(BigInt(3)))
    }

    "reject params that are not an array of the expected arity" taggedAs UnitTest in {
      decodeBuild.decodeJson(Some(JArray(List(JString("0x11"))))).isLeft shouldBe true
      decodeCommit.decodeJson(None).isLeft shouldBe true
      decodeBuild.decodeJson(Some(JArray(List(JString("0x" + "11" * 32), attrsJson, JInt(7))))).isLeft shouldBe true
    }

    "encode the commit response as a bare block hash string" taggedAs UnitTest in {
      encodeCommit.encodeJson(CommitBlockResponse(ByteString(Array.fill(32)(0x7f.toByte)))) shouldBe
        JString("0x" + "7f" * 32)
    }

    "emit exactly the fixture's envelope keys, and omit executionRequests pre-Prague" taggedAs UnitTest in
      new Setup:
        val built = service
          .buildBlock(BuildBlockRequest(genesisHeader.hash.value, attrs(), Some(Nil), Some(ByteString.empty)))
          .unsafeRunSync()
          .getOrElse(fail("build failed"))

        val prague = encodeBuild
          .encodeJson(built.copy(executionRequests = Some(Nil)))
          .asInstanceOf[JObject]
          .obj
          .map(_._1)
        prague should contain theSameElementsAs List(
          "executionPayload",
          "blockValue",
          "blobsBundle",
          "shouldOverrideBuilder",
          "executionRequests"
        )

        val preShanghai = encodeBuild
          .encodeJson(built.copy(executionRequests = None))
          .asInstanceOf[JObject]
          .obj
          .map(_._1)
        preShanghai should not contain "executionRequests"

        val bundle = encodeBuild
          .encodeJson(built)
          .asInstanceOf[JObject]
          .obj
          .toMap
          .apply("blobsBundle")
          .asInstanceOf[JObject]
          .obj
          .map(_._1)
        bundle should contain theSameElementsAs List("commitments", "proofs", "blobs")
  }

  /** Harness modelled on EngineApiGasLimitElasticitySpec: real VM, ephemeral storage, a funded genesis, and a stub
    * transaction pool that answers immediately.
    */
  private trait Setup extends EphemBlockchainTestSetup:

    implicit override def blockchainConfig: BlockchainConfig =
      initBlockchainConfig.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = BigInt(0)))

    // REAL validators, not ScenarioSetup's MockValidatorsAlwaysSucceed. The whole point of the
    // invalid-transaction cases is that signed-transaction validation (nonce, funds, signature)
    // actually runs and actually fails; with the success mock a nonce-999 transaction sails
    // through and the test silently asserts nothing.
    override lazy val validators: com.chipprbots.ethereum.consensus.validators.Validators = powValidators

    override lazy val vm: VMImpl = new VMImpl
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

    lazy val pendingTxManager: org.apache.pekko.actor.typed.ActorRef[
      com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
    ] = classicSystem.spawn(
      org.apache.pekko.actor.typed.scaladsl.Behaviors
        .receiveMessage[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command] {
          case com.chipprbots.ethereum.transactions.PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            replyTo ! com.chipprbots.ethereum.transactions.PendingTransactionsManager
              .PendingTransactionsResponse(Nil)
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
          case _ => org.apache.pekko.actor.typed.scaladsl.Behaviors.same
        },
      s"ptm-stub-testing-${java.util.UUID.randomUUID()}"
    )
    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExec,
      forkChoiceManager,
      Some(pendingTxManager)
    )(blockchainConfig, typedScheduler)

    lazy val service = new TestingService(
      engineApi,
      blockchainReader,
      blockchainWriter,
      forkChoiceManager,
      Some(pendingTxManager),
      blockTopic = None,
      gasLimitTarget = GasLimitTarget
    )(blockchainConfig)

    val senderKeys: org.bouncycastle.crypto.AsymmetricCipherKeyPair = crypto.generateKeyPair(
      new java.security.SecureRandom()
    )
    val senderAddress: Address = Address(
      crypto
        .kec256(
          senderKeys.getPublic
            .asInstanceOf[org.bouncycastle.crypto.params.ECPublicKeyParameters]
            .getQ
            .getEncoded(false)
            .tail
        )
        .drop(12)
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
      val funded = world.saveAccount(
        senderAddress,
        Account(balance = UInt256(BigInt("1000000000000000000")))
      )
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
      gasLimit = GasAmount(GenesisGasLimit),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(1000),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostOlympia(BaseFeeCalculator.InitialBaseFee)
    )

    blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil))).commit()
    // saveBestKnownBlocks, not putBestBlockNumber: `getBestBlock` resolves the head through
    // appStateStorage's BlockInfo (hash AND number). Writing only the number leaves the hash at
    // its zero default, getBestBlock returns None, and testing_commitBlockV1 has no head to build
    // on — which is exactly the failure this fixture must not fake past.
    blockchainWriter.saveBestKnownBlocks(genesisHeader.hash, 0)

    def attrs(ts: Long = 1001L): PayloadAttributes = PayloadAttributes(
      timestamp = ts,
      prevRandao = ByteString(Array.fill(32)(0x42.toByte)),
      suggestedFeeRecipient = Address(ByteString(new Array[Byte](20))),
      withdrawals = Some(Nil)
    )

    /** A signed, fundable value transfer at `nonce`, in the raw form the RPC parameter carries.
      *
      * Type-2 (EIP-1559) on purpose: every transaction in the execution-apis testing_* fixtures is type-2, and the
      * y-parity signing schema sidesteps the legacy EIP-155 `v = chainId*2+35` encoding, which does not fit this
      * chain's id.
      */
    def rawTx(nonce: BigInt): ByteString =
      val tx = TransactionWithDynamicFee(
        chainId = blockchainConfig.chainId.value,
        nonce = nonce,
        maxPriorityFeePerGas = BigInt(500),
        maxFeePerGas = BigInt(2_000_000_000L),
        gasLimit = GasAmount(21000),
        receivingAddress = Some(Address(0x42)),
        value = BigInt(1),
        payload = ByteString.empty,
        accessList = Nil
      )
      val stx = SignedTransaction.sign(tx, senderKeys, Some(blockchainConfig.chainId.value))
      ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))
