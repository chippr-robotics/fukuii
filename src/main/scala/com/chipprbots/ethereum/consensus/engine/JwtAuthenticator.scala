package com.chipprbots.ethereum.consensus.engine

import java.nio.file.{Files, Paths}
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.reflect.ClassTag

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives._

import com.chipprbots.ethereum.utils.Logger

/** JWT authentication for the Engine API, per EIP-3675 / execution-apis spec.
  * Uses HS256 (HMAC-SHA256) with a shared secret.
  * Tokens are valid for 60 seconds.
  */
class JwtAuthenticator(secretHex: String) extends Logger {

  private val secretBytes: Array[Byte] = hexToBytes(secretHex.trim)
  private val MaxClockSkewSeconds = 60L

  /** Pekko HTTP directive that validates the JWT bearer token. */
  def authenticate: Directive1[Unit] = {
    optionalHeaderValueByType(ClassTag(classOf[Authorization])).flatMap {
      case Some(Authorization(OAuth2BearerToken(token))) =>
        validateToken(token) match {
          case Right(_) => provide(())
          case Left(err) =>
            log.warn(s"JWT auth failed: $err")
            complete(StatusCodes.Unauthorized, err)
        }
      case _ =>
        log.warn("JWT auth: missing Authorization header")
        complete(StatusCodes.Unauthorized, "Missing bearer token")
    }
  }

  private def validateToken(token: String): Either[String, Unit] = {
    val parts = token.split('.')
    if (parts.length != 3) return Left("Invalid JWT format")

    val headerPayload = s"${parts(0)}.${parts(1)}"
    val expectedSig = hmacSha256(headerPayload)
    val actualSig = parts(2)

    if (!constantTimeEquals(base64UrlDecode(expectedSig), base64UrlDecode(actualSig))) {
      return Left("Invalid JWT signature")
    }

    // Decode payload and check iat (issued-at) claim
    val payloadJson = new String(java.util.Base64.getUrlDecoder.decode(parts(1)))
    val iatPattern = """"iat"\s*:\s*(\d+)""".r
    iatPattern.findFirstMatchIn(payloadJson) match {
      case Some(m) =>
        val iat = m.group(1).toLong
        val now = Instant.now().getEpochSecond
        if (Math.abs(now - iat) > MaxClockSkewSeconds) {
          Left(s"JWT expired: iat=$iat, now=$now, skew=${Math.abs(now - iat)}s")
        } else {
          Right(())
        }
      case None => Left("Missing iat claim")
    }
  }

  private def hmacSha256(data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"))
    val sig = mac.doFinal(data.getBytes("UTF-8"))
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(sig)
  }

  private def base64UrlDecode(s: String): Array[Byte] =
    java.util.Base64.getUrlDecoder.decode(s)

  private def constantTimeEquals(a: Array[Byte], b: Array[Byte]): Boolean = {
    if (a.length != b.length) return false
    var result = 0
    var i = 0
    while (i < a.length) {
      result |= a(i) ^ b(i)
      i += 1
    }
    result == 0
  }

  private def hexToBytes(hex: String): Array[Byte] = {
    val clean = if (hex.startsWith("0x")) hex.substring(2) else hex
    clean.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  }
}

object JwtAuthenticator extends Logger {

  /** Load JWT secret from a file path. The file should contain a 32-byte hex-encoded secret. */
  def fromFile(path: String): JwtAuthenticator = {
    val secretHex = new String(Files.readAllBytes(Paths.get(path))).trim
    if (secretHex.replaceAll("^0x", "").length < 64) {
      throw new IllegalArgumentException(s"JWT secret must be at least 32 bytes (64 hex chars), got: ${secretHex.length}")
    }
    log.info(s"Loaded JWT secret from $path")
    new JwtAuthenticator(secretHex)
  }

  /** Generate a random 32-byte JWT secret (for testing/development). */
  def generateRandom(): JwtAuthenticator = {
    val random = new java.security.SecureRandom()
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    val hex = bytes.map("%02x".format(_)).mkString
    log.info("Generated random JWT secret for Engine API")
    new JwtAuthenticator(hex)
  }
}
