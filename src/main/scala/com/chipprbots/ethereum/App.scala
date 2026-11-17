package com.chipprbots.ethereum

import java.io.File

import scala.sys

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.cli.CliLauncher
import com.chipprbots.ethereum.crypto.SignatureValidator
import com.chipprbots.ethereum.ephemery.EphemeryFetchCli
import com.chipprbots.ethereum.ephemery.EphemeryResolver
import com.chipprbots.ethereum.ephemery.StartupDeps
import com.chipprbots.ethereum.faucet.Faucet
import com.chipprbots.ethereum.utils.JvmFlagGuard
import com.chipprbots.ethereum.utils.LogbackReload
import com.chipprbots.ethereum.utils.Logger

object App extends Logger:

  // Known network names that correspond to config files in conf/ directory
  private val knownNetworks = Set(
    "etc",
    "eth",
    "mordor",
    "sepolia",
    "hoodi",
    "plataberget",
    "ephemery",
    "hive",
    "bootnode",
    "gorgoroth"
  )

  // Known modifiers that affect launcher behavior
  // public is for nodes with discovery on and connecting to any peer
  private val knownModifiers = Set("public", "enterprise")

  // Launcher commands
  private val launchFukuii = "fukuii"
  private val launchKeytool = "keytool"
  private val downloadBootstrap = "bootstrap"
  private val faucet = "faucet"
  private val cli = "cli"
  private val sigValidator = "signature-validator"

  /** Check if argument is an option flag (starts with -) */
  private def isOptionFlag(arg: String): Boolean = arg.startsWith("-")

  /** Check if argument is a known network name */
  private def isNetwork(arg: String): Boolean = knownNetworks.contains(arg)

  /** Check if argument is a known modifier */
  private def isModifier(arg: String): Boolean = knownModifiers.contains(arg)

  private def findFilesystemConfig(network: String, currentConfigFile: Option[String]): Option[File] =
    val envConfiguredDir = sys.env.get("FUKUII_CONF_DIR").map(new File(_))
    val systemConfiguredDir = Option(System.getProperty("fukuii.conf.dir")).map(new File(_))
    val launcherConfigDir = currentConfigFile
      .flatMap(path => Option(new File(path).getParentFile))

    val candidateDirs = Seq(envConfiguredDir, systemConfiguredDir, launcherConfigDir).flatten
      .map(_.getAbsoluteFile)
      .distinctBy(_.getAbsolutePath)

    val directCandidates = Seq(
      new File(s"conf/$network.conf"),
      new File(s"$network.conf")
    ).map(_.getAbsoluteFile)

    val filesFromDirs = candidateDirs.map(dir => new File(dir, s"$network.conf"))

    (filesFromDirs ++ directCandidates)
      .find(file => file.exists() && file.isFile)

  /** Set config file for the specified network (must be called before Config is accessed) */
  private[ethereum] def setNetworkConfig(network: String): Unit =
    val currentConfigFile = Option(System.getProperty("config.file"))
    findFilesystemConfig(network, currentConfigFile) match
      case Some(file) =>
        val absolutePath = file.getAbsolutePath
        System.setProperty("config.file", absolutePath)
        System.clearProperty("config.resource")
        log.info(s"Loading network configuration from filesystem: $absolutePath")
      case None =>
        val resourcePath = s"conf/$network.conf"
        val resourceExists = Option(getClass.getClassLoader.getResource(resourcePath)).isDefined
        if resourceExists then
          System.clearProperty("config.file")
          System.setProperty("config.resource", resourcePath)
          log.info(s"Loading network configuration from classpath resource: $resourcePath")
        else log.warn(s"Config file '$resourcePath' not found in filesystem or classpath, using default config")
    // Invalidate cached config so ConfigFactory.load() picks up the new system properties.
    // Without this, logback's ConfigPropertyDefiner may have already triggered a
    // ConfigFactory.load() call during logger initialization, caching the default
    // application.conf (with network="etc") before we set config.resource here.
    ConfigFactory.invalidateCaches()

  private def determineNetworkArg(args: Array[String]): Option[String] =
    args.headOption match
      case Some(`launchFukuii`) => args.tail.find(isNetwork)
      // `cli ephemery-fetch` reads the Ephemery configuration (spec 028): a plain `cli` command loads no network's conf,
      // so it selects the Ephemery one itself, before anything reads the configuration.
      case Some(`cli`) if args.tail.headOption.contains(EphemeryFetchCli.CommandName) =>
        Some("ephemery")
      case Some(`launchKeytool`) | Some(`downloadBootstrap`) | Some(`faucet`) | Some(`sigValidator`) | Some(`cli`) =>
        None
      case Some(network) if isNetwork(network) => Some(network)
      case _                                   => None

  /** Apply modifiers to system configuration */
  private def applyModifiers(modifiers: Set[String]): Unit =
    if modifiers.contains("public") then
      System.setProperty("fukuii.network.discovery.discovery-enabled", "true")
      // Public mode: use both bootstrap nodes and static nodes for better sync experience
      System.setProperty("fukuii.network.discovery.use-bootstrap-nodes", "true")
      log.info("Public discovery explicitly enabled")
      log.info("- Using both bootstrap nodes and static-nodes.json for peer discovery")

    if modifiers.contains("enterprise") then
      // Enterprise mode: Best practices for private/permissioned EVM networks

      // Disable public peer discovery - use static nodes only
      System.setProperty("fukuii.network.discovery.discovery-enabled", "false")

      // Disable automatic port forwarding (not needed in enterprise environments)
      System.setProperty("fukuii.network.automatic-port-forwarding", "false")

      // Use known nodes from configuration/bootstrap only
      System.setProperty("fukuii.network.discovery.reuse-known-nodes", "true")

      // Enterprise mode: ignore bootstrap nodes, use only static-nodes.json
      System.setProperty("fukuii.network.discovery.use-bootstrap-nodes", "false")

      // Disable sync blacklisting to allow retry in controlled environments
      System.setProperty("fukuii.sync.blacklist-duration", "0.seconds")

      // Set RPC interface to localhost by default for security
      // Can be overridden with explicit config if needed
      System.setProperty("fukuii.network.rpc.http.interface", "localhost")

      log.info("Enterprise mode enabled: configured for private/permissioned network")
      log.info("- Public discovery disabled")
      log.info("- Using ONLY static-nodes.json (bootstrap nodes ignored)")
      log.info("- Automatic port forwarding disabled")
      log.info("- RPC bound to localhost (override with config if needed)")

  private def showHelp(): Unit =
    log.info(
      """
        |Fukuii Ethereum Client
        |
        |Usage: fukuii [public|enterprise] [network] [options]
        |   or: fukuii [command] [options]
        |
        |Modifiers:
        |  public                 Explicitly enable public peer discovery
        |                         (useful for ensuring discovery on testnets)
        |
        |  enterprise             Configure for private/permissioned EVM networks
        |                         - Disables public peer discovery
        |                         - Disables automatic port forwarding
        |                         - Binds RPC to localhost by default
        |                         - Optimized for controlled network environments
        |                         Use with custom network configuration
        |
        |Networks:
        |  etc                    Ethereum Classic mainnet (default)
        |  eth                    Ethereum mainnet
        |  sepolia                Sepolia testnet (ETH)
        |  hoodi                  Hoodi testnet (ETH)
        |  plataberget            Platåberget testnet (ETH, public Glamsterdam testnet)
        |  ephemery               Ephemery testnet (ETH, resets every 28 days; built in, refreshes itself after a reset)
        |  mordor                 Mordor testnet
        |  gorgoroth              Fukuii battlenet
        |  bootnode               Bootnode configuration (advanced)
        |
        |Commands:
        |  cli [subcommand]       Command-line utilities
        |                         Run 'fukuii cli --help' for more information
        |                         Key generation: fukuii cli generate-key-pairs [n]
        |
        |  keytool [options]      Key management tool
        |
        |  bootstrap [path]       Download blockchain bootstrap data
        |
        |  faucet                 Start faucet JSON-RPC server for testnet token distribution
        |                         Endpoints: faucet_sendFunds, faucet_status
        |
        |  signature-validator <pubkey> <sig> <hash>
        |                         Validate ECDSA signature against public key and message hash
        |
        |  [network] export <file> [<first> [<last>]]
        |                         Write blocks first..last (default: genesis to head) to <file> in
        |                         go-ethereum's chain format (bare RLP blocks; .gz is gzipped).
        |                         Run with the node stopped.
        |
        |  [network] import <file> [<file> ...]
        |                         Import a chain file (geth export / admin_exportChain format),
        |                         validating and executing every block; stops at the first error.
        |                         Run with the node stopped.
        |
        |MCP (Model Context Protocol) Support:
        |  MCP methods are available via the JSON-RPC API on port 8545
        |  Enable with: fukuii.network.rpc.http.apis = "eth,net,web3,fukuii,mcp"
        |  Methods: mcp_initialize, tools/list, tools/call, resources/list,
        |           resources/read, prompts/list, prompts/get
        |
        |Options:
        |  --help, -h             Show this help message
        |  --tui                  Enable the Terminal UI (disabled by default)
        |                         Shows real-time node status, peer count, sync progress
        |                         Console logs are suppressed while TUI is active
        |
        |Custom Configuration:
        |  Use JVM property to specify a custom config file:
        |  java -Dconfig.file=/path/to/custom.conf -jar fukuii.jar
        |
        |Examples:
        |  fukuii                                # Start Ethereum Classic node (default)
        |  fukuii etc                            # Start Ethereum Classic node
        |  fukuii etc --tui                      # Start with Terminal UI enabled
        |  fukuii mordor                         # Start Mordor testnet node
        |  fukuii public                         # Start ETC with public discovery enabled
        |  fukuii public etc                     # Start ETC with public discovery enabled
        |  fukuii public mordor                  # Start Mordor with public discovery enabled
        |  fukuii public --tui                   # Start ETC with public discovery and TUI
        |  fukuii enterprise                     # Start in enterprise mode (private network)
        |  fukuii enterprise gorgoroth           # Start private gorgoroth network
        |  fukuii cli --help                     # Show CLI utilities help
        |  fukuii cli generate-private-key       # Generate a new private key
        |  fukuii cli generate-key-pairs         # Generate node key pair (for node.key)
        |  fukuii cli generate-key-pairs 5       # Generate 5 key pairs
        |  fukuii faucet                         # Start faucet server (testnet)
        |  fukuii signature-validator <pk> <sig> <hash>  # Validate a signature
        |  fukuii mordor export mordor.rlp.gz 0 100000   # Export Mordor blocks 0..100000 (node stopped)
        |  fukuii mordor import mordor.rlp.gz            # Import them into this datadir (node stopped)
        |
        |For more information, visit: https://github.com/chippr-robotics/fukuii
        |""".stripMargin
    )

  def main(args: Array[String]): Unit = run(args, StartupDeps.production)

  /** The launcher, with what it takes from outside the process (the clock, the network, the exit) passed in, so the
    * forked integration harness can run the real startup with its own (spec 028 R5, "Test seam").
    */
  private[ethereum] def run(args: Array[String], deps: StartupDeps): Unit =

    // Parse and extract modifiers from arguments
    val modifiers = args.filter(isModifier).toSet
    val argsWithoutModifiers = args.filterNot(isModifier)

    // Configure network before any logging (required for logback property definers)
    val network = determineNetworkArg(argsWithoutModifiers)
    network.foreach(setNetworkConfig)

    // `cli ephemery-fetch` writes into the directory it is given (or a node's root): its log should not go to a node's
    // datadir, so the file appenders are dropped and it logs to the console only. BEST EFFORT: the launcher's logger was set up when `App` was
    // initialised, before any argument was read, and its file appender has already opened (and created) the default
    // network's log file. Moving that is App-init logging, which is B-241 and the user's call; this only stops further
    // writes to it. A caller can also pass `-Dlogging.logs-dir` (fetch-genesis.sh and update-bundled.sh do).
    if isEphemeryFetch(argsWithoutModifiers) then LogbackReload.consoleOnly()

    // Ephemery needs no preparation step (spec 028): choose its iteration now, after the network is set and before
    // anything reads `utils.Config`, which reads its properties once. A refusal ends the process with its code.
    val refused =
      if network.contains("ephemery") && !argsWithoutModifiers.headOption.contains(cli) then
        EphemeryResolver.startup(deps)
      else None

    refused match
      case Some(code) => deps.exit(code)
      case None       =>
        // `Fukuii.run` writes the resolver's report after it has truncated the logs. Any other command, and any path
        // that ends by throwing before `Fukuii.run` got that far, writes it here: it is held back until then, and
        // `flushReport` writes it once.
        try dispatch(argsWithoutModifiers, modifiers, deps)
        finally EphemeryResolver.flushReport()

  private def isEphemeryFetch(args: Array[String]): Boolean =
    args.headOption.contains(cli) && args.tail.headOption.contains(EphemeryFetchCli.CommandName)

  private def dispatch(argsWithoutModifiers: Array[String], modifiers: Set[String], deps: StartupDeps): Unit =

    // Apply modifiers (e.g., "public" enables discovery)
    applyModifiers(modifiers)

    argsWithoutModifiers.headOption match
      case None                  => Fukuii.run(argsWithoutModifiers, deps)
      case Some("--help" | "-h") => showHelp()
      case Some(`launchFukuii`)  =>
        // Filter out network name from remaining args to avoid passing it to Fukuii.main
        val remainingArgs = argsWithoutModifiers.tail.headOption.filter(isNetwork) match
          case Some(_) => argsWithoutModifiers.tail.tail
          case None    => argsWithoutModifiers.tail
        Fukuii.run(remainingArgs, deps)
      case Some(`launchKeytool`)     => KeyTool.main(argsWithoutModifiers.tail)
      case Some(`downloadBootstrap`) =>
        // Import Config locally to ensure it's loaded after any network config is set.
        // This delayed import is intentional - Config is a lazy-initialized object that
        // reads config.file system property at initialization time.
        import com.chipprbots.ethereum.utils.Config
        Config.Db.dataSource match
          case "rocksdb" => BootstrapDownload.main(argsWithoutModifiers.tail :+ Config.Db.RocksDb.path)
      // HIBERNATED: vm-server case commented out
      // case Some(`vmServer`)     => VmServerApp.main(argsWithoutModifiers.tail)
      case Some("runtime") =>
        log.info("Starting Fukuii multi-instance runtime")
        JvmFlagGuard.check() // this path bypasses Fukuii.main, which runs the same check
        val rootConfig = com.typesafe.config.ConfigFactory.load()
        val runtime = com.chipprbots.ethereum.runtime.FukuiiRuntime.fromConfig(rootConfig)
        runtime.startAll()
        // Block until shutdown
        Runtime.getRuntime.addShutdownHook(new Thread(() => runtime.stopAll()))
      case Some(`faucet`)       => Faucet.main(argsWithoutModifiers.tail)
      case Some(`sigValidator`) => SignatureValidator.main(argsWithoutModifiers.tail)
      case Some(`cli`)          => CliLauncher.main(argsWithoutModifiers.tail)
      case Some("checkpoint")   =>
        // `fukuii [network] checkpoint <subcommand>`. The network arg (if present) was already
        // applied by setNetworkConfig; strip it from the args we forward so decline only sees
        // `checkpoint export ...`.
        com.chipprbots.ethereum.blockchain.checkpoint.CheckpointCli.main(argsWithoutModifiers.tail)
      // `fukuii import|export ...` without a network: the default (or -Dconfig.file) chain.
      case Some(cmd) if com.chipprbots.ethereum.nodebuilder.ChainTool.isCommand(cmd) =>
        Fukuii.run(argsWithoutModifiers, deps)
      case Some(network) if isNetwork(network) =>
        val tail = argsWithoutModifiers.tail
        tail.headOption match
          case Some("checkpoint") =>
            com.chipprbots.ethereum.blockchain.checkpoint.CheckpointCli.main(tail.tail)
          case _ => Fukuii.run(tail, deps)
      case Some(arg) if isOptionFlag(arg) =>
        // Option flags (starting with -) are passed directly to Fukuii
        Fukuii.run(argsWithoutModifiers, deps)
      case Some(unknown) =>
        log.error(
          s"Unrecognised launcher option: $unknown\n" +
            s"Run 'fukuii --help' to see available commands."
        )
