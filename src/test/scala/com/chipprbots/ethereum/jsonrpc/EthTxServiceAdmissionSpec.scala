package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.KzgCellProofs
import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.jsonrpc.EthTxService.SendRawTransactionRequest
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.SubmitLocalTransaction
import com.chipprbots.ethereum.transactions.TxAdmission
import com.chipprbots.ethereum.transactions.TxTypeGate
import com.chipprbots.ethereum.transactions.testing.AcceptLocalSubmissions
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Hex

/** eth_sendRawTransaction admission (#1629 S0): a locally submitted transaction meets the pool's rules for the chain
  * head, the rules go-ethereum's `txpool.ValidateTransaction` / `ValidateTransactionWithState` apply, and is refused
  * with geth's message before it reaches the pool. A refused transaction is never handed to the pool, so it is never
  * announced to peers.
  *
  * ETH runs on the shipped Sepolia and mainnet configs, with the head placed before or after each fork; ETC on the
  * shipped Mordor config, where Olympia is unscheduled, so only types 0x0 and 0x1 are admissible.
  */
class EthTxServiceAdmissionSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with SecureRandomBuilder:

  implicit val runtime: IORuntime = IORuntime.global

  // the blob sidecar rules verify KZG proofs (#1629 S0b)
  KzgTestSetup.ensureLoaded()

  private val shipped = ConfigFactory.load()
  private def chain(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shipped.getConfig(s"fukuii.blockchains.$name"))

  private val sepolia: BlockchainConfig = chain("sepolia")
  private val ethMainnet: BlockchainConfig = chain("eth")
  private val mordor: BlockchainConfig = chain("mordor")

  private def ts(cfg: BlockchainConfig, pick: com.chipprbots.ethereum.utils.ForkTimestamps => Option[Long]): Long =
    pick(cfg.forkTimestamps).getOrElse(fail("fork timestamp not configured"))

  // Sepolia heads: London and Berlin from genesis; Cancun, Prague and Osaka by timestamp.
  private val sepoliaShanghai = ts(sepolia, _.shanghaiTimestamp) + 1
  private val sepoliaCancun = ts(sepolia, _.cancunTimestamp) + 1
  private val sepoliaPrague = ts(sepolia, _.pragueTimestamp) + 1
  private val sepoliaOsaka = ts(sepolia, _.osakaTimestamp) + 1

  private val OneGwei = BigInt(1_000_000_000)
  private val Rich = BigInt(10).pow(24)
  private val To = Some(Address(0x42))

  /** EthTxService over a head and the accounts in that head's state; the pool is a probe. */
  class Fixture(
      val cfg: BlockchainConfig,
      headNumber: BigInt,
      headTimestamp: Long,
      baseFee: Option[BigInt] = Some(OneGwei),
      headGasLimit: BigInt = 30_000_000,
      noHead: Boolean = false,
      stateMissing: Boolean = false,
      rpc: RpcTxSubmission = RpcTxSubmission.Default
  ):
    val pool: TestProbe[PendingTransactionsManager.Command] = createTestProbe[PendingTransactionsManager.Command]()
    var accounts: Map[Address, Account] = Map.empty

    val head: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(headNumber),
      unixTimestamp = Timestamp(headTimestamp),
      gasLimit = GasAmount(headGasLimit),
      extraFields = baseFee.map(HefPostOlympia(_)).getOrElse(HeaderExtraFields.HefEmpty)
    )

    private val reader: BlockchainReader = new BlockchainReader(null, null, null, null, null, null, null, null):
      override def getBestBlockHeader: Option[BlockHeader] = Option.unless(noHead)(head)
      override def getAccountAtStateRoot(
          @scala.annotation.unused stateRoot: ByteString,
          address: Address
      ): Option[Account] =
        if stateMissing then throw new MissingNodeException(ByteString(1, 2, 3))
        else accounts.get(address)

    val service: EthTxService =
      new EthTxService(
        null,
        reader,
        null,
        spawn(AcceptLocalSubmissions.forwardingTo(pool.ref)),
        5.seconds,
        null,
        system.scheduler,
        rpc
      )(using
        cfg
      )

    def fund(keyPair: AsymmetricCipherKeyPair, balance: BigInt = Rich, nonce: BigInt = 0): Unit =
      accounts += Address(keyPair) -> Account(nonce = UInt256(nonce), balance = UInt256(balance))

    def submit(stx: SignedTransaction): Either[JsonRpcError, ByteString] = submitRaw(stx.toBytes)

    def submitRaw(raw: Array[Byte]): Either[JsonRpcError, ByteString] =
      service.sendRawTransaction(SendRawTransactionRequest(ByteString(raw))).unsafeRunSync().map(_.transactionHash)

    /** Admitted: the hash comes back and the tx is handed to the pool. */
    def admitted(stx: SignedTransaction): Unit =
      submit(stx) shouldBe Right(stx.hash.value)
      pool.expectMessageType[SubmitLocalTransaction].signedTransaction shouldBe stx

    /** Refused with go-ethereum's -32000 `message`, and never handed to the pool (so never gossiped). */
    def refused(stx: SignedTransaction, message: String): Unit =
      refusedRaw(stx.toBytes, message)

    def refusedRaw(raw: Array[Byte], message: String): Unit =
      submitRaw(raw) shouldBe Left(JsonRpcError.LogicError(message))
      pool.expectNoMessage(100.millis)

  private def newKey(): AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)

  private def legacy(
      gasPrice: BigInt = 2 * OneGwei,
      gasLimit: BigInt = 21_000,
      nonce: BigInt = 0,
      payload: ByteString = ByteString.empty,
      to: Option[Address] = To
  ): LegacyTransaction =
    LegacyTransaction(nonce, GasPrice(gasPrice), GasAmount(gasLimit), to, BigInt(1), payload)

  private def accessListTx(chainId: BigInt, gasLimit: BigInt = 30_000): TransactionWithAccessList =
    TransactionWithAccessList(
      chainId,
      nonce = 0,
      gasPrice = GasPrice(2 * OneGwei),
      gasLimit = GasAmount(gasLimit),
      receivingAddress = To,
      value = 1,
      payload = ByteString.empty,
      accessList = List(AccessListItem(Address(0x43), List(StorageKey(BigInt(1)))))
    )

  private def dynamicFeeTx(
      chainId: BigInt,
      maxFee: BigInt = 2 * OneGwei,
      tip: BigInt = OneGwei,
      gasLimit: BigInt = 21_000,
      nonce: BigInt = 0,
      payload: ByteString = ByteString.empty,
      to: Option[Address] = To
  ): TransactionWithDynamicFee =
    TransactionWithDynamicFee(chainId, nonce, tip, maxFee, GasAmount(gasLimit), to, BigInt(1), payload, Nil)

  private def setCodeTx(chainId: BigInt, auths: Int = 1): SetCodeTransaction =
    SetCodeTransaction(
      chainId,
      nonce = 0,
      maxPriorityFeePerGas = OneGwei,
      maxFeePerGas = 2 * OneGwei,
      gasLimit = GasAmount(100_000),
      receivingAddress = To,
      value = 0,
      payload = ByteString.empty,
      accessList = Nil,
      authorizationList = List.fill(auths)(SetCodeAuthorization(chainId, Address(0x44), 0, 0, 1, 1))
    )

  private def blobTx(chainId: BigInt): BlobTransaction =
    BlobTransaction(
      chainId,
      nonce = 0,
      maxPriorityFeePerGas = OneGwei,
      maxFeePerGas = 2 * OneGwei,
      gasLimit = GasAmount(21_000),
      receivingAddress = To,
      value = 0,
      payload = ByteString.empty,
      accessList = Nil,
      maxFeePerBlobGas = OneGwei,
      blobVersionedHashes = List(BlobVersionedHash(ByteString(Array(1.toByte) ++ Array.fill[Byte](31)(0))))
    )

  private def sign(tx: Transaction, keyPair: AsymmetricCipherKeyPair, chainId: Option[BigInt]): SignedTransaction =
    SignedTransaction.sign(tx, keyPair, chainId)

  // ---------------------------------------------------------------------------------------------------------------
  // ETH: accept path, per type, at a head where each type is active
  // ---------------------------------------------------------------------------------------------------------------

  "eth_sendRawTransaction on an ETH chain" should "admit every type active at the head, as before" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val (k0, k1, k2, k4) = (newKey(), newKey(), newKey(), newKey())
    Seq(k0, k1, k2, k4).foreach(f.fund(_))
    f.admitted(sign(legacy(), k0, Some(id)))
    f.admitted(sign(accessListTx(id), k1, Some(id)))
    f.admitted(sign(dynamicFeeTx(id), k2, Some(id)))
    f.admitted(sign(setCodeTx(id), k4, Some(id)))
  }

  it should "admit a blob tx sent with its sidecar at a Cancun head" taggedAs (UnitTest, RPCTest) in {
    // The execution-apis send-blob-tx vector (EIP-7594 wrapper). Its chain is the rpc-compat genesis's, 0x0c72dd9d5e883e.
    val wrapper = readResource("/eip7594-blob-tx-wrapper.rlp")
    val vectorChain = sepolia.copy(chainId = ChainId(BigInt("0c72dd9d5e883e", 16)))
    val f = new Fixture(vectorChain, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val stx = wrapper.toSignedTransactionWithSidecar._1
    f.accounts += SignedTransaction.getSender(stx)(using vectorChain).get -> Account(balance = UInt256(Rich))
    f.submitRaw(wrapper).map(h => Hex.toHexString(h.toArray)) shouldBe
      Right("05d85f6a761cac82cfdf06dd168952838ac452b10641aabccdfdad46e03d2f0b")
    f.pool.expectMessageType[SubmitLocalTransaction].blobTxRawBytes.map(_.toSeq) shouldBe Some(wrapper.toSeq)

    // The same tx without its sidecar (the wrapper's first item, re-prefixed): go-ethereum's validateBlobSidecar.
    val withoutSidecar = rlp.rawDecode(wrapper.tail) match
      case w: rlp.RLPList => Array(0x03.toByte) ++ rlp.encode(w.items.head)
      case other          => fail(s"not a blob tx wrapper: $other")
    f.refusedRaw(withoutSidecar, "missing sidecar in blob transaction")
  }

  it should "admit the execution-apis eth_sendRawTransaction vectors hive rpc-compat sends" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // tests/eth_sendRawTransaction/send-{legacy,access-list,dynamic-fee,dynamic-fee-access-list}-transaction.io:
    // raw tx -> expected hash. Signed for the rpc-compat genesis chain, 0x0c72dd9d5e883e; the legacy one is EIP-155.
    val vectors = Seq(
      "f86b8083bc78d58261a894aa000000000000000000000000000000000000000a8255448718e5bb3abd10a0a0087bf8fecb6644f97c30" +
        "1410660b7361393b196642ccc452300f109d0b597350a003c8a1e82c8070d1a6c18ab99f261b01807d78c1d8f31d52f31ac384777247" +
        "f6" ->
        "53d46b560f40eaf585f73cbe70e13442b1a37772470b1ae29cd5a8d36d55dd09",
      "01f8cb870c72dd9d5e883e0283bc7ac883015f90947dcd17433742f4c0ca53122ab541d0ba67fc27df8083010203f85bf859947dcd17" +
        "433742f4c0ca53122ab541d0ba67fc27dff842a00000000000000000000000000000000000000000000000000000000000000000a001" +
        "0000000000000000000000000000000000000000000000000000000000000080a003fd60a2e5afe6ef117fd269eb4b809b314e060e40" +
        "38484b74de0f7029713307a0340201015023da756afa83b3ecbdc1f3068b45072d76dc612760dab591b69876" ->
        "3f3211fc89376be2c9f9a520e47344966b673307db0cafcd8e56e18cad132980",
      "02f891870c72dd9d5e883e018201f483bc7ac882ea60802ab73d602d80600a3d3981f3363d3d373d3d3d363d734d11c446473105a02b" +
        "5c1ab9ebe9b03f33902a295af43d82803e903d91602b57fd5bf3c080a07e04c33a4d6ab36db614d796ae19f446fcb10b132f1faacfc9" +
        "520a85dff36803a070c682a0b34c1daed8cfdd3252855c05e2ba9a6c0b335bfef65339947c458b5a" ->
        "059ebfaca990412c79920dd88df9960281567857d93e7e4147209c23f8cf97ba",
      "02f8cf870c72dd9d5e883e038201f483bc7ac883013880947dcd17433742f4c0ca53122ab541d0ba67fc27df808401020304f85bf859" +
        "947dcd17433742f4c0ca53122ab541d0ba67fc27dff842a0000000000000000000000000000000000000000000000000000000000000" +
        "0000a0010000000000000000000000000000000000000000000000000000000000000001a0ff922d11e0e5b698424eba59b0d8affd0b" +
        "32f0f035953cef16d588385694f1dba04cba0c5d811a1f14b8a38dc351b42ba980ad9b914dbec720813f6d44ef357131" ->
        "d8777dfdb7a721bf86aa42870164fa01b1a7c9cfb02feb3421b9d688f52d8b28"
    )
    val vectorChain = sepolia.copy(chainId = ChainId(BigInt("0c72dd9d5e883e", 16)))
    val f = new Fixture(vectorChain, 9_000_000, sepoliaPrague)
    vectors.foreach { case (rawHex, expectedHash) =>
      val raw = Hex.decode(rawHex)
      val stx = raw.toSignedTransaction
      val sender = SignedTransaction.getSender(stx)(using vectorChain).getOrElse(fail(s"no sender for $expectedHash"))
      f.accounts += sender -> Account(nonce = UInt256(stx.tx.nonce), balance = UInt256(Rich))
      f.submitRaw(raw).map(h => Hex.toHexString(h.toArray)) shouldBe Right(expectedHash)
      f.pool.expectMessageType[SubmitLocalTransaction].signedTransaction shouldBe stx
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // ETH: type activation at the head (timestamp forks on Sepolia, block forks on mainnet)
  // ---------------------------------------------------------------------------------------------------------------

  it should "refuse a type not yet active at the head: 0x3 before Cancun, 0x4 before Prague" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val id = sepolia.chainId.value
    val beforeCancun = new Fixture(sepolia, 9_000_000, sepoliaShanghai)
    val k = newKey()
    beforeCancun.fund(k)
    beforeCancun.refused(
      sign(blobTx(id), k, Some(id)),
      "transaction type not supported: type 3 rejected, pool not yet in Cancun"
    )
    beforeCancun.refused(
      sign(setCodeTx(id), k, Some(id)),
      "transaction type not supported: type 4 rejected, pool not yet in Prague"
    )

    val beforePrague = new Fixture(sepolia, 9_000_000, sepoliaCancun)
    beforePrague.fund(k)
    beforePrague.refused(
      sign(setCodeTx(id), k, Some(id)),
      "transaction type not supported: type 4 rejected, pool not yet in Prague"
    )
  }

  it should "refuse 0x2 before London and 0x1 before Berlin (mainnet block forks)" taggedAs (UnitTest, RPCTest) in {
    val id = ethMainnet.chainId.value
    val berlin = ethMainnet.forkBlockNumbers.berlinBlockNumber
    val london = ethMainnet.forkBlockNumbers.olympiaBlockNumber
    val k = newKey()

    val beforeLondon = new Fixture(ethMainnet, london - 1, 1_620_000_000L, baseFee = None)
    beforeLondon.fund(k)
    beforeLondon.refused(
      sign(dynamicFeeTx(id), k, Some(id)),
      "transaction type not supported: type 2 rejected, pool not yet in London"
    )
    beforeLondon.admitted(sign(accessListTx(id), k, Some(id)))

    val beforeBerlin = new Fixture(ethMainnet, berlin - 1, 1_610_000_000L, baseFee = None)
    beforeBerlin.fund(k)
    beforeBerlin.refused(
      sign(accessListTx(id), k, Some(id)),
      "transaction type not supported: type 1 rejected, pool not yet in Berlin"
    )
    beforeBerlin.admitted(sign(legacy(), k, Some(id)))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // ETH: chain ID and signature
  // ---------------------------------------------------------------------------------------------------------------

  it should "refuse a typed tx signed for another chain, and not hand it to the pool" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val k = newKey()
    f.fund(k)
    f.refused(
      sign(dynamicFeeTx(chainId = 1), k, Some(1)),
      s"invalid sender: invalid chain id for signer: have 1 want ${sepolia.chainId.value}"
    )
    f.refused(
      sign(accessListTx(chainId = 1), k, Some(1)),
      s"invalid sender: invalid chain id for signer: have 1 want ${sepolia.chainId.value}"
    )
  }

  it should "refuse an EIP-155 legacy tx signed for another chain with the same error" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val k = newKey()
    f.fund(k)
    f.refused(
      sign(legacy(), k, Some(1)),
      s"invalid sender: invalid chain id for signer: have 1 want ${sepolia.chainId.value}"
    )
  }

  it should "refuse a tx whose signature recovers no sender" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val unrecoverable = SignedTransaction(legacy(), ECDSASignature(r = BigInt(1), s = BigInt(1), v = BigInt(30)))
    f.refused(unrecoverable, "invalid sender: invalid transaction v, r, s values")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // ETH: gas, fees, initcode
  // ---------------------------------------------------------------------------------------------------------------

  it should "refuse a tx below its intrinsic gas" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refused(
      sign(dynamicFeeTx(id, gasLimit = 20_000), k, Some(id)),
      "intrinsic gas too low: gas 20000, minimum needed 21000"
    )
  }

  it should "refuse a tx below the EIP-7623 calldata floor from Prague, and admit it before" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val id = sepolia.chainId.value
    // 100 non-zero bytes: intrinsic 21,000 + 16 * 100 = 22,600; floor 21,000 + 10 * 4 * 100 = 25,000.
    val payload = ByteString(Array.fill[Byte](100)(1))
    val k = newKey()
    val atPrague = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    atPrague.fund(k)
    atPrague.refused(
      sign(dynamicFeeTx(id, gasLimit = 23_000, payload = payload), k, Some(id)),
      "insufficient gas for floor data gas cost: gas 23000, minimum needed 25000"
    )
    val atCancun = new Fixture(sepolia, 9_000_000, sepoliaCancun)
    atCancun.fund(k)
    atCancun.admitted(sign(dynamicFeeTx(id, gasLimit = 23_000, payload = payload), k, Some(id)))
  }

  it should "refuse a tip above the fee cap" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refused(
      sign(dynamicFeeTx(id, maxFee = OneGwei, tip = 2 * OneGwei), k, Some(id)),
      "max priority fee per gas higher than max fee per gas"
    )
  }

  it should "refuse a tip below the pool's minimum" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refused(
      sign(dynamicFeeTx(id, tip = sepolia.minTip - 1), k, Some(id)),
      s"transaction gas price below minimum: gas tip cap ${sepolia.minTip - 1}, minimum needed ${sepolia.minTip}"
    )
  }

  it should "refuse a gas limit above EIP-7825's cap from Osaka, and above the head's gas limit" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val id = sepolia.chainId.value
    val k = newKey()
    val atOsaka = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    atOsaka.fund(k)
    atOsaka.refused(
      sign(dynamicFeeTx(id, gasLimit = 16_777_217), k, Some(id)),
      "transaction gas limit too high (cap: 16777216, tx: 16777217)"
    )
    val atPrague = new Fixture(sepolia, 9_000_000, sepoliaPrague, headGasLimit = 30_000_000)
    atPrague.fund(k)
    atPrague.refused(sign(dynamicFeeTx(id, gasLimit = 30_000_001), k, Some(id)), "exceeds block gas limit")
  }

  it should "keep -32602 INITCODE_SIZE_EXCEEDED for initcode over EIP-3860's limit" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    val tooLarge = sign(
      dynamicFeeTx(id, gasLimit = 10_000_000, payload = ByteString(Array.fill[Byte](49_153)(0)), to = None),
      k,
      Some(id)
    )
    f.submit(tooLarge) shouldBe Left(
      JsonRpcError.InvalidParams("INITCODE_SIZE_EXCEEDED: initcode size 49153 exceeds max 49152")
    )
    f.pool.expectNoMessage(100.millis)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // ETH: the sender's account at the head
  // ---------------------------------------------------------------------------------------------------------------

  it should "refuse a nonce below the account's, and admit one at or above it" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k, nonce = 5)
    f.refused(sign(dynamicFeeTx(id, nonce = 3), k, Some(id)), "nonce too low: next nonce 5, tx nonce 3")
    f.admitted(sign(dynamicFeeTx(id, nonce = 5), k, Some(id)))
    f.admitted(sign(dynamicFeeTx(id, nonce = 7), k, Some(id)))
  }

  it should "refuse a tx the sender cannot pay for, counting gas * fee cap + value" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    val cost = BigInt(21_000) * 2 * OneGwei + 1
    f.fund(k, balance = cost - 1)
    f.refused(
      sign(dynamicFeeTx(id), k, Some(id)),
      s"insufficient funds for gas * price + value: balance ${cost - 1}, tx cost $cost, overshot 1"
    )
    // An account the head's state does not hold has nothing.
    val stranger = newKey()
    f.refused(
      sign(dynamicFeeTx(id), stranger, Some(id)),
      s"insufficient funds for gas * price + value: balance 0, tx cost $cost, overshot $cost"
    )
  }

  it should "report, not admit, when there is no head or the head's state is missing" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val id = sepolia.chainId.value
    val k = newKey()
    val noHead = new Fixture(sepolia, 0, 0, noHead = true)
    noHead.refused(sign(dynamicFeeTx(id), k, Some(id)), "cannot validate transaction: no chain head")

    val missing = new Fixture(sepolia, 9_000_000, sepoliaPrague, stateMissing = true)
    val res = missing.submit(sign(dynamicFeeTx(id), k, Some(id)))
    res.left.map(_.code) shouldBe Left(-32000)
    res.left.map(_.message.startsWith("cannot validate transaction: state at head 9000000 is not available")) shouldBe
      Left(true)
    missing.pool.expectNoMessage(100.millis)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // ETC (Mordor): block-number forks, Olympia unscheduled
  // ---------------------------------------------------------------------------------------------------------------

  private val mordorHead: BigInt = 10_000_000 // post-Spiral, pre-Olympia (unscheduled)

  "eth_sendRawTransaction on Mordor" should "admit 0x0 and 0x1 and refuse 0x2, 0x3 and 0x4 before Olympia" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val f = new Fixture(mordor, mordorHead, 1_700_000_000L, baseFee = None)
    val id = mordor.chainId.value
    val k0, k1, k = newKey()
    Seq(k0, k1, k).foreach(f.fund(_))
    f.admitted(sign(legacy(), k0, Some(id)))
    f.admitted(sign(accessListTx(id), k1, Some(id)))
    f.refused(
      sign(dynamicFeeTx(id), k, Some(id)),
      "transaction type not supported: type 2 rejected, pool not yet in Olympia"
    )
    f.refused(sign(blobTx(id), k, Some(id)), "transaction type not supported: type 3 rejected, pool not yet in Cancun")
    f.refused(
      sign(setCodeTx(id), k, Some(id)),
      "transaction type not supported: type 4 rejected, pool not yet in Olympia"
    )
  }

  it should "refuse 0x1 before Magneto" taggedAs (UnitTest, RPCTest) in {
    val magneto = mordor.forkBlockNumbers.magnetoBlockNumber
    val f = new Fixture(mordor, magneto - 1, 1_600_000_000L, baseFee = None)
    val id = mordor.chainId.value
    val k = newKey()
    f.fund(k)
    f.refused(
      sign(accessListTx(id), k, Some(id)),
      "transaction type not supported: type 1 rejected, pool not yet in Magneto"
    )
  }

  it should "refuse a legacy tx signed for ETC mainnet" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(mordor, mordorHead, 1_700_000_000L, baseFee = None)
    val k = newKey()
    f.fund(k)
    f.refused(sign(legacy(), k, Some(61)), "invalid sender: invalid chain id for signer: have 61 want 63")
  }

  it should "admit a legacy tx priced at 1 gwei before Olympia, and refuse a zero price (1 wei pricelimit)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // Mordor's base-fee-floor is 1 gwei, but before Olympia there is no base fee: the tip is the whole gas price, and
    // the floor is core-geth's 1 wei pricelimit (ECIP-1122). Charging the floor as a base fee refused this tx.
    val f = new Fixture(mordor, mordorHead, 1_700_000_000L, baseFee = None)
    val id = mordor.chainId.value
    val k = newKey()
    f.fund(k)
    f.admitted(sign(legacy(gasPrice = OneGwei), k, Some(id)))
    f.refused(
      sign(legacy(gasPrice = 0, nonce = 1), k, Some(id)),
      "transaction gas price below minimum: gas tip cap 0, minimum needed 1"
    )
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The shared rules, as the peer path uses them
  // ---------------------------------------------------------------------------------------------------------------

  "TxTypeGate" should "name the types active at the head per chain family" taggedAs (UnitTest) in {
    def header(n: BigInt, t: Long) =
      Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(n), unixTimestamp = Timestamp(t))
    TxTypeGate.activeTypes(header(mordorHead, 0))(using mordor) shouldBe Set[Byte](0, 1)
    TxTypeGate.activeTypes(header(9_000_000, sepoliaShanghai))(using sepolia) shouldBe Set[Byte](0, 1, 2)
    TxTypeGate.activeTypes(header(9_000_000, sepoliaCancun))(using sepolia) shouldBe Set[Byte](0, 1, 2, 3)
    TxTypeGate.activeTypes(header(9_000_000, sepoliaPrague))(using sepolia) shouldBe Set[Byte](0, 1, 2, 3, 4)
  }

  "TxAdmission.tipRejection" should "read no base fee before Olympia on ETC, and the floor from Olympia" taggedAs (
    UnitTest
  ) in {
    val preOlympia = Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(mordorHead))
    TxAdmission.tipRejection(legacy(gasPrice = OneGwei), Some(preOlympia))(using mordor) shouldBe None
    TxAdmission.tipRejection(legacy(gasPrice = 0), Some(preOlympia))(using mordor) shouldBe
      Some(TxAdmission.Rejection.TipBelowMinimum(0, 1))

    // Olympia scheduled at the head (a synthetic config: Olympia is not scheduled on any ETC chain).
    val olympia = mordor.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = mordorHead))
    val postOlympia = preOlympia.copy(extraFields = HefPostOlympia(OneGwei))
    TxAdmission.tipRejection(legacy(gasPrice = 2 * OneGwei), Some(postOlympia))(using olympia) shouldBe None
    TxAdmission.tipRejection(legacy(gasPrice = OneGwei + 1), Some(postOlympia))(using olympia) shouldBe
      Some(TxAdmission.Rejection.TipBelowMinimum(1, olympia.minTip))
  }

  it should "count the blob fee in the upfront cost" taggedAs (UnitTest) in {
    val tx = blobTx(sepolia.chainId.value)
    TxAdmission.upfrontCost(tx) shouldBe BigInt(21_000) * 2 * OneGwei + BigInt(131_072) * OneGwei
  }

  // ---------------------------------------------------------------------------------------------------------------
  // #1629 S0b: the RPC layer (go-ethereum SubmitTransaction / checkTxFee), per chain
  // ---------------------------------------------------------------------------------------------------------------

  private val Unprotected = "only replay-protected (EIP-155) transactions allowed over RPC"

  "eth_sendRawTransaction (S0b)" should "refuse an unprotected legacy tx by default, on ETH and on Mordor" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    for (cfg, head, ts, baseFee) <- Seq(
        (sepolia, BigInt(9_000_000), sepoliaPrague, Some(OneGwei)),
        (mordor, mordorHead, 1_700_000_000L, None)
      )
    do
      val f = new Fixture(cfg, head, ts, baseFee = baseFee)
      val k = newKey()
      f.fund(k)
      f.refused(sign(legacy(), k, None), Unprotected)
      f.admitted(sign(legacy(), k, Some(cfg.chainId.value)))
  }

  it should "admit an unprotected legacy tx when allow-unprotected-txs is set, on ETH and on Mordor" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val allow = RpcTxSubmission(allowUnprotectedTxs = true, txFeeCap = 1.0)
    for (cfg, head, ts, baseFee) <- Seq(
        (sepolia, BigInt(9_000_000), sepoliaPrague, Some(OneGwei)),
        (mordor, mordorHead, 1_700_000_000L, None)
      )
    do
      val f = new Fixture(cfg, head, ts, baseFee = baseFee, rpc = allow)
      val k = newKey()
      f.fund(k)
      f.admitted(sign(legacy(), k, None))
  }

  it should "refuse a fee above the 1 ether cap before any other check, on ETH and on Mordor" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // 21000 gas at 100 000 gwei is 2.1 ether. Signed for the wrong chain and unfunded: the fee cap is checked first.
    val price = BigInt(10).pow(14)
    for (cfg, head, ts, baseFee) <- Seq(
        (sepolia, BigInt(9_000_000), sepoliaPrague, Some(OneGwei)),
        (mordor, mordorHead, 1_700_000_000L, None)
      )
    do
      val f = new Fixture(cfg, head, ts, baseFee = baseFee)
      val k = newKey()
      f.refused(
        sign(legacy(gasPrice = price), k, Some(12345)),
        "tx fee (2.10 ether) exceeds the configured cap (1.00 ether)"
      )
      // with no cap, the same tx meets the pool's rules (here: signed for another chain)
      val uncapped = new Fixture(cfg, head, ts, baseFee = baseFee, rpc = RpcTxSubmission.Default.copy(txFeeCap = 0))
      uncapped.fund(k)
      uncapped.admitted(sign(legacy(gasPrice = price), k, Some(cfg.chainId.value)))
  }

  it should "price a 0x2 tx at its fee cap against the cap" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refused(
      sign(dynamicFeeTx(id, maxFee = BigInt(10).pow(14), tip = OneGwei), k, Some(id)),
      "tx fee (2.10 ether) exceeds the configured cap (1.00 ether)"
    )
    f.admitted(sign(dynamicFeeTx(id, maxFee = BigInt(10).pow(13), tip = OneGwei), k, Some(id)))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // #1629 S0b: blob sidecars (go-ethereum txpool validateBlobSidecar / ValidateCells; v1.16 validateBlobTx)
  // ---------------------------------------------------------------------------------------------------------------

  private val BlobSize = 131_072
  private lazy val blobA: ByteString = ByteString(new Array[Byte](BlobSize))
  // a non-constant polynomial: a constant one (blobA is 0) has the same, trivial, opening proofs as blobA
  private lazy val blobB: ByteString = ByteString(Array.tabulate[Byte](BlobSize)(i => if i == 31 then 1 else 0))
  private def commitment(blob: ByteString): ByteString =
    ByteString(ethereum.ckzg4844.CKZG4844JNI.blobToKzgCommitment(blob.toArray))
  private lazy val commitmentA = commitment(blobA)
  private lazy val commitmentB = commitment(blobB)
  private lazy val blobProofA =
    ByteString(ethereum.ckzg4844.CKZG4844JNI.computeBlobKzgProof(blobA.toArray, commitmentA.toArray))
  private lazy val cellProofsA: List[ByteString] =
    KzgCellProofs.computeCellsAndKzgProofs(blobA.toArray)._2.toList.map(ByteString(_))
  private lazy val cellProofsB: List[ByteString] =
    KzgCellProofs.computeCellsAndKzgProofs(blobB.toArray)._2.toList.map(ByteString(_))
  private def vhash(c: ByteString): BlobVersionedHash = BlobTxSidecar.versionedHash(c)

  private def blobTxHashes(chainId: BigInt, hashes: List[BlobVersionedHash]): BlobTransaction =
    blobTx(chainId).copy(blobVersionedHashes = hashes)

  /** `tx` signed and wrapped with `sidecar`, in network form. */
  private def wrapped(tx: BlobTransaction, k: AsymmetricCipherKeyPair, sidecar: BlobTxSidecar): Array[Byte] =
    sidecar.networkEncoding(sign(tx, k, Some(tx.chainId))).toArray

  private def v1(blobs: List[ByteString], commitments: List[ByteString], proofs: List[ByteString]) =
    BlobTxSidecar(BlobTxSidecar.Version1, blobs, commitments, proofs)
  private def v0(blobs: List[ByteString], commitments: List[ByteString], proofs: List[ByteString]) =
    BlobTxSidecar(BlobTxSidecar.Version0, blobs, commitments, proofs)

  "a blob tx at an Osaka head (S0b)" should "be admitted with a valid version-1 sidecar" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    val raw = wrapped(blobTxHashes(id, List(vhash(commitmentA))), k, v1(List(blobA), List(commitmentA), cellProofsA))
    f.submitRaw(raw) shouldBe a[Right[?, ?]]
    f.pool.expectMessageType[SubmitLocalTransaction].blobTxRawBytes.map(_.toSeq) shouldBe Some(raw.toSeq)
  }

  it should "convert a version-0 sidecar to version 1, as go-ethereum's eth_sendRawTransaction does" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    val stx = sign(blobTxHashes(id, List(vhash(commitmentA))), k, Some(id))
    val v0Form = v0(List(blobA), List(commitmentA), List(blobProofA)).networkEncoding(stx).toArray
    f.submitRaw(v0Form) shouldBe Right(stx.hash.value)
    // the pool gets (and peers are served) the version-1 form, with the cell proofs computed from the blobs
    f.pool.expectMessageType[SubmitLocalTransaction].blobTxRawBytes shouldBe
      Some(v1(List(blobA), List(commitmentA), cellProofsA).networkEncoding(stx))
  }

  it should "still refuse a converted sidecar whose commitments do not verify" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    // blobA with blobB's commitment (and matching versioned hash): the computed cell proofs do not open it
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentB))), k, v0(List(blobA), List(commitmentB), List(blobProofA))),
      "KZG verification error: invalid proof"
    )
  }

  it should "refuse versioned hashes that do not match the commitments" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    val sc = v1(List(blobA), List(commitmentA), cellProofsA)
    def hex(h: BlobVersionedHash) = "0x" + Hex.toHexString(h.value.toArray)
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentB))), k, sc),
      s"blob 0: computed hash ${hex(vhash(commitmentA))} mismatches transaction one ${hex(vhash(commitmentB))}"
    )
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentA), vhash(commitmentB))), k, sc),
      "invalid number of 1 blob commitments compared to 2 blob hashes"
    )
  }

  it should "refuse more than 6 blobs" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    val seven = List.fill(7)(commitmentA)
    f.refusedRaw(
      wrapped(blobTxHashes(id, seven.map(vhash)), k, v1(List.fill(7)(blobA), seven, Nil)),
      "transaction blob limit exceeded: blob count 7, limit 6"
    )
  }

  it should "refuse a blob tx with no blobs, and a wrong cell-proof count" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refusedRaw(wrapped(blobTxHashes(id, Nil), k, v1(Nil, Nil, Nil)), "blobless blob transaction")
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentA))), k, v1(List(blobA), List(commitmentA), List(blobProofA))),
      "Wrong sidecar format: invalid number of 1 blob proofs expected 128"
    )
  }

  it should "refuse cell proofs that do not verify" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaOsaka, headGasLimit = 60_000_000)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentA))), k, v1(List(blobA), List(commitmentA), cellProofsB)),
      "KZG verification error: invalid proof"
    )
  }

  "a blob tx before Osaka (S0b)" should "be admitted with a valid version-0 sidecar" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    val raw =
      wrapped(blobTxHashes(id, List(vhash(commitmentA))), k, v0(List(blobA), List(commitmentA), List(blobProofA)))
    f.submitRaw(raw) shouldBe a[Right[?, ?]]
    f.pool.expectMessageType[SubmitLocalTransaction]
  }

  it should "refuse a version-1 (cell proof) sidecar, as go-ethereum v1.16 does" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentA))), k, v1(List(blobA), List(commitmentA), cellProofsA)),
      "cell proof is not supported yet"
    )
  }

  it should "refuse no blobs, more than 6, and a blob proof that does not verify" taggedAs (UnitTest, RPCTest) in {
    val f = new Fixture(sepolia, 9_000_000, sepoliaPrague)
    val id = sepolia.chainId.value
    val k = newKey()
    f.fund(k)
    f.refusedRaw(wrapped(blobTxHashes(id, Nil), k, v0(Nil, Nil, Nil)), "blobless blob transaction")
    val seven = List.fill(7)(commitmentA)
    f.refusedRaw(
      wrapped(blobTxHashes(id, seven.map(vhash)), k, v0(List.fill(7)(blobA), seven, Nil)),
      "too many blobs in transaction: have 7, permitted 6"
    )
    val wrongProof = ByteString(ethereum.ckzg4844.CKZG4844JNI.computeBlobKzgProof(blobB.toArray, commitmentB.toArray))
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentA))), k, v0(List(blobA), List(commitmentA), List(wrongProof))),
      "invalid blob 0: invalid proof"
    )
  }

  "a blob tx on Mordor (S0b)" should "meet the type gate before its sidecar is looked at" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val f = new Fixture(mordor, mordorHead, 1_700_000_000L, baseFee = None)
    val id = mordor.chainId.value
    val k = newKey()
    f.fund(k)
    f.refusedRaw(
      wrapped(blobTxHashes(id, List(vhash(commitmentA))), k, v0(List(blobA), List(commitmentA), List(blobProofA))),
      "transaction type not supported: type 3 rejected, pool not yet in Cancun"
    )
  }

  private def readResource(path: String): Array[Byte] =
    val is = getClass.getResourceAsStream(path)
    try is.readAllBytes()
    finally is.close()
