package com.chipprbots.ethereum.console.ops

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.BundleInputs
import com.chipprbots.ethereum.console.LogLine
import com.chipprbots.ethereum.console.TuiFixtures
import com.chipprbots.ethereum.console.TuiState
import com.chipprbots.ethereum.console.TuiView
import com.chipprbots.ethereum.testing.Tags.*

/** The support bundle (spec 029 FR-054, FR-058, SC-012, T099): a marker planted in every key shape that holds a secret,
  * a password in a URL, and a URL with a password in a log line reach no file of it; it says what it holds; and it is
  * written under the data directory for the owner alone.
  */
class SupportBundleSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val Marker = "PLANTED-SECRET-7f3a"
  private val UrlPassword = "URL-PASSWORD-91c2"
  private val LogPassword = "LOG-PASSWORD-3e8b"

  /** Every key shape [[Redaction.isSecret]] knows: each key on its list, under `fukuii` and not; and each of its words
    * as a key's whole last part, at the end of it, at the start of it, and in capitals.
    */
  private val secretKeys: Vector[String] =
    Redaction.SecretKeys.toVector.sorted ++
      Redaction.SecretWords.zipWithIndex.flatMap { (word, i) =>
        Vector(
          s"planted.whole$i.$word",
          s"planted.ending$i.keystore-$word",
          s"planted.starting$i.$word-value",
          s"planted.capitals$i.${word.toUpperCase}"
        )
      }

  /** The node's configuration: every secret key holding the marker with its number, paths to secrets, and a URL with a
    * user and a password under a key that is no secret.
    */
  private val config: Config = ConfigFactory
    .parseString(
      (Vector(
        """fukuii.datadir = "/var/lib/fukuii/etc"""",
        """fukuii.network.engine-api.jwt-secret-path = "/var/lib/fukuii/jwt.hex"""",
        """fukuii.network.rpc.http.certificate.password-file = "/etc/fukuii/keystore.pass"""",
        s"""fukuii.sync.checkpoint-sync-url = "https://operator:$UrlPassword@checkpoints.example.org/etc"""",
        s"""fukuii.planted.list.api-tokens = ["$Marker-list-1", "$Marker-list-2"]"""
      ) ++ secretKeys.zipWithIndex.map((key, i) => s"""fukuii.$key = "$Marker-$i""""))
        .mkString("\n")
    )
    .resolve()

  private def configRows(): Vector[String] = ConfigView.rows(config.getConfig("fukuii"), "fukuii")

  /** A node at its head, whose log has a line with a URL as a fetch logs it: user and password in it. */
  private val state: TuiState =
    val synced = TuiFixtures.resourcesEtc()
    synced.copy(logs =
      synced.logs :+ LogLine(
        9_100L,
        "12:00:00",
        "INFO",
        "com.chipprbots.ethereum.blockchain.sync.CheckpointSync",
        s"Fetching the checkpoint from https://operator:$LogPassword@checkpoints.example.org/etc/latest.checkpoint"
      )
    )

  private val Build = Seq("fukuii 0.11.0", "commit 0123abc", "scala 3.9.0")

  private def inputs(of: TuiState = state): SupportBundle.Inputs = BundleInputs.of(() => of, Build, () => configRows())

  private lazy val files: Seq[(String, String)] = SupportBundle.files(inputs(), TuiFixtures.Now)
  private def file(name: String): String = files.toMap.apply(name)

  /** The files, by name, that have `text` in them. */
  private def holding(text: String): Seq[String] = files.collect { case (name, body) if body.contains(text) => name }

  // ---- what is in it ------------------------------------------------------------------------------------------------

  "The planted keys" should "each be one the redaction knows for a secret" taggedAs (UnitTest) in {
    secretKeys should have size (Redaction.SecretKeys.size + Redaction.SecretWords.size * 4).toLong
    for key <- secretKeys do
      withClue(s"$key: ") {
        Redaction.isSecret(s"fukuii.$key") shouldBe true
      }
  }

  "A support bundle" should "have a file for each thing it holds, and one for each view" taggedAs (UnitTest) in {
    files.map(_._1) shouldBe Seq(
      "README.txt",
      "build.txt",
      "config.txt",
      "logs.txt",
      "alerts.txt",
      "database.txt",
      "threads.txt",
      "memory.txt"
    ) ++ TuiView.values.toSeq.map(v => s"views/${v.title}.txt")
    for (name, body) <- files do
      withClue(s"$name: ") {
        body should not be empty
        body should endWith("\n")
      }
  }

  it should "say in its README what it holds and what it does not" taggedAs (UnitTest) in {
    file("README.txt").split('\n').toVector shouldBe Vector(
      "fukuii support bundle, written 2026-11-07T12:00:00Z by the --tui dashboard of the node it describes.",
      "It holds no key, no keystore, no JWT secret, no state and no chain data.",
      "It holds peer addresses and this node's id.",
      "Every configuration value whose key holds a secret is hidden; a URL is written without its credentials."
    )
  }

  it should "hold what it was given: the build, the configuration, the log, the alerts, the database" taggedAs (
    UnitTest
  ) in {
    file("build.txt") shouldBe "fukuii 0.11.0\ncommit 0123abc\nscala 3.9.0\n"
    file("config.txt").split('\n').toVector shouldBe configRows()
    (file("logs.txt").split('\n') should have).length(state.logs.size.toLong)
    file("logs.txt") should include(
      "12:00:00 INFO com.chipprbots.ethereum.blockchain.sync.regular.RegularSync RegularSync: at head"
    )
    file("alerts.txt") should include("health checks:")
    file("database.txt") should startWith("read at 2026-11-07T11:59:59Z\n")
    // The process as it is when the bundle is made: this thread is in the dump, and the heap in the memory figures.
    val now = SupportBundle.files(inputs(), TuiFixtures.Now).toMap
    now("threads.txt") should include(s"\"${Thread.currentThread().getName}\" RUNNABLE")
    now("memory.txt") should startWith("heap used ")
    now("memory.txt") should include("collector ")
    now("memory.txt") should include("uptime ")
    // The views are the dashboard's, as text.
    file("views/Peers.txt") should include("203.0.113.")
  }

  // ---- no secret reaches a file (FR-058) ----------------------------------------------------------------------------

  it should "hold the marker of no secret key, in no file" taggedAs (UnitTest) in {
    holding(Marker) shouldBe empty
    // Each planted key is there, hidden; and a path to a secret is shown, for it is where the secret is, not the secret.
    val rows = file("config.txt").split('\n').toVector
    for key <- secretKeys do
      withClue(s"$key: ") {
        rows.filter(_.startsWith(s"fukuii.$key = ")).map(_.takeWhile(_ != '#').trim) shouldBe
          Vector(s"fukuii.$key = ${Redaction.Hidden}")
      }
    rows.count(_.contains(Redaction.Hidden)) shouldBe secretKeys.size + 1 // and the list of tokens, whole
    file("config.txt") should include("fukuii.network.engine-api.jwt-secret-path = /var/lib/fukuii/jwt.hex")
    file("config.txt") should include("fukuii.network.rpc.http.certificate.password-file = /etc/fukuii/keystore.pass")
  }

  it should "hold a URL of the configuration without its user and password, in no file" taggedAs (UnitTest) in {
    holding(UrlPassword) shouldBe empty
    file("config.txt") should include("fukuii.sync.checkpoint-sync-url = https://checkpoints.example.org/etc   # ")
  }

  it should "write a logged URL without its user and password in its log file" taggedAs (UnitTest) in {
    (file("logs.txt") should not).include(LogPassword)
    file("logs.txt") should include(
      "Fetching the checkpoint from https://checkpoints.example.org/etc/latest.checkpoint"
    )
  }

  // FAILS (reported): the log file is scrubbed, and the views are not: the Logs view (and any view that shows the log's
  // tail) is written as the screen draws it, with the URL whole.
  it should "hold a logged URL's password in no file at all" taggedAs (UnitTest) in {
    holding(LogPassword) shouldBe empty
  }

  // FAILS (reported): a log line is scrubbed word by word, and a word is taken for a URL only when it starts with the
  // scheme. A URL written `url=https://…`, or in quotes or brackets, keeps its password.
  "A log line" should "lose the password of a URL however the URL is written into it" taggedAs (UnitTest) in {
    val url = s"https://operator:$LogPassword@checkpoints.example.org/etc"
    for line <- Seq(
        s"fetching $url now",
        s"fetching $url, then importing",
        s"fetching url=$url",
        s"fetching \"$url\"",
        s"fetching ($url)",
        s"fetching [$url]",
        s"fetching\t$url"
      )
    do
      withClue(s"$line: ") {
        (SupportBundle.scrubbed(line) should not).include(LogPassword)
      }
  }

  it should "be written as it is when it has no URL with credentials in it" taggedAs (UnitTest) in {
    for line <- Seq(
        "Imported block 21,884,102 0x6d21…1a0e with 14 txs in 22ms",
        "Peers: 41 handshaked · 14 in · 27 out",
        "  two  spaces   kept",
        "listening on http://127.0.0.1:8546/",
        "operator@example.org wrote at 12:00:00",
        ""
      )
    do SupportBundle.scrubbed(line) shouldBe line
    SupportBundle.scrubbed("see https://user:pw@example.org/x and http://example.org/y") shouldBe
      "see https://example.org/x and http://example.org/y"
  }

  // FAILS (reported): an enode's node id stands where a URL has its user, so it is taken for a credential and cut. The
  // log file of a bundle then names no peer by its id, and the README says the bundle holds node ids.
  it should "keep the node id of an enode: it is public, and it is what names a peer" taggedAs (UnitTest) in {
    val enode = s"enode://${"ab12cd34" * 16}@203.0.113.9:30303"
    SupportBundle.scrubbed(s"Handshaked peer $enode") shouldBe s"Handshaked peer $enode"
  }

  // ---- where it is written, and for whom ----------------------------------------------------------------------------

  /** A directory of the test's own, removed with everything in it when `f` ends. */
  private def withTempDir[A](f: Path => A): A =
    val dir = Files.createTempDirectory("support-bundle-spec")
    try f(dir)
    finally
      val all = Using.resource(Files.walk(dir))(_.iterator().asScala.toVector)
      all.reverse.foreach(Files.deleteIfExists(_))

  private def isPosix(dir: Path): Boolean = Files.getFileStore(dir).supportsFileAttributeView("posix")

  private def permissions(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

  "The bundle's directory" should "be under the data directory, named by the second it was made, in UTC" taggedAs (
    UnitTest
  ) in {
    SupportBundle.directory(Path.of("/var/lib/fukuii/etc"), TuiFixtures.Now) shouldBe
      Path.of("/var/lib/fukuii/etc/support/bundle-20261107-120000")
    SupportBundle.directory(Path.of("/data"), Instant.parse("2027-01-02T03:04:05.678Z")) shouldBe
      Path.of("/data/support/bundle-20270102-030405")
  }

  "Writing a bundle" should "write every file as it is, the views in a directory of their own" taggedAs (UnitTest) in {
    withTempDir { dataDir =>
      val target = SupportBundle.directory(dataDir, TuiFixtures.Now)
      SupportBundle.write(target, files)
      for (name, body) <- files do
        withClue(s"$name: ") {
          val written = target.resolve(name)
          Files.isRegularFile(written) shouldBe true
          new String(Files.readAllBytes(written), StandardCharsets.UTF_8) shouldBe body
        }
      // Nothing else is there.
      val found = Using.resource(Files.walk(target))(_.iterator().asScala.filter(Files.isRegularFile(_)).toVector)
      found.map(p => target.relativize(p).toString.replace('\\', '/')).toSet shouldBe files.map(_._1).toSet
    }
  }

  it should "write for the owner alone, where the file system has such permissions" taggedAs (UnitTest) in {
    withTempDir { dataDir =>
      val target = SupportBundle.directory(dataDir, TuiFixtures.Now)
      SupportBundle.write(target, files)
      if isPosix(dataDir) then
        permissions(dataDir.resolve("support")) shouldBe "rwx------"
        permissions(target) shouldBe "rwx------"
        permissions(target.resolve("views")) shouldBe "rwx------"
        for (name, _) <- files do
          withClue(s"$name: ") {
            permissions(target.resolve(name)) shouldBe "rw-------"
          }
      else info("this file system has no owner-only permissions: only the files' presence was checked")
    }
  }

  it should "close a support directory that was open to others, and write over a bundle of the same second" taggedAs (
    UnitTest
  ) in {
    withTempDir { dataDir =>
      val support = dataDir.resolve("support")
      val target = SupportBundle.directory(dataDir, TuiFixtures.Now)
      Files.createDirectories(target)
      Files.write(target.resolve("README.txt"), "an older bundle".getBytes(StandardCharsets.UTF_8))
      if isPosix(dataDir) then
        Files.setPosixFilePermissions(support, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.setPosixFilePermissions(target.resolve("README.txt"), PosixFilePermissions.fromString("rw-r--r--"))
      SupportBundle.write(target, files)
      new String(Files.readAllBytes(target.resolve("README.txt")), StandardCharsets.UTF_8) shouldBe file("README.txt")
      if isPosix(dataDir) then
        permissions(support) shouldBe "rwx------"
        permissions(target.resolve("README.txt")) shouldBe "rw-------"
    }
  }

  // ---- the action ---------------------------------------------------------------------------------------------------

  private val request = ActionRequest(Actions.Bundle.name)

  "bundle" should "say the path, every file with its size, what it holds, and that it goes nowhere" taggedAs (
    UnitTest
  ) in {
    withTempDir { dataDir =>
      val handler = SupportBundle.handler(inputs(), () => Some(dataDir), () => TuiFixtures.Now)
      val p = handler.preview(request)
      p.refusal shouldBe None
      p.lines should have size 5
      p.lines(0) shouldBe s"Writes ${files.size} files under ${dataDir.resolve("support/bundle-20261107-120000")}:"
      // Every file is named, with its size.
      for (name, _) <- files do p.lines(1) should include(s"$name (")
      p.lines(1) should include(s"README.txt (${file("README.txt").getBytes(StandardCharsets.UTF_8).length} B)")
      p.lines(1) should endWith(".")
      p.lines.drop(2) shouldBe Vector(
        "It holds peer addresses and this node's id. It holds no key, no JWT secret, no state and no chain data.",
        "Secrets in the configuration are hidden. The files are readable by this node's user only.",
        "It goes nowhere: sending it to anyone is up to you."
      )
      // A preview writes nothing.
      Files.exists(dataDir.resolve("support")) shouldBe false
    }
  }

  it should "name every file in the preview the runner publishes, where a line is bounded" taggedAs (UnitTest) in {
    withTempDir { dataDir =>
      val inline = new SameThread
      val runner = new ActionRunner(
        Actions.all,
        Map(Actions.Bundle.name -> SupportBundle.handler(inputs(), () => Some(dataDir), () => TuiFixtures.Now)),
        clock = TuiFixtures.clock,
        audit = _ => (),
        previewThread = inline,
        actionThread = inline,
        observeThread = inline
      )
      try
        runner.preview(request)
        val published = runner.view.previews(request)
        published.refusal shouldBe None
        for (name, _) <- files do
          withClue(s"$name: ") {
            published.lines(1) should include(s"$name (")
          }
        // Not cut: the list ends as the handler ended it.
        published.lines(1) should endWith(".")
      finally runner.shutdown()
    }
  }

  it should "write the bundle, say where, and say how far it is as it goes" taggedAs (UnitTest) in {
    withTempDir { dataDir =>
      val handler = SupportBundle.handler(inputs(), () => Some(dataDir), () => TuiFixtures.Now)
      val progress = mutable.Buffer.empty[String]
      val target = dataDir.resolve("support/bundle-20261107-120000")
      handler.run(request, progress += _) shouldBe Right(s"${files.size} files written to $target")
      progress shouldBe Seq("reading the node", s"writing ${files.size} files")
      Files.isRegularFile(target.resolve("README.txt")) shouldBe true
      Files.isRegularFile(target.resolve("views/Ops.txt")) shouldBe true
      (new String(Files.readAllBytes(target.resolve("config.txt")), StandardCharsets.UTF_8) should not).include(Marker)
    }
  }

  it should "be refused on a node with no data directory" taggedAs (UnitTest) in {
    val handler = SupportBundle.handler(inputs(), () => None, () => TuiFixtures.Now)
    handler.preview(request) shouldBe Preview(Vector.empty, Some("this node has no data directory to write under"))
    handler.run(request, _ => ()) shouldBe Left("this node has no data directory to write under")
  }

  it should "fail, saying where it could not write, when the data directory cannot hold it" taggedAs (UnitTest) in {
    withTempDir { dir =>
      // A file where the data directory should be: nothing can be made under it.
      val notADirectory = Files.createFile(dir.resolve("datadir"))
      val handler = SupportBundle.handler(inputs(), () => Some(notADirectory), () => TuiFixtures.Now)
      val failed = handler.run(request, _ => ()).left.toOption.value
      failed should startWith(s"could not write ${notADirectory.resolve("support/bundle-20261107-120000")}: ")
    }
  }
