package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** When is a gas-used mismatch PROVEN, rather than possibly a missing-contract-code artifact?
  *
  * WHY THIS EXISTS. `InvalidChainReporter.provesConsensusInvalid` refuses to report a bare gas-used mismatch, because a
  * node missing contract code executes a call as a plain transfer and under-counts gas on an honest block. That case
  * was handed to `BlockImporter`, whose gas-used arm only runs when NOTHING in a batch executed. A mismatch found
  * mid-batch was reported by nobody — `ConsensusAdapter` maps a partial failure to `BlockImportedToTop` and drops the
  * error. Measured on hive `engine` 501368ec9: `Invalid Missing Ancestor Syncing ReOrg, GasUsed … Invalid P8`, 2/2
  * failing, with no `import path reported` line in either client log.
  *
  * THE PROOF. Yellow Paper invariant, every fork before Amsterdam and every ETC fork: header gasUsed equals the final
  * receipt's cumulativeGasUsed. If our computed receipts hash to the header's receiptsRoot, they ARE the receipts the
  * header commits to; if their final cumulative gas differs from header.gasUsed, the block contradicts itself and is
  * invalid whatever state we hold.
  *
  * Half the cases are NEGATIVE, and they are the safety property: no proof when the root disagrees (the missing-code
  * case), none at or after Amsterdam (receipts no longer commit to header gasUsed), and none when the header agrees
  * with its receipts and only our executed total differs.
  */
class GasUsedReceiptsProofSpec extends AnyFlatSpec with Matchers:

  // The REAL block validator: it is what decides whether the receipts root matched. The header and signed-tx
  // validators are never consulted after execution.
  private val validators = new StdValidators(
    StdBlockValidator,
    MockValidatorsAlwaysSucceed.blockHeaderValidator,
    MockValidatorsAlwaysSucceed.signedTransactionValidator
  )

  private val baseConfig: BlockchainConfig = Config.blockchains.blockchainConfig
  private val preAmsterdam: BlockchainConfig =
    baseConfig.copy(forkTimestamps = baseConfig.forkTimestamps.copy(amsterdamTimestamp = None))
  private val headerTs = 2000L
  private val amsterdamAtHeader: BlockchainConfig =
    baseConfig.copy(forkTimestamps = baseConfig.forkTimestamps.copy(amsterdamTimestamp = Some(headerTs)))

  private val stateRoot = ByteString(Array.fill(32)(0x11.toByte))

  private def receipt(cumulativeGas: BigInt): Receipt =
    LegacyReceipt.withHashOutcome(ByteString(Array.fill(32)(0x22.toByte)), cumulativeGas, BloomFilter.Empty, Nil)

  /** The receipts-trie root, built exactly as `MptListValidator.isValid` builds it. */
  private def receiptsRootOf(receipts: Seq[Receipt]): ByteString =
    val trie = MerklePatriciaTrie[Int, Receipt](StateStorage.getReadOnlyStorage(EphemDataSource()))(
      MptListValidator.intByteArraySerializable,
      Receipt.byteArraySerializable
    )
    ByteString(receipts.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash)

  private def header(gasUsed: BigInt, receiptsRoot: ByteString): BlockHeader =
    BlockHeader(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(ByteString(new Array[Byte](32))),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(stateRoot),
      transactionsRoot = TrieRoot(ByteString(new Array[Byte](32))),
      receiptsRoot = TrieRoot(receiptsRoot),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(1),
      gasLimit = GasAmount(3000000),
      gasUsed = GasAmount(gasUsed),
      unixTimestamp = Timestamp(headerTs),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostOlympia(BigInt("1000000000"))
    )

  private def validate(h: BlockHeader, receipts: Seq[Receipt], executedGas: BigInt)(using
      BlockchainConfig
  ): Either[BlockExecutionError, BlockExecutionSuccess] =
    StdValidators.validateBlockAfterExecution(
      validators,
      Block(h, BlockBody(Nil, Nil)),
      stateRoot,
      receipts,
      executedGas
    )

  private def reason(result: Either[BlockExecutionError, BlockExecutionSuccess]): String = result match
    case Left(ValidationAfterExecError(r)) => r
    case other                             => fail(s"expected a ValidationAfterExecError, got $other")

  private val Marker = StdValidators.HeaderGasContradictsReceiptsMarker

  "the gas-used arm" should "PROVE a header that contradicts the receipts it commits to" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // hive's GasUsed corruption: only header.gasUsed is altered; receiptsRoot is the honest one.
    val receipts = Seq(receipt(21000), receipt(42000))
    val r = reason(validate(header(99999, receiptsRootOf(receipts)), receipts, 42000)(using preAmsterdam))

    r should include(Marker)
    // BlockImporter's gas-used arm still matches, so ETC import behaviour is byte-for-byte what it was.
    r should startWith("Block has invalid gas used")
    InvalidChainReporter.provesConsensusInvalid(ValidationAfterExecError(r)) shouldBe true
  }

  it should "PROVE it for an empty block — no receipts commit to zero gas" taggedAs (UnitTest, ConsensusTest) in {
    val r = reason(validate(header(5, BlockHeader.EmptyMpt), Nil, 0)(using preAmsterdam))
    r should include(Marker)
    InvalidChainReporter.provesConsensusInvalid(ValidationAfterExecError(r)) shouldBe true
  }

  it should "NOT prove it when the receipts root disagrees — the missing-code case" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Our receipts do not hash to the header's root, so they are not the receipts it commits to. This is what a node
    // missing contract code produces, and it must stay unreported.
    val ours = Seq(receipt(21000))
    val committed = Seq(receipt(99999))
    val r = reason(validate(header(99999, receiptsRootOf(committed)), ours, 21000)(using preAmsterdam))

    (r should not).include(Marker)
    r shouldBe "Block has invalid gas used, expected 99999 but got 21000"
    InvalidChainReporter.provesConsensusInvalid(ValidationAfterExecError(r)) shouldBe false
  }

  it should "NOT prove it at or after Amsterdam — receipts no longer commit to header gasUsed" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Identical inputs to the first case; only the fork differs. EIP-8037/7778: header gasUsed is max(dimensions),
    // receipts carry the sum, so a root match proves nothing.
    val receipts = Seq(receipt(21000), receipt(42000))
    val r = reason(validate(header(99999, receiptsRootOf(receipts)), receipts, 42000)(using amsterdamAtHeader))

    (r should not).include(Marker)
    InvalidChainReporter.provesConsensusInvalid(ValidationAfterExecError(r)) shouldBe false
  }

  it should "NOT prove it when the header AGREES with its receipts and only our executed total differs" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Condition A. The arm is entered because header.gasUsed != the executed total passed in, but the header is
    // consistent with the receipts it commits to, so there is no contradiction to prove. Reading the receipts
    // directly, rather than trusting the executed total, is what keeps this case unreported.
    val receipts = Seq(receipt(42000))
    val r = reason(validate(header(42000, receiptsRootOf(receipts)), receipts, 50000)(using preAmsterdam))

    (r should not).include(Marker)
    InvalidChainReporter.provesConsensusInvalid(ValidationAfterExecError(r)) shouldBe false
  }

  it should "still check gasUsed FIRST and return early" taggedAs (UnitTest, ConsensusTest) in {
    // Both gasUsed and stateRoot wrong: the verdict is the gas one, so the ordering InvalidChainReporter relies on
    // for the state-root arm is intact.
    val receipts = Seq(receipt(42000))
    val h = header(99999, receiptsRootOf(receipts)).copy(stateRoot = TrieRoot(ByteString(Array.fill(32)(0x33.toByte))))
    reason(validate(h, receipts, 42000)(using preAmsterdam)) should startWith("Block has invalid gas used")
  }

  "HeaderGasContradictsReceiptsMarker" should "contain none of the error substrings other consumers match on" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Every `.contains`/`.startsWith` on an error string in src/main, enumerated. If the marker contained any of
    // these, appending it would change some other consumer's behaviour.
    val otherConsumers = Seq(
      "root mismatch",
      "Proof root",
      "Missing proof for empty account range",
      "Peer disconnected",
      "Missing root node",
      "ATTR:",
      "fork-id mismatch",
      InvalidChainReporter.GasUsedMismatchMarker
    )
    otherConsumers.foreach(sub => (Marker should not).include(sub))
  }
