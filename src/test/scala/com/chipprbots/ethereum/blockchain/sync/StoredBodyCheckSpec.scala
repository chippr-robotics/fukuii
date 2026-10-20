package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.testing.Tags.*

/** CQ-020-07: the SNAP pivot was stored as `BlockBody.empty` under a header committing to 16 withdrawals. */
class StoredBodyCheckSpec extends AnyFlatSpec with Matchers:
  import StoredBodyCheckSpec.*

  "BlockBody.committedEmpty" should "derive Some(Nil) withdrawals under a Shanghai header with the empty root" taggedAs
    UnitTest in {
      BlockBody.committedEmpty(emptyShanghai) shouldBe Some(BlockBody(Nil, Nil, withdrawals = Some(Nil)))
    }

  it should "derive withdrawals = None under a pre-Shanghai header (ETC shape unchanged)" taggedAs UnitTest in {
    BlockBody.committedEmpty(Fixtures.Blocks.Genesis.header) shouldBe Some(BlockBody(Nil, Nil, withdrawals = None))
  }

  it should "derive nothing when the header commits to withdrawals" taggedAs UnitTest in {
    BlockBody.committedEmpty(withdrawalsPivot) shouldBe None
  }

  "BlockBody.shapeMismatch" should "flag the Ephemery 137594 shape: no withdrawals field under a non-empty root" taggedAs
    UnitTest in {
      BlockBody.shapeMismatch(withdrawalsPivot, BlockBody.empty) shouldBe defined
    }

  it should "flag an empty withdrawals list under a non-empty root" taggedAs UnitTest in {
    BlockBody.shapeMismatch(withdrawalsPivot, BlockBody(Nil, Nil, Some(Nil))) shouldBe defined
  }

  it should "flag a 3-item body under a Shanghai header with the empty root (the genesis shape)" taggedAs UnitTest in {
    BlockBody.shapeMismatch(emptyShanghai, BlockBody(Nil, Nil)) shouldBe defined
  }

  it should "flag a withdrawals field under a pre-Shanghai header" taggedAs UnitTest in {
    BlockBody.shapeMismatch(Fixtures.Blocks.Genesis.header, BlockBody(Nil, Nil, Some(Nil))) shouldBe defined
  }

  it should "flag no transactions under a non-empty transactionsRoot" taggedAs UnitTest in {
    BlockBody.shapeMismatch(Fixtures.Blocks.Block3125369.header, BlockBody.empty) shouldBe defined
  }

  it should "accept the real body" taggedAs UnitTest in {
    BlockBody.shapeMismatch(withdrawalsPivot, withdrawalsBody) shouldBe None
    BlockBody.shapeMismatch(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body) shouldBe None
  }

  "StoredBodyCheck.decide" should "keep a stored body that validates (the body newPayload stored)" taggedAs UnitTest in {
    StoredBodyCheck.decide(withdrawalsPivot, Some(withdrawalsBody)) shouldBe StoredBodyCheck.Action.Keep
  }

  it should "remove a placeholder body under a header that commits to content" taggedAs UnitTest in {
    StoredBodyCheck.decide(withdrawalsPivot, Some(BlockBody.empty)) shouldBe a[StoredBodyCheck.Action.Remove]
  }

  it should "remove a body with the wrong withdrawals (shape fine, root wrong)" taggedAs UnitTest in {
    val wrong = BlockBody(Nil, Nil, Some(withdrawals.take(1)))
    StoredBodyCheck.decide(withdrawalsPivot, Some(wrong)) shouldBe a[StoredBodyCheck.Action.Remove]
  }

  it should "leave an absent body absent when it cannot be derived" taggedAs UnitTest in {
    StoredBodyCheck.decide(withdrawalsPivot, None) shouldBe StoredBodyCheck.Action.LeaveAbsent
  }

  it should "derive the body when the header commits to an empty one, replacing a wrong-shaped one" taggedAs
    UnitTest in {
      StoredBodyCheck.decide(emptyShanghai, None) shouldBe
        StoredBodyCheck.Action.Write(BlockBody(Nil, Nil, Some(Nil)), replacing = None)
      StoredBodyCheck.decide(emptyShanghai, Some(BlockBody.empty)) match
        case StoredBodyCheck.Action.Write(body, Some(_)) => body shouldBe BlockBody(Nil, Nil, Some(Nil))
        case other                                       => fail(s"expected a replacing Write, got $other")
    }

object StoredBodyCheckSpec:
  val withdrawals: Seq[Withdrawal] = (0 until 16).map { i =>
    Withdrawal(BigInt(1000 + i), BigInt(i), Address(ByteString(Array.fill(20)(i.toByte))), BigInt(32_000 + i))
  }
  val withdrawalsBody: BlockBody = BlockBody(Nil, Nil, Some(withdrawals))

  val emptyShanghai: BlockHeader = Fixtures.Blocks.Genesis.header.copy(
    number = BlockNumber(137_593),
    extraFields = HefPostShanghai(baseFee = BigInt(7), withdrawalsRoot = BlockHeader.EmptyMpt)
  )

  /** Same shape as Ephemery 137,594: no transactions, 16 withdrawals. */
  val withdrawalsPivot: BlockHeader = Fixtures.Blocks.Genesis.header.copy(
    number = BlockNumber(137_594),
    extraFields = HefPostShanghai(
      baseFee = BigInt(7),
      withdrawalsRoot = StdBlockValidator.computeWithdrawalsRoot(withdrawals)
    )
  )
