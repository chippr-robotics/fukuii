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
class JwtAuthenticator(secretHex: String) extends Logger:

  private val secretBytes: Array[Byte] = hexToBytes(secretHex.trim)
  private val MaxClockSkewSeconds = 60L

  /** Pekko HTTP directive that validates the JWT bearer token. */
  def authenticate: Directive1[Unit] =
    optionalHeaderValueByType(ClassTag(classOf[Authorization])).flatMap {
      case Some(Authorization(OAuth2BearerToken(token))) =>
        validateToken(token) match
          case Right(_) => provide(())
          case Left(err) =>
            log.warn(s"JWT auth failed: $err")
            EngineApiMetrics.recordAuthFailure()
            complete(StatusCodes.Unauthorized, err)
      case _ =>
        log.warn("JWT auth: missing Authorization header")
        EngineApiMetrics.recordAuthFailure()
        complete(StatusCodes.Unauthorized, "Missing bearer token")
    }

  /** Validates a token the way go-ethereum's `node/jwt_handler.go` does: three base64url segments, a valid HMAC-SHA256
    * signature, a header that names HS256, and an `iat` within [[MaxClockSkewSeconds]] of now. A malformed token is a
    * `Left` with a short reason, never an exception: the caller answers it with 401.
    */
  private def validateToken(token: String): Either[String, Unit] =
    // limit -1 keeps a trailing empty segment, so `a.b.c.` is four segments, not three
    token.split("\\.", -1) match
      case Array(header, payload, signature) =>
        for
          actualSig <- decodeSegment(signature, "Invalid JWT signature encoding")
          _ <- Either.cond(
            MessageDigest.isEqual(hmacSha256(s"$header.$payload"), actualSig),
            (),
            "Invalid JWT signature"
          )
          headerJson <- decodeJson(header, "header")
          _ <- checkAlgorithm(headerJson)
          payloadJson <- decodeJson(payload, "payload")
          _ <- checkIssuedAt(payloadJson)
        yield ()
      case _ => Left("Invalid JWT format")

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
        else Right(())

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
