package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._

/** Verifies that EthereumMessageDecoder dispatches to ETH68/ETH69 decoders.
  *
  * ETH61-67 decoders were removed when Fukuii retired pre-ETH68 capability negotiation.
  * This spec replaces the old per-version decoder tests with ETH68/ETH69-only coverage.
  */
class MessageDecodersSpec extends AnyFlatSpec with Matchers with SecureRandomBuilder {

  def decode: Capability => MessageDecoder = EthereumMessageDecoder.ethMessageDecoder _

  val exampleHash: ByteString = ByteString(
    Hex.decode("fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef6")
  )

  "MessageDecoders" should "dispatch ETH68 capability to ETH68MessageDecoder" taggedAs (UnitTest, NetworkTest) in {
    val getBlockHeaders = ETHPackets.GetBlockHeaders(BigInt(1), Left(1), 1, 0, false)
    val bytes = getBlockHeaders.toBytes
    decode(Capability.ETH68).fromBytes(Codes.GetBlockHeadersCode, bytes) shouldBe Right(getBlockHeaders)
  }

  it should "dispatch ETH69 capability to ETH69MessageDecoder" taggedAs (UnitTest, NetworkTest) in {
    val getBlockHeaders = ETHPackets.GetBlockHeaders(BigInt(1), Left(1), 1, 0, false)
    val bytes = getBlockHeaders.toBytes
    decode(Capability.ETH69).fromBytes(Codes.GetBlockHeadersCode, bytes) shouldBe Right(getBlockHeaders)
  }

  it should "decode GetBlockBodies in ETH68" taggedAs (UnitTest, NetworkTest) in {
    val msg = ETHPackets.GetBlockBodies(BigInt(1), Seq(exampleHash))
    decode(Capability.ETH68).fromBytes(Codes.GetBlockBodiesCode, msg.toBytes) shouldBe Right(msg)
  }

  it should "decode BlockBodies in ETH68" taggedAs (UnitTest, NetworkTest) in {
    val msg = ETHPackets.BlockBodies(
      BigInt(1),
      Seq(Fixtures.Blocks.Block3125369.body, Fixtures.Blocks.DaoForkBlock.body)
    )
    decode(Capability.ETH68).fromBytes(Codes.BlockBodiesCode, msg.toBytes) shouldBe Right(msg)
  }

  it should "reject GetNodeData in ETH68 (EIP-4938 removal)" taggedAs (UnitTest, NetworkTest) in {
    // GetNodeData was removed in ETH68. The decoder returns a MalformedMessageError.
    val result = decode(Capability.ETH68).fromBytes(Codes.GetNodeDataCode, Array.emptyByteArray)
    result shouldBe a[Left[_, _]]
  }

  it should "reject NodeData in ETH68 (EIP-4938 removal)" taggedAs (UnitTest, NetworkTest) in {
    val result = decode(Capability.ETH68).fromBytes(Codes.NodeDataCode, Array.emptyByteArray)
    result shouldBe a[Left[_, _]]
  }

  it should "decode NewBlock in ETH68" taggedAs (UnitTest, NetworkTest) in {
    import ETHPackets.NewBlock._
    val msg = ETHPackets.NewBlock(Fixtures.Blocks.Block3125369.block, BigInt(12345))
    decode(Capability.ETH68).fromBytes(Codes.NewBlockCode, msg.toBytes) shouldBe Right(msg)
  }

  it should "decode SignedTransactions in ETH68" taggedAs (UnitTest, NetworkTest) in {
    import ETHPackets.SignedTransactions._
    val txs = ETHPackets.SignedTransactions(ObjectGenerators.signedTxSeqGen(3, secureRandom, None).sample.get)
    decode(Capability.ETH68).fromBytes(Codes.SignedTransactionsCode, txs.toBytes) shouldBe Right(txs)
  }

  it should "return error for unknown message code in ETH68" taggedAs (UnitTest, NetworkTest) in {
    decode(Capability.ETH68).fromBytes(0xff, Array.emptyByteArray) shouldBe a[Left[_, _]]
  }
}
