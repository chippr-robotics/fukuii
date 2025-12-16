package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Test to verify that genesis block hash is calculated correctly to match Geth/Core-Geth.
  * 
  * This test uses the exact values from the gorgoroth genesis file to ensure the hash
  * calculation produces the expected result.
  */
class GenesisHashCalculationSpec extends AnyFlatSpec with Matchers {

  "Genesis block hash calculation" should "match Geth hash for gorgoroth genesis" in {
    // Values from gorgoroth-genesis.json
    val genesisHeader = BlockHeader(
      parentHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
      beneficiary = ByteString(Hex.decode("0000000000000000000000000000000000000000")),
      stateRoot = ByteString(Hex.decode("c22374cb808edd849fae4ef966b459424a1e6ada8d3752eaae4c60b15689ddd0")),
      transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      logsBloom = ByteString(Hex.decode("0" * 512)),
      difficulty = BigInt("131072"),  // 0x20000
      number = 0,
      gasLimit = BigInt("8000000"),  // 0x7a1200
      gasUsed = 0,
      unixTimestamp = 1733402624L,  // 0x6751a000
      extraData = ByteString(Hex.decode("00")),
      mixHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      nonce = ByteString(Hex.decode("0000000000000042"))
      // extraFields defaults to HefEmpty
    )
    
    // Expected hash from Geth
    val expectedGethHash = "770bf91ef34b80474db89336b4be6df254f7f613ca1c317329290b46fe439190"
    
    // Calculate actual hash
    val actualHash = Hex.toHexString(genesisHeader.hash.toArray)
    
    // Verify extraFields is HefEmpty
    genesisHeader.extraFields shouldBe BlockHeader.HeaderExtraFields.HefEmpty
    
    // Verify checkpoint is None
    genesisHeader.checkpoint shouldBe None
    
    // Print for debugging
    println(s"Expected Geth hash: $expectedGethHash")
    println(s"Actual Fukuii hash: $actualHash")
    println(s"Extra fields: ${genesisHeader.extraFields}")
    println(s"Has checkpoint: ${genesisHeader.hasCheckpoint}")
    
    // Debug RLP encoding
    import com.chipprbots.ethereum.rlp.{encode => rlpEncode}
    import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
    val rlpBytes = rlpEncode(genesisHeader.toRLPEncodable)
    println(s"RLP size: ${rlpBytes.length} bytes")
    println(s"RLP hex (first 100 bytes): ${Hex.toHexString(rlpBytes.take(100))}")
    println(s"RLP hex (full): ${Hex.toHexString(rlpBytes)}")
    
    // The hash should match Geth
    actualHash shouldBe expectedGethHash
  }
  
  it should "produce wrong hash when extraFields is incorrectly set to HefPostEcip1097" in {
    // This test checks if maybe the genesis block was stored with wrong extraFields
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
      unixTimestamp = 1733402624L,
      extraData = ByteString(Hex.decode("00")),
      mixHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      nonce = ByteString(Hex.decode("0000000000000042")),
      extraFields = BlockHeader.HeaderExtraFields.HefPostEcip1097(None)  // WRONG! Should be HefEmpty
    )
    
    val actualHash = Hex.toHexString(genesisHeader.hash.toArray)
    val wrongFukuiiHash = "039853933811aac8bd0fe9ed298ec7c6ea8fa412190d5b1148b6084ab22bab29"
    
    println(s"Hash with HefPostEcip1097(None): $actualHash")
    println(s"Fukuii's wrong hash: $wrongFukuiiHash")
    
    // If this matches, it means the genesis block was stored with wrong extraFields!
    if (actualHash == wrongFukuiiHash) {
      fail("FOUND THE BUG! Genesis block was stored with HefPostEcip1097(None) instead of HefEmpty!")
    }
  }
}
