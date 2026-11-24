package com.chipprbots.ethereum.consensus.engine

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives.*

import scala.reflect.ClassTag
import scala.util.Try

import org.json4s.JDecimal
import org.json4s.JDouble
import org.json4s.JInt
import org.json4s.JLong
import org.json4s.JObject
import org.json4s.JString
import org.json4s.JValue
import org.json4s.native.JsonMethods.parse

import com.chipprbots.ethereum.utils.Logger

/** JWT authentication for the Engine API, per EIP-3675 / execution-apis spec. Uses HS256 (HMAC-SHA256) with a shared
  * secret. Tokens are valid for 60 seconds. A request whose token is missing or malformed in any way is answered with
  * 401.
  */
class JwtAuthenticator(secretHex: String, calls: EngineCallLog = EngineCallLog.Node) extends Logger:

  private val secretBytes: Array[Byte] = hexToBytes(secretHex.trim)
  private val MaxClockSkewSeconds = 60L

  /** An HS256 token with an `iat` claim is under 200 characters; this bound only keeps a hostile header from being
    * split, hashed and parsed at full size.
    */
  private val MaxTokenLength = 4096

  private val UnpaddedUrlEncoder = java.util.Base64.getUrlEncoder.withoutPadding
  private val PaddedUrlEncoder = java.util.Base64.getUrlEncoder

  /** Pekko HTTP directive that validates the JWT bearer token. */
  def authenticate: Directive1[Unit] =
    optionalHeaderValueByType(ClassTag(classOf[Authorization])).flatMap {
      case Some(Authorization(OAuth2BearerToken(token))) =>
        validateToken(token) match
          case Right(_) => provide(())
          case Left(err) =>
            log.warn(s"JWT auth failed: $err")
            EngineApiMetrics.recordAuthFailure()
            // The reason, as one of a fixed few, for the dashboard (spec 029 FR-040): the message itself carries the
            // token's `iat` and is no key. The answer is what it was.
            calls.authFailed(AuthFailure.of(err))
            complete(StatusCodes.Unauthorized, err)
      case _ =>
        log.warn("JWT auth: missing Authorization header")
        EngineApiMetrics.recordAuthFailure()
        calls.authFailed(AuthFailure.Missing)
        complete(StatusCodes.Unauthorized, "Missing bearer token")
    }

  /** Validates a token the way go-ethereum's `node/jwt_handler.go` does: three base64url segments, a valid HMAC-SHA256
    * signature, a header that names HS256, and an `iat` within [[MaxClockSkewSeconds]] of now. A malformed token is a
    * `Left` with a short reason, never an exception: the caller answers it with 401.
    *
    * Nothing the caller sent is Base64-decoded before the signature checks out (CVE-2026-114938: the JDK's Base64
    * decoder could be driven to memory corruption by crafted input). The signature is compared as text against the
    * encoding of the HMAC this node computes, unpadded or padded; header and payload are decoded only after that.
    */
  private def validateToken(token: String): Either[String, Unit] =
    if token.length > MaxTokenLength then Left("Invalid JWT format")
    else
      // limit -1 keeps a trailing empty segment, so `a.b.c.` is four segments, not three
      token.split("\\.", -1) match
        case Array(header, payload, signature) =>
          for
            _ <- Either.cond(isBase64UrlSyntax(signature), (), "Invalid JWT signature encoding")
            _ <- Either.cond(signatureMatches(s"$header.$payload", signature), (), "Invalid JWT signature")
            headerJson <- decodeJson(header, "header")
            _ <- checkAlgorithm(headerJson)
            payloadJson <- decodeJson(payload, "payload")
            _ <- checkIssuedAt(payloadJson)
          yield ()
        case _ => Left("Invalid JWT format")

  /** Constant-time comparison of the presented signature with the base64url text of the expected HMAC, in its canonical
    * unpadded form or padded with `=`, the two forms the decoder used to accept. A non-canonical encoding of the right
    * bytes (non-zero trailing bits) no longer passes; clients encode canonically.
    */
  private def signatureMatches(signingInput: String, signature: String): Boolean =
    val mac = hmacSha256(signingInput)
    val presented = signature.getBytes(StandardCharsets.US_ASCII)
    def matches(encoder: java.util.Base64.Encoder): Boolean =
      MessageDigest.isEqual(encoder.encode(mac), presented)
    // `|`, not `||`: both comparisons run whichever form was sent
    matches(UnpaddedUrlEncoder) | matches(PaddedUrlEncoder)

  /** Base64url syntax alone, without decoding: the URL-safe alphabet, then at most two `=` that pad to a multiple of 4.
    * This keeps the "signature encoding" failure apart from a wrong signature for the auth-failure dashboard.
    */
  private def isBase64UrlSyntax(segment: String): Boolean =
    val body = segment.reverse.dropWhile(_ == '=').reverse
    val padding = segment.length - body.length
    val alphabetOk = body.forall(c => c.isLetterOrDigit && c < 128 || c == '-' || c == '_')
    alphabetOk && body.length % 4 != 1 && (padding == 0 || (padding <= 2 && segment.length % 4 == 0))

  private def checkAlgorithm(header: JObject): Either[String, Unit] =
    field(header, "alg") match
      case Some(JString("HS256")) => Right(())
      case _                      => Left("Unsupported JWT algorithm")

  private def checkIssuedAt(payload: JObject): Either[String, Unit] =
    val issuedAt: Option[BigInt] = field(payload, "iat").collect {
      case JInt(n)  => n
      case JLong(n) => BigInt(n)
      // json4s reads 1e400 as an infinite double, which BigDecimal refuses; such a value is no usable iat
      case JDouble(n) if !n.isInfinite && !n.isNaN => BigDecimal(n).toBigInt
      case JDecimal(n)                             => n.toBigInt
    }
    issuedAt match
      case None => Left("Missing iat claim")
      case Some(iat) =>
        val now = BigInt(Instant.now().getEpochSecond)
        val skew = (now - iat).abs
        if skew > BigInt(MaxClockSkewSeconds) then Left(s"JWT expired: iat=$iat, now=$now, skew=${skew}s")
        else checkExpiresAt(payload, now)

  /** The optional `exp` claim (RFC 7519 4.1.4): a token past it is refused, as go-ethereum's JWT handler refuses it
    * (golang-jwt's claims validation, `VerifyExpiresAt`; B-419). Absent, nothing is checked; the Engine API spec makes
    * only `iat` required.
    */
  private def checkExpiresAt(payload: JObject, now: BigInt): Either[String, Unit] =
    val expiresAt: Option[BigInt] = field(payload, "exp").collect {
      case JInt(n)                                 => n
      case JLong(n)                                => BigInt(n)
      case JDouble(n) if !n.isInfinite && !n.isNaN => BigDecimal(n).toBigInt
      case JDecimal(n)                             => n.toBigInt
    }
    expiresAt match
      case Some(exp) if now > exp => Left(s"JWT expired: exp=$exp")
      case _                      => Right(())

  /** Decodes a base64url segment (padded or not) holding a JSON object. */
  private def decodeJson(segment: String, what: String): Either[String, JObject] =
    for
      bytes <- decodeSegment(segment, s"Invalid JWT $what encoding")
      text = new String(bytes, StandardCharsets.UTF_8)
      json <- Try(parse(text)).toOption.toRight(s"Invalid JWT $what")
      obj <- asObject(json, s"Invalid JWT $what")
    yield obj

  private def asObject(json: JValue, error: String): Either[String, JObject] =
    json match
      case obj: JObject => Right(obj)
      case _            => Left(error)

  private def field(obj: JObject, name: String): Option[JValue] =
    obj.obj.collectFirst { case (`name`, value) => value }

  private def decodeSegment(segment: String, error: String): Either[String, Array[Byte]] =
    Try(java.util.Base64.getUrlDecoder.decode(segment)).toOption.toRight(error)

  private def hmacSha256(data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))

  private def hexToBytes(hex: String): Array[Byte] =
    val clean = if hex.startsWith("0x") then hex.substring(2) else hex
    clean.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

object JwtAuthenticator extends Logger:

  /** Load the JWT secret from an operator's file: 64 hex characters, `0x` optional, never created or changed. See
    * [[JwtSecretFile.loadExplicit]].
    */
  def fromFile(path: String): JwtAuthenticator = JwtSecretFile.loadExplicit(java.nio.file.Paths.get(path))

  /** Generate a random in-memory 32-byte JWT secret. For tests only: no consensus client can share it, so the node
    * never uses it (B-246; see [[JwtSecretFile]]).
    */
  def generateRandom(): JwtAuthenticator =
    val random = new java.security.SecureRandom()
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    val hex = bytes.map("%02x".format(_)).mkString
    log.info("Generated random JWT secret for Engine API")
    new JwtAuthenticator(hex)
