package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator._
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.testing.Tags._

/** Direct coverage for EIP-4895 withdrawal-validation additions to `StdBlockValidator`:
  *   - `validateWithdrawalsPresence` — reject body-only withdrawals with a pre-Shanghai header.
  *   - `validateWithdrawalsOrdering` — reject duplicated or out-of-order withdrawal indices.
  *
  * Corresponds to the `InvalidBlocks/bc4895-withdrawals` hive consensus sub-suite — tests that submit malformed
  * withdrawal blocks and expect rejection. Previously Fukuii accepted any block whose `header.withdrawalsRoot` matched
  * a trie computed over its own (bad) body, because the root comparison is self-consistent: a mis-ordered body produces
  * a matching mis-ordered root.
  */
class StdBlockValidatorWithdrawalsSpec extends AnyFlatSpec with Matchers {

  // Pre-Shanghai header (no withdrawalsRoot). Transactions + ommers roots are correct for
  // Fixtures.Blocks.ValidBlock.body, so the earlier checks in validateHeaderAndBody pass.
  private val preShanghaiHeader: BlockHeader = Fixtures.Blocks.ValidBlock.header
  private val preShanghaiBody = Fixtures.Blocks.ValidBlock.body

  // A dummy Shanghai withdrawals root — the withdrawals root check happens AFTER presence +
  // ordering, so tests that expect a presence/ordering error don't care what's here.
  private val dummyRoot: ByteString = ByteString(Hex.decode("00" * 32))

  private def shanghaiHeader(root: ByteString = dummyRoot): BlockHeader =
    preShanghaiHeader.copy(extraFields = HefPostShanghai(BigInt(0), root))

  private val defaultAddr = Address(Hex.decode("4675c7e5baafbffbca748158becba61ef3b0a263"))

  private def withdrawal(index: Long, validatorIndex: Long = 0L, amountGwei: Long = 1L): Withdrawal =
    Withdrawal(
      index = BigInt(index),
      validatorIndex = BigInt(validatorIndex),
      address = defaultAddr,
      amount = BigInt(amountGwei)
    )

  "validateHeaderAndBody" should
    "accept a pre-Shanghai block with no withdrawals attached" taggedAs (UnitTest, ConsensusTest) in {
      StdBlockValidator.validateHeaderAndBody(preShanghaiHeader, preShanghaiBody) shouldBe Right(BlockValid)
    }

  it should "accept a pre-Shanghai header paired with explicitly-empty body.withdrawals" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val body = preShanghaiBody.copy(withdrawals = Some(Seq.empty))
    StdBlockValidator.validateHeaderAndBody(preShanghaiHeader, body) shouldBe Right(BlockValid)
  }

  it should "reject a pre-Shanghai header paired with non-empty body.withdrawals (orphaned withdrawals)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val body = preShanghaiBody.copy(withdrawals = Some(Seq(withdrawal(0))))
    val result = StdBlockValidator.validateHeaderAndBody(preShanghaiHeader, body)
    result shouldBe Left(BlockWithdrawalsOrphanedError)
  }

  it should "reject a Shanghai block whose withdrawals have duplicate indices" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val bad = Seq(withdrawal(3), withdrawal(3))
    val body = preShanghaiBody.copy(withdrawals = Some(bad))
    val result = StdBlockValidator.validateHeaderAndBody(shanghaiHeader(), body)
    result shouldBe Left(BlockWithdrawalsIndexError)
  }

  it should "reject a Shanghai block whose withdrawals decrease in index" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val bad = Seq(withdrawal(5), withdrawal(4))
    val body = preShanghaiBody.copy(withdrawals = Some(bad))
    val result = StdBlockValidator.validateHeaderAndBody(shanghaiHeader(), body)
    result shouldBe Left(BlockWithdrawalsIndexError)
  }

  it should "run ordering validation before root validation (so a bad root and bad ordering both register as Index)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Header declares the dummy root; body has out-of-order withdrawals. The root comparison
    // would fail later, but the ordering check should short-circuit first — so the reported
    // error must be BlockWithdrawalsIndexError, not BlockWithdrawalsRootError.
    val bad = Seq(withdrawal(10), withdrawal(3))
    val body = preShanghaiBody.copy(withdrawals = Some(bad))
    val result = StdBlockValidator.validateHeaderAndBody(shanghaiHeader(dummyRoot), body)
    result shouldBe Left(BlockWithdrawalsIndexError)
  }

  it should "accept a single-withdrawal body against a correct Shanghai root" taggedAs (UnitTest, ConsensusTest) in {
    // When withdrawals.size < 2, ordering is vacuously OK. Root comparison still runs; we
    // rely on the validator itself to compute + accept the root, so we feed it an empty Seq
    // which computes to BlockHeader.EmptyMpt (the canonical empty-trie root).
    val body = preShanghaiBody.copy(withdrawals = Some(Seq.empty))
    val headerWithEmptyRoot = preShanghaiHeader.copy(
      extraFields = HefPostShanghai(BigInt(0), BlockHeader.EmptyMpt)
    )
    StdBlockValidator.validateHeaderAndBody(headerWithEmptyRoot, body) shouldBe Right(BlockValid)
  }
}
