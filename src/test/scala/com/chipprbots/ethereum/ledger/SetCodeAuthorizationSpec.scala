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
  * Tests drive [[BlockPreparator.executeTransaction]] with Type-4 [[SetCodeTransaction]]s and verify the state changes
  * produced by the authorization pipeline: delegation code, nonce increment, EOA-only guard, wildcard chain ID,
  * undelegate (zero target), nonce mismatch skip, and gas refund for pre-existing authority accounts.
  */
// scalastyle:off magic.number
class SetCodeAuthorizationSpec extends AnyFlatSpec with Matchers {

  private val setup = new TestSetup {}
  private val secureRandom = new SecureRandom()

  private val olympiaConfig: BlockchainConfig = setup.blockchainConfig.withUpdatedForkBlocks(
    _.copy(olympiaBlockNumber = BigInt(1), homesteadBlockNumber = BigInt(0), eip155BlockNumber = BigInt(0))
  )

  private val olympiaHeader: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
    number = 2,
    gasLimit = 30_000_000,
    gasUsed = 0,
    extraFields = HefPostOlympia(BigInt(1_000_000_000))
  )

  private val targetAddress: Address = Address(0x42)
  private val senderKeyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
  private val senderAddress: Address = Address(senderKeyPair)
  private val senderBalance: UInt256 = UInt256(BigInt("1000000000000000000000"))

  // ── Helpers ──────────────────────────────────────────────────────────────

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
          RLPList(toEncodeable(chainId), toEncodeable(target.toArray), toEncodeable(nonce))
        )
      )
    )
    val sig = ECDSASignature.sign(sigHash, keyPair)
    val yParity = if (sig.v == ECDSASignature.negativePointSign) BigInt(0) else BigInt(1)
    SetCodeAuthorization(chainId, target, nonce, yParity, sig.r, sig.s)
  }

  private def buildWorld(
      extra: Map[Address, Account] = Map.empty,
      extraCode: Map[Address, ByteString] = Map.empty
  ): InMemoryWorldStateProxy = {
    val base = setup.emptyWorld.saveAccount(senderAddress, Account(nonce = UInt256(0), balance = senderBalance))
    val withAccts = extra.foldLeft(base) { case (w, (addr, acc)) => w.saveAccount(addr, acc) }
    extraCode.foldLeft(withAccts) { case (w, (addr, code)) => w.saveCode(addr, code) }
  }

  private def makeSetCodeTx(
      authList: List[SetCodeAuthorization],
      senderNonce: BigInt = 0
  ): SignedTransaction = {
    val tx = SetCodeTransaction(
      chainId = olympiaConfig.chainId,
      nonce = senderNonce,
      maxPriorityFeePerGas = BigInt(0),
      maxFeePerGas = BigInt(2_000_000_000),
      gasLimit = BigInt(500_000),
      receivingAddress = Some(Address(1)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil,
      authorizationList = authList
    )
    SignedTransaction.sign(tx, senderKeyPair, Some(olympiaConfig.chainId))
  }

  private def execTx(stx: SignedTransaction, world: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
    setup.prep.executeTransaction(stx, senderAddress, olympiaHeader, world)(olympiaConfig).worldState

  // ── Tests ────────────────────────────────────────────────────────────────

  "SetCodeAuthorization" should "set delegation code on authority account after valid authorization" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), buildWorld())
    result.getCode(authority) shouldBe addressToDelegation(targetAddress)
  }

  it should "increment authority nonce after applying authorization" taggedAs (OlympiaTest, ConsensusTest) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), buildWorld())
    result.getAccount(authority).map(_.nonce.toBigInt) shouldBe Some(BigInt(1))
  }

  it should "skip authorization with wrong chain ID (not 0 and not current chain)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth = signAuth(authKeys, BigInt(999), targetAddress, nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), buildWorld())
    result.getCode(authority) shouldBe ByteString.empty
  }

  it should "accept chain ID = 0 as wildcard (any chain)" taggedAs (OlympiaTest, ConsensusTest) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth = signAuth(authKeys, BigInt(0), targetAddress, nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), buildWorld())
    result.getCode(authority) shouldBe addressToDelegation(targetAddress)
  }

  it should "clear delegation when target address is zero (EIP-7702 undelegate)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val existingCode = addressToDelegation(targetAddress)
    val world = buildWorld(
      extra = Map(authority -> Account(nonce = UInt256(0), balance = UInt256(0))),
      extraCode = Map(authority -> existingCode)
    )
    val auth = signAuth(authKeys, olympiaConfig.chainId, Address(0L), nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), world)
    result.getCode(authority) shouldBe ByteString.empty
  }

  it should "skip authorization if authority nonce does not match" taggedAs (OlympiaTest, ConsensusTest) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 5)
    val result = execTx(makeSetCodeTx(List(auth)), buildWorld())
    result.getCode(authority) shouldBe ByteString.empty
  }

  it should "issue a gas refund for pre-existing authority accounts" taggedAs (OlympiaTest, ConsensusTest) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val auth = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val stx = makeSetCodeTx(List(auth))

    val gasWithExisting = setup.prep
      .executeTransaction(
        stx,
        senderAddress,
        olympiaHeader,
        buildWorld(extra = Map(authority -> Account(nonce = UInt256(0), balance = UInt256(100))))
      )(olympiaConfig)
      .gasUsed
    val gasWithoutExisting = setup.prep
      .executeTransaction(stx, senderAddress, olympiaHeader, buildWorld())(olympiaConfig)
      .gasUsed

    gasWithExisting should be < gasWithoutExisting
    (gasWithoutExisting - gasWithExisting) should be <= BigInt(12_500)
  }

  it should "not set code on a non-EOA authority (contract code blocks delegation)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val authKeys = generateKeyPair(secureRandom)
    val authority = Address(authKeys)
    val contractCode = ByteString(0x60.toByte, 0x60.toByte, 0x60.toByte, 0x40.toByte)
    val world = buildWorld(
      extra = Map(authority -> Account(nonce = UInt256(0), balance = UInt256(0))),
      extraCode = Map(authority -> contractCode)
    )
    val auth = signAuth(authKeys, olympiaConfig.chainId, targetAddress, nonce = 0)
    val result = execTx(makeSetCodeTx(List(auth)), world)
    result.getCode(authority) shouldBe contractCode
  }
}
// scalastyle:on magic.number
