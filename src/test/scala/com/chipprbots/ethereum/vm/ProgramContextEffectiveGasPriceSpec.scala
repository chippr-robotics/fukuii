package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.{Fixtures => CommonFixtures}
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.testing.Tags._

/** Regression for `ProgramContext.apply` setting `gasPrice` to the EIP-1559 effective gas price (min(maxFeePerGas,
  * baseFee + maxPriorityFeePerGas)) rather than the raw `tx.gasPrice` (which for Type-2 returns maxFeePerGas).
  *
  * Surfaces on the hive `bcEIP1559/burnVerify_Cancun` consensus test, whose contract stores `GASPRICE` to a slot
  * already holding the baseFee — making it a no-op SSTORE (100 gas) only if `GASPRICE == effectiveGasPrice == baseFee`.
  * When `GASPRICE` was leaking out as `maxFeePerGas` (1000) instead of the effective price (875 = baseFee), Fukuii
  * treated it as a fresh-slot reset (2900 gas), yielding the observed +2800 gas delta.
  */
class ProgramContextEffectiveGasPriceSpec extends AnyFlatSpec with Matchers {

  private val senderAddr: Address = Address(ByteString(Array.fill[Byte](20)(0x11.toByte)))

  // Only the EIP-1559 fields matter for `effectiveGasPrice`. Other fields use
  // arbitrary fixture values.
  private def newDynamicFeeTx(maxFeePerGas: BigInt, maxPriorityFeePerGas: BigInt): SignedTransaction = {
    val raw = TransactionWithDynamicFee(
      nonce = 0,
      maxPriorityFeePerGas = maxPriorityFeePerGas,
      maxFeePerGas = maxFeePerGas,
      gasLimit = 100000,
      receivingAddress = Some(Address(ByteString(Array.fill[Byte](20)(0xcc.toByte)))),
      value = 0,
      payload = ByteString.empty,
      accessList = Nil,
      chainId = 1
    )
    SignedTransaction(raw, ECDSASignature(BigInt(0), BigInt(0), BigInt(0)))
  }

  // HefPostOlympia carries just the baseFee, which is all `Transaction.effectiveGasPrice`
  // looks at. For Cancun-era txs the same baseFee plumbing applies through HefPostShanghai/
  // HefPostCancun; testing the simpler branch is sufficient here.
  private def newHeader(baseFee: BigInt): BlockHeader =
    CommonFixtures.Blocks.ValidBlock.header.copy(
      extraFields = BlockHeader.HeaderExtraFields.HefPostOlympia(baseFee)
    )

  // EvmConfig isn't read by ProgramContext.apply for `gasPrice`, so any config works.
  // `Fixtures.blockchainConfig` (in this package) returns a BlockchainConfigForEvm directly.
  private val evmConfig: EvmConfig = EvmConfig.forBlock(BigInt(1), Fixtures.blockchainConfig)

  "ProgramContext" should
    "expose the EIP-1559 effective gas price (not maxFeePerGas) for Type-2 txs" taggedAs (UnitTest) in {
      val stx = newDynamicFeeTx(maxFeePerGas = 1000, maxPriorityFeePerGas = 0)
      val header = newHeader(baseFee = 875)
      val ctx = ProgramContext(stx, header, senderAddr, null, evmConfig)
      // effective = min(maxFee=1000, baseFee+priority = 875+0 = 875) = 875
      ctx.gasPrice shouldBe UInt256(875)
    }

  it should "cap at maxFeePerGas when baseFee + priority exceeds it" taggedAs (UnitTest) in {
    val stx = newDynamicFeeTx(maxFeePerGas = 900, maxPriorityFeePerGas = 100)
    val header = newHeader(baseFee = 875)
    val ctx = ProgramContext(stx, header, senderAddr, null, evmConfig)
    // effective = min(maxFee=900, baseFee+priority = 875+100 = 975) = 900
    ctx.gasPrice shouldBe UInt256(900)
  }

  it should "add priority fee on top of baseFee when under maxFee" taggedAs (UnitTest) in {
    val stx = newDynamicFeeTx(maxFeePerGas = 2000, maxPriorityFeePerGas = 100)
    val header = newHeader(baseFee = 875)
    val ctx = ProgramContext(stx, header, senderAddr, null, evmConfig)
    // effective = min(maxFee=2000, baseFee+priority = 875+100 = 975) = 975
    ctx.gasPrice shouldBe UInt256(975)
  }
}
