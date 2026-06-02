package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets._
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.{NewBlockHashes, BlockHash}
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions._
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status68.{Status68 => Status68Class}
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status68.Status68.{Status68Enc, Status68Dec}
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol._

/** Serialization round-trip tests for ETH68+ wire messages.
  *
  * ETH61-67 serialization tests removed with those protocol files.
  * ETH68 is Fukuii's minimum supported version (EIP-4938).
  */
class MessagesSerializationSpec extends AnyWordSpec with Matchers {

  val version: Capability = Capability.ETH68

  "Wire Protocol" when {

    "encoding and decoding Hello" should {
      "return same result" in {
        verify(
          Hello(1, "fukuii", Seq(Capability.ETH68, Capability.ETH69), 30303, ByteString("Id")),
          (m: Hello) => m.toBytes,
          Hello.code,
          version
        )
      }
    }

    "encoding and decoding Disconnect" should {
      "return same result" in {
        verify(
          Disconnect(Disconnect.Reasons.AlreadyConnected),
          (m: Disconnect) => m.toBytes,
          Disconnect.code,
          version
        )
      }
    }

    "encoding and decoding Ping" should {
      "return same result" in {
        verify(Ping(), (m: Ping) => m.toBytes, Ping.code, version)
      }
    }

    "encoding and decoding Pong" should {
      "return same result" in {
        verify(Pong(), (m: Pong) => m.toBytes, Pong.code, version)
      }
    }
  }

  "ETH68" when {

    "encoding and decoding Status68" should {
      "return same result" in {
        val msg = Status68Class(68, 1L, BigInt(100), ByteString("HASH"), ByteString("HASH2"), ForkId(1L, None))
        verify(msg, (m: Status68Class) => m.toBytes, Codes.StatusCode, version)
      }

      "handle values >= 128 correctly (two's complement edge case)" in {
        val msg = Status68Class(
          protocolVersion = 128,
          networkId = 256,
          totalDifficulty = BigInt("8000000000000000", 16),
          bestHash = ByteString("HASH"),
          genesisHash = ByteString("HASH2"),
          forkId = ForkId(1L, None)
        )
        verify(msg, (m: Status68Class) => m.toBytes, Codes.StatusCode, version)
      }
    }

    "encoding and decoding SignedTransactions" should {
      "return same result" in {
        val msg = SignedTransactions(Fixtures.Blocks.Block3125369.body.transactionList)
        verify(msg, (m: SignedTransactions) => m.toBytes, Codes.SignedTransactionsCode, version)
      }

      "return same result for typed (EIP-2930) transaction wire encoding" in {
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
        verify(msg, (m: SignedTransactions) => m.toBytes, Codes.SignedTransactionsCode, version)
      }
    }

    "encoding and decoding NewBlock" should {
      "return same result" in {
        val msg = NewBlock(Fixtures.Blocks.Block3125369.block, 2323)
        verify(msg, (m: NewBlock) => m.toBytes, Codes.NewBlockCode, version)
      }

      "handle totalDifficulty >= 128 correctly (two's complement edge case)" in {
        val msg = NewBlock(
          Fixtures.Blocks.Block3125369.block,
          totalDifficulty = BigInt("8000000000000000", 16)
        )
        verify(msg, (m: NewBlock) => m.toBytes, Codes.NewBlockCode, version)
      }
    }

    "encoding and decoding NewBlockHashes" should {
      "return same result" in {
        val msg = NewBlockHashes(Seq(BlockHash(ByteString("hash1"), 1), BlockHash(ByteString("hash2"), 2)))
        verify(msg, (m: NewBlockHashes) => m.toBytes, Codes.NewBlockHashesCode, version)
      }
    }

    "encoding and decoding BlockBodies" should {
      "return same result" in {
        val msg = BlockBodies(BigInt(1), Seq(Fixtures.Blocks.Block3125369.body, Fixtures.Blocks.DaoForkBlock.body))
        verify(msg, (m: BlockBodies) => m.toBytes, Codes.BlockBodiesCode, version)
      }
    }

    "encoding and decoding GetBlockBodies" should {
      "return same result" in {
        val msg = GetBlockBodies(BigInt(1), Seq(ByteString("111"), ByteString("2222")))
        verify(msg, (m: GetBlockBodies) => m.toBytes, Codes.GetBlockBodiesCode, version)
      }
    }

    "encoding and decoding BlockHeaders" should {
      "return same result" in {
        val msg = BlockHeaders(
          BigInt(1),
          Seq(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.DaoForkBlock.header)
        )
        verify(msg, (m: BlockHeaders) => m.toBytes, Codes.BlockHeadersCode, version)
      }
    }

    "encoding and decoding GetBlockHeaders (by number)" should {
      "return same result" in {
        verify(
          GetBlockHeaders(BigInt(1), Left(1), 1, 0, false),
          (m: GetBlockHeaders) => m.toBytes,
          Codes.GetBlockHeadersCode,
          version
        )
      }
    }

    "encoding and decoding GetBlockHeaders (by hash)" should {
      "return same result" in {
        verify(
          GetBlockHeaders(BigInt(1), Right(ByteString("1" * 32)), 1, 0, true),
          (m: GetBlockHeaders) => m.toBytes,
          Codes.GetBlockHeadersCode,
          version
        )
      }
    }
  }

  def verify[T](msg: T, encode: T => Array[Byte], code: Int, version: Capability): Unit =
    messageDecoder(version).fromBytes(code, encode(msg)) shouldEqual Right(msg)

  private def messageDecoder(v: Capability) =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(v))
}
