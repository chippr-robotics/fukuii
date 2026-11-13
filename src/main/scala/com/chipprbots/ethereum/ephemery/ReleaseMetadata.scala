package com.chipprbots.ethereum.ephemery

import java.time.Instant
import java.util.regex.Pattern

import scala.util.Try

import com.chipprbots.ethereum.ephemery.StrictJson.*

/** The `release.json` written next to a validated release (spec 028 contracts/release-json.md). It records what a
  * reader needs without re-deriving it, and it is never trusted alone: a reader re-runs [[EphemeryRelease.validate]]
  * over the release's own files and compares the genesis hash.
  *
  * @param chainId
  *   a decimal string in the file, so no JSON reader rounds it
  * @param source
  *   the release page URL, or the absolute local path; never a redirect or asset URL, which carry signed queries
  * @param archiveSha256
  *   informational: the tarball is not signed, so this proves nothing about authenticity
  */
final case class ReleaseMetadata(
    release: String,
    iteration: Int,
    chainId: BigInt,
    genesisTimestamp: Long,
    resetInterval: Long,
    resetTime: Long,
    genesisHash: String,
    source: String,
    archiveSha256: Option[String],
    producedBy: String,
    producedAt: Instant
):

  /** The file's text, in the key order of the contract. */
  def toJson: String =
    render(
      JObj(
        Vector(
          "schema" -> JNum(ReleaseMetadata.Schema.toString),
          "release" -> JStr(release),
          "iteration" -> JNum(iteration.toString),
          "chainId" -> JStr(chainId.toString),
          "genesisTimestamp" -> JNum(genesisTimestamp.toString),
          "resetInterval" -> JNum(resetInterval.toString),
          "resetTime" -> JNum(resetTime.toString),
          "genesisHash" -> JStr(genesisHash),
          "source" -> JStr(source),
          "archiveSha256" -> archiveSha256.fold[Js](JNull)(JStr(_)),
          "producedBy" -> JStr(producedBy),
          "producedAt" -> JStr(producedAt.toString)
        )
      )
    )

object ReleaseMetadata:

  /** The only schema this build reads. An unknown value makes a cache entry invisible (logged, never deleted). */
  val Schema: Int = 1

  private val ReleaseName = Pattern.compile("ephemery-[0-9]+")
  private val Hash = Pattern.compile("0x[0-9a-f]{64}")
  private val Sha256 = Pattern.compile("[0-9a-f]{64}")
  private val Digits = Pattern.compile("[0-9]{1,80}")

  /** The metadata of a validated release. `source`, `archiveSha256` and `producedBy` are the caller's to give. */
  def of(
      release: EphemeryRelease.ValidRelease,
      source: String,
      archiveSha256: Option[String],
      producedBy: String,
      producedAt: Instant
  ): ReleaseMetadata =
    ReleaseMetadata(
      release = release.release,
      iteration = release.iteration,
      chainId = release.chainId,
      genesisTimestamp = release.genesisTimestamp,
      resetInterval = release.resetInterval,
      resetTime = release.resetTime,
      genesisHash = release.genesisHashHex,
      source = source,
      archiveSha256 = archiveSha256,
      producedBy = producedBy,
      producedAt = producedAt
    )

  /** What `source` may be: an `https` URL with no credentials, query or fragment, or an absolute local path. */
  def sourceProblem(source: String): Option[String] =
    if source.startsWith("https://") then
      Try(new java.net.URI(source)).toOption match
        case Some(uri)
            if uri.getHost != null && uri.getUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null =>
          None
        case _ => Some("source is not an https URL without credentials, query or fragment")
    else if source.startsWith("/") && !source.exists(_ < ' ') then None
    else Some("source is neither an https URL nor an absolute path")

  /** Parses and checks the file. `Left` names the first rule broken. */
  def decode(text: String): Either[String, ReleaseMetadata] =
    for
      js <- StrictJson.parse(text)
      obj <- js match
        case o: JObj => Right(o)
        case _       => Left("release.json is not a JSON object")
      schema <- integer(obj, "schema")
      _ <- Either.cond(schema == BigInt(Schema), (), s"release.json has schema $schema, which this build does not read")
      release <- string(obj, "release")
      _ <- Either.cond(ReleaseName.matcher(release).matches(), (), s"release '$release' is not ephemery-<N>")
      iterationBig <- integer(obj, "iteration")
      iteration <- Either.cond(
        iterationBig.isValidInt && iterationBig >= 0,
        iterationBig.toInt,
        "iteration is out of range"
      )
      _ <- Either.cond(release == s"ephemery-$iteration", (), s"release '$release' is not ephemery-$iteration")
      chainIdText <- string(obj, "chainId")
      _ <- Either.cond(Digits.matcher(chainIdText).matches(), (), s"chainId '$chainIdText' is not a decimal string")
      chainId = BigInt(chainIdText)
      _ <- Either.cond(
        chainId - EphemeryRelease.IterationBase == BigInt(iteration),
        (),
        s"iteration $iteration is not chainId $chainId minus ${EphemeryRelease.IterationBase}"
      )
      timestamp <- long(obj, "genesisTimestamp")
      interval <- long(obj, "resetInterval")
      resetTime <- long(obj, "resetTime")
      _ <- Either.cond(resetTime == timestamp + interval, (), "resetTime is not genesisTimestamp + resetInterval")
      hash <- string(obj, "genesisHash")
      _ <- Either.cond(Hash.matcher(hash).matches(), (), s"genesisHash '$hash' is not 0x and 64 lowercase hex digits")
      source <- string(obj, "source")
      _ <- sourceProblem(source).toLeft(())
      sha <- obj.get("archiveSha256") match
        case None | Some(JNull)                           => Right(None)
        case Some(JStr(s)) if Sha256.matcher(s).matches() => Right(Some(s))
        case Some(_) => Left("archiveSha256 is not null or 64 lowercase hex digits")
      by <- string(obj, "producedBy")
      atText <- string(obj, "producedAt")
      at <- Try(Instant.parse(atText)).toEither.left.map(_ => s"producedAt '$atText' is not an instant")
    yield ReleaseMetadata(release, iteration, chainId, timestamp, interval, resetTime, hash, source, sha, by, at)

  private def string(obj: JObj, key: String): Either[String, String] = obj.get(key) match
    case Some(JStr(s)) => Right(s)
    case _             => Left(s"$key is missing or not a string")

  private def integer(obj: JObj, key: String): Either[String, BigInt] = obj.get(key) match
    case Some(n: JNum) if n.isInteger => Right(BigInt(n.raw))
    case _                            => Left(s"$key is missing or not an integer")

  private def long(obj: JObj, key: String): Either[String, Long] =
    integer(obj, key).flatMap(n => Either.cond(n.isValidLong && n >= 0, n.toLong, s"$key is out of range"))
