package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.SimulateBlockResult
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.EthSimulateResponse
import com.chipprbots.ethereum.jsonrpc.EthSimulateJsonMethodsImplicits.given
import com.chipprbots.ethereum.jsonrpc.EthBlocksJsonMethodsImplicits.given
import com.chipprbots.ethereum.jsonrpc.EthTxJsonMethodsImplicits.given
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Regression coverage for uint64 block timestamps at or above 2^63 (bit 63 set).
  *
  * `domain.Timestamp` is an opaque `Long` holding the uint64 BIT PATTERN — see `domain/Timestamp.scala`. Any
  * RPC/GraphQL/MCP response builder that widens it with a signed `BigInt(x.unixTimestamp.toLong)` sign-extends the
  * bit-63 case into a negative `BigInt`, which then renders as e.g. `"0x-2"` through `encodeAsHex(BigInt)`
  * (`input.toString(16)`). The fix is `x.unixTimestamp.toUnsignedBigInt`, which widens the bit pattern as an unsigned
  * uint64 value.
  *
  * `2^64 - 2` (`Timestamp(-2L)`, the bit pattern `0xfffffffffffffffe`) is hive's
  * `eip4788_beacon_root::test_beacon_root_contract_timestamps[...timestamp_18446744073709551614...]` fixture value —
  * the measured failure this spec guards against.
  */
class TimestampEncodingSpec extends AnyFlatSpec with Matchers:

  given blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  // 2^64 - 2, the bit pattern 0xfffffffffffffffe — reads as -2 through a signed Long/BigInt widening.
  private val ExtremeTimestamp: Timestamp = Timestamp(-2L)
  private val ExtremeTimestampUnsignedDecimal = "18446744073709551614"
  private val ExtremeTimestampHex = "0xfffffffffffffffe"

  // A normal, real-world block timestamp — far below 2^63, so signed and unsigned widening agree.
  private val NormalTimestamp: Timestamp = Timestamp(1701302272L)
  private val NormalTimestampHex = "0x" + BigInt(1701302272L).toString(16)

  private def header(ts: Timestamp): BlockHeader = BlockHeader(
    parentHash = BlockHash(ByteString(Hex.decode("00" * 32))),
    ommersHash = BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
    beneficiary = ByteString(Hex.decode("00" * 20)),
    stateRoot = TrieRoot(ByteString(Hex.decode("c22374cb808edd849fae4ef966b459424a1e6ada8d3752eaae4c60b15689ddd0"))),
    transactionsRoot =
      TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    receiptsRoot = TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    logsBloom = BloomFilter(ByteString(Hex.decode("0" * 512))),
    difficulty = Difficulty(BigInt("131072")),
    number = BlockNumber(1),
    gasLimit = GasAmount(BigInt("8000000")),
    gasUsed = GasAmount.Zero,
    unixTimestamp = ts,
    extraData = ByteString(Hex.decode("00")),
    mixHash = BlockHash(ByteString(Hex.decode("00" * 32))),
    nonce = ByteString(Hex.decode("0000000000000042"))
  )

  private def signedTx: SignedTransaction = SignedTransaction(
    tx = LegacyTransaction(
      nonce = 0,
      gasPrice = GasPrice(123),
      gasLimit = GasAmount(123),
      receivingAddress = Address("0x1234"),
      value = 0,
      payload = ByteString()
    ),
    signature = ECDSASignature(0, 0, 27)
  )

  private def timestampField(json: JValue): String =
    (json \ "timestamp").asInstanceOf[JString].s

  // ── BlockResponse (eth_getBlockByNumber / eth_getBlockByHash) ───────────────────────────────

  "BlockResponse" should "encode a uint64 timestamp >= 2^63 as its unsigned hex value" in {
    val block = Block(header(ExtremeTimestamp), BlockBody(Nil, Nil))
    val response = BlockResponse(block, None, fullTxs = false, pendingBlock = false)
    response.timestamp shouldBe BigInt(ExtremeTimestampUnsignedDecimal)

    val json = blockResponseEncoder.encodeJson(response)
    timestampField(json) shouldBe ExtremeTimestampHex
  }

  it should "leave a normal (< 2^63) timestamp unchanged" in {
    val block = Block(header(NormalTimestamp), BlockBody(Nil, Nil))
    val response = BlockResponse(block, None, fullTxs = false, pendingBlock = false)
    response.timestamp shouldBe BigInt(1701302272L)

    val json = blockResponseEncoder.encodeJson(response)
    timestampField(json) shouldBe NormalTimestampHex
  }

  // ── TransactionResponse (eth_getTransactionByHash blockTimestamp) ───────────────────────────

  "TransactionResponse" should "encode blockTimestamp >= 2^63 as its unsigned hex value" in {
    val response = TransactionResponse(signedTx, Some(header(ExtremeTimestamp)), Some(0))
    response.blockTimestamp shouldBe Some(BigInt(ExtremeTimestampUnsignedDecimal))

    val json = transactionResponseJsonEncoder.encodeJson(response)
    (json \ "blockTimestamp").asInstanceOf[JString].s shouldBe ExtremeTimestampHex
  }

  it should "leave a normal blockTimestamp unchanged" in {
    val response = TransactionResponse(signedTx, Some(header(NormalTimestamp)), Some(0))
    response.blockTimestamp shouldBe Some(BigInt(1701302272L))
  }

  // ── TransactionReceiptResponse (eth_getTransactionReceipt blockTimestamp) ───────────────────

  "TransactionReceiptResponse" should "encode blockTimestamp >= 2^63 as its unsigned hex value" in {
    val stx = signedTx
    val receipt = LegacyReceipt.withHashOutcome(
      postTransactionStateHash = ByteString(),
      cumulativeGasUsed = 0,
      logsBloomFilter = BloomFilter(ByteString(Hex.decode("0" * 512))),
      logs = Nil
    )
    val response = TransactionReceiptResponse(
      receipt = receipt,
      stx = stx,
      signedTransactionSender = Address("0x1234"),
      transactionIndex = 0,
      blockHeader = header(ExtremeTimestamp),
      gasUsedByTransaction = 0,
      baseLogIndex = 0
    )
    response.blockTimestamp shouldBe Some(BigInt(ExtremeTimestampUnsignedDecimal))

    val json = transactionReceiptResponseJsonEncoder.encodeJson(response)
    (json \ "blockTimestamp").asInstanceOf[JString].s shouldBe ExtremeTimestampHex
  }

  // ── eth_simulateV1 block/tx timestamp encoding ───────────────────────────────────────────────

  "eth_simulateV1 response encoder" should "encode a simulated block's timestamp >= 2^63 as its unsigned hex value" in {
    val result = SimulateBlockResult(
      header = header(ExtremeTimestamp),
      body = BlockBody(Nil, Nil),
      transactions = Nil,
      senders = Nil,
      calls = Nil,
      receipts = Nil
    )
    val json = eth_simulateV1.encodeJson(EthSimulateResponse(Seq(result)))
    val blockJson = json.asInstanceOf[JArray].arr.head
    timestampField(blockJson) shouldBe ExtremeTimestampHex
  }
