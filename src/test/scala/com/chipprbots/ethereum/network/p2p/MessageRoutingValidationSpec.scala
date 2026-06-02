package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.testing.Tags._

/** Validates that ETH68 and ETH69 messages are correctly routed to their decoders.
  * ETH62-67 decoder routing removed: Fukuii only negotiates ETH68 and ETH69.
  */
class MessageRoutingValidationSpec extends AnyFlatSpec with Matchers {

  val exampleHash: ByteString = ByteString(
    Hex.decode("fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef6")
  )

  val exampleForkId: ForkId = ForkId(
    hash = BigInt(1, Hex.decode("be46d57c")),
    next = Some(BigInt("1000000000000000000"))
  )

  "MessageDecoders" should "route ETH68 Status messages to ETH68MessageDecoder" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    import ETHPackets.Status68.Status68._
    val status = ETHPackets.Status68.Status68(
      protocolVersion = 68,
      networkId = 1,
      totalDifficulty = BigInt("1000000000000000000"),
      bestHash = exampleHash,
      genesisHash = exampleHash,
      forkId = exampleForkId
    )

    val statusBytes = status.toBytes
    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68)
    val decoded = decoder.fromBytes(Codes.StatusCode, statusBytes)

    decoded shouldBe a[Right[?, ?]]
    decoded.toOption.get shouldBe a[ETHPackets.Status68.Status68]

    val decodedStatus = decoded.toOption.get.asInstanceOf[ETHPackets.Status68.Status68]
    decodedStatus.protocolVersion shouldBe 68
    decodedStatus.networkId shouldBe 1
    decodedStatus.forkId shouldBe exampleForkId
  }

  it should "route ETH69 Status messages to ETH69MessageDecoder" taggedAs (UnitTest, NetworkTest) in {
    import ETHPackets.Status69.Status69._
    val status = ETHPackets.Status69.Status69(
      protocolVersion = 69,
      networkId = 1,
      genesisHash = exampleHash,
      forkId = exampleForkId,
      earliestBlock = BigInt(0),
      latestBlock = BigInt(24000000),
      latestBlockHash = exampleHash
    )

    val statusBytes = status.toBytes
    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH69)
    val decoded = decoder.fromBytes(Codes.StatusCode, statusBytes)

    decoded shouldBe a[Right[?, ?]]
    decoded.toOption.get shouldBe a[ETHPackets.Status69.Status69]

    val decodedStatus = decoded.toOption.get.asInstanceOf[ETHPackets.Status69.Status69]
    decodedStatus.protocolVersion shouldBe 69
    decodedStatus.networkId shouldBe 1
    decodedStatus.latestBlock shouldBe BigInt(24000000)
  }

  it should "reject messages with invalid protocol version encoding" taggedAs (UnitTest, NetworkTest) in {
    val malformedBytes = Hex.decode("c0") // Empty RLP list

    val decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68)
    val result = decoder.fromBytes(Codes.StatusCode, malformedBytes)

    result shouldBe a[Left[?, ?]]
    result.swap.toOption.get shouldBe a[MessageDecoder.MalformedMessageError]
  }

  it should "handle GetNodeData and NodeData correctly based on protocol version" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val emptyPayload = Array.empty[Byte]

    // ETH68 should reject GetNodeData (EIP-4938)
    val eth68Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68)
    val eth68Result = eth68Decoder.fromBytes(Codes.GetNodeDataCode, emptyPayload)
    eth68Result shouldBe a[Left[?, ?]]
    eth68Result.swap.toOption.get shouldBe a[MessageDecoder.MalformedMessageError]
    eth68Result.swap.toOption.get
      .asInstanceOf[MessageDecoder.MalformedMessageError]
      .message should include("not supported in eth/68")

    // ETH69 should also reject GetNodeData (EIP-4938)
    val eth69Decoder = EthereumMessageDecoder.ethMessageDecoder(Capability.ETH69)
    val eth69Result = eth69Decoder.fromBytes(Codes.GetNodeDataCode, emptyPayload)
    eth69Result shouldBe a[Left[?, ?]]
    eth69Result.swap.toOption.get
      .asInstanceOf[MessageDecoder.MalformedMessageError]
      .message should include("not supported in eth/69")
  }
}
