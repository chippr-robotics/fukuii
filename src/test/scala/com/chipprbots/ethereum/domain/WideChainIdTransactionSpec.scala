package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Transactions for a chain id wider than 32 bits.
  *
  * Platåberget's chain id is 7091047534 (0x1a6a8cc6e) — the first shipped chain whose id does not fit in an Int, or
  * even a UInt32. An EIP-155 `v` for it is 14182095103/14182095104, which a Byte-, Int- or UInt32-typed signature or
  * chain-id path would silently truncate, recovering the wrong sender (or none) for every legacy transaction on that
  * network. Every other shipped chain id (1, 61, 63, 11155111) fits in an Int, so nothing else in the suite exercises
  * this width.
  */
class WideChainIdTransactionSpec extends AnyFlatSpec with Matchers:

  private val WideChainId: BigInt = BigInt(7091047534L)

  private given BlockchainConfig = Config.blockchains.blockchainConfig.copy(chainId = ChainId(WideChainId))

  // The EIP-155 specification's example key, so the vector is reproducible outside this suite.
  private val signingKey = crypto.keyPairFromPrvKey(
    Hex.decode("4646464646464646464646464646464646464646464646464646464646464646")
  )
  private val sender = Address(signingKey)
  private val recipient = Some(Address("0x3535353535353535353535353535353535353535"))

  "a legacy transaction signed for a chain id above 2^32" should
    "carry v = 2*chainId + 35|36 and round-trip through the wire codec with its sender" taggedAs (UnitTest) in {
      val tx = LegacyTransaction(
        nonce = 9,
        gasPrice = GasPrice(BigInt(20000000000L)),
        gasLimit = GasAmount(21000),
        receivingAddress = recipient,
        value = BigInt("1000000000000000000"),
        payload = ByteString.empty
      )
      val stx = SignedTransaction.sign(tx, signingKey, Some(WideChainId))

      stx.signature.v should (equal(WideChainId * 2 + 35).or(equal(WideChainId * 2 + 36)))

      val decoded = stx.toBytes.toSignedTransaction
      decoded shouldBe stx
      SignedTransaction.getSender(decoded) shouldBe Some(sender)
    }

  "an EIP-1559 transaction for a chain id above 2^32" should
    "round-trip through the wire codec with its chain id and sender intact" taggedAs (UnitTest) in {
      val tx = TransactionWithDynamicFee(
        chainId = WideChainId,
        nonce = 0,
        maxPriorityFeePerGas = BigInt(1000000000),
        maxFeePerGas = BigInt(2000000000),
        gasLimit = GasAmount(21000),
        receivingAddress = recipient,
        value = BigInt(1),
        payload = ByteString.empty,
        accessList = Nil
      )
      val stx = SignedTransaction.sign(tx, signingKey, Some(WideChainId))

      val decoded = stx.toBytes.toSignedTransaction
      decoded.tx shouldBe tx
      SignedTransaction.getSender(decoded) shouldBe Some(sender)
    }
