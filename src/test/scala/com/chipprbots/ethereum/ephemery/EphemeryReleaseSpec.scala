package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.ephemery.ReleaseRefusal.*
import com.chipprbots.ethereum.ephemery.StrictJson.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.BlockchainsConfig

/** Every rule of the Ephemery release engine (spec 028 research R2, beacon's conditions on the encoder), each with its
  * refusal case, its exit class and the wording `fetch-genesis.sh` used. A test starts from the raw inputs of
  * ephemery-165 and changes one field of an in-memory copy.
  */
class EphemeryReleaseSpec extends AnyFlatSpec with Matchers:

  private val base: ReleaseInputs = raw(165)

  private def refused(in: ReleaseInputs): ReleaseRefusal =
    EphemeryRelease.validate(in) match
      case Left(r)  => r
      case Right(v) => fail(s"expected a refusal, got the release ${v.release}")

  private def valid(in: ReleaseInputs): EphemeryRelease.ValidRelease =
    EphemeryRelease.validate(in).fold(r => fail(r.message), identity)

  /** The refusal is `kind`, exits 78 and its message includes every one of `texts`. */
  private def refusesWith[K <: ReleaseRefusal](in: ReleaseInputs, texts: String*)(using
      ct: scala.reflect.ClassTag[K]
  ): Unit =
    val r = refused(in)
    withClue(s"${r.getClass.getSimpleName}: ${r.message}: ") {
      ct.runtimeClass.isInstance(r) shouldBe true
      r.exitCode shouldBe 78
      texts.foreach(t => r.message should include(t))
    }

  private def exactly(n: BigInt): JNum = JNum(n.toString)

  // ---------------------------------------------------------------------------------------------------------------
  "a valid release" should "be accepted for ephemery-164 and ephemery-165" taggedAs (UnitTest) in {
    valid(raw(164)).iteration shouldBe 164
    valid(raw(165)).iteration shouldBe 165
    valid(raw(165)).chainId shouldBe BigInt(39438165)
  }

  it should "carry its reset time: genesis timestamp plus the interval (N4)" taggedAs (UnitTest) in {
    val v = valid(base)
    v.genesisTimestamp shouldBe 1792695600L
    v.resetInterval shouldBe 2419200L
    v.resetTime shouldBe 1795114800L // 2026-11-19 19:00 UTC
    valid(base.retention("GENESIS_RESET_INTERVAL", "1000")).resetTime shouldBe 1792696600L
    valid(base.withoutRetention("GENESIS_RESET_INTERVAL")).resetInterval shouldBe 2419200L
  }

  it should "refuse a retention.vars without CHAIN_ID, ITERATION_NUMBER or GENESIS_TIMESTAMP, or an empty one (B1)" taggedAs (
    UnitTest
  ) in {
    refusesWith[Inconsistent](base.copy(retentionVars = ""), "retention.vars has no CHAIN_ID")
    refusesWith[Inconsistent](base.withoutRetention("CHAIN_ID"), "retention.vars has no CHAIN_ID")
    refusesWith[Inconsistent](base.withoutRetention("ITERATION_NUMBER"), "retention.vars has no ITERATION_NUMBER")
    refusesWith[Inconsistent](base.withoutRetention("GENESIS_TIMESTAMP"), "retention.vars has no GENESIS_TIMESTAMP")
    // optional ones stay optional
    valid(base.withoutRetention("ITERATION_RELEASE").withoutRetention("GENESIS_RESET_INTERVAL")).iteration shouldBe 165
  }

  it should "keep a number above 2^53 exact" taggedAs (UnitTest) in {
    val big = BigInt("123456789012345678901234567890")
    val in = base.editConfig(
      _.edit("blobSchedule")(
        _.edit("bpo2")(_.set("baseFeeUpdateFraction", exactly(big)))
      )
    )
    val v = valid(in)
    v.chainConf should include(s"base-fee-update-fraction = $big")
  }

  it should "give a hash that does not depend on the order of the genesis keys" taggedAs (UnitTest) in {
    val shuffled = base.editGenesis(g => JObj(g.fields.reverse))
    valid(shuffled).genesisHashHex shouldBe Hash165
  }

  // ---------------------------------------------------------------------------------------------------------------
  "R1" should "refuse a genesis without config.chainId, or with one that is not an integer" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](base.editConfig(_.without("chainId")), "genesis.json has no config.chainId")
    refusesWith[Inconsistent](
      base.editConfig(_.set("chainId", JStr("39438165"))),
      "config.chainId is not an integer: 39438165"
    )
    refusesWith[Inconsistent](
      base.editConfig(_.set("chainId", JNum("1.5"))),
      "config.chainId is not an integer: 1.5"
    )
    refusesWith[Inconsistent](base.editGenesis(_.without("config")), "genesis.json has no config.chainId")
  }

  "R2" should "refuse a retention.vars CHAIN_ID that disagrees with genesis.json" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      base.retention("CHAIN_ID", "39438164"),
      "retention.vars CHAIN_ID=39438164 disagrees with genesis.json chainId=39438165"
    )
  }

  "N2" should "refuse an iteration that is not the chain id minus 39438000" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      base.retention("ITERATION_NUMBER", "164"),
      "ITERATION_NUMBER=164",
      "iteration 165"
    )
    refusesWith[Inconsistent](
      base.retention("ITERATION_RELEASE", "ephemery-164"),
      "ITERATION_RELEASE=ephemery-164"
    )
    val notEphemery = base.editConfig(_.set("chainId", JNum("1"))).retention("CHAIN_ID", "1")
    refusesWith[Inconsistent](notEphemery, "is not an Ephemery chain id")
  }

  "R3" should "refuse a config key it cannot map, such as bpo3Time" taggedAs (UnitTest) in {
    val r = refused(base.editConfig(_.set("bpo3Time", num(1795114800L))))
    r shouldBe a[CannotRepresent]
    r.exitCode shouldBe 78
    r.message should include("genesis config declares keys fukuii cannot map: bpo3Time")
    r.message should include("a newer fukuii")
  }

  "R4" should "refuse a block fork that is not 0" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editConfig(_.set("londonBlock", num(5))),
      "block forks after genesis are not supported for Ephemery (all must be 0): londonBlock"
    )
    refusesWith[CannotRepresent](
      base.editConfig(_.set("berlinBlock", JStr("0"))),
      "(all must be 0): berlinBlock"
    )
  }

  "R5" should "refuse a terminal total difficulty that is not 0" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editConfig(_.set("terminalTotalDifficulty", num(1))),
      "terminalTotalDifficulty must be 0 (Ephemery is merged at genesis)"
    )
    refusesWith[CannotRepresent](
      base.editConfig(_.without("terminalTotalDifficulty")),
      "terminalTotalDifficulty must be 0"
    )
  }

  "beacon 11" should "refuse terminalTotalDifficultyPassed that is not true" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editConfig(_.set("terminalTotalDifficultyPassed", JBool(false))),
      "terminalTotalDifficultyPassed must be true"
    )
    valid(base.editConfig(_.without("terminalTotalDifficultyPassed"))).release shouldBe "ephemery-165"
  }

  "R6" should "refuse a fork time that is not an integer or null, and a missing shanghai, cancun or prague" taggedAs (
    UnitTest
  ) in {
    refusesWith[CannotRepresent](
      base.editConfig(_.set("osakaTime", JNum("1.5"))),
      "config.osakaTime is not an integer: 1.5"
    )
    refusesWith[CannotRepresent](base.editConfig(_.set("osakaTime", JNum("1e3"))), "config.osakaTime is not an integer")
    refusesWith[CannotRepresent](
      base.editConfig(_.set("osakaTime", JStr("soon"))),
      "config.osakaTime is not an integer: soon"
    )
    refusesWith[CannotRepresent](
      base.editConfig(_.set("osakaTime", JStr("0x8000000000000000"))),
      "config.osakaTime is above 2^63-1"
    )
    refusesWith[CannotRepresent](
      base.editConfig(_.without("shanghaiTime")),
      "Ephemery genesis without SHANGHAI is not supported (expected it active at genesis)"
    )
    refusesWith[CannotRepresent](base.editConfig(_.set("cancunTime", JNull)), "Ephemery genesis without CANCUN")
    refusesWith[CannotRepresent](base.editConfig(_.without("pragueTime")), "Ephemery genesis without PRAGUE")
  }

  it should "read an optional fork that is null as absent, which changes the header shape and so the hash" taggedAs (UnitTest) in {
    // 165 has every fork at genesis; without amsterdam the header is the Prague shape: no rule of R6 refuses it, and N3
    // is what notices.
    val r = refused(base.editConfig(_.set("amsterdamTime", JNull)))
    r shouldBe a[HashMismatch]
  }

  "beacon 14" should "refuse fork timestamps out of the order of the forks" taggedAs (UnitTest) in {
    val in = raw(164).editConfig(_.set("osakaTime", num(1791063433L))) // bpo1Time is 1791063432
    refusesWith[Inconsistent](
      in,
      "fork timestamps are not in the order of the forks: osakaTime (1791063433) is after bpo1Time"
    )
  }

  it should "refuse a shanghai, cancun or prague time after the genesis timestamp" taggedAs (UnitTest) in {
    val after = num(1792695601L) // one second after the genesis timestamp
    refusesWith[CannotRepresent](
      base.editConfig(
        _.set("pragueTime", after)
          .set("osakaTime", after)
          .set("bpo1Time", after)
          .set("bpo2Time", after)
          .set("amsterdamTime", after)
      ),
      "pragueTime (1792695601) after the genesis timestamp (1792695600)"
    )
  }

  "R7" should "refuse a deposit contract address that is missing or malformed" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      base.editConfig(_.set("depositContractAddress", JStr("0x1234"))),
      "config.depositContractAddress missing or malformed: 0x1234"
    )
    refusesWith[Inconsistent](
      base.editConfig(_.without("depositContractAddress")),
      "config.depositContractAddress missing or malformed: null"
    )
  }

  "beacon 12" should "warn, and not refuse, when the deposit contract is not in the alloc" taggedAs (UnitTest) in {
    val v = valid(base.editConfig(_.set("depositContractAddress", JStr("0x000000000000000000000000000000000000dEaD"))))
    v.warnings.exists(
      _.contains("0x000000000000000000000000000000000000dEaD is not in the genesis alloc")
    ) shouldBe true
  }

  "R8" should "refuse a blobSchedule fork it has no blob fork for" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editConfig(
        _.edit("blobSchedule")(
          _.set("bpo3", JObj(Vector("target" -> num(1), "max" -> num(2), "baseFeeUpdateFraction" -> num(3))))
        )
      ),
      "blobSchedule declares fork(s) fukuii has no blob fork for: bpo3"
    )
  }

  it should "refuse an entry with a missing or non-integer field" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editConfig(_.edit("blobSchedule")(_.edit("bpo1")(_.without("max")))),
      "blobSchedule.bpo1 has a missing or non-integer field (target/max/baseFeeUpdateFraction = 8/null/6676955)"
    )
    refusesWith[CannotRepresent](
      base.editConfig(_.edit("blobSchedule")(_.edit("bpo1")(_.set("target", JNum("8.5"))))),
      "blobSchedule.bpo1 has a missing or non-integer field",
      "8.5"
    )
  }

  "beacon 13" should "refuse an entry for a fork the config does not declare" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      raw(164).editConfig(
        _.without("bpo2Time")
      ),
      "blobSchedule has an entry for bpo2, but config.bpo2Time is not declared"
    )
  }

  it should "run the rendered configuration through BlobSchedule, so a bad schedule fails at fetch" taggedAs (
    UnitTest
  ) in {
    // max below target
    refusesWith[CannotRepresent](
      base.editConfig(_.edit("blobSchedule")(_.edit("bpo1")(_.set("max", num(1))))),
      "invalid blob-schedule",
      "bpo1.max"
    )
    // a declared blob fork with no entry
    refusesWith[CannotRepresent](
      base.editConfig(_.edit("blobSchedule")(_.without("bpo2"))),
      "invalid blob-schedule",
      "bpo2"
    )
    // an osaka entry that is not what osaka inherits
    refusesWith[CannotRepresent](
      base.editConfig(
        _.edit("blobSchedule")(
          _.set("osaka", JObj(Vector("target" -> num(1), "max" -> num(2), "baseFeeUpdateFraction" -> num(3))))
        )
      ),
      "invalid blob-schedule",
      "osaka"
    )
  }

  "R9" should "refuse a top-level key it does not read, and a slotNumber (it is not in the allow-list)" taggedAs (
    UnitTest
  ) in {
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("surprise", JStr("x"))),
      "genesis.json declares top-level keys fukuii does not read: surprise"
    )
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("slotNumber", JStr("0x1"))),
      "top-level keys fukuii does not read: slotNumber"
    )
  }

  it should "refuse a non-zero parentHash, number or gasUsed" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("number", JStr("0x1"))),
      "genesis.json sets non-zero values fukuii does not read: number"
    )
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("gasUsed", num(5))),
      "non-zero values fukuii does not read: gasUsed"
    )
    valid(base.editGenesis(_.set("parentHash", JStr("0x" + "0" * 64)))).genesisHashHex shouldBe Hash165
  }

  it should "refuse mixHash and mixhash that differ, and write only mixHash" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("mixHash", JStr("0x" + "1" * 64))),
      "genesis.json has both mixHash and mixhash with different values"
    )
    val v = valid(base)
    v.encodedGenesis should include("\"mixHash\"")
    (v.encodedGenesis should not).include("\"mixhash\"")
  }

  "R10" should "re-encode a decimal quantity as hex, and refuse a decimal of 10^18 or more" taggedAs (UnitTest) in {
    val v = valid(base) // timestamp is the decimal string "1792695600"
    v.encodedGenesis should include("\"timestamp\": \"0x6ada5d30\"")
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("gasLimit", JStr("1000000000000000000"))),
      "genesis.gasLimit is neither 0x-hex nor a decimal below 10^18: '1000000000000000000'"
    )
    refusesWith[CannotRepresent](base.editGenesis(_.set("timestamp", JStr("soon"))), "genesis.timestamp is neither")
    refusesWith[CannotRepresent](base.editGenesis(_.without("timestamp")), "genesis.timestamp missing")
    refusesWith[CannotRepresent](
      base.editGenesis(_.without("gasLimit")),
      "genesis.gasLimit is neither 0x-hex nor a decimal below 10^18: 'null'"
    )
  }

  "beacon 1" should "refuse a hex quantity above 2^63-1, in every header quantity" taggedAs (UnitTest) in {
    val tooBig = JStr("0x8000000000000000")
    List("timestamp", "gasLimit", "difficulty", "baseFeePerGas", "blobGasUsed", "excessBlobGas").foreach { field =>
      withClue(field) {
        refusesWith[CannotRepresent](base.editGenesis(_.set(field, tooBig)), s"genesis.$field is above 2^63-1")
      }
    }
    // at the ceiling it is read, and then N3 decides
    refused(base.editGenesis(_.set("gasLimit", JStr("0x7fffffffffffffff")))) shouldBe a[HashMismatch]
  }

  it should "compare as BigInt, so a value that wraps a Long is refused too" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("timestamp", JStr("0x10000000000000000"))),
      "genesis.timestamp is above 2^63-1"
    )
  }

  "beacon 2" should "write a quantity as lowercase 0x and digits without leading zeros, zero as 0x0" taggedAs (
    UnitTest
  ) in {
    // the encoder alone: these values would not give the published hash
    val genesis = parsed(base.genesisJson)
      .set("difficulty", JStr("0X00000001"))
      .set("baseFeePerGas", JStr("0x0"))
      .set("blobGasUsed", num(0))
      .set("excessBlobGas", JStr("00000012"))
    val json = GenesisJsonEncoder.encode(genesis).fold(r => fail(r.message), _.json)
    json should include("\"difficulty\": \"0x1\"")
    json should include("\"baseFeePerGas\": \"0x0\"")
    json should include("\"blobGasUsed\": \"0x0\"")
    json should include("\"excessBlobGas\": \"0xc\"") // decimal 12
  }

  it should "refuse a bare 0x, a float and an exponent" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](base.editGenesis(_.set("gasLimit", JStr("0x"))), "genesis.gasLimit is neither")
    refusesWith[CannotRepresent](base.editGenesis(_.set("gasLimit", JNum("36000000.0"))), "genesis.gasLimit is neither")
    refusesWith[CannotRepresent](base.editGenesis(_.set("gasLimit", JNum("3.6e7"))), "genesis.gasLimit is neither")
    refusesWith[CannotRepresent](base.editGenesis(_.set("gasLimit", JNum("-1"))), "genesis.gasLimit is neither")
  }

  "beacon 3" should "refuse a nonce that is not 0x and 1 to 16 hex digits (a decimal would be read as hex)" taggedAs (
    UnitTest
  ) in {
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("nonce", JStr("1234"))),
      "genesis.nonce must be 0x followed by 1 to 16 hex digits"
    )
    refusesWith[CannotRepresent](base.editGenesis(_.set("nonce", JStr("0x12345678901234567"))), "genesis.nonce")
    refusesWith[CannotRepresent](base.editGenesis(_.set("nonce", JStr("0x"))), "genesis.nonce")
    refusesWith[CannotRepresent](base.editGenesis(_.set("nonce", num(1234))), "genesis.nonce")
  }

  it should "refuse a mixHash that is not 32 bytes, a coinbase that is not 20, an extraData above 32" taggedAs (
    UnitTest
  ) in {
    refusesWith[CannotRepresent](
      base.editGenesis(_.without("mixhash").set("mixHash", JStr("0x" + "0" * 62))),
      "genesis.mixHash must be 0x followed by 64 hex digits"
    )
    refusesWith[CannotRepresent](base.editGenesis(_.set("coinbase", JStr("0x" + "0" * 38))), "genesis.coinbase must be")
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("extraData", JStr("0x" + "00" * 33))),
      "genesis.extraData must be"
    )
    refusesWith[CannotRepresent](base.editGenesis(_.set("extraData", JStr("0x123"))), "genesis.extraData must be")
    // 32 bytes is allowed: it gets as far as the hash, which is then not the published one
    refused(base.editGenesis(_.set("extraData", JStr("0x" + "00" * 32)))) shouldBe a[HashMismatch]
  }

  "beacon 4" should "refuse an alloc balance or nonce of 2^256 or more, or that is not a number" taggedAs (UnitTest) in {
    val big = JStr((BigInt(1) << 256).toString)
    def withAccount(f: JObj => JObj): ReleaseInputs =
      base.editGenesis(
        _.edit("alloc")(a =>
          a.set("0x0000000000000000000000000000000000000000", f(a.obj("0x0000000000000000000000000000000000000000")))
        )
      )
    refusesWith[CannotRepresent](withAccount(_.set("balance", big)), "is 2^256 or more")
    refusesWith[CannotRepresent](withAccount(_.set("nonce", JStr("0x1" + "0" * 64))), "is 2^256 or more")
    refusesWith[CannotRepresent](withAccount(_.set("balance", JStr("-1"))), "is not a 0x-hex or decimal number")
    refusesWith[CannotRepresent](withAccount(_.set("balance", JStr(""))), "is not a 0x-hex or decimal number")
  }

  it should "keep: a balance or nonce must be a JSON string (counted as the script did)" taggedAs (UnitTest) in {
    def withAccount(f: JObj => JObj): ReleaseInputs =
      base.editGenesis(
        _.edit("alloc")(a =>
          a.set("0x0000000000000000000000000000000000000000", f(a.obj("0x0000000000000000000000000000000000000000")))
        )
      )
    refusesWith[Inconsistent](
      withAccount(_.set("balance", num(1))),
      "1 alloc balance/nonce values are not JSON strings"
    )
  }

  it should "refuse an alloc key longer than 40 hex digits, or not hex" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editGenesis(_.edit("alloc")(_.set("0x" + "a" * 41, JObj(Vector("balance" -> JStr("1")))))),
      "is not a hex address of 1 to 40 digits"
    )
    refusesWith[CannotRepresent](
      base.editGenesis(_.edit("alloc")(_.set("0xnothex", JObj(Vector("balance" -> JStr("1")))))),
      "is not a hex address"
    )
  }

  it should "refuse two keys that are one address (case, prefix, or padding)" taggedAs (UnitTest) in {
    val account = JObj(Vector("balance" -> JStr("1")))
    val one = "0xaBcDef0000000000000000000000000000000001"
    refusesWith[Inconsistent](
      base.editGenesis(_.edit("alloc")(_.set(one, account).set(one.toLowerCase.replace("0x", "0X"), account))),
      "alloc declares the account 0xabcdef0000000000000000000000000000000001 twice"
    )
    refusesWith[Inconsistent](
      base.editGenesis(
        _.edit("alloc")(_.set("0x0000000000000000000000000000000000000100", account).set("100", account))
      ),
      "twice"
    )
  }

  it should "refuse an account field it does not read" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      base.editGenesis(
        _.edit("alloc")(
          _.set(
            "0x0000000000000000000000000000000000000000",
            JObj(Vector("balance" -> JStr("1"), "secretKey" -> JStr("0x01")))
          )
        )
      ),
      "declares keys fukuii does not read: secretKey"
    )
  }

  "beacon 10" should "refuse a GENESIS_RESET_INTERVAL that is not an integer above 0" taggedAs (UnitTest) in {
    List("0", "abc", "", "99999999999999999999").foreach { v =>
      withClue(s"'$v' ") {
        refusesWith[Inconsistent](
          base.retention("GENESIS_RESET_INTERVAL", v),
          "GENESIS_RESET_INTERVAL must be an integer above 0"
        )
      }
    }
  }

  "beacon 15" should "refuse a duplicate key anywhere in genesis.json" taggedAs (UnitTest) in {
    val text = base.genesisJson
    val inConfig = text.replaceFirst("\"chainId\": 39438165", "\"chainId\": 39438165, \"chainId\": 1")
    val r1 = refused(base.copy(genesisJson = inConfig))
    r1 shouldBe a[Inconsistent]
    r1.message should include("the key \"chainId\" is declared twice")
    val inAlloc = text.replaceFirst(
      "\"0x0000000000000000000000000000000000000001\": \\{",
      "\"0x0000000000000000000000000000000000000001\": {\"balance\": \"2\"},\n    \"0x0000000000000000000000000000000000000001\": {"
    )
    inAlloc should not be text
    refused(base.copy(genesisJson = inAlloc)).message should include("is declared twice")
  }

  "R11" should "refuse a retention.vars GENESIS_TIMESTAMP that disagrees with genesis.json" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      base.retention("GENESIS_TIMESTAMP", "1792695601"),
      "retention.vars GENESIS_TIMESTAMP=1792695601 disagrees with genesis.json timestamp=1792695600"
    )
  }

  it should "refuse a chainspec.json whose genesis timestamp disagrees, and accept one that agrees" taggedAs (UnitTest) in {
    def chainspec(ts: String) = base.copy(chainspecJson = Some(s"""{"genesis": {"timestamp": "$ts"}}"""))
    refusesWith[Inconsistent](
      chainspec("0x6ada5d31"),
      "chainspec.json genesis timestamp 0x6ada5d31 disagrees with genesis.json 1792695600"
    )
    valid(chainspec("0x6ada5d30")).release shouldBe "ephemery-165"
    valid(chainspec("1792695600")).release shouldBe "ephemery-165"
  }

  "N1" should "refuse a published hash that is absent or malformed" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      base.copy(publishedHashText = ""),
      "deposit_contract_block_hash.txt does not hold 0x and 64 hex digits"
    )
    refusesWith[Inconsistent](base.copy(publishedHashText = "0x1234\n"), "does not hold 0x and 64 hex digits")
    valid(base.copy(publishedHashText = Hash165.toUpperCase.replace("0X", "0x") + "\n")).genesisHashHex shouldBe Hash165
  }

  "N3" should "refuse a computed genesis hash that is not the published one, as a plain HashMismatch" taggedAs (
    UnitTest
  ) in {
    val other = "0x" + "ab" * 32
    refused(base.copy(publishedHashText = other)) shouldBe HashMismatch(other, Hash165)
    refused(base.copy(publishedHashText = other)).message should include(
      s"computed genesis $Hash165 does not match the published $other"
    )
    // one changed balance
    val r = refused(
      base.editGenesis(
        _.edit("alloc")(a => a.set("0x0000000000000000000000000000000000000000", JObj(Vector("balance" -> JStr("2")))))
      )
    )
    r shouldBe a[HashMismatch]
  }

  it should "need the rendered configuration: the header shape follows it, not the genesis file" taggedAs (UnitTest) in {
    // Amsterdam at genesis is the 23-field header (165); dropping amsterdamTime gives the Prague header and another hash.
    refused(base.editConfig(_.without("amsterdamTime"))) shouldBe a[HashMismatch]
  }

  "the inputs" should "refuse a genesis.json that is not JSON, or not an object" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](base.copy(genesisJson = "{"), "genesis.json is not valid JSON")
    refusesWith[Inconsistent](base.copy(genesisJson = "[]"), "genesis.json is not a JSON object")
    refusesWith[Inconsistent](base.copy(genesisJson = "{} x"), "genesis.json is not valid JSON")
  }

  it should "refuse a retention.vars that assigns a name twice with different values" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      base.copy(retentionVars = base.retentionVars + "export CHAIN_ID=\"1\"\n"),
      "retention.vars assigns CHAIN_ID twice"
    )
  }

  it should "not copy a bootnode or ENR line that could be read as syntax" taggedAs (UnitTest) in {
    refusesWith[Inconsistent](
      base.copy(enodesText = Some("enode://abc\"@1.2.3.4:30303\n")),
      "enodes.txt holds a line fukuii will not copy"
    )
    refusesWith[Inconsistent](
      base.copy(bootEnrText = Some("enr:-abc$(rm)\n")),
      "boot_enr.txt holds a line fukuii will not copy"
    )
  }

  it should "warn, and not refuse, when the release ships no EL enodes" taggedAs (UnitTest) in {
    val v = valid(base.copy(enodesText = None))
    v.bootstrapNodes shouldBe empty
    v.warnings.exists(_.contains("release ships no EL enodes")) shouldBe true
    v.chainConf should include("bootstrap-nodes = [\n\n  ]")
  }

  "ReleaseInputs.fromDir" should "refuse a directory without genesis.json as a BadSource" taggedAs (UnitTest) in {
    val dir = Files.createTempDirectory("eph-release-test")
    try
      val r = ReleaseInputs.fromDir(dir).left.getOrElse(fail("expected a refusal"))
      r shouldBe a[BadSource]
      r.exitCode shouldBe 78
      r.message should include("has no genesis.json")
      Files.write(dir.resolve("genesis.json"), "{}".getBytes(StandardCharsets.UTF_8))
      ReleaseInputs.fromDir(dir).left.getOrElse(fail("expected a refusal")).message should include(
        "has no retention.vars"
      )
    finally
      Files.list(dir).forEach(f => Files.delete(f))
      Files.delete(dir)
  }

  "M2" should "refuse a retention.vars line that is not export NAME=\"value\" (a comment line or blank is fine)" taggedAs (
    UnitTest
  ) in {
    ReleaseInputs.parseRetention("# c\n\nexport A=\"1\"\nexport B=2\nexport C=\"\"\n") shouldBe Right(
      Map("A" -> "1", "B" -> "2", "C" -> "")
    )
    List(
      "export A=\"1\" # trailing comment",
      "  export A=\"1\"",
      "A=\"1\"",
      "export A='1'",
      "export A=\"1",
      "export A=1\"",
      "export A=\"a b\"",
      "export a=\"1\""
    ).foreach { bad =>
      withClue(bad) {
        ReleaseInputs.parseRetention(s"export OK=\"1\"\n$bad\n").left.getOrElse(fail("accepted")) should include(
          "retention.vars line 2"
        )
      }
    }
    refusesWith[Inconsistent](
      base.copy(retentionVars = base.retentionVars + "export X='1'\n"),
      "retention.vars line"
    )
  }

  "B2" should "refuse a storage key or value without 0x, which the loader would read as decimal" taggedAs (UnitTest) in {
    def withStorage(slots: (String, Js)*): ReleaseInputs =
      base.editGenesis(
        _.edit("alloc")(a =>
          a.set(
            "0x0000000000000000000000000000000000000001",
            JObj(Vector("balance" -> JStr("1"), "storage" -> JObj(slots.toVector)))
          )
        )
      )
    refusesWith[CannotRepresent](withStorage("1" -> JStr("0x1")), "storage key must be 0x-prefixed hex")
    refusesWith[CannotRepresent](withStorage("0x1" -> JStr("5")), "storage value must be 0x-prefixed hex")
    refusesWith[CannotRepresent](withStorage("0x1" -> num(5)), "storage value must be 0x-prefixed hex")
    // prefixed: it gets as far as the hash
    refused(withStorage("0x1" -> JStr("0x5"))) shouldBe a[HashMismatch]
  }

  "B3" should "refuse a gap in the fork schedule" taggedAs (UnitTest) in {
    refusesWith[CannotRepresent](
      raw(164).editConfig(c => c.without("osakaTime").edit("blobSchedule")(_.without("osaka"))),
      "config.bpo1Time is declared but config.osakaTime is not"
    )
    refusesWith[CannotRepresent](
      raw(164).editConfig(c => c.without("bpo1Time").edit("blobSchedule")(_.without("bpo1"))),
      "config.bpo2Time is declared but config.bpo1Time is not"
    )
    refusesWith[CannotRepresent](
      base.editConfig(_.without("osakaTime")),
      "is declared but config.osakaTime is not"
    )
    valid(raw(164)).iteration shouldBe 164
  }

  "M4" should "refuse a JSON number of more than 80 characters" taggedAs (UnitTest) in {
    val long = "1" * 81
    refusesWith[Inconsistent](
      base.copy(genesisJson = base.genesisJson.replaceFirst("\"chainId\": 39438165", s"\"chainId\": $long")),
      "longer than 80 characters"
    )
  }

  "W1" should "bound every digit string it parses at 80 characters: 81 refused, 80 still parsed" taggedAs (UnitTest) in {
    // retention.vars values
    ReleaseInputs.parseRetention("export A=\"" + "1" * 80 + "\"\n").isRight shouldBe true
    ReleaseInputs.parseRetention("export A=\"" + "1" * 81 + "\"\n").left.getOrElse(fail("accepted")) should include(
      "line 1"
    )
    List("CHAIN_ID", "ITERATION_NUMBER", "GENESIS_TIMESTAMP", "GENESIS_RESET_INTERVAL").foreach { key =>
      withClue(key) {
        refused(base.retention(key, "1" * 81)).message should include("retention.vars")
        // 80 digits get as far as the rule that compares them
        refused(base.retention(key, "1" * 80)).message should (include("disagrees").or(include("must be an integer")))
      }
    }
    // header quantities, hex: 80 digits of the same value parse, 81 do not
    val gas = "0x" + "0" * 73 + "2255100"
    valid(base.editGenesis(_.set("gasLimit", JStr(gas)))).genesisHashHex shouldBe Hash165
    refusesWith[CannotRepresent](
      base.editGenesis(_.set("gasLimit", JStr("0x0" + gas.drop(2)))),
      "genesis.gasLimit is neither"
    )
    // alloc balance, decimal and hex
    def balance(b: String): ReleaseInputs =
      base.editGenesis(
        _.edit("alloc")(a => a.set("0x0000000000000000000000000000000000000000", JObj(Vector("balance" -> JStr(b)))))
      )
    valid(balance("0x" + "0" * 79 + "1")).genesisHashHex shouldBe Hash165
    valid(balance("0" * 79 + "1")).genesisHashHex shouldBe Hash165
    refusesWith[CannotRepresent](balance("0x" + "0" * 80 + "1"), "is not a 0x-hex or decimal number")
    refusesWith[CannotRepresent](balance("0" * 80 + "1"), "is not a 0x-hex or decimal number")
  }

  "L11" should "refuse a symlink, even a dangling one, for an optional member" taggedAs (UnitTest) in {
    val dir = Files.createTempDirectory("eph-link-test")
    try
      List("genesis.json", "retention.vars", "deposit_contract_block_hash.txt").foreach(f =>
        Files.copy(releaseDir(165).resolve(f), dir.resolve(f))
      )
      Files.createSymbolicLink(dir.resolve("chainspec.json"), dir.resolve("nowhere.json"))
      val r = ReleaseInputs.fromDir(dir).left.getOrElse(fail("expected a refusal"))
      r shouldBe a[Inconsistent]
      r.message should include("is a symlink")
    finally
      Files.list(dir).forEach(f => Files.delete(f))
      Files.delete(dir)
  }

  // ---------------------------------------------------------------------------------------------------------------
  "beacon 16" should "give a rendered configuration that parses through BlockchainConfig.fromRawConfig and a custom-chains-dir" taggedAs (
    UnitTest
  ) in {
    val v = valid(base)
    val dir = Files.createTempDirectory("eph-chains-test")
    try
      Files.write(dir.resolve(EphemeryRelease.ChainConfFileName), v.chainConf.getBytes(StandardCharsets.UTF_8))
      Files.write(dir.resolve(EphemeryRelease.GenesisFileName), v.encodedGenesis.getBytes(StandardCharsets.UTF_8))
      val viaDir: BlockchainConfig = BlockchainsConfig(
        ConfigFactory
          .empty()
          .withValue("network", ConfigValueFactory.fromAnyRef("ephemery"))
          .withValue("custom-chains-dir", ConfigValueFactory.fromAnyRef(dir.toString))
      ).blockchainConfig
      viaDir.chainId.value shouldBe v.chainId
      viaDir.forkTimestamps.amsterdamTimestamp shouldBe Some(0L)
      // the node loads the genesis from that directory: its hash is the one the release engine computed in memory
      val data = GenesisDataLoader.parseGenesisData(viaDir.customGenesisJsonOpt.getOrElse(fail("no genesis"))).get
      GenesisDataLoader.genesisHeader(data)(using viaDir).hash.value shouldBe v.genesisHash
      // and the raw conf parses on its own too, with the two spec 024 keys inside the chain object (R14)
      val rawConf = ConfigFactory.parseFile(dir.resolve(EphemeryRelease.ChainConfFileName).toFile)
      BlockchainConfig.fromRawConfig(rawConf).chainId.value shouldBe v.chainId
      rawConf.getString("consensus") shouldBe "engine-api"
      rawConf.getString("genesis-hash") shouldBe v.genesisHashHex
    finally
      Files.list(dir).forEach(f => Files.delete(f))
      Files.delete(dir)
  }
