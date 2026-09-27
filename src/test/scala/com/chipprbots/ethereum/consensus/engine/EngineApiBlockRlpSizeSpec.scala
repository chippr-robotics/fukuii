package com.chipprbots.ethereum.consensus.engine

import java.security.MessageDigest

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.ledger.QueuePredeploys
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.SignedTransactionEnc
import com.chipprbots.ethereum.rlp.encode as rlpEncode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1439: EIP-7934's MAX_RLP_BLOCK_SIZE on the Engine API path.
  *
  * `newPayload` executes with `alreadyValidated = true`, which skips `StdBlockValidator` — the only place the cap was
  * enforced — so an otherwise valid payload whose RLP exceeds 8,388,608 bytes was executed and answered VALID (EEST
  * `test_block_at_rlp_size_limit_boundary[max_rlp_size_plus_1_byte]` via the Engine API). go-ethereum rejects it in
  * `BlockValidator.ValidateBody` from Osaka on, and not before.
  *
  * One genesis on EEST's `PragueToOsakaAtTime15k` schedule; every payload is a block 1 of plain transactions carrying
  * 1,400,000 zero bytes of calldata each, at exactly their EIP-7623 floor (under EIP-7825's cap), so the block is VALID
  * in every respect but size. Six of them make an 8.4 MB block; five make a 7 MB one.
  */
// scalastyle:off magic.number
class EngineApiBlockRlpSizeSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val ChainId = BigInt(1)
  private val OsakaTs = 15_000L
  private val PragueTs = 12L
  private val CalldataBytes = 1_400_000
  private val Cap = StdBlockValidator.BlockRLPSizeCap

  private val senderKeyPair =
    crypto.keyPairFromPrvKey(BigInt("45a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8", 16))
  private val sender = Address(senderKeyPair)
  private val Recipient = Address(0xbeef)
  private val zero32 = ByteString(new Array[Byte](32))

  /** EIP-7685 requestsHash of a block with no requests: sha256 of nothing. */
  private val NoRequestsHash = ByteString(MessageDigest.getInstance("SHA-256").digest())

  private def fatTransactions(count: Int): Seq[SignedTransaction] =
    (0 until count).map { nonce =>
      val tx = LegacyTransaction(
        nonce = BigInt(nonce),
        gasPrice = GasPrice(1_000_000_000),
        gasLimit = GasAmount(21_000 + 10 * CalldataBytes), // EIP-7623 floor for zero bytes; 14,021,000 < 2^24
        receivingAddress = Some(Recipient),
        value = 0,
        payload = ByteString(new Array[Byte](CalldataBytes))
      )
      SignedTransaction.sign(tx, senderKeyPair, Some(ChainId))
    }

  private def mptRoot[T](items: Seq[T], ser: ByteArraySerializable[T]): ByteString =
    val storage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    val trie = items.zipWithIndex.foldLeft(MerklePatriciaTrie[Int, T](storage)(intByteArraySerializable, ser)) {
      case (t, (item, index)) => t.put(index, item)
    }
    ByteString(trie.getRootHash)

  private class Env extends EphemBlockchainTestSetup:
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
    override lazy val vm: VMImpl = new VMImpl

  /** A real EngineApiService over a genesis that funds the sender and carries the Prague request-queue predeploys (a
    * Prague block without them is invalid).
    */
  private class Chain:
    private val env = new Env
    import env.{blockQueue, blockchain, blockchainReader, blockchainWriter, mining, storagesInstance}

    given config: BlockchainConfig = EestBlockchainReplay
      .configFor(env.blockchainConfig, "PragueToOsakaAtTime15k", ChainId)
      .fold(err => throw new IllegalStateException(err), identity)

    private def hex(address: Address): String = Hex.toHexString(address.bytes.toArray)
    private def predeploy(code: ByteString) =
      GenesisAccount(None, UInt256.Zero, Some(code), Some(UInt256(1)), None)

    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    ).loadGenesisData(
      GenesisData(
        nonce = ByteString(new Array[Byte](8)),
        mixHash = Some(zero32),
        difficulty = "0x0",
        extraData = ByteString.empty,
        gasLimit = "0x5f5e100", // 100,000,000
        coinbase = ByteString(new Array[Byte](20)),
        timestamp = "0x0",
        alloc = Map(
          hex(sender) -> GenesisAccount(None, UInt256(BigInt(10).pow(19)), None, Some(UInt256.Zero), None),
          hex(BlockExecution.WithdrawalQueueAddress) -> predeploy(QueuePredeploys.WithdrawalQueueCode),
          hex(BlockExecution.ConsolidationQueueAddress) -> predeploy(QueuePredeploys.ConsolidationQueueCode)
        ),
        baseFeePerGas = Some("0x7"),
        excessBlobGas = Some("0x0"),
        blobGasUsed = Some("0x0")
      )
    ).get

    val genesis: BlockHeader = blockchainReader.getBlockHeaderByNumber(0).get

    private val blockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      new BlockValidation(mining, blockchainReader, blockQueue)
    )

    private val service = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExecution,
      new ForkChoiceManager(blockchainReader, blockchainWriter),
      None
    )(config, null)

    /** Block 1 at `timestamp` carrying `txs`, with every header field execution determines filled in. */
    def block1(timestamp: Long, txs: Seq[SignedTransaction]): Block =
      val template = BlockHeader(
        parentHash = genesis.hash,
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(ByteString.empty),
        transactionsRoot = TrieRoot(mptRoot(txs, SignedTransaction.byteArraySerializable)),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(1),
        gasLimit = genesis.gasLimit,
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(timestamp),
        extraData = ByteString.empty,
        mixHash = BlockHash(zero32),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostPrague(
          BaseFeeCalculator.calcBaseFee(genesis, config),
          BlockHeader.EmptyMpt,
          BigInt(0),
          BigInt(0),
          zero32,
          NoRequestsHash
        )
      )
      val body = BlockBody(txs, Nil, withdrawals = Some(Nil))
      blockExecution.executeBlockNoValidationWithRequests(Block(template, body)) match
        case Right((receipts, gasUsed, stateRoot, requests, _)) =>
          requests shouldBe empty
          Block(
            template.copy(
              stateRoot = TrieRoot(stateRoot),
              gasUsed = GasAmount(gasUsed),
              receiptsRoot = TrieRoot(mptRoot(receipts, Receipt.byteArraySerializable))
            ),
            body
          )
        case Left(error) => fail(s"block 1 does not execute: ${error.describe}")

    def newPayload(block: Block): PayloadStatusV1 =
      service
        .newPayload(
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
            transactions =
              block.body.transactionList.map(stx => ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))),
            withdrawals = Some(Nil),
            blobGasUsed = Some(BigInt(0)),
            excessBlobGas = Some(BigInt(0)),
            parentBeaconBlockRoot = Some(zero32),
            expectedBlobVersionedHashes = Some(Nil),
            executionRequests = Some(Nil)
          )
        )
        .unsafeRunSync()

  "engine_newPayload at Osaka" should {

    "answer an otherwise valid payload over MAX_RLP_BLOCK_SIZE INVALID, the parent being the latest valid block" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val chain = new Chain
      val oversized = chain.block1(OsakaTs, fatTransactions(6))
      Block.size(oversized) should be > Cap

      val status = chain.newPayload(oversized)

      status.status shouldBe Invalid
      status.validationError.getOrElse("") should include("RLP_BLOCK_LIMIT_EXCEEDED")
      status.latestValidHash shouldBe Some(chain.genesis.hash.value)
    }

    "answer the same shape under the cap VALID" taggedAs (UnitTest, ConsensusTest) in {
      val chain = new Chain
      val underCap = chain.block1(OsakaTs, fatTransactions(5))
      Block.size(underCap) should be < Cap

      val status = chain.newPayload(underCap)

      withClue(s"validationError=${status.validationError}: ") {
        status.status shouldBe Valid
      }
    }
  }

  "engine_newPayload before Osaka (Prague)" should {

    "answer the oversized payload VALID, as go-ethereum and execution-specs do: the cap starts at Osaka" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val chain = new Chain
      val oversized = chain.block1(PragueTs, fatTransactions(6))
      Block.size(oversized) should be > Cap

      val status = chain.newPayload(oversized)

      withClue(s"validationError=${status.validationError}: ") {
        status.status shouldBe Valid
      }
    }
  }
