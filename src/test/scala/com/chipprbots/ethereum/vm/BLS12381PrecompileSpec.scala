package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks
import com.chipprbots.ethereum.vm.PrecompiledContracts._

/** EIP-2537: BLS12-381 precompile execution behavioral tests.
  *
  * Tests drive [[PrecompiledContracts.BlsG1Add]], [[BlsG2Add]], [[BlsG1MultiExp]], [[BlsG2MultiExp]],
  * [[BlsPairing]], [[BlsMapG1]], and [[BlsMapG2]] via their `exec` methods using the canonical EIP-2537
  * test vectors from `/EIPs/assets/eip-2537/`. Gas constant tests cover the four constant-gas precompiles.
  *
  * All tests call `assume(LibEthPairings.ENABLED)` and are cancelled (not failed) if the native
  * `libeth_pairings` shared library is absent from the test JVM.
  */
// scalastyle:off line.size.limit
class BLS12381PrecompileSpec extends AnyFlatSpec with Matchers {

  import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings

  private def h(s: String): ByteString = ByteString(Hex.decode(s))

  // ── EIP-2537 point constants ─────────────────────────────────────────────

  // BLS12-381 G1 generator (128 bytes, uncompressed: 16-byte pad + 48-byte coord × 2)
  private val g1Gen: String =
    "0000000000000000000000000000000017f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb" +
    "0000000000000000000000000000000008b3f481e3aaa0f1a09e30ed741d8ae4fcf5e095d5d00af600db18cb2c04b3edd03cc744a2888ae40caa232946c5e7e1"

  // BLS12-381 G1 point p1 (second generator from EIP-2537 test vectors)
  private val g1P1: String =
    "00000000000000000000000000000000112b98340eee2777cc3c14163dea3ec97977ac3dc5c70da32e6e87578f44912e902ccef9efe28d4a78b8999dfbca9426" +
    "00000000000000000000000000000000186b28d92356c4dfec4b5201ad099dbdede3781f8998ddf929b4cd7756192185ca7b8f4ef7088f813270ac3d48868a21"

  // G1 point at infinity (zero, 128 bytes)
  private val g1Zero: String = "00" * 128

  // BLS12-381 G2 generator (256 bytes, uncompressed: 16-byte pad + 48-byte coord × 4)
  private val g2Gen: String =
    "00000000000000000000000000000000024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8" +
    "0000000000000000000000000000000013e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e" +
    "000000000000000000000000000000000ce5d527727d6e118cc9cdc6da2e351aadfd9baa8cbdd3a76d429a695160d12c923ac9cc3baca289e193548608b82801" +
    "000000000000000000000000000000000606c4a02ea734cc32acd2b02bc28b99cb3e287e85a763af267492ab572e99ab3f370d275cec1da1aaa9075ff05f79be"

  // BLS12-381 G2 point p2
  private val g2P2: String =
    "00000000000000000000000000000000103121a2ceaae586d240843a398967325f8eb5a93e8fea99b62b9f88d8556c80dd726a4b30e84a36eeabaf3592937f27" +
    "00000000000000000000000000000000086b990f3da2aeac0a36143b7d7c824428215140db1bb859338764cb58458f081d92664f9053b50b3fbd2e4723121b68" +
    "000000000000000000000000000000000f9e7ba9a86a8f7624aa2b42dcc8772e1af4ae115685e60abc2c9b90242167acef3d0be4050bf935eed7c3b6fc7ba77e" +
    "000000000000000000000000000000000d22c3652d0dc6f0fc9316e14268477c2049ef772e852108d269d9c38dba1d4802e8dae479818184c08f9a569d878451"

  // ── Tests ────────────────────────────────────────────────────────────────

  "BlsG1Add" should "add two valid G1 points and return the correct sum" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    val input    = h(g1Gen + g1P1)
    val expected = h(
      "000000000000000000000000000000000a40300ce2dec9888b60690e9a41d3004fda4886854573974fab73b046d3147ba5b7a5bde85279ffede1b45b3918d82d" +
      "0000000000000000000000000000000006d3d887e9f53b9ec4eb6cedf5607226754b07c01ace7834f57f3e7315faefb739e59018e22c492006190fba4a870025"
    )
    BlsG1Add.exec(input) shouldBe Some(expected)
  }

  it should "return the point unchanged when adding the identity (G1 + 0 = G1)" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    val input    = h(g1Gen + g1Zero)
    val expected = h(g1Gen)
    BlsG1Add.exec(input) shouldBe Some(expected)
  }

  it should "return None for empty input" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    BlsG1Add.exec(ByteString.empty) shouldBe None
  }

  "BlsG2Add" should "add two valid G2 points and return the correct sum" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    val input    = h(g2Gen + g2P2)
    val expected = h(
      "000000000000000000000000000000000b54a8a7b08bd6827ed9a797de216b8c9057b3a9ca93e2f88e7f04f19accc42da90d883632b9ca4dc38d013f71ede4db" +
      "00000000000000000000000000000000077eba4eecf0bd764dce8ed5f45040dd8f3b3427cb35230509482c14651713282946306247866dfe39a8e33016fcbe52" +
      "000000000000000000000000000000014e60a76a29ef85cbd69f251b9f29147b67cfe3ed2823d3f9776b3a0efd2731941d47436dc6d2b58d9e65f8438bad073" +
      "000000000000000000000000000000001586c3c910d95754fef7a732df78e279c3d37431c6a2b77e67a00c7c130a8fcd4d19f159cbeb997a178108fffffcbd20"
    )
    BlsG2Add.exec(input) shouldBe Some(expected)
  }

  it should "return None for empty input" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    BlsG2Add.exec(ByteString.empty) shouldBe None
  }

  "BlsG1MultiExp" should "compute scalar multiplication 1*G1 = G1" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    // Input: 128-byte G1 point + 32-byte scalar 1
    val scalar1  = "0000000000000000000000000000000000000000000000000000000000000001"
    val input    = h(g1Gen + scalar1)
    val expected = h(g1Gen)
    BlsG1MultiExp.exec(input) shouldBe Some(expected)
  }

  "BlsG2MultiExp" should "compute scalar multiplication 1*G2 = G2" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    // Input: 256-byte G2 point + 32-byte scalar 1
    val scalar1  = "0000000000000000000000000000000000000000000000000000000000000001"
    val input    = h(g2Gen + scalar1)
    val expected = h(g2Gen)
    BlsG2MultiExp.exec(input) shouldBe Some(expected)
  }

  "BlsPairing" should "return 0x01 for a single pair of identity points (vacuous truth)" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    // One pair of (G1=0, G2=0): e(0, 0) = 1 in GT, so check succeeds.
    val input    = ByteString(new Array[Byte](384)) // 128 zero G1 + 256 zero G2
    val expected = h("0000000000000000000000000000000000000000000000000000000000000001")
    BlsPairing.exec(input) shouldBe Some(expected)
  }

  it should "return 0x00 for a single non-trivial pair (G1, G2) — pairing ≠ 1 in GT" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    // e(G1, G2) is non-identity in GT — the product-of-pairings check returns 0.
    val input    = h(g1Gen + g2Gen)
    val expected = h("0000000000000000000000000000000000000000000000000000000000000000")
    BlsPairing.exec(input) shouldBe Some(expected)
  }

  "BlsMapG1" should "map a valid Fp field element to a G1 point" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    // EIP-2537 bls_g1map_ test vector
    val input    = h("00000000000000000000000000000000156c8a6a2c184569d69a76be144b5cdc5141d2d2ca4fe341f011e25e3969c55ad9e9b9ce2eb833c81a908e5fa4ac5f03")
    val expected = h(
      "00000000000000000000000000000000184bb665c37ff561a89ec2122dd343f20e0f4cbcaec84e3c3052ea81d1834e192c426074b02ed3dca4e7676ce4ce48ba" +
      "0000000000000000000000000000000004407b8d35af4dacc809927071fc0405218f1401a6d15af775810e4e460064bcc9468beeba82fdc751be70476c888bf3"
    )
    BlsMapG1.exec(input) shouldBe Some(expected)
  }

  "BlsMapG2" should "map a valid Fp2 field element to a G2 point" taggedAs (OlympiaTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    // EIP-2537 bls_g2map_ test vector
    val input    = h(
      "0000000000000000000000000000000007355d25caf6e7f2f0cb2812ca0e513bd026ed09dda65b177500fa31714e09ea0ded3a078b526bed3307f804d4b93b04" +
      "0000000000000000000000000000000002829ce3c021339ccb5caf3e187f6370e1e2a311dec9b75363117063ab2015603ff52c3d3b98f19c2f65575e99e8b78c"
    )
    val expected = h(
      "0000000000000000000000000000000000e7f4568a82b4b7dc1f14c6aaa055edf51502319c723c4dc2688c7fe5944c213f510328082396515734b6612c4e7bb7" +
      "00000000000000000000000000000000126b855e9e69b1f691f816e48ac6977664d24d99f8724868a184186469ddfd4617367e94527d4b74fc86413483afb35b" +
      "000000000000000000000000000000000caead0fd7b6176c01436833c79d305c78be307da5f6af6c133c47311def6ff1e0babf57a0fb5539fce7ee12407b0a42" +
      "000000000000000000000000000000001498aadcf7ae2b345243e281ae076df6de84455d766ab6fcdaad71fab60abb2e8b980a440043cd305db09d283c895e3d"
    )
    BlsMapG2.exec(input) shouldBe Some(expected)
  }

  "BLS12-381 gas constants" should "match EIP-2537 specification" taggedAs (OlympiaTest, VMTest) in {
    val etc  = EtcForks.Olympia
    val eth  = EthForks.Berlin
    val in   = ByteString.empty
    BlsG1Add.gas(in, etc, eth)  shouldBe BigInt(375)
    BlsG2Add.gas(in, etc, eth)  shouldBe BigInt(600)
    BlsMapG1.gas(in, etc, eth)  shouldBe BigInt(5500)
    BlsMapG2.gas(in, etc, eth)  shouldBe BigInt(23800)
  }
}
// scalastyle:on line.size.limit
