package com.chipprbots.ethereum.ephemery

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.StrictJson.*
import com.chipprbots.ethereum.testing.Tags.*

/** The strict JSON reader the release rules stand on: what it refuses, and the `jq` format it writes. */
class StrictJsonSpec extends AnyFlatSpec with Matchers:

  private def refusal(text: String): String = StrictJson.parse(text).left.getOrElse(fail(s"accepted: $text"))

  private def nested(depth: Int): String = "[" * depth + "]" * depth

  "StrictJson.parse" should "accept nesting to depth 64 and refuse 65" taggedAs (UnitTest) in {
    StrictJson.parse(nested(64)) should matchPattern { case Right(_) => }
    refusal(nested(65)) should include("nesting deeper than 64")
  }

  it should "refuse a duplicate key, also when one is written with an escape" taggedAs (UnitTest) in {
    refusal("""{"a": 1, "a": 2}""") should include("the key \"a\" is declared twice")
    refusal("""{"a": 1, "\u0061": 2}""") should include("is declared twice")
    refusal("""{"x": {"a": 1, "a": 2}}""") should include("is declared twice")
  }

  it should "refuse an unpaired surrogate in a \\u escape, and accept a pair" taggedAs (UnitTest) in {
    refusal("\"\\ud800\"") should include("unpaired surrogate")
    refusal("\"\\udc00\"") should include("unpaired surrogate")
    refusal("\"\\ud800x\"") should include("unpaired surrogate")
    StrictJson.parse("\"\\ud83d\\ude00\"") shouldBe Right(JStr("\ud83d\ude00"))
  }

  it should "refuse a malformed number" taggedAs (UnitTest) in {
    List("01", "1.", "-", "1e", "+1", ".5", "1e+", "--1").foreach(n => withClue(n)(refusal(n)))
    StrictJson.parse("-0") shouldBe Right(JNum("-0"))
    StrictJson.parse("1.5e10") shouldBe Right(JNum("1.5e10"))
  }

  it should "refuse a number of more than 80 characters, and keep one of 80 exact" taggedAs (UnitTest) in {
    refusal("1" * 81) should include("longer than 80 characters")
    val eighty = "9" * 80
    StrictJson.parse(eighty) shouldBe Right(JNum(eighty))
    JNum(eighty).toBigInt shouldBe Some(BigInt(eighty))
    JNum("1" * 81).isInteger shouldBe false
    JNum("1" * 81).toBigInt shouldBe None
  }

  it should "refuse a raw control character in a string" taggedAs (UnitTest) in {
    refusal("\"a\tb\"") should include("control character")
    refusal("\"a\nb\"") should include("control character")
    StrictJson.parse("\"a\\tb\"") shouldBe Right(JStr("a\tb"))
  }

  it should "refuse trailing garbage, an empty input and a bad literal" taggedAs (UnitTest) in {
    refusal("{} x") should include("unexpected text after the value")
    refusal("[] []") should include("unexpected text")
    refusal("") should include("unexpected end")
    refusal("tru") should include("unexpected token")
    refusal("{\"a\" 1}") should include("expected ':'")
    refusal("[1,]") should include("unexpected character")
    refusal("\"abc") should include("unterminated")
  }

  it should "read only ASCII digits as digits" taggedAs (UnitTest) in {
    refusal("\u0663") should include("unexpected character") // an Arabic-Indic digit
  }

  "StrictJson.render" should "print the way jq does, keeping the order of the keys" taggedAs (UnitTest) in {
    val v = JObj(Vector("b" -> JNum("1"), "a" -> JObj(Vector.empty), "c" -> JArr(Vector(JStr("x\"y"), JNull))))
    render(v) shouldBe "{\n  \"b\": 1,\n  \"a\": {},\n  \"c\": [\n    \"x\\\"y\",\n    null\n  ]\n}\n"
    StrictJson.parse(render(v)) shouldBe Right(v)
  }
