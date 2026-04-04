package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions
import com.chipprbots.ethereum.network.p2p.messages._
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._

class MessageDecodersSpec extends AnyFlatSpec with Matchers with SecureRandomBuilder {

  def decode: Capability => MessageDecoder = EthereumMessageDecoder.ethMessageDecoder _

  val exampleHash: ByteString = ByteString(
    Hex.decode("fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef6")
  )

  val blockHashesFromNumberBytes: Array[Byte] = Hex.decode("c20c28")

  val NewBlockHashesETH61bytes: Array[Byte] =
    Hex.decode(
      "f842a0fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef6a0fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef6"
    )

  "MessageDecoders" should "decode wire protocol message for all versions of protocol" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // helloBytes encodes ETH68 (0x44) capability — was eth/63 (0x3f) before ETH63-67 removal
    val helloBytes: Array[Byte] =
      Hex.decode(
        "f854048666756b756969c6c58365746844820d05b840a13f3f0555b5037827c743e40fce29139fcf8c3f2a8f12753872fe906a77ff70f6a7f517be995805ff39ab73af1d53dac1a6c9786eebc5935fc455ac8f41ba67"
      )
    val hello = WireProtocol.Hello(
      p2pVersion = 4,
      clientId = "fukuii",
      capabilities = Seq(Capability.ETH68),
      listenPort = 3333,
      nodeId = ByteString(
        Hex.decode(
          "a13f3f0555b5037827c743e40fce29139fcf8c3f2a8f12753872fe906a77ff70f6a7f517be995805ff39ab73af1d53dac1a6c9786eebc5935fc455ac8f41ba67"
        )
      )
    )
    NetworkMessageDecoder.fromBytes(WireProtocol.Hello.code, helloBytes) shouldBe Right(hello)
  }

  it should "decode NewBlockHashes message for all supported versions of protocol" taggedAs (UnitTest, NetworkTest) in {
    val NewBlockHashesETH62bytes: Array[Byte] =
      Hex.decode(
        "f846e2a0fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef601e2a0fccdbfe911f9df0a6cc0107d1240f76dfdd1d301b65fdc3cd2ae62752affbef602"
      )
    val newBlockHashesETH62 =
      ETH62.NewBlockHashes(Seq(ETH62.BlockHash(exampleHash, 1), ETH62.BlockHash(exampleHash, 2)))

    decode(Capability.ETH68)
      .fromBytes(Codes.NewBlockHashesCode, NewBlockHashesETH62bytes) shouldBe Right(newBlockHashesETH62)
    decode(Capability.ETH68)
      .fromBytes(Codes.NewBlockHashesCode, NewBlockHashesETH62bytes) shouldBe Right(newBlockHashesETH62)
  }

  // BlockHashesFromNumber (ETH61) shares code 0x08 with NewPooledTransactionHashes (ETH68).
  // ETH68 decoder always decodes code 0x08 as NewPooledTransactionHashes — ETH61 is no longer supported.
  it should "decode NewPooledTransactionHashes on code 0x08 for ETH68" taggedAs (UnitTest, NetworkTest) in {
    val types = Seq[Byte](0, 1)
    val sizes = Seq[BigInt](100, 200)
    val hashes = Seq(exampleHash, exampleHash)
    val msg = messages.ETH67.NewPooledTransactionHashes(types, sizes, hashes)
    val encoded = msg.toBytes
    decode(Capability.ETH68).fromBytes(Codes.NewPooledTransactionHashesCode, encoded) shouldBe Right(msg)
  }

  // ETH68 uses ETH66's requestId-wrapped format for block sync messages.
  // Tests updated from ETH62/ETH63 format to ETH66 format after ETH63-67 removal.
  it should "decode GetBlockHeaders message for ETH68" taggedAs (UnitTest, NetworkTest) in {
    val getBlockHeaders = ETH66.GetBlockHeaders(requestId = 42, block = Left(1), maxHeaders = 1, skip = 1, reverse = false)
    val getBlockHeadersBytes: Array[Byte] = getBlockHeaders.toBytes

    decode(Capability.ETH68).fromBytes(Codes.GetBlockHeadersCode, getBlockHeadersBytes) shouldBe Right(getBlockHeaders)
  }

  it should "decode BlockHeaders message for ETH68" taggedAs (UnitTest, NetworkTest) in {
    val blockHeaders = ETH66.BlockHeaders(requestId = 42, headers = ObjectGenerators.seqBlockHeaderGen.sample.get)
    val blockHeadersBytes: Array[Byte] = blockHeaders.toBytes

    decode(Capability.ETH68).fromBytes(Codes.BlockHeadersCode, blockHeadersBytes) shouldBe Right(blockHeaders)
  }

  it should "decode GetBlockBodies message for ETH68" taggedAs (UnitTest, NetworkTest) in {
    val getBlockBodies = ETH66.GetBlockBodies(requestId = 42, hashes = Seq(exampleHash))
    val getBlockBodiesBytes: Array[Byte] = getBlockBodies.toBytes

    decode(Capability.ETH68).fromBytes(Codes.GetBlockBodiesCode, getBlockBodiesBytes) shouldBe Right(getBlockBodies)
  }

  it should "decode BlockBodies message for ETH68" taggedAs (UnitTest, NetworkTest) in {
    val blockBodies = ETH66.BlockBodies(requestId = 42, bodies = Seq(Fixtures.Blocks.Block3125369.body, Fixtures.Blocks.DaoForkBlock.body))
    val blockBodiesBytes: Array[Byte] = blockBodies.toBytes

    decode(Capability.ETH68).fromBytes(Codes.BlockBodiesCode, blockBodiesBytes) shouldBe Right(blockBodies)
  }

  // GetNodeData and NodeData are not supported in ETH68 (removed per EIP-4444 direction).
  it should "reject GetNodeData with specific error in ETH68" taggedAs (UnitTest, NetworkTest) in {
    val getNodeData = ETH63.GetNodeData(Seq(exampleHash))
    val getNodeDataBytes: Array[Byte] = getNodeData.toBytes
    val result = decode(Capability.ETH68).fromBytes(Codes.GetNodeDataCode, getNodeDataBytes)
    result shouldBe a[Left[?, ?]]
    result.swap.toOption.get.getMessage should include("not supported in eth/68")
  }

  it should "reject NodeData with specific error in ETH68" taggedAs (UnitTest, NetworkTest) in {
    val nodeData = ETH63.NodeData(Seq(exampleHash))
    val nodeDataBytes: Array[Byte] = nodeData.toBytes
    val result = decode(Capability.ETH68).fromBytes(Codes.NodeDataCode, nodeDataBytes)
    result shouldBe a[Left[?, ?]]
    result.swap.toOption.get.getMessage should include("not supported in eth/68")
  }

  it should "decode GetReceipts message for ETH68" taggedAs (UnitTest, NetworkTest) in {
    val getReceipts = ETH66.GetReceipts(requestId = 42, blockHashes = Seq(exampleHash))
    val getReceiptsBytes: Array[Byte] = getReceipts.toBytes

    decode(Capability.ETH68).fromBytes(Codes.GetReceiptsCode, getReceiptsBytes) shouldBe Right(getReceipts)
  }

  it should "decode Receipts message for ETH68" taggedAs (UnitTest, NetworkTest) in {
    // ETH68 uses ETH66 Receipts format (with requestId). Round-trip encode/decode.
    val receipts = ETH66.Receipts(requestId = 42L, receiptsForBlocks = RLPList())
    val receiptsBytes: Array[Byte] = receipts.toBytes

    decode(Capability.ETH68).fromBytes(Codes.ReceiptsCode, receiptsBytes) shouldBe Right(receipts)
  }

  // ETH68 only supports ETH64+ Status format (with ForkId). ETH63 format (no ForkId) is rejected.
  it should "decode Status message for ETH68" taggedAs (UnitTest, NetworkTest) in {
    val statusEth64 =
      ETH64.Status(Capability.ETH68.version, 1, BigInt(100), exampleHash, exampleHash, ForkId(1L, None))

    decode(Capability.ETH68).fromBytes(Codes.StatusCode, statusEth64.toBytes) shouldBe Right(statusEth64)
  }

  it should "decode NewBlock message for all supported versions of protocol" taggedAs (UnitTest, NetworkTest) in {
    val newBlock63 = ObjectGenerators.newBlockGen(secureRandom, None).sample.get
    val newBlock63Bytes: Array[Byte] = newBlock63.toBytes

    decode(Capability.ETH68).fromBytes(Codes.NewBlockCode, newBlock63Bytes) shouldBe Right(newBlock63)
    decode(Capability.ETH68).fromBytes(Codes.NewBlockCode, newBlock63Bytes) shouldBe Right(newBlock63)
  }

  it should "decode SignedTransactions message for all supported versions of protocol" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val signedTransactions = SignedTransactions(ObjectGenerators.signedTxSeqGen(3, secureRandom, None).sample.get)
    val signedTransactionsBytes: Array[Byte] = signedTransactions.toBytes

    decode(Capability.ETH68)
      .fromBytes(Codes.SignedTransactionsCode, signedTransactionsBytes) shouldBe Right(signedTransactions)
    decode(Capability.ETH68)
      .fromBytes(Codes.SignedTransactionsCode, signedTransactionsBytes) shouldBe Right(signedTransactions)
  }

  it should "not decode message not existing taggedAs (UnitTest, NetworkTest) in given protocol" in {
    decode(Capability.ETH68)
      .fromBytes(Codes.SignedTransactionsCode, blockHashesFromNumberBytes) shouldBe a[Left[_, Message]]

  }
}
