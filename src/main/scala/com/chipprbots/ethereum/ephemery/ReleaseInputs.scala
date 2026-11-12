package com.chipprbots.ethereum.ephemery

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Try

/** The execution-layer members of one Ephemery release, as text. The consensus-layer members are never read.
  *
  * `retentionVars` is the shell-assignment file the release ships. It is parsed by [[ReleaseInputs.parseRetention]]
  * with a key=value pattern, and never sourced.
  *
  * @param publishedHashText
  *   `deposit_contract_block_hash.txt`: the published genesis block hash (N1)
  * @param chainspecJson
  *   `chainspec.json`, checked only when the release ships it (R11)
  * @param enodesText
  *   `enodes.txt`: the execution-layer bootnodes
  * @param bootEnrText
  *   `boot_enr.txt`: the consensus-layer bootnodes, passed through to `release.env`
  */
final case class ReleaseInputs(
    genesisJson: String,
    retentionVars: String,
    publishedHashText: String,
    chainspecJson: Option[String] = None,
    enodesText: Option[String] = None,
    bootEnrText: Option[String] = None
)

object ReleaseInputs:

  val GenesisFile = "genesis.json"
  val RetentionFile = "retention.vars"
  val PublishedHashFile = "deposit_contract_block_hash.txt"
  val ChainspecFile = "chainspec.json"
  val EnodesFile = "enodes.txt"
  val BootEnrFile = "boot_enr.txt"

  /** No member of a release is anywhere near this: genesis.json is about 50 KB. */
  val MaxMemberBytes: Int = 16 * 1024 * 1024

  private val RetentionLine = """^export ([A-Z_]+)=(?:"([^"]*)"|([0-9A-Za-z_-]*))$""".r
  private val ValueChars = java.util.regex.Pattern.compile("[0-9A-Za-z_-]{0,80}")

  /** The assignments of retention.vars, by name. A line that is not an `export NAME="value"` assignment is ignored (the
    * file is shell, with comments); a name assigned twice with different values is refused, since a shell would take
    * the last and a reader of this file the first.
    */
  def parseRetention(text: String): Either[String, Map[String, String]] =
    text.linesIterator
      .map(_.stripSuffix("\r"))
      .zipWithIndex
      .foldLeft[Either[String, Map[String, String]]](
        Right(Map.empty)
      ) {
        case (Left(e), _)                                                  => Left(e)
        case (acc, (line, _)) if line.trim.isEmpty || line.startsWith("#") => acc
        case (Right(acc), (RetentionLine(name, quoted, bare), n)) =>
          val value = Option(quoted).getOrElse(bare)
          if !ValueChars.matcher(value).matches() then
            Left(
              s"retention.vars line ${n + 1} is not export NAME=\"value\" (the value holds other than 0-9 A-Z a-z _ -)"
            )
          else
            acc.get(name) match
              case Some(earlier) if earlier != value =>
                Left(s"retention.vars assigns $name twice, with different values")
              case _ => Right(acc.updated(name, value))
        case (_, (_, n)) => Left(s"retention.vars line ${n + 1} is not export NAME=\"value\"")
      }

  /** Reads the members of a release from the directory `dir`. A missing `genesis.json` is [[ReleaseRefusal.BadSource]]:
    * the directory is not a release. A missing `retention.vars` or `deposit_contract_block_hash.txt` is
    * [[ReleaseRefusal.Inconsistent]] (N1).
    */
  def fromDir(dir: Path): Either[ReleaseRefusal, ReleaseInputs] =
    if !Files.isDirectory(dir) then Left(ReleaseRefusal.BadSource(s"$dir is not a readable directory"))
    else
      for
        genesis <- required(dir, GenesisFile)(detail => ReleaseRefusal.BadSource(detail))
        retention <- required(dir, RetentionFile)(detail => ReleaseRefusal.Inconsistent(detail))
        hash <- required(dir, PublishedHashFile)(detail => ReleaseRefusal.Inconsistent(detail))
        chainspec <- optional(dir, ChainspecFile)
        enodes <- optional(dir, EnodesFile)
        enrs <- optional(dir, BootEnrFile)
      yield ReleaseInputs(genesis, retention, hash, chainspec, enodes, enrs)

  private def required(dir: Path, name: String)(
      refuse: String => ReleaseRefusal
  ): Either[ReleaseRefusal, String] =
    optional(dir, name).flatMap(_.toRight(refuse(s"$dir has no $name")))

  private def optional(dir: Path, name: String): Either[ReleaseRefusal, Option[String]] =
    val file = dir.resolve(name)
    if Files.isSymbolicLink(file) then
      Left(ReleaseRefusal.Inconsistent(s"$file is a symlink; a release member must be a regular file"))
    else if !Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) then Right(None)
    else if !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) then
      Left(ReleaseRefusal.BadSource(s"$file is not a regular file"))
    else if Files.size(file) > MaxMemberBytes then
      Left(ReleaseRefusal.BadSource(s"$file is larger than $MaxMemberBytes bytes"))
    else
      Try(Files.readAllBytes(file)).toEither.left
        .map(e => ReleaseRefusal.BadSource(s"cannot read $file: ${e.getMessage}"))
        .flatMap(bytes => decode(bytes, file.toString).map(Some(_)))

  /** UTF-8, strictly: a malformed byte is a refusal, not a replacement character. */
  def decode(bytes: Array[Byte], what: String): Either[ReleaseRefusal, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch case _: CharacterCodingException => Left(ReleaseRefusal.BadSource(s"$what is not valid UTF-8"))
