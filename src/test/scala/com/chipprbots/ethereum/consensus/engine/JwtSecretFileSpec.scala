package com.chipprbots.ethereum.consensus.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** The Engine API's persistent JWT secret (B-246): `<datadir>/jwt.hex`, created the way go-ethereum creates it. */
class JwtSecretFileSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private var dir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = dir = Files.createTempDirectory("jwt-secret")

  override def afterEach(): Unit =
    Files.list(dir).iterator().asScala.toList.foreach(Files.delete)
    Files.delete(dir)

  private def perms(p: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  private val Hex64 = "[0-9a-f]{64}"
  private val Good = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

  private def write(p: Path, text: String, mode: String): Unit =
    Files.writeString(p, text)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode))

  /** Every message the object under test logs while `f` runs. */
  private def logged[A](f: => A): (A, List[String]) =
    val logger = LoggerFactory.getLogger(JwtSecretFile.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    val result =
      try f
      finally
        logger.setLevel(previous)
        logger.detachAppender(appender)
    (result, appender.list.asScala.toList.map(_.getFormattedMessage))

  "JwtSecretFile.loadOrCreate" should "create the file at 0600 with 64 hex characters when it is absent" taggedAs UnitTest in {
    val path = dir.resolve("jwt.hex")
    JwtSecretFile.loadOrCreate(path, new SecureRandom())
    Files.isRegularFile(path) shouldBe true
    perms(path) shouldBe "rw-------"
    new String(Files.readAllBytes(path), UTF_8).trim should fullyMatch.regex(Hex64)
  }

  it should "create the missing parent directory and leave no temporary file" taggedAs UnitTest in {
    val path = dir.resolve("sub").resolve("jwt.hex")
    JwtSecretFile.loadOrCreate(path, new SecureRandom())
    Files.isRegularFile(path) shouldBe true
    Files.list(dir.resolve("sub")).iterator().asScala.toList.map(_.getFileName.toString) shouldBe List("jwt.hex")
    Files.delete(path)
    Files.delete(dir.resolve("sub"))
  }

  it should "reuse an existing file and leave its bytes and permissions alone" taggedAs UnitTest in {
    val path = dir.resolve("jwt.hex")
    write(path, "0x" + Good + "\n", "r--------")
    JwtSecretFile.loadOrCreate(path, new SecureRandom())
    new String(Files.readAllBytes(path), UTF_8) shouldBe "0x" + Good + "\n"
    perms(path) shouldBe "r--------"
  }

  it should "load the same secret on the second call as the first created" taggedAs UnitTest in {
    val path = dir.resolve("jwt.hex")
    JwtSecretFile.loadOrCreate(path, new SecureRandom())
    val first = Files.readString(path)
    JwtSecretFile.loadOrCreate(path, new SecureRandom())
    Files.readString(path) shouldBe first
  }

  it should "use the random source it is given for the 32 bytes" taggedAs UnitTest in {
    val path = dir.resolve("jwt.hex")
    val fixed = new SecureRandom():
      override def nextBytes(bytes: Array[Byte]): Unit = java.util.Arrays.fill(bytes, 0xab.toByte)
    JwtSecretFile.loadOrCreate(path, fixed)
    Files.readString(path).trim shouldBe "ab" * 32
  }

  it should "refuse a file that is not 64 hex characters, naming the path and how to fix it" taggedAs UnitTest in {
    val bad = List("", "abc", Good.dropRight(2), Good + "00", Good.dropRight(1) + "g", "0x" + Good.dropRight(2))
    bad.foreach { text =>
      val path = dir.resolve("jwt.hex")
      write(path, text, "rw-------")
      val e = the[IllegalStateException] thrownBy JwtSecretFile.loadOrCreate(path, new SecureRandom())
      e.getMessage should include(path.toString)
      e.getMessage should include("64 hex")
      e.getMessage should include("delete")
      if text.length > 8 then (e.getMessage should not).include(text)
      Files.delete(path)
    }
  }

  it should "refuse a file readable by its group or by others, and say how to fix it" taggedAs UnitTest in {
    List("rw-r-----", "rw----r--", "rw-rw-rw-", "r--r--r--").foreach { mode =>
      val path = dir.resolve("jwt.hex")
      write(path, Good, mode)
      val e = the[IllegalStateException] thrownBy JwtSecretFile.loadOrCreate(path, new SecureRandom())
      e.getMessage should include(path.toString)
      e.getMessage should include("chmod 600")
      Files.setPosixFilePermissions(path, Set(PosixFilePermission.OWNER_WRITE).asJava)
      Files.delete(path)
    }
  }

  it should "never widen the permissions of an existing file it refuses" taggedAs UnitTest in {
    val path = dir.resolve("jwt.hex")
    write(path, Good, "rw-r-----")
    an[IllegalStateException] should be thrownBy JwtSecretFile.loadOrCreate(path, new SecureRandom())
    perms(path) shouldBe "rw-r-----"
  }

  it should "log the path at INFO and never the secret, on create or on load" taggedAs UnitTest in {
    val created = dir.resolve("created.hex")
    val (_, onCreate) = logged(JwtSecretFile.loadOrCreate(created, new SecureRandom()))
    val secret = Files.readString(created).trim
    onCreate.exists(_.contains(created.toString)) shouldBe true
    onCreate.foreach(m => (m should not).include(secret))

    val existing = dir.resolve("existing.hex")
    write(existing, "0x" + Good, "rw-------")
    val (_, onLoad) = logged(JwtSecretFile.loadOrCreate(existing, new SecureRandom()))
    onLoad.exists(_.contains(existing.toString)) shouldBe true
    onLoad.foreach(m => (m should not).include(Good))
    Files.delete(created)
    Files.delete(existing)
  }

  it should "build an authenticator whose secret is the file's" taggedAs UnitTest in {
    val path = dir.resolve("jwt.hex")
    write(path, Good, "rw-------")
    // an authenticator from the same hex agrees: both reject a token neither signed, which is all a unit can see
    // without the HTTP route; the HS256 check itself is JwtAuthenticatorSpec's
    JwtSecretFile.loadOrCreate(path, new SecureRandom()) shouldBe a[JwtAuthenticator]
  }

  "JwtSecretFile.authenticator" should "use an explicitly configured path in preference to the datadir file" taggedAs UnitTest in {
    val explicit = dir.resolve("explicit.hex")
    write(explicit, Good, "rw-------")
    val datadir = dir.resolve("data")
    JwtSecretFile.authenticator(Some(explicit.toString), false, datadir.toString, new SecureRandom())
    Files.exists(datadir.resolve("jwt.hex")) shouldBe false
    Files.delete(explicit)
  }

  it should "default to <datadir>/jwt.hex when no path is configured" taggedAs UnitTest in {
    val datadir = dir.resolve("data")
    JwtSecretFile.authenticator(None, false, datadir.toString, new SecureRandom())
    Files.isRegularFile(datadir.resolve("jwt.hex")) shouldBe true
    perms(datadir.resolve("jwt.hex")) shouldBe "rw-------"
    Files.delete(datadir.resolve("jwt.hex"))
    Files.delete(datadir)
  }

  it should "create a path the node pinned itself, and read an operator's path as it always has, creating nothing" taggedAs UnitTest in {
    val pinned = dir.resolve("root").resolve("jwt.hex")
    JwtSecretFile.authenticator(Some(pinned.toString), true, dir.resolve("data").toString, new SecureRandom())
    Files.isRegularFile(pinned) shouldBe true
    perms(pinned) shouldBe "rw-------"
    Files.delete(pinned)
    Files.delete(pinned.getParent)

    val operators = dir.resolve("operators.hex")
    an[IllegalStateException] should be thrownBy
      JwtSecretFile.authenticator(Some(operators.toString), false, dir.toString, new SecureRandom())
    Files.exists(operators) shouldBe false
  }

  it should "refuse a pinned file that is readable by others, as it does <datadir>/jwt.hex" taggedAs UnitTest in {
    val pinned = dir.resolve("pinned.hex")
    write(pinned, Good, "rw-rw-r--")
    an[IllegalStateException] should be thrownBy
      JwtSecretFile.authenticator(Some(pinned.toString), true, dir.toString, new SecureRandom())
    Files.delete(pinned)
  }

  it should "never replace a file that appears while it creates one, and agree with every concurrent creator" taggedAs UnitTest in {
    val path = dir.resolve("raced.hex")
    val n = 8
    val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
    val start = new java.util.concurrent.CountDownLatch(1)
    try
      val futures = (1 to n).map { _ =>
        pool.submit(
          new java.util.concurrent.Callable[String]:
            override def call(): String =
              start.await()
              JwtSecretFile.loadOrCreateHex(path, new SecureRandom())
        )
      }
      start.countDown()
      val secrets = futures.map(_.get()).toSet
      secrets should have size 1
      secrets.head shouldBe Files.readString(path).trim
    finally pool.shutdownNow()
  }

  it should "leave the bytes of a file that already exists exactly as they were" taggedAs UnitTest in {
    val path = dir.resolve("jwt.hex")
    write(path, "0x" + Good + "\n", "rw-------")
    JwtSecretFile.loadOrCreateHex(path, new SecureRandom()) shouldBe Good
    Files.readString(path) shouldBe "0x" + Good + "\n"
  }

  it should "refuse a symlink at the path, and write nothing through it" taggedAs UnitTest in {
    val target = dir.resolve("real.hex")
    val link = dir.resolve("jwt.hex")
    Files.createSymbolicLink(link, target)
    val e = the[IllegalStateException] thrownBy JwtSecretFile.loadOrCreate(link, new SecureRandom())
    e.getMessage should include(link.toString)
    e.getMessage should include("symbolic link")
    Files.exists(target) shouldBe false
    Files.delete(link)
  }

  it should "turn an I/O failure into the same refusal" taggedAs UnitTest in {
    val file = dir.resolve("a-file")
    Files.writeString(file, "x")
    val e = the[IllegalStateException] thrownBy JwtSecretFile.loadOrCreate(file.resolve("jwt.hex"), new SecureRandom())
    e.getMessage should include("jwt.hex")
    Files.delete(file)
  }

  "an explicit jwt-secret-path" should "never be created, and a missing file is a clear refusal" taggedAs UnitTest in {
    val missing = dir.resolve("missing.hex")
    val e = the[IllegalStateException] thrownBy
      JwtSecretFile.authenticator(Some(missing.toString), false, dir.toString, new SecureRandom())
    e.getMessage should include(missing.toString)
    e.getMessage should include("never created")
    Files.exists(missing) shouldBe false
  }

  it should "take 64 hex characters, with an optional 0x and surrounding whitespace, and refuse anything else" taggedAs UnitTest in {
    val path = dir.resolve("explicit.hex")
    List(Good, "0x" + Good, "  " + Good + "\n\n").foreach { text =>
      write(path, text, "rw-------")
      JwtAuthenticator.fromFile(path.toString) shouldBe a[JwtAuthenticator]
    }
    List("", "abc", Good + "00", Good.dropRight(1) + "g").foreach { text =>
      write(path, text, "rw-------")
      val e = the[IllegalStateException] thrownBy JwtAuthenticator.fromFile(path.toString)
      e.getMessage should include(path.toString)
      e.getMessage should include("64 hex")
      if text.length > 8 then (e.getMessage should not).include(text)
    }
  }

  it should "only warn about a group- or world-readable file, and leave its permissions alone" taggedAs UnitTest in {
    val path = dir.resolve("explicit.hex")
    write(path, Good, "rw-r--r--")
    val (_, messages) = logged(JwtAuthenticator.fromFile(path.toString))
    perms(path) shouldBe "rw-r--r--"
    messages.exists(m => m.contains(path.toString) && m.contains("readable")) shouldBe true
    messages.foreach(m => (m should not).include(Good))
  }

  it should "fall back to a plain move, never a replace, when hard links are not supported" taggedAs UnitTest in {
    // The fallback is taken for UnsupportedOperationException, AtomicMoveNotSupportedException and any other
    // FileSystemException from createLink except FileAlreadyExistsException (G2). A seam for a link that fails would
    // need an injectable filesystem; what can be shown here is the contract of the fallback itself: Files.move without
    // REPLACE_EXISTING refuses an existing target, so a file that appeared first still wins.
    val existing = dir.resolve("winner.hex")
    write(existing, Good, "rw-------")
    val tmp = Files.createTempFile(dir, "jwt-", ".tmp")
    a[java.nio.file.FileAlreadyExistsException] should be thrownBy Files.move(tmp, existing)
    Files.readString(existing) shouldBe Good
    Files.delete(tmp)
    JwtSecretFile.isLinkUnsupported(
      new java.nio.file.FileSystemException("x", null, "Operation not permitted")
    ) shouldBe true
    JwtSecretFile.isLinkUnsupported(new java.nio.file.FileAlreadyExistsException("x")) shouldBe false
    JwtSecretFile.isLinkUnsupported(new UnsupportedOperationException) shouldBe true
    JwtSecretFile.isLinkUnsupported(new java.nio.file.AtomicMoveNotSupportedException("a", "b", "c")) shouldBe true
    JwtSecretFile.isLinkUnsupported(new java.io.IOException("disk")) shouldBe false
  }
