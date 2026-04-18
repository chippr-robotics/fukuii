package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.PayloadStatus._
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.validators.std.StdValidators
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.Mocks.MockVM
import com.chipprbots.ethereum.testing.Tags._

import BlockHeaderImplicits._

// scalastyle:off magic.number
class EngineApiServiceSpec extends AnyWordSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  /** Focused test: StdValidators.validateBlockAfterExecution should detect field mismatches */
  "StdValidators.validateBlockAfterExecution" should {

    "reject block with modified stateRoot" taggedAs UnitTest in {
      val validators = MockValidatorsAlwaysSucceed
      val correctStateRoot = ByteString(Array.fill(32)(0x11.toByte))
      val modifiedStateRoot = ByteString(Array.fill(32)(0xaa.toByte))
      val header = BlockHeader(
        parentHash = ByteString(new Array[Byte](32)),
        ommersHash = ByteString(new Array[Byte](32)),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = modifiedStateRoot, // block claims this stateRoot
        transactionsRoot = ByteString(new Array[Byte](32)),
        receiptsRoot = ByteString(new Array[Byte](32)),
        logsBloom = ByteString(new Array[Byte](256)),
        difficulty = 0,
        number = 1,
        gasLimit = 3000000,
        gasUsed = 21000,
        unixTimestamp = 1000,
        extraData = ByteString.empty,
        mixHash = ByteString(new Array[Byte](32)),
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
      )

      result.isLeft shouldBe true
      result.left.getOrElse(null).toString should include("state root")
    }

    "reject block with modified gasUsed" taggedAs UnitTest in {
      val validators = MockValidatorsAlwaysSucceed
      val stateRoot = ByteString(Array.fill(32)(0x11.toByte))
      val header = BlockHeader(
        parentHash = ByteString(new Array[Byte](32)),
        ommersHash = ByteString(new Array[Byte](32)),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = stateRoot,
        transactionsRoot = ByteString(new Array[Byte](32)),
        receiptsRoot = ByteString(new Array[Byte](32)),
        logsBloom = ByteString(new Array[Byte](256)),
        difficulty = 0,
        number = 1,
        gasLimit = 3000000,
        gasUsed = 99999, // block claims this gasUsed
        unixTimestamp = 1000,
        extraData = ByteString.empty,
        mixHash = ByteString(new Array[Byte](32)),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )
      val block = Block(header, BlockBody(Nil, Nil))

      val result = StdValidators.validateBlockAfterExecution(
        self = validators,
        block = block,
        stateRootHash = stateRoot,
        receipts = Seq.empty,
        gasUsed = 21000 // execution computed different gasUsed
      )

      result.isLeft shouldBe true
      result.left.getOrElse(null).toString should include("gas used")
    }

    "accept block with matching stateRoot and gasUsed" taggedAs UnitTest in {
      val validators = MockValidatorsAlwaysSucceed
      val stateRoot = ByteString(Array.fill(32)(0x11.toByte))
      val header = BlockHeader(
        parentHash = ByteString(new Array[Byte](32)),
        ommersHash = ByteString(new Array[Byte](32)),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = stateRoot,
        transactionsRoot = ByteString(new Array[Byte](32)),
        receiptsRoot = ByteString(new Array[Byte](32)),
        logsBloom = ByteString(new Array[Byte](256)),
        difficulty = 0,
        number = 1,
        gasLimit = 3000000,
        gasUsed = 21000,
        unixTimestamp = 1000,
        extraData = ByteString.empty,
        mixHash = ByteString(new Array[Byte](32)),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )
      val block = Block(header, BlockBody(Nil, Nil))

      val result = StdValidators.validateBlockAfterExecution(
        self = validators,
        block = block,
        stateRootHash = stateRoot, // matches
        receipts = Seq.empty,
        gasUsed = 21000 // matches
      )

      result.isRight shouldBe true
    }
  }

  /** End-to-end test: EngineApiService.newPayload with real block execution */
  "EngineApiService.newPayload" should {

    trait EngineApiTestSetup extends EphemBlockchainTestSetup {

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
      lazy val pendingTxManager = org.apache.pekko.actor.ActorRef.noSender

      lazy val engineApi = new EngineApiService(
        blockchainReader,
        blockchainWriter,
        blockExec,
        forkChoiceManager,
        mining,
        storagesInstance.storages.evmCodeStorage,
        pendingTxManager
      )(blockchainConfig)

      // Build a post-merge genesis block with accounts
      private val genesisStateRoot = {
        val world = InMemoryWorldStateProxy(
          storagesInstance.storages.evmCodeStorage,
          blockchain.getBackingMptStorage(0),
          (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash),
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
      }

      val genesisHeader = BlockHeader(
        parentHash = ByteString(new Array[Byte](32)),
        ommersHash = BlockHeader.EmptyOmmers,
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = genesisStateRoot,
        transactionsRoot = BlockHeader.EmptyMpt,
        receiptsRoot = BlockHeader.EmptyMpt,
        logsBloom = ByteString(new Array[Byte](256)),
        difficulty = 0,
        number = 0,
        gasLimit = 3000000,
        gasUsed = 0,
        unixTimestamp = 1000,
        extraData = ByteString.empty,
        mixHash = ByteString(new Array[Byte](32)),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )

      // Store genesis block
      blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil))).commit()
      storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

      /** Build a valid block 1 on top of genesis, execute it to get correct fields */
      def buildValidBlock1(): (Block, Seq[Receipt]) = {
        val emptyWithdrawalsRoot = BlockHeader.EmptyMpt

        val headerTemplate = BlockHeader(
          parentHash = genesisHeader.hash,
          ommersHash = BlockHeader.EmptyOmmers,
          beneficiary = ByteString(new Array[Byte](20)),
          stateRoot = ByteString.empty, // will be filled after execution
          transactionsRoot = BlockHeader.EmptyMpt,
          receiptsRoot = BlockHeader.EmptyMpt,
          logsBloom = ByteString(new Array[Byte](256)),
          difficulty = 0,
          number = 1,
          gasLimit = 3000000,
          gasUsed = 0,
          unixTimestamp = 1001,
          extraData = ByteString("fukuii".getBytes),
          mixHash = ByteString(Array.fill(32)(0x42.toByte)), // prevRandao
          nonce = ByteString(new Array[Byte](8)),
          extraFields = HefPostShanghai(
            baseFee = BigInt("1000000000"),
            withdrawalsRoot = emptyWithdrawalsRoot
          )
        )
        val block = Block(headerTemplate, BlockBody(Nil, Nil, withdrawals = Some(Nil)))

        // Execute to compute the correct stateRoot, receiptsRoot, gasUsed
        blockExec.executeBlockNoValidation(block)(blockchainConfig) match {
          case Right((receipts, gasUsed, computedStateRoot)) =>
            // Build the correct header with computed values
            val correctHeader = headerTemplate.copy(
              stateRoot = computedStateRoot,
              gasUsed = gasUsed
            )
            (Block(correctHeader, block.body), receipts)
          case Left(error) =>
            throw new RuntimeException(s"Failed to execute block: ${error.reason}")
        }
      }

      def blockToPayload(block: Block): ExecutionPayload = {
        import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._
        import com.chipprbots.ethereum.rlp.{encode => rlpEncode}

        ExecutionPayload(
          parentHash = block.header.parentHash,
          feeRecipient = Address(block.header.beneficiary),
          stateRoot = block.header.stateRoot,
          receiptsRoot = block.header.receiptsRoot,
          logsBloom = block.header.logsBloom,
          prevRandao = block.header.mixHash,
          blockNumber = block.header.number,
          gasLimit = block.header.gasLimit,
          gasUsed = block.header.gasUsed,
          timestamp = block.header.unixTimestamp,
          extraData = block.header.extraData,
          baseFeePerGas = block.header.baseFee.getOrElse(BigInt(0)),
          blockHash = block.header.hash,
          transactions = block.body.transactionList.map { stx =>
            ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))
          },
          withdrawals = block.body.withdrawals
        )
      }

      /** Create modified payload with random stateRoot, recomputing blockHash */
      def withModifiedStateRoot(payload: ExecutionPayload): ExecutionPayload = {
        val randomStateRoot = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
        val modified = payload.copy(stateRoot = randomStateRoot)
        // Recompute blockHash from the modified header
        val modifiedBlock = engineApi.asInstanceOf[{ def payloadToBlock(p: ExecutionPayload): Block }]
        // Instead, manually build the header and compute hash
        val header = BlockHeader(
          parentHash = modified.parentHash,
          ommersHash = BlockHeader.EmptyOmmers,
          beneficiary = modified.feeRecipient.bytes,
          stateRoot = modified.stateRoot,
          transactionsRoot = payload.blockHash, // placeholder, need real txRoot
          receiptsRoot = modified.receiptsRoot,
          logsBloom = modified.logsBloom,
          difficulty = 0,
          number = modified.blockNumber,
          gasLimit = modified.gasLimit,
          gasUsed = modified.gasUsed,
          unixTimestamp = modified.timestamp,
          extraData = modified.extraData,
          mixHash = modified.prevRandao,
          nonce = ByteString(new Array[Byte](8)),
          extraFields = HefPostShanghai(
            baseFee = modified.baseFeePerGas,
            withdrawalsRoot = BlockHeader.EmptyMpt
          )
        )
        // Need to use same txRoot as the original block
        modified.copy(blockHash = header.hash)
      }
    }

    "return VALID for a correctly constructed empty block" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      val result = engineApi.newPayload(payload).unsafeRunSync()

      result.status shouldBe Valid
      result.latestValidHash shouldBe Some(validBlock.header.hash)
    }

    "return INVALID_BLOCK_HASH when blockHash doesn't match header" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)
      val badPayload = payload.copy(blockHash = ByteString(Array.fill(32)(0xff.toByte)))

      val result = engineApi.newPayload(badPayload).unsafeRunSync()

      result.status shouldBe a[InvalidBlockHash]
    }

    "return INVALID for block with modified stateRoot" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      // Modify the stateRoot and recompute blockHash to match
      val randomStateRoot = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
      val modifiedHeader = validBlock.header.copy(stateRoot = randomStateRoot)
      val modifiedPayload = payload.copy(
        stateRoot = randomStateRoot,
        blockHash = modifiedHeader.hash
      )

      val result = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      // Should be INVALID — execution produces different stateRoot than header claims
      result.status shouldBe Invalid
      result.latestValidHash shouldBe Some(genesisHeader.hash)
      result.validationError should not be empty
    }

    "return INVALID for block with modified gasUsed" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      // Modify gasUsed and recompute blockHash
      val modifiedGasUsed = validBlock.header.gasUsed + 999
      val modifiedHeader = validBlock.header.copy(gasUsed = modifiedGasUsed)
      val modifiedPayload = payload.copy(
        gasUsed = modifiedGasUsed,
        blockHash = modifiedHeader.hash
      )

      val result = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      result.status shouldBe Invalid
    }

    "return ACCEPTED/SYNCING for block with unknown parentHash" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      // Modify parentHash to unknown hash and recompute blockHash
      val unknownParent = ByteString(kec256(Array[Byte](9, 8, 7, 6)))
      val modifiedHeader = validBlock.header.copy(parentHash = unknownParent)
      val modifiedPayload = payload.copy(
        parentHash = unknownParent,
        blockHash = modifiedHeader.hash
      )

      val result = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      // Parent unknown → ACCEPTED (not INVALID, not VALID)
      result.status shouldBe Accepted
      result.latestValidHash shouldBe None
    }

    "store ACCEPTED blocks by hash only (not by number)" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      val unknownParent = ByteString(kec256(Array[Byte](9, 8, 7, 6)))
      val modifiedHeader = validBlock.header.copy(parentHash = unknownParent)
      val modifiedPayload = payload.copy(
        parentHash = unknownParent,
        blockHash = modifiedHeader.hash
      )

      engineApi.newPayload(modifiedPayload).unsafeRunSync()

      // ACCEPTED block IS stored by hash (for later re-validation)
      blockchainReader.getBlockHeaderByHash(modifiedPayload.blockHash) shouldBe defined
      // But NOT stored by number
      blockchainReader.getBlockHeaderByNumber(1).map(_.hash) should not be Some(modifiedPayload.blockHash)
    }

    "return INVALID for block with modified timestamp (header validation)" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      // Set timestamp <= parent timestamp (invalid per spec)
      val modifiedHeader = validBlock.header.copy(unixTimestamp = genesisHeader.unixTimestamp)
      val modifiedPayload = payload.copy(
        timestamp = genesisHeader.unixTimestamp,
        blockHash = modifiedHeader.hash
      )

      val result = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      result.status shouldBe Invalid
      result.validationError.getOrElse("") should include("timestamp")
    }

    "return INVALID for block with wrong number (header validation)" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      // Set number != parent.number + 1
      val modifiedHeader = validBlock.header.copy(number = 5)
      val modifiedPayload = payload.copy(
        blockNumber = 5,
        blockHash = modifiedHeader.hash
      )

      val result = engineApi.newPayload(modifiedPayload).unsafeRunSync()

      result.status shouldBe Invalid
      result.validationError.getOrElse("") should include("block number")
    }

    "not store INVALID blocks in hash storage" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      val randomStateRoot = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
      val modifiedHeader = validBlock.header.copy(stateRoot = randomStateRoot)
      val modifiedPayload = payload.copy(
        stateRoot = randomStateRoot,
        blockHash = modifiedHeader.hash
      )

      val result = engineApi.newPayload(modifiedPayload).unsafeRunSync()
      result.status shouldBe Invalid

      // The INVALID block should NOT be accessible by hash
      blockchainReader.getBlockHeaderByHash(modifiedPayload.blockHash) shouldBe None
    }

    "mark child of INVALID block as INVALID" taggedAs UnitTest in new EngineApiTestSetup {
      val (validBlock, _) = buildValidBlock1()
      val payload = blockToPayload(validBlock)

      // First send an INVALID block (bad stateRoot)
      val randomStateRoot = ByteString(kec256(Array[Byte](1, 2, 3, 4)))
      val modifiedHeader = validBlock.header.copy(stateRoot = randomStateRoot)
      val invalidPayload = payload.copy(
        stateRoot = randomStateRoot,
        blockHash = modifiedHeader.hash
      )
      val r1 = engineApi.newPayload(invalidPayload).unsafeRunSync()
      r1.status shouldBe Invalid

      // Now send a child block referencing the invalid parent
      val childHeader = BlockHeader(
        parentHash = invalidPayload.blockHash,
        ommersHash = BlockHeader.EmptyOmmers,
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = ByteString(new Array[Byte](32)),
        transactionsRoot = BlockHeader.EmptyMpt,
        receiptsRoot = BlockHeader.EmptyMpt,
        logsBloom = ByteString(new Array[Byte](256)),
        difficulty = 0,
        number = 2,
        gasLimit = 3000000,
        gasUsed = 0,
        unixTimestamp = 1002,
        extraData = ByteString("fukuii".getBytes),
        mixHash = ByteString(new Array[Byte](32)),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostShanghai(BigInt("1000000000"), BlockHeader.EmptyMpt)
      )
      val childPayload = ExecutionPayload(
        parentHash = invalidPayload.blockHash,
        feeRecipient = Address(ByteString(new Array[Byte](20))),
        stateRoot = ByteString(new Array[Byte](32)),
        receiptsRoot = BlockHeader.EmptyMpt,
        logsBloom = ByteString(new Array[Byte](256)),
        prevRandao = ByteString(new Array[Byte](32)),
        blockNumber = 2,
        gasLimit = 3000000,
        gasUsed = 0,
        timestamp = 1002,
        extraData = ByteString("fukuii".getBytes),
        baseFeePerGas = BigInt("1000000000"),
        blockHash = childHeader.hash,
        transactions = Seq.empty,
        withdrawals = Some(Nil)
      )

      val r2 = engineApi.newPayload(childPayload).unsafeRunSync()
      r2.status shouldBe Invalid
      r2.validationError.getOrElse("") should include("parent")
      // latestValidHash should propagate from the invalid parent — it should be the genesis hash
      // (the last valid ancestor before the invalid block)
      r2.latestValidHash shouldBe Some(genesisHeader.hash)
    }
  }
}
