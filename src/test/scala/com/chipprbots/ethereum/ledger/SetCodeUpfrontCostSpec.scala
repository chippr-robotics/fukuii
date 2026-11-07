package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.SetCodeTransaction.addressToDelegation
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-7702: the upfront balance check of a set-code transaction runs before its authorization list is processed
  * (go-ethereum `preCheck` -> `buyGas`, then `applyAuthorization`; execution-specs `check_transaction` before
  * `set_delegation`). A transaction rejected for its cost therefore applies none of its authorizations, even a
  * correctly signed one.
  *
  * The cases use one VALID authorization — signed by a fixed authority key for this chain id and the authority's
  * current nonce — and drive `BlockPreparator.executeTransactions` with the real validator, on ETH Osaka and Amsterdam.
  */
// scalastyle:off magic.number
class SetCodeUpfrontCostSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Recipient: Address = Address(0x42)
  private val Coinbase: Address = Address(0xc0ffee)
  private val DelegationTarget: Address = Address(0xde1e)

  /** A fixed authority key, so every run signs the same authorization. */
  private val authorityKeyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(Hex.decode("8a1f9a8f95be41cd7ccb6168179afb4504aefe388d1e14474d32c45c72ce7b7a"))
  private val authority: Address = Address(authorityKeyPair)

  private val TwoPow256: BigInt = BigInt(2).pow(256)
  private val MaxBalance: BigInt = UInt256.MaxValue.toBigInt
  private val TxGas: BigInt = BigInt(2).pow(20)

  private val preparator =
    new BlockPreparator(new VMImpl, StdSignedTransactionValidator, setup.blockchain, setup.blockchainReader)

  final private case class Chain(label: String, config: BlockchainConfig, header: BlockHeader):
    val baseFee: BigInt = header.baseFee.getOrElse(BigInt(0))

  private val chains: Seq[Chain] = Seq(
    Chain(
      "ETH Osaka (forTimestamp)",
      preAmsterdamConfig,
      preAmsterdamHeader(46, 180).copy(beneficiary = Coinbase.bytes)
    ),
    Chain("ETH Amsterdam (forTimestamp)", amsterdamConfig, amsterdamHeader(47, 360).copy(beneficiary = Coinbase.bytes))
  )

  /** EIP-7702 authorization: `sign(keccak(0x05 || rlp([chain_id, address, nonce])))`, y-parity in `v`. */
  private def validAuthorization(chainId: BigInt): SetCodeAuthorization =
    val sigHash = kec256(
      encode(
        PrefixedRLPEncodable(
          0x05,
          RLPList(toEncodeable(chainId), toEncodeable(DelegationTarget.toArray), toEncodeable(BigInt(0)))
        )
      )
    )
    val sig = ECDSASignature.sign(sigHash, authorityKeyPair)
    val yParity = if sig.v == ECDSASignature.negativePointSign then BigInt(0) else BigInt(1)
    SetCodeAuthorization(chainId, DelegationTarget, BigInt(0), yParity, sig.r, sig.s)

  private def setCodeTx(chain: Chain, maxFeePerGas: BigInt): SignedTransaction =
    val chainId = chain.config.chainId.value
    val tx = SetCodeTransaction(
      chainId = chainId,
      nonce = 0,
      maxPriorityFeePerGas = 0,
      maxFeePerGas = maxFeePerGas,
      gasLimit = GasAmount(TxGas),
      receivingAddress = Some(Recipient),
      value = 0,
      payload = ByteString.empty,
      accessList = Nil,
      authorizationList = List(validAuthorization(chainId))
    )
    SignedTransaction.sign(tx, senderKeyPair, Some(chainId))

  private def world(senderBalance: BigInt): InMemoryWorldStateProxy =
    setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(senderBalance)))
      .saveAccount(Recipient, Account(nonce = UInt256(0), balance = UInt256(1)))

  private val Watched: Seq[Address] = Seq(senderAddress, Recipient, Coinbase)

  private def balances(w: InMemoryWorldStateProxy): Seq[BigInt] =
    Watched.map(a => w.getAccount(a).map(_.balance.toBigInt).getOrElse(BigInt(0)))

  chains.foreach { chain =>
    chain.label should "apply a valid authorization when the cost is affordable (control)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val stx = setCodeTx(chain, maxFeePerGas = chain.baseFee * 2)
      val pre = world(BigInt(10).pow(18))
      val br = preparator
        .executeTransactions(Seq(stx), pre, chain.header)(using chain.config)
        .fold(err => fail(s"[${chain.label}] rejected: ${err.reason}"), identity)
      br.receipts.size shouldBe 1
      br.worldState.getCode(authority) shouldBe addressToDelegation(DelegationTarget)
      br.worldState.getAccount(authority).map(_.nonce.toBigInt) shouldBe Some(BigInt(1))
      val gasUsed = br.receipts.head.cumulativeGasUsed
      val effectiveGasPrice = Transaction.effectiveGasPrice(stx.tx, chain.header.baseFee)
      effectiveGasPrice shouldBe chain.baseFee
      balances(br.worldState).head shouldBe BigInt(10).pow(18) - gasUsed * effectiveGasPrice
    }

    it should "reject a transaction with a valid authorization whose cost exceeds 256 bits, applying no " +
      "authorization and leaving every balance unchanged" taggedAs (UnitTest, ConsensusTest) in {
        val stx = setCodeTx(chain, maxFeePerGas = TwoPow256 / TxGas)
        (stx.tx.gasLimit.value * stx.tx.gasPrice.value) shouldBe TwoPow256
        val pre = world(MaxBalance)
        preparator.executeTransactions(Seq(stx), pre, chain.header)(using chain.config) match
          case Left(err) =>
            err.reason should startWith("INSUFFICIENT_ACCOUNT_FUNDS")
            err.stateBeforeError.acumReceipts shouldBe empty
            balances(err.stateBeforeError.worldState) shouldBe balances(pre)
            err.stateBeforeError.worldState.getCode(authority) shouldBe ByteString.empty
            err.stateBeforeError.worldState.getAccount(authority) shouldBe None
          case Right(br) =>
            fail(
              s"[${chain.label}] expected INSUFFICIENT_ACCOUNT_FUNDS, but the block executed (gasUsed ${br.gasUsed}); " +
                s"authority code ${br.worldState.getCode(authority)}"
            )
      }

    it should "reject a transaction with a valid authorization whose cost is under 2^256 but above the sender's " +
      "balance, applying no authorization (control: no wrap)" taggedAs (UnitTest, ConsensusTest) in {
        val stx = setCodeTx(chain, maxFeePerGas = chain.baseFee * 2)
        val required = stx.tx.gasLimit.value * stx.tx.gasPrice.value
        val pre = world(required - 1)
        preparator.executeTransactions(Seq(stx), pre, chain.header)(using chain.config) match
          case Left(err) =>
            err.reason should startWith("INSUFFICIENT_ACCOUNT_FUNDS")
            err.stateBeforeError.worldState.getCode(authority) shouldBe ByteString.empty
          case Right(_) => fail(s"[${chain.label}] expected INSUFFICIENT_ACCOUNT_FUNDS, but the block executed")
      }
  }
