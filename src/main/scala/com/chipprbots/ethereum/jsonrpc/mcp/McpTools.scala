package com.chipprbots.ethereum.jsonrpc.mcp

import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.ExecutionContext
import scala.util.Try

import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jvalue2monadic
import org.json4s.jvalue2extractable
import org.json4s.DefaultFormats

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.{AkkaTaskOps, McpDependencies, McpService}
import com.chipprbots.ethereum.jsonrpc.McpService._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.ByteStringUtils

import AkkaTaskOps._

private implicit val formats: org.json4s.Formats = DefaultFormats

// --- Status Tools (replace stubs) ---

object NodeInfoTool {
  val name = "mcp_node_info"
  val description = Some("Get detailed information about the Fukuii ETC node including version, network, and build info")

  def execute(deps: McpDependencies): IO[String] = {
    val networkName = if (deps.blockchainConfig.chainId == BigInt(61)) "ETC Mainnet"
      else if (deps.blockchainConfig.chainId == BigInt(63)) "Mordor Testnet"
      else s"Chain ${deps.blockchainConfig.chainId}"
    IO.pure(s"""Fukuii Node Information:
      |  Version: ${BuildInfo.version}
      |  Scala Version: ${BuildInfo.scalaVersion}
      |  Git Commit: ${BuildInfo.gitHeadCommit}
      |  Git Branch: ${BuildInfo.gitCurrentBranch}
      |  Network: $networkName
      |  Chain ID: ${deps.blockchainConfig.chainId}
      |  Network ID: ${deps.blockchainConfig.networkId}
      |  Client ID: Fukuii/${BuildInfo.version}""".stripMargin)
  }
}

object NodeStatusTool {
  val name = "mcp_node_status"
  val description = Some("Get the current status of the Fukuii node including sync state, peer count, and block numbers")

  def execute(deps: McpDependencies)(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    val syncStatusIO = deps.syncController.askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
    val peersIO = deps.peerManager.askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)

    for {
      syncStatus <- syncStatusIO.recover { case _ => SyncProtocol.Status.NotSyncing }
      peers <- peersIO.recover { case _ => PeerManagerActor.Peers(Map.empty) }
    } yield {
      val bestBlock = deps.blockchainReader.getBestBlockNumber()
      val peerCount = peers.peers.size
      val handshakedCount = peers.handshaked.size
      val (syncState, progress) = syncStatus match {
        case SyncProtocol.Status.Syncing(start, blocks, stateNodes) =>
          val pct = if (blocks.target > 0) f"${(blocks.current.toDouble / blocks.target.toDouble * 100)}%.1f%%" else "N/A"
          (s"Syncing (from block $start)", s"Block ${blocks.current}/${blocks.target} ($pct)")
        case SyncProtocol.Status.SyncDone => ("Synced", "Complete")
        case SyncProtocol.Status.NotSyncing => ("Not syncing", "N/A")
      }
      s"""Node Status:
        |  Running: true
        |  Sync State: $syncState
        |  Sync Progress: $progress
        |  Best Block: $bestBlock
        |  Peers: $peerCount total ($handshakedCount handshaked)""".stripMargin
    }
  }
}

object BlockchainInfoTool {
  val name = "mcp_blockchain_info"
  val description = Some("Get information about the blockchain state including best block, total difficulty, and genesis hash")

  def execute(deps: McpDependencies): IO[String] = IO {
    val bestBlockNum = deps.blockchainReader.getBestBlockNumber()
    val bestBlock = deps.blockchainReader.getBestBlock()
    val bestHash = bestBlock.map(b => ByteStringUtils.hash2string(b.header.hash)).getOrElse("unknown")
    val td = bestBlock.flatMap(b => deps.blockchainReader.getChainWeightByHash(b.header.hash))
      .map(_.totalDifficulty.toString).getOrElse("unknown")
    val genesisHash = deps.blockchainReader.getBlockHeaderByNumber(0)
      .map(h => ByteStringUtils.hash2string(h.hash)).getOrElse("unknown")
    s"""Blockchain Information:
      |  Network: ${if (deps.blockchainConfig.chainId == BigInt(61)) "Ethereum Classic (ETC)" else s"Chain ${deps.blockchainConfig.chainId}"}
      |  Best Block Number: $bestBlockNum
      |  Best Block Hash: $bestHash
      |  Chain ID: ${deps.blockchainConfig.chainId}
      |  Total Difficulty: $td
      |  Genesis Hash: $genesisHash""".stripMargin
  }
}

object SyncStatusTool {
  val name = "mcp_sync_status"
  val description = Some("Get detailed synchronization status including mode, progress, and remaining blocks")

  def execute(deps: McpDependencies)(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    deps.syncController.askFor[SyncProtocol.Status](SyncProtocol.GetStatus).recover {
      case _ => SyncProtocol.Status.NotSyncing
    }.map { status =>
      val bestBlock = deps.blockchainReader.getBestBlockNumber()
      status match {
        case SyncProtocol.Status.Syncing(start, blocks, stateNodes) =>
          val remaining = blocks.target - blocks.current
          val pct = if (blocks.target > 0) f"${(blocks.current.toDouble / blocks.target.toDouble * 100)}%.1f%%" else "N/A"
          val stateInfo = stateNodes.filter(_.nonEmpty).map(s =>
            s"\n  State Nodes: ${s.current}/${s.target}"
          ).getOrElse("")
          s"""Sync Status:
            |  Mode: Fast/SNAP Sync
            |  Syncing: true
            |  Starting Block: $start
            |  Current Block: ${blocks.current}
            |  Target Block: ${blocks.target}
            |  Remaining Blocks: $remaining
            |  Progress: $pct$stateInfo""".stripMargin
        case SyncProtocol.Status.SyncDone =>
          s"""Sync Status:
            |  Mode: Regular Sync
            |  Syncing: false
            |  Best Block: $bestBlock
            |  Status: Fully synced""".stripMargin
        case SyncProtocol.Status.NotSyncing =>
          s"""Sync Status:
            |  Mode: Not syncing
            |  Syncing: false
            |  Best Block: $bestBlock
            |  Status: Idle""".stripMargin
      }
    }
  }
}

object PeerListTool {
  val name = "mcp_peer_list"
  val description = Some("List all connected peers with their addresses, status, and connection direction")

  def execute(deps: McpDependencies)(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    deps.peerManager.askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers).recover {
      case _ => PeerManagerActor.Peers(Map.empty)
    }.map { peers =>
      if (peers.peers.isEmpty) {
        "Connected Peers: 0\n  No peers connected."
      } else {
        val peerLines = peers.peers.toList.sortBy(_._1.id.value).map { case (peer, status) =>
          val direction = if (peer.incomingConnection) "inbound" else "outbound"
          val addr = peer.remoteAddress.toString
          val statusStr = status match {
            case com.chipprbots.ethereum.network.PeerActor.Status.Handshaked => "handshaked"
            case com.chipprbots.ethereum.network.PeerActor.Status.Connecting => "connecting"
            case com.chipprbots.ethereum.network.PeerActor.Status.Disconnected => "disconnected"
            case s: com.chipprbots.ethereum.network.PeerActor.Status.Handshaking => s"handshaking (retry ${s.numRetries})"
            case _ => "idle"
          }
          s"  ${peer.id.value}: $addr ($direction, $statusStr)"
        }
        s"Connected Peers: ${peers.peers.size}\n${peerLines.mkString("\n")}"
      }
    }
  }
}

object SetEtherbaseTool {
  val name = "mcp_etherbase_info"
  val description = Some("Get information about setting the etherbase (coinbase) address for mining rewards via JSON-RPC")

  def execute(): IO[String] = {
    IO.pure(s"""Etherbase (Coinbase) Configuration:
      |  Method: eth_setEtherbase
      |  Description: Sets the coinbase address for mining rewards
      |  Usage: Send JSON-RPC request with method "eth_setEtherbase" and address parameter
      |  Example: {"jsonrpc":"2.0","method":"eth_setEtherbase","params":["0x1234..."],"id":1}
      |  Note: Changes take effect immediately for newly generated blocks""".stripMargin)
  }
}

object MiningRpcSummaryTool {
  val name = "mcp_mining_rpc_summary"
  val description = Some("List mining RPC endpoints and their usage")

  def execute(): IO[String] = {
    IO.pure("""Mining RPC Endpoints:
      |  eth_mining -> Check if the node is mining
      |  eth_hashrate -> Get the current hash rate
      |  eth_getWork -> Get current work package (powHash, dagSeed, target)
      |  eth_coinbase -> Get the coinbase (etherbase) address
      |  eth_submitWork -> Submit a proof-of-work solution (nonce, powHash, mixDigest)
      |  eth_submitHashrate -> Submit external hashrate for monitoring
      |  miner_start -> Start mining (params: optional thread count)
      |  miner_stop -> Stop mining
      |  miner_getStatus -> Get mining status (isMining, coinbase, hashRate)
      |""".stripMargin)
  }
}

// --- Blockchain Query Tools (new) ---

object GetBlockTool {
  val name = "get_block"
  val description = Some("Get block information by number, hash, or 'latest'. Returns header details including hash, parent, miner, gas, and timestamps.")

  val inputSchema: JValue =
    ("type" -> "object") ~
    ("properties" -> ("block" -> (("type" -> "string") ~ ("description" -> "Block number, block hash (0x-prefixed), or 'latest'")))) ~
    ("required" -> List("block"))

  def execute(args: Option[JValue], deps: McpDependencies): IO[String] = IO {
    val blockArg = args.flatMap(a => (a \ "block").extractOpt[String]).getOrElse("latest")
    val headerOpt = blockArg.toLowerCase match {
      case "latest" =>
        deps.blockchainReader.getBestBlock().map(_.header)
      case s if s.startsWith("0x") && s.length > 10 =>
        val hash = org.apache.pekko.util.ByteString(org.bouncycastle.util.encoders.Hex.decode(s.drop(2)))
        deps.blockchainReader.getBlockByHash(hash).map(_.header)
      case s =>
        Try(BigInt(s)).toOption.flatMap(n => deps.blockchainReader.getBlockHeaderByNumber(n))
    }
    headerOpt match {
      case Some(h) =>
        val td = deps.blockchainReader.getChainWeightByHash(h.hash).map(_.totalDifficulty.toString).getOrElse("unknown")
        s"""Block #${h.number}:
          |  Hash: ${ByteStringUtils.hash2string(h.hash)}
          |  Parent: ${ByteStringUtils.hash2string(h.parentHash)}
          |  Miner: 0x${org.bouncycastle.util.encoders.Hex.toHexString(h.beneficiary.toArray)}
          |  Difficulty: ${h.difficulty}
          |  Total Difficulty: $td
          |  Gas Limit: ${h.gasLimit}
          |  Gas Used: ${h.gasUsed}
          |  Timestamp: ${h.unixTimestamp} (${java.time.Instant.ofEpochSecond(h.unixTimestamp)})
          |  Transactions Root: ${ByteStringUtils.hash2string(h.transactionsRoot)}
          |  State Root: ${ByteStringUtils.hash2string(h.stateRoot)}
          |  Extra Data: 0x${org.bouncycastle.util.encoders.Hex.toHexString(h.extraData.toArray)}""".stripMargin
      case None => s"Block not found: $blockArg"
    }
  }
}

object GetTransactionTool {
  val name = "get_transaction"
  val description = Some("Get transaction location by hash. Returns the block hash and transaction index where the transaction was included.")

  val inputSchema: JValue =
    ("type" -> "object") ~
    ("properties" -> ("hash" -> (("type" -> "string") ~ ("description" -> "Transaction hash (0x-prefixed)")))) ~
    ("required" -> List("hash"))

  def execute(args: Option[JValue], deps: McpDependencies): IO[String] = IO {
    val hashStr = args.flatMap(a => (a \ "hash").extractOpt[String]).getOrElse("")
    val hashBytes = Try(org.bouncycastle.util.encoders.Hex.decode(hashStr.stripPrefix("0x"))).getOrElse(Array.empty[Byte])
    if (hashBytes.length != 32) {
      s"Invalid transaction hash: $hashStr (expected 32 bytes)"
    } else {
      deps.transactionMappingStorage.get(hashBytes.toIndexedSeq) match {
        case Some(loc) =>
          s"""Transaction Found:
            |  Hash: $hashStr
            |  Block Hash: ${ByteStringUtils.hash2string(loc.blockHash)}
            |  Transaction Index: ${loc.txIndex}""".stripMargin
        case None =>
          s"Transaction not found: $hashStr"
      }
    }
  }
}

object GetAccountTool {
  val name = "get_account"
  val description = Some("Get account state (nonce, balance) at the current best block. May fail during sync if state is unavailable.")

  val inputSchema: JValue =
    ("type" -> "object") ~
    ("properties" -> ("address" -> (("type" -> "string") ~ ("description" -> "Account address (0x-prefixed)")))) ~
    ("required" -> List("address"))

  def execute(args: Option[JValue], deps: McpDependencies): IO[String] = IO {
    val addrStr = args.flatMap(a => (a \ "address").extractOpt[String]).getOrElse("")
    Try {
      val addrBytes = org.bouncycastle.util.encoders.Hex.decode(addrStr.stripPrefix("0x"))
      val address = Address(org.apache.pekko.util.ByteString(addrBytes))
      val blockNum = deps.blockchainReader.getBestBlockNumber()
      val accountOpt = deps.blockchainReader.getAccount(deps.blockchainReader.getBestBranch(), address, blockNum)
      accountOpt match {
        case Some(account) =>
          val balanceEtc = BigDecimal(account.balance.toBigInt) / BigDecimal("1000000000000000000")
          s"""Account: $addrStr
            |  Block: $blockNum
            |  Nonce: ${account.nonce}
            |  Balance: ${account.balance} wei ($balanceEtc ETC)
            |  Storage Root: ${ByteStringUtils.hash2string(account.storageRoot)}
            |  Code Hash: ${ByteStringUtils.hash2string(account.codeHash)}""".stripMargin
        case None =>
          s"""Account: $addrStr
            |  Block: $blockNum
            |  Status: Empty (no state)""".stripMargin
      }
    }.recover {
      case _: MissingNodeException => s"Account state unavailable (node is syncing): $addrStr"
      case e: Exception => s"Error querying account $addrStr: ${e.getMessage}"
    }.get
  }
}

// --- ETC-Specific Tools (new) ---

object DetectReorgTool {
  val name = "detect_reorg"
  val description = Some("Check recent blocks for chain reorganization by verifying parent hash consistency")

  val inputSchema: JValue =
    ("type" -> "object") ~
    ("properties" -> ("depth" -> (("type" -> "integer") ~ ("description" -> "Number of recent blocks to check (default: 20)")))) ~
    ("required" -> List.empty[String])

  def execute(args: Option[JValue], deps: McpDependencies): IO[String] = IO {
    val depth = args.flatMap(a => (a \ "depth").extractOpt[Int]).getOrElse(20)
    val bestNum = deps.blockchainReader.getBestBlockNumber()
    val startNum = (bestNum - depth).max(0)

    val headers = (startNum to bestNum).flatMap(n => deps.blockchainReader.getBlockHeaderByNumber(n))
    val inconsistencies = headers.sliding(2).flatMap {
      case Seq(parent, child) if child.parentHash != parent.hash =>
        Some(s"  Reorg detected: block ${child.number} parent ${ByteStringUtils.hash2string(child.parentHash)} != block ${parent.number} hash ${ByteStringUtils.hash2string(parent.hash)}")
      case _ => None
    }.toList

    if (inconsistencies.isEmpty) {
      s"No reorgs detected in blocks $startNum to $bestNum ($depth blocks checked)"
    } else {
      s"Reorg(s) detected in blocks $startNum to $bestNum:\n${inconsistencies.mkString("\n")}"
    }
  }
}

object ConvertUnitsTool {
  val name = "convert_units"
  val description = Some("Convert between ETC denominations: wei, gwei, and etc")

  val inputSchema: JValue =
    ("type" -> "object") ~
    ("properties" -> (
      ("value" -> (("type" -> "string") ~ ("description" -> "Numeric value to convert"))) ~
      ("from_unit" -> (("type" -> "string") ~ ("description" -> "Source unit: wei, gwei, or etc"))) ~
      ("to_unit" -> (("type" -> "string") ~ ("description" -> "Target unit: wei, gwei, or etc")))
    )) ~
    ("required" -> List("value", "from_unit", "to_unit"))

  def execute(args: Option[JValue]): IO[String] = IO {
    val value = args.flatMap(a => (a \ "value").extractOpt[String]).getOrElse("0")
    val fromUnit = args.flatMap(a => (a \ "from_unit").extractOpt[String]).getOrElse("wei").toLowerCase
    val toUnit = args.flatMap(a => (a \ "to_unit").extractOpt[String]).getOrElse("etc").toLowerCase

    Try {
      val weiValue: BigDecimal = fromUnit match {
        case "wei" => BigDecimal(value)
        case "gwei" => BigDecimal(value) * BigDecimal("1000000000")
        case "etc" | "ether" => BigDecimal(value) * BigDecimal("1000000000000000000")
        case _ => throw new IllegalArgumentException(s"Unknown unit: $fromUnit")
      }
      val result: BigDecimal = toUnit match {
        case "wei" => weiValue
        case "gwei" => weiValue / BigDecimal("1000000000")
        case "etc" | "ether" => weiValue / BigDecimal("1000000000000000000")
        case _ => throw new IllegalArgumentException(s"Unknown unit: $toUnit")
      }
      s"$value $fromUnit = ${result.bigDecimal.toPlainString} $toUnit"
    }.recover {
      case e: Exception => s"Conversion error: ${e.getMessage}"
    }.get
  }
}

object GetEtcEmissionTool {
  val name = "get_etc_emission"
  val description = Some("Get the ETC emission schedule and current era information based on the best block number")

  def execute(deps: McpDependencies): IO[String] = IO {
    val bestBlock = deps.blockchainReader.getBestBlockNumber()
    val eraLength = BigInt(5000000)
    val currentEra = (bestBlock / eraLength).toInt
    val blocksInEra = bestBlock % eraLength
    val nextEraBlock = (currentEra + 1) * eraLength

    val baseReward = BigDecimal("5")
    val currentReward = baseReward * BigDecimal(0.8).pow(currentEra)

    s"""ETC Emission Schedule:
      |  Best Block: $bestBlock
      |  Current Era: $currentEra (era length: $eraLength blocks)
      |  Blocks Into Era: $blocksInEra
      |  Next Era Block: $nextEraBlock
      |  Base Reward: 5 ETC (Era 0)
      |  Current Reward: ~${currentReward.setScale(4, BigDecimal.RoundingMode.HALF_UP)} ETC/block
      |  Reduction: 20% per era (ECIP-1017)""".stripMargin
  }
}

object GetEtcForksTool {
  val name = "get_etc_forks"
  val description = Some("Get the ECIP hard fork history and activation blocks for this network")

  def execute(deps: McpDependencies): IO[String] = IO {
    val forks = deps.blockchainConfig.forkBlockNumbers
    val bestBlock = deps.blockchainReader.getBestBlockNumber()

    def status(block: BigInt): String = if (block <= bestBlock) "ACTIVE" else s"PENDING (in ${block - bestBlock} blocks)"

    s"""ETC Fork History (Chain ID: ${deps.blockchainConfig.chainId}):
      |  Frontier:       block ${forks.frontierBlockNumber} [${status(forks.frontierBlockNumber)}]
      |  Homestead:      block ${forks.homesteadBlockNumber} [${status(forks.homesteadBlockNumber)}]
      |  EIP-150:        block ${forks.eip150BlockNumber} [${status(forks.eip150BlockNumber)}]
      |  EIP-155/160:    block ${forks.eip155BlockNumber} [${status(forks.eip155BlockNumber)}]
      |  Atlantis:       block ${forks.atlantisBlockNumber} [${status(forks.atlantisBlockNumber)}]
      |  Agharta:        block ${forks.aghartaBlockNumber} [${status(forks.aghartaBlockNumber)}]
      |  Phoenix:        block ${forks.phoenixBlockNumber} [${status(forks.phoenixBlockNumber)}]
      |  Magneto:        block ${forks.magnetoBlockNumber} [${status(forks.magnetoBlockNumber)}]
      |  Mystique:       block ${forks.mystiqueBlockNumber} [${status(forks.mystiqueBlockNumber)}]
      |  Spiral:         block ${forks.spiralBlockNumber} [${status(forks.spiralBlockNumber)}]
      |  Best Block: $bestBlock""".stripMargin
  }
}

object GetTreasuryStatusTool {
  val name = "get_treasury_status"
  val description = Some("Get the treasury account balance at the current best block")

  def execute(deps: McpDependencies): IO[String] = IO {
    val treasuryAddr = deps.blockchainConfig.treasuryAddress
    val blockNum = deps.blockchainReader.getBestBlockNumber()
    Try {
      val accountOpt = deps.blockchainReader.getAccount(deps.blockchainReader.getBestBranch(), treasuryAddr, blockNum)
      accountOpt match {
        case Some(account) =>
          val balanceEtc = BigDecimal(account.balance.toBigInt) / BigDecimal("1000000000000000000")
          s"""Treasury Status:
            |  Address: 0x${org.bouncycastle.util.encoders.Hex.toHexString(treasuryAddr.bytes.toArray)}
            |  Block: $blockNum
            |  Balance: ${account.balance} wei ($balanceEtc ETC)
            |  Nonce: ${account.nonce}""".stripMargin
        case None =>
          s"""Treasury Status:
            |  Address: 0x${org.bouncycastle.util.encoders.Hex.toHexString(treasuryAddr.bytes.toArray)}
            |  Block: $blockNum
            |  Status: Empty (no balance)""".stripMargin
      }
    }.recover {
      case _: MissingNodeException => s"Treasury state unavailable (node is syncing)"
      case e: Exception => s"Error querying treasury: ${e.getMessage}"
    }.get
  }
}

object GetChainConfigTool {
  val name = "get_chain_config"
  val description = Some("Get the blockchain configuration as structured output including chain ID, network ID, and monetary policy")

  def execute(deps: McpDependencies): IO[String] = IO {
    val cfg = deps.blockchainConfig
    s"""Chain Configuration:
      |  Chain ID: ${cfg.chainId}
      |  Network ID: ${cfg.networkId}
      |  Account Start Nonce: ${cfg.accountStartNonce}
      |  Gas Tie Breaker: ${cfg.gasTieBreaker}
      |  ETH Compatible Storage: ${cfg.ethCompatibleStorage}
      |  Max Code Size: ${cfg.maxCodeSize.getOrElse("unlimited")}
      |  Treasury Address: 0x${org.bouncycastle.util.encoders.Hex.toHexString(cfg.treasuryAddress.bytes.toArray)}
      |  Monetary Policy:
      |    Era Duration: ${cfg.monetaryPolicyConfig.eraDuration} blocks
      |    Reward Reduction Rate: ${cfg.monetaryPolicyConfig.rewardReductionRate}
      |    First Era Block Reward: ${cfg.monetaryPolicyConfig.firstEraBlockReward} wei
      |    First Era Reduced Block Reward: ${cfg.monetaryPolicyConfig.firstEraReducedBlockReward} wei""".stripMargin
  }
}

// --- Tool Registry ---

object McpToolRegistry {

  def getAllTools(): List[McpToolDefinition] = List(
    // Status tools
    McpToolDefinition(NodeStatusTool.name, NodeStatusTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(NodeInfoTool.name, NodeInfoTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(BlockchainInfoTool.name, BlockchainInfoTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(SyncStatusTool.name, SyncStatusTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(PeerListTool.name, PeerListTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(SetEtherbaseTool.name, SetEtherbaseTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(MiningRpcSummaryTool.name, MiningRpcSummaryTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    // Blockchain query tools
    McpToolDefinition(GetBlockTool.name, GetBlockTool.description,
      inputSchema = Some(GetBlockTool.inputSchema),
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true), idempotentHint = Some(true)))),
    McpToolDefinition(GetTransactionTool.name, GetTransactionTool.description,
      inputSchema = Some(GetTransactionTool.inputSchema),
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true), idempotentHint = Some(true)))),
    McpToolDefinition(GetAccountTool.name, GetAccountTool.description,
      inputSchema = Some(GetAccountTool.inputSchema),
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true), idempotentHint = Some(true)))),
    // ETC-specific tools
    McpToolDefinition(DetectReorgTool.name, DetectReorgTool.description,
      inputSchema = Some(DetectReorgTool.inputSchema),
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(ConvertUnitsTool.name, ConvertUnitsTool.description,
      inputSchema = Some(ConvertUnitsTool.inputSchema),
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true), idempotentHint = Some(true)))),
    McpToolDefinition(GetEtcEmissionTool.name, GetEtcEmissionTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(GetEtcForksTool.name, GetEtcForksTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(GetTreasuryStatusTool.name, GetTreasuryStatusTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true)))),
    McpToolDefinition(GetChainConfigTool.name, GetChainConfigTool.description,
      annotations = Some(McpToolAnnotations(readOnlyHint = Some(true))))
  )

  def executeTool(
      toolName: String,
      arguments: Option[JValue],
      deps: McpDependencies
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    toolName match {
      case NodeStatusTool.name => NodeStatusTool.execute(deps)
      case NodeInfoTool.name => NodeInfoTool.execute(deps)
      case BlockchainInfoTool.name => BlockchainInfoTool.execute(deps)
      case SyncStatusTool.name => SyncStatusTool.execute(deps)
      case PeerListTool.name => PeerListTool.execute(deps)
      case SetEtherbaseTool.name => SetEtherbaseTool.execute()
      case MiningRpcSummaryTool.name => MiningRpcSummaryTool.execute()
      case GetBlockTool.name => GetBlockTool.execute(arguments, deps)
      case GetTransactionTool.name => GetTransactionTool.execute(arguments, deps)
      case GetAccountTool.name => GetAccountTool.execute(arguments, deps)
      case DetectReorgTool.name => DetectReorgTool.execute(arguments, deps)
      case ConvertUnitsTool.name => ConvertUnitsTool.execute(arguments)
      case GetEtcEmissionTool.name => GetEtcEmissionTool.execute(deps)
      case GetEtcForksTool.name => GetEtcForksTool.execute(deps)
      case GetTreasuryStatusTool.name => GetTreasuryStatusTool.execute(deps)
      case GetChainConfigTool.name => GetChainConfigTool.execute(deps)
      case _ => IO.pure(s"Unknown tool: $toolName")
    }
  }
}

case class McpToolDefinition(
    name: String,
    description: Option[String],
    inputSchema: Option[JValue] = None,
    annotations: Option[McpToolAnnotations] = None
)
