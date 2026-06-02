package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder

class ETH68MessagesSpec extends AnyWordSpec with Matchers {


  "ETH68" when {
    val version = Capability.ETH68

    "encoding and decoding Status" should {
      "return same result" in {
        import ETHPackets.Status68.Status68._
        val msg = ETHPackets.Status68.Status68(1, 2, 3, ByteString("HASH"), ByteString("HASH2"), ForkId(1L, None))
        verify(msg, (m: ETHPackets.Status68.Status68) => m.toBytes, Codes.StatusCode, version)
      }
    }

    "encoding and decoding NewPooledTransactionHashes with types and sizes" should {
      "return same result" in {
        import ETHPackets.NewPooledTransactionHashes._
        val types = Seq[Byte](0, 1, 2)
        val sizes = Seq[BigInt](100, 200, 300)
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"), ByteString("hash3"))
        val msg = ETHPackets.NewPooledTransactionHashes(types, sizes, hashes)
        verify(msg, (m: ETHPackets.NewPooledTransactionHashes) => m.toBytes, Codes.NewPooledTransactionHashesCode, version)
      }
    }

    "decoding NewPooledTransactionHashes in legacy ETH65 format" should {
      "successfully decode and set default types and sizes" in {
        import com.chipprbots.ethereum.rlp._
        import com.chipprbots.ethereum.rlp.RLPImplicits.given
        import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
        import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes._

        val hashes = Seq(ByteString("hash1"), ByteString("hash2"), ByteString("hash3"))
        val legacyEncoded = encode(toRlpList(hashes))

        val decoded = legacyEncoded.toNewPooledTransactionHashes

        decoded.hashes shouldBe hashes
        decoded.types shouldBe Seq[Byte](0, 0, 0)
        decoded.sizes shouldBe Seq(BigInt(0), BigInt(0), BigInt(0))
      }
    }

    "decoding GetNodeData" should {
      "fail with specific error message" in {
        val payload = Array[Byte](0x01, 0x02, 0x03)
        val result = messageDecoder(version).fromBytes(Codes.GetNodeDataCode, payload)
        result.isLeft shouldBe true
        result.left.map(_.getMessage) shouldBe Left("GetNodeData (0x0d) not supported in eth/68 (EIP-4938)")
      }
    }

    "decoding NodeData" should {
      "fail with specific error message" in {
        val payload = Array[Byte](0x01, 0x02, 0x03)
        val result = messageDecoder(version).fromBytes(Codes.NodeDataCode, payload)
        result.isLeft shouldBe true
        result.left.map(_.getMessage) shouldBe Left("NodeData (0x0e) not supported in eth/68 (EIP-4938)")
      }
    }
  }

  def verify[T](msg: T, encode: T => Array[Byte], code: Int, version: Capability): Unit = {
    val encoded = encode(msg)
    val decoded = messageDecoder(version).fromBytes(code, encoded)
    decoded shouldEqual Right(msg)
  }

  private def messageDecoder(version: Capability) =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(version))
}
