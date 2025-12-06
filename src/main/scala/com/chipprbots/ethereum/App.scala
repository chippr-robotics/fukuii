package com.chipprbots.ethereum

import java.io.File

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

  /** Check if argument is an option flag (starts with -) */
  private def isOptionFlag(arg: String): Boolean = arg.startsWith("-")

  /** Check if argument is a known network name */
  private def isNetwork(arg: String): Boolean = knownNetworks.contains(arg)

  /** Check if argument is a known modifier */
  private def isModifier(arg: String): Boolean = knownModifiers.contains(arg)

  /** Set config file for the specified network (must be called before Config is accessed) */
  private def setNetworkConfig(network: String): Unit = {
    val configFile = s"conf/$network.conf"
    // Only set if the config file exists
    val file = new File(configFile)
    if (file.exists()) {
      System.setProperty("config.file", configFile)
    } else {
      // Log warning when config file doesn't exist for a known network
      log.warn(s"Config file '$configFile' not found for network '$network', using default config")
    }
  }

  /** Apply modifiers to system configuration */
  private def applyModifiers(modifiers: Set[String]): Unit = {
    if (modifiers.contains("public")) {
      System.setProperty("fukuii.network.discovery.discovery-enabled", "true")
      log.info("Public discovery explicitly enabled")
    }
    
    if (modifiers.contains("enterprise")) {
      // Enterprise mode: Best practices for private/permissioned EVM networks
      
      // Disable public peer discovery - use bootstrap nodes only
      System.setProperty("fukuii.network.discovery.discovery-enabled", "false")
      
      // Disable automatic port forwarding (not needed in enterprise environments)
      System.setProperty("fukuii.network.automatic-port-forwarding", "false")
      
      // Use known nodes from configuration/bootstrap only
      System.setProperty("fukuii.network.discovery.reuse-known-nodes", "true")
      
      // Disable sync blacklisting to allow retry in controlled environments
      System.setProperty("fukuii.sync.blacklist-duration", "0.seconds")
      
      // Set RPC interface to localhost by default for security
      // Can be overridden with explicit config if needed
      System.setProperty("fukuii.network.rpc.http.interface", "localhost")
      
      log.info("Enterprise mode enabled: configured for private/permissioned network")
      log.info("- Public discovery disabled (use bootstrap nodes)")
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
        |
        |  keytool [options]      Key management tool
        |
        |  bootstrap [path]       Download blockchain bootstrap data
        |
        |  faucet [options]       Run faucet service
        |
        |  eckeygen [options]     Generate EC key pairs
        |
        |  signature-validator    Validate signatures
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
        |  fukuii                          # Start Ethereum Classic node (default)
        |  fukuii etc                      # Start Ethereum Classic node
        |  fukuii etc --tui                # Start with Terminal UI enabled
        |  fukuii mordor                   # Start Mordor testnet node
        |  fukuii public                   # Start ETC with public discovery enabled
        |  fukuii public etc               # Start ETC with public discovery enabled
        |  fukuii public mordor            # Start Mordor with public discovery enabled
        |  fukuii public --tui             # Start ETC with public discovery and TUI
        |  fukuii enterprise               # Start in enterprise mode (private network)
        |  fukuii enterprise pottery       # Start private pottery network
        |  fukuii cli --help               # Show CLI utilities help
        |  fukuii cli generate-private-key # Generate a new private key
        |
        |For more information, visit: https://github.com/chippr-robotics/fukuii
        |""".stripMargin
    )

  def main(args: Array[String]): Unit = {

    val launchFukuii = "fukuii"
    val launchKeytool = "keytool"
    val downloadBootstrap = "bootstrap"
    // HIBERNATED: vm-server option commented out
    // val vmServer = "vm-server"
    val faucet = "faucet"
    val ecKeyGen = "eckeygen"
    val cli = "cli"
    val sigValidator = "signature-validator"

    // Parse and extract modifiers from arguments
    val modifiers = args.filter(isModifier).toSet
    val argsWithoutModifiers = args.filterNot(isModifier)

    // Apply modifiers (e.g., "public" enables discovery)
    applyModifiers(modifiers)

    argsWithoutModifiers.headOption match {
      case None                  => Fukuii.main(argsWithoutModifiers)
      case Some("--help" | "-h") => showHelp()
      case Some(`launchFukuii`) =>
        // Handle 'fukuii <network>' case - set config before launching
        argsWithoutModifiers.tail.headOption.filter(isNetwork).foreach(setNetworkConfig)
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
        // Network name specified - set config and launch Fukuii
        setNetworkConfig(network)
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
