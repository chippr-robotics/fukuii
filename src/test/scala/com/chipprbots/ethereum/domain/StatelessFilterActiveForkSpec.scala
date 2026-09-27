package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** WI-14 (#1430): the txpool's stateless pre-filter admits a transaction under the rules of the fork ACTIVE AT THE
  * CHAIN HEAD, as go-ethereum's pool does, and a replay of a stored block no longer borrows the pool's rules for an
  * Amsterdam block.
  *
  * Before, the filter used the latest configured fork up to Osaka as its "now". It never selected Amsterdam, so a valid
  * 12,000-gas self-transfer (EIP-2780) and a call paying Amsterdam's lower floor were refused once the chain was past
  * Amsterdam. Where Amsterdam was scheduled but not active, it could not tell which floor applied and applied none.
  *
  * The chain is the Amsterdam fixture schedule: Prague 120, Osaka 180, Amsterdam 360.
  */
// scalastyle:off magic.number
class StatelessFilterActiveForkSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val recipient: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  private val atOsaka = Timestamp(300L)
  private val atAmsterdam = Timestamp(460L)

  /** 200 non-zero bytes, zero value, to another account. Osaka: intrinsic 24,200, EIP-7623 floor 21,000 + 800 x 10 =
    * 29,000. Amsterdam: intrinsic 12,000 + 3,000 + 3,200 = 18,200, EIP-7976 floor 15,000 + 12,800 = 27,800. So 28,000
    * gas is valid at Amsterdam and invalid at Osaka.
    */
  private def call200(gasLimit: BigInt): SignedTransaction =
    dynamicFeeTx(Some(recipient), value = 0, gasLimit = gasLimit, payload = nonZeros(200), config = amsterdamConfig)

  /** Osaka: 21,000. Amsterdam: 12,000 (EIP-2780 charges a self-transfer no recipient and no value cost). */
  private def selfTransfer(gasLimit: BigInt): SignedTransaction =
    dynamicFeeTx(Some(senderAddress), value = 1, gasLimit = gasLimit, config = amsterdamConfig)

  private def admitted(stxs: Seq[SignedTransaction], head: Timestamp): Seq[SignedTransaction] =
    SignedTransactionWithSender.getStatelessValidTransactions(stxs, head)(amsterdamConfig)

  // ── Pool admission ──────────────────────────────────────────────────────────

  "The txpool pre-filter" should "admit under Osaka's rules while the head is before Amsterdam" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    admitted(Seq(call200(28000)), atOsaka) shouldBe empty // below EIP-7623's 29,000
    admitted(Seq(call200(29000)), atOsaka) should have size 1
    admitted(Seq(selfTransfer(12000)), atOsaka) shouldBe empty // below 21,000
    admitted(Seq(selfTransfer(21000)), atOsaka) should have size 1
  }

  it should "admit under Amsterdam's rules once the head reaches it" taggedAs (UnitTest, ConsensusTest) in {
    admitted(Seq(call200(28000)), atAmsterdam) should have size 1
    admitted(Seq(call200(27799)), atAmsterdam) shouldBe empty // below EIP-7976's 27,800
    admitted(Seq(selfTransfer(12000)), atAmsterdam) should have size 1
    admitted(Seq(selfTransfer(11999)), atAmsterdam) shouldBe empty

    // And through sender recovery: the self-transfer is admitted with its real sender.
    SignedTransactionWithSender.getSignedTransactions(Seq(selfTransfer(12000)), atAmsterdam)(amsterdamConfig) shouldBe
      Seq(SignedTransactionWithSender(selfTransfer(12000), senderAddress))
  }

  it should "switch at the Amsterdam timestamp of the head, not at the latest configured fork" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    admitted(Seq(selfTransfer(12000)), Timestamp(359L)) shouldBe empty
    admitted(Seq(selfTransfer(12000)), Timestamp(360L)) should have size 1
    admitted(Seq(call200(28000)), Timestamp(359L)) shouldBe empty
    admitted(Seq(call200(28000)), Timestamp(360L)) should have size 1
  }

  it should "not change on a chain that schedules no Amsterdam" taggedAs (UnitTest, ConsensusTest) in {
    SignedTransactionWithSender.getStatelessValidTransactions(
      Seq(selfTransfer(12000), call200(28000)),
      atAmsterdam
    )(preAmsterdamConfig) shouldBe empty
  }

  // ── Replay of a stored block ────────────────────────────────────────────────

  private def of(header: BlockHeader, stxs: Seq[SignedTransaction], config: BlockchainConfig) =
    SignedTransactionWithSender.getSignedTransactionsOfBlock(header, stxs)(config).map(_.tx)

  "Replaying an Amsterdam block" should "keep every transaction it carries" taggedAs (UnitTest, ConsensusTest) in {
    // Both are valid at Amsterdam. The pool's pre-WI-14 rules (Osaka's, no floor) refused the self-transfer, and the
    // replay dropped it from the trace.
    val txs = Seq(selfTransfer(12000), call200(27800))
    of(amsterdamHeader(46, 460), txs, amsterdamConfig) shouldBe txs
  }

  it should "keep all of a large block's transactions, recovered in parallel, in order" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val txs = (0 until 20).map(n => dynamicFeeTx(Some(senderAddress), 1, 12000, nonce = n, config = amsterdamConfig))
    SignedTransactionWithSender.getSignedTransactionsOfBlock(amsterdamHeader(46, 460), txs)(amsterdamConfig) shouldBe
      txs.map(SignedTransactionWithSender(_, senderAddress))
  }

  "Replaying a block before Amsterdam" should "filter it exactly as every replay did before WI-14" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // The replay rules are the latest configured fork up to Osaka, and no floor where Amsterdam is scheduled: the
    // 28,000-gas call is kept (Osaka intrinsic 24,200) and the 12,000-gas self-transfer is dropped, whatever the head.
    of(preAmsterdamHeader(46, 300), Seq(call200(28000), selfTransfer(12000)), amsterdamConfig) shouldBe
      Seq(call200(28000))
  }

  "Replaying an ETC block" should "filter it exactly as before" taggedAs (UnitTest, ConsensusTest, OlympiaTest) in {
    val etc: BlockchainConfig =
      setup.blockchainConfig.copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
    // Olympia's config by construction, no floor rule: a 1,000-byte call at its 37,000 intrinsic cost is kept, and one
    // gas less is dropped.
    val keep = legacyTx(Some(recipient), value = 0, gasLimit = 37000, payload = nonZeros(1000), config = etc)
    val drop = legacyTx(Some(recipient), value = 0, gasLimit = 36999, payload = nonZeros(1000), nonce = 1, config = etc)
    val header = Fixtures.Blocks.ValidBlock.header.copy(extraFields = HefPostOlympia(BigInt(1)))
    of(header, Seq(keep, drop), etc) shouldBe Seq(keep)
  }
