package com.chipprbots.ethereum.consensus.validators

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.MissingBlobGasFieldsError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.MissingWithdrawalsRootError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.PoSNonceError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.PoSOmmersError
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps

/** Unit coverage for [[PoSBlockHeaderValidator]] (EIP-3675 post-merge header rules).
  *
  * The validator skips PoW/difficulty-algorithm checks and instead enforces the post-merge invariants: difficulty == 0,
  * nonce == 8 zero bytes, empty ommers hash, withdrawalsRoot present once Shanghai (EIP-4895) is active, and blob-gas
  * fields present once Cancun (EIP-4844) is active.
  *
  * Most cases drive `validateHeaderOnly`, which runs `validateExtraData` + `validateGasUsed` + `validateEvenMore`
  * without parent-dependent baseFee/number/timestamp checks — isolating the post-merge-specific rules. The parentHash
  * case uses the `getBlockHeaderByHash` overload, which is the only path that resolves (and can reject) the parent.
  */
// scalastyle:off magic.number
class PoSBlockHeaderValidatorSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider:

  private val EmptyNonce: ByteString = ByteString(Array.fill[Byte](8)(0))
  private val ShanghaiTs: Long = 1_000L
  private val CancunTs: Long = 2_000L

  // Timestamps configured so that a header at unixTimestamp == HeaderTs has Shanghai + Cancun active.
  private val HeaderTs: Long = 3_000L

  private val baseExtraData: ByteString = ByteString("test".getBytes)
  private val withdrawalsRoot: ByteString = Fixtures.Blocks.ValidBlock.header.stateRoot.value
  private val beaconRoot: ByteString = Fixtures.Blocks.ValidBlock.header.parentHash.value

  implicit val config: BlockchainConfig = blockchainConfig.copy(
    forkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Some(ShanghaiTs),
      cancunTimestamp = Some(CancunTs)
    )
  )

  /** A Cancun-active post-merge header that satisfies every post-merge invariant. */
  private def validCancunHeader: BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      difficulty = Difficulty.Zero,
      nonce = EmptyNonce,
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(HeaderTs),
      extraData = baseExtraData,
      extraFields = HefPostCancun(
        baseFee = BigInt(1),
        withdrawalsRoot = withdrawalsRoot,
        blobGasUsed = BigInt(0),
        excessBlobGas = BigInt(0),
        parentBeaconBlockRoot = beaconRoot
      )
    )

  "PoSBlockHeaderValidator" when {

    "header satisfies all post-merge invariants" should {
      "accept a valid Cancun-active header" taggedAs (UnitTest, ConsensusTest) in {
        PoSBlockHeaderValidator.validateHeaderOnly(validCancunHeader) shouldBe Right(BlockHeaderValid)
      }
    }

    "the parent cannot be resolved" should {
      "fail with HeaderParentNotFoundError" taggedAs (UnitTest, ConsensusTest) in {
        // The getBlockHeaderByHash overload is the only path that resolves the parent;
        // returning None models a parentHash that matches no known header.
        val result = PoSBlockHeaderValidator.validate(validCancunHeader, _ => None)
        result shouldBe Left(HeaderParentNotFoundError)
      }
    }

    "mixHash (prevRandao) is non-zero" should {
      // Post-merge, mixHash carries prevRandao (EIP-4399) and is NOT constrained to zero.
      // This documents that the validator intentionally does not reject a non-zero mixHash.
      "accept the header (mixHash is not validated post-merge)" taggedAs (UnitTest, ConsensusTest) in {
        val withMixHash = validCancunHeader.copy(mixHash = BlockHash(ByteString(Array.fill[Byte](32)(1))))
        PoSBlockHeaderValidator.validateHeaderOnly(withMixHash) shouldBe Right(BlockHeaderValid)
      }
    }

    "nonce is non-zero" should {
      "fail with PoSNonceError" taggedAs (UnitTest, ConsensusTest) in {
        val badNonce = validCancunHeader.copy(nonce = ByteString(Array.fill[Byte](8)(7)))
        val result = PoSBlockHeaderValidator.validateHeaderOnly(badNonce)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get shouldBe a[PoSNonceError]
      }
    }

    "difficulty is non-zero" should {
      "fail with HeaderDifficultyError" taggedAs (UnitTest, ConsensusTest) in {
        val badDifficulty = validCancunHeader.copy(difficulty = Difficulty(BigInt(1)))
        PoSBlockHeaderValidator.validateHeaderOnly(badDifficulty) shouldBe Left(HeaderDifficultyError)
      }
    }

    "ommers hash is not the empty-list hash" should {
      "fail with PoSOmmersError" taggedAs (UnitTest, ConsensusTest) in {
        val badOmmers = validCancunHeader.copy(ommersHash = BlockHash(ByteString(Array.fill[Byte](32)(9))))
        PoSBlockHeaderValidator.validateHeaderOnly(badOmmers) shouldBe Left(PoSOmmersError)
      }
    }

    "Shanghai is active but withdrawalsRoot is absent" should {
      "fail with MissingWithdrawalsRootError" taggedAs (UnitTest, ConsensusTest) in {
        // HefEmpty carries no withdrawalsRoot; under an active-Shanghai timestamp this is invalid.
        val noWithdrawals = validCancunHeader.copy(
          unixTimestamp = Timestamp(ShanghaiTs), // Shanghai active, Cancun not yet
          extraFields = HefEmpty
        )
        PoSBlockHeaderValidator.validateHeaderOnly(noWithdrawals) shouldBe Left(MissingWithdrawalsRootError)
      }
    }

    "Cancun is active but blob-gas fields are absent" should {
      "fail with MissingBlobGasFieldsError" taggedAs (UnitTest, ConsensusTest) in {
        // HefPostShanghai carries withdrawalsRoot but no blobGasUsed/excessBlobGas/parentBeaconBlockRoot;
        // under an active-Cancun timestamp this is invalid.
        val noBlobFields = validCancunHeader.copy(
          extraFields = HefPostShanghai(baseFee = BigInt(1), withdrawalsRoot = withdrawalsRoot)
        )
        PoSBlockHeaderValidator.validateHeaderOnly(noBlobFields) shouldBe Left(MissingBlobGasFieldsError)
      }
    }
  }
// scalastyle:on magic.number
