package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.testing.Tags.*

/** Regression suite for the "optimistic head" consensus defect (ETH/PoS Engine API only).
  *
  * Defect: `engine_forkchoiceUpdated`'s `headOptimistic` branch returned SYNCING (correct) but first called
  * `ForkChoiceManager.applyForkChoiceState` for its BeaconHead publish side effect. Because the head header WAS present
  * by hash (stored via `storeBlockByHashOnly` with no receipts and no number→hash mapping), that method took its
  * head-known branch and ran `promoteBranchToCanonical` + `saveBestKnownBlocks`, writing a canonical number→hash
  * mapping for a block that was never executed. `engine_newPayload`'s dedup branch then read that mapping back as proof
  * of successful execution and answered VALID.
  *
  * Net effect: newPayload(invalid, parent unknown) → ACCEPTED → FCU(head=invalid) → SYNCING + poisoned mapping →
  * newPayload(invalid) → VALID. hive requires INVALID with latestValidHash = the parent's hash
  * (`simulators/ethereum/engine/suites/engine/invalid_payload.go:242-244`, the "Invalid NewPayload, Transaction *,
  * Syncing=True" family — 26 CI failures).
  *
  * The mirror case (`payload_execution.go:421-436`, "Valid NewPayload->ForkchoiceUpdated on Syncing Client") pins the
  * fix from the other side: the same store-by-hash → SYNCING-FCU → re-send sequence on a VALID block must still end in
  * VALID, by genuinely re-executing rather than by reading a stale mapping.
  */
// scalastyle:off magic.number
class EngineApiOptimisticHeadSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  "EngineApiService optimistic head handling" should {

    trait Setup extends EphemBlockchainTestSetup:

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
        "ptm-ignore-optimistic-head-spec"
      )
      implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

      lazy val engineApi = new EngineApiService(
        blockchainReader,
        blockchainWriter,
        blockExec,
        forkChoiceManager,
        Some(pendingTxManager)
      )(blockchainConfig, typedScheduler)

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

      blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil))).commit()
      storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

      /** Build a valid empty post-Shanghai block on top of `parentHeader`, executing it to fill in the correct
        * stateRoot / gasUsed. The parent header MUST already be readable by hash.
        */
      def buildValidBlockOn(parentHeader: BlockHeader): Block =
        val template = BlockHeader(
          parentHash = parentHeader.hash,
          ommersHash = BlockHash(BlockHeader.EmptyOmmers),
          beneficiary = ByteString(new Array[Byte](20)),
          stateRoot = TrieRoot(ByteString.empty), // filled after execution
          transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
          receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
          logsBloom = BloomFilter.Empty,
          difficulty = Difficulty.Zero,
          number = BlockNumber(parentHeader.number.value + 1),
          gasLimit = GasAmount(3000000),
          gasUsed = GasAmount(0),
          unixTimestamp = Timestamp(parentHeader.unixTimestamp.toLong + 1),
          extraData = ByteString("fukuii".getBytes),
          mixHash = BlockHash(ByteString(Array.fill(32)(0x42.toByte))), // prevRandao
          nonce = ByteString(new Array[Byte](8)),
          extraFields = HefPostShanghai(
            baseFee = BigInt("1000000000"),
            withdrawalsRoot = BlockHeader.EmptyMpt
          )
        )
        val candidate = Block(template, BlockBody(Nil, Nil, withdrawals = Some(Nil)))
        blockExec.executeBlockNoValidation(candidate)(blockchainConfig) match
          case Right((_, gasUsed, computedStateRoot)) =>
            Block(
              template.copy(stateRoot = TrieRoot(computedStateRoot), gasUsed = GasAmount(gasUsed)),
              candidate.body
            )
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

      /** Reproduce hive's "withheld parent" setup.
        *
        * Returns (block1, block2) where block1 is a valid child of genesis and block2 is a valid child of block1, and
        * NEITHER is present in storage afterwards. block1 must be transiently readable by hash to execute block2, so it
        * is stored by hash only and then removed again — `storeBlockByHashOnly` writes no number→hash mapping and no
        * receipts, so removing the header/body leaves no trace.
        */
      def buildWithheldParentAndChild(): (Block, Block) =
        val block1 = buildValidBlockOn(genesisHeader)
        blockchainWriter.storeBlockByHashOnly(block1).commit()
        val block2 = buildValidBlockOn(block1.header)
        blockchainWriter.removeBlockByHash(block1.header.hash).commit()
        // Precondition: block1 is unknown to us, exactly as if the CL had withheld it.
        blockchainReader.getBlockHeaderByHash(block1.header.hash) shouldBe None
        (block1, block2)

      def syncingForkChoice(head: ByteString): ForkChoiceState =
        ForkChoiceState(
          headBlockHash = head,
          safeBlockHash = head,
          finalizedBlockHash = head
        )

      /** Corrupt the stateRoot and re-derive the block hash. The corruption is invisible without executing against the
        * parent's post-state — the same property hive's six failing tx fields have (tests.go:198,
        * InvalidDetectedOnSync=false).
        */
      def withBadStateRoot(block: Block): Block =
        Block(
          block.header.copy(stateRoot = TrieRoot(ByteString(kec256(Array[Byte](0xde.toByte, 0xad.toByte))))),
          block.body
        )

    /** THE DEFECT. Mirrors hive invalid_payload.go:104-245 for the six "Syncing=True" tx fields. */
    "not return VALID for an unexecuted block after a SYNCING forkchoiceUpdated named it as head" taggedAs UnitTest in
      new Setup:
        val (block1, block2) = buildWithheldParentAndChild()
        val badBlock2: Block = withBadStateRoot(block2)
        val badPayload: ExecutionPayload = blockToPayload(badBlock2)

        // hive CALL 2 — newPayload(invalid) with the parent withheld. Must NOT be INVALID here:
        // the defect is only visible by executing against block1's post-state, which we lack.
        val accepted: PayloadStatusV1 = engineApi.newPayload(badPayload).unsafeRunSync()
        accepted.status shouldBe Accepted
        accepted.latestValidHash shouldBe None
        // Stored by hash only: header readable, no canonical number→hash mapping, no receipts.
        blockchainReader.getBlockHeaderByHash(BlockHash(badPayload.blockHash)) shouldBe defined
        blockchainReader.getBlockHeaderByNumber(2) shouldBe None
        blockchainReader.getReceiptsByHash(BlockHash(badPayload.blockHash)) shouldBe None

        // hive CALL 3 — FCU(head=safe=finalized=invalid). Strictly SYNCING, and it must leave
        // NO canonical mapping behind. This is the assertion that fails on unmodified code.
        val fcuResponse: Either[String, ForkchoiceUpdatedResponse] =
          engineApi.forkchoiceUpdated(syncingForkChoice(badPayload.blockHash), payloadAttributes = None).unsafeRunSync()
        fcuResponse.isRight shouldBe true
        fcuResponse.toOption.get.payloadStatus.status shouldBe Syncing
        withClue("SYNCING forkchoiceUpdated must not write a canonical number→hash mapping for an unexecuted head: ") {
          blockchainReader.getBlockHeaderByNumber(2) shouldBe None
        }

        // hive CALL 6 — a repeated FCU on the same state must still be SYNCING, never VALID.
        // Pre-fix the first FCU falsified its own `headOptimistic` predicate by writing the mapping.
        val fcuAgain: Either[String, ForkchoiceUpdatedResponse] =
          engineApi.forkchoiceUpdated(syncingForkChoice(badPayload.blockHash), payloadAttributes = None).unsafeRunSync()
        fcuAgain.toOption.map(_.payloadStatus.status) shouldBe Some(Syncing)

        // hive CALL 4 — the withheld parent finally arrives and is fully executed.
        val parentStatus: PayloadStatusV1 = engineApi.newPayload(blockToPayload(block1)).unsafeRunSync()
        parentStatus.status shouldBe Valid
        parentStatus.latestValidHash shouldBe Some(block1.header.hash)

        // hive CALL 5 — the SAME invalid payload re-sent. Now that the parent is executed we can
        // see the corruption, so the answer must be INVALID with latestValidHash = parent hash.
        val rejected: PayloadStatusV1 = engineApi.newPayload(badPayload).unsafeRunSync()
        rejected.status should not be Valid
        rejected.status shouldBe Invalid
        rejected.latestValidHash shouldBe Some(block1.header.hash)

        // hive CALL 7 — the invalid block must not be exposed to the eth namespace.
        blockchainReader.getBlockHeaderByNumber(2).map(_.hash) should not be Some(badBlock2.header.hash)

    /** THE MIRROR. hive payload_execution.go:390-436, "Valid NewPayload->ForkchoiceUpdated on Syncing Client". Guards
      * against over-correcting: removing the dedup shortcut must let the re-send genuinely re-execute.
      */
    "re-execute and return VALID for a valid block re-sent after its withheld parent arrives" taggedAs UnitTest in
      new Setup:
        val (block1, block2) = buildWithheldParentAndChild()
        val childPayload: ExecutionPayload = blockToPayload(block2)

        val accepted: PayloadStatusV1 = engineApi.newPayload(childPayload).unsafeRunSync()
        accepted.status shouldBe Accepted
        accepted.latestValidHash shouldBe None

        val fcuResponse: Either[String, ForkchoiceUpdatedResponse] =
          engineApi
            .forkchoiceUpdated(syncingForkChoice(childPayload.blockHash), payloadAttributes = None)
            .unsafeRunSync()
        fcuResponse.toOption.map(_.payloadStatus.status) shouldBe Some(Syncing)
        blockchainReader.getBlockHeaderByNumber(2) shouldBe None

        engineApi.newPayload(blockToPayload(block1)).unsafeRunSync().status shouldBe Valid

        val revalidated: PayloadStatusV1 = engineApi.newPayload(childPayload).unsafeRunSync()
        revalidated.status shouldBe Valid
        revalidated.latestValidHash shouldBe Some(block2.header.hash)
        // Genuinely executed this time: receipts present and canonical by number.
        blockchainReader.getReceiptsByHash(block2.header.hash) shouldBe defined
        blockchainReader.getBlockHeaderByNumber(2).map(_.hash) shouldBe Some(block2.header.hash)

        // And the CL can now finalize it.
        val finalFcu: Either[String, ForkchoiceUpdatedResponse] =
          engineApi
            .forkchoiceUpdated(syncingForkChoice(childPayload.blockHash), payloadAttributes = None)
            .unsafeRunSync()
        finalFcu.toOption.map(_.payloadStatus.status) shouldBe Some(Valid)

    /** Unit-level pin on the split: the notify-only entry point must publish BeaconHead and write nothing. */
    "publish BeaconHead without promoting the branch when notifyBeaconHead is used" taggedAs UnitTest in new Setup:
      import org.apache.pekko.testkit.TestProbe

      val (_, block2) = buildWithheldParentAndChild()
      blockchainWriter.storeBlockByHashOnly(block2).commit()

      val probe: TestProbe = TestProbe()(classicSystem)
      forkChoiceManager.setListener(probe.ref)

      forkChoiceManager.notifyBeaconHead(syncingForkChoice(block2.header.hash.value))

      val beacon: ForkChoiceManager.BeaconHead = probe.expectMsgType[ForkChoiceManager.BeaconHead]
      beacon.headHash shouldBe block2.header.hash.value
      // knownHeader must stay Some — SNAPSyncController gates lagging-peer eviction on it.
      beacon.knownHeader.map(_.hash) shouldBe Some(block2.header.hash)
      // ...but nothing canonical may be written.
      blockchainReader.getBlockHeaderByNumber(2) shouldBe None
      forkChoiceManager.getState shouldBe None
  }
