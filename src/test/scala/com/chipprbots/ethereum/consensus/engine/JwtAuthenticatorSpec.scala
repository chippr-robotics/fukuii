package com.chipprbots.ethereum.consensus.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.server.Directives.complete
import org.apache.pekko.http.scaladsl.server.Directives.handleExceptions
import org.apache.pekko.http.scaladsl.server.ExceptionHandler
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Engine API JWT authentication (execution-apis `authentication.md`; go-ethereum `node/jwt_handler.go`): every token
  * that is missing or malformed gets 401, never a server error, and only HS256 is accepted.
  *
  * The route under test is wrapped in a handler that records any exception the directive lets escape. An escaped
  * exception is what Pekko HTTP turns into a 500 and an ERROR log with a stack trace, so each case also asserts that
  * nothing escaped.
  */
class JwtAuthenticatorSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest:

  private val secretHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
  private val secret: Array[Byte] = secretHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private val otherSecret: Array[Byte] = Array.fill(32)(0x42.toByte)

  private val escaped = new ConcurrentLinkedQueue[Throwable]()

  private val route: Route =
    handleExceptions(ExceptionHandler { case t =>
      escaped.add(t)
      complete(StatusCodes.InternalServerError, "server error")
    }) {
      new JwtAuthenticator(secretHex).authenticate(_ => complete("ok"))
    }

  private def now: Long = Instant.now().getEpochSecond

  private def b64(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  private def b64(text: String): String = b64(text.getBytes(UTF_8))

  private def hmac(data: String, key: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    b64(mac.doFinal(data.getBytes(UTF_8)))

  private val HeaderJson = """{"alg":"HS256","typ":"JWT"}"""

  /** A token with the given header and payload JSON, signed with HMAC-SHA256 under `key`. */
  private def signed(header: String, payload: String, key: Array[Byte] = secret): String =
    val signingInput = s"${b64(header)}.${b64(payload)}"
    s"$signingInput.${hmac(signingInput, key)}"

  private def valid: String = signed(HeaderJson, s"""{"iat":$now}""")

  private def bearer(token: String): HttpRequest = Post("/").addHeader(Authorization(OAuth2BearerToken(token)))

  private def expectOk(token: String): Unit =
    escaped.clear()
    bearer(token) ~> route ~> check {
      withClue(s"token '$token': ") {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual "ok"
      }
    }
    withClue("an exception escaped the directive: ")(escaped.asScala.toList shouldBe empty)

  private def expectUnauthorized(token: String, reason: String): Unit =
    escaped.clear()
    bearer(token) ~> route ~> check {
      withClue(s"token '$token': ") {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual reason
      }
    }
    withClue(s"an exception escaped the directive for token '$token': ")(escaped.asScala.toList shouldBe empty)

  "JwtAuthenticator" should "accept a valid HS256 token" taggedAs (UnitTest, RPCTest) in {
    expectOk(valid)
  }

  it should "accept an iat within the 60 second skew, past or future, whole or fractional" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    expectOk(signed(HeaderJson, s"""{"iat":${now - 30}}"""))
    expectOk(signed(HeaderJson, s"""{"iat":${now + 30}}"""))
    expectOk(signed(HeaderJson, s"""{"iat":$now.5}"""))
    expectOk(signed(HeaderJson, s"""{"iat":$now,"exp":${now + 3600}}"""))
  }

  it should "answer 401 to a signature that is not base64url" taggedAs (UnitTest, RPCTest) in {
    val signingInput = valid.split('.').take(2).mkString(".")
    expectUnauthorized(s"$signingInput.!!!!", "Invalid JWT signature encoding")
    expectUnauthorized(s"$signingInput.abc+/=", "Invalid JWT signature encoding")
    expectUnauthorized(s"$signingInput.a b", "Invalid JWT signature encoding")
    expectUnauthorized(s"$signingInput.a", "Invalid JWT signature encoding")
    expectUnauthorized(s"$signingInput.%%%%", "Invalid JWT signature encoding")
  }

  it should "answer 401 to a signature of the wrong length or made with another secret" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val parts = valid.split('.')
    val (h, p, sig) = (parts(0), parts(1), parts(2))
    expectUnauthorized(s"$h.$p.", "Invalid JWT signature")
    expectUnauthorized(s"$h.$p.${b64("short")}", "Invalid JWT signature")
    expectUnauthorized(signed(HeaderJson, s"""{"iat":$now}""", otherSecret), "Invalid JWT signature")
    // a payload edited after signing
    expectUnauthorized(s"$h.${b64(s"""{"iat":${now + 1}}""")}.$sig", "Invalid JWT signature")
  }

  it should "answer 401 to a token with the wrong number of segments" taggedAs (UnitTest, RPCTest) in {
    expectUnauthorized("", "Invalid JWT format")
    expectUnauthorized("abc", "Invalid JWT format")
    expectUnauthorized("a.b", "Invalid JWT format")
    expectUnauthorized(s"$valid.extra", "Invalid JWT format")
    expectUnauthorized(s"$valid.", "Invalid JWT format")
    expectUnauthorized("..", "Invalid JWT signature")
    expectUnauthorized("...", "Invalid JWT format")
  }

  it should "answer 401 to a header or payload that is not base64url, even under a valid signature" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    def signedRaw(header: String, payload: String): String =
      val signingInput = s"$header.$payload"
      s"$signingInput.${hmac(signingInput, secret)}"

    expectUnauthorized(signedRaw("!!!", b64(s"""{"iat":$now}""")), "Invalid JWT header encoding")
    expectUnauthorized(signedRaw(b64(HeaderJson), "!!!"), "Invalid JWT payload encoding")
    expectUnauthorized(signedRaw(b64(HeaderJson), "a"), "Invalid JWT payload encoding")
  }

  it should "answer 401 to a header or payload that is not a JSON object" taggedAs (UnitTest, RPCTest) in {
    expectUnauthorized(signed("not json", s"""{"iat":$now}"""), "Invalid JWT header")
    expectUnauthorized(signed("""{"alg":"HS256"""", s"""{"iat":$now}"""), "Invalid JWT header")
    expectUnauthorized(signed("""["HS256"]""", s"""{"iat":$now}"""), "Invalid JWT header")
    expectUnauthorized(signed("", s"""{"iat":$now}"""), "Invalid JWT header")
    expectUnauthorized(signed(HeaderJson, "not json"), "Invalid JWT payload")
    expectUnauthorized(signed(HeaderJson, """{"iat":"""), "Invalid JWT payload")
    expectUnauthorized(signed(HeaderJson, s"[$now]"), "Invalid JWT payload")
    expectUnauthorized(signed(HeaderJson, "123"), "Invalid JWT payload")
    expectUnauthorized(signed(HeaderJson, ""), "Invalid JWT payload")
  }

  it should "accept only HS256" taggedAs (UnitTest, RPCTest) in {
    val payload = s"""{"iat":$now}"""
    // the signature is a valid HMAC-SHA256 in each case; the header's claim about the algorithm is what is refused
    expectUnauthorized(signed("""{"alg":"HS512","typ":"JWT"}""", payload), "Unsupported JWT algorithm")
    expectUnauthorized(signed("""{"alg":"HS384","typ":"JWT"}""", payload), "Unsupported JWT algorithm")
    expectUnauthorized(signed("""{"alg":"RS256","typ":"JWT"}""", payload), "Unsupported JWT algorithm")
    expectUnauthorized(signed("""{"alg":"none"}""", payload), "Unsupported JWT algorithm")
    expectUnauthorized(signed("""{"alg":"hs256"}""", payload), "Unsupported JWT algorithm")
    expectUnauthorized(signed("""{"typ":"JWT"}""", payload), "Unsupported JWT algorithm")
    expectUnauthorized(signed("""{"alg":["HS256"]}""", payload), "Unsupported JWT algorithm")
    expectUnauthorized(signed("""{"alg":null}""", payload), "Unsupported JWT algorithm")
    // an unsigned token (alg none, empty signature)
    expectUnauthorized(s"${b64("""{"alg":"none"}""")}.${b64(payload)}.", "Invalid JWT signature")
    // a header with only the algorithm is enough
    expectOk(signed("""{"alg":"HS256"}""", payload))
  }

  it should "answer 401 to an iat that is missing, not a number, or out of range" taggedAs (UnitTest, RPCTest) in {
    expectUnauthorized(signed(HeaderJson, "{}"), "Missing iat claim")
    expectUnauthorized(signed(HeaderJson, """{"iat":"abc"}"""), "Missing iat claim")
    expectUnauthorized(signed(HeaderJson, s"""{"iat":"$now"}"""), "Missing iat claim")
    expectUnauthorized(signed(HeaderJson, """{"iat":null}"""), "Missing iat claim")
    expectUnauthorized(signed(HeaderJson, """{"iat":true}"""), "Missing iat claim")
    // only a top-level claim counts
    expectUnauthorized(signed(HeaderJson, s"""{"nested":{"iat":$now}}"""), "Missing iat claim")
    // a number too large for a double is read as an infinite one (1e30 above is finite); it is no usable iat
    expectUnauthorized(signed(HeaderJson, """{"iat":1e400}"""), "Missing iat claim")
    expectUnauthorized(signed(HeaderJson, """{"iat":-1e400}"""), "Missing iat claim")
    expectUnauthorized(signed(HeaderJson, """{"iat":1E400}"""), "Missing iat claim")

    def expired(payload: String): Unit =
      escaped.clear()
      bearer(signed(HeaderJson, payload)) ~> route ~> check {
        withClue(s"payload $payload: ") {
          status shouldEqual StatusCodes.Unauthorized
          responseAs[String] should startWith("JWT expired: iat=")
        }
      }
      escaped.asScala.toList shouldBe empty

    expired(s"""{"iat":${now - 61}}""")
    expired(s"""{"iat":${now - 3600}}""")
    expired(s"""{"iat":${now + 61}}""")
    expired("""{"iat":0}""")
    expired("""{"iat":-5}""")
    expired("""{"iat":99999999999999999999999999}""")
    expired("""{"iat":-99999999999999999999999999}""")
    expired("""{"iat":9223372036854775807}""")
    expired("""{"iat":-9223372036854775808}""")
    expired("""{"iat":1e30}""")
    expired("""{"iat":1e-400}""")
  }

  it should "answer 401 when the Authorization header is missing or is not a bearer token" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    escaped.clear()
    Post("/") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "Missing bearer token"
    }
    Post("/").addHeader(Authorization(BasicHttpCredentials("user", "pass"))) ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "Missing bearer token"
    }
    escaped.asScala.toList shouldBe empty
  }
