package com.chipprbots.ethereum

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.logging.LogManager

import com.typesafe.config.ConfigFactory
import org.rocksdb

import com.chipprbots.ethereum.console.Tui
import com.chipprbots.ethereum.console.TuiConfig
import com.chipprbots.ethereum.nodebuilder.StdNode
import com.chipprbots.ethereum.nodebuilder.TestNode
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger

object Fukuii extends Logger:
  def main(args: Array[String]): Unit =
    LogManager.getLogManager().reset(); // disable java.util.logging, ie. in legacy parts of jupnp

    // Redirect all JVM temp files to the configured tmpdir (defaults to <datadir>/tmp).
    // Prevents root SSD from filling up during SNAP sync — RocksDB JNI .so extraction,
    // contract account temp files, and JFR profiles all land on the same volume as the database.
    val tmpDir = Paths.get(Config.config.getString("tmpdir"))
    Files.createDirectories(tmpDir)
    System.setProperty("java.io.tmpdir", tmpDir.toString)

    // Truncate log files so each process starts with a clean log (no stale output from prior runs).
    // Placed here — after tmpdir is set, before any log.info() call — so the truncation notice
    // is the first line in each file and nothing from the current process is missed.
    truncateLogs()

    // Check for --tui flag to enable console UI (disabled by default)
    val enableConsoleUI = args.contains("--tui")

    if !enableConsoleUI then
      log.info("TUI disabled (use --tui flag to enable)")
      printBanner()

    log.info("Fukuii app {}", Config.clientVersion)
    log.info("Using network {}", Config.blockchains.network)

    NodeStatusReporter.report()
    MilestoneLog.logMilestones(Config.blockchains.blockchainConfig.forkBlockNumbers)

    val configErrors = ConfigValidator.validate(Config.config)
    if configErrors.nonEmpty then
      configErrors.foreach(err => log.error("Configuration error: {}", err))
      System.exit(1)

    if Config.blockchains.blockchainConfig.forkTimestamps.cancunTimestamp.isDefined then
      log.info("Cancun fork detected — loading KZG trusted setup for EIP-4844 point-evaluation precompile")
      // A malformed/wrong-format trusted setup makes the native c-kzg loader over-read and
      // double-free, crashing the JVM with an UNCATCHABLE SIGSEGV (the try/catch below cannot
      // save us). Pre-validate the resource in pure JVM-land — the only place we can guard —
      // and refuse the native call if the layout does not match the c-kzg-4844 v2 (PeerDAS)
      // format. On failure the node still boots; precompile 0x0A simply reverts every call.
      if !kzgTrustedSetupIsValid(KzgTrustedSetupResource) then
        log.error(
          "KZG trusted setup {} failed format pre-validation — skipping native load. " +
            "Point-evaluation precompile (0x0A) will revert all calls.",
          KzgTrustedSetupResource
        )
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
          case e: Exception =>
            log.error(
              "Failed to load KZG trusted setup — point-evaluation precompile (0x0A) will revert all calls: {}",
              e.getMessage
            )

    // Take over the terminal only after configuration has been validated: the TUI suppresses console logging, and a
    // configuration error must reach the user's screen.
    if enableConsoleUI then
      val tui = Tui.getInstance(TuiConfig.default)
      // Register the restore hook first, so the terminal leaves raw mode / the alternate screen on any exit path.
      Runtime.getRuntime.addShutdownHook(new Thread(() => tui.shutdown(), "TuiRestore"))
      if tui.initialize() then
        tui.updateNetwork(Config.blockchains.network)
        tui.updateConnectionStatus("Starting node...")
        tui.render()
      else printBanner()

    val node =
      if Config.testmode then
        log.info("Starting Fukuii in test mode")
        deleteRocksDBFiles()
        new TestNode
      else new StdNode

    node.start()

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
    val banner = """
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                            ›ízzzzzí›                                                            
                                                         ›zzzzzízzzzzzz›                                                         
                                                      —ízzzízzzzzÏ6zzzzzzz—                                                      
                                                   —zzzzzzzzzzízzÏÅgÅGzzzzzzz—                                                   
                                   6ÆÅgggÆg     {zzzzzzízzzzízzzzzz6Å6ÆÏzzzzzzzí—                                                
                                 ÆÏ ›í———{—{GÅÏzzzzízzzzzzzzzzzíízzzÏÆüÆüzzzízzzzzz{                                             
                               üÅ ————{—{{—{——GgzzzzzzzzzíízzzzzzzzzzügüÅíízzzzzzzzzzí{                                          
                               Æ›{{{—züÞÇüz{í{{6ÅzzzzzzzzzzzzízzzzzzzzgÇGÞzzzzzzzzzzzzzízz                                       
                              6Þ———{zÅüzz6Æüí———ÞÞzzzzzzzzzzízzííízzzz6G6gzzzzízzzzzzzzzzzzzíÇÅÅÇügÅÆÆ{                          
                              ÆííízzüÆzzzzzÅ6{——{Æzzzzzzzzzzzzzíízzzzíüg6gzzzzzzzzz{ízzzízGÅÇ6gÅ6                                
                             Æg ›————ÞÆzzzzGGí{—{Åüzzzzzízzízzzízzízzz6G6gzzzzzzízzzzzzzÆgÏÅÅzzzzzz                              
                         ÏÆG— ————————6ÅzzíGG{———Åüzzzzí{ízzzzzzzzzzzíGÞÞGzzzzzzzzzzzzÅGÏÅÞzzzzízzzzzí                           
                       —Åí›—————{—{{———ÅÇzzgÇ{—{íÆzzzzzíízzzízízzzzízzÆÏÅüzzí{{zzzzzÞÅÏÅÞzzzzzzzzzzízzzzz                        
                     zÏÅ —6Ç————{—Åz{——6gzÏÆí———G6zzízzízzzzzzzÏ6ÇÞÞÇGg6gzzzzzzzízÏÆüÞÅzzzzzzzízzzzzzzízzzzz                     
                 ›zízzÅ6›{ÆÆ—{———{ÇÆ{——gÞíÅü———ÏÅzzzzzzzzzGÅÆGÏg—››››——íGÆÆÅGzzzzÇÅüÅÏzízzízzzzzzízzízzízzzzzzz—                 
                zzzzzzÆ6—————{—{—z————{ÆÏÏÆÏíí{g6zzzzzÏgÆÇ——{ÞÏ{í{í{{íÏí{{—›—6ÅÅÅÞÞgzzzzzzzzzzzízzzzzzzzzzzzzzzzz                
                zzzzzzüÅ—{——{ÆÆÅGÆí——{ÅÏzGÞí———gÇzízÏÅÅ{—ízzgí{íííí{› ›{ííííí{zÆüÆÆ6zzzzzzzzízí{{zízzzzzzzízzzzzz                
                zzzzzzíüÆÏ———{ÅÇÅ{{—GÆzízÞGÏ——{{ÅÇígÅ{{zzzÏGííí{íí{—  {í{ííííÇÅüÆüíGÆ6zízzzzzzzíízzzzzzzzzzzzzzzz                
                zzzzízzzz6ÅÅÇÏÏÏ6GÆGzízzzzÆüü{—{—ÏgÆÆÅÇzzzígíííí{{{{  ››››{íüÞÆGüz{ííÆgzzzzzzzzzzzzzzzízízzzzzzzz                
                zzzzzzzzzzzzzzzzzzzzzzzízzüÆüÏ{—í{——{zg6zzzzÞíí{   — ›—›—{{íízzí{{í{{íÆGzzízízzzzzzzzzzzzzzzízzzz                
                zzzzzzzzzzzzzzzzízzzzzzzzzzzGÅ6ÏÏzíííüÆÏzzzGíí{{—›— ›{ííííí{í{íííííí{{íÆÏzzzzzzzzzzzzízzzzzzzzzzz                
                zízzzzzzzzzízzízzzzzzzzzzzzízzüÅÆGÇ6ggÏzzzÏ6í{   › ›{ííí{{ííÞG6ÏzÏÇÅÞí{ÆÅzzzzízzzzzzzzzízzzzzzzzz                
                zzzzíííí{zzzzzzzzzzzíízí{ízzzííÅ6zzzÏGÆgÏzÇíí{í{{› {{{{{ííÏíííí{íí{í{gÞ6Åzzzzzz{{zzzzzzzzzzzzzzzz                
                zzzzzzzzzzízzzzzzzzzzzzzízzzízGÅízÏÅÆÆÆÆÆÅÏíí{í{—    ›{ííííÏGgÅgÇ{íí{íÞÅÅzzzzízzzzzzzzzzzzzzzzzzz                
                zzzzzzzzzzzzzízzí{zzzzzzzzzzzzÆízÏÆÆÆÆ6  üÆÞ{ííí{ ›{{íííííGÆgÅÆÆÆÆÆG{í{íÆgzzzzzzzzzzzzzízzízzzzzzz                
                zízzízzzzzzzzzzízízzzzízzíízz6Æ{zgÆÆÆ{  gÆg{í{{{›{ííí{íÏÆÞ   6ÆÆÆÅgGí{{ÅGzíízzzzzzzízzzzíííízzzzz                
                zzzzzzzzízzzzízzzzzzízzzzízzzÇÆ—zÆÆÆÆÆÆÆÆÆÞí{íí{íííííízÆÆ6   ÆÆÆÆÅGg{í{ÆGzzzzízzzzzzzzzzzzízzzzzz                
                zzzzzzízzzzzzzííüÇÞ6zzzzzzzzz6Æ{zÅÅÆÆÆÆÆÆÆÏíí{íííí{{{íÇÆÆÆÆÆÆÆÆÆÆGGg{{íÆüízzzzízzzzzzzízzzzzzzzíz                
                zzzízzzzízzzzgÆÆÆz››gÆüzzzzzzzÅzzÇÆgÆÆÆÞÆÇ{{{{ííí{íí{íÞÆÆÆÆÆÆÆÆÆgÞÅüí{ÞÅzzzzzííízzízzzzzzízzízzzz                
                zízzzízzzíÏÅÅí›{ííÅí{›ÅÅzzízzz6ÆÏz6ÆÅgÅÆÏííííí{íííí{í{6ÆÆÆÆÆÆGÅGÞÅÞííÏÆzzzzízzzzzzzzzzzzzzzzzzzzz                
                zzzzzzzízÅÅ››í{{íí6Ïí{—GÆzzzzzzÇÆ6zzzGííííííí{íí{í{ííí{GÅgÅÅgÞÞGÅü{í6Æüzzzzzzzzzzzzzízzzzzzzzzízz                
                zzzzzzíÏÆz›íí{í{{íÏ6ííí{GÆzízzzzzÅÆ6ÞÏííí{Þgü{{zÇÅÅÞíí{íígÆÆÆÆGí{{ÏgÅzzzzzzzzzzzízízzzzízzzzzzízz                
                zzzzzz6Å—›{íí{íí{íÅGíííííggzzízzzzzüÅÞíííízÅGÅG666ÇGííííííí{{íízüÞÆÇzzzzzzzzÞgGzzzzzzízíízzzzzzzz                
                zzzzzüÅ››ííí{íí{íÅÅüí{í{í{Å6zzzízzzzzÅgí{í{ÇgÏÏÏÏÏÅí{íí{zÏÏüüüÇÅÅÇzzzzzzzzÅÅ{zÆzízzzzzz{{zzzzzzzz                
                zzzzzÆz›íí{{{íííÅÆgüz{íííííÆÏzzízzzzzzÞÆÇííízÅÆÆÅüí{{ízüüGÅÆÆÅüzzzzzzzzzÅÆ—zügÅzzzzzízízzzzzzzzzz                
                zzízGg›{{ííí{íÅgzÇÆüüíí{{{{6Æzzzí{ízzzzzÇÆÅÞüí{íízzÏüÇgÆÅÅgzzzzzzzzzzzÇÅííüüüÅzzzzíízzzzzzzzzzzzz                
                zzzzÆz—{í{{íÏüÆ6íÞÆüüí{íííí{gGzzzzzzzzí6ÆÅÆÅÞgÆÅÅÅÅÅgGGGGÆgÞÞÅÅzzzzzzÆG{üüüüGÆzzzzízzzzzzízzzzzzz                
                zzzÏÅ›í{íí{6GÅÅGÏÆüüüüí{{{íí{ÅÏzzzzzízgGÇggÏüüÆÆÅgGggÅÆÅGüü6ÇÇGgzzzÏÆzzüüüüüÆzzzzzzzzííízízzzzzzz                
                zzzÏÅ›íííüÆüÏzzízzÅÅüüÏíííííí6ÆzzzzÇÅÅÆÇÇÆíííÏÞgÞGGÞGÅGüüÏÇÆGGÆgzzÇÅ{Ïüüüü6ÅgzzzzzzzGÆüÅÏzzzzzzzz                
                zzzÏÆ›{{zÅzzízzzízÆÅ6üüz{íííííg6GÆgzüüÆÞgÞíí{ííGzüüíííí{{ÏÅ6Ïz6ÆzÞÅ{üüüüÇÇÞÅzzízíÏÅg—›{Åüzzzzzzíz                
                zzzzÆGííÆÞzzzízzzíÅgüüüüÏ{{í{í6ÆÏ{{ízüügÆü{í{{Þ{í{íí{ííííÆÇ66ÅÆÆgÅzüüüÇÇÇÇÆzzzzÅÆ6Åg{{íÅzzízzzzzz                
                zzzzzÆzzÅízízzzízzzzÞÆGüüüzíí{6ÇÅzÏüüGÆÞÅüííízÇ{ííí{ííí{ÏÆGÞg{ízÆ6üü6ÇÇÇÇÆüügÆ6››—{íÞÞÏÆzzzz{ízzz                
                zízzzÏÆÇÅzzízzzzízzzzíGÆÇüüüü6ÅüÆ6ÅÆGzzzÞgíí{Çz{ííííí{{zÅÇgÆÅÏ{ízÆÇ6ÇÇÇÞÆÆgí››ÏÆííí{{6ÆGízzzzzzzz                
                zzzízízgÆzzzízzzzzzzzzzzÏÅÆÅÅggÆÅüzzzzzzzÇÅ6zGz{ííí{í{ÞGÇÇÞÅggÏíízÆÅÆÆÇ—››—{í{ííÞÞíííüÆzzzzzzzzzz                
                zzzzzzzzzzzzzzí{ízzzízzzzzzzzízzzzzzzízzzzüÆÇÇÇGÅgÞGÅGÇÇÇGÅÇÇgÅüÏízÆz{ízÇzííí{íí{6ÞÏüÅÇzzzzzzzzzz                
                  ízzzzízzzzzzzzzzíízzzzzzzzzzzÏÅÅÆgzzzízzzggÇÇÇÇÇÇÅÇÇÇÇÆÅGÇÇÇÞÆÅGgÅÅ{{íííÇí{{ííí{ÅüÅÅzzzzzzzzí                  
                     zzzzzzzzzzígÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÅÅÆÆÆÆÆÆÆÆÆÆÆÆgÆÆÆÆÆÆÆÆÆÆÆÆÅÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆüüGÅÅzzzzzzí                     
                        {zzízzzzÆÅ         Æ—   gÆÆÆ   zÆÇ   ÇÆÆü    Æg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆüÅgzzzz{                        
                           ízzízÆÅ        ›Æ—   gÆÆÆ   zÆÇ   ÇÆz   ›ÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆÆÞzzí                           
                              ízÆÅ   GÆÆÆÆÆÆ—   gÆÆÆ   zÆÇ   Ç{   {ÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆzí                              
                                ÆÅ   —íí{ÏÆÆ—   gÆÆÆ   zÆÇ       zÆÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   ÏÆÆ                                
                                ÆÅ        ÆÆ—   gÆÆÆ   zÆÇ       6ÆÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÅÅÅÅÆÆ—   gÆÆÆ   zÆÇ   6    ÏÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÆÅÏÏGÆÏ   zÆÆz   6ÆÇ   ÇÆ    íÆÆÆ    GÆg    ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÆg   ÆÆ         —ÆÆÇ   ÇÆÆ›   ›ÆÆÇ         GÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÆGGGÅÆÏ    ÅÆÆGÇÏÏÇGÆÆÆÆÆÞÞGÅÆÆÆGGGGÅÆÆÆgÞüÏüÞÅÆÆÆÆÅGGGÆÆÆGGGÆÆÅ                                
                                 í666ü        zÇGggggÞzzzÞGGGGÏzüGÆGGGÞÇüGgÅÅgG6—   z666Ï —ü66ü›                                 
                                                      —zzzzzzzzzzzÆíí6gzzz—                                                      
                                                          zzzzzzzzgÇÏÆÏ›                                                         
                                                             ízzzzzÅÅÞ                                                           
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 """

    log.info(banner)
