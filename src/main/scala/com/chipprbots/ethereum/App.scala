package com.chipprbots.ethereum

import java.io.File

import scala.sys

import com.chipprbots.ethereum.cli.CliLauncher
import com.chipprbots.ethereum.crypto.EcKeyGen
import com.chipprbots.ethereum.crypto.SignatureValidator
import com.chipprbots.ethereum.faucet.Faucet
import com.chipprbots.ethereum.utils.Logger

object App extends Logger {

  // Known network names that correspond to config files in conf/ directory
  private val knownNetworks = Set(
    "etc",
    "eth",
    "mordor",
    "pottery",
    "sagano",
    "bootnode",
    "testnet-internal-nomad",
    "gorgoroth"
  )

  // Known modifiers that affect launcher behavior
  private val knownModifiers = Set("public", "enterprise")

  // Launcher commands
  private val launchFukuii = "fukuii"
  private val launchKeytool = "keytool"
  private val downloadBootstrap = "bootstrap"
  private val faucet = "faucet"
  private val ecKeyGen = "eckeygen"
  private val cli = "cli"
  private val sigValidator = "signature-validator"

  /** Check if argument is an option flag (starts with -) */
  private def isOptionFlag(arg: String): Boolean = arg.startsWith("-")

  /** Check if argument is a known network name */
  private def isNetwork(arg: String): Boolean = knownNetworks.contains(arg)

  /** Check if argument is a known modifier */
  private def isModifier(arg: String): Boolean = knownModifiers.contains(arg)

  private def findFilesystemConfig(network: String, currentConfigFile: Option[String]): Option[File] = {
    val envConfiguredDir = sys.env.get("FUKUII_CONF_DIR").map(new File(_))
    val systemConfiguredDir = Option(System.getProperty("fukuii.conf.dir")).map(new File(_))
    val launcherConfigDir = currentConfigFile
      .flatMap(path => Option(new File(path).getParentFile))

    val candidateDirs = Seq(envConfiguredDir, systemConfiguredDir, launcherConfigDir)
      .flatten
      .map(_.getAbsoluteFile)
      .distinctBy(_.getAbsolutePath)

    val directCandidates = Seq(
      new File(s"conf/$network.conf"),
      new File(s"$network.conf")
    ).map(_.getAbsoluteFile)

    val filesFromDirs = candidateDirs.map(dir => new File(dir, s"$network.conf"))

    (filesFromDirs ++ directCandidates)
      .find(file => file.exists() && file.isFile)
  }

  /** Set config file for the specified network (must be called before Config is accessed) */
  private def setNetworkConfig(network: String): Unit = {
    val currentConfigFile = Option(System.getProperty("config.file"))
    findFilesystemConfig(network, currentConfigFile) match {
      case Some(file) =>
        val absolutePath = file.getAbsolutePath
        System.setProperty("config.file", absolutePath)
        System.clearProperty("config.resource")
        log.info(s"Loading network configuration from filesystem: $absolutePath")
      case None =>
        val resourcePath = s"conf/$network.conf"
        val resourceExists = Option(getClass.getClassLoader.getResource(resourcePath)).isDefined
        if (resourceExists) {
          System.clearProperty("config.file")
          System.setProperty("config.resource", resourcePath)
          log.info(s"Loading network configuration from classpath resource: $resourcePath")
        } else {
          log.warn(s"Config file '$resourcePath' not found in filesystem or classpath, using default config")
        }
    }
  }

  private def determineNetworkArg(args: Array[String]): Option[String] =
    args.headOption match {
      case Some(`launchFukuii`) => args.tail.find(isNetwork)
      case Some(`launchKeytool`) | Some(`downloadBootstrap`) | Some(`faucet`) | Some(`sigValidator`) |
          Some(`cli`) => None
      case Some(network) if isNetwork(network) => Some(network)
      case _                                   => None
    }

  /** Apply modifiers to system configuration */
  private def applyModifiers(modifiers: Set[String]): Unit = {
    if (modifiers.contains("public")) {
      System.setProperty("fukuii.network.discovery.discovery-enabled", "true")
      // Public mode: use both bootstrap nodes and static nodes for better sync experience
      System.setProperty("fukuii.network.discovery.use-bootstrap-nodes", "true")
      log.info("Public discovery explicitly enabled")
      log.info("- Using both bootstrap nodes and static-nodes.json for peer discovery")
    }
    
    if (modifiers.contains("enterprise")) {
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
    }
  }

  private def showHelp(): Unit =
    println(
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
        |  mordor                 Mordor testnet
        |  pottery                Pottery testnet
        |  sagano                 Sagano testnet
        |  bootnode               Bootnode configuration (advanced)
        |  testnet-internal-nomad Internal Nomad testnet (advanced)
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
        |MCP (Model Context Protocol) Support:
        |  MCP methods are available via the JSON-RPC API on port 8545
        |  Enable with: fukuii.network.rpc.apis = [..., "mcp"]
        |  Methods: mcp_initialize, tools/list, tools/call, resources/list,
        |           resources/read, prompts/list, prompts/get
        |
        |Options:
        |  --help, -h             Show this help message
        |  --tui                  Enable the Terminal UI (disabled by default)
        |                         Shows real-time node status, peer count, sync progress
        |                         Console logs are suppressed while TUI is active
        |  --force-pivot-sync     Disable checkpoint bootstrapping and force pivot sync
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
        |  fukuii enterprise pottery             # Start private pottery network
        |  fukuii cli --help                     # Show CLI utilities help
        |  fukuii cli generate-private-key       # Generate a new private key
        |  fukuii cli generate-key-pairs         # Generate node key pair (for node.key)
        |  fukuii cli generate-key-pairs 5       # Generate 5 key pairs
        |  fukuii faucet                         # Start faucet server (testnet)
        |  fukuii signature-validator <pk> <sig> <hash>  # Validate a signature
        |
        |For more information, visit: https://github.com/chippr-robotics/fukuii
        |""".stripMargin
    )

  def main(args: Array[String]): Unit = {

    // Parse and extract modifiers from arguments
    val modifiers = args.filter(isModifier).toSet
    val argsWithoutModifiers = args.filterNot(isModifier)

    // Configure network before any logging (required for logback property definers)
    determineNetworkArg(argsWithoutModifiers).foreach(setNetworkConfig)

    // Apply modifiers (e.g., "public" enables discovery)
    applyModifiers(modifiers)

    argsWithoutModifiers.headOption match {
      case None                  => Fukuii.main(argsWithoutModifiers)
      case Some("--help" | "-h") => showHelp()
      case Some(`launchFukuii`) =>
        // Filter out network name from remaining args to avoid passing it to Fukuii.main
        val remainingArgs = argsWithoutModifiers.tail.headOption.filter(isNetwork) match {
          case Some(_) => argsWithoutModifiers.tail.tail
          case None    => argsWithoutModifiers.tail
        }
        Fukuii.main(remainingArgs)
      case Some(`launchKeytool`) => KeyTool.main(argsWithoutModifiers.tail)
      case Some(`downloadBootstrap`) =>
        // Import Config locally to ensure it's loaded after any network config is set.
        // This delayed import is intentional - Config is a lazy-initialized object that
        // reads config.file system property at initialization time.
        import com.chipprbots.ethereum.utils.Config
        Config.Db.dataSource match {
          case "rocksdb" => BootstrapDownload.main(argsWithoutModifiers.tail :+ Config.Db.RocksDb.path)
        }
      // HIBERNATED: vm-server case commented out
      // case Some(`vmServer`)     => VmServerApp.main(argsWithoutModifiers.tail)
      case Some(`faucet`)       => Faucet.main(argsWithoutModifiers.tail)
      case Some(`ecKeyGen`)     => EcKeyGen.main(argsWithoutModifiers.tail)
      case Some(`sigValidator`) => SignatureValidator.main(argsWithoutModifiers.tail)
      case Some(`cli`)          => CliLauncher.main(argsWithoutModifiers.tail)
      case Some(network) if isNetwork(network) =>
        Fukuii.main(argsWithoutModifiers.tail)
      case Some(arg) if isOptionFlag(arg) =>
        // Option flags (starting with -) are passed directly to Fukuii
        Fukuii.main(argsWithoutModifiers)
      case Some(unknown) =>
        log.error(
          s"Unrecognised launcher option: $unknown\n" +
            s"Run 'fukuii --help' to see available commands."
        )
    }

  }
}
