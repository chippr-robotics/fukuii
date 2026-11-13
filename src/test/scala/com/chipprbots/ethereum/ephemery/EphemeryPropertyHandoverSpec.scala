package com.chipprbots.ethereum.ephemery

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.engine.JwtSecretFile
import com.chipprbots.ethereum.ephemery.EphemeryResolver.*
import com.chipprbots.ethereum.testing.Tags.*

/** What reaches the node's configuration when the resolver's properties are applied (spec 028 R5, contracts/config.md,
  * "What moves at each reset"). It loads a fresh `ConfigFactory.load()` and never the `Config` object, which is
  * process-wide and read once.
  */
class EphemeryPropertyHandoverSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private val inside165 = Instant.parse("2026-11-12T10:00:00Z")

  private var base: Path = scala.compiletime.uninitialized
  private def root: Path = base.resolve("ephemery")

  private val touched =
    EphemeryResolver.PropertyKeys ++ List("fukuii.datadir", "logging.logs-dir", "fukuii.blockchains.custom-chains-dir")

  private def clear(): Unit =
    touched.foreach(System.clearProperty)
    ConfigFactory.invalidateCaches()

  override def beforeEach(): Unit =
    clear()
    base = Files.createTempDirectory("eph-handover")

  override def afterEach(): Unit =
    clear()
    delete(base)

  private def delete(p: Path): Unit =
    if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then
      if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then Files.list(p).iterator().asScala.toList.foreach(delete)
      Files.delete(p)

  /** The configuration of `fukuii ephemery`: the shipped conf/ephemery.conf, with the system properties on top. */
  private def load() = ConfigFactory.load(ConfigFactory.parseResources("conf/ephemery.conf"))

  "the properties of a managed iteration" should "move every datadir-derived path and pin the logs" taggedAs UnitTest in {
    // as `-Dfukuii.datadir=<root>` on the command line
    System.setProperty("fukuii.datadir", root.toString)
    ConfigFactory.invalidateCaches()
    val before = load()
    before.getString("fukuii.datadir") shouldBe root.toString
    before.getString("logging.logs-dir") shouldBe s"$root/logs"

    val settings = Settings.from(before)
    val result = EphemeryResolver.resolve(
      settings,
      StartupDeps(Clock.fixed(inside165, ZoneOffset.UTC), RefusingTransport, _ => ()),
      BundleSource.classpath
    )
    result.properties.foreach { case (k, v) => System.setProperty(k, v) }
    ConfigFactory.invalidateCaches()

    val after = load()
    val iteration = s"$root/ephemery-165"
    after.getString("fukuii.datadir") shouldBe iteration
    after.getString("fukuii.db.rocksdb.path") shouldBe s"$iteration/rocksdb/"
    after.getString("fukuii.node-key-file") shouldBe s"$iteration/node.key"
    after.getString("fukuii.keyStore.keystore-dir") shouldBe s"$iteration/keystore"
    after.getString("fukuii.tmpdir") shouldBe s"$iteration/tmp"
    after.getString("fukuii.network.rpc.ipc.socket-file") shouldBe s"$iteration/fukuii.ipc"
    after.getString("logging.logs-dir") shouldBe s"$root/logs"
    // not derived from the iteration: the secret survives a reset, so the consensus client keeps working
    after.getString("fukuii.network.engine-api.jwt-secret-path") shouldBe s"$root/jwt.hex"
    after.getBoolean("fukuii.network.engine-api.enabled") shouldBe true
    after.getBoolean("fukuii.ephemery.active.jwt-secret-defaulted") shouldBe true

    // what NodeBuilder does with it on a fresh root: the file is created there, at 0600, outside the iteration
    val engine = after.getConfig("fukuii.network.engine-api")
    JwtSecretFile.authenticator(
      Some(engine.getString("jwt-secret-path")),
      after.getBoolean("fukuii.ephemery.active.jwt-secret-defaulted"),
      after.getString("fukuii.datadir"),
      new java.security.SecureRandom()
    )
    val secret = root.resolve("jwt.hex")
    Files.isRegularFile(secret) shouldBe true
    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(secret)) shouldBe "rw-------"
    Files.exists(root.resolve("ephemery-165/jwt.hex")) shouldBe false
    after.getString("fukuii.blockchains.custom-chains-dir") shouldBe s"$root/releases/ephemery-165/chains"
    after.getInt("fukuii.ephemery.active.iteration") shouldBe 165
    after.getLong("fukuii.ephemery.active.reset-time") shouldBe 1795114800L
    after.getString("fukuii.ephemery.active.genesis-hash") shouldBe
      "0x9485bbe8e32e278194666d249590c9167057edeabaecc62e7a68355f68305ecc"
    after.getBoolean("fukuii.ephemery.active.managed") shouldBe true
  }

  it should "leave an absolute path an operator set where it is" taggedAs UnitTest in {
    System.setProperty("fukuii.datadir", root.toString)
    System.setProperty("fukuii.node-key-file", s"$base/stable.key")
    try
      ConfigFactory.invalidateCaches()
      val settings = Settings.from(load())
      val result = EphemeryResolver.resolve(
        settings,
        StartupDeps(Clock.fixed(inside165, ZoneOffset.UTC), RefusingTransport, _ => ()),
        BundleSource.classpath
      )
      result.properties.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      load().getString("fukuii.node-key-file") shouldBe s"$base/stable.key"
    finally System.clearProperty("fukuii.node-key-file")
  }

  "the shipped conf/ephemery.conf" should "carry the fukuii.ephemery defaults and no default custom-chains-dir" taggedAs UnitTest in {
    val conf = load()
    conf.hasPath("fukuii.blockchains.custom-chains-dir") shouldBe false
    conf.getBoolean("fukuii.ephemery.refresh") shouldBe true
    conf.getString("fukuii.ephemery.source") shouldBe "https://github.com/ephemery-testnet/ephemery-genesis"
    conf.getDuration("fukuii.ephemery.refresh-timeout").getSeconds shouldBe 60L
    conf.getBoolean("fukuii.ephemery.exit-at-reset") shouldBe true
  }
