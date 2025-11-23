package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.testing.Tags._

class ETH65PlusMessagesSpec extends AnyWordSpec with Matchers {

  "ETH65" when {
    val version = Capability.ETH65

    "encoding and decoding Status" should {
      "return same result" in {
        val msg = ETH64.Status(1, 2, 3, ByteString("HASH"), ByteString("HASH2"), ForkId(1L, None))
        verify(msg, (m: ETH64.Status) => m.toBytes, Codes.StatusCode, version)
      }
    }

    "encoding and decoding NewPooledTransactionHashes" should {
      "return same result" in {
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"), ByteString("hash3"))
        val msg = ETH65.NewPooledTransactionHashes(hashes)
        verify(msg, (m: ETH65.NewPooledTransactionHashes) => m.toBytes, Codes.NewPooledTransactionHashesCode, version)
      }
    }

    "encoding and decoding GetPooledTransactions" should {
      "return same result" in {
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"))
        val msg = ETH65.GetPooledTransactions(hashes)
        verify(msg, (m: ETH65.GetPooledTransactions) => m.toBytes, Codes.GetPooledTransactionsCode, version)
      }
    }

    "encoding and decoding PooledTransactions" should {
      "return same result" in {
        val msg = ETH65.PooledTransactions(Fixtures.Blocks.Block3125369.body.transactionList)
        verify(msg, (m: ETH65.PooledTransactions) => m.toBytes, Codes.PooledTransactionsCode, version)
      }
    }
  }

  "ETH66" when {
    val version = Capability.ETH66

    "encoding and decoding Status" should {
      "return same result" in {
        val msg = ETH64.Status(1, 2, 3, ByteString("HASH"), ByteString("HASH2"), ForkId(1L, None))
        verify(msg, (m: ETH64.Status) => m.toBytes, Codes.StatusCode, version)
      }
    }

    "encoding and decoding GetBlockHeaders with request-id" should {
      "return same result for block number" in {
        val msg = ETH66.GetBlockHeaders(requestId = 42, block = Left(1), maxHeaders = 10, skip = 0, reverse = false)
        verify(msg, (m: ETH66.GetBlockHeaders) => m.toBytes, Codes.GetBlockHeadersCode, version)
      }

      "return same result for block hash" in {
        val msg = ETH66.GetBlockHeaders(
          requestId = 42,
          block = Right(ByteString("1" * 32)),
          maxHeaders = 10,
          skip = 0,
          reverse = true
        )
        verify(msg, (m: ETH66.GetBlockHeaders) => m.toBytes, Codes.GetBlockHeadersCode, version)
      }
    }

    "encoding and decoding BlockHeaders with request-id" should {
      "return same result" in {
        val msg = ETH66.BlockHeaders(
          requestId = 42,
          headers = Seq(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.DaoForkBlock.header)
        )
        verify(msg, (m: ETH66.BlockHeaders) => m.toBytes, Codes.BlockHeadersCode, version)
      }
    }

    "encoding and decoding GetBlockBodies with request-id" should {
      "return same result" in {
        val msg = ETH66.GetBlockBodies(requestId = 42, hashes = Seq(ByteString("111"), ByteString("2222")))
        verify(msg, (m: ETH66.GetBlockBodies) => m.toBytes, Codes.GetBlockBodiesCode, version)
      }
    }

    "encoding and decoding BlockBodies with request-id" should {
      "return same result" in {
        val msg =
          ETH66.BlockBodies(
            requestId = 42,
            bodies = Seq(Fixtures.Blocks.Block3125369.body, Fixtures.Blocks.DaoForkBlock.body)
          )
        verify(msg, (m: ETH66.BlockBodies) => m.toBytes, Codes.BlockBodiesCode, version)
      }
    }

    "encoding and decoding GetPooledTransactions with request-id" should {
      "return same result" in {
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"))
        val msg = ETH66.GetPooledTransactions(requestId = 42, txHashes = hashes)
        verify(msg, (m: ETH66.GetPooledTransactions) => m.toBytes, Codes.GetPooledTransactionsCode, version)
      }
    }

    "encoding and decoding PooledTransactions with request-id" should {
      "return same result" in {
        val msg = ETH66.PooledTransactions(requestId = 42, txs = Fixtures.Blocks.Block3125369.body.transactionList)
        verify(msg, (m: ETH66.PooledTransactions) => m.toBytes, Codes.PooledTransactionsCode, version)
      }
    }

    "encoding and decoding GetReceipts with request-id" should {
      "return same result" in {
        val msg = ETH66.GetReceipts(requestId = 42, blockHashes = Seq(ByteString("hash1"), ByteString("hash2")))
        verify(msg, (m: ETH66.GetReceipts) => m.toBytes, Codes.GetReceiptsCode, version)
      }
    }
  }

  "ETH67" when {
    val version = Capability.ETH67

    "encoding and decoding Status" should {
      "return same result" in {
        val msg = ETH64.Status(1, 2, 3, ByteString("HASH"), ByteString("HASH2"), ForkId(1L, None))
        verify(msg, (m: ETH64.Status) => m.toBytes, Codes.StatusCode, version)
      }
    }

    "encoding and decoding NewPooledTransactionHashes with types and sizes" should {
      "return same result" in {
        val types = Seq[Byte](0, 1, 2)
        val sizes = Seq[BigInt](100, 200, 300)
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"), ByteString("hash3"))
        val msg = ETH67.NewPooledTransactionHashes(types, sizes, hashes)
        verify(msg, (m: ETH67.NewPooledTransactionHashes) => m.toBytes, Codes.NewPooledTransactionHashesCode, version)
      }
    }

    "validating NewPooledTransactionHashes" should {
      "fail when types, sizes, and hashes have different lengths" in {
        val types = Seq[Byte](0, 1)
        val sizes = Seq[BigInt](100, 200, 300)
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"))

        assertThrows[IllegalArgumentException] {
          ETH67.NewPooledTransactionHashes(types, sizes, hashes)
        }
      }
    }

    "decoding NewPooledTransactionHashes in legacy ETH65 format" should {
      "successfully decode and set default types and sizes" in {
        import com.chipprbots.ethereum.rlp._
        import com.chipprbots.ethereum.rlp.RLPImplicits._
        import com.chipprbots.ethereum.rlp.RLPImplicits.given
        import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
        import com.chipprbots.ethereum.network.p2p.messages.ETH67.NewPooledTransactionHashes._

        // Encode in legacy ETH65 format: [hash1, hash2, hash3]
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"), ByteString("hash3"))
        val legacyEncoded = encode(toRlpList(hashes))

        // Decode as ETH67 message
        val decoded = legacyEncoded.toNewPooledTransactionHashes

        // Should decode successfully with default types and sizes
        decoded.hashes shouldBe hashes
        decoded.types shouldBe Seq[Byte](0, 0, 0)
        decoded.sizes shouldBe Seq(BigInt(0), BigInt(0), BigInt(0))
      }
    }
  }

  "ETH68" when {
    val version = Capability.ETH68

    "encoding and decoding Status" should {
      "return same result" in {
        val msg = ETH64.Status(1, 2, 3, ByteString("HASH"), ByteString("HASH2"), ForkId(1L, None))
        verify(msg, (m: ETH64.Status) => m.toBytes, Codes.StatusCode, version)
      }
    }

    "encoding and decoding NewPooledTransactionHashes with types and sizes" should {
      "return same result" in {
        val types = Seq[Byte](0, 1, 2)
        val sizes = Seq[BigInt](100, 200, 300)
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"), ByteString("hash3"))
        val msg = ETH67.NewPooledTransactionHashes(types, sizes, hashes)
        verify(msg, (m: ETH67.NewPooledTransactionHashes) => m.toBytes, Codes.NewPooledTransactionHashesCode, version)
      }
    }

    "decoding NewPooledTransactionHashes in legacy ETH65 format" should {
      "successfully decode and set default types and sizes" in {
        import com.chipprbots.ethereum.rlp._
        import com.chipprbots.ethereum.rlp.RLPImplicits._
        import com.chipprbots.ethereum.rlp.RLPImplicits.given
        import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
        import com.chipprbots.ethereum.network.p2p.messages.ETH67.NewPooledTransactionHashes._

        // Encode in legacy ETH65 format: [hash1, hash2, hash3]
        val hashes = Seq(ByteString("hash1"), ByteString("hash2"), ByteString("hash3"))
        val legacyEncoded = encode(toRlpList(hashes))

        // Decode as ETH68 message (uses ETH67 decoder)
        val decoded = legacyEncoded.toNewPooledTransactionHashes

        // Should decode successfully with default types and sizes
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
        result.left.map(_.getMessage) shouldBe Left("GetNodeData (0x0d) is not supported in eth/68")
      }
    }

    "decoding NodeData" should {
      "fail with specific error message" in {
        val payload = Array[Byte](0x01, 0x02, 0x03)
        val result = messageDecoder(version).fromBytes(Codes.NodeDataCode, payload)
        result.isLeft shouldBe true
        result.left.map(_.getMessage) shouldBe Left("NodeData (0x0e) is not supported in eth/68")
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
