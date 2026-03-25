package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.params.ECPublicKeyParameters

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

/** Tests for StatePrefetcher — background cache warming for block transactions.
  */
class StatePrefetcherSpec extends AnyFlatSpec with Matchers with SecureRandomBuilder {

  implicit val runtime: IORuntime = IORuntime.global

  "StatePrefetcher" should "prefetch without throwing for block with transactions" taggedAs (UnitTest) in new TestSetup {
    noException should be thrownBy {
      prefetcher.prefetchAsync(blockWithTx, ByteString(MerklePatriciaTrie.EmptyRootHash))
    }
    Thread.sleep(200) // Allow async prefetch to complete
  }

  it should "be a no-op for blocks with no transactions" taggedAs (UnitTest) in new TestSetup {
    noException should be thrownBy {
      prefetcher.prefetchAsync(emptyBlock, ByteString(MerklePatriciaTrie.EmptyRootHash))
    }
  }

  it should "handle missing state gracefully" taggedAs (UnitTest) in new TestSetup {
    // Use a bogus state root — prefetch should catch the exception silently
    val bogusRoot = ByteString(Array.fill(32)(0xFF.toByte))
    noException should be thrownBy {
      prefetcher.prefetchAsync(blockWithTx, bogusRoot)
    }
    Thread.sleep(200)
  }

  it should "handle contract creation transactions (no receiver)" taggedAs (UnitTest) in new TestSetup {
    noException should be thrownBy {
      prefetcher.prefetchAsync(blockWithContractCreation, ByteString(MerklePatriciaTrie.EmptyRootHash))
    }
    Thread.sleep(200)
  }

  trait TestSetup extends EphemBlockchainTestSetup {
    override implicit lazy val blockchainConfig = Config.blockchains.blockchainConfig

    val prefetcher = new StatePrefetcher(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage
    )

    val senderKeyPair = generateKeyPair(secureRandom)
    val senderAddress: Address = Address(
      kec256(senderKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail)
    )

    val recipientAddress: Address = Address(ByteString(org.bouncycastle.util.encoders.Hex.decode("abcdef0123" * 4)))

    val stx1: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(nonce = 0, gasPrice = 20000000000L, gasLimit = 21000,
        receivingAddress = recipientAddress, value = BigInt("1000000000000000000"), payload = ByteString.empty),
      senderKeyPair, Some(BigInt(1))
    )

    val contractTx: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(nonce = 1, gasPrice = 20000000000L, gasLimit = 100000,
        receivingAddress = None, value = 0, payload = ByteString(Array[Byte](0x60, 0x00))),
      senderKeyPair, Some(BigInt(1))
    )

    val parentHeader = BlockHeader(
      parentHash = ByteString(new Array[Byte](32)),
      ommersHash = ByteString(new Array[Byte](32)),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = ByteString(MerklePatriciaTrie.EmptyRootHash),
      transactionsRoot = ByteString(new Array[Byte](32)),
      receiptsRoot = ByteString(new Array[Byte](32)),
      logsBloom = ByteString(new Array[Byte](256)),
      difficulty = 1000,
      number = 0,
      gasLimit = 8000000,
      gasUsed = 0,
      unixTimestamp = 1000,
      extraData = ByteString.empty,
      mixHash = ByteString(new Array[Byte](32)),
      nonce = ByteString(new Array[Byte](8))
    )

    val blockWithTx = Block(
      parentHeader.copy(number = 1, parentHash = parentHeader.hash),
      BlockBody(Seq(stx1), Nil)
    )

    val emptyBlock = Block(
      parentHeader.copy(number = 1, parentHash = parentHeader.hash),
      BlockBody.empty
    )

    val blockWithContractCreation = Block(
      parentHeader.copy(number = 1, parentHash = parentHeader.hash),
      BlockBody(Seq(contractTx), Nil)
    )
  }
}
