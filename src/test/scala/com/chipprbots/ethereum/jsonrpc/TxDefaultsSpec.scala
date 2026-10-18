package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The defaults engine of eth_sendTransaction / eth_signTransaction / eth_fillTransaction against go-ethereum
  * internal/ethapi transaction_args.go `setDefaults` / `setFeeDefaults` (#1629 S2).
  *
  * The fee cases port transaction_args_test.go `TestSetFeeDefaults` (base fee 10, suggested tip 42, so the default fee
  * cap is 2 * 10 + 42 = 0x3e); messages are compared verbatim. Configs are the shipped ETH mainnet and Mordor ones.
  */
class TxDefaultsSpec extends AnyFlatSpec with Matchers:
  import TxDefaultsSpec.*

  private val fortyTwo = BigInt(42)
  private val baseFee = BigInt(10)
  private val maxFee = baseFee * 2 + fortyTwo
  private val al = List(AccessListItem(Address(0xaa), List(StorageKey(BigInt(1)))))
  private val auths = List(SetCodeAuthorization(0, Address(0xbb), 0, 0, 0, 0))

  private def fees(args: TxArgs, head: BlockHeader, cfg: BlockchainConfig = eth): Either[String, TxArgs] =
    TxDefaults.setFeeDefaults(args, env(head, cfg)).left.map(_.message)

  // ── TestSetFeeDefaults: legacy ──────────────────────────────────────────────

  "setFeeDefaults" should "default gasPrice from the oracle before London" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(), preLondon) shouldBe Right(TxArgs(gasPrice = Some(GasPrice(fortyTwo))))
  }

  it should "keep a zero gasPrice before London" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(gasPrice = Some(GasPrice(0))), preLondon) shouldBe Right(TxArgs(gasPrice = Some(GasPrice(0))))
  }

  it should "keep an explicit gasPrice after London" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(gasPrice = Some(GasPrice(fortyTwo))), london) shouldBe Right(
      TxArgs(gasPrice = Some(GasPrice(fortyTwo)))
    )
  }

  it should "reject a zero gasPrice after London" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(gasPrice = Some(GasPrice(0))), london) shouldBe Left("gasPrice must be non-zero after london fork")
  }

  // ── access list ─────────────────────────────────────────────────────────────

  it should "price an access-list request with gasPrice before London, and type it 0x1 (fukuii: geth drops the list)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    fees(TxArgs(accessList = Some(al)), preLondon) shouldBe
      Right(TxArgs(accessList = Some(al), gasPrice = Some(GasPrice(fortyTwo)), txType = Some(TxArgs.AccessListType)))
  }

  it should "keep an access list with an explicit gasPrice" taggedAs (UnitTest, RPCTest) in {
    val in = TxArgs(accessList = Some(al), gasPrice = Some(GasPrice(fortyTwo)))
    fees(in, preLondon) shouldBe Right(in)
  }

  it should "give an access-list request 1559 fees after London" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(accessList = Some(al)), london) shouldBe
      Right(TxArgs(accessList = Some(al), maxFeePerGas = Some(maxFee), maxPriorityFeePerGas = Some(fortyTwo)))
    fees(TxArgs(accessList = Some(al), maxFeePerGas = Some(maxFee)), london) shouldBe
      Right(TxArgs(accessList = Some(al), maxFeePerGas = Some(maxFee), maxPriorityFeePerGas = Some(fortyTwo)))
  }

  // ── dynamic fee ─────────────────────────────────────────────────────────────

  it should "default tip and fee cap (tip + 2 * baseFee) after London" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(), london) shouldBe Right(TxArgs(maxFeePerGas = Some(maxFee), maxPriorityFeePerGas = Some(fortyTwo)))
    fees(TxArgs(maxFeePerGas = Some(maxFee)), london) shouldBe
      Right(TxArgs(maxFeePerGas = Some(maxFee), maxPriorityFeePerGas = Some(fortyTwo)))
  }

  it should "reject 1559 fee fields before London" taggedAs (UnitTest, RPCTest) in {
    val msg = "maxFeePerGas and maxPriorityFeePerGas are not valid before London is active"
    fees(TxArgs(maxFeePerGas = Some(maxFee)), preLondon) shouldBe Left(msg)
    fees(TxArgs(maxPriorityFeePerGas = Some(fortyTwo)), preLondon) shouldBe Left(msg)
  }

  it should "reject maxFeePerGas below the tip, given or defaulted" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(maxFeePerGas = Some(maxFee), maxPriorityFeePerGas = Some(1000)), london) shouldBe
      Left("maxFeePerGas (0x3e) < maxPriorityFeePerGas (0x3e8)")
    fees(TxArgs(maxFeePerGas = Some(7)), london) shouldBe Left("maxFeePerGas (0x7) < maxPriorityFeePerGas (0x2a)")
  }

  it should "reject a zero maxFeePerGas" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(maxFeePerGas = Some(0), maxPriorityFeePerGas = Some(0)), london) shouldBe
      Left("maxFeePerGas must be non-zero")
  }

  // ── misc ────────────────────────────────────────────────────────────────────

  it should "reject gasPrice together with 1559 fields or an authorization list" taggedAs (UnitTest, RPCTest) in {
    val both = "both gasPrice and (maxFeePerGas or maxPriorityFeePerGas) specified"
    val gp = Some(GasPrice(fortyTwo))
    fees(TxArgs(gasPrice = gp, maxFeePerGas = Some(maxFee), maxPriorityFeePerGas = Some(fortyTwo)), preLondon) shouldBe
      Left(both)
    fees(TxArgs(gasPrice = gp, maxPriorityFeePerGas = Some(fortyTwo)), preLondon) shouldBe Left(both)
    fees(TxArgs(gasPrice = gp, maxFeePerGas = Some(maxFee)), london) shouldBe Left(both)
    fees(TxArgs(gasPrice = gp, authorizationList = Some(auths)), london) shouldBe
      Left("both gasPrice and authorizationList specified")
  }

  // ── EIP-4844 ────────────────────────────────────────────────────────────────

  it should "default maxFeePerBlobGas to twice the head's blob base fee once Cancun is active" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val head = cancun
    val blobFee = BlobGasUtils.getBlobGasPrice(head.excessBlobGas.get, head.unixTimestamp, eth)
    blobFee should be > BigInt(1) // a non-trivial blob base fee, so 2x is visible
    fees(TxArgs(blobVersionedHashes = Some(Nil)), head) shouldBe Right(
      TxArgs(
        blobVersionedHashes = Some(Nil),
        maxFeePerBlobGas = Some(blobFee * 2),
        maxFeePerGas = Some(maxFee),
        maxPriorityFeePerGas = Some(fortyTwo)
      )
    )
    fees(
      TxArgs(blobVersionedHashes = Some(Nil), maxFeePerGas = Some(maxFee), maxPriorityFeePerGas = Some(fortyTwo)),
      head
    )
      .map(_.maxFeePerBlobGas) shouldBe Right(Some(blobFee * 2))
  }

  it should "reject a zero maxFeePerBlobGas" taggedAs (UnitTest, RPCTest) in {
    fees(TxArgs(maxFeePerBlobGas = Some(0)), cancun) shouldBe Left("maxFeePerBlobGas, if specified, must be non-zero")
  }

  // ── setDefaults: the rest of geth's order ───────────────────────────────────

  private val from = Address(0x1234)
  private val to = Address(0x42)

  private def defaults(
      args: TxArgs,
      head: BlockHeader = london,
      sidecar: TxDefaults.SidecarConfig = TxDefaults.SidecarConfig.NotAllowed,
      cfg: BlockchainConfig = eth
  ): Either[JsonRpcError, TxArgs] =
    TxDefaults.setDefaults(args, env(head, cfg), sidecar).unsafeRunSync()

  "setDefaults" should "fill value, the pool nonce, the estimated gas and the node's chain id" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val filled = defaults(TxArgs(from = Some(from), to = Some(to))).toOption.get
    filled.value shouldBe Some(BigInt(0))
    filled.nonce shouldBe Some(PoolNonce)
    filled.gas shouldBe Some(GasAmount(Estimate))
    filled.chainId shouldBe Some(eth.chainId)
    filled.toTransaction(eth.chainId, TxArgs.DynamicFeeType).toOption.get shouldBe
      TransactionWithDynamicFee(1, PoolNonce, fortyTwo, maxFee, GasAmount(Estimate), Some(to), 0, ByteString.empty, Nil)
  }

  it should "keep given nonce, gas and chain id, and reject a foreign chain id" taggedAs (UnitTest, RPCTest) in {
    val args = TxArgs(
      from = Some(from),
      to = Some(to),
      nonce = Some(9),
      gas = Some(GasAmount(50000)),
      chainId = Some(ChainId(1))
    )
    val filled = defaults(args).toOption.get
    (filled.nonce, filled.gas, filled.chainId) shouldBe (Some(BigInt(9)), Some(GasAmount(50000)), Some(ChainId(1)))
    defaults(args.copy(chainId = Some(ChainId(61)))).left.map(_.message) shouldBe
      Left("chainId does not match node's (have=61, want=1)")
  }

  it should "build a legacy transaction before London (ETC)" taggedAs (UnitTest, RPCTest) in {
    val filled = defaults(TxArgs(from = Some(from), to = Some(to)), mordorHead, cfg = mordor).toOption.get
    filled.toTransaction(mordor.chainId, TxArgs.DynamicFeeType).toOption.get shouldBe
      LegacyTransaction(PoolNonce, GasPrice(fortyTwo), GasAmount(Estimate), Some(to), 0, ByteString.empty)
  }

  it should "refuse blobs where the method does not allow them (eth_sendTransaction)" taggedAs (UnitTest, RPCTest) in {
    defaults(TxArgs(from = Some(from), to = Some(to), blobs = Some(List(ByteString(1)))), cancun).left
      .map(_.message) shouldBe
      Left(""""blobs" is not supported for this RPC method""")
  }

  it should "apply geth's blob count and contract-creation rules" taggedAs (UnitTest, RPCTest) in {
    val seven = List.fill(7)(BlobVersionedHash(ByteString(1) ++ ByteString(new Array[Byte](31))))
    defaults(TxArgs(from = Some(from), to = Some(to), blobVersionedHashes = Some(seven)), cancun).left
      .map(_.message) shouldBe
      Left("too many blobs in transaction (have=7, max=6)")
    defaults(TxArgs(from = Some(from))).left.map(_.message) shouldBe Left("contract creation without any data provided")
  }

  it should "propagate the estimator's error" taggedAs (UnitTest, RPCTest) in {
    val failing = env(london, eth).copy(estimateGas = _ => IO.pure(Left(JsonRpcError.LogicError("execution reverted"))))
    TxDefaults
      .setDefaults(TxArgs(from = Some(from), to = Some(to)), failing, TxDefaults.SidecarConfig.NotAllowed)
      .unsafeRunSync() shouldBe Left(JsonRpcError.LogicError("execution reverted"))
  }

  // ── type gate (until #1629 S0's TxTypeGate) ─────────────────────────────────

  "checkTypeActive" should "refuse 0x2, 0x3 and 0x4 on Mordor and allow 0x0 and 0x1" taggedAs (UnitTest, RPCTest) in {
    given BlockchainConfig = mordor
    def gate(args: TxArgs) = TxDefaults.checkTypeActive(args, mordorHead).left.map(_.message)
    gate(TxArgs()) shouldBe Right(())
    gate(TxArgs(accessList = Some(al))) shouldBe Right(())
    gate(TxArgs(txType = Some(TxArgs.LegacyType))) shouldBe Right(())
    gate(TxArgs(maxFeePerGas = Some(1))) shouldBe
      Left("transaction type not supported: type 2 rejected, pool not yet in Olympia")
    gate(TxArgs(blobVersionedHashes = Some(Nil))) shouldBe
      Left("transaction type not supported: type 3 rejected, pool not yet in Cancun")
    gate(TxArgs(authorizationList = Some(auths))) shouldBe
      Left("transaction type not supported: type 4 rejected, pool not yet in Olympia")
    gate(TxArgs(txType = Some(TxArgs.DynamicFeeType))) shouldBe
      Left("transaction type not supported: type 2 rejected, pool not yet in Olympia")
  }

  it should "allow every type on ETH mainnet after Osaka" taggedAs (UnitTest, RPCTest) in {
    given BlockchainConfig = eth
    for t <- TxArgs.SupportedTypes do TxDefaults.checkTypeActive(TxArgs(txType = Some(t)), cancun) shouldBe Right(())
  }

object TxDefaultsSpec:
  val PoolNonce: BigInt = 7
  val Estimate: BigInt = 21000

  private lazy val shippedRoot = ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()
  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$name"))

  lazy val eth: BlockchainConfig = shipped("eth")
  lazy val mordor: BlockchainConfig = shipped("mordor")

  private val base = Fixtures.Blocks.Block3125369.header

  /** ETH mainnet before London (Berlin era). */
  lazy val preLondon: BlockHeader = base.copy(number = BlockNumber(12300000), extraFields = HefEmpty)

  /** ETH mainnet after London, base fee 10 (geth's backendMock). */
  lazy val london: BlockHeader = base.copy(number = BlockNumber(13000000), extraFields = HefPostOlympia(10))

  /** ETH mainnet after Osaka, base fee 10, with blob excess gas. */
  lazy val cancun: BlockHeader = base.copy(
    number = BlockNumber(23000000),
    unixTimestamp = Timestamp(1764798551L + 12),
    extraFields = HefPostPrague(
      10,
      ByteString(new Array[Byte](32)),
      0,
      BigInt(400000000),
      ByteString(new Array[Byte](32)),
      ByteString(new Array[Byte](32))
    )
  )

  /** Mordor today: Magneto active, Olympia unscheduled. */
  lazy val mordorHead: BlockHeader = base.copy(number = BlockNumber(10000000), extraFields = HefEmpty)

  def env(head: BlockHeader, cfg: BlockchainConfig): TxDefaults.Env =
    TxDefaults.Env(
      head = head,
      config = cfg,
      suggestGasPrice = () => BigInt(42),
      suggestGasTipCap = () => BigInt(42),
      poolNonce = _ => IO.pure(PoolNonce),
      estimateGas = _ => IO.pure(Right(Estimate))
    )
