package com.chipprbots.ethereum

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.logging.LogManager

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import org.rocksdb

import com.chipprbots.ethereum.console.FukuiiBanner
import com.chipprbots.ethereum.console.Tui
import com.chipprbots.ethereum.console.TuiConfig
import com.chipprbots.ethereum.console.TuiEnv
import com.chipprbots.ethereum.ephemery.EphemeryResetWatchdog
import com.chipprbots.ethereum.ephemery.EphemeryResolver
import com.chipprbots.ethereum.ephemery.StartupDeps
import com.chipprbots.ethereum.nodebuilder.ChainTool
import com.chipprbots.ethereum.nodebuilder.StdNode
import com.chipprbots.ethereum.nodebuilder.TestNode
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.JvmFlagGuard
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.NativeBackends

object Fukuii extends Logger:
  def main(args: Array[String]): Unit = run(args, StartupDeps.production)

  /** Starts the node. `deps` is what `App.run` was given: the Ephemery reset watchdog takes its clock and its exit from
    * it, so a test can drive both.
    */
  def run(args: Array[String], deps: StartupDeps): Unit =
    // The resolver's report is written after the log files are truncated below. If startup throws before that (the
    // configuration fails to load), it is written here instead; `flushReport` writes it once.
    try start(args, deps)
    finally EphemeryResolver.flushReport()

  private def start(args: Array[String], deps: StartupDeps): Unit =
    LogManager.getLogManager().reset(); // disable java.util.logging, ie. in legacy parts of jupnp

    // A thread that dies of a missing precompile backend halts the node; any other VirtualMachineError is logged (B-135).
    FatalErrors.installUncaughtHandler()

    // Redirect all JVM temp files to the configured tmpdir (defaults to <datadir>/tmp).
    // Prevents root SSD from filling up during SNAP sync — RocksDB JNI .so extraction,
    // contract account temp files, and JFR profiles all land on the same volume as the database.
    val tmpDir = Paths.get(Config.config.getString("tmpdir"))
    Files.createDirectories(tmpDir)
    System.setProperty("java.io.tmpdir", tmpDir.toString)

    // `export` / `import`: offline chain file tools (ChainTool). They run against the node's database with the node
    // stopped, so they must not truncate the node's logs or take over the terminal.
    val chainToolCommand = args.headOption.filter(ChainTool.isCommand)

    // Truncate log files so each process starts with a clean log (no stale output from prior runs).
    // Placed here — after tmpdir is set, before any log.info() call — so the truncation notice
    // is the first line in each file and nothing from the current process is missed.
    if chainToolCommand.isEmpty then truncateLogs()

    // The Ephemery resolver ran before this, and its report would have been truncated away: it is written now.
    EphemeryResolver.flushReport()

    // Check for --tui flag to enable console UI (disabled by default)
    val enableConsoleUI = args.contains("--tui") && chainToolCommand.isEmpty

    if !enableConsoleUI then
      log.info("TUI disabled (use --tui flag to enable)")
      printBanner()

    log.info("Fukuii app {}", Config.clientVersion)
    log.info("Using network {}", Config.blockchains.network)

    // JDK-8385993: C2 ReduceAllocationMerges can silently miscompile field reads on JDK 23+.
    JvmFlagGuard.check()

    NodeStatusReporter.report()
    MilestoneLog.logMilestones(Config.blockchains.blockchainConfig.forkBlockNumbers)

    val configErrors = ConfigValidator.validate(Config.config)
    if configErrors.nonEmpty then
      configErrors.foreach(err => log.error("Configuration error: {}", err))
      System.exit(1)

    // The native libraries behind the precompiles of every configured fork (BLS12-381 from ETH Prague or ETC Olympia,
    // KZG from ETH Cancun or ETC Olympia). A missing one would make this node compute different results from the
    // network, so it must not start without them (B-135).
    val nativeBackends = NativeBackends.required(Config.blockchains.blockchainConfig)

    if nativeBackends.contains(NativeBackends.Backend.Kzg) then
      log.info("A configured fork runs the EIP-4844 point-evaluation precompile — loading the KZG trusted setup")
      // A malformed/wrong-format trusted setup makes the native c-kzg loader over-read and
      // double-free, crashing the JVM with an UNCATCHABLE SIGSEGV (the try/catch below cannot
      // save us). Pre-validate the resource in pure JVM-land — the only place we can guard —
      // and refuse the native call if the layout does not match the c-kzg-4844 v2 (PeerDAS)
      // format. On failure the self-check below stops the node.
      if !kzgTrustedSetupIsValid(KzgTrustedSetupResource) then
        log.error("KZG trusted setup {} failed format pre-validation — skipping native load", KzgTrustedSetupResource)
      else
        try
          ethereum.ckzg4844.CKZG4844JNI.loadNativeLibrary()
          ethereum.ckzg4844.CKZG4844JNI.loadTrustedSetupFromResource(
            KzgTrustedSetupResource,
            classOf[ethereum.ckzg4844.CKZG4844JNI],
            0L
          )
          log.info("KZG trusted setup loaded successfully")
        catch
          case e: (Exception | LinkageError) =>
            log.error("Failed to load the KZG native library or trusted setup: {}", e.toString)

    val nativeBackendErrors = NativeBackends.selfCheck(nativeBackends)
    if nativeBackendErrors.nonEmpty then
      nativeBackendErrors.foreach(err => log.error("Native backend check failed: {}", err))
      System.exit(1)
    else if nativeBackends.nonEmpty then
      log.info("Native precompile backends checked: {}", nativeBackends.map(_.library).mkString("; "))
    // From here on, a precompile that finds its backend missing halts the node where it finds it. Installed after the
    // self-check, whose probes report a missing backend instead of halting.
    FatalErrors.installCallTimeHalt()

    // Take over the terminal only after configuration has been validated: the TUI suppresses console logging, and a
    // configuration error must reach the user's screen.
    if enableConsoleUI then
      // `fukuii.tui` with the NO_COLOR / FUKUII_TUI_* overrides. A bad environment value is a warning (the configured
      // value applies); a bad `fukuii.tui` value stops the node, like any other configuration error.
      val tuiEnv = TuiEnv.system()
      tuiEnv.warnings.foreach(warning => log.warn(warning))
      val tuiConfig =
        try TuiConfig.load(Config.config, tuiEnv)
        catch
          case e: ConfigException =>
            log.error("Configuration error: {}", e.getMessage)
            sys.exit(1)
      val tui = Tui.getInstance(tuiConfig)
      // Register the restore hook first, so the terminal leaves raw mode / the alternate screen on any exit path. `q`
      // restores before it exits; this hook covers Ctrl-C and SIGTERM, including when JLine's hook closes the terminal
      // first (Tui.shutdown then writes the reset to the process output).
      Runtime.getRuntime.addShutdownHook(new Thread(() => tui.shutdown(), "TuiRestore"))
      if tui.initialize() then
        tui.updateNetwork(Config.blockchains.network)
        tui.updateConnectionStatus("Starting node...")
        tui.render()
      else printBanner()

    if chainToolCommand.isDefined then
      // Test mode wipes the database on start; never let that meet a chain export or import.
      if Config.testmode then
        log.error("export / import are not available in test mode")
        sys.exit(2)
      sys.exit(ChainTool.run(args.toIndexedSeq, Config))

    val node =
      if Config.testmode then
        log.info("Starting Fukuii in test mode")
        deleteRocksDBFiles()
        new TestNode
      else new StdNode

    node.start()

    // An Ephemery iteration ends at its reset: exit 75 for a supervisor to restart (managed), or only warn (an
    // operator's prepared directory, or `exit-at-reset = false`). Nothing is armed on any other network.
    EphemeryResetWatchdog.startFor(Config.config, deps)

  private def truncateLogs(): Unit =
    import scala.util.Try
    val fullConfig = ConfigFactory.load()
    val logsDir = Try(fullConfig.getString("logging.logs-dir")).getOrElse("./logs")
    val logsFile = Try(fullConfig.getString("logging.logs-file")).getOrElse("fukuii")

    val paths = Seq(
      Paths.get(logsDir).resolve(s"$logsFile.log"),
      Paths.get(logsDir).resolve("milestone.log")
    )

    paths.foreach { path =>
      if Files.exists(path) then
        Try(Files.write(path, Array.emptyByteArray, StandardOpenOption.TRUNCATE_EXISTING)).failed.foreach(e =>
          log.warn("Failed to truncate log file {}: {}", path, e.getMessage)
        )
    }
    log.info("Log files truncated on startup")

  private def deleteRocksDBFiles(): Unit =
    log.warn("Deleting previous database {}", Config.Db.RocksDb.path)
    rocksdb.RocksDB.destroyDB(Config.Db.RocksDb.path, new rocksdb.Options())

  /** Classpath resource holding the EIP-4844/7594 KZG trusted setup (c-kzg-4844 v2 / PeerDAS layout). */
  private val KzgTrustedSetupResource = "/trusted_setup.txt"

  /** Validate the bundled KZG trusted setup against the c-kzg-4844 v2 (PeerDAS) text format BEFORE it reaches the
    * native loader. c-kzg-4844 2.0.0 expects an extra G1 monomial section that the legacy EIP-4844 (v1) file lacks;
    * feeding the native `load_trusted_setup` a short/legacy file makes it over-read and free uninitialised pointers
    * (`free_trusted_setup` → `__libc_free`), killing the JVM with a SIGSEGV that no try/catch can intercept. This
    * pure-JVM structural check is the only safe gate.
    *
    * Expected layout (FIELD_ELEMENTS_PER_BLOB = feb, NUM_G2_POINTS = numG2): line 1: feb (4096) line 2: numG2 (65) feb
    * lines: G1 Lagrange points (96 hex chars each) numG2 lines: G2 monomial points (192 hex chars each) feb lines: G1
    * monomial points (96 hex chars each) ← added in c-kzg v2 / EIP-7594
    *
    * Total non-empty lines = 2 + feb + numG2 + feb. We deliberately reject the legacy (2 + feb + numG2) layout: loading
    * it natively is exactly what SIGSEGV-crashes the node.
    */
  private def kzgTrustedSetupIsValid(resource: String): Boolean =
    Option(getClass.getResourceAsStream(resource)) match
      case None =>
        log.error("KZG trusted setup resource {} not found on classpath", resource)
        false
      case Some(in) =>
        try
          val lines = scala.io.Source.fromInputStream(in).getLines().map(_.trim).filter(_.nonEmpty).toVector
          if lines.sizeIs < 2 then
            log.error("KZG trusted setup {} is truncated ({} non-empty lines)", resource, lines.size)
            false
          else
            (lines(0).toIntOption, lines(1).toIntOption) match
              case (Some(feb), Some(numG2)) if feb > 0 && numG2 > 0 =>
                val expected = 2 + feb + numG2 + feb // c-kzg v2 / PeerDAS layout
                if lines.sizeIs == expected then true
                else
                  log.error(
                    "KZG trusted setup {} has {} non-empty lines; expected {} for the c-kzg-4844 v2 (PeerDAS) " +
                      "format (FIELD_ELEMENTS_PER_BLOB={}, NUM_G2_POINTS={}). Refusing native load to avoid SIGSEGV.",
                    resource,
                    lines.size,
                    expected,
                    feb,
                    numG2
                  )
                  false
              case _ =>
                log.error(
                  "KZG trusted setup {} has an unparseable header (lines 1-2 must be integer point counts); " +
                    "refusing native load",
                  resource
                )
                false
        finally in.close()

  private def printBanner(): Unit =
    log.info(FukuiiBanner.Text)
