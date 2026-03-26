package com.chipprbots.ethereum.jsonrpc.server.http

import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directive0
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import org.json4s.DefaultFormats
import org.json4s.Formats
import org.json4s.Serialization
import org.json4s.native

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JwtAuthConfig
import com.chipprbots.ethereum.utils.Logger

/** JWT authentication directive for JSON-RPC endpoints.
  *
  * Implements the go-ethereum/Besu JWT authentication pattern (EIP-6963 engine API):
  *   - HS256 (HMAC-SHA256) with a 32-byte shared secret
  *   - Secret loaded from a hex-encoded file (same as `--authrpc.jwtsecret`)
  *   - Token validated via `Authorization: Bearer <token>` header
  *   - `iat` (issued-at) claim must be within ±60 seconds of server time
  *
  * When disabled, all requests pass through without authentication.
  */
class JwtAuth(config: JwtAuthConfig) extends Directive0 with Json4sSupport with Logger {

  implicit override val serialization: Serialization = native.Serialization
  implicit override val formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer

  private val MaxClockSkew = 60L // seconds

  private lazy val secret: Array[Byte] = loadSecret()

  private def loadSecret(): Array[Byte] = {
    val path = Paths.get(config.secretFile)
    if (!Files.exists(path)) {
      log.warn(s"JWT secret file not found: ${config.secretFile}. Generating new secret.")
      val newSecret = new Array[Byte](32)
      new java.security.SecureRandom().nextBytes(newSecret)
      Files.createDirectories(path.getParent)
      Files.writeString(path, bytesToHex(newSecret))
      newSecret
    } else {
      val content = Files.readString(path).trim
      // Support both hex-encoded (64 chars) and raw 32-byte files
      if (content.length == 64 && content.matches("[0-9a-fA-F]+")) {
        hexToBytes(content)
      } else {
        val raw = Files.readAllBytes(path)
        if (raw.length >= 32) raw.take(32)
        else throw new IllegalArgumentException(
          s"JWT secret file must contain at least 32 bytes (hex-encoded or raw): ${config.secretFile}"
        )
      }
    }
  }

  private def hexToBytes(hex: String): Array[Byte] = {
    val len = hex.length
    val data = new Array[Byte](len / 2)
    var i = 0
    while (i < len) {
      data(i / 2) = ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16)).toByte
      i += 2
    }
    data
  }

  private def bytesToHex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  override def tapply(f: Unit => Route): Route =
    if (!config.enabled) f.apply(())
    else
      optionalHeaderValueByName("Authorization") {
        case Some(value) if value.startsWith("Bearer ") =>
          val token = value.substring(7)
          verifyToken(token) match {
            case Right(_) => f.apply(())
            case Left(err) =>
              log.debug(s"JWT auth failed: $err")
              complete((StatusCodes.Unauthorized, JsonRpcError.JwtAuthError(err)))
          }
        case Some(_) =>
          complete((StatusCodes.Unauthorized, JsonRpcError.JwtAuthError("Invalid Authorization header format. Expected: Bearer <token>")))
        case None =>
          complete((StatusCodes.Unauthorized, JsonRpcError.JwtAuthError("Missing Authorization header")))
      }

  private def verifyToken(token: String): Either[String, Unit] = {
    val parts = token.split('.')
    if (parts.length != 3) return Left("Invalid JWT format")

    val headerPayload = s"${parts(0)}.${parts(1)}"
    val signature = parts(2)

    // Verify signature
    val expectedSig = hmacSha256(headerPayload)
    if (!constantTimeEquals(base64UrlDecode(signature), expectedSig))
      return Left("Invalid signature")

    // Decode header and verify algorithm
    val headerJson = new String(base64UrlDecode(parts(0)), "UTF-8")
    if (!headerJson.contains("\"HS256\""))
      return Left("Unsupported algorithm (only HS256 accepted)")

    // Decode payload and verify iat
    val payloadJson = new String(base64UrlDecode(parts(1)), "UTF-8")
    extractIat(payloadJson) match {
      case Some(iat) =>
        val now = System.currentTimeMillis() / 1000
        val diff = math.abs(now - iat)
        if (diff > MaxClockSkew)
          Left(s"Token expired or too far in future (iat=$iat, now=$now, diff=${diff}s, max=${MaxClockSkew}s)")
        else
          Right(())
      case None =>
        Left("Missing 'iat' claim")
    }
  }

  private def hmacSha256(data: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8"))
  }

  private def base64UrlDecode(s: String): Array[Byte] =
    Base64.getUrlDecoder.decode(s)

  private def constantTimeEquals(a: Array[Byte], b: Array[Byte]): Boolean = {
    if (a.length != b.length) return false
    var result = 0
    var i = 0
    while (i < a.length) {
      result |= (a(i) ^ b(i))
      i += 1
    }
    result == 0
  }

  /** Extract `iat` from a simple JSON payload without pulling in a full JSON parser. */
  private def extractIat(json: String): Option[Long] = {
    val pattern = """"iat"\s*:\s*(\d+)""".r
    pattern.findFirstMatchIn(json).map(_.group(1).toLong)
  }
}
