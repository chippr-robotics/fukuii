package com.chipprbots.ethereum.consensus.engine

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** The Engine API's JWT secret file (B-246).
  *
  * Default file. When the Engine API is enabled and `network.engine-api.jwt-secret-path` is unset, the secret lives in
  * `<datadir>/jwt.hex`, created the way go-ethereum creates `<datadir>/geth/jwtsecret`: an existing file is loaded, and
  * an absent one is created holding 32 random bytes as 64 hex characters. The file is written and synced under a
  * temporary name at mode 0600, then hard-linked to its name, which fails if that name exists, so a file that appears
  * meanwhile is never replaced: the loser reads the winner's. A file that is not exactly 64 hex characters (optionally
  * `0x`-prefixed), or that is readable by its group or by others, or is a symbolic link, is refused with an
  * [[IllegalStateException]] saying how to fix it. Symbolic links are refused rather than followed, so a link cannot
  * lead the check, or the creation, to a file elsewhere.
  *
  * Explicit path. An operator's path is never created and never chmod'ed. The file must exist and hold the same 64 hex
  * characters; a group- or world-readable one only draws a WARN, as the file may be shared on purpose.
  *
  * The secret never reaches a log line or an exception message: only the path does.
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
  private val nonPosixWarned = new AtomicBoolean(false)

  /** The authenticator for the Engine API.
    *
    * `pathIsDefault` marks a path the node itself chose (`fukuii ephemery` pins `<root>/jwt.hex`, and tells the node
    * through `EphemeryResolver.pinnedJwtSecretPath`): it is created and checked like `<datadir>/jwt.hex`. Any other
    * configured path is an operator's, read as it is and never created.
    */
  def authenticator(
      configuredPath: Option[String],
      pathIsDefault: Boolean,
      datadir: String,
      random: SecureRandom
  ): JwtAuthenticator =
    configuredPath match
      case Some(path) if pathIsDefault => loadOrCreate(Paths.get(path), random)
      case Some(path)                  => loadExplicit(Paths.get(path))
      case None                        => loadOrCreate(Paths.get(datadir).resolve(FileName), random)

  def loadOrCreate(path: Path, random: SecureRandom): JwtAuthenticator =
    new JwtAuthenticator(loadOrCreateHex(path, random))

  /** The secret of a default file, as 64 hex characters (with no `0x`), creating the file if it is absent. */
  private[engine] def loadOrCreateHex(path: Path, random: SecureRandom): String =
    try
      if Files.isSymbolicLink(path) then throw refuse(path, "it is a symbolic link; use the real file's path")
      if !Files.exists(path) then create(path, random)
      val secret = read(path)
      log.info(s"Engine API JWT secret file: $path")
      secret
    catch case e: IOException => throw refuse(path, s"cannot access it: ${e.getMessage}")

  /** An operator's file: it must exist, hold 64 hex characters, and is never changed. */
  def loadExplicit(path: Path): JwtAuthenticator =
    val text =
      try new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      catch
        case _: NoSuchFileException =>
          throw refuse(path, "it does not exist, and a configured jwt-secret-path is never created: create it first")
        case e: IOException => throw refuse(path, s"cannot read it: ${e.getMessage}")
    val secret = parse(path, text)
    if widenedBy(path).nonEmpty then
      log.warn(
        s"Engine API JWT secret file $path is readable by its group or by others; " +
          "restrict it with `chmod 600` unless that is intended"
      )
    log.info(s"Loaded the Engine API JWT secret from $path")
    new JwtAuthenticator(secret)

  private def create(path: Path, random: SecureRandom): Unit =
    val parent = Option(path.toAbsolutePath.getParent).getOrElse(Paths.get("."))
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    val hex = bytes.map(b => f"${b & 0xff}%02x").mkString
    Files.createDirectories(parent)
    val tmp =
      if supportsPosix(parent) then
        Files.createTempFile(parent, "jwt-", ".tmp", PosixFilePermissions.asFileAttribute(Private))
      else Files.createTempFile(parent, "jwt-", ".tmp")
    try
      val channel = FileChannel.open(tmp, StandardOpenOption.WRITE)
      try
        channel.write(ByteBuffer.wrap(hex.getBytes(StandardCharsets.US_ASCII)))
        channel.force(true)
      finally channel.close()
      // Neither a link nor a plain move replaces an existing target: the file that got there first wins.
      try
        try Files.createLink(path, tmp)
        catch case e if isLinkUnsupported(e) => Files.move(tmp, path)
        log.info(s"Created the Engine API JWT secret file $path")
      catch case _: FileAlreadyExistsException => log.info(s"Engine API JWT secret file $path was created meanwhile")
    finally Files.deleteIfExists(tmp)

  /** A failure of `createLink` that means the filesystem cannot make hard links (FAT, some network and FUSE mounts
    * report a plain `FileSystemException`), as opposed to "the name exists", which is the race this method exists to
    * lose gracefully.
    */
  private[engine] def isLinkUnsupported(e: Throwable): Boolean =
    e match
      case _: FileAlreadyExistsException                                         => false
      case _: UnsupportedOperationException | _: AtomicMoveNotSupportedException => true
      case _: java.nio.file.FileSystemException                                  => true
      case _                                                                     => false

  /** True on a filesystem with POSIX permissions; warns once, when not, that the file's permissions cannot be held. */
  private def supportsPosix(dir: Path): Boolean =
    val posix = Files.getFileStore(dir).supportsFileAttributeView("posix")
    if !posix && nonPosixWarned.compareAndSet(false, true) then
      log.warn(
        s"The filesystem of $dir has no POSIX permissions: the Engine API JWT secret file's permissions " +
          "cannot be enforced or checked there"
      )
    posix

  private def widenedBy(path: Path): Set[PosixFilePermission] =
    try
      if supportsPosix(path.toAbsolutePath.getParent) then
        Files.getPosixFilePermissions(path).asScala.toSet.intersect(Widened)
      else Set.empty
    catch case NonFatal(_) => Set.empty

  /** The secret in a default file, as the 64 hex characters it holds, or a refusal. */
  private def read(path: Path): String =
    if widenedBy(path).nonEmpty then
      throw refuse(
        path,
        s"it is readable by its group or by others (${PosixFilePermissions.toString(Files.getPosixFilePermissions(path))}); " +
          s"run `chmod 600 $path`, or delete it to have a new secret created"
      )
    parse(path, new String(Files.readAllBytes(path), StandardCharsets.UTF_8))

  private def parse(path: Path, raw: String): String =
    val text = raw.trim
    if !HexSecret.matcher(text).matches() then
      throw refuse(
        path,
        "it must hold exactly 64 hex characters (32 bytes), optionally prefixed with 0x; " +
          "fix it, or delete it to have a new secret created"
      )
    text.stripPrefix("0x").stripPrefix("0X")

  private def refuse(path: Path, why: String): IllegalStateException =
    new IllegalStateException(s"Refusing to start: the Engine API JWT secret file $path is not usable: $why")
