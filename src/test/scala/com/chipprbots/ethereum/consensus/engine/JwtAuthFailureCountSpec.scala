package com.chipprbots.ethereum.consensus.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.server.Directives.complete
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The Engine API's authentication failures are counted (spec 027 slice 5, E3), and counting changes nothing a client
  * sees: `JwtAuthenticatorSpec` is the proof of the 401 bodies and runs unmodified; this spec only adds the count.
  *
  * The count is one number with no tag. A failure's reason carries the token's own `iat` and the time, so a tag from it
  * would be a new series for each unauthenticated request (beacon, slice 5 pre-flight).
  */
class JwtAuthFailureCountSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest:

  private val secretHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
  private val secret: Array[Byte] = secretHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private val route: Route = new JwtAuthenticator(secretHex).authenticate(_ => complete("ok"))

  private def b64(text: String): String = Base64.getUrlEncoder.withoutPadding.encodeToString(text.getBytes(UTF_8))

  private def signed(payload: String, key: Array[Byte] = secret): String =
    val input = s"${b64("""{"alg":"HS256","typ":"JWT"}""")}.${b64(payload)}"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    s"$input.${Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(input.getBytes(UTF_8)))}"

  private def bearer(token: String): HttpRequest = Post("/").addHeader(Authorization(OAuth2BearerToken(token)))

  /** How many failures `request` added to the count, and what the client was told. */
  private def counted(request: HttpRequest): (Long, StatusCode, String) =
    val before = EngineApiMetrics.authFailures.value().toLong
    val (code, body) = request ~> route ~> check((status, responseAs[String]))
    (EngineApiMetrics.authFailures.value().toLong - before, code, body)

  "The authentication failure count" should "stay put for a valid token" taggedAs (UnitTest) in {
    val (added, code, body) = counted(bearer(signed(s"""{"iat":${Instant.now().getEpochSecond}}""")))
    (added, code, body) shouldBe ((0L, StatusCodes.OK, "ok"))
  }

  it should "count a request with no Authorization header, and still answer 401 with the same body" taggedAs (
    UnitTest
  ) in {
    counted(Post("/")) shouldBe ((1L, StatusCodes.Unauthorized, "Missing bearer token"))
  }

  it should "count a scheme that is not Bearer" taggedAs (UnitTest) in {
    val request = Post("/").addHeader(Authorization(BasicHttpCredentials("user", "pass")))
    counted(request) shouldBe ((1L, StatusCodes.Unauthorized, "Missing bearer token"))
  }

  it should "count a bad signature, an expired token and a token that is not a JWT, once each" taggedAs (UnitTest) in {
    val now = Instant.now().getEpochSecond
    counted(bearer(signed(s"""{"iat":$now}""", Array.fill(32)(0x42.toByte)))) shouldBe
      ((1L, StatusCodes.Unauthorized, "Invalid JWT signature"))
    val (expired, code, body) = counted(bearer(signed(s"""{"iat":${now - 3_600}}""")))
    (expired, code) shouldBe ((1L, StatusCodes.Unauthorized))
    body should startWith("JWT expired: iat=")
    counted(bearer("not-a-jwt")) shouldBe ((1L, StatusCodes.Unauthorized, "Invalid JWT format"))
  }

  it should "count every failed request, not every distinct reason" taggedAs (UnitTest) in {
    val before = EngineApiMetrics.authFailures.value().toLong
    for _ <- 1 to 5 do Post("/") ~> route ~> check(status shouldBe StatusCodes.Unauthorized)
    EngineApiMetrics.authFailures.value().toLong - before shouldBe 5L
  }
