package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*

/** `gasPrice` in a transaction object (#1629 G6), as go-ethereum's `newRPCTransaction` reports it: a mined fee-market
  * tx reports what it paid, min(maxFeePerGas, baseFee + maxPriorityFeePerGas); a pending one has paid nothing and
  * reports its fee cap, maxFeePerGas. A pending typed tx used to report min(maxFee, 0 + tip), i.e. its tip.
  * eth_getTransactionByHash on a pending tx and every txpool_* method go through this.
  */
class TransactionResponseGasPriceSpec extends AnyFlatSpec with Matchers with SecureRandomBuilder:

  private val keyPair = crypto.generateKeyPair(secureRandom)
  private val maxFee = BigInt(30)
  private val tip = BigInt(2)

  private def signed(tx: Transaction): SignedTransaction = SignedTransaction.sign(tx, keyPair, Some(BigInt(1)))

  private val dynamicFee = signed(
    TransactionWithDynamicFee(1, 0, tip, maxFee, GasAmount(21_000), Some(Address(1)), 0, ByteString.empty, Nil)
  )
  private val setCode = signed(
    SetCodeTransaction(1, 0, tip, maxFee, GasAmount(60_000), Some(Address(1)), 0, ByteString.empty, Nil, Nil)
  )
  private val legacy = signed(
    LegacyTransaction(0, GasPrice(7), GasAmount(21_000), Some(Address(1)), 0, ByteString.empty)
  )

  private def minedAt(baseFee: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(extraFields = HefPostOlympia(baseFee))

  "TransactionResponse.gasPrice" should "be maxFeePerGas for a pending fee-market tx" taggedAs (UnitTest, RPCTest) in {
    TransactionResponse(dynamicFee).gasPrice shouldBe maxFee
    TransactionResponse(setCode).gasPrice shouldBe maxFee
  }

  it should "be the effective price for a mined fee-market tx" taggedAs (UnitTest, RPCTest) in {
    TransactionResponse(dynamicFee, Some(minedAt(10)), Some(0)).gasPrice shouldBe 12 // 10 + 2 < 30
    TransactionResponse(dynamicFee, Some(minedAt(29)), Some(0)).gasPrice shouldBe maxFee // 29 + 2 capped at 30
  }

  it should "be gasPrice for a legacy tx, pending or mined" taggedAs (UnitTest, RPCTest) in {
    TransactionResponse(legacy).gasPrice shouldBe 7
    TransactionResponse(legacy, Some(minedAt(5)), Some(0)).gasPrice shouldBe 7
    TransactionResponse(legacy, Some(Fixtures.Blocks.ValidBlock.header), Some(0)).gasPrice shouldBe 7
  }
