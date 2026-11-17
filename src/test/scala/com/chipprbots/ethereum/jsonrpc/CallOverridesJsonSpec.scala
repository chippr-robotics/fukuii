package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.testing.Tags.*

/** #1727 class C, the eth_call / eth_estimateGas side: [[CallOverridesJson]] is the one decoder of the override
  * parameters, and eth_simulateV1 decodes through it too, so these rules are geth's `override.StateOverride` and
  * `override.BlockOverrides` JSON rules (go-ethereum v1.17.9; `common/hexutil/json.go`; `encoding/json`'s case-folded
  * field match). What differs from eth_simulateV1 is what a single call can apply: geth's `BlockOverrides.Apply`
  * refuses `beaconRoot` and `withdrawals` there (override.go:148-153).
  *
  * Deliberate deviation from geth, kept from before: an unknown key is -32602 (geth's encoding/json ignores it).
  */
// scalastyle:off magic.number
class CallOverridesJsonSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  import EthJsonMethodsImplicits.eth_call

  private val Probe = "0x000000000000000000000000000000000000bbbb"
  private val Hash32 = "0x" + "ab" * 32
  private val Slot = "0x" + "00" * 31 + "01"
  private val Call = parse(s"""{"to":"$Probe"}""")

  // json4s native parses objects and arrays at the top level, not a bare scalar
  private def json(s: String): JValue = s match
    case "null"                  => JNull
    case "5"                     => JInt(5)
    case _ if s.startsWith("\"") => JString(s.stripPrefix("\"").stripSuffix("\""))
    case _                       => parse(s)

  private def decode(stateOverrides: String = "null", blockOverrides: String = "null") =
    eth_call.decodeJson(Some(JArray(List(Call, JString("latest"), json(stateOverrides), json(blockOverrides)))))

  private def blockOverrides(s: String): BlockOverrides = decode(blockOverrides = s).value.blockOverrides.value
  private def account(s: String): StateOverride =
    decode(stateOverrides = s"""{"$Probe":$s}""").value.stateOverrides.value(Address(Probe))
  private def error(stateOverrides: String = "null", blockOverrides: String = "null"): (Int, String) =
    val e = decode(stateOverrides, blockOverrides).left.value
    (e.code, e.message)

  "eth_call override keys" should {
    "match case-insensitively, as encoding/json does" taggedAs (UnitTest, RPCTest) in {
      blockOverrides("""{"BASEFEEPERGAS":"0x3","Number":"0x10","gaslimit":"0x5208"}""") shouldBe
        BlockOverrides(baseFeePerGas = Some(3), number = Some(16), gasLimit = Some(0x5208))
      account(s"""{"Balance":"0x1","STATEDIFF":{"$Slot":"$Slot"},"Nonce":"0x2"}""") shouldBe
        StateOverride(balance = Some(1), nonce = Some(2), stateDiff = Some(Map(BigInt(1) -> BigInt(1))))
    }

    "refuse two keys that name the same field (-32602)" taggedAs (UnitTest, RPCTest) in {
      error(blockOverrides = """{"number":"0x1","Number":"0x2"}""") shouldBe
        (-32602, """invalid argument 3: keys "Number" and "number" in block override name the same field""")
      error(stateOverrides = s"""{"$Probe":{"balance":"0x1","BALANCE":"0x2"}}""")._1 shouldBe -32602
    }

    "keep refusing an unknown key (-32602)" taggedAs (UnitTest, RPCTest) in {
      error(blockOverrides = """{"bogusField":"0x1"}""") shouldBe
        (-32602, """invalid argument 3: unknown field "bogusField" in block override""")
    }
  }

  "eth_call override values" should {
    "read the empty string as a zero quantity: hexutil's checkNumberText allows it" taggedAs (UnitTest, RPCTest) in {
      blockOverrides("""{"number":""}""") shouldBe BlockOverrides(number = Some(0))
      account("""{"balance":""}""") shouldBe StateOverride(balance = Some(0))
    }

    "refuse a leading zero, as geth's hexutil does (-32602)" taggedAs (UnitTest, RPCTest) in {
      error(blockOverrides = """{"number":"0x01"}""") shouldBe
        (
          -32602,
          "invalid argument 3: json: cannot unmarshal hex number with leading zero digits into Go struct field BlockOverrides.Number of type *hexutil.Big"
        )
      error(stateOverrides = s"""{"$Probe":{"nonce":"0x01"}}""") shouldBe
        (
          -32602,
          "invalid argument 2: json: cannot unmarshal hex number with leading zero digits into Go struct field OverrideAccount.nonce of type hexutil.Uint64"
        )
    }

    "read a null account override as an empty one: JSON null leaves the zero OverrideAccount" taggedAs
      (UnitTest, RPCTest) in {
        decode(stateOverrides = s"""{"$Probe":null}""").value.stateOverrides.value shouldBe
          Map(Address(Probe) -> StateOverride())
      }

    "name the kind of a value that is not an object, as encoding/json does (-32602)" taggedAs (UnitTest, RPCTest) in {
      error(stateOverrides = "5") shouldBe
        (-32602, "invalid argument 2: json: cannot unmarshal number into Go value of type override.StateOverride")
      error(blockOverrides = "\"x\"") shouldBe
        (-32602, "invalid argument 3: json: cannot unmarshal string into Go value of type override.BlockOverrides")
      error(stateOverrides = s"""{"$Probe":[]}""") shouldBe
        (-32602, "invalid argument 2: json: cannot unmarshal array into Go value of type override.OverrideAccount")
    }

    "keep raising 'state and stateDiff' while decoding, as the single call has no block to defer it to" taggedAs
      (UnitTest, RPCTest) in {
        error(stateOverrides = s"""{"$Probe":{"state":{},"stateDiff":{}}}""")._1 shouldBe -32000
      }
  }

  "eth_call block overrides geth cannot apply to a single call" should {
    "refuse beaconRoot with -32000 once it has decoded" taggedAs (UnitTest, RPCTest) in {
      error(blockOverrides = s"""{"beaconRoot":"$Hash32"}""") shouldBe
        (-32000, """block override "beaconRoot" is not supported for this RPC method""")
    }

    "refuse withdrawals with -32000 once they have decoded, an empty list too" taggedAs (UnitTest, RPCTest) in {
      error(blockOverrides = """{"withdrawals":[]}""") shouldBe
        (-32000, """block override "withdrawals" is not supported for this RPC method""")
    }

    "name beaconRoot first when both are given, as Apply checks it first" taggedAs (UnitTest, RPCTest) in {
      error(blockOverrides = s"""{"withdrawals":[],"beaconRoot":"$Hash32"}""")._2 shouldBe
        """block override "beaconRoot" is not supported for this RPC method"""
    }

    "report a malformed beaconRoot or withdrawals as -32602: the decode comes before Apply" taggedAs
      (UnitTest, RPCTest) in {
        error(blockOverrides = """{"beaconRoot":"0x00"}""") shouldBe
          (-32602, "invalid argument 3: hex string has length 2, want 64 for common.Hash")
        error(blockOverrides = """{"withdrawals":[{"index":"0x01"}]}""")._1 shouldBe -32602
        error(blockOverrides = s"""{"beaconRoot":"$Hash32","number":"0x01"}""")._1 shouldBe -32602
      }
  }
