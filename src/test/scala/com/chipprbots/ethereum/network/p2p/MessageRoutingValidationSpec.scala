package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.testing.Tags._

/** Validation test suite to ensure messages are correctly routed to ETH64+ decoders instead of being incorrectly routed
  * to legacy ETC64 or ETH63 decoders.
  *
  * This addresses the issue where messages were being routed to etc63 vs eth64 in the codebase.
  */
class MessageRoutingValidationSpec extends AnyFlatSpec with Matchers {

  val exampleHash: ByteString = ByteString(
    Hex.decode("fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef6")
  )

  val exampleForkId: ForkId = ForkId(
    hash = BigInt(1, Hex.decode("12345678")),
    next = None
  )

  "MessageDecoders" should "route ETH64 Status messages to ETH64MessageDecoder" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val status = ETH64.Status(
      protocolVersion = 64,
      networkId = 1,
      totalDifficulty = BigInt("1000000000000000000"),
      bestHash = exampleHash,
      genesisHash = exampleHash,
      forkId = exampleForkId
    )

    val statusBytes = status.toBytes
    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH64)
    val decoded = decoder.fromBytes(Codes.StatusCode, statusBytes)

    decoded shouldBe a[Right[?, ?]]
    // Using .get here is safe because the assertion above ensures we have a Right
    decoded.toOption.get shouldBe a[ETH64.Status]

    val decodedStatus = decoded.toOption.get.asInstanceOf[ETH64.Status]
    decodedStatus.protocolVersion shouldBe 64
    decodedStatus.networkId shouldBe 1
    decodedStatus.forkId shouldBe exampleForkId
  }

  it should "route ETH63 Status messages to ETH63MessageDecoder" taggedAs (UnitTest, NetworkTest) in {
    val status = BaseETH6XMessages.Status(
      protocolVersion = 63,
      networkId = 1,
      totalDifficulty = BigInt("1000000000000000000"),
      bestHash = exampleHash,
      genesisHash = exampleHash
    )

    val statusBytes = status.toBytes
    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH63)
    val decoded = decoder.fromBytes(Codes.StatusCode, statusBytes)

    decoded shouldBe a[Right[?, ?]]
    decoded.toOption.get shouldBe a[BaseETH6XMessages.Status]

    val decodedStatus = decoded.toOption.get.asInstanceOf[BaseETH6XMessages.Status]
    decodedStatus.protocolVersion shouldBe 63
    decodedStatus.networkId shouldBe 1
  }

  it should "route ETH65 Status messages to ETH65MessageDecoder" taggedAs (UnitTest, NetworkTest) in {
    val status = ETH64.Status(
      protocolVersion = 65,
      networkId = 1,
      totalDifficulty = BigInt("1000000000000000000"),
      bestHash = exampleHash,
      genesisHash = exampleHash,
      forkId = exampleForkId
    )

    val statusBytes = status.toBytes
    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH65)
    val decoded = decoder.fromBytes(Codes.StatusCode, statusBytes)

    decoded shouldBe a[Right[?, ?]]
    decoded.toOption.get shouldBe a[ETH64.Status]

    val decodedStatus = decoded.toOption.get.asInstanceOf[ETH64.Status]
    decodedStatus.protocolVersion shouldBe 65
  }

  it should "route ETH66 Status messages to ETH66MessageDecoder" taggedAs (UnitTest, NetworkTest) in {
    val status = ETH64.Status(
      protocolVersion = 66,
      networkId = 1,
      totalDifficulty = BigInt("1000000000000000000"),
      bestHash = exampleHash,
      genesisHash = exampleHash,
      forkId = exampleForkId
    )

    val statusBytes = status.toBytes
    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH66)
    val decoded = decoder.fromBytes(Codes.StatusCode, statusBytes)

    decoded shouldBe a[Right[?, ?]]
    decoded.toOption.get shouldBe a[ETH64.Status]

    val decodedStatus = decoded.toOption.get.asInstanceOf[ETH64.Status]
    decodedStatus.protocolVersion shouldBe 66
  }

  it should "ensure ETH64+ uses ForkId while ETH63 does not" taggedAs (UnitTest, NetworkTest) in {
    // ETH64 Status includes ForkId
    val eth64Status = ETH64.Status(
      protocolVersion = 64,
      networkId = 1,
      totalDifficulty = BigInt("1000000000000000000"),
      bestHash = exampleHash,
      genesisHash = exampleHash,
      forkId = exampleForkId
    )

    eth64Status.forkId shouldBe exampleForkId

    // ETH63 Status does not include ForkId
    val eth63Status = BaseETH6XMessages.Status(
      protocolVersion = 63,
      networkId = 1,
      totalDifficulty = BigInt("1000000000000000000"),
      bestHash = exampleHash,
      genesisHash = exampleHash
    )

    // ETH63 Status should not have a forkId field
    val eth63StatusClass = eth63Status.getClass
    eth63StatusClass.getDeclaredFields.exists(_.getName.contains("forkId")) shouldBe false
  }

  it should "correctly route NewBlock messages for different protocol versions" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // NewBlock is shared across ETH63 and ETH64+ via BaseETH6XMessages
    // Use a test block from Fixtures
    val newBlock = BaseETH6XMessages.NewBlock(
      Fixtures.Blocks.Block3125369.block,
      totalDifficulty = BigInt("1000000000000000000")
    )

    val newBlockBytes = newBlock.toBytes

    // Should decode correctly for ETH63
    val eth63Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH63)
    val eth63Decoded = eth63Decoder.fromBytes(Codes.NewBlockCode, newBlockBytes)
    eth63Decoded shouldBe a[Right[?, ?]]

    // Should decode correctly for ETH64
    val eth64Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH64)
    val eth64Decoded = eth64Decoder.fromBytes(Codes.NewBlockCode, newBlockBytes)
    eth64Decoded shouldBe a[Right[?, ?]]

    // Should decode correctly for ETH66
    val eth66Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH66)
    val eth66Decoded = eth66Decoder.fromBytes(Codes.NewBlockCode, newBlockBytes)
    eth66Decoded shouldBe a[Right[?, ?]]
  }

  it should "validate that ETC64 specific decoders are no longer used" taggedAs (UnitTest, NetworkTest) in {
    // Ensure that we don't have ETC64 capability in the negotiation
    val eth64Selected = Capability.negotiate(
      List(Capability.ETH64, Capability.ETH63),
      List(Capability.ETH64, Capability.ETH65)
    )
    
    eth64Selected shouldBe Some(Capability.ETH64)

    // Ensure decoder routing goes through ETH64MessageDecoder for ETH64
    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH64)
    decoder shouldBe ETH64MessageDecoder
  }

  it should "ensure consistent message code handling across protocol versions" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Verify that common message codes are handled consistently
    val commonCodes = List(
      Codes.StatusCode,
      Codes.NewBlockHashesCode,
      Codes.GetBlockHeadersCode,
      Codes.BlockHeadersCode,
      Codes.GetBlockBodiesCode,
      Codes.BlockBodiesCode,
      Codes.NewBlockCode
    )

    val eth63Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH63)
    val eth64Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH64)
    val eth66Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH66)

    // All decoders should recognize these codes (even if decoding fails due to invalid payload)
    commonCodes.foreach { code =>
      val emptyPayload = Array.empty[Byte]

      // Should return Either (not throw exception) for known codes
      eth63Decoder.fromBytes(code, emptyPayload) shouldBe a[Left[?, ?]]
      eth64Decoder.fromBytes(code, emptyPayload) shouldBe a[Left[?, ?]]
      eth66Decoder.fromBytes(code, emptyPayload) shouldBe a[Left[?, ?]]
    }
  }

  it should "reject messages with invalid protocol version encoding" taggedAs (UnitTest, NetworkTest) in {
    // Create a malformed Status message with invalid RLP encoding
    val malformedBytes = Hex.decode("c0") // Empty RLP list

    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH64)
    val result = decoder.fromBytes(Codes.StatusCode, malformedBytes)

    result shouldBe a[Left[?, ?]]
    result.swap.toOption.get shouldBe a[MessageDecoder.MalformedMessageError]
  }

  it should "handle GetNodeData and NodeData correctly based on protocol version" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // GetNodeData/NodeData are supported in ETH63-67 but removed in ETH68
    val emptyPayload = Array.empty[Byte]

    // ETH64 should support GetNodeData
    val eth64Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH64)
    val eth64Result = eth64Decoder.fromBytes(Codes.GetNodeDataCode, emptyPayload)
    eth64Result shouldBe a[Left[?, ?]] // Will fail on empty payload but code is recognized
    eth64Result.swap.toOption.get shouldBe a[MessageDecoder.MalformedMessageError]

    // ETH68 should reject GetNodeData
    val eth68Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68)
    val eth68Result = eth68Decoder.fromBytes(Codes.GetNodeDataCode, emptyPayload)
    eth68Result shouldBe a[Left[?, ?]]
    eth68Result.swap.toOption.get shouldBe a[MessageDecoder.MalformedMessageError]
    eth68Result.swap.toOption.get
      .asInstanceOf[MessageDecoder.MalformedMessageError]
      .message should include("not supported in eth/68")
  }
}
