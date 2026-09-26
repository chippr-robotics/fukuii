package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager.BeaconHead
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.consensus.validators.std.StdValidators
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
class EngineApiServiceSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  /** Focused test: StdValidators.validateBlockAfterExecution should detect field mismatches */
  "StdValidators.validateBlockAfterExecution" should {

    "reject block with modified stateRoot" taggedAs UnitTest in {
      val validators = MockValidatorsAlwaysSucceed
      val correctStateRoot = ByteString(Array.fill(32)(0x11.toByte))
      val modifiedStateRoot = ByteString(Array.fill(32)(0xaa.toByte))
      val header = BlockHeader(
        parentHash = BlockHash(ByteString(new Array[Byte](32))),
        ommersHash = BlockHash(ByteString(new Array[Byte](32))),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(modifiedStateRoot), // block claims this stateRoot
        transactionsRoot = TrieRoot(ByteString(new Array[Byte](32))),
        receiptsRoot = TrieRoot(ByteString(new Array[Byte](32))),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(1),
        gasLimit = GasAmount(3000000),
        gasUsed = GasAmount(21000),
        unixTimestamp = Timestamp(1000),
        extraData = ByteString.empty,
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )
      val block = Block(header, BlockBody(Nil, Nil))

      val result = StdValidators.validateBlockAfterExecution(
        self = validators,
        block = block,
        stateRootHash = correctStateRoot, // execution computed this
        receipts = Seq.empty,
        gasUsed = 21000
      )(using com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig)

      result.isLeft shouldBe true
      result.left.getOrElse(null).toString should include("state root")
    }

    "reject block with modified gasUsed" taggedAs UnitTest in {
      val validators = MockValidatorsAlwaysSucceed
      val stateRoot = TrieRoot(ByteString(Array.fill(32)(0x11.toByte)))
      val header = BlockHeader(
        parentHash = BlockHash(ByteString(new Array[Byte](32))),
        ommersHash = BlockHash(ByteString(new Array[Byte](32))),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = stateRoot,
        transactionsRoot = TrieRoot(ByteString(new Array[Byte](32))),
        receiptsRoot = TrieRoot(ByteString(new Array[Byte](32))),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(1),
        gasLimit = GasAmount(3000000),
        gasUsed = GasAmount(99999), // block claims this gasUsed
        unixTimestamp = Timestamp(1000),
        extraData = ByteString.empty,
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )
      val block = Block(header, BlockBody(Nil, Nil))

      val result = StdValidators.validateBlockAfterExecution(
        self = validators,
        block = block,
        stateRootHash = stateRoot.value,
        receipts = Seq.empty,
        gasUsed = 21000 // execution computed different gasUsed
      )(using com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig)

      result.isLeft shouldBe true
      result.left.getOrElse(null).toString should include("gas used")
    }

    "accept block with matching stateRoot and gasUsed" taggedAs UnitTest in {
      val validators = MockValidatorsAlwaysSucceed
      val stateRoot = TrieRoot(ByteString(Array.fill(32)(0x11.toByte)))
      val header = BlockHeader(
        parentHash = BlockHash(ByteString(new Array[Byte](32))),
        ommersHash = BlockHash(ByteString(new Array[Byte](32))),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = stateRoot,
        transactionsRoot = TrieRoot(ByteString(new Array[Byte](32))),
        receiptsRoot = TrieRoot(ByteString(new Array[Byte](32))),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(1),
        gasLimit = GasAmount(3000000),
        gasUsed = GasAmount(21000),
        unixTimestamp = Timestamp(1000),
        extraData = ByteString.empty,
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )
      val block = Block(header, BlockBody(Nil, Nil))

      val result = StdValidators.validateBlockAfterExecution(
        self = validators,
        block = block,
        stateRootHash = stateRoot.value, // matches
        receipts = Seq.empty,
        gasUsed = 21000 // matches
      )(using com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig)

      result.isRight shouldBe true
    }
  }

  /** End-to-end test: EngineApiService.newPayload with real block execution */
  "EngineApiService.newPayload" should {

    trait EngineApiTestSetup extends EphemBlockchainTestSetup:

      // Use real VM and validators (not mocks) to test actual validation
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
        org.apache.pekko.actor.typed.scaladsl.Behaviors.ignore[
          com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
        ],
        "ptm-ignore-engine-spec"
      )
      implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

      lazy val engineApi = new EngineApiService(
        blockchainReader,
        blockchainWriter,
        blockExec,
        forkChoiceManager,
        Some(pendingTxManager)
      )(blockchainConfig, typedScheduler)

      // Build a post-merge genesis block with accounts
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
        // Fund a test account
        val funded = world.saveAccount(
          Address(ByteString(Array.fill(20)(0x01.toByte))),
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
        gasLimit = GasAmount(3000000),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(1000),
        extraData = ByteString.empty,
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )

      // Store genesis block
      blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil))).commit()
      storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

      /** Build a valid block 1 on top of genesis, execute it to get correct fields */
      def buildValidBlock1(): (Block, Seq[Receipt]) =
        val emptyWithdrawalsRoot = BlockHeader.EmptyMpt

        val headerTemplate = BlockHeader(
          parentHash = genesisHeader.hash,
          ommersHash = BlockHash(BlockHeader.EmptyOmmers),
          beneficiary = ByteString(new Array[Byte](20)),
          stateRoot = TrieRoot(ByteString.empty), // will be filled after execution
          transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
          receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
          logsBloom = BloomFilter.Empty,
          difficulty = Difficulty.Zero,
          number = BlockNumber(1),
          gasLimit = GasAmount(3000000),
          gasUsed = GasAmount(0),
          unixTimestamp = Timestamp(1001),
          extraData = ByteString("fukuii".getBytes),
          mixHash = BlockHash(ByteString(Array.fill(32)(0x42.toByte))), // prevRandao
          nonce = ByteString(new Array[Byte](8)),
          extraFields = HefPostShanghai(
            baseFee = BigInt("1000000000"),
            withdrawalsRoot = emptyWithdrawalsRoot
          )
        )
        val block = Block(headerTemplate, BlockBody(Nil, Nil, withdrawals = Some(Nil)))

        // Execute to compute the correct stateRoot, receiptsRoot, gasUsed
        blockExec.executeBlockNoValidation(block)(blockchainConfig) match
          case Right((receipts, gasUsed, computedStateRoot)) =>
            // Build the correct header with computed values
            val correctHeader = headerTemplate.copy(
              stateRoot = TrieRoot(computedStateRoot),
              gasUsed = GasAmount(gasUsed)
            )
            (Block(correctHeader, block.body), receipts)
          case Left(error) =>
            throw new RuntimeException(s"Failed to execute block: ${error.describe}")

      def blockToPayload(block: Block): ExecutionPayload =
        import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
        import com.chipprbots.ethereum.rlp.encode as rlpEncode

        ExecutionPayload(
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
          transactions = block.body.transactionList.map { stx =>
            ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))
          },
          withdrawals = block.body.withdrawals
        )

      /** Create modified payload with random stateRoot, recomputing blockHash */
      def withModifiedStateRoot(payload: ExecutionPayload): ExecutionPayload =
        val randomStateRoot = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
        val modified = payload.copy(stateRoot = randomStateRoot)
        // Recompute blockHash from the modified header
        val _ = engineApi.asInstanceOf[{ def payloadToBlock(p: ExecutionPayload): Block }]
        // Instead, manually build the header and compute hash
        val header = BlockHeader(
          parentHash = BlockHash(modified.parentHash),
          ommersHash = BlockHash(BlockHeader.EmptyOmmers),
          beneficiary = modified.feeRecipient.bytes,
          stateRoot = TrieRoot(modified.stateRoot),
          transactionsRoot = TrieRoot(payload.blockHash), // placeholder, need real txRoot
          receiptsRoot = TrieRoot(modified.receiptsRoot),
          logsBloom = BloomFilter(modified.logsBloom),
          difficulty = Difficulty.Zero,
          number = BlockNumber(modified.blockNumber),
          gasLimit = GasAmount(modified.gasLimit),
          gasUsed = GasAmount(modified.gasUsed),
          unixTimestamp = Timestamp(modified.timestamp),
          extraData = modified.extraData,
          mixHash = BlockHash(modified.prevRandao),
          nonce = ByteString(new Array[Byte](8)),
          extraFields = HefPostShanghai(
            baseFee = modified.baseFeePerGas,
            withdrawalsRoot = BlockHeader.EmptyMpt
          )
        )
        // Need to use same txRoot as the original block
        modified.copy(blockHash = header.hash.value)

    "give an executed block a ChainWeight, so p2p branch resolution can cross it" taggedAs UnitTest in
      new EngineApiTestSetup:
        // WHY. `storeBlock`/`storeBlockByHashOnly` write a header and a body and nothing else, and nothing in the
        // Engine API used to write a weight. A node whose chain was built entirely through newPayload therefore had
        // no weight for any of its own blocks, and `BranchResolution.compareBranch` aborted the first time a
        // competing branch arrived:
        //   ERROR [BranchResolution] ChainWeight for 6: c05c5658… not found when resolving branch
        // Measured on hive `engine` 6c8bc97 in 32 of 486 client logs, covering all 24
        // `Missing Ancestor Syncing … CanonicalReOrg=True` failures and all 4 `Withdrawals … Re-Org Sync` timeouts.
        // GenesisDataLoader writes a weight for genesis in production; the fixture stores genesis raw, so do it here.
        blockchainWriter
          .storeChainWeight(genesisHeader.hash, ChainWeight.totalDifficultyOnly(genesisHeader.difficulty.value))
          .commit()
        blockchainReader.getChainWeightByHash(genesisHeader.hash) should not be empty

        val (validBlock, _) = buildValidBlock1()
        engineApi.newPayload(blockToPayload(validBlock)).unsafeRunSync().status shouldBe Valid

        // Post-merge the NUMBER is uninteresting — difficulty is 0, so it equals the parent's. The entry EXISTING is
        // the whole point: `None` is what aborted branch resolution.
        blockchainReader.getChainWeightByHash(validBlock.header.hash) shouldBe
          blockchainReader.getChainWeightByHash(genesisHeader.hash)

    "not invent a ChainWeight when the parent has none" taggedAs UnitTest in new EngineApiTestSetup:
      // The fixture deliberately leaves genesis without a weight. A fabricated value would be a worse answer than an
      // absent one on a chain where the number still means something, and execution must be unaffected either way.
      blockchainReader.getChainWeightByHash(genesisHeader.hash) shouldBe None

      val (validBlock, _) = buildValidBlock1()
      engineApi.newPayload(blockToPayload(validBlock)).unsafeRunSync().status shouldBe Valid

      blockchainReader.getChainWeightByHash(validBlock.header.hash) shouldBe None

    "return VALID for a correctly constructed empty block" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      val result: PayloadStatusV1 = engineApi.newPayload(payload).unsafeRunSync()

      result.status shouldBe Valid
      result.latestValidHash shouldBe Some(validBlock.header.hash)

    "return INVALID with null latestValidHash on hash mismatch (parent known)" taggedAs UnitTest in
      new EngineApiTestSetup:
        // Per execution-apis PR #338 (Shanghai+): hash mismatch returns INVALID (not
        // INVALID_BLOCK_HASH) with latestValidHash=null. The corruption is in the
        // payload envelope, not attributable to a specific ancestor. Aligns with hive
        // "Bad Hash on NewPayload" tests.
        val (validBlock, _) = buildValidBlock1()
        val payload: ExecutionPayload = blockToPayload(validBlock)
        val badPayload: ExecutionPayload = payload.copy(blockHash = ByteString(Array.fill(32)(0xff.toByte)))

        val result: PayloadStatusV1 = engineApi.newPayload(badPayload).unsafeRunSync()

        result.status shouldBe Invalid
        result.latestValidHash shouldBe None

    "return INVALID with null latestValidHash on hash mismatch (parent unknown)" taggedAs UnitTest in
      new EngineApiTestSetup:
        val (validBlock, _) = buildValidBlock1()
        val payload: ExecutionPayload = blockToPayload(validBlock)
        val badPayload: ExecutionPayload = payload.copy(
          parentHash = ByteString(kec256(Array[Byte](9, 9, 9))),
          blockHash = ByteString(Array.fill(32)(0xff.toByte))
        )

        val result: PayloadStatusV1 = engineApi.newPayload(badPayload).unsafeRunSync()

        result.status shouldBe Invalid
        result.latestValidHash shouldBe None

    "return INVALID for block with modified stateRoot" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      // Modify the stateRoot and recompute blockHash to match
      val randomStateRoot: ByteString = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
      val modifiedHeader: BlockHeader = validBlock.header.copy(stateRoot = TrieRoot(randomStateRoot))
      val modifiedPayload: ExecutionPayload = payload.copy(
        stateRoot = randomStateRoot,
        blockHash = modifiedHeader.hash.value
      )

      val result: PayloadStatusV1 = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      // Should be INVALID — execution produces different stateRoot than header claims
      result.status shouldBe Invalid
      result.latestValidHash shouldBe Some(genesisHeader.hash)
      result.validationError should not be empty

    "return INVALID when newPayloadV3 expectedBlobVersionedHashes mismatches payload txs" taggedAs UnitTest in
      new EngineApiTestSetup:
        // EIP-4844 / Engine API V3: the CL passes expectedBlobVersionedHashes as the 2nd param
        // to engine_newPayloadV3; the EL must compare the CL's list against the concatenation
        // of every blob tx's blobVersionedHashes in the payload and reject on mismatch. The
        // payload built here has no blob txs (derived list is empty), but we declare a single
        // expected hash — so the comparison must fail and return INVALID with
        // latestValidHash = parent.hash.
        val (validBlock, _) = buildValidBlock1()
        val payload: ExecutionPayload = blockToPayload(validBlock)
        val fakeHash: ByteString = ByteString(kec256(Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)))
        val payloadWithMismatchedHashes: ExecutionPayload = payload.copy(
          expectedBlobVersionedHashes = Some(Seq(fakeHash))
        )

        val result: PayloadStatusV1 = engineApi.newPayload(payloadWithMismatchedHashes).unsafeRunSync()

        result.status shouldBe Invalid
        result.latestValidHash shouldBe Some(genesisHeader.hash)
        result.validationError.getOrElse("") should include("INVALID_VERSIONED_HASHES")

    "return INVALID for block with modified gasUsed" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      // Modify gasUsed and recompute blockHash
      val modifiedGasUsed: BigInt = (validBlock.header.gasUsed + GasAmount(999)).value
      val modifiedHeader: BlockHeader = validBlock.header.copy(gasUsed = GasAmount(modifiedGasUsed))
      val modifiedPayload: ExecutionPayload = payload.copy(
        gasUsed = modifiedGasUsed,
        blockHash = modifiedHeader.hash.value
      )

      val result: PayloadStatusV1 = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      result.status shouldBe Invalid

    "return ACCEPTED/SYNCING for block with unknown parentHash" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      // Modify parentHash to unknown hash and recompute blockHash
      val unknownParent: ByteString = ByteString(kec256(Array[Byte](9, 8, 7, 6)))
      val modifiedHeader: BlockHeader = validBlock.header.copy(parentHash = BlockHash(unknownParent))
      val modifiedPayload: ExecutionPayload = payload.copy(
        parentHash = unknownParent,
        blockHash = modifiedHeader.hash.value
      )

      val result: PayloadStatusV1 = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      // Parent unknown → ACCEPTED (not INVALID, not VALID)
      result.status shouldBe Accepted
      result.latestValidHash shouldBe None

    "store ACCEPTED blocks by hash only (not by number)" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      val unknownParent: ByteString = ByteString(kec256(Array[Byte](9, 8, 7, 6)))
      val modifiedHeader: BlockHeader = validBlock.header.copy(parentHash = BlockHash(unknownParent))
      val modifiedPayload: ExecutionPayload = payload.copy(
        parentHash = unknownParent,
        blockHash = modifiedHeader.hash.value
      )

      engineApi.newPayload(modifiedPayload).unsafeRunSync()

      // ACCEPTED block IS stored by hash (for later re-validation)
      blockchainReader.getBlockHeaderByHash(BlockHash(modifiedPayload.blockHash)) shouldBe defined
      // But NOT stored by number
      blockchainReader.getBlockHeaderByNumber(1).map(_.hash) should not be Some(modifiedPayload.blockHash)

    "return INVALID for block with modified timestamp (header validation)" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      // Set timestamp <= parent timestamp (invalid per spec)
      val modifiedHeader: BlockHeader = validBlock.header.copy(unixTimestamp = genesisHeader.unixTimestamp)
      val modifiedPayload: ExecutionPayload = payload.copy(
        timestamp = genesisHeader.unixTimestamp.toLong,
        blockHash = modifiedHeader.hash.value
      )

      val result: PayloadStatusV1 = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      result.status shouldBe Invalid
      result.validationError.getOrElse("") should include("timestamp")

    "return INVALID for block with wrong number (header validation)" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      // Set number != parent.number + 1
      val modifiedHeader: BlockHeader = validBlock.header.copy(number = BlockNumber(5))
      val modifiedPayload: ExecutionPayload = payload.copy(
        blockNumber = 5,
        blockHash = modifiedHeader.hash.value
      )

      val result: PayloadStatusV1 = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      result.status shouldBe Invalid
      result.validationError.getOrElse("") should include("block number")

    "not store INVALID blocks in hash storage" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      val randomStateRoot: ByteString = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
      val modifiedHeader: BlockHeader = validBlock.header.copy(stateRoot = TrieRoot(randomStateRoot))
      val modifiedPayload: ExecutionPayload = payload.copy(
        stateRoot = randomStateRoot,
        blockHash = modifiedHeader.hash.value
      )

      val result: PayloadStatusV1 = engineApi.newPayload(modifiedPayload).unsafeRunSync()
      result.status shouldBe Invalid

      // The INVALID block should NOT be accessible by hash
      blockchainReader.getBlockHeaderByHash(BlockHash(modifiedPayload.blockHash)) shouldBe None

    "mark child of INVALID block as INVALID" taggedAs UnitTest in new EngineApiTestSetup:
      val (validBlock, _) = buildValidBlock1()
      val payload: ExecutionPayload = blockToPayload(validBlock)

      // First send an INVALID block (bad stateRoot)
      val randomStateRoot: ByteString = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
      val modifiedHeader: BlockHeader = validBlock.header.copy(stateRoot = TrieRoot(randomStateRoot))
      val invalidPayload: ExecutionPayload = payload.copy(
        stateRoot = randomStateRoot,
        blockHash = modifiedHeader.hash.value
      )
      val r1: PayloadStatusV1 = engineApi.newPayload(invalidPayload).unsafeRunSync()
      r1.status shouldBe Invalid

      // Now send a child block referencing the invalid parent
      val childHeader: BlockHeader = BlockHeader(
        parentHash = BlockHash(invalidPayload.blockHash),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(ByteString(new Array[Byte](32))),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(2),
        gasLimit = GasAmount(3000000),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(1002),
        extraData = ByteString("fukuii".getBytes),
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostShanghai(BigInt("1000000000"), BlockHeader.EmptyMpt)
      )
      val childPayload: ExecutionPayload = ExecutionPayload(
        parentHash = invalidPayload.blockHash,
        feeRecipient = Address(ByteString(new Array[Byte](20))),
        stateRoot = ByteString(new Array[Byte](32)),
        receiptsRoot = BlockHeader.EmptyMpt,
        logsBloom = BloomFilter.Empty.value,
        prevRandao = ByteString(new Array[Byte](32)),
        blockNumber = 2,
        gasLimit = 3000000,
        gasUsed = 0,
        timestamp = 1002,
        extraData = ByteString("fukuii".getBytes),
        baseFeePerGas = BigInt("1000000000"),
        blockHash = childHeader.hash.value,
        transactions = Seq.empty,
        withdrawals = Some(Nil)
      )

      val r2: PayloadStatusV1 = engineApi.newPayload(childPayload).unsafeRunSync()
      r2.status shouldBe Invalid
      r2.validationError.getOrElse("") should include("parent")
      // latestValidHash should propagate from the invalid parent — it should be the genesis hash
      // (the last valid ancestor before the invalid block)
      r2.latestValidHash shouldBe Some(genesisHeader.hash)

    /** Regression for cold-start sync bootstrap (post-merge chains).
      *
      * Pre-fix, `engine_forkchoiceUpdated` short-circuited to SYNCING for unknown heads **without** invoking
      * `forkChoiceManager.applyForkChoiceState`, which is the only path that publishes `BeaconHead` to the
      * ForkChoiceManager listener. SyncController's BeaconHead handler is the trigger that forwards `CLPivotHint` to
      * SNAPSyncController; without it, SNAP sync sat in `[CL-PIVOT] waiting for engine_forkchoiceUpdated` forever —
      * fukuii accepted every newPayload as `ACCEPTED (parent unknown)` but never started actually syncing.
      */
    "publish BeaconHead to ForkChoiceManager listener even when the head is unknown (SYNCING short-circuit)"
      .taggedAs(UnitTest) in new EngineApiTestSetup:
      import org.apache.pekko.testkit.TestProbe

      val probe: TestProbe = TestProbe()(classicSystem)
      forkChoiceManager.setListener(probe.ref)

      val unknownHead: ByteString = ByteString(Array.fill(32)(0xab.toByte))
      val state: ForkChoiceState = ForkChoiceState(
        headBlockHash = unknownHead,
        safeBlockHash = ByteString(new Array[Byte](32)),
        finalizedBlockHash = ByteString(new Array[Byte](32))
      )

      val response: Either[String, ForkchoiceUpdatedResponse] =
        engineApi.forkchoiceUpdated(state, payloadAttributes = None).unsafeRunSync()
      response.isRight shouldBe true
      response.toOption.get.payloadStatus.status shouldBe Syncing

      // Critical assertion: BeaconHead must reach the listener so SyncController can drive
      // SNAP sync's CL-PIVOT trigger. Pre-fix this would TIMEOUT — applyForkChoiceState
      // was never called on the SYNCING short-circuit path.
      val beacon: BeaconHead = probe.expectMsgType[ForkChoiceManager.BeaconHead]
      beacon.headHash shouldBe unknownHead
      beacon.knownHeader shouldBe None
  }
