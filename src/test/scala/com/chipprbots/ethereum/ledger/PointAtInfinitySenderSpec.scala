package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSignatureError
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.TxsExecutionError
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType

/** A transaction whose signature recovers to the secp256k1 point at infinity has NO sender. core-geth and go-ethereum
  * (core/types/transaction_signing.go recoverPlain -> crypto.Ecrecover; libsecp256k1 secp256k1_ecdsa_sig_recover
  * returns 0 for Q = O) reject it. fukuii used to hash the empty key and execute it as sender
  * 0xdcc703c0e500b653ca82273b7bfad8045d85a470 — with gasPrice 0 and that account's nonce 0 it passed every other check,
  * so a block carrying it was accepted by fukuii and rejected by the reference.
  */
class PointAtInfinitySenderSpec extends AnyFlatSpec with Matchers:

  private val setup = new TestSetup {}
  private val preparator =
    new BlockPreparator(setup.vm, StdSignedTransactionValidator, setup.blockchain, setup.blockchainReader)

  private val header: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
    number = BlockNumber(1),
    gasLimit = GasAmount(8_000_000),
    gasUsed = GasAmount.Zero
  )

  /** ETC mainnet rules as of Spiral (pre-Olympia), chain id 61. */
  private val etcConfig: BlockchainConfig = setup.blockchainConfig
    .withUpdatedForkBlocks(
      _.copy(
        homesteadBlockNumber = 0,
        eip150BlockNumber = 0,
        eip155BlockNumber = 0,
        eip160BlockNumber = 0,
        atlantisBlockNumber = 0,
        aghartaBlockNumber = 0,
        phoenixBlockNumber = 0,
        magnetoBlockNumber = 0,
        mystiqueBlockNumber = 0,
        spiralBlockNumber = 0
      )
    )
    .copy(networkType = NetworkType.ETC, chainId = ChainId(61))

  /** ETH rules through Berlin (block-number forks, legacy gas price), chain id 1. */
  private val ethConfig: BlockchainConfig = setup.blockchainConfig
    .withUpdatedForkBlocks(
      _.copy(
        homesteadBlockNumber = 0,
        eip150BlockNumber = 0,
        eip155BlockNumber = 0,
        eip160BlockNumber = 0,
        eip161BlockNumber = 0,
        byzantiumBlockNumber = 0,
        constantinopleBlockNumber = 0,
        petersburgBlockNumber = 0,
        istanbulBlockNumber = 0,
        berlinBlockNumber = 0
      )
    )
    .copy(networkType = NetworkType.ETH, chainId = ChainId(1))

  private val to = Address(0x42)

  private def legacy = LegacyTransaction(0, GasPrice(0), GasAmount(21_000), to, 0, ByteString.empty)

  private def execute(stx: SignedTransaction)(using cfg: BlockchainConfig) =
    preparator.executeTransactions(Seq(stx), setup.emptyWorld, header)(cfg)

  private def assertRejected(stx: SignedTransaction)(using cfg: BlockchainConfig): Unit =
    // Syntactically valid: r and s are in range, s <= n/2, v matches the chain.
    stx.signature.r should ((be > BigInt(0)).and(be < PointAtInfinitySignature.N))
    stx.signature.s should ((be > BigInt(0)).and(be <= PointAtInfinitySignature.N / 2))
    val sender = SignedTransaction.getSender(stx)
    val result = execute(stx)
    withClue(s"sender=$sender, executed=${result.map(r => s"${r.receipts.size} receipt(s)")}: ") {
      sender shouldBe None
      result match
        case Left(TxsExecutionError(_, _, reason)) => reason shouldBe TransactionSignatureError.toString
        case other                                 => fail(s"expected TransactionSignatureError, got $other")
    }

  "A transaction signed to recover the point at infinity" should "have no sender on ETC (legacy EIP-155, chain 61)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    given BlockchainConfig = etcConfig
    assertRejected(PointAtInfinitySignature.sign(legacy, Some(BigInt(61))))
  }

  it should "have no sender on ETC (unprotected legacy)" taggedAs (UnitTest, ConsensusTest) in {
    given BlockchainConfig = etcConfig
    assertRejected(PointAtInfinitySignature.sign(legacy, None))
  }

  it should "have no sender on ETH (legacy EIP-155, chain 1)" taggedAs (UnitTest, ConsensusTest) in {
    given BlockchainConfig = ethConfig
    assertRejected(PointAtInfinitySignature.sign(legacy, Some(BigInt(1))))
  }

  it should "have no sender on ETH (EIP-2930 access-list transaction)" taggedAs (UnitTest, ConsensusTest) in {
    given BlockchainConfig = ethConfig
    val tx = TransactionWithAccessList(1, 0, GasPrice(0), GasAmount(21_000), Some(to), 0, ByteString.empty, Nil)
    assertRejected(PointAtInfinitySignature.sign(tx, Some(BigInt(1))))
  }
