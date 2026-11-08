package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.testing.Tags.*

/** #1727 class C: how `eth_simulateV1` decodes `blockOverrides` and `stateOverrides`, against go-ethereum v1.17.9
  * (`internal/ethapi/simulate.go` `simBlock.UnmarshalJSON` and `checkOverrideKeys`, `override/override.go`,
  * `common/hexutil/json.go`). The values are those of geth's types (`hexutil.Big`, `hexutil.Uint64`, `common.Hash`,
  * `common.Address`), so each rule here is the rule geth's decoder applies, and every error is -32602.
  *
  * The texts are geth's where its reason can be read from its source: `json: cannot unmarshal <reason> into Go struct
  * field <Struct>.<field> of type <type>` for the hexutil decode errors, `hex string has length n, want m for <type>`
  * for a fixed-size value. They were not compared against a running geth.
  */
// scalastyle:off magic.number
class EthSimulateOverrideDecodingSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  private val Addr = "0xc100000000000000000000000000000000000000"
  private val From = "0xc000000000000000000000000000000000000000"
  private val Hash32 = "0x" + "ab" * 32
  private val Slot = "0x" + "00" * 31 + "01"
  private val Call = s"""{"from":"$From","to":"$Addr"}"""

  private def decode(payload: String) =
    EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(Some(JArray(List(parse(payload), JString("latest")))))

  private def blockWith(inner: String) = s"""{"blockStateCalls":[{$inner,"calls":[$Call]}]}"""
  private def withBlockOverrides(json: String) = blockWith(s""""blockOverrides":$json""")
  private def withStateOverrides(json: String) = blockWith(s""""stateOverrides":$json""")
  private def withAccount(json: String) = withStateOverrides(s"""{"$Addr":$json}""")

  private def blockOverrides(json: String): BlockOverrides =
    decode(withBlockOverrides(json)).value.blockStateCalls.head.blockOverrides.value
  private def account(json: String): StateOverride =
    decode(withAccount(json)).value.blockStateCalls.head.stateOverrides.value(Address(Addr))
  private def error(payload: String): (Int, String) =
    val e = decode(payload).left.value
    (e.code, e.message)

  private def bytes(hex: String): ByteString = ByteString(Hex.decode(hex.stripPrefix("0x")))

  "eth_simulateV1 override keys" should {
    "match a block override key case-insensitively, as geth's checkOverrideKeys does" taggedAs UnitTest in {
      blockOverrides(
        s"""{"NUMBER":"0x5","gaslimit":"0x5208","FeeRecipient":"$Addr","PrevRandao":"$Hash32","baseFeePerGas":"0x7"}"""
      ) shouldBe BlockOverrides(
        number = Some(5),
        gasLimit = Some(0x5208),
        feeRecipient = Some(Address(Addr)),
        prevRandao = Some(bytes(Hash32)),
        baseFeePerGas = Some(7)
      )
    }

    "match an account override key case-insensitively" taggedAs UnitTest in {
      account(
        s"""{"BALANCE":"0x1","Nonce":"0x2","CODE":"0x00","StateDiff":{"$Slot":"$Slot"},"MovePrecompileToAddress":"$From"}"""
      ) shouldBe StateOverride(
        balance = Some(1),
        nonce = Some(2),
        code = Some(bytes("0x00")),
        stateDiff = Some(Map(BigInt(1) -> BigInt(1))),
        movePrecompileToAddress = Some(Address(From))
      )
    }

    "match the blockOverrides, stateOverrides and calls keys of a block case-insensitively" taggedAs UnitTest in {
      val bsc = decode(
        s"""{"blockStateCalls":[{"BlockOverrides":{"number":"0x5"},"STATEOVERRIDES":{"$Addr":{"balance":"0x1"}},"Calls":[$Call]}]}"""
      ).value.blockStateCalls.head
      bsc.blockOverrides.value shouldBe BlockOverrides(number = Some(5))
      bsc.stateOverrides.value shouldBe Map(Address(Addr) -> StateOverride(balance = Some(1)))
      bsc.calls.value should have size 1
    }

    "reject two block override keys that name the same field (-32602)" taggedAs UnitTest in {
      // geth reports them in sorted key order, so the uppercase one comes first
      error(withBlockOverrides("""{"number":"0x1","Number":"0x2"}""")) shouldBe
        (-32602, """invalid argument 0: keys "Number" and "number" in block override name the same field""")
    }

    "reject two account override keys that name the same field (-32602)" taggedAs UnitTest in {
      error(withAccount("""{"balance":"0x1","BALANCE":"0x2"}""")) shouldBe
        (
          -32602,
          s"""invalid argument 0: keys "BALANCE" and "balance" in state override for $Addr name the same field"""
        )
    }

    "keep the last of a key repeated with the same case, as encoding/json does" taggedAs UnitTest in {
      blockOverrides("""{"number":"0x1","number":"0x2"}""") shouldBe BlockOverrides(number = Some(2))
    }

    "report the first unknown key in sorted order, not document order" taggedAs UnitTest in {
      error(withBlockOverrides("""{"zzz":"0x1","aaa":"0x1"}""")) shouldBe
        (-32602, """invalid argument 0: unknown field "aaa" in block override""")
    }

    "reject an unknown key that only resembles a field (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"Numbers":"0x1"}""")) shouldBe
        (-32602, """invalid argument 0: unknown field "Numbers" in block override""")
    }
  }

  "eth_simulateV1 blockOverrides.beaconRoot" should {
    "be a known key and decode as a 32-byte hash" taggedAs UnitTest in {
      blockOverrides(s"""{"beaconRoot":"$Hash32"}""") shouldBe BlockOverrides(beaconRoot = Some(bytes(Hash32)))
    }

    "be refused when it is not exactly 32 bytes (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"beaconRoot":"0x00"}""")) shouldBe
        (-32602, "invalid argument 0: hex string has length 2, want 64 for common.Hash")
    }
  }

  "eth_simulateV1 overrides that are not objects" should {
    val kinds = Seq("5" -> "number", "\"x\"" -> "string", "[]" -> "array", "true" -> "bool")

    for (json, kind) <- kinds do
      s"refuse a $kind blockOverrides (-32602)" taggedAs UnitTest in {
        error(withBlockOverrides(json)) shouldBe
          (-32602, s"invalid argument 0: json: cannot unmarshal $kind into Go value of type override.BlockOverrides")
      }

      s"refuse a $kind stateOverrides (-32602)" taggedAs UnitTest in {
        error(withStateOverrides(json)) shouldBe
          (
            -32602,
            s"invalid argument 0: json: cannot unmarshal $kind into Go struct field .StateOverrides of type map[common.Address]json.RawMessage"
          )
      }

      s"refuse a $kind account override (-32602)" taggedAs UnitTest in {
        error(withAccount(json)) shouldBe
          (-32602, s"invalid argument 0: json: cannot unmarshal $kind into Go value of type override.OverrideAccount")
      }

    "read a null blockOverrides or stateOverrides as none, as geth's RawMessage and nil map do" taggedAs UnitTest in {
      val bsc = decode(
        s"""{"blockStateCalls":[{"blockOverrides":null,"stateOverrides":null,"calls":[$Call]}]}"""
      ).value.blockStateCalls.head
      bsc.blockOverrides shouldBe None
      bsc.stateOverrides shouldBe None
    }

    "read a null account override as an empty one: JSON null leaves the zero OverrideAccount" taggedAs UnitTest in {
      decode(withAccount("null")).value.blockStateCalls.head.stateOverrides.value shouldBe
        Map(Address(Addr) -> StateOverride())
    }
  }

  "eth_simulateV1 override values" should {
    "need the 0x prefix on a quantity (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"number":"5"}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex string without 0x prefix into Go struct field BlockOverrides.Number of type *hexutil.Big"
        )
    }

    "refuse a quantity with a leading zero (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"time":"0x01"}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex number with leading zero digits into Go struct field BlockOverrides.Time of type hexutil.Uint64"
        )
      error(withAccount("""{"nonce":"0x00"}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex number with leading zero digits into Go struct field OverrideAccount.nonce of type hexutil.Uint64"
        )
    }

    "refuse a bare 0x as a quantity (-32602)" taggedAs UnitTest in {
      error(withAccount("""{"balance":"0x"}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex string \"0x\" into Go struct field OverrideAccount.balance of type *hexutil.Big"
        )
    }

    "read the empty string as a zero quantity: hexutil's checkNumberText allows it" taggedAs UnitTest in {
      blockOverrides("""{"number":""}""") shouldBe BlockOverrides(number = Some(0))
    }

    "refuse a quantity that is not a string (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"gasLimit":5}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal non-string into Go struct field BlockOverrides.GasLimit of type hexutil.Uint64"
        )
    }

    "need a prevRandao of exactly 32 bytes (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"prevRandao":"0x00"}""")) shouldBe
        (-32602, "invalid argument 0: hex string has length 2, want 64 for common.Hash")
      error(withBlockOverrides(s"""{"prevRandao":"${Hash32}00"}""")) shouldBe
        (-32602, "invalid argument 0: hex string has length 66, want 64 for common.Hash")
    }

    "need a feeRecipient and a movePrecompileToAddress of exactly 20 bytes (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"feeRecipient":"0x12"}""")) shouldBe
        (-32602, "invalid argument 0: hex string has length 2, want 40 for common.Address")
      error(withAccount("""{"movePrecompileToAddress":"0x12"}""")) shouldBe
        (-32602, "invalid argument 0: hex string has length 2, want 40 for common.Address")
    }

    "need the state address key to be 0x and exactly 20 bytes (-32602)" taggedAs UnitTest in {
      error(withStateOverrides("""{"0x1234":{}}""")) shouldBe
        (-32602, "invalid argument 0: hex string has length 4, want 40 for Address")
      error(withStateOverrides(s"""{"${Addr.drop(2)}":{}}""")) shouldBe
        (-32602, "invalid argument 0: hex string without 0x prefix")
    }

    for field <- Seq("state", "stateDiff") do
      s"need every $field slot key and value to be exactly 32 bytes (-32602)" taggedAs UnitTest in {
        error(withAccount(s"""{"$field":{"0x01":"$Slot"}}""")) shouldBe
          (-32602, "invalid argument 0: hex string has length 2, want 64 for Hash")
        error(withAccount(s"""{"$field":{"$Slot":"0x02"}}""")) shouldBe
          (-32602, "invalid argument 0: hex string has length 2, want 64 for common.Hash")
      }

    "refuse code with an odd number of digits or no 0x (-32602)" taggedAs UnitTest in {
      error(withAccount("""{"code":"0xabc"}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex string of odd length into Go struct field OverrideAccount.code of type hexutil.Bytes"
        )
      error(withAccount("""{"code":"6000"}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex string without 0x prefix into Go struct field OverrideAccount.code of type hexutil.Bytes"
        )
    }

    "leave the 'state and stateDiff' fault to the block that applies it, as geth's Apply does" taggedAs UnitTest in {
      decode(withAccount("""{"state":{},"stateDiff":{}}""")).isRight shouldBe true
    }
  }

  "eth_simulateV1 blockOverrides.withdrawals" should {
    "decode a withdrawal's four fields" taggedAs UnitTest in {
      blockOverrides(
        s"""{"withdrawals":[{"index":"0x1","validatorIndex":"0x2","address":"$Addr","amount":"0x3"}]}"""
      ).withdrawals shouldBe Some(Seq(Withdrawal(1, 2, Address(Addr), 3)))
    }

    "take a field a withdrawal leaves out as zero: geth's Withdrawal has no required field" taggedAs UnitTest in {
      blockOverrides("""{"withdrawals":[{"index":"0x7"}]}""").withdrawals shouldBe
        Some(Seq(Withdrawal(7, 0, Address(0), 0)))
      blockOverrides("""{"withdrawals":[{}]}""").withdrawals shouldBe Some(Seq(Withdrawal(0, 0, Address(0), 0)))
    }

    "match a withdrawal's field names case-insensitively and ignore other keys" taggedAs UnitTest in {
      blockOverrides(
        s"""{"withdrawals":[{"INDEX":"0x1","ValidatorIndex":"0x2","Address":"$Addr","AMOUNT":"0x3","extra":1}]}"""
      ).withdrawals shouldBe Some(Seq(Withdrawal(1, 2, Address(Addr), 3)))
    }

    "decode an empty list as no withdrawals, not as none given" taggedAs UnitTest in {
      blockOverrides("""{"withdrawals":[]}""").withdrawals shouldBe Some(Seq.empty)
      blockOverrides("""{"withdrawals":null}""").withdrawals shouldBe None
    }

    "decode a withdrawal's quantities as hexutil.Uint64 does (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"withdrawals":[{"index":"0x01"}]}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex number with leading zero digits into Go struct field BlockOverrides.Withdrawals.index of type hexutil.Uint64"
        )
      error(withBlockOverrides("""{"withdrawals":[{"amount":"0x10000000000000000"}]}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal hex number > 64 bits into Go struct field BlockOverrides.Withdrawals.amount of type hexutil.Uint64"
        )
    }

    "need a withdrawal's address to be exactly 20 bytes (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"withdrawals":[{"address":"0x12"}]}""")) shouldBe
        (-32602, "invalid argument 0: hex string has length 2, want 40 for common.Address")
    }

    "refuse a withdrawals that is not an array (-32602)" taggedAs UnitTest in {
      error(withBlockOverrides("""{"withdrawals":"x"}""")) shouldBe
        (
          -32602,
          "invalid argument 0: json: cannot unmarshal string into Go struct field BlockOverrides.Withdrawals of type types.Withdrawals"
        )
    }

    // Deliberate deviation (fail loudly): geth decodes a null entry to a nil *Withdrawal and then dereferences it
    "refuse a null withdrawal (-32602)" taggedAs UnitTest in {
      decode(withBlockOverrides("""{"withdrawals":[null]}""")).left.value.code shouldBe -32602
    }
  }
