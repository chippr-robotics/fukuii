package com.chipprbots.ethereum.vm

import java.nio.charset.StandardCharsets

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.crypto.sha256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.CallErrors
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks

/** #1727 PR 7b: a precompile that fails says why in go-ethereum v1.17.9's words (core/vm/contracts.go), on ETC Olympia
  * and ETH Osaka, where every precompile with a failure mode is active.
  *
  * Each case runs the precompile and checks, on both chains:
  *   - what consensus sees, which does not change: all the frame's gas consumed, no output, the world untouched;
  *   - the error: [[PreCompiledContractFail]] carrying this precompile and this input;
  *   - its `failureReason`, against go-ethereum's text for the same input;
  *   - the RPC's message for the error (`jsonrpc.CallErrors.vmErrorMessage`), against the same text.
  *
  * The BLS12-381 cases include go-ethereum's own failure vectors, each with the `ExpectedError` its `contracts_test.go`
  * asserts. BN254 is go-ethereum's gnark wrapper (crypto/bn256/gnark); KZG is go-eth-kzg v1.5.0, its default backend.
  */
// scalastyle:off magic.number
class PrecompileFailureReasonSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with AmsterdamFixtureVectors:

  override def beforeAll(): Unit = KzgTestSetup.ensureLoaded()

  private val C = Eip2537Corpus

  final private case class Chain(name: String, evmConfig: EvmConfig, header: BlockHeader)

  private val etcOlympia = Chain(
    "ETC Olympia",
    EvmConfig.OlympiaConfigBuilder(Fixtures.blockchainConfig),
    BlockFixtures.ValidBlock.header.copy(number = BlockNumber(Fixtures.OlympiaBlockNumber))
  )

  private val ethOsaka =
    val header = preAmsterdamHeader(500, 180)
    Chain("ETH Osaka", EvmConfig.forBlock(header.number.value, header.unixTimestamp, preAmsterdamConfig), header)

  private val Caller = Address(0xca11)
  private val Plenty: BigInt = 10_000_000

  private def run(chain: Chain, address: Address, input: ByteString, gas: BigInt) =
    val world = MockWorldState()
    val context = ProgramContext[MockWorldState, MockStorage](
      callerAddr = Caller,
      originAddr = Caller,
      recipientAddr = Some(address),
      gasPrice = 1,
      startGas = gas,
      inputData = input,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      blockHeader = chain.header,
      callDepth = 1,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = chain.evmConfig,
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )
    PrecompiledContracts.isDefinedAt(context) shouldBe true
    (context, PrecompiledContracts.run(context))

  final private case class Failing(name: String, address: Address, input: ByteString, gas: BigInt, geth: String)

  /** The reason the RPC reports: the precompile's, or go-ethereum's `ErrOutOfGas` text. */
  private def reason(context: ProgramContext[MockWorldState, MockStorage], error: Option[ProgramError]): String =
    error match
      case Some(PreCompiledContractFail(contract, input)) =>
        contract shouldBe theSameInstanceAs(PrecompiledContracts.getContracts(context)(context.recipientAddr.get))
        input shouldBe context.inputData
        contract.failureReason(input)
      case Some(OutOfGas) => "out of gas"
      case other          => fail(s"not a precompile's failure: $other")

  private def check(failing: Failing): Unit =
    for chain <- Seq(etcOlympia, ethOsaka) do
      withClue(s"${chain.name} ${failing.name}: ") {
        val (context, result) = run(chain, failing.address, failing.input, failing.gas)
        result.gasRemaining shouldBe 0
        result.returnData shouldBe ByteString.empty
        (result.world should be).theSameInstanceAs(context.world)
        reason(context, result.error) shouldBe failing.geth
        // what eth_call, eth_estimateGas, eth_createAccessList and eth_simulateV1 print
        CallErrors.vmErrorMessage(result.error.get) shouldBe failing.geth
      }

  private def h(s: String): ByteString = ByteString(Hex.decode(s))
  private def zeros(n: Int): ByteString = ByteString(new Array[Byte](n))
  private def word(n: BigInt): ByteString = UInt256(n).bytes

  // ── MODEXP (0x05): EIP-7823 ──────────────────────────────────────────────────────────────────────────────────

  private val ModExpTooLong = "one or more of base/exponent/modulus length exceeded 1024 bytes"

  private def modexp(baseLen: BigInt, expLen: BigInt, modLen: BigInt): ByteString =
    word(baseLen) ++ word(expLen) ++ word(modLen) ++ zeros((baseLen + expLen + modLen).min(4096).toInt)

  "MODEXP" should "fail an operand longer than 1024 bytes with go-ethereum's text" taggedAs (VMTest, OlympiaTest) in {
    val modexpAddr = PrecompiledContracts.ModExpAddr
    Seq(
      Failing("base 1025", modexpAddr, modexp(1025, 1, 1), Plenty, ModExpTooLong),
      Failing("exponent 1025", modexpAddr, modexp(1, 1025, 1), Plenty, ModExpTooLong),
      Failing("modulus 1025", modexpAddr, modexp(1, 1, 1025), Plenty, ModExpTooLong),
      // EIP-7883 prices base 1025 at 2 * ceil(1025 / 8)^2 = 33,282: exactly enough gas reaches the length check
      Failing("base 1025 with exactly its gas", modexpAddr, modexp(1025, 1, 1), 33_282, ModExpTooLong)
    ).foreach(check)
  }

  it should "run out of gas first when its gas does not cover the price, as go-ethereum charges before it runs" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    val modexpAddr = PrecompiledContracts.ModExpAddr
    Seq(
      Failing("base 1025, one gas short", modexpAddr, modexp(1025, 1, 1), 33_281, "out of gas"),
      Failing("base 1025, little gas", modexpAddr, modexp(1025, 1, 1), 10_000, "out of gas"),
      Failing("a 2^32 length word", modexpAddr, modexp(BigInt(1) << 32, 1, 1), Plenty, "out of gas")
    ).foreach(check)
  }

  it should "still run an operand longer than 1024 bytes before ETC Olympia and ETH Osaka" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    val spiral = Chain(
      "ETC Spiral",
      EvmConfig.SpiralConfigBuilder(Fixtures.blockchainConfig),
      BlockFixtures.ValidBlock.header.copy(number = BlockNumber(Fixtures.SpiralBlockNumber))
    )
    val prague =
      val header = preAmsterdamHeader(500, 120)
      Chain("ETH Prague", EvmConfig.forBlock(header.number.value, header.unixTimestamp, preAmsterdamConfig), header)
    for chain <- Seq(spiral, prague) do
      withClue(s"${chain.name}: ") {
        // EIP-2565: ceil(1025 / 8)^2 / 3 = 5,547
        val (_, result) = run(chain, PrecompiledContracts.ModExpAddr, modexp(1025, 1, 1), 5_547)
        result.error shouldBe None
        result.gasRemaining shouldBe 0
        result.returnData shouldBe zeros(1)
      }
  }

  // ── BN254 (0x06 - 0x08) ──────────────────────────────────────────────────────────────────────────────────────

  private val BnP = BigInt("21888242871839275222246405745257275088696311157297823662689037894645226208583")
  private val NotCanonical = "invalid fp.Element encoding"
  private val NotOnCurve = "point is not on curve"

  private val BnG2Generator = h(
    "198e9393920d483a7260bfb731fb5d25f1aa493335a9e71297e485b7aef312c2" +
      "1800deef121f1e76426a00665e5c4479674322d4f75edadd46debd5cd992f6ed" +
      "090689d0585ff075ec9e99ad690c3395bc4b313370b38ef355acdadcd122975b" +
      "12c85ea5db8c6deb4aab71808dcb408fe3d1e7690c43d37b4ce6cc0166fa7daa"
  )
  private val BnG2NotOnCurve = set(BnG2Generator, 127, 0xab)

  /** The G2 point of EEST stZeroKnowledge/ecpairing_inputs[invalid_g2_subgroup-10] (PrecompiledContractsSpec's
    * `BN128Pairing_G2_outside_subgroup_fails`): on the twist, outside the order-r subgroup.
    */
  private val BnG2NotInSubgroup = h(
    "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000192b7e3a0ca8b63592989fe8b2589465703315272bc730644e72e131a029b85045b68181585d00001b86b77538000000000100000000128a694e7017ae1db6a312c9ef648b1a4910a41e684cb554302044a2065f04680df2d76a91278279cf401d431c31876ee9c8ad35070694552ccbd36875541383"
  ).drop(64)

  "BN254 ADD, MUL and PAIRING" should "fail a bad point with go-ethereum's text" taggedAs (VMTest, OlympiaTest) in {
    val add = PrecompiledContracts.Bn128AddAddr
    val mul = PrecompiledContracts.Bn128MulAddr
    val pairing = PrecompiledContracts.Bn128PairingAddr
    val g1 = word(1) ++ word(2)
    Seq(
      Failing("ADD (1,1)", add, word(1) ++ word(1) ++ zeros(64), Plenty, NotOnCurve),
      Failing("ADD x = p", add, word(BnP) ++ word(0) ++ zeros(64), Plenty, NotCanonical),
      Failing("ADD y = p", add, word(0) ++ word(BnP) ++ zeros(64), Plenty, NotCanonical),
      Failing("ADD G + (1,3)", add, g1 ++ word(1) ++ word(3), Plenty, NotOnCurve),
      Failing("ADD a 1-byte input, zero-padded to (2^248, 0)", add, h("01"), Plenty, NotOnCurve),
      Failing(
        "ADD (1,1) before a non-canonical second point",
        add,
        word(1) ++ word(1) ++ word(BnP) ++ word(0),
        Plenty,
        NotOnCurve
      ),
      Failing(
        "ADD a non-canonical first point before (1,1)",
        add,
        word(BnP) ++ word(0) ++ word(1) ++ word(1),
        Plenty,
        NotCanonical
      ),
      Failing("MUL (1,1)", mul, word(1) ++ word(1) ++ word(2), Plenty, NotOnCurve),
      Failing("MUL x = p", mul, word(BnP) ++ word(1) ++ word(1), Plenty, NotCanonical),
      Failing("PAIRING 191 bytes", pairing, zeros(191), Plenty, "bad elliptic curve pairing size"),
      Failing("PAIRING 193 bytes", pairing, zeros(193), Plenty, "bad elliptic curve pairing size"),
      Failing("PAIRING G1 (1,1)", pairing, word(1) ++ word(1) ++ BnG2Generator, Plenty, NotOnCurve),
      Failing("PAIRING G2 off the twist", pairing, zeros(64) ++ BnG2NotOnCurve, Plenty, NotOnCurve),
      Failing("PAIRING G2 x.1 = p", pairing, zeros(64) ++ word(BnP) ++ BnG2Generator.drop(32), Plenty, NotCanonical),
      Failing(
        "PAIRING G2 outside the subgroup",
        pairing,
        zeros(64) ++ BnG2NotInSubgroup,
        Plenty,
        "point is not in correct subgroup"
      ),
      Failing(
        "PAIRING G1 (1,1) before a G2 outside the subgroup",
        pairing,
        word(1) ++ word(1) ++ BnG2NotInSubgroup,
        Plenty,
        NotOnCurve
      ),
      Failing(
        "PAIRING a good pair, then G1 x = p",
        pairing,
        g1 ++ BnG2Generator ++ word(BnP) ++ word(0) ++ BnG2Generator,
        Plenty,
        NotCanonical
      )
    ).foreach(check)
  }

  // ── BLAKE2F (0x09) ───────────────────────────────────────────────────────────────────────────────────────────

  private def blake2f(rounds: Int, flag: Int): ByteString =
    ByteString(BigInt(rounds).toByteArray.reverse.padTo(4, 0.toByte).reverse.takeRight(4)) ++ zeros(208) ++
      ByteString(flag.toByte)

  "BLAKE2F" should "fail a bad length or final flag with go-ethereum's text" taggedAs (VMTest, OlympiaTest) in {
    val blake = PrecompiledContracts.Blake2bCompressionAddr
    Seq(
      Failing("empty", blake, ByteString.empty, Plenty, "invalid input length"),
      Failing("212 bytes", blake, zeros(212), Plenty, "invalid input length"),
      Failing("214 bytes", blake, zeros(214), Plenty, "invalid input length"),
      Failing("flag 2", blake, blake2f(0, 2), Plenty, "invalid final flag"),
      Failing("flag 0xff, 12 rounds, exactly 12 gas", blake, blake2f(12, 0xff), 12, "invalid final flag")
    ).foreach(check)
  }

  it should "price a bad final flag by its rounds, and run out of gas first, as go-ethereum" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    val blake = PrecompiledContracts.Blake2bCompressionAddr
    // go-ethereum blake2F.RequiredGas: the rounds for any 213-byte input, 0 for any other length
    PrecompiledContracts.Blake2bCompress.gas(blake2f(12, 2), EtcForks.Olympia, EthForks.Berlin) shouldBe 12
    PrecompiledContracts.Blake2bCompress.gas(blake2f(12, 1), EtcForks.Olympia, EthForks.Berlin) shouldBe 12
    PrecompiledContracts.Blake2bCompress.gas(zeros(212), EtcForks.Olympia, EthForks.Berlin) shouldBe 0
    Seq(
      Failing("flag 2, 65,535 rounds, 100 gas", blake, blake2f(0xffff, 2), 100, "out of gas"),
      Failing("flag 0xff, 12 rounds, 11 gas", blake, blake2f(12, 0xff), 11, "out of gas")
    ).foreach(check)
  }

  // ── KZG point evaluation (0x0a) ──────────────────────────────────────────────────────────────────────────────

  private val Kzg = "error verifying kzg proof: "
  private val KzgInfinity: ByteString = ByteString(0xc0.toByte) ++ zeros(47)

  private def set(bs: ByteString, at: Int, value: Int): ByteString = C.withByte(bs, at, _ => value)

  private def versionedHash(commitment: ByteString): ByteString = set(sha256(commitment), 0, 0x01)

  /** A 48-byte ZCash-style G1 encoding: x big-endian with `flags` in the top three bits. */
  private def compressed(flags: Int, x: BigInt): ByteString = C.withByte(C.fp(x).drop(16), 0, _ | flags)

  private def kzg(commitment: ByteString, z: BigInt, y: BigInt, proof: ByteString): ByteString =
    versionedHash(commitment) ++ word(z) ++ word(y) ++ commitment ++ proof

  /** go-ethereum core/vm/testdata/precompiles/pointEvaluation.json, pointEvaluation1: a proof that verifies. */
  private val ValidKzg = h(
    "01e798154708fe7789429634053cbf9f99b619f9f084048927333fce637f549b" +
      "564c0a11a0f704f4fc3e8acfe0f8245f0ad1347b378fbf96e206da11a5d36306" +
      "24d25032e67a7e6a4910df5834b8fe70e6bcfeeac0352434196bdf4b2485d5a1" +
      "8f59a8d2a1a625a17f3fea0fe5eb8c896db3764f3185481bc22f91b4aaffcca2" +
      "5f26936857bc3a7c2539ea8ec3a952b7873033e038326e87ed3e1276fd140253" +
      "fa08e9fc25fb2d9a98527fc22a2c9612fbeafdad446cbc7bcdbdcd780af2c16a"
  )

  "KZG point evaluation" should "fail with go-ethereum's text, in go-ethereum's check order" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    val point = PrecompiledContracts.KzgPointEvalAddr
    val r = C.R
    val NotCanonicalScalar = Kzg + "scalar is not canonical when interpreted as a big integer in big-endian"
    val subgroup = Kzg + "invalid point: subgroup check failed"
    Seq(
      Failing("191 bytes", point, zeros(191), Plenty, "invalid input length"),
      Failing("193 bytes", point, zeros(193), Plenty, "invalid input length"),
      Failing(
        "version byte 2",
        point,
        set(kzg(KzgInfinity, 0, 0, KzgInfinity), 0, 2),
        Plenty,
        "mismatched versioned hash"
      ),
      Failing(
        "another commitment's hash",
        point,
        versionedHash(zeros(48)) ++ kzg(KzgInfinity, 0, 0, KzgInfinity).drop(32),
        Plenty,
        "mismatched versioned hash"
      ),
      Failing(
        "version byte 2 before y = r",
        point,
        set(kzg(KzgInfinity, 0, r, KzgInfinity), 0, 2),
        Plenty,
        "mismatched versioned hash"
      ),
      Failing("y = r", point, kzg(KzgInfinity, 0, r, KzgInfinity), Plenty, NotCanonicalScalar),
      Failing("z = r", point, kzg(KzgInfinity, r, 0, KzgInfinity), Plenty, NotCanonicalScalar),
      Failing(
        "y = 2^256 - 1 before a bad commitment",
        point,
        kzg(ByteString(0xe0.toByte) ++ zeros(47), 0, C.Max256, KzgInfinity),
        Plenty,
        NotCanonicalScalar
      ),
      Failing(
        "z = r before a bad commitment",
        point,
        kzg(ByteString(0xe0.toByte) ++ zeros(47), r, 0, KzgInfinity),
        Plenty,
        NotCanonicalScalar
      ),
      Failing(
        "commitment mask 0b111",
        point,
        kzg(ByteString(0xe0.toByte) ++ zeros(47), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "invalid point encoding"
      ),
      Failing(
        "commitment mask 0b001",
        point,
        kzg(ByteString(0x20.toByte) ++ zeros(47), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "invalid point encoding"
      ),
      Failing(
        "commitment mask 0b011",
        point,
        kzg(ByteString(0x60.toByte) ++ zeros(47), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "invalid point encoding"
      ),
      Failing(
        "commitment uncompressed (48 zero bytes)",
        point,
        kzg(zeros(48), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "short buffer"
      ),
      Failing(
        "commitment uncompressed infinity",
        point,
        kzg(ByteString(0x40.toByte) ++ zeros(47), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "short buffer"
      ),
      Failing(
        "commitment infinity with a set bit",
        point,
        kzg(set(KzgInfinity, 47, 1), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "invalid infinity point encoding"
      ),
      Failing(
        "commitment infinity with a low flag bit",
        point,
        kzg(set(KzgInfinity, 0, 0xc1), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "invalid infinity point encoding"
      ),
      Failing(
        "commitment x = p",
        point,
        kzg(compressed(0x80, C.P), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "invalid fp.Element encoding"
      ),
      Failing(
        "commitment x = 1: x^3 + 4 is not a square",
        point,
        kzg(compressed(0x80, 1), 0, 0, KzgInfinity),
        Plenty,
        Kzg + "invalid compressed coordinate: square root doesn't exist"
      ),
      Failing("commitment (0, 2): order 3", point, kzg(compressed(0x80, 0), 0, 0, KzgInfinity), Plenty, subgroup),
      Failing("commitment (0, -2): order 3", point, kzg(compressed(0xa0, 0), 0, 0, KzgInfinity), Plenty, subgroup),
      Failing(
        "proof mask 0b111",
        point,
        kzg(KzgInfinity, 0, 0, ByteString(0xe0.toByte) ++ zeros(47)),
        Plenty,
        Kzg + "invalid point encoding"
      ),
      Failing("proof (0, 2): order 3", point, kzg(KzgInfinity, 0, 0, compressed(0x80, 0)), Plenty, subgroup),
      Failing(
        "commitment x = 1 before a bad proof",
        point,
        kzg(compressed(0x80, 1), 0, 0, ByteString(0xe0.toByte) ++ zeros(47)),
        Plenty,
        Kzg + "invalid compressed coordinate: square root doesn't exist"
      ),
      Failing(
        "commitment (0, 2) before a bad proof",
        point,
        kzg(compressed(0x80, 0), 0, 0, ByteString(0xe0.toByte) ++ zeros(47)),
        Plenty,
        subgroup
      ),
      Failing(
        "the zero polynomial claimed to be 1 at 0",
        point,
        kzg(KzgInfinity, 0, 1, KzgInfinity),
        Plenty,
        Kzg + "can't verify opening proof"
      ),
      Failing(
        "pointEvaluation1 with y + 1",
        point,
        ValidKzg.take(64) ++ word(BigInt(1, ValidKzg.slice(64, 96).toArray) + 1) ++ ValidKzg.drop(96),
        Plenty,
        Kzg + "can't verify opening proof"
      )
    ).foreach(check)
  }

  // ── BLS12-381 (0x0b - 0x11) ──────────────────────────────────────────────────────────────────────────────────

  /** go-ethereum v1.17.9 `core/vm/testdata/precompiles/fail-bls*.json` (vendored for B-153): each vector carries the
    * `ExpectedError` go-ethereum's `contracts_test.go` asserts. The pre-final MUL vectors run as MSMs at k = 1.
    */
  private val GethFailVectors: Seq[(String, Address)] = Seq(
    "fail-blsG1Add.json" -> PrecompiledContracts.BlsG1AddAddr,
    "fail-blsG1Mul.json" -> PrecompiledContracts.BlsG1MultiExpAddr,
    "fail-blsG1MultiExp.json" -> PrecompiledContracts.BlsG1MultiExpAddr,
    "fail-blsG2Add.json" -> PrecompiledContracts.BlsG2AddAddr,
    "fail-blsG2Mul.json" -> PrecompiledContracts.BlsG2MultiExpAddr,
    "fail-blsG2MultiExp.json" -> PrecompiledContracts.BlsG2MultiExpAddr,
    "fail-blsPairing.json" -> PrecompiledContracts.BlsPairingAddr,
    "fail-blsMapG1.json" -> PrecompiledContracts.BlsMapG1Addr,
    "fail-blsMapG2.json" -> PrecompiledContracts.BlsMapG2Addr
  )

  private def gethFailures(file: String, address: Address): Seq[Failing] =
    val stream = getClass.getClassLoader.getResourceAsStream(s"eip2537/geth/$file")
    val json =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    parse(json) match
      case JArray(items) =>
        items.map {
          case JObject(fields) =>
            val byKey = fields.toMap
            def text(key: String): String = byKey(key) match
              case JString(s) => s
              case other      => fail(s"$file: $key is $other")
            Failing(s"$file ${text("Name")}", address, C.unhex(text("Input")), Plenty, text("ExpectedError"))
          case other => fail(s"$file: $other")
        }
      case other => fail(s"$file: $other")

  "The BLS12-381 precompiles" should "fail every go-ethereum failure vector with its ExpectedError" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    val all = GethFailVectors.flatMap(gethFailures)
    all should have size 59
    all.foreach(check)
  }

  private val G1Subgroup = "g1 point is not on correct subgroup"
  private val G2Subgroup = "g2 point is not on correct subgroup"
  private val BlsNotOnCurve = "invalid point: not on curve"

  private def msm(pairs: (ByteString, BigInt)*): ByteString =
    pairs.map((pt, s) => pt ++ C.scalar(s)).foldLeft(ByteString.empty)(_ ++ _)

  it should "check pair by pair in go-ethereum's order" taggedAs (VMTest, OlympiaTest) in {
    val g1Msm = PrecompiledContracts.BlsG1MultiExpAddr
    val g2Msm = PrecompiledContracts.BlsG2MultiExpAddr
    val pairing = PrecompiledContracts.BlsPairingAddr
    Seq(
      Failing(
        "G1MSM subgroup before a later point off the curve",
        g1Msm,
        msm(C.G1NotInSubgroup -> 0, C.G1NotOnCurve -> 0),
        Plenty,
        G1Subgroup
      ),
      Failing("G1MSM a later point off the curve", g1Msm, msm(C.G1 -> 1, C.G1NotOnCurve -> 0), Plenty, BlsNotOnCurve),
      Failing("G1MSM the order-3 point (0, 2)", g1Msm, msm(C.G1Order3 -> 3), Plenty, G1Subgroup),
      Failing(
        "G1MSM infinity passes, then a subgroup failure",
        g1Msm,
        msm(C.G1Inf -> 0, C.G1NotInSubgroup -> 0),
        Plenty,
        G1Subgroup
      ),
      Failing(
        "G1MSM x top bytes before y = p",
        g1Msm,
        msm(C.withByte(C.withWordAt(C.G1, 64, C.P), 0, _ => 1) -> 1),
        Plenty,
        "invalid field element top bytes"
      ),
      Failing(
        "G1MSM x = p before y top bytes",
        g1Msm,
        msm(C.withByte(C.withWordAt(C.G1, 0, C.P), 64, _ => 1) -> 1),
        Plenty,
        NotCanonical
      ),
      Failing(
        "G2MSM subgroup before a later point off the curve",
        g2Msm,
        msm(C.G2NotInSubgroup -> 0, C.G2NotOnCurve -> 1),
        Plenty,
        G2Subgroup
      ),
      Failing(
        "PAIRING decodes G2 before it checks G1's subgroup",
        pairing,
        C.G1NotInSubgroup ++ C.G2NotOnCurve,
        Plenty,
        BlsNotOnCurve
      ),
      Failing("PAIRING G1's subgroup before G2's", pairing, C.G1NotInSubgroup ++ C.G2NotInSubgroup, Plenty, G1Subgroup),
      Failing("PAIRING G2's subgroup", pairing, C.G1 ++ C.G2NotInSubgroup, Plenty, G2Subgroup),
      Failing(
        "PAIRING a good pair, then G1 off the curve",
        pairing,
        C.G1 ++ C.G2 ++ C.G1NotOnCurve ++ C.G2,
        Plenty,
        BlsNotOnCurve
      ),
      Failing(
        "PAIRING infinities, then the order-3 G1",
        pairing,
        C.G1Inf ++ C.G2Inf ++ C.G1Order3 ++ C.G2,
        Plenty,
        G1Subgroup
      ),
      Failing(
        "G1ADD has no subgroup check: off the curve",
        PrecompiledContracts.BlsG1AddAddr,
        C.G1NotInSubgroup ++ C.G1NotOnCurve,
        Plenty,
        BlsNotOnCurve
      ),
      Failing(
        "MAP_FP2_TO_G2 c0 = p before c1 top bytes",
        PrecompiledContracts.BlsMapG2Addr,
        C.fp(C.P) ++ C.withByte(C.fp(1), 3, _ => 1),
        Plenty,
        NotCanonical
      )
    ).foreach(check)
  }

  it should "price fewer than one MSM or pairing pair as EIP-2537 does, so its error is its length" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    // EIP-2537 "Gas schedule clarifications" and go-ethereum RequiredGas: k = floor(length / pair size); an MSM with
    // k = 0 costs 0, a pairing 37,700 + 32,600 k
    val (etc, eth) = (EtcForks.Olympia, EthForks.Berlin)
    PrecompiledContracts.BlsG1MultiExp.gas(ByteString.empty, etc, eth) shouldBe 0
    PrecompiledContracts.BlsG1MultiExp.gas(zeros(159), etc, eth) shouldBe 0
    PrecompiledContracts.BlsG1MultiExp.gas(zeros(161), etc, eth) shouldBe 12_000
    PrecompiledContracts.BlsG2MultiExp.gas(zeros(287), etc, eth) shouldBe 0
    PrecompiledContracts.BlsG2MultiExp.gas(zeros(288), etc, eth) shouldBe 22_500
    PrecompiledContracts.BlsPairing.gas(ByteString.empty, etc, eth) shouldBe 37_700
    PrecompiledContracts.BlsPairing.gas(zeros(383), etc, eth) shouldBe 37_700
    PrecompiledContracts.BlsPairing.gas(zeros(384), etc, eth) shouldBe 70_300

    val g1Msm = PrecompiledContracts.BlsG1MultiExpAddr
    val g2Msm = PrecompiledContracts.BlsG2MultiExpAddr
    val pairing = PrecompiledContracts.BlsPairingAddr
    Seq(
      Failing("G1MSM empty, no gas", g1Msm, ByteString.empty, 0, "invalid input length"),
      Failing("G1MSM 159 bytes, no gas", g1Msm, zeros(159), 0, "invalid input length"),
      Failing("G2MSM 287 bytes, no gas", g2Msm, zeros(287), 0, "invalid input length"),
      Failing("PAIRING empty, 37,700 gas", pairing, ByteString.empty, 37_700, "invalid input length"),
      Failing("PAIRING 383 bytes, 37,700 gas", pairing, zeros(383), 37_700, "invalid input length"),
      Failing("PAIRING empty, 37,699 gas", pairing, ByteString.empty, 37_699, "out of gas"),
      Failing("G1MSM 161 bytes, one pair's price less one", g1Msm, zeros(161), 11_999, "out of gas")
    ).foreach(check)
  }

  // ── An input go-ethereum would run ───────────────────────────────────────────────────────────────────────────

  "failureReason" should "name none of go-ethereum's errors for an input go-ethereum runs" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    // `exec` never fails these; a reason is asked for only after it did.
    PrecompiledContracts.Bn128Add.failureReason(word(1) ++ word(2)) shouldBe PrecompiledContracts.UnexplainedFailure
    PrecompiledContracts.Blake2bCompress.failureReason(blake2f(12, 1)) shouldBe PrecompiledContracts.UnexplainedFailure
    PrecompiledContracts.ModExp.failureReason(modexp(1024, 1024, 1024)) shouldBe PrecompiledContracts.UnexplainedFailure
    PrecompiledContracts.BlsG1Add.failureReason(
      C.G1 ++ C.G1NotInSubgroup
    ) shouldBe PrecompiledContracts.UnexplainedFailure
    PrecompiledContracts.BlsPairing.failureReason(C.G1 ++ C.G2) shouldBe PrecompiledContracts.UnexplainedFailure
    PrecompiledContracts.KzgPointEvaluation.failureReason(ValidKzg) shouldBe PrecompiledContracts.UnexplainedFailure
    PrecompiledContracts.Identity.failureReason(ByteString(1)) shouldBe PrecompiledContracts.UnexplainedFailure
  }
