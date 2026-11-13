package com.chipprbots.ethereum.consensus.engine

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.regex.Pattern

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** The Engine API's persistent JWT secret, created the way go-ethereum creates `<datadir>/geth/jwtsecret` (B-246).
  *
  * When the Engine API is enabled and `network.engine-api.jwt-secret-path` is unset, the secret lives in
  * `<datadir>/jwt.hex`: an existing file is loaded, and an absent one is created atomically (a temporary file with mode
  * 0600 from its creation, then a rename) holding 32 random bytes as 64 hex characters. The secret never reaches a log
  * line or an exception message: only the path does.
  *
  * A file that is not exactly 64 hex characters (optionally `0x`-prefixed), or that is readable by its group or by
  * others, is refused with an [[IllegalStateException]] that says how to fix it. The node does not start on it, and the
  * file is never changed.
  */
object JwtSecretFile extends Logger:

  val FileName: String = "jwt.hex"

  private val HexSecret = Pattern.compile("(?:0[xX])?[0-9a-fA-F]{64}")
  private val Private = PosixFilePermissions.fromString("rw-------")
  private val Widened: Set[PosixFilePermission] = Set(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE,
    PosixFilePermission.OTHERS_EXECUTE
  )

  /** The authenticator for the Engine API.
    *
    * An operator's `jwt-secret-path` behaves as it always has: [[JwtAuthenticator.fromFile]], which neither creates nor
    * restricts the file. No path means `<datadir>/jwt.hex`. `pathIsDefault` marks a path the node itself chose, which
    * `fukuii ephemery` pins to `<root>/jwt.hex` (spec 028): it is created and checked like `<datadir>/jwt.hex`.
    */
  def authenticator(
      configuredPath: Option[String],
      pathIsDefault: Boolean,
      datadir: String,
      random: SecureRandom
  ): JwtAuthenticator =
    configuredPath match
      case Some(path) if pathIsDefault => loadOrCreate(Paths.get(path), random)
      case Some(path)                  => JwtAuthenticator.fromFile(path)
      case None                        => loadOrCreate(Paths.get(datadir).resolve(FileName), random)

  def loadOrCreate(path: Path, random: SecureRandom): JwtAuthenticator =
    if !Files.exists(path) then create(path, random)
    val secret = read(path)
    log.info(s"Engine API JWT secret file: $path")
    new JwtAuthenticator(secret)

  private def create(path: Path, random: SecureRandom): Unit =
    val parent = Option(path.toAbsolutePath.getParent).getOrElse(Paths.get("."))
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    val hex = bytes.map(b => f"${b & 0xff}%02x").mkString
    try
      Files.createDirectories(parent)
      val tmp =
        if supportsPosix(parent) then
          Files.createTempFile(parent, "jwt-", ".tmp", PosixFilePermissions.asFileAttribute(Private))
        else Files.createTempFile(parent, "jwt-", ".tmp")
      try
        Files.write(tmp, hex.getBytes(StandardCharsets.US_ASCII))
        move(tmp, path)
        log.info(s"Created the Engine API JWT secret file $path")
      finally Files.deleteIfExists(tmp)
    catch
      case NonFatal(e) =>
        throw new IllegalStateException(s"Cannot create the Engine API JWT secret file $path: ${e.getMessage}", e)

  private def move(from: Path, to: Path): Unit =
    try Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    catch case _: AtomicMoveNotSupportedException => Files.move(from, to)

  private def supportsPosix(dir: Path): Boolean =
    Files.getFileStore(dir).supportsFileAttributeView("posix")

  /** The secret in `path`, as the 64 hex characters it holds, or a refusal. */
  private def read(path: Path): String =
    if supportsPosix(path.toAbsolutePath.getParent) then
      val widened =
        try Files.getPosixFilePermissions(path).asScala.toSet.intersect(Widened)
        catch
          case _: NoSuchFileException =>
            throw refuse(path, "the file disappeared while it was being read")
          case e: IOException => throw refuse(path, s"cannot read its permissions: ${e.getMessage}")
      if widened.nonEmpty then
        throw refuse(
          path,
          s"it is readable by its group or by others (${PosixFilePermissions.toString(Files.getPosixFilePermissions(path))}); " +
            s"run `chmod 600 $path`, or delete it to have a new secret created"
        )
    val text =
      try new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim
      catch case e: IOException => throw refuse(path, s"cannot read it: ${e.getMessage}")
    if !HexSecret.matcher(text).matches() then
      throw refuse(
        path,
        "it must hold exactly 64 hex characters (32 bytes), optionally prefixed with 0x; " +
          s"fix it, or delete it to have a new secret created"
      )
    text

  private def refuse(path: Path, why: String): IllegalStateException =
    new IllegalStateException(s"Refusing to start: the Engine API JWT secret file $path is not usable: $why")
