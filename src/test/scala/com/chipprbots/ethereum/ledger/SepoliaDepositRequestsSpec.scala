package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-6110 on Sepolia, from a real block: deposits must be read from the chain's OWN deposit contract.
  *
  * Sepolia block 11,321,359 carries ten `DepositEvent`s from Sepolia's contract `0x7f02c3e3…295d`, and its header's
  * `requestsHash` commits to them. Before this fix execution scanned only the mainnet contract `0x00000000219a…05fa`,
  * found nothing, computed `sha256("")`, and rejected the canonical block as INVALID_REQUESTS.
  *
  * Provenance (`src/test/resources/sepolia/deposit-block-11321359.json`): header fields verbatim from
  * `eth_getBlockByNumber`, receipts 76-78 verbatim from `eth_getBlockReceipts`. The data authenticates itself: the
  * rebuilt header must hash to the canonical block hash, and the logs must reproduce that header's `requestsHash`,
  * which is a SHA-256 commitment. Transaction 77 is the block's only deposit transaction. Besides its ten deposits it
  * emits ten ERC-20 `Transfer`s from the same contract (right address, wrong topic) and twelve logs from a batching
  * contract (wrong address), so both arms of the log filter are exercised.
  */
class SepoliaDepositRequestsSpec extends AnyFlatSpec with Matchers:

  private val SepoliaDepositContract = Address("0x7f02c3e3c98b133055b8b348b2ac625669ed295d")

  /** Shipped chain configs, assembled as at runtime (see `ChainConfigMatrixSpec`). */
  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private lazy val sepolia: BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig("fukuii.blockchains.sepolia"))

  private lazy val fixture: JValue =
    val src = scala.io.Source.fromResource("sepolia/deposit-block-11321359.json")
    try parse(src.mkString)
    finally src.close()

  private def str(v: JValue): String = v.values.toString
  private def bytes(v: JValue): ByteString = ByteString(Hex.decode(str(v).stripPrefix("0x")))
  private def quantity(v: JValue): BigInt = BigInt(str(v).stripPrefix("0x"), 16)

  private lazy val header: BlockHeader =
    val h = fixture \ "header"
    BlockHeader(
      parentHash = BlockHash(bytes(h \ "parentHash")),
      ommersHash = BlockHash(bytes(h \ "sha3Uncles")),
      beneficiary = bytes(h \ "miner"),
      stateRoot = TrieRoot(bytes(h \ "stateRoot")),
      transactionsRoot = TrieRoot(bytes(h \ "transactionsRoot")),
      receiptsRoot = TrieRoot(bytes(h \ "receiptsRoot")),
      logsBloom = BloomFilter(bytes(h \ "logsBloom")),
      difficulty = Difficulty(quantity(h \ "difficulty")),
      number = BlockNumber(quantity(h \ "number")),
      gasLimit = GasAmount(quantity(h \ "gasLimit")),
      gasUsed = GasAmount(quantity(h \ "gasUsed")),
      unixTimestamp = Timestamp(quantity(h \ "timestamp").toLong),
      extraData = bytes(h \ "extraData"),
      mixHash = BlockHash(bytes(h \ "mixHash")),
      nonce = bytes(h \ "nonce"),
      extraFields = HefPostPrague(
        baseFee = quantity(h \ "baseFeePerGas"),
        withdrawalsRoot = bytes(h \ "withdrawalsRoot"),
        blobGasUsed = quantity(h \ "blobGasUsed"),
        excessBlobGas = quantity(h \ "excessBlobGas"),
        parentBeaconBlockRoot = bytes(h \ "parentBeaconBlockRoot"),
        requestsHash = bytes(h \ "requestsHash")
      )
    )

  private lazy val block: Block = Block(header, BlockBody.empty)

  private lazy val receipts: Seq[Receipt] =
    val JArray(items) = fixture \ "receipts": @unchecked
    items.map { r =>
      val JArray(logs) = r \ "logs": @unchecked
      val entries = logs.map { l =>
        val JArray(topics) = l \ "topics": @unchecked
        TxLogEntry(Address(bytes(l \ "address")), topics.map(bytes), bytes(l \ "data"))
      }
      val outcome = if quantity(r \ "status") == 1 then SuccessOutcome else FailureOutcome
      val legacy =
        LegacyReceipt(outcome, quantity(r \ "cumulativeGasUsed"), BloomFilter(bytes(r \ "logsBloom")), entries)
      quantity(r \ "type").toInt match
        case 0 => legacy
        case 1 => Type01Receipt(legacy)
        case 2 => Type02Receipt(legacy)
        case 3 => Type03Receipt(legacy)
        case 4 => Type04Receipt(legacy)
        case t => fail(s"unexpected receipt type $t")
    }

  private trait Setup extends EphemBlockchainTestSetup:
    lazy val exec: BlockExecution = mkBlockExecution()

  "the Sepolia deposit fixture" should "be block 11,321,359 as the chain has it, a post-Prague pre-Amsterdam block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    ("0x" + Hex.toHexString(header.hash.value.toArray)) shouldBe str(fixture \ "header" \ "hash")
    header.number shouldBe BlockNumber(11321359)
    sepolia.isPragueTimestamp(header.unixTimestamp) shouldBe true
    sepolia.isAmsterdamTimestamp(header.unixTimestamp) shouldBe false
  }

  "EIP-6110 deposit collection" should "reproduce the header's requestsHash on the shipped Sepolia config" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    BlockExecution.depositContractFor(sepolia) shouldBe SepoliaDepositContract

    val Right(Some(request)) = exec.collectDepositRequests(receipts)(sepolia): @unchecked
    request.head shouldBe BlockExecution.DepositRequestType.toByte
    request.length shouldBe 1 + 10 * 192

    // The block's own requests: its ten deposits and no EIP-7002 / EIP-7251 output.
    BlockExecution.computeRequestsHash(Seq(request)) shouldBe header.requestsHash.get
    exec.validateRequestsHash(block, Seq(request))(sepolia) shouldBe Right(())

  it should "keep only the contract's DepositEvent logs, in log order" taggedAs (UnitTest, ConsensusTest) in new Setup:
    val Right(Some(request)) = exec.collectDepositRequests(receipts)(sepolia): @unchecked
    // Each 192-byte body ends with the deposit contract's little-endian 8-byte `index`. Ten consecutive indices mean the
    // ten DepositEvents, not the ten Transfers or the batcher's twelve logs, were taken, and in emission order.
    val indices = request.drop(1).grouped(192).map(body => BigInt(1, body.takeRight(8).reverse.toArray)).toSeq
    indices shouldBe (427 to 436).map(BigInt(_))

  it should "reject the canonical block when the chain's contract is not the one scanned (the pre-fix behaviour)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    // Sepolia as shipped before this fix: no `deposit-contract-address`, so the mainnet contract was scanned.
    val undeclared = sepolia.copy(depositContractAddress = None)
    BlockExecution.depositContractFor(undeclared) shouldBe BlockExecution.DepositContractAddress

    exec.collectDepositRequests(receipts)(undeclared) shouldBe Right(None)
    val Left(error) = exec.validateRequestsHash(block, Nil)(undeclared): @unchecked
    error.describe should include("INVALID_REQUESTS")
