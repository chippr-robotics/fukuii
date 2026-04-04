package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages._
import com.chipprbots.ethereum.network.p2p.messages.ETH62._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol._

class MessagesSerializationSpec extends AnyWordSpec with ScalaCheckPropertyChecks with Matchers {

  "Wire Protocol" when {

    "encoding and decoding Hello" should {
      "return same result" in {
        verify(
          Hello(1, "teest", Seq(Capability.ETH68, Capability.ETH68), 1, ByteString("Id")),
          (m: Hello) => m.toBytes,
          Hello.code,
          Capability.ETH68
        )
      }
    }

    "encoding and decoding Disconnect" should {
      "return same result" in {
        verify(
          Disconnect(Disconnect.Reasons.AlreadyConnected),
          (m: Disconnect) => m.toBytes,
          Disconnect.code,
          Capability.ETH68
        )
      }
    }

    "encoding and decoding Ping" should {
      "return same result" in {
        verify(Ping(), (m: Ping) => m.toBytes, Ping.code, Capability.ETH68)
      }
    }

    "encoding and decoding Pong" should {
      "return same result" in {
        verify(Pong(), (m: Pong) => m.toBytes, Pong.code, Capability.ETH68)
      }
    }
  }

  "Common Messages" when {
    "encoding and decoding SignedTransactions" should {
      "return same result" in {
        val msg = SignedTransactions(Fixtures.Blocks.Block3125369.body.transactionList)
        verify(msg, (m: SignedTransactions) => m.toBytes, Codes.SignedTransactionsCode, Capability.ETH68)
      }

      "return same result for typed transaction wire encoding" in {
        val typedTx = TransactionWithAccessList(
          chainId = 1,
          nonce = 1,
          gasPrice = 1,
          gasLimit = 21000,
          receivingAddress = None,
          value = 0,
          payload = ByteString.empty,
          accessList = Nil
        )
        val signedTypedTx = SignedTransaction(typedTx, ECDSASignature(r = 1, s = 2, v = 1))
        val msg = SignedTransactions(Seq(signedTypedTx))
        verify(msg, (m: SignedTransactions) => m.toBytes, Codes.SignedTransactionsCode, Capability.ETH68)
      }
    }

    "encoding and decoding NewBlock" should {
      "return same result for NewBlock v63" in {
        val msg = NewBlock(Fixtures.Blocks.Block3125369.block, 2323)
        verify(msg, (m: NewBlock) => m.toBytes, Codes.NewBlockCode, Capability.ETH68)
      }

      // Test with totalDifficulty >= 128 to verify RLP encoding handles high-bit values correctly
      "handle totalDifficulty >= 128 correctly (two's complement edge case)" in {
        val msg = NewBlock(
          Fixtures.Blocks.Block3125369.block,
          totalDifficulty = BigInt("8000000000000000", 16) // Tests high bit in large value
        )
        verify(msg, (m: NewBlock) => m.toBytes, Codes.NewBlockCode, Capability.ETH68)
      }
    }
  }

  "ETH68" when {
    val version = Capability.ETH68
    "encoding and decoding Status" should {
      "return same result" in {
        val msg = ETH64.Status(1, 2, 3, ByteString("HASH"), ByteString("HASH2"), ForkId(1L, None))
        verify(msg, (m: ETH64.Status) => m.toBytes, Codes.StatusCode, Capability.ETH68)
      }

      // Test with values >= 128 to verify RLP encoding handles high-bit values correctly
      "handle values >= 128 correctly (two's complement edge case)" in {
        val msg = ETH64.Status(
          protocolVersion = 128, // Tests high bit in single byte
          networkId = 256, // Tests value requiring 2 bytes
          totalDifficulty = BigInt("8000000000000000", 16), // Tests high bit in large value
          bestHash = ByteString("HASH"),
          genesisHash = ByteString("HASH2"),
          forkId = ForkId(1L, None)
        )
        verify(msg, (m: ETH64.Status) => m.toBytes, Codes.StatusCode, Capability.ETH68)
      }
    }
    commonEthAssertions(version)
  }

  // scalastyle:off method.length
  def commonEthAssertions(version: Capability): Unit = {
    "encoding and decoding ETH61.NewBlockHashes" should {
      "throw for unsupported message version" in {
        val msg = ETH61.NewBlockHashes(Seq(ByteString("23"), ByteString("10"), ByteString("36")))
        assertThrows[RuntimeException] {
          verify(msg, (m: ETH61.NewBlockHashes) => m.toBytes, Codes.NewBlockHashesCode, version)
        }
      }
    }

    "encoding and decoding ETH62.NewBlockHashes" should {
      "return same result" in {
        val msg = ETH62.NewBlockHashes(Seq(BlockHash(ByteString("hash1"), 1), BlockHash(ByteString("hash2"), 2)))
        verify(msg, (m: ETH62.NewBlockHashes) => m.toBytes, Codes.NewBlockHashesCode, version)
      }
    }

    "encoding and decoding ETH66.BlockBodies" should {
      "return same result" in {
        val msg = ETH66.BlockBodies(0, Seq(Fixtures.Blocks.Block3125369.body, Fixtures.Blocks.DaoForkBlock.body))
        verify(msg, (m: ETH66.BlockBodies) => m.toBytes, Codes.BlockBodiesCode, version)
      }
    }

    "encoding and decoding ETH66.GetBlockBodies" should {
      "return same result" in {
        val msg = ETH66.GetBlockBodies(0, Seq(ByteString("111"), ByteString("2222")))
        verify(msg, (m: ETH66.GetBlockBodies) => m.toBytes, Codes.GetBlockBodiesCode, version)
      }
    }

    "encoding and decoding ETH66.BlockHeaders" should {
      "return same result" in {
        val msg = ETH66.BlockHeaders(0, Seq(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.DaoForkBlock.header))
        verify(msg, (m: ETH66.BlockHeaders) => m.toBytes, Codes.BlockHeadersCode, version)
      }
    }

    "encoding and decoding ETH66.GetBlockHeaders" should {
      "return same result" in {
        verify(
          ETH66.GetBlockHeaders(0, Left(1), 1, 1, false),
          (m: ETH66.GetBlockHeaders) => m.toBytes,
          Codes.GetBlockHeadersCode,
          version
        )
        verify(
          ETH66.GetBlockHeaders(0, Right(ByteString("1" * 32)), 1, 1, true),
          (m: ETH66.GetBlockHeaders) => m.toBytes,
          Codes.GetBlockHeadersCode,
          version
        )
      }
    }
  }
  // scalastyle:on

  def verify[T](msg: T, encode: T => Array[Byte], code: Int, version: Capability): Unit =
    messageDecoder(version).fromBytes(code, encode(msg)) shouldEqual Right(msg)

  private def messageDecoder(version: Capability) =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(version))
}
