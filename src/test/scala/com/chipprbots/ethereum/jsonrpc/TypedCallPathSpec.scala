package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*

import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.BlockOverrides
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.StateOverride
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.CallTracer

/** #1629 S3: every message-call endpoint runs [[TxArgs]] through [[CallTxBuilder]] and `StxLedger.simulateCall` /
  * `estimateCallGas`, so each transaction type is honoured as go-ethereum's call path honours it (internal/ethapi
  * `ToMessage` / `doCall`, core `ApplyMessage`). Executed against a real ledger on an Osaka (pre-Amsterdam) ETH block
  * with base fee 7, and on ETC before Olympia for the per-chain gating.
  */
// scalastyle:off magic.number
class TypedCallPathSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with AmsterdamFixtureVectors:

  implicit val runtime: IORuntime = IORuntime.global

  private def code(hex: String): ByteString = ByteString(Hex.decode(hex))

  // GASPRICE, BASEFEE -> 64 bytes
  private val FeeEcho = Address(0xfee1)
  private val FeeEchoCode = code("3a6000524860205260406000f3")
  // NUMBER -> 32 bytes
  private val NumberEcho = Address(0xfee2)
  private val NumberEchoCode = code("4360005260206000f3")
  // BLOBHASH(0) -> 32 bytes
  private val BlobReader = Address(0xfee3)
  private val BlobReaderCode = code("60004960005260206000f3")
  // SLOAD(1), stop
  private val SlotReader = Address(0xfee4)
  private val SlotReaderCode = code("6001545000")
  // ADDRESS -> 32 bytes (the delegation target)
  private val AddressEcho = Address(0xfee5)
  private val AddressEchoCode = code("3060005260206000f3")
  // SLOAD(0), SLOAD(5), BALANCE(Probe), CALL(identity precompile 0x04), stop
  private val Probe = Address(0xbbbb)
  private val Collector = Address(0xfee6)
  private val CollectorCode = code(
    "600054506005545073" + "000000000000000000000000000000000000bbbb" + "3150" +
      "60006000600060006000600461fffff150" + "00"
  )

  private val baseFee = BigInt(7)
  private val osakaHeader: BlockHeader = preAmsterdamHeader(46, 300)

  private val authorityKey: AsymmetricCipherKeyPair = generateKeyPair(setup.secureRandom)
  private val authority: Address = Address(authorityKey)
  private val unfunded: Address = Address(0xdead)

  private val etcConfig: BlockchainConfig =
    preAmsterdamConfig
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = BigInt(Long.MaxValue)))
  private val etcHeader: BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(100), extraFields = HefEmpty)

  private class Node(config: BlockchainConfig = preAmsterdamConfig, header: BlockHeader = osakaHeader)
      extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    lazy val ledger: StxLedger =
      new StxLedger(
        blockchain,
        blockchainReader,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        this
      )

    private def withContract(
        w: InMemoryWorldStateProxy,
        address: Address,
        c: ByteString,
        slots: Map[Int, Int] = Map()
    ): InMemoryWorldStateProxy =
      val w1 = w.saveAccount(address, Account(codeHash = CodeHash(kec256(c)))).saveCode(address, c)
      w1.saveStorage(address, slots.foldLeft(w1.getStorage(address)) { case (s, (k, v)) => s.store(k, v) })

    val world: InMemoryWorldStateProxy =
      val empty = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
        UInt256.Zero,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = true,
        ethCompatibleStorage = true
      )
      val funded = empty
        .saveAccount(senderAddress, Account(balance = UInt256(BigInt(10).pow(24))))
        .saveAccount(Probe, Account(balance = UInt256(1)))
      val contracts = List(
        (FeeEcho, FeeEchoCode),
        (NumberEcho, NumberEchoCode),
        (BlobReader, BlobReaderCode),
        (AddressEcho, AddressEchoCode),
        (Collector, CollectorCode)
      ).foldLeft(funded) { case (w, (a, c)) => withContract(w, a, c) }
      InMemoryWorldStateProxy.persistState(withContract(contracts, SlotReader, SlotReaderCode, Map(1 -> 42)))

    val head: BlockHeader = header.copy(stateRoot = TrieRoot(world.stateRootHash))
    blockchainWriter.save(Block(head, BlockBody(Nil, Nil, Some(Nil))), Nil, ChainWeight.zero, saveAsBestBlock = true)

    def message(args: TxArgs, overrides: Option[BlockOverrides] = None): CallTxBuilder.CallMessage =
      CallTxBuilder
        .build(
          args.validateForCall.value,
          head,
          overrides,
          Address(0),
          args.gas.fold(BigInt(1_000_000))(_.value),
          Map()
        )
        .value

    def call(args: TxArgs, overrides: Option[BlockOverrides] = None): Either[String, TxResult] =
      val m = message(args, overrides)
      ledger.simulateCall(m.stx, m.header, None, m.options, None).left.map(_.message)

    def output(args: TxArgs, overrides: Option[BlockOverrides] = None): ByteString =
      call(args, overrides).value.vmReturnData

    def estimate(args: TxArgs): Either[StxLedger.CallEstimateFailure, BigInt] =
      val m = message(args.copy(gas = Some(GasAmount(1_000_000))))
      ledger.estimateCallGas(m.stx, m.header, None, m.options)

    // syncController / scheduler / keyStore are never reached by the call methods (every request names `from`)
    lazy val service = new EthInfoService(
      blockchain,
      blockchainReader,
      config,
      mining,
      ledger,
      null,
      null,
      Capability.ETH68,
      Timeout(5.seconds),
      null
    )

  private def word(v: BigInt): ByteString = ByteString(Hex.decode(f"$v%064x"))
  private def args(to: Address, extra: TxArgs => TxArgs = identity): TxArgs =
    extra(TxArgs(from = Some(senderAddress), to = Some(to)))

  // ── Legacy: byte-identical to the path it replaces ─────────────────────────────────────────────────────────────

  "simulateCall" should "return exactly what simulateTransaction returns for a legacy call" taggedAs (
    UnitTest,
    RPCTest
  ) in
    new Node:
      for gp <- List(BigInt(0), BigInt(9)) do
        val m = message(args(SlotReader, _.copy(gasPrice = Some(GasPrice(gp)))))
        m.stx.tx.tx shouldBe a[LegacyTransaction]
        val viaCall = ledger.simulateCall(m.stx, m.header, None, m.options, None).value
        val viaTx = ledger.simulateTransaction(m.stx, m.header, None)
        (viaCall.gasUsed, viaCall.vmReturnData, viaCall.vmError, viaCall.logs) shouldBe
          (viaTx.gasUsed, viaTx.vmReturnData, viaTx.vmError, viaTx.logs)
        viaCall.worldState.stateRootHash shouldBe viaTx.worldState.stateRootHash

        val t1 = new CallTracer()
        val t2 = new CallTracer()
        ledger.simulateCall(m.stx, m.header, None, m.options, Some(t1))
        ledger.simulateTransactionWithTracer(m.stx, m.header, None, t2)
        t1.getResult shouldBe t2.getResult

  "estimateCallGas" should "answer what binarySearchGasEstimation answers for a legacy call" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    val m = message(args(SlotReader, _.copy(gas = Some(GasAmount(1_000_000)))))
    ledger.estimateCallGas(m.stx, m.header, None, m.options).value shouldBe
      ledger.binarySearchGasEstimation(m.stx, m.header, None)

  // ── Fees (geth ToMessage) ──────────────────────────────────────────────────────────────────────────────────────

  "eth_call fees" should "price a 1559 message at min(maxFee, baseFee + tip) and keep BASEFEE" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    val withFees = args(FeeEcho, _.copy(maxFeePerGas = Some(100), maxPriorityFeePerGas = Some(3)))
    message(withFees).stx.tx.tx shouldBe a[TransactionWithDynamicFee]
    output(withFees) shouldBe word(baseFee + 3) ++ word(baseFee)
    // capped by maxFeePerGas (execution-apis call-callenv-options-eip1559 shape: echoed GASPRICE = maxFee - 1 there)
    output(args(FeeEcho, _.copy(maxFeePerGas = Some(8), maxPriorityFeePerGas = Some(3)))) shouldBe word(8) ++ word(7)
    // maxFeePerGas alone: tip 0, price = baseFee
    output(args(FeeEcho, _.copy(maxFeePerGas = Some(100)))) shouldBe word(7) ++ word(7)

  it should "use a legacy gasPrice as is" taggedAs (UnitTest, RPCTest) in new Node:
    output(args(FeeEcho, _.copy(gasPrice = Some(GasPrice(9))))) shouldBe word(9) ++ word(7)

  it should "lower the base fee to 0 when the message's price is 0 (no fee fields, or zero ones)" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    output(args(FeeEcho)) shouldBe word(0) ++ word(0)
    output(args(FeeEcho, _.copy(gasPrice = Some(GasPrice(0))))) shouldBe word(0) ++ word(0)
    output(args(FeeEcho, _.copy(maxFeePerGas = Some(0)))) shouldBe word(0) ++ word(0)

  it should "refuse fee caps the block cannot accept, with go-ethereum's messages" taggedAs (UnitTest, RPCTest) in
    new Node:
      call(args(FeeEcho, _.copy(maxFeePerGas = Some(5), maxPriorityFeePerGas = Some(1)))).left.value should startWith(
        "max fee per gas less than block base fee: address "
      )
      call(args(FeeEcho, _.copy(maxFeePerGas = Some(10), maxPriorityFeePerGas = Some(11)))).left.value should include(
        "max priority fee per gas higher than max fee per gas"
      )
      call(args(FeeEcho, _.copy(gasPrice = Some(GasPrice(6))))).left.value should include(
        "maxFeePerGas: 6, baseFee: 7"
      )

  it should "require funds only when the message pays for gas or value" taggedAs (UnitTest, RPCTest) in new Node:
    // execution-apis call-unfunded-sender: no fee fields, zero value -> runs
    call(TxArgs(from = Some(unfunded), to = Some(Probe))).isRight shouldBe true
    call(TxArgs(from = Some(unfunded), to = Some(Probe), gasPrice = Some(GasPrice(9)))).left.value shouldBe
      s"insufficient funds for gas * price + value: address 0x000000000000000000000000000000000000dEaD have 0 want ${BigInt(1_000_000) * 9}"
    call(TxArgs(from = Some(unfunded), to = Some(Probe), value = Some(1))).left.value should include("want 1")

  it should "refuse a gas limit below the intrinsic cost" taggedAs (UnitTest, RPCTest) in new Node:
    call(args(Probe, _.copy(gas = Some(GasAmount(20000))))).left.value shouldBe
      "intrinsic gas too low: have 20000, want 21000"

  // ── Access list (EIP-2929 pre-warming) ─────────────────────────────────────────────────────────────────────────

  "eth_call accessList" should "warm the listed slot and charge for the list, as a type-1 transaction does" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    val plain = call(args(SlotReader)).value.gasUsed
    val listed = call(
      args(SlotReader, _.copy(accessList = Some(List(AccessListItem(SlotReader, List(StorageKey(1)))))))
    ).value.gasUsed
    // + 2,400 (address) + 1,900 (slot) intrinsic, - (2,100 cold - 100 warm) SLOAD
    listed - plain shouldBe BigInt(2300)
    message(args(SlotReader, _.copy(accessList = Some(Nil)))).stx.tx.tx shouldBe a[TransactionWithAccessList]

  // ── Blobs ──────────────────────────────────────────────────────────────────────────────────────────────────────

  "eth_call blobVersionedHashes" should "be visible to BLOBHASH" taggedAs (UnitTest, RPCTest) in new Node:
    val hash = ByteString(Hex.decode("01" + "ab" * 31))
    val blobArgs = args(BlobReader, _.copy(blobVersionedHashes = Some(List(BlobVersionedHash(hash)))))
    message(blobArgs).stx.tx.tx shouldBe a[BlobTransaction]
    output(blobArgs) shouldBe hash

  it should "be refused with a bad version or none at all" taggedAs (UnitTest, RPCTest) in new Node:
    val bad = ByteString(Hex.decode("02" + "ab" * 31))
    call(args(BlobReader, _.copy(blobVersionedHashes = Some(List(BlobVersionedHash(bad)))))).left.value shouldBe
      "blob 0 has invalid hash version"
    call(args(BlobReader, _.copy(blobVersionedHashes = Some(Nil)))).left.value shouldBe
      "blob transaction missing blob hashes"

  // ── EIP-7702 ───────────────────────────────────────────────────────────────────────────────────────────────────

  private def authorization(target: Address, nonce: BigInt = 0, chainId: BigInt = 0): SetCodeAuthorization =
    val hash = kec256(
      rlp.encode(
        PrefixedRLPEncodable(0x05, RLPList(toEncodeable(chainId), toEncodeable(target.toArray), toEncodeable(nonce)))
      )
    )
    val sig = ECDSASignature.sign(hash, authorityKey)
    SetCodeAuthorization(chainId, target, nonce, sig.v - 27, sig.r, sig.s)

  "eth_call authorizationList" should "apply the delegation before the call and run the delegated code" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    // without the list the authority is an empty account: no code, no output
    output(args(authority)) shouldBe ByteString.empty
    val delegated = args(authority, _.copy(authorizationList = Some(List(authorization(AddressEcho)))))
    message(delegated).stx.tx.tx shouldBe a[SetCodeTransaction]
    // the delegated code runs in the authority's context, so ADDRESS is the authority
    output(delegated) shouldBe word(BigInt(1, authority.toArray))

  it should "raise eth_estimateGas by the authorization's cost" taggedAs (UnitTest, RPCTest) in new Node:
    val without = estimate(args(authority)).value
    val withAuth = estimate(args(authority, _.copy(authorizationList = Some(List(authorization(AddressEcho)))))).value
    withAuth should be > without

  it should "refuse an empty list as go-ethereum does" taggedAs (UnitTest, RPCTest) in new Node:
    call(args(authority, _.copy(authorizationList = Some(Nil)))).left.value should startWith(
      "EIP-7702 transaction with empty auth list (sender "
    )

  // ── Overrides ──────────────────────────────────────────────────────────────────────────────────────────────────

  "block overrides" should "replace the base fee and number the call sees" taggedAs (UnitTest, RPCTest) in new Node:
    val ov = Some(BlockOverrides(baseFeePerGas = Some(20), number = Some(12345)))
    output(args(FeeEcho, _.copy(maxFeePerGas = Some(100), maxPriorityFeePerGas = Some(3))), ov) shouldBe
      word(23) ++ word(20)
    output(args(NumberEcho), ov) shouldBe word(12345)

  "eth_call with state overrides" should "run against the overridden balance and code" taggedAs (UnitTest, RPCTest) in
    new Node:
      val funds = unfunded -> StateOverride(balance = Some(BigInt(10).pow(18)))
      val req = CallRequest(
        TxArgs(from = Some(unfunded), to = Some(Probe), gasPrice = Some(GasPrice(9)), gas = Some(GasAmount(100000))),
        BlockParam.Latest,
        stateOverrides = Some(Map(funds, Probe -> StateOverride(code = Some(NumberEchoCode))))
      )
      // without the balance override the sender cannot pay 100,000 * 9
      service.call(req.copy(stateOverrides = None)).unsafeRunSync().left.value.message should startWith(
        "insufficient funds"
      )
      service.call(req).unsafeRunSync().value shouldBe CallResponse(word(46))
      // and with a block override on top
      service
        .call(req.copy(blockOverrides = Some(BlockOverrides(number = Some(99)))))
        .unsafeRunSync()
        .value shouldBe CallResponse(word(99))

  // ── eth_createAccessList ───────────────────────────────────────────────────────────────────────────────────────

  "eth_createAccessList" should "collect the touched slots and addresses until the list is stable" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    val r = service
      .createAccessList(
        CreateAccessListRequest(args(Collector, _.copy(gas = Some(GasAmount(200000)))), BlockParam.Latest)
      )
      .unsafeRunSync()
      .value
    // Collector itself (the recipient) appears because it read its own storage; the identity precompile and the
    // sender are excluded; Probe is listed with no slots. Ordered by address.
    r.accessList shouldBe List(
      AccessListItem(Probe, Nil),
      AccessListItem(Collector, List(StorageKey(0), StorageKey(5)))
    )
    r.error shouldBe None
    // the reported gas is the gas of a call carrying exactly that list
    r.gasUsed shouldBe call(
      args(Collector, _.copy(gas = Some(GasAmount(200000)), accessList = Some(r.accessList)))
    ).value.gasUsed

  // 29,700 gas: the first run (no list) succeeds and collects the list; with the list's 8,600 intrinsic gas the second
  // run runs out of gas after the first SLOAD and collects the same list, so the answer carries the failure.
  it should "report the access list of a failing call with geth's error string" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    val r = service
      .createAccessList(
        CreateAccessListRequest(args(Collector, _.copy(gas = Some(GasAmount(29700)))), BlockParam.Latest)
      )
      .unsafeRunSync()
      .value
    r.error shouldBe Some("out of gas")
    r.accessList.map(_.address) should contain(Collector)

  it should "return an empty list for a plain transfer (execution-apis create-al-value-transfer shape)" taggedAs (
    UnitTest,
    RPCTest
  ) in new Node:
    val r = service
      .createAccessList(CreateAccessListRequest(TxArgs(from = Some(unfunded), to = Some(Probe)), BlockParam.Latest))
      .unsafeRunSync()
      .value
    r shouldBe CreateAccessListResponse(Nil, BigInt(21000), None)

  it should "apply state overrides and leave the call's authorities out" taggedAs (UnitTest, RPCTest) in new Node:
    // Probe's overridden code reads slot 7 and the balance of Collector
    val probeCode = code("6007545073" + "000000000000000000000000000000000000fee6" + "315000")
    val r = service
      .createAccessList(
        CreateAccessListRequest(
          args(
            Probe,
            _.copy(gas = Some(GasAmount(200000)), authorizationList = Some(List(authorization(AddressEcho))))
          ),
          BlockParam.Latest,
          stateOverrides = Some(Map(Probe -> StateOverride(code = Some(probeCode))))
        )
      )
      .unsafeRunSync()
      .value
    r.accessList shouldBe List(AccessListItem(Probe, List(StorageKey(7))), AccessListItem(Collector, Nil))
    r.accessList.map(_.address) should not contain authority

  // ── ETC: per-chain gating ──────────────────────────────────────────────────────────────────────────────────────

  "CallTxBuilder on ETC before Olympia" should "refuse blob hashes, authorizations and an explicit type 0x2" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    given BlockchainConfig = etcConfig
    def build(a: TxArgs) = CallTxBuilder.build(a, etcHeader, None, Address(0), 100000, Map())
    val hash = BlobVersionedHash(ByteString(Hex.decode("01" + "ab" * 31)))
    build(args(Probe, _.copy(blobVersionedHashes = Some(List(hash))))).left.value.code shouldBe -32602
    build(args(Probe, _.copy(authorizationList = Some(Nil)))).left.value.message should include("type 4 not active")
    build(args(Probe, _.copy(txType = Some(2), maxFeePerGas = Some(1)))).left.value.message should include(
      "type 2 not active"
    )
  }

  it should "ignore maxFeePerGas / maxPriorityFeePerGas without a type, as go-ethereum does before London" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    given BlockchainConfig = etcConfig
    val m = CallTxBuilder
      .build(
        args(Probe, _.copy(maxFeePerGas = Some(50), maxPriorityFeePerGas = Some(2))),
        etcHeader,
        None,
        Address(0),
        100000,
        Map()
      )
      .value
    m.stx.tx.tx shouldBe LegacyTransaction(0, GasPrice(0), GasAmount(100000), Some(Probe), 0, ByteString.empty)
    m.header shouldBe etcHeader
  }

  it should "build an access-list message (type 0x1 is active on ETC)" taggedAs (UnitTest, RPCTest) in {
    given BlockchainConfig = etcConfig
    CallTxBuilder
      .build(
        args(Probe, _.copy(accessList = Some(Nil), gasPrice = Some(GasPrice(3)), txType = Some(1))),
        etcHeader,
        None,
        Address(0),
        100000,
        Map()
      )
      .value
      .stx
      .tx
      .tx shouldBe a[TransactionWithAccessList]
  }

  // ── JSON: params and overrides ─────────────────────────────────────────────────────────────────────────────────

  "eth_call params" should "accept the call alone, a block, state overrides and block overrides" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    import EthJsonMethodsImplicits.eth_call
    val call = parse("""{"to":"0x000000000000000000000000000000000000bbbb","maxFeePerGas":"0x10","accessList":[]}""")
    val st = parse(
      """{"0x000000000000000000000000000000000000bbbb":{"balance":"0x1","stateDiff":{"0x0000000000000000000000000000000000000000000000000000000000000001":"0x000000000000000000000000000000000000000000000000000000000000002a"}}}"""
    )
    val bo = parse("""{"baseFeePerGas":"0x3","number":"0x10"}""")
    val one = eth_call.decodeJson(Some(JArray(List(call)))).value
    one.block shouldBe BlockParam.Latest
    one.tx.accessList shouldBe Some(Nil)
    val four = eth_call.decodeJson(Some(JArray(List(call, JString("latest"), st, bo)))).value
    four.stateOverrides.value(Probe) shouldBe StateOverride(
      balance = Some(1),
      stateDiff = Some(Map(BigInt(1) -> BigInt(42)))
    )
    four.blockOverrides.value shouldBe BlockOverrides(number = Some(16), baseFeePerGas = Some(3))
    eth_call.decodeJson(Some(JArray(List(call, JString("latest"), JNull, bo)))).value.stateOverrides shouldBe None
  }

  it should "reject malformed overrides" taggedAs (UnitTest, RPCTest) in {
    import EthJsonMethodsImplicits.eth_call
    val call = parse("""{"to":"0x000000000000000000000000000000000000bbbb"}""")
    def json(s: String): JValue = if s == "null" then JNull else parse(s)
    def err(st: String, bo: String = "null") =
      eth_call.decodeJson(Some(JArray(List(call, JString("latest"), json(st), json(bo))))).left.value
    err("""{"0x00bb":{}}""").code shouldBe -32602
    err("""{"0x000000000000000000000000000000000000bbbb":{"balance":"12"}}""").code shouldBe -32602
    err("""{"0x000000000000000000000000000000000000bbbb":{"bogus":"0x1"}}""").code shouldBe -32602
    err("""{"0x000000000000000000000000000000000000bbbb":{"state":{},"stateDiff":{}}}""").message should include(
      "has both 'state' and 'stateDiff'"
    )
    err(
      "null",
      """{"beaconRoot":"0x0000000000000000000000000000000000000000000000000000000000000000"}"""
    ).message shouldBe
      "block override \"beaconRoot\" is not supported for this RPC method"
    eth_call
      .decodeJson(Some(JArray(List(call, JString("latest"), JNull, JNull, JNull))))
      .left
      .value
      .code shouldBe -32602
  }

  "eth_createAccessList params" should "take the call, a block and state overrides" taggedAs (UnitTest, RPCTest) in {
    import EthJsonMethodsImplicits.eth_createAccessList
    val call = parse("""{"to":"0x000000000000000000000000000000000000bbbb"}""")
    eth_createAccessList.decodeJson(Some(JArray(List(call)))).value.block shouldBe BlockParam.Latest
    eth_createAccessList
      .decodeJson(Some(JArray(List(call, JString("latest"), JObject()))))
      .value
      .stateOverrides shouldBe
      Some(Map.empty)
    eth_createAccessList.decodeJson(Some(JArray(List(call, JString("latest"), JNull, JNull)))).isLeft shouldBe true
    eth_createAccessList.encodeJson(
      CreateAccessListResponse(List(AccessListItem(Probe, List(StorageKey(1)))), 0x5b85, Some("execution reverted"))
    ) shouldBe parse(
      """{"accessList":[{"address":"0x000000000000000000000000000000000000bbbb","storageKeys":["0x0000000000000000000000000000000000000000000000000000000000000001"]}],"gasUsed":"0x5b85","error":"execution reverted"}"""
    )
  }
