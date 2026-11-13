package com.chipprbots.ethereum.ephemery

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 028 S3 (US1, US5, FR-001, FR-016): `fukuii ephemery` with nothing prepared starts on the bundled iteration in a
  * datadir of its own, and exits 75 when that iteration ends.
  *
  * Each case forks a JVM running [[EphemeryForkedHarness]] (the fork pattern of `HealCrashSafetyItSpec`): the real
  * `App.run`, an empty temporary Ephemery root, a clock that reads the instant the test gives, and the real exit. The
  * node runs isolated: every listener is on 127.0.0.1 and on a port this test found free (never 30303, 8545, 8551 or
  * 8546), discovery and bootstrap nodes are off, nothing is advertised or detected, and `user.home` is a temporary
  * directory. No wait is for time to pass: each waits for a line of the child's output.
  *
  * The class-load order is checked too: `utils.Config$` must be loaded only after the resolver has reported, which is
  * what "the resolver does not initialise `Config`" means (the child runs with `-Xlog:class+load`).
  */
class EphemeryLaunchItSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  // ephemery-165: genesis 2026-10-22T19:00:00Z, reset 2026-11-19T19:00:00Z
  private val Genesis165 = 1792695600L
  private val Reset165 = 1795114800L

  private var base: Path = scala.compiletime.uninitialized
  private def root: Path = base.resolve("ephemery")

  override def beforeEach(): Unit = base = Files.createTempDirectory("eph-launch-it")
  override def afterEach(): Unit = delete(base)

  private def delete(p: Path): Unit =
    if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then
      if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then Files.list(p).iterator().asScala.toList.foreach(delete)
      Files.delete(p)

  private def freePort(): Int =
    val s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    try s.getLocalPort
    finally s.close()

  /** What the child produced: every line it wrote, and how it ended. */
  final private case class Run(lines: Vector[String], exit: Option[Int])

  /** Forks the harness at `anchor`; returns when `until` accepts a line, or the process ends. A process still running
    * is stopped (SIGTERM, then SIGKILL), and `exit` is then `None`.
    */
  private def fork(anchor: Long, until: String => Boolean, timeoutSeconds: Long): Run =
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val home = Files.createDirectories(base.resolve("home"))
    val ports = List.fill(3)(freePort())
    val command = Seq(
      java,
      "-Xmx1g",
      "-XX:+IgnoreUnrecognizedVMOptions",
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:-ReduceAllocationMerges",
      "-Xlog:class+load=info",
      s"-Duser.home=$home",
      // the main logback.xml (an explicit property wins over logback-test.xml), as the launcher passes its own
      "-Dlogback.configurationFile=logback.xml",
      s"-Dfukuii.datadir=$root",
      "-Dfukuii.network.server-address.interface=127.0.0.1",
      s"-Dfukuii.network.server-address.port=${ports(0)}",
      "-Dfukuii.network.server-address.advertised-address=127.0.0.1",
      "-Dfukuii.network.server-address.external-ip-detection=none",
      "-Dfukuii.network.automatic-port-forwarding=false",
      "-Dfukuii.network.discovery.discovery-enabled=false",
      "-Dfukuii.network.discovery.use-bootstrap-nodes=false",
      "-Dfukuii.network.discovery.reuse-known-nodes=false",
      "-Dfukuii.network.discovery.interface=127.0.0.1",
      s"-Dfukuii.network.discovery.port=${ports(0)}",
      "-Dfukuii.network.rpc.http.interface=127.0.0.1",
      s"-Dfukuii.network.rpc.http.port=${ports(1)}",
      "-Dfukuii.network.rpc.ws.enabled=false",
      "-Dfukuii.network.rpc.graphql.enabled=false",
      "-Dfukuii.network.engine-api.enabled=true",
      "-Dfukuii.network.engine-api.interface=127.0.0.1",
      s"-Dfukuii.network.engine-api.port=${ports(2)}",
      "-Dfukuii.metrics.enabled=false",
      "-cp",
      System.getProperty("java.class.path"),
      "com.chipprbots.ethereum.ephemery.EphemeryForkedHarness",
      anchor.toString,
      "ephemery"
    )
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val queue = new LinkedBlockingQueue[String]()
    val EndOfOutput = "\u0000EOF"
    val reader = new Thread(() =>
      val in = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
      try Iterator.continually(in.readLine()).takeWhile(_ != null).foreach(queue.put)
      catch case _: IOException => ()
      queue.put(EndOfOutput)
    )
    reader.setDaemon(true)
    reader.start()

    val seen = mutable.ArrayBuffer.empty[String]
    var done = false
    var stopped = false
    // A bound on a child that hangs, not a wait for anything to happen: a line of output ends each wait.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while !done do
      val left = deadline - System.nanoTime()
      Option(queue.poll(math.max(left, 0L), TimeUnit.NANOSECONDS)) match
        case None              => done = true
        case Some(EndOfOutput) => done = true
        case Some(line) =>
          seen += line
          if until(line) then
            done = true
            stopped = true
    // The output ended (the process is exiting, or has): give it time to be reaped. A child stopped on a line is not.
    val exited = process.waitFor(if stopped then 0 else 60, TimeUnit.SECONDS)
    val code = if exited then Some(process.exitValue()) else None
    if !exited then
      process.destroy()
      if !process.waitFor(60, TimeUnit.SECONDS) then
        process.destroyForcibly()
        process.waitFor()
    reader.join(TimeUnit.SECONDS.toMillis(10))
    queue.forEach(l => if l != EndOfOutput then seen += l)
    Run(seen.toVector, code)

  private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  "fukuii ephemery with nothing prepared" should "start on the bundled iteration, in <root>/ephemery-165" taggedAs (
    IntegrationTest
  ) in {
    val run = fork(Genesis165 + 10L * 86400, _.contains("Using network ephemery"), timeoutSeconds = 240)

    val report = run.lines.find(_.contains("Ephemery ephemery-165 (chain id 39438165")).getOrElse {
      fail(s"no startup report in:\n${run.lines.takeRight(60).mkString("\n")}")
    }
    report should include("genesis 0x9485…5ecc")
    report should include("from bundled")
    report should include(s"datadir ${root.resolve("ephemery-165")}")
    run.lines.exists(_.contains("Using network ephemery")) shouldBe true

    // the iteration is in the root, and the datadir is the iteration's
    Files.isRegularFile(root.resolve("releases/ephemery-165/chains/ephemery-chain.conf")) shouldBe true
    Files.isRegularFile(root.resolve("releases/ephemery-165/release.json")) shouldBe true
    Files.isDirectory(root.resolve("ephemery-165")) shouldBe true
    Files.exists(root.resolve("rocksdb")) shouldBe false

    // `utils.Config$` was initialised only after the resolver had set its properties (the child logs every class load).
    // `LogbackReload$` is loaded inside `EphemeryResolver.startup`, right after `System.setProperty` and
    // `invalidateCaches()`; the report itself is written later, after the log files are truncated.
    def loadedAt(cls: String): Int =
      run.lines.indexWhere(l => l.contains("[class,load]") && l.contains(s"$cls source:"))
    val resolverAt = loadedAt("com.chipprbots.ethereum.ephemery.EphemeryResolver$")
    val propertiesSetAt = loadedAt("com.chipprbots.ethereum.utils.LogbackReload$")
    val configAt = loadedAt("com.chipprbots.ethereum.utils.Config$")
    resolverAt should be >= 0
    propertiesSetAt should be >= 0
    configAt should be >= 0
    withClue("Config$ loaded before the resolver had set its properties: ")(propertiesSetAt should be < configAt)

    // the log is where the resolver pinned it: <root>/logs, with the report in it (after the truncation at start)
    val log = root.resolve("logs/fukuii.log")
    Files.isRegularFile(log) shouldBe true
    read(log) should include("Ephemery ephemery-165 (chain id 39438165")
  }

  it should "exit 75 at the reset, and leave a RocksDB that reopens cleanly" taggedAs IntegrationTest in {
    val run = fork(Reset165 - 20L, _ => false, timeoutSeconds = 300)

    withClue(s"the child's last lines:\n${run.lines.takeRight(40).mkString("\n")}\n") {
      run.exit shouldBe Some(75)
    }
    run.lines.exists(l =>
      l.contains(
        "Ephemery iteration ephemery-165 ended at 2026-11-19T19:00:00Z; exiting with code 75 so a supervisor " +
          "restart picks up ephemery-166"
      )
    ) shouldBe true

    // the node's database is the iteration's, and it reopens: the graceful shutdown closed it
    val db = root.resolve("ephemery-165/rocksdb")
    Files.isDirectory(db) shouldBe true
    val source = RocksDbDataSource(rocksConfig(db.toAbsolutePath.toString), Namespaces.nsSeq)
    try new AppStateStorage(source).getBestBlockNumber() shouldBe BigInt(0)
    finally source.close()
  }

  private def rocksConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = false
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432
