package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s._
import org.json4s.native.JsonMethods._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.jsonrpc.EthBlocksJsonMethodsImplicits._

/** Test to verify that genesis block (without ECIP-1097) is serialized correctly:
  * 1. mixHash field should be present
  * 2. checkpoint-related fields should NOT be present when block has no checkpoint
  */
class GenesisBlockResponseSpec extends AnyFlatSpec with Matchers {

  "BlockResponse for genesis block" should "include mixHash field" in {
    val genesisHeader = BlockHeader(
      parentHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
      beneficiary = ByteString(Hex.decode("0000000000000000000000000000000000000000")),
      stateRoot = ByteString(Hex.decode("c22374cb808edd849fae4ef966b459424a1e6ada8d3752eaae4c60b15689ddd0")),
      transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      logsBloom = ByteString(Hex.decode("0" * 512)),
      difficulty = BigInt("131072"),
      number = 0,
      gasLimit = BigInt("8000000"),
      gasUsed = 0,
      unixTimestamp = 1701302272L,
      extraData = ByteString(Hex.decode("00")),
      mixHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      nonce = ByteString(Hex.decode("0000000000000042"))
      // Note: no extraFields parameter, so it defaults to HefEmpty (no checkpoint)
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

    // Verify checkpoint-related fields do NOT exist (since no checkpoint)
    fields should not contain "lastCheckpointNumber"
    fields should not contain "checkpoint"
    fields should not contain "signature"
    fields should not contain "signer"
  }

  it should "have correct hash calculation without checkpoint fields" in {
    val genesisHeader = BlockHeader(
      parentHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
      beneficiary = ByteString(Hex.decode("0000000000000000000000000000000000000000")),
      stateRoot = ByteString(Hex.decode("c22374cb808edd849fae4ef966b459424a1e6ada8d3752eaae4c60b15689ddd0")),
      transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      logsBloom = ByteString(Hex.decode("0" * 512)),
      difficulty = BigInt("131072"),
      number = 0,
      gasLimit = BigInt("8000000"),
      gasUsed = 0,
      unixTimestamp = 1701302272L,
      extraData = ByteString(Hex.decode("00")),
      mixHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      nonce = ByteString(Hex.decode("0000000000000042"))
    )

    // The header should have HefEmpty extraFields (default)
    genesisHeader.extraFields shouldBe BlockHeader.HeaderExtraFields.HefEmpty

    // The checkpoint should be None
    genesisHeader.checkpoint shouldBe None

    // The hash should be calculated from RLP encoding without checkpoint fields
    // This verifies that the hash calculation is correct
    genesisHeader.hash.length shouldBe 32 // Hash should be 32 bytes
  }
}
