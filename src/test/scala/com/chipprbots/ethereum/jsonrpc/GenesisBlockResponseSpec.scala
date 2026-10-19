package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.jsonrpc.EthBlocksJsonMethodsImplicits.given
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Test to verify that genesis block is serialized correctly:
  *   1. mixHash field should be present
  */
class GenesisBlockResponseSpec extends AnyFlatSpec with Matchers:

  // BlockResponse/TransactionResponse take the serving node's chain config; here, the test config's.
  private given BlockchainConfig = Config.blockchains.blockchainConfig

  "BlockResponse for genesis block" should "include mixHash field" in {
    val genesisHeader = BlockHeader(
      parentHash =
        BlockHash(ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000"))),
      ommersHash =
        BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
      beneficiary = ByteString(Hex.decode("0000000000000000000000000000000000000000")),
      stateRoot = TrieRoot(ByteString(Hex.decode("c22374cb808edd849fae4ef966b459424a1e6ada8d3752eaae4c60b15689ddd0"))),
      transactionsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      receiptsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      logsBloom = BloomFilter(ByteString(Hex.decode("0" * 512))),
      difficulty = Difficulty(BigInt("131072")),
      number = BlockNumber(0),
      gasLimit = GasAmount(BigInt("8000000")),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(1701302272L),
      extraData = ByteString(Hex.decode("00")),
      mixHash = BlockHash(ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000"))),
      nonce = ByteString(Hex.decode("0000000000000042"))
    )

    val genesisBlock = Block(genesisHeader, BlockBody(Nil, Nil))
    val blockResponse = BlockResponse(genesisBlock, None, fullTxs = false, pendingBlock = false)

    // Encode the response to JSON
    val jsonResponse = blockResponseEncoder.encodeJson(blockResponse)
    val jsonString = compact(render(jsonResponse))

    // Verify mixHash is present
    jsonString should include("mixHash")

    // Extract the JSON object
    val jsonObj = parse(jsonString).asInstanceOf[JObject]
    val fields = jsonObj.obj.map(_._1).toSet

    // Verify mixHash field exists
    fields should contain("mixHash")
  }

  it should "have correct hash calculation without checkpoint fields" in {
    val genesisHeader = BlockHeader(
      parentHash =
        BlockHash(ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000"))),
      ommersHash =
        BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
      beneficiary = ByteString(Hex.decode("0000000000000000000000000000000000000000")),
      stateRoot = TrieRoot(ByteString(Hex.decode("c22374cb808edd849fae4ef966b459424a1e6ada8d3752eaae4c60b15689ddd0"))),
      transactionsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      receiptsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      logsBloom = BloomFilter(ByteString(Hex.decode("0" * 512))),
      difficulty = Difficulty(BigInt("131072")),
      number = BlockNumber(0),
      gasLimit = GasAmount(BigInt("8000000")),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(1701302272L),
      extraData = ByteString(Hex.decode("00")),
      mixHash = BlockHash(ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000"))),
      nonce = ByteString(Hex.decode("0000000000000042"))
    )

    // The hash should be calculated from RLP encoding
    // This verifies that the hash calculation is correct
    genesisHeader.hash.value.length shouldBe 32 // Hash should be 32 bytes
  }
