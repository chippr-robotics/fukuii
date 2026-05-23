package com.chipprbots.ethereum.ledger

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.SetCodeTransaction.addressToDelegation
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-7702: SetCode authorization execution behavioral tests.
  *
  * These tests drive [[BlockPreparator.executeTransaction]] with Type-4 [[SetCodeTransaction]]s and
  * verify the state changes produced by the authorization pipeline: delegation code, nonce increment,
  * EOA-only guard, wildcard chain ID, undelegate (zero target), nonce mismatch skip, and gas refund
  * for pre-existing authority accounts.
  */
// scalastyle:off magic.number
class SetCodeAuthorizationSpec extends AnyFlatSpec with Matchers {

  // Instantiate the full ledger infrastructure via TestSetup (provides prep, emptyWorld, etc.)
  private val setup = new TestSetup {}

  private val secureRandom = new SecureRandom()

  // Olympia-era config: olympia activates at block 1 so block 2 is post-fork.
  private val olympiaConfig: BlockchainConfig = setup.blockchainConfig.withUpdatedForkBlocks(
    _.copy(
      olympiaBlockNumber    = BigInt(1),
      homesteadBlockNumber  = BigInt(0),
      eip155BlockNumber     = BigInt(0)
    )
  )

  // Block header in the Olympia era (number > olympiaBlockNumber, baseFee set per EIP-1559).
  private val olympiaHeader: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
    number    = 2,
    gasLimit  = 30_000_000,
    gasUsed   = 0,
    extraFields = HefPostOlympia(BigInt(1_000_000_000)) // 1 Gwei baseFee
  )

  // A fixed target address that authorizations point to.
  private val targetAddress: Address = Address(0x42)

  // Sender key pair for signing the outer Type-4 transaction.
  private val senderKeyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
  private val senderAddress: Address = Address(senderKeyPair)
  private val senderBalance: UInt256 = UInt256(BigInt("1000000000000000000000")) // 1000 ETH

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Sign a SetCode authorization tuple. Returns a [[SetCodeAuthorization]] with a valid ECDSA
    * signature (r, s) and correct y-parity (0 or 1) for the given key pair.
    */
  private def signAuth(
      keyPair: AsymmetricCipherKeyPair,
      chainId: BigInt,
      target: Address,
      nonce: BigInt
  ): SetCodeAuthorization = {
    val sigHash = kec256(
      encode(
        PrefixedRLPEncodable(
          0x05,
          RLPList(
            toEncodeable(chainId),
            toEncodeable(target.toArray),
            toEncodeable(nonce)
          )
        )
      )
    )
    val sig = ECDSASignature.sign(sigHash, keyPair)
    val yParity: BigInt = if (sig.v == ECDSASignature.negativePointSign) BigInt(0) else BigInt(1)
    SetCodeAuthorization(chainId, target, nonce, yParity, sig.r, sig.s)
  }

  /** Build a world state with a rich sender plus any additional accounts. */
  private def buildWorld(
      extra: Map[Address, Account] = Map.empty,
      extraCode: Map[Address, ByteString] = Map.empty
  ): InMemoryWorldStateProxy = {
    val base = setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = senderBalance))
    val withAccounts = extra.foldLeft(base) { case (w, (addr, acc)) => w.saveAccount(addr, acc) }
    extraCode.foldLeft(withAccounts) { case (w, (addr, code)) => w.saveCode(addr, code) }
  }

  /** Build and sign a SetCodeTransaction with the given authorization list. */
  private def makeSetCodeTx(
      authList: List[SetCodeAuthorization],
      senderNonce: BigInt = 0
  ): SignedTransaction = {
    val tx = SetCodeTransaction(
      chainId             = olympiaConfig.chainId,
      nonce               = senderNonce,
      maxPriorityFeePerGas = BigInt(0),
      maxFeePerGas        = BigInt(2_000_000_000), // 2 Gwei
      gasLimit            = BigInt(500_000),
      receivingAddress    = Some(Address(1)),
      value               = BigInt(0),
      payload             = ByteString.empty,
      accessList          = Nil,
      authorizationList   = authList
    )
    SignedTransaction.sign(tx, senderKeyPair, Some(olympiaConfig.chainId))
  }

  /** Execute a signed tx and return the resulting world state. */
  private def execTx(
      stx: SignedTransaction,
      world: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy =
    setup.prep.executeTransaction(stx, senderAddress, olympiaHeader, world)(olympiaConfig).worldState

  // ── Tests ────────────────────────────────────────────────────────────────────

  "SetCodeAuthorization" should "set delegation code on authority account after valid authorization" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys  = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth      = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val result    = execTx(makeSetCodeTx(List(auth)), buildWorld())

    result.getCode(authority) shouldBe addressToDelegation(targetAddress)
  }

  it should "increment authority nonce after applying authorization" taggedAs (OlympiaTest, ConsensusTest) in {
    val authKeys  = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth      = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val result    = execTx(makeSetCodeTx(List(auth)), buildWorld())

    result.getAccount(authority).map(_.nonce.toBigInt) shouldBe Some(BigInt(1))
  }

  it should "skip authorization with wrong chain ID (not 0 and not current chain)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys  = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val wrongChainId = BigInt(999)
    val auth      = signAuth(authKeys, wrongChainId, targetAddress, nonce = 0)
    val result    = execTx(makeSetCodeTx(List(auth)), buildWorld())

    result.getCode(authority) shouldBe ByteString.empty
  }

  it should "accept chain ID = 0 as wildcard (any chain)" taggedAs (OlympiaTest, ConsensusTest) in {
    val authKeys  = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth      = signAuth(authKeys, BigInt(0), targetAddress, nonce = 0)
    val result    = execTx(makeSetCodeTx(List(auth)), buildWorld())

    result.getCode(authority) shouldBe addressToDelegation(targetAddress)
  }

  it should "clear delegation when target address is zero (EIP-7702 undelegate)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys      = generateKeyPair(secureRandom)
    val authority     = Address(authKeys)
    val zeroAddress   = Address(0L)
    val existingCode  = addressToDelegation(targetAddress)

    // Authority already has a delegation installed; auth targets zero → clear it.
    val world = buildWorld(
      extra = Map(authority -> Account(nonce = UInt256(0), balance = UInt256(0))),
      extraCode = Map(authority -> existingCode)
    )
    val auth   = signAuth(authKeys, olympiaConfig.chainId, zeroAddress, nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), world)

    result.getCode(authority) shouldBe ByteString.empty
  }

  it should "skip authorization if authority nonce does not match" taggedAs (OlympiaTest, ConsensusTest) in {
    val authKeys  = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    // Auth claims nonce=5 but the fresh authority account has nonce=0.
    val auth   = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 5)
    val result = execTx(makeSetCodeTx(List(auth)), buildWorld())

    result.getCode(authority) shouldBe ByteString.empty
  }

  it should "issue 12,500 gas refund per pre-existing authority account" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys  = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth      = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val stx       = makeSetCodeTx(List(auth))

    val worldWithExisting    = buildWorld(extra = Map(authority -> Account(nonce = UInt256(0), balance = UInt256(100))))
    val worldWithoutExisting = buildWorld()

    val gasWithExisting    = setup.prep.executeTransaction(stx, senderAddress, olympiaHeader, worldWithExisting)(olympiaConfig).gasUsed
    val gasWithoutExisting = setup.prep.executeTransaction(stx, senderAddress, olympiaHeader, worldWithoutExisting)(olympiaConfig).gasUsed

    // A pre-existing authority grants a 12,500 gas refund (capped by gasUsed / 5 per EIP-3529).
    gasWithExisting should be < gasWithoutExisting
    (gasWithoutExisting - gasWithExisting) should be <= BigInt(12_500)
  }

  it should "not set code on a non-EOA authority (contract code blocks delegation)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys     = generateKeyPair(secureRandom)
    val authority    = Address(authKeys)
    val contractCode = ByteString(0x60.toByte, 0x60.toByte, 0x60.toByte, 0x40.toByte) // not a delegation

    val world = buildWorld(
      extra = Map(authority -> Account(nonce = UInt256(0), balance = UInt256(0))),
      extraCode = Map(authority -> contractCode)
    )
    val auth   = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), world)

    // Authorization is skipped: existing non-delegation code must remain untouched.
    result.getCode(authority) shouldBe contractCode
  }
}
// scalastyle:on magic.number
