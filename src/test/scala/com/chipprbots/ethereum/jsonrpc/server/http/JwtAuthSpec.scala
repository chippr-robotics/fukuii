package com.chipprbots.ethereum.jsonrpc.server.http

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JwtAuthConfig

class JwtAuthSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with BeforeAndAfterAll {

  private val SecretHex = "a9b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9"
  private var tempDir: Path = _
  private var secretFile: Path = _
  private val secretBytes: Array[Byte] = {
    val hex = SecretHex
    val len = hex.length
    val data = new Array[Byte](len / 2)
    var i = 0
    while (i < len) {
      data(i / 2) = ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16)).toByte
      i += 2
    }
    data
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    tempDir = Files.createTempDirectory("jwt-auth-test")
    secretFile = tempDir.resolve("jwt.hex")
    Files.writeString(secretFile, SecretHex)
  }

  override def afterAll(): Unit = {
    Files.deleteIfExists(secretFile)
    Files.deleteIfExists(tempDir)
    super.afterAll()
  }

  private def enabledConfig: JwtAuthConfig = new JwtAuthConfig {
    override val enabled: Boolean = true
    override val secretFile: String = JwtAuthSpec.this.secretFile.toString
  }

  private def disabledConfig: JwtAuthConfig = new JwtAuthConfig {
    override val enabled: Boolean = false
    override val secretFile: String = ""
  }

  private def createToken(iat: Long): String = {
    val header = Base64.getUrlEncoder.withoutPadding.encodeToString(
      """{"alg":"HS256","typ":"JWT"}""".getBytes("UTF-8")
    )
    val payload = Base64.getUrlEncoder.withoutPadding.encodeToString(
      s"""{"iat":$iat}""".getBytes("UTF-8")
    )
    val data = s"$header.$payload"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"))
    val sig = Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(data.getBytes("UTF-8")))
    s"$header.$payload.$sig"
  }

  private def testRoute(config: JwtAuthConfig) = {
    val jwtAuth = new JwtAuth(config)
    jwtAuth {
      complete("ok")
    }
  }

  // --- Disabled mode ---

  "JwtAuth (disabled)" should "pass all requests without authentication" in {
    Get() ~> testRoute(disabledConfig) ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldBe "ok"
    }
  }

  // --- Enabled mode: valid tokens ---

  "JwtAuth (enabled)" should "accept a valid token" in {
    val token = createToken(System.currentTimeMillis() / 1000)
    Get() ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldBe "ok"
    }
  }

  it should "accept a token within the 60-second clock skew window (past)" in {
    val token = createToken(System.currentTimeMillis() / 1000 - 55)
    Get() ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "accept a token within the 60-second clock skew window (future)" in {
    val token = createToken(System.currentTimeMillis() / 1000 + 55)
    Get() ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  // --- Enabled mode: rejection cases ---

  it should "reject requests with no Authorization header" in {
    Get() ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.Unauthorized
      responseAs[String] should include("Missing Authorization header")
    }
  }

  it should "reject requests with non-Bearer auth" in {
    Get() ~> addHeader(RawHeader("Authorization", "Basic abc123")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.Unauthorized
      responseAs[String] should include("Invalid Authorization header format")
    }
  }

  it should "reject tokens with invalid signature" in {
    val token = createToken(System.currentTimeMillis() / 1000)
    val tampered = token.dropRight(4) + "XXXX"
    Get() ~> addHeader(RawHeader("Authorization", s"Bearer $tampered")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.Unauthorized
      responseAs[String] should include("Invalid signature")
    }
  }

  it should "reject expired tokens (iat too old)" in {
    val token = createToken(System.currentTimeMillis() / 1000 - 120)
    Get() ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.Unauthorized
      responseAs[String] should include("expired or too far in future")
    }
  }

  it should "reject future tokens (iat too far ahead)" in {
    val token = createToken(System.currentTimeMillis() / 1000 + 120)
    Get() ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.Unauthorized
      responseAs[String] should include("expired or too far in future")
    }
  }

  it should "reject malformed JWT (wrong segment count)" in {
    Get() ~> addHeader(RawHeader("Authorization", "Bearer not.a.valid.jwt.token")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.Unauthorized
    }
  }

  it should "reject JWT with missing iat claim" in {
    val header = Base64.getUrlEncoder.withoutPadding.encodeToString(
      """{"alg":"HS256","typ":"JWT"}""".getBytes("UTF-8")
    )
    val payload = Base64.getUrlEncoder.withoutPadding.encodeToString(
      """{"sub":"test"}""".getBytes("UTF-8")
    )
    val data = s"$header.$payload"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"))
    val sig = Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(data.getBytes("UTF-8")))
    val token = s"$header.$payload.$sig"

    Get() ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> testRoute(enabledConfig) ~> check {
      status shouldBe StatusCodes.Unauthorized
      responseAs[String] should include("Missing 'iat' claim")
    }
  }

  // --- Secret file generation ---

  "JwtAuth secret file" should "auto-generate when missing on first request" in {
    val newSecretPath = tempDir.resolve("auto-generated-jwt.hex")
    Files.deleteIfExists(newSecretPath)

    val config = new JwtAuthConfig {
      override val enabled: Boolean = true
      override val secretFile: String = newSecretPath.toString
    }

    val jwtAuth = new JwtAuth(config)
    val route = jwtAuth { complete("ok") }

    // Secret is lazy — trigger it by making a request with a Bearer token (will fail sig but generate the file)
    Get() ~> addHeader(RawHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJpYXQiOjB9.invalid")) ~> route ~> check {
      status shouldBe StatusCodes.Unauthorized
    }

    // The secret file should now exist (generated on first auth attempt)
    Files.exists(newSecretPath) shouldBe true
    val content = Files.readString(newSecretPath).trim
    content.length shouldBe 64 // 32 bytes = 64 hex chars
    content.matches("[0-9a-f]+") shouldBe true

    // Clean up
    Files.deleteIfExists(newSecretPath)
  }
}
